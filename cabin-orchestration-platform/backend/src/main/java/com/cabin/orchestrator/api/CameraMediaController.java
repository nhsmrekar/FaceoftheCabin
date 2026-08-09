package com.cabin.orchestrator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Proxies camera media (snapshot, clip, live stream) from Frigate through
 * cabin-backend so cabin-ui's authenticated Camera Events panel can show
 * real video/images without exposing Frigate's own admin API/UI publicly
 * (Frigate itself stays Tailscale-only, same reasoning as Node-RED/HA —
 * see ROADMAP.md's Phase 6). Gated by GoogleAuthInterceptor via
 * WebConfig's /api/camera/** pattern — this is meaningfully more
 * sensitive than the metadata-only public activity widget (Phase 4).
 *
 * Frigate's real endpoint shapes confirmed by reading its installed
 * source directly (frigate/api/media.py) on the M920q, not assumed from
 * generic docs:
 *   GET /api/events/{event_id}/snapshot.jpg
 *   GET /api/events/{event_id}/clip.mp4
 *   GET /api/{camera_name}                   (MJPEG live stream)
 * {event_id} here is Frigate's own event id (the "frigateEventId" field
 * MqttBridgeService now captures from the MQTT events payload), not
 * cabin-backend's own CabinEvent.eventId.
 */
@RestController
@RequestMapping("/api/camera")
@CrossOrigin(
    origins = "${cabin.security.cors.allowedOrigins:https://hub.unicornpingpong.com,https://cabin.unicornpingpong.com,http://localhost:5173,http://127.0.0.1:5173,http://localhost:4080,http://127.0.0.1:4080,http://localhost:4081,http://127.0.0.1:4081}",
    allowCredentials = "true")
public class CameraMediaController {

    private static final Logger log = LoggerFactory.getLogger(CameraMediaController.class);

    @Value("${cabin.devices.cameras.frigateUrl:http://frigate:5000}")
    private String frigateUrl;

    // Blink cameras (unlike the Reolink, which is continuously live over
    // native RTSP) are motion-triggered, not truly live -- blinkbridge
    // synthesizes Frigate's normal feed from Blink's own motion clips.
    // "Watch live" for a Blink camera instead triggers a real, on-demand
    // Blink liveview session via blinkbridge's control API (added
    // 2026-08-03 -- see blinkbridge's own patches.py/main.py for why this
    // was needed and how the session is relayed into the same mediamtx
    // path Frigate already reads from). Unset/empty by default -- a
    // camera not listed here is assumed to already be truly live (like
    // the Reolink), and start/stop become harmless no-ops for it.
    @Value("${cabin.devices.cameras.blinkBridgeUrl:http://blinkbridge:8811}")
    private String blinkBridgeUrl;

    // "cabinCameraName:blinkDeviceName" pairs, comma-separated -- e.g.
    // "driveway:Outdoor 4 - DHEE". The right-hand side is Blink's own
    // device name (used as blinkbridge's URL path segment), which is
    // unrelated to and doesn't need to match this platform's camera
    // naming (see docs/ontology.yaml's camera rename history for why
    // those two naming layers are deliberately kept separate).
    @Value("${cabin.devices.cameras.blinkCameraMap:}")
    private String blinkCameraMapRaw;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private final ObjectMapper mapper = new ObjectMapper();

    private java.util.Map<String, String> blinkCameraMap() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (blinkCameraMapRaw == null || blinkCameraMapRaw.isBlank()) return map;
        for (String pair : blinkCameraMapRaw.split(",")) {
            int idx = pair.indexOf(':');
            if (idx <= 0) continue;
            map.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
        }
        return map;
    }

    /**
     * Real, current camera names from Frigate's own config — not derived
     * from event history (that was the original approach in cabin-ui's
     * "watch live" button list; it broke the moment cameras got renamed
     * 2026-08-02, since historical events keep their old sourceDeviceId
     * forever by design, but Frigate itself only recognizes the new
     * names — a real bug found via live testing, not caught by review).
     */
    @GetMapping(value = "/list")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> cameras = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(frigateUrl + "/api/config"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Frigate config fetch returned {}", resp.statusCode());
                return cameras;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode camerasNode = root.path("cameras");
            Iterator<String> names = camerasNode.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                boolean enabled = camerasNode.path(name).path("enabled").asBoolean(false);
                cameras.add(Map.of("name", name, "enabled", enabled));
            }
        } catch (IOException | InterruptedException e) {
            log.warn("Failed to fetch camera list from Frigate: {}", e.getMessage());
        }
        return cameras;
    }

    @GetMapping(value = "/events/{frigateEventId}/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable String frigateEventId) {
        return proxyBytes("/api/events/" + frigateEventId + "/snapshot.jpg", MediaType.IMAGE_JPEG);
    }

    @GetMapping(value = "/events/{frigateEventId}/clip")
    public ResponseEntity<byte[]> clip(@PathVariable String frigateEventId) {
        return proxyBytes("/api/events/" + frigateEventId + "/clip.mp4", MediaType.valueOf("video/mp4"));
    }

    /** MJPEG live stream, proxied continuously — not buffered like snapshot/clip above. */
    @GetMapping(value = "/{cameraName}/live")
    public ResponseEntity<StreamingResponseBody> live(@PathVariable String cameraName) {
        String url = frigateUrl + "/api/" + cameraName;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        StreamingResponseBody body = (OutputStream out) -> {
            try {
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    log.warn("Frigate live stream for {} returned {}", cameraName, resp.statusCode());
                    return;
                }
                try (InputStream in = resp.body()) {
                    in.transferTo(out);
                }
            } catch (IOException | InterruptedException e) {
                // Expected whenever the client just closes the tab/panel —
                // not a real error, don't log at warn for the common case.
                log.debug("Live stream for {} ended: {}", cameraName, e.getMessage());
            }
        };

        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("multipart/x-mixed-replace; boundary=frame"))
            .body(body);
    }

    /**
     * POST /api/camera/{cameraName}/liveview/start
     * Triggers a real, on-demand Blink liveview session for cameras
     * listed in cabin.devices.cameras.blinkCameraMap. A no-op (ok:true,
     * skipped:true) for any other camera -- e.g. the Reolink, which is
     * already continuously live over native RTSP and has nothing to
     * "start." cabin-ui calls this right before rendering the live
     * <img> for a camera; see App.jsx's CameraEventsPanel.
     */
    @PostMapping(value = "/{cameraName}/liveview/start")
    public ResponseEntity<?> startLiveview(@PathVariable String cameraName) {
        return proxyLiveviewControl(cameraName, "start");
    }

    /** POST /api/camera/{cameraName}/liveview/stop -- see startLiveview's javadoc. Called when the viewer closes the live view. */
    @PostMapping(value = "/{cameraName}/liveview/stop")
    public ResponseEntity<?> stopLiveview(@PathVariable String cameraName) {
        return proxyLiveviewControl(cameraName, "stop");
    }

    private ResponseEntity<?> proxyLiveviewControl(String cameraName, String action) {
        String blinkName = blinkCameraMap().get(cameraName);
        if (blinkName == null) {
            return ResponseEntity.ok(Map.of("ok", true, "skipped", true));
        }
        try {
            String encoded = java.net.URLEncoder.encode(blinkName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            HttpRequest req = HttpRequest.newBuilder(URI.create(blinkBridgeUrl + "/liveview/" + encoded + "/" + action))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("blinkbridge liveview {} for {} returned {}", action, cameraName, resp.statusCode());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("ok", false));
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(resp.body());
        } catch (IOException | InterruptedException e) {
            log.warn("Failed to reach blinkbridge for liveview {} on {}: {}", action, cameraName, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("ok", false, "error", "blinkbridge unreachable"));
        }
    }

    private ResponseEntity<byte[]> proxyBytes(String path, MediaType contentType) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(frigateUrl + path))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404) {
                return ResponseEntity.notFound().build();
            }
            if (resp.statusCode() != 200) {
                log.warn("Frigate returned {} for {}", resp.statusCode(), path);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }
            return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(resp.body());
        } catch (IOException | InterruptedException e) {
            log.warn("Failed to proxy {} from Frigate: {}", path, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
