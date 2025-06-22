import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class MethodsManager {
    private final Tracker tracker;

    public MethodsManager(Tracker tracker) {
        this.tracker = tracker;
    }

    public void sendPeersList(DatagramSocket socket, InetAddress ip, int port, String requesterAddr) {
        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(byteStream);

            out.writeUTF("PEERS_LIST");
            out.writeLong(System.currentTimeMillis());

            AtomicInteger peerCount = new AtomicInteger();

            tracker.getPeers().forEach((peerAddr, info) -> {
                if (!peerAddr.equals(requesterAddr) && info != null) {
                    peerCount.incrementAndGet();
                }
            });

            out.writeInt(peerCount.get());

            tracker.getPeers().forEach((peerAddr, info) -> {
                if (peerAddr.equals(requesterAddr) || info == null) return;

                try {
                    out.writeUTF(peerAddr);
                    out.writeLong(info.lastSeen);
                    out.writeInt(info.files.size());

                    for (Map.Entry<String, Set<Integer>> fileEntry : info.files.entrySet()) {
                        out.writeUTF(fileEntry.getKey());
                        Set<Integer> indices = fileEntry.getValue();
                        out.writeInt(indices.size());
                        for (int index : indices) {
                            out.writeInt(index);
                        }
                    }
                } catch (IOException e) {
                    tracker.log("Erro na serialização do peer " + peerAddr + ": " + e.getMessage());
                }
            });

            byte[] data = byteStream.toByteArray();
            if (data.length > tracker.getMaxPacketSize()) {
                tracker.log("AVISO: Resposta muito grande (" + data.length + " bytes) para " + requesterAddr);
            }

            DatagramPacket responsePacket = new DatagramPacket(data, data.length, ip, port);
            socket.send(responsePacket);
            tracker.log("Enviou lista com " + peerCount.get() + " peers para " + requesterAddr);
        } catch (IOException e) {
            tracker.log("Erro ao enviar lista de peers: " + e.getMessage());
        }
    }

    public void sendAck(DatagramSocket socket, InetAddress ip, int port) {
        try {
            String ackMsg = "ACK " + System.currentTimeMillis();
            byte[] data = ackMsg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            DatagramPacket responsePacket = new DatagramPacket(data, data.length, ip, port);
            socket.send(responsePacket);
        } catch (IOException e) {
            tracker.log("Erro ao enviar ACK: " + e.getMessage());
        }
    }

    public void sendErrorResponse(DatagramSocket socket, InetAddress ip, int port, String errorCode) {
        try {
            String errorMsg = "ERROR " + errorCode + " " + System.currentTimeMillis();
            byte[] data = errorMsg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            DatagramPacket responsePacket = new DatagramPacket(data, data.length, ip, port);
            socket.send(responsePacket);
        } catch (IOException e) {
            tracker.log("Erro ao enviar resposta de erro: " + e.getMessage());
        }
    }
}