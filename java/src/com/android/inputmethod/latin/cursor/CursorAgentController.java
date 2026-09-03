package com.android.inputmethod.latin.cursor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Orchestrates one Cursor-agent run for the IME: reads config, gets shell
 * context from the bridge, builds the prompt, starts the session, and routes
 * streamed replies + permission requests to the {@link AgentPanelView}.
 *
 * <p>Client callbacks arrive on a background thread; all UI updates are posted
 * to the main thread.
 */
public class CursorAgentController implements AgentPanelView.Listener {
    /** Lets the controller read the active text and commit text into the field. */
    public interface InputBridge {
        String getCurrentText();
        void commitText(String text);
    }

    private final Context mContext;
    private final AgentPanelView mPanel;
    private final InputBridge mInput;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private CursorAgentClient mClient;
    private boolean mRunning;
    private AgentConfig mConfig;
    private final StringBuilder mStream = new StringBuilder();

    public CursorAgentController(Context context, AgentPanelView panel, InputBridge input) {
        mContext = context.getApplicationContext();
        mPanel = panel;
        mInput = input;
        panel.setListener(this);
    }

    public boolean isRunning() {
        return mRunning;
    }

    public void toggle() {
        if (mRunning || mPanel.isShowing()) {
            stop();
        } else {
            start();
        }
    }

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        mStream.setLength(0);
        mConfig = AgentConfig.load(mContext);
        mPanel.expand();
        mPanel.setRunning(true);
        mPanel.setContent("");
        mPanel.setStatus("Connecting...");

        Thread worker = new Thread(this::runSession, "cursor-agent-run");
        worker.setDaemon(true);
        worker.start();
    }

    private void runSession() {
        CursorAgentClient client = new CursorAgentClient();
        mClient = client;
        String sessionId = null;
        try {
            client.setConfig(mConfig);
            if (!authenticateIfNeeded(client)) {
                return;
            }
            sessionId = client.connectBlocking(mConfig, new ClientListener());
            if (sessionId == null) {
                postError("Could not establish an agent session.");
                return;
            }
            String contextJson = null;
            if (mConfig.captureContext) {
                contextJson = client.requestContext();
            }
            String prompt = buildPrompt(contextJson, mInput.getCurrentText());
            postStatus("Agent running...");
            client.sendPrompt(sessionId, prompt);
        } catch (Exception e) {
            postError("Failed: " + e.getMessage());
        }
    }

    /** Push the API key to the bridge if one was saved; a no-op when none is configured. */
    private boolean authenticateIfNeeded(CursorAgentClient client) {
        if (!mConfig.hasApiKey()) {
            return true;
        }
        if (!KeyVault.isUnlocked()) {
            postError("Master key is locked. Open Cursor Agent Settings and unlock it.");
            return false;
        }
        try {
            String key = KeyVault.decryptWithCached(mConfig.apiKeyEnc);
            return client.authenticate(key);
        } catch (Exception e) {
            postError("Authentication failed: " + e.getMessage());
            return false;
        }
    }

    private String buildPrompt(String contextJson, String inputText) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are helping with a command in a shell on an Android device.\n");
        if (contextJson != null) {
            sb.append("\nCurrent shell context:\n").append(contextJson).append("\n");
        }
        sb.append("\nI am typing right now:\n")
                .append(inputText == null || inputText.isEmpty() ? "(empty)" : inputText)
                .append("\n");
        if (mConfig.model != null && !mConfig.model.isEmpty()) {
            sb.append("\nPlease use this model: ").append(mConfig.model).append("\n");
        }
        sb.append("\nHelp me with what I am doing. If you propose a ready-to-run command, ")
                .append("put it in a single fenced code block so I can insert it. Be concise.\n");
        return sb.toString();
    }

    // ---- Client callbacks (background thread) ------------------------------------

    private final class ClientListener implements CursorAgentListener {
        @Override
        public void onAgentStreamText(String text) {
            mStream.append(text);
            mHandler.post(() -> mPanel.appendContent(text));
        }

        @Override
        public void onAgentPromptComplete() {
            mRunning = false;
            mHandler.post(() -> {
                mPanel.setRunning(false);
                mPanel.setStatus("Done");
            });
        }

        @Override
        public void onAgentError(String message) {
            mRunning = false;
            postError(message);
        }

        @Override
        public void onAgentPermissionRequest(int requestId, String title, String details) {
            if (mConfig.autoApprove) {
                CursorAgentClient c = mClient;
                if (c != null) {
                    c.respondPermission(requestId, true);
                    mHandler.post(() -> mPanel.setStatus("Auto-approved a tool call"));
                }
            } else {
                mHandler.post(() -> mPanel.showPermission(requestId, title, details));
            }
        }
    }

    // ---- Panel listener (UI thread) ---------------------------------------------

    @Override
    public void onAgentToggle() {
        toggle();
    }

    @Override
    public void onAgentStop() {
        stop();
    }

    @Override
    public void onAgentInsert(String text) {
        if (text != null && !text.isEmpty()) {
            mInput.commitText(text);
        }
    }

    @Override
    public void onAgentPermission(int requestId, boolean allowed) {
        CursorAgentClient c = mClient;
        if (c != null) {
            c.respondPermission(requestId, allowed);
        }
    }

    // ---- Helpers ----------------------------------------------------------------

    public void stop() {
        try {
            if (mClient != null) {
                mClient.disconnect();
            }
        } finally {
            mClient = null;
            mRunning = false;
            mHandler.post(() -> mPanel.collapse());
        }
    }

    private void postStatus(final String status) {
        mHandler.post(() -> mPanel.setStatus(status));
    }

    private void postError(final String message) {
        mRunning = false;
        mHandler.post(() -> {
            mPanel.setRunning(false);
            mPanel.setStatus(message);
        });
    }
}
