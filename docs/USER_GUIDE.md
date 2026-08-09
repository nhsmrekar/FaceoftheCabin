# Family Hub & FaceOfTheCabin — User Guide

*Audience: family members and guests using the platform day to day —
on the wall-mounted kiosk, a phone, or a laptop. For technical
operation, see [`MAINTENANCE.md`](MAINTENANCE.md). For standing up your
own instance, see [`REPLICATION.md`](REPLICATION.md).*

---

## What this is

**Family Hub** (`hub.unicornpingpong.com`) is an always-on family
dashboard: calendar, parenting schedule, chores, rewards, photos, and a
shared notepad, designed to sit on a wall display the whole family walks
past every day. **FaceOfTheCabin** (`cabin.unicornpingpong.com`) is the
cabin-side companion — camera activity, device status, and (for signed-in
users) live and recorded video.

Both are part of one platform, sharing a common identity model, a common
event bus, and a single [living ontology](ontology.yaml) that defines
every piece of data the platform tracks and why.

---

## Signing in vs. "Who am I?" — two different things

This is the one concept worth understanding before anything else, because
it trips people up:

- **Google Sign-In** authorizes your *device* to sync data — post notes,
  mark chores done, see camera activity that only signed-in users can
  see. It does not say *which family member* is doing something.
- **"Who am I?"** (the actor picker, reached from Dashboard → Overview) is
  a separate, lightweight choice of which family member the *current
  device* is speaking as right now. It's not a login — anyone can pick
  anyone, the same way anyone in the house can write on a paper note on
  the fridge. It exists so a note, a completed chore, or a schedule
  change gets attributed to the right person, not to whoever's Google
  account happens to be signed in on the shared kiosk.

Your "Who am I?" choice is **local to this device and expires after 3
minutes of inactivity** (or immediately if you switch to someone else).
It never syncs to other devices — a phone picking "Sam" doesn't make the
kitchen kiosk start speaking as Sam too. What *does* sync across every
device is the underlying **family profile directory** (see below) — the
list of who exists, their name, avatar, and role.

---

## Family Profiles

Every family member, pet, and guest has a **profile**: display name,
role, avatar, and a few role-specific fields. Profiles are managed from
Dashboard → Family (add / edit / delete), and sync across every signed-in
device via `cabin-backend`'s `/api/profiles`.

| Role | Who | Notes |
|---|---|---|
| **Parent** | Adults in the household | Full access, can manage profiles |
| **Kid** | Children | Age-gated chores, reward eligibility |
| **Pet** | Family pets | Display-only — birthdays, no chores or acting identity |
| **Friends & Family** | Grandparents, aunts/uncles, cousins, regular guests | Full acting/note parity with kids and parents; has a "relationship" field (Grandma, Grandpa, Aunt, Uncle, Cousin, Friend, Other) |

Adding a Friends & Family profile on one device makes it available on
every other signed-in device within a normal sync cycle (about 20
seconds) — this used to be a real, one-way-only limitation before profile
sync was built; it isn't anymore.

**Avatars**: a searchable catalog of ~90 emoji (people, animals, fantasy
characters, dinosaurs, space, sports, and more) — search by keyword when
picking one.

---

## The Family Notepad

A shared, fridge-note-style message board, always one tap away from the
right edge of the screen.

- **Sending a note requires picking "Who am I?" first** — the composer
  is disabled with a "Who's leaving this note?" prompt until you do.
  This is deliberate: a note with no clear author isn't useful to
  anyone reading it later. If your 3-minute window expires mid-typing,
  your draft is preserved — you'll just be asked to confirm who's
  sending it before it actually goes out.
- Notes sync across every signed-in device (Postgres-backed,
  `cabin-backend`'s `/api/notes`). If you're signed out, or offline, your
  note is still saved locally and will show up for you — it just won't
  reach anyone else's device until you're back in sync.
- The panel auto-opens when a new note arrives, and force-collapses again
  24 hours later so it doesn't sit open indefinitely.
- History keeps the most recent 50 notes; the panel itself shows the most
  recent 12 at a glance.

---

## Chores & Rewards

- Each kid's available chores are filtered by age (`minAge` per chore) —
  younger kids see a shorter list, older kids see the full list.
- Completion is tracked per day, per kid, and syncs across every
  signed-in device.
- **Reward eligibility**: a kid qualifies for the week's reward once
  they've completed **3 or more chores on at least 4 of their home
  days** in the current 2-week cycle. This threshold is intentionally
  forgiving — it doesn't require a perfect streak, just consistent
  effort across most of the days they're actually at the cabin.

---

## Parenting Schedule & Calendar

The parenting schedule is **versioned** — custody rules can change over
time (e.g. moving from one anchor pattern to a different split), and the
platform always computes historical dates using whichever rule was
actually in effect on that date, not today's rule applied retroactively.
Holidays and special observations can override the default pattern for
specific dates. See `docs/ontology.yaml`'s `parenting_schedule_rule_version`
entity for the exact, current rule set in force.

---

## Alerts & Notifications

Not every sensor reading deserves a phone notification, and not every one
that gets logged is worth interrupting you for. The platform sorts what
happens at the cabin into three levels:

| Level | Examples | What happens |
|---|---|---|
| **Critical** | Water leak detected, smoke/CO alarm | Pushes a notification straight to your phone |
| **Warning** | A door left open, a sensor's battery running low, tamper detected | Logged, visible in the system's event history — but doesn't page you |
| **Informational** | Motion, a door opening and closing normally, routine device activity | Logged only — this is the platform quietly keeping a record, not something that needs your attention |

**Why routine activity doesn't page you:** if every door open or motion
blip sent a push notification, you'd tune the notifications out within a
day and miss the ones that actually matter. Only things that genuinely
need a response — an active leak, smoke or CO — interrupt you. Everything
else is still recorded (see "Who to ask" below for how to review it), just
not pushed at you in the moment.

**Getting the phone notifications**: critical alerts arrive via
[ntfy](https://ntfy.sh) — a free notification app, not a platform account.
Install it (Android/iOS), and whoever manages your platform gives you a
private subscription topic to add in the app. There's no sign-up and
nothing to configure beyond that one topic.

**What's still evolving**: as of this writing, warning- and info-level
activity is recorded but doesn't yet have its own dashboard view inside
Family Hub or FaceOfTheCabin — today it lives in the raw event history
your platform operator can query. A proper in-app view for this is on the
roadmap; this guide will be updated when it ships rather than describing
something that isn't there yet.

---

## Camera Activity — what you see, and what it means for privacy

Family Hub's Overview page shows a **live, public, no-sign-in-required**
camera-activity widget — at a glance, "is anyone at the cabin right now."
This is genuinely useful, but it's unavoidably also a presence/absence
indicator: the same information that tells you someone's home also tells
anyone glancing at the screen that no one's home right now.

**You control how much it reveals** — Settings → Cabin Camera Activity:

| Level | What's shown |
|---|---|
| **Full detail** (default) | Camera name, what was detected, and when |
| **Activity only** | Just "recent activity: yes/no" — no camera name, no object, no precise time |
| **Off** | Widget hidden entirely |

If this display sits somewhere more people can casually glance at than
just your own family, turn it down. The same warning is shown right next
to the widget, linking straight to this setting.

**This widget is metadata only** — camera name, detected object type,
timestamp. It never shows an image or video frame. Actual video is a
separate, more sensitive surface — see below.

### Real video — cabin-ui's Camera Events panel (signed in required)

For actual snapshots, event clips, and live camera viewing,
`cabin.unicornpingpong.com`'s Camera Events panel is where that lives —
**An admitted platform session is required**, gated server-side (not just
hidden in the UI). A valid Family Hub session is handed over automatically;
direct cabin navigation offers Google sign-in when needed. This is
intentionally a bigger step up in access than the public
metadata widget above: an image or video frame is categorically more
sensitive than "motion detected at 3:14pm."

What's there:
- **Live view** — a "watch live" button per camera currently known to the
  system.
- **Event clips** — 15 seconds before to 60 seconds after each detected
  event, retained 10 days.
- **Continuous recording** — the full camera feed, retained on a rolling
  basis (currently 5 days, deliberately conservative — see
  `MAINTENANCE.md` for the storage reasoning).

If you ever add another signed-in user to `cabin.unicornpingpong.com`,
they get this same video access — there's currently no separate,
finer-grained permission tier for video versus the rest of the app.

---

## Themes

Family Hub ships with 8 visual themes (Settings → Theme): Modern, LCARS,
Monolith, Retro-CRT, Mad Science, 80s Neon, Pac-Man, and Deep Space.
Purely cosmetic — pick whatever the household likes best; every feature
works identically regardless of theme. `cabin-ui` has its own, separate
7-theme set, since it's a different application with its own visual
identity.

---

## Troubleshooting

**"I made a change but it's not showing up on another device."** Sync
runs on a roughly-20-second interval while a device is signed in and
online. If it's been longer than that and still hasn't appeared, check
that the other device is actually signed in — signed-out devices fall
back to local-only storage and won't receive or send updates.

**"The camera panel shows nothing / a broken image."** Camera media uses the
same platform session as the rest of `cabin.unicornpingpong.com`; it should
never request a separate camera sign-in. Family Hub hands that session over,
while direct cabin access offers its own Google sign-in only if the handoff is
missing, expired, or rejected. If the app is signed in and the panel is still
empty, there may not have been a recent qualifying event to display — check
with whoever manages the platform.

**"A family member I added isn't showing up on my phone."** Confirm both
devices are signed in. New profiles push to the shared directory
immediately on save, but a device that's signed out or offline won't see
it until it's back online and re-syncs.

---

## Who to ask

For anything below the day-to-day feature level — adding a new camera,
rotating a password, changing who has admin access, standing up a new
instance for another family — that's covered in
[`MAINTENANCE.md`](MAINTENANCE.md) and [`REPLICATION.md`](REPLICATION.md),
not this guide.
