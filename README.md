# 🌐 Sistema P2P de Compartilhamento de Arquivos

Um sistema completo de compartilhamento de arquivos peer-to-peer (P2P) com tracker centralizado, implementado em Java. O sistema permite que múltiplos peers compartilhem arquivos de forma distribuída e eficiente.

## 📋 Índice

- [Visão Geral](#-visão-geral)
- [Funcionalidades](#-funcionalidades)
- [Protocolos de Comunicação](#-protocolos-de-comunicação)

## 🎯 Visão Geral

Este projeto implementa uma rede P2P híbrida onde um **tracker central** coordena a descoberta de peers, mas a transferência de arquivos ocorre diretamente entre os peers. O sistema é projetado para ser eficiente, escalável e robusto.

### Principais Características

- **Descoberta automática de peers** através do tracker central
- **Transferência direta** de arquivos entre peers
- **Monitoramento em tempo real** de arquivos compartilhados
- **Sistema de chunks** para transferência eficiente de arquivos grandes
- **Detecção automática** de peers inativos
- **Interface de linha de comando** intuitiva

## 🏗️ Arquitetura

```mermaid
graph TB
    T[Tracker Central<br/>Coordenação e Descoberta] 
    
    P1[Peer 1<br/>Cliente P2P]
    P2[Peer 2<br/>Cliente P2P] 
    P3[Peer 3<br/>Cliente P2P]
    P4[Peer N<br/>Cliente P2P]
    
    T -.->|UDP<br/>Registro/Heartbeat| P1
    T -.->|UDP<br/>Registro/Heartbeat| P2
    T -.->|UDP<br/>Registro/Heartbeat| P3
    T -.->|UDP<br/>Registro/Heartbeat| P4
    
    P1 -->|TCP<br/>Transferência| P2
    P1 -->|TCP<br/>Transferência| P3
    P2 -->|TCP<br/>Transferência| P4
    P3 -->|TCP<br/>Transferência| P4
    
    style T fill:#fffff
    style P1 fill:#fffff
    style P2 fill:#fffff
    style P3 fill:#fffff
    style P4 fill:#fffff
```

## ✨ Funcionalidades

### 🛰️ Tracker Central
- ✅ **Registro dinâmico** de peers na rede
- ✅ **Atualização em tempo real** da lista de arquivos disponíveis
- ✅ **Remoção automática** de peers inativos (timeout)
- ✅ **Distribuição da lista** de peers ativos
- ✅ **Comandos suportados**: `REGISTER`, `UPDATE`, `UNREGISTER`, `HEARTBEAT`

### 💻 Peer (Cliente P2P)
- ✅ **Registro automático** no tracker ao iniciar
- ✅ **Monitoramento contínuo** da pasta compartilhada
- ✅ **Interface de comandos** interativa e intuitiva
- ✅ **Download paralelo** de arquivos de múltiplos peers
- ✅ **Sistema de chunks** para arquivos grandes
- ✅ **Heartbeat periódico** para manter conexão ativa
- ✅ **Comunicação TCP** otimizada para transferências

## 📡 Protocolos de Comunicação

### Comunicação Peer ↔ Tracker (UDP)

```mermaid
sequenceDiagram
    participant P as Peer
    participant T as Tracker
    
    P->>T: REGISTER (IP:Porta)
    T-->>P: PEERS_LIST (lista inicial)
    
    loop Heartbeat Periódico
        P->>T: HEARTBEAT
        T-->>P: ACK
    end
    
    P->>T: UPDATE (lista de arquivos)
    T-->>P: PEERS_LIST (atualizada)
    
    P->>T: UNREGISTER
    T-->>P: ACK
```

### Transferência Peer ↔ Peer (TCP)

```mermaid
sequenceDiagram
    participant P1 as Peer Solicitante
    participant P2 as Peer Fornecedor
    
    P1->>P2: SOLICITAR_ARQUIVO (nome, chunk)
    P2-->>P1: ARQUIVO_CHUNK (dados)
    
    loop Para cada chunk
        P1->>P2: SOLICITAR_CHUNK (índice)
        P2-->>P1: ENVIAR_CHUNK (dados)
    end
    
    P1->>P2: FINALIZAR_TRANSFERENCIA
    P2-->>P1: ACK
```
