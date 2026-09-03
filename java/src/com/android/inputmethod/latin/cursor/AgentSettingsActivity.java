package com.android.inputmethod.latin.cursor;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import com.android.inputmethod.latin.R;

/**
 * Standalone settings screen for the Cursor-agent bridge connection. Stores
 * values in the {@code cursor_agent_prefs} preferences used by
 * {@link AgentConfig}.
 */
public class AgentSettingsActivity extends Activity {
    private EditText mHost;
    private EditText mPort;
    private EditText mWorkspace;
    private EditText mModel;
    private EditText mApiKey;
    private CheckBox mAutoApprove;
    private CheckBox mCaptureContext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.agent_settings);

        mHost = findViewById(R.id.agent_host);
        mPort = findViewById(R.id.agent_port);
        mWorkspace = findViewById(R.id.agent_workspace);
        mModel = findViewById(R.id.agent_model);
        mApiKey = findViewById(R.id.agent_api_key);
        mAutoApprove = findViewById(R.id.agent_auto_approve);
        mCaptureContext = findViewById(R.id.agent_capture_context);

        loadValues();

        Button save = findViewById(R.id.agent_save);
        save.setOnClickListener(this::saveValues);
    }

    private void loadValues() {
        AgentConfig cfg = AgentConfig.load(this);
        mHost.setText(cfg.bridgeHost);
        mPort.setText(String.valueOf(cfg.bridgePort));
        mWorkspace.setText(cfg.workspace);
        mModel.setText(cfg.model);
        mApiKey.setText(cfg.apiKeyFile);
        mAutoApprove.setChecked(cfg.autoApprove);
        mCaptureContext.setChecked(cfg.captureContext);
    }

    private void saveValues(View ignored) {
        getSharedPreferences(AgentConfig.PREFS, MODE_PRIVATE).edit()
                .putString(AgentConfig.KEY_HOST, mHost.getText().toString().trim())
                .putInt(AgentConfig.KEY_PORT, parseInt(mPort.getText().toString(), 9043))
                .putString(AgentConfig.KEY_WORKSPACE, mWorkspace.getText().toString().trim())
                .putString(AgentConfig.KEY_MODEL, mModel.getText().toString().trim())
                .putString(AgentConfig.KEY_API_KEY_FILE, mApiKey.getText().toString().trim())
                .putBoolean(AgentConfig.KEY_AUTO_APPROVE, mAutoApprove.isChecked())
                .putBoolean(AgentConfig.KEY_CAPTURE_CONTEXT, mCaptureContext.isChecked())
                .apply();
        Toast.makeText(this, "Cursor Agent settings saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
