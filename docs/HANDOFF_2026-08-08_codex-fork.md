# Handoff — 2026-08-08 → 2026-08-14, Codex Fork Session

> Written because the user is about to hit their weekly Claude Code usage
> limit and intends to fork `ImpressiveLLC/FaceoftheCabin` in git and
> continue work with a different AI coding tool ("Codex") until Claude Code
> is available again — **planned resume date: Friday, 2026-08-14.** This
> document is the operating charter for that gap. It exists to (a) give
> Codex the same check-down discipline this project already runs on, (b)
> hard-guardrail anything that touches shared/production infrastructure so
> a fork session can't collide with the canonical repo or the live M920q,
> and (c) leave a precise enough state snapshot that reconciling the fork's
> work back into a Claude Code session on 8/14 is tractable rather than a
> reconstruction project.
>
> Last commit on `main` as of writing: **`6dbbadc`** — "Fix Device
> Manager's location filtering; add the missing 'Add Place' UI". Working
> tree clean, `main` up to date with `origin/main`, nothing uncommitted.
>
> **Update, same day, before the limit was actually reached:** got real
> work done on Item 1 (device checkin-status tiering) and Item 4 (the
> Rules & Alerts location-split, both sub-items now done); then, in a
> follow-up planning-only round, added a new Item 6 (armed-state trigger
> gap + native camera-confidence UI — includes a real bug find, not yet
> fixed) and a hardware-tier framework for Item 5 (`ROADMAP.md`'s new
> Phase 8) — **which the user then corrected**: not full-stack-per-device,
> but a lightweight local collector routing to a central "main brain."
> Phase 8 was rewritten accordingly, and the user then confirmed Home
> itself follows this model — a collector hub routing into Cabin's M920q,
> not a full independent peer, ideally with a kiosk touchscreen loading
> Family Hub too. See the revised Item 5 below for the concrete
> recommendation. This file is being kept current in place per its own
> §5 instruction rather than left stale.
>
> **Codex continuation, 2026-08-09:** Item 1's MQTT/Zigbee availability
> work has been reconciled with the ontology and the user's explicit device
> security semantics. Admission, configuration, enablement, and liveness are
> now independent. Unknown observations are durable review candidates only;
> rejected sources remain recognizable; operational state requires a verified
> source binding plus `ADMITTED`, `CONFORMING`, and `ENABLED`. Existing devices
> are not grandfathered. The earlier synthetic `NOT_CONFIGURED` liveness state
> has been removed. No live deployment or Google Drive access occurred.

---

## 0. Read this first, then follow the project's own check-down order

This project already has a documentation hierarchy — don't invent a new
one. In order:

1. **`CLAUDE.md`** (repo root) — full project context: architecture, repo
   structure, both locations' hardware/URLs, env vars, API endpoint list,
   UI panel list, CI/CD mechanics, testing setup, design constraints.
   Start every session here.
2. **`ROADMAP.md`** (repo root) — phased priority task list and current
   status per phase. Check this before starting any new work to see if
   it's already scoped, in progress, or explicitly deferred.
3. **`docs/DEFINITION_OF_DONE.md`** — the session exit checklist (10
   numbered sections) **and**, at the bottom, a live **"Next Session — Open
   Items"** punch list. That punch list is the single most current summary
   of what's actually still broken/open — read it before touching anything
   this doc doesn't mention below.
4. **`docs/ontology.yaml`** — the canonical definition of every user-facing
   configurable concept (storage keys, transformation functions, source of
   truth). If you add or change a concept a user can configure, it needs an
   entry here in the same commit.
5. **`docs/PRODUCT_NOTES.md`** — design-decision rationale (the *why*
   behind UX choices). **`docs/MAINTENANCE.md`** — operational runbook,
   incident log, exact recovery procedures. Consult whichever is relevant
   to the task; both are referenced by name from `CLAUDE.md` and
   `ROADMAP.md` at the specific points where they matter.
6. **`docs/EXECUTION_PLAN_*.md`** — dated, scoped implementation plans for
   specific multi-part features (this file follows that same naming
   convention). If a plan file already exists covering your task, follow
   it instead of re-deriving scope.

**Every one of those files must stay accurate.** `docs/DEFINITION_OF_DONE.md`
section 3 and 7 exist specifically to catch drift between docs and code —
run that checklist, not just a mental review, before considering any
chunk of work done.

---

## 1. Hard guardrails — read before Codex touches git, CI, or the M920q

These exist because of a **real incident earlier in this project**: a
concurrent session ran a destructive cleanup command without checking
`git status` first and destroyed another session's unpushed commits. The
risk of two independent, loosely-coordinated sessions (this fork + the
eventual Claude Code resume) operating against the *same shared production
host* is structurally the same shape of risk. Treat everything below as
non-negotiable, not a suggestion.

### 1a. The self-hosted GitHub Actions runner will NOT serve a fork

Both deploy workflows target `runs-on: [self-hosted, cabin-m920q]`:
- `.github/workflows/deploy-cabin-backend.yml`
- `.github/workflows/deploy-family-hub.yml`
- `.github/workflows/rotate-secrets.yml`

This runner is a physical process registered on the M920q **specifically
to `ImpressiveLLC/FaceoftheCabin`**. Self-hosted runners are registered
per-repository in GitHub — a fork gets its own separate Actions
environment with **no runner attached at all**. Concretely:

- Pushing to a fork's `main` will **not** deploy anything, anywhere. The
  workflow files exist in the fork's copy but have nowhere to execute.
  Do not assume "CI just works" on the fork, and do not interpret a green
  or absent Actions run as proof of anything.
- **Do not register a second self-hosted runner on the M920q pointed at
  the fork.** That would create two independent deploy pipelines capable
  of racing each other against the same containers, volumes, and Postgres
  database — the exact multi-pipeline-one-host hazard the earlier `rm -rf`
  incident already demonstrated the cost of, just via CI instead of a
  manual shell session.
- If the fork's work needs to be *tested* against real infrastructure
  before 8/14, that is a **manual, explicit, user-approved** deploy
  (see `docs/MAINTENANCE.md`'s "Manual (cabin-backend)" section for the
  existing safe procedure), never an automatic one from the fork.

### 1b. Git safety

- **Never push to `ImpressiveLLC/FaceoftheCabin`'s `main` from the fork.**
  All fork work stays on the fork (or a branch within it) until the user
  explicitly reconciles it back — see §5.
- **Never force-push, `git reset --hard`, or delete branches** without the
  user explicitly asking for that specific action in that specific moment.
- **Always run `git status` before any destructive operation** (checkout,
  restore, reset, clean, or any `rm -rf` touching a repo path). If
  anything unexpected is sitting there (uncommitted changes, an unfamiliar
  branch), stop and ask rather than clearing it — it may be in-progress
  work, including the user's own manual edits made outside a session.
- **Never skip hooks** (`--no-verify`) or bypass signing unless the user
  explicitly asks.
- **Commit messages describe why, not just what** — this project's
  existing `git log` is the style reference; skim the last ~15 commits
  before writing new ones.

### 1c. Production host safety (the M920q, `unicornpingpong.com`)

- The M920q runs **live, in-use production services** for a real family
  (cameras, leak/smoke sensors, door locks) — not a sandbox. Nothing gets
  SSH'd into or deployed on it without the user explicitly present and
  approving that specific action.
- Never touch `docker-compose.m920q.yml`'s disabled-services list
  (`mqtt`/`homeassistant`/`nodered`/`frigate`/`watchdog` are intentionally
  disabled there because a separately-managed stack for those already runs
  on that host — re-enabling them would double-start services and conflict
  on ports).
- Never print, log, or diff a secret by its raw value. Compare by
  presence/hash only. (A real incident already happened here: a
  `docker inspect` env dump printed `BLINK_PASSWORD` in plaintext into a
  session transcript — see §4 below, this is still an open item.)
- Secrets never get committed to make CI "just work," on the fork or
  otherwise.

### 1d. Testing — no loose code, even without working CI

`docs/DEFINITION_OF_DONE.md` §10 requires every new piece of testable
logic to ship with a real test in the suite CI already runs:
`backend/src/test/` (JUnit + Testcontainers), `cabin-orchestration-platform/ui`'s
Vitest suite (`src/*.test.jsx`), `family-hub/test/run.js`'s Playwright
checks. **Because the fork's CI won't execute (§1a), this becomes a
manual gate instead of an automatic one — it does not become optional.**
Before considering any fork-side task done:
- Backend: `./mvnw test` (needs the pinned Temurin 21 JDK — see
  `CLAUDE.md`'s "Testing" section for the exact JAVA_HOME gotcha and two
  other pre-existing environment gaps already solved there).
- Frontend (cabin-ui): `cd cabin-orchestration-platform/ui && npm test`
  (Vitest).
- Family Hub: `cd family-hub/test && node run.js` (Playwright).

State explicitly which of these actually ran and passed in whatever
summary/commit accompanies the work — "looks right" is not the same claim
as "the suite ran and passed." If a test genuinely can't run in Codex's
environment (e.g. no Docker for Testcontainers), say so explicitly rather
than silently skipping it.

### 1e. No silent scope narrowing, no drift

- If a requested item can't be done, say so explicitly — never quietly
  drop it.
- If something looks inconsistent with what's documented mid-task
  (a config value, a name, a piece of state), stop and reconcile it right
  then against git history/docs rather than noting it and moving on —
  this project has already been burned once by not doing that (see
  `docs/DEFINITION_OF_DONE.md`'s "(i) Additional practices" section for
  the full story).
- Docs written *about* a change ship in the *same* commit as the change.

---

## 2. Current state snapshot (verified, as of `6dbbadc`)

Everything through commit `6dbbadc` is committed, pushed to
`origin/main`, and — because both deploy workflows trigger automatically
on push to the canonical repo's `main` — already live and deployed on the
M920q. Recent work this session, newest first:

- `6dbbadc` — Fixed `DeviceManagerPanel` showing devices from the wrong
  location (it read `activeLocation` for a localStorage key but never
  actually filtered the `devices` array passed to its See/Change/Remove
  sub-views). Added `allLocationsLabel()` so the location-switcher's
  "Both" option becomes "All" once a 3rd location exists instead of
  staying hardcoded at "Both". Added the first-ever UI for creating a new
  location (`AddPlaceForm`, `POST /api/locations`).
- `196b8e8` — Replaced the Grafana iframe in the Monitoring panel with a
  native `CameraHealthPanel` (Prometheus-sourced via a new backend proxy,
  not a public Grafana exposure). This was an explicit user decision, not
  a bug fix — see §3 item 2026-08-08-a below for the full reasoning.
- `7801adc` — `FrigateMetricsController` (`GET /api/frigate-metrics`):
  server-side Prometheus query proxy so Prometheus itself stays
  Tailscale/internal-only, never publicly exposed.
- `b236660` — **The real root cause** of a whole cluster of symptoms the
  user reported (devices not showing, Grafana/Node-RED/Live-MQTT panels
  broken, showing internal Docker hostnames): the `hub_locations` Postgres
  table had been seeded, very early in a prior session, with unreachable
  Docker-internal default URLs, and because the frontend's
  `mergeHubLocations()` lets API data override hardcoded/env defaults on
  every page load, every subsequent URL fix (Node-RED, Grafana) was
  silently overridden back to broken on next load. Fixed live via a
  `PATCH /api/locations/cabin` call, then at the source by adding the
  missing `CABIN_LOCATIONS_CABIN_*` env vars to
  `infra/docker-compose.m920q.yml` so the seed is correct from boot.
  **Verified surviving a real backend restart.**

All of the above have real tests wired into the existing CI gates
(`FrigateMetricsControllerTest`, `App.test.jsx` additions for
`cameraHealthLabel`, `allLocationsLabel`, and `AddPlaceForm` — 52/52 green
in the frontend suite as of that commit).

**One commit newer** (after this handoff doc was first written, same
session, before actually stopping): **Item 1 below (device checkin-status)
got a real, tested first increment** — `CheckinStatus` enum
(`ON_SCHEDULE`/`LATE`/`MISSED`), `DeviceHealthMonitor`
tiering with a real active HA poll before escalating past the grace tier,
`GET /api/devices/checkin-status`, and Device Manager/Monitoring badge
wiring. Full detail in `ROADMAP.md`'s Phase 7 (newest entry) and
`docs/ontology.yaml`'s new `device_checkin_status` entity — this file
doesn't duplicate that detail, just flags that Item 1 is no longer
"not started." See the revised §3 Item 1 below for exactly what's done vs.
still open within that item.

### Things resolved this session that are NOT bugs to reopen

- **The Grafana iframe's original "white screen" root cause was
  correctly diagnosed and fixed** (the `hub_locations` bug above, verified
  via Grafana's own server logs showing a real referer for the first time)
  — but the user then **deliberately decided to replace the iframe
  entirely**, not just fix it, because a session-dependent embed a
  "just wants it to work" user has to remember to scroll inside isn't
  acceptable for a mixed-technical-skill multi-user product. Do not
  reintroduce a Grafana iframe as a "simpler fix" without checking with
  the user first — that would be re-litigating a decision already made.
  A plain Grafana **link-out** still exists for anyone who wants the full
  dashboard.
- The `blinkbridge`/`driveway` camera outage this session was a
  **transient Blink cloud-API failure**, not a code regression — confirmed
  recovered (`docker restart blinkbridge`, `driveway` back to real FPS).
  No code change was needed or made for this.

---

## 3. Open work items — from the user's most recent request, in their words

The user's last substantive request before the usage-limit/handoff
message was a 5-part "big things right now" list. **None of these five
have been started.** Full context for each:

### Item 1 — Device state semantics are misleading — PARTIALLY DONE

> "devices that show offline are not necessarily offline just not
> reporting... the correct state is probably something like 'checking in
> on schedule' / 'not checked in on schedule' / 'hasn't checked in' /
> 'not configured' and we need a listener with an HA, mq, or rules based
> (automation check) way to know in the UI if it's basically good or bad
> based on assumption that it's only bad if we haven't heard back from a
> known device and attempts to ping it aren't successful. The sockets or
> cards for each device, cameras included, should have this behavior
> baked-in."

**Built this session** (see `ROADMAP.md`'s Phase 7 newest entry,
`docs/ontology.yaml`'s `device_checkin_status`): a new `CheckinStatus`
enum — `ON_SCHEDULE` / `LATE` / `MISSED` — computed by
`DeviceHealthMonitor` every 60s
cycle as an *additional* axis alongside `DeviceStatus.state`, not a
replacement (nothing that reads `state` needed to change). A device gets
a grace tier (LATE) before anything downstream calls it OFFLINE; `state`
itself still only flips to OFFLINE at the MISSED tier. `GET /api/devices/
checkin-status` exposes it; `checkinStatusLabel()` in `App.jsx` overrides
the badge shown in Device Manager (`DmDeviceRow`, `DmDeviceDetail`) and
Monitoring (`KpiListItem`) — never for ALARM/CRITICAL, which always shows
as itself. 6 backend tests + 5 frontend tests, all green; verified live in
a browser preview with no console errors (backend unreachable from that
sandbox, so only graceful-degradation was actually confirmed there — see
"still open" below).

**The active-verification half is real but partial**, and this is stated
explicitly rather than left implied-complete: for `ha_rest` devices,
`DeviceRegistry.activeFetch()` calls the real `HomeAssistantAdapter.
fetchState()` (a genuine HTTP round-trip to HA, not a simulation) before a
device is allowed to escalate to MISSED — if that poll succeeds, the
device recovers immediately regardless of how stale it looked. This is
the actual "ping it, only bad if attempts aren't successful" mechanism
the user asked for. MQTT/Zigbee retained availability and RTSP socket
reachability now provide separate protocol-specific checks as described below.

**Ontology/security correction built 2026-08-09:** the first increment's
`NOT_CONFIGURED` value conflated configuration with enablement and liveness.
It is removed. `DeviceCatalogService` now stores retained candidates, catalog
entries, explicit identity bindings, and append-only lifecycle decisions.
Discovery cannot allocate a runtime device. Only a bound catalog entry whose
independent states are `ADMITTED`, `CONFORMING`, and `ENABLED` can create state,
events, rule inputs, alerts, probes, or commands. The 13 historical Cabin
Zigbee records and 14 described Home prospects are seeded only as `AVAILABLE`,
`READY_TO_CONFIGURE`, `DISABLED`, and unbound; there is no grandfathering.
Rejected candidates remain rejected when they resurface, while an attributable
operator may reconsider a false negative without auto-admitting it. Lifecycle
review requires Google authentication plus an explicit operator allowlist;
connection material is redacted from catalog and legacy config APIs.
The owner-facing review UI, live IEEE adjudication for the 13 existing devices,
non-Zigbee publisher/ACL evidence, and production migration remain open and
require an explicitly approved live session. Direct legacy Device Manager
add/edit/remove calls now fail closed rather than bypassing the lifecycle.

**Still open, if picking this item back up:**
- **MQTT/Zigbee retained-availability check is now built** — stale Z2M
  devices re-subscribe to their authoritative availability topic and only
  recover on retained `online`. Retained `offline`, malformed/non-retained
  traffic, timeout, or broker disconnect does not hide MISSED. The ontology
  now keeps availability on the existing `mqtt_availability_topic`, defines
  retained delivery separately as `mqtt_message_retained`, and derives the
  normalized probe result from both facts. Both new concepts remain candidates
  until verified against the real M920q broker and devices.
  Targeted backend tests: 17/17. Full backend run: 81/84 passed; the only
  errors were the 3 already-documented Testcontainers tests because Docker
  is unavailable in this environment.
- **RTSP connect-and-drop check is now built** — enabled RTSP descriptors
  use a bounded TCP connection to the configured URI host/port. Success may
  recover check-in status; failures fail closed. The ontology deliberately
  distinguishes socket reachability from authentication, decodable frames,
  and Frigate FPS. `rtsp_stream_uri`, `rtsp_socket_reachable`, and
  `rtsp_connect_timeout_ms` remain candidates pending verification with a
  real enabled camera from the running backend. Targeted RTSP/monitor tests:
  16/16. Full backend run: 87/90; only the same 3 Docker/Testcontainers tests
  failed to start because Docker is unavailable.
- **Not yet verified against the real M920q backend with real devices**
  — only verified in a browser preview with the backend unreachable
  (graceful-degradation path only) and via unit/integration tests. Watch
  in particular whether the `LATE` grace tier (3x the existing stale
  threshold — e.g. 45 min for `ha_rest`/thermostat/lock-type devices
  before OFFLINE fires at all) feels right in practice, or needs tuning;
  it's a placeholder multiplier (`MISSED_MULTIPLIER = 3` in
  `DeviceHealthMonitor.java`), not user-validated.
- **Camera cards specifically**: the user said "cameras included" —
  Frigate-based cabin cameras go through `CameraHealthPanel` (separate,
  already-existing FPS-based labeling, untouched by this change) rather
  than `DeviceRegistry`'s checkin tracking. Home's RTSP camera
  prospects exist in `DeviceCatalogService` as `AVAILABLE` but do not flow
  through `DeviceRegistry` at all. Consequently they have no check-in row,
  raw offline state, event, or warning authority. This replaces the earlier
  disabled/`NOT_CONFIGURED` mitigation with the stronger ontology boundary:
  only operational devices can be counted by `alert_eligible_offline_count`.
  Focused lifecycle/MQTT/Zigbee/health/security tests pass 57/57. The full
  backend run passes 105/108; only the same three Docker/Testcontainers tests
  fail to start. This has not been verified live, and the larger alert UX
  retrenchment remains open.

### Item 2 — Camera auth should inherit from Family Hub, single persistent OAuth

> "camera auth's should still be inherited from the parent account thats
> used as the atomic owner address for the orchestration hub... we should
> inherit, and by design that's the default, from Family Hub as part of
> the templates for both sides of the product, but we should allow — as
> we do — direct landing on the cabin.unicornpingpong.com and a single
> oauth only that persists for the cameras and refreshes using the auth
> method for the hub as longs as the session is active. no more pivoting,
> and the ux interaction model will be cleaner."

This directly connects to an **already-logged but unbuilt roadmap item**:
"App-wide Google OAuth gate + consistent landing page" (see
`docs/DEFINITION_OF_DONE.md`'s "Next Session — Open Items", second
bullet). Today auth only gates Camera Events/Opportunities panels, not
the app as a whole, and there's no single persistent session shared across
camera auth and the rest of the hub. This item should probably be
designed and built together with that existing roadmap item rather than
as a separate pass — check `ROADMAP.md`'s matching entry for whatever
scope notes already exist there before starting design.

### Item 3 — No UI to add additional "My Places"

**Resolved this session** — `AddPlaceForm` shipped in `6dbbadc` (see §2).
The user asked for this "earlier, but still don't see" it, so verify it
actually renders and works for them before considering this fully closed
— it has Vitest coverage but has **not been visually verified in a real
browser** (no browser tool was available this session — see §4).

### Item 4 — Places-based Rules & Alerts context, per-location Node-RED — DONE

> "places-based contexts for rules & alerts isn't set; I only see one
> node red and I know this is something we need to set up as a templated
> workflow at the time of implementing number 3 above, all can live on
> the 920q for now. same context shift behavior for all locations. now in
> devices, if I shift context to Home as a place, I still see devices
> online at Cabin, so this needs to be cleaned up. it's correct insofar
> as if I select both I should see all devices, but once I add a third
> location the 'both' reference will shift to 'All' so we should fix that
> now."

Both sub-items are done as of this session:
- **4a — Device Manager showing wrong-location devices when context is
  switched: FIXED** (`6dbbadc`, see §2). The "Both"→"All" relabeling the
  user asked for in the same message is also done (`allLocationsLabel()`).
- **4b — Per-location Node-RED / Rules & Alerts context: FIXED** (this
  session, one commit newer than the checkin-status work above — see
  `ROADMAP.md`'s Phase 7 newest entry and `docs/ontology.yaml`'s new
  `rules_panel_location_context`). `RulesPanel` now computes its location
  list from `activeLocation` (same pattern Monitoring's `MnSeeView`
  already used) and renders one `LocationRulesSection` per location in
  "Both" mode instead of a single hardcoded iframe. A location without
  its own configured Node-RED falls back to Cabin's with a visible hint
  saying so; Cabin itself is never flagged as a fallback. Per the user's
  own scoping ("all can live on the 920q for now"), this is deliberately
  a rendering-only fix — no second Node-RED instance was stood up. 3 new
  frontend tests, full suite 60/60, verified live in a browser preview
  across Cabin/Home/Both with no console errors.
  **Still genuinely open** (not this item's scope, but adjacent): actual
  per-location *flow content* — Home getting its own tailored automations
  once it has its own Node-RED instance — depends on Item 5 below.

### Item 5 — Home location physical setup

> "I'm happy to set up Home now to help work through some of the
> considerations, could be done through this machine (ilikethelights), a
> raspberry pi 2 with added storage via wifi or directly to my linksys
> router, or could dedicate an emulated OS through an android with
> termux (I have one ready for this), or use a dedicated laptop (ubuntu
> is ready and I can keep it awake). lets go!"

**Still not started, but the model is now decided — not just a hardware
menu anymore.** Same day, three-message arc: (1) user asked me to rank
hardware tiers for future spin-off instances; (2) corrected my first pass
— not a device to run the full stack, a lightweight **local hub that
collects device metrics and routes them to a central "main brain"**
(self-hosted M920q-class, or cloud); (3) **confirmed Home specifically
follows this model**: "I need something at home to be up all the time,
get devices online, and then to the 920q in my instance." So: **Home is
a collector hub routing into Cabin's M920q as the shared main brain —
NOT a full independent peer.** `locations/home/docker-compose.yml`'s
current "Full Stack" design (own Postgres/Kafka/backend/Grafana/HA/
Node-RED/Frigate) is **not** what should get deployed to Home. See
`ROADMAP.md`'s **Phase 8 — Accessible Hardware Program: Local Collector
Hubs**, "Home is the real pilot for this model" subsection, for the full
writeup.

**New requirement, same message**: the Home device should ideally also
drive a touchscreen kiosk display loading Family Hub. This is easy to
satisfy — `family-hub` is already a plain static-file container (nginx,
`family-hub/family-hub.html`) reachable over Tailscale from the M920q, so
the kiosk device just needs a browser pointed at that existing URL, not
its own hosting.

**Concrete recommendation, in `ROADMAP.md`'s Phase 8**: validate first,
cheaply — the user already has an Android device set up for Termux
("I have one ready for this"), zero acquisition cost, and Android's
kiosk-browser ecosystem (e.g. Fully Kiosk Browser) is more mature than
DIY Chromium-kiosk-mode. The one real unknown, unproven in this project:
whether Termux can reach a USB Zigbee coordinator over Android USB-OTG
without root. **Test that specifically before buying anything** — a full
step-by-step playbook now exists:
[`docs/POC_2026-08-08_termux-zigbee-collector.md`](POC_2026-08-08_termux-zigbee-collector.md)
(8 phases: ADB bridge from ilikethelights for visibility, Termux setup,
USB device detection, the actual Zigbee2MQTT test with an explicit
go/no-go signal, Tailscale reachability to the M920q, optional real-device
pairing, 24/7 reliability scaffolding, and a separate kiosk-display
check). If it fails, fall back to a Raspberry Pi 4 (not 3B+/Zero 2 W —
the kiosk requirement wants the extra headroom) with a touch monitor,
~$60–120 all-in — the Android device would still work fine as a
kiosk-only display in that case. **If Codex picks this item up before
the user has run the POC, do not assume the tests below**: it should
either wait for the user to report a result, or (if instructed to run
it) follow that playbook and report back honestly, including a clean
failure — this is real, unproven hardware behavior, not something to
simulate or assume passing.

**Coordinator recommendation, same day — changes the POC's risk
profile.** User asked which Zigbee coordinator to buy. Recommended:
**SMLIGHT SLZB-06 or SLZB-07, Ethernet variant** (plugs into the router
like any wired LAN device, own USB-C power, place for good Zigbee RF
coverage across the house — not necessarily wherever's closest to the
router) — a *network-attached* coordinator rather than USB, so
Zigbee2MQTT connects over plain TCP (`tcp://<ip>:6638`). This **sidesteps
the USB-OTG risk the POC playbook above was built to test** — a TCP
socket needs no special Android permissions, unlike raw USB serial
access. Run Zigbee2MQTT locally at Home (not centralized at Cabin) so
Zigbee control survives a Home↔Cabin network hiccup; only MQTT telemetry
needs to reach the M920q. Fallback if matching Cabin's own coordinator is
preferred: Sonoff Zigbee 3.0 USB Dongle Plus V2 (same as Cabin, `ember`
adapter, already proven in this repo). **The POC playbook hasn't been
rewritten for this yet** — no coordinator has actually been purchased as
of this writing. Once an SLZB unit is in hand with a known IP, Phases
2–4 of `docs/POC_2026-08-08_termux-zigbee-collector.md` should be
replaced with a much simpler plain-TCP-socket check rather than the
USB-OTG gauntlet — don't do that rewrite speculatively before the
hardware exists, the exact commands depend on the real device.

**Codex update, 2026-08-08:** the architecture prerequisite is now scoped in
[`docs/EXECUTION_PLAN_2026-08-08_collector-hubs.md`](EXECUTION_PLAN_2026-08-08_collector-hubs.md).
Runtime implementation has not started. It defines the edge/central boundary,
location-first MQTT, legacy Cabin compatibility, location-scoped commands,
one-central-API UI migration, ACLs, tests, and rollback.

A safety defect was found in the original POC before Phase 5: a second Z2M
instance using Cabin's default `zigbee2mqtt/#` could collide with retained
state and coordinator controls. Fork commit `b09b7a5` now requires a local
Phase 4 broker, isolated `poc/home/zigbee2mqtt/#`, and fresh approval before
Phase 5 touches the M920q. Hardware evidence is still pending.

---

### Item 6 — Armed state isn't a trigger; no native camera confidence UI

> User question, 2026-08-08, paraphrased: is the armed/disarmed icon
> active or a future trigger, and how can Frigate presence + door
> detection be watched even when "all" automations aren't armed? Is the
> Grafana link-out as far as the native front-end goes, or is
> Prometheus-sourced functional state with configurable confidence
> thresholds coming to the app itself?

**Investigated live this session, not assumed — findings, most important
first:**

- **`SecurityBadge` (App.jsx) is real but read-only.** No arm/disarm
  control exists anywhere in cabin-ui; the actual toggle is
  `input_boolean.cabin_security_armed_away` in Home Assistant. One global
  boolean — no zones, no granular arming.
- **Real bug, found this session, not previously known:**
  `MqttBridgeService.handleFrigateDetectionEvent()` hardcodes every
  Frigate detection to `"INFO"` severity — it never reaches
  `AlertSeverityClassifier`, so no Frigate detection can become WARN/
  CRITICAL or trigger `NtfyAlertPublisher` through this app's own event
  pipeline, regardless of content. Same bug class as the
  `Zigbee2MqttAdapter` fix from 2026-08-06 — that fix missed this call
  site. **This should probably be the first fix if this item is picked
  up** — it's a small, isolated, clearly-scoped bug fix, unlike the rest
  of this item which is real design work.
- `AlertSeverityClassifier.java` still explicitly doesn't consider armed/
  presence state (unchanged deliberate MVP scope cut).
- A **separate, working alert path already exists but is invisible from
  cabin-ui**: Node-RED's "Camera Overnight Alerts" flow (see
  `docs/MAINTENANCE.md`) already gates Frigate alert-tier detections on
  armed+presence and pushes real ntfy notifications (fixed/verified
  2026-08-07) — but it lives entirely in the embedded Node-RED editor,
  with no cabin-ui-native surface showing it's active.
- **Grafana link-out is genuinely still the ceiling** for anything beyond
  `CameraHealthPanel`'s one metric (`frigate_camera_fps`). Frigate's own
  detection `score` is already captured and shown per-event in Camera
  Events (`label (87%)`), but there's no live, confidence-threshold-
  configurable functional-state tile anywhere yet.

**Full scope, not yet an execution plan** — see `ROADMAP.md`'s new Phase
7 punch-list item for the five-part breakdown (fix the INFO-hardcoding
bug; wire armed+presence into the classifier; decouple presence/door
watching from the single global armed toggle so it can run even when
"not all" is armed, per the user's explicit ask; surface the Node-RED
overnight-alert logic as a real cabin-ui control instead of an embedded
editor tab; build the native confidence-threshold UI). Recommend doing
the INFO-hardcoding bug fix first (isolated, testable, low-risk) before
attempting the larger armed/presence-decoupling design work, which needs
real product thinking about what "watching without being armed" should
mean UX-wise, not just a wiring change.

---

## 4. Known unresolved issues (do not silently drop these)

- **`BLINK_PASSWORD` credential exposure — rotation status UNCONFIRMED.**
  Earlier in this project's history, a `docker inspect`-based env dump
  printed the live `BLINK_PASSWORD` value in plaintext into a session
  transcript. The user was told to rotate that Blink account's password.
  **No confirmation has been received that this rotation actually
  happened.** This is a live open action item — surface it to the user
  again if it comes up in this handoff period, and do not assume it's
  been handled. (The *procedural* fix — never printing `$PASS`, piping it
  through a shell variable instead — is already documented in
  `docs/MAINTENANCE.md`'s blinkbridge rebuild/redeploy section and does
  not need to be redone; it's specifically the *rotation of that one
  password* that's unconfirmed.)
- **`front_door` (Reolink) camera still physically off-network.** Needs
  on-site checking (power, WiFi re-pairing) — not fixable remotely by any
  session, Claude or Codex.
- **`CameraHealthPanel` (new, `6dbbadc`/`196b8e8`) has not been visually
  verified in a real browser** — this session had no browser/preview tool
  available. It has Vitest coverage for its pure logic
  (`cameraHealthLabel()`) but the actual kiosk-vs-mobile layout, metric
  selection, and visual density the user asked about ("Apple product
  levels of zero thought process," not wanting a scrollable embed) has
  not been confirmed against a real device. This is explicitly logged as
  a follow-up in `ROADMAP.md` and `docs/DEFINITION_OF_DONE.md`'s punch
  list already — if Codex has browser/preview tooling available, this is
  a good candidate to actually close out.
- **`AddPlaceForm` (new, `6dbbadc`) likewise not visually verified** —
  same caveat as above, same recommendation if tooling allows it.

---

## 5. Reconciling the fork back into Claude Code on 2026-08-14

When the user returns to Claude Code:

1. **Do not merge the fork into `ImpressiveLLC/FaceoftheCabin`'s `main`
   directly from Codex.** Push the fork's work to a branch (on the fork,
   or as a branch pushed to the canonical repo if the user grants push
   access) and open it as a reviewable diff — a GitHub PR is the natural
   mechanism, but the actual merge decision belongs to the user + Claude
   Code session on 8/14, not to an autonomous merge beforehand.
2. Bring **this file's §2 "Current state snapshot"** and **§3 "Open work
   items"** sections into that session unchanged as the starting context,
   updated with whatever Codex actually completed in the interim —
   i.e. Codex should keep this file itself current (git-tracked, same
   commit as any work it describes) rather than letting it go stale,
   exactly per this project's own "docs ship in the same commit as the
   change" discipline (§1e above).
3. Before Claude Code trusts any fork-side claim of "done," it should
   independently verify per `docs/DEFINITION_OF_DONE.md` — run the actual
   test suites, check `git log` against what's claimed, re-read
   `docs/ontology.yaml`/`ROADMAP.md` for drift — the same way any session
   in this project verifies its own work, not looser scrutiny just because
   the work originated elsewhere.
4. If the self-hosted-runner/fork gap (§1a) means nothing from the fork
   period actually got deployed to the M920q, that's expected and correct
   — deployment of fork-originated work is something to do deliberately
   once it's back in the canonical repo, not something to have attempted
   from the fork.
5. Explicitly re-confirm BLINK_PASSWORD rotation status (§4) — don't let
   that item silently vanish across the handoff boundary the way the
   `blinkbridge` architecture decision was once lost across a Claude-web
   session (see `CLAUDE.md`'s "Decisions made where Claude can't commit"
   section — this handoff document is this project's version of the same
   discipline, applied to a tool-switch instead of a machine-switch).

---

**This document should be updated in place (not superseded by a new
file) if Codex makes meaningful progress before 8/14** — keep §2 and §3
current rather than leaving them as a stale snapshot, per this project's
own documented practice of reconciling drift as soon as it's found rather
than at the end.
