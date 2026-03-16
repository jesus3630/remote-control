import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * AiAssistant — streams responses from Claude (claude-opus-4-6) via the
 * Anthropic Messages API using only the built-in java.net.http.HttpClient.
 *
 * Requires: ANTHROPIC_API_KEY environment variable.
 */
public class AiAssistant {

    private static final String API_URL   = "https://api.anthropic.com/v1/messages";
    private static final String MODEL     = "claude-opus-4-6";
    private static final String API_KEY   = System.getenv("ANTHROPIC_API_KEY");
    private static final String VERSION   = "2023-06-01";

    private static final String SYSTEM_PROMPT =
        "You are an AI assistant embedded in a remote desktop/SSH control application. " +
        "You help the user manage remote machines via SSH and a desktop agent.\n\n" +
        "Capabilities of the app:\n" +
        "- SSH command execution on remote machines\n" +
        "- File browser to navigate remote directories\n" +
        "- File upload/download via SCP\n" +
        "- Mouse/keyboard control of a remote desktop via a Java agent\n" +
        "- Screenshot capture\n\n" +
        "Guidelines:\n" +
        "- When suggesting shell commands, wrap them in ```bash\\n...\\n``` blocks.\n" +
        "- Keep answers concise and practical.\n" +
        "- If the user asks you to run a command, suggest the exact command they should execute.\n" +
        "- Use the context provided (SSH host, current directory, recent output) to give relevant answers.";

    private final HttpClient http = HttpClient.newHttpClient();

    // ------------------------------------------------------------------ public API

    /**
     * Send a message and stream the response token by token.
     *
     * @param userMessage  the user's chat message
     * @param context      optional context string (SSH info, current dir, recent output)
     * @param history      JSON array string of prior messages, e.g. [{"role":"user","content":"hi"},...]
     * @param onToken      called for each streamed text token
     * @param onDone       called once streaming is complete
     * @param onError      called if an error occurs
     */
    public void streamChat(String userMessage, String context,
                           String history,
                           Consumer<String> onToken,
                           Runnable onDone,
                           Consumer<String> onError) {

        if (API_KEY == null || API_KEY.isBlank()) {
            onError.accept("ANTHROPIC_API_KEY environment variable is not set.");
            return;
        }

        String messageContent = context != null && !context.isBlank()
            ? "<context>\n" + context + "\n</context>\n\n" + userMessage
            : userMessage;

        // Build messages array (history + new user message)
        String messagesJson = buildMessagesJson(history, messageContent);

        String requestBody = "{"
            + "\"model\":\"" + MODEL + "\","
            + "\"max_tokens\":4096,"
            + "\"stream\":true,"
            + "\"thinking\":{\"type\":\"adaptive\"},"
            + "\"system\":" + jsonStr(SYSTEM_PROMPT) + ","
            + "\"messages\":" + messagesJson
            + "}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Content-Type",      "application/json")
            .header("x-api-key",         API_KEY)
            .header("anthropic-version", VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
            .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
            .thenAccept(resp -> {
                if (resp.statusCode() != 200) {
                    // Collect error body
                    StringBuilder sb = new StringBuilder();
                    resp.body().forEach(l -> sb.append(l).append('\n'));
                    onError.accept("API error " + resp.statusCode() + ": " + sb);
                    return;
                }
                resp.body().forEach(line -> parseSseLine(line, onToken));
                onDone.run();
            })
            .exceptionally(ex -> {
                onError.accept("Request failed: " + ex.getMessage());
                return null;
            });
    }

    // ------------------------------------------------------------------ SSE parsing

    /** Parse a single SSE line from the Anthropic streaming response. */
    private static void parseSseLine(String line, Consumer<String> onToken) {
        if (!line.startsWith("data: ")) return;
        String data = line.substring(6).trim();
        if (data.equals("[DONE]")) return;

        // Extract text_delta — minimal JSON parsing without a library
        // Looks for: "type":"text_delta","text":"..."
        if (!data.contains("\"text_delta\"")) return;
        int textIdx = data.indexOf("\"text\":");
        if (textIdx < 0) return;

        int start = data.indexOf('"', textIdx + 7);
        if (start < 0) return;
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < data.length()) {
            char c = data.charAt(i);
            if (c == '\\' && i + 1 < data.length()) {
                char esc = data.charAt(i + 1);
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
        if (sb.length() > 0) onToken.accept(sb.toString());
    }

    // ------------------------------------------------------------------ helpers

    private static String buildMessagesJson(String history, String userContent) {
        if (history == null || history.isBlank() || history.equals("[]")) {
            return "[{\"role\":\"user\",\"content\":" + jsonStr(userContent) + "}]";
        }
        // Strip trailing ] and append new message
        String trimmed = history.trim();
        if (trimmed.equals("[]")) {
            return "[{\"role\":\"user\",\"content\":" + jsonStr(userContent) + "}]";
        }
        // Remove last ']' and append
        String base = trimmed.substring(0, trimmed.lastIndexOf(']'));
        String sep  = base.trim().equals("[") ? "" : ",";
        return base + sep + "{\"role\":\"user\",\"content\":" + jsonStr(userContent) + "}]";
    }

    static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            + "\"";
    }

    public boolean isConfigured() {
        return API_KEY != null && !API_KEY.isBlank();
    }
}
