import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.*;
import java.io.*;

public class DownloadManager {
    private static final int MAX_CONCURRENT_DOWNLOADS = 3;
    private static final int CHUNK_REQUEST_TIMEOUT_MS = 10000;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String peerIdentifier;
    private final TrackerClient trackerClient;
    private final FileManager fileManager;
    private final TCPClient tcpClient;

    private final ExecutorService downloadExecutor =
            Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS);
    private final Map<String, DownloadTask> activeDownloads = new ConcurrentHashMap<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger completedDownloads = new AtomicInteger(0);

    public DownloadManager(String peerIdentifier, TrackerClient trackerClient, FileManager fileManager) {
        this.peerIdentifier = peerIdentifier;
        this.trackerClient = trackerClient;
        this.fileManager = fileManager;
        this.tcpClient = new TCPClient(peerIdentifier);
    }

    public void start() {
        isRunning.set(true);
        log("DownloadManager iniciado");

        // Verifica se há arquivos parciais que podem ser reconstruídos
        reconstructAvailableFiles();
    }

    public void stop() {
        isRunning.set(false);

        // Cancela downloads ativos
        for (DownloadTask task : activeDownloads.values()) {
            task.cancel();
        }
        activeDownloads.clear();

        downloadExecutor.shutdown();
        try {
            if (!downloadExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                downloadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log("DownloadManager parado");
    }

    public void reconstructAvailableFiles() {
        log("Verificando arquivos que podem ser reconstruídos...");

        Map<String, Set<Integer>> availableFiles = fileManager.getAvailableFiles();

        for (String fileName : availableFiles.keySet()) {
            try {
                reconstructFileFromChunks(fileName);
            } catch (Exception e) {
                log("Erro ao tentar reconstruir '" + fileName + "': " + e.getMessage());
            }
        }
    }

    public boolean reconstructFileFromChunks(String fileName) {
        try {
            FileManager.FileMetadata metadata = fileManager.getFileMetadata(fileName);

            // Se metadados não existirem, tenta criá-los a partir dos chunks
            if (metadata == null) {
                metadata = createMetadataFromChunks(fileName);
                if (metadata == null) {
                    log("Metadata não encontrada para '" + fileName + "'");
                    return false;
                }
                fileManager.addFileMetadata(metadata);
                fileManager.saveMetadata(fileName, metadata);
            }

            Set<Integer> availableChunks = fileManager.getAvailableChunks(fileName);
            if (availableChunks.isEmpty()) {
                log("Nenhum chunk disponível para '" + fileName + "'");
                return false;
            }

            // Verifica se o arquivo original já existe
            Path originalFile = fileManager.getSharedFolder().resolve(fileName);
            if (Files.exists(originalFile)) {
                log("Arquivo '" + fileName + "' já existe");
                return true;
            }

            // Verifica se temos todos os chunks necessários
            boolean isComplete = availableChunks.size() == metadata.totalChunks;

            if (isComplete) {
                log("Todos os chunks disponíveis para '" + fileName + "'. Reconstruindo arquivo completo...");
                return reconstructCompleteFile(fileName);
            } else {
                log("Arquivo '" + fileName + "' está incompleto (" +
                        availableChunks.size() + "/" + metadata.totalChunks + " chunks). " +
                        "Criando arquivo parcial...");
                return reconstructPartialFile(fileName, metadata, availableChunks);
            }

        } catch (Exception e) {
            log("Erro ao reconstruir '" + fileName + "': " + e.getMessage());
            return false;
        }
    }

    private boolean reconstructCompleteFile(String fileName) {
        try {
            FileManager.FileMetadata metadata = fileManager.getFileMetadata(fileName);
            if (metadata == null) {
                log("Metadata não encontrada para '" + fileName + "'");
                return false;
            }

            Path outputFile = fileManager.getSharedFolder().resolve(fileName);
            Path tempFile = fileManager.getSharedFolder().resolve(fileName + ".tmp");

            // Verifica se o arquivo já existe
            if (Files.exists(outputFile)) {
                log("Arquivo já existe: " + fileName);
                return true;
            }

            log("Reconstruindo arquivo completo: " + fileName);

            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                for (int i = 0; i < metadata.totalChunks; i++) {
                    byte[] chunkData = fileManager.loadChunk(fileName, i);
                    if (chunkData == null) {
                        log("Erro: Chunk " + i + " não encontrado para " + fileName);
                        Files.deleteIfExists(tempFile);
                        return false;
                    }
                    fos.write(chunkData);
                }
            }

            // Verifica integridade do arquivo reconstruído
            if (verifyFileIntegrity(tempFile, metadata)) {
                Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
                log("✅ Arquivo '" + fileName + "' reconstruído com sucesso!");

                // Atualiza metadados se necessário
                if (fileManager.getFileMetadata(fileName) == null) {
                    String fileHash = calculateFileHash(outputFile);
                    FileManager.FileMetadata newMetadata = new FileManager.FileMetadata(
                            fileName,
                            Files.size(outputFile),
                            metadata.totalChunks,
                            fileHash
                    );
                    fileManager.addFileMetadata(metadata);
                    fileManager.saveMetadata(fileName, newMetadata);
                }

                return true;
            } else {
                log("❌ Falha na verificação de integridade de '" + fileName + "'");
                Files.deleteIfExists(tempFile);
                return false;
            }

        } catch (IOException e) {
            log("Erro ao reconstruir arquivo completo '" + fileName + "': " + e.getMessage());
            return false;
        }
    }

    private boolean reconstructPartialFile(String fileName, FileManager.FileMetadata metadata, Set<Integer> availableChunks) {
        try {
            Path outputFile = fileManager.getSharedFolder().resolve(fileName + ".partial");
            Path infoFile = fileManager.getSharedFolder().resolve(fileName + ".partial.info");

            log("Criando arquivo parcial: " + fileName + ".partial");

            // Cria arquivo com os chunks disponíveis
            try (FileOutputStream fos = new FileOutputStream(outputFile.toFile())) {
                // Cria arquivo do tamanho total (com zeros nos chunks faltantes)
                byte[] emptyChunk = new byte[1024 * 1024]; // 1MB de zeros

                for (int i = 0; i < metadata.totalChunks; i++) {
                    if (availableChunks.contains(i)) {
                        byte[] chunkData = fileManager.loadChunk(fileName, i);
                        if (chunkData != null) {
                            fos.write(chunkData);
                        } else {
                            // Se não conseguir carregar o chunk, preenche com zeros
                            int chunkSize = (i == metadata.totalChunks - 1) ?
                                    (int)(metadata.fileSize % (1024 * 1024)) : 1024 * 1024;
                            fos.write(emptyChunk, 0, chunkSize);
                        }
                    } else {
                        // Chunk faltante - preenche com zeros
                        int chunkSize = (i == metadata.totalChunks - 1) ?
                                (int)(metadata.fileSize % (1024 * 1024)) : 1024 * 1024;
                        if (chunkSize == 0) chunkSize = 1024 * 1024;
                        fos.write(emptyChunk, 0, chunkSize);
                    }
                }
            }

            // Cria arquivo de informações sobre os chunks disponíveis
            createPartialFileInfo(infoFile, fileName, metadata, availableChunks);

            log("✅ Arquivo parcial criado: " + fileName + ".partial (" +
                    availableChunks.size() + "/" + metadata.totalChunks + " chunks)");

            return true;

        } catch (IOException e) {
            log("Erro ao criar arquivo parcial '" + fileName + "': " + e.getMessage());
            return false;
        }
    }

    private FileManager.FileMetadata createMetadataFromChunks(String fileName) {
        try {
            Set<Integer> chunks = fileManager.getAvailableChunks(fileName);
            if (chunks.isEmpty()) return null;

            // Determina tamanho total e número de chunks
            int totalChunks = Collections.max(chunks) + 1;
            long fileSize = 0;

            // Calcula tamanho somando os chunks
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunkData = fileManager.loadChunk(fileName, i);
                if (chunkData != null) {
                    fileSize += chunkData.length;
                }
            }

            // Cria metadados básicos (hash será calculado após reconstrução)
            return new FileManager.FileMetadata(
                    fileName,
                    fileSize,
                    totalChunks,
                    "pending-verification"
            );
        } catch (Exception e) {
            log("Erro ao criar metadados: " + e.getMessage());
            return null;
        }
    }

    private void createPartialFileInfo(Path infoFile, String fileName,
                                       FileManager.FileMetadata metadata, Set<Integer> availableChunks) {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(infoFile))) {
            writer.println("Arquivo Parcial: " + fileName);
            writer.println("Tamanho Original: " + formatSize(metadata.fileSize));
            writer.println("Total de Chunks: " + metadata.totalChunks);
            writer.println("Chunks Disponíveis: " + availableChunks.size());
            writer.println("Completude: " + String.format("%.1f%%",
                    (availableChunks.size() * 100.0) / metadata.totalChunks));
            writer.println("Hash Original: " + metadata.fileHash);
            writer.println();
            writer.println("Chunks Faltantes:");

            Set<Integer> missingChunks = new TreeSet<>();
            for (int i = 0; i < metadata.totalChunks; i++) {
                if (!availableChunks.contains(i)) {
                    missingChunks.add(i);
                }
            }

            writer.println(missingChunks.toString());
            writer.println();
            writer.println("Criado em: " + LocalDateTime.now());

        } catch (IOException e) {
            log("Erro ao criar arquivo de informações: " + e.getMessage());
        }
    }

    private boolean verifyFileIntegrity(Path filePath, FileManager.FileMetadata metadata) {
        try {
            // Verifica tamanho
            long actualSize = Files.size(filePath);
            if (actualSize != metadata.fileSize) {
                log("Tamanho incorreto: esperado " + metadata.fileSize + ", obtido " + actualSize);
                return false;
            }

            // Verifica hash (opcional - pode ser demorado para arquivos grandes)
            if (metadata.fileSize < 100 * 1024 * 1024 && !metadata.fileHash.equals("pending-verification")) {
                String calculatedHash = calculateFileHash(filePath);
                if (!metadata.fileHash.equals(calculatedHash)) {
                    log("Hash incorreto: esperado " + metadata.fileHash + ", obtido " + calculatedHash);
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            log("Erro na verificação de integridade: " + e.getMessage());
            return false;
        }
    }

    private String calculateFileHash(Path filePath) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
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
            log("Erro ao calcular hash: " + e.getMessage());
            return "unknown";
        }
    }

    public void forceReconstruction(String fileName) {
        log("Forçando reconstrução de '" + fileName + "'...");

        // Remove arquivo existente se houver
        try {
            Path existingFile = fileManager.getSharedFolder().resolve(fileName);
            if (Files.exists(existingFile)) {
                Files.delete(existingFile);
                log("Arquivo existente removido: " + fileName);
            }
        } catch (IOException e) {
            log("Erro ao remover arquivo existente: " + e.getMessage());
        }

        reconstructFileFromChunks(fileName);
    }

    public List<String> getReconstructableFiles() {
        List<String> reconstructable = new ArrayList<>();
        Map<String, Set<Integer>> availableFiles = fileManager.getAvailableFiles();

        for (String fileName : availableFiles.keySet()) {
            Set<Integer> chunks = availableFiles.get(fileName);
            if (!chunks.isEmpty()) {
                Path originalFile = fileManager.getSharedFolder().resolve(fileName);
                if (!Files.exists(originalFile)) {
                    reconstructable.add(fileName);
                }
            }
        }

        return reconstructable;
    }

    public Map<String, Object> getReconstructionStats() {
        Map<String, Object> stats = new HashMap<>();
        Map<String, Set<Integer>> availableFiles = fileManager.getAvailableFiles();

        int totalFiles = availableFiles.size();
        int completeFiles = 0;
        int partialFiles = 0;
        int reconstructedFiles = 0;

        for (String fileName : availableFiles.keySet()) {
            FileManager.FileMetadata metadata = fileManager.getFileMetadata(fileName);
            if (metadata != null) {
                Set<Integer> chunks = availableFiles.get(fileName);
                boolean isComplete = chunks.size() == metadata.totalChunks;

                if (isComplete) {
                    completeFiles++;
                } else if (!chunks.isEmpty()) {
                    partialFiles++;
                }

                Path originalFile = fileManager.getSharedFolder().resolve(fileName);
                if (Files.exists(originalFile)) {
                    reconstructedFiles++;
                }
            }
        }

        stats.put("totalFiles", totalFiles);
        stats.put("completeFiles", completeFiles);
        stats.put("partialFiles", partialFiles);
        stats.put("reconstructedFiles", reconstructedFiles);
        stats.put("reconstructableFiles", completeFiles - reconstructedFiles);

        return stats;
    }

    public void requestDownload(String fileName) {
        if (!isRunning.get()) {
            log("DownloadManager não está rodando");
            return;
        }

        if (activeDownloads.containsKey(fileName)) {
            log("Download de '" + fileName + "' já está em andamento");
            return;
        }

        if (fileManager.hasCompleteFile(fileName)) {
            log("Arquivo '" + fileName + "' já existe localmente");
            return;
        }

        DownloadTask task = new DownloadTask(fileName);
        activeDownloads.put(fileName, task);
        downloadExecutor.submit(task);

        log("Iniciando download de '" + fileName + "'");
    }

    public int getActiveDownloadsCount() {
        return activeDownloads.size();
    }

    public int getCompletedDownloadsCount() {
        return completedDownloads.get();
    }

    public List<String> getActiveDownloadsList() {
        return new ArrayList<>(activeDownloads.keySet());
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) +
                "] [" + peerIdentifier + "] [DL] " + message);
    }

    private class DownloadTask implements Runnable {
        private final String fileName;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final Set<Integer> neededChunks = ConcurrentHashMap.newKeySet();
        private final Set<Integer> downloadedChunks = ConcurrentHashMap.newKeySet();
        private final AtomicInteger totalChunks = new AtomicInteger(0);

        public DownloadTask(String fileName) {
            this.fileName = fileName;
        }

        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public void run() {
            try {
                if (!discoverFileStructure()) {
                    log("Falha ao descobrir estrutura do arquivo '" + fileName + "'");
                    return;
                }

                log("Arquivo '" + fileName + "' tem " + totalChunks.get() + " chunks");

                // Verifica quais chunks já temos
                Set<Integer> existingChunks = fileManager.getAvailableChunks(fileName);
                neededChunks.addAll(generateChunkList(totalChunks.get()));
                neededChunks.removeAll(existingChunks);

                if (neededChunks.isEmpty()) {
                    log("Arquivo '" + fileName + "' já está completo");
                    reconstructFileFromChunks(fileName); // Reconstrói se necessário
                    completedDownloads.incrementAndGet();
                    return;
                }

                log("Precisa baixar " + neededChunks.size() + " chunks de '" + fileName + "'");

                // Download dos chunks necessários
                downloadChunks();

                if (!cancelled.get() && neededChunks.isEmpty()) {
                    saveFileMetadata(); // Salva metadados antes da reconstrução
                    reconstructFileFromChunks(fileName); // Reconstrói o arquivo
                    completedDownloads.incrementAndGet();
                    log("✅ Download de '" + fileName + "' completado com sucesso!");
                } else if (cancelled.get()) {
                    log("❌ Download de '" + fileName + "' foi cancelado");
                } else {
                    log("⚠️  Download de '" + fileName + "' incompleto (" +
                            downloadedChunks.size() + "/" + totalChunks.get() + " chunks)");
                    // Mesmo incompleto, tenta reconstruir o que for possível
                    reconstructFileFromChunks(fileName);
                }

            } catch (Exception e) {
                log("Erro no download de '" + fileName + "': " + e.getMessage());
            } finally {
                activeDownloads.remove(fileName);
            }
        }

        private boolean discoverFileStructure() {
            Map<String, TrackerClient.PeerInfo> peers = trackerClient.getKnownPeers();

            for (Map.Entry<String, TrackerClient.PeerInfo> entry : peers.entrySet()) {
                if (cancelled.get()) return false;

                TrackerClient.PeerInfo peerInfo = entry.getValue();
                if (!peerInfo.hasFile(fileName)) continue;

                Set<Integer> peerChunks = peerInfo.getFileIndices(fileName);
                if (peerChunks.isEmpty()) continue;

                // Usa o maior número de chunks encontrado como referência
                int maxChunk = Collections.max(peerChunks);
                if (maxChunk + 1 > totalChunks.get()) {
                    totalChunks.set(maxChunk + 1);
                }
            }

            return totalChunks.get() > 0;
        }

        private void downloadChunks() {
            List<Integer> chunksList = new ArrayList<>(neededChunks);
            Collections.shuffle(chunksList); // Download aleatório para distribuir carga

            ExecutorService chunkDownloader = Executors.newFixedThreadPool(3);

            try {
                for (Integer chunkIndex : chunksList) {
                    if (cancelled.get()) break;

                    chunkDownloader.submit(() -> downloadChunk(chunkIndex));
                }

                chunkDownloader.shutdown();
                chunkDownloader.awaitTermination(5, TimeUnit.MINUTES);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void downloadChunk(int chunkIndex) {
            if (cancelled.get()) return;

            Map<String, TrackerClient.PeerInfo> peers = trackerClient.getKnownPeers();
            List<String> candidatePeers = new ArrayList<>();

            // Encontra peers que têm este chunk
            for (Map.Entry<String, TrackerClient.PeerInfo> entry : peers.entrySet()) {
                TrackerClient.PeerInfo peerInfo = entry.getValue();
                if (peerInfo.hasFile(fileName) &&
                        peerInfo.getFileIndices(fileName).contains(chunkIndex)) {
                    candidatePeers.add(entry.getKey());
                }
            }

            if (candidatePeers.isEmpty()) {
                log("Nenhum peer tem o chunk " + chunkIndex + " de '" + fileName + "'");
                return;
            }

            // Tenta baixar de peers aleatórios com retry
            Collections.shuffle(candidatePeers);

            for (String peerAddr : candidatePeers) {
                if (cancelled.get()) return;

                // Extrai endereço de rede do identificador do peer
                String networkAddr = TrackerClient.extractNetworkAddress(peerAddr);

                for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
                    try {
                        TCPClient.ChunkResponse response = tcpClient.requestChunk(networkAddr, fileName, chunkIndex);

                        if (response.success && response.data != null) {
                            fileManager.saveChunk(fileName, chunkIndex, response.data);
                            downloadedChunks.add(chunkIndex);
                            neededChunks.remove(chunkIndex);
                            log("✓ Chunk " + chunkIndex + "/" + (totalChunks.get()-1) +
                                    " de '" + fileName + "' baixado de " + peerAddr +
                                    " (" + response.data.length + " bytes)");
                            return;
                        } else {
                            log("Tentativa " + attempt + " falhou para chunk " + chunkIndex +
                                    " de " + peerAddr + ": " + response.message);

                            if (attempt < MAX_RETRY_ATTEMPTS) {
                                Thread.sleep(1000 * attempt); // Backoff
                            }
                        }

                    } catch (Exception e) {
                        log("Erro na tentativa " + attempt + " para chunk " + chunkIndex +
                                " de " + peerAddr + ": " + e.getMessage());

                        if (attempt < MAX_RETRY_ATTEMPTS) {
                            try {
                                Thread.sleep(1000 * attempt);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
            }

            log("❌ Falha ao baixar chunk " + chunkIndex + " de '" + fileName + "' após " +
                    MAX_RETRY_ATTEMPTS + " tentativas");
        }

        private void saveFileMetadata() {
            try {
                int total = totalChunks.get();
                long fileSize = 0;

                // Calcula tamanho total somando os chunks
                for (int i = 0; i < total; i++) {
                    byte[] data = fileManager.loadChunk(fileName, i);
                    if (data != null) fileSize += data.length;
                }

                // Gera hash do arquivo reconstruído
                Path tempFile = Files.createTempFile("tmp-", ".download");
                try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                    for (int i = 0; i < total; i++) {
                        byte[] data = fileManager.loadChunk(fileName, i);
                        if (data != null) fos.write(data);
                    }
                }
                String fileHash = calculateFileHash(tempFile);
                Files.delete(tempFile);

                // Cria e salva metadados
                FileManager.FileMetadata metadata = new FileManager.FileMetadata(
                        fileName, fileSize, total, fileHash
                );
                fileManager.addFileMetadata(metadata);
                fileManager.saveMetadata(fileName, metadata);

            } catch (Exception e) {
                log("Erro ao salvar metadados: " + e.getMessage());
            }
        }
    }

    private Set<Integer> generateChunkList(int totalChunks) {
        Set<Integer> chunks = new HashSet<>();
        for (int i = 0; i < totalChunks; i++) {
            chunks.add(i);
        }
        return chunks;
    }
}