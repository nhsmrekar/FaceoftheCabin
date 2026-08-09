# Cabin Orchestration Platform

**ImpressiveLLC / FaceoftheCabin**

A unified smart-home control surface for two physical locations — the Smrekar family cabin and home — managed from a single React UI backed by a Spring Boot hub on each site.

![Spring Boot 3.3.5](https://img.shields.io/badge/Spring%20Boot-3.3.5-6db33f?style=flat-square)
![Java 21](https://img.shields.io/badge/Java-21-007396?style=flat-square)
![React + Vite](https://img.shields.io/badge/React%20%2B%20Vite-18-61dafb?style=flat-square)

---

## Purpose

Replace fragmented per-device apps (Home Assistant, Zigbee2MQTT, Grafana, Node-RED) with a single opinionated surface. The operator should be able to see every device at both locations, understand their state, apply presence-aware alert rules, and control or configure anything — from one tab.

---

## Locations

| Hub | Site | Hardware |
|-----|------|----------|
| `cabin-hub` | Cumberland Cabin | Lenovo ThinkCentre M920q · Ubuntu 24.04 · Aeotec Z-Stick 10 Pro |
| `home-hub` | Primary residence | Same hardware class · Daikin HVAC · Emporia Vue energy · 5× Reolink cameras |

Both hubs are reachable from anywhere via **Tailscale MagicDNS** (`cabin-hub` / `home-hub`).

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                  React UI  (Vite, port 5175)                 │
│  Nav Rail · Toolbar · 5 Panels · Theme System · Drag-Order  │
└───────────────────┬─────────────────────────┬───────────────┘
                    │ REST /api/*              │ WebSocket
┌───────────────────▼─────────────────────────▼───────────────┐
│         Spring Boot 3.3.5 Backend  (port 8080)               │
│  DeviceRegistry · PresenceService · DeviceDisplayConfig      │
│  AutomationRuleService · DeviceHealthMonitor                 │
│  Zigbee2MqttAdapter  ·  HomeAssistantAdapter                 │
└──┬────────────┬─────────────┬────────────┬──────────────────┘
   │ MQTT       │ JDBC        │ Kafka      │ HA REST API
Mosquitto     TimescaleDB   KRaft        home-hub:8123
(port 1883)   (Postgres)    (port 9092)  cabin-hub:8123
   │
Zigbee2MQTT ──▶ Aeotec Z-Stick 10 Pro ──▶ Zigbee devices

Also in Docker stack: Grafana (3000) · Node-RED (1880) · Frigate (5000)
Two identical stacks: cabin-hub + home-hub, linked via Tailscale
```

**Why two independent hubs?** Each location runs its own Zigbee coordinator and local MQTT broker. Cabin-side logic survives internet outages. The UI routes API calls to the selected location; the backend routes HA commands to the correct hub by `DeviceDescriptor.location()`.

---

## Features

### Device Registry & Protocol Adapters

The durable `DeviceCatalogService` holds ontology identity and independent admission, configuration, and enablement states. `DeviceRegistry` contains only the operational projection (`DeviceDescriptor` plus runtime `DeviceStatus`) for explicitly bound devices whose catalog record is `ADMITTED`, `CONFORMING`, and `ENABLED`. Protocol adapters implement a `ProtocolAdapter` interface:

- **Zigbee2MqttAdapter** — subscribes to `zigbee2mqtt/#`, retains observations from `bridge/devices`, and infers candidate capabilities by recursively walking the `exposes[]` schema (handles composite features like THIRDREALITY Drip Detect). An observation remains inert until its IEEE identity is explicitly bound to a conforming, enabled catalog record; friendly names never allocate runtime state.
- **HomeAssistantAdapter** — uses the HA REST API, routing to `cabin-hub` or `home-hub` based on device location.

Thirteen historically observed Cabin Zigbee records and fourteen described Home prospects are seeded into the catalog as `AVAILABLE`, `READY_TO_CONFIGURE`, `DISABLED`, and unbound. No seed is a runtime device or has alert/command authority.

### Device Health Monitor

`@Scheduled` task runs every 60 s for operationally authorized devices only. Stale thresholds are adapter-type-aware: Zigbee (10 min), cameras (5 min), HA devices (15 min), others (30 min). A stale device first becomes `LATE`; only a failed grace-period/active-probe path becomes `MISSED` and flips runtime state to `OFFLINE`. `GET /api/system/health` returns aggregate counts, Zigbee bridge state, and stale devices; catalog-only, rejected, disabled, or nonconforming records have no liveness or alert state.

### Presence Profiles

System-wide occupancy status with four states: `AT_HOME`, `AT_CABIN`, `AWAY`, `BOTH_OCCUPIED`. Persisted to a single-row Postgres table via `PresenceService` (created on boot). Exposed via `GET/PUT /api/presence`. The UI toolbar shows a **MapPin dropdown** that reads and writes the active profile optimistically. All automation rules and display configs are evaluated against the active profile at runtime.

**Why profiles over schedules?** Rule context is "where is the family right now" rather than "what time is it". A single profile enum drives both alert severity overrides (display config) and automation rule behavior — this models actual household intent more directly than cron-style schedules.

### Device Display Config

Per-device overrides keyed by `(deviceId, location, presenceProfile)`:
- Custom display name
- `stateLabelMap` — e.g. `ONLINE → "Unlocked"`
- Severity override (`OK / WARN / ALERT`)

Stored in `device_display_config` Postgres table (JSON for label map, varchar for override). API: `GET/PATCH/DELETE /api/devices/{id}/display-config?profile=` and bulk `GET /api/devices/display-config?profile=` used by the UI at paint time. The same device renders differently depending on which profile is active.

### Presence-Aware Automation Rules

`AutomationRuleService` runs Java-side safety rules in parallel with Node-RED, so critical alerts survive a Node-RED outage. Built-in rules:

- Water pressure low/high (PSI thresholds configurable via `application.yml`)
- Freeze risk (< 38°F)
- Smoke alarm escalation
- **Presence-aware lock checks** — a lock unlocked while `AT_CABIN` or `AWAY` triggers `LOCK_UNLOCKED_WHILE_AWAY`; same event while `AT_HOME` is silently logged
- Motion events escalate to WARN when profile indicates the home should be empty

Complex automation logic stays in Node-RED flows; Java handles only safety-critical rules.

### Device Manager Panel (L1→L2→L3)

Four-view L1 nav:

- **See** — device list with health bar and detail pane
- **Change** — edit name and enabled state
- **Add** — Zigbee pairing flow with 254-second countdown + poll for new `z2m-` devices, or manual registration form
- **Remove** — confirmation flow with device preview

In **Reorder mode**, device rows become draggable (HTML5 DnD) with grip handles; ALARM/CRITICAL devices auto-pin to position 0 with a lock icon.

### Monitoring Panel (L1→L2→L3)

Same four-view structure. **See** renders KPI tiles in a CSS auto-fill grid with live MQTT WebSocket feed and embedded Grafana iframe; tiles apply the active profile's display config. **Change/Add** surfaces a `DisplayConfigForm` per device — custom name, severity override dropdown, and a state-label key→value editor. **Remove** lists all configs for the active profile with one-click delete.

In **Reorder mode**, the grid switches to a flat draggable list (`KpiListItem` rows), order persisted to `localStorage` keyed by `order.monitoring.{location}`.

### Nav Rail Alert System

Per-panel opt-in state machine persisted to localStorage (`cabin-alert-cfg`):

| State | Condition | Visual |
|-------|-----------|--------|
| `unconfigured` | Never enabled | No badge; Enable button shown inside panel |
| `watching` | Enabled, no issue | No badge |
| `warn` | Issue < 20 min | Orange dot on nav icon |
| `critical` | Issue ≥ 20 min | Pulsing red AlertTriangle; red left border on nav item |

Polling every 30 s via `/api/system/health`. Reset returns to `unconfigured` — nothing alerts until re-enabled.

**Why a 20-minute threshold?** Eliminates transient offline states (router reboots, Z2M restarts) from creating alert fatigue.

### Theme System

Six presets via `ThemeProvider`:

| Preset | Character |
|--------|-----------|
| **Modern** | GitHub-dark inspired |
| **LCARS** | Star Trek orange |
| **Monolith** | Near-black minimal |
| **Retro-CRT** | Phosphor green |
| **Bluefin-mono** | Navy monochrome |
| **Mad Science** | Neon green / UV purple on near-black |

Themes stamp CSS custom properties on `:root` and a `data-theme` attribute on `<html>`. Mad Science loads Share Tech Mono from Google Fonts dynamically; all other themes use system stacks.

### Drag-and-Drop Card Reorder

`useDraggableOrder(storageKey, items, isAlarm)` hook:
- Merges unknown new devices to the tail of saved order
- Auto-pins ALARM/CRITICAL to position 0 (settles after drop, not mid-gesture)
- Reorder mode toggle in panel header; exits on L1 tab change
- HTML5 DnD with `onDragStart/Over/Drop/End` — no library dependency
- Folded-corner `::after` triangle (accent color, bottom-right) appears on every card in reorder mode
- Dashed outline marks the active drop target

**Why native DnD?** No library dependency needed for list reordering. The grid-to-list switch in Monitoring reorder mode avoids the 2D insertion-point ambiguity of native DnD on CSS `auto-fill` grids.

---

## Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Backend runtime | Spring Boot 3.3.5 · Java 21 | Mature ecosystem, virtual threads available, strong MQTT + JDBC + scheduling support |
| Persistence | Spring Data JDBC · TimescaleDB (Postgres) | JDBC over JPA — simpler for a small schema with no lazy-loading needs. TimescaleDB for time-series without a separate TSDB |
| Messaging | Eclipse Paho MQTT · Kafka (KRaft) | MQTT for device state (low-latency, Z2M native). Kafka for event streaming; KRaft removes ZooKeeper dependency |
| UI runtime | React · Vite · No TypeScript | Fast HMR; no TS overhead for a single-developer project. lucide-react for icons |
| Automation | Node-RED (flows) + Java rules | Node-RED for complex visual rules. Java for safety-critical rules that must survive Node-RED downtime |
| Video | Frigate NVR · RTSP | Local ML object detection; no cloud dependency |
| Monitoring | Grafana | Embedded iframe in Monitoring panel; TimescaleDB as data source |
| Network | Tailscale MagicDNS | Zero-config mesh VPN; `cabin-hub` / `home-hub` resolve from anywhere without port-forwarding |
| Deployment | Docker Compose (6 services per hub) | Single `docker compose up` to start the full stack |

**Why no TypeScript, no ORM?** One-developer project. Spring Data JDBC with `JdbcTemplate` keeps the persistence layer transparent. Tables are created with `CREATE TABLE IF NOT EXISTS` in `@PostConstruct` — no migration tooling required at current schema size.

---

## Current Status

Active local development. Full Docker stack runs on the cabin-hub dev machine; Spring Boot backend and Vite UI are started manually in dev mode.

| Component | State |
|-----------|-------|
| Backend `:8080` | Running |
| UI `:5175` (Vite) | Running |
| TimescaleDB `:5432` | Running |
| MQTT `:1883` | Running |
| Kafka | Not running in dev (producer warns, non-fatal) |
| Frigate | Config pending |
| home-hub | Not yet deployed |

---

## Backlog (implied next steps)

- Notification service (email/SMS from `AutomationRuleService` alert events)
- `GET/PATCH /api/preferences/card-order` for multi-device order sync
- Node-RED rule CRUD API (unlocks Rules panel reorder)
- home-hub deployment and cross-hub presence sync
- Production Docker Compose with env-var secrets management

---

*Smrekar family · ImpressiveLLC · July 2026*
