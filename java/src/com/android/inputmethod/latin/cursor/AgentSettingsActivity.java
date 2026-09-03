package com.android.inputmethod.latin.cursor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.android.inputmethod.latin.R;

import org.json.JSONObject;

/**
 * Setup screen for the Cursor-agent bridge: shows live bridge/agent health,
 * lets the user authenticate by encrypting their Cursor API key under a
 * user-set master key (see {@link KeyVault}), and stores connection + behavior
 * settings.
 */
public class AgentSettingsActivity extends Activity {
    private TextView mStatusDot;
    private TextView mStatusTitle;
    private TextView mStatusDetail;
    private EditText mHost;
    private EditText mPort;
    private RadioGroup mConnType;
    private RadioButton mConnLocal;
    private RadioButton mConnExternal;
    private View mLocalSection;
    private View mExternalSection;
    private EditText mEndpointUrl;
    private EditText mSharedToken;
    private CheckBox mTlsInsecure;
    private EditText mWorkspace;
    private EditText mModel;
    private EditText mApiKey;
    private CheckBox mApiKeyShow;
    private EditText mMasterKey;
    private TextView mAuthStatus;
    private Button mAuthButton;
    private CheckBox mAutoApprove;
    private CheckBox mCaptureContext;
    private Button mInstallButton;
    private TextView mInstallStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.agent_settings);

        mStatusDot = findViewById(R.id.agent_status_dot);
        mStatusTitle = findViewById(R.id.agent_status_title);
        mStatusDetail = findViewById(R.id.agent_status_detail);
        mHost = findViewById(R.id.agent_host);
        mPort = findViewById(R.id.agent_port);
        mConnType = findViewById(R.id.agent_conn_type);
        mConnLocal = findViewById(R.id.agent_conn_local);
        mConnExternal = findViewById(R.id.agent_conn_external);
        mLocalSection = findViewById(R.id.agent_local_section);
        mExternalSection = findViewById(R.id.agent_external_section);
        mEndpointUrl = findViewById(R.id.agent_endpoint_url);
        mSharedToken = findViewById(R.id.agent_shared_token);
        mTlsInsecure = findViewById(R.id.agent_tls_insecure);
        mWorkspace = findViewById(R.id.agent_workspace);
        mModel = findViewById(R.id.agent_model);
        mApiKey = findViewById(R.id.agent_api_key);
        mApiKeyShow = findViewById(R.id.agent_api_key_show);
        mMasterKey = findViewById(R.id.agent_master_key);
        mAuthStatus = findViewById(R.id.agent_auth_status);
        mAuthButton = findViewById(R.id.agent_auth_button);
        mAutoApprove = findViewById(R.id.agent_auto_approve);
        mCaptureContext = findViewById(R.id.agent_capture_context);
        mInstallButton = findViewById(R.id.agent_install_button);
        mInstallStatus = findViewById(R.id.agent_install_status);

        loadValues();
        refreshAuthUi();

        mConnType.setOnCheckedChangeListener((group, checkedId) -> updateConnSections());
        mApiKeyShow.setOnCheckedChangeListener((v, checked) -> toggleApiKeyVisibility(checked));

        Button save = findViewById(R.id.agent_save);
        save.setOnClickListener(this::saveValues);

        mAuthButton.setOnClickListener(this::onAuthAction);
        findViewById(R.id.agent_test_button).setOnClickListener(v -> runHealthCheck());
        mInstallButton.setOnClickListener(this::onInstallAction);
    }

    private void loadValues() {
        AgentConfig cfg = AgentConfig.load(this);
        mHost.setText(cfg.bridgeHost);
        mPort.setText(String.valueOf(cfg.bridgePort));
        if (AgentConfig.TYPE_EXTERNAL.equals(cfg.connectionType)) {
            mConnExternal.setChecked(true);
        } else {
            mConnLocal.setChecked(true);
        }
        mEndpointUrl.setText(cfg.endpointUrl);
        mSharedToken.setText(cfg.sharedToken);
        mTlsInsecure.setChecked(cfg.tlsInsecure);
        mWorkspace.setText(cfg.workspace);
        mModel.setText(cfg.model);
        mAutoApprove.setChecked(cfg.autoApprove);
        mCaptureContext.setChecked(cfg.captureContext);
        updateConnSections();
    }

    private void updateConnSections() {
        boolean external = mConnType.getCheckedRadioButtonId() == R.id.agent_conn_external;
        mLocalSection.setVisibility(external ? View.GONE : View.VISIBLE);
        mExternalSection.setVisibility(external ? View.VISIBLE : View.GONE);
    }

    private void saveValues(View ignored) {
        getSharedPreferences(AgentConfig.PREFS, MODE_PRIVATE).edit()
                .putString(AgentConfig.KEY_HOST, mHost.getText().toString().trim())
                .putInt(AgentConfig.KEY_PORT, parseInt(mPort.getText().toString(), 9043))
                .putString(AgentConfig.KEY_CONN_TYPE,
                        mConnType.getCheckedRadioButtonId() == R.id.agent_conn_external
                                ? AgentConfig.TYPE_EXTERNAL : AgentConfig.TYPE_LOCAL)
                .putString(AgentConfig.KEY_ENDPOINT_URL, mEndpointUrl.getText().toString().trim())
                .putString(AgentConfig.KEY_SHARED_TOKEN, mSharedToken.getText().toString().trim())
                .putBoolean(AgentConfig.KEY_TLS_INSECURE, mTlsInsecure.isChecked())
                .putString(AgentConfig.KEY_WORKSPACE, mWorkspace.getText().toString().trim())
                .putString(AgentConfig.KEY_MODEL, mModel.getText().toString().trim())
                .putBoolean(AgentConfig.KEY_AUTO_APPROVE, mAutoApprove.isChecked())
                .putBoolean(AgentConfig.KEY_CAPTURE_CONTEXT, mCaptureContext.isChecked())
                .apply();
        Toast.makeText(this, "Cursor Agent settings saved", Toast.LENGTH_SHORT).show();
    }

    // ---- Authentication --------------------------------------------------------

    private void refreshAuthUi() {
        AgentConfig cfg = AgentConfig.load(this);
        boolean hasKey = cfg.hasApiKey();
        boolean unlocked = hasKey && KeyVault.isUnlocked();

        if (!hasKey) {
            mApiKey.setVisibility(View.VISIBLE);
            mApiKeyShow.setVisibility(View.VISIBLE);
            mMasterKey.setVisibility(View.VISIBLE);
            mAuthStatus.setText("No key saved");
            mAuthStatus.setTextColor(getColor(R.color.agent_gray));
            mAuthButton.setText("Save & encrypt");
        } else if (!unlocked) {
            mApiKey.setVisibility(View.GONE);
            mApiKeyShow.setVisibility(View.GONE);
            mMasterKey.setVisibility(View.VISIBLE);
            mAuthStatus.setText("Protected \u2014 needs unlock");
            mAuthStatus.setTextColor(getColor(R.color.agent_status_warn));
            mAuthButton.setText("Unlock");
        } else {
            mApiKey.setVisibility(View.GONE);
            mApiKeyShow.setVisibility(View.GONE);
            mMasterKey.setVisibility(View.GONE);
            mAuthStatus.setText("Protected \u2014 unlocked");
            mAuthStatus.setTextColor(getColor(R.color.agent_status_ok));
            mAuthButton.setText("Lock");
        }
    }

    private void onAuthAction(View ignored) {
        AgentConfig cfg = AgentConfig.load(this);
        if (!cfg.hasApiKey()) {
            saveAndEncrypt();
        } else if (!KeyVault.isUnlocked()) {
            unlockWithMasterKey(cfg);
        } else {
            KeyVault.lock();
            Toast.makeText(this, "Locked", Toast.LENGTH_SHORT).show();
            refreshAuthUi();
        }
    }

    private void saveAndEncrypt() {
        String apiKey = mApiKey.getText().toString().trim();
        String masterKey = mMasterKey.getText().toString();
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Enter your Cursor API key", Toast.LENGTH_SHORT).show();
            return;
        }
        if (masterKey.length() < 8) {
            Toast.makeText(this, "Master key must be at least 8 characters",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int iterations = KeyVault.DEFAULT_ITERATIONS;
            String blob = KeyVault.encrypt(apiKey, masterKey, iterations);
            getSharedPreferences(AgentConfig.PREFS, MODE_PRIVATE).edit()
                    .putString(AgentConfig.KEY_API_KEY_ENC, blob)
                    .putInt(AgentConfig.KEY_API_KEY_ITER, iterations)
                    .apply();
            mApiKey.setText("");
            mMasterKey.setText("");
            Toast.makeText(this, "Crypto: key encrypted with your master key",
                    Toast.LENGTH_SHORT).show();
            refreshAuthUi();
        } catch (Exception e) {
            Toast.makeText(this, "Could not encrypt key: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void unlockWithMasterKey(AgentConfig cfg) {
        String masterKey = mMasterKey.getText().toString();
        if (masterKey.isEmpty()) {
            Toast.makeText(this, "Enter your master key", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            KeyVault.decrypt(cfg.apiKeyEnc, masterKey, cfg.apiKeyIter);
            mMasterKey.setText("");
            Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show();
            refreshAuthUi();
        } catch (Exception e) {
            Toast.makeText(this, "Wrong master key", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleApiKeyVisibility(boolean show) {
        if (show) {
            mApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
            mApiKey.setTransformationMethod(null);
        } else {
            mApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            mApiKey.setTransformationMethod(new PasswordTransformationMethod());
        }
    }

    // ---- Health check ----------------------------------------------------------

    private void runHealthCheck() {
        final AgentConfig cfg = buildConfigFromFields();
        mStatusDot.setTextColor(getColor(R.color.agent_gray));
        mStatusTitle.setText("Checking\u2026");
        mStatusDetail.setText("Contacting " + endpointLabel(cfg));

        new Thread(() -> {
            CursorAgentClient client = new CursorAgentClient();
            client.setConfig(cfg);
            try {
                if (!client.ping()) {
                    runOnUiThread(() -> {
                        mStatusDot.setTextColor(getColor(R.color.agent_status_error));
                        mStatusTitle.setText("Bridge not reachable");
                        mStatusDetail.setText("Ensure the bridge is running on "
                                + endpointLabel(cfg));
                    });
                    return;
                }
                JSONObject status = client.requestStatus();
                if (status == null) {
                    runOnUiThread(() -> {
                        mStatusDot.setTextColor(getColor(R.color.agent_status_error));
                        mStatusTitle.setText("No status from bridge");
                        mStatusDetail.setText("The bridge answered but returned no status.");
                    });
                    return;
                }
                runOnUiThread(() -> renderStatus(status));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    mStatusDot.setTextColor(getColor(R.color.agent_status_error));
                    mStatusTitle.setText("Error");
                    mStatusDetail.setText(e.getMessage());
                });
            }
        }, "cursor-status-check").start();
    }

    private void renderStatus(JSONObject status) {
        boolean installed = status.optBoolean("agent_installed", false);
        String version = status.optString("agent_version", "");
        boolean authenticated = status.optBoolean("authenticated", false);
        boolean fromDevice = status.optBoolean("authenticated_from_device", false);
        boolean wsExists = status.optBoolean("workspace_exists", false);
        String workspace = status.optString("workspace", "");

        if (installed && authenticated) {
            statusDot(R.color.agent_status_ok, "Ready");
        } else if (installed) {
            statusDot(R.color.agent_status_warn, "Setup incomplete");
        } else {
            statusDot(R.color.agent_status_error, "Agent missing");
        }

        StringBuilder detail = new StringBuilder();
        detail.append("Bridge: reachable\n");
        detail.append("Agent: ").append(installed ? version : "not installed");
        if (!installed) {
            detail.append(" \u2014 tap \"Show setup command\" below");
        }
        detail.append("\nAuth: ").append(authenticated
                ? (fromDevice ? "key set from this device" : "uses bridge env key")
                : "not configured");
        if (!authenticated) {
            detail.append(" \u2014 set your API key below");
        }
        boolean tls = status.optBoolean("tls", false);
        boolean tokenRequired = status.optBoolean("token_required", false);
        if (tls || tokenRequired) {
            detail.append("\nTransport: ");
            if (tls) {
                detail.append("TLS");
            }
            if (tokenRequired) {
                detail.append(tls ? " + token" : "token required");
            }
        }
        String endpoint = status.optString("endpoint", "");
        if (!endpoint.isEmpty()) {
            detail.append("\nEndpoint: ").append(endpoint);
        }
        if (!workspace.isEmpty()) {
            detail.append("\nWorkspace: ").append(workspace);
            if (!wsExists) {
                detail.append(" (missing)");
            }
        }
        mStatusDetail.setText(detail.toString());
    }

    // ---- Install agent (command to run in the chroot) --------------------------

    private void onInstallAction(View ignored) {
        boolean external = mConnType.getCheckedRadioButtonId() == R.id.agent_conn_external;
        if (external) {
            showSetupCommandDialog("Run this on the remote bridge host",
                    buildExternalSetupCommand(),
                    "Run the bridge on the machine hosting the agent (e.g. a server or a Cloudflare Tunnel origin), then point the keyboard at its public URL.");
            return;
        }
        String host = mHost.getText().toString().trim();
        if (host.isEmpty()) {
            host = "127.0.0.1";
        }
        String port = mPort.getText().toString().trim();
        if (port.isEmpty()) {
            port = "9043";
        }
        String workspace = mWorkspace.getText().toString().trim();
        if (workspace.isEmpty()) {
            workspace = "~";
        }
        showSetupCommandDialog("Run this inside the chroot",
                buildSetupCommand(host, port, workspace),
                "Copy the command, run it in the chroot (Termux), then tap \u201cTest connection\u201d.");
    }

    /**
     * Build a copy-paste shell script for the user to run inside the chroot. This
     * is the most compatible path: it works regardless of the bridge version, so we
     * avoid needing to redeploy the bridge before it can update itself.
     */
    private String buildSetupCommand(String host, String port, String workspace) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 1) Install the Cursor CLI (inside the chroot)\n");
        sb.append("curl -fsSL https://cursor.com/install | bash\n");
        sb.append("export PATH=\"$HOME/.local/bin:$PATH\"\n");
        sb.append("agent --version\n");
        sb.append("\n# 2) If a dynamic-linker error appears, install the arm64 libs:\n");
        sb.append("sudo apt-get update && sudo apt-get install -y libc6:arm64 libgcc-s1:arm64 libidn2-0:arm64\n");
        sb.append("\n# 3) (Re)start the bridge with the current settings\n");
        sb.append("pkill -f cursor_acp_bridge.py 2>/dev/null; sleep 1\n");
        sb.append("cd bridge\n");
        sb.append("export CURSOR_API_KEY=\"$(cat ~/.config/cursor-keyboard/key 2>/dev/null)\"\n");
        sb.append("nohup python3 cursor_acp_bridge.py --host ").append(host)
                .append(" --port ").append(port)
                .append(" --workspace ").append(workspace)
                .append(" >/dev/null 2>&1 &\n");
        return sb.toString();
    }

    private String buildExternalSetupCommand() {
        String workspace = mWorkspace.getText().toString().trim();
        if (workspace.isEmpty()) {
            workspace = "~";
        }
        String token = mSharedToken.getText().toString().trim();
        StringBuilder sb = new StringBuilder();
        sb.append("# On the machine that will host the agent (not the phone):\n");
        sb.append("curl -fsSL https://cursor.com/install | bash\n");
        sb.append("export PATH=\"$HOME/.local/bin:$PATH\"\n");
        sb.append("agent --version\n");
        sb.append("\n# Serve the bridge to the phone. Two ways:\n");
        sb.append("#  A) Put it behind Cloudflare Tunnel (WebSocket): set the keyboard\n");
        sb.append("#     URL to wss://<tunnel-hostname> (or https://...).\n");
        sb.append("#  B) Expose it directly over TLS: set the URL to tls://<host>:9043.\n");
        sb.append("\ncd bridge\n");
        sb.append("export CURSOR_API_KEY=\"$(cat ~/.config/cursor-keyboard/key 2>/dev/null)\"\n");
        sb.append("nohup python3 cursor_acp_bridge.py --host 0.0.0.0 --port 9043 \\\n");
        sb.append("  --workspace ").append(workspace).append(" \\\n");
        if (!token.isEmpty()) {
            sb.append("  --token '").append(token).append("' \\\n");
        }
        sb.append("  >/dev/null 2>&1 &\n");
        sb.append("\n# Then run: cloudflared tunnel --url http://localhost:9043\n");
        sb.append("# and point the keyboard at the tunnel URL + the same shared token.\n");
        return sb.toString();
    }

    private void showSetupCommandDialog(String title, String cmd, String statusMsg) {
        TextView tv = new TextView(this);
        tv.setText(cmd);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextSize(12f);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(tv)
                .setPositiveButton("Copy", (d, w) -> copyToClipboard(cmd))
                .setNegativeButton("Close", null)
                .show();
        mInstallStatus.setText(statusMsg);
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("cursor-chroot-setup", text));
        Toast.makeText(this, "Setup command copied", Toast.LENGTH_SHORT).show();
    }

    private AgentConfig buildConfigFromFields() {
        int port = parseInt(mPort.getText().toString(), 9043);
        boolean external = mConnType.getCheckedRadioButtonId() == R.id.agent_conn_external;
        return new AgentConfig(
                mHost.getText().toString().trim(),
                port,
                external ? AgentConfig.TYPE_EXTERNAL : AgentConfig.TYPE_LOCAL,
                mEndpointUrl.getText().toString().trim(),
                mSharedToken.getText().toString().trim(),
                mTlsInsecure.isChecked(),
                mWorkspace.getText().toString().trim(),
                mModel.getText().toString().trim(),
                "",
                KeyVault.DEFAULT_ITERATIONS,
                mAutoApprove.isChecked(),
                mCaptureContext.isChecked());
    }

    private String endpointLabel(AgentConfig cfg) {
        if (cfg.isExternal() && cfg.endpointUrl != null && !cfg.endpointUrl.trim().isEmpty()) {
            return cfg.endpointUrl.trim();
        }
        return cfg.bridgeHost + ":" + cfg.bridgePort;
    }

    private void statusDot(int colorRes, String title) {
        mStatusDot.setTextColor(getColor(colorRes));
        mStatusTitle.setText(title);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
