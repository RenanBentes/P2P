import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class CommandInterface {
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String peerIdentifier;
    private final String customName;
    private final FileManager fileManager;
    private final TrackerClient trackerClient;
    private final DownloadManager downloadManager;
    private final Peer peer;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread commandThread;
    private Scanner scanner;

    public CommandInterface(String peerIdentifier, String customName, FileManager fileManager,
                            TrackerClient trackerClient, DownloadManager downloadManager, Peer peer) {
        this.peerIdentifier = peerIdentifier;
        this.customName = customName;
        this.fileManager = fileManager;
        this.trackerClient = trackerClient;
        this.downloadManager = downloadManager;
        this.peer = peer;
    }

    public void start() {
        if (!isRunning.compareAndSet(false, true)) {
            log("CommandInterface já está rodando");
            return;
        }

        commandThread = new Thread(this::runCommandLoop, "CommandInterface-" + customName);
        commandThread.setDaemon(false);
        commandThread.start();
        log("Interface de comandos iniciada");
    }

    public void stop() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }

        log("Parando interface de comandos...");

        if (commandThread != null) {
            commandThread.interrupt();
        }

        if (scanner != null) {
            scanner.close();
        }

        log("Interface de comandos parada");
    }

    private void runCommandLoop() {
        scanner = new Scanner(System.in);

        // Aguarda um pouco para garantir que todos os componentes estejam prontos
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        printWelcome();
        printHelp();

        while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
            try {
                System.out.print(buildPrompt());

                if (!scanner.hasNextLine()) {
                    break;
                }

                String input = scanner.nextLine().trim();
                if (input.isEmpty()) {
                    continue;
                }

                processCommand(input);
            } catch (Exception e) {
                System.out.println("❌ Erro no comando: " + e.getMessage());
                log("Erro no processamento de comando: " + e.getMessage());
            }
        }

        if (scanner != null) {
            scanner.close();
        }
    }

    private void printWelcome() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("🚀 PEER P2P INICIADO COM SUCESSO!");
        System.out.println("📛 Nome: " + customName);
        System.out.println("🆔 ID: " + peerIdentifier);
        System.out.println("📁 Pasta: " + fileManager.getSharedFolder().toAbsolutePath());
        System.out.println("=".repeat(50));
    }

    private String buildPrompt() {
        String shortId = extractShortId();
        String statusIcon = peer.isRunning() ? "🟢" : "🔴";
        String trackerIcon = trackerClient.isConnectedToTracker() ? "🔗" : "❌";

        return String.format("%s[%s%s]%s@%s> ",
                statusIcon, trackerIcon, shortId, customName, shortId);
    }

    private String extractShortId() {
        if (peerIdentifier.contains(":")) {
            String[] parts = peerIdentifier.split(":");
            return parts[parts.length - 1];
        }
        return peerIdentifier.substring(Math.max(0, peerIdentifier.length() - 4));
    }

    private void processCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        try {
            switch (command) {
                case "help", "h", "?" -> printHelp();
                case "list", "ls", "files" -> listFiles();
                case "peers", "p" -> listPeers();
                case "download", "dl", "get" -> handleDownload(args);
                case "status", "info" -> showStatus();
                case "whoami", "me" -> showIdentity();
                case "refresh", "update" -> handleRefresh();
                case "tracker" -> showTrackerInfo();
                case "downloads", "dls" -> showDownloads();
                case "quit", "exit", "q", "bye" -> handleQuit();
                default -> {
                    System.out.println("❌ Comando desconhecido: '" + command + "'");
                    System.out.println("💡 Digite 'help' para ver os comandos disponíveis.");
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Erro ao executar comando '" + command + "': " + e.getMessage());
            log("Erro no comando " + command + ": " + e.getMessage());
        }
    }

    private void printHelp() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("📋 COMANDOS DISPONÍVEIS");
        System.out.println("=".repeat(60));
        System.out.println("📁 ARQUIVOS:");
        System.out.println("  list (ls, files)    - Lista arquivos locais");
        System.out.println("  download <arquivo>  - Baixa arquivo da rede");
        System.out.println("  downloads (dls)     - Mostra downloads em andamento");
        System.out.println();
        System.out.println("🌐 REDE:");
        System.out.println("  peers (p)           - Lista peers conhecidos");
        System.out.println("  refresh (update)    - Atualiza informações do tracker");
        System.out.println("  tracker             - Informações do tracker");
        System.out.println();
        System.out.println("ℹ️  INFORMAÇÕES:");
        System.out.println("  status (info)       - Status do peer");
        System.out.println("  whoami (me)         - Identificação do peer");
        System.out.println();
        System.out.println("🔧 SISTEMA:");
        System.out.println("  help (h, ?)         - Mostra este menu");
        System.out.println("  quit (exit, q, bye) - Sair do programa");
        System.out.println("=".repeat(60) + "\n");
    }

    private void listFiles() {
        Map<String, Set<Integer>> files = fileManager.getAvailableFiles();

        if (files.isEmpty()) {
            System.out.println("📂 Nenhum arquivo disponível localmente.");
            System.out.println("💡 Coloque arquivos em: " + fileManager.getSharedFolder().toAbsolutePath());
            return;
        }

        System.out.println("\n" + "=".repeat(50));
        System.out.println("📁 ARQUIVOS LOCAIS (" + files.size() + ")");
        System.out.println("=".repeat(50));

        List<Map.Entry<String, Set<Integer>>> sortedFiles = new ArrayList<>(files.entrySet());
        sortedFiles.sort(Map.Entry.comparingByKey());

        for (Map.Entry<String, Set<Integer>> entry : sortedFiles) {
            String fileName = entry.getKey();
            Set<Integer> chunks = entry.getValue();
            boolean isComplete = fileManager.hasCompleteFile(fileName);

            FileManager.FileMetadata metadata = fileManager.getFileMetadata(fileName);

            String statusIcon = isComplete ? "✅" : "📦";
            String sizeInfo = "";

            if (metadata != null) {
                sizeInfo = String.format(" (%s)", formatSize(metadata.fileSize));
                double progress = (double) chunks.size() / metadata.totalChunks * 100;
                System.out.printf("%s %s%s - %.1f%% (%d/%d chunks)\n",
                        statusIcon, fileName, sizeInfo, progress, chunks.size(), metadata.totalChunks);
            } else {
                System.out.printf("%s %s%s (%d chunks)\n",
                        statusIcon, fileName, sizeInfo, chunks.size());
            }
        }
        System.out.println("=".repeat(50) + "\n");
    }

    private void listPeers() {
        Map<String, TrackerClient.PeerInfo> peers = trackerClient.getKnownPeers();

        if (peers.isEmpty()) {
            System.out.println("🌐 Nenhum peer conhecido.");
            System.out.println("💡 Use 'refresh' para atualizar informações do tracker.");
            return;
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("🌐 PEERS CONHECIDOS (" + peers.size() + ")");
        System.out.println("=".repeat(60));

        List<Map.Entry<String, TrackerClient.PeerInfo>> sortedPeers = new ArrayList<>(peers.entrySet());
        sortedPeers.sort((a, b) -> {
            long timeDiff = b.getValue().lastSeen - a.getValue().lastSeen;
            return Long.compare(timeDiff, 0);
        });

        for (Map.Entry<String, TrackerClient.PeerInfo> entry : sortedPeers) {
            String peerAddr = entry.getKey();
            TrackerClient.PeerInfo info = entry.getValue();

            long timeSinceLastSeen = System.currentTimeMillis() - info.lastSeen;
            String timeAgo = formatTimeAgo(timeSinceLastSeen);
            String networkAddr = TrackerClient.extractNetworkAddress(peerAddr);

            String statusIcon = timeSinceLastSeen < 30000 ? "🟢" :
                    timeSinceLastSeen < 300000 ? "🟡" : "🔴";

            System.out.printf("%s %s (%s) - visto há %s\n",
                    statusIcon, peerAddr, networkAddr, timeAgo);

            if (info.files.isEmpty()) {
                System.out.println("     📂 Nenhum arquivo compartilhado");
            } else {
                int fileCount = info.files.size();
                if (fileCount <= 3) {
                    for (Map.Entry<String, Set<Integer>> fileEntry : info.files.entrySet()) {
                        System.out.printf("     📄 %s (%d chunks)\n",
                                fileEntry.getKey(), fileEntry.getValue().size());
                    }
                } else {
                    System.out.printf("     📄 %d arquivos compartilhados\n", fileCount);
                }
            }
        }
        System.out.println("=".repeat(60) + "\n");
    }

    private void handleDownload(String fileName) {
        if (fileName.isEmpty()) {
            System.out.println("❌ Uso: download <nome_do_arquivo>");
            return;
        }

        if (fileManager.hasCompleteFile(fileName)) {
            System.out.println("✅ Arquivo '" + fileName + "' já existe localmente e está completo.");
            return;
        }

        // Verifica se algum peer tem o arquivo
        Map<String, TrackerClient.PeerInfo> peers = trackerClient.getKnownPeers();
        List<String> peersWithFile = new ArrayList<>();

        for (Map.Entry<String, TrackerClient.PeerInfo> entry : peers.entrySet()) {
            if (entry.getValue().hasFile(fileName)) {
                peersWithFile.add(entry.getKey());
            }
        }

        if (peersWithFile.isEmpty()) {
            System.out.println("❌ Arquivo '" + fileName + "' não encontrado em nenhum peer conhecido.");
            System.out.println("💡 Use 'peers' para ver arquivos disponíveis.");
            System.out.println("💡 Use 'refresh' para atualizar lista de peers.");
            return;
        }

        System.out.printf("🔍 Arquivo encontrado em %d peer(s): %s\n",
                peersWithFile.size(), peersWithFile);
        System.out.println("⬇️  Iniciando download de '" + fileName + "'...");

        downloadManager.requestDownload(fileName);
    }

    private void showStatus() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("📊 STATUS DO PEER");
        System.out.println("=".repeat(50));

        // Informações básicas
        System.out.println("📛 Nome: " + customName);
        System.out.println("🆔 Identificador: " + peerIdentifier);
        System.out.println("🌐 Endereço: " + TrackerClient.extractNetworkAddress(peerIdentifier));
        System.out.println("🔌 Porta: " + peer.getPort());

        // Status dos serviços
        String peerStatus = peer.isRunning() ? "🟢 Ativo" : "🔴 Inativo";
        String trackerStatus = trackerClient.isConnectedToTracker() ? "🟢 Conectado" : "🔴 Desconectado";

        System.out.println("⚡ Peer: " + peerStatus);
        System.out.println("🔗 Tracker: " + trackerStatus);

        // Estatísticas de arquivos
        Map<String, Set<Integer>> localFiles = fileManager.getAvailableFiles();
        int completeFiles = 0;
        for (String fileName : localFiles.keySet()) {
            if (fileManager.hasCompleteFile(fileName)) {
                completeFiles++;
            }
        }

        System.out.println("📁 Arquivos: " + localFiles.size() + " (" + completeFiles + " completos)");
        System.out.println("📦 Chunks: " + fileManager.getTotalChunksCount());

        // Informações de rede
        Map<String, TrackerClient.PeerInfo> peers = trackerClient.getKnownPeers();
        System.out.println("🌐 Peers conhecidos: " + peers.size());
        System.out.println("⬇️  Downloads ativos: " + downloadManager.getActiveDownloadsCount());

        // Informações de armazenamento
        long storageUsed = fileManager.getTotalStorageUsed();
        System.out.println("💾 Armazenamento usado: " + formatSize(storageUsed));
        System.out.println("📂 Pasta compartilhada: " + fileManager.getSharedFolder().toAbsolutePath());

        System.out.println("=".repeat(50) + "\n");
    }

    private void showIdentity() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("🆔 IDENTIFICAÇÃO DO PEER");
        System.out.println("=".repeat(50));
        System.out.println("📛 Nome customizado: " + customName);
        System.out.println("🆔 Identificador: " + peerIdentifier);
        System.out.println("🌐 Endereço de rede: " + TrackerClient.extractNetworkAddress(peerIdentifier));
        System.out.println("🔌 Porta TCP: " + peer.getPort());

        try {
            String localIp = java.net.InetAddress.getLocalHost().getHostAddress();
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            System.out.println("🖥️  IP local: " + localIp);
            System.out.println("🖥️  Hostname: " + hostname);
        } catch (Exception e) {
            System.out.println("⚠️  Erro ao detectar informações de rede: " + e.getMessage());
        }

        System.out.println("📂 Pasta: " + fileManager.getSharedFolder().toAbsolutePath());
        System.out.println("🕐 Iniciado em: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
        System.out.println("=".repeat(50) + "\n");
    }

    private void handleRefresh() {
        System.out.println("🔄 Atualizando informações do tracker...");

        if (trackerClient.sendUpdateToTracker()) {
            System.out.println("✅ Informações atualizadas com sucesso!");

            Map<String, TrackerClient.PeerInfo> peers = trackerClient.getKnownPeers();
            System.out.println("🌐 Peers conhecidos: " + peers.size());
        } else {
            System.out.println("❌ Falha ao atualizar informações do tracker.");
        }
    }

    private void showTrackerInfo() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("📡 INFORMAÇÕES DO TRACKER");
        System.out.println("=".repeat(50));
        System.out.println("🌐 Endereço: " + peer.getTrackerClient().getTrackerAddress());
        System.out.println("🔗 Status: " + (trackerClient.isConnectedToTracker() ? "🟢 Conectado" : "🔴 Desconectado"));

        Map<String, TrackerClient.PeerInfo> peers = trackerClient.getKnownPeers();
        System.out.println("👥 Peers conhecidos: " + peers.size());

        // Conta total de arquivos únicos na rede
        Set<String> uniqueFiles = new HashSet<>();
        for (TrackerClient.PeerInfo info : peers.values()) {
            uniqueFiles.addAll(info.files.keySet());
        }

        System.out.println("=".repeat(50) + "\n");
    }

    private void showDownloads() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("⬇️  DOWNLOADS");
        System.out.println("=".repeat(50));
        System.out.println("🟢 Ativos: " + downloadManager.getActiveDownloadsCount());
        System.out.println("✅ Completados: " + downloadManager.getCompletedDownloadsCount());
        System.out.println("=".repeat(50) + "\n");
    }

    private void handleQuit() {
        System.out.println("\n👋 Encerrando " + customName + " (" + peerIdentifier + ")...");
        System.out.println("🔄 Salvando dados e desconectando...");

        stop();
        peer.shutdown();

        System.out.println("✅ Peer encerrado com sucesso!");
        System.out.println("🚀 Obrigado por usar o P2P!");
        System.exit(0);
    }

    // Métodos utilitários
    private String formatTimeAgo(long millisAgo) {
        long seconds = millisAgo / 1000;
        if (seconds < 60) return seconds + "s";

        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";

        long hours = minutes / 60;
        if (hours < 24) return hours + "h";

        long days = hours / 24;
        return days + "d";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) +
                "] [" + customName + "] [CI] " + message);
    }
}