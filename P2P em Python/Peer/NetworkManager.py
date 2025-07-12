import socket
import threading
import logging
import time
import json
import struct
import zlib
from concurrent.futures import ThreadPoolExecutor

class NetworkManager:
    """Gere toda a comunicação de rede: UDP com o Tracker e TCP com outros Peers"""

    RESPONSE_TIMEOUT_S = 5
    UPDATE_INTERVAL_S = 30
    HEARTBEAT_INTERVAL_S = 15
    MAX_RETRY_ATTEMPTS = 3
    TCP_SERVER_MAX_CONNECTIONS = 20
    MAX_PAYLOAD_SIZE = 65535
    COMPRESSION_THRESHOLD = 1024

    def __init__(self, peer_id, own_port, tracker_addr, file_manager):
        self.peer_id = peer_id
        self.own_port = own_port
        self.tracker_ip, self.tracker_port = tracker_addr
        self.file_manager = file_manager

        self.is_active = False
        self.last_tracker_response_time = 0
        self.tcp_server_thread = None
        self.periodic_update_thread = None
        self.heartbeat_thread = None
        self.tcp_executor = ThreadPoolExecutor(max_workers=self.TCP_SERVER_MAX_CONNECTIONS,
                                               thread_name_prefix='TCPHandler')

        self.udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp_socket.settimeout(self.RESPONSE_TIMEOUT_S)

        self.known_peers = {}
        self.peers_lock = threading.Lock()
        self.metrics = NetworkMetrics()

    def start(self):
        """Inicia todos os serviços de rede com melhor sequenciamento"""
        self.is_active = True

        # Inicia servidor TCP
        self.tcp_server_thread = threading.Thread(
            target=self._run_tcp_server,
            daemon=True,
            name="TCPServerThread"
        )
        self.tcp_server_thread.start()

        # Registra no tracker
        if self.register_with_tracker():
            # Inicia threads de manutenção
            self.periodic_update_thread = threading.Thread(
                target=self._periodic_update_loop,
                daemon=True,
                name="PeriodicUpdateThread"
            )
            self.periodic_update_thread.start()

            self.heartbeat_thread = threading.Thread(
                target=self._heartbeat_loop,
                daemon=True,
                name="HeartbeatThread"
            )
            self.heartbeat_thread.start()
        else:
            logging.error("Falha ao registrar no tracker")

    def stop(self):
        """Shutdown gracioso melhorado"""

        if not self.is_active:
            return
        logging.info("Iniciando shutdown gracioso do NetworkManager...")
        self.is_active = False

        # 1. Para de aceitar novas conexões
        try:
            # Força fechamento do socket do servidor
            with socket.create_connection(('127.0.0.1', self.own_port), timeout=0.5):
                pass
        except (socket.timeout, ConnectionRefusedError, OSError):
            pass

        # 2. Aguarda conclusão de operações TCP
        self.tcp_executor.shutdown(wait=True, cancel_futures=False)

        # 3. Desregistra do tracker
        self.unregister_from_tracker()

        # 4. Fecha sockets
        self.udp_socket.close()

        # 5. Aguarda threads terminarem
        for thread in [self.tcp_server_thread, self.periodic_update_thread, self.heartbeat_thread]:
            if thread and thread.is_alive():
                thread.join(timeout=3)

        logging.info("NetworkManager encerrado com sucesso")

    def is_connected_to_tracker(self):
        """Verifica se está conectado ao tracker"""
        return (time.time() - self.last_tracker_response_time) < 90

    def get_network_metrics(self):
        """Retorna métricas de rede"""
        return {
            'messages_sent': self.metrics.messages_sent,
            'messages_received': self.metrics.messages_received,
            'failed_attempts': self.metrics.failed_attempts,
            'last_response_time': self.metrics.last_response_time,
            'connected_to_tracker': self.is_connected_to_tracker(),
            'known_peers_count': len(self.known_peers)
        }

    # --- Comunicação com Tracker Melhorada ---
    def _send_udp_request(self, payload):
        """Envia requisição UDP com compressão e retry melhorado"""

        destination = (self.tracker_ip, self.tracker_port)
        for attempt in range(self.MAX_RETRY_ATTEMPTS):
            try:
                # Serializa e comprime se necessário
                json_data = json.dumps(payload).encode('utf-8')
                compressed_data, is_compressed = self._compress_if_needed(json_data)

                if is_compressed:
                    # Adiciona flag de compressão
                    message = b'COMPRESSED:' + compressed_data
                else:
                    message = json_data

                if len(message) > self.MAX_PAYLOAD_SIZE:
                    logging.error(f"Payload muito grande: {len(message)} bytes")
                    return False

                logging.debug(f"Enviando UDP para {destination}: {payload}")
                start_time = time.time()

                self.udp_socket.sendto(message, destination)
                self.metrics.record_message_sent()

                data, _ = self.udp_socket.recvfrom(self.MAX_PAYLOAD_SIZE)
                response_time = time.time() - start_time

                response = self._decompress_if_needed(data)
                self.metrics.record_response_received(response_time)

                self._process_tracker_response(response)
                return True

            except socket.timeout:
                logging.warning(
                    f"Timeout na comunicação com tracker (tentativa {attempt + 1}/{self.MAX_RETRY_ATTEMPTS})")
                self.metrics.record_failed_attempt()
                time.sleep(0.5 * (attempt + 1))  # Backoff exponencial

            except Exception as e:
                logging.error(f"Erro na comunicação com tracker: {e}")
                self.metrics.record_failed_attempt()

        self.last_tracker_response_time = 0
        return False

    def _compress_if_needed(self, data):
        """Comprime dados se excederem o threshold"""

        if len(data) > self.COMPRESSION_THRESHOLD:
            compressed = zlib.compress(data, level=6)
            return compressed, True
        return data, False

    def _decompress_if_needed(self, data):
        """Descomprime dados se necessário"""
        if data.startswith(b'COMPRESSED:'):
            compressed_data = data[11:]  # Remove prefix
            decompressed = zlib.decompress(compressed_data)
            return json.loads(decompressed.decode('utf-8'))
        return json.loads(data.decode('utf-8'))

    def _process_tracker_response(self, response):
        """Processa resposta do tracker com merge inteligente"""
        if response.get("status") == "success":
            if "peers" in response:
                with self.peers_lock:
                    # Merge inteligente ao invés de substituição completa
                    new_peers = response["peers"]

                    # Atualiza peers existentes e adiciona novos
                    for peer_id, peer_info in new_peers.items():
                        if peer_id in self.known_peers:
                            # Preserva informações locais importantes
                            self.known_peers[peer_id].update(peer_info)
                        else:
                            self.known_peers[peer_id] = peer_info

                    # Remove peers que não estão mais na resposta
                    current_peer_ids = set(new_peers.keys())
                    self.known_peers = {
                        pid: info for pid, info in self.known_peers.items()
                        if pid in current_peer_ids
                    }

                logging.info(f"Lista de peers atualizada: {len(self.known_peers)} peers")

            self.last_tracker_response_time = time.time()
        else:
            logging.warning(f"Tracker retornou erro: {response.get('message', 'Unknown error')}")

    def _periodic_update_loop(self):
        """Loop de updates periódicos"""
        while self.is_active:
            time.sleep(self.UPDATE_INTERVAL_S)
            if self.is_active:
                self.send_update_to_tracker()

    def _heartbeat_loop(self):
        """Loop de heartbeat para manter conexão ativa"""
        while self.is_active:
            time.sleep(self.HEARTBEAT_INTERVAL_S)
            if self.is_active and self.is_connected_to_tracker():
                payload = {"command": "HEARTBEAT", "peer_id": self.peer_id}
                self._send_udp_request(payload)

    def register_with_tracker(self):
        """Registra no tracker com validação"""
        logging.info("Registrando no tracker...")
        payload = {
            "command": "REGISTER",
            "peer_id": self.peer_id,
            "port": self.own_port,
            "timestamp": time.time()
        }
        return self._send_udp_request(payload)

    def unregister_from_tracker(self):
        """Desregistra do tracker"""
        logging.info("Desregistrando do tracker...")
        payload = {
            "command": "UNREGISTER",
            "peer_id": self.peer_id,
            "timestamp": time.time()
        }
        message = json.dumps(payload).encode('utf-8')
        try:
            self.udp_socket.sendto(message, (self.tracker_ip, self.tracker_port))
        except Exception as e:
            logging.debug(f"Erro ao enviar desregistro: {e}")

    def send_update_to_tracker(self):
        """Envia update para o tracker"""
        logging.debug("Enviando update para o tracker...")
        files_dict = self.file_manager.get_available_files()
        serializable_files = {file: list(chunks) for file, chunks in files_dict.items()}

        payload = {
            "command": "UPDATE",
            "peer_id": self.peer_id,
            "files": serializable_files,
            "timestamp": time.time()
        }
        self._send_udp_request(payload)

    # --- Métodos de consulta ---
    def get_known_peers(self):
        with self.peers_lock:
            return dict(self.known_peers)

    def find_peers_with_file(self, file_name):
        with self.peers_lock:
            return {
                peer_id: info for peer_id, info in self.known_peers.items()
                if file_name in info.get('files', {})
            }

    def find_peers_with_chunk(self, file_name, chunk_index):
        with self.peers_lock:
            return {
                peer_id: info for peer_id, info in self.known_peers.items()
                if chunk_index in info.get('files', {}).get(file_name, set())
            }

    # --- Servidor TCP (mantido do original) ---
    def _run_tcp_server(self):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server_socket:
                server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                server_socket.bind(('', self.own_port))
                server_socket.listen()
                logging.info(f"Servidor TCP escutando na porta {self.own_port}")

                while self.is_active:
                    try:
                        conn, addr = server_socket.accept()
                        if self.is_active:
                            self.tcp_executor.submit(self._handle_tcp_client, conn, addr)
                    except OSError:
                        if self.is_active:
                            logging.error("Erro no servidor TCP")
                        break
        except Exception as e:
            if self.is_active:
                logging.error(f"Erro fatal no servidor TCP: {e}")

    def _handle_tcp_client(self, conn, addr):
        """Manipula cliente TCP com timeout melhorado"""
        logging.debug(f"Nova conexão TCP de {addr}")
        try:
            with conn:
                conn.settimeout(30)
                header, _ = self._read_tcp_response(conn)
                if not header:
                    return

                command = header.get("command")
                handlers = {
                    "GET_CHUNK": self._handle_get_chunk,
                    "FILE_INFO": self._handle_file_info,
                }

                handler = handlers.get(command)
                if handler:
                    handler(conn, header)
                else:
                    self._send_tcp_error(conn, "UNKNOWN_COMMAND", "Comando desconhecido")

        except Exception as e:
            logging.error(f"Erro ao manipular cliente TCP {addr}: {e}")

    # --- Métodos TCP auxiliares ---
    def _send_tcp_request(self, sock, header, data=None):
        header_json = json.dumps(header).encode('utf-8')
        sock.sendall(struct.pack('>I', len(header_json)))
        sock.sendall(header_json)
        if data:
            sock.sendall(data)

    def _read_tcp_response(self, sock):
        header_size_data = sock.recv(4)
        if not header_size_data:
            return None, None
        header_size = struct.unpack('>I', header_size_data)[0]

        header_data = sock.recv(header_size)
        header = json.loads(header_data.decode('utf-8'))

        data = None
        if header.get("status") == "success" and "chunk_size" in header:
            chunk_size = header["chunk_size"]
            data = bytearray()
            while len(data) < chunk_size:
                packet = sock.recv(chunk_size - len(data))
                if not packet:
                    return header, None
                data.extend(packet)

        return header, data

    def _handle_get_chunk(self, sock, request):
        file_name, chunk_index = request["file_name"], request["chunk_index"]
        chunk_data = self.file_manager.load_chunk(file_name, chunk_index)
        if chunk_data:
            response_header = {"status": "success", "chunk_size": len(chunk_data)}
            self._send_tcp_request(sock, response_header, chunk_data)
        else:
            self._send_tcp_error(sock, "CHUNK_NOT_FOUND", "Chunk não encontrado")

    def _handle_file_info(self, sock, request):
        file_name = request["file_name"]
        metadata = self.file_manager.get_file_metadata(file_name)
        if metadata:
            response_header = {"status": "success", "metadata": metadata}
            self._send_tcp_request(sock, response_header)
        else:
            self._send_tcp_error(sock, "FILE_NOT_FOUND", f"Arquivo '{file_name}' não encontrado")

    def _send_tcp_error(self, sock, error_code, message):
        header = {"status": "error", "error_code": error_code, "message": message}
        self._send_tcp_request(sock, header)

    # --- Métodos de Cliente TCP ---
    def request_chunk(self, peer_addr, file_name, chunk_index):
        ip, port_str = peer_addr.split(':')
        try:
            with socket.create_connection((ip, int(port_str)), timeout=15) as sock:
                request_header = {"command": "GET_CHUNK", "file_name": file_name, "chunk_index": chunk_index}
                self._send_tcp_request(sock, request_header)

                resp_header, data = self._read_tcp_response(sock)
                if resp_header and resp_header["status"] == "success":
                    return data
                elif resp_header:
                    logging.warning(f"Erro do peer {peer_addr}: {resp_header['message']}")
        except Exception as e:
            logging.error(f"Falha ao requisitar chunk {chunk_index} de {peer_addr}: {e}")
        return None

    def request_file_info(self, peer_addr, file_name):
        ip, port_str = peer_addr.split(':')
        try:
            with socket.create_connection((ip, int(port_str)), timeout=10) as sock:
                request_header = {"command": "FILE_INFO", "file_name": file_name}
                self._send_tcp_request(sock, request_header)

                resp_header, _ = self._read_tcp_response(sock)
                if resp_header and resp_header["status"] == "success":
                    return resp_header.get("metadata")
        except Exception as e:
            logging.error(f"Falha ao requisitar info do arquivo de {peer_addr}: {e}")
        return None


class NetworkMetrics:
    """Classe para coletar métricas de rede"""

    def __init__(self):
        self.messages_sent = 0
        self.messages_received = 0
        self.failed_attempts = 0
        self.last_response_time = 0

    def record_message_sent(self):
        self.messages_sent += 1

    def record_response_received(self, response_time):
        self.messages_received += 1
        self.last_response_time = response_time

    def record_failed_attempt(self):
        self.failed_attempts += 1