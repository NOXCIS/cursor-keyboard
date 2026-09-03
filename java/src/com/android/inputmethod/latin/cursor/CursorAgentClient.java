package com.android.inputmethod.latin.cursor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Minimal client for the Cursor CLI "agent acp" Agent-Client-Protocol server,
 * exposed by the on-device bridge over a socket.
 *
 * <p>ACP is JSON-RPC 2.0 framed as newline-delimited JSON. This client connects
 * to the bridge, performs the `initialize` -> `session/new` handshake
 * synchronously, sends a `session/prompt`, and streams `session/update`
 * notifications to a listener. Tool-permission requests are surfaced to the
 * listener so the user can approve or deny them.
 *
 * <p>The endpoint comes from {@link AgentConfig} and may be reached over:
 * <ul>
 *   <li>Newline-delimited TCP: {@code tcp://host:port} (local chroot) or
 *       {@code tls://host:port} (direct TLS).</li>
 *   <li>WebSocket, so a TLS-terminating proxy (e.g. Cloudflare Tunnel) can
 *       forward {@code wss://public-host} / {@code https://public-host} to the
 *       bridge: the same JSON-RPC stream flows inside WebSocket text frames.</li>
 * </ul>
 *
 * <p>When a shared token is configured it is presented as the first message on
 * every connection, so an externally reachable bridge can reject unauthorized
 * clients before any agent runs.
 *
 * <p>Exact ACP payload shapes vary between Cursor CLI versions, so this client
 * parses defensively: streamed text is extracted by walking the JSON tree for
 * string values under "text" keys.
 */
public final class CursorAgentClient {
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final Object writeLock = new Object();
    private final ConcurrentHashMap<Integer, BlockingQueue<JSONObject>> pending =
            new ConcurrentHashMap<>();
    private final AtomicInteger idSeq = new AtomicInteger(1);

    private volatile Transport transport;
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

        Transport t = openConn();
        if (t == null) {
            return null;
        }
        this.transport = t;
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
        if (config.model != null && !config.model.trim().isEmpty()) {
            params.put("model", config.model.trim());
        }
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

    /** Set the config before connecting (used for standalone RPCs like status/auth). */
    public void setConfig(AgentConfig config) {
        this.config = config;
    }

    /**
     * Open a short-lived connection and send a single JSON-RPC request, returning
     * the response. Avoids the agent session handshake for control methods.
     */
    private JSONObject rpcOnSocket(String method, JSONObject params)
            throws IOException, JSONException {
        if (config == null) {
            throw new IllegalStateException("AgentConfig is not set");
        }
        Transport t = openConn();
        if (t == null) {
            return null;
        }
        try {
            int id = nextId();
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("id", id);
            req.put("method", method);
            req.put("params", params == null ? new JSONObject() : params);
            t.write(req.toString());
            long deadline = System.currentTimeMillis() + 8000;
            String line;
            while ((line = t.read()) != null && System.currentTimeMillis() < deadline) {
                if (line.isEmpty()) {
                    continue;
                }
                JSONObject resp = new JSONObject(new JSONTokener(line));
                if (resp.optInt("id", -1) == id) {
                    return resp;
                }
            }
            return null;
        } finally {
            try {
                t.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Push the Cursor API key to the bridge so it can start authenticated agents. */
    public boolean authenticate(String apiKey) throws IOException, JSONException {
        JSONObject params = new JSONObject();
        params.put("apiKey", apiKey);
        JSONObject resp = rpcOnSocket("cursor_keyboard/authenticate", params);
        return resp != null && !resp.has("error") && resp.optJSONObject("result") != null;
    }

    /** Query bridge/agent health. Returns the status result object, or null. */
    public JSONObject requestStatus() throws IOException, JSONException {
        JSONObject resp = rpcOnSocket("cursor_keyboard/status", new JSONObject());
        if (resp == null || resp.has("error")) {
            return null;
        }
        return resp.optJSONObject("result");
    }

    /** Ping the bridge to verify it is reachable. */
    public boolean ping() throws IOException, JSONException {
        JSONObject resp = rpcOnSocket("cursor_keyboard/ping", new JSONObject());
        if (resp == null || resp.has("error")) {
            return false;
        }
        JSONObject result = resp.optJSONObject("result");
        return result != null && result.optBoolean("pong", false);
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

    // ---- Connection transport ---------------------------------------------

    /** A bidirectional message transport. One JSON message per write/read. */
    private interface Transport extends Closeable {
        void write(String line) throws IOException;

        /** Returns the next message, or {@code null} on EOF. */
        String read() throws IOException;

        Socket socket();
    }

    /** Newline-delimited JSON over a raw (optionally TLS) socket. */
    private static final class TcpTransport implements Transport {
        private final Socket socket;
        private final PrintWriter writer;
        private final BufferedReader reader;

        TcpTransport(Socket socket) throws IOException {
            this.socket = socket;
            this.writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            this.reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public void write(String line) throws IOException {
            writer.println(line);
        }

        @Override
        public String read() throws IOException {
            return reader.readLine();
        }

        @Override
        public Socket socket() {
            return socket;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * Minimal RFC 6455 WebSocket client. Client frames are masked; server frames
     * are unmasked. Supports text messages, ping/pong, close, and fragmentation
     * within a single message.
     */
    private static final class WebSocketTransport implements Transport {
        private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;

        WebSocketTransport(Socket socket, String host, int port, String path) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            this.out = new BufferedOutputStream(socket.getOutputStream());
            handshake(host, port, path);
        }

        private void handshake(String host, int port, String path) throws IOException {
            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String key = java.util.Base64.getEncoder().encodeToString(nonce);
            String hostHeader = (port == 443 || port == 80) ? host : host + ":" + port;
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String status = readHttpLine();
            if (status == null || !status.contains("101")) {
                throw new IOException("WebSocket upgrade failed: " + status);
            }
            String expected;
            try {
                expected = java.util.Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1")
                                .digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
            } catch (Exception e) {
                throw new IOException("WebSocket handshake failed", e);
            }
            String line;
            while ((line = readHttpLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx < 0) {
                    continue;
                }
                String name = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                if (name.equalsIgnoreCase("Sec-WebSocket-Accept")
                        && !value.equalsIgnoreCase(expected)) {
                    throw new IOException("WebSocket accept mismatch");
                }
            }
        }

        private String readHttpLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c = in.read();
            if (c < 0) {
                return null;
            }
            while (c >= 0) {
                sb.append((char) c);
                if (sb.length() >= 2
                        && sb.charAt(sb.length() - 2) == '\r'
                        && sb.charAt(sb.length() - 1) == '\n') {
                    sb.setLength(sb.length() - 2);
                    return sb.toString();
                }
                c = in.read();
            }
            return sb.toString();
        }

        @Override
        public void write(String line) throws IOException {
            sendFrame(0x1, line.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String read() throws IOException {
            ByteArrayOutputStream fragments = new ByteArrayOutputStream();
            while (true) {
                int b1 = in.read();
                int b2 = in.read();
                if (b1 < 0 || b2 < 0) {
                    return null;
                }
                int opcode = b1 & 0x0F;
                boolean fin = (b1 & 0x80) != 0;
                boolean masked = (b2 & 0x80) != 0;
                long length = b2 & 0x7F;
                if (length == 126) {
                    length = (in.read() & 0xFF) << 8 | (in.read() & 0xFF);
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | (in.read() & 0xFF);
                    }
                }
                byte[] mask = new byte[4];
                if (masked) {
                    in.readFully(mask);
                }
                byte[] payload = new byte[(int) length];
                in.readFully(payload);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }
                if (opcode == 0x8) { // close
                    return null;
                }
                if (opcode == 0x9) { // ping -> pong
                    sendFrame(0xA, payload);
                    continue;
                }
                if (opcode == 0xA) { // pong
                    continue;
                }
                fragments.write(payload);
                if (fin) {
                    return fragments.toString(StandardCharsets.UTF_8.name());
                }
            }
        }

        private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            header.write(0x80 | (opcode & 0x0F));
            int n = payload.length;
            if (n < 126) {
                header.write(0x80 | n);
            } else if (n < 65536) {
                header.write(0x80 | 126);
                header.write((n >>> 8) & 0xFF);
                header.write(n & 0xFF);
            } else {
                header.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    header.write((n >>> (8 * i)) & 0xFF);
                }
            }
            header.write(mask, 0, 4);
            byte[] masked = new byte[n];
            for (int i = 0; i < n; i++) {
                masked[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
            out.write(header.toByteArray());
            out.write(masked);
            out.flush();
        }

        @Override
        public Socket socket() {
            return socket;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** Open the configured endpoint, presenting the shared token first if set. */
    private Transport openConn() throws IOException {
        URI uri = config.endpointUri();
        if (uri == null) {
            throw new IOException("Invalid agent endpoint URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        int port = uri.getPort();
        if (port < 0) {
            port = defaultPort(scheme);
        }
        if (host == null) {
            throw new IOException("Endpoint URL has no host: " + uri);
        }
        boolean websocket = isWebSocket(scheme);
        Socket s = createSocket(uri, port);
        Transport t;
        if (websocket) {
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            t = new WebSocketTransport(s, host, port, path);
        } else {
            t = new TcpTransport(s);
        }

        if (config.hasSharedToken() && !authenticateTokenOn(t)) {
            try {
                t.close();
            } catch (IOException ignored) {
            }
            throw new IOException("Bridge rejected the shared token (unauthorized)");
        }
        return t;
    }

    private int defaultPort(String scheme) {
        switch (scheme) {
            case "wss":
            case "https":
                return 443;
            case "ws":
            case "http":
                return 80;
            default:
                return config.bridgePort > 0 ? config.bridgePort : 9043;
        }
    }

    private boolean isWebSocket(String scheme) {
        return scheme.equals("ws") || scheme.equals("wss")
                || scheme.equals("http") || scheme.equals("https");
    }

    /** Create a connected TCP or TLS socket for the given endpoint. */
    private Socket createSocket(URI uri, int port) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean tls = scheme.equals("tls") || scheme.equals("wss") || scheme.equals("https");
        String host = uri.getHost();

        if (tls) {
            SSLSocket ssl = (SSLSocket) sslContext().getSocketFactory().createSocket();
            if (!config.tlsInsecure) {
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                try {
                    params.setServerNames(Collections.singletonList(new SNIHostName(host)));
                } catch (IllegalArgumentException ignored) {
                    // Host is an IP literal; SNI is not applicable.
                }
                ssl.setSSLParameters(params);
            }
            ssl.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return ssl;
        }

        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        return s;
    }

    private SSLContext sslContext() throws IOException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            if (config.tlsInsecure) {
                ctx.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
            } else {
                ctx.init(null, null, null);
            }
            return ctx;
        } catch (Exception e) {
            throw new IOException("Could not initialize TLS: " + e.getMessage(), e);
        }
    }

    /** Accept any certificate when {@code tlsInsecure} is enabled (self-signed/lab use). */
    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    /** Send the shared token on a fresh socket and wait for the bridge's ack. */
    private boolean authenticateTokenOn(Transport t) throws IOException {
        int id = nextId();
        JSONObject req = new JSONObject();
        try {
            req.put("jsonrpc", "2.0");
            req.put("id", id);
            req.put("method", "cursor_keyboard/authenticate");
            JSONObject params = new JSONObject();
            params.put("token", config.sharedToken);
            req.put("params", params);
        } catch (JSONException e) {
            return false;
        }
        t.write(req.toString());

        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        String line;
        try {
            while ((line = t.read()) != null && System.currentTimeMillis() < deadline) {
                if (line.isEmpty()) {
                    continue;
                }
                JSONObject resp = new JSONObject(new JSONTokener(line));
                if (resp.optInt("id", -1) == id) {
                    return !resp.has("error") && resp.optJSONObject("result") != null;
                }
            }
        } catch (JSONException e) {
            return false;
        }
        return false;
    }

    private void send(JSONObject msg) {
        synchronized (writeLock) {
            if (transport != null) {
                try {
                    transport.write(msg.toString());
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void readLoop() {
        try {
            String line;
            while (running && (line = transport.read()) != null) {
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
        Transport t = transport;
        if (t != null) {
            try {
                t.close();
            } catch (IOException ignored) {
            }
        }
    }

    private int nextId() {
        return idSeq.getAndIncrement();
    }
}
