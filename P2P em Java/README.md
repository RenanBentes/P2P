
## 🔧 Pré-requisitos

- **Java JDK 23.0.1** ou superior
- **Sistema operacional**: Windows, Linux ou macOS
- **Rede**: Conectividade UDP e TCP
- **Portas**: Certifique-se de que as portas não estejam bloqueadas por firewall

## 🚀 Como Usar

### Iniciando o Sistema

1. **Inicie o Tracker** primeiro:
   ```bash
   cd out
   java Tracker
   ```
   O tracker ficará escutando na porta padrão

2. **Inicie os Peers** (em terminais separados):
   ```bash
   cd out
   java Peer
   ```
   Cada peer se registrará automaticamente no tracker

## 📁 Estrutura do Projeto

```
P2P em Java/
├── Peer/
│   ├── src/
│   │   ├── CommandInterface.java     # Interface de comandos do peer
│   │   ├── DownloadManager.java      # Gerenciamento de downloads
│   │   ├── FileManager.java          # Gerenciamento de arquivos
│   │   ├── Peer.java                 # Cliente peer principal
│   │   ├── TCPClient.java            # Cliente TCP para transferências
│   │   ├── TCPServer.java            # Servidor TCP do peer
│   │   └── TrackerClient.java        # Cliente para comunicação com tracker
│   └── Peer.iml                      # Arquivo de configuração IntelliJ
├── Tracker/
│   ├── src/
│   │   ├── MethodsManager.java       # Gerenciamento de métodos do tracker
│   │   └── Tracker.java              # Servidor tracker principal
│   └── Tracker.iml                   # Arquivo de configuração IntelliJ
├── .idea/                            # Configurações do IntelliJ IDEA
├── out/                              # Arquivos compilados

```
