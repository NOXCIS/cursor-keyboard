package com.android.inputmethod.latin.cursor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Minimal client for the Cursor CLI "agent acp" Agent-Client-Protocol server,
 * exposed by the on-device bridge over a local socket.
 *
 * <p>ACP is JSON-RPC 2.0 framed as newline-delimited JSON. This client connects
 * to the bridge on loopback, performs the `initialize` -> `session/new`
 * handshake synchronously, sends a `session/prompt`, and streams
 * `session/update` notifications to a listener. Tool-permission requests are
 * surfaced to the listener so the user can approve or deny them.
 *
 * <p>Exact ACP payload shapes vary between Cursor CLI versions, so this client
 * parses defensively: streamed text is extracted by walking the JSON tree for
 * string values under "text" keys.
 */
public final class CursorAgentClient {
    private final Object writeLock = new Object();
    private final ConcurrentHashMap<Integer, BlockingQueue<JSONObject>> pending =
            new ConcurrentHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(1);

    private volatile Socket socket;
    private volatile PrintWriter writer;
    private volatile BufferedReader reader;
    private volatile Thread readerThread;
    private volatile boolean running;
    private volatile int promptId = -1;

    private AgentConfig config;
    private CursorAgentListener listener;

    /**
     * Open the socket, start the reader thread, and perform the ACP handshake.
     * Returns the session id, or {@code null} if the handshake failed.
     */
    public String connectBlocking(AgentConfig config, CursorAgentListener listener)
            throws IOException, JSONException {
        this.config = config;
        this.listener = listener;

        Socket s = new Socket();
        s.connect(new InetSocketAddress(config.bridgeHost, config.bridgePort), 5000);
        this.socket = s;
        this.writer = new PrintWriter(
                new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
        this.reader = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        this.running = true;

        readerThread = new Thread(this::readLoop, "cursor-agent-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        return initializeAndNewSession();
    }

    private String initializeAndNewSession() throws IOException, JSONException {
        int initId = nextId();
        JSONObject init = new JSONObject();
        init.put("jsonrpc", "2.0");
        init.put("id", initId);
        init.put("method", "initialize");
        JSONObject caps = new JSONObject();
        JSONObject fs = new JSONObject();
        fs.put("readTextFile", true);
        fs.put("writeTextFile", true);
        caps.put("fs", fs);
        JSONObject initParams = new JSONObject();
        initParams.put("protocolVersion", 1);
        initParams.put("clientCapabilities", caps);
        init.put("params", initParams);
        send(init);

        JSONObject initResp = awaitResponse(initId, 8000);
        if (initResp == null) {
            return null;
        }

        int sessId = nextId();
        JSONObject newSess = new JSONObject();
        newSess.put("jsonrpc", "2.0");
        newSess.put("id", sessId);
        newSess.put("method", "session/new");
        JSONObject params = new JSONObject();
        params.put("cwd", config.workspace.isEmpty() ? "/" : config.workspace);
        params.put("mcpServers", new JSONArray());
        newSess.put("params", params);
        send(newSess);

        JSONObject sessResp = awaitResponse(sessId, 15000);
        if (sessResp == null) {
            return null;
        }
        JSONObject result = sessResp.optJSONObject("result");
        return result == null ? null : result.optString("sessionId", null);
    }

    /** Ask the bridge for the current shell/terminal context (returns JSON text). */
    public String requestContext() throws JSONException {
        int id = nextId();
        JSONObject msg = new JSONObject();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", "cursor_keyboard/get_context");
        msg.put("params", new JSONObject());
        send(msg);
        JSONObject resp = awaitResponse(id, 5000);
        if (resp == null || resp.has("error")) {
            return null;
        }
        JSONObject result = resp.optJSONObject("result");
        return result == null ? null : result.toString();
    }

    public void sendPrompt(String sessionId, String prompt) throws JSONException {
        int id = nextId();
        promptId = id;
        JSONObject msg = new JSONObject();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", "session/prompt");
        JSONObject params = new JSONObject();
        params.put("sessionId", sessionId);
        JSONArray arr = new JSONArray();
        JSONObject part = new JSONObject();
        part.put("type", "text");
        part.put("text", prompt);
        arr.put(part);
        params.put("prompt", arr);
        msg.put("params", params);
        send(msg);
    }

    public void respondPermission(int requestId, boolean allowed) {
        try {
            JSONObject resp = new JSONObject();
            resp.put("jsonrpc", "2.0");
            resp.put("id", requestId);
            JSONObject result = new JSONObject();
            result.put("outcome", allowed ? "granted" : "denied");
            resp.put("result", result);
            send(resp);
        } catch (JSONException ignored) {
            // Building a fixed-shape response should never fail; nothing to recover.
        }
    }

    private void send(JSONObject msg) {
        synchronized (writeLock) {
            if (writer != null) {
                writer.println(msg.toString());
            }
        }
    }

    private void readLoop() {
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    dispatch(line);
                }
            }
        } catch (IOException e) {
            if (running) {
                listener.onAgentError("Lost connection to bridge (" + e.getMessage() + ")");
            }
        } finally {
            if (running) {
                running = false;
            }
        }
    }

    private void dispatch(String line) {
        JSONObject msg;
        try {
            msg = new JSONObject(new JSONTokener(line));
        } catch (JSONException e) {
            return;
        }
        if (msg.has("id") && (msg.has("result") || msg.has("error"))) {
            handleResponse(msg);
            return;
        }
        String method = msg.optString("method", "");
        if ("session/update".equals(method)) {
            String text = extractText(msg.optJSONObject("params"));
            if (text != null && !text.isEmpty()) {
                listener.onAgentStreamText(text);
            }
        } else if ("session/request_permission".equals(method)) {
            int id = msg.optInt("id", -1);
            JSONObject params = msg.optJSONObject("params");
            String title = params == null ? "Run tool"
                    : params.optString("title", firstString(params.optJSONObject("request")));
            String details = params == null ? ""
                    : params.optString("description", firstString(params.optJSONObject("request")));
            listener.onAgentPermissionRequest(id, title, details);
        } else if (method.startsWith("cursor/")) {
            respondToBlockingCursorMethod(msg);
        }
    }

    private void handleResponse(JSONObject msg) {
        int id = msg.optInt("id", -1);
        BlockingQueue<JSONObject> queue = pending.remove(id);
        if (queue != null) {
            queue.offer(msg);
        }
        if (id == promptId) {
            promptId = -1;
            listener.onAgentPromptComplete();
        }
    }

    private void respondToBlockingCursorMethod(JSONObject msg) {
        if (!msg.has("id")) {
            return;
        }
        try {
            JSONObject resp = new JSONObject();
            resp.put("jsonrpc", "2.0");
            resp.put("id", msg.optInt("id"));
            // Benign acknowledgement so the agent loop does not block forever.
            resp.put("result", new JSONObject());
            send(resp);
        } catch (JSONException ignored) {
            // Fixed-shape response; nothing to recover.
        }
    }

    private JSONObject awaitResponse(int id, long timeoutMillis) {
        BlockingQueue<JSONObject> queue = new LinkedBlockingQueue<>();
        pending.put(id, queue);
        try {
            return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pending.remove(id);
        }
    }

    private String extractText(JSONObject params) {
        if (params == null) {
            return null;
        }
        JSONObject update = params.optJSONObject("update");
        if (update == null) {
            update = params;
        }
        StringBuilder sb = new StringBuilder();
        collectText(update, sb, 0);
        return sb.toString();
    }

    private void collectText(Object node, StringBuilder sb, int depth) {
        if (depth > 8 || node == null) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
                String key = it.next();
                Object value = obj.opt(key);
                if ("text".equals(key) || "markdown".equals(key)) {
                    if (value instanceof String && !((String) value).isEmpty()) {
                        sb.append((String) value);
                    }
                } else {
                    collectText(value, sb, depth + 1);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectText(arr.opt(i), sb, depth + 1);
            }
        }
    }

    private String firstString(JSONObject obj) {
        if (obj == null) {
            return "";
        }
        for (Iterator<String> it = obj.keys(); it.hasNext(); ) {
            String key = it.next();
            Object v = obj.opt(key);
            if (v instanceof String) {
                return (String) v;
            }
        }
        return "";
    }

    public boolean isRunning() {
        return running;
    }

    public void disconnect() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private int nextId() {
        return idSeq.getAndIncrement();
    }
}
