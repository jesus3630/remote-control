import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Server — HTTP bridge between the browser UI and the remote Agent or SSH host.
 * Serves index.html and relays commands/status via SSE.
 *
 * Usage:
 *   java Server [httpPort] [agentHost] [agentPort]
 *   java Server 8080 192.168.1.50 9090
 */
public class Server {

    private static final int    DEFAULT_HTTP_PORT  = 8080;
    private static final String DEFAULT_AGENT_HOST = "localhost";
    private static final int    DEFAULT_AGENT_PORT = 9090;

    private static String agentHost;
    private static int    agentPort;

    // SSE client writers — one per connected browser tab
    static final List<PrintWriter> sseClients = new CopyOnWriteArrayList<>();

    private static volatile PrintWriter agentOut       = null;
    private static volatile boolean     agentConnected = false;

    // SSH connector (one active session at a time)
    static final SshConnector ssh = new SshConnector();

    // AI assistant
    static final AiAssistant ai = new AiAssistant();

    public static void main(String[] args) throws Exception {
        int httpPort = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_HTTP_PORT;
        agentHost    = args.length > 1 ? args[1] : DEFAULT_AGENT_HOST;
        agentPort    = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_AGENT_PORT;

        // Background thread: maintain persistent connection to agent
        Thread agentThread = new Thread(Server::agentLoop);
        agentThread.setDaemon(true);
        agentThread.start();

        // Terminal WebSocket server
        new TerminalServer().start();

        HttpServer http = HttpServer.create(new InetSocketAddress(httpPort), 0);
        http.createContext("/",           new StaticHandler());
        http.createContext("/command",    new CommandHandler());
        http.createContext("/status",     new SseHandler());
        http.createContext("/ssh/connect",  new SshConnectHandler());
        http.createContext("/ssh/exec",     new SshExecHandler());
        http.createContext("/ssh/upload",   new SshUploadHandler());
        http.createContext("/ssh/download", new SshDownloadHandler());
        http.createContext("/ssh/browse",   new SshBrowseHandler());
        http.createContext("/ai/chat",      new AiChatHandler());
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();

        System.out.println("Server running → http://localhost:" + httpPort);
        System.out.println("Connecting to agent at " + agentHost + ":" + agentPort + " ...");
    }

    // ------------------------------------------------------------------ agent loop

    private static void agentLoop() {
        while (true) {
            try (Socket socket = new Socket(agentHost, agentPort)) {
                agentOut       = new PrintWriter(socket.getOutputStream(), true);
                agentConnected = true;
                broadcast("STATUS Connected to agent " + agentHost + ":" + agentPort);
                System.out.println("Agent connected.");

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                String line;
                while ((line = in.readLine()) != null) {
                    broadcast(line);
                }
            } catch (Exception e) {
                agentConnected = false;
                agentOut       = null;
                broadcast("STATUS Agent disconnected. Retrying in 3 s...");
                System.out.println("Agent disconnected: " + e.getMessage());
            }
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        }
    }

    static void broadcast(String message) {
        for (PrintWriter w : sseClients) {
            w.print("data: " + message + "\n\n");
            w.flush();
        }
    }

    // ------------------------------------------------------------------ handlers

    /** Serves static files from the working directory. */
    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

            File file = new File("." + path);
            if (!file.exists() || file.isDirectory()) {
                ex.sendResponseHeaders(404, -1);
                return;
            }

            byte[] bytes = Files.readAllBytes(file.toPath());
            String ct = path.endsWith(".html") ? "text/html; charset=utf-8"
                      : path.endsWith(".css")  ? "text/css"
                      : path.endsWith(".js")   ? "application/javascript"
                      : "application/octet-stream";

            ex.getResponseHeaders().set("Content-Type", ct);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.getResponseBody().close();
        }
    }

    /** Forwards a command to the Agent over TCP. */
    static class CommandHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            setCors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
            if (!"POST".equals(ex.getRequestMethod()))   { ex.sendResponseHeaders(405, -1); return; }

            String body = new String(ex.getRequestBody().readAllBytes()).trim();

            if (agentOut != null && agentConnected) {
                broadcast("CMD → " + body);
                agentOut.println(body);
                respond(ex, 200, "OK");
            } else {
                broadcast("STATUS Agent not connected");
                respond(ex, 503, "Agent not connected");
            }
        }
    }

    /**
     * POST /ssh/connect
     * Body (form-encoded or plain lines):
     *   host=...&port=22&user=...&auth=key&key=/path/to/key
     *   host=...&port=22&user=...&auth=password&password=...
     */
    static class SshConnectHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            setCors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
            if (!"POST".equals(ex.getRequestMethod()))   { ex.sendResponseHeaders(405, -1); return; }

            String body = new String(ex.getRequestBody().readAllBytes()).trim();
            Map<String, String> params = parseForm(body);

            String host     = params.getOrDefault("host", "");
            int    port     = Integer.parseInt(params.getOrDefault("port", "22"));
            String user     = params.getOrDefault("user", System.getProperty("user.name"));
            String auth     = params.getOrDefault("auth", "key");

            if (host.isBlank()) { respond(ex, 400, "host required"); return; }

            broadcast("STATUS SSH connecting to " + user + "@" + host + ":" + port + " ...");

            if ("password".equals(auth)) {
                String password = params.getOrDefault("password", "");
                ssh.configure(host, port, user, password, true);
            } else {
                String keyPath = params.getOrDefault("key", "");
                ssh.configure(host, port, user, keyPath.isBlank() ? null : keyPath);
            }

            // Test connection in background so HTTP response returns quickly
            new Thread(() -> {
                boolean ok = ssh.testConnection(Server::broadcast);
                if (ok) {
                    broadcast("STATUS SSH connected → " + ssh.getSummary());
                } else {
                    broadcast("STATUS SSH connection failed to " + ssh.getSummary());
                }
            }).start();

            respond(ex, 200, "Connecting...");
        }
    }

    /**
     * POST /ssh/exec
     * Body: the shell command to run on the remote host.
     */
    static class SshExecHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            setCors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
            if (!"POST".equals(ex.getRequestMethod()))   { ex.sendResponseHeaders(405, -1); return; }

            String command = new String(ex.getRequestBody().readAllBytes()).trim();
            if (command.isBlank()) { respond(ex, 400, "command required"); return; }

            if (!ssh.isConnected()) {
                broadcast("STATUS SSH not connected");
                respond(ex, 503, "SSH not connected");
                return;
            }

            broadcast("CMD [SSH] → " + command);
            respond(ex, 200, "OK");

            // Run asynchronously so output streams via SSE
            new Thread(() -> {
                try {
                    int exit = ssh.exec(command, Server::broadcast, Server::broadcast, 60);
                    broadcast("OK SSH_EXEC_DONE exit=" + exit);
                } catch (Exception e) {
                    broadcast("ERROR SSH exec failed: " + e.getMessage());
                }
            }).start();
        }
    }

    /**
     * POST /ssh/upload
     * Headers: X-Remote-Path (destination path on remote)
     * Body:    raw file bytes
     */
    static class SshUploadHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            setCors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
            if (!"POST".equals(ex.getRequestMethod()))   { ex.sendResponseHeaders(405, -1); return; }

            if (!ssh.isConnected()) {
                broadcast("STATUS SSH not connected");
                respond(ex, 503, "SSH not connected");
                return;
            }

            String remotePath = ex.getRequestHeaders().getFirst("X-Remote-Path");
            String filename   = ex.getRequestHeaders().getFirst("X-Filename");
            if (remotePath == null || remotePath.isBlank()) {
                respond(ex, 400, "X-Remote-Path header required");
                return;
            }
            if (filename == null || filename.isBlank()) filename = "upload";
            // Sanitise filename to prevent path traversal
            filename = new File(filename).getName();

            byte[] data = ex.getRequestBody().readAllBytes();
            if (data.length == 0) { respond(ex, 400, "empty file"); return; }

            // Save to temp file, then scp
            File tmp = File.createTempFile("rc_upload_", "_" + filename);
            tmp.deleteOnExit();
            Files.write(tmp.toPath(), data);

            final String tmpPath    = tmp.getAbsolutePath();
            final String remPathFin = remotePath;
            final long   fileSize   = data.length;
            final String filenameFin = filename;

            broadcast("STATUS Uploading " + filename + " (" + humanSize(fileSize) + ") → " + remPathFin);
            respond(ex, 200, "OK");

            new Thread(() -> {
                try {
                    int exit = ssh.scpUpload(tmpPath, remPathFin, Server::broadcast);
                    if (exit == 0) broadcast("OK UPLOAD_DONE " + filenameFin + " → " + remPathFin);
                    else           broadcast("ERROR Upload failed (exit " + exit + ")");
                } catch (Exception e) {
                    broadcast("ERROR Upload error: " + e.getMessage());
                } finally {
                    tmp.delete();
                }
            }).start();
        }
    }

    /**
     * POST /ssh/download
     * Body: remote file path to download
     * Response: raw file bytes with Content-Disposition: attachment
     */
    static class SshDownloadHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            setCors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
            if (!"POST".equals(ex.getRequestMethod()))   { ex.sendResponseHeaders(405, -1); return; }

            if (!ssh.isConnected()) {
                broadcast("STATUS SSH not connected");
                respond(ex, 503, "SSH not connected");
                return;
            }

            String remotePath = new String(ex.getRequestBody().readAllBytes()).trim();
            if (remotePath.isBlank()) { respond(ex, 400, "remote path required"); return; }

            String filename = new File(remotePath).getName();
            if (filename.isBlank()) filename = "download";

            File tmp = File.createTempFile("rc_download_", "_" + filename);
            tmp.deleteOnExit();

            broadcast("STATUS Downloading " + remotePath + " ...");

            try {
                int exit = ssh.scpDownload(remotePath, tmp.getAbsolutePath(), Server::broadcast);
                if (exit != 0 || !tmp.exists() || tmp.length() == 0) {
                    broadcast("ERROR Download failed (exit " + exit + ")");
                    respond(ex, 500, "Download failed");
                    return;
                }

                byte[] bytes = Files.readAllBytes(tmp.toPath());
                broadcast("OK DOWNLOAD_DONE " + filename + " (" + humanSize(bytes.length) + ")");

                ex.getResponseHeaders().set("Content-Type",        "application/octet-stream");
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                ex.getResponseHeaders().set("Access-Control-Expose-Headers", "Content-Disposition");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.getResponseBody().close();
            } catch (Exception e) {
                broadcast("ERROR Download error: " + e.getMessage());
                respond(ex, 500, e.getMessage());
            } finally {
                tmp.delete();
            }
        }
    }

    /**
     * POST /ssh/browse
     * Body: remote directory path (default: ~)
     * Response: JSON directory listing
     */
    static class SshBrowseHandler implements HttpHandler {

        // Matches `ls -la` lines on both GNU/Linux and BSD/macOS
        // Groups: 1=perms  2=size  3=date  4=name
        private static final Pattern LS_LINE = Pattern.compile(
            "^([dlrwxstST\\-]{10})\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+" +
            "(\\w+\\s+\\d+\\s+[\\d:]+|\\d{4}-\\d{2}-\\d{2}(?:\\s+[\\d:]+)?)\\s+(.+)$"
        );

        public void handle(HttpExchange ex) throws IOException {
            setCors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
            if (!"POST".equals(ex.getRequestMethod()))   { ex.sendResponseHeaders(405, -1); return; }

            if (!ssh.isConnected()) {
                respondJson(ex, 503, "{\"error\":\"SSH not connected\"}");
                return;
            }

            String rawPath = new String(ex.getRequestBody().readAllBytes()).trim();
            if (rawPath.isEmpty()) rawPath = "~";

            // Resolve ~ and normalise; use 'ls' with full ISO date for reliable parsing
            final String path = rawPath;
            List<String> lines = new ArrayList<>();

            try {
                // --time-style works on GNU coreutils; gracefully falls back on BSD
                String cmd = "ls -la --time-style='+%Y-%m-%d %H:%M' " + shellEscape(path)
                           + " 2>/dev/null || ls -la " + shellEscape(path);
                ssh.exec(cmd, line -> {
                    if (line.startsWith("OUTPUT ")) lines.add(line.substring(7));
                }, line -> {}, 15);
            } catch (Exception e) {
                respondJson(ex, 500, "{\"error\":" + jsonStr(e.getMessage()) + "}");
                return;
            }

            // Resolve the real path (handle ~, relative paths)
            List<String> resolvedLines = new ArrayList<>();
            try {
                ssh.exec("realpath " + shellEscape(path) + " 2>/dev/null || echo " + shellEscape(path),
                    line -> { if (line.startsWith("OUTPUT ")) resolvedLines.add(line.substring(7)); },
                    line -> {}, 8);
            } catch (Exception ignored) {}
            String resolvedPath = resolvedLines.isEmpty() ? path : resolvedLines.get(0).trim();

            // Build JSON
            StringBuilder json = new StringBuilder();
            json.append("{\"path\":").append(jsonStr(resolvedPath)).append(",\"entries\":[");

            boolean first = true;
            for (String line : lines) {
                if (line.startsWith("total ") || line.isBlank()) continue;
                Matcher m = LS_LINE.matcher(line);
                if (!m.matches()) continue;

                String perms   = m.group(1);
                long   size    = Long.parseLong(m.group(2));
                String date    = m.group(3).trim();
                String name    = m.group(4).trim();

                // Strip symlink target (name -> target)
                String displayName = name.contains(" -> ") ? name.substring(0, name.indexOf(" -> ")) : name;

                char typeChar = perms.charAt(0);
                String type = typeChar == 'd' ? "dir"
                            : typeChar == 'l' ? "link"
                            : "file";

                if (!first) json.append(",");
                first = false;
                json.append("{")
                    .append("\"name\":").append(jsonStr(displayName)).append(",")
                    .append("\"type\":").append(jsonStr(type)).append(",")
                    .append("\"size\":").append(size).append(",")
                    .append("\"date\":").append(jsonStr(date)).append(",")
                    .append("\"perms\":").append(jsonStr(perms))
                    .append("}");
            }

            json.append("]}");
            respondJson(ex, 200, json.toString());
        }

        private static String shellEscape(String s) {
            return "'" + s.replace("'", "'\\''") + "'";
        }

        private static String jsonStr(String s) {
            if (s == null) return "null";
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                           .replace("\n", "\\n").replace("\r", "") + "\"";
        }

        private void respondJson(HttpExchange ex, int code, String json) throws IOException {
            byte[] b = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
        }
    }

    /** Server-Sent Events endpoint. */
    static class SseHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            ex.getResponseHeaders().set("Content-Type",  "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection",    "keep-alive");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, 0);

            PrintWriter writer = new PrintWriter(ex.getResponseBody(), true);
            sseClients.add(writer);
            writer.print("data: STATUS SSE stream connected\n\n");
            writer.flush();

            try {
                while (!writer.checkError()) {
                    writer.print(": ping\n\n");
                    writer.flush();
                    Thread.sleep(15_000);
                }
            } catch (InterruptedException ignored) {
            } finally {
                sseClients.remove(writer);
            }
        }
    }

    // ------------------------------------------------------------------ utils

    private static void setCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes();
        ex.getResponseHeaders().set("Content-Type", "text/plain");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.getResponseBody().close();
    }

    /**
     * POST /ai/chat
     * Body JSON: { "message": "...", "context": "...", "history": [...] }
     * Response: SSE stream of text tokens, then a final "DONE" event.
     */
    static class AiChatHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            setCors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
            if (!"POST".equals(ex.getRequestMethod()))   { ex.sendResponseHeaders(405, -1); return; }

            if (!ai.isConfigured()) {
                respond(ex, 503, "ANTHROPIC_API_KEY is not set on the server.");
                return;
            }

            String body = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String message = extractJson(body, "message");
            String context = extractJson(body, "context");
            String history = extractJsonArray(body, "history");

            if (message == null || message.isBlank()) {
                respond(ex, 400, "message required");
                return;
            }

            // Open SSE response
            ex.getResponseHeaders().set("Content-Type",  "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection",    "keep-alive");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, 0);

            PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(ex.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8), true);

            ai.streamChat(
                message, context, history,
                token -> {
                    writer.print("event: token\ndata: " + AiAssistant.jsonStr(token) + "\n\n");
                    writer.flush();
                },
                () -> {
                    writer.print("event: done\ndata: {}\n\n");
                    writer.flush();
                    writer.close();
                },
                err -> {
                    writer.print("event: error\ndata: " + AiAssistant.jsonStr(err) + "\n\n");
                    writer.flush();
                    writer.close();
                }
            );
        }

        /** Extract a simple top-level string field from JSON without a library. */
        private static String extractJson(String json, String key) {
            String search = "\"" + key + "\"";
            int idx = json.indexOf(search);
            if (idx < 0) return null;
            int colon = json.indexOf(':', idx + search.length());
            if (colon < 0) return null;
            int start = json.indexOf('"', colon + 1);
            if (start < 0) return null;
            StringBuilder sb = new StringBuilder();
            int i = start + 1;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char esc = json.charAt(i + 1);
                    switch (esc) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        default:   sb.append(esc);  break;
                    }
                    i += 2;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }

        /** Extract a top-level JSON array field as a raw string. */
        private static String extractJsonArray(String json, String key) {
            String search = "\"" + key + "\"";
            int idx = json.indexOf(search);
            if (idx < 0) return "[]";
            int colon = json.indexOf(':', idx + search.length());
            if (colon < 0) return "[]";
            int start = json.indexOf('[', colon + 1);
            if (start < 0) return "[]";
            int depth = 0;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth == 0) return json.substring(start, i + 1); }
            }
            return "[]";
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024)             return bytes + " B";
        if (bytes < 1024 * 1024)      return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** Parse application/x-www-form-urlencoded body. */
    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(URLDecoder.decode(kv[0], java.nio.charset.StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return map;
    }
}
