# Cursor Keyboard — on-device bridge + context

These scripts run inside the on-device **Debian Trixie/Sid chroot** (Termux) and
are the server side the keyboard connects to on loopback. The bridge can also be
exposed outside the device (direct TLS, or behind Cloudflare Tunnel over
WebSocket) so the keyboard can reach an agent that is not running in the local
chroot.

## What each file does

| File | Purpose |
|------|---------|
| `cursor_acp_bridge.py` | Spawns `agent acp` and relays ACP JSON-RPC to the keyboard over `127.0.0.1`, serving `cursor_keyboard/get_context`; auto-detects newline-delimited TCP vs WebSocket and supports TLS + a shared token. |
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

## 5. Expose the bridge outside the device

The keyboard connects to `tcp://` / `tls://` for the local chroot and can also
connect to a remote/self-hosted bridge over the network. Two options:

### A. Direct TLS + shared token

```bash
python3 cursor_acp_bridge.py --host 0.0.0.0 --port 9043 \
  --workspace ~ \
  --tls-cert server.crt --tls-key server.key \
  --token 'REPLACE_WITH_A_SHARED_SECRET'
```

Point the keyboard at `tls://<host>:9043` with the same shared token. The
bridge rejects any client that does not present the token before an agent runs.
Use a certificate whose SAN matches the host and IP you connect to, or toggle
"Allow self-signed / unverified TLS" in the keyboard settings for lab use.

### B. Cloudflare Tunnel (WebSocket) — recommended for public access

Cloudflare Tunnel exposes an HTTPS/WebSocket hostname, so the bridge speaks the
same ACP stream inside WebSocket text frames and needs only its HTTP origin:

```bash
# Run the bridge on the origin host with a shared token
python3 cursor_acp_bridge.py --host 127.0.0.1 --port 9043 \
  --workspace ~ \
  --token 'REPLACE_WITH_A_SHARED_SECRET'

# In another terminal, tunnel it (cloudflared installs from https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
cloudflared tunnel --url http://localhost:9043
```

Then set the keyboard URL to `wss://<tunnel-hostname>` (or `https://...`) and
the same shared token. The keyboard connects over TLS to Cloudflare, which
forwards the WebSocket upgrade to the bridge; all privileged work (agent,
context capture) stays on the bridge/origin side, never on the unprivileged
keyboard.

> Security: any externally reachable bridge MUST set a shared `--token`. TLS is
> provided by Cloudflare (option B) or by `--tls-cert/--tls-key` (option A).
> Binding to `0.0.0.0` without a token or TLS logs a warning — don't do it for a
> real deployment.

## 6. Configure the keyboard

In the keyboard's **Cursor Agent** settings:

- **Connection type** = `Local chroot (loopback)` — set `Bridge host` = `127.0.0.1`,
  `Bridge port` = `9043`.
- **Connection type** = `External agent URL` — set the **Agent URL** to
  `tls://host:9043` or `wss://<tunnel-hostname>`, plus the **Shared token** the
  bridge expects; optionally allow unverified TLS for a self-signed cert.

Press the **Cursor** key while the terminal has focus to run an agent session
against the captured context.

## Troubleshooting

- **Cannot connect**: ensure the bridge is running and reachable; the keyboard
  reads the connection type / Agent URL from settings. For a tunnel, confirm
  the `cloudflared` origin is healthy and the URL uses `wss://` or `https://`.
- **`agent acp` exits immediately**: check `agent --version` / auth; the bridge
  streams the agent's stderr into the terminal so you can see the error.
- **No context**: make sure the context hook ran, or press the Agent key in the
  terminal so the keyboard requests context from the bridge.
