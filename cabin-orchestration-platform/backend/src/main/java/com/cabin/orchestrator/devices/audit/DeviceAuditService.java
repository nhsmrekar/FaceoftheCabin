package com.cabin.orchestrator.devices.audit;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Writes append-only, attributable evidence for user-requested device actions. */
@Service
public class DeviceAuditService {

    private final JdbcTemplate jdbc;

    public DeviceAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS device_audit_log (
              id          VARCHAR(64) PRIMARY KEY,
              device_id   VARCHAR(128) NOT NULL,
              action_type VARCHAR(64) NOT NULL,
              actor_email VARCHAR(320) NOT NULL,
              outcome     VARCHAR(64) NOT NULL,
              detail      TEXT,
              created_at  BIGINT NOT NULL
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_device_audit_device ON device_audit_log (device_id, created_at DESC)");
    }

    public DeviceAuditRecord record(String deviceId, String actionType,
                                    String actorEmail, String outcome, String detail) {
        DeviceAuditRecord record = new DeviceAuditRecord(
            UUID.randomUUID().toString(), required(deviceId), required(actionType),
            required(actorEmail), required(outcome), bounded(detail), System.currentTimeMillis());
        jdbc.update("""
            INSERT INTO device_audit_log
              (id, device_id, action_type, actor_email, outcome, detail, created_at)
            VALUES (?,?,?,?,?,?,?)
            """,
            record.id(), record.deviceId(), record.actionType(), record.actorEmail(),
            record.outcome(), record.detail(), record.createdAt());
        return record;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Audit value is required");
        return value.trim();
    }

    private static String bounded(String value) {
        if (value == null) return null;
        return value.length() <= 2048 ? value : value.substring(0, 2048);
    }
}
