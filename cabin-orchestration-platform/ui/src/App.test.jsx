import React from "react";
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { render, screen, fireEvent, cleanup, waitFor } from "@testing-library/react";
import { isCameraEvent, mergeHubLocations, buildCameraEventsUrl, buildCameraLiveUrl, isLocationDeployed, formatPresenceSignals, formatArmedTitle, cameraHealthLabel, allLocationsLabel, checkinStatusLabel, recoveryActionAvailable, recoveryPowerNotice, alertEligibleOfflineCount, resolvePostAuthPanel, AuthGate, App, AppContext, DeviceRecoveryAction, FamilyHubPanel, FamilyConfigPanel, RulesPanel } from "./App.jsx";
import { ThemeProvider } from "./ThemeProvider.jsx";

// Covers the actual reported bug this session ("Camera Events" showing
// device logs instead of camera activity) -- see
// docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md §4a and
// isCameraEvent's own comment in App.jsx.
describe("isCameraEvent", () => {
  it("accepts DETECTION_* events", () => {
    expect(isCameraEvent({ eventType: "DETECTION_NEW" })).toBe(true);
    expect(isCameraEvent({ eventType: "DETECTION_UPDATE" })).toBe(true);
    expect(isCameraEvent({ eventType: "DETECTION_END" })).toBe(true);
  });

  it("accepts MOTION_* events", () => {
    expect(isCameraEvent({ eventType: "MOTION_ON" })).toBe(true);
    expect(isCameraEvent({ eventType: "MOTION_OFF" })).toBe(true);
  });

  it("rejects non-camera device event types -- this is the actual bug being fixed", () => {
    expect(isCameraEvent({ eventType: "STATE_CHANGE" })).toBe(false);
    expect(isCameraEvent({ eventType: "ALARM" })).toBe(false);
    expect(isCameraEvent({ eventType: "LEAK_DETECTED" })).toBe(false);
  });

  it("does not false-positive on a type that merely contains the camera prefix mid-string", () => {
    expect(isCameraEvent({ eventType: "NOT_A_MOTION_EVENT" })).toBe(false);
  });

  it("handles a missing/undefined eventType without throwing", () => {
    expect(isCameraEvent({})).toBe(false);
    expect(isCameraEvent({ eventType: undefined })).toBe(false);
  });
});

// Covers Phase 7 §4c -- CameraEventsPanel's real server-side pagination/
// filtering (replacing the old client-side isCameraEvent filter + hard
// 30-event cap). See EventController's own comment and
// docs/ontology.yaml's cabin_camera_event entry.
describe("buildCameraEventsUrl", () => {
  it("requests only camera event types (DETECTION_*/MOTION_*)", () => {
    const url = buildCameraEventsUrl("http://cabin-hub:8090", 0);
    expect(url).toContain("eventTypePrefix=DETECTION_,MOTION_");
  });

  it("requests the first page at offset 0", () => {
    const url = buildCameraEventsUrl("http://cabin-hub:8090", 0);
    expect(url).toContain("offset=0");
    expect(url).toContain("limit=30");
  });

  it("requests subsequent pages at the given offset", () => {
    const url = buildCameraEventsUrl("http://cabin-hub:8090", 30);
    expect(url).toContain("offset=30");
  });

  it("targets the given location's apiBase", () => {
    const url = buildCameraEventsUrl("http://home-hub:8080", 0);
    expect(url.startsWith("http://home-hub:8080/api/events?")).toBe(true);
  });
});

// Covers Phase 7 §1b -- see docs/ontology.yaml's hub_location entity and
// mergeHubLocations' own comment in App.jsx.
describe("mergeHubLocations", () => {
  const cabin = { id: "cabin", label: "Cabin", apiBase: "http://cabin-hub:8090", grafanaUrl: "http://cabin-hub:3002" };
  const home = { id: "home", label: "Home", apiBase: "http://home-hub:8080" };
  const current = { cabin, home };

  it("adds an entirely new location from the API response", () => {
    const result = mergeHubLocations(current, [{ id: "lakehouse", label: "Lake House", apiBase: "http://lakehouse-hub:8080" }]);
    expect(Object.keys(result)).toEqual(["cabin", "home", "lakehouse"]);
    expect(result.lakehouse.label).toBe("Lake House");
  });

  it("overrides fields on an existing location the API returns a value for", () => {
    const result = mergeHubLocations(current, [{ id: "cabin", label: "Cabin (renamed)" }]);
    expect(result.cabin.label).toBe("Cabin (renamed)");
  });

  it("preserves existing fields the API response doesn't include", () => {
    const result = mergeHubLocations(current, [{ id: "cabin", label: "Cabin (renamed)" }]);
    // apiBase/grafanaUrl weren't in the API row -- must not be wiped to null
    expect(result.cabin.apiBase).toBe("http://cabin-hub:8090");
    expect(result.cabin.grafanaUrl).toBe("http://cabin-hub:3002");
  });

  it("does not mutate the object passed in as `current`", () => {
    mergeHubLocations(current, [{ id: "cabin", label: "Cabin (renamed)" }]);
    expect(current.cabin.label).toBe("Cabin"); // unchanged
  });

  it("skips rows with no id rather than throwing", () => {
    const result = mergeHubLocations(current, [{ label: "No id here" }, null, undefined]);
    expect(Object.keys(result)).toEqual(["cabin", "home"]);
  });

  it("handles an empty or missing API response", () => {
    expect(mergeHubLocations(current, [])).toEqual(current);
    expect(mergeHubLocations(current, undefined)).toEqual(current);
  });
});

// Covers the 2026-08-07 finding: the "API offline" badge required EVERY
// attempted location fetch to succeed, including home-hub's -- which is
// always going to fail until home-hub is actually deployed, permanently
// showing "offline" while viewing Home/Both regardless of cabin's real
// health. isLocationDeployed() is refreshDevices' signal for which
// locations' failures should actually count. See isLocationDeployed's
// own comment in App.jsx.
describe("isLocationDeployed", () => {
  it("treats the undeployed Docker-internal placeholder as not deployed", () => {
    expect(isLocationDeployed({ id: "home", apiBase: "http://home-hub:8080" })).toBe(false);
  });

  it("treats a real hostname/IP apiBase as deployed", () => {
    expect(isLocationDeployed({ id: "cabin", apiBase: "https://api.unicornpingpong.com" })).toBe(true);
    expect(isLocationDeployed({ id: "cabin", apiBase: "http://100.77.44.113:8090" })).toBe(true);
  });

  it("does not false-positive on a different location's placeholder-shaped host", () => {
    // "home"'s own placeholder host must not accidentally clear "cabin"'s check or vice versa
    expect(isLocationDeployed({ id: "cabin", apiBase: "http://home-hub:8080" })).toBe(true);
  });

  it("handles a missing apiBase without throwing", () => {
    expect(isLocationDeployed({ id: "home", apiBase: null })).toBe(false);
    expect(isLocationDeployed({ id: "home" })).toBe(false);
    expect(isLocationDeployed(null)).toBe(false);
  });
});

// Covers Phase 7 §3 -- place-card reordering. Renders the real
// FamilyHubPanel (not a reimplementation), reusing the same
// useDraggableOrder hook and localStorage key ("order.places") the app
// itself uses, so this exercises the actual integration, not just a
// pure helper. See docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md
// §3.
describe("FamilyHubPanel reordering", () => {
  const ORDER_KEY = "order.places";

  beforeEach(() => localStorage.removeItem(ORDER_KEY));
  afterEach(() => { cleanup(); localStorage.removeItem(ORDER_KEY); });

  function renderPanel() {
    return render(
      <ThemeProvider>
        <AppContext.Provider value={{ devices: [] }}>
          <FamilyHubPanel />
        </AppContext.Provider>
      </ThemeProvider>
    );
  }

  it("renders a card per location with no saved order", () => {
    renderPanel();
    expect(screen.getByText("Cabin")).toBeTruthy();
    expect(screen.getByText("Home")).toBeTruthy();
  });

  it("respects a previously-saved order from localStorage on initial render", () => {
    localStorage.setItem(ORDER_KEY, JSON.stringify(["home", "cabin"]));
    renderPanel();
    const labels = screen.getAllByText(/^(Cabin|Home)$/).map(el => el.textContent);
    expect(labels).toEqual(["Home", "Cabin"]);
  });

  it("toggling Reorder makes cards draggable and shows a drag handle", () => {
    const { container } = renderPanel();
    expect(container.querySelectorAll(".family-hub-drag-handle")).toHaveLength(0);

    fireEvent.click(screen.getByText("Reorder"));

    const wraps = container.querySelectorAll(".family-hub-card-wrap");
    expect(wraps.length).toBeGreaterThan(0);
    wraps.forEach(w => expect(w.getAttribute("draggable")).toBe("true"));
    expect(container.querySelectorAll(".family-hub-drag-handle")).toHaveLength(wraps.length);

    fireEvent.click(screen.getByText("Done"));
    expect(container.querySelectorAll(".family-hub-drag-handle")).toHaveLength(0);
  });

  it("a drag-and-drop persists the new order to localStorage", () => {
    const { container } = renderPanel();
    fireEvent.click(screen.getByText("Reorder"));

    const [first, second] = container.querySelectorAll(".family-hub-card-wrap");
    const dataTransfer = { effectAllowed: null };
    fireEvent.dragStart(first, { dataTransfer });
    fireEvent.dragOver(second, { dataTransfer });
    fireEvent.drop(second, { dataTransfer });

    expect(JSON.parse(localStorage.getItem(ORDER_KEY))).toEqual(["home", "cabin"]);
  });
});

// Covers the 2026-08-08 finding: hub_location's full backend CRUD
// (Phase 7 §1b) has existed since that work landed, but nothing in the
// frontend ever called POST /api/locations -- there was no way to add a
// place through the UI. User's own framing: "I will be adding another
// location" -- this needed to actually exist before that's possible.
describe("FamilyHubPanel — Add Place", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  function renderPanel() {
    return render(
      <ThemeProvider>
        <AppContext.Provider value={{ devices: [] }}>
          <FamilyHubPanel />
        </AppContext.Provider>
      </ThemeProvider>
    );
  }

  it("Add Place reveals a form requiring at least ID and Display Name", async () => {
    renderPanel();
    fireEvent.click(screen.getByText("Add Place"));

    expect(screen.getByPlaceholderText("lakehouse")).toBeTruthy();

    fireEvent.click(screen.getByText("Create Place"));

    await waitFor(() => expect(screen.getByText(/ID and Display Name are required/)).toBeTruthy());
  });

  it("submitting a valid form POSTs only the filled-in fields", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) });
    vi.stubGlobal("fetch", fetchMock);
    // onCreated() calls window.location.reload() (a real full reload is
    // the simplest way to pick up the new location -- see App.jsx's own
    // comment). jsdom logs a harmless "Not implemented: navigation"
    // stderr line for this -- it's not a failure, jsdom's Location.reload
    // isn't configurable enough in this version to stub cleanly, and the
    // assertions below don't depend on the reload actually doing anything.
    renderPanel();
    fireEvent.click(screen.getByText("Add Place"));

    fireEvent.change(screen.getByPlaceholderText("lakehouse"), { target: { value: "lakehouse" } });
    fireEvent.change(screen.getByPlaceholderText("Lake House"), { target: { value: "Lake House" } });
    fireEvent.click(screen.getByText("Create Place"));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url, opts] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/locations$/);
    expect(opts.method).toBe("POST");
    const body = JSON.parse(opts.body);
    expect(body).toEqual({ id: "lakehouse", label: "Lake House" });
  });

  it("shows the server's error message rather than swallowing a failed create", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 400, text: async () => "id is required" }));
    renderPanel();
    fireEvent.click(screen.getByText("Add Place"));
    fireEvent.change(screen.getByPlaceholderText("lakehouse"), { target: { value: "x" } });
    fireEvent.change(screen.getByPlaceholderText("Lake House"), { target: { value: "X" } });
    fireEvent.click(screen.getByText("Create Place"));

    await waitFor(() => expect(screen.getByText("id is required")).toBeTruthy());
  });
});

describe("allLocationsLabel", () => {
  it("reads as Both for exactly two locations", () => {
    expect(allLocationsLabel(2)).toBe("Both");
  });

  it("reads as All once a third location exists", () => {
    expect(allLocationsLabel(3)).toBe("All");
    expect(allLocationsLabel(5)).toBe("All");
  });

  it("still reads as Both for a single-location instance", () => {
    expect(allLocationsLabel(1)).toBe("Both");
  });
});

// Covers the 2026-08-08 request: "offline" was firing the instant a device
// missed one poll interval, which is misleading (not-yet-reported vs.
// actually-unreachable). checkinStatusLabel is the pure function that maps
// GET /api/devices/checkin-status onto the badge shown on device cards.
describe("checkinStatusLabel", () => {
  it("never overrides an ALARM or CRITICAL state, regardless of checkin status", () => {
    expect(checkinStatusLabel("ALARM", "MISSED")).toBeNull();
    expect(checkinStatusLabel("CRITICAL", "LATE")).toBeNull();
  });

  it("shows a grace-tier label for LATE without implying the device is broken", () => {
    expect(checkinStatusLabel("OFFLINE", "LATE")).toEqual({ text: "Late checking in", cls: "state-late" });
  });

  it("shows a distinct label for MISSED", () => {
    expect(checkinStatusLabel("OFFLINE", "MISSED")).toEqual({ text: "Not responding", cls: "state-offline" });
  });

  it("does not turn an unknown lifecycle value into a liveness label", () => {
    expect(checkinStatusLabel("UNKNOWN", "NOT_CONFIGURED")).toBeNull();
  });

  it("falls through to the raw state for ON_SCHEDULE or missing data", () => {
    expect(checkinStatusLabel("ONLINE", "ON_SCHEDULE")).toBeNull();
    expect(checkinStatusLabel("ONLINE", undefined)).toBeNull();
  });
});

describe("off-schedule device recovery actions", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("only enables Check now for LATE or MISSED devices", () => {
    expect(recoveryActionAvailable("LATE")).toBe(true);
    expect(recoveryActionAvailable("MISSED")).toBe(true);
    expect(recoveryActionAvailable("ON_SCHEDULE")).toBe(false);
    expect(recoveryActionAvailable(undefined)).toBe(false);
  });

  it("uses battery telemetry as positive evidence but does not infer mains power from missing data", () => {
    expect(recoveryPowerNotice({ attributes: { battery: 72 } })).toMatch(/^This device reports a battery\./);
    expect(recoveryPowerNotice({ attributes: {} })).toMatch(/^If this device is battery-powered,/);
  });

  it("runs the authenticated check-now action and shows the backend receipt", async () => {
    const authedFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        deviceId: "z2m-door/side",
        action: "CHECK_NOW",
        outcome: "REACHABLE",
        previousStatus: "LATE",
        checkinStatus: "ON_SCHEDULE",
        checkedAt: "2026-08-09T19:00:00Z",
        message: "The Zigbee availability source reports this device online.",
        receiptId: "abc12345-rest-of-receipt",
      }),
    });
    const refreshDevices = vi.fn();
    const onChecked = vi.fn();
    const device = {
      deviceId: "z2m-door/side",
      location: "cabin",
      attributes: { battery: 72 },
    };

    render(
      <AppContext.Provider value={{ auth: { authedFetch }, refreshDevices }}>
        <DeviceRecoveryAction device={device} checkinStatus="LATE" onChecked={onChecked} />
      </AppContext.Provider>
    );

    expect(screen.getByText(/checking now can use additional battery/i)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /check now/i }));

    await waitFor(() => expect(authedFetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/devices/z2m-door%2Fside/check-now"),
      { method: "POST" }
    ));
    expect((await screen.findByRole("status")).textContent).toMatch(/reports this device online/i);
    expect(screen.getByText("Receipt abc12345")).toBeTruthy();
    expect(onChecked).toHaveBeenCalledOnce();
    expect(refreshDevices).toHaveBeenCalledOnce();
    expect(screen.queryByRole("button", { name: /check now/i })).toBeNull();
  });

  it("renders no recovery control for an on-schedule device", () => {
    const { container } = render(
      <AppContext.Provider value={{ auth: { authedFetch: vi.fn() } }}>
        <DeviceRecoveryAction device={{ deviceId: "ok", attributes: {} }} checkinStatus="ON_SCHEDULE" />
      </AppContext.Provider>
    );
    expect(container.innerHTML).toBe("");
  });
});

describe("Item 2 authentication continuity", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("defaults every authenticated entry to My Places", () => {
    expect(resolvePostAuthPanel("")).toBe("FAMILY_HUB");
    expect(resolvePostAuthPanel("?panel=unknown")).toBe("FAMILY_HUB");
  });

  it("preserves an explicit valid deep-link intent through authentication", () => {
    expect(resolvePostAuthPanel("?panel=CAMERA_EVENTS")).toBe("CAMERA_EVENTS");
  });

  it("never puts a Google or platform credential in a camera URL", () => {
    const url = buildCameraLiveUrl("https://api.example.test", "front door");
    expect(url).toBe("https://api.example.test/api/camera/front%20door/live");
    expect(url).not.toContain("token");
    expect(url).not.toContain("?");
  });

  it("shows only the handoff check while inherited-session validation is pending", () => {
    render(<ThemeProvider><AuthGate auth={{ checking: true }} /></ThemeProvider>);
    expect(screen.getByText(/Checking for your Family Hub session/)).toBeTruthy();
    expect(screen.queryByText("Sign in with Google")).toBeFalsy();
    expect(screen.queryByText("My Places")).toBeFalsy();
  });

  it("does not mount or load the application data tree before authentication", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      status: 401,
      ok: false,
    });

    render(<ThemeProvider><App /></ThemeProvider>);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    expect(String(fetchMock.mock.calls[0][0])).toMatch(/\/api\/auth\/session$/);
    expect(screen.queryByText("My Places")).toBeFalsy();
    expect(screen.queryByText(/devices$/)).toBeFalsy();
  });
});

describe("alertEligibleOfflineCount", () => {
  it("uses the ontology-governed configured-device count when available", () => {
    expect(alertEligibleOfflineCount({ offline: 5, alertEligibleOffline: 0 })).toBe(0);
    expect(alertEligibleOfflineCount({ offline: 5, alertEligibleOffline: 2 })).toBe(2);
  });

  it("falls back to the legacy offline total during a mixed-version rollout", () => {
    expect(alertEligibleOfflineCount({ offline: 3 })).toBe(3);
    expect(alertEligibleOfflineCount(null)).toBe(0);
  });
});

// Covers the 2026-08-08 request: "I only see one node red... same context
// shift behavior for all locations" — Rules & Alerts should split per
// location in "Both" mode the same way Monitoring already does, and a
// location without its own configured Node-RED should say so rather than
// silently show Cabin's flows as if they were its own.
describe("RulesPanel — per-location Node-RED", () => {
  afterEach(cleanup);

  function renderWith(activeLocation) {
    return render(
      <AppContext.Provider value={{ activeLocation }}>
        <RulesPanel />
      </AppContext.Provider>
    );
  }

  it("shows only Cabin's flows, unlabeled as a fallback, when Cabin is the active location", () => {
    renderWith("cabin");
    expect(screen.getByTitle("Node-RED — Cabin")).toBeTruthy();
    expect(screen.queryByTitle("Node-RED — Home")).toBeNull();
    expect(screen.queryByText(/doesn't have its own Node-RED/)).toBeNull();
  });

  it("shows Home's section with a fallback hint when Home has no configured instance of its own", () => {
    renderWith("home");
    expect(screen.getByTitle("Node-RED — Home")).toBeTruthy();
    expect(screen.getByText(/Home doesn't have its own Node-RED instance configured yet/)).toBeTruthy();
  });

  it("splits into one section per location in Both mode, never flagging Cabin as a fallback", () => {
    renderWith("both");
    expect(screen.getByTitle("Node-RED — Cabin")).toBeTruthy();
    expect(screen.getByTitle("Node-RED — Home")).toBeTruthy();
    expect(screen.getAllByText(/doesn't have its own Node-RED instance configured yet/)).toHaveLength(1);
  });
});

// Covers the 2026-08-08 request: drop the vestigial "Family" wording from
// this panel (it configures the whole instance, not just family
// settings), and make the Google Account / Platform / Remote Access
// cards reflect real, dynamic state instead of hardcoded JSX text. See
// isLocationDeployed's sibling entity instance_template_config in
// docs/ontology.yaml for the backend side of this.
describe("FamilyConfigPanel", () => {
  afterEach(cleanup);

  function renderPanel({ auth, config = {} } = {}) {
    return render(
      <AppContext.Provider value={{ config, locationCfg: LOCATIONS_CABIN }}>
        <FamilyConfigPanel auth={auth} />
      </AppContext.Provider>
    );
  }
  const LOCATIONS_CABIN = { haUrl: "http://cabin-hub:8123" };

  it("no longer says Family anywhere in the header", () => {
    renderPanel();
    expect(screen.getByText("Configuration")).toBeTruthy();
    expect(screen.queryByText(/Family Config/i)).toBeFalsy();
  });

  it("shows the signed-in Google account and a switch-account control", () => {
    const signIn = () => {};
    renderPanel({ auth: { userEmail: "nate@example.com", signIn } });
    expect(screen.getByText(/Signed in as nate@example.com/)).toBeTruthy();
    expect(screen.getByText("Switch Google Account")).toBeTruthy();
  });

  it("offers a sign-in control when no Google account is signed in", () => {
    renderPanel({ auth: { userEmail: null, signIn: () => {} } });
    expect(screen.getByText(/No active platform session/)).toBeTruthy();
    expect(screen.getByText("Sign in with Google")).toBeTruthy();
  });

  it("displays the configured platform and remote access from real backend config", () => {
    renderPanel({ config: { platformName: "Test Platform", platform: "A test VM", remoteAccess: "Tailscale,WireGuard" } });
    expect(screen.getByText("A test VM")).toBeTruthy();
    expect(screen.getByText("Tailscale, WireGuard")).toBeTruthy();
  });

  it("defaults remote access to Tailscale when config hasn't loaded yet", () => {
    renderPanel({ config: {} });
    expect(screen.getByText("Tailscale")).toBeTruthy();
  });
});

// Covers the 2026-08-08 presence-toggle finding: the map-pin toolbar
// widget read as "your detected location" but was purely manual, with
// nothing real behind it despite driving real security-severity
// decisions (AutomationRuleService, backend). formatPresenceSignals
// builds the live-detection tooltip from real per-person, per-location
// signals -- N people x M locations by design, not Nate-at-cabin-only.
// See PresenceToggle's own comment and PresenceService.java (backend).
describe("formatPresenceSignals", () => {
  it("names who is present and where, for one signal", () => {
    expect(formatPresenceSignals([{ personId: "nate", location: "cabin", present: true }]))
      .toBe("nate at cabin");
  });

  it("names multiple people across different locations", () => {
    expect(formatPresenceSignals([
      { personId: "nate", location: "cabin", present: true },
      { personId: "emma", location: "home", present: true },
    ])).toBe("nate at cabin, emma at home");
  });

  it("excludes people whose signal is currently not-present", () => {
    expect(formatPresenceSignals([
      { personId: "nate", location: "cabin", present: true },
      { personId: "emma", location: "home", present: false },
    ])).toBe("nate at cabin");
  });

  it("reports nobody present without throwing on an empty or all-absent list", () => {
    expect(formatPresenceSignals([])).toBe("No one currently detected present");
    expect(formatPresenceSignals([{ personId: "nate", location: "cabin", present: false }]))
      .toBe("No one currently detected present");
    expect(formatPresenceSignals(undefined)).toBe("No one currently detected present");
  });
});

// Covers the 2026-08-08 armed-state finding: cabin/security/armed_away
// is a real, live, HA-published MQTT signal that cabin-backend never
// subscribed to. formatArmedTitle must never let "no signal yet" read
// as "disarmed" -- those mean very different things to someone looking
// at an ambiguous alert. See SecurityBadge's own comment in App.jsx.
describe("formatArmedTitle", () => {
  it("reports armed with a timestamp", () => {
    const title = formatArmedTitle({ armed: true, lastUpdated: "2026-08-08T04:00:00Z" });
    expect(title).toMatch(/^Armed \(as of /);
  });

  it("reports disarmed with a timestamp", () => {
    const title = formatArmedTitle({ armed: false, lastUpdated: "2026-08-08T04:00:00Z" });
    expect(title).toMatch(/^Disarmed \(as of /);
  });

  it("never reads as disarmed when no signal has ever been received", () => {
    const title = formatArmedTitle(null);
    expect(title).not.toMatch(/^Disarmed/);
    expect(title).not.toMatch(/^Armed/);
    expect(title).toMatch(/no armed\/disarmed signal/i);
  });

  it("handles undefined the same as null without throwing", () => {
    expect(() => formatArmedTitle(undefined)).not.toThrow();
  });
});

// Covers the 2026-08-08 Grafana-iframe replacement: three separate fix
// attempts failed (the real blocker turned out to be a completely
// different bug -- see docs/ontology.yaml's cabin_grafana_public_access),
// so the embed was replaced with native camera-fps tiles sourced
// directly from Prometheus (FrigateMetricsController, backend) plus a
// link out to the full Grafana dashboard. cameraHealthLabel must never
// let "no data yet" (Prometheus unreachable, fps field absent) read as
// "camera confirmed down" -- those mean very different things.
describe("cameraHealthLabel", () => {
  it("reports fps for a healthy camera", () => {
    expect(cameraHealthLabel(5.1)).toEqual({ label: "5.1 fps", className: "camera-health-ok" });
  });

  it("reports no signal for a camera reporting exactly zero fps", () => {
    expect(cameraHealthLabel(0)).toEqual({ label: "No signal", className: "camera-health-down" });
  });

  it("reports unknown rather than down when fps data is simply absent", () => {
    expect(cameraHealthLabel(null)).toEqual({ label: "Unknown", className: "camera-health-unknown" });
    expect(cameraHealthLabel(undefined)).toEqual({ label: "Unknown", className: "camera-health-unknown" });
  });
});
