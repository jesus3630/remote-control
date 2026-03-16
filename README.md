# Remote Control

A browser-based remote control application built with plain Java and HTML/CSS/JS. Control remote machines via SSH, browse files, run commands, and chat with an AI assistant — all from a single web UI.

## Features

- **SSH terminal** — Full interactive PTY terminal via xterm.js (WebSocket)
- **SSH command execution** — Run shell commands and stream output live
- **File browser** — Navigate remote directories with drag-and-drop upload, multi-select, context menu, and sortable columns
- **File transfer** — Upload/download files via SCP with progress bars
- **Desktop agent** — Mouse/keyboard control and screenshots of a remote desktop
- **AI assistant** — Chat with Claude (claude-opus-4-6) with awareness of your SSH session and recent terminal output
- **Live output** — All events streamed to the browser via Server-Sent Events

## Architecture

```
Browser (HTML/CSS/JS)
  │
  ├── HTTP + SSE   →  Server.java (port 8080)
  │                     ├── TCP socket  →  Agent.java (port 9090)  →  Remote desktop
  │                     ├── ssh/scp     →  Remote machine (SSH)
  │                     └── HTTPS       →  Anthropic API (Claude)
  │
  └── WebSocket    →  TerminalServer.java (port 8081)
                          └── ssh -tt   →  Remote machine (PTY)
```

## Requirements

- Java 11+
- `ssh` and `scp` installed (standard on Linux/macOS, available on Windows 10+)
- `sshpass` (optional, for password-based SSH auth)
- An [Anthropic API key](https://console.anthropic.com) for the AI assistant

## Quick Start

**1. Clone and compile**
```bash
git clone https://github.com/jesus3630/remote-control.git
cd remote-control
javac *.java
```

**2. Start the server**
```bash
export ANTHROPIC_API_KEY=sk-ant-...
java Server
```

**3. Open the UI**

Navigate to [http://localhost:8080](http://localhost:8080)

**4. (Optional) Start the desktop agent** on the machine you want to control:
```bash
java Agent
```

## Usage

### SSH Connection
Fill in host, user, port, and choose key or password auth, then click **Connect via SSH**.

### Terminal
Click **Connect** in the Terminal panel to open a live interactive shell.

### File Browser
Navigates automatically to `~` on SSH connect. Right-click for a context menu. Drag and drop files onto the table to upload them.

### AI Assistant
Type in the AI panel to chat with Claude. It automatically receives context about your SSH session and recent terminal output. Suggested shell commands include a **▶ Run via SSH** button.

### Desktop Agent
Run `java Agent [port]` on the target machine. The server connects automatically on port 9090. Supports mouse move/click, key press, text input, shell commands, and screenshots.

## Configuration

| Option | Default | Description |
|--------|---------|-------------|
| `java Server [httpPort]` | 8080 | HTTP server port |
| `java Server 8080 [agentHost]` | localhost | Agent hostname |
| `java Server 8080 localhost [agentPort]` | 9090 | Agent port |
| `java Agent [port]` | 9090 | Agent listen port |
| `ANTHROPIC_API_KEY` | — | Required for AI assistant |

## No External Dependencies

All Java code uses only the standard JDK:

| Feature | Java API used |
|---------|--------------|
| HTTP server | `com.sun.net.httpserver` |
| Live streaming | Server-Sent Events (SSE) |
| WebSocket terminal | `java.net.ServerSocket` (raw WebSocket) |
| SSH/SCP | `ProcessBuilder` → system `ssh`/`scp` |
| Claude API | `java.net.http.HttpClient` |
| Desktop control | `java.awt.Robot` |
