import hashlib
import logging
import time
import configparser
import threading
from pathlib import Path
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler


class FileManager:
    """
    Gerencia todas as operações de ficheiros no disco: dividir em chunks,
    guardar/carregar metadados, reconstruir ficheiros e monitorizar a pasta de partilha.
    """
    CHUNK_SIZE = 1024 * 1024  # 1MB
    METADATA_EXTENSION = ".meta"

    def __init__(self, peer_name):
        self.peer_name = peer_name
        self.shared_folder = Path.home() / "Downloads" / "P2P" / self.peer_name
        self.chunks_dir = self.shared_folder / "chunks"
        self.metadata_dir = self.shared_folder / "metadata"

        # Cria os diretórios necessários se não existirem
        self.shared_folder.mkdir(parents=True, exist_ok=True)
        self.chunks_dir.mkdir(exist_ok=True)
        self.metadata_dir.mkdir(exist_ok=True)

        # Dicionários em memória para o estado dos ficheiros
        self.local_files_metadata = {}
        self.available_chunks = {}
        self.lock = threading.Lock()  # Para proteger o acesso aos dicionários

        self._load_local_state()

        self.observer = Observer()
        self.network_manager = None

    def start_watching(self, network_manager):
        """Inicia o monitoramento da pasta de partilha para novas mudanças."""
        self.network_manager = network_manager
        event_handler = FileChangeHandler(self)
        self.observer.schedule(event_handler, str(self.shared_folder), recursive=False)
        self.observer.start()
        logging.info(f"A monitorizar a pasta: {self.shared_folder}")

    def stop_watching(self):
        """Para o monitoramento da pasta."""
        if self.observer.is_alive():
            self.observer.stop()
            self.observer.join()

    def _load_local_state(self):
        """Carrega metadados e chunks existentes no disco para a memória ao iniciar."""
        logging.info("A carregar estado local dos ficheiros...")
        with self.lock:
            for meta_file in self.metadata_dir.glob(f"*{self.METADATA_EXTENSION}"):
                self._load_metadata_file(meta_file)

            for file_name in self.local_files_metadata.keys():
                self._scan_chunks_for_file(file_name)

        logging.info(f"{len(self.local_files_metadata)} ficheiros conhecidos. A verificar novos ficheiros...")
        self.scan_shared_folder_for_new_files()

    def _load_metadata_file(self, meta_file_path):
        """Lê um ficheiro .meta e carrega as suas informações."""
        try:
            config = configparser.ConfigParser()
            config.read(meta_file_path)
            meta = config['metadata']
            file_name = meta['filename']
            self.local_files_metadata[file_name] = {
                'fileName': file_name,
                'fileSize': int(meta['filesize']),
                'fileHash': meta['filehash'],
                'totalChunks': int(meta['totalchunks']),
                'createdAt': int(meta.get('createdat', 0))
            }
        except Exception as e:
            logging.error(f"Erro ao carregar metadados de {meta_file_path.name}: {e}")

    def _scan_chunks_for_file(self, file_name):
        """Verifica quais chunks de um determinado ficheiro existem no disco."""
        self.available_chunks[file_name] = set()
        for chunk_file in self.chunks_dir.glob(f"{self._safe_filename(file_name)}.*.chunk"):
            try:
                # Extrai o índice do nome do ficheiro do chunk
                index = int(chunk_file.stem.split('.')[-1])
                self.available_chunks[file_name].add(index)
            except (ValueError, IndexError):
                continue

    def scan_shared_folder_for_new_files(self):
        """Verifica a pasta por ficheiros que ainda não foram processados."""
        for file_path in self.shared_folder.iterdir():
            # Ignora diretórios e ficheiros especiais/temporários
            if file_path.is_file() and not file_path.name.startswith('.') and not file_path.name.endswith(
                    ('.meta', '.chunk', '.part', '.tmp')):
                if file_path.name not in self.local_files_metadata:
                    self.process_new_file(file_path)

    def process_new_file(self, file_path: Path):
        """Processa um novo ficheiro, dividindo-o em chunks e guardando os metadados."""
        try:
            file_name = file_path.name
            file_size = file_path.stat().st_size
            if file_size == 0:
                logging.warning(f"A ignorar ficheiro vazio: {file_name}")
                return

            logging.info(f"A processar novo ficheiro: {file_name}")

            file_hash = self._calculate_hash(file_path)
            total_chunks = (file_size + self.CHUNK_SIZE - 1) // self.CHUNK_SIZE

            metadata = {
                'fileName': file_name,
                'fileSize': file_size,
                'fileHash': file_hash,
                'totalChunks': total_chunks,
                'createdAt': int(time.time() * 1000)
            }

            with self.lock:
                self.local_files_metadata[file_name] = metadata
                self.available_chunks[file_name] = set()

            self._save_metadata(file_name)

            # Cria e guarda os chunks
            with open(file_path, 'rb') as f:
                for i in range(total_chunks):
                    chunk_data = f.read(self.CHUNK_SIZE)
                    self.save_chunk(file_name, i, chunk_data)

            logging.info(f"Ficheiro '{file_name}' processado em {total_chunks} chunks.")
            # Notifica o tracker sobre o novo ficheiro
            if self.network_manager:
                self.network_manager.send_update_to_tracker()

        except Exception as e:
            logging.error(f"Erro ao processar o ficheiro {file_name}: {e}")

    def save_chunk(self, file_name, chunk_index, data):
        """Guarda um pedaço de ficheiro no disco."""
        chunk_path = self.chunks_dir / f"{self._safe_filename(file_name)}.{chunk_index}.chunk"
        chunk_path.write_bytes(data)
        with self.lock:
            if file_name not in self.available_chunks:
                self.available_chunks[file_name] = set()
            self.available_chunks[file_name].add(chunk_index)

    def load_chunk(self, file_name, chunk_index):
        """Carrega um pedaço de ficheiro do disco."""
        chunk_path = self.chunks_dir / f"{self._safe_filename(file_name)}.{chunk_index}.chunk"
        if chunk_path.exists():
            return chunk_path.read_bytes()
        return None

    def reconstruct_file(self, file_name):
        """Reconstrói um ficheiro completo a partir dos seus chunks."""
        if not self.has_complete_file(file_name):
            logging.warning(f"Tentativa de reconstruir ficheiro incompleto '{file_name}'")
            return False

        meta = self.get_file_metadata(file_name)
        output_path = self.shared_folder / file_name

        if output_path.exists() and output_path.stat().st_size == meta['fileSize']:
            logging.info(f"Ficheiro '{file_name}' já existe e está completo.")
            return True

        logging.info(f"A reconstruir ficheiro '{file_name}'...")
        try:
            temp_path = output_path.with_suffix('.part')
            with open(temp_path, 'wb') as f_out:
                for i in range(meta['totalChunks']):
                    chunk_data = self.load_chunk(file_name, i)
                    if chunk_data is None:
                        logging.error(f"Chunk {i} de '{file_name}' em falta para reconstrução!")
                        temp_path.unlink(missing_ok=True)
                        return False
                    f_out.write(chunk_data)

            reconstructed_hash = self._calculate_hash(temp_path)
            if reconstructed_hash == meta['fileHash']:
                temp_path.rename(output_path)
                logging.info(f"✅ Ficheiro '{file_name}' reconstruído com sucesso e verificado.")
                return True
            else:
                logging.error(f"❌ Falha na verificação de hash para '{file_name}'. Ficheiro corrompido.")
                temp_path.unlink(missing_ok=True)
                return False
        except Exception as e:
            logging.error(f"Erro ao reconstruir '{file_name}': {e}")
            return False

    # --- Métodos de API para outros componentes ---
    def get_available_files(self):
        with self.lock:
            return {name: set(chunks) for name, chunks in self.available_chunks.items()}

    def get_file_metadata(self, file_name):
        with self.lock:
            return self.local_files_metadata.get(file_name)

    def has_complete_file(self, file_name):
        meta = self.get_file_metadata(file_name)
        chunks = self.available_chunks.get(file_name)
        if not meta or not chunks:
            return False
        return len(chunks) == meta['totalChunks']

    def get_total_chunks_count(self):
        with self.lock:
            return sum(len(chunks) for chunks in self.available_chunks.values())

    def get_total_storage_used(self):
        return sum(f.stat().st_size for f in self.chunks_dir.glob('*.chunk') if f.is_file())

    def get_files_info_for_tracker(self):
        """Formata a informação de ficheiros para enviar ao tracker."""
        parts = []
        with self.lock:
            for file_name, chunks in self.available_chunks.items():
                chunk_indices_str = ",".join(map(str, sorted(list(chunks))))
                parts.append(f"{file_name},{chunk_indices_str}")
        return ";;".join(parts)

    # --- Métodos Utilitários ---
    def _safe_filename(self, filename):
        """Remove caracteres inválidos para nomes de ficheiro."""
        return "".join(c for c in filename if c.isalnum() or c in ('.', '_', '-')).rstrip()

    def _save_metadata(self, file_name):
        """Guarda os metadados de um ficheiro num ficheiro .meta."""
        meta = self.get_file_metadata(file_name)
        if not meta: return

        config = configparser.ConfigParser()
        config['metadata'] = {
            'filename': meta['fileName'],
            'filesize': str(meta['fileSize']),
            'filehash': meta['fileHash'],
            'totalchunks': str(meta['totalChunks']),
            'createdat': str(meta['createdAt'])
        }

        meta_path = self.metadata_dir / f"{self._safe_filename(file_name)}{self.METADATA_EXTENSION}"
        with open(meta_path, 'w') as f:
            config.write(f)

    def _calculate_hash(self, file_path):
        """Calcula o hash SHA-256 de um ficheiro."""
        sha256 = hashlib.sha256()
        with open(file_path, 'rb') as f:
            while chunk := f.read(8192 * 1024):  # Lê em blocos de 8MB para eficiência
                sha256.update(chunk)
        return sha256.hexdigest()


class FileChangeHandler(FileSystemEventHandler):
    """Handler para eventos do sistema de ficheiros na pasta de partilha."""

    def __init__(self, file_manager: FileManager):
        self.file_manager = file_manager

    def on_created(self, event):
        """Chamado quando um novo ficheiro é criado."""
        if not event.is_directory:
            file_path = Path(event.src_path)
            # Ignora ficheiros temporários ou especiais
            if file_path.name.startswith('.') or file_path.name.endswith(
                    ('.tmp', '.part', '.crdownload', '.meta', '.chunk')):
                return

            logging.info(f"Novo ficheiro detetado: {file_path.name}")
            # Espera um pouco para garantir que a escrita do ficheiro terminou
            time.sleep(2)
            self.file_manager.process_new_file(file_path)
