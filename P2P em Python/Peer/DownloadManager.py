import logging
import time
import random
import threading
from concurrent.futures import ThreadPoolExecutor, Future


class DownloadManager:
    """
    Orquestra o processo de download de ficheiros da rede P2P,
    gerindo tarefas, selecionando peers e reconstruindo ficheiros.
    """
    MAX_CONCURRENT_DOWNLOADS = 3
    MAX_CHUNK_RETRY_ATTEMPTS = 3
    CHUNK_DOWNLOADER_THREADS = 5  # Threads para descarregar chunks de um *único* ficheiro

    def __init__(self, peer_id, file_manager, network_manager):
        self.peer_id = peer_id
        self.file_manager = file_manager
        self.network_manager = network_manager

        self.download_executor = ThreadPoolExecutor(
            max_workers=self.MAX_CONCURRENT_DOWNLOADS,
            thread_name_prefix='DownloadTask'
        )
        self.active_downloads = {}  # {file_name: Future}
        self.completed_downloads_count = 0
        self.lock = threading.Lock()

    def start_download(self, file_name):
        """
        Ponto de entrada público para iniciar o download de um ficheiro.
        É chamado pela CommandInterface.
        """
        with self.lock:
            if file_name in self.active_downloads:
                logging.warning(f"Download de '{file_name}' já está em andamento.")
                return

            if self.file_manager.has_complete_file(file_name):
                logging.info(f"Ficheiro '{file_name}' já existe localmente e está completo.")
                # Tenta reconstruir caso o ficheiro final tenha sido apagado
                self.file_manager.reconstruct_file(file_name)
                return

        peers_with_file = self.network_manager.find_peers_with_file(file_name)
        if not peers_with_file:
            logging.error(f"Nenhum peer encontrado com o ficheiro '{file_name}'.")
            return

        logging.info(f"A iniciar tarefa de download para '{file_name}' a partir de {len(peers_with_file)} peers.")
        task_future = self.download_executor.submit(self._download_task, file_name, peers_with_file)
        with self.lock:
            self.active_downloads[file_name] = task_future

    def _download_task(self, file_name, peers_info):
        """
        A tarefa de download que executa em background. Gere o ciclo de vida completo
        de um download de ficheiro.
        """
        try:
            # 1. Descobrir metadados do ficheiro a partir dos peers
            metadata = self._discover_file_metadata(file_name, peers_info)
            if not metadata:
                logging.error(f"Não foi possível obter metadados para '{file_name}'. A abortar.")
                return

            # Guarda os metadados descobertos para referência futura
            with self.file_manager.lock:
                self.file_manager.local_files_metadata[file_name] = metadata
            self.file_manager._save_metadata(file_name)

            # 2. Determinar quais chunks são necessários
            local_chunks = self.file_manager.available_chunks.get(file_name, set())
            needed_chunks = set(range(metadata['totalChunks'])) - local_chunks

            if not needed_chunks:
                logging.info(f"Todos os chunks para '{file_name}' já estão presentes localmente.")
            else:
                logging.info(f"A iniciar download de {len(needed_chunks)} chunks para '{file_name}'.")
                # 3. Descarregar todos os chunks necessários
                self._fetch_all_needed_chunks(file_name, needed_chunks)

            # 4. Verificar e Reconstruir
            if self.file_manager.has_complete_file(file_name):
                if self.file_manager.reconstruct_file(file_name):
                    with self.lock:
                        self.completed_downloads_count += 1
                # Se a reconstrução falhar, o erro já foi logado pelo FileManager
            else:
                logging.warning(f"Download de '{file_name}' terminou incompleto.")

        except Exception as e:
            logging.error(f"Erro crítico na tarefa de download de '{file_name}': {e}", exc_info=True)
        finally:
            with self.lock:
                if file_name in self.active_downloads:
                    del self.active_downloads[file_name]

    def _discover_file_metadata(self, file_name, peers_info):
        """Consulta peers para obter os metadados de um ficheiro."""
        # Tenta obter informações de vários peers para garantir consistência
        for peer_id in random.sample(list(peers_info.keys()), k=min(3, len(peers_info))):
            peer_addr = peers_info[peer_id]['addr']
            metadata = self.network_manager.request_file_info(peer_addr, file_name)
            if metadata:
                logging.info(f"Metadados para '{file_name}' obtidos de {peer_id}.")
                return metadata
        return None

    def _fetch_all_needed_chunks(self, file_name, needed_chunks):
        """Orquestra o download de um conjunto de chunks usando um pool de threads."""
        with ThreadPoolExecutor(max_workers=self.CHUNK_DOWNLOADER_THREADS,
                                thread_name_prefix=f"ChunkDownloader_{file_name[:10]}") as executor:
            while needed_chunks:
                # A cada iteração, recalcula a raridade dos chunks restantes
                sorted_chunks_to_download = self._get_rarest_chunks_first(needed_chunks, file_name)

                # Submete tarefas para descarregar os chunks mais raros em paralelo
                futures = {
                    executor.submit(self._fetch_one_chunk, file_name, chunk_index): chunk_index
                    for chunk_index in sorted_chunks_to_download[:self.CHUNK_DOWNLOADER_THREADS]
                }

                for future in futures:
                    chunk_index = futures[future]
                    was_successful = future.result()
                    if was_successful:
                        needed_chunks.remove(chunk_index)

    def _fetch_one_chunk(self, file_name, chunk_index):
        """Tenta descarregar um único chunk de um peer disponível."""
        for attempt in range(self.MAX_CHUNK_RETRY_ATTEMPTS):
            peers_with_chunk = self.network_manager.find_peers_with_chunk(file_name, chunk_index)
            if not peers_with_chunk:
                logging.warning(f"Nenhum peer encontrado para o chunk {chunk_index} de '{file_name}'. A aguardar...")
                time.sleep(5)
                continue

            # Tenta um peer aleatório que tenha o chunk
            target_peer_id = random.choice(list(peers_with_chunk.keys()))
            target_peer_addr = peers_with_chunk[target_peer_id]['addr']

            logging.debug(f"A tentar descarregar chunk {chunk_index} de {target_peer_id} (tentativa {attempt + 1})")
            chunk_data = self.network_manager.request_chunk(target_peer_addr, file_name, chunk_index)

            if chunk_data:
                self.file_manager.save_chunk(file_name, chunk_index, chunk_data)
                logging.info(f"✓ Chunk {chunk_index} de '{file_name}' descarregado com sucesso.")
                return True

        logging.error(
            f"❌ Falha ao descarregar chunk {chunk_index} de '{file_name}' após {self.MAX_CHUNK_RETRY_ATTEMPTS} tentativas.")
        return False

    def _get_rarest_chunks_first(self, needed_chunks, file_name):
        """Calcula a raridade dos chunks e ordena-os do mais raro para o mais comum."""
        chunk_frequency = {chunk_idx: 0 for chunk_idx in needed_chunks}

        all_peers = self.network_manager.get_known_peers()
        for peer_info in all_peers.values():
            peer_files = peer_info.get('files', {})
            if file_name in peer_files:
                for chunk_index in peer_files[file_name]:
                    if chunk_index in chunk_frequency:
                        chunk_frequency[chunk_index] += 1

        # Ordena os chunks pela sua frequência (raridade)
        sorted_chunks = sorted(needed_chunks, key=lambda idx: chunk_frequency.get(idx, 0))
        return sorted_chunks

    def get_active_downloads_count(self):
        """Retorna o número de downloads atualmente ativos."""
        with self.lock:
            return len(self.active_downloads)

    def get_completed_downloads_count(self):
        """Retorna o número de downloads completados desde o início."""
        with self.lock:
            return self.completed_downloads_count
