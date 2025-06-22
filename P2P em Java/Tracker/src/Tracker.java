import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Tracker {
    public static final int TRACKER_PORT = 6881;
    private static final int PEER_TIMEOUT_MINUTES = 2;
    private static final int CLEANUP_INTERVAL_SECONDS = 60;
    public static final int MAX_PACKET_SIZE = 65535;
    private static final int THREAD_POOL_SIZE = 10;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConcurrentHashMap<String, PeerInfo> peers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ExecutorService requestHandlers =
            Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    private volatile boolean isActive = true;
    private final MethodsManager methodsManager = new MethodsManager(this);

    public static void main(String[] args) {
        new Tracker().start();
    }

    public void start() {
        log("Tracker iniciado na porta " + TRACKER_PORT);
        scheduler.scheduleAtFixedRate(
                this::removeTimeoutPeers,
                CLEANUP_INTERVAL_SECONDS,
                CLEANUP_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );

        try (DatagramSocket socket = new DatagramSocket(TRACKER_PORT)) {
            socket.setSoTimeout(1000);
            while (isActive) {
                try {
                    byte[] buf = new byte[MAX_PACKET_SIZE];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    requestHandlers.execute(() -> handleRequest(socket, packet));
                } catch (SocketTimeoutException e) {
                    // Timeout normal, continua o loop
                }
            }
        } catch (IOException e) {
            log("Erro no socket: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    private void handleRequest(DatagramSocket socket, DatagramPacket packet) {
        try {
            String msg = new String(
                    packet.getData(),
                    0,
                    packet.getLength(),
                    java.nio.charset.StandardCharsets.UTF_8
            ).trim();

            String[] parts = msg.split(" ", 4);
            if (parts.length < 3) {
                log("Mensagem inválida: " + msg);
                methodsManager.sendErrorResponse(socket, packet.getAddress(), packet.getPort(), "INVALID_FORMAT");
                return;
            }

            String command = parts[0];
            String peerAddr = parts[1] + ":" + parts[2];
            log("Recebeu " + command + " de " + peerAddr);

            switch (command) {
                case "REGISTER" -> {
                    handleRegister(peerAddr);
                    methodsManager.sendPeersList(socket, packet.getAddress(), packet.getPort(), peerAddr);
                }
                case "UPDATE" -> {
                    String filesInfo = parts.length > 3 ? parts[3] : "";
                    handleUpdate(peerAddr, filesInfo);
                    methodsManager.sendPeersList(socket, packet.getAddress(), packet.getPort(), peerAddr);
                }
                case "UNREGISTER" -> {
                    handleUnregister(peerAddr);
                    methodsManager.sendAck(socket, packet.getAddress(), packet.getPort());
                }
                case "HEARTBEAT" -> {
                    handleHeartbeat(peerAddr);
                    methodsManager.sendAck(socket, packet.getAddress(), packet.getPort());
                }
                default -> {
                    log("Comando desconhecido: " + command);
                    methodsManager.sendErrorResponse(socket, packet.getAddress(), packet.getPort(), "UNKNOWN_COMMAND");
                }
            }
        } catch (Exception e) {
            log("Erro ao processar requisição: " + e.getMessage());
            methodsManager.sendErrorResponse(socket, packet.getAddress(), packet.getPort(), "PROCESSING_ERROR");
        }
    }

    private void handleRegister(String peerAddr) {
        PeerInfo newPeer = new PeerInfo(new ConcurrentHashMap<>(), System.currentTimeMillis());
        peers.put(peerAddr, newPeer);
        log("Peer registrado: " + peerAddr);
        logPeerCount();
    }

    private void handleUpdate(String peerAddr, String filesInfo) {
        peers.compute(peerAddr, (addr, info) -> {
            Map<String, Set<Integer>> files = parseFilesInfo(filesInfo);
            if (info != null) {
                return new PeerInfo(files, System.currentTimeMillis());
            } else {
                log("Peer não registrado tentando atualizar: " + peerAddr);
                return new PeerInfo(files, System.currentTimeMillis());
            }
        });
        log("Peer atualizado: " + peerAddr);
        logPeerCount();
    }

    private void handleUnregister(String peerAddr) {
        if (peers.remove(peerAddr) != null) {
            log("Peer desregistrado: " + peerAddr);
            logPeerCount();
        } else {
            log("Tentativa de desregistrar peer inexistente: " + peerAddr);
        }
    }

    private void handleHeartbeat(String peerAddr) {
        peers.computeIfPresent(peerAddr, (addr, info) ->
                new PeerInfo(info.files, System.currentTimeMillis())
        );
    }

    private Map<String, Set<Integer>> parseFilesInfo(String filesInfo) {
        Map<String, Set<Integer>> filesMap = new ConcurrentHashMap<>();
        if (filesInfo == null || filesInfo.isEmpty()) {
            return filesMap;
        }

        try {
            for (String entry : filesInfo.split(";;")) {
                if (entry.isEmpty()) continue;

                String[] parts = entry.split(",");
                if (parts.length == 0) continue;

                String fileName = parts[0];
                Set<Integer> indices = ConcurrentHashMap.newKeySet();

                for (int i = 1; i < parts.length; i++) {
                    try {
                        int index = Integer.parseInt(parts[i]);
                        if (index >= 0) {
                            indices.add(index);
                        }
                    } catch (NumberFormatException e) {
                        log("Índice inválido ignorado: " + parts[i]);
                    }
                }
                if (!fileName.isEmpty()) {
                    filesMap.put(fileName, indices);
                }
            }
        } catch (Exception e) {
            log("Erro ao parsear informações de arquivos: " + e.getMessage());
        }
        return filesMap;
    }

    private void removeTimeoutPeers() {
        long timeoutMillis = TimeUnit.MINUTES.toMillis(PEER_TIMEOUT_MINUTES);
        long now = System.currentTimeMillis();
        AtomicInteger removedCount = new AtomicInteger();

        peers.entrySet().removeIf(entry -> {
            if (now - entry.getValue().lastSeen > timeoutMillis) {
                log("Removido por timeout: " + entry.getKey());
                removedCount.incrementAndGet();
                return true;
            }
            return false;
        });

        if (removedCount.get() > 0) {
            log("Removidos " + removedCount.get() + " peers por timeout. Peers ativos: " + peers.size());
        }
    }

    private void logPeerCount() {
        log("Peers ativos: " + peers.size());
    }

    public void stop() {
        isActive = false;
        log("Solicitação de parada recebida");
    }

    private void shutdown() {
        isActive = false;
        log("Iniciando shutdown...");

        requestHandlers.shutdown();
        scheduler.shutdown();

        try {
            if (!requestHandlers.awaitTermination(5, TimeUnit.SECONDS)) {
                log("Forçando shutdown dos handlers...");
                requestHandlers.shutdownNow();
            }
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log("Forçando shutdown do scheduler...");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("Shutdown interrompido");
        }
        log("Tracker encerrado");
    }

    public void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] " + message);
    }

    public ConcurrentHashMap<String, PeerInfo> getPeers() {
        return peers;
    }

    public int getMaxPacketSize() {
        return MAX_PACKET_SIZE;
    }

    static class PeerInfo {
        final Map<String, Set<Integer>> files;
        final long lastSeen;

        PeerInfo(Map<String, Set<Integer>> files, long lastSeen) {
            this.files = files;
            this.lastSeen = lastSeen;
        }
    }
}