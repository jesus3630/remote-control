import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * TerminalServer — WebSocket server on port 8081 that bridges a browser
 * xterm.js terminal to a remote SSH session (with PTY).
 *
 * Protocol (first WebSocket message from browser):
 *   JSON config: {"host":"...","user":"...","port":22,"auth":"key"|"password",
 *                 "key":"/path","password":"..."}
 * Subsequent messages:
 *   Raw keystrokes  OR  resize JSON: {"type":"resize","cols":80,"rows":24}
 */
public class TerminalServer {

    private static final int    WS_PORT  = 8081;
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public void start() {
        Thread t = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(WS_PORT)) {
                System.out.println("Terminal WebSocket on ws://localhost:" + WS_PORT);
                while (true) {
                    Socket client = ss.accept();
                    new Thread(() -> handleClient(client)).start();
                }
            } catch (Exception e) {
                System.err.println("TerminalServer error: " + e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ client

    private void handleClient(Socket socket) {
        try {
            InputStream  rawIn  = socket.getInputStream();
            OutputStream rawOut = socket.getOutputStream();

            // 1. WebSocket handshake
            String wsKey = readUpgradeRequest(rawIn);
            if (wsKey == null) { socket.close(); return; }
            sendUpgradeResponse(rawOut, wsKey);

            // 2. First frame = JSON config
            String configMsg = readWsText(rawIn);
            if (configMsg == null) { socket.close(); return; }

            String host     = jsonStr(configMsg, "host");
            String user     = jsonStr(configMsg, "user");
            String portStr  = jsonStr(configMsg, "port");
            String auth     = jsonStr(configMsg, "auth");
            String keyPath  = jsonStr(configMsg, "key");
            String password = jsonStr(configMsg, "password");
            String colsStr  = jsonStr(configMsg, "cols");
            String rowsStr  = jsonStr(configMsg, "rows");

            if (host == null || host.isBlank()) {
                sendWsText(rawOut, "\r\nError: host is required\r\n");
                socket.close(); return;
            }

            int sshPort = 22;
            try { sshPort = Integer.parseInt(portStr); } catch (Exception ignored) {}
            int cols = 80, rows = 24;
            try { cols = Integer.parseInt(colsStr); } catch (Exception ignored) {}
            try { rows = Integer.parseInt(rowsStr); } catch (Exception ignored) {}

            // 3. Spawn SSH with PTY
            List<String> cmd = buildSshCmd(host, user, sshPort, auth, keyPath, password, cols, rows);
            Process proc = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

            sendWsText(rawOut, "\r\nConnecting to " + user + "@" + host + "...\r\n");

            // 4. SSH stdout → WebSocket (background thread)
            final OutputStream finalOut = rawOut;
            Thread reader = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = proc.getInputStream().read(buf)) != -1) {
                        sendWsBytes(finalOut, buf, n);
                    }
                } catch (Exception ignored) {}
            });
            reader.setDaemon(true);
            reader.start();

            // 5. WebSocket → SSH stdin
            OutputStream sshIn = proc.getOutputStream();
            while (socket.isConnected()) {
                WsFrame frame = readWsFrame(rawIn);
                if (frame == null || frame.opcode == 0x8) break;  // null or close frame

                if (frame.opcode == 0x1 || frame.opcode == 0x2) {
                    String text = new String(frame.payload, StandardCharsets.UTF_8);

                    // Resize event?
                    if (text.startsWith("{") && text.contains("\"resize\"")) {
                        String c = jsonStr(text, "cols");
                        String r = jsonStr(text, "rows");
                        if (c != null && r != null) {
                            // Send stty resize to remote shell
                            sshIn.write(("stty cols " + c + " rows " + r + "\n")
                                .getBytes(StandardCharsets.UTF_8));
                            sshIn.flush();
                        }
                        continue;
                    }

                    sshIn.write(frame.payload, 0, frame.payload.length);
                    sshIn.flush();
                }
            }

            proc.destroy();
            socket.close();

        } catch (Exception e) {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ SSH command builder

    private List<String> buildSshCmd(String host, String user, int port,
                                      String auth, String keyPath, String password,
                                      int cols, int rows) {
        List<String> cmd = new ArrayList<>();

        if ("password".equals(auth) && password != null && !password.isBlank()) {
            cmd.add("sshpass"); cmd.add("-p"); cmd.add(password);
        }

        cmd.add("ssh");
        cmd.add("-tt");                          // force PTY
        cmd.add("-p"); cmd.add(String.valueOf(port));
        cmd.add("-o"); cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o"); cmd.add("ConnectTimeout=10");
        cmd.add("-o"); cmd.add("RequestTTY=force");
        // Set initial terminal size via environment
        cmd.add("-o"); cmd.add("SendEnv=COLUMNS LINES");

        if ("key".equals(auth) && keyPath != null && !keyPath.isBlank()) {
            cmd.add("-i"); cmd.add(keyPath);
        }

        String u = (user != null && !user.isBlank()) ? user : System.getProperty("user.name");
        cmd.add(u + "@" + host);

        // Set initial window size via stty on remote
        cmd.add("stty cols " + cols + " rows " + rows + " && exec $SHELL -l");

        return cmd;
    }

    // ------------------------------------------------------------------ WebSocket handshake

    private String readUpgradeRequest(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String wsKey = null;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.startsWith("Sec-WebSocket-Key:")) {
                wsKey = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return wsKey;
    }

    private void sendUpgradeResponse(OutputStream out, String wsKey) throws Exception {
        String accept = computeAccept(wsKey);
        String response =
            "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String computeAccept(String key) throws Exception {
        String combined = key + WS_MAGIC;
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(combined.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    // ------------------------------------------------------------------ WebSocket frame I/O

    private static class WsFrame {
        int    opcode;
        byte[] payload;
    }

    private WsFrame readWsFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;

        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long payloadLen = b1 & 0x7F;

        if (payloadLen == 126) {
            payloadLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (payloadLen == 127) {
            payloadLen = 0;
            for (int i = 0; i < 8; i++) payloadLen = (payloadLen << 8) | (in.read() & 0xFF);
        }

        byte[] maskKey = new byte[4];
        if (masked) {
            int read = 0;
            while (read < 4) read += in.read(maskKey, read, 4 - read);
        }

        byte[] payload = new byte[(int) payloadLen];
        int read = 0;
        while (read < payload.length) read += in.read(payload, read, payload.length - read);

        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= maskKey[i % 4];
        }

        WsFrame frame = new WsFrame();
        frame.opcode  = opcode;
        frame.payload = payload;
        return frame;
    }

    private String readWsText(InputStream in) throws IOException {
        WsFrame frame = readWsFrame(in);
        if (frame == null) return null;
        return new String(frame.payload, StandardCharsets.UTF_8);
    }

    private synchronized void sendWsText(OutputStream out, String text) throws IOException {
        sendWsBytes(out, text.getBytes(StandardCharsets.UTF_8), -1);
    }

    private synchronized void sendWsBytes(OutputStream out, byte[] data, int len) throws IOException {
        if (len < 0) len = data.length;
        byte[] frame = buildWsFrame((byte) 0x02, data, len);  // binary frame
        out.write(frame);
        out.flush();
    }

    private byte[] buildWsFrame(byte opcode, byte[] payload, int len) {
        byte[] header;
        if (len < 126) {
            header = new byte[]{ (byte)(0x80 | opcode), (byte) len };
        } else if (len < 65536) {
            header = new byte[]{ (byte)(0x80 | opcode), (byte) 126,
                                 (byte)(len >> 8), (byte) len };
        } else {
            header = new byte[]{ (byte)(0x80 | opcode), (byte) 127,
                0, 0, 0, 0,
                (byte)(len >> 24), (byte)(len >> 16), (byte)(len >> 8), (byte) len };
        }
        byte[] frame = new byte[header.length + len];
        System.arraycopy(header,  0, frame, 0,             header.length);
        System.arraycopy(payload, 0, frame, header.length, len);
        return frame;
    }

    // ------------------------------------------------------------------ minimal JSON string extractor

    private static String jsonStr(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;

        // Skip whitespace
        int i = colon + 1;
        while (i < json.length() && json.charAt(i) == ' ') i++;

        if (i >= json.length()) return null;

        if (json.charAt(i) == '"') {
            // String value
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char esc = json.charAt(i + 1);
                    sb.append(esc == 'n' ? '\n' : esc == 't' ? '\t' : esc);
                    i += 2;
                } else if (c == '"') { break; }
                else { sb.append(c); i++; }
            }
            return sb.toString();
        } else {
            // Number or boolean
            int end = i;
            while (end < json.length() && ",}] \r\n".indexOf(json.charAt(end)) < 0) end++;
            return json.substring(i, end).trim();
        }
    }
}
