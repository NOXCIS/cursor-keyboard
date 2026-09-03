#!/bin/sh
# termux_context_hook.sh -- refresh the Cursor Keyboard context file after
# every command. Source this from ~/.bashrc (bash) or ~/.zshrc (zsh).

CONTEXT_FILE="${CURSOR_CONTEXT_FILE:-$HOME/.config/cursor-keyboard/context.json}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

refresh_context() {
  python3 "$SCRIPT_DIR/termux_context.py" --out "$CONTEXT_FILE" >/dev/null 2>&1
}

# zsh
if [ -n "${ZSH_VERSION:-}" ]; then
  autoload -Uz add-zsh-hook
  add-zsh-hook precmd refresh_context
fi

# bash (PROMPT_COMMAND)
if [ -n "${BASH_VERSION:-}" ]; then
  PROMPT_COMMAND="refresh_context${PROMPT_COMMAND:+;$PROMPT_COMMAND}"
fi
