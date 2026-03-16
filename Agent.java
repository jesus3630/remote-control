import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * Agent — runs on the machine to be controlled.
 * Listens for TCP connections and executes commands.
 *
 * Usage: java Agent [port]   (default port: 9090)
 */
public class Agent {

    private static final int DEFAULT_PORT = 9090;
    private static Robot robot;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        robot = new Robot();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Agent listening on port " + port);
            while (true) {
                Socket client = serverSocket.accept();
                System.out.println("Server connected: " + client.getInetAddress());
                new Thread(() -> handleConnection(client)).start();
            }
        }
    }

    private static void handleConnection(Socket socket) {
        try (
            BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter    out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            String line;
            while ((line = in.readLine()) != null) {
                processCommand(line.trim(), out);
            }
        } catch (Exception e) {
            System.out.println("Connection closed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ commands

    private static void processCommand(String command, PrintWriter out) {
        if (command.isEmpty()) return;

        String upper = command.toUpperCase();
        try {
            if (upper.startsWith("MOUSE_MOVE ")) {
                String[] p = command.split(" ");
                int x = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
                robot.mouseMove(x, y);
                out.println("OK MOUSE_MOVED " + x + " " + y);

            } else if (upper.startsWith("MOUSE_CLICK ")) {
                String[] p = command.split(" ");
                int x      = Integer.parseInt(p[1]);
                int y      = Integer.parseInt(p[2]);
                int button = p.length > 3 ? Integer.parseInt(p[3]) : 1;
                int mask   = button == 2 ? InputEvent.BUTTON2_DOWN_MASK
                           : button == 3 ? InputEvent.BUTTON3_DOWN_MASK
                           : InputEvent.BUTTON1_DOWN_MASK;
                robot.mouseMove(x, y);
                robot.mousePress(mask);
                robot.mouseRelease(mask);
                out.println("OK MOUSE_CLICKED " + x + " " + y + " btn=" + button);

            } else if (upper.startsWith("KEY_PRESS ")) {
                String keyName = command.substring(10).trim().toUpperCase();
                int keyCode    = KeyEvent.class.getField("VK_" + keyName).getInt(null);
                robot.keyPress(keyCode);
                robot.keyRelease(keyCode);
                out.println("OK KEY_PRESSED " + keyName);

            } else if (upper.startsWith("TYPE ")) {
                String text = command.substring(5);
                typeText(text);
                out.println("OK TYPED");

            } else if (upper.startsWith("EXEC ")) {
                String cmd = command.substring(5);
                runExec(cmd, out);

            } else if (upper.equals("SCREENSHOT")) {
                takeScreenshot(out);

            } else if (upper.equals("PING")) {
                out.println("OK PONG");

            } else {
                out.println("ERROR Unknown command: " + command);
            }
        } catch (Exception e) {
            out.println("ERROR " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void runExec(String cmd, PrintWriter out) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        pb.command(isWindows ? new String[]{"cmd.exe", "/c", cmd}
                             : new String[]{"sh", "-c", cmd});
        pb.redirectErrorStream(true);

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.println("OUTPUT " + line);
            }
        }
        int exit = process.waitFor();
        out.println("OK EXEC_DONE exit=" + exit);
    }

    private static void takeScreenshot(PrintWriter out) throws Exception {
        Dimension size       = Toolkit.getDefaultToolkit().getScreenSize();
        BufferedImage image  = robot.createScreenCapture(new Rectangle(size));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        out.println("SCREENSHOT " + b64);
    }

    private static void typeText(String text) throws Exception {
        for (char c : text.toCharArray()) {
            typeChar(c);
            robot.delay(20);
        }
    }

    private static void typeChar(char c) throws Exception {
        boolean shift = Character.isUpperCase(c) || "!@#$%^&*()_+{}|:\"<>?".indexOf(c) >= 0;
        int keyCode;

        if (Character.isLetter(c)) {
            keyCode = KeyEvent.class.getField("VK_" + Character.toUpperCase(c)).getInt(null);
        } else {
            switch (c) {
                case ' ':  keyCode = KeyEvent.VK_SPACE;  shift = false; break;
                case '\n': keyCode = KeyEvent.VK_ENTER;  shift = false; break;
                case '\t': keyCode = KeyEvent.VK_TAB;    shift = false; break;
                case '0':  keyCode = KeyEvent.VK_0;      shift = false; break;
                case '1':  keyCode = KeyEvent.VK_1;      shift = false; break;
                case '2':  keyCode = KeyEvent.VK_2;      shift = false; break;
                case '3':  keyCode = KeyEvent.VK_3;      shift = false; break;
                case '4':  keyCode = KeyEvent.VK_4;      shift = false; break;
                case '5':  keyCode = KeyEvent.VK_5;      shift = false; break;
                case '6':  keyCode = KeyEvent.VK_6;      shift = false; break;
                case '7':  keyCode = KeyEvent.VK_7;      shift = false; break;
                case '8':  keyCode = KeyEvent.VK_8;      shift = false; break;
                case '9':  keyCode = KeyEvent.VK_9;      shift = false; break;
                case '.':  keyCode = KeyEvent.VK_PERIOD; shift = false; break;
                case '-':  keyCode = KeyEvent.VK_MINUS;  shift = false; break;
                case '_':  keyCode = KeyEvent.VK_MINUS;  shift = true;  break;
                default: return; // skip unsupported characters
            }
        }

        if (shift) robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(keyCode);
        robot.keyRelease(keyCode);
        if (shift) robot.keyRelease(KeyEvent.VK_SHIFT);
    }
}
