# Cursor Keyboard — on-device setup & end-to-end verification

This is the runbook for setting up the **Cursor Keyboard** fork on a rooted
Android device (Odin) with a **Termux + Debian Trixie/Sid chroot**, and for
verifying the full flow: type in a terminal → press **Cursor** → context is
captured → agent streams a reply → you insert it.

> The keyboard APK side (build + integrate) lives in this repo's `java/` source.
> The server side (agent + context) lives in `bridge/`. The two talk over
> loopback `127.0.0.1:9043`.

## 0. Prerequisites

- Rooted device, adb access.
- A Debian Trixie/Sid chroot running on the device (e.g. `termux-chroot`, or a
  `proot`/`chroot` rootfs).
- The APK from `./gradlew assembleDebug`.

## 1. Build & install the keyboard APK

```bash
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/LatinIME-debug.apk
# If the stock LatinIME conflicts (same package):
adb shell pm uninstall --user 0 com.android.inputmethod.latin
adb install -r build/outputs/apk/debug/LatinIME-debug.apk
adb shell ime enable com.android.inputmethod.latin/.LatinIME
adb shell ime set com.android.inputmethod.latin/.LatinIME
```

## 2. Install Cursor CLI in the chroot

Inside the chroot (arm64):

```bash
curl -fsSL https://cursor.com/install | bash
export PATH="$HOME/.local/bin:$PATH"
agent --version
```

If `agent --version` throws a dynamic-linker error, install the arm64 runtime
libs and retry:

```bash
sudo apt-get update && sudo apt-get install -y libc6:arm64 libgcc-s1:arm64 libidn2-0:arm64
```

## 3. Authenticate

Prefer a **user-scoped** Cursor API key. Store it privately in the chroot:

```bash
mkdir -p ~/.config/cursor-keyboard
echo -n 'YOUR_USER_SCOPED_KEY' > ~/.config/cursor-keyboard/key
chmod 600 ~/.config/cursor-keyboard/key
```

(Or `agent login` once for interactive auth.)

## 4. Run the bridge + context hook

Copy `bridge/` into the chroot, then:

```bash
cd bridge
export PATH="$HOME/.local/bin:$PATH"
export CURSOR_API_KEY="$(cat ~/.config/cursor-keyboard/key)"
python3 cursor_acp_bridge.py --host 127.0.0.1 --port 9043 --workspace ~
```

Keep it alive with `nohup ... &` (plus `termux-wake-lock`) or a service.

Optional — auto-refresh the context file after each command:

```bash
source termux_context_hook.sh     # add to ~/.zshrc / ~/.bashrc
```

## 5. Configure the keyboard

Open **Cursor Agent Settings** (a launcher icon) and set:

- **Connection type**: `Local chroot (loopback)`
- **Bridge host**: `127.0.0.1`
- **Bridge port**: `9043`
- **Workspace directory**: e.g. `/data/data/com.termux/files/home` (or `~` in the chroot)
- **Auto-approve tool calls**: on for unattended use, off for confirmations
- **Capture shell context from the bridge**: on

### External / remote agent (optional)

To run the agent somewhere other than the local chroot (e.g. a server or a
Cloudflare Tunnel origin), set **Connection type** to `External agent URL` and
enter the URL:

- Direct TLS: `tls://host:9043`
- Behind Cloudflare Tunnel (WebSocket over HTTPS): `wss://<tunnel-hostname>` or
  `https://<tunnel-hostname>`

Set the **Shared token** to match the bridge's `--token`. The keyboard stays
unprivileged: all root-capable work (the `agent acp` process, context capture)
runs on the bridge side, whether local or remote. See `bridge/README.md` for the
full runbook. Tap **Show setup command** in external mode for the remote-side
command (including `cloudflared tunnel --url http://localhost:9043`).

## 6. End-to-end verification checklist

Check each box; all must pass for the integration to be considered working.

- [ ] `agent --version` prints a version (not a loader error) in the chroot.
- [ ] Bridge prints `[bridge] listening on 127.0.0.1:9043 (agent=agent)`.
- [ ] `adb shell ime list -s` lists `com.android.inputmethod.latin/.LatinIME`.
- [ ] The keyboard shows the **Cursor** header button.
- [ ] In a terminal field, press **Cursor** → panel shows `Connecting...` then
      `Agent running...`.
- [ ] Streamed reply text appears in the panel (may be gradual).
- [ ] The prompt sent to the agent included the current shell context +
      surrounding text (verify in the agent's logs / bridge output).
- [ ] If a tool permission is requested, **Allow** / **Deny** resolves it.
- [ ] Tap **Insert** → the proposed command is committed into the terminal field.
- [ ] Tap **Stop** → the session ends and the panel collapses.

If any step fails, check the bridge stderr for `agent acp` errors (auth, missing
binary, linker issues) and re-run the failing step.
