import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class TCPClient {
    private static final int CONNECTION_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String peerName;

    public TCPClient(String peerName) {
        this.peerName = peerName;
    }

    public ChunkResponse requestChunk(String peerAddress, String fileName, int chunkIndex) {
        String[] parts = peerAddress.split(":");
        if (parts.length != 2) {
            return new ChunkResponse(false, "Endereço inválido: " + peerAddress, null, -1);
        }

        try {
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            return requestChunk(host, port, fileName, chunkIndex);
        } catch (NumberFormatException e) {
            return new ChunkResponse(false, "Porta inválida em: " + peerAddress, null, -1);
        }
    }

    public ChunkResponse requestChunk(String host, int port, String fileName, int chunkIndex) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECTION_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {

                // Envia requisição
                String request = "GET_CHUNK " + fileName + " " + chunkIndex;
                out.println(request);

                // Lê resposta
                String status = in.readUTF();

                if ("SUCCESS".equals(status)) {
                    String responseFileName = in.readUTF();
                    int responseChunkIndex = in.readInt();
                    int dataLength = in.readInt();

                    byte[] chunkData = new byte[dataLength];
                    in.readFully(chunkData);

                    log("Chunk " + chunkIndex + " de '" + fileName + "' recebido de " +
                            host + ":" + port + " (" + dataLength + " bytes)");

                    return new ChunkResponse(true, "Sucesso", chunkData, responseChunkIndex);

                } else if ("ERROR".equals(status)) {
                    String errorCode = in.readUTF();
                    String errorMessage = in.readUTF();
                    long timestamp = in.readLong();

                    log("Erro ao solicitar chunk de " + host + ":" + port + ": " +
                            errorCode + " - " + errorMessage);

                    return new ChunkResponse(false, errorCode + ": " + errorMessage, null, -1);
                } else {
                    return new ChunkResponse(false, "Resposta inválida: " + status, null, -1);
                }
            }

        } catch (SocketTimeoutException e) {
            log("Timeout ao conectar com " + host + ":" + port);
            return new ChunkResponse(false, "Timeout de conexão", null, -1);
        } catch (IOException e) {
            log("Erro de I/O com " + host + ":" + port + ": " + e.getMessage());
            return new ChunkResponse(false, "Erro de I/O: " + e.getMessage(), null, -1);
        } catch (Exception e) {
            log("Erro inesperado com " + host + ":" + port + ": " + e.getMessage());
            return new ChunkResponse(false, "Erro inesperado: " + e.getMessage(), null, -1);
        }
    }

    public FileListResponse requestFileList(String peerAddress) {
        String[] parts = peerAddress.split(":");
        if (parts.length != 2) {
            return new FileListResponse(false, "Endereço inválido: " + peerAddress, null);
        }

        try {
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            return requestFileList(host, port);
        } catch (NumberFormatException e) {
            return new FileListResponse(false, "Porta inválida em: " + peerAddress, null);
        }
    }

    public FileListResponse requestFileList(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECTION_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {

                out.println("LIST_FILES");

                String status = in.readUTF();

                if ("SUCCESS".equals(status)) {
                    int filesCount = in.readInt();
                    Map<String, Set<Integer>> files = new HashMap<>();

                    for (int i = 0; i < filesCount; i++) {
                        String fileName = in.readUTF();
                        int chunksCount = in.readInt();
                        Set<Integer> chunks = new HashSet<>();

                        for (int j = 0; j < chunksCount; j++) {
                            chunks.add(in.readInt());
                        }

                        files.put(fileName, chunks);
                    }

                    log("Lista de arquivos recebida de " + host + ":" + port +
                            " (" + filesCount + " arquivos)");

                    return new FileListResponse(true, "Sucesso", files);

                } else if ("ERROR".equals(status)) {
                    String errorCode = in.readUTF();
                    String errorMessage = in.readUTF();

                    return new FileListResponse(false, errorCode + ": " + errorMessage, null);
                } else {
                    return new FileListResponse(false, "Resposta inválida: " + status, null);
                }
            }

        } catch (Exception e) {
            log("Erro ao solicitar lista de arquivos de " + host + ":" + port + ": " + e.getMessage());
            return new FileListResponse(false, "Erro: " + e.getMessage(), null);
        }
    }

    public FileInfoResponse requestFileInfo(String peerAddress, String fileName) {
        String[] parts = peerAddress.split(":");
        if (parts.length != 2) {
            return new FileInfoResponse(false, "Endereço inválido: " + peerAddress, null);
        }

        try {
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            return requestFileInfo(host, port, fileName);
        } catch (NumberFormatException e) {
            return new FileInfoResponse(false, "Porta inválida em: " + peerAddress, null);
        }
    }

    public FileInfoResponse requestFileInfo(String host, int port, String fileName) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECTION_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {

                out.println("FILE_INFO " + fileName);

                String status = in.readUTF();

                if ("SUCCESS".equals(status)) {
                    String responseFileName = in.readUTF();
                    long fileSize = in.readLong();
                    int totalChunks = in.readInt();
                    String fileHash = in.readUTF();
                    long createdAt = in.readLong();
                    boolean isComplete = in.readBoolean();
                    int availableChunksCount = in.readInt();

                    Set<Integer> availableChunks = new HashSet<>();
                    for (int i = 0; i < availableChunksCount; i++) {
                        availableChunks.add(in.readInt());
                    }

                    FileInfo fileInfo = new FileInfo(responseFileName, fileSize, totalChunks,
                            fileHash, createdAt, isComplete, availableChunks);

                    log("Informações de '" + fileName + "' recebidas de " + host + ":" + port);

                    return new FileInfoResponse(true, "Sucesso", fileInfo);

                } else if ("ERROR".equals(status)) {
                    String errorCode = in.readUTF();
                    String errorMessage = in.readUTF();

                    return new FileInfoResponse(false, errorCode + ": " + errorMessage, null);
                } else {
                    return new FileInfoResponse(false, "Resposta inválida: " + status, null);
                }
            }

        } catch (Exception e) {
            log("Erro ao solicitar informações de '" + fileName + "' de " + host + ":" + port +
                    ": " + e.getMessage());
            return new FileInfoResponse(false, "Erro: " + e.getMessage(), null);
        }
    }

    public boolean ping(String peerAddress) {
        String[] parts = peerAddress.split(":");
        if (parts.length != 2) {
            return false;
        }

        try {
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            return ping(host, port);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public boolean ping(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECTION_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {

                long startTime = System.currentTimeMillis();
                out.println("PING");

                String status = in.readUTF();
                if ("SUCCESS".equals(status)) {
                    String response = in.readUTF();
                    long serverTime = in.readLong();
                    String serverName = in.readUTF();
                    long endTime = System.currentTimeMillis();

                    long latency = endTime - startTime;
                    log("PING para " + host + ":" + port + " bem-sucedido - " +
                            "Latência: " + latency + "ms, Peer: " + serverName);

                    return true;
                }
            }

        } catch (Exception e) {
            log("PING falhou para " + host + ":" + port + ": " + e.getMessage());
        }

        return false;
    }

    private void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) +
                "] [" + peerName + "] [TCP-Client] " + message);
    }

    // Classes de resposta
    public static class ChunkResponse {
        public final boolean success;
        public final String message;
        public final byte[] data;
        public final int chunkIndex;

        public ChunkResponse(boolean success, String message, byte[] data, int chunkIndex) {
            this.success = success;
            this.message = message;
            this.data = data;
            this.chunkIndex = chunkIndex;
        }
    }

    public static class FileListResponse {
        public final boolean success;
        public final String message;
        public final Map<String, Set<Integer>> files;

        public FileListResponse(boolean success, String message, Map<String, Set<Integer>> files) {
            this.success = success;
            this.message = message;
            this.files = files;
        }
    }

    public static class FileInfoResponse {
        public final boolean success;
        public final String message;
        public final FileInfo fileInfo;

        public FileInfoResponse(boolean success, String message, FileInfo fileInfo) {
            this.success = success;
            this.message = message;
            this.fileInfo = fileInfo;
        }
    }

    public static class FileInfo {
        public final String fileName;
        public final long fileSize;
        public final int totalChunks;
        public final String fileHash;
        public final long createdAt;
        public final boolean isComplete;
        public final Set<Integer> availableChunks;

        public FileInfo(String fileName, long fileSize, int totalChunks, String fileHash,
                        long createdAt, boolean isComplete, Set<Integer> availableChunks) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.totalChunks = totalChunks;
            this.fileHash = fileHash;
            this.createdAt = createdAt;
            this.isComplete = isComplete;
            this.availableChunks = availableChunks;
        }
    }
}