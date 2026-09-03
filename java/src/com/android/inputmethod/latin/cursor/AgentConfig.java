package com.android.inputmethod.latin.cursor;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Configuration for the Cursor-agent connection, backed by a dedicated
 * {@link SharedPreferences} store so we don't entangle with LatinIME's settings.
 */
public final class AgentConfig {
    public static final String PREFS = "cursor_agent_prefs";

    public static final String KEY_HOST = "bridge_host";
    public static final String KEY_PORT = "bridge_port";
    public static final String KEY_WORKSPACE = "workspace";
    public static final String KEY_MODEL = "model";
    public static final String KEY_API_KEY_FILE = "api_key_file";
    public static final String KEY_AUTO_APPROVE = "auto_approve";
    public static final String KEY_CAPTURE_CONTEXT = "capture_context";

    public final String bridgeHost;
    public final int bridgePort;
    public final String workspace;
    public final String model;
    public final String apiKeyFile;
    public final boolean autoApprove;
    public final boolean captureContext;

    private AgentConfig(String bridgeHost, int bridgePort, String workspace, String model,
            String apiKeyFile, boolean autoApprove, boolean captureContext) {
        this.bridgeHost = bridgeHost;
        this.bridgePort = bridgePort;
        this.workspace = workspace;
        this.model = model;
        this.apiKeyFile = apiKeyFile;
        this.autoApprove = autoApprove;
        this.captureContext = captureContext;
    }

    public static AgentConfig load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String host = sp.getString(KEY_HOST, "127.0.0.1");
        int port = sp.getInt(KEY_PORT, 9043);
        String workspace = sp.getString(KEY_WORKSPACE, "");
        String model = sp.getString(KEY_MODEL, "");
        String apiKeyFile = sp.getString(KEY_API_KEY_FILE, "");
        boolean autoApprove = sp.getBoolean(KEY_AUTO_APPROVE, true);
        boolean captureContext = sp.getBoolean(KEY_CAPTURE_CONTEXT, true);
        return new AgentConfig(host, port, workspace, model, apiKeyFile, autoApprove, captureContext);
    }
}
