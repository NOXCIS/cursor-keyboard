package com.android.inputmethod.latin.cursor;

/**
 * Callbacks from {@link CursorAgentClient} to the controller/IME, delivered on
 * the client's reader thread.
 */
public interface CursorAgentListener {
    /** A new text chunk was streamed from the agent session. */
    void onAgentStreamText(String text);

    /** The agent finished replying to the current prompt. */
    void onAgentPromptComplete();

    /** The session hit an error / the connection was lost. */
    void onAgentError(String message);

    /** The agent requested permission to run a tool. */
    void onAgentPermissionRequest(int requestId, String title, String details);
}
