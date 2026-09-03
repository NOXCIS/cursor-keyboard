# Cursor Keyboard — on-device bridge + context

These scripts run inside the on-device **Debian Trixie/Sid chroot** (Termux) and
are the server side the keyboard connects to on loopback.

## What each file does

| File | Purpose |
|------|---------|
| `cursor_acp_bridge.py` | Spawns `agent acp` and relays ACP JSON-RPC to the keyboard over a TCP socket on `127.0.0.1`; also serves `cursor_keyboard/get_context`. |
| `termux_context.py` | Captures shell context (`pwd`, recent history, uname) as JSON. |
| `termux_context_hook.sh` | Auto-refreshes the context file after each command (zsh `precmd` / bash `PROMPT_COMMAND`). |

## 1. Install Cursor CLI in the chroot

```bash
# Inside the Debian chroot (Trixie/Sid, aarch64)
curl -fsSL https://cursor.com/install | bash
export PATH="$HOME/.local/bin:$PATH"
agent --version        # sanity check: must print a version, not a loader error
ldd "$HOME/.local/share/cursor-agent/versions/$(ls "$HOME/.local/share/cursor-agent/versions" | tail -1)/node" 2>&1 | grep -i "not found" || true
```

If `agent --version` fails with a dynamic-linker error, install the arm64
runtime deps:

```bash
sudo apt-get update && sudo apt-get install -y libc6:arm64 libgcc-s1:arm64 libidn2-0:arm64
```

## 2. Authenticate

Use a **user-scoped** Cursor API key (Cursor Dashboard -> API Keys) and pass it
to the bridge. Do not commit it; keep it in the chroot, e.g. in
`~/.config/cursor-keyboard/key`:

```bash
mkdir -p ~/.config/cursor-keyboard
echo -n 'YOUR_USER_SCOPED_KEY' > ~/.config/cursor-keyboard/key
chmod 600 ~/.config/cursor-keyboard/key
```

Or log in interactively once: `agent login`.

## 3. Run the bridge

```bash
export PATH="$HOME/.local/bin:$PATH"
export CURSOR_API_KEY="$(cat ~/.config/cursor-keyboard/key)"
python3 cursor_acp_bridge.py \
  --host 127.0.0.1 \
  --port 9043 \
  --workspace ~           # the area the agent operates on
```

You should see: `[bridge] listening on 127.0.0.1:9043 (agent=agent)`.

To run it as a kept-alive service, use `nohup`, a `termux-wake-lock`, or a
systemd/pids service inside the chroot.

## 4. (Optional) Auto-refresh the context file

Source the hook in your shell so the context file stays fresh:

```bash
source termux_context_hook.sh
```

## 5. Configure the keyboard

In the keyboard's **Cursor Agent** settings, set:

- `Bridge host` = `127.0.0.1`
- `Bridge port` = `9043`
- (other options as desired)

Press the **Cursor** key while the terminal has focus to run an agent session
against the captured context.

## Troubleshooting

- **Cannot connect**: ensure the bridge is running and bound to `127.0.0.1`;
  the keyboard reads `Bridge host`/`Bridge port` from settings.
- **`agent acp` exits immediately**: check `agent --version` / auth; the bridge
  streams the agent's stderr into the terminal so you can see the error.
- **No context**: make sure the context hook ran, or press the Agent key in the
  terminal so the keyboard requests context from the bridge.
