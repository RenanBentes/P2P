# 🔧 Pré-requisitos

- **Java JDK 23.0.1** ou superior
- **Sistema operacional**: Windows, Linux ou macOS
- **Rede**: Conectividade UDP e TCP
- **Portas**: Certifique-se de que as portas não estejam bloqueadas por firewall

# 🚀 Como Usar

## Iniciando o Sistema

Este guia explica como compilar e executar o tracker e os peers.

## Passo 1: Iniciar o Tracker

O Tracker é o servidor central que gerencia os peers e deve ser iniciado primeiro.

1. Em um terminal, navegue até o diretório onde os arquivos do projeto foram compilados
2. Execute o seguinte comando:
   ```bash
   java Tracker
   ```
3. O Tracker será iniciado na porta padrão 6881
4. Anote o endereço IP da máquina onde o Tracker está rodando, pois você precisará dele para configurar os peers

## Passo 2: Configurar e Iniciar os Peers

Antes de iniciar um peer, você precisa configurar o nome do peer e o endereço do Tracker no código-fonte.

### Configuração do IP do Tracker

1. Abra o arquivo `Peer.java` em um editor de texto
2. Localize a linha dentro do método `main`:
   ```java
   String trackerIp = "10.20.180.128";
   ```
3. Altere o endereço IP para o da máquina onde o Tracker está sendo executado

### Configuração do Nome do Peer (Opcional)

Se estiver usando mais de um peer local:

1. Localize a linha dentro do método `main`:
   ```java
   String customName = "User";
   ```
2. Altere a string "User" para o nome que deseja usar neste peer

### Compilação e Execução

1. Após fazer as alterações, salve o arquivo e recompile o projeto
2. Quando um peer é iniciado, ele cria automaticamente uma pasta de compartilhamento (ex: `~/Downloads/P2P/User/`)
3. Coloque os arquivos que deseja compartilhar nesta pasta

## Passo 3: Interface de Comandos do Peer

Com o peer rodando, você pode usar os seguintes comandos no terminal:

| Comando e Aliases | Descrição |
|-------------------|-----------|
| `list` (ls, files) | Lista os arquivos locais (completos e parciais) e o progresso |
| `peers` (p) | Mostra a lista de outros peers conhecidos na rede e seus arquivos |
| `download <arquivo>` | Inicia o download de um arquivo da rede |
| `downloads` (dls) | Mostra o status dos downloads ativos e completados |
| `status` (info) | Exibe um resumo completo do status do peer |
| `refresh` (update) | Força uma atualização da lista de peers junto ao tracker |
| `tracker` | Mostra informações sobre a conexão com o tracker |
| `whoami` (me) | Mostra a identidade e endereço de rede do seu peer |
| `help` (h, ?) | Exibe a lista de todos os comandos disponíveis |
| `quit` (exit, q, bye) | Encerra o peer de forma segura |

# 📁 Estrutura do Projeto

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
