import threading
import logging
import time
import sys


class CommandInterface:
    """
    Fornece uma interface de linha de comando (CLI) rica e interativa para o utilizador,
    replicando a funcionalidade da versão Java.
    """

    def __init__(self, peer):
        self.peer = peer
        self.is_running = False
        self.thread = None

    def start(self):
        """Inicia o loop de comandos numa thread separada."""
        if self.is_running:
            return
        self.is_running = True
        self.thread = threading.Thread(target=self._run_loop, name=f"CommandInterface-{self.peer.name}")
        self.thread.daemon = True
        self.thread.start()
        logging.info("Interface de Comandos iniciada.")

    def stop(self):
        """Sinaliza para a thread do loop de comandos parar."""
        self.is_running = False
        print("\nA encerrar... Pressione Enter para sair.")

    def _run_loop(self):
        """O loop principal que lê e processa os comandos do utilizador."""
        time.sleep(1)  # Aguarda outros componentes iniciarem
        self._print_welcome()
        self._print_help()

        while self.is_running:
            try:
                prompt = self._build_prompt()
                cmd_line = input(prompt).strip()
                if not cmd_line:
                    continue

                parts = cmd_line.split(' ', 1)
                command = parts[0].lower()
                args = parts[1] if len(parts) > 1 else ""

                self._process_command(command, args)

            except (EOFError, KeyboardInterrupt):
                self._handle_quit()
                break
            except Exception as e:
                logging.error(f"Erro no processamento do comando: {e}", exc_info=True)

        logging.info("Loop de comandos encerrado.")

    def _process_command(self, command, args):
        """Mapeia o comando para o método handler correspondente."""
        commands = {
            'help': self._print_help, 'h': self._print_help, '?': self._print_help,
            'list': self._list_files, 'ls': self._list_files, 'files': self._list_files,
            'peers': self._list_peers, 'p': self._list_peers,
            'download': self._handle_download, 'dl': self._handle_download, 'get': self._handle_download,
            'status': self._show_status, 'info': self._show_status,
            'whoami': self._show_identity, 'me': self._show_identity,
            'refresh': self._handle_refresh, 'update': self._handle_refresh,
            'tracker': self._show_tracker_info, 't': self._show_tracker_info,
            'downloads': self._show_downloads, 'dls': self._show_downloads,
            'quit': self._handle_quit, 'exit': self._handle_quit, 'q': self._handle_quit, 'bye': self._handle_quit,
        }

        handler = commands.get(command)
        if handler:
            # Passa os argumentos apenas para os comandos que os esperam
            if command in ['download', 'dl', 'get']:
                handler(args)
            else:
                handler()
        else:
            print(f"❌ Comando desconhecido: '{command}'")
            print("💡 Digite 'help' para ver os comandos disponíveis.")

    def _print_welcome(self):
        print("\n" + "=" * 50)
        print("🚀 PEER P2P INICIADO COM SUCESSO!")
        print(f"📛 Nome: {self.peer.name}")
        print(f"🆔 ID: {self.peer.peer_id}")
        print(f"📁 Pasta: {self.peer.file_manager.shared_folder.resolve()}")
        print("=" * 50)

    def _build_prompt(self):
        """Constrói o prompt dinâmico, similar à versão Java."""
        status_icon = "🟢" if self.is_running else "🔴"
        tracker_icon = "🔗" if self.peer.network_manager.is_connected_to_tracker() else "❌"
        short_id = str(self.peer.port)
        return f"{status_icon}[{tracker_icon}{short_id}]{self.peer.name}> "

    def _print_help(self):
        print("\n" + "=" * 60)
        print("📋 COMANDOS DISPONÍVEIS")
        print("=" * 60)
        print("📁 ARQUIVOS:")
        print("  list (ls, files)    - Lista os arquivos locais")
        print("  download <arquivo> - Baixa um arquivo da rede")
        print("  downloads (dls)     - Mostra os downloads em andamento")
        print("\n🌐 REDE:")
        print("  peers (p)           - Lista os peers conhecidos na rede")
        print("  refresh (update)    - Força a atualização de informações do tracker")
        print("  tracker (t)         - Mostra informações sobre a ligação ao tracker")
        print("\nℹ️  INFORMAÇÕES:")
        print("  status (info)       - Mostra o estado geral do peer")
        print("  whoami (me)         - Mostra a identidade e endereço deste peer")
        print("\n🔧 SISTEMA:")
        print("  help (h, ?)         - Mostra este menu de ajuda")
        print("  quit (exit, q, bye) - Encerra o programa de forma segura")
        print("=" * 60 + "\n")

    def _list_files(self):
        files = self.peer.file_manager.get_available_files()
        if not files:
            print("📂 Nenhum arquivo disponível localmente.")
            print(f"💡 Coloque arquivos em: {self.peer.file_manager.shared_folder.resolve()}")
            return

        print("\n" + "=" * 70)
        print(f"📁 ARQUIVOS LOCAIS ({len(files)})")
        print("-" * 70)

        for file_name in sorted(files.keys()):
            metadata = self.peer.file_manager.get_file_metadata(file_name)
            if not metadata:
                continue

            chunks = files.get(file_name, set())
            is_complete = len(chunks) == metadata['totalChunks']

            status_icon = "✅" if is_complete else "📦"
            size_info = self._format_size(metadata['fileSize'])
            progress = (len(chunks) / metadata['totalChunks']) * 100 if metadata['totalChunks'] > 0 else 0

            print(
                f"{status_icon} {file_name:<30} ({size_info}) - {progress:>5.1f}% ({len(chunks)}/{metadata['totalChunks']} chunks)")
        print("=" * 70 + "\n")

    def _list_peers(self):
        peers = self.peer.network_manager.get_known_peers()
        if not peers:
            print("🌐 Nenhum peer conhecido.")
            print("💡 Use 'refresh' para atualizar a lista de peers do tracker.")
            return

        print("\n" + "=" * 60)
        print(f"🌐 PEERS CONHECIDOS ({len(peers)})")
        print("-" * 60)

        sorted_peers = sorted(peers.items(), key=lambda item: item[1]['last_seen'], reverse=True)

        for peer_id, info in sorted_peers:
            time_since_last_seen = time.time() - info['last_seen']
            time_ago = self._format_time_ago(time_since_last_seen)

            status_icon = "🟢" if time_since_last_seen < 60 else "🟡" if time_since_last_seen < 300 else "🔴"

            print(f"{status_icon} {peer_id} ({info['addr']}) - visto há {time_ago}")

            if not info.get('files'):
                print("     📂 Nenhum arquivo partilhado")
            else:
                for file_name, chunks in info['files'].items():
                    print(f"     📄 {file_name} ({len(chunks)} chunks)")
        print("=" * 60 + "\n")

    def _handle_download(self, file_name):
        if not file_name:
            print("❌ Uso: download <nome_do_arquivo>")
            return

        self.peer.download_manager.start_download(file_name)

    def _show_status(self):
        print("\n" + "=" * 50)
        print("📊 ESTADO DO PEER")
        print("-" * 50)
        self._show_identity()
        print(f"⚡ Estado do Peer: {'🟢 Ativo' if self.is_running else '🔴 Inativo'}")
        print(
            f"🔗 Estado do Tracker: {'🟢 Ligado' if self.peer.network_manager.is_connected_to_tracker() else '🔴 Desligado'}")
        print(f"🌐 Peers conhecidos: {len(self.peer.network_manager.get_known_peers())}")
        print(f"⬇️  Downloads ativos: {self.peer.download_manager.get_active_downloads_count()}")
        print(f"📦 Total de chunks locais: {self.peer.file_manager.get_total_chunks_count()}")
        print(f"💾 Armazenamento usado: {self._format_size(self.peer.file_manager.get_total_storage_used())}")
        print("=" * 50 + "\n")

    def _show_identity(self):
        print(f"📛 Nome: {self.peer.name}")
        print(f"🆔 ID: {self.peer.peer_id}")
        print(f"🔌 Porta: {self.peer.port}")

    def _handle_refresh(self):
        print("🔄 A atualizar informações do tracker...")
        self.peer.network_manager.send_update_to_tracker()
        print("✅ Pedido de atualização enviado!")

    def _show_tracker_info(self):
        nm = self.peer.network_manager
        print("\n" + "=" * 50)
        print("📡 INFORMAÇÕES DO TRACKER")
        print("-" * 50)
        print(f"🌐 Endereço: {nm.tracker_ip}:{nm.tracker_port}")
        print(f"🔗 Estado: {'🟢 Ligado' if nm.is_connected_to_tracker() else '🔴 Desligado'}")
        print("=" * 50 + "\n")

    def _show_downloads(self):
        dm = self.peer.download_manager
        active = dm.get_active_downloads_count()
        completed = dm.get_completed_downloads_count()
        print("\n" + "=" * 50)
        print("⬇️  DOWNLOADS")
        print("-" * 50)
        print(f"🟢 Ativos: {active}")
        print(f"✅ Completados: {completed}")
        print("=" * 50 + "\n")

    def _handle_quit(self):
        print(f"\n👋 A encerrar {self.peer.name}...")
        self.stop()
        self.peer.shutdown()
        # A terminação do programa é gerida pela thread principal
        # sys.exit(0) seria abrupto aqui.

    # --- Métodos Utilitários ---
    def _format_size(self, size_bytes):
        if size_bytes < 1024:
            return f"{size_bytes} B"
        elif size_bytes < 1024 ** 2:
            return f"{size_bytes / 1024:.1f} KB"
        elif size_bytes < 1024 ** 3:
            return f"{size_bytes / 1024 ** 2:.1f} MB"
        else:
            return f"{size_bytes / 1024 ** 3:.1f} GB"

    def _format_time_ago(self, seconds_ago):
        if seconds_ago < 60:
            return f"{int(seconds_ago)}s"
        elif seconds_ago < 3600:
            return f"{int(seconds_ago / 60)}m"
        elif seconds_ago < 86400:
            return f"{int(seconds_ago / 3600)}h"
        else:
            return f"{int(seconds_ago / 86400)}d"
