import socket
import threading
import time
import logging
import json
from concurrent.futures import ThreadPoolExecutor

class Tracker:
    """ Servidor Tracker que gere uma lista de peers numa rede P2P.
    Comunica-se usando um protocolo UDP baseado em JSON. """

    TRACKER_PORT = 6881
    PEER_TIMEOUT_MINUTES = 2
    CLEANUP_INTERVAL_SECONDS = 30
    MAX_PACKET_SIZE = 65535
    THREAD_POOL_SIZE = 10

    def __init__(self):
        # Dicionário para armazenar informações dos peers.
        # Estrutura: { peer_id: {'files': {...}, 'last_seen': timestamp, 'addr': 'ip:port'} }
        self.peers = {}
        self.peers_lock = threading.Lock()
        self.executor = ThreadPoolExecutor(max_workers=self.THREAD_POOL_SIZE, thread_name_prefix='Handler')
        self.is_active = True

    def start(self):
        """Inicia o servidor Tracker e o processo de limpeza de peers inativos."""
        logging.info(f"Tracker a iniciar na porta {self.TRACKER_PORT}")

        # Inicia a thread de limpeza de peers inativos
        cleanup_thread = threading.Thread(target=self._remove_timeout_peers_loop, daemon=True, name="CleanupThread")
        cleanup_thread.start()

        # Inicia o servidor UDP
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.bind(('', self.TRACKER_PORT))
            logging.info("Tracker online. A aguardar requisições...")

            while self.is_active:
                try:
                    data, addr = sock.recvfrom(self.MAX_PACKET_SIZE)
                    # Delega o processamento da requisição para uma thread do pool
                    self.executor.submit(self._handle_request, sock, data, addr)
                except Exception as e:
                    if self.is_active:
                        logging.error(f"Erro no loop principal do socket: {e}")

    def _handle_request(self, sock, data, response_addr):
        """Processa uma requisição JSON recebida de um peer."""
        try:
            payload = json.loads(data.decode('utf-8'))
            command = payload.get("command")
            peer_id = payload.get("peer_id")

            if not command or not peer_id:
                logging.warning(f"Requisição JSON inválida de {response_addr}: 'command' ou 'peer_id' em falta.")
                return

            logging.debug(f"Recebeu '{command}' de {peer_id} @ {response_addr}")

            peer_ip = response_addr[0]
            peer_tcp_port = payload.get("port")

            handlers = {
                "REGISTER": self._handle_register,
                "UPDATE": self._handle_update,
                "UNREGISTER": self._handle_unregister,
                "HEARTBEAT": self._handle_heartbeat,
            }

            handler = handlers.get(command)
            if handler:
                handler(sock, payload, response_addr, peer_ip, peer_tcp_port)
            else:
                logging.warning(f"Comando desconhecido '{command}' de {peer_id}")
                self._send_error_response(sock, response_addr, "UNKNOWN_COMMAND", "Comando não reconhecido.")

        except json.JSONDecodeError:
            logging.warning(f"Recebida mensagem não-JSON de {response_addr}")
        except Exception as e:
            logging.error(f"Erro ao processar requisição de {response_addr}: {e}", exc_info=True)
            self._send_error_response(sock, response_addr, "PROCESSING_ERROR", "Erro interno no tracker.")

    # --- Lógica de Manipulação de Peers ---

    def _handle_register(self, sock, payload, response_addr, peer_ip, peer_tcp_port):
        peer_id = payload["peer_id"]
        if not peer_tcp_port:
            logging.warning(f"Pedido de registo de {peer_id} sem porta TCP.")
            return

        public_addr_str = f"{peer_ip}:{peer_tcp_port}"
        with self.peers_lock:
            self.peers[peer_id] = {
                'files': {},
                'last_seen': time.time(),
                'addr': public_addr_str
            }
        logging.info(f"Peer registado: {peer_id} em {public_addr_str}. Total de peers: {len(self.peers)}")
        self._send_peers_list(sock, response_addr, peer_id)

    def _handle_update(self, sock, payload, response_addr, peer_ip, peer_tcp_port):
        peer_id = payload["peer_id"]
        files = payload.get("files", {})
        public_addr_str = f"{peer_ip}:{peer_tcp_port}"

        with self.peers_lock:
            if peer_id in self.peers:
                self.peers[peer_id]['files'] = files
                self.peers[peer_id]['last_seen'] = time.time()
                logging.debug(f"Peer atualizado: {peer_id} com {len(files)} ficheiros.")
            else:
                self.peers[peer_id] = {
                    'files': files,
                    'last_seen': time.time(),
                    'addr': public_addr_str
                }
                logging.info(f"Peer não registado enviou update. Registado agora: {peer_id}")
        self._send_peers_list(sock, response_addr, peer_id)

    def _handle_unregister(self, sock, payload, response_addr, peer_ip, peer_tcp_port):
        peer_id = payload["peer_id"]
        with self.peers_lock:
            if peer_id in self.peers:
                del self.peers[peer_id]
                logging.info(f"Peer desregistado: {peer_id}. Total de peers: {len(self.peers)}")
        self._send_ack(sock, response_addr)

    def _handle_heartbeat(self, sock, payload, response_addr, peer_ip, peer_tcp_port):
        """Processa um heartbeat, atualizando o last_seen do peer."""
        peer_id = payload["peer_id"]
        with self.peers_lock:
            if peer_id in self.peers:
                self.peers[peer_id]['last_seen'] = time.time()
                logging.debug(f"Heartbeat recebido de {peer_id}")
            else:
                logging.warning(f"Heartbeat de peer desconhecido: {peer_id}. A registar...")
                # Se um peer envia um heartbeat sem estar registado, é sensato registá-lo
                # assumindo que ele enviou a sua porta TCP.
                if peer_tcp_port:
                    self._handle_register(sock, payload, response_addr, peer_ip, peer_tcp_port)
                    return  # A resposta já é enviada pelo _handle_register
                else:
                    logging.warning(f"Heartbeat de peer desconhecido ({peer_id}) sem porta. A ignorar.")

        self._send_ack(sock, response_addr)

    # --- Lógica de Respostas JSON ---

    def _send_peers_list(self, sock, response_addr, requester_id):
        """Serializa e envia a lista de peers (em JSON) para o solicitante."""
        with self.peers_lock:
            peers_to_send = {pid: info for pid, info in self.peers.items() if pid != requester_id}

        response = {"status": "success", "peers": peers_to_send}
        self._send_response(sock, response_addr, response)
        logging.debug(f"Enviada lista com {len(peers_to_send)} peers para {requester_id}")

    def _send_ack(self, sock, response_addr):
        response = {"status": "success", "message": "ACK"}
        self._send_response(sock, response_addr, response)

    def _send_error_response(self, sock, response_addr, error_code, message):
        response = {"status": "error", "error_code": error_code, "message": message}
        self._send_response(sock, response_addr, response)

    def _send_response(self, sock, response_addr, payload):
        try:
            message = json.dumps(payload).encode('utf-8')
            sock.sendto(message, response_addr)
        except Exception as e:
            logging.error(f"Falha ao enviar resposta para {response_addr}: {e}")

    # --- Tarefas de Manutenção ---

    def _remove_timeout_peers_loop(self):
        """Loop que remove periodicamente peers inativos."""
        while self.is_active:
            time.sleep(self.CLEANUP_INTERVAL_SECONDS)
            with self.peers_lock:
                timeout_threshold = self.PEER_TIMEOUT_MINUTES * 60
                now = time.time()
                to_remove = [
                    peer_id for peer_id, info in self.peers.items()
                    if now - info['last_seen'] > timeout_threshold
                ]
                for peer_id in to_remove:
                    del self.peers[peer_id]
                    logging.info(f"Removido por timeout: {peer_id}")
            if to_remove:
                logging.info(f"Peers ativos após limpeza: {len(self.peers)}")

    def shutdown(self):
        """Encerra o tracker de forma graciosa."""
        logging.info("A encerrar o tracker...")
        self.is_active = False
        self.executor.shutdown(wait=True, cancel_futures=True)

if __name__ == "__main__":
    # Configuração do Logging movida para dentro do if __name__ para ser mais seguro
    logging.basicConfig(
        level=logging.INFO,
        format='[%(asctime)s] [%(levelname)s] %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )

    tracker = Tracker()
    try:
        tracker.start()
    except KeyboardInterrupt:
        logging.info("Sinal de encerramento recebido.")
    finally:
        tracker.shutdown()
