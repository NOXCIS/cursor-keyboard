package com.android.inputmethod.latin.cursor;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.inputmethod.latin.R;

/**
 * Inline panel that shows the streaming reply from the Cursor agent and lets the
 * user insert text, stop the run, and approve/deny tool permission requests.
 *
 * <p>The header bar (with the Cursor button + status) is always visible at the
 * top of the keyboard; the streaming content expands/collapses around a session.
 */
public class AgentPanelView extends LinearLayout {
    public interface Listener {
        void onAgentToggle();
        void onAgentStop();
        void onAgentInsert(String text);
        void onAgentPermission(int requestId, boolean allowed);
    }

    private TextView mStatusText;
    private TextView mContentText;
    private ScrollView mScroll;
    private TextView mPermissionText;
    private View mContentContainer;
    private View mPermissionRow;
    private Button mStopButton;
    private Listener mListener;
    private int mCurrentRequestId = -1;

    public AgentPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.agent_panel, this, true);

        mStatusText = findViewById(R.id.agent_status_text);
        mContentText = findViewById(R.id.agent_content_text);
        mScroll = findViewById(R.id.agent_scroll);
        mPermissionText = findViewById(R.id.agent_permission_text);
        mContentContainer = findViewById(R.id.agent_content_container);
        mPermissionRow = findViewById(R.id.agent_permission_row);
        mStopButton = findViewById(R.id.agent_stop_button);

        findViewById(R.id.agent_toggle_button).setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onAgentToggle();
            }
        });
        mStopButton.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onAgentStop();
            }
        });
        findViewById(R.id.agent_insert_button).setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onAgentInsert(mContentText.getText().toString().trim());
            }
        });
        findViewById(R.id.agent_clear_button).setOnClickListener(v -> setContent(""));

        Button allow = findViewById(R.id.agent_permission_allow);
        Button deny = findViewById(R.id.agent_permission_deny);
        allow.setOnClickListener(v -> respondPermission(true));
        deny.setOnClickListener(v -> respondPermission(false));
    }

    private void respondPermission(boolean allowed) {
        hidePermission();
        if (mListener != null && mCurrentRequestId >= 0) {
            mListener.onAgentPermission(mCurrentRequestId, allowed);
        }
        mCurrentRequestId = -1;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /** Expand to show the streaming content area (starts a session). */
    public void expand() {
        mContentContainer.setVisibility(View.VISIBLE);
    }

    /** Collapse to just the header bar. */
    public void collapse() {
        mContentContainer.setVisibility(View.GONE);
        hidePermission();
    }

    public boolean isShowing() {
        return mContentContainer.getVisibility() == View.VISIBLE;
    }

    public void setRunning(boolean running) {
        mStopButton.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    public void setStatus(String status) {
        mStatusText.setText(status);
    }

    public void setContent(String text) {
        mContentText.setText(text);
        scrollToBottom();
    }

    public void appendContent(String text) {
        mContentText.append(text);
        scrollToBottom();
    }

    private void scrollToBottom() {
        mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
    }

    public void showPermission(int requestId, String title, String details) {
        mCurrentRequestId = requestId;
        String text = title == null ? "Run tool" : title;
        if (details != null && !details.isEmpty()) {
            text += "\n" + details;
        }
        mPermissionText.setText(text);
        mPermissionRow.setVisibility(View.VISIBLE);
    }

    public void hidePermission() {
        mPermissionRow.setVisibility(View.GONE);
    }
}
