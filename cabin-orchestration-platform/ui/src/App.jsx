/**
 * Cabin Orchestration Platform — Shell UI
 *
 * Docked/expandable panels:
 *   FAMILY_HUB     — "My Places" location-card grid (Cabin/Home at a glance)
 *   FAMILY_CONFIG  — Instance config: Google account, notification preferences, platform/remote access
 *   DEVICE_MANAGER — Add / edit / remove / activate devices (drag-reorder)
 *   MONITORING     — Live WebSocket telemetry tiles + Grafana embed
 *   RULES_ENGINE   — Node-RED embed + rule CRUD + Kafka topic browser
 *   CAMERA_EVENTS  — Authenticated camera snapshots/clips/live view
 *   OPPORTUNITY_MAP — Tech ID Service findings as actionable, ontology-
 *                     linked "Opportunities" (See/Think/Act)
 *
 * Location switcher: Cabin | Home | Both
 * Both hubs are reachable via Tailscale (cabin-hub / home-hub).
 * Env vars VITE_CABIN_* and VITE_HOME_* override defaults for local dev.
 */

import React, { useEffect, useState, useRef, useCallback, useMemo, createContext, useContext } from "react";
import { createRoot } from "react-dom/client";
import { ThemeProvider, ThemeSwitcher, useTheme } from "./ThemeProvider.jsx";
import {
  Home, Settings, Cpu, Activity, Zap,
  ChevronDown, ChevronUp, Wifi, WifiOff,
  Droplets, Thermometer, Camera, ShieldAlert, Lock, Unlock,
  RefreshCw, Plus, Trash2, ToggleLeft, ToggleRight,
  AlertTriangle, CheckCircle, Circle, ArrowLeft,
  Eye, Edit2, UserPlus, Minus, ExternalLink,
  Radio, Clock, Battery, MapPin, GripVertical, BarChart2,
  Lightbulb, ThumbsUp, ThumbsDown, ShoppingCart, Wrench, Send
} from "lucide-react";
import "./styles.css";

// ─── Temperature unit ──────────────────────────────────────────────────────
function useTempUnit() {
  const [unit, setUnit] = useState(() => localStorage.getItem("tempUnit") || "F");
  const toggle = () => setUnit(u => { const n = u === "F" ? "C" : "F"; localStorage.setItem("tempUnit", n); return n; });
  return [unit, toggle];
}
function fmtTemp(celsius, unit) {
  if (celsius == null) return "—";
  const val = unit === "F" ? (celsius * 9/5 + 32).toFixed(1) : celsius;
  return `${val}°${unit}`;
}

// ─── Location config ───────────────────────────────────────────────────────
// Both hubs exposed via Tailscale MagicDNS. Override per-hub with env vars
// (e.g. VITE_CABIN_API_BASE=http://localhost:8080 for local dev on cabin-hub).
const LOCATIONS = {
  cabin: {
    id: "cabin",
    label: "Cabin",
    apiBase:       import.meta.env.VITE_CABIN_API_BASE       || "http://cabin-hub:8090",
    wsBase:        import.meta.env.VITE_CABIN_WS_BASE        || "ws://cabin-hub:9001",
    grafanaUrl:    import.meta.env.VITE_CABIN_GRAFANA_URL    || "http://cabin-hub:3002",
    noderedUrl:    import.meta.env.VITE_CABIN_NODERED_URL    || "http://cabin-hub:1880",
    haUrl:         import.meta.env.VITE_CABIN_HA_URL         || "http://cabin-hub:8123",
    frigateUrl:    import.meta.env.VITE_CABIN_FRIGATE_URL    || "http://cabin-hub:5000",
    z2mUrl:        import.meta.env.VITE_CABIN_Z2M_URL        || "http://cabin-hub:8080",
    familyHubUrl:  import.meta.env.VITE_CABIN_FAMILY_HUB_URL || null,
  },
  home: {
    id: "home",
    label: "Home",
    apiBase:       import.meta.env.VITE_HOME_API_BASE       || "http://home-hub:8080",
    wsBase:        import.meta.env.VITE_HOME_WS_BASE        || "ws://home-hub:9001",
    grafanaUrl:    import.meta.env.VITE_HOME_GRAFANA_URL    || "http://home-hub:3000",
    noderedUrl:    import.meta.env.VITE_HOME_NODERED_URL    || "http://home-hub:1880",
    haUrl:         import.meta.env.VITE_HOME_HA_URL         || "http://home-hub:8123",
    frigateUrl:    import.meta.env.VITE_HOME_FRIGATE_URL    || "http://home-hub:5000",
    familyHubUrl:  import.meta.env.VITE_HOME_FAMILY_HUB_URL || null,
  },
};

// A location whose apiBase is still the undeployed-placeholder value
// (`{id}-hub:<port>`, a Docker-internal hostname no real browser can
// resolve) has never had its VITE_*_API_BASE build arg set -- i.e. it
// isn't live yet, same signal docker-compose.m920q.yml's build args
// encode. Used so an undeployed location's always-failing fetch doesn't
// drag down the "API offline" badge for a location that IS actually up
// (found 2026-08-07: viewing "Home" or "Both" before home-hub existed
// permanently flipped the badge red regardless of cabin's real health).
export function isLocationDeployed(loc) {
  return !!loc?.apiBase && !new RegExp(`^https?://${loc.id}-hub:`).test(loc.apiBase);
}

// Real Grafana dashboard UIDs that actually exist, by location -- see the
// MonitoringPanel Grafana embed's own comment for why this replaced a
// hardcoded `${location}-overview` UID that was never real. Only "cabin"
// has one today (the Frigate monitoring dashboard, Phase 7 §1a); a
// per-location, ontology-driven dashboard is still open work.
const GRAFANA_DASHBOARD_UID = {
  cabin: "aezbolgn22qdce",
};

// ─── Shared platform session ───────────────────────────────────────────────
// Family Hub establishes this first-party session during its existing Google
// OAuth callback. Direct cabin-ui navigation can establish the same session
// independently. The raw Google access token is used once for that exchange,
// never stored here, and never transported in a camera URL.
export function usePlatformAuth() {
  const clientId = import.meta.env.VITE_CABIN_GOOGLE_CLIENT_ID || "";
  const authApiBase = LOCATIONS.cabin.apiBase;
  const [session, setSession] = useState(null);
  const [checking, setChecking] = useState(true);
  const [sessionExpired, setSessionExpired] = useState(false);
  const [authError, setAuthError] = useState(null);
  const tokenClientRef = useRef(null);

  const clearSession = useCallback(() => setSession(null), []);

  const bootstrap = useCallback(() => {
    setChecking(true);
    setAuthError(null);
    return fetch(`${authApiBase}/api/auth/session`, { credentials: "include" })
      .then(async res => {
        if (res.status === 401) {
          clearSession();
          return;
        }
        if (!res.ok) throw new Error(`Session handoff failed (HTTP ${res.status})`);
        setSession(await res.json());
        setSessionExpired(false);
      })
      .catch(err => {
        clearSession();
        setAuthError(err.message);
      })
      .finally(() => setChecking(false));
  }, [authApiBase, clearSession]);

  useEffect(() => { bootstrap(); }, [bootstrap]);

  useEffect(() => {
    if (!session?.expiresAt) return undefined;
    const remaining = session.expiresAt - Date.now();
    if (remaining <= 0) {
      clearSession();
      setSessionExpired(true);
      return undefined;
    }
    const timer = setTimeout(() => {
      clearSession();
      setSessionExpired(true);
    }, remaining);
    return () => clearTimeout(timer);
  }, [session, clearSession]);

  const ensureTokenClient = useCallback(() => {
    if (tokenClientRef.current || !window.google?.accounts?.oauth2 || !clientId) return tokenClientRef.current;
    tokenClientRef.current = window.google.accounts.oauth2.initTokenClient({
      client_id: clientId,
      scope: "openid email",
      callback: async (resp) => {
        if (resp.error || !resp.access_token) {
          setAuthError(resp.error_description || resp.error || "Google sign-in failed");
          return;
        }
        try {
          const res = await fetch(`${authApiBase}/api/auth/session`, {
            method: "POST",
            credentials: "include",
            headers: {
              Authorization: `Bearer ${resp.access_token}`,
              "X-Cabin-Auth-Source": "DIRECT_CABIN",
            },
          });
          if (!res.ok) throw new Error(`Google credentials were rejected (HTTP ${res.status})`);
          setSession(await res.json());
          setSessionExpired(false);
          setAuthError(null);
        } catch (err) {
          clearSession();
          setAuthError(err.message);
        }
      },
    });
    return tokenClientRef.current;
  }, [authApiBase, clearSession, clientId]);

  const signIn = useCallback(() => {
    setAuthError(null);
    const client = ensureTokenClient();
    if (!client) {
      setAuthError("Google Sign-In is still loading. Try again in a moment.");
      return;
    }
    client.requestAccessToken({ prompt: "select_account" });
  }, [ensureTokenClient]);

  const signOut = useCallback(async () => {
    try {
      await fetch(`${authApiBase}/api/auth/session`, {
        method: "DELETE",
        credentials: "include",
      });
    } catch { /* local fail-closed state still clears below */ }
    setSessionExpired(false);
    setAuthError(null);
    clearSession();
  }, [authApiBase, clearSession]);

  const handleUnauthorized = useCallback(() => {
    clearSession();
    setSessionExpired(true);
  }, [clearSession]);

  const authedFetch = useCallback((url, options = {}) => {
    if (!session) return Promise.reject(new Error("Not signed in"));
    return fetch(url, {
      ...options,
      credentials: "include",
      headers: { ...(options.headers || {}) },
    }).then(res => {
      if (res.status === 401) handleUnauthorized();
      return res;
    });
  }, [session, handleUnauthorized]);

  return useMemo(() => ({
    userEmail: session?.email || null,
    signedIn: !!session,
    checking,
    sessionExpired,
    authError,
    authSource: session?.authSource || null,
    signIn,
    signOut,
    authedFetch,
    configured: !!clientId,
  }), [session, checking, sessionExpired, authError, signIn, signOut, authedFetch, clientId]);
}

// ─── Camera media: authenticated snapshot/clip fetch ──────────────────────
// /api/camera/** requires the inherited/direct platform session (see
// WebConfig.java) — a plain <img src="..."> or <video src="..."> does not
// give us the same explicit expiry handling, so fetch as a blob and hand the component an
// object URL instead. Revokes the previous URL on cleanup/change so this
// doesn't leak memory as someone scrolls through a long event list.
function useAuthedMediaUrl(url, authedFetch) {
  const [objectUrl, setObjectUrl] = useState(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!url) { setObjectUrl(null); return; }
    let cancelled = false;
    let currentUrl = null;
    setError(false);
    authedFetch(url)
      .then(res => { if (!res.ok) throw new Error(res.status); return res.blob(); })
      .then(blob => {
        if (cancelled) return;
        currentUrl = URL.createObjectURL(blob);
        setObjectUrl(currentUrl);
      })
      .catch(() => { if (!cancelled) setError(true); });
    return () => {
      cancelled = true;
      if (currentUrl) URL.revokeObjectURL(currentUrl);
    };
  }, [url, authedFetch]);

  return { objectUrl, error };
}

// DTM (date/time) stamp overlay, rendered directly on the thumbnail image
// itself -- not just as adjacent list text -- so the image still carries
// its own timestamp if viewed or shared out of that list context. Uses
// CabinEvent.timestamp, already returned by /api/events; no new data or
// backend change needed. If Frigate's own snapshot already burns in a
// timestamp (unverified against the live M920q as of this session -- see
// docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md §4b), this
// overlay would be redundant with that and worth dropping once confirmed.
function CameraEventThumbnail({ apiBase, authedFetch, frigateEventId, timestamp }) {
  const { objectUrl, error } = useAuthedMediaUrl(
    frigateEventId ? `${apiBase}/api/camera/events/${frigateEventId}/snapshot` : null,
    authedFetch
  );
  if (!frigateEventId || error) {
    return <div className="camera-event-thumb camera-event-thumb-empty"><Camera size={18} /></div>;
  }
  if (!objectUrl) {
    return <div className="camera-event-thumb camera-event-thumb-loading" />;
  }
  return (
    <div className="camera-event-thumb-wrap">
      <img className="camera-event-thumb" src={objectUrl} alt="" />
      {timestamp && <span className="camera-event-thumb-dtm">{new Date(timestamp).toLocaleString()}</span>}
    </div>
  );
}

function CameraEventClip({ apiBase, authedFetch, frigateEventId }) {
  const { objectUrl, error } = useAuthedMediaUrl(
    `${apiBase}/api/camera/events/${frigateEventId}/clip`,
    authedFetch
  );
  if (error) return <p className="config-desc">Clip not available for this event.</p>;
  if (!objectUrl) return <p className="config-desc">Loading clip…</p>;
  return <video className="camera-clip-player" src={objectUrl} controls autoPlay muted />;
}

// Live view uses a plain <img> against Frigate's MJPEG multipart stream.
// The browser sends the HttpOnly platform-session cookie to the API, so no
// Google token or platform credential appears in the media URL.
//
// Found 2026-08-03 (external review): a camera whose Frigate stream is
// down still produces a "completed" <img> load event with
// naturalWidth/naturalHeight of 0 — no error event fires, so the old
// version rendered an empty box with no explanation and the "Stop
// {camera}" button stayed up as if it were working. Fixed with an
// explicit status state: onLoad checks natural dimensions (zero-size
// "success" is treated as a failure, not a success), onError catches a
// hard failure, and a bounded timeout catches a request that never
// resolves either way.
export function buildCameraLiveUrl(apiBase, cameraName) {
  return `${apiBase}/api/camera/${encodeURIComponent(cameraName)}/live`;
}

function CameraLiveView({ apiBase, cameraName }) {
  const [status, setStatus] = useState("loading"); // loading | ok | error
  const timeoutRef = useRef(null);
  const src = buildCameraLiveUrl(apiBase, cameraName);

  useEffect(() => {
    setStatus("loading");
    timeoutRef.current = setTimeout(() => {
      setStatus(prev => (prev === "loading" ? "error" : prev));
    }, 8000);
    return () => clearTimeout(timeoutRef.current);
  }, [src]);

  const handleLoad = (e) => {
    clearTimeout(timeoutRef.current);
    const ok = e.target.naturalWidth > 0 && e.target.naturalHeight > 0;
    setStatus(ok ? "ok" : "error");
  };
  const handleError = () => {
    clearTimeout(timeoutRef.current);
    setStatus("error");
  };

  return (
    <div className="camera-live-view">
      <img
        key={src}
        src={src}
        alt={`Live: ${cameraName}`}
        onLoad={handleLoad}
        onError={handleError}
        style={status === "error" ? { display: "none" } : undefined}
      />
      {status === "loading" && <p className="config-desc camera-live-status">Connecting to {cameraName}…</p>}
      {status === "error" && (
        <p className="config-desc camera-live-status camera-live-error">
          Camera unavailable — {cameraName}'s stream didn't load. It may be offline or misconfigured.
        </p>
      )}
    </div>
  );
}

// ─── Panel: Camera Events ───────────────────────────────────────────────────
// /api/events returns every device's events, not just cameras' -- Found
// 2026-08-07: CameraEventsPanel fetched that unfiltered stream directly, so
// leak/temp/motion-sensor state changes showed up mixed in with real
// camera activity. EventController's `camera` query param only scopes to
// one named camera at a time, not "any camera" -- filtering by the
// eventType values cabin_camera_event actually produces
// (DETECTION_*/MOTION_*, per docs/ontology.yaml) is the correct scope
// here client-side. A server-side eventType filter (avoiding fetching
// non-camera events at all) is tracked as the fuller fix in
// docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md §4a/§4c.
// Module-level (not redefined per render) and exported so it's directly
// unit-testable -- see src/App.test.jsx.
export const isCameraEvent = (e) => /^(DETECTION_|MOTION_)/.test(e?.eventType || "");

// Page size for both the initial load and each "Load older" click.
const CAMERA_EVENTS_PAGE_SIZE = 30;

// Pure URL-builder, no fetch inside -- extracted specifically so the
// offset/eventTypePrefix query-param wiring is directly unit-testable
// without mocking fetch. See src/App.test.jsx.
export function buildCameraEventsUrl(apiBase, offset) {
  return `${apiBase}/api/events?limit=${CAMERA_EVENTS_PAGE_SIZE}&offset=${offset}&window=24h&eventTypePrefix=DETECTION_,MOTION_`;
}

function CameraEventsPanel({ auth }) {
  const { locationCfg } = useApp();
  const apiBase = locationCfg?.apiBase || LOCATIONS.cabin.apiBase;
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [expandedEventId, setExpandedEventId] = useState(null);
  const [liveCamera, setLiveCamera] = useState(null);
  const [cameras, setCameras] = useState([]);
  const [cameraListError, setCameraListError] = useState(null);

  // Triggers/ends a real on-demand liveview session for Blink-backed
  // cameras (a no-op server-side for the Reolink, which is already
  // continuously live) whenever liveCamera changes -- see
  // CameraMediaController's startLiveview/stopLiveview and blinkbridge's
  // own liveview control API (added 2026-08-03). The effect's own
  // cleanup naturally covers every way this needs to stop: switching to
  // a different camera, clicking "Stop", or leaving this panel entirely.
  useEffect(() => {
    if (!liveCamera || !auth.signedIn) return;
    auth.authedFetch(`${apiBase}/api/camera/${liveCamera}/liveview/start`, { method: "POST" }).catch(() => {});
    return () => {
      auth.authedFetch(`${apiBase}/api/camera/${liveCamera}/liveview/stop`, { method: "POST" }).catch(() => {});
    };
  }, [liveCamera, apiBase, auth]);

  // Real server-side filtering (eventTypePrefix) as of 2026-08-07 -- see
  // EventController's own comment. This replaced the original client-side
  // isCameraEvent filter (still exported/tested for reuse elsewhere, but
  // no longer needed here since the server now only returns camera
  // events to begin with).
  const refresh = useCallback(() => {
    setLoading(true);
    fetch(buildCameraEventsUrl(apiBase, 0))
      .then(r => r.json())
      .then(list => {
        setEvents(list);
        setHasMore(list.length === CAMERA_EVENTS_PAGE_SIZE);
      })
      .catch(() => { setEvents([]); setHasMore(false); })
      .finally(() => setLoading(false));
  }, [apiBase]);

  // "Load older" -- pages back further than the initial 30 instead of the
  // old hard cap. Appends rather than replacing (refresh() above still
  // owns the "get the current newest state" full-replace behavior, used
  // for the initial load and the periodic poll).
  const loadMore = useCallback(() => {
    setLoadingMore(true);
    fetch(buildCameraEventsUrl(apiBase, events.length))
      .then(r => r.json())
      .then(list => {
        setEvents(prev => [...prev, ...list]);
        setHasMore(list.length === CAMERA_EVENTS_PAGE_SIZE);
      })
      .catch(() => setHasMore(false))
      .finally(() => setLoadingMore(false));
  }, [apiBase, events.length]);

  // Real, current camera names from Frigate's own config — NOT derived
  // from event history. That was the original approach and broke the
  // moment cameras got renamed 2026-08-02: historical events keep their
  // old sourceDeviceId forever by design, but Frigate itself only
  // recognizes the new names, so "watch live" against an old name 404'd
  // (a real bug, found via live testing after the rename, not caught by
  // review beforehand).
  //
  // Found 2026-08-03 (external review): a failed/401 fetch here used to
  // just fall back to an empty camera list with zero explanation, which
  // is indistinguishable in the UI from "this host has no cameras
  // configured." Now surfaces a real error message, and goes through
  // auth.authedFetch so an expired token clears the session instead of
  // this request silently 401ing forever.
  const refreshCameraList = useCallback(() => {
    if (!auth.signedIn) return;
    setCameraListError(null);
    auth.authedFetch(`${apiBase}/api/camera/list`)
      .then(r => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.json();
      })
      .then(list => setCameras(list.filter(c => c.enabled).map(c => c.name)))
      .catch(err => {
        setCameras([]);
        // A 401 already triggers the "session expired" banner via
        // auth.sessionExpired — no need to duplicate that message here.
        if (!auth.sessionExpired) setCameraListError(err.message);
      });
  }, [apiBase, auth]);

  useEffect(() => {
    if (!auth.signedIn) return;
    refresh();
    refreshCameraList();
    const t = setInterval(refresh, 20000);
    return () => clearInterval(t);
  }, [auth.signedIn, refresh, refreshCameraList]);

  if (!auth.configured) {
    return (
      <div className="panel-content">
        <div className="panel-header-bar"><h2>Camera Events</h2></div>
        <p className="config-desc">Google Sign-In isn't configured on this host yet (VITE_CABIN_GOOGLE_CLIENT_ID unset at build time).</p>
      </div>
    );
  }

  if (!auth.signedIn) {
    return (
      <div className="panel-content">
        <div className="panel-header-bar"><h2>Camera Events</h2></div>
        {auth.sessionExpired ? (
          <p className="config-desc camera-live-error">Session expired — sign in again.</p>
        ) : (
          <p className="config-desc">Sign in to view camera activity.</p>
        )}
        <button className="btn-primary" onClick={auth.signIn}>Sign in with Google</button>
      </div>
    );
  }

  return (
    <div className="panel-content">
      <div className="panel-header-bar">
        <h2>Camera Events</h2>
        <div className="toolbar-right">
          {auth.userEmail && <span className="config-desc">{auth.userEmail}</span>}
          <button className="btn-secondary" onClick={auth.signOut}>Sign out</button>
        </div>
      </div>

      {cameraListError && (
        <p className="config-desc camera-live-error">Couldn't load the camera list ({cameraListError}).</p>
      )}
      {cameras.length > 0 && (
        <div className="camera-live-section">
          <div className="camera-live-buttons">
            {cameras.map(cam => (
              <button
                key={cam}
                className={`btn-secondary${liveCamera === cam ? " active" : ""}`}
                onClick={() => setLiveCamera(liveCamera === cam ? null : cam)}
              >
                <Radio size={14} /> {liveCamera === cam ? `Stop ${cam}` : `Watch ${cam} live`}
              </button>
            ))}
          </div>
          {liveCamera && (
            <CameraLiveView apiBase={apiBase} cameraName={liveCamera} />
          )}
        </div>
      )}

      {loading && events.length === 0 && <p className="config-desc">Loading…</p>}
      {!loading && events.length === 0 && <p className="config-desc">No camera activity in the last 24 hours.</p>}
      <div className="camera-events-list">
        {events.map(e => {
          const frigateEventId = e.payload?.frigateEventId;
          const isExpanded = expandedEventId === e.eventId;
          const canExpand = !!frigateEventId && e.payload?.hasClip;
          return (
            <div key={e.eventId} className="camera-event-item">
              <div
                className={`camera-event-row${canExpand ? " clickable" : ""}`}
                onClick={() => canExpand && setExpandedEventId(isExpanded ? null : e.eventId)}
              >
                <CameraEventThumbnail apiBase={apiBase} authedFetch={auth.authedFetch} frigateEventId={e.payload?.hasSnapshot ? frigateEventId : null} timestamp={e.timestamp} />
                <div>
                  <div className="camera-event-title">
                    {e.sourceDeviceId} — {e.eventType.replace("DETECTION_", "").replace("MOTION_", "motion ").toLowerCase()}
                    {e.payload?.label ? ` (${e.payload.label}${e.payload.score ? `, ${Math.round(e.payload.score * 100)}%` : ""})` : ""}
                  </div>
                  <div className="camera-event-time">{new Date(e.timestamp).toLocaleString()}</div>
                </div>
              </div>
              {isExpanded && (
                <div className="camera-clip-expanded">
                  <CameraEventClip apiBase={apiBase} authedFetch={auth.authedFetch} frigateEventId={frigateEventId} />
                </div>
              )}
            </div>
          );
        })}
      </div>
      {hasMore && (
        <button className="btn-secondary camera-events-load-more" onClick={loadMore} disabled={loadingMore}>
          {loadingMore ? "Loading…" : "Load older events"}
        </button>
      )}
    </div>
  );
}

// ─── Panel: Opportunity Map ─────────────────────────────────────────────────
// See docs/PRODUCT_NOTES.md's 2026-08-03 "Opportunity Map: UX & Architecture
// Review" for the full design this implements. Findings (backend name:
// TechIdFinding) surface here as "Opportunities" — every user-facing label
// in this panel says Opportunity, never the technical name.
const FINDING_TYPE_LABELS = {
  new_api: "New API",
  deprecation: "Deprecation",
  better_integration: "Better Integration",
  competitive_product: "Competitive Product",
  complementary_device: "Complementary Device",
};
const DISMISS_REASONS = [
  { key: "not_interested", label: "Not interested" },
  { key: "already_have_it", label: "Already have it" },
  { key: "not_applicable", label: "Not applicable" },
];

// Resolves raw ontology entity ids (lineage on a finding) to display-safe
// labels via GET /api/ontology/entities — never render a snake_case id
// directly, per docs/PRODUCT_NOTES.md's Name Management Decision.
function useOntologyLabels(apiBase, ids) {
  const [labels, setLabels] = useState({});
  const key = ids.join(",");
  useEffect(() => {
    if (!key) return;
    fetch(`${apiBase}/api/ontology/entities?ids=${encodeURIComponent(key)}`)
      .then(r => r.ok ? r.json() : [])
      .then(list => {
        setLabels(prev => {
          const next = { ...prev };
          for (const e of list) next[e.id] = e.uiDisplayName || e.id;
          return next;
        });
      })
      .catch(() => {});
  }, [apiBase, key]);
  return labels;
}

function OpportunityCard({ apiBase, auth, opportunity, entityLabels, onChanged }) {
  const [expanded, setExpanded] = useState(false);
  const [choosingReason, setChoosingReason] = useState(false);
  const lineageIds = [opportunity.entityId, ...(opportunity.relatedEntityIds || [])].filter(Boolean);

  // Routed through auth.authedFetch (not a raw fetch + manual header) so
  // an expired token is handled the same way everywhere in the app: the
  // session clears and auth.sessionExpired flips, instead of this POST
  // just silently 401ing with no visible effect.
  const logAction = (actionType, detail) => {
    if (!auth.signedIn) return Promise.resolve();
    return auth.authedFetch(`${apiBase}/api/tech-id/findings/${opportunity.id}/actions`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ actionType, detail: detail || null }),
    }).catch(() => {});
  };

  const setStatus = (status) => {
    if (!auth.signedIn) return Promise.resolve();
    return auth.authedFetch(`${apiBase}/api/tech-id/findings/${opportunity.id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status }),
    }).catch(() => {});
  };

  const toggleExpand = () => {
    const next = !expanded;
    setExpanded(next);
    if (next) logAction("see_expand", null);
  };

  const include = () => { setStatus("reviewed").then(onChanged); logAction("think_include", null); };
  const dismissWithReason = (reason) => {
    setChoosingReason(false);
    setStatus("dismissed").then(onChanged);
    logAction("think_dismiss", reason);
  };

  const buyElsewhere = () => {
    const url = opportunity.actionable?.url || opportunity.sources?.[0];
    logAction("act_purchase_elsewhere", url || null);
    if (url) window.open(url, "_blank", "noopener,noreferrer");
  };
  const requestCore = () => logAction("act_request_core", opportunity.summary);
  const doItNow = () => {
    logAction("act_do_it_now", opportunity.actionable?.detail || null);
    if (opportunity.actionable?.url) window.open(opportunity.actionable.url, "_blank", "noopener,noreferrer");
  };

  const canSelfServe = opportunity.actionable?.mode === "self_serve";
  const dimmed = opportunity.status === "dismissed";

  return (
    <div className={`opportunity-card${dimmed ? " opportunity-dismissed" : ""}`}>
      <div className="opportunity-header">
        <span className="opportunity-type-badge">{FINDING_TYPE_LABELS[opportunity.findingType] || opportunity.findingType}</span>
        <span className={`opportunity-confidence opportunity-confidence-${opportunity.confidence}`}>{opportunity.confidence} confidence</span>
        {opportunity.status !== "new" && <span className="opportunity-status">{opportunity.status}</span>}
      </div>
      <p className="opportunity-summary">{opportunity.summary}</p>
      {lineageIds.length > 0 && (
        <div className="opportunity-lineage">
          {lineageIds.map(id => (
            <span key={id} className="opportunity-lineage-chip">because you have: {entityLabels[id] || id}</span>
          ))}
        </div>
      )}

      <button className="opportunity-see-more" onClick={toggleExpand}>
        {expanded ? "See less" : "See more"}
      </button>
      {expanded && (
        <div className="opportunity-detail">
          {opportunity.sources?.length > 0 && (
            <ul className="opportunity-sources">
              {opportunity.sources.map((s, i) => (
                <li key={i}><a href={s} target="_blank" rel="noopener noreferrer">{s}</a></li>
              ))}
            </ul>
          )}
          <div className="config-desc">Checked {new Date(opportunity.checkedAt).toLocaleDateString()} · via {opportunity.provider}</div>
        </div>
      )}

      {!auth.signedIn ? (
        <p className="config-desc">
          {auth.sessionExpired ? "Session expired — sign in again to " : "Sign in to "}
          think through or act on this opportunity.
        </p>
      ) : (
        <>
          <div className="opportunity-think-row">
            {!choosingReason ? (
              <>
                <button className="btn-secondary" onClick={include}><ThumbsUp size={14} /> Worth exploring</button>
                <button className="btn-secondary" onClick={() => setChoosingReason(true)}><ThumbsDown size={14} /> Not for us</button>
              </>
            ) : (
              DISMISS_REASONS.map(r => (
                <button key={r.key} className="btn-secondary" onClick={() => dismissWithReason(r.key)}>{r.label}</button>
              ))
            )}
          </div>
          <div className="opportunity-act-row">
            <button className="btn-secondary" onClick={buyElsewhere}><ShoppingCart size={14} /> Buy it elsewhere ↗</button>
            <button className="btn-secondary" onClick={requestCore}><Send size={14} /> Request this for the platform</button>
            {canSelfServe && (
              <button className="btn-primary" onClick={doItNow}><Wrench size={14} /> Do it now</button>
            )}
          </div>
          {canSelfServe && <p className="config-desc opportunity-self-serve-detail">{opportunity.actionable.detail}</p>}
        </>
      )}
    </div>
  );
}

function OpportunityMapPanel({ auth }) {
  const { locationCfg } = useApp();
  const apiBase = locationCfg?.apiBase || LOCATIONS.cabin.apiBase;
  const [opportunities, setOpportunities] = useState([]);
  const [loading, setLoading] = useState(false);
  const [showDismissed, setShowDismissed] = useState(false);

  const refresh = useCallback(() => {
    setLoading(true);
    fetch(`${apiBase}/api/tech-id/findings?limit=50`)
      .then(r => r.json()).then(setOpportunities).catch(() => setOpportunities([]))
      .finally(() => setLoading(false));
  }, [apiBase]);

  useEffect(() => { refresh(); }, [refresh]);

  const allEntityIds = useMemo(() => {
    const ids = new Set();
    for (const o of opportunities) {
      if (o.entityId) ids.add(o.entityId);
      for (const id of (o.relatedEntityIds || [])) ids.add(id);
    }
    return [...ids];
  }, [opportunities]);
  const entityLabels = useOntologyLabels(apiBase, allEntityIds);

  const visible = showDismissed ? opportunities : opportunities.filter(o => o.status !== "dismissed");

  return (
    <div className="panel-content">
      <div className="panel-header-bar">
        <h2>Opportunities</h2>
        <div className="toolbar-right">
          {!auth.signedIn && auth.configured && (
            <button className="btn-secondary" onClick={auth.signIn}>Sign in to think/act</button>
          )}
          <label className="config-desc opportunity-show-dismissed">
            <input type="checkbox" checked={showDismissed} onChange={e => setShowDismissed(e.target.checked)} /> Show dismissed
          </label>
        </div>
      </div>
      <p className="config-desc">
        Findings from the platform's monthly Tech ID Service scan — new capabilities,
        deprecations, or better ways to use devices and services you already have.
      </p>
      {loading && visible.length === 0 && <p className="config-desc">Loading…</p>}
      {!loading && visible.length === 0 && <p className="config-desc">No opportunities yet — the next monthly scan will surface anything it finds here.</p>}
      <div className="opportunity-list">
        {visible.map(o => (
          <OpportunityCard key={o.id} apiBase={apiBase} auth={auth} opportunity={o} entityLabels={entityLabels} onChanged={refresh} />
        ))}
      </div>
    </div>
  );
}

const PANELS = [
  { id: "FAMILY_HUB",     label: "My Places",       icon: Home },
  { id: "FAMILY_CONFIG",  label: "Config",   icon: Settings },
  { id: "DEVICE_MANAGER", label: "Devices",         icon: Cpu },
  { id: "MONITORING",     label: "Monitoring",      icon: Activity },
  { id: "RULES_ENGINE",   label: "Rules & Alerts",  icon: Zap },
  { id: "CAMERA_EVENTS",  label: "Camera Events",   icon: Camera },
  { id: "OPPORTUNITY_MAP", label: "Opportunities",  icon: Lightbulb },
];

export function resolvePostAuthPanel(search = window.location.search) {
  const requested = new URLSearchParams(search).get("panel");
  return PANELS.some(panel => panel.id === requested) ? requested : "FAMILY_HUB";
}

export function AuthGate({ auth }) {
  return (
    <div className="auth-gate" role="main" aria-label="Orchestration Hub sign in">
      <div className="auth-gate-toolbar"><ThemeSwitcher /></div>
      <div className="auth-gate-card">
        <ShieldAlert size={38} aria-hidden="true" />
        <h1>Orchestration Hub</h1>
        {auth.checking ? (
          <p>Checking for your Family Hub session…</p>
        ) : !auth.configured ? (
          <p>Google Sign-In is not configured on this host.</p>
        ) : (
          <>
            <p>
              {auth.sessionExpired
                ? "Your platform session expired. Sign in again to continue."
                : "Sign in before viewing places, devices, rules, or cameras."}
            </p>
            {auth.authError && <p className="auth-gate-error">{auth.authError}</p>}
            <button className="btn-primary" onClick={auth.signIn}>Sign in with Google</button>
          </>
        )}
      </div>
    </div>
  );
}

// ─── Context ───────────────────────────────────────────────────────────────
export const AppContext = createContext(null); // exported for src/App.test.jsx's FamilyHubPanel render test
function useApp() { return useContext(AppContext); }

// ─── Location switcher component ───────────────────────────────────────────
// Exported for src/App.test.jsx. "Both" only reads correctly for exactly
// two locations -- found 2026-08-08 (user, ahead of adding a third
// location): the internal value driving the "show every location"
// toggle stays "both" everywhere (existing localStorage order keys,
// refreshDevices' branching, etc. all key off that literal string, and
// changing it would orphan them for no benefit) but the LABEL shown to
// the user must read correctly regardless of count.
export function allLocationsLabel(locationCount) {
  return locationCount <= 2 ? "Both" : "All";
}

function LocationSwitcher({ active, onChange }) {
  // Object.keys(LOCATIONS) instead of a hardcoded ["cabin","home"] so a
  // location added via POST /api/locations (see useHubLocations below)
  // shows up here without a source change.
  const locationIds = Object.keys(LOCATIONS);
  const options = [...locationIds, "both"];
  return (
    <div className="location-switcher">
      {options.map(loc => (
        <button
          key={loc}
          className={`loc-btn ${active === loc ? "loc-active" : ""}`}
          onClick={() => onChange(loc)}
        >
          {loc === "both" ? allLocationsLabel(locationIds.length) : (LOCATIONS[loc]?.label || loc)}
        </button>
      ))}
    </div>
  );
}

// ─── Helpers ───────────────────────────────────────────────────────────────
function stateColor(state) {
  if (!state) return "state-unknown";
  const s = state.toUpperCase();
  if (s === "ONLINE" || s === "LOCKED" || s === "ON") return "state-ok";
  if (s === "ALARM" || s === "CRITICAL") return "state-alarm";
  if (s === "OFFLINE" || s === "DOWN") return "state-offline";
  return "state-unknown";
}

// "OFFLINE" the instant a device misses one poll interval was misleading
// users (2026-08-08 report) — it doesn't distinguish "hasn't checked in
// yet" from "confirmed unreachable." checkinStatus (from
// GET /api/devices/checkin-status, backed by DeviceHealthMonitor's
// ON_SCHEDULE/LATE/MISSED tiering) is the more honest
// label; this only overrides the badge for the ambiguous cases and never
// touches an ALARM/CRITICAL device, which must always read as itself.
export function checkinStatusLabel(state, checkinStatus) {
  const s = (state || "").toUpperCase();
  if (s === "ALARM" || s === "CRITICAL") return null;
  switch (checkinStatus) {
    case "LATE":            return { text: "Late checking in", cls: "state-late" };
    case "MISSED":           return { text: "Not responding", cls: "state-offline" };
    default: return null; // ON_SCHEDULE, or no data yet — show the raw state
  }
}

function useCheckinStatuses(apiBase) {
  const [statuses, setStatuses] = useState({});
  useEffect(() => {
    let cancelled = false;
    fetch(`${apiBase}/api/devices/checkin-status`)
      .then(r => r.json())
      .then(data => { if (!cancelled) setStatuses(data || {}); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [apiBase]);
  return statuses;
}

function deviceIcon(type) {
  const map = {
    CAMERA: Camera, WATER_PRESSURE_SENSOR: Droplets, TEMPERATURE_SENSOR: Thermometer,
    THERMOSTAT: Thermometer, SMOKE_ALARM: ShieldAlert, CO_ALARM: ShieldAlert,
    LOCK: Lock, MOTION_SENSOR: Activity, HOME_ASSISTANT_ENTITY: Wifi,
    DISHWASHER: Cpu, WASHING_MACHINE: Cpu, DRYER: Cpu, POWER_METER: Zap,
    DASHBOARD: Home,
  };
  return map[type] || Circle;
}

// ─── Live WebSocket telemetry hook ─────────────────────────────────────────
// wsBase: the Mosquitto WebSocket URL for the target hub, or null to disconnect.
function useMqttTelemetry(active, wsBase) {
  const [messages, setMessages] = useState([]);
  const ws = useRef(null);

  useEffect(() => {
    if (!active || !wsBase) { ws.current?.close(); return; }
    try {
      ws.current = new WebSocket(wsBase);
      ws.current.onopen = () => console.log("MQTT WS connected →", wsBase);
      ws.current.onmessage = (e) => {
        try {
          const data = JSON.parse(e.data);
          setMessages(prev => [{ ts: Date.now(), ...data }, ...prev].slice(0, 50));
        } catch {}
      };
      ws.current.onerror = () => {};
    } catch {}
    return () => ws.current?.close();
  }, [active, wsBase]);

  return messages;
}

// ─── Panel: Family Hub ─────────────────────────────────────────────────────
function FamilyHubLocationCard({ locId, locCfg, devices }) {
  const locDevices = devices.filter(d => !d.location || d.location === locId);
  const online  = locDevices.filter(d => d.state === "ONLINE").length;
  const alarm   = locDevices.filter(d => d.state === "ALARM").length;
  const offline = locDevices.filter(d => d.state === "OFFLINE").length;
  const total   = locDevices.length;
  const [tempUnit, toggleTempUnit] = useTempUnit();
  const tempSensors = locDevices.filter(d => d.type === "TEMPERATURE_SENSOR");

  const deployed = !!locCfg.apiBase;
  const stateColor = alarm > 0 ? "var(--state-alarm)" : online === total && total > 0 ? "var(--state-online)" : "var(--state-warning)";

  const quickLinks = [
    { label: "Home Assistant", url: locCfg.haUrl,      icon: Home },
    { label: "Zigbee2MQTT",   url: locCfg.z2mUrl,     icon: Radio },
    { label: "Grafana",       url: locCfg.grafanaUrl,  icon: BarChart2 },
    { label: "Node-RED",      url: locCfg.noderedUrl,  icon: Cpu },
  ];
  // Carry this app's active theme into Family Hub on link-out (same
  // ?theme= handoff ThemeProvider reads on load) -- the two apps live on
  // different subdomains so localStorage can't do this by itself.
  const { themeId } = useTheme();
  const familyHubUrl = locCfg.familyHubUrl
    ? `${locCfg.familyHubUrl}${locCfg.familyHubUrl.includes("?") ? "&" : "?"}theme=${themeId}`
    : locCfg.familyHubUrl;

  return (
    <div className="family-hub-card">
      <div className="family-hub-card-header">
        <span className="family-hub-location-label">{locCfg.label}</span>
        {deployed && (
          <span className="family-hub-state-dot" style={{ background: stateColor }} />
        )}
      </div>

      {!deployed ? (
        <div className="family-hub-placeholder">
          <Home size={28} opacity={0.25} />
          <p>Hub not yet deployed</p>
        </div>
      ) : (
        <>
          <div className="family-hub-device-summary">
            <span className="fh-stat fh-online">{online} online</span>
            {alarm   > 0 && <span className="fh-stat fh-alarm">{alarm} alarm</span>}
            {offline > 0 && <span className="fh-stat fh-offline">{offline} offline</span>}
            <span className="fh-stat fh-total">{total} total</span>
          </div>

          {tempSensors.length > 0 && (
            <div className="family-hub-temps">
              {tempSensors.map(s => (
                <div key={s.deviceId} className="fh-temp-row">
                  <Thermometer size={13} />
                  <span className="fh-temp-name">{s.name}</span>
                  <span className="fh-temp-val">
                    {fmtTemp(s.attributes?.temperature, tempUnit)}
                    {s.attributes?.humidity != null && ` · ${s.attributes.humidity}%`}
                  </span>
                </div>
              ))}
              <button className="btn-ghost btn-ghost-xs" onClick={toggleTempUnit}>°{tempUnit}</button>
            </div>
          )}

          {familyHubUrl && (
            <a href={familyHubUrl} target="_blank" rel="noreferrer" className="fh-launch-btn">
              Open Family Hub ↗
            </a>
          )}

          <div className="family-hub-links">
            {quickLinks.map(({ label, url, icon: Icon }) => (
              <a key={label} href={url} target="_blank" rel="noreferrer" className="fh-quick-link">
                <Icon size={12} /> {label} ↗
              </a>
            ))}
          </div>
          <div className="tailscale-hint">
            <Lock size={11} /> These are cabin-network admin tools — link only opens if you're on Tailscale.
          </div>
        </>
      )}
    </div>
  );
}

// Exported for src/App.test.jsx. Only id/label are required -- the
// connection URLs (apiBase/wsBase/grafanaUrl/noderedUrl/haUrl/
// frigateUrl/z2mUrl/familyHubUrl) are genuinely optional at creation
// time, since a brand-new physical location's stack usually doesn't
// exist yet when the place is first added (see PATCH /api/locations/
// {id} for filling them in later, same endpoint the live hub_locations
// URL fix used 2026-08-08).
const ADD_PLACE_FIELDS = [
  { key: "id",           label: "ID (slug)",       placeholder: "lakehouse", required: true },
  { key: "label",        label: "Display Name",    placeholder: "Lake House", required: true },
  { key: "apiBase",      label: "API Base URL",      placeholder: "https://api.example.com (fill in later if unknown)" },
  { key: "wsBase",       label: "WebSocket Base",    placeholder: "ws://... (fill in later if unknown)" },
  { key: "grafanaUrl",   label: "Grafana URL",       placeholder: "(fill in later if unknown)" },
  { key: "noderedUrl",   label: "Node-RED URL",      placeholder: "(fill in later if unknown)" },
  { key: "haUrl",        label: "Home Assistant URL", placeholder: "(fill in later if unknown)" },
  { key: "frigateUrl",   label: "Frigate URL",       placeholder: "(fill in later if unknown)" },
  { key: "z2mUrl",       label: "Zigbee2MQTT URL",   placeholder: "(fill in later if unknown)" },
  { key: "familyHubUrl", label: "Family Hub URL",    placeholder: "(fill in later if unknown)" },
];

function AddPlaceForm({ onCreated, onCancel }) {
  const [form, setForm] = useState(() => Object.fromEntries(ADD_PLACE_FIELDS.map(f => [f.key, ""])));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);

  const update = (key) => (e) => setForm(f => ({ ...f, [key]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    if (!form.id.trim() || !form.label.trim()) {
      setError("ID and Display Name are required.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const body = {};
      for (const [k, v] of Object.entries(form)) {
        if (v.trim()) body[k] = v.trim();
      }
      const res = await fetch(`${LOCATIONS.cabin.apiBase}/api/locations`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!res.ok) {
        const text = await res.text().catch(() => "");
        throw new Error(text || `HTTP ${res.status}`);
      }
      onCreated();
    } catch (err) {
      setError(err.message || "Failed to create location.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="add-place-form" onSubmit={submit}>
      <div className="add-place-grid">
        {ADD_PLACE_FIELDS.map(f => (
          <label key={f.key} className="add-place-field">
            {f.label}{f.required && " *"}
            <input value={form[f.key]} onChange={update(f.key)} placeholder={f.placeholder} />
          </label>
        ))}
      </div>
      {error && <p className="add-place-error">{error}</p>}
      <div className="add-place-actions">
        <button type="submit" className="btn-primary" disabled={saving}>
          {saving ? "Creating…" : "Create Place"}
        </button>
        <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>
      </div>
    </form>
  );
}

// Place-card order (Phase 7 §3): same client-side, per-browser
// localStorage pattern as Device Manager/Monitoring's own drag-reorder
// (useDraggableOrder, reused as-is) -- matches the user's own request
// ("the same way we allow re-ordering of devices"), not the
// server-persisted /api/locations/reorder endpoint hub_location's backend
// also exposes (built in §1b, still unused by any UI -- a server-synced
// order is a deliberate, separate future option, not what this
// implements).
export function FamilyHubPanel() { // exported for src/App.test.jsx's reorder test
  const { devices } = useApp();
  const [reorderMode, setReorderMode] = useState(false);
  const [adding, setAdding] = useState(false);
  const [dragIdx, setDragIdx] = useState(null);
  const [overIdx, setOverIdx] = useState(null);
  const { ordered, reorder } = useDraggableOrder("order.places", Object.values(LOCATIONS));

  const onDragStart = (idx) => (e) => { setDragIdx(idx); e.dataTransfer.effectAllowed = "move"; };
  const onDragOver  = (idx) => (e) => { e.preventDefault(); setOverIdx(idx); };
  const onDrop      = (idx) => (e) => {
    e.preventDefault();
    if (dragIdx !== null) reorder(dragIdx, idx);
    setDragIdx(null); setOverIdx(null);
  };
  const onDragEnd   = () => { setDragIdx(null); setOverIdx(null); };

  return (
    <div className="panel-content">
      <div className="panel-header-bar">
        <div className="panel-header-title">
          <h2>My Places</h2>
          <span className="panel-subtitle">Both locations at a glance</span>
        </div>
        <div className="header-actions">
          <button
            className={`btn-ghost ${adding ? "btn-ghost-active" : ""}`}
            onClick={() => { setAdding(a => !a); setReorderMode(false); }}>
            <UserPlus size={14}/> {adding ? "Cancel" : "Add Place"}
          </button>
          <button
            className={`btn-ghost ${reorderMode ? "btn-ghost-active" : ""}`}
            onClick={() => { setReorderMode(r => !r); setAdding(false); }}>
            <GripVertical size={14}/> {reorderMode ? "Done" : "Reorder"}
          </button>
        </div>
      </div>
      {/* Found 2026-08-08 (user report, ahead of adding a real second
          location): the backend's full create/update/reorder/archive
          CRUD for hub_location (Phase 7 §1b) has existed since that
          work landed, but nothing in the frontend ever called
          POST /api/locations -- there was no way to add a place through
          the UI at all. AddPlaceForm below is that missing piece. */}
      {adding && (
        <AddPlaceForm
          onCreated={() => { setAdding(false); window.location.reload(); }}
          onCancel={() => setAdding(false)}
        />
      )}
      <div className={`family-hub-grid ${reorderMode ? "reorder-mode" : ""}`}>
        {ordered.map((cfg, idx) => {
          const isOver = reorderMode && overIdx === idx && dragIdx !== idx;
          return (
            <div key={cfg.id}
              className={`family-hub-card-wrap reorder-card ${isOver ? "drag-over-card" : ""}`}
              draggable={reorderMode}
              onDragStart={reorderMode ? onDragStart(idx) : undefined}
              onDragOver={reorderMode ? onDragOver(idx) : undefined}
              onDrop={reorderMode ? onDrop(idx) : undefined}
              onDragEnd={reorderMode ? onDragEnd : undefined}
            >
              {reorderMode && <GripVertical size={14} className="drag-handle family-hub-drag-handle" />}
              <FamilyHubLocationCard locId={cfg.id} locCfg={cfg} devices={devices} />
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ─── Panel: Config ──────────────────────────────────────────────────────
// Exported for src/App.test.jsx's rename/dynamic-config-fields test.
export function FamilyConfigPanel({ auth }) {
  const { config, locationCfg } = useApp();
  const haUrl = locationCfg?.haUrl || LOCATIONS.cabin.haUrl;
  const remoteAccessMethods = (config?.remoteAccess || "Tailscale")
    .split(",").map(s => s.trim()).filter(Boolean);
  return (
    <div className="panel-content">
      <div className="panel-header-bar"><h2>Configuration</h2></div>
      <div className="config-grid">
        <ConfigCard title="Google Account" icon={Home}>
          {auth?.userEmail ? (
            <>
              <p className="config-desc">Signed in as {auth.userEmail}.</p>
              <button className="btn-secondary" onClick={auth.signIn}>Switch Google Account</button>
            </>
          ) : (
            <>
              <p className="config-desc">No active platform session.</p>
              <button className="btn-secondary" onClick={auth?.signIn}>Sign in with Google</button>
            </>
          )}
          <p className="config-hint">
            Switching only grants entry if the verified account is in this instance's platform
            allowlist (ADMIN_EMAILS). This is the same session Family Hub hands to this app;
            cameras do not perform a second authentication.
          </p>
          <a href={`${haUrl}/config/integrations`} target="_blank" rel="noreferrer" className="btn-secondary">
            Manage Home Assistant's Google integration ↗
          </a>
          <div className="tailscale-hint">
            <Lock size={11} /> Won't load off Tailscale — Home Assistant admin is cabin-network-only.
          </div>
        </ConfigCard>
        <ConfigCard title="Notification Preferences" icon={AlertTriangle}>
          <p className="config-desc">Configure alert escalation: MQTT → email/SMS thresholds.</p>
          <p className="config-hint">Node-RED flows handle routing. Edit in Rules &amp; Alerts panel.</p>
        </ConfigCard>
        <ConfigCard title="Remote Access" icon={Wifi}>
          <p className="config-desc">{remoteAccessMethods.join(", ")}</p>
          <p className="config-hint">
            New template clones default to Tailscale — set CABIN_INSTANCE_REMOTE_ACCESS
            (comma-separated) once a method is configured, and it appears here.
          </p>
          <code className="code-block">tailscale up --advertise-routes=192.168.1.0/24 --hostname=home-hub</code>
        </ConfigCard>
        <ConfigCard title="Platform" icon={Cpu}>
          <p className="config-desc">{config?.platformName || "Orchestration Platform"}</p>
          <p className="config-hint">{config?.platform || "Not configured — set CABIN_INSTANCE_PLATFORM"}</p>
        </ConfigCard>
      </div>
    </div>
  );
}

function ConfigCard({ title, icon: Icon, children }) {
  return (
    <div className="config-card">
      <div className="config-card-header"><Icon size={18} /><strong>{title}</strong></div>
      {children}
    </div>
  );
}

// ─── Alert controls (rendered at top of each alertable panel) ─────────────
function AlertControls({ panelId }) {
  const { alertCfg, enableAlert, resetAlert } = useApp();
  const entry = alertCfg?.[panelId] || { enabled: false, alertSince: null };

  if (!entry.enabled) {
    return (
      <div className="alert-ctrl alert-ctrl-unconfigured">
        <Circle size={12} className="alert-ctrl-dot"/>
        <span>Alert monitoring not enabled</span>
        <button className="btn-ghost alert-ctrl-btn" onClick={() => enableAlert(panelId)}>
          Enable
        </button>
      </div>
    );
  }

  const alertDur = entry.alertSince ? Date.now() - entry.alertSince : null;
  const isCritical = alertDur !== null && alertDur >= CRITICAL_MS;
  const isWarn     = alertDur !== null && alertDur < CRITICAL_MS;

  return (
    <div className={`alert-ctrl ${isCritical ? "alert-ctrl-critical" : isWarn ? "alert-ctrl-warn" : "alert-ctrl-ok"}`}>
      {isCritical && <AlertTriangle size={12} className="alert-ctrl-dot"/>}
      {isWarn     && <AlertTriangle size={12} className="alert-ctrl-dot"/>}
      {!isCritical && !isWarn && <CheckCircle size={12} className="alert-ctrl-dot"/>}
      <span>
        {isCritical && `Critical — alert active for ${Math.floor(alertDur / 60000)} min`}
        {isWarn     && `Warning — alert active for ${Math.floor(alertDur / 60000)} min`}
        {!isCritical && !isWarn && "Watching — no active alerts"}
      </span>
      <button className="btn-ghost alert-ctrl-btn" onClick={() => resetAlert(panelId)}
        title="Reset to unconfigured — stops all alerting until re-enabled">
        Reset alerts
      </button>
    </div>
  );
}

// ─── Panel: Device Manager ─────────────────────────────────────────────────
// L1 nav: See | Change | Add | Remove
// L2: device list filtered by action
// L3: device detail / edit / pairing flow / confirm remove

const DM_VIEWS = [
  { id: "see",    label: "See",    icon: Eye },
  { id: "change", label: "Change", icon: Edit2 },
  { id: "add",    label: "Add",    icon: UserPlus },
  { id: "remove", label: "Remove", icon: Minus },
];

function DeviceManagerPanel() {
  const { devices, refreshDevices, activeLocation } = useApp();
  const [view, setView]             = useState("see");
  const [selected, setSelected]     = useState(null);
  const [reorderMode, setReorderMode] = useState(false);

  // Found 2026-08-08 (user report): every DmXView below was rendering the
  // FULL, unfiltered devices array regardless of which location tab was
  // active -- LocationSwitcher changed activeLocation, but nothing here
  // ever consumed it to actually filter the list. Selecting "Home" still
  // showed Cabin's devices. Filtered once, here, so all three sub-views
  // (See/Change/Remove) share one correct source instead of each needing
  // its own filter (Add doesn't need one -- it creates, not lists).
  const locDevices = activeLocation === "both"
    ? devices
    : devices.filter(d => !d.location || d.location === activeLocation);

  const handleViewChange = (v) => { setView(v); setSelected(null); setReorderMode(false); };

  return (
    <div className="panel-content">
      <div className="panel-header-bar">
        <h2>Device Manager</h2>
        <div className="header-actions">
          {view === "see" && (
            <button
              className={`btn-ghost ${reorderMode ? "btn-ghost-active" : ""}`}
              onClick={() => setReorderMode(r => !r)}>
              <GripVertical size={14}/> {reorderMode ? "Done" : "Reorder"}
            </button>
          )}
          <button className="btn-ghost" onClick={refreshDevices}><RefreshCw size={14}/> Refresh</button>
        </div>
      </div>

      <AlertControls panelId="DEVICE_MANAGER" />

      <div className="dm-l1-nav">
        {DM_VIEWS.map(v => {
          const Icon = v.icon;
          return (
            <button key={v.id}
              className={`dm-l1-btn ${view === v.id ? "dm-l1-active" : ""}`}
              onClick={() => handleViewChange(v.id)}>
              <Icon size={15}/> {v.label}
            </button>
          );
        })}
        <a href={LOCATIONS.cabin.z2mUrl}
           className="dm-advanced-link" target="_blank" rel="noreferrer"
           title="Requires Tailscale — Zigbee2MQTT admin is cabin-network-only">
          Advanced (Z2M) <ExternalLink size={11}/>
        </a>
      </div>

      {view === "see"    && <DmSeeView    devices={locDevices} selected={selected} onSelect={setSelected} reorderMode={reorderMode} />}
      {view === "change" && <DmChangeView devices={locDevices} selected={selected} onSelect={setSelected} onRefresh={refreshDevices} />}
      {view === "add"    && <DmAddView    onDone={() => { refreshDevices(); setView("see"); }} />}
      {view === "remove" && <DmRemoveView devices={locDevices} selected={selected} onSelect={setSelected} onRefresh={refreshDevices} />}
    </div>
  );
}

// ── L2/L3: See ──
function DmSeeView({ devices, selected, onSelect, reorderMode }) {
  const { activeLocation } = useApp();
  const [health, setHealth] = useState(null);
  const [dragIdx, setDragIdx] = useState(null);
  const [overIdx, setOverIdx] = useState(null);
  const checkinStatuses = useCheckinStatuses(LOCATIONS.cabin.apiBase);

  useEffect(() => {
    fetch(`${LOCATIONS.cabin.apiBase}/api/system/health`)
      .then(r => r.json()).then(setHealth).catch(() => {});
  }, []);

  const isAlarm = useCallback((d) => d.state === "ALARM" || d.state === "CRITICAL", []);
  const { ordered, pinnedCount, reorder } = useDraggableOrder(
    `order.devices.${activeLocation}`, devices, isAlarm
  );

  const sel = selected ? ordered.find(d => d.deviceId === selected) : null;

  const onDragStart = (idx) => (e) => { setDragIdx(idx); e.dataTransfer.effectAllowed = "move"; };
  const onDragOver  = (idx) => (e) => { e.preventDefault(); setOverIdx(idx); };
  const onDrop      = (idx) => (e) => {
    e.preventDefault();
    if (dragIdx !== null) reorder(dragIdx, idx);
    setDragIdx(null); setOverIdx(null);
  };
  const onDragEnd   = () => { setDragIdx(null); setOverIdx(null); };

  return (
    <div className="dm-layout">
      <div className={`dm-list ${reorderMode ? "reorder-mode" : ""}`}>
        {health && (
          <div className="dm-health-bar">
            <span className="health-chip health-ok"><CheckCircle size={11}/> {health.online} online</span>
            <span className="health-chip health-offline"><WifiOff size={11}/> {health.offline} offline</span>
            {health.alarm > 0 && <span className="health-chip health-alarm"><ShieldAlert size={11}/> {health.alarm} alarm</span>}
            <span className="health-chip health-z2m">Z2M: {health.zigbeeBridge || "—"}</span>
          </div>
        )}
        {ordered.map((d, idx) => {
          const isPinned = idx < pinnedCount;
          const isOver   = reorderMode && overIdx === idx && dragIdx !== idx;
          return (
            <div key={d.deviceId}
              className={`reorder-card ${isOver ? "drag-over-card" : ""}`}
              draggable={reorderMode && !isPinned}
              onDragStart={reorderMode && !isPinned ? onDragStart(idx) : undefined}
              onDragOver={reorderMode ? onDragOver(idx) : undefined}
              onDrop={reorderMode ? onDrop(idx) : undefined}
              onDragEnd={reorderMode ? onDragEnd : undefined}
            >
              <DmDeviceRow device={d}
                selected={selected === d.deviceId}
                checkinStatus={checkinStatuses[d.deviceId]}
                onClick={() => onSelect(selected === d.deviceId ? null : d.deviceId)}
                dragHandle={reorderMode
                  ? (isPinned
                    ? <Lock size={12} className="auto-pin-icon" title="Auto-pinned: alarm active"/>
                    : <GripVertical size={12} className="drag-handle"/>)
                  : null}
              />
            </div>
          );
        })}
        {devices.length === 0 && <div className="empty-state"><Cpu size={36} opacity={0.3}/><p>No devices registered.</p></div>}
      </div>
      {sel && (
        <div className="dm-detail">
          <DmDeviceDetail device={sel} checkinStatus={checkinStatuses[sel.deviceId]} />
        </div>
      )}
    </div>
  );
}

// ── L2/L3: Change ──
function DmChangeView({ devices, selected, onSelect, onRefresh }) {
  const sel = selected ? devices.find(d => d.deviceId === selected) : null;
  return (
    <div className="dm-layout">
      <div className="dm-list">
        <p className="dm-hint">Select a device to edit its name or enabled state.</p>
        {devices.map(d => <DmDeviceRow key={d.deviceId} device={d} selected={selected === d.deviceId} onClick={() => onSelect(selected === d.deviceId ? null : d.deviceId)} />)}
      </div>
      {sel && (
        <div className="dm-detail">
          <DmEditForm device={sel} onSaved={onRefresh} />
        </div>
      )}
    </div>
  );
}

// ── L2/L3: Add ──
function DmAddView({ onDone }) {
  const [mode, setMode] = useState(null); // null | "zigbee" | "manual"
  return (
    <div className="dm-add-root">
      {!mode && (
        <div className="dm-add-choice">
          <p className="dm-hint">How do you want to add a device?</p>
          <div className="dm-add-options">
            <button className="dm-add-option" onClick={() => setMode("zigbee")}>
              <Radio size={28}/>
              <strong>Pair a Zigbee device</strong>
              <span>Opens a 4-minute pairing window on the cabin hub's Zigbee coordinator</span>
            </button>
            <button className="dm-add-option" onClick={() => setMode("manual")}>
              <Cpu size={28}/>
              <strong>Register manually</strong>
              <span>Add a Home Assistant entity, RTSP camera, or MQTT device by ID</span>
            </button>
          </div>
        </div>
      )}
      {mode === "zigbee" && <ZigbeePairingFlow onBack={() => setMode(null)} onDone={onDone} />}
      {mode === "manual" && <ManualAddForm onBack={() => setMode(null)} onDone={onDone} />}
    </div>
  );
}

// ── Zigbee pairing flow ──
function ZigbeePairingFlow({ onBack, onDone }) {
  const { auth } = useApp();
  const PAIR_DURATION = 254; // seconds (~4m14s)
  const [phase, setPhase]         = useState("idle"); // idle | pairing | found | done
  const [secondsLeft, setSeconds] = useState(PAIR_DURATION);
  const [newDevices, setNewDevices] = useState([]);
  const [afterChoice, setAfterChoice] = useState(null); // "another" | "configure" | "seeall"
  const timerRef = useRef(null);
  const pollRef  = useRef(null);
  const prevIds  = useRef(null);

  const startPairing = async () => {
    // Snapshot current device IDs before opening window
    const snap = await fetch(`${LOCATIONS.cabin.apiBase}/api/devices`)
      .then(r => r.json()).catch(() => []);
    prevIds.current = new Set(snap.map(d => d.deviceId));

    await auth.authedFetch(`${LOCATIONS.cabin.apiBase}/api/devices/permit-join`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enable: true, duration: PAIR_DURATION })
    });
    setPhase("pairing");
    setSeconds(PAIR_DURATION);

    // Countdown
    timerRef.current = setInterval(() => {
      setSeconds(s => {
        if (s <= 1) { clearInterval(timerRef.current); setPhase("done"); return 0; }
        return s - 1;
      });
    }, 1000);

    // Poll for new devices every 3s
    pollRef.current = setInterval(async () => {
      const all = await fetch(`${LOCATIONS.cabin.apiBase}/api/devices`)
        .then(r => r.json()).catch(() => []);
      const found = all.filter(d => !prevIds.current.has(d.deviceId));
      if (found.length > 0) setNewDevices(found);
    }, 3000);
  };

  const stopPairing = async () => {
    clearInterval(timerRef.current);
    clearInterval(pollRef.current);
    await auth.authedFetch(`${LOCATIONS.cabin.apiBase}/api/devices/permit-join`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enable: false, duration: 0 })
    });
    setPhase("done");
  };

  useEffect(() => () => { clearInterval(timerRef.current); clearInterval(pollRef.current); }, []);

  const mins = String(Math.floor(secondsLeft / 60)).padStart(2, "0");
  const secs = String(secondsLeft % 60).padStart(2, "0");

  if (phase === "idle") return (
    <div className="pairing-container">
      <button className="btn-ghost dm-back" onClick={onBack}><ArrowLeft size={14}/> Back</button>
      <div className="pairing-card">
        <Radio size={36} className="pairing-icon"/>
        <h3>Pair a Zigbee Device</h3>
        <p>Put your device into pairing mode (hold the button until the LED flashes), then open the pairing window.</p>
        <p className="config-hint">The cabin hub's Zigbee coordinator will accept new devices for 4 minutes 14 seconds.</p>
        <button className="btn-primary pairing-start-btn" onClick={startPairing}>
          Open pairing window
        </button>
      </div>
    </div>
  );

  if (phase === "pairing") return (
    <div className="pairing-container">
      <div className="pairing-card pairing-active">
        <div className="pairing-countdown">{mins}:{secs}</div>
        <p className="pairing-status">Pairing window open — put device into pairing mode now</p>
        {newDevices.length > 0 && (
          <div className="pairing-found">
            <strong>New device{newDevices.length > 1 ? "s" : ""} found:</strong>
            {newDevices.map(d => (
              <div key={d.deviceId} className="pairing-found-row">
                <CheckCircle size={14} className="found-check"/> {d.name} ({d.deviceId})
              </div>
            ))}
          </div>
        )}
        <button className="btn-ghost" onClick={stopPairing}>Stop early</button>
      </div>
    </div>
  );

  // phase === "done"
  return (
    <div className="pairing-container">
      <div className="pairing-card">
        {newDevices.length > 0 ? (
          <>
            <CheckCircle size={36} className="pairing-icon pairing-success"/>
            <h3>Paired {newDevices.length} device{newDevices.length > 1 ? "s" : ""}!</h3>
            {newDevices.map(d => (
              <div key={d.deviceId} className="pairing-found-row">
                <CheckCircle size={13} className="found-check"/> {d.name}
              </div>
            ))}
            <div className="pairing-choices">
              <button className="btn-secondary" onClick={() => { setPhase("idle"); setNewDevices([]); }}>
                Add another
              </button>
              <button className="btn-primary" onClick={onDone}>See all devices</button>
            </div>
          </>
        ) : (
          <>
            <Clock size={36} className="pairing-icon"/>
            <h3>Pairing window closed</h3>
            <p className="config-hint">No new devices were found. Make sure the device is in pairing mode before opening the window.</p>
            <div className="pairing-choices">
              <button className="btn-secondary" onClick={() => setPhase("idle")}>Try again</button>
              <button className="btn-ghost" onClick={onBack}>Back</button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

// ── Manual add form ──
function ManualAddForm({ onBack, onDone }) {
  const { locationCfg, auth } = useApp();
  const [form, setForm] = useState({
    deviceId: "", name: "", type: "HOME_ASSISTANT_ENTITY",
    protocolAdapter: "ha_rest", connectionString: "", enabled: true,
    location: locationCfg?.id || "cabin"
  });
  const [saving, setSaving] = useState(false);
  const apiBase = form.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;

  const submit = async () => {
    if (!form.deviceId || !form.name) return;
    setSaving(true);
    await auth.authedFetch(`${apiBase}/api/devices`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...form, capabilities: [] })
    });
    setSaving(false);
    onDone();
  };

  return (
    <div className="pairing-container">
      <button className="btn-ghost dm-back" onClick={onBack}><ArrowLeft size={14}/> Back</button>
      <div className="dm-edit-form">
        <h3>Register Device Manually</h3>
        <label>Location
          <select value={form.location} onChange={e=>setForm({...form,location:e.target.value})}>
            <option value="cabin">Cabin</option>
            <option value="home">Home</option>
          </select>
        </label>
        <label>Device ID <input value={form.deviceId} placeholder="e.g. home-lock-garage" onChange={e=>setForm({...form,deviceId:e.target.value})}/></label>
        <label>Display Name <input value={form.name} placeholder="e.g. Home Garage Lock" onChange={e=>setForm({...form,name:e.target.value})}/></label>
        <label>Type
          <select value={form.type} onChange={e=>setForm({...form,type:e.target.value})}>
            {["LOCK","THERMOSTAT","SMOKE_ALARM","CAMERA","WATER_PRESSURE_SENSOR","WATER_LEAK_SENSOR",
              "MOTION_SENSOR","CONTACT_SENSOR","DISHWASHER","WASHING_MACHINE","DRYER","POWER_METER",
              "HOME_ASSISTANT_ENTITY"].map(t=><option key={t}>{t}</option>)}
          </select>
        </label>
        <label>Protocol Adapter
          <select value={form.protocolAdapter} onChange={e=>setForm({...form,protocolAdapter:e.target.value})}>
            {["ha_rest","mqtt","rtsp","http_poll","google_sdm"].map(a=><option key={a}>{a}</option>)}
          </select>
        </label>
        <label>Connection String
          <input value={form.connectionString}
            placeholder="HA entity_id, MQTT topic, or RTSP URL"
            onChange={e=>setForm({...form,connectionString:e.target.value})}/>
        </label>
        <div className="modal-actions">
          <button className="btn-ghost" onClick={onBack}>Cancel</button>
          <button className="btn-primary" onClick={submit} disabled={saving || !form.deviceId || !form.name}>
            {saving ? "Saving…" : "Register Device"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── L2/L3: Remove ──
function DmRemoveView({ devices, selected, onSelect, onRefresh }) {
  const { auth } = useApp();
  const [confirming, setConfirming] = useState(false);
  const sel = selected ? devices.find(d => d.deviceId === selected) : null;

  const doRemove = async () => {
    if (!sel) return;
    const apiBase = LOCATIONS[sel.location]?.apiBase || LOCATIONS.cabin.apiBase;
    await auth.authedFetch(`${apiBase}/api/devices/${sel.deviceId}`, { method: "DELETE" });
    onSelect(null);
    setConfirming(false);
    onRefresh();
  };

  return (
    <div className="dm-layout">
      <div className="dm-list">
        <p className="dm-hint">Select a device to remove it from the registry.</p>
        {devices.map(d => <DmDeviceRow key={d.deviceId} device={d} selected={selected === d.deviceId} onClick={() => { onSelect(selected === d.deviceId ? null : d.deviceId); setConfirming(false); }} />)}
      </div>
      {sel && (
        <div className="dm-detail">
          {!confirming ? (
            <div className="dm-remove-panel">
              <div className="dm-remove-device-name">{sel.name}</div>
              <div className="dm-remove-device-id">{sel.deviceId} · {sel.location}</div>
              <p className="dm-hint">This removes the device from the registry. It does not affect Home Assistant or Zigbee2MQTT pairing.</p>
              <button className="btn-danger dm-remove-confirm-btn" onClick={() => setConfirming(true)}>
                <Trash2 size={14}/> Remove this device
              </button>
            </div>
          ) : (
            <div className="dm-remove-panel">
              <AlertTriangle size={32} className="remove-warn-icon"/>
              <strong>Remove "{sel.name}"?</strong>
              <p className="dm-hint">This cannot be undone from the UI. The device will disappear from all panels.</p>
              <div className="pairing-choices">
                <button className="btn-ghost" onClick={() => setConfirming(false)}>Cancel</button>
                <button className="btn-danger" onClick={doRemove}><Trash2 size={13}/> Confirm remove</button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ── Shared sub-components ──

function DmDeviceRow({ device, selected, onClick, dragHandle, checkinStatus }) {
  const Icon = deviceIcon(device.type);
  const isZ2m = device.deviceId.startsWith("z2m-");
  const override = checkinStatusLabel(device.state, checkinStatus);
  return (
    <div className={`dm-device-row ${selected ? "dm-row-selected" : ""}`} onClick={onClick}>
      {dragHandle}
      <Icon size={16} className="dm-row-icon"/>
      <div className="dm-row-info">
        <span className="dm-row-name">{device.name}</span>
        <span className="dm-row-meta">{device.type} · {device.location}{isZ2m ? " · zigbee" : ""}</span>
      </div>
      <span className={`state-badge ${override ? override.cls : stateColor(device.state)}`}>
        {override ? override.text : device.state}
      </span>
    </div>
  );
}

function DmDeviceDetail({ device, checkinStatus }) {
  const override = checkinStatusLabel(device.state, checkinStatus);
  return (
    <div className="dm-detail-inner">
      <div className="dm-detail-name">{device.name}</div>
      <div className="dm-detail-id">{device.deviceId}</div>
      <div className="dm-detail-rows">
        <div className="dm-detail-row"><span>Type</span><span>{device.type}</span></div>
        <div className="dm-detail-row"><span>Location</span><span>{device.location}</span></div>
        <div className="dm-detail-row"><span>State</span>
          <span className={`state-badge ${override ? override.cls : stateColor(device.state)}`}>
            {override ? override.text : device.state}
          </span>
        </div>
        <div className="dm-detail-row"><span>Last seen</span>
          <span>{device.lastSeen ? new Date(device.lastSeen).toLocaleString() : "—"}</span>
        </div>
      </div>
      {Object.keys(device.attributes || {}).length > 0 && (
        <>
          <div className="dm-detail-section">Attributes</div>
          {Object.entries(device.attributes).map(([k, v]) => v != null && (
            <div key={k} className="attr-row">
              <span className="attr-key">{k}</span>
              <span className="attr-val">{String(v)}</span>
            </div>
          ))}
        </>
      )}
      {device.type === "LOCK" && <DmLockActions device={device}/>}
    </div>
  );
}

function DmLockActions({ device }) {
  const { refreshDevices, auth } = useApp();
  const apiBase = device.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;
  const cmd = async (command) => {
    await auth.authedFetch(`${apiBase}/api/devices/${device.deviceId}/command`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ command })
    });
    refreshDevices();
  };
  return (
    <div className="device-actions" style={{marginTop: 12}}>
      <button className="btn-secondary" onClick={() => cmd("lock.lock")}><Lock size={12}/> Lock</button>
      <button className="btn-secondary" onClick={() => cmd("lock.unlock")}>Unlock</button>
    </div>
  );
}

function DmEditForm({ device, onSaved }) {
  const { auth } = useApp();
  const [name, setName]       = useState(device.name);
  const [enabled, setEnabled] = useState(device.enabled !== false);
  const [saving, setSaving]   = useState(false);
  const [saved, setSaved]     = useState(false);
  const apiBase = device.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;

  const save = async () => {
    setSaving(true);
    await auth.authedFetch(`${apiBase}/api/devices/${device.deviceId}/config`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, enabled })
    });
    setSaving(false);
    setSaved(true);
    onSaved();
    setTimeout(() => setSaved(false), 2000);
  };

  return (
    <div className="dm-edit-form">
      <div className="dm-detail-name">{device.deviceId}</div>
      <label>Display Name
        <input value={name} onChange={e => { setName(e.target.value); setSaved(false); }}/>
      </label>
      <label className="dm-toggle-row">
        <span>Enabled</span>
        <button className="btn-ghost" onClick={() => setEnabled(e => !e)}>
          {enabled ? <ToggleRight size={22} className="toggle-on"/> : <ToggleLeft size={22} className="toggle-off"/>}
        </button>
      </label>
      <div className="modal-actions">
        {saved && <span className="save-ok"><CheckCircle size={13}/> Saved</span>}
        <button className="btn-primary" onClick={save} disabled={saving}>
          {saving ? "Saving…" : "Save changes"}
        </button>
      </div>
    </div>
  );
}

// ─── Panel: Monitoring ─────────────────────────────────────────────────────

// Replaced the embedded Grafana iframe, 2026-08-08 -- three separate fix
// attempts (URL/subpath, SameSite cookie, a theory that was then
// disproven live) all failed. Root cause landed on either browser-level
// third-party cookie blocking or the iframe request never reaching the
// server at all -- see docs/ontology.yaml's cabin_grafana_public_access
// notes. User's own call: stop fighting the iframe, query Prometheus
// directly (via cabin-backend's new /api/frigate-metrics -- Prometheus
// itself stays Tailscale/internal-only, never exposed publicly) for the
// one metric that actually matters, and link out to the full Grafana
// dashboard for anyone who wants more detail.

// Exported for src/App.test.jsx. Pure function, no fetch/state -- kept
// separate from "no data yet" (fps === undefined/null) so an unreachable
// Prometheus never reads as "camera confirmed down," same reasoning as
// SecurityBadge's Unknown state.
export function cameraHealthLabel(fps) {
  if (fps == null) return { label: "Unknown", className: "camera-health-unknown" };
  return fps > 0
    ? { label: `${fps.toFixed(1)} fps`, className: "camera-health-ok" }
    : { label: "No signal", className: "camera-health-down" };
}

function useFrigateMetrics(apiBase) {
  const [metrics, setMetrics] = useState({});
  useEffect(() => {
    const fetchMetrics = () =>
      fetch(`${apiBase}/api/frigate-metrics`).then(r => r.json()).then(setMetrics).catch(() => {});
    fetchMetrics();
    const t = setInterval(fetchMetrics, 15000);
    return () => clearInterval(t);
  }, [apiBase]);
  return metrics;
}

function CameraHealthPanel({ locCfg }) {
  const metrics = useFrigateMetrics(locCfg.apiBase);
  const cameraIds = Object.keys(metrics);
  const dashboardUid = GRAFANA_DASHBOARD_UID[locCfg.id];

  return (
    <div className="embed-section">
      <div className="embed-label">Camera Health — {locCfg.label}</div>
      <div className="camera-health-grid">
        {cameraIds.length === 0 && (
          <p className="config-desc">No camera metrics available yet.</p>
        )}
        {cameraIds.map(id => {
          const { label, className } = cameraHealthLabel(metrics[id]?.cameraFps);
          return (
            <div key={id} className={`camera-health-tile ${className}`}>
              <Camera size={16} />
              <span className="camera-health-name">{id}</span>
              <span className="camera-health-value">{label}</span>
            </div>
          );
        })}
      </div>
      {dashboardUid ? (
        <a className="btn-secondary" href={`${locCfg.grafanaUrl}/grafana/d/${dashboardUid}`}
          target="_blank" rel="noreferrer">
          Open Full Dashboard in Grafana ↗
        </a>
      ) : (
        <p className="config-hint">No Grafana dashboard configured for {locCfg.label} yet.</p>
      )}
    </div>
  );
}

// Renders KPI tiles + Grafana + event log for a single location.
function LocationMonitoringSection({ locCfg, devices, active }) {
  const liveMessages = useMqttTelemetry(active, locCfg.wsBase);
  const [tempUnit, toggleTempUnit] = useTempUnit();

  const locDevices  = devices.filter(d => !d.location || d.location === locCfg.id);
  const pressure    = locDevices.find(d => d.type === "WATER_PRESSURE_SENSOR");
  const thermostats = locDevices.filter(d => d.type === "THERMOSTAT");
  const tempSensors = locDevices.filter(d => d.type === "TEMPERATURE_SENSOR");
  const smoke       = locDevices.find(d => d.type === "SMOKE_ALARM");
  const locks       = locDevices.filter(d => d.type === "LOCK");
  const cameras     = locDevices.filter(d => d.type === "CAMERA");
  const energy      = locDevices.find(d => d.type === "POWER_METER");

  return (
    <div className="location-section">
      <div className="location-section-header">
        {locCfg.label}
        <button className="btn-ghost btn-ghost-sm" onClick={toggleTempUnit} title="Toggle °F / °C">
          °{tempUnit}
        </button>
      </div>

      <div className="kpi-grid">
        {pressure && (
          <KpiTile icon={Droplets} label="Water Pressure" deviceId={pressure.deviceId}
            value={pressure.attributes?.psi != null ? `${pressure.attributes.psi} PSI` : "—"}
            state={pressure.state} />
        )}
        {thermostats.map(t => (
          <KpiTile key={t.deviceId} icon={Thermometer} label={t.name} deviceId={t.deviceId}
            value={fmtTemp(t.attributes?.current_temperature, tempUnit)}
            state={t.state} />
        ))}
        {tempSensors.map(s => {
          const temp = s.attributes?.temperature;
          const hum  = s.attributes?.humidity;
          const val  = [
            fmtTemp(temp, tempUnit),
            hum  != null && `${hum}%`,
          ].filter(Boolean).join(" · ") || "—";
          return (
            <KpiTile key={s.deviceId} icon={Thermometer} label={s.name} deviceId={s.deviceId}
              value={val} state={s.state} />
          );
        })}
        {smoke && (
          <KpiTile icon={ShieldAlert} label={smoke.name || "Smoke/CO Alarm"} deviceId={smoke.deviceId}
            value={smoke.state || "UNKNOWN"}
            state={smoke.state === "ALARM" ? "ALARM" : smoke.state} />
        )}
        {energy && (
          <KpiTile icon={Zap} label="Energy" deviceId={energy.deviceId}
            value={energy.attributes?.state_w != null ? `${energy.attributes.state_w} W` : "—"}
            state={energy.state} />
        )}
        {locks.map(l => (
          <KpiTile key={l.deviceId} icon={Lock} label={l.name} deviceId={l.deviceId}
            value={l.state} state={l.state} />
        ))}
        {cameras.map(c => (
          <KpiTile key={c.deviceId} icon={Camera} label={c.name} deviceId={c.deviceId}
            value={c.state} state={c.state} />
        ))}
      </div>

      <CameraHealthPanel locCfg={locCfg} />

      <div className="event-log">
        <div className="event-log-header">Live MQTT — {locCfg.label}</div>
        {liveMessages.length === 0 && (
          <div className="event-log-empty">No live messages from {locCfg.wsBase}</div>
        )}
        {liveMessages.map((m, i) => (
          <div key={i} className="event-row">
            <span className="event-ts">{new Date(m.ts).toLocaleTimeString()}</span>
            <span className="event-body">{JSON.stringify(m)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

const MN_VIEWS = [
  { id: "see",    label: "See",    icon: Eye },
  { id: "change", label: "Change", icon: Edit2 },
  { id: "add",    label: "Add",    icon: Plus },
  { id: "remove", label: "Remove", icon: Minus },
];

function MonitoringPanel({ active }) {
  const { devices, activeLocation, activeProfile } = useApp();
  const [view, setView]               = useState("see");
  const [selected, setSelected]       = useState(null);
  const [reorderMode, setReorderMode] = useState(false);

  const handleViewChange = (v) => { setView(v); setSelected(null); setReorderMode(false); };

  return (
    <div className="panel-content">
      <AlertControls panelId="MONITORING" />
      <div className="panel-header-bar">
        <h2>Monitoring</h2>
        <div className="header-actions">
          {view === "see" && (
            <button
              className={`btn-ghost ${reorderMode ? "btn-ghost-active" : ""}`}
              onClick={() => setReorderMode(r => !r)}>
              <GripVertical size={14}/> {reorderMode ? "Done" : "Reorder"}
            </button>
          )}
          <span className={`ws-indicator ${active ? "ws-live" : "ws-off"}`}>
            {active ? <><Wifi size={12}/> Live</> : <><WifiOff size={12}/> Docked</>}
          </span>
        </div>
      </div>

      <div className="dm-l1-nav">
        {MN_VIEWS.map(v => {
          const Icon = v.icon;
          return (
            <button key={v.id}
              className={`dm-l1-btn ${view === v.id ? "dm-l1-active" : ""}`}
              onClick={() => handleViewChange(v.id)}>
              <Icon size={15}/> {v.label}
            </button>
          );
        })}
        <span className="mn-profile-hint">
          <MapPin size={11}/> {PROFILE_LABELS[activeProfile] || activeProfile}
        </span>
      </div>

      {view === "see"    && <MnSeeView devices={devices} activeLocation={activeLocation} active={active} reorderMode={reorderMode} />}
      {view === "change" && <MnChangeView devices={devices} selected={selected} onSelect={setSelected} />}
      {view === "add"    && <MnChangeView devices={devices} selected={selected} onSelect={setSelected} />}
      {view === "remove" && <MnRemoveView />}
    </div>
  );
}

function KpiListItem({ device, idx, dragIdx, overIdx, reorderMode, isPinned,
    onDragStart, onDragOver, onDrop, onDragEnd, checkinStatus }) {
  const { displayConfigs } = useApp();
  const cfg = displayConfigs?.[device.deviceId];
  const Icon = deviceIcon(device.type);
  const effectiveLabel = cfg?.displayName || device.name;
  const override        = !cfg?.stateLabelMap?.[device.state] ? checkinStatusLabel(device.state, checkinStatus) : null;
  const stCls          = severityClass(cfg?.severityOverride) || (override ? override.cls : stateColor(device.state));
  const badgeLabel     = cfg?.stateLabelMap?.[device.state] || (override ? override.text : device.state) || "UNKNOWN";
  const isOver         = reorderMode && overIdx === idx && dragIdx !== idx;

  return (
    <div
      className={`kpi-list-item reorder-card ${isOver ? "drag-over-card" : ""}`}
      draggable={reorderMode && !isPinned}
      onDragStart={reorderMode && !isPinned ? onDragStart : undefined}
      onDragOver={reorderMode ? onDragOver : undefined}
      onDrop={reorderMode ? onDrop : undefined}
      onDragEnd={reorderMode ? onDragEnd : undefined}
    >
      {reorderMode && (isPinned
        ? <Lock size={13} className="auto-pin-icon" title="Auto-pinned: alarm active"/>
        : <GripVertical size={13} className="drag-handle"/>)}
      <Icon size={15} style={{ flexShrink: 0, opacity: 0.65 }}/>
      <span className="kpi-list-label">{effectiveLabel}</span>
      <span className="kpi-list-type">{device.type?.toLowerCase().replace(/_/g, " ")}</span>
      <span className={`state-badge ${stCls}`}>{badgeLabel}</span>
    </div>
  );
}

function MnSeeView({ devices, activeLocation, active, reorderMode }) {
  const [dragIdx, setDragIdx] = useState(null);
  const [overIdx, setOverIdx] = useState(null);
  const checkinStatuses = useCheckinStatuses(LOCATIONS.cabin.apiBase);

  const isAlarm = useCallback((d) => d.state === "ALARM" || d.state === "CRITICAL", []);
  const locDevices = activeLocation === "both"
    ? devices
    : devices.filter(d => !d.location || d.location === activeLocation);

  const { ordered, pinnedCount, reorder } = useDraggableOrder(
    `order.monitoring.${activeLocation}`, locDevices, isAlarm
  );

  const onDragStart = (idx) => (e) => { setDragIdx(idx); e.dataTransfer.effectAllowed = "move"; };
  const onDragOver  = (idx) => (e) => { e.preventDefault(); setOverIdx(idx); };
  const onDrop      = (idx) => (e) => {
    e.preventDefault();
    if (dragIdx !== null) reorder(dragIdx, idx);
    setDragIdx(null); setOverIdx(null);
  };
  const onDragEnd = () => { setDragIdx(null); setOverIdx(null); };

  if (reorderMode) {
    return (
      <div className="kpi-list reorder-mode">
        {ordered.map((d, idx) => (
          <KpiListItem key={d.deviceId} device={d} idx={idx}
            dragIdx={dragIdx} overIdx={overIdx}
            reorderMode={reorderMode} isPinned={idx < pinnedCount}
            onDragStart={onDragStart(idx)} onDragOver={onDragOver(idx)}
            onDrop={onDrop(idx)} onDragEnd={onDragEnd}
            checkinStatus={checkinStatuses[d.deviceId]}
          />
        ))}
        {ordered.length === 0 && (
          <div className="empty-state"><Activity size={36} opacity={0.3}/><p>No devices for this location.</p></div>
        )}
      </div>
    );
  }

  const locs = activeLocation === "both"
    ? [LOCATIONS.cabin, LOCATIONS.home]
    : [LOCATIONS[activeLocation] || LOCATIONS.cabin];
  return (
    <div className={activeLocation === "both" ? "monitoring-split" : ""}>
      {locs.map(loc => (
        <LocationMonitoringSection key={loc.id} locCfg={loc} devices={devices} active={active} />
      ))}
    </div>
  );
}

function MnChangeView({ devices, selected, onSelect }) {
  const { activeProfile, refreshDisplayConfigs } = useApp();
  const sel = selected ? devices.find(d => d.deviceId === selected) : null;

  return (
    <div className="dm-layout">
      <div className="dm-list">
        <div className="dm-health-bar" style={{ fontSize: 11, opacity: 0.65 }}>
          Configure display overrides for profile:&nbsp;<strong>{PROFILE_LABELS[activeProfile] || activeProfile}</strong>
        </div>
        {devices.map(d => (
          <DmDeviceRow key={d.deviceId} device={d}
            selected={selected === d.deviceId}
            onClick={() => onSelect(selected === d.deviceId ? null : d.deviceId)} />
        ))}
        {devices.length === 0 && (
          <div className="empty-state"><Cpu size={36} opacity={0.3}/><p>No devices.</p></div>
        )}
      </div>
      {sel && (
        <div className="dm-detail">
          <DisplayConfigForm device={sel} profile={activeProfile} onSaved={refreshDisplayConfigs} />
        </div>
      )}
    </div>
  );
}

function MnRemoveView() {
  const { activeProfile, displayConfigs, refreshDisplayConfigs, devices, auth } = useApp();
  const [removing, setRemoving] = useState(null);
  const configured = Object.values(displayConfigs);

  const doRemove = async (cfg) => {
    setRemoving(cfg.deviceId);
    const apiBase = cfg.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;
    await auth.authedFetch(`${apiBase}/api/devices/${cfg.deviceId}/display-config?profile=${activeProfile}`,
      { method: "DELETE" }).catch(() => {});
    setRemoving(null);
    refreshDisplayConfigs();
  };

  if (configured.length === 0) {
    return (
      <div className="empty-state" style={{ marginTop: 24 }}>
        <Activity size={36} opacity={0.3}/>
        <p>No display overrides configured for<br/><strong>{PROFILE_LABELS[activeProfile] || activeProfile}</strong>.</p>
      </div>
    );
  }

  return (
    <div className="dm-remove-list">
      <p className="config-hint" style={{ margin: "10px 14px" }}>
        Display configs for profile: <strong>{PROFILE_LABELS[activeProfile] || activeProfile}</strong>
      </p>
      {configured.map(cfg => {
        const dev = devices.find(d => d.deviceId === cfg.deviceId);
        return (
          <div key={cfg.deviceId} className="dm-remove-row">
            <div className="dm-remove-info">
              <strong>{cfg.displayName || dev?.name || cfg.deviceId}</strong>
              <span className="config-hint">{cfg.deviceId} · {cfg.presenceProfile}</span>
              {cfg.severityOverride && (
                <span className="health-chip">Override: {cfg.severityOverride}</span>
              )}
            </div>
            <button className="btn-danger" disabled={removing === cfg.deviceId}
              onClick={() => doRemove(cfg)}>
              <Trash2 size={14}/> {removing === cfg.deviceId ? "Removing…" : "Remove"}
            </button>
          </div>
        );
      })}
    </div>
  );
}

function DisplayConfigForm({ device, profile, onSaved }) {
  const { displayConfigs, auth } = useApp();
  const existing = displayConfigs?.[device.deviceId];

  const [displayName,      setDisplayName]      = useState(existing?.displayName || "");
  const [severityOverride, setSeverityOverride] = useState(existing?.severityOverride || "");
  const [labelMap,         setLabelMap]         = useState(existing?.stateLabelMap || {});
  const [newKey,  setNewKey]  = useState("");
  const [newVal,  setNewVal]  = useState("");
  const [saving,  setSaving]  = useState(false);
  const [saved,   setSaved]   = useState(false);

  // Sync form when device or existing config changes
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    setDisplayName(existing?.displayName || "");
    setSeverityOverride(existing?.severityOverride || "");
    setLabelMap(existing?.stateLabelMap || {});
    setSaved(false);
  }, [device.deviceId, JSON.stringify(existing)]);

  const save = async () => {
    setSaving(true);
    const apiBase = device.location === "home" ? LOCATIONS.home.apiBase : LOCATIONS.cabin.apiBase;
    await auth.authedFetch(`${apiBase}/api/devices/${device.deviceId}/display-config?profile=${profile}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ displayName, stateLabelMap: labelMap, severityOverride }),
    }).catch(() => {});
    setSaving(false);
    setSaved(true);
    onSaved();
    setTimeout(() => setSaved(false), 2000);
  };

  const addLabel = () => {
    if (!newKey.trim()) return;
    setLabelMap(m => ({ ...m, [newKey.trim()]: newVal }));
    setNewKey(""); setNewVal("");
  };
  const removeLabel = (k) => setLabelMap(m => { const n = { ...m }; delete n[k]; return n; });

  return (
    <div className="dm-edit-form">
      <div className="dm-detail-name">{device.name || device.deviceId}</div>
      <p className="config-hint">Display overrides for: <strong>{PROFILE_LABELS[profile] || profile}</strong></p>

      <label>Custom display name
        <input placeholder={device.name} value={displayName}
          onChange={e => { setDisplayName(e.target.value); setSaved(false); }} />
      </label>

      <label>Severity override
        <select value={severityOverride}
          onChange={e => { setSeverityOverride(e.target.value); setSaved(false); }}>
          <option value="">Default (auto from status)</option>
          <option value="OK">OK — green</option>
          <option value="WARN">Warn — orange</option>
          <option value="ALERT">Alert — red</option>
        </select>
      </label>

      <div className="dm-section-label">State label overrides</div>
      <p className="config-hint" style={{ marginBottom: 6 }}>
        Map raw status → display label (e.g. ONLINE → "Unlocked")
      </p>

      {Object.entries(labelMap).map(([k, v]) => (
        <div key={k} className="dm-label-row">
          <code className="dm-label-key">{k}</code>
          <span>→</span>
          <input value={v} onChange={e => setLabelMap(m => ({ ...m, [k]: e.target.value }))} />
          <button className="btn-ghost" onClick={() => removeLabel(k)}><Trash2 size={13}/></button>
        </div>
      ))}

      <div className="dm-label-row dm-label-add">
        <input placeholder="State (e.g. ONLINE)" value={newKey}
          onChange={e => setNewKey(e.target.value)}
          onKeyDown={e => e.key === "Enter" && addLabel()} />
        <span>→</span>
        <input placeholder="Label (e.g. Unlocked)" value={newVal}
          onChange={e => setNewVal(e.target.value)}
          onKeyDown={e => e.key === "Enter" && addLabel()} />
        <button className="btn-ghost" onClick={addLabel}><Plus size={13}/></button>
      </div>

      <div className="modal-actions">
        {saved && <span className="save-ok"><CheckCircle size={13}/> Saved</span>}
        <button className="btn-primary" onClick={save} disabled={saving}>
          {saving ? "Saving…" : "Save overrides"}
        </button>
      </div>
    </div>
  );
}

function severityClass(override) {
  return { OK: "state-ok", WARN: "state-warn", ALERT: "state-alarm" }[override] || null;
}

function KpiTile({ icon: Icon, label, value, state, deviceId }) {
  const { displayConfigs } = useApp();
  const cfg = deviceId ? displayConfigs?.[deviceId] : null;

  const effectiveLabel = cfg?.displayName || label;
  const effectiveValue = cfg?.stateLabelMap?.[state] || cfg?.stateLabelMap?.[value] || value;
  const stCls          = severityClass(cfg?.severityOverride) || stateColor(state);
  const badgeLabel     = cfg?.stateLabelMap?.[state] || state || "UNKNOWN";

  return (
    <div className={`kpi-tile kpi-${stCls}`}>
      <Icon size={22} />
      <div className="kpi-label">{effectiveLabel}</div>
      <div className="kpi-value">{effectiveValue}</div>
      <span className={`state-badge ${stCls}`}>{badgeLabel}</span>
    </div>
  );
}

// ─── Panel: Rules Engine ───────────────────────────────────────────────────
// Per-location Node-RED section — mirrors LocationMonitoringSection's
// split pattern so "Both" behaves the same way here as everywhere else
// in the app (user's explicit ask, 2026-08-08: "same context shift
// behavior for all locations... I only see one node red"). "All can live
// on the 920q for now" (user's words) — this doesn't stand up a second
// Node-RED instance, it just makes the UI honest about which flows it's
// actually showing: a location without its own configured instance falls
// back to Cabin's, labeled as a fallback rather than silently presented
// as if it were Home's own.
function LocationRulesSection({ locCfg }) {
  // Cabin is always the canonical/fallback source, never a fallback target.
  const isCabin = locCfg.id === "cabin";
  const hasOwnNodeRed = isCabin || (isLocationDeployed(locCfg) && !!locCfg.noderedUrl);
  const noderedUrl = hasOwnNodeRed ? locCfg.noderedUrl : LOCATIONS.cabin.noderedUrl;
  return (
    <div className="location-section rules-nodered">
      <div className="location-section-header">
        {locCfg.label} Automation Flows
        <a href={noderedUrl} target="_blank" rel="noreferrer" className="btn-ghost btn-ghost-sm">
          Open ↗
        </a>
      </div>
      {!hasOwnNodeRed && (
        <p className="config-hint">
          {locCfg.label} doesn't have its own Node-RED instance configured yet — showing Cabin's flows.
        </p>
      )}
      <div className="tailscale-hint">
        <Lock size={11} /> Won't load off Tailscale — the flow editor is cabin-network-only.
      </div>
      <iframe title={`Node-RED — ${locCfg.label}`} src={noderedUrl} className="embed-frame" />
    </div>
  );
}

export function RulesPanel() { // exported for src/App.test.jsx's location-split test
  const { activeLocation } = useApp();
  const locationIds = Object.keys(LOCATIONS);
  const locs = activeLocation === "both"
    ? locationIds.map(id => LOCATIONS[id])
    : [LOCATIONS[activeLocation] || LOCATIONS.cabin];

  return (
    <div className="panel-content">
      <AlertControls panelId="RULES_ENGINE" />
      <div className="panel-header-bar">
        <h2>Rules &amp; Alerts</h2>
      </div>
      <div className="rules-layout">
        <div className={locs.length > 1 ? "rules-nodered-split" : "rules-nodered-single"}>
          {locs.map(loc => <LocationRulesSection key={loc.id} locCfg={loc} />)}
        </div>
        <div className="rules-sidebar">
          <KafkaStatus location={activeLocation} />
          <BuiltinRules />
        </div>
      </div>
    </div>
  );
}

function KafkaStatus({ location }) {
  const loc = location === "both" ? "cabin + home" : (location || "cabin");
  const prefix = location === "home" ? "home" : "cabin";
  return (
    <div className="sidebar-card">
      <strong>Kafka Topics — {loc}</strong>
      <div className="kafka-topics">
        {[`${prefix}.events.raw`, `${prefix}.events.alerts`, `${prefix}.events.motion`].map(t => (
          <div key={t} className="kafka-topic">
            <span className="topic-dot">●</span>{t}
          </div>
        ))}
      </div>
      <p className="config-hint">Node-RED consumes {prefix}.events.raw. Broker: localhost:9092.</p>
    </div>
  );
}

function BuiltinRules() {
  const rules = [
    { id: 1, name: "Water Pressure Low",   trigger: "PSI < 30",   action: "Alert + email", active: true },
    { id: 2, name: "Water Pressure High",  trigger: "PSI > 75",   action: "Alert + email", active: true },
    { id: 3, name: "Freeze Risk",          trigger: "Temp < 38°F", action: "CRITICAL alert", active: true },
    { id: 4, name: "Smoke Alarm",          trigger: "alarm=true",  action: "CRITICAL + SMS", active: true },
    { id: 5, name: "Motion After Midnight", trigger: "motion + hour 0-6", action: "Notify", active: false },
  ];
  return (
    <div className="sidebar-card">
      <strong>Built-in Safety Rules</strong>
      <p className="config-hint">These Java-side rules run even when Node-RED is offline.</p>
      {rules.map(r => (
        <div key={r.id} className="rule-row">
          <span className={`rule-dot ${r.active ? "rule-active" : "rule-inactive"}`}>●</span>
          <div>
            <div className="rule-name">{r.name}</div>
            <div className="rule-detail">{r.trigger} → {r.action}</div>
          </div>
        </div>
      ))}
    </div>
  );
}

// ─── Nav alert system ──────────────────────────────────────────────────────
//
// State machine per panel (persisted to localStorage):
//   unconfigured  → no badge, panel shows "Enable monitoring" button
//   watching      → no badge, alert condition not yet met
//   warn          → orange dot, alert condition has been true < 20 min
//   critical      → red pulsing AlertTriangle, alert condition ≥ 20 min
//
// Reset always returns to "unconfigured". Nothing alerts until re-enabled.
//
// Panels that support alerting: DEVICE_MANAGER, MONITORING, RULES_ENGINE
// Alert conditions:
//   DEVICE_MANAGER / MONITORING: any device OFFLINE or ALARM
//   RULES_ENGINE:                escalates to warn when others are critical
//
const ALERT_PANELS   = ["DEVICE_MANAGER", "MONITORING", "RULES_ENGINE"];
const CRITICAL_MS    = 20 * 60 * 1000;
const ALERT_CFG_KEY  = "cabin-alert-cfg";  // localStorage key

function loadAlertCfg() {
  try { return JSON.parse(localStorage.getItem(ALERT_CFG_KEY)) || {}; }
  catch { return {}; }
}

export function alertEligibleOfflineCount(health) {
  if (typeof health?.alertEligibleOffline === "number") return health.alertEligibleOffline;
  return health?.offline || 0; // compatibility with an older backend during rollout
}

// cfg shape per panel: { enabled: bool, alertSince: ms|null }
function useNavAlerts() {
  const [cfg, setCfg] = useState(loadAlertCfg);
  // "level" derived each poll cycle, not stored
  const [levels, setLevels] = useState({});

  // Persist cfg changes
  useEffect(() => {
    localStorage.setItem(ALERT_CFG_KEY, JSON.stringify(cfg));
  }, [cfg]);

  const enableAlert  = (panelId) => setCfg(c => ({ ...c, [panelId]: { enabled: true,  alertSince: null } }));
  const resetAlert   = (panelId) => setCfg(c => ({ ...c, [panelId]: { enabled: false, alertSince: null } }));

  useEffect(() => {
    const check = async () => {
      let h = null;
      try { h = await fetch(`${LOCATIONS.cabin.apiBase}/api/system/health`).then(r => r.json()); }
      catch { return; }

      const now       = Date.now();
      const hasAlarm  = (h.alarm  || 0) > 0;
      const hasOffline= alertEligibleOfflineCount(h) > 0;
      const alertCondition = hasAlarm || hasOffline; // true = something needs attention

      setCfg(prev => {
        const next = { ...prev };
        for (const panelId of ALERT_PANELS) {
          const entry = prev[panelId] || { enabled: false, alertSince: null };
          if (!entry.enabled) { next[panelId] = entry; continue; }

          // RULES_ENGINE only alerts when device panels are in trouble
          const condition = panelId === "RULES_ENGINE" ? hasOffline : alertCondition;

          if (condition && entry.alertSince === null) {
            // Condition just started — start the timer
            next[panelId] = { ...entry, alertSince: now };
          } else if (!condition && entry.alertSince !== null) {
            // Condition cleared
            next[panelId] = { ...entry, alertSince: null };
          } else {
            next[panelId] = entry;
          }
        }
        return next;
      });

      // Derive display levels from the updated cfg (read from prev + above logic)
      setLevels(prev => {
        const next = {};
        for (const panelId of ALERT_PANELS) {
          // Re-read from localStorage since setCfg above is async
          const stored = loadAlertCfg()[panelId] || { enabled: false, alertSince: null };
          if (!stored.enabled || stored.alertSince === null) { next[panelId] = null; continue; }
          const dur = now - stored.alertSince;
          next[panelId] = dur >= CRITICAL_MS ? "critical" : "warn";
        }
        return next;
      });
    };
    check();
    const t = setInterval(check, 30_000);
    return () => clearInterval(t);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return { levels, cfg, enableAlert, resetAlert };
}

// ─── Draggable order ──────────────────────────────────────────────────────
// Manages a user-defined sort order for a list of items, persisted in
// localStorage. Alarm items auto-pin to the front regardless of saved order.
// Unknown items (newly registered devices) append to the tail.
function useDraggableOrder(storageKey, items, isAlarm) {
  const getId = (item) => item.deviceId || item.id || item.name;

  const [savedOrder, setSavedOrder] = useState(() => {
    try { return JSON.parse(localStorage.getItem(storageKey)) || []; }
    catch { return []; }
  });

  useEffect(() => {
    localStorage.setItem(storageKey, JSON.stringify(savedOrder));
  }, [storageKey, savedOrder]);

  const ordered = useMemo(() => {
    const idToItem = new Map(items.map(i => [getId(i), i]));
    // Saved IDs first (filter out stale), then new unknowns at tail
    const inOrder = [
      ...savedOrder.filter(id => idToItem.has(id)).map(id => idToItem.get(id)),
      ...items.filter(i => !savedOrder.includes(getId(i))),
    ];
    if (!isAlarm) return inOrder;
    return [...inOrder.filter(isAlarm), ...inOrder.filter(i => !isAlarm(i))];
  }, [items, savedOrder, isAlarm]); // eslint-disable-line react-hooks/exhaustive-deps

  const pinnedCount = isAlarm ? ordered.filter(isAlarm).length : 0;

  const reorder = useCallback((fromIdx, toIdx) => {
    if (fromIdx === toIdx || fromIdx < pinnedCount || toIdx < pinnedCount) return;
    const next = [...ordered];
    const [moved] = next.splice(fromIdx, 1);
    next.splice(toIdx, 0, moved);
    setSavedOrder(next.map(getId));
  }, [ordered, pinnedCount]); // eslint-disable-line react-hooks/exhaustive-deps

  return { ordered, pinnedCount, reorder };
}

// ─── Presence profile ─────────────────────────────────────────────────────
// ─── Hub locations: merge server-configured locations into LOCATIONS ──────
// LOCATIONS (module-level, not React state) is read synchronously from ~30
// call sites throughout this file, many of them outside any component that
// could easily receive it as a prop -- converting all of them to read from
// state instead is real, separately-scoped work (see
// docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md §1b/§3). This
// hook takes the lower-risk path for now: cabin/home stay exactly as
// hardcoded today (still the guaranteed-present fallback every other call
// site assumes), and any *additional* location the backend knows about
// (GET /api/locations, backed by the new hub_location table) gets merged
// into the same LOCATIONS object by id, so it starts showing up in
// LocationSwitcher and the My Places card grid without a source change.
// Bootstraps off LOCATIONS.cabin.apiBase, matching every other
// cross-cutting fetch in this file (usePresence, useNavAlerts, /api/system/
// health) that already treats cabin as the primary backend to reach first.

// Pure merge step, no fetch/state inside -- takes the current LOCATIONS
// object and a raw /api/locations response, returns a NEW object with each
// returned row merged in by id (existing fields preserved when the API
// value is missing/falsy). Split out from the hook below so it's directly
// unit-testable -- see src/App.test.jsx.
export function mergeHubLocations(current, apiList) {
  const merged = { ...current };
  for (const loc of apiList || []) {
    if (!loc?.id) continue;
    const existing = merged[loc.id];
    merged[loc.id] = {
      id: loc.id,
      label: loc.label || loc.id,
      apiBase: loc.apiBase || existing?.apiBase || null,
      wsBase: loc.wsBase || existing?.wsBase || null,
      grafanaUrl: loc.grafanaUrl || existing?.grafanaUrl || null,
      noderedUrl: loc.noderedUrl || existing?.noderedUrl || null,
      haUrl: loc.haUrl || existing?.haUrl || null,
      frigateUrl: loc.frigateUrl || existing?.frigateUrl || null,
      z2mUrl: loc.z2mUrl || existing?.z2mUrl || null,
      familyHubUrl: loc.familyHubUrl || existing?.familyHubUrl || null,
    };
  }
  return merged;
}

function useHubLocations() {
  const [version, setVersion] = useState(0);

  useEffect(() => {
    fetch(`${LOCATIONS.cabin.apiBase}/api/locations`)
      .then(r => r.json())
      .then(list => {
        const hadAnyValidRow = (list || []).some(loc => loc?.id);
        if (!hadAnyValidRow) return;
        const merged = mergeHubLocations(LOCATIONS, list);
        // LOCATIONS itself isn't state -- mutate it in place (so the ~30
        // call sites reading it synchronously elsewhere in this file see
        // the update immediately) and bump `version` so components that
        // render from it (LocationSwitcher, FamilyHubPanel's card grid)
        // actually re-render to reflect the mutation.
        Object.assign(LOCATIONS, merged);
        setVersion(v => v + 1);
      })
      .catch(() => {}); // offline backend: cabin/home hardcoded defaults still work exactly as before
  }, []);

  return version;
}

// Found 2026-08-08 (user report): this was a purely manual value with a
// map-pin icon that read as "your detected location" -- nothing behind
// it derived it from anything real, despite AutomationRuleService (
// backend) using it for real security-event severity decisions. Backend
// now derives it from live per-person, per-location presence signals
// (MqttBridgeService.handlePresenceTopic -> PresenceService.
// recomputeFromSignals -- N people x M locations, not cabin/Nate-only)
// whenever any exist; autoDerived/signals below are surfaced so the
// toggle can show whether the current value is live-detected or a
// manual fallback (a location/instance with no presence automation
// configured yet still needs the manual path -- see PresenceService's
// class comment).
function usePresence() {
  const [profile, setProfileState] = useState("AT_HOME");
  const [options, setOptions]      = useState([]);
  const [autoDerived, setAutoDerived] = useState(false);
  const [signals, setSignals]      = useState([]);

  const refresh = useCallback(() => {
    fetch(`${LOCATIONS.cabin.apiBase}/api/presence`)
      .then(r => r.json())
      .then(data => {
        setProfileState(data.profile);
        setOptions(data.options || []);
        setAutoDerived(!!data.autoDerived);
        setSignals(data.signals || []);
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    refresh();
    // Presence is live, MQTT-driven state on the backend -- poll so a
    // signal that arrived from someone else's phone (not this browser's
    // own PUT) shows up here too, same reasoning as every other
    // cross-cutting live-state poll in this file.
    const t = setInterval(refresh, 15000);
    return () => clearInterval(t);
  }, [refresh]);

  const setProfile = (p) => {
    setProfileState(p); // optimistic
    setAutoDerived(false); // a manual PUT is never auto-derived -- see PresenceService.set()
    fetch(`${LOCATIONS.cabin.apiBase}/api/presence`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ profile: p }),
    }).then(r => r.json()).then(d => setProfileState(d.profile)).catch(() => {});
  };

  return { profile, setProfile, options, autoDerived, signals };
}

// Found 2026-08-08 (user question, following the presence fix above):
// "armed" already exists as a real, live, retained MQTT signal
// (cabin/security/armed_away, self-healing -- republishes on toggle and
// on HA restart, see docs/ontology.yaml's automation_cabin_security_
// publish_arm_state) -- cabin-backend just never subscribed to it, so
// the UI had no way to answer "is this actually armed" for a user
// looking at an ambiguous alert. Same wire-up pattern as usePresence,
// deliberately not folded into it -- arming and presence are different
// concerns with different sources of truth (a human toggle vs. a WiFi
// signal) even though both ride the same MQTT bridge.
function useSecurityState() {
  const [states, setStates] = useState({}); // { [location]: { armed, lastUpdated } }

  const refresh = useCallback(() => {
    fetch(`${LOCATIONS.cabin.apiBase}/api/security`)
      .then(r => r.json())
      .then(setStates)
      .catch(() => {});
  }, []);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 15000);
    return () => clearInterval(t);
  }, [refresh]);

  return states;
}

// ─── Display configs (bulk, for active profile) ────────────────────────────
function useDisplayConfigs(profile) {
  const [configs, setConfigs] = useState({}); // deviceId → DeviceDisplayConfig

  const refetch = useCallback(() => {
    if (!profile) return;
    fetch(`${LOCATIONS.cabin.apiBase}/api/devices/display-config?profile=${profile}`)
      .then(r => r.json())
      .then(list => {
        const map = {};
        list.forEach(c => { map[c.deviceId] = c; });
        setConfigs(map);
      })
      .catch(() => {});
  }, [profile]);

  useEffect(() => { refetch(); }, [refetch]);

  return { configs, refetch };
}

// ─── Presence toggle (toolbar widget) ─────────────────────────────────────
const PROFILE_LABELS = {
  AT_HOME: "At Home", AT_CABIN: "At Cabin", AWAY: "Away", BOTH_OCCUPIED: "Both Occupied",
};

// Exported for src/App.test.jsx -- builds the presence pin's tooltip text
// from real signals rather than a hardcoded string, so it says something
// concrete ("nate at cabin, emma at home") instead of just "auto" or
// "manual". Pure function, no fetch/state, for direct unit testing.
export function formatPresenceSignals(signals) {
  const present = (signals || []).filter(s => s.present);
  if (present.length === 0) return "No one currently detected present";
  return present.map(s => `${s.personId} at ${s.location}`).join(", ");
}

function PresenceToggle() {
  const { activeProfile, setProfile, presenceOptions, presenceAutoDerived, presenceSignals } = useApp();
  const opts = presenceOptions.length > 0
    ? presenceOptions
    : Object.entries(PROFILE_LABELS).map(([value, label]) => ({ value, label }));
  // Found 2026-08-08: this pin's icon reads as "your detected location,"
  // but the value behind it was purely a manual toggle with nothing real
  // driving it -- see PresenceService's class comment for why that
  // mattered beyond cosmetics (it feeds real security-severity logic).
  // Auto-derived now wins whenever any real signal exists; the select
  // below still allows a manual override for an instance/location with
  // no presence automation configured yet (or a guest with no tracked
  // phone) -- see usePresence's comment.
  const title = presenceAutoDerived
    ? `Live-detected: ${formatPresenceSignals(presenceSignals)}`
    : "Manually set — no live presence signal detected yet for this instance";
  return (
    <div className="presence-toggle" title={title}>
      <MapPin size={13} style={{ opacity: 0.6 }}/>
      {presenceAutoDerived && <span className="presence-live-dot" aria-label="Live-detected" />}
      <select className="presence-select" value={activeProfile}
        onChange={e => setProfile(e.target.value)}>
        {opts.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </div>
  );
}

// Exported for src/App.test.jsx. Pure function so the "unknown" case
// (no armed_away signal ever received for this location) is impossible
// to mix up with "disarmed" -- those are very different things to tell
// a user looking at an ambiguous alert, and conflating them was exactly
// the kind of gap that prompted this feature. See SecurityBadge below.
export function formatArmedTitle(state) {
  if (!state) return "No armed/disarmed signal received yet for this location";
  const ts = new Date(state.lastUpdated).toLocaleTimeString();
  return `${state.armed ? "Armed" : "Disarmed"} (as of ${ts})`;
}

// Found 2026-08-08, directly following the presence fix: "armed" is
// exactly the same class of gap -- a real, live, self-healing MQTT
// signal (cabin/security/armed_away, an HA automation, see
// docs/ontology.yaml's automation_cabin_security_publish_arm_state)
// that cabin-backend had simply never subscribed to, leaving the UI
// with no way to answer "is this actually armed" for a user looking at
// an ambiguous alert. See useSecurityState's comment for why this is a
// separate hook/badge from presence rather than folded into it.
function SecurityBadge() {
  const { securityStates, activeLocation } = useApp();
  const loc = activeLocation !== "both" ? activeLocation : "cabin";
  const state = securityStates?.[loc];
  const title = formatArmedTitle(state);

  if (!state) {
    return (
      <span className="security-badge security-unknown" title={title}>
        <ShieldAlert size={13} style={{ opacity: 0.5 }}/> Unknown
      </span>
    );
  }
  return state.armed ? (
    <span className="security-badge security-armed" title={title}>
      <Lock size={13}/> Armed
    </span>
  ) : (
    <span className="security-badge security-disarmed" title={title}>
      <Unlock size={13}/> Disarmed
    </span>
  );
}

// ─── Navigation Rail ───────────────────────────────────────────────────────
function NavRail({ active, onSelect, alertLevels }) {
  const alerts = alertLevels;

  return (
    <nav className="nav-rail">
      <img className="nav-logo" src="/hodgson-crest.svg" alt="" />
      {PANELS.map(p => {
        const level = alerts[p.id];
        const isCritical = level === "critical";
        const isWarn     = level === "warn";
        const Icon = p.icon;

        return (
          <button key={p.id}
            className={[
              "nav-item",
              active === p.id ? "nav-active" : "",
              isCritical ? "nav-critical" : "",
              isWarn && !isCritical ? "nav-warn" : "",
            ].filter(Boolean).join(" ")}
            onClick={() => onSelect(p.id)}
            title={`${p.label}${isCritical ? " — CRITICAL" : isWarn ? " — attention needed" : ""}`}>

            {/* Critical: swap icon for AlertTriangle with animated pulse */}
            {isCritical
              ? <AlertTriangle size={20} className="nav-alert-icon nav-alert-critical" />
              : <Icon size={20} className={isWarn ? "nav-alert-icon nav-alert-warn" : ""} />
            }
            <span className="nav-label">{p.label}</span>

            {/* Dot badge for warn; no dot for critical (whole icon already changes) */}
            {isWarn && !isCritical && <span className="nav-badge nav-badge-warn" />}
          </button>
        );
      })}
    </nav>
  );
}

// ─── Authenticated application tree ───────────────────────────────────────
// This component owns every data-loading hook. App does not mount it until a
// Family Hub handoff or direct cabin sign-in has produced a valid session.
export function AuthenticatedApp({ auth }) {
  const [activePanel,    setActivePanel]    = useState(() => resolvePostAuthPanel());
  const [activeLocation, setActiveLocation] = useState("cabin");
  const [devices,        setDevices]        = useState([]);
  const [config,         setConfig]         = useState({});
  const [connected,      setConnected]      = useState(false);
  const [apiError,       setApiError]       = useState(null); // { message, at } | null -- see refreshDevices
  const { levels: alertLevels, cfg: alertCfg, enableAlert, resetAlert } = useNavAlerts();
  useHubLocations(); // merges GET /api/locations into LOCATIONS; re-renders this tree when it changes
  const { profile: activeProfile, setProfile, options: presenceOptions, autoDerived: presenceAutoDerived, signals: presenceSignals } = usePresence();
  const securityStates = useSecurityState();
  const { configs: displayConfigs, refetch: refreshDisplayConfigs } = useDisplayConfigs(activeProfile);

  // Browser back/forward cache can restore the exact in-memory React tree
  // without remounting. Treat that restoration as a fresh entry so an
  // accidental prior panel never defeats the deterministic landing contract.
  useEffect(() => {
    const resetRestoredLanding = event => {
      if (event.persisted) setActivePanel(resolvePostAuthPanel());
    };
    window.addEventListener("pageshow", resetRestoredLanding);
    return () => window.removeEventListener("pageshow", resetRestoredLanding);
  }, []);

  // locationCfg is null when "both" — individual components handle that case.
  const locationCfg = activeLocation !== "both" ? LOCATIONS[activeLocation] : null;

  // Found 2026-08-03 (external review): the "API offline" badge pinged
  // /actuator/health directly, which has no CORS configuration at all
  // (unlike every business endpoint, which carries @CrossOrigin) -- the
  // browser blocked reading that response every time regardless of
  // whether the backend was actually up, so the badge was permanently
  // wrong. Rather than add CORS to Actuator just to drive a status
  // badge, "connected" is now derived from whether the device fetch this
  // panel already depends on actually succeeded.
  //
  // Found 2026-08-07: "connected" required EVERY attempted fetch to
  // succeed, including home-hub's -- which is always going to fail
  // (isLocationDeployed() is false for it) until home-hub is actually
  // deployed. Viewing "Home" or "Both" therefore showed "API offline"
  // permanently regardless of cabin's real health. Only fetches for
  // *deployed* locations now count toward connected/apiError; an
  // undeployed location's fetch is still attempted (so its devices show
  // up the moment it does go live) but its failure is expected, not an
  // outage.
  const refreshDevices = useCallback(() => {
    // Fetch from cabin hub always; also fetch home hub when viewing home or both.
    const attempts = [];
    if (activeLocation === "cabin" || activeLocation === "both") {
      attempts.push({ loc: LOCATIONS.cabin, promise:
        fetch(`${LOCATIONS.cabin.apiBase}/api/devices`)
          .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); })
      });
    }
    if (activeLocation === "home" || activeLocation === "both") {
      attempts.push({ loc: LOCATIONS.home, promise:
        fetch(`${LOCATIONS.home.apiBase}/api/devices`)
          .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); })
      });
    }
    Promise.allSettled(attempts.map(a => a.promise)).then(results => {
      const succeeded = results.filter(r => r.status === "fulfilled");
      setDevices(succeeded.map(r => r.value).flat());

      const relevant = attempts
        .map((a, i) => ({ loc: a.loc, result: results[i] }))
        .filter(a => isLocationDeployed(a.loc));
      const relevantFailure = relevant.find(a => a.result.status === "rejected");

      setConnected(relevant.length === 0 || !relevantFailure);
      setApiError(relevantFailure
        ? { message: `${relevantFailure.loc.label}: ${relevantFailure.result.reason?.message || "unreachable"}`, at: new Date() }
        : null);
    });
  }, [activeLocation]);

  useEffect(() => {
    refreshDevices();
    const apiBase = locationCfg?.apiBase || LOCATIONS.cabin.apiBase;
    fetch(`${apiBase}/api/dashboard/config`)
      .then(r => r.json()).then(setConfig).catch(() => {});
    const t = setInterval(refreshDevices, 15000);
    return () => clearInterval(t);
  }, [refreshDevices, locationCfg]);

  const locationLabel = activeLocation === "both"
    ? "Cabin + Home"
    : (LOCATIONS[activeLocation]?.label || "Hub");

  return (
    <AppContext.Provider value={{
      devices, config, refreshDevices,
      activeLocation, locationCfg,
      alertCfg, enableAlert, resetAlert,
      activeProfile, setProfile, presenceOptions, presenceAutoDerived, presenceSignals,
      securityStates,
      displayConfigs, refreshDisplayConfigs,
      auth,
    }}>
      <div className="app-shell">
        <NavRail active={activePanel} onSelect={setActivePanel} alertLevels={alertLevels} />
        <main className="main-area">
          <div className="main-toolbar">
            <span className="platform-name">{locationLabel} — Orchestration Hub</span>
            <div className="toolbar-right">
              <LocationSwitcher active={activeLocation} onChange={setActiveLocation} />
              <PresenceToggle />
              <SecurityBadge />
              <ThemeSwitcher />
              <span className="auth-account" title={auth.authSource === "FAMILY_HUB" ? "Inherited from Family Hub" : "Signed in here"}>
                {auth.userEmail}
              </span>
              <button className="btn-secondary auth-signout" onClick={auth.signOut}>Sign out</button>
              {connected ? (
                <span className="api-status api-ok">
                  <CheckCircle size={12}/> API
                </span>
              ) : (
                <a
                  className="api-status api-err"
                  href={`${(locationCfg || LOCATIONS.cabin).apiBase}/actuator/health`}
                  target="_blank"
                  rel="noreferrer"
                  title={apiError
                    ? `${apiError.message} (as of ${apiError.at.toLocaleTimeString()}) — click to open /actuator/health`
                    : "click to open /actuator/health"}
                >
                  <AlertTriangle size={12}/> API offline
                </a>
              )}
              <span className="device-count">{devices.length} devices</span>
            </div>
          </div>
          <div className="panel-area">
            {activePanel === "FAMILY_HUB"     && <FamilyHubPanel />}
            {activePanel === "FAMILY_CONFIG"  && <FamilyConfigPanel auth={auth} />}
            {activePanel === "DEVICE_MANAGER" && <DeviceManagerPanel />}
            {activePanel === "MONITORING"     && <MonitoringPanel active={true} />}
            {activePanel === "RULES_ENGINE"   && <RulesPanel />}
            {activePanel === "CAMERA_EVENTS"  && <CameraEventsPanel auth={auth} />}
            {activePanel === "OPPORTUNITY_MAP" && <OpportunityMapPanel auth={auth} />}
          </div>
        </main>
      </div>
    </AppContext.Provider>
  );
}

// ─── Root authentication boundary ─────────────────────────────────────────
export function App() {
  const auth = usePlatformAuth();
  if (!auth.signedIn) return <AuthGate auth={auth} />;
  return <AuthenticatedApp auth={auth} />;
}

// Guarded so this module can be imported for its exported pure functions
// (isCameraEvent, mergeHubLocations) from a unit test without a real
// index.html/#root present -- see src/App.test.jsx. Always truthy in the
// actual app (index.html always has <div id="root">).
const rootEl = document.getElementById("root");
if (rootEl) {
  createRoot(rootEl).render(
    <ThemeProvider>
      <App />
    </ThemeProvider>
  );
}
