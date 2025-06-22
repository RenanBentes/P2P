import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

public class Peer {
    private final String customName;
    private final String peerIdentifier; // Peer_IP:Porta
    private final int port;
    private final String trackerIp;
    private final int trackerPort;

    private final FileManager fileManager;
    private final TrackerClient trackerClient;
    private final TCPServer tcpServer;
    private final DownloadManager downloadManager;
    private final CommandInterface commandInterface;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public Peer(String customName, int port, String trackerIp, int trackerPort) {
        this.customName = customName;
        this.port = port;
        this.trackerIp = trackerIp;
        this.trackerPort = trackerPort;

        // Gera identificador baseado em IP:Porta
        this.peerIdentifier = generatePeerIdentifier(port);

        // Passa o nome customizado para o FileManager junto com o identificador
        this.fileManager = new FileManager(customName, peerIdentifier);
        this.trackerClient = new TrackerClient(peerIdentifier, port, trackerIp, trackerPort, fileManager);
        this.tcpServer = new TCPServer(peerIdentifier, port, fileManager);
        this.downloadManager = new DownloadManager(peerIdentifier, trackerClient, fileManager);
        this.commandInterface = new CommandInterface(peerIdentifier, customName, fileManager, trackerClient, downloadManager, this);

        start();
    }

    private String generatePeerIdentifier(int port) {
        try {
            String localIp = InetAddress.getLocalHost().getHostAddress();
            return "Peer_" + localIp + ":" + port;
        } catch (Exception e) {
            return "Peer_0.0.0.0:" + port;
        }
    }

    private void start() {
        if (!isRunning.compareAndSet(false, true)) {
            System.out.println("Peer já está rodando!");
            return;
        }

        try {
            fileManager.start();
            tcpServer.start();

            // Registra com o tracker
            if (trackerClient.registerWithTracker()) {
                trackerClient.sendUpdateToTracker();
            }

            // Inicia monitoramento de arquivos
            fileManager.startFolderWatcher(trackerClient);

            // Inicia serviços de rede
            trackerClient.startPeriodicUpdates();
            downloadManager.start();

            // Interface de comando por último
            commandInterface.start();

            System.out.println("Peer " + peerIdentifier + " iniciado com sucesso!");
            System.out.println("📁 Coloque seus arquivos em: " + fileManager.getSharedFolder());
        } catch (Exception e) {
            System.err.println("Erro ao iniciar peer: " + e.getMessage());
            shutdown();
        }
    }

    public void shutdown() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }

        System.out.println("=== ENCERRANDO " + peerIdentifier + " ===");

        try {
            // Desregistra do tracker
            trackerClient.unregisterFromTracker();

            // Para serviços na ordem inversa
            commandInterface.stop();
            downloadManager.stop();
            trackerClient.stop();
            fileManager.stop();
            tcpServer.stop();

            System.out.println("Peer " + peerIdentifier + " encerrado com sucesso!");
        } catch (Exception e) {
            System.err.println("Erro durante shutdown: " + e.getMessage());
        }
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public String getCustomName() { return customName; }
    public String getPeerIdentifier() { return peerIdentifier; }
    public int getPort() { return port; }
    public FileManager getFileManager() { return fileManager; }
    public TrackerClient getTrackerClient() { return trackerClient; }

    public static void main(String[] args) {
        String customName = "User";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 1000 + (int)(Math.random() * 1000);
        String trackerIp = "192.168.0.2";
        int trackerPort = 6881;

        System.out.println("Iniciando peer com nome: " + customName + " na porta " + port);
        Peer peer = new Peer(customName, port, trackerIp, trackerPort);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nRecebido sinal de shutdown...");
            peer.shutdown();
        }));
    }
}