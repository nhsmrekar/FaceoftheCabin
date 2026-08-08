# Smrekar Platform — Strategic Roadmap & Architecture Brief

> **Prepared:** 2026-07-27  
> **Review checkpoint:** 2026-08-27 _(30 days — reassess phase status, update discovery flags, verify unicornpingpong.com tunnel)_  
> **Audience:** Claude Code handoff · CIO · Product Lead · Data Architect · Ontology Lead  
> **Source:** Architecture Brief v1.0 · unicornpingpong.com · Multi-role review completed 2026-07-27

> **2026-08-08 — fork handoff in effect.** The user is working from a git
> fork with a different AI tool ("Codex") until Claude Code resumes
> 2026-08-14. See [`docs/HANDOFF_2026-08-08_codex-fork.md`](docs/HANDOFF_2026-08-08_codex-fork.md)
> for the full operating charter (check-down doc order, fork/CI/git
> guardrails, current-state snapshot, and the 5 open work items from the
> user's last request). Remove this note once reconciled back into `main`.

---

## Northstar Goals

1. Unified family experience across cabin display, home machine, and phone via single domain (`unicornpingpong.com`)
2. All elements and attributes must have a rationale supporting user-centric design principles: **See → Think → Act**
3. Living ontology as the platform's primary differentiator — every data point is findable, traceable, actionable across all platform verticals and horizontal architecture and data derivatives
4. FAIR data principles throughout: Findable, Accessible, Interoperable, Reusable — enabling AI/RAG/LLM at any layer
5. Event-driven architecture: camera → detection → normalized event → automation → action, with full audit trail
6. Self-improving discovery: the platform actively monitors for new integrations based on ontology definitions for any non-deprecated device or service; scheduled rechecks — monthly for active devices — against industry-standard vendor sources and DIY/pro community pages, surfacing "next best idea" opportunities per component (upgrade paths, better-utilized configurations, new comparable products) rather than just flagging new purchases. See [The Tech ID Service](#the-tech-id-service) — fully specified, not yet built.
7. Radical device flexibility: no device, protocol, or vendor is a hard architectural assumption — every integration goes through the same ontology-first pattern (register the entity, document what's blocking full integration, verify against real installed source, only mark complete after a live test). See `docs/REPLICATION.md`'s device onboarding section for the concrete, repeatable process, evidenced by this project's own Zigbee/Frigate/HA-integration/appliance work.

---

## Product Architecture

### 3.1 Family Hub — `hub.unicornpingpong.com`

- Ambient full-screen display: clock, parenting schedule, Google Calendar events, Google Photos slideshow
- Kids' chore tracking with per-child reward progress (Sam age 9, Emma age 6)
- Parenting schedule logic: versioned rules (see `docs/ontology.yaml`'s
  `parenting_schedule_rule_version`) — March 13 2026 original anchor,
  14-day cycle, kids-home-days `[0,1,4,5,8,9,12,13]`, **superseded July 27
  2026** by a 50/50 split (`[2,3,4,5,9,10]` = Wed/Thu/Fri/Sat week A,
  Wed/Thu week B; Rachel has Mon/Tue always, Sun alternates). Historical
  dates always compute under whichever version was actually in effect at
  the time, not today's rule applied retroactively. Holidays override
  per-date via `holiday_override`. See the Environment & Credentials
  Reference table below for the full current detail.
- Admin tab: edit rewards, chore list, reward rules (min chores/night, qualifying nights/week) — no code required
- Google OAuth via GSI token client — owner: `nhsmrekar@gmail.com`, calendar/photos: `smrekarfamilia@gmail.com`
- **Known resolved:** overlay z-index stacking context bug — backdrop-filter on `#dashboard-overlay` compositing conflict. Fix: settings-btn/panel moved to end of body (DOM paint order fix)
- **Pending:** PWA manifest for phone install; offline clock/chores fallback

### 3.2 FaceOfTheCabin — `cabin.unicornpingpong.com`

Conceptually unified cabin intelligence service, technically decomposed:

| Container | Role |
|-----------|------|
| `faceofthecabin-api` | REST + WebSocket gateway |
| `faceofthecabin-automation` | Rules engine, YAML ontology consumer |
| `faceofthecabin-camera-adapter` | Normalizes Frigate detection events to platform event schema |
| `frigate` | AI object detection on camera streams |
| `mediamtx` | RTSP stream management |
| `mosquitto` | MQTT broker / event bus |
| `node-red` | Flow automation and integration glue |
| `home-assistant` | Device state, UI overrides, appliance integrations |
| `zigbee2mqtt` | Zigbee sensor ingestion (14 devices paired) |
| `sensor-adapters` | Bosch dishwasher, LG ThinQ washer, Liebherr fridge |

### 3.3 Shared Platform Services — `api.unicornpingpong.com`

Spring Boot backend + React frontend (lives in `FaceoftheCabin` — `cabin-backend` + `cabin-orchestration-platform/ui`):

- Identity and roles (Google OAuth, `nhsmrekar@gmail.com`)
- YAML ontology — canonical entity definitions, naming contract, discovery flags
- Event bus — MQTT / Mosquitto, normalized platform event schema
- Notifications — push to dad's phone, alert routing
- Audit/history — event log, clip index, state change trail
- API gateway — single entry point for all platform services
- Common UI shell — DeviceManagerPanel, RulesEnginePanel (reductive status-first UI), MonitoringPanel

### 3.4 Infrastructure

| Component | Detail |
|-----------|--------|
| Domains | `unicornpingpong.com` — platform (hub · cabin · api subdomains) |
| | `impressive.llc` — org domain, aligns with ImpressiveLLC GitHub org |
| DNS | Cloudflare free tier — nameservers from Porkbun |
| Public access | Cloudflare Tunnel (`cloudflared` container on M920q) — bypasses Starlink CGNAT |
| Private mesh | Tailscale — cabin ↔ home, admin/SSH. M920q stable IP: `100.77.44.113` |
| Git | `nhsmrekar` + `smrekarfamilia-sudo` GitHub accounts, `ImpressiveLLC` org for shared repos |
| Monitoring | Uptime Kuma + Homepage on M920q |
| Primary server | Lenovo M920q · Ubuntu · Docker Compose at `/storage/containers/compose/cabin/` |

---

## Event Pipeline — Camera → Action

```
Camera (Reolink PoE, RTSP via MediaMTX)
  ↓
Frigate / detection service  →  raw detection event
  ↓
faceofthecabin-camera-adapter  →  normalized platform event
  ↓
MQTT event bus  (topic: platform/events/presence.detected)
  ↓
faceofthecabin-automation  →  evaluates rules against event
  ↓
Actions: notify / retain_clip / update_state / alert / dashboard update
```

**Design principle:** Frigate owns detection. FaceOfTheCabin owns what to do about it. The normalized event is the handshake. Keep capability together, separate the containers.

### Canonical Event Schema

```yaml
event:
  type: presence.detected
  source: camera.driveway
  location: cabin.exterior.driveway
  timestamp: 2026-07-27T14:22:00Z
  attributes:
    object_type: person
    confidence: 0.91
    direction: approaching
```

### Canonical Automation Schema

```yaml
automation:
  id: driveway_arrival
  trigger:
    event_type: presence.detected
    source: camera.driveway
  conditions:
    - attribute: object_type
      equals: person
    - state: cabin.occupancy
      equals: vacant
  actions:
    - notify: dad
    - retain_clip:
        duration_before: 20s
        duration_after: 60s
    - update_state:
        entity: cabin.security
        value: attention_required
```

---

## Ontology — The Special Sauce

> **Northstar:** The ontology is the platform's primary differentiator. Every data point — primitive, derived, conceptual, or searched — must be findable, traceable, and actionable from anywhere in the platform by any user, service, or AI agent.

The ontology is not a schema registry. It is a **living, self-extending knowledge graph** that answers four questions for every entity:

1. **What is this?** (definition, type, lineage, origin)
2. **Where does it come from and where does it go?** (provenance, lineage, upstream/downstream trace)
3. **What can I do with it?** (available actions, integrations, automation hooks)
4. **What workarounds exist?** (DIY hacks, Reddit tricks, custom integrations, OCR workarounds, audio detection fallbacks — non-standard integration paths)
5. **What update schedule keeps this current?** (discovery gap checks, deprecation watches, new context schedules per entity)
6. **What might be possible that I don't know about yet?** (discovery flags, new service checks, alternative paths)

### Entity Types

| Type | Examples |
|------|---------|
| Primitive data elements | `temp_mech_room`, `camera.driveway.frame` |
| Derived elements | `cabin.occupancy` (derived from `door_front_contact` + `motion_entry`) |
| Conceptual attributes | `cabin.security.status` (from camera + motion + door sensors) |
| Devices | Reolink RLC-820A, SONOFF SNZB-05P |
| Services | Frigate, Google Calendar, Kidde cloud API |
| Automations | First-class ontology entities with trigger/condition/action graph |
| Events | Typed, versioned entities with schema and lineage |
| Locations | `cabin.exterior.driveway`, `cabin.interior.mechanical_room` |
| **Candidates** | Discovered-but-not-yet-integrated devices (e.g. Kidde CO alarm before purchase) |

### Canonical Ontology Entry Schema

```yaml
ontology_entry:
  id: camera.driveway                      # snake_case canonical ID
  type: device.camera
  label: "Driveway Camera"
  description: "Reolink RLC-820A PoE, exterior driveway"
  location: cabin.exterior.driveway
  status: active                           # active | deprecated | candidate
  data_class: raw                          # raw | derived | conceptual | composite
  version: "1.0"
  changelog:
    - date: 2026-07-01
      change: "Initial registration"

  # Lineage (DAG reference structure)
  produces:
    - event: presence.detected
    - stream: rtsp.driveway
  consumed_by:
    - service: frigate
    - automation: driveway_arrival
  derived_from: []                         # raw device, no upstream
  # DAG format when applicable:
  # derived_from:
  #   - entity_id: motion_entry
  #     relationship_type: triggers
  #     transformation: presence_inference

  # Relationship vocabulary
  # replaces | extends | complements | monitors | controls | notifies
  # derives_from | triggers | depends_on
  relationships:
    - type: complements
      entity: frigate
    - type: depends_on
      entity: mediamtx

  # Integration state
  integration:
    protocol: rtsp
    adapter: faceofthecabin-camera-adapter
    poe_switch: tp-link-sg605p.port_1
    api_available: false
    local_only: true

  # Discovery flags
  discovery:
    check_for_new: true
    check_schedule: monthly              # weekly | monthly | quarterly | annually
    last_checked: 2026-07-01
    new_alternatives: false
    other_options: false
    new_api_available: false
    deprecated: false
    notes: "Watch for Reolink HomeKit or ONVIF profile expansion"

  # FAIR metadata
  fair:
    findable:
      tags: [camera, security, exterior, reolink, poe, presence]
      search_aliases: [driveway, front camera, outside camera]
    accessible:
      roles: [admin, parent]
      ui_surfaces: [cabin.dashboard, family.hub, ha.lovelace]
    interoperable:
      protocols: [rtsp, mqtt, frigate-api]
      schema_version: "1.0"
    reusable:
      reuse_count: computed               # derived from automation/service reference graph
      shareable: false
```

### Discovery Flag Schedule by Entity Type

| Entity Status | check_schedule |
|--------------|----------------|
| `active` devices | monthly |
| `candidate` entities | weekly (actively being evaluated) |
| services/APIs | quarterly |
| `deprecated` entities | never |

### The Tech ID Service

> **Status: fully specified, not yet built.** Everything in this section
> is a design, not a running capability — Phase 4 below has zero
> completed items. Flagged explicitly here because the prose below reads
> confidently enough that it's easy to mistake for a shipped feature; it
> isn't one yet. This is the platform's most concrete answer to "AI-driven
> tech opportunity updates" — the user's own framing, 2026-08-03: checking
> "industry standard and DIY pro pages" on a schedule for "next best
> ideas" per component, so every cataloged device/service gets evaluated
> for whether it's being under-utilized or has a better integration path
> available now than when it was first registered.

A background platform service that:

- Maintains a registry of all ontology entities with `check_for_new: true`
- Runs scheduled checks per entity schedule against: official vendor
  API/changelog pages, community integration hubs (HACS, Home Assistant's
  own integration release notes), and DIY/pro forums and build logs
  (r/homeassistant, r/homeautomation, vendor-specific communities) —
  "industry standard and DIY pro pages," in the user's own framing —
  looking specifically for whether an owned device/service could be
  better utilized than its current configuration, not just whether a
  newer product exists
- Updates discovery flags in the ontology when new information is found
- Publishes `ontology.entity.updated` platform event when flags change
- Surfaces discoveries as actionable notifications: _"Reolink released a new webhook API — 2 of your devices may benefit"_
- Grows the ontology by identifying candidate entities from browsing context, device purchases, and integration logs

**Ontology growth events:** `ontology.entity.created` · `ontology.entity.updated` · `ontology.entity.deprecated`
**Ontology is append-only for history** — deprecated entities are flagged, never deleted.

### Tech ID Service — Provider Model

> **Status: the ingestion API is real and live** (`POST`/`GET`/`PATCH
> /api/tech-id/findings`, `com.cabin.orchestrator.techid` package,
> `tech_id_finding` Postgres table). The scheduled-scan *provider* side —
> a routine that actually calls out to an AI/research service and POSTs
> results here — is not yet wired up for any provider. Everything below
> this line describes the model this API was built to support.

The scanning work itself (deciding what's new, reading a vendor
changelog, judging whether a DIY forum post describes something real)
was never meant to live inside `cabin-backend` — that would hard-wire
one AI vendor's account into every deployment of this platform, which
directly contradicts the "replicable by other, unrelated instance
owners" goal (`docs/REPLICATION.md`). Instead the platform defines one
normalized **findings contract** any provider can fill in, and stays
agnostic about who did the research:

```
TechIdFinding { entityId, provider, findingType, summary, confidence, sources[], status, checkedAt, createdAt }
```

`provider` is free text, not an enum — accepting insights "from
anywhere possible" (the point of the finding record's design) means the
platform never needs a code change to recognize a new source. Three
providers are expected to exist side by side:

1. **The reference Claude Code routine** — this repo's own scheduled
   scan (see "Scheduled kickoff" below), included at no extra cost as
   the default/reference implementation. Anyone standing up a fresh
   instance from this template gets this for free.
2. **An operator-run, higher-tier scanning service** — the commercial
   path: an instance owner who wants deeper or more frequent coverage
   than the free reference routine pays the platform operator for a
   richer scan (more source categories, tighter schedule, human-curated
   query tuning) that still just POSTs `TechIdFinding` rows to their own
   instance's `/api/tech-id/findings`. From the API's point of view this
   is indistinguishable from provider 1 or 3 — it's a business-model
   choice, not an architectural one.
3. **Bring-your-own-AI** — an instance owner who'd rather run their own
   scan (a cheaper, volume-priced model instead of Claude, an in-house
   script, a different vendor's research API entirely) points that
   pipeline at their own instance's endpoint instead. The platform's job
   stops at *ingesting and adjudicating* findings, not at mandating who
   produces them.

**Submission (any provider):** `POST /api/tech-id/findings` with header
`X-Tech-Id-Api-Key: <cabin.techid.apiKey>`. One shared secret per
instance gates *whether* a caller may submit at all; `provider` is a
self-reported label, not a checked identity — there's no per-provider
credential registry, by design, since that would recreate the
single-vendor lock-in this model exists to avoid. Unset by default
(returns `503`) — an operator must opt in.

**Reading:** `GET /api/tech-id/findings?entityId=&limit=` is open,
matching the existing `/api/events`/`/api/devices` precedent — findings
aren't more sensitive than event/device data.

**Adjudication:** `PATCH /api/tech-id/findings/{id}` (`status`: `new` →
`reviewed`/`actioned`/`dismissed`) requires a signed-in human via
`GoogleAuthInterceptor` — this is the "decision making" step a machine
provider hands off to a person. A finding landing in the table is a
*claim*, not an automatic action; nothing here writes back into
`docs/ontology.yaml`'s `discovery:` fields automatically. Reconciling an
adjudicated finding into the versioned ontology (today: a human editing
`ontology.yaml` directly, as done for `nvr_frigate` and the two camera
entities this session) is a deliberate, separate, slower step — fast/
live findings vs. slow/versioned ontology history, on purpose.

**Scheduled kickoff, as its own trigger type:** the reference routine
runs on a recurring cron schedule (a `RemoteTrigger` cloud routine, or
any equivalent scheduler an instance operator prefers), not on a git
push, PR, or CI event — "kickoff" here means *time-based*, decoupled
from whether any code changed. This matters because the platform's
existing automation triggers (`docs/ontology.yaml`'s event-driven
automations, GitHub Actions in `.github/workflows/`) are all git- or
sensor-driven; a monthly tech-opportunity scan needed a genuinely new
trigger *category* — see `docs/REPLICATION.md`'s Tech ID Service setup
section for how an instance operator configures one.

### The Kidde CO Alarm — Canonical Discovery Use Case

> _This is the platform's canonical demo scenario for the Tech ID Service._

1. User browses kidde.com — browser extension or tab context captures product interest signal
2. Tech ID Service queries ontology: finds 2 Kidde devices already registered
3. Service checks Kidde's current API surface, developer portal, IFTTT/webhook listings — finds new cloud API released Q1 2026
4. Ontology queried for integration paths: official API (new, available), local Zigbee (no native support), camera OCR (viable), audio trigger (viable)
5. Platform generates structured response: _"Here are your options — ranked by integration quality and your existing ecosystem"_
6. _"You already have 2 Kidde devices. A new Kidde API is available that could unify all 3. Alternatively, a camera pointed at its display can read CO levels. Your existing Frigate setup already supports both camera OCR and audio classification — no new hardware needed for fallback paths."_
7. Discovery result written back to ontology: `new_api_available: true`, `other_options: true`, complementary devices listed

### See · Think · Act — User Interaction Model

Every data point must support this three-state interaction from any UI surface:

**See**
- Any primitive, derived, or conceptual element is visible with current value, source, and last-updated timestamp
- Lineage is one click away: "where did this come from?"
- Usage is one click away: "what uses this?"

**Think**
- Every entity shows its discovery status: `check_for_new`, `last_checked`, `new_alternatives`, `other_options`
- AI/RAG layer answers natural language: _"what else can I do with cabin.security.status?"_
- Cross-entity reasoning: _"these 3 sensors could combine into cabin.water_risk_level"_

**Act**
- Every entity has contextual actions based on its type and current state
- Actions are ontology-driven — not hardcoded in UI, derived from the entity's action surface
- New discovery actions: "Investigate Kidde API", "Add as candidate integration", "Schedule for next Tech ID check"
- Admin actions are separate from family-facing actions — roles enforced at the ontology layer
- `check_for_new` schedule is user-configurable per entity from Admin UI — not YAML-only

### FAIR Principles Applied

| Principle | Implementation |
|-----------|---------------|
| **Findable** | Canonical snake_case ID, human label, tags, `search_aliases`. Full-text search returns entity + action surface |
| **Accessible** | Roles per entity (`admin`, `parent`, `child`). UI surfaces listed. API via platform gateway. No orphaned data |
| **Interoperable** | Protocols declared per entity. Schema versioned. All adapters normalize to same event schema |
| **Reusable** | `reuse_count` computed from automation graph. Derived entities reference source lineage. Automations parameterized by entity ID |

### AI/RAG Readiness

- Each entity is a self-describing document — ideal for embedding and retrieval
- Event log is a structured time-series corpus — LLM can answer _"what happened at the cabin last Tuesday night?"_
- Discovery flag updates are events — RAG can answer _"what has the Tech ID Service found recently?"_
- Natural language automation authoring: _"notify me when someone approaches the cabin after dark when we're away"_ → ontology resolves entities, generates YAML automation draft
- **Retrieval strategy decision (pending):** start with YAML + embedding, migrate to graph DB (e.g. Neo4j) when horizontal trace queries become complex

### Ontology Root Structure

```yaml
ontology_version: "1.0"          # Add this — migration tooling needs a version anchor
# reuse_count: computed           # Never manually set — derived from automation/service ref graph
# derived_from: DAG structure     # { entity_id, relationship_type, transformation }
```

---

## Priority Task List

> Ordered by dependency and impact.

### Phase 1 — Foundation

- [x] Fix Family Hub overlay z-index bug
- [x] All platform code unified in `FaceoftheCabin` (`smrekar-platform` deprecated)
- [x] Cross-device backend on `cabin-backend` (Postgres-backed, matches the existing
      `api.unicornpingpong.com` shared-services scope in §3.3). **Done 2026-08-01.**
      Poll-based delivery (20s `setInterval`, matches the file's existing pattern), gated
      by `GoogleAuthInterceptor` (valid Google access token required — first auth-gated,
      first write, endpoints on this backend). Verified against a real local Postgres +
      running backend, two isolated browser contexts standing in for two devices, both
      directions, zero errors. Note the scope actually shipped: the raw per-chore
      completion record syncs now; the `chore_daily_success`/`chore_weekly_success`
      *derived* threshold flags (3+/day, 4+/week) are still computed live client-side
      from that synced data, not separately persisted server-side — see those entities'
      notes in `docs/ontology.yaml` for why that's a deliberate, smaller remaining gap,
      not an oversight. `family_profile` itself also still isn't synced (localStorage/
      per-device) — degrades gracefully (see `family_note`'s ontology notes), not fixed
      here.
- [ ] **[BLOCKER]** Push current M920q `docker-compose.yml` to CabinAutomations repo (currently local-only at `/storage/containers/compose/cabin/`)
- [x] **[FOUND 2026-07-30, FIXED 2026-08-02]** `cabin-postgres` was running on
      the fallback dev password (`cabin_dev_password`), a literal string in the
      public repo. Fixed live against the running container: `ALTER USER cabin
      WITH PASSWORD '...'` (Postgres only applies `POSTGRES_PASSWORD` at first
      volume init, so an env-var change alone would've done nothing), then
      `.env` updated to match so a future recreate doesn't drift back. Verified
      the *real* auth path, not the deceptive one — `docker exec ... -h
      localhost` hits `pg_hba.conf`'s `trust` rule for loopback and succeeds
      with any password, old or new; the actual dependent services
      (`cabin-backend`, `cabin-grafana`) connect over the Docker network alias
      and correctly hit the `scram-sha-256` rule. Confirmed both reconnected
      successfully with the new password (`cabin-backend`'s `/actuator/health`
      showed `db: UP`, Grafana's `CabinDB` datasource health check returned
      `Database Connection OK`) before considering this closed.
      **Bonus fix found in the same pass:** `cabin-grafana` never actually
      received `POSTGRES_PASSWORD` as an env var, despite its own provisioning
      YAML referencing `${POSTGRES_PASSWORD}` — Grafana only expands
      provisioning-file env vars present in its own container, not anywhere
      else in the compose file, so the `CabinDB` datasource had likely never
      authenticated correctly. Added the missing env var alongside the
      password rotation.
- [x] Register `unicornpingpong.com` at Porkbun, add to Cloudflare free tier —
      confirmed live: all three subdomains resolve and serve real responses.
- [x] Add `cloudflared` container to M920q docker-compose, configure tunnel to
      `hub/cabin/api` subdomains — confirmed 2026-08-01 via direct `curl`:
      `hub.` serves family-hub.html, `cabin.` serves cabin-ui (200), `api.`
      `/actuator/health` reports `{"status":"UP"}`.
- [x] Update Google OAuth authorized origin from `http://127.0.0.1:5500` to
      `https://hub.unicornpingpong.com` — done live during an earlier session's
      debugging (root-caused a "doesn't comply with OAuth 2.0 policy" error to
      the bare apex domain being authorized instead of the exact `hub.` origin).
- [ ] Family Hub PWA manifest — installable on phone, offline clock/chores fallback

### Phase 1.5 — Location Context & Vocabulary Feedback Loop

- [ ] Build `location_vocabulary_term` table in Postgres (id, value, applies_to, parent_room_types, status, usage_count, source)
- [ ] Seed master terms: common room names + zone/qualifier lists per room type
- [ ] Build `candidate_term_submission` table + API endpoint (POST /ontology/vocabulary/candidates)
- [ ] Wire soft-enum dropdowns in device setup UI: dropdown + "Other" → free text → auto-POST candidate on save
- [ ] Build admin review queue UI: pending candidates ranked by submission_count, promote/reject actions
- [ ] Build `device_location_formatted` derivation service: L1+L2 → snake_case / camelCase / PascalCase / label variants
- [ ] Wire formatted output into Z2M friendly_name seed and fotc_device_id generation
- [ ] NLP dedup check on candidate submission (embedding similarity vs existing master terms)
- [ ] Auto-promotion rule: submission_count >= N (threshold TBD) triggers auto-promote candidate → master

### Phase 2 — Ontology Foundation

- [ ] Create `/ontology` directory in CabinAutomations repo
- [ ] Create `ontology.yaml` root with `ontology_version: "1.0"` and entity schema (see Section above)
- [ ] Seed with all 14 paired Zigbee devices, 3 candidate Reolink cameras, and key platform services
- [ ] Add relationship vocabulary: `replaces`, `extends`, `complements`, `monitors`, `controls`, `notifies`, `derives_from`, `triggers`, `depends_on`
- [ ] Define `candidate` entity type for discovered-but-not-yet-integrated devices
- [ ] Wire `derived_from` as DAG reference structure: `{ entity_id, relationship_type, transformation }`
- [ ] Add `data_class` field: `raw | derived | conceptual | composite`
- [ ] **[FOUND 2026-08-02]** `platform_secret` entity added to `docs/ontology.yaml`
      (infra layer) — the definition-first model for every credential the
      platform depends on (POSTGRES_PASSWORD, GRAFANA_PASSWORD, HA_TOKEN,
      GOOGLE_CLIENT_ID, GitHub runner registration token). Documents current
      state honestly (entirely manual — ad hoc generation, plain `.env`
      storage, hand-run rotation) and names the target: an Ansible Vault (or
      equivalent) role that generates/stores/templates these values, so
      rotation is "run the role" instead of SSHing in by hand — see the
      `cabin-postgres` rotation (2026-08-02) for what the manual version
      costs. **Built and verified live, 2026-08-02** (same day, later
      session): `ansible/roles/secrets` + `ansible/playbooks/rotate-secrets.yml`
      — a real end-to-end rotation ran against the M920q (generate → `ALTER
      USER` → re-encrypt vault → re-template `.env` → restart dependents →
      validate `db: UP`), see `ansible/README.md`'s Secrets section for the
      exact commands and the two real bugs found running it for the first
      time. `GRAFANA_PASSWORD`/`HA_TOKEN` rotation remains manual — different
      mechanics needed (Grafana admin API, HA's own token UI), documented as
      a follow-up, not silently skipped.

### Phase 3 — Event Pipeline

- [ ] Formalize Mosquitto topic structure: `platform/events/{event_type}/{source}`
- [ ] Build `faceofthecabin-camera-adapter`: consume Frigate MQTT events, publish normalized platform events
- [ ] Build `faceofthecabin-automation`: consume platform events, evaluate YAML automation rules, publish actions
- [ ] Implement lineage record on every state-change: `{ from_event, via_automation, to_state, timestamp }`

### Phase 4 — Tech ID Service

> **Ingestion API built; reference routine live, PR-based.** See "Tech
> ID Service — Provider Model" above for what's real vs. designed. The
> `TechIdFinding` record, `tech_id_finding` table, and `POST`/`GET`/
> `PATCH /api/tech-id/findings` endpoints exist and compile/run today.
> The reference Claude Code routine (`RemoteTrigger` id
> `trig_0188XfA9eewXVEoumKr7tmkC`, monthly, `0 8 1 * *` UTC) is created
> and enabled — it researches `check_for_new: true` entities and opens
> a PR against `docs/ontology.yaml`'s `discovery:` fields directly,
> **not** by calling `POST /api/tech-id/findings` yet: the routine's
> cloud sandbox has repo access but no path to the Ansible-vaulted
> `TECH_ID_API_KEY`, so hardcoding a real secret into a stored,
> remotely-visible trigger definition was rejected as unsafe. The
> prompt does POST opportunistically if `TECH_ID_API_KEY` happens to be
> present as an env var in its sandbox (it isn't, today) — see
> `docs/MAINTENANCE.md`'s Known Issues for the resolved GitHub-access
> story and the still-open secret-injection gap. This is the platform's
> concrete implementation of Northstar Goal #6 (self-improving
> discovery / "next best idea" scanning).

- [x] Define provider-agnostic findings schema (`TechIdFinding`)
- [x] Build findings ingestion API (`POST`/`GET`/`PATCH /api/tech-id/findings`, shared-secret-gated submission, Google-auth-gated adjudication)
- [x] Wire up the reference Claude Code routine as an actual `RemoteTrigger` scheduled scan (PR-based; live-findings POST is opportunistic, blocked on a secret-injection mechanism for cloud-routine sandboxes — see Known Issues)
- [ ] Give the reference routine a real path to `TECH_ID_API_KEY` (or an equivalent per-provider token) so its findings land in the live table, not just a PR
- [x] Build the Opportunity Map: cabin-ui panel presenting findings as ontology-linked, actionable "Opportunities" per the See/Think/Act model, with lineage chips resolved to real device names (`OntologyLookupService`, `GET /api/ontology/entities`) and every interaction logged (`tech_id_finding_action` table, `POST /api/tech-id/findings/{id}/actions`) — see `docs/PRODUCT_NOTES.md`'s 2026-08-03 UX Lead / Application Architect review for the full design
- [ ] Server-side validation that a submitted finding's `entityId`/`relatedEntityIds` actually exist in `docs/ontology.yaml` — currently a documented, unenforced convention
- [ ] Implement Kidde use case as first end-to-end integration test (browse → scan → finding → adjudicate → ontology update)
- [ ] Build the reconciliation step: adjudicated (`actioned`) findings write back into `docs/ontology.yaml`'s `discovery:` fields — today this is manual
- [ ] Publish `ontology.entity.updated` events when discovery flags change
- [ ] Connect to notification service: push alert when `new_api_available` or `new_alternatives` flip true
- [ ] `check_for_new` schedule configurable per entity from Admin UI (not YAML-only) — the Opportunity Map covers the "review queue" half of the old combined item, scheduling is what remains

### Phase 5 — Platform UI

- [ ] Build see/think/act UI shell in `cabin-orchestration-platform/ui` React frontend
- [ ] Implement entity search: full-text across ontology IDs, labels, tags, and `search_aliases`
- [ ] Implement lineage trace view: upstream (`derived_from`) and downstream (`consumed_by`) per entity
- [ ] Implement discovery panel: entities with `check_for_new: true`, schedule, `last_checked`, current flags
- [ ] RulesEnginePanel reductive UI: Active→Reset, Recent→Undo, filterable by time — for non-technical users managing automations

### Phase 6 — Camera Video Viewing

> Planned 2026-08-02, **built later the same day** once the user confirmed
> they wanted to proceed. Grounded in Frigate's *actual current config* and
> *actual installed source* throughout (pulled live via `GET /api/config`
> and by reading `frigate/api/media.py` directly on the M920q, not assumed
> from generic docs). See `docs/ontology.yaml`'s `cabin_camera_live_view` /
> `cabin_camera_event_clip` / `cabin_camera_continuous_recording` entities
> for full detail, including what's still genuinely unverified (a real
> signed-in browser session hasn't rendered a thumbnail/clip/live view yet
> — no qualifying recent event existed at deploy time to test against).
>
> Key finding that reframed the scope, confirmed correct: **Frigate already
> did most of the underlying video engineering** — this phase ended up
> being mostly configuration plus a proxy/viewing layer in
> cabin-backend/cabin-ui, not a video pipeline built from scratch.

- [x] **(b) Event clip pre/post buffer** — changed to `pre_capture: 15` /
      `post_capture: 60`, both `alerts` and `detections` review tiers
      (decided: both, not just `alerts`).
- [x] **(d) Continuous (DTM) recording** — enabled at `continuous.days: 5`
      — deliberately conservative, decided directly with the user, not the
      originally-floated 14-30 days: `driveway`'s record role turned out
      to use its 4K main stream (missed at planning time), which at
      realistic 4K bitrates could be 80-160+ GB/day once that camera
      reconnects (currently off-network). Re-measure real GB/day before
      extending retention, don't trust this as more than a starting
      estimate.
- [x] **(a) Live view "like Blink"** — built via Frigate's plain MJPEG
      endpoint (`GET /api/{camera}`, simpler than the MSE/jsmpeg/go2rtc
      mechanism assumed at planning time, no go2rtc config needed),
      proxied through cabin-backend and rendered as a plain `<img>` tag in
      cabin-ui. One real wrinkle: `<img>` can't set an Authorization
      header, and this stream is unbounded so it can't be blob-fetched —
      `GoogleAuthInterceptor` now also accepts the token as an
      `?access_token=` query param for this one case.
- [x] **(c) Persisted-event review UI**, mostly — `CameraEventsPanel` now
      shows a real thumbnail (authenticated blob-fetch) and an expandable
      clip player per event. **Not done**: real pagination/filtering
      across full history (still capped at the most recent 30). Also
      fixed a real gap found while building this: `MqttBridgeService`
      never captured Frigate's own event id at all, so even stored clips
      would have been permanently unreachable — now captured as
      `frigateEventId`. Scope question resolved: video/clip access stays
      cabin-ui-only, authenticated (not added to Family Hub's public
      widget) — gated behind `GoogleAuthInterceptor`, matching
      notes/chores/profiles.
- [x] Update `README.md`'s camera-activity security note to also cover
      video/clip storage and retention.
- [x] Not blocked on the `driveway` camera coming back online — confirmed
      camera-count-agnostic in practice: the whole pipeline (event
      capture, clip config, media proxy) applies uniformly regardless of
      which cameras are actually live.

### Phase 7 — Template Configurability, Cross-App Theming, Configurable Place Cards, Camera Drilldown

> Planned 2026-08-07 from four user-reported gaps (Grafana 403 + no
> per-location dashboards; hardcoded `LOCATIONS`; vestigial "Family Hub"
> nav label + stray `⌂` glyph + theme not carrying over between
> `family-hub`/`cabin-ui`; Camera Events panel showing unfiltered device
> events instead of camera-only activity, no DTM-stamped cold-storage
> drilldown, no automation-lineage links). Full grounding, root-cause
> detail, and step sequencing in
> `docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md` — this entry is
> a pointer, not a duplicate of that detail. Sequenced 2026-08-07 by loose
> WSJF (highest value, least effort first) — the items marked `[x]` below
> are done (several partial, honestly flagged as such); everything else in
> this phase is still just the plan. This session also added cabin-ui's
> first-ever test suite (Vitest) and a `HubLocationServiceTest`
> (Testcontainers), both wired into the existing CI test gates — see
> `docs/DEFINITION_OF_DONE.md`'s new §10.

- [x] §2a — Rename "Family Hub" nav panel to "My Places" (it's a
      cabin/home launcher grid, not the actual Family Hub app). The panel's
      own `<h2>` still reads "Smrekar Familia Hub" (page content, not the
      confusing nav label) — left as-is, not part of what was asked.
- [x] §2b (partial) — Replaced the hardcoded `⌂` nav-rail glyph with the
      real family crest (`hodgson-crest.svg`, copied into
      `cabin-orchestration-platform/ui/public/`). **Not done**: cabin-ui's
      hardcoded "Smrekar Familia Hub" heading still doesn't read
      `hub_family_name` — that needs the shared-branding/`platform_branding`
      work (execution plan §2b step 2-3), bigger than a glyph swap, still
      open.
- [x] §2c (partial) — Root cause (origin-scoped `localStorage`, not bad
      apply logic) confirmed and fixed for the actual reported symptom:
      every cross-app link-out (`ThemeProvider.jsx`'s init state,
      `family-hub.html`'s `activeThemeId` init, both apps' link-out hrefs)
      now carries `?theme=<id>`, verified working both directions against a
      real running dev server + static file, plus real automated tests:
      `resolveInitialThemeId` unit-tested (cabin-ui's new Vitest suite) and
      the `?theme=` handoff itself covered in `family-hub/test/run.js`
      (both now CI-gated). §2d, done (partial): the concrete drift found
      this session — `family-hub.html` had `neon80s`/`pacman`,
      `ThemeProvider.jsx` didn't — is closed; both apps now define the
      same 9 themes, palette-translated (not byte-identical CSS vars —
      the two apps use different variable schemes) using the same
      family-`--gold`→cabin-`--accent`/`--teal`→`--success`/`--rose`→`--danger`
      mapping already consistent across every other paired theme. A real
      regression guard now exists too: `ThemeCatalogDrift.test.jsx`
      extracts both files' theme id sets and fails the build if they ever
      diverge again (CI-gated — the cabin-ui test Docker image's build
      context was changed to the repo root specifically so
      `family-hub.html` is reachable inside it). **Still not done**: a
      genuinely single shared source the two files both consume (this
      session closed the *symptom* — matching catalogs, an enforced
      guard against re-drifting — not the *duplication* itself; each
      file still hand-defines its own 9-entry THEMES object).
- [x] §1a — **Live and confirmed working end-to-end as of 2026-08-07.**
      A real Grafana dashboard exists and is genuinely reachable:
      Prometheus scraping Frigate's `/api/metrics` (confirmed `health: up`,
      zero errors, real scrape data), a Grafana Prometheus datasource, and
      Frigate's own official community dashboard (grafana.com/grafana/
      dashboards/24165) provisioned and visible — `https://grafana.
      unicornpingpong.com/grafana/d/aezbolgn22qdce/frigate-monitoring-dashboard`.
      Built by a concurrent Claude Code session (commit `5b5cda2`,
      cherry-picked here), then this session: (1) confirmed the Frigate
      hostname assumption live via SSH (M920q access became available
      mid-session) rather than leaving it as a documented guess; (2)
      corrected a real, separate misdiagnosis — Grafana's Google OAuth
      login actually works (was documented as 403ing since 2026-08-04;
      confirmed via live logs showing the user's own real, current,
      authenticated session); (3) deployed via the normal CI/CD path
      (`gh run watch`-confirmed, not assumed) plus one manual step —
      `cabin-grafana`/`cabin-prometheus` are deliberately excluded from
      the automated family-hub/cabin-ui deploy (same "stateful services
      get a considered rollout" policy as cabin-backend), and a bind-mount
      content change alone didn't trigger Grafana's dashboard provisioner
      to re-scan without an actual container restart — see
      `docs/ontology.yaml`'s `cabin_grafana_frigate_dashboard` entry for
      the full sequence, including a named gap in the deploy tooling this
      surfaced. **Still open**: (1) this is one dashboard for Frigate, not
      yet "per-location, generated from ontology entities" as originally
      scoped — a real, more ambitious remainder; (2) Cloudflare Access
      still isn't confirmed actually gating the public hostname (separate
      from login, which works); (3) a newly-found, real
      session-token-rotation bug causing intermittent 401s on an
      otherwise-valid session — not diagnosed or fixed, flagged for next
      session.
- [x] §1b (partial) — Real `hub_location` Postgres table + `/api/locations`
      CRUD (list/create/patch/delete/reorder) now exists, seeded from this
      instance's own config so a fresh fork writes what App.jsx already
      assumed. cabin-ui merges it into the existing `LOCATIONS` object on
      load — verified live against a stub API returning a third, fictional
      location ("Lake House"), which correctly appeared in both the
      location switcher and the My Places grid. **Not done**: ~30 other
      call sites in App.jsx (presence, alerts, health polling) still
      hardcode `LOCATIONS.cabin`/`.home` directly and assume exactly those
      two keys exist — genuinely making every feature N-location-aware,
      an admin UI for add/edit (the reorder endpoint exists, unused by any
      UI yet), and wiring per-location Grafana dashboards to this table
      are the real remainder, not silently dropped — see
      `docs/ontology.yaml`'s `hub_location` entry. A real
      `HubLocationServiceTest` (Testcontainers-against-Postgres, same
      pattern as `EventPipelineIntegrationTest`) now covers seed/create/
      upsert/patch/archive/reorder — written and confirmed to fail *only*
      on "no Docker" in this sandbox, wired into `deploy-cabin-backend.yml`'s
      existing `mvn test` gate so it actually runs against real Postgres
      on the next push, before anything deploys. Not personally verified
      passing by this session (no Docker here) — that's CI's job now.
- [x] §3 (partial) — Place cards are now reorderable: reused the existing
      `useDraggableOrder` pattern as-is (same client-side, per-browser
      `localStorage` model as Device Manager/Monitoring — matches the
      user's own "the same way we allow re-ordering of devices" request,
      not the server-persisted `/api/locations/reorder` endpoint §1b also
      built, which stays available-but-unused for a future server-synced
      option). A real drag-and-drop test (`App.test.jsx`, dispatches
      actual `dragstart`/`dragover`/`drop` events) confirms the new order
      persists to `localStorage`, not just that the UI renders. Grid is
      also now explicitly responsive — 3 columns desktop / 2 tablet / 1
      mobile, verified live at 3 viewport widths (375px/966px/1400px).
      **Not done**: per-card field configurability (which stats/links show
      per location — still fixed/hardcoded) and dynamic card-size shrink
      to guarantee 9 cards with zero scrolling at any count — the grid
      just scrolls past 9 today, which matches "all available when
      scrolled" but not the "reduce relative sizing" part of the ask. Both
      tracked in the execution plan §3, not silently dropped.
- [x] §4a — `CameraEventsPanel` was fetching `/api/events` completely
      unfiltered — that was the whole "seeing device inputs, not camera
      events" bug. Now fully fixed server-side: `EventController`/
      `CabinEventService` gained a real `eventTypePrefix` filter (the
      fast client-side `isCameraEvent` fix from earlier this session is
      no longer the primary mechanism, though it stays exported/tested
      for reuse elsewhere). Confirmed live against the M920q's *currently
      deployed* (pre-this-session) backend that the old, unfiltered
      behavior is real — `curl`ing the live `/api/events` returned raw
      Zigbee `TELEMETRY` events exactly as described; the new filter
      param will take effect once this deploys.
- [x] §4b (partial) — DTM stamp now renders as an overlay directly on each
      camera event's thumbnail image (not just adjacent list text), using
      `CabinEvent.timestamp` already returned by the API — no backend
      change needed. **Not done**: never verified against the live M920q
      whether Frigate's own snapshot already burns in a timestamp, which
      would make this overlay redundant — M920q access became available
      later in this session but this specific check wasn't done yet.
- [x] §4c (partial) — Real server-side pagination now exists:
      `CabinEventService.recent()` gained `offset` + `eventTypePrefix`
      params (a new 5-arg overload; the old 3-arg signature stays as a
      backward-compatible overload), `EventController` exposes both as
      query params, and `CameraEventsPanel` has a "Load older events"
      button instead of the old hard 30-event cap. New
      `CabinEventServiceTest` (Testcontainers-against-Postgres) covers
      the filter, the pagination, and both combined — same
      "fails only on no-Docker in this sandbox" caveat as this session's
      other Testcontainers tests. Frigate's recordings API
      (`GET /api/{camera}/recordings`) confirmed live against the real
      M920q — real endpoint, valid JSON array response — but the
      continuous-recording timeline/scrubber UI itself is **not built**;
      that's real, separate frontend work using this now-confirmed API
      shape. `cold_storage_backend` ontology entity also **not built** —
      still open, no live-infra blocker for it specifically, just not
      reached this session.
- [ ] §4d — Event → automation lineage record (`ROADMAP.md` Phase 3's
      already-planned `{ from_event, via_automation, to_state, timestamp }`
      — this is its concrete motivating use case), deep-links to the
      triggering HA automation/Node-RED flow, and — sequenced last,
      highest-risk — disable/auto-rearm an automation directly from an
      alert.
- [x] Ontology schema v0.4.0 (partial) — `lifecycle_status` / `first_used` /
      `deprecated_date` fields defined (`docs/ontology.yaml`'s header
      comment) and applied to 3 entries (`hub_location`, `theme_preference`,
      `cabin_camera_event`) with real, citable provenance — not a guess.
      Deliberately NOT a full backfill across ~150+ entries — see the
      Ontology Migration Review section below for the tracked, priority-order
      remainder.
- [x] §2e — Renamed "Family Config" nav label and panel header to just
      "Config" (it configures the whole instance, not only family
      settings). Config panel's Google Account card now reflects the real,
      switchable `useGoogleAuth()` sign-in used to gate the app itself
      (not the separate, unrelated Home Assistant Google integration link,
      kept alongside it) — "Switch Google Account" re-opens Google's
      account chooser (`prompt: "select_account"`, already supported by
      the existing hook, just not surfaced in this panel before). Platform
      and Remote Access cards are now backed by real deploy-time config
      (`CABIN_INSTANCE_PLATFORM` / `CABIN_INSTANCE_REMOTE_ACCESS`,
      `DashboardController`) instead of hardcoded JSX text — both are now
      documented "Template Configuration Fields" in `docs/REPLICATION.md`
      §2/§4 for anyone cloning this app. **Not done / roadmap item**:
      switching to a Google account that isn't already signed into Google
      in the current browser (only switching between accounts already
      signed in, or signing in fresh, works today) — flagged explicitly
      by the user as acceptable to defer, not silently dropped.
- [x] Node-RED embed white-screen (found alongside the above, same
      mixed-content mechanism as the Grafana embed fixed 2026-08-04):
      cabin-ui is served over HTTPS, Node-RED's iframe still pointed at
      plain `http://100.77.44.113:1880`, which browsers silently block as
      mixed content. Fixed with `tailscale serve` (real Let's Encrypt cert
      via the tailnet itself — `tailscale set --operator=nate` once, then
      `tailscale serve --bg 1880` on the M920q) rather than the Grafana
      approach of a public Cloudflare Tunnel route, specifically to keep
      Node-RED's existing, deliberate Tailscale-only boundary intact.
      `VITE_CABIN_NODERED_URL` now points at
      `https://nates-little-m920q.tailb20f8b.ts.net`. Confirmed serving
      (`curl` 200, both from the M920q and via a real page load).
- [x] Camera device staleness bug (found 2026-08-07 investigating a "loads
      then disappears after ~5 min" report): `MqttBridgeService.
      handleCameraTopic()` never called `DeviceRegistry.update()` for any
      camera MQTT message, so a camera's `lastSeen` was set once at
      registration and never refreshed — `DeviceHealthMonitor`'s 5-minute
      camera stale threshold then fired exactly once, permanently, with no
      recovery path. Fixed with `touchCamera()`, wired into the motion and
      per-label-count topics; 5 new tests
      (`MqttBridgeServiceTest`), full backend suite still green.
- [x] "API offline" badge accuracy (found investigating the same report):
      `connected` required *every* attempted location fetch to succeed,
      including an undeployed Home's — permanently false-negative whenever
      viewing Home/Both regardless of Cabin's real health. Fixed with
      `isLocationDeployed()` (an undeployed location's expected failure no
      longer counts), plus the badge now shows the real failure reason and
      timestamp on hover and is a real link to that location's
      `/actuator/health` (a plain navigation, not a `fetch()` — sidesteps
      Actuator's missing CORS config, same reasoning as the 2026-08-03 fix
      this replaces). 4 new tests, verified live against a real dev server
      (both the false-positive-cleared and genuinely-down cases).
- [x] blinkbridge crash-on-no-clip (live incident, 2026-08-07/08, not this
      repo — `roger-/blinkbridge` fork on the M920q at
      `/storage/services/blinkbridge-src/`): a transient Blink cloud-API
      failure made `save_latest_clip()` return `None`, which
      `start_server()` passed straight into `ffmpeg`'s argv, crashing a
      bare `threading.Thread` with no handler and leaving the driveway
      camera dark (`camera_fps: 0.0`) until a manual restart. Root-caused
      via the container's real logs/filesystem, fixed (`start_server()`
      now skips cleanly and logs instead of crashing), covered by 3 new
      tests (stdlib `unittest`, run directly in the container — this fork
      has no CI), rebuilt, and redeployed; confirmed recovered
      (`camera_fps: 4.64`) on the fixed image, not just the restart.
- [x] "My Places" panel header still read "Smrekar Familia Hub" — renamed
      to "My Places" (matching the nav label), plus two stale top-of-file
      doc comments in `App.jsx` that described `FAMILY_HUB`/`FAMILY_CONFIG`
      inaccurately corrected.
- [ ] **[MAJOR, PLANNING NEEDED]** Alert/ontology UX retrenchment — found
      2026-08-08 via direct user report + screenshot: the "Warning — alert
      active" banner (`AlertControls`/`useNavAlerts`) fires on a plain
      `offline_count > 0 OR alarm_count > 0` boolean across ALL devices —
      confirmed live: it was firing with `alarm: 0` real alarms, driven
      entirely by the 5 permanently-placeholder Home cameras (Home isn't
      deployed) being OFFLINE, the same as always. Concretely, today's
      gaps: (1) no per-alert identification of which device or why; (2)
      no distinction between a real emergency (leak/smoke/security) and
      routine/expected offline status (battery Zigbee sensors,
      undeployed-location placeholders); (3) alert-watching requires a
      manual per-panel "Enable" opt-in — a real alarm on an unwatched
      panel shows nothing at all, not even a downgraded warning; (4) zero
      "armed/disarmed" indicator anywhere in cabin-ui (confirmed via
      grep — despite a real `cabin/security/armed_away` MQTT topic and HA
      automations existing server-side) and no arm/disarm control; (5) no
      actionable next-step guidance of any kind. User's own framing:
      "if you're not sure, we have significant retrenchment needed in
      ontology and UX patterns." This needs a real design pass against
      the Northstar "See, Think, Act" goals, not a patch — scope it as
      its own planning item before touching code. Immediate cheap
      mitigation worth doing separately/first: exclude known-undeployed-
      location devices from the offline-alert condition so the banner at
      least stops firing for a permanently-known-non-issue.
- [ ] **[NEW FEATURE, PLANNING NEEDED]** WiFi RSSI-based presence/
      proximity detection — user's proposal, 2026-08-08: ping
      signal-strength/intensity between wired devices (smart switches
      etc.) and the network dongle roughly every minute, for two
      purposes: (1) may help keep wired devices from going dormant/stale
      (same class of issue as tonight's camera `lastSeen` fix — a
      device that's periodically pinged has a fresher liveness signal);
      (2) doubles as ad-hoc "something/someone new showed up that wasn't
      there when the location was armed" detection, via intensity-change
      pattern rather than a dedicated presence sensor. Explicitly NOT
      cabin-only — applies to any location devices are deployed at. Needs,
      once configured: a product/marketing write-up, a configuration
      design-doc section (this is a per-instance/per-device opt-in, not a
      blanket default), and maintenance documentation covering the real
      tradeoff — battery drain on non-wired devices if enabled there,
      vs. the monitoring/alerting benefit, which is the main reason to
      turn it on for wired devices specifically and be cautious about
      battery-powered ones.
      **Candidate evaluated 2026-08-08**: user supplied Grafana community
      dashboard #22019 ("Wifi Scan") — saved and evaluated at
      `grafana/dashboards/22019-wifi-scan/README.md` (not adopted, not
      provisioned live). Finding: it measures a *different* signal than
      proposed here — nearby AP beacon strength via `iw scan` (a network
      site-survey tool), not connected-client-to-dongle link RSSI — and
      it's InfluxDB-based, a TSDB this platform doesn't otherwise run
      (existing metrics path is Prometheus + Grafana, from the Frigate
      work). Recommendation in that README: don't adopt as-published;
      if pursued, route any derived "occupancy-likely" signal through
      MQTT into the same `MqttBridgeService` → `AutomationRuleService`
      path presence/armed-state already use this session, rather than a
      disconnected Grafana-alerting silo — directly relevant given the
      alert-clarity gap logged above. Decision not yet made.
- [x] **Prototype built 2026-08-08**: "go with the prototype option" —
      Zigbee LQI (link quality) instead of WiFi RSSI, zero new hardware.
      Verified live first: three of the cabin mesh's devices are already
      wired, always-on Zigbee routers spread across the building
      (`heater_mech_room`, `main_water_valve`, `smart_switch_breaker_box`),
      and `linkquality` was already flowing through every Zigbee message
      — just never trended. `SignalQualityRegistry` (new, in-memory,
      per-device rolling history) + a hook in
      `Zigbee2MqttAdapter.handleDeviceState()` + `GET /api/signal-quality`
      surface current/baseline/anomalous per device. Deliberately NOT
      wired into `AlertSeverityClassifier`, the toolbar, or any real
      alert path — this is the observe-and-evaluate stage, not a shipped
      feature; `ANOMALY_DROP_RATIO` (30% drop) is an explicit, untuned
      placeholder. **User's hardware note**: the spare, currently-
      unplugged C4000LG router can serve as an additional collection
      point/hub anywhere in the building once this — or a WiFi-based
      approach — proves out enough to justify it; logged, not yet
      actioned. 15 new tests (`SignalQualityRegistryTest`,
      `Zigbee2MqttAdapterTest` — this adapter's first-ever test coverage,
      scoped to only this new integration point — `SignalQualityControllerTest`).
      Backend suite: 67/67 (3 pre-existing Docker-unavailable failures,
      unrelated). See `docs/ontology.yaml`'s `signal_quality_prototype`
      for the full entity.
- [x] Presence toggle was purely manual with nothing real behind it
      (found via user report, 2026-08-08): the toolbar's map-pin widget
      read as "your detected location," but `PresenceProfile` was only
      ever set by a manual `PUT /api/presence`, and `AutomationRuleService`
      already used it to scale real security-event severity — a stale or
      wrong manual toggle had a real safety consequence, not just a
      cosmetic one. A real signal (`cabin/presence/nate`, HA's WiFi
      ARP-check automation) already existed and was already live; nothing
      in `cabin-backend` had ever subscribed to it. Fixed with
      `PresenceSignalRegistry` (new, in-memory, keyed by location+person)
      + `PresenceService.recomputeFromSignals()` (auto-derivation, always
      wins over a manual override once any real signal exists) +
      `MqttBridgeService` subscribing to `+/presence/#` (wildcarded on
      location, not hardcoded to cabin). Deliberately N-people x
      M-locations from day one, not "Nate at the cabin" hardcoded — see
      `docs/ontology.yaml`'s new `active_presence_profile` entity for the
      full derivation rules (including `BOTH_OCCUPIED` meaning *different*
      people at each location, not the same person in two places). Manual
      override is kept, not removed, for a location/instance with no
      presence automation configured yet. `GET /api/presence` now also
      returns `autoDerived`/`signals[]`; the toolbar pin shows a live-dot
      + tooltip (`formatPresenceSignals()`) when auto-derived. 16 new
      backend tests (`MqttBridgeServiceTest`, `PresenceServiceTest`,
      `PresenceSignalRegistryTest` — 43/43 backend suite green) + 8 new
      frontend tests (39/39 green). **Not yet built**: an equivalent
      home-hub-side presence automation (home isn't deployed) and a
      formal "tracked person" registry (personId is today just whatever
      string a publisher's topic uses, auto-discovered like
      `DeviceRegistry` does for devices, not linked to `family_profile`).
- [x] Armed/disarmed state wasn't surfaced anywhere in cabin-ui (found
      immediately after the presence fix above, via the user's own direct
      question: "does armed not simply become a downstream output from
      Node-RED/MQTT/mosquitto based on automation rules?" — correct on
      every count). `cabin/security/armed_away` is a real, live, retained,
      self-healing MQTT signal (HA automation, republishes on toggle AND
      on HA restart) that cabin-backend had simply never subscribed to —
      same class of gap as presence, not a new concept to invent. Fixed
      with the identical pattern: `SecurityStateRegistry` (in-memory,
      keyed by location) + `MqttBridgeService` subscribing to
      `+/security/armed_away` (location-agnostic) + `GET /api/security`
      + a toolbar `SecurityBadge` (lock/unlock icon, tooltip with
      timestamp). Deliberately a direct passthrough, not a derived
      aggregate like presence — no combination logic needed. A location
      with no signal yet reads as a distinct "Unknown," never silently as
      "Disarmed" — conflating those would be dangerous for exactly the
      ambiguous-alert situation this exists to resolve. **Not done**:
      `AlertSeverityClassifier` still doesn't consume armed state (or
      presence) for severity scoring — same pre-existing tracked gap,
      unchanged by this — this only makes the raw signal visible in the
      UI. 9 new backend tests (`MqttBridgeServiceTest`,
      `SecurityStateRegistryTest`, `SecurityControllerTest` — 52/52
      backend suite green) + 4 new frontend tests (43/43 green).
- [x] **Grafana embed — actual root cause found and the iframe replaced
      entirely, 2026-08-08.** The SameSite/cookie theory logged above was
      a real dead end — investigated with genuine evidence, but wrong.
      The actual blocker (found the next morning, from a live user
      report of devices/Node-RED/Grafana/Live-MQTT all failing at once):
      `hub_locations` (Postgres) had been seeded with unreachable
      Docker-internal placeholder URLs for `grafanaUrl` (and every other
      URL field) the very first time that table was created, and
      `mergeHubLocations()` lets that API data silently override the
      correct hardcoded/env-var defaults on every page load — the
      iframe's `src` was pointing at `http://cabin-hub:3002`, which
      cannot resolve from any real browser, so it never had anything to
      do with cookies or Google's iframe policy at all. Fixed live via
      `PATCH /api/locations/cabin`, then root-caused in
      `docker-compose.m920q.yml` (new `CABIN_LOCATIONS_CABIN_*` env vars
      so a future reseed can't reintroduce this). Confirmed via Grafana's
      own server logs: a request with `referer=cabin.unicornpingpong.com`
      appeared for the first time ever once the URL was corrected.
      **Then, separately, by the user's own explicit decision**: even
      with the URL fixed and the embed technically working, an iframe
      that depends on a separate app's session state — and that a
      "just want it to work" user would have to learn to scroll inside —
      isn't acceptable for a multi-user product with mixed technical
      skill levels. Replaced with a native `CameraHealthPanel`
      (`FrigateMetricsController` queries Prometheus directly server-side
      — Prometheus itself stays Tailscale/internal-only, no new exposure
      needed, confirmed cabin-backend already reaches it internally) plus
      a plain "Open Full Dashboard in Grafana ↗" link-out for anyone who
      wants the full metric set. 4 new backend tests + 3 new frontend
      tests (71/71 backend, 46/46 frontend). See `docs/ontology.yaml`'s
      `camera_health_panel` entity for the full writeup.
- [ ] **[NEW, follow-up to the above]** `CameraHealthPanel` today shows
      exactly one metric (`frigate_camera_fps`) with zero user-facing
      configurability. User's actual ask goes further: "cherry pick"
      which library metrics to show, reorder them, and tune distinct
      minimalist/maximalist layouts for kiosk vs. mobile form factors —
      not "just make the iframe smaller," a real curated-metrics-picker
      feature. Scope next session: what other `frigate_*`/Prometheus
      metrics are worth exposing (detection_fps, process_fps already
      confirmed available), what a metric-selection UI looks like (maybe
      an extension of the Config panel), and concretely different
      kiosk vs. mobile layouts rather than one flex-wrap grid trying to
      serve both. **Not yet visually verified on a real mobile/kiosk
      viewport at all** — the browser tool was unavailable all session;
      today's flex-wrap layout is a reasonable default (same pattern as
      the existing KPI tiles), not a confirmed one.
- [x] "No live messages from ws://..." in the Monitoring panel's Live
      MQTT tile was always going to show that, regardless of hostname —
      found investigating the user's report: mosquitto had no WebSocket
      listener at all (`mosquitto.conf` only had `listener 1883`).
      Fixed on the M920q's pre-existing stack (not this repo — see
      `docs/MAINTENANCE.md`'s new "MQTT WebSocket listener" section for
      the full two-part fix: `mosquitto.conf` gained a `listener 9001` /
      `protocol websockets` block, and the *separate* compose file
      managing mosquitto needed `- "9001:9001"` added to actually
      publish it to the host — mosquitto bound the port inside the
      container but nothing exposed it, confirmed via `docker port`).
      Verified with a real WebSocket handshake (`HTTP/1.1 101 Switching
      Protocols`, `mqtt` subprotocol echoed back), not just an open
      port; all existing MQTT clients reconnected cleanly after the
      container recreate. **Not yet verified**: cabin-ui's
      `useMqttTelemetry` hook rendering real messages end-to-end in a
      browser — the transport was never reachable before, so that code
      path is realistically untested, not just unverified today.
- [ ] **[NEW, PLANNING NEEDED — user directive, 2026-08-08, roadmap for
      tomorrow, not built tonight]** App-wide Google OAuth gate +
      consistent landing page. Two separate but related asks:
      1. **Auth gating is currently inconsistent and too narrow.**
         `useGoogleAuth()` today only actually gates `CameraEventsPanel`
         and `OpportunityMapPanel` (passed an `auth` prop) — every other
         panel (Devices, Monitoring, Config, Rules) renders and loads
         real data with no sign-in check at all. User's framing: "if
         we're going to require credentialed google oauth login, it
         shouldn't just be at the camera events tab — that's silly if it
         applies to multiple cookies and workflows in
         cabin.unicornpingpong.com." The gate needs to move to the top
         of the app — before any panel/data loads, not per-panel.
      2. **Landing page needs to be consistent, not whatever was open
         last.** User's report: cabin-ui currently reopens on whatever
         panel was showing when it was last closed, which reads as
         unintuitive. **Checked the actual code before roadmapping this
         — worth starting from an accurate baseline tomorrow**:
         `activePanel`'s `useState` initializer (App.jsx) does NOT
         persist to localStorage and defaults to `"MONITORING"` (or
         `?panel=` from the URL) on every real mount — there is no
         app-level code currently making this "sticky." What the user is
         observing is most likely the browser's own tab/session
         restoration (Chrome reopening the SPA in whatever in-memory
         state it was in, without a real page load happening at all) —
         a real UX problem regardless of the mechanism, but tomorrow's
         session should confirm this diagnosis rather than assume a
         localStorage bug that doesn't exist in the code as of tonight.
         User's own assumption: the consistent landing page should be
         **My Places** (`FamilyHubPanel`) — not confirmed/decided, just
         their stated default expectation to start planning from.
      3. **Auth flow on landing, two behaviors requested:** (a) persist/
         reuse a still-active Family Hub login if one exists (family-hub
         and cabin-ui already share the same `GOOGLE_CLIENT_ID` — but
         they're different origins, so this is NOT automatic; needs real
         design work, e.g. Google's own silent/One Tap re-auth, or some
         other session-sharing mechanism, not assumed to already work),
         or (b) if there's no reusable session, require login **before**
         offering any other data/UI/functionality to load — a hard gate,
         not the current "some panels check, most don't" state. Explicitly
         called out as mattering most for **direct navigation** into
         cabin-ui (a deep link bypassing family-hub's own link-out flow).
      Scope this properly next session — this is real auth/UX
      architecture work, not a quick patch.
- [x] **Device Manager showed every device regardless of the active
      location tab** (found 2026-08-08, user report: switching to Home
      still showed Cabin's devices) — `DmSeeView`/`DmChangeView`/
      `DmRemoveView` all received the full, unfiltered `devices` array;
      `activeLocation` was only ever used to key the reorder-order
      localStorage key, never to actually filter what rendered. Fixed by
      filtering once in `DeviceManagerPanel` (respecting `"both"` =
      show all) before passing down to all three sub-views. Also fixed
      `DmRemoveView`'s hardcoded `location === "home" ? ... : ...`
      apiBase ternary (would have broken for any 3rd location) to read
      from `LOCATIONS[sel.location]` instead.
- [x] **"Both" only reads correctly for exactly two locations** (found
      2026-08-08, user flagging this ahead of adding a 3rd location) —
      `allLocationsLabel(count)` now shows "Both" for ≤2 locations,
      "All" for 3+. The internal value driving the toggle stays the
      literal string `"both"` everywhere (existing localStorage order
      keys and `refreshDevices`' branching key off it) — only the
      user-facing label changes.
- [x] **No UI existed to actually add a new location** (found 2026-08-08
      — `hub_location`'s full backend CRUD, Phase 7 §1b, existed since
      that work landed, but nothing in the frontend ever called
      `POST /api/locations`). Added `AddPlaceForm` in My Places — id +
      label required, all connection URLs optional at creation (fillable
      later via `PATCH /api/locations/{id}`, same endpoint the live
      `hub_locations` URL fix used this session). 7 new frontend tests
      (form validation, POST body shape, server-error surfacing, label
      logic). Full suite: 52/52.
- [x] **"Offline" was misleading — didn't distinguish "hasn't reported
      yet" from "actually unreachable"** (user report, 2026-08-08, verbatim
      in `docs/ontology.yaml`'s new `device_checkin_status` entity). Added
      a second, additive status axis (`CheckinStatus`: ON_SCHEDULE / LATE
      / MISSED / NOT_CONFIGURED) computed by `DeviceHealthMonitor` every
      60s cycle alongside — not instead of — the existing `DeviceStatus.
      state`. A device now gets a grace tier (LATE) before anything reads
      OFFLINE; `ha_rest` devices additionally get a real active poll
      (`DeviceRegistry.activeFetch()` → `HomeAssistantAdapter.fetchState()`,
      a genuine HTTP round-trip) attempted before escalating to MISSED —
      only MISSED still flips `state` to OFFLINE, same trigger point as
      before. Disabled/not-yet-installed devices report NOT_CONFIGURED
      and skip staleness tracking entirely. New `GET /api/devices/
      checkin-status` endpoint; `checkinStatusLabel()` (App.jsx) overrides
      the Device Manager and Monitoring badges, never for ALARM/CRITICAL.
      6 new backend tests (`DeviceHealthMonitorTest`, pure classification
      + full-cycle behavior with a fake HA adapter) + 5 new frontend tests.
      Full suites: backend 80/80 excluding 3 pre-existing Docker-dependent
      tests this sandbox can't run (no Docker — see `CLAUDE.md`'s Testing
      section, unrelated to this change); frontend 57/57. Verified live in
      a browser preview (no console errors, graceful degradation when the
      backend is unreachable) — not yet verified against the real M920q
      backend with actual devices.
      **Fast-follow, 2026-08-08**: MQTT/Zigbee now has a real active
      verification slice too. When a Z2M device is stale, the monitor
      re-subscribes to its authoritative `<device>/availability` topic and
      only recovers it when the broker replays retained `online`. Retained
      `offline`, malformed/non-retained traffic, timeout, or broker disconnect
      does not suppress MISSED. RTSP cameras remain time-based only. Canonical
      definitions: `z2m_retained_availability` and
      `z2m_retained_availability_probe_result` in `docs/ontology.yaml`.
      Targeted backend tests 17/17; full backend run 81/84 passed, with
      only the 3 pre-existing Docker/Testcontainers tests unable to start
      because Docker is unavailable in this environment.
- [x] **Rules & Alerts always showed one Node-RED regardless of location
      context** (user report, 2026-08-08, verbatim: "I only see one node
      red... same context shift behavior for all locations"). `RulesPanel`
      now computes its location list from `activeLocation` the same way
      `MnSeeView` (Monitoring) already does, and splits into one
      `LocationRulesSection` per location in "Both" mode instead of one
      hardcoded iframe. A location without its own configured Node-RED
      (checked via the existing `isLocationDeployed` heuristic) falls back
      to Cabin's flows with an explicit, visible hint saying so — Cabin
      itself is never flagged as a fallback. User's own scoping, honored:
      "all can live on the 920q for now" — no second Node-RED instance was
      stood up, this is a rendering-only fix. 3 new frontend tests
      (single-location, fallback-hint, Both-mode split). Full suite:
      60/60. Verified live in a browser preview across Cabin/Home/Both.
      See `docs/ontology.yaml`'s new `rules_panel_location_context`.
- [ ] **[NEW, PLANNING NEEDED — user question, 2026-08-08]** Armed/disarmed
      state isn't a trigger for anything in this app, and Frigate presence
      + door detection can't be watched independently of a single
      monolithic "armed" toggle. Investigated live, not assumed:
      - `SecurityBadge` (App.jsx) is a real, live-wired but **read-only**
        indicator — no arm/disarm control exists anywhere in cabin-ui. The
        actual toggle is `input_boolean.cabin_security_armed_away` in
        Home Assistant, republished to `cabin/security/armed_away` and
        merely displayed here. One global boolean, no zones/granular
        arming.
      - `AlertSeverityClassifier.java` still explicitly ignores armed/
        presence state (comment: "no armed/presence awareness yet,
        deliberate scope cut") — confirmed still true, not wired despite
        both signals being live since 2026-08-08 (see
        `docs/DEFINITION_OF_DONE.md`'s punch list, unchanged item).
      - **Real bug found this session, not previously known**:
        `MqttBridgeService.handleFrigateDetectionEvent()` hardcodes every
        Frigate detection to `"INFO"` severity — it never reaches
        `AlertSeverityClassifier` at all, so no Frigate detection can ever
        become WARN/CRITICAL or trigger `NtfyAlertPublisher` through this
        app's own event pipeline, regardless of content or armed state.
        This is the same class of bug fixed for `Zigbee2MqttAdapter` on
        2026-08-06 — that fix apparently didn't cover this call site.
      - A **separate, working mechanism already exists but is invisible
        from cabin-ui**: Node-RED's "Camera Overnight Alerts" flow (see
        `docs/MAINTENANCE.md`) already gates Frigate alert-tier
        detections on `armed_away` + presence and pushes real ntfy
        notifications — fixed and verified 2026-08-07. It's real, but
        lives entirely in the embedded Node-RED editor with no
        cabin-ui-native surface showing it's active or letting a user
        tune it.
      - `CameraHealthPanel` has no confidence-threshold concept at all —
        Frigate's own `score` field is already captured in the event
        payload and shown per-event in Camera Events (`label (87%)`), but
        never as a live, configurable-threshold functional-state tile.
      **What this needs** (not yet scoped into an execution plan): (1)
      fix the hardcoded-INFO bug so Frigate detections actually reach the
      classifier; (2) wire armed+presence into
      `AlertSeverityClassifier` (the "purely a wiring task" already
      flagged as ready); (3) decouple "presence/door watching" from the
      single global armed toggle — likely a second, independent state
      distinct from full security-arming — so cameras/doors can alert
      even when "all" automations aren't armed, per the user's explicit
      ask; (4) surface Node-RED's existing overnight-alert logic (or its
      replacement) as a real cabin-ui control, not an embedded editor tab;
      (5) a native confidence-threshold-configurable functional-state UI
      for camera/presence detection, extending `CameraHealthPanel` rather
      than replacing it. See `docs/HANDOFF_2026-08-08_codex-fork.md`'s
      Item 6 for the full framing if picked up on the fork.

### Phase 8 — Accessible Hardware Program: Local Collector Hubs (planning only, 2026-08-08)

> **Execution scope:** [docs/EXECUTION_PLAN_2026-08-08_collector-hubs.md](docs/EXECUTION_PLAN_2026-08-08_collector-hubs.md)
> defines implementation slices, compatibility rules, test gates, security
> boundaries, and the Home pilot. Runtime implementation has not started.
>

> Product decision, not yet implemented. **Corrected same day** by the
> user after my first pass wrongly assumed the goal was cheap hardware to
> run the *full* stack per location. The actual ask: a **local hub is a
> tiny server that collects device metrics and routes them to a central
> "main brain"** — it is explicitly *not* meant to run Postgres/Kafka/the
> Spring Boot backend/Grafana itself. The "main brain" stays at least
> M920q-class, and — separately — always has the option to run in the
> cloud instead of self-hosted. This is a genuine architecture
> correction, not a hardware-list edit, and is written up as such rather
> than silently patched.
>
> **This exposes a real gap: today's repo has no edge/central split at
> all.** Both `infra/docker-compose.m920q.yml` and
> `locations/home/docker-compose.yml` stand up a **full independent
> stack per location** — each with its own Postgres, Kafka, Spring Boot
> backend, Grafana, HA, Node-RED, and Frigate (`locations/home/
> docker-compose.yml`'s own header literally reads "Full Stack").
> Nothing in this codebase today lets a lightweight box just collect and
> forward. Realizing the user's actual ask needs real design work before
> any hardware pick is fully actionable — see "Required design work"
> below.
>
> `CLAUDE.md`'s "no ARM/Pi hardware" constraint reconciliation from the
> first pass still holds, now scoped correctly: it's the *local
> collector hub* role, not "the platform," that's the planned ARM/Pi
> target.

**What a local hub actually needs to run** (much lighter than a full
stack): Zigbee2MQTT (hardware-bound — needs a USB Zigbee coordinator
physically attached to whichever box is local), an MQTT broker or just an
MQTT forward/bridge, and *optionally* Frigate if camera object-detection
should stay local (bandwidth/privacy win — send detection events, not
raw video, to the central brain) rather than streaming raw RTSP
centrally. Everything else (Postgres, Kafka, the Spring Boot backend,
cabin-ui, Grafana/Prometheus) belongs to the central brain, reachable by
one or many local hubs the same way `HomeAssistantAdapter` already
reaches a location's HA instance today (a Tailscale-routed URL) — that
existing pattern is the natural starting point for the forwarding
mechanism, not something invented from scratch.

**Required design work before hardware selection is fully actionable**
(now scoped in `docs/EXECUTION_PLAN_2026-08-08_collector-hubs.md`, but not implemented):
1. Define exactly which services are "edge" vs. "central" (draft above
   is a starting point, not final).
2. Design the edge→central forwarding mechanism — most likely an MQTT
   bridge/forward over Tailscale, reusing the existing location-aware
   URL-config pattern (`hub_location`, `CABIN_LOCATIONS_*` env vars)
   rather than inventing a new transport.
3. Make the central brain genuinely multi-location-aware as **one**
   instance serving many edge hubs, rather than today's one-full-stack-
   per-location model — a materially bigger shift than it sounds, since
   `hub_locations` today assumes each location already has its own
   reachable API/Grafana/Node-RED/etc., not just an MQTT feed.
4. Document and test an actual cloud-hosted deployment target for the
   central brain (ties to the pre-existing, still-open
   `docs/DEFINITION_OF_DONE.md` §8 gap: "a placeholder exists for a
   non-self-hosted deployment target, even if unused today" — the
   Docker-based backend is already architecturally cloud-portable, this
   has just never been documented or tested as a real path).

**Ranked hardware for the local-hub (collector/router) role specifically
— a much lighter bar than my first pass assumed:**

1. **Raspberry Pi — 3B+/Zero 2 W if MQTT+Zigbee-only, Pi 4 if Frigate
   stays local at the edge.** Now the best fit, reversing my first
   ranking: without Postgres/Kafka/a JVM backend to carry, even a Pi 2
   would be closer to viable for pure MQTT+Zigbee collection, but I'd
   still recommend 3B+/Zero 2 W as the realistic floor (better
   supply/support/price-per-unit today) and Pi 4 specifically if local
   Frigate is wanted. Official Docker images exist for Zigbee2MQTT/
   Mosquitto/Frigate on arm64 already. Real work still needed: an actual
   "collector-only" compose profile doesn't exist in this repo yet (see
   "Required design work" above) — today's `locations/home/
   docker-compose.yml` can't just be pointed at a Pi as-is, it would try
   to stand up the full stack.
2. **Android + Termux.** Lowest acquisition barrier of all (an idle
   phone many people already own), and *more* viable for this scoped-
   down collector role than for running a full stack — Mosquitto/a
   forwarder/even Node-RED (Node.js-based) run fine under Termux without
   root. **Two real, unproven risks to flag, not assume away**: (a) a
   USB Zigbee coordinator via Android USB-OTG + Termux USB permissions is
   genuinely finicky and has not been validated in this project at all;
   (b) 24/7 unattended reliability against Android's Doze mode/aggressive
   background-process killing needs `Termux:Boot` + wake locks + a
   battery-optimization exemption — solvable, known patterns, but not yet
   prototyped here. Recommend a small spike to validate both before
   committing to this as a first-class supported option.
3. **Cheap secondhand x86_64 mini-PC or laptop, imaged with Ubuntu.**
   Still valid, but no longer uniquely necessary just to have somewhere
   to run things, since the local-hub role no longer needs Kafka/
   Postgres/a JVM. Repositioned: the right choice when local Frigate
   /heavier edge processing is wanted, or when someone wants to collapse
   both tiers onto one box and self-host the central brain there too —
   which is exactly what the M920q does today, and remains a fully valid
   choice, just a different question ("do I want one box or two") than
   "what's the cheapest local collector."

**Deliberately excluded from the top 3**: the user's own Windows PC
(`ilikethelights`) — existing dev machine, not a template recommendation
for a future stranger's spin-off.

**Implementation status:** no collector runtime or central-ingestion code has
started. The architecture is now scoped in
`docs/EXECUTION_PLAN_2026-08-08_collector-hubs.md`; its slices and test gates
are prerequisites to deployment, not optional polish.

**Home is the real pilot for this model — resolved, same day.** User
confirmed: Home should be "up all the time, get devices online, and then
[route] to the 920q" — i.e. Home is the collector-hub model, not a full
independent peer (`locations/home/docker-compose.yml`'s current "Full
Stack" design is *not* what gets deployed there). This resolves the open
question raised in the Codex handoff doc's Item 5. **New requirement,
same message**: the Home device should ideally also drive a touchscreen
kiosk display loading Family Hub — good news, `family-hub` is already a
plain static-file container (`family-hub/family-hub.html`, served via
nginx, see `infra/docker-compose.m920q.yml`) reachable over Tailscale, so
the kiosk device doesn't need to *host* anything, just point a kiosk
browser at the M920q's existing `family-hub` URL. This narrows tiers 1–2
above to a concrete recommendation for Home specifically:
- **Validate first, cheaply, before buying anything**: the user already
  has an Android device set up for Termux ("I have one ready for this")
  — zero acquisition cost, and Android's kiosk-browser ecosystem (e.g.
  Fully Kiosk Browser) is more mature than DIY Chromium-kiosk-mode. The
  one real unknown is whether Termux can reach a USB Zigbee coordinator
  over Android USB-OTG without root — unproven in this project. **Test
  that specifically, first**, before committing hardware budget — full
  step-by-step playbook in
  [`docs/POC_2026-08-08_termux-zigbee-collector.md`](docs/POC_2026-08-08_termux-zigbee-collector.md),
  including the exact go/no-go signal to look for and where to stop and
  fall back rather than over-invest time.
- **If the USB-Zigbee test fails**: fall back to a Raspberry Pi 4 (not
  3B+/Zero 2 W, given the kiosk-display requirement now — Chromium kiosk
  mode wants the extra RAM/CPU) with an HDMI touch monitor or the
  official 7" touchscreen — ~$60–120 all-in, well-documented Zigbee-
  coordinator-over-USB + Chromium-kiosk pattern, zero unknowns. The
  existing Android device would still work fine as a **kiosk-only**
  display in this case (pointed at Family Hub/cabin-ui), just not as the
  Zigbee collector.
- Either way, this still needs the "Required design work" list above
  built first — there is no collector-only compose profile in this repo
  yet to actually deploy to either device.

**Coordinator hardware recommendation, same day — changes the POC's
risk profile, not just a parts pick.** User asked which Zigbee
coordinator to buy for Home. Recommended: **SMLIGHT SLZB-06 or SLZB-07
(Ethernet variant preferred over WiFi)** — a *network-attached*
coordinator, not a USB one. Zigbee2MQTT talks to it over plain TCP
(`tcp://<ip>:6638`) instead of a directly-attached USB serial device,
which **sidesteps the entire USB-OTG/`termux-usb`/serial-chipset
uncertainty** the Termux POC playbook above was built around — a regular
TCP socket connection needs no special Android permissions at all, unlike
raw USB device access. Run Zigbee2MQTT itself locally at Home (on
whichever collector device — the Termux phone or a Pi — connecting to
the SLZB unit over Home's own LAN), not centralized at Cabin, so Home's
Zigbee control keeps working even during a Home↔Cabin network hiccup;
only the resulting MQTT telemetry needs to reach the M920q "main brain."
**Fallback if matching Cabin's existing setup is preferred instead**:
the Sonoff Zigbee 3.0 USB Dongle Plus V2 (same coordinator Cabin already
uses, `ember` adapter, fully proven in this repo) — its CP2102N
USB-serial chip has reasonably good odds on Android compared to more
obscure chipsets, if the USB-OTG path is still worth testing. **Not
recommended**: ConBee II/III (deCONZ) or TI CC2652P-based dongles — both
fine coordinators on their own, but introduce a second firmware/adapter
ecosystem alongside Cabin's `ember` setup for no real benefit here.
**Practical effect on the POC playbook**: with an SLZB unit, Phases 2–4
of `docs/POC_2026-08-08_termux-zigbee-collector.md` (the risky USB-OTG
part) can likely be skipped in favor of a much simpler "can Termux open
a plain TCP socket to the coordinator's LAN IP" check — the playbook
hasn't been rewritten for this yet since no coordinator has been
purchased; do that rewrite once the SLZB unit is actually in hand and
its IP is known, rather than guessing at exact commands now.

---

## Environment & Credentials Reference

| Item | Value |
|------|-------|
| M920q Tailscale IP | `100.77.44.113` |
| Docker Compose path | `/storage/containers/compose/cabin/` |
| Google OAuth owner | `nhsmrekar@gmail.com` |
| Calendar / Photos | `smrekarfamilia@gmail.com` |
| OAuth current origin | `http://nates-little-m920q.tailb20f8b.ts.net:4081` _(update to unicornpingpong.com)_ |
| Zigbee coordinator | `/dev/serial/by-id` → `/dev/ttyACM0` · adapter: ember |
| Parenting schedule | Versioned rules (see `docs/ontology.yaml` → `parenting_schedule_rule_version`): March 13 2026 original (`[0,1,4,5,8,9,12,13]`) superseded by July 27 2026 50/50 split (`[2,3,4,5,9,10]` = Wed/Thu/Fri/Sat wk A, Wed/Thu wk B; Rachel Mon/Tue always, Sun per week). Holidays override per-date via `holiday_override`. Historical dates always compute under whichever version was actually in effect. |
| Domain registrar | Porkbun — `unicornpingpong.com` |
| DNS | Cloudflare free tier |

---

## Appendix — Paired Zigbee Devices

All 14 devices paired and renamed to canonical snake_case ontology IDs:

| Ontology ID | Device | Location |
|-------------|--------|----------|
| `motion_entry` | SONOFF SNZB-03PR2 SenseGuard Motion Gen2 | cabin entry |
| `door_front_contact` | SONOFF SNZB-04P | front door |
| `door_second_contact` | SONOFF SNZB-04P | secondary door |
| `temp_outside_lowest` | SONOFF SNZB-02WD | outside back door |
| `temp_kitchen` | SONOFF SNZB-02WD | upstairs/kitchen |
| `leak_mech_room` | THIRDREALITY leak sensor | basement floor |
| `leak_alarm_fridge` | THIRDREALITY leak sensor | fridge area |
| `leak_alarm_dishwasher` | THIRDREALITY leak sensor | dishwasher area |
| `leak_alarm_bathroom` | THIRDREALITY leak sensor | bath/toilet/tub |
| `temp_mech_room` | SONOFF SNZB-02WD | basement breaker box area |
| `heater_mech_room` | THIRDREALITY smart plug | plumbing heater auto-switch |
| `main_water_valve` | Zigbee clamp-on actuator | 3/4" main water shutoff |
| `smart_switch_breaker_box` | THIRDREALITY smart plug | fan/heater for mech room humidity |
| `water_leak_buzzer` | THIRDREALITY buzzer | repurposed 120dB intrusion siren |

---

## Review Checkpoints

### Platform Review — 2026-08-27
- [ ] `unicornpingpong.com` registered and Cloudflare tunnel live
- [x] All platform code in `FaceoftheCabin` (smrekar-platform deprecated 2026-07-27)
- [ ] M920q docker-compose.yml committed to CabinAutomations
- [ ] Phase 1 blockers cleared
- [ ] Tech ID Service discovery flags updated for any new device purchases
- [ ] Ontology schema version still at `1.0` or bumped with changelog

### Ontology Migration Review — Monthly (recurring until 0 pending remain)

`docs/ontology.yaml` contains entries with `migration_status: pending` that must be upgraded
to v0.3.0 schema (`entity_type`, `ui_display_name`, `data_type`, `constraint`,
`rendering` replacing `label`, `relationships` replacing `derives_from`/`used_by`).
Work in `migration_priority` order. Update the count below after each session.

| Review Date | Pending Count | Notes |
|-------------|---------------|-------|
| 2026-07-27  | 24 of 24      | v0.3.0 schema established; Family Hub (32 entries) complete |
| 2026-07-28  | 0 of 24       | All 24 legacy entries migrated; ontology fully at v0.3.0 |
| 2026-07-28  | n/a           | +8 entries: location_context class, L1–L3 properties, device_location_formatted, location_vocabulary_term, candidate_term_submission |
| 2026-08-27  | —             | _(scheduled review — verify no new pending entries)_ |
| 2026-09-27  | —             | _(scheduled)_ |
| 2026-10-27  | —             | _(scheduled)_ |

**v0.4.0 migration — schema defined 2026-08-07, 3 of ~150+ entries
migrated.** Adds `lifecycle_status` / `first_used` / `deprecated_date` per
element (Phase 7, execution plan §5; field definitions in
`docs/ontology.yaml`'s header comment). Applied so far: `hub_location`
(new entity, full provenance from its own creation this session),
`theme_preference` and `cabin_camera_event` (pre-existing entries, dates
backed by real citable evidence already in this file/`CLAUDE.md` — not
guessed; `theme_preference` is the deliberate example of *why* `first_used`
is distinct from the ontology entry's own add date — the concept shipped
2026-07-26, the entry documenting it was added 2026-07-31). No full
backfill attempted — same priority-order approach as v0.3.0 below, and
the same rule as v0.3.0's own migration: every entity newly ADDED to this
file from 2026-08-07 onward carries these fields from day one (see
`docs/DEFINITION_OF_DONE.md` §9/§10), existing entries migrate
opportunistically (touched for another reason) or in scheduled
priority-order passes.

| Review Date | Migrated | Notes |
|-------------|----------|-------|
| 2026-08-07  | 3        | Schema defined; `hub_location`, `theme_preference`, `cabin_camera_event` |

**Priority order (migrate highest first):**

| Priority | ID | Reason |
|----------|----|--------|
| 1 | `display_name` | Active in UI, 4 consumers |
| 2 | `device_state` | Active backend + UI, 4 consumers |
| 3 | `temperature_celsius` | Active sensor, 4 consumers |
| 4 | `humidity_percent` | Active sensor, 4 consumers |
| 5 | `battery_percent` | Active sensor, 4 consumers |
| 6 | `contact_state` | Active sensor, 4 consumers |
| 7 | `fotc_device_id` | Active API + UI, 4 consumers |
| 8 | `tailscale_ip` | Active infra, 4 consumers |
| 9 | `z2m_friendly_name` | Active integration, 3 consumers |
| 10 | `zigbee_ieee_address` | Active hardware FK, 3 consumers |
| 11 | `linkquality` | Active sensor, 3 consumers |
| 12 | `mqtt_topic` | Active protocol, 3 consumers |
| 13 | `device_command` | Active/planned backend, 3 consumers |
| 14 | `audit_log` | Partially implemented, 3 consumers |
| 15 | `zigbee_model` | Active hardware, 3 consumers |
| 16 | `mqtt_availability_topic` | Active protocol, 2 consumers |
| 17 | `zigbee_vendor` | Active hardware, 2 consumers |
| 18 | `device_room` | Planned metadata table, 4 consumers |
| 19 | `device_install_photo` | Planned, 3 consumers |
| 20 | `data_dictionary_ui` | Planned UI, 3 consumers |
| 21 | `semantic_layer_toggle` | Planned UI, 4 consumers |
| 22 | `action_confirmation_copy` | Planned safety UI, 2 consumers |
| 23 | `docker_network_cabin_default` | Infra-only, 2 consumers |
| 24 | `machine_hostname` | QA runner only, 2 consumers |
