# Execution Plan — 2026-08-08 Local Collector Hubs and Central Brain

> Planning deliverable only. No collector runtime, central ingestion change, or
> production deployment ships with this file. The Termux USB-Zigbee POC remains
> the hardware gate; this plan is independent of whether Home ultimately uses
> Termux or the Raspberry Pi 4 fallback.
>
> Extends `ROADMAP.md` Phase 8 and the Codex handoff Item 5. Review this plan
> before implementation begins.

---

## 0. Outcome and boundaries

Home becomes a **local collector hub** feeding one central M920q-class **main
brain**. It does not run a second Postgres, Kafka, Spring backend, Grafana, or
cabin-ui. The central brain owns durable state, APIs, UI, ontology lookup, and
cross-location analysis.

The collector profile is capability-based:

- Required for Home: Zigbee2MQTT, a local MQTT broker/bridge, and Tailscale.
- Optional later: local Home Assistant for LAN/mDNS integrations and local
  Frigate when privacy/bandwidth warrants edge video processing.
- Kiosk display is a client only. It loads the existing Family Hub URL.

This plan does not touch the live M920q until the user approves a specific
pilot step. It never re-enables disabled M920q services or attaches a fork
runner to production.

## 1. Current-state findings

1. `locations/home/docker-compose.yml` is a full independent stack, conflicting
   with the confirmed collector/central model.
2. `MqttBridgeService` connects to one broker and hardcodes Cabin device,
   camera, event, and system topics. Only presence/security are location-wide.
3. `Zigbee2MqttAdapter` hardcodes `zigbee2mqtt/`, one location, and unscoped
   pairing/command topics.
4. `DeviceRegistry` is keyed only by `deviceId`; duplicate friendly names can
   collide. One path guesses Home from a `home-` ID prefix instead of topics.
5. `POST /api/devices/permit-join` has no location.
6. cabin-ui fetches separate per-location APIs. A collector has no Spring API;
   the central model requires one central fetch and location filtering.
7. `hub_location` assumes full-stack URLs. A collector needs an explicit
   runtime role and optional capabilities.
8. Template Mosquitto permits anonymous access. Production collectors require
   per-location credentials and ACLs.

## 2. Canonical contracts

### 2.1 Runtime role

Add `hub_runtime_role`:

- `CENTRAL_BRAIN`: shared API, data, UI, ontology, and analysis.
- `COLLECTOR_HUB`: gathers local signals and forwards them centrally.
- `UNDEPLOYED`: modeled but has no running profile.

UI deployment checks use role/capabilities, not placeholder URLs.

### 2.2 MQTT namespace

Central production topics are location-first:

```text
{location}/zigbee2mqtt/#
{location}/device/{deviceId}/#
{location}/camera/#
{location}/event/#
{location}/system/#
{location}/presence/#
{location}/security/#
{location}/collector/#
```

Topic location is authoritative; names never determine location. Cabin's
existing `zigbee2mqtt/#` stays a documented legacy alias mapped to Cabin during
migration. New collectors never use it. Termux POC traffic stays under
`poc/home/zigbee2mqtt/#`, not the production namespace.

### 2.3 Identity and commands

Existing Cabin IDs such as `z2m-motion_entry` stay stable. New scoped IDs use
`z2m-{location}-{friendlyName}`. A pure resolver owns and tests this rule;
human labels remain friendly names.

Pairing and commands require location:

```json
{ "location": "home", "enable": true, "duration": 254 }
```

Missing location may default to Cabin for one compatibility release and must
emit a deprecation warning. Home commands can never publish to Cabin topics.

### 2.4 Collector health

Each collector publishes retained `{location}/collector/status` with ID,
profile, capabilities, version, start time, and bridge state. The backend
uses on-schedule/late/missed semantics; a timeout alone is not called offline.

## 3. Implementation slices

### Slice A — ontology and topology

- Add `runtimeRole`, `mqttNamespace`, and capabilities to `HubLocation` and
  `hub_locations` using additive columns. Default Cabin=`CENTRAL_BRAIN`,
  Home=`UNDEPLOYED` until real.
- Extend location CRUD and Add Place.
- Add Postgres migration/CRUD tests and frontend role tests.

### Slice B — location-aware MQTT and Zigbee control

- Separate Zigbee parsing from connection management.
- Consume Cabin legacy `zigbee2mqtt/#` and configured
  `{location}/zigbee2mqtt/#`.
- Subscribe platform traffic by location; flow topic-derived location through
  descriptors, status, events, signal quality, and check-in state.
- Route permit-join/commands through the matching location adapter.
- Prove duplicate friendly names do not collide and Home commands never reach
  Cabin.

### Slice C — collector-only artifacts

Create reusable `locations/collector/`; do not turn the old Home full-stack
file into a misleading hybrid.

- Termux: Zigbee2MQTT + Mosquitto, Termux:Boot, wake lock, status heartbeat.
- Pi/Ubuntu: collector-only Compose using the same semantic inputs.
- Z2M uses local `zigbee2mqtt/#`; Mosquitto bridges it bidirectionally to the
  central `{location}/` prefix with QoS 1, persistence, unique IDs, and bridge
  notifications.
- Validate loop-free remapping against Mosquitto's documented
  `topic ... local-prefix remote-prefix` behavior.

### Slice D — one central API and UI

- Fetch devices once from central API, then filter by location.
- Distinguish central, connected collector, disconnected collector, undeployed.
- Render only links backed by capabilities; no fake API/Grafana/Node-RED URLs.
- Use central MQTT WebSocket and filter by namespace.

### Slice E — security, resilience, cloud portability

- Per-location broker credentials/ACLs: Home publishes Home telemetry/status
  and receives Home commands only.
- Keep MQTT private to Tailscale/LAN; never expose 1883 via Cloudflare.
- Keep secrets out of Git; verify presence/hash only.
- Test reconnect, queued QoS 1, retained state, and ACL isolation.
- Document a cloud central-brain target using the same contracts.

### Slice F — Home pilot

Only after fork review/merge and explicit production approval:

1. Record real Termux phone/Android/coordinator/error or pass evidence.
2. Provision `collector-home` identity/ACL without displaying its secret.
3. Start Home on production `home/` namespace.
4. Observe status/topics without pairing a safety device.
5. Pair one spare device; verify one Home device through central API/UI.
6. Test a location-scoped command; close permit-join immediately.
7. Test Tailscale disconnect/reconnect and queued telemetry recovery.
8. Retire old Home full-stack instructions only after replacement works.

## 4. Test and acceptance matrix

- Backend unit: namespace parsing, identity compatibility, command routing,
  collector status tiering.
- Backend integration: additive location migration; two Mosquitto containers
  proving remapping, retained state, ACL isolation, reconnect delivery.
- Frontend: one central fetch, N-location filtering, role/status labels,
  capability links.
- Family Hub: kiosk/link behavior unchanged.
- Config: Zigbee2MQTT schema and `mosquitto -c <file> -t`.
- Safety: no Home traffic under Cabin's unscoped tree; Home permit-join cannot
  open Cabin; duplicate names stay distinct; no secret appears in logs/diffs.

Fork evidence names suites actually run. Missing Docker means Testcontainers is
blocked, never passed.

## 5. Rollback

Stop Home collector/bridge, revoke its credential, and leave Cabin's legacy
topics/containers unchanged. Additive columns may remain unused; no database
downgrade. Never use force-push, branch deletion, or compose cleanup.

## 6. User decision gates

- Termux pass/fail vs. Pi 4.
- Whether first pilot needs local HA/mDNS or Zigbee only. USB does not prove HA
  or camera processing viability on Android.
- Whether Frigate later stays edge-local.
- Approval for production access, credentials, and spare pilot device.

## 7. Primary references

- https://www.zigbee2mqtt.io/guide/configuration/mqtt.html
- https://www.zigbee2mqtt.io/guide/faq/
- https://mosquitto.org/man/mosquitto-conf-5.html