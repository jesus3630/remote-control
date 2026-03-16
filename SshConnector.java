import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * SshConnector — executes commands on a remote host via the system ssh binary.
 * Supports key-based auth and password auth (requires sshpass).
 * No external Java dependencies.
 */
public class SshConnector {

    public enum AuthMethod { KEY, PASSWORD }

    private String     host;
    private int        port     = 22;
    private String     user;
    private AuthMethod authMethod;
    private String     keyPath;      // path to private key file
    private String     password;     // used with sshpass

    private volatile boolean connected = false;

    // ------------------------------------------------------------------ config

    public void configure(String host, int port, String user, String keyPath) {
        this.host       = host;
        this.port       = port;
        this.user       = user;
        this.keyPath    = keyPath;
        this.authMethod = AuthMethod.KEY;
        this.connected  = false;
    }

    public void configure(String host, int port, String user, String password, boolean isPassword) {
        this.host       = host;
        this.port       = port;
        this.user       = user;
        this.password   = password;
        this.authMethod = AuthMethod.PASSWORD;
        this.connected  = false;
    }

    /** Test connectivity with a simple echo command. */
    public boolean testConnection(Consumer<String> output) {
        try {
            int exit = exec("echo __SSH_OK__", output, output, 8);
            connected = (exit == 0);
            return connected;
        } catch (Exception e) {
            output.accept("ERROR " + e.getMessage());
            connected = false;
            return false;
        }
    }

    public boolean isConnected() { return connected; }

    // ------------------------------------------------------------------ exec

    /**
     * Run a shell command on the remote host.
     * @param command   shell command string
     * @param stdout    consumer for stdout lines (prefixed "OUTPUT ")
     * @param status    consumer for status/error lines
     * @param timeoutSec max seconds to wait (0 = wait forever)
     * @return process exit code
     */
    public int exec(String command, Consumer<String> stdout,
                    Consumer<String> status, int timeoutSec) throws Exception {

        List<String> cmd = buildSshCommand(command);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        Process proc = pb.start();

        // Stream stdout
        CompletableFuture<Void> stdoutFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    stdout.accept("OUTPUT " + line);
                }
            } catch (IOException ignored) {}
        });

        // Stream stderr
        CompletableFuture<Void> stderrFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    status.accept("OUTPUT [stderr] " + line);
                }
            } catch (IOException ignored) {}
        });

        boolean finished;
        if (timeoutSec > 0) {
            finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                status.accept("ERROR Command timed out after " + timeoutSec + "s");
                return -1;
            }
        } else {
            proc.waitFor();
            finished = true;
        }

        stdoutFuture.join();
        stderrFuture.join();

        return proc.exitValue();
    }

    // ------------------------------------------------------------------ upload / download

    /**
     * Copy a local file to the remote host using scp.
     * @param localPath  absolute path on local machine
     * @param remotePath absolute path on remote machine
     */
    public int scpUpload(String localPath, String remotePath, Consumer<String> output) throws Exception {
        List<String> cmd = buildScpCommand(localPath, user + "@" + host + ":" + remotePath);
        return runScp(cmd, output);
    }

    /**
     * Copy a remote file to a local path using scp.
     * @param remotePath absolute path on remote machine
     * @param localPath  absolute path on local machine
     */
    public int scpDownload(String remotePath, String localPath, Consumer<String> output) throws Exception {
        List<String> cmd = buildScpCommand(user + "@" + host + ":" + remotePath, localPath);
        return runScp(cmd, output);
    }

    private int runScp(List<String> cmd, Consumer<String> output) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) output.accept("OUTPUT " + line);
        }
        return proc.waitFor();
    }

    // ------------------------------------------------------------------ private builders

    private List<String> buildSshCommand(String remoteCommand) {
        List<String> cmd = new ArrayList<>();

        if (authMethod == AuthMethod.PASSWORD) {
            cmd.add("sshpass");
            cmd.add("-p");
            cmd.add(password);
        }

        cmd.add("ssh");
        cmd.add("-p"); cmd.add(String.valueOf(port));
        cmd.add("-o"); cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o"); cmd.add("BatchMode=" + (authMethod == AuthMethod.KEY ? "yes" : "no"));
        cmd.add("-o"); cmd.add("ConnectTimeout=8");

        if (authMethod == AuthMethod.KEY && keyPath != null && !keyPath.isBlank()) {
            cmd.add("-i"); cmd.add(keyPath);
        }

        cmd.add(user + "@" + host);
        cmd.add(remoteCommand);
        return cmd;
    }

    private List<String> buildScpCommand(String src, String dst) {
        List<String> cmd = new ArrayList<>();

        if (authMethod == AuthMethod.PASSWORD) {
            cmd.add("sshpass");
            cmd.add("-p");
            cmd.add(password);
        }

        cmd.add("scp");
        cmd.add("-P"); cmd.add(String.valueOf(port));
        cmd.add("-o"); cmd.add("StrictHostKeyChecking=no");

        if (authMethod == AuthMethod.KEY && keyPath != null && !keyPath.isBlank()) {
            cmd.add("-i"); cmd.add(keyPath);
        }

        cmd.add(src);
        cmd.add(dst);
        return cmd;
    }

    // ------------------------------------------------------------------ getters

    public String getHost()    { return host; }
    public String getUser()    { return user; }
    public int    getPort()    { return port; }
    public String getSummary() { return user + "@" + host + ":" + port; }
}
