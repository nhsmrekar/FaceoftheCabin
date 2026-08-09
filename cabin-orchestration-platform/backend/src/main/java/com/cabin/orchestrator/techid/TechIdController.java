package com.cabin.orchestrator.techid;

import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic findings intake, plus the human-facing "Opportunity
 * Map" surface built on top of it (see docs/PRODUCT_NOTES.md's
 * 2026-08-03 UX Lead / Application Architect review for the full
 * design). Three auth tiers on purpose:
 *
 *  - POST /api/tech-id/findings (submit) is gated by a shared-secret API
 *    key (X-Tech-Id-Api-Key), not GoogleAuthInterceptor -- submitters
 *    are automated services (the reference Claude Code routine, an
 *    operator's paid scanning tier, or an instance owner's own AI of
 *    choice), not signed-in humans. Any caller holding the configured
 *    key can submit as any `provider` name it claims -- the key
 *    controls *whether* a caller may submit, not *which* provider
 *    identity it may claim. That's deliberate: providers are free text,
 *    so there's no registry of provider identities to check a claim
 *    against.
 *  - GET (read) is open, matching the /api/events and /api/devices
 *    precedent -- findings aren't more sensitive than event/device data.
 *  - Everything else on this controller -- PATCH (Think: adjudicate),
 *    POST /{id}/actions (log a See/Think/Act interaction), and GET
 *    /{id}/actions (who did what -- carries real account emails, unlike
 *    the findings themselves) -- requires a human, riding
 *    GoogleAuthInterceptor via WebConfig's /api/tech-id/findings/**
 *    pattern. That interceptor exempts only the exact collection path
 *    (`/api/tech-id/findings`, no suffix) for GET/POST; every sub-path
 *    here falls through to the normal token check.
 */
@RestController
@RequestMapping("/api/tech-id/findings")
@CrossOrigin(
    origins = "${cabin.security.cors.allowedOrigins:https://hub.unicornpingpong.com,https://cabin.unicornpingpong.com,http://localhost:5173,http://127.0.0.1:5173,http://localhost:4080,http://127.0.0.1:4080,http://localhost:4081,http://127.0.0.1:4081}",
    allowCredentials = "true")
public class TechIdController {

    private final TechIdFindingService findingService;
    private final TechIdFindingActionService actionService;

    @Value("${cabin.techid.apiKey:}")
    private String apiKey;

    public TechIdController(TechIdFindingService findingService, TechIdFindingActionService actionService) {
        this.findingService = findingService;
        this.actionService = actionService;
    }

    public record SubmitRequest(
        String entityId,
        List<String> relatedEntityIds,
        String provider,
        String findingType,
        String summary,
        String confidence,
        List<String> sources,
        TechIdFinding.Actionable actionable,
        Long checkedAt
    ) {}

    public record StatusUpdateRequest(String status) {}

    public record ActionRequest(String actionType, String detail) {}

    private static final List<String> VALID_ACTION_TYPES = List.of(
        "see_expand", "think_include", "think_dismiss",
        "act_purchase_elsewhere", "act_request_core", "act_do_it_now");

    @PostMapping
    public ResponseEntity<?> submit(@RequestBody SubmitRequest body, HttpServletRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Tech ID Service submission is not configured on this instance (cabin.techid.apiKey unset)."));
        }
        String presented = request.getHeader("X-Tech-Id-Api-Key");
        if (presented == null || !apiKey.equals(presented)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Missing or invalid X-Tech-Id-Api-Key."));
        }
        if (body.entityId() == null || body.provider() == null || body.findingType() == null
                || body.summary() == null || body.confidence() == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                "entityId, provider, findingType, summary, and confidence are required."));
        }
        long checkedAt = body.checkedAt() != null ? body.checkedAt() : System.currentTimeMillis();
        List<String> sources = body.sources() != null ? body.sources() : List.of();
        List<String> relatedEntityIds = body.relatedEntityIds() != null ? body.relatedEntityIds() : List.of();
        TechIdFinding finding = findingService.submit(
            body.entityId(), relatedEntityIds, body.provider(), body.findingType(), body.summary(),
            body.confidence(), sources, body.actionable(), checkedAt);
        return ResponseEntity.status(HttpStatus.CREATED).body(finding);
    }

    /**
     * GET /api/tech-id/findings?entityId=nvr_frigate&limit=20
     * entityId matches either the finding's primary entityId or any of
     * its relatedEntityIds -- a finding filed against a camera should
     * still show up when browsing a complementary device it references.
     */
    @GetMapping
    public List<TechIdFinding> recent(
            @RequestParam(name = "entityId", required = false) String entityId,
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        return findingService.recent(entityId, cappedLimit);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> adjudicate(@PathVariable String id, @RequestBody StatusUpdateRequest body,
                                         HttpServletRequest request) {
        if (body.status() == null || !List.of("new", "reviewed", "actioned", "dismissed").contains(body.status())) {
            return ResponseEntity.badRequest().body(Map.of("error", "status must be one of: new, reviewed, actioned, dismissed"));
        }
        TechIdFinding updated = findingService.updateStatus(id, body.status());
        if (updated == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(updated);
    }

    /**
     * POST /api/tech-id/findings/{id}/actions -- the See/Think/Act
     * interaction log. Every tap on the Opportunity Map's See/Think/Act
     * buttons calls this, per the user's explicit requirement that any
     * action taken from the opportunity map be logged as durable data
     * for opportunity analysis, not just executed and forgotten.
     */
    @PostMapping("/{id}/actions")
    public ResponseEntity<?> logAction(@PathVariable String id, @RequestBody ActionRequest body,
                                        HttpServletRequest request) {
        if (body.actionType() == null || !VALID_ACTION_TYPES.contains(body.actionType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "actionType must be one of: " + VALID_ACTION_TYPES));
        }
        if (findingService.byId(id) == null) return ResponseEntity.notFound().build();
        Object email = request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL);
        String actorEmail = email != null ? email.toString() : "unknown";
        TechIdFindingAction action = actionService.record(id, body.actionType(), actorEmail, body.detail());
        return ResponseEntity.status(HttpStatus.CREATED).body(action);
    }

    @GetMapping("/{id}/actions")
    public ResponseEntity<?> actionsForFinding(@PathVariable String id) {
        if (findingService.byId(id) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(actionService.forFinding(id));
    }
}
