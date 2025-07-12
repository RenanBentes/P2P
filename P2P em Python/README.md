## 🔧 Pré-requisitos

### Software
- **Python 3.8** ou superior
- **Sistema Operacional**: Windows, Linux ou macOS

### Conectividade de Rede
- **UDP**: Comunicação entre tracker e peers
- **TCP**: Comunicação direta entre peers
- **Portas**: 
  - Tracker: 6881 (padrão)
  - Peers: 9000-9999 (faixa dinâmica)

⚠️ **Importante**: Certifique-se de que as portas não estão bloqueadas por firewall.

## 📦 Instalação

1. **Clone ou descarregue o projeto**
2. **Instale as dependências necessárias**:
   ```bash
   pip install watchdog
   ```

## ⚙️ Configuração

### Configurar o Endereço do Tracker

**Antes de iniciar o sistema**, é necessário configurar o endereço IP do tracker:

1. Abra o ficheiro `Peer/Peer.py` num editor de texto
2. Localize a linha de configuração:
   ```python
   # Mude este IP para o endereço da máquina onde o Tracker está a ser executado
   TRACKER_IP = "192.168.12.38"
   ```
3. Altere o endereço IP:
   - **Mesma máquina**: `"127.0.0.1"`
   - **Máquina diferente**: IP da máquina onde executa o Tracker

## 🚀 Como Usar

### 1. Iniciar o Tracker

```bash
cd Tracker/
python Tracker.py
```

✅ O tracker ficará a escutar na porta 6881

### 2. Iniciar os Peers

**Terminal separado para cada peer:**

```bash
cd Peer/
python Peer.py
```

**Opções de inicialização:**
- **Porta automática**: `python Peer.py` (escolhe porta entre 9000-9999)
- **Porta específica**: `python Peer.py 9001`

### 3. Partilhar Ficheiros

Cada peer cria automaticamente uma pasta de partilha:
```
~/Downloads/P2P/User-<porta>/
```

**Para partilhar ficheiros**: Coloque os ficheiros nesta pasta.

## 💻 Comandos Disponíveis

| Comando | Aliases | Descrição |
|---------|---------|-----------|
| `list` | `ls`, `files` | Lista ficheiros locais e progresso de downloads |
| `peers` | `p` | Mostra outros peers conhecidos na rede |
| `download <ficheiro>` | - | Descarrega um ficheiro da rede |
| `downloads` | `dls` | Mostra estado dos downloads ativos e completados |
| `status` | `info` | Exibe resumo completo do estado do peer |
| `refresh` | `update` | Força atualização da lista de peers do tracker |
| `tracker` | - | Mostra informações sobre ligação ao tracker |
| `whoami` | `me` | Mostra identidade e endereço deste peer |
| `help` | `h`, `?` | Exibe lista de todos os comandos disponíveis |
| `quit` | `exit`, `q`, `bye` | Encerra o peer de forma segura |

### Exemplos de Uso

```bash
# Listar ficheiros disponíveis
> list

# Descarregar um ficheiro
> download exemplo.txt

# Ver estado dos downloads
> downloads

# Ver peers conectados
> peers

# Obter informações completas
> status
```

## 📁 Estrutura do Projeto

```
P2P em Python/
├── Peer/
│   ├── CommandInterface.py    # Interface de comandos do peer
│   ├── DownloadManager.py     # Gestão de downloads
│   ├── FileManager.py         # Gestão de ficheiros e chunks
│   ├── NetworkManager.py      # Gestão da comunicação de rede (UDP/TCP)
│   └── Peer.py                # Cliente peer principal
└── Tracker/
    └── Tracker.py             # Servidor tracker principal
```

## 🏗️ Arquitetura

### Componentes Principais

**Tracker (Servidor Central)**
- Regista e mantém lista de peers ativos
- Facilita descoberta de peers
- Comunica via UDP na porta 6881

**Peer (Cliente)**
- Partilha e descarrega ficheiros
- Comunica com tracker via UDP
- Transfere ficheiros diretamente com outros peers via TCP
- Monitoriza pasta de partilha em tempo real

### Fluxo de Comunicação

1. **Peer** regista-se no **Tracker** (UDP)
2. **Peer** solicita lista de outros peers (UDP)
3. **Peer** comunica diretamente com outros peers para transferir ficheiros (TCP)
