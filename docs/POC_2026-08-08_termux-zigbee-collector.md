# POC Playbook — Termux as Home's Local Collector Hub

> Validates the one real unknown in `ROADMAP.md`'s Phase 8 recommendation
> for Home: can Termux (non-root Android) actually talk to a USB Zigbee
> coordinator well enough to run Zigbee2MQTT, and can that traffic reach
> the M920q over Tailscale. This is a go/no-go test, not a production
> setup — if it fails, the documented fallback is a Raspberry Pi 4 (see
> `ROADMAP.md`'s Phase 8). Nothing here touches Cabin's live production
> Zigbee mesh or the M920q's running containers.

**Time-box this.** If you're not past Phase 3 within about an hour of
active troubleshooting, that's your answer — stop and treat it as a
"no," don't sink a weekend into it. The Pi 4 fallback is cheap and
guaranteed to work; Termux is the free-if-it-works experiment.

---

## 0. Before you start — checklist

- [ ] **Termux app** — install from **F-Droid or GitHub releases**, not
      the Play Store (the Play Store build is deprecated and can't
      update its own packages properly).
      F-Droid: `https://f-droid.org/packages/com.termux/`
- [ ] **Termux:API app** — same source (F-Droid/GitHub), a *separate*
      companion app. Required for `termux-usb`.
- [ ] **A USB Zigbee coordinator** — a spare/freshly-unboxed one if
      possible, **not** Cabin's live Sonoff Dongle Plus V2. You don't
      want to disrupt the real mesh, and this test doesn't need a real
      network to prove the concept — just coordinator firmware
      responding. Note the chipset if you can find it (common ones:
      Silicon Labs EFR32 coordinators like Sonoff's own dongles present
      as CP2102/CP2104; ConBee II uses an FTDI FT230X). If you don't
      have a spare coordinator yet, this is the one hard blocker — you
      can still do Phases 0–2 (basic USB visibility) with any USB-serial
      device to sanity-check the concept, but Phase 4's real test needs
      an actual Zigbee coordinator.
- [ ] **A USB-OTG adapter/cable** for the phone (USB-C phones: a
      USB-C-to-USB-A OTG adapter is usually a few dollars).
- [ ] **ilikethelights (this Windows PC) with Android platform-tools**
      for ADB — makes the whole POC much easier to drive (copy-paste
      commands into an ADB shell instead of typing on the phone
      keyboard, and you get a persistent scrollback). See Phase 0.
- [ ] **Same network(s)**: phone on the same Wi-Fi as ilikethelights for
      ADB-over-network (optional, USB ADB works without it), and
      **Tailscale installed and signed in on the phone** (official
      Android app, Play Store is fine for this one — Tailscale isn't
      Termux) so it can reach the M920q's Tailscale IP later in Phase 5.

---

## Phase 0 — ADB bridge from ilikethelights (recommended, not required)

On the phone: **Settings → About phone → tap "Build number" 7 times**
(enables Developer Options) → **Settings → Developer Options → USB
debugging → on**.

On ilikethelights (PowerShell):
```powershell
adb --version
```
If that errors, install platform-tools first:
```powershell
winget install Google.PlatformTools
```
Plug the phone into ilikethelights via USB, accept the "Allow USB
debugging?" prompt on the phone, then:
```powershell
adb devices
```
You should see the device listed as `device` (not `unauthorized`). From
here you can open a shell directly into the phone:
```powershell
adb shell
```
...and from *inside* that shell, drop into Termux's own environment:
```bash
run-as com.termux
```
This mostly won't work well for interactive Termux commands (Android
sandboxing) — the more useful pattern is: **keep the ADB shell open in
one PowerShell window to watch logs** (`adb logcat` below), and type the
actual Termux commands directly on the phone's Termux app. A Bluetooth
keyboard paired to the phone makes this much less painful than the touch
keyboard if you have one lying around.

Useful during Phase 2 troubleshooting:
```powershell
adb logcat | Select-String -Pattern "usb|Usb|USB"
```
This shows Android's own USB subsystem log lines in real time as you
plug the coordinator in — the fastest way to see whether Android
recognized it at all, before Termux even enters the picture.

---

## Phase 1 — Termux base setup

In Termux:
```bash
pkg update -y && pkg upgrade -y
pkg install termux-api -y
```
Grant Termux storage/notification permissions if prompted. Confirm the
API bridge works:
```bash
termux-battery-status
```
You should get back a JSON blob (battery %, health, etc.) — if this
fails, the Termux:API *app* isn't installed/paired correctly; fix that
before continuing, everything after this depends on it.

---

## Phase 2 — Does Android even see the USB device? (the real first test)

Plug the coordinator into the phone via the OTG adapter. Immediately:
```bash
termux-usb -l
```
- **If you see a device path** like `/dev/bus/usb/001/002` — good,
  Android's USB host stack recognizes it. Continue to Phase 3.
- **If the list is empty** — check `adb logcat` (Phase 0) for USB lines
  while re-plugging the device. Some phones need a specific OTG
  adapter/cable (not all "OTG cables" are equal), and some Android
  builds disable USB host mode entirely for battery/OEM-policy reasons.
  If nothing shows up here after trying a different cable/port, **this
  is your no-go signal** — stop here, move to the Pi 4 fallback.

Also check whether the kernel exposed a serial tty node directly (some
stock kernels do this for common USB-serial chipsets even without any
app requesting it):
```bash
ls /dev/tty* 2>/dev/null
```
Look for anything like `/dev/ttyUSB0` or `/dev/ttyACM0`. Note whatever
you find (or don't) — you'll need it in Phase 3/4 either way.

---

## Phase 3 — Can Termux actually *open* the device?

Two paths, try both — whichever works is the one you'll use in Phase 4.

**Path A — direct tty access** (simplest, works if the kernel exposed a
`/dev/ttyUSB*`/`/dev/ttyACM*` node in Phase 2 *and* Termux's process has
permission to read it):
```bash
cat /dev/ttyUSB0
```
(swap in whatever device Phase 2 found). Let it run a couple seconds,
then Ctrl+C. **`Permission denied` here is expected on most stock,
non-rooted Android** — don't be discouraged, that's exactly why Path B
exists.

**Path B — termux-usb's permission-granted file descriptor:**
```bash
termux-usb -r -e 'echo "opened as: $1"' /dev/bus/usb/001/002
```
(use the actual path from Phase 2's `termux-usb -l`). This triggers a
**system permission dialog on the phone** — tap Allow. If it prints an
`opened as:` line with a file-descriptor-style path
(`/proc/self/fd/NN`), Termux *can* open the raw device.

**Be honest with yourself about what this proves.** Path B gets you a
raw USB handle, not automatically a working serial (tty) abstraction —
Zigbee2MQTT's `serialport` library expects proper serial semantics
(baud rate, line discipline), which a raw USB bulk-transfer handle
doesn't automatically provide. This is the actual hard part community
reports on this topic disagree about. **Phase 4 is the real test** —
don't declare victory or defeat based on Phase 3 alone.

---

## Phase 4 — The real go/no-go: install Zigbee2MQTT and try it for real

```bash
pkg install nodejs-lts git mosquitto -y
git clone --depth 1 https://github.com/Koenkk/zigbee2mqtt.git
cd zigbee2mqtt
npm ci
```

Zigbee2MQTT requires a reachable MQTT broker even for this coordinator-only
test. Start a local, POC-only broker in Termux before launching it:
```bash
mosquitto -d
mosquitto_sub -h localhost -t '$SYS/broker/version' -C 1 -W 5
```
The second command should print the local Mosquitto version and exit. If it
times out, fix the local broker before interpreting any Zigbee2MQTT startup
failure as a USB/coordinator failure.
Edit `data/configuration.yaml` (copy from `data/configuration.yaml.example`
if it doesn't exist yet) — minimal config for this test:
```yaml
version: 5
homeassistant:
  enabled: false
mqtt:
  server: 'mqtt://localhost:1883'
  # Never use the production Cabin mesh's default `zigbee2mqtt` topic for
  # this second coordinator. The unique namespace is part of the POC's
  # safety boundary and remains unchanged in Phase 5.
  base_topic: 'poc/home/zigbee2mqtt'
  client_id: 'home-termux-poc'
serial:
  port: '/dev/ttyUSB0'              # or whatever Phase 2/3 found
frontend:
  enabled: true
  port: 8099
advanced:
  network_key: GENERATE
  pan_id: GENERATE
  ext_pan_id: GENERATE
```
Run it directly (not as a service yet):
```bash
npm start
```
**Watch the startup log carefully:**
- A line like `Coordinator firmware version: ...` or `zigbee-herdsman
  started` — **this is the pass condition.** The coordinator responded
  to a real protocol handshake. Termux can run this.
- `Error: Permission denied, cannot open /dev/ttyUSB0` or `Error: No
  such file or directory` — **this is the fail condition.** Stop here,
  move to the Pi 4 fallback (see `ROADMAP.md`'s Phase 8) — don't spend
  more time patching around it for this POC.
- If Path A (Phase 3) failed but Path B produced an `fd` path, try
  pointing `serial.port` at that `/proc/self/fd/NN` path instead of
  `/dev/ttyUSB0` — this sometimes works, sometimes doesn't, depending
  on phone/kernel; it's worth the one extra try before calling it a
  fail.

If you get the coordinator responding, this POC has answered its
question — **you don't need to actually pair a real device or wire up
MQTT to call Phase 4 a success.** Phases 5–8 below turn a successful
Phase 4 into something closer to real, but aren't required to make the
hardware decision.

---

## Phase 5 — Reach the M920q over Tailscale (only if Phase 4 passed)

> **STOP — this phase touches the live M920q MQTT broker and requires the
> user's explicit approval at that moment.** Phase 4 is the hardware go/no-go
> gate and is complete without Phase 5. Do not proceed merely because the
> earlier phases passed.

Cabin's live Zigbee2MQTT instance already publishes retained state and accepts
control requests under `zigbee2mqtt/#`. Zigbee2MQTT's own documentation
requires a different `mqtt.base_topic` for every instance. Reusing the default
here could overwrite Cabin bridge/device discovery state or make a control
request reach both coordinators. **Keep `poc/home/zigbee2mqtt` exactly as
configured in Phase 4; never use or subscribe this POC to `zigbee2mqtt/#`.**

Confirm Tailscale is connected on the phone (Android Tailscale app, not
Termux) and can see the M920q:
```bash
ping -c 3 cabin-hub
```
(or the M920q's Tailscale IP if MagicDNS doesn't resolve from Termux —
check the Tailscale admin console for the exact hostname/IP).

Point Zigbee2MQTT's `mqtt.server` in `data/configuration.yaml` at the
M920q's actual broker, changing only `server`:
```yaml
mqtt:
  server: 'mqtt://cabin-hub:1883'
  base_topic: 'poc/home/zigbee2mqtt'
  client_id: 'home-termux-poc'
```
Restart `npm start`, and on the M920q (or from ilikethelights, if it can
reach the broker too) confirm messages are actually arriving via:
```bash
mosquitto_sub -h <cabin-hub-tailscale-ip> -t 'poc/home/zigbee2mqtt/#' -v
```
The current central backend does not consume this POC namespace yet; seeing
the isolated messages arrive is the whole Phase 5 pass condition. Do not
rename the topic to make the current UI/backend discover it — that adapter
work belongs to the collector-hub implementation plan.

---

## Phase 6 — Pair one real test device (optional, only if you want to go this far)

Open the POC coordinator's join window from its frontend, or publish the
scoped request below from Termux:
```bash
mosquitto_pub -h cabin-hub \
  -t 'poc/home/zigbee2mqtt/bridge/request/permit_join' \
  -m '{"time":254}'
```
Put a spare Zigbee end device (not a live safety sensor from Cabin) in pairing
mode and watch Zigbee2MQTT's log for a `device joined` message, then confirm
it shows up under `poc/home/zigbee2mqtt/` on the M920q's broker (same check as
Phase 5). Close the join window when finished by publishing `{"time":0}` to
that same scoped request topic.

---

## Phase 7 — Reliability scaffolding (only worth doing once Phases 4–6 pass)

This POC doesn't need this to answer the go/no-go question, but if
you're moving toward "yes, use the phone for real," these are the next
things to set up before trusting it unattended overnight:

- **Termux:Boot app** (same source as Termux/Termux:API) — lets a script
  auto-start Zigbee2MQTT when the phone reboots, instead of needing you
  to manually open Termux and run `npm start` every time.
- **`termux-wake-lock`** — run this once per session (or from a
  Termux:Boot script) to stop Android's Doze mode from suspending the
  process in the background.
- **Battery optimization exemption** — Android Settings → Apps →
  Termux (and Termux:Boot) → Battery → **Unrestricted**. Without this,
  Android will still kill or throttle the process eventually regardless
  of the wake lock.
- Consider `pm2` (Node process manager, `npm install -g pm2`) instead of
  a bare `npm start`, so Zigbee2MQTT actually restarts itself if it
  crashes.

---

## Phase 8 (stretch, separate from the Zigbee question) — Kiosk display test

Independent of everything above — validates the "load Family Hub on a
touchscreen" half of the ask, and can be done on a *different* Android
device if you don't want to tie up the Termux phone:

1. Install **Fully Kiosk Browser** (Play Store).
2. Point its Start URL at the M920q's `family-hub` service over
   Tailscale (check `infra/docker-compose.m920q.yml` / Cloudflare Tunnel
   config for the exact reachable URL — it's already served as a plain
   static site, no new hosting needed).
3. Enable Fully Kiosk's "Kiosk mode" (hides system UI, disables the
   status bar, auto-restarts the browser on crash) and, separately,
   Android's own screen-timeout/always-on settings for the display.

This part has no real technical unknowns — it's configuration, not a
feasibility question — so it's lower priority than Phases 0–4.

---

## Reporting back

Whatever the outcome, capture it in `ROADMAP.md`'s Phase 8 (which
currently just says "test that specifically") and
`docs/HANDOFF_2026-08-08_codex-fork.md`'s Item 5 — both should be
updated with the real result (pass/fail, phone model + Android version,
coordinator chipset, and the exact error if it failed) so this doesn't
need to be re-litigated later. If it fails, that update is what confirms
the Pi 4 fallback as the actual decision, not just the contingency.
