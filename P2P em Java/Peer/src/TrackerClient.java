import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TrackerClient {
    private static final int UPDATE_INTERVAL_SECONDS = 30;
    private static final int HEARTBEAT_INTERVAL_SECONDS = 60;
    private static final int RESPONSE_TIMEOUT_MS = 5000;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String peerIdentifier;
    private final int port;
    private final String trackerIp;
    private final int trackerPort;
    private final FileManager fileManager;

    private final Map<String, PeerInfo> knownPeers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private DatagramSocket socket;
    private long lastTrackerResponse = 0;

    public TrackerClient(String peerIdentifier, int port, String trackerIp, int trackerPort, FileManager fileManager) {
        this.peerIdentifier = peerIdentifier;
        this.port = port;
        this.trackerIp = trackerIp;
        this.trackerPort = trackerPort;
        this.fileManager = fileManager;
    }

    public boolean registerWithTracker() {
        try {
            if (socket == null) {
                socket = new DatagramSocket();
                socket.setSoTimeout(RESPONSE_TIMEOUT_MS);
            }

            String message = "REGISTER " + getLocalIp() + " " + port;
            boolean success = sendMessageWithRetry(message);

            if (success) {
                log("Registrado com sucesso no tracker");
                isActive.set(true);
                return true;
            } else {
                log("ERRO: Falha ao registrar com tracker");
                return false;
            }
        } catch (Exception e) {
            log("ERRO ao registrar: " + e.getMessage());
            return false;
        }
    }

    public boolean sendUpdateToTracker() {
        if (!isActive.get()) {
            return false;
        }

        try {
            String filesInfo = buildFilesInfo();
            String message = "UPDATE " + getLocalIp() + " " + port + " " + filesInfo;
            boolean success = sendMessageWithRetry(message);

            if (success) {
                log("Update recebido do tracker (" + knownPeers.size() + " peers conhecidos)");
                return true;
            } else {
                log("ERRO: Falha ao enviar update");
                return false;
            }
        } catch (Exception e) {
            log("ERRO no update: " + e.getMessage());
            return false;
        }
    }

    public boolean unregisterFromTracker() {
        if (!isActive.get()) {
            return true;
        }

        try {
            String message = "UNREGISTER " + getLocalIp() + " " + port;
            boolean success = sendMessageWithRetry(message);

            if (success) {
                log("Desregistrado do tracker");
            } else {
                log("AVISO: Falha ao desregistrar do tracker");
            }

            isActive.set(false);
            return success;
        } catch (Exception e) {
            log("ERRO ao desregistrar: " + e.getMessage());
            isActive.set(false);
            return false;
        }
    }

    private boolean sendHeartbeat() {
        if (!isActive.get()) {
            return false;
        }

        try {
            String message = "HEARTBEAT " + getLocalIp() + " " + port;
            return sendMessageWithRetry(message);
        } catch (Exception e) {
            log("ERRO no heartbeat: " + e.getMessage());
            return false;
        }
    }

    private boolean sendMessageWithRetry(String message) {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                byte[] data = message.getBytes("UTF-8");
                InetAddress trackerAddress = InetAddress.getByName(trackerIp);
                DatagramPacket packet = new DatagramPacket(
                        data, data.length, trackerAddress, trackerPort
                );

                socket.send(packet);

                // Aguarda resposta
                byte[] responseBuffer = new byte[65535];
                DatagramPacket responsePacket = new DatagramPacket(
                        responseBuffer, responseBuffer.length
                );

                socket.receive(responsePacket);
                lastTrackerResponse = System.currentTimeMillis();

                // Processa resposta
                processTrackerResponse(responsePacket);
                return true;

            } catch (SocketTimeoutException e) {
                log("Timeout na tentativa " + attempt + "/" + MAX_RETRY_ATTEMPTS);
                if (attempt == MAX_RETRY_ATTEMPTS) {
                    return false;
                }
            } catch (Exception e) {
                log("Erro na tentativa " + attempt + ": " + e.getMessage());
                if (attempt == MAX_RETRY_ATTEMPTS) {
                    return false;
                }
            }

            // Delay entre tentativas
            try {
                Thread.sleep(1000 * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void processTrackerResponse(DatagramPacket packet) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                    packet.getData(), 0, packet.getLength()
            ));

            String responseType = in.readUTF();

            switch (responseType) {
                case "PEERS_LIST" -> processPeersList(in);
                case "ACK" -> {
                    long timestamp = in.readLong();
                    log("ACK recebido do tracker");
                }
                case "ERROR" -> {
                    String errorCode = in.readUTF();
                    long timestamp = in.readLong();
                    log("ERRO do tracker: " + errorCode);
                }
                default -> {
                    processLegacyPeersList(packet);
                }
            }
        } catch (Exception e) {
           if (e.getMessage() != null)
            log("Erro ao processar resposta: " + e.getMessage());
        }
    }

    private void processPeersList(DataInputStream in) throws IOException {
        long timestamp = in.readLong();
        int peerCount = in.readInt();

        Map<String, PeerInfo> newPeers = new ConcurrentHashMap<>();

        for (int i = 0; i < peerCount; i++) {
            String peerAddr = in.readUTF();
            long lastSeen = in.readLong();
            int filesCount = in.readInt();

            Map<String, Set<Integer>> files = new HashMap<>();
            for (int j = 0; j < filesCount; j++) {
                String fileName = in.readUTF();
                int indicesCount = in.readInt();
                Set<Integer> indices = new HashSet<>();

                for (int k = 0; k < indicesCount; k++) {
                    indices.add(in.readInt());
                }
                files.put(fileName, indices);
            }

            String normalizedPeerAddr = normalizePeerAddress(peerAddr);
            newPeers.put(normalizedPeerAddr, new PeerInfo(files, lastSeen));
        }

        // Atualiza peers conhecidos
        knownPeers.clear();
        knownPeers.putAll(newPeers);
        log("Lista atualizada: " + peerCount + " peers disponíveis");

        // Log dos peers encontrados
        for (String peer : newPeers.keySet()) {
            log("  Peer disponível: " + peer);
        }
    }

    private void processLegacyPeersList(DatagramPacket packet) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                    packet.getData(), 0, packet.getLength()
            ));

            Map<String, PeerInfo> newPeers = new ConcurrentHashMap<>();

            while (in.available() > 0) {
                String peerAddr = in.readUTF();
                int filesCount = in.readInt();

                Map<String, Set<Integer>> files = new HashMap<>();
                for (int i = 0; i < filesCount; i++) {
                    String fileName = in.readUTF();
                    int indicesCount = in.readInt();
                    Set<Integer> indices = new HashSet<>();

                    for (int j = 0; j < indicesCount; j++) {
                        indices.add(in.readInt());
                    }
                    files.put(fileName, indices);
                }

                String normalizedPeerAddr = normalizePeerAddress(peerAddr);
                newPeers.put(normalizedPeerAddr, new PeerInfo(files, System.currentTimeMillis()));
            }

            knownPeers.clear();
            knownPeers.putAll(newPeers);

            log("Lista legada processada: " + newPeers.size() + " peers");
        } catch (Exception e) {
            log("Erro ao processar lista legada: " + e.getMessage());
        }
    }

    private String normalizePeerAddress(String peerAddr) {
        // Se já está no formato Peer_IP:Porta, retorna como está
        if (peerAddr.startsWith("Peer_")) {
            return peerAddr;
        }

        // Se está no formato IP:Porta, converte para Peer_IP:Porta
        if (peerAddr.contains(":")) {
            return "Peer_" + peerAddr;
        }

        // Formato desconhecido, retorna como está
        return peerAddr;
    }

    public void startPeriodicUpdates() {
        if (isActive.get()) {
            // Updates periódicos
            scheduler.scheduleAtFixedRate(
                    this::sendUpdateToTracker,
                    UPDATE_INTERVAL_SECONDS,
                    UPDATE_INTERVAL_SECONDS,
                    TimeUnit.SECONDS
            );

            // Heartbeat
            scheduler.scheduleAtFixedRate(
                    this::sendHeartbeat,
                    HEARTBEAT_INTERVAL_SECONDS,
                    HEARTBEAT_INTERVAL_SECONDS,
                    TimeUnit.SECONDS
            );

            log("Updates periódicos iniciados");
        }
    }

    public void stop() {
        isActive.set(false);
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        log("TrackerClient parado");
    }

    private String buildFilesInfo() {
        Map<String, Set<Integer>> files = fileManager.getAvailableFiles();
        if (files.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (Map.Entry<String, Set<Integer>> entry : files.entrySet()) {
            if (!first) {
                sb.append(";;");
            }
            first = false;

            sb.append(entry.getKey());
            for (Integer index : entry.getValue()) {
                sb.append(",").append(index);
            }
        }

        return sb.toString();
    }

    private String getLocalIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public static String extractNetworkAddress(String peerIdentifier) {
        if (peerIdentifier.startsWith("Peer_")) {
            return peerIdentifier.substring(5); // Remove "Peer_"
        }
        return peerIdentifier;
    }

    public Map<String, PeerInfo> getKnownPeers() {
        return new HashMap<>(knownPeers);
    }

    public boolean isConnectedToTracker() {
        return isActive.get() && (System.currentTimeMillis() - lastTrackerResponse) < 120000; // 2 minutos
    }

    public void forceUpdate() {
        if (isActive.get()) {
            scheduler.execute(this::sendUpdateToTracker);
        }
    }

    private void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] [" + peerIdentifier + "] " + message);
    }

    public String getTrackerAddress() {
        return trackerIp + ":" + trackerPort;

    }

    // Classe interna para informações do peer
    public static class PeerInfo {
        public final Map<String, Set<Integer>> files;
        public final long lastSeen;

        public PeerInfo(Map<String, Set<Integer>> files, long lastSeen) {
            this.files = files;
            this.lastSeen = lastSeen;
        }

        public boolean hasFile(String fileName) {
            return files.containsKey(fileName);
        }

        public Set<Integer> getFileIndices(String fileName) {
            return files.getOrDefault(fileName, new HashSet<>());
        }
    }
}