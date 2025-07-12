import logging
import random
import time
import sys
import socket

from FileManager import FileManager
from NetworkManager import NetworkManager
from DownloadManager import DownloadManager
from CommandInterface import CommandInterface

# --- CONFIGURAÇÃO ---
# Mude este IP para o endereço da máquina onde o Tracker está a ser executado.

TRACKER_IP = "192.168.12.38"
TRACKER_PORT = 6881

class Peer:
    """
    A classe principal que orquestra todos os componentes de um peer na rede P2P.
    """

    def __init__(self, name, port, tracker_addr):
        self.name = name
        self.port = port
        self.tracker_addr = tracker_addr
        self.is_running = True

        # Gera um ID único para o peer usando o IP local e a porta
        try:
            host_ip = socket.gethostbyname(socket.gethostname())
        except socket.gaierror:
            host_ip = "127.0.0.1"
        self.peer_id = f"Peer_{host_ip}:{self.port}"

        logging.info(f"A iniciar Peer '{self.name}' com ID '{self.peer_id}'")

        # Inicializa todos os componentes gestores na ordem correta
        self.file_manager = FileManager(self.name)
        self.network_manager = NetworkManager(self.peer_id, self.port, self.tracker_addr, self.file_manager)
        self.download_manager = DownloadManager(self.peer_id, self.file_manager, self.network_manager)
        self.command_interface = CommandInterface(self)

    def start(self):
        """Inicia todos os serviços do peer em ordem."""
        self.network_manager.start()
        self.file_manager.start_watching(self.network_manager)
        self.command_interface.start()

        logging.info("Peer iniciado com sucesso! Pronto para receber comandos.")

    def shutdown(self):
        """Encerra todos os serviços do peer de forma segura e na ordem inversa."""
        if not self.is_running:
            return

        logging.info("A encerrar o peer...")
        self.is_running = False

        # Para os componentes na ordem inversa do arranque
        self.command_interface.stop()
        self.file_manager.stop_watching()
        self.network_manager.stop()

        logging.info("Peer encerrado com sucesso.")


if __name__ == "__main__":
    # Configuração do Logging para uma saída clara e informativa
    logging.basicConfig(
        level=logging.INFO,
        format='[%(asctime)s] [%(levelname)s] [%(threadName)s] %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )

    # Nome do utilizador
    peer_name = f"User"
    peer_port = random.randint(1000, 9999)
    tracker_address = (TRACKER_IP, TRACKER_PORT)

    peer = Peer(name=peer_name, port=peer_port, tracker_addr=tracker_address)

    try:
        peer.start()
        # Mantém a thread principal viva enquanto o peer estiver a correr.
        while peer.is_running:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nSinal de encerramento recebido (Ctrl+C)...")
    finally:
        peer.shutdown()