import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class FileManager {
    private static final Logger LOGGER = Logger.getLogger(FileManager.class.getName());
    private static final int CHUNK_SIZE = 1024 * 1024; // 1MB por chunk
    private static final String CHUNKS_EXTENSION = ".chunks";
    private static final String METADATA_EXTENSION = ".meta";
    private static final String PARTIAL_EXTENSION = ".partial";
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String nome;
    private final String peerIdentifier;
    private final Path sharedFolder;
    private final Path chunksDirectory;
    private final Path metadataDirectory;

    private final Map<String, FileMetadata> localFiles = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> availableChunks = new ConcurrentHashMap<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final ExecutorService watcherExecutor;

    private WatchService watchService;
    private Future<?> watcherTask;
    private TrackerClient trackerClient;

    public FileManager(String nome, String peerIdentifier) {
        this.nome = nome;
        this.peerIdentifier = peerIdentifier;
        this.watcherExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FolderWatcher-" + nome);
            t.setDaemon(true);
            return t;
        });
        this.sharedFolder = setupSharedFolder();
        this.chunksDirectory = sharedFolder.resolve("chunks");
        this.metadataDirectory = sharedFolder.resolve("metadata");

        initializeDirectories();
        loadLocalFiles();
    }

    public FileManager(String peerIdentifier) {
        this(extractNomeFromIdentifier(peerIdentifier), peerIdentifier);
    }

    private static String extractNomeFromIdentifier(String peerIdentifier) {
        if (peerIdentifier.startsWith("Peer_")) {
            return peerIdentifier.substring(5).replace(":", "_");
        }
        return peerIdentifier.replace(":", "_");
    }

    private Path setupSharedFolder() {
        Path folder = Paths.get(System.getProperty("user.home"), "Downloads", "P2P", nome);
        try {
            Files.createDirectories(folder);
            log("Pasta compartilhada criada/verificada: " + folder.toAbsolutePath());
            return folder;
        } catch (IOException e) {
            log("ERRO ao criar pasta: " + e.getMessage());
            throw new RuntimeException("Não foi possível criar pasta compartilhada", e);
        }
    }

    private void initializeDirectories() {
        try {
            Files.createDirectories(chunksDirectory);
            Files.createDirectories(metadataDirectory);
            log("Diretórios inicializados:");
            log("  - Pasta principal: " + sharedFolder.toAbsolutePath());
            log("  - Chunks: " + chunksDirectory.toAbsolutePath());
            log("  - Metadata: " + metadataDirectory.toAbsolutePath());
        } catch (IOException e) {
            log("ERRO ao criar diretórios: " + e.getMessage());
            throw new RuntimeException("Falha na inicialização dos diretórios", e);
        }
    }

    public void start() {
        if (!isRunning.compareAndSet(false, true)) {
            log("FileManager já está rodando");
            return;
        }

        try {
            log("=== INICIANDO FILEMANAGER ===");
            log("Pasta compartilhada: " + sharedFolder.toAbsolutePath());
            log("Arquivos locais carregados: " + localFiles.size());
            log("Chunks disponíveis: " + getTotalChunksCount());

            scanSharedFolderForNewFiles();
            log("=== FILEMANAGER INICIADO ===");
        } catch (Exception e) {
            log("ERRO ao iniciar FileManager: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void stop() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }

        try {
            if (watchService != null) {
                watchService.close();
            }
            if (watcherTask != null && !watcherTask.isDone()) {
                watcherTask.cancel(true);
            }
            watcherExecutor.shutdown();
            try {
                if (!watcherExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    watcherExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            saveAllMetadata();
            log("FileManager parado");
        } catch (Exception e) {
            log("Erro ao parar FileManager: " + e.getMessage());
        }
    }

    public void startFolderWatcher(TrackerClient trackerClient) {
        this.trackerClient = trackerClient;

        try {
            watchService = FileSystems.getDefault().newWatchService();
            sharedFolder.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);

            watcherTask = watcherExecutor.submit(this::watchForChanges);
            log("Monitoramento de pasta iniciado");
        } catch (IOException e) {
            log("Erro ao iniciar monitoramento: " + e.getMessage());
        }
    }

    private void watchForChanges() {
        while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    Path fileName = (Path) event.context();
                    Path fullPath = sharedFolder.resolve(fileName);

                    if (shouldIgnoreFile(fileName.toString())) {
                        continue;
                    }

                    if (kind == StandardWatchEventKinds.ENTRY_CREATE ||
                            kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        Thread.sleep(500); // Espera para garantir escrita completa
                        handleFileAdded(fullPath);
                    } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        handleFileDeleted(fileName.toString());
                    }
                }

                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log("Erro no monitoramento: " + e.getMessage());
            }
        }
    }

    private boolean shouldIgnoreFile(String fileName) {
        return fileName.endsWith(CHUNKS_EXTENSION) ||
                fileName.endsWith(METADATA_EXTENSION) ||
                fileName.endsWith(PARTIAL_EXTENSION) ||
                fileName.startsWith(".") ||
                fileName.endsWith(".tmp") ||
                fileName.endsWith(".complete") ||
                fileName.equals("chunks") ||
                fileName.equals("metadata") ||
                fileName.endsWith(".part") ||
                fileName.endsWith(".crdownload");
    }

    private void handleFileAdded(Path filePath) {
        if (!Files.isRegularFile(filePath)) {
            log("Ignorando (não é arquivo regular): " + filePath.getFileName());
            return;
        }

        try {
            String fileName = filePath.getFileName().toString();
            long fileSize = Files.size(filePath);

            if (fileSize == 0) {
                log("Ignorando arquivo vazio: " + fileName);
                return;
            }

            FileMetadata existingMetadata = localFiles.get(fileName);
            if (existingMetadata != null && existingMetadata.fileSize == fileSize) {
                log("Arquivo já processado (mesmo tamanho): " + fileName);
                return;
            }

            log("NOVO ARQUIVO DETECTADO: " + fileName + " (" + formatSize(fileSize) + ")");
            processFileIntoChunks(filePath, fileName);

            if (trackerClient != null) {
                trackerClient.forceUpdate();
            }

        } catch (IOException e) {
            log("ERRO ao processar arquivo " + filePath + ": " + e.getMessage());
        }
    }

    private void handleFileDeleted(String fileName) {
        if (localFiles.containsKey(fileName)) {
            log("ARQUIVO REMOVIDO: " + fileName);
            localFiles.remove(fileName);
            availableChunks.remove(fileName);
            deleteAssociatedFiles(fileName);

            if (trackerClient != null) {
                trackerClient.forceUpdate();
            }
        }
    }

    private void processFileIntoChunks(Path filePath, String fileName) {
        try {
            log("Processando arquivo em chunks: " + fileName);

            long fileSize = Files.size(filePath);
            int totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
            String fileHash = calculateFileHash(filePath);

            log("Detalhes do arquivo:");
            log("  - Tamanho: " + formatSize(fileSize));
            log("  - Total de chunks: " + totalChunks);
            log("  - Hash: " + fileHash.substring(0, 16) + "...");

            FileMetadata metadata = new FileMetadata(fileName, fileSize, totalChunks, fileHash);
            localFiles.put(fileName, metadata);

            Set<Integer> chunks = ConcurrentHashMap.newKeySet();

            try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
                byte[] buffer = new byte[CHUNK_SIZE];
                int chunkIndex = 0;
                int bytesRead;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    byte[] chunkData = Arrays.copyOf(buffer, bytesRead);
                    saveChunkToDisk(fileName, chunkIndex, chunkData);
                    chunks.add(chunkIndex);
                    chunkIndex++;
                }
            }

            availableChunks.put(fileName, chunks);
            saveMetadata(fileName, metadata);

            log("✅ Arquivo processado com sucesso: " + fileName + " -> " + totalChunks + " chunks");

        } catch (Exception e) {
            log("ERRO ao processar arquivo em chunks: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void saveChunk(String fileName, int chunkIndex, byte[] data) {
        try {
            saveChunkToDisk(fileName, chunkIndex, data);

            availableChunks.computeIfAbsent(fileName, k -> ConcurrentHashMap.newKeySet())
                    .add(chunkIndex);

            log("Chunk " + chunkIndex + " de '" + fileName + "' salvo (" + data.length + " bytes)");
            checkFileCompletion(fileName);

        } catch (IOException e) {
            log("Erro ao salvar chunk: " + e.getMessage());
        }
    }

    private void saveChunkToDisk(String fileName, int chunkIndex, byte[] data) throws IOException {
        String safeFileName = makeSafeFileName(fileName);
        Path chunkFile = chunksDirectory.resolve(safeFileName + "_" + chunkIndex + CHUNKS_EXTENSION);
        Files.write(chunkFile, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private String makeSafeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    public byte[] loadChunk(String fileName, int chunkIndex) {
        try {
            String safeFileName = makeSafeFileName(fileName);
            Path chunkFile = chunksDirectory.resolve(safeFileName + "_" + chunkIndex + CHUNKS_EXTENSION);
            if (Files.exists(chunkFile)) {
                return Files.readAllBytes(chunkFile);
            }
        } catch (IOException e) {
            log("Erro ao carregar chunk " + chunkIndex + " de " + fileName + ": " + e.getMessage());
        }
        return null;
    }

    public boolean hasChunk(String fileName, int chunkIndex) {
        Set<Integer> chunks = availableChunks.get(fileName);
        return chunks != null && chunks.contains(chunkIndex);
    }

    public Set<Integer> getAvailableChunks(String fileName) {
        return new HashSet<>(availableChunks.getOrDefault(fileName, Collections.emptySet()));
    }

    public Map<String, Set<Integer>> getAvailableFiles() {
        Map<String, Set<Integer>> result = new HashMap<>();
        availableChunks.forEach((key, value) ->
                result.put(key, new HashSet<>(value)));
        return result;
    }

    public boolean hasCompleteFile(String fileName) {
        FileMetadata metadata = localFiles.get(fileName);
        if (metadata == null) return false;

        Set<Integer> chunks = availableChunks.get(fileName);
        if (chunks == null) return false;

        return chunks.size() == metadata.totalChunks;
    }

    public void markFileAsComplete(String fileName) {
        if (hasCompleteFile(fileName)) {
            log("✅ Arquivo '" + fileName + "' está completo!");
            try {
                reconstructFile(fileName);
            } catch (Exception e) {
                log("Erro ao reconstruir arquivo: " + e.getMessage());
            }
        }
    }

    public boolean reconstructFile(String fileName) throws IOException {
        FileMetadata metadata = localFiles.get(fileName);
        if (metadata == null) {
            throw new IOException("Metadata não encontrada para " + fileName);
        }

        Path outputFile = sharedFolder.resolve(fileName);
        if (Files.exists(outputFile)) {
            log("Arquivo já existe: " + fileName);
            return true;
        }

        Path tempFile = sharedFolder.resolve(fileName + ".tmp");

        try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
            for (int i = 0; i < metadata.totalChunks; i++) {
                byte[] chunkData = loadChunk(fileName, i);
                if (chunkData == null) {
                    throw new IOException("Chunk " + i + " não encontrado para " + fileName);
                }
                fos.write(chunkData);
            }
        }

        String reconstructedHash = calculateFileHash(tempFile);
        if (metadata.fileHash.equals(reconstructedHash)) {
            Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
            log("✅ Arquivo reconstruído com sucesso: " + outputFile.getFileName());
            return true;
        } else {
            log("❌ Erro de integridade no arquivo reconstruído: " + fileName);
            Files.deleteIfExists(tempFile);
            throw new IOException("Hash do arquivo reconstruído não coincide");
        }
    }

    private void checkFileCompletion(String fileName) {
        if (hasCompleteFile(fileName)) {
            log("🎉 Arquivo '" + fileName + "' foi completado!");
            markFileAsComplete(fileName);

            if (trackerClient != null) {
                trackerClient.forceUpdate();
            }
        }
    }

    private void loadLocalFiles() {
        try {
            log("=== CARREGANDO ARQUIVOS LOCAIS ===");

            if (Files.exists(metadataDirectory)) {
                log("Carregando metadados existentes...");
                Files.walk(metadataDirectory, 1)
                        .filter(path -> path.toString().endsWith(METADATA_EXTENSION))
                        .forEach(this::loadMetadataFile);
            }

            if (Files.exists(chunksDirectory)) {
                log("Carregando chunks existentes...");
                loadExistingChunks();
            }

            log("Escaneando pasta compartilhada para novos arquivos...");
            scanSharedFolderForNewFiles();

            log("=== CARREGAMENTO CONCLUÍDO ===");
            log("Total de arquivos: " + localFiles.size());
            log("Total de chunks: " + getTotalChunksCount());

        } catch (IOException e) {
            log("ERRO ao carregar arquivos locais: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadMetadataFile(Path metadataFile) {
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(metadataFile.toFile())) {
                props.load(fis);
            }

            String fileName = props.getProperty("fileName");
            long fileSize = Long.parseLong(props.getProperty("fileSize"));
            int totalChunks = Integer.parseInt(props.getProperty("totalChunks"));
            String fileHash = props.getProperty("fileHash");

            FileMetadata metadata = new FileMetadata(fileName, fileSize, totalChunks, fileHash);
            localFiles.put(fileName, metadata);

            log("Metadata carregada: " + fileName + " (" + formatSize(fileSize) + ")");

        } catch (Exception e) {
            log("ERRO ao carregar metadata de " + metadataFile + ": " + e.getMessage());
        }
    }

    private void loadExistingChunks() {
        try {
            Files.walk(chunksDirectory, 1)
                    .filter(path -> path.toString().endsWith(CHUNKS_EXTENSION))
                    .forEach(this::processExistingChunk);
        } catch (IOException e) {
            log("ERRO ao carregar chunks existentes: " + e.getMessage());
        }
    }

    private void processExistingChunk(Path chunkFile) {
        try {
            String fileName = chunkFile.getFileName().toString();
            int underscoreIndex = fileName.lastIndexOf('_');
            int dotIndex = fileName.lastIndexOf('.');

            if (underscoreIndex > 0 && dotIndex > underscoreIndex) {
                String safeFileName = fileName.substring(0, underscoreIndex);
                int chunkIndex = Integer.parseInt(fileName.substring(underscoreIndex + 1, dotIndex));

                String originalFileName = findOriginalFileName(safeFileName);
                if (originalFileName != null) {
                    availableChunks.computeIfAbsent(originalFileName, k -> ConcurrentHashMap.newKeySet())
                            .add(chunkIndex);
                }
            }
        } catch (Exception e) {
            log("ERRO ao processar chunk " + chunkFile + ": " + e.getMessage());
        }
    }

    private String findOriginalFileName(String safeFileName) {
        for (String originalName : localFiles.keySet()) {
            if (makeSafeFileName(originalName).equals(safeFileName)) {
                return originalName;
            }
        }
        return null;
    }

    private void scanSharedFolderForNewFiles() {
        try {
            log("Escaneando pasta: " + sharedFolder.toAbsolutePath());

            if (!Files.exists(sharedFolder)) {
                log("AVISO: Pasta compartilhada não existe!");
                return;
            }

            Files.walk(sharedFolder, 1)
                    .filter(Files::isRegularFile)
                    .filter(path -> !shouldIgnoreFile(path.getFileName().toString()))
                    .forEach(filePath -> {
                        String fileName = filePath.getFileName().toString();
                        if (!localFiles.containsKey(fileName)) {
                            log("Processando novo arquivo: " + fileName);
                            handleFileAdded(filePath);
                        }
                    });

        } catch (IOException e) {
            log("ERRO ao escanear pasta compartilhada: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void forceScan() {
        log("Executando scan forçado da pasta...");
        scanSharedFolderForNewFiles();
        log("Scan forçado concluído. Arquivos: " + localFiles.size());
    }

    public void saveMetadata(String fileName, FileMetadata metadata) {
        try {
            String safeFileName = makeSafeFileName(fileName);
            Path metadataFile = metadataDirectory.resolve(safeFileName + METADATA_EXTENSION);
            Properties props = new Properties();

            props.setProperty("fileName", metadata.fileName);
            props.setProperty("fileSize", String.valueOf(metadata.fileSize));
            props.setProperty("totalChunks", String.valueOf(metadata.totalChunks));
            props.setProperty("fileHash", metadata.fileHash);
            props.setProperty("createdAt", String.valueOf(metadata.createdAt));

            try (FileOutputStream fos = new FileOutputStream(metadataFile.toFile())) {
                props.store(fos, "Metadata for " + fileName);
            }

        } catch (IOException e) {
            log("ERRO ao salvar metadata: " + e.getMessage());
        }
    }

    private void saveAllMetadata() {
        localFiles.forEach((fileName, metadata) -> saveMetadata(fileName, metadata));
    }

    private void deleteAssociatedFiles(String fileName) {
        try {
            String safeFileName = makeSafeFileName(fileName);

            Files.walk(chunksDirectory, 1)
                    .filter(path -> path.getFileName().toString().startsWith(safeFileName + "_"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log("Erro ao deletar chunk: " + e.getMessage());
                        }
                    });

            Path metadataFile = metadataDirectory.resolve(safeFileName + METADATA_EXTENSION);
            Files.deleteIfExists(metadataFile);

        } catch (IOException e) {
            log("Erro ao deletar arquivos associados: " + e.getMessage());
        }
    }

    private String calculateFileHash(Path filePath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];

            try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }

            byte[] hashBytes = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();
        } catch (Exception e) {
            log("ERRO ao calcular hash: " + e.getMessage());
            return "unknown";
        }
    }

    public long getTotalStorageUsed() {
        try {
            return Files.walk(chunksDirectory, 1)
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    public int getTotalFilesCount() {
        return localFiles.size();
    }

    public int getTotalChunksCount() {
        return availableChunks.values().stream().mapToInt(Set::size).sum();
    }

    public List<String> getFilesList() {
        return new ArrayList<>(localFiles.keySet());
    }

    public FileMetadata getFileMetadata(String fileName) {
        return localFiles.get(fileName);
    }

    public Path getSharedFolder() {
        return sharedFolder;
    }

    public String getNome() {
        return nome;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) +
                "] [" + nome + "] [FM] " + message);
    }

    public void addFileMetadata(FileMetadata metadata) {
        this.localFiles.put(metadata.fileName, metadata);
        saveMetadata(metadata.fileName, metadata);
    }
    public void removeFileMetadata(String fileName) {
        this.localFiles.remove(fileName);
        deleteAssociatedFiles(fileName);
    }
    public static class FileMetadata {
        public final String fileName;
        public final long fileSize;
        public final int totalChunks;
        public final String fileHash;
        public final long createdAt;

        public FileMetadata(String fileName, long fileSize, int totalChunks, String fileHash) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.totalChunks = totalChunks;
            this.fileHash = fileHash;
            this.createdAt = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("FileMetadata{name='%s', size=%d, chunks=%d, hash='%s'}",
                    fileName, fileSize, totalChunks, fileHash.substring(0, 8) + "...");
        }
    }
}