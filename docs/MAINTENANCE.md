# Maintenance & Operations Guide

*Audience: whoever is operating, deploying, or debugging this platform.
For product/day-to-day usage, see [`USER_GUIDE.md`](USER_GUIDE.md). For
standing up an independent instance, see [`REPLICATION.md`](REPLICATION.md).
For the strategic/architectural brief, see [`../ROADMAP.md`](../ROADMAP.md).*

---

## Architecture at a glance

```
                          Cloudflare Tunnel (public)
                                    │
        ┌───────────────┬──────────┼──────────┬───────────────┐
        │               │          │           │               │
   hub.…com         cabin.…com  api.…com   (Tailscale-only, not tunneled)
  family-hub         cabin-ui  cabin-backend      HA · Node-RED
  (static HTML)      (React)   (Spring Boot)      Frigate · Z2M
                                    │              Grafana
                          ┌─────────┼─────────┐
                     Postgres    Kafka       MQTT (Mosquitto)
```

- **family-hub** (static HTML, no build step) — the ambient Family Hub
  display. Deployed as-is, no compilation.
- **cabin-ui** (React + Vite) — the cabin-side control app, includes the
  authenticated Camera Events panel and the Opportunity Map panel (Tech
  ID Service findings, presented per the See/Think/Act model — see
  `docs/PRODUCT_NOTES.md`'s 2026-08-03 review).
- **cabin-backend** (Spring Boot) — the shared API: notes, chores,
  profiles, camera media proxy, device status, event ingestion, Tech ID
  Service findings intake and action log (`/api/tech-id/findings` — see
  below), ontology entity lookup (`/api/ontology/entities` — resolves
  raw entity ids to display names, backing the Opportunity Map's
  lineage chips; parses `docs/ontology.yaml` directly via a **read-only
  bind mount**, `../../docs:/app/docs:ro` in
  `docker-compose.m920q.yml` — the Docker build context is
  `../backend` only, so this file is not otherwise reachable inside the
  container; missing the mount degrades lookups to a best-effort
  humanized label rather than failing). Talks to Postgres (state),
  Kafka (event bus), and MQTT (device/camera telemetry in).
- **Frigate** — NVR: camera detection, recording, live streaming. Publishes
  detection/motion events to MQTT.
- **Home Assistant + Node-RED** — device automation (leak/freeze sensors,
  water shutoff, intrusion alarm, WiFi-based presence, camera alerts).
  Tailscale-only; not exposed through the tunnel.
- **Zigbee2MQTT** — Zigbee coordinator bridge (14 paired sensors/actuators).

**Public vs. private surface, by design**: `hub`/`cabin`/`api` are public
through Cloudflare Tunnel — that's the actual product. Everything else
(HA, Node-RED, Frigate's own admin UI, Zigbee2MQTT's UI, Grafana) is
Tailscale-only. This isn't an oversight to "fix" — it's the deliberate
boundary between the product surface and the reconfiguration surface. See
`docs/ontology.yaml` and `ROADMAP.md` for the full reasoning.

---

## Deployment

### Automated (family-hub, cabin-ui)

A self-hosted GitHub Actions runner on the M920q polls for pushes to
`main` and auto-deploys `family-hub` and `cabin-ui` when their paths
change (`.github/workflows/deploy-family-hub.yml`). No inbound ports, no
secrets stored in GitHub — the runner connects outbound over Tailscale.
Setup/recovery instructions: [`../ansible/README.md`](../ansible/README.md).

### Manual (cabin-backend) — **do this every time you change the backend**

`cabin-backend` is **deliberately excluded** from the automated workflow
— it's stateful/sensitive enough to warrant a considered rollout, not a
blanket rebuild on every push. After merging a backend change:

```bash
ssh nate@nates-little-m920q.tailb20f8b.ts.net
cd /home/nate/FaceoftheCabin
git pull
cd cabin-orchestration-platform/infra
docker compose -f docker-compose.yml -f docker-compose.m920q.yml build cabin-backend
docker compose -f docker-compose.yml -f docker-compose.m920q.yml up -d cabin-backend
```

**Always verify after deploying**, don't trust a green build alone:

```bash
curl -s http://localhost:8090/actuator/health   # expect {"status":"UP",...}
```

A real, repeated lesson from this project: a passing build and a healthy
container status are not proof the *feature* works. Verify against the
actual live endpoint/response, not just process status — see "Known
Issues" below for cases where everything *looked* fine and wasn't.

---

## Secrets

Every credential (`POSTGRES_PASSWORD`, `GRAFANA_PASSWORD`, `HA_TOKEN`,
`CAMERA_PASSWORD`, `TECH_ID_API_KEY`) is Ansible Vault-managed — encrypted
at rest, committed to git (that's the point of Vault), templated into
`infra/.env` by the `secrets` role rather than hand-edited. Full setup and
rotation instructions: [`../ansible/README.md`](../ansible/README.md)'s
Secrets section.

`TECH_ID_API_KEY` (→ `cabin.techid.apiKey`) gates `POST
/api/tech-id/findings` — the shared secret any Tech ID Service provider
(this instance's own scheduled scan, an operator's paid tier, or an
instance owner's own AI pipeline) presents via `X-Tech-Id-Api-Key` to
submit findings. Unset by default; submission returns `503` until an
operator opts in. See `ROADMAP.md`'s "Tech ID Service — Provider Model"
and `REPLICATION.md` §8 for the full design and setup steps.

**Quick reference:**
```bash
cd ansible
# Template .env from the current vault (after any vault edit)
ansible-playbook -i inventory.ini site.yml --limit cabin --vault-password-file ~/.ansible_vault_pass --tags secrets

# Rotate POSTGRES_PASSWORD end to end (generate, apply live, re-encrypt, re-template, restart, validate)
ansible-playbook -i inventory.ini playbooks/rotate-secrets.yml --limit cabin --vault-password-file ~/.ansible_vault_pass
```

Runs automatically monthly via `.github/workflows/rotate-secrets.yml`.
`GRAFANA_PASSWORD`/`HA_TOKEN` rotation is not automated yet — different
mechanics needed (Grafana's admin API, HA's own token UI), still a manual
`ansible-vault edit` + matching account-side change.

**If running Ansible directly on the M920q** (as opposed to from a
separate machine with SSH access), self-targeting via its own Tailscale
hostname doesn't work — it's a hairpin routing limitation, not a bug in
the playbook. Use `-c local -e ansible_become=false` in that case.

**Never diff a secret by its raw value.** Compare by presence/absence, a
hash, or a boolean the script prints — never `diff <(grep KEY old)
<(grep KEY new)` where the value itself lands in a terminal or log. This
project had a real incident where a password briefly appeared in a
session transcript this way; it's a very easy mistake to repeat if you're
not deliberately avoiding it.

---

## CI/CD

Self-hosted GitHub Actions runner, registered on the M920q, connecting
outbound over Tailscale — no inbound ports, no SSH keys stored in GitHub.
Chosen specifically because the M920q is behind Starlink CGNAT (no public
IP to receive inbound connections on) and a GitHub-hosted runner has no
network path to a Tailscale-only host. Full setup/recovery runbook:
[`../ansible/README.md`](../ansible/README.md).

---

## Database & Storage

- **Postgres** (`cabin-postgres`) — application state: notes, chore
  completion, family profiles, camera events. Password is Vault-managed
  (see Secrets above); `POSTGRES_PASSWORD` only takes effect at *first*
  volume initialization — changing the env var alone does not change a
  running instance's actual password, only `ALTER USER` does (the
  rotation playbook handles this correctly; a naive env-var-only change
  would silently break the connection).
- **Kafka** (`cabin-kafka`) — single-broker event bus for camera/device
  events. **Known gotcha**: the internal `__consumer_offsets` topic
  defaults to replication factor 3, which silently fails to create on a
  single-broker cluster — the symptom is every consumer group hanging
  forever with zero partitions assigned, no visible error anywhere except
  a `FIND_COORDINATOR` timeout if you probe directly. Fixed via
  `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1` (and the matching
  transaction-log settings) in `docker-compose.yml` — already applied,
  but worth knowing if you ever see consumers silently not consuming.
- **`/storage`** (`/dev/sda1`, ~931GB, separate from the boot NVMe) —
  where Frigate writes recordings. Check headroom before increasing any
  retention window: `df -h /storage`. Continuous recording is currently
  capped at 5 days deliberately conservative — the `front_door` (Reolink)
  camera's record role uses its full 4K stream, which at realistic
  bitrates could plausibly consume 80-160+ GB/day once it's back online.
  Re-measure real usage before extending retention, don't trust an
  estimate.

---

## Cameras (Frigate)

Live config lives at `/storage/services/frigate/config.yml` on the
M920q — **this file has zero git history** (a known, real gap; the file
predates this project's git tracking and hasn't been retrofitted in).
**Always back it up before editing**:

```bash
cp /storage/services/frigate/config.yml /storage/services/frigate/config.yml.bak-$(date +%Y%m%d-%H%M%S)
```

Edit locally, validate with `python3 -c "import yaml; yaml.safe_load(open('config.yml'))"`
*before* copying it to the host — a YAML syntax error in a live config
will prevent Frigate from starting cleanly. Never build the file inline
inside a quoted SSH command on a Windows/Git Bash client — backticks and
certain special characters get expanded by the *local* shell before
transmission, which has genuinely corrupted this file once already. Write
the file locally, `scp` it over, then apply.

**Current cameras**: `front_door` (Reolink RLC-820A, 4K record / 640×480
detect, currently off-network — physical/WiFi issue, needs on-site
checking) and `driveway` (a Blink camera bridged via `blinkbridge` +
`mediamtx`, no true continuous stream — only "live" when Blink's own
cloud has already detected motion, so its detection is a second,
finer-grained pass on top of Blink's own trigger, not a replacement for
it). Both physically cover the same front-door/driveway area from
different angles — the names describe available semantic slots, not
fixed device identities; one is expected to eventually relocate to cover
the building's rear.

Restart after any config change: `docker restart frigate`, then verify:
```bash
curl -s http://localhost:5000/api/config | python3 -c "import sys,json; print(list(json.load(sys.stdin)['cameras'].keys()))"
```

**There are two Frigate container definitions on this host — only one is
real.** Confirmed 2026-08-03 after an external review flagged an
apparent "config disagrees with git" drift. `docker-compose.yml`'s own
bundled `frigate:` service (container name `cabin-frigate`, config
`cabin-orchestration-platform/infra/frigate.yml`, git-tracked) exists in
this repo but has never actually been started (`docker inspect
cabin-frigate` shows `Status=created`, `RestartCount=0`) — it's inert,
using zero resources, but its config file is genuinely stale (still has
the pre-rename camera names/settings) and reads as a live source of
truth if you don't check container state first. **The container that
actually matters is `frigate`** (no `cabin-` prefix), managed outside
this repo by CabinAutomations, config at `/storage/services/frigate/config.yml`
per the section above — `cabin-backend`'s `FRIGATE_URL` defaults to
`http://frigate:5000`, which resolves to this one on the shared
`cabin_default` Docker network. When debugging a camera/Frigate issue,
always check `docker ps` for which container is actually running before
trusting any config file's contents. Not yet cleaned up (removing the
orphaned service from `docker-compose.yml` is a real infra edit,
flagged for the user to confirm rather than done unilaterally) — this
note exists so the next person doesn't lose time to the same confusion.

**Blink camera (`driveway`) went dark for hours with no auto-recovery
— found and fixed 2026-08-03.** External review's "no camera-event
pipeline activity" finding traced to `docker exec frigate curl
localhost:5000/api/stats`: both cameras showed `camera_fps: 0.0` — no
incoming video at all, so zero detections were possible regardless of
anything downstream (MQTT, Kafka, Postgres were all healthy and
correctly wired the whole time). Two independent causes:
- `front_door` (Reolink, 192.168.2.200): `ffmpeg` logs showed `No route
  to host` — the existing, already-documented off-network issue above,
  unrelated to this incident, still needs on-site checking.
- `driveway` (Blink): `blinkbridge`'s logs showed a transient failure
  reaching Blink's own cloud API (`rest-e003.immedia-semi.com`, "Cannot
  connect to host") around 05:17 UTC that morning, after which
  `blinkbridge` logged `too many failures, disabling` and never
  retried — the container itself never crashed or restarted
  (`RestartCount=0`), it just gave up internally and sat idle for
  hours, so no restart policy would have caught it. `mediamtx` logs
  confirmed nothing was publishing to `rtsp://mediamtx:8554/outdoor_4_-_dhee`
  (Frigate's configured source for this camera) the whole time, hence
  its own repeating `404 Not Found` / `Unable to read frames` errors.
  **Fixed with `docker restart blinkbridge`** — re-authenticated with
  Blink immediately, stream resumed, Frigate's `camera_fps` went from
  `0.0` to `5.1` within seconds. **Not yet fixed**: `blinkbridge` has no
  self-healing behavior for this specific failure mode (an internal
  "give up" state that a container restart clears but nothing else
  does) — worth an Uptime Kuma check against `mediamtx`'s stream health
  or Frigate's own `camera_fps` so this doesn't require someone to
  notice "no events in days" before catching it next time.
  **Recurred 2026-08-06**: same symptom (user reported Blink motion not
  registering; `driveway camera_fps` was `0.0`, `blinkbridge` logs showed
  liveview requests hitting "unknown/disabled camera"), same fix
  (`docker restart blinkbridge` — recovered to `camera_fps: 4.59` within
  ~25s). `RestartCount=0` the whole time it was stuck, confirming again
  this is an internal give-up state, not a crash — no Docker restart
  policy will ever catch it. The Uptime Kuma monitor recommended above is
  still not confirmed to exist; this is now a repeat incident, not a
  one-off, so it's been promoted to the open punch list rather than left
  as a suggestion buried in this note.

  **Root cause fully diagnosed, same day, after it went dark a third time
  within hours of the restart above.** `blinkbridge` is a third-party tool
  (`github.com/roger-/blinkbridge`, cloned locally at
  `/storage/services/blinkbridge-src` on the M920q) whose own README
  already describes exactly the "poll and download the latest clip, ~30s
  lag by design" model — it was never trying to be truly live, and its own
  TODO list admits "Better error handling" is unfinished. The actual bug,
  read directly from `blinkbridge/main.py`: `config/config.json` sets
  `max_failures: 3`, `restart_delay_seconds: 60`. On the 3rd failure
  within that window, the code does `self.stream_servers.pop(camera_name)`
  and logs "too many failures, disabling" — **nothing in the codebase ever
  adds the camera back.** The only recovery path is a full container
  restart. Confirmed via full log review that Blink's own API throws
  intermittent, clearly transient errors (`"throttled"`,
  `"Cannot connect to host rest-e003.immedia-semi.com"`) every 15
  minutes to a few hours — normal flakiness for a free-tier cloud API, not
  evidence of a lapsed trial or account problem, since re-authentication
  and streaming both succeed cleanly every time it's restarted. Three
  transient blips in a row (not unusual against this API) is enough to
  kill the camera for good until a human notices.

  **The fix, in two parts, deliberately not conflated:**
  1. **Immediate**: patch `main.py` so hitting `max_failures` retries with
     backoff instead of permanently disabling — small, scoped change to
     code with direct git access, targets the actual bug (permanent
     give-up) rather than the symptom (camera dark).
  2. **Separate, bigger question, intentionally not bundled into the
     patch above**: whether to decouple reliable clip-recording from the
     RTSP-live-relay entirely, since the live relay (FFmpeg muxing,
     liveview session handoff) is where most of the remaining complexity
     and fragility lives, and "recording first" doesn't structurally
     require it. The user recalled agreeing to exactly this direction in
     an earlier session — **that decision was never written down anywhere
     in this repo** and had to be reconstructed from memory alone. See
     `CLAUDE.md`'s new "Decisions made where Claude can't commit" section
     for the process fix that's meant to prevent this specific failure
     mode from recurring.

  **Part 1 shipped and deployed 2026-08-06.** Patched
  `/storage/services/blinkbridge-src/blinkbridge/main.py`: hitting
  `max_failures` now backs off (capped at 15 minutes, doubling each
  additional failure) and keeps retrying, instead of
  `self.stream_servers.pop(camera_name)` with nothing that ever adds it
  back. Rebuilt (`docker build -t cabin-blinkbridge:new4
  /storage/services/blinkbridge-src`) and redeployed following this
  section's own documented rebuild procedure below (credentials pulled
  from the running container's own env via `docker inspect`, never
  printed). Confirmed the new image is what's actually running
  (`docker exec blinkbridge grep MAX_RESTART_BACKOFF
  /app/blinkbridge/main.py` found it) and that `driveway` is recording
  again (`camera_fps: 5.0`, `detection_fps: 5.0`, both healthy
  immediately after redeploy). Also committed locally within
  `blinkbridge-src`'s own git history for the first time — the
  2026-08-03 liveview feature below had been sitting as permanent
  uncommitted drift in that clone since it was built; both changes are
  now one real commit there instead of invisible working-tree state.
  **Not yet proven**: whether the backoff actually saves a real transient
  failure without human intervention — that only shows up the next time
  Blink's API hiccups, which the pre-patch logs show happens every
  15 minutes to a few hours. Watch for it, don't assume it from the
  rebuild alone.

  **Part 2 — the decouple-from-RTSP-relay question — is still open,**
  deliberately not bundled into this fix. See the note above.

### Real on-demand liveview for the Blink camera (built 2026-08-03)

**The bug this fixes**: `driveway`'s "live view" was never actually
live. `blinkbridge` only republishes Blink's motion-triggered clips on
a loop (see the Frigate config section above), so watching "live"
during the day could show a stale frame from hours-old nighttime
motion — reported directly by the user ("camera view is still
nighttime and it's daytime now, so it's not live") after the previous
incident's fix already had the camera streaming again.

**The fix**: an actual on-demand Blink liveview session, triggered only
while someone is watching (explicit decision — Blink battery cameras
aren't built for continuous streaming; see
`docs/ontology.yaml`'s `cabin_camera_live_view` entry for the full
write-up). Three pieces:

1. **`blinkbridge` itself** (source at `/storage/services/blinkbridge-src`
   on the M920q — **also zero git history**, same known gap as
   `frigate/config.yml`, backed up to a timestamped copy in the same
   directory before this session's edits). Added:
   - A small `aiohttp.web` control API (`POST /liveview/{camera}/start`
     and `/stop`, port `8811`, reachable only from other containers on
     `cabin_default`, no host port published) that opens a real
     `blinkpy` `BlinkLiveStream` session and relays it into the same
     `mediamtx` path Frigate already reads from — nothing downstream
     (Frigate config, cabin-backend) needed to change.
   - Automatic fallback: if Blink's own liveview server ends the
     session early (observed twice during testing — see the `blinkpy`
     bug below), or after a 5-minute hard cap if nothing calls `/stop`,
     the motion-clip loop resumes on its own. Before this, a camera
     stuck in a dead live session had no recovery path but a manual
     `/stop` call.
   - **Found and fixed a real bug in the third-party `blinkpy`
     library** while building this: its `BlinkLiveStream.recv()` reads
     protocol data with `StreamReader.read(n)`, which asyncio does
     **not** guarantee returns exactly `n` bytes — reliably killed
     every liveview session within ~5 seconds against the real camera
     ("Insufficient data for payload"). Confirmed present in both the
     installed version (0.25.7) and the latest on PyPI (0.25.9) at the
     time — upgrading would not have helped. Patched at runtime via
     `blinkbridge/patches.py` (`readexactly()` instead of `read()`,
     applied by monkey-patching the class at import time) rather than
     forking `blinkpy` — delete that file if a future `blinkpy` release
     fixes this upstream.
2. **`cabin-backend`**: `CameraMediaController` gained
   `POST /api/camera/{cameraName}/liveview/start|stop`, gated the same
   as the rest of `/api/camera/**`. Config-driven and camera-agnostic —
   `cabin.devices.cameras.blinkCameraMap` (`TECH_ID`-style
   `cabinName:blinkDeviceName` pairs, e.g. `driveway:Outdoor 4 - DHEE`)
   maps this platform's camera names to Blink's own device names. Any
   camera not in that map (the Reolink) gets a harmless no-op —
   correct, since it's already continuously live over native RTSP and
   has nothing to "start."
3. **`cabin-ui`**: `CameraEventsPanel`'s existing "Watch live" toggle
   now fires the start/stop calls via a `useEffect` keyed on
   `liveCamera` — switching cameras, clicking "Stop," or leaving the
   panel all correctly end the previous session through the same
   cleanup path, no special-casing needed per trigger.

**Verified against the real camera**, not just code review: a snapshot
pulled directly from Frigate mid-session showed genuine, current
daylight footage (sunlit trees, visible ground debris) — a stark
contrast from the stale nighttime IR frame every attempt showed before
the `blinkpy` patch.

**Rebuild/redeploy procedure** (for the next time `blinkbridge`
needs a code change):
```bash
# On the M920q, after editing /storage/services/blinkbridge-src/blinkbridge/*.py
cp -r /storage/services/blinkbridge-src /storage/services/blinkbridge-src.bak-$(date +%Y%m%d-%H%M%S)
docker build -t cabin-blinkbridge:new /storage/services/blinkbridge-src
PASS=$(docker inspect blinkbridge --format '{{range .Config.Env}}{{println .}}{{end}}' | grep ^BLINK_PASSWORD= | cut -d= -f2-)
docker stop blinkbridge && docker rm blinkbridge
docker run -d --name blinkbridge --network cabin_default --restart unless-stopped \
  -v /storage/services/blinkbridge:/config \
  -v /storage/cameras/blinkbridge:/working \
  -e BLINKBRIDGE_CONFIG=/config/config.json \
  -e BLINK_USERNAME=nhsmrekar@gmail.com \
  -e "BLINK_PASSWORD=$PASS" \
  cabin-blinkbridge:new
unset PASS
```
Never print `$PASS` — keep it inside the remote shell only. (This
project had a real incident where `docker inspect`'s full environment
output was dumped without filtering and printed a live password into a
session transcript — see the Secrets section's "never diff a secret by
raw value" note; the same discipline applies here.)

---

## Overnight Camera Alerts (Node-RED)

A dedicated Node-RED tab ("Camera Overnight Alerts") pushes a real
notification via [ntfy.sh](https://ntfy.sh) when Frigate detects an
alert-tier object while the cabin is armed-away and no one's present.
Gated on the same MQTT-published state (`cabin/security/armed_away`,
`cabin/presence/nate`) the existing intrusion-alarm flow already tracks,
subscribed independently rather than sharing Node-RED flow-context
directly (flow context is tab-scoped by default; MQTT pub/sub is the
intended decoupling point).

**Editing this flow**: prefer Node-RED's Admin API (`POST`/`PUT
/flow/:id` at `http://localhost:1880`) over hand-editing
`/storage/services/nodered/flows.json` directly — the file gets
overwritten by the running editor's own autosave, and if anyone is
actively editing a different tab in the Node-RED UI at the time, a raw
file edit risks losing their in-progress work.

**Known timing gotcha**: immediately after a fresh deploy via the Admin
API, retained MQTT state may not be delivered to a brand-new
subscription's local flow-context yet — a detection tested within the
first second or two of deploy can be incorrectly gated out. This
self-resolves within about a minute as the existing WiFi-presence
automation (`cabin_security_presence.yaml`) republishes state every 60s
regardless. Not a broken subscription — confirmed via direct testing that
a fresh (non-retained) publish is received correctly immediately.

**Real bug found and fixed, 2026-08-07 — this "known gap" note
undersold what was actually wrong.** Both Node-RED `mqtt in` nodes
gating on armed state (`armed-in` on the intrusion-alarm tab,
`cam-alert-armed-in` on Camera Overnight Alerts) were subscribed to
`cabin/security/node_red_armed` — a topic HA's own `cabin_security.yaml`
package has **never published to**. The real, live-published topic is
`cabin/security/armed_away` (see `packages/cabin_security.yaml`'s
`cabin_security_publish_arm_state` automation, `retain: true`, fires on
every toggle of `input_boolean.cabin_security_armed_away` and again on
every HA restart). This wasn't "no ongoing publisher, relying on a
one-off manual value" — it was two topics that were never the same
string, so toggling the real HA arm/disarm switch never reached either
Node-RED flow at all, ever. Fixed by repointing both `mqtt in` nodes to
`cabin/security/armed_away` (Node-RED Admin API,
`POST /flows` with `Node-RED-Deployment-Type: full`, both nodes' topic
field changed, nothing else touched). Verified: both nodes' `topic`
field confirmed correct via a fresh `GET /flows` after deploy, and a
live retained value (`OFF`) confirmed present on the corrected topic —
MQTT delivers retained messages to a new subscription immediately, so
no separate fresh-publish test was needed to prove propagation.

No additional heartbeat/polling was added for the armed-state topic
itself — unlike `cabin/presence/nate` (which has a genuine 60s
`scan_interval` from `cabin_security_presence.yaml`'s WiFi-detection
sensor), armed-state only republishes on toggle and on HA startup. That
remains sufficient self-healing for a boolean that changes rarely and is
now correctly retained on the topic both consumers actually read — a
broker restart without persistence would still need the startup-republish
automation to run once, same as before, just now on the topic that
matters.

---

## Grafana — Off-Tailscale Access via Cloudflare + Google OAuth

**UPDATE 2026-08-08: the embedded panel's white-screen is NOT fixable
by more cabin/Grafana config — it's Google's own iframe policy.**
Direct header checks against `grafana.unicornpingpong.com` confirm
Grafana itself sends no `X-Frame-Options`/CSP frame-blocking headers,
and the 2026-08-07 `GF_SECURITY_COOKIE_SAMESITE=none` fix is genuinely
live on the container. But: (1) a session cookie issued *before* that
config change keeps its old `SameSite=Lax` attribute until the user
actually re-authenticates — changing the server setting doesn't
retroactively rewrite cookies already sitting in the browser; (2) when
the iframe's Grafana session is invalid for that reason (or any reason)
and needs a fresh login, it has to redirect through Google's OAuth
sign-in page — and Google's sign-in pages refuse to render inside any
iframe at all, by design, for anti-clickjacking reasons Grafana has no
control over. Net effect: this embed can only ever work by reusing an
already-valid Grafana session established in a real top-level tab; it
can never complete a first-time or expired login on its own, and no
further server-side config change will alter that. **Concrete fix for
the user right now**: open `grafana.unicornpingpong.com` directly (not
inside cabin-ui), sign out and back in with Google there to get a fresh
`SameSite=None` cookie, then reload cabin-ui's Monitoring panel. Fixed
the embed's own hint text (App.jsx) to say this instead of the
previous, incorrect "may prompt inside the frame on first load" claim.

**UPDATE 2026-08-07: Google OAuth login is now confirmed actually
working** — `docker logs cabin-grafana` shows a real, current,
successfully authenticated session for `nhsmrekar@gmail.com` from a real
external IP against the public URL, checked live via SSH (Tailscale SSH
access to the M920q became available this session). This directly
contradicts the "Current real state" 403 diagnosis below, which was
accurate as of 2026-08-04 and is not accurate as of 2026-08-07. Root
cause of the change not investigated — worth confirming next session
whether something else fixed it incidentally, or whether the original
403 was intermittent/account-state-dependent. See
`docs/ontology.yaml`'s `cabin_grafana_public_access` entry for the full
correction, including two other real things found live in the same
check: an intermittent session-token-rotation bug (`error="[session.
token.rotate] token needs to be rotated"`, looping in the logs) and — at
the time of checking — no dashboards were actually provisioned into the
running container yet.

**Status as of 2026-08-04, end of session: all infrastructure is
deployed, but neither of the two intended access gates is confirmed
working — Grafana's password login was deliberately disabled as a
result. See "Current real state" below before touching this again.**
(Superseded in part by the 2026-08-07 update above — Google OAuth does
work now.) Grafana was Tailscale-only by design,
alongside HA/Node-RED/Zigbee2MQTT (see "Public vs. private surface, by
design" at the top of this doc) — this is a deliberate, one-off
exception for Grafana specifically, made after the redirect-bug fix
above prompted the user to ask for real off-Tailscale reachability, not
just a working Tailscale link. **Node-RED, Frigate's admin UI, HA, and
Zigbee2MQTT are unchanged** — still Tailscale-only, still not exposed
through the tunnel.

**Design**: Grafana gets its own HTTPS hostname
(`grafana.unicornpingpong.com`) through the existing Cloudflare Tunnel
(`cloudflared` and `cabin-grafana` already share the `infra_default`
Docker network, so the tunnel routes straight to
`http://cabin-grafana:3000` internally — no host port involved), gated
by a Cloudflare Access policy at the edge (email-based One-Time PIN,
**not** Google — see below for why), then Grafana's own login requires
Google OAuth using the **same** Google Cloud OAuth client
family-hub/cabin-ui already use (`GOOGLE_CLIENT_ID`), not a second
registered app. Explicit user requirement: "I don't want to oauth
twice." Realistic version of that: one OAuth *client* to manage (done),
not literal zero-click SSO across apps (Grafana's OAuth is its own
server-side redirect — not something this setup collapses into cabin-
ui's session). Cloudflare Access uses email OTP rather than its own
Google sign-in specifically so the *Google* OAuth prompt only ever
happens once, at Grafana's own login — stacking two separate Google
sign-in prompts (Access, then Grafana) would have been a real "OAuth
twice" in the way the user meant it, even though technically
same-account, different mechanism.

### What's done (code side)

- `ansible/group_vars/cabin/vars.yml` / `roles/secrets/templates/env.j2`
  / `.env.m920q.example`: new `GOOGLE_CLIENT_SECRET` — a **real** secret
  (unlike `GOOGLE_CLIENT_ID`, which is safe client-side by design),
  needed because Grafana's OAuth is a server-side authorization-code
  flow. Empty by default; Grafana's Google auth block stays inert
  (falls back to its own admin/password login) until this is set.
- `docker-compose.m920q.yml`'s `cabin-grafana` service: `GF_AUTH_GOOGLE_*`
  block (enabled, reuses `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`,
  `GF_AUTH_GOOGLE_ALLOWED_EMAILS` reuses `ADMIN_EMAILS`), and
  `GF_SERVER_ROOT_URL` updated to `https://grafana.unicornpingpong.com/grafana`
  (was the Tailscale IP — see the redirect-bug entry above).
- `cabin-ui`'s `VITE_CABIN_GRAFANA_URL` build arg updated to match, so
  the embedded dashboard panel and the "Grafana" quick-link both point
  at the new hostname.
- **Not yet verified**: `GF_AUTH_GOOGLE_ALLOWED_EMAILS` is a real
  Grafana setting in reasonably recent releases, but this hasn't been
  confirmed against whatever `grafana/grafana:latest` resolves to at
  actual deploy time. Confirm on first real login (step 6 below) that
  an email *not* in `ADMIN_EMAILS` is genuinely refused, not just
  untested.

### Current real state (end of session, 2026-08-04)

All the infrastructure setup described above was actually completed
this session — Cloudflare Tunnel token rotated (multiple times; see
the dedicated Cloudflare Tunnel setup lesson below), `GOOGLE_CLIENT_SECRET`
retrieved and added to the vault, redirect URI added in Google Cloud
Console, Cloudflare Tunnel Public Hostname added
(`grafana.unicornpingpong.com` → `http://cabin-grafana:3000`), and a
Cloudflare Access Application + Policy created (Allow, Emails matching
`ADMIN_EMAILS`, One-Time PIN). **Despite all of that being in place,
neither access gate is confirmed actually working:**

- **Cloudflare Access is not gating the hostname.** A fresh,
  cookie-free request to `https://grafana.unicornpingpong.com/grafana/login`
  returns Grafana's own login page directly (`200`, real Grafana HTML)
  with no Access challenge at all — confirmed at the very end of the
  session. It briefly *appeared* to be enforcing earlier (an in-browser
  test showed a challenge), but the last direct verification says
  otherwise. Not yet root-caused — possible leads for next time: the
  Application's domain-match config, whether the Application needs an
  explicit "publish" step beyond "saved," or a Cloudflare-side
  propagation delay that a longer wait would resolve (unlike everything
  else in this saga, this one hasn't been given a clean multi-minute
  settle-and-retest).
- **Grafana's own Google OAuth login fails with a Google-side 403**
  ("We're sorry, but you do not have access to this page. That's all
  we know.") for `nhsmrekar@gmail.com` — the same account that already
  works fine for cabin-ui/family-hub through the same OAuth client.
  Ruled out, confirmed NOT the cause: the account is listed as a Test
  user on the OAuth consent screen; the Google People API is enabled
  on the project; the outgoing authorization request itself
  (`client_id`, `redirect_uri`, `scope`, PKCE params) is correctly
  formed and matches what's registered. Root cause not found. One
  untested lead: this request uses `access_type=offline` (requesting a
  refresh token) with `prompt=consent`, a meaningfully different
  request shape than cabin-ui's implicit-flow `openid email` scopes —
  worth checking whether offline/refresh-token access has a stricter
  or different requirement for Testing-mode apps that hasn't been hit
  by the simpler flow.

**Because neither gate is confirmed working, Grafana's password login
was deliberately disabled** (`GF_AUTH_DISABLE_LOGIN_FORM: "true"` in
`docker-compose.m920q.yml`, commit `6794ae0`) rather than leave a
guessable admin password reachable from the open internet overnight.
Confirmed via `curl`: password login now returns `400
auth.client.notConfigured`. **This means nobody — including the
admin — can currently log into Grafana at all**, by any method, until
one of the two gates above is fixed. `hub`/`cabin`/`api` are
unaffected; this only touches Grafana.

**Next session, in order:**
1. Give the Cloudflare Access question a real multi-minute settle
   period and retest with a fresh, cookie-free `curl` (or private
   browser window) before assuming it's still broken — this specific
   check was never given the "wait and retry" treatment everything
   else in this session's Cloudflare work needed.
2. If Access still isn't gating after that, re-check the Application's
   exact domain-match configuration in Zero Trust → Access →
   Applications.
3. Separately, investigate the Google 403 — the `access_type=offline`
   lead above is the most promising untried thread. Chrome/Firefox
   DevTools' Network tab on the actual failed request may show a more
   specific error than the generic page does.
4. Once **at least one** gate is confirmed genuinely working, decide
   whether to re-enable `GF_AUTH_DISABLE_LOGIN_FORM` as a fallback or
   leave it off now that a real gate exists — don't re-enable it
   reflexively without that decision.

---

## MQTT WebSocket listener (cabin-ui's Live MQTT tile)

**Added 2026-08-08.** cabin-ui's Monitoring panel has always had a "Live
MQTT" tile pointed at `LOCATIONS.cabin.wsBase`
(`ws://100.77.44.113:9001`), and always shown "No live messages" — not
a bug in that URL, there was simply no WebSocket listener on mosquitto
at all. Confirmed directly: `mosquitto.conf` only had `listener 1883`
(raw MQTT), and nothing was listening on port 9001 anywhere on the
M920q (`ss -tln`, empty).

**Fixed on the M920q's pre-existing stack, not this repo** — mosquitto
here is managed by `/storage/containers/compose/cabin/docker-compose.yml`
(the same "M920q docker-compose.yml, currently local-only" flagged as a
Phase 1 blocker in `ROADMAP.md` — still not pushed to CabinAutomations,
still a real gap, this fix lives on top of that same gap):

1. `/storage/services/mosquitto/config/mosquitto.conf` — appended a
   second listener:
   ```
   listener 9001
   protocol websockets
   ```
   (backed up first: `mosquitto.conf.bak-<timestamp>` in the same dir).
2. `docker-compose.yml`'s `mosquitto` service — the config change alone
   wasn't enough; mosquitto bound port 9001 *inside* the container
   (confirmed in its logs: "Websockets support available" / "Opening
   ipv4 listen socket on port 9001") but nothing published it to the
   host — `docker port mosquitto` only showed `1883`. Added `- "9001:9001"`
   to that service's `ports:` list (backed up first, same convention),
   then `docker compose up -d mosquitto` to recreate the container with
   the new port binding — a plain `docker restart` would not have picked
   this up, since port publishing is set at container-creation time.

**Verified working, not just deployed**: a real WebSocket handshake
against `http://100.77.44.113:9001` (curl, `Upgrade: websocket`,
`Sec-WebSocket-Protocol: mqtt`) returns `HTTP/1.1 101 Switching
Protocols` with `Sec-WebSocket-Protocol: mqtt` echoed back — a genuine
MQTT-over-WebSocket connection, not just an open port. All existing MQTT
clients (`cabin-orchestrator`, `z2m-adapter`, `nodered-cabin-security`)
reconnected cleanly after the container recreate; `cabin-backend`'s
`/actuator/health` stayed `UP` throughout.

**Not yet verified**: that cabin-ui's actual `useMqttTelemetry` hook
renders real live messages end-to-end in a browser once connected — the
transport now works, but the tile's own message-handling code hasn't
been exercised against a real live connection since this fix (it was
never reachable before, so it's realistically untested code, not just
unverified today).

---

## Known Issues & Operational Lessons

*A working incident log — real problems found and fixed, kept here so
the next person (or session) doesn't have to rediscover them.*

### Grafana redirected every remote request to "localhost" (found and fixed 2026-08-03)

Reported as "won't load off Tailscale" — cabin-ui already carries an
honest warning label next to the Grafana embed to that effect
(`App.jsx`: "Won't load off Tailscale — Grafana is cabin-network-only"),
so the report read at first like a case of that label finally getting
noticed, not a bug. It was a bug. `GF_SERVER_ROOT_URL` was set to
`"%(protocol)s://%(domain)s:%(http_port)s/grafana"` — Grafana fills
`%(domain)s`/`%(http_port)s` from its own `[server]` settings, neither
of which was ever configured, so they silently defaulted to
`domain=localhost` and `http_port=3000` (Grafana's *internal* container
port — it has no idea this host remaps it to `3002`). Confirmed via
`curl -I http://100.77.44.113:3002/`: connection succeeded fine (so it
was never actually a Tailscale/network/firewall issue), but the
response was `301` to `http://localhost:3000/grafana/` — "localhost"
meaning the *viewer's own machine*, which has nothing listening on port
3000. Every real browser hit this same redirect and failed to load,
regardless of network path. **Fixed** by making `GF_SERVER_ROOT_URL`
fully explicit (`http://${GRAFANA_EXTERNAL_HOST:-100.77.44.113}:3002/grafana`,
new optional env var, see `.env.m920q.example`) instead of relying on
template substitution that had no way to know about the host's port
remap. **Lesson**: when a Docker port mapping remaps the external port
(`3002:3000`), any app-level "what's my own URL" template variable
needs to be told the *external* port explicitly — it cannot infer the
remap from inside the container.

**Follow-up, 2026-08-04**: once this redirect bug was fixed, the user
asked a fair follow-up — did they actually want Tailscale-only, or did
they want Grafana reachable from anywhere? They chose the latter.
`GRAFANA_EXTERNAL_HOST`'s meaning and default changed accordingly (now
a public hostname, not the Tailscale IP) — see the new "Grafana — Off-
Tailscale Access" section below for the real fix that followed this one.

### CORS preflight requests were silently rejected (found 2026-08-03)

`GoogleAuthInterceptor` checked for a valid Bearer token on *every*
request matching its gated paths, including CORS preflight `OPTIONS`
requests — which never carry a real credential, by design. Browsers
require the preflight response itself to be a plain 2xx or they refuse
to send the real request at all, **regardless of which CORS headers are
present on a non-2xx response**. `curl` does not enforce this rule,
which is exactly why extensive `curl`-based testing repeatedly (and
wrongly) looked correct. **Real impact**: this silently broke notes,
chores, profile, and camera sync from any real browser this entire
project, the whole time — each has a graceful offline fallback, which is
exactly why it went unnoticed. **Lesson**: `curl` cannot validate CORS
preflight behavior. Any endpoint gated by an auth interceptor and called
cross-origin needs a real-browser test, not just a `curl` check, however
thorough the `curl` check looks.

### Kafka `__consumer_offsets` replication factor (found 2026-08-02)

See "Database & Storage" above — single-broker Kafka silently fails to
create the internal offsets topic at its default replication factor 3,
with consumers hanging forever and no visible error.

### `-parameters` compiler flag missing (found 2026-08-02)

Unnamed `@RequestParam`/`@PathVariable` arguments throw
`IllegalArgumentException` at the *first real invocation* without
Spring Boot's `-parameters` javac flag — not at compile time, not at
startup, only when the endpoint is actually hit. Fixed globally in
`pom.xml`'s compiler plugin config, since it silently affected every
other unnamed `@RequestParam` in the codebase, not just the one endpoint
that surfaced it.

### JDBC `jsonb` columns return `PGobject`, not `String` (found 2026-08-02)

Casting a `jsonb` column's value directly to `String` in a generic
`JdbcTemplate` row-mapping throws `ClassCastException` — the driver
returns a `PGobject`. Call `.toString()` on the raw `Object` first.

### Docker Compose build args silently dropped without matching `ARG`/`ENV` (found 2026-08-02)

A `docker-compose.yml` `args:` entry does nothing unless the Dockerfile
has a matching `ARG` *and* re-exports it via `ENV` — otherwise Vite (and
similar build tools) never sees the value, and the build silently falls
back to whatever default is baked into the source. Confirmed this had
been happening for `VITE_CABIN_HA_URL`/`VITE_CABIN_Z2M_URL`/
`VITE_CABIN_FAMILY_HUB_URL` for some time before being caught.

### Cloudflare edge-caching a build-time config file (found 2026-08-01)

`host-config.js` (carries `GOOGLE_CLIENT_ID`/`ADMIN_EMAILS`/`CABIN_API_URL`,
regenerated on every deploy) got caught by a blanket `.js` cache rule
meant for genuinely static assets, then cached at Cloudflare's edge for
7 days as `immutable`. A stale cached copy silently disables
cross-device sync for real visitors with no visible error — it just
degrades to the offline/localStorage-only fallback. Fixed with an
exact-match no-cache rule for this specific file; **an already-cached
edge copy still needs a manual Cloudflare purge** to take effect
immediately rather than waiting out the TTL.

### Ansible role search path breaks when a playbook is nested (found 2026-08-02)

`ansible/playbooks/rotate-secrets.yml`'s `include_role: name: secrets`
failed to find `ansible/roles/secrets` — Ansible's default role search
path is relative to the *playbook's own directory*, not the `ansible/`
root. Fixed via `ansible/ansible.cfg`'s `roles_path`, discovered via
cwd-relative lookup (matches every documented usage's `cd ansible`
first).

### Self-targeting via Tailscale hostname fails when run from the same host (found 2026-08-02)

See "Secrets" above — a genuine hairpin routing limitation, not an
Ansible bug.

### Never diff secrets by raw value (found 2026-08-03)

See "Secrets" above.

### Manual MQTT test publishes leave permanent fake events behind (found 2026-08-03)

Testing the overnight camera alert flow via `mosquitto_pub` directly
against the live `cabin/camera/events` topic worked correctly for
verifying the pipeline, but `MqttBridgeService` has no way to
distinguish a real Frigate detection from a manually-injected test
message on the same topic — every test publish became a permanent,
real-looking `cabin_event` row (complete with a plausible `person`/`dog`
label and confidence score), indistinguishable from a genuine detection
in `/api/events` or the Camera Events panel. Found when the user
reasonably asked why "detected" events had no video — they were never
real detections, just leftover test artifacts nobody cleaned up.
**Lesson**: after any live MQTT test against a production topic, delete
the resulting event rows explicitly (`DELETE FROM cabin_event WHERE
event_id IN (...)`, exact IDs only, never a broad time-range or label
match) — don't leave test data mixed into a table real users see through
the actual product UI. This action correctly requires explicit user
confirmation (Claude Code's auto-mode classifier blocks unconfirmed
`DELETE`s against a live database) — that's working as intended, not a
tool to route around.

### `RemoteTrigger` scheduled routine returns 403 without explicit repo access grant (found and resolved 2026-08-03)

Creating a claude.ai cloud routine (the mechanism behind the Tech ID
Service's reference scheduled scan — see `ROADMAP.md`'s "Tech ID Service
— Provider Model") against `ImpressiveLLC/FaceoftheCabin` failed with
`HTTP 403: "You don't have access to a repository this routine uses."`
even with full push access to the repo via git/SSH. Root cause: the
general claude.ai GitHub connector (repo read/search) is a separate
grant from the GitHub App's own per-repo access used by Code
environments/routines — the latter needs explicit approval for an
org-owned repo (`ImpressiveLLC`), which a personal-account connector
doesn't cover. **Resolved**: the org owner opened a regular (non-
scheduled) Code session against the repo from claude.ai, which
succeeded once GitHub access was actually in place and provisioned a
real environment. Routines require `job_config.ccr.environment_id` (or
`self_hosted_runner_pool_id`) — confirmed via a live `400` from the
`RemoteTrigger create` API when a bare repo URL was tried instead — and
that Code session's own chat UUID turned out to double as its
`environment_id`, so no separate lookup UI was needed once the session
existed. The reference routine (`trig_0188XfA9eewXVEoumKr7tmkC`,
monthly, `0 8 1 * *` UTC) is now live using that environment.

**Still open**: the routine's cloud sandbox has repo access but no path
to the Ansible-vaulted `TECH_ID_API_KEY`, so it currently reports
findings via a PR against `docs/ontology.yaml` rather than `POST
/api/tech-id/findings` — hardcoding a real secret into a stored,
remotely-visible trigger definition was rejected as unsafe, and no
secrets/env-injection field was found on the trigger schema during
setup. The prompt checks for `TECH_ID_API_KEY` as a sandbox env var and
POSTs opportunistically if present, but nothing sets it today.

### Camera panel: stale auth looked signed-in, failures rendered as silent blanks (found and fixed 2026-08-03)

An external code review of cabin-ui's Camera Events panel (no code
changed by the reviewer — read-only findings from a real browser
session) found the underlying bugs were in cabin-ui's own error
handling, not the backend or Frigate:

- **Expired Google token treated as authenticated.** The former `useGoogleAuth`
  stored the access token in `sessionStorage` with no expiry tracking,
  so a genuinely expired token still rendered "signed in" (email shown,
  "Sign out" present) while every authenticated request 401'd and
  callers quietly converted that into an empty list. **Fixed**: token
  storage now includes `expires_in` (with a 30s safety margin); a
  stored token already past that expiry is refused on load, not
  resurrected; and every authenticated call now goes through one
  `authedFetch` helper that clears the session and sets
  `sessionExpired` on any `401`, instead of each caller independently
  swallowing the failure. **Superseded 2026-08-09:** cabin-ui no longer stores
  or resurrects a Google token at all. `usePlatformAuth` validates the durable,
  revocable first-party session before mounting the application, tracks its
  server-issued expiry, and keeps the same centralized `401` behavior.
- **A dead camera stream rendered as an unexplained blank box.**
  `CameraLiveView`'s `<img>` against Frigate's MJPEG stream had no
  `onLoad`/`onError` handling — a broken stream produced a "completed"
  load event with `naturalWidth`/`naturalHeight` of `0`, which looked
  identical to a working-but-quiet camera. **Fixed**: explicit
  `loading`/`ok`/`error` status, a zero-dimension "load" now counts as
  a failure, an 8s bounded timeout catches a request that never
  resolves either way, and an error state renders "Camera unavailable"
  instead of nothing.
- **The "API offline" badge was checking the wrong thing.** It pinged
  `/actuator/health` directly from the browser, which — unlike every
  business endpoint (`@CrossOrigin`) — has no CORS configuration, so the
  browser blocked reading the response every time regardless of whether
  the backend was actually up. **Fixed**: "connected" is now derived
  from whether the `/api/devices` fetch this panel already depends on
  actually succeeded, rather than a second, separately-broken check.
  Deliberately did not add CORS to Actuator just to drive a status
  badge — that would widen Actuator's exposure for a cosmetic fix.

See `cabin-orchestration-platform/ui/src/App.jsx`'s `usePlatformAuth`,
`CameraLiveView`, and `App()`'s `refreshDevices` for the fixed code —
each carries an inline comment dated the same day explaining the bug.

### Blink camera silently stopped producing frames for hours, no auto-recovery (found and fixed 2026-08-03)

Same external review flagged `/api/events` returning empty and asked
whether Frigate detection, MQTT, or the event adapter were at fault.
Root-caused via `docker exec frigate curl localhost:5000/api/stats`:
both cameras showed `camera_fps: 0.0` — genuinely no incoming video, so
MQTT/Kafka/Postgres (all confirmed healthy) had nothing to carry. Full
detail and fix in "Cameras (Frigate)" above — summary: `blinkbridge`
disabled itself after a transient Blink cloud-API failure and never
retried; `docker restart blinkbridge` recovered it immediately
(`camera_fps` `0.0` → `5.1`). `front_door`'s `0.0` is the separate,
already-documented off-network issue, not part of this incident.

---

## Monitoring

Uptime Kuma + Homepage on the M920q (`http://192.168.2.46:3001` /
`100.77.44.113:3001`). Pre-existing monitors (Homepage, Home Assistant,
Frigate root, Node-RED, a Tailscale reachability ping) just check that
each service responds — they say nothing about whether cameras are
actually recording.

**`Frigate driveway camera_fps`** (added 2026-08-07, id `7` in Uptime
Kuma's own DB) is the first monitor that actually checks that: a
JSON-query monitor hitting `http://frigate:5000/api/stats`, evaluating
`cameras.driveway.camera_fps > 0` (JSONata syntax — Uptime Kuma 2.x's
`json-query` monitor type). This is the health check the `blinkbridge`
incidents above kept flagging as missing — if `driveway`'s `camera_fps`
ever drops back to `0` (the exact symptom of `blinkbridge` silently
disabling again), this goes DOWN and pages instead of requiring someone
to notice days later. `maxretries: 2` / `retry_interval: 60s` gives a
brief grace window so a single transient blip doesn't page.

**Uptime Kuma had zero notification channels configured before this** —
every existing monitor could go red with nobody told, silently, possibly
for a long time. Added one: `ntfy - cabin alerts`, set as the account
default, reusing the same ntfy.sh topic `cabin_critical_event_alert`
already established (see `docs/ontology.yaml`) — not a second
subscription for you to add in the app. **Not yet attached to the
pre-existing monitors** (Homepage/HA/Frigate/Node-RED/Tailscale) — see
`DEFINITION_OF_DONE.md`'s punch list; a reasonable, cheap follow-up now
that a channel actually exists.

Built via direct SQLite manipulation of Uptime Kuma's own
`/app/data/kuma.db` (`monitor`, `notification`, `monitor_notification`
tables) followed by a container restart to load it — there's no simpler
config-as-code path in this Uptime Kuma version; the web UI is the
normal way to do this, but wasn't reachable from this session's browser
tool (private-IP access gate). Verified live: `SELECT ... FROM heartbeat
WHERE monitor_id=7` showed a real passing check
(`"JSON query passes (comparing 5.1 > 0)"`), not just a green icon
trusted at face value.

---

## Incident Response — quick triage

1. **Public site unreachable** — check `docker ps` for `cloudflared`
   first; a crashed tunnel container is the most common single cause.
   `docker restart cloudflared` if it's not running.
2. **A feature "isn't syncing"** — check the browser's Network tab for
   the actual request/response, not just that the UI looks broken. A
   surprising number of real bugs in this project (see Known Issues
   above) looked like application bugs but were actually silent
   network-layer failures invisible to `curl`-based testing.
3. **`cabin-backend` returns 401 unexpectedly** — check
   `GoogleAuthInterceptor`'s OPTIONS bypass is still in place (see Known
   Issues) before assuming it's a token problem.
4. **A container won't start after a config change** — check the
   relevant service's logs first (`docker logs <container> --since 1m`),
   and confirm the config file is valid before assuming the container
   itself is broken.
