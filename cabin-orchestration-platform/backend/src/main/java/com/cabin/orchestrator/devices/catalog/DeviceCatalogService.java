package com.cabin.orchestrator.devices.catalog;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable catalog and admission boundary derived from docs/ontology.yaml.
 *
 * Discovery is deliberately write-only with respect to operational state: an
 * observation creates or refreshes a candidate, but only an explicit binding
 * plus ADMITTED + CONFORMING + ENABLED can return an authorized entry. Proposed
 * IDs and friendly names never allocate DeviceRegistry state by themselves.
 */
@Service
public class DeviceCatalogService {

    private static final int MAX_CANDIDATES = 4096;
    private static final int MAX_SOURCE_IDENTITY = 512;
    private static final int MAX_PROPOSED_ID = 128;
    private static final int MAX_METADATA_FIELDS = 32;
    private static final int MAX_METADATA_VALUE = 512;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, DeviceCatalogEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, DeviceObservationCandidate> candidatesBySource = new ConcurrentHashMap<>();
    private final Map<String, DeviceObservationCandidate> candidatesById = new ConcurrentHashMap<>();
    private final Map<String, DeviceIdentityBinding> bindings = new ConcurrentHashMap<>();
    private final List<DeviceLifecycleDecision> decisions = new ArrayList<>();

    public DeviceCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** In-memory implementation for focused unit tests; production always injects JDBC. */
    public static DeviceCatalogService inMemory() {
        return new DeviceCatalogService(null);
    }

    @PostConstruct
    void init() {
        if (jdbc == null) return;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS device_catalog_entry (
              device_id             VARCHAR(128) PRIMARY KEY,
              ontology_id           VARCHAR(256),
              name                  VARCHAR(256) NOT NULL,
              device_type           VARCHAR(64) NOT NULL,
              capabilities_json     TEXT NOT NULL,
              protocol_adapter      VARCHAR(64) NOT NULL,
              connection_string     TEXT NOT NULL,
              location              VARCHAR(64) NOT NULL,
              identity_assurance    VARCHAR(32) NOT NULL,
              admission_status      VARCHAR(32) NOT NULL,
              configuration_status  VARCHAR(32) NOT NULL,
              enablement_status     VARCHAR(32) NOT NULL,
              updated_at            BIGINT NOT NULL
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS device_observation_candidate (
              candidate_id       VARCHAR(64) PRIMARY KEY,
              source_type        VARCHAR(64) NOT NULL,
              source_identity    VARCHAR(512) NOT NULL,
              proposed_device_id VARCHAR(128),
              metadata_json      TEXT NOT NULL,
              disposition        VARCHAR(32) NOT NULL,
              first_seen         BIGINT NOT NULL,
              last_seen          BIGINT NOT NULL,
              seen_count         BIGINT NOT NULL,
              rejection_reason   TEXT,
              rejected_by        VARCHAR(256),
              rejected_at        BIGINT,
              UNIQUE (source_type, source_identity)
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS device_identity_binding (
              source_type     VARCHAR(64) NOT NULL,
              source_identity VARCHAR(512) NOT NULL,
              device_id       VARCHAR(128) NOT NULL UNIQUE,
              actor_id        VARCHAR(256) NOT NULL,
              bound_at        BIGINT NOT NULL,
              PRIMARY KEY (source_type, source_identity)
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS device_lifecycle_decision (
              decision_id VARCHAR(64) PRIMARY KEY,
              candidate_id VARCHAR(64),
              device_id VARCHAR(128),
              action VARCHAR(64) NOT NULL,
              actor_type VARCHAR(32) NOT NULL,
              actor_id VARCHAR(256) NOT NULL,
              reason TEXT,
              decided_at BIGINT NOT NULL
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_device_candidate_last_seen ON device_observation_candidate (last_seen DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_device_decision_candidate ON device_lifecycle_decision (candidate_id, decided_at DESC)");
        loadFromDatabase();
    }

    private void loadFromDatabase() {
        jdbc.queryForList("SELECT * FROM device_catalog_entry").stream()
            .map(this::entryFromRow).forEach(entry -> entries.put(entry.deviceId(), entry));
        jdbc.queryForList("SELECT * FROM device_observation_candidate").stream()
            .map(this::candidateFromRow).forEach(candidate -> {
                candidatesBySource.put(sourceKey(candidate.sourceType(), candidate.sourceIdentity()), candidate);
                candidatesById.put(candidate.candidateId(), candidate);
            });
        jdbc.queryForList("SELECT * FROM device_identity_binding").stream()
            .map(this::bindingFromRow).forEach(binding ->
                bindings.put(sourceKey(binding.sourceType(), binding.sourceIdentity()), binding));
        jdbc.queryForList("SELECT * FROM device_lifecycle_decision ORDER BY decided_at").stream()
            .map(this::decisionFromRow).forEach(decisions::add);
    }

    /** Seed catalog knowledge without admitting, binding, configuring, or enabling it. */
    @Transactional
    public synchronized DeviceCatalogEntry ensureAvailableEntry(DeviceCatalogEntry offered) {
        DeviceCatalogEntry existing = entries.get(offered.deviceId());
        if (existing != null) return existing;
        DeviceCatalogEntry available = offered.withLifecycle(
            offered.identityAssurance(), DeviceAdmissionStatus.AVAILABLE,
            offered.configurationStatus(), DeviceEnablementStatus.DISABLED);
        persistEntry(available);
        entries.put(available.deviceId(), available);
        return available;
    }

    /** Retain an untrusted observation without granting it an ID or side effects. */
    @Transactional
    public synchronized Optional<DeviceObservationCandidate> observe(DeviceObservation observation) {
        String sourceType = normalizeSourceType(observation.sourceType());
        String sourceIdentity = boundedRequired(observation.sourceIdentity(), MAX_SOURCE_IDENTITY);
        if (sourceType == null || sourceIdentity == null) return Optional.empty();
        String proposedId = boundedOptional(observation.proposedDeviceId(), MAX_PROPOSED_ID);
        Map<String, Object> metadata = sanitizeMetadata(observation.metadata());
        String key = sourceKey(sourceType, sourceIdentity);
        DeviceObservationCandidate previous = candidatesBySource.get(key);
        if (previous == null && candidatesBySource.size() >= MAX_CANDIDATES) return Optional.empty();

        Instant now = Instant.now();
        DeviceObservationCandidate candidate = previous == null
            ? new DeviceObservationCandidate(UUID.randomUUID().toString(), sourceType, sourceIdentity,
                proposedId, metadata, DeviceCandidateDisposition.AVAILABLE, now, now, 1,
                null, null, null)
            : new DeviceObservationCandidate(previous.candidateId(), sourceType, sourceIdentity,
                proposedId != null ? proposedId : previous.proposedDeviceId(), metadata,
                previous.disposition(), previous.firstSeen(), now, previous.seenCount() + 1,
                previous.rejectionReason(), previous.rejectedBy(), previous.rejectedAt());

        persistCandidate(candidate);
        candidatesBySource.put(key, candidate);
        candidatesById.put(candidate.candidateId(), candidate);
        return Optional.of(candidate);
    }

    /** Bind an AVAILABLE candidate to an existing catalog entry and admit it. */
    @Transactional
    public synchronized DeviceCatalogEntry admitCandidate(String candidateId, String deviceId,
                                                            String actorId, String reason) {
        DeviceObservationCandidate candidate = requireCandidate(candidateId);
        if (candidate.disposition() != DeviceCandidateDisposition.AVAILABLE) {
            throw new IllegalStateException("Candidate must be AVAILABLE before admission");
        }
        if ("ZIGBEE2MQTT".equals(candidate.sourceType())
            && !candidate.sourceIdentity().matches("(?i)^0x[0-9a-f]{16}$")) {
            throw new IllegalStateException(
                "Zigbee admission requires the ontology-defined 64-bit IEEE source identity");
        }
        DeviceCatalogEntry existing = requireEntry(deviceId);
        String key = sourceKey(candidate.sourceType(), candidate.sourceIdentity());
        DeviceIdentityBinding currentBinding = bindings.get(key);
        if (currentBinding != null && !currentBinding.deviceId().equals(deviceId)) {
            throw new IllegalStateException("Observed identity is already bound to another device");
        }
        boolean deviceBoundElsewhere = bindings.values().stream()
            .anyMatch(binding -> binding.deviceId().equals(deviceId)
                && !sourceKey(binding.sourceType(), binding.sourceIdentity()).equals(key));
        if (deviceBoundElsewhere) {
            throw new IllegalStateException("Catalog device is already bound to another identity");
        }

        Instant now = Instant.now();
        DeviceIdentityBinding binding = new DeviceIdentityBinding(candidate.sourceType(),
            candidate.sourceIdentity(), deviceId, requireActor(actorId), now);
        DeviceCatalogEntry admitted = existing.withLifecycle(DeviceIdentityAssurance.VERIFIED,
            DeviceAdmissionStatus.ADMITTED, existing.configurationStatus(),
            DeviceEnablementStatus.DISABLED);
        DeviceObservationCandidate updatedCandidate = copyCandidate(candidate,
            DeviceCandidateDisposition.ADMITTED, null, null, null);
        DeviceLifecycleDecision decision = decision(candidateId, deviceId, "ADMIT", "HUMAN",
            actorId, reason);

        persistEntry(admitted);
        persistBinding(binding);
        persistCandidate(updatedCandidate);
        persistDecision(decision);
        entries.put(deviceId, admitted);
        bindings.put(key, binding);
        cacheCandidate(updatedCandidate);
        decisions.add(decision);
        return admitted;
    }

    /** Reject and retain a candidate; any prior admission is revoked and disabled. */
    @Transactional
    public synchronized DeviceObservationCandidate rejectCandidate(String candidateId,
                                                                     String actorId, String reason) {
        DeviceObservationCandidate candidate = requireCandidate(candidateId);
        Instant now = Instant.now();
        String actor = requireActor(actorId);
        DeviceObservationCandidate rejected = copyCandidate(candidate,
            DeviceCandidateDisposition.REJECTED, boundedOptional(reason, 2048), actor, now);
        DeviceIdentityBinding binding = bindings.get(sourceKey(candidate.sourceType(), candidate.sourceIdentity()));
        DeviceCatalogEntry revoked = null;
        if (binding != null) {
            DeviceCatalogEntry entry = requireEntry(binding.deviceId());
            revoked = entry.withLifecycle(entry.identityAssurance(), DeviceAdmissionStatus.REVOKED,
                entry.configurationStatus(), DeviceEnablementStatus.DISABLED);
        }
        DeviceLifecycleDecision decision = decision(candidateId,
            binding == null ? null : binding.deviceId(), "REJECT", "HUMAN", actor, reason);

        if (revoked != null) persistEntry(revoked);
        persistCandidate(rejected);
        persistDecision(decision);
        if (revoked != null) entries.put(revoked.deviceId(), revoked);
        cacheCandidate(rejected);
        decisions.add(decision);
        return rejected;
    }

    /** Re-open a false-negative rejection for review; never re-admits it. */
    @Transactional
    public synchronized DeviceObservationCandidate reconsiderCandidate(String candidateId,
                                                                         String actorId, String reason) {
        DeviceObservationCandidate candidate = requireCandidate(candidateId);
        if (candidate.disposition() != DeviceCandidateDisposition.REJECTED) {
            throw new IllegalStateException("Only a REJECTED candidate can be reconsidered");
        }
        DeviceObservationCandidate available = copyCandidate(candidate,
            DeviceCandidateDisposition.AVAILABLE, null, null, null);
        DeviceIdentityBinding binding = bindings.get(sourceKey(candidate.sourceType(), candidate.sourceIdentity()));
        DeviceLifecycleDecision decision = decision(candidateId,
            binding == null ? null : binding.deviceId(), "RECONSIDER", "HUMAN", actorId, reason);
        persistCandidate(available);
        persistDecision(decision);
        cacheCandidate(available);
        decisions.add(decision);
        return available;
    }

    @Transactional
    public synchronized DeviceCatalogEntry setConfiguration(String deviceId,
                                                              DeviceConfigurationStatus status,
                                                              String actorType, String actorId,
                                                              String reason) {
        DeviceCatalogEntry existing = requireEntry(deviceId);
        Objects.requireNonNull(status, "status");
        if (status == DeviceConfigurationStatus.CONFORMING
            && (existing.identityAssurance() != DeviceIdentityAssurance.VERIFIED
                || existing.admissionStatus() != DeviceAdmissionStatus.ADMITTED
                || !existing.configurationComplete())) {
            throw new IllegalStateException("Conformance requires verified identity, admission, and complete configuration");
        }
        DeviceCatalogEntry updated = existing.withLifecycle(existing.identityAssurance(),
            existing.admissionStatus(), status, existing.enablementStatus());
        DeviceLifecycleDecision decision = decision(null, deviceId, "SET_CONFIGURATION_" + status,
            actorType, actorId, reason);
        persistEntry(updated);
        persistDecision(decision);
        entries.put(deviceId, updated);
        decisions.add(decision);
        return updated;
    }

    @Transactional
    public synchronized DeviceCatalogEntry setEnablement(String deviceId,
                                                           DeviceEnablementStatus status,
                                                           String actorType, String actorId,
                                                           String reason) {
        DeviceCatalogEntry existing = requireEntry(deviceId);
        Objects.requireNonNull(status, "status");
        if (status == DeviceEnablementStatus.ENABLED
            && !(existing.identityAssurance() == DeviceIdentityAssurance.VERIFIED
                && existing.admissionStatus() == DeviceAdmissionStatus.ADMITTED
                && existing.configurationStatus() == DeviceConfigurationStatus.CONFORMING)) {
            throw new IllegalStateException("Enablement requires verified identity, admission, and conformance");
        }
        DeviceCatalogEntry updated = existing.withLifecycle(existing.identityAssurance(),
            existing.admissionStatus(), existing.configurationStatus(), status);
        DeviceLifecycleDecision decision = decision(null, deviceId, "SET_ENABLEMENT_" + status,
            actorType, actorId, reason);
        persistEntry(updated);
        persistDecision(decision);
        entries.put(deviceId, updated);
        decisions.add(decision);
        return updated;
    }

    public Optional<DeviceCatalogEntry> authorizedEntryForObservation(String sourceType,
                                                                       String sourceIdentity) {
        String normalizedType = normalizeSourceType(sourceType);
        String normalizedIdentity = boundedRequired(sourceIdentity, MAX_SOURCE_IDENTITY);
        if (normalizedType == null || normalizedIdentity == null) return Optional.empty();
        String key = sourceKey(normalizedType, normalizedIdentity);
        DeviceObservationCandidate candidate = candidatesBySource.get(key);
        DeviceIdentityBinding binding = bindings.get(key);
        if (candidate == null || binding == null
            || candidate.disposition() != DeviceCandidateDisposition.ADMITTED) {
            return Optional.empty();
        }
        DeviceCatalogEntry entry = entries.get(binding.deviceId());
        return entry != null && entry.operationallyAuthorized() ? Optional.of(entry) : Optional.empty();
    }

    public Optional<DeviceCatalogEntry> authorizedEntryForDeviceId(String deviceId) {
        DeviceCatalogEntry entry = entries.get(deviceId);
        if (entry == null || !entry.operationallyAuthorized()) return Optional.empty();
        boolean hasAdmittedBinding = bindings.values().stream()
            .filter(binding -> binding.deviceId().equals(deviceId))
            .map(binding -> candidatesBySource.get(sourceKey(binding.sourceType(), binding.sourceIdentity())))
            .anyMatch(candidate -> candidate != null
                && candidate.disposition() == DeviceCandidateDisposition.ADMITTED);
        return hasAdmittedBinding ? Optional.of(entry) : Optional.empty();
    }

    public Optional<DeviceCatalogEntry> entry(String deviceId) {
        return Optional.ofNullable(entries.get(deviceId));
    }

    public Optional<DeviceObservationCandidate> candidate(String candidateId) {
        return Optional.ofNullable(candidatesById.get(candidateId));
    }

    public Optional<DeviceObservationCandidate> candidateForObservation(String sourceType,
                                                                          String sourceIdentity) {
        String type = normalizeSourceType(sourceType);
        String identity = boundedRequired(sourceIdentity, MAX_SOURCE_IDENTITY);
        if (type == null || identity == null) return Optional.empty();
        return Optional.ofNullable(candidatesBySource.get(sourceKey(type, identity)));
    }

    public List<DeviceCatalogEntry> allEntries() {
        return entries.values().stream().sorted(Comparator.comparing(DeviceCatalogEntry::deviceId)).toList();
    }

    public List<DeviceObservationCandidate> allCandidates() {
        return candidatesById.values().stream()
            .sorted(Comparator.comparing(DeviceObservationCandidate::lastSeen).reversed())
            .toList();
    }

    public synchronized List<DeviceLifecycleDecision> decisionsForCandidate(String candidateId) {
        return decisions.stream().filter(d -> Objects.equals(candidateId, d.candidateId())).toList();
    }

    private DeviceCatalogEntry requireEntry(String deviceId) {
        DeviceCatalogEntry entry = entries.get(deviceId);
        if (entry == null) throw new NoSuchElementException("Unknown catalog device: " + deviceId);
        return entry;
    }

    private DeviceObservationCandidate requireCandidate(String candidateId) {
        DeviceObservationCandidate candidate = candidatesById.get(candidateId);
        if (candidate == null) throw new NoSuchElementException("Unknown candidate: " + candidateId);
        return candidate;
    }

    private DeviceObservationCandidate copyCandidate(DeviceObservationCandidate candidate,
                                                       DeviceCandidateDisposition disposition,
                                                       String rejectionReason, String rejectedBy,
                                                       Instant rejectedAt) {
        return new DeviceObservationCandidate(candidate.candidateId(), candidate.sourceType(),
            candidate.sourceIdentity(), candidate.proposedDeviceId(), candidate.metadata(), disposition,
            candidate.firstSeen(), candidate.lastSeen(), candidate.seenCount(), rejectionReason,
            rejectedBy, rejectedAt);
    }

    private void cacheCandidate(DeviceObservationCandidate candidate) {
        candidatesBySource.put(sourceKey(candidate.sourceType(), candidate.sourceIdentity()), candidate);
        candidatesById.put(candidate.candidateId(), candidate);
    }

    private DeviceLifecycleDecision decision(String candidateId, String deviceId, String action,
                                               String actorType, String actorId, String reason) {
        return new DeviceLifecycleDecision(UUID.randomUUID().toString(), candidateId, deviceId,
            action, boundedRequired(actorType, 32) == null ? "UNKNOWN" : actorType.trim().toUpperCase(Locale.ROOT),
            requireActor(actorId), boundedOptional(reason, 2048), Instant.now());
    }

    private String requireActor(String actorId) {
        String actor = boundedRequired(actorId, 256);
        if (actor == null) throw new IllegalArgumentException("An attributable actor is required");
        return actor;
    }

    private String normalizeSourceType(String value) {
        String normalized = boundedRequired(value, 64);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT).replace('-', '_');
        return normalized.matches("[A-Z0-9_:.]+") ? normalized : null;
    }

    private String boundedRequired(String value, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.length() > maxLength ? null : trimmed;
    }

    private String boundedOptional(String value, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.substring(0, Math.min(trimmed.length(), maxLength));
    }

    private Map<String, Object> sanitizeMetadata(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, Object> sanitized = new LinkedHashMap<>();
        raw.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(MAX_METADATA_FIELDS)
            .forEach(entry -> {
                String key = boundedRequired(entry.getKey(), 128);
                if (key == null) return;
                Object value = entry.getValue();
                if (value == null || value instanceof Number || value instanceof Boolean) {
                    sanitized.put(key, value);
                } else {
                    String text = String.valueOf(value);
                    sanitized.put(key, text.substring(0, Math.min(text.length(), MAX_METADATA_VALUE)));
                }
            });
        return Map.copyOf(sanitized);
    }

    private String sourceKey(String sourceType, String sourceIdentity) {
        return sourceType + "\u0000" + sourceIdentity;
    }

    private void persistEntry(DeviceCatalogEntry entry) {
        if (jdbc == null) return;
        jdbc.update("""
            INSERT INTO device_catalog_entry
              (device_id, ontology_id, name, device_type, capabilities_json,
               protocol_adapter, connection_string, location, identity_assurance,
               admission_status, configuration_status, enablement_status, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (device_id) DO UPDATE SET
              ontology_id=EXCLUDED.ontology_id, name=EXCLUDED.name,
              device_type=EXCLUDED.device_type, capabilities_json=EXCLUDED.capabilities_json,
              protocol_adapter=EXCLUDED.protocol_adapter,
              connection_string=EXCLUDED.connection_string, location=EXCLUDED.location,
              identity_assurance=EXCLUDED.identity_assurance,
              admission_status=EXCLUDED.admission_status,
              configuration_status=EXCLUDED.configuration_status,
              enablement_status=EXCLUDED.enablement_status, updated_at=EXCLUDED.updated_at
            """, entry.deviceId(), entry.ontologyId(), entry.name(), entry.type().name(),
            json(entry.capabilities().stream().map(Enum::name).sorted().toList()),
            entry.protocolAdapter(), entry.connectionString(), entry.location(),
            entry.identityAssurance().name(), entry.admissionStatus().name(),
            entry.configurationStatus().name(), entry.enablementStatus().name(),
            entry.updatedAt().toEpochMilli());
    }

    private void persistCandidate(DeviceObservationCandidate candidate) {
        if (jdbc == null) return;
        jdbc.update("""
            INSERT INTO device_observation_candidate
              (candidate_id, source_type, source_identity, proposed_device_id,
               metadata_json, disposition, first_seen, last_seen, seen_count,
               rejection_reason, rejected_by, rejected_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (source_type, source_identity) DO UPDATE SET
              proposed_device_id=EXCLUDED.proposed_device_id,
              metadata_json=EXCLUDED.metadata_json, disposition=EXCLUDED.disposition,
              last_seen=EXCLUDED.last_seen, seen_count=EXCLUDED.seen_count,
              rejection_reason=EXCLUDED.rejection_reason,
              rejected_by=EXCLUDED.rejected_by, rejected_at=EXCLUDED.rejected_at
            """, candidate.candidateId(), candidate.sourceType(), candidate.sourceIdentity(),
            candidate.proposedDeviceId(), json(candidate.metadata()), candidate.disposition().name(),
            candidate.firstSeen().toEpochMilli(), candidate.lastSeen().toEpochMilli(),
            candidate.seenCount(), candidate.rejectionReason(), candidate.rejectedBy(),
            candidate.rejectedAt() == null ? null : candidate.rejectedAt().toEpochMilli());
    }

    private void persistBinding(DeviceIdentityBinding binding) {
        if (jdbc == null) return;
        jdbc.update("""
            INSERT INTO device_identity_binding
              (source_type, source_identity, device_id, actor_id, bound_at)
            VALUES (?,?,?,?,?)
            ON CONFLICT (source_type, source_identity) DO UPDATE SET
              device_id=EXCLUDED.device_id, actor_id=EXCLUDED.actor_id,
              bound_at=EXCLUDED.bound_at
            """, binding.sourceType(), binding.sourceIdentity(), binding.deviceId(),
            binding.actorId(), binding.boundAt().toEpochMilli());
    }

    private void persistDecision(DeviceLifecycleDecision decision) {
        if (jdbc == null) return;
        jdbc.update("""
            INSERT INTO device_lifecycle_decision
              (decision_id, candidate_id, device_id, action, actor_type, actor_id, reason, decided_at)
            VALUES (?,?,?,?,?,?,?,?)
            """, decision.decisionId(), decision.candidateId(), decision.deviceId(),
            decision.action(), decision.actorType(), decision.actorId(), decision.reason(),
            decision.decidedAt().toEpochMilli());
    }

    private DeviceCatalogEntry entryFromRow(Map<String, Object> row) {
        List<String> capabilityNames = readJson(String.valueOf(row.get("capabilities_json")),
            new TypeReference<>() {});
        Set<DeviceCapability> capabilities = new LinkedHashSet<>();
        capabilityNames.forEach(name -> capabilities.add(DeviceCapability.valueOf(name)));
        return new DeviceCatalogEntry((String) row.get("device_id"), (String) row.get("ontology_id"),
            (String) row.get("name"), DeviceType.valueOf((String) row.get("device_type")),
            capabilities, (String) row.get("protocol_adapter"),
            (String) row.get("connection_string"), (String) row.get("location"),
            DeviceIdentityAssurance.valueOf((String) row.get("identity_assurance")),
            DeviceAdmissionStatus.valueOf((String) row.get("admission_status")),
            DeviceConfigurationStatus.valueOf((String) row.get("configuration_status")),
            DeviceEnablementStatus.valueOf((String) row.get("enablement_status")),
            Instant.ofEpochMilli(number(row.get("updated_at"))));
    }

    private DeviceObservationCandidate candidateFromRow(Map<String, Object> row) {
        Map<String, Object> metadata = readJson(String.valueOf(row.get("metadata_json")),
            new TypeReference<>() {});
        Object rejectedAt = row.get("rejected_at");
        return new DeviceObservationCandidate((String) row.get("candidate_id"),
            (String) row.get("source_type"), (String) row.get("source_identity"),
            (String) row.get("proposed_device_id"), metadata,
            DeviceCandidateDisposition.valueOf((String) row.get("disposition")),
            Instant.ofEpochMilli(number(row.get("first_seen"))),
            Instant.ofEpochMilli(number(row.get("last_seen"))), number(row.get("seen_count")),
            (String) row.get("rejection_reason"), (String) row.get("rejected_by"),
            rejectedAt == null ? null : Instant.ofEpochMilli(number(rejectedAt)));
    }

    private DeviceIdentityBinding bindingFromRow(Map<String, Object> row) {
        return new DeviceIdentityBinding((String) row.get("source_type"),
            (String) row.get("source_identity"), (String) row.get("device_id"),
            (String) row.get("actor_id"), Instant.ofEpochMilli(number(row.get("bound_at"))));
    }

    private DeviceLifecycleDecision decisionFromRow(Map<String, Object> row) {
        return new DeviceLifecycleDecision((String) row.get("decision_id"),
            (String) row.get("candidate_id"), (String) row.get("device_id"),
            (String) row.get("action"), (String) row.get("actor_type"),
            (String) row.get("actor_id"), (String) row.get("reason"),
            Instant.ofEpochMilli(number(row.get("decided_at"))));
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize device catalog data", e);
        }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read device catalog data", e);
        }
    }
}
