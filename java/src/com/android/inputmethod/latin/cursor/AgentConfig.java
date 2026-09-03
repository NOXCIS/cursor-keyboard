package com.android.inputmethod.latin.cursor;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;

/**
 * Configuration for the Cursor-agent connection, backed by a dedicated
 * {@link SharedPreferences} store so we don't entangle with LatinIME's settings.
 *
 * <p>The keyboard can reach the agent two ways:
 * <ul>
 *   <li><b>Local chroot</b>: a plain loopback bridge on {@link #bridgeHost}:{@link #bridgePort},
 *       expected to be running inside the on-device chroot. No TLS, no token.</li>
 *   <li><b>External agent</b>: a remote/self-hosted bridge reached over the network, e.g.
 *       {@code tls://host:port} or {@code tcp://host:port}, protected by a shared {@link #sharedToken}
 *       and optionally TLS. This lets the keyboard talk to an agent that is NOT running in the
 *       local chroot while leaving all privileged work on the bridge side.</li>
 * </ul>
 */
public final class AgentConfig {
    public static final String PREFS = "cursor_agent_prefs";
    /** Connection type values. */
    public static final String TYPE_LOCAL = "local";
    public static final String TYPE_EXTERNAL = "external";

    public static final String KEY_HOST = "bridge_host";
    public static final String KEY_PORT = "bridge_port";
    public static final String KEY_CONN_TYPE = "conn_type";
    public static final String KEY_ENDPOINT_URL = "endpoint_url";
    public static final String KEY_SHARED_TOKEN = "shared_token";
    public static final String KEY_TLS_INSECURE = "tls_insecure";
    public static final String KEY_WORKSPACE = "workspace";
    public static final String KEY_MODEL = "model";
    public static final String KEY_API_KEY_ENC = "api_key_enc";
    public static final String KEY_API_KEY_ITER = "api_key_iter";
    public static final String KEY_AUTO_APPROVE = "auto_approve";
    public static final String KEY_CAPTURE_CONTEXT = "capture_context";

    /** Loopback bridge host (local mode). */
    public final String bridgeHost;
    /** Loopback bridge port (local mode). */
    public final int bridgePort;
    /** "local" or "external". */
    public final String connectionType;
    /** Full endpoint URL for external mode (e.g. tls://host:port). */
    public final String endpointUrl;
    /** Shared token the bridge requires on every connection (external mode). */
    public final String sharedToken;
    /** Skip TLS certificate/hostname verification (self-signed certs). Insecure. */
    public final boolean tlsInsecure;
    public final String workspace;
    public final String model;
    /** Base64 blob of the Cursor API key encrypted under the user's master key. */
    public final String apiKeyEnc;
    /** PBKDF2 iteration count used when {@link #apiKeyEnc} was created. */
    public final int apiKeyIter;
    public final boolean autoApprove;
    public final boolean captureContext;

    public AgentConfig(String bridgeHost, int bridgePort, String connectionType, String endpointUrl,
            String sharedToken, boolean tlsInsecure, String workspace, String model,
            String apiKeyEnc, int apiKeyIter, boolean autoApprove, boolean captureContext) {
        this.bridgeHost = bridgeHost;
        this.bridgePort = bridgePort;
        this.connectionType = connectionType;
        this.endpointUrl = endpointUrl;
        this.sharedToken = sharedToken;
        this.tlsInsecure = tlsInsecure;
        this.workspace = workspace;
        this.model = model;
        this.apiKeyEnc = apiKeyEnc;
        this.apiKeyIter = apiKeyIter;
        this.autoApprove = autoApprove;
        this.captureContext = captureContext;
    }

    public static AgentConfig load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String host = sp.getString(KEY_HOST, "127.0.0.1");
        int port = sp.getInt(KEY_PORT, 9043);
        String connType = sp.getString(KEY_CONN_TYPE, TYPE_LOCAL);
        String endpointUrl = sp.getString(KEY_ENDPOINT_URL, "");
        String sharedToken = sp.getString(KEY_SHARED_TOKEN, "");
        boolean tlsInsecure = sp.getBoolean(KEY_TLS_INSECURE, false);
        String workspace = sp.getString(KEY_WORKSPACE, "");
        String model = sp.getString(KEY_MODEL, "");
        String apiKeyEnc = sp.getString(KEY_API_KEY_ENC, "");
        int apiKeyIter = sp.getInt(KEY_API_KEY_ITER, KeyVault.DEFAULT_ITERATIONS);
        boolean autoApprove = sp.getBoolean(KEY_AUTO_APPROVE, true);
        boolean captureContext = sp.getBoolean(KEY_CAPTURE_CONTEXT, true);
        return new AgentConfig(host, port, connType, endpointUrl, sharedToken, tlsInsecure,
                workspace, model, apiKeyEnc, apiKeyIter, autoApprove, captureContext);
    }

    public boolean hasApiKey() {
        return apiKeyEnc != null && !apiKeyEnc.isEmpty();
    }

    public boolean hasSharedToken() {
        return sharedToken != null && !sharedToken.isEmpty();
    }

    public boolean isExternal() {
        return TYPE_EXTERNAL.equals(connectionType);
    }

    /**
     * Resolve the endpoint the client should connect to. In external mode this
     * uses {@link #endpointUrl} (defaulting the scheme to {@code tls://} if one is
     * omitted); otherwise it points at the loopback bridge. Returns {@code null}
     * when the URL cannot be parsed.
     */
    public URI endpointUri() {
        if (isExternal() && endpointUrl != null && !endpointUrl.trim().isEmpty()) {
            String url = endpointUrl.trim();
            if (!url.contains("://")) {
                url = "tls://" + url;
            }
            try {
                URI uri = URI.create(url);
                // A scheme with no authority (e.g. "tls:host:port") is invalid for a socket.
                if (uri.getHost() == null && uri.getRawAuthority() == null) {
                    return null;
                }
                return uri;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        try {
            return URI.create("tcp://" + bridgeHost + ":" + bridgePort);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
