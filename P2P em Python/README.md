# P2P em Python

Este é um sistema de partilha de ficheiros peer-to-peer (P2P) desenvolvido em Python. O sistema utiliza um tracker central para a descoberta de peers e comunicação direta via TCP para a transferência de ficheiros.

## 🔧 Pré-requisitos

- **Python 3.8** ou superior.
- **Dependências**: Instale a biblioteca `watchdog` para o monitoramento de pastas em tempo real.
  ```bash
  pip install watchdog

Sistema Operacional: Windows, Linux ou macOS.

Rede: Conectividade UDP e TCP entre o tracker e os peers.

Portas: Certifique-se de que as portas utilizadas (padrão: 6881 para o tracker, 9000-9999 para os peers) não estejam bloqueadas por uma firewall.

🚀 Como Usar
Configurando o Sistema
Antes de iniciar, é crucial configurar o endereço do Tracker no cliente Peer.

Abra o ficheiro Peer/Peer.py num editor de texto.

Localize a linha de configuração do IP:

# Mude este IP para o endereço da máquina onde o Tracker está a ser executado.
TRACKER_IP = "192.168.12.38"

Altere o endereço IP para o IP da máquina onde irá executar o Tracker.py. Se for na mesma máquina, pode usar "127.0.0.1".

Iniciando o Sistema
Inicie o Tracker primeiro. Navegue até à pasta Tracker e execute:

cd Tracker/
python Tracker.py

O tracker ficará a escutar na porta padrão 6881.

Inicie os Peers (em terminais separados). Navegue até à pasta Peer e execute:

cd Peer/
python Peer.py

O peer iniciará numa porta aleatória entre 9000 e 9999.

Para especificar uma porta, passe-a como argumento: python Peer.py 9001

Cada peer criará automaticamente uma pasta de partilha em ~/Downloads/P2P/User-<porta>/. Coloque os ficheiros que deseja partilhar nesta pasta.

💻 Comandos do Peer
Uma vez que o peer esteja em execução, pode usar os seguintes comandos na consola:

Comando

Descrição

list (ls, files)

Lista os ficheiros locais e o progresso do download.

peers (p)

Mostra os outros peers conhecidos na rede.

download <ficheiro>

Descarrega um ficheiro da rede.

downloads (dls)

Mostra o estado dos downloads ativos e completados.

status (info)

Exibe um resumo completo do estado do peer.

refresh (update)

Força uma atualização da lista de peers do tracker.

tracker

Mostra informações sobre a ligação ao tracker.

whoami (me)

Mostra a identidade e endereço deste peer.

help (h, ?)

Exibe a lista de todos os comandos disponíveis.

quit (exit, q, bye)

Encerra o peer de forma segura.

📁 Estrutura do Projeto
P2P em Python/
├── Peer/
│   ├── CommandInterface.py    # Interface de comandos do peer
│   ├── DownloadManager.py     # Gestão de downloads
│   ├── FileManager.py         # Gestão de ficheiros e chunks
│   ├── NetworkManager.py      # Gestão de toda a comunicação de rede (UDP/TCP)
│   └── Peer.py                # Cliente peer principal que orquestra os componentes
└── Tracker/
    └── Tracker.py             # Servidor tracker principal

