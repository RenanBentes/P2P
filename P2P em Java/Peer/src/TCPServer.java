
import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TCPServer {
    private static final int CONNECTION_TIMEOUT_MS = 30000;
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_CONCURRENT_CONNECTIONS = 20;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String peerName;
    private final int port;
    private final FileManager fileManager;

    private ServerSocket serverSocket;
    private final ExecutorService connectionPool =
            Executors.newFixedThreadPool(MAX_CONCURRENT_CONNECTIONS);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger successfulTransfers = new AtomicInteger(0);
    private Thread serverThread;

    public TCPServer(String peerName, int port, FileManager fileManager) {
        this.peerName = peerName;
        this.port = port;
        this.fileManager = fileManager;
    }

    public void start() {
        if (!isRunning.compareAndSet(false, true)) {
            log("TCPServer já está rodando");
            return;
        }

        serverThread = new Thread(this::runServer, "TCPServer-" + peerName);
        serverThread.setDaemon(false);
        serverThread.start();

        log("TCPServer iniciado na porta " + port);
    }

    public void stop() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }

            connectionPool.shutdown();
            if (!connectionPool.awaitTermination(10, TimeUnit.SECONDS)) {
                connectionPool.shutdownNow();
            }

            if (serverThread != null) {
                serverThread.interrupt();
            }

            log("TCPServer parado. Estatísticas finais:");
            log("  Total de requisições: " + totalRequests.get());
            log("  Transferências bem-sucedidas: " + successfulTransfers.get());

        } catch (Exception e) {
            log("Erro ao parar TCPServer: " + e.getMessage());
        }
    }

    private void runServer() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(1000); // Timeout para verificar isRunning

            log("Aguardando conexões na porta " + port);

            while (isRunning.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    totalRequests.incrementAndGet();

                    if (activeConnections.get() >= MAX_CONCURRENT_CONNECTIONS) {
                        log("Máximo de conexões atingido, rejeitando: " +
                                clientSocket.getRemoteSocketAddress());
                        clientSocket.close();
                        continue;
                    }

                    connectionPool.execute(new ClientHandler(clientSocket));

                } catch (SocketTimeoutException e) {
                    // Timeout normal, continua o loop
                } catch (IOException e) {
                    if (isRunning.get()) {
                        log("Erro ao aceitar conexão: " + e.getMessage());
                    }
                }
            }

        } catch (IOException e) {
            if (isRunning.get()) {
                log("Erro fatal no servidor: " + e.getMessage());
            }
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final String clientAddress;

        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
            this.clientAddress = clientSocket.getRemoteSocketAddress().toString();
        }

        @Override
        public void run() {
            activeConnections.incrementAndGet();

            try {
                clientSocket.setSoTimeout(CONNECTION_TIMEOUT_MS);
                log("Nova conexão de " + clientAddress);

                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(clientSocket.getInputStream()));
                     DataOutputStream out = new DataOutputStream(
                             clientSocket.getOutputStream())) {

                    String request = in.readLine();
                    if (request == null || request.trim().isEmpty()) {
                        sendErrorResponse(out, "EMPTY_REQUEST", "Requisição vazia");
                        return;
                    }

                    processRequest(request.trim(), in, out);

                } catch (SocketTimeoutException e) {
                    log("Timeout na conexão com " + clientAddress);
                } catch (IOException e) {
                    log("Erro de I/O com " + clientAddress + ": " + e.getMessage());
                }

            } catch (Exception e) {
                log("Erro no handler para " + clientAddress + ": " + e.getMessage());
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    log("Erro ao fechar socket: " + e.getMessage());
                }
                activeConnections.decrementAndGet();
            }
        }

        private void processRequest(String request, BufferedReader in, DataOutputStream out) {
            try {
                String[] parts = request.split(" ", 4);
                if (parts.length < 2) {
                    sendErrorResponse(out, "INVALID_FORMAT", "Formato de requisição inválido");
                    return;
                }

                String command = parts[0].toUpperCase();

                switch (command) {
                    case "GET_CHUNK" -> handleGetChunk(parts, out);
                    case "LIST_FILES" -> handleListFiles(out);
                    case "FILE_INFO" -> handleFileInfo(parts, out);
                    case "PING" -> handlePing(out);
                    case "STATS" -> handleStats(out);
                    default -> sendErrorResponse(out, "UNKNOWN_COMMAND",
                            "Comando desconhecido: " + command);
                }

            } catch (Exception e) {
                log("Erro ao processar requisição '" + request + "': " + e.getMessage());
                try {
                    sendErrorResponse(out, "PROCESSING_ERROR", "Erro interno do servidor");
                } catch (IOException ioException) {
                    log("Erro ao enviar resposta de erro: " + ioException.getMessage());
                }
            }
        }

        private void handleGetChunk(String[] parts, DataOutputStream out) throws IOException {
            if (parts.length < 3) {
                sendErrorResponse(out, "INVALID_PARAMS", "Uso: GET_CHUNK <arquivo> <chunk_index>");
                return;
            }

            String fileName = parts[1];
            int chunkIndex;

            try {
                chunkIndex = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                sendErrorResponse(out, "INVALID_CHUNK_INDEX", "Índice de chunk inválido");
                return;
            }

            if (!fileManager.hasChunk(fileName, chunkIndex)) {
                sendErrorResponse(out, "CHUNK_NOT_FOUND",
                        "Chunk " + chunkIndex + " do arquivo '" + fileName + "' não encontrado");
                return;
            }

            byte[] chunkData = fileManager.loadChunk(fileName, chunkIndex);
            if (chunkData == null) {
                sendErrorResponse(out, "CHUNK_READ_ERROR", "Erro ao ler chunk do disco");
                return;
            }

            // Resposta de sucesso
            out.writeUTF("SUCCESS");
            out.writeUTF(fileName);
            out.writeInt(chunkIndex);
            out.writeInt(chunkData.length);
            out.write(chunkData);
            out.flush();

            successfulTransfers.incrementAndGet();
            log("Chunk " + chunkIndex + " de '" + fileName + "' enviado para " + clientAddress +
                    " (" + chunkData.length + " bytes)");
        }

        private void handleListFiles(DataOutputStream out) throws IOException {
            Map<String, Set<Integer>> availableFiles = fileManager.getAvailableFiles();

            out.writeUTF("SUCCESS");
            out.writeInt(availableFiles.size());

            for (Map.Entry<String, Set<Integer>> entry : availableFiles.entrySet()) {
                String fileName = entry.getKey();
                Set<Integer> chunks = entry.getValue();

                out.writeUTF(fileName);
                out.writeInt(chunks.size());

                for (Integer chunkIndex : chunks) {
                    out.writeInt(chunkIndex);
                }
            }

            out.flush();
            log("Lista de arquivos enviada para " + clientAddress +
                    " (" + availableFiles.size() + " arquivos)");
        }

        private void handleFileInfo(String[] parts, DataOutputStream out) throws IOException {
            if (parts.length < 2) {
                sendErrorResponse(out, "INVALID_PARAMS", "Uso: FILE_INFO <arquivo>");
                return;
            }

            String fileName = parts[1];
            FileManager.FileMetadata metadata = fileManager.getFileMetadata(fileName);

            if (metadata == null) {
                sendErrorResponse(out, "FILE_NOT_FOUND", "Arquivo '" + fileName + "' não encontrado");
                return;
            }

            Set<Integer> availableChunks = fileManager.getAvailableChunks(fileName);
            boolean isComplete = fileManager.hasCompleteFile(fileName);

            out.writeUTF("SUCCESS");
            out.writeUTF(metadata.fileName);
            out.writeLong(metadata.fileSize);
            out.writeInt(metadata.totalChunks);
            out.writeUTF(metadata.fileHash);
            out.writeLong(metadata.createdAt);
            out.writeBoolean(isComplete);
            out.writeInt(availableChunks.size());

            for (Integer chunkIndex : availableChunks) {
                out.writeInt(chunkIndex);
            }

            out.flush();
            log("Informações de '" + fileName + "' enviadas para " + clientAddress);
        }

        private void handlePing(DataOutputStream out) throws IOException {
            out.writeUTF("SUCCESS");
            out.writeUTF("PONG");
            out.writeLong(System.currentTimeMillis());
            out.writeUTF(peerName);
            out.flush();

            log("PING respondido para " + clientAddress);
        }

        private void handleStats(DataOutputStream out) throws IOException {
            out.writeUTF("SUCCESS");
            out.writeUTF(peerName);
            out.writeInt(fileManager.getTotalFilesCount());
            out.writeInt(fileManager.getTotalChunksCount());
            out.writeLong(fileManager.getTotalStorageUsed());
            out.writeInt(activeConnections.get());
            out.writeInt(totalRequests.get());
            out.writeInt(successfulTransfers.get());
            out.writeLong(System.currentTimeMillis());
            out.flush();

            log("Estatísticas enviadas para " + clientAddress);
        }

        private void sendErrorResponse(DataOutputStream out, String errorCode, String errorMessage)
                throws IOException {
            out.writeUTF("ERROR");
            out.writeUTF(errorCode);
            out.writeUTF(errorMessage);
            out.writeLong(System.currentTimeMillis());
            out.flush();

            log("Erro enviado para " + clientAddress + ": " + errorCode + " - " + errorMessage);
        }
    }

    // Métodos públicos para estatísticas
    public boolean isRunning() {
        return isRunning.get();
    }

    public int getActiveConnectionsCount() {
        return activeConnections.get();
    }

    public int getTotalRequestsCount() {
        return totalRequests.get();
    }

    public int getSuccessfulTransfersCount() {
        return successfulTransfers.get();
    }

    public int getPort() {
        return port;
    }

    public void printStatistics() {
        log("=== ESTATÍSTICAS DO TCP SERVER ===");
        log("Status: " + (isRunning.get() ? "🟢 Ativo" : "🔴 Inativo"));
        log("Porta: " + port);
        log("Conexões ativas: " + activeConnections.get() + "/" + MAX_CONCURRENT_CONNECTIONS);
        log("Total de requisições: " + totalRequests.get());
        log("Transferências bem-sucedidas: " + successfulTransfers.get());

        if (totalRequests.get() > 0) {
            double successRate = (double) successfulTransfers.get() / totalRequests.get() * 100;
            log("Taxa de sucesso: " + String.format("%.1f%%", successRate));
        }

        Map<String, Set<Integer>> files = fileManager.getAvailableFiles();
        log("Arquivos disponíveis: " + files.size());
        log("Total de chunks: " + fileManager.getTotalChunksCount());
        log("Armazenamento usado: " + formatBytes(fileManager.getTotalStorageUsed()));
        log("================================");
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void log(String message) {
        System.out.println("[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) +
                "] [" + peerName + "] [TCP] " + message);
    }
}