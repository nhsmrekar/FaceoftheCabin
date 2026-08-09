# Smrekar Platform

**ImpressiveLLC / FaceoftheCabin** · unicornpingpong.com _(live — hub/cabin/api all public)_

A self-hosted platform unifying home/property automation and family
coordination under a shared event bus, identity layer, and living
ontology — built for one family's real deployment (a Lenovo M920q at a
cabin, exposed via Cloudflare Tunnel, administered privately via
Tailscale) and designed from the start to be replicated independently
for another family, another property, or another team's use case. See
[`docs/REPLICATION.md`](docs/REPLICATION.md) for what that actually
takes.

---

## Northstar Goals

1. **Unified family experience** across cabin display, home machine, and phone via single domain (`unicornpingpong.com`)
2. **See → Think → Act** — every element and attribute supports this user interaction model at every UI surface
3. **Living ontology as primary differentiator** — every data point is findable, traceable, and actionable across all platform verticals
4. **FAIR data throughout** — Findable, Accessible, Interoperable, Reusable — enabling AI/RAG/LLM at any layer
5. **Event-driven architecture** — camera → detection → normalized event → automation → action, with full audit trail
6. **Self-improving discovery** — monthly, scheduled checks against industry-standard vendor sources and DIY/pro community pages, surfacing "next best idea" upgrade opportunities per cataloged device/service, not just new-purchase alerts. *Fully specified in `ROADMAP.md`'s Tech ID Service section; not yet built — see that doc for honest status.*
7. **Radical device flexibility** — no device, protocol, or vendor is a hard architectural assumption; every integration follows the same ontology-first onboarding pattern (`docs/REPLICATION.md`)

---

## Products

| Product | Subdomain | Description |
|---------|-----------|-------------|
| **Family Hub** | `hub.unicornpingpong.com` | Ambient display: clock, parenting schedule, calendar, photos, chores, rewards, family notepad |
| **FaceOfTheCabin** | `cabin.unicornpingpong.com` | Cabin automation: cameras, sensors, alerting, presence detection |
| **Platform API** | `api.unicornpingpong.com` | Shared services: identity, ontology, event bus, audit trail |

---

## ⚠️ Security note: camera activity on Family Hub

Family Hub's Overview page shows a live camera-activity widget from
FaceOfTheCabin — by default it shows **which camera, what was detected,
and when**, refreshed every 20 seconds, visible to **anyone looking at the
screen, no sign-in required**. This is genuinely useful (at a glance, is
anyone at the cabin right now) but it is also, unavoidably, a presence/
absence indicator — the same information that tells you "someone's home"
also tells anyone else looking that "no one's home right now."

**This is configurable, not fixed.** Settings → Cabin Camera Activity has
three levels:
- **Full detail** (default) — camera, object, timestamp.
- **Activity only** — just "recent activity: yes/no," no camera name, no
  object, no precise time.
- **Off** — hides the widget entirely.

If this display sits somewhere more people can casually glance at than
just your own family (a shared space, a device you don't fully control
who sees), turn it down. The same warning is shown directly next to the
widget itself, with a link straight to this setting.

**Video viewing (built 2026-08-02, see `ROADMAP.md` Phase 6) is a
separate, more sensitive surface from the widget above.** cabin-ui's
Camera Events panel — authenticated, Google sign-in required, **not**
part of this public Family Hub widget — now shows real snapshots and
event clips, plus a live per-camera view. This is a meaningfully bigger
exposure than the metadata-only widget above: an image or video frame is
categorically more sensitive than "motion detected at 3:14pm." Concretely:

- **Event clips**: 15 seconds before to 60 seconds after each detected
  event, kept 10 days.
- **Continuous (24/7) recording**: every camera's full feed, kept 5 days
  — a deliberately short retention window given one camera's recording
  stream is full 4K resolution (storage cost scales fast; extended only
  after real usage is measured, not a guessed number).
- **Live view**: on-demand streaming, not itself separately recorded —
  covered by continuous recording above.

None of this is reachable from the public, no-sign-in Family Hub widget
described earlier in this note — it lives entirely behind cabin-ui's
platform session (`cabin.unicornpingpong.com`), gated server-side on every
request, not just hidden client-side. Family Hub hands an accepted session to
cabin-ui; direct cabin navigation offers Google sign-in only when there is no
valid inherited session. Cameras never add a second login. Every explicitly
admitted platform user gets the same video access — there is no separate,
finer-grained permission tier for video vs. the rest of cabin-ui today.

---

## Repository Map

| Repo | Org | Contents |
|------|-----|----------|
| [FaceoftheCabin](https://github.com/ImpressiveLLC/FaceoftheCabin) | ImpressiveLLC | cabin-ui (React), cabin-backend (Spring Boot), family-hub (static HTML), infra (Docker Compose) |
| [CabinAutomations](https://github.com/ImpressiveLLC/CabinAutomations) | ImpressiveLLC | Zigbee pairing, HA config, Node-RED flows |
**GitHub accounts:** `nhsmrekar` (primary dev) · `smrekarfamilia-sudo` (family org) · `ImpressiveLLC` (shared/platform)

---

## Infrastructure Quick-Reference

| Item | Value |
|------|-------|
| Primary server | Lenovo M920q · Ubuntu · Docker Compose at `/home/nate/FaceoftheCabin/cabin-orchestration-platform/infra/` (`/storage/containers/compose/cabin/` is a stale path from an earlier setup — confirmed unused, only a leftover `.env` and old `CabinAutomations` copy remain there) |
| Tailscale IP | `100.77.44.113` |
| Public access | Cloudflare Tunnel → `unicornpingpong.com` — live, all three subdomains |
| Private mesh | Tailscale — cabin ↔ home, admin/SSH |
| Domains | `unicornpingpong.com` — platform (hub/cabin/api subdomains) · `impressive.llc` — org domain |
| Domain registrar | Porkbun |
| DNS | Cloudflare free tier |
| Monitoring | Uptime Kuma + Homepage on M920q |
| Google OAuth owner | `nhsmrekar@gmail.com` |
| Calendar / Photos | `smrekarfamilia@gmail.com` |
| Zigbee coordinator | `/dev/ttyACM0` · adapter: ember · 14 devices paired |
| Parenting schedule | Versioned rules — current: 50/50 split since July 27 2026 (see `ROADMAP.md`'s Environment & Credentials Reference for the full detail; `docs/ontology.yaml`'s `parenting_schedule_rule_version` is the source of truth) |
| Secrets | Ansible Vault (`ansible/group_vars/*/vault.yml`) — see `ansible/README.md`'s Secrets section, not hand-edited `.env` |

---

## Documentation Index

| Document | Location | Audience | Contents |
|----------|----------|----------|----------|
| **User Guide** | [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md) | Family members, guests | Day-to-day usage: identity, profiles, notepad, chores, camera privacy |
| **Maintenance & Ops** | [`docs/MAINTENANCE.md`](docs/MAINTENANCE.md) | Operators, developers | Deployment, secrets, known issues, incident response |
| **Replication Guide** | [`docs/REPLICATION.md`](docs/REPLICATION.md) | New-instance implementers | Standing up an independent instance from scratch |
| **Platform Roadmap** | [`ROADMAP.md`](ROADMAP.md) | Technical, product, strategic | Strategic brief, architecture, ontology design, priority task list |
| **Product Vision** | [`docs/PRODUCT_VISION.md`](docs/PRODUCT_VISION.md) | Family, stakeholders | Value proposition, competitive positioning, honest maturity — implementation-agnostic on purpose |
| **Product Notes** | [`docs/PRODUCT_NOTES.md`](docs/PRODUCT_NOTES.md) | Product, design | Dated design decisions, persona research, architecture reviews |
| **Ontology Contract** | [`docs/ontology.yaml`](docs/ontology.yaml) | Technical | Canonical entity definitions, device registry, naming contract |
| **Platform README** | [`cabin-orchestration-platform/README.md`](cabin-orchestration-platform/README.md) | Technical | Backend/UI architecture, Docker Compose reference |
| **QA / Testing** | [`docs/QA.md`](docs/QA.md) | Technical | Per-feature test coverage, automated + manual checklists |
| **Definition of Done** | [`docs/DEFINITION_OF_DONE.md`](docs/DEFINITION_OF_DONE.md) | Internal (session process) | Per-session exit checklist — not a product or architecture doc |
| **Replicating This Template** | [`docs/REPLICATION.md`](docs/REPLICATION.md) | Standing up a fully independent instance — own accounts, domain, host, repo |

---

## Quick Start — M920q Deploy

```bash
# SSH in via Tailscale
ssh nate@100.77.44.113

# Deploy / rebuild a single service
cd ~/FaceoftheCabin && git pull
docker compose \
  -f cabin-orchestration-platform/infra/docker-compose.yml \
  -f cabin-orchestration-platform/infra/docker-compose.m920q.yml \
  up -d --build <service-name>
```

Services: `family-hub` · `cabin-ui` · `cabin-backend` · `cabin-grafana` · `postgres` · `kafka`
