package com.cabin.orchestrator.devices.audit;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceAuditServiceTest {

    @Test
    void recordsAnAppendOnlyAttributedDeviceAction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DeviceAuditService service = new DeviceAuditService(jdbc);

        DeviceAuditRecord receipt = service.record(
            "z2m-door", "CHECK_NOW", "owner@example.com",
            "REACHABLE", "Device answered");

        assertNotNull(receipt.id());
        assertEquals("z2m-door", receipt.deviceId());
        assertEquals("owner@example.com", receipt.actorEmail());
        assertEquals("REACHABLE", receipt.outcome());
        verify(jdbc).update(anyString(),
            eq(receipt.id()), eq("z2m-door"), eq("CHECK_NOW"),
            eq("owner@example.com"), eq("REACHABLE"), eq("Device answered"),
            eq(receipt.createdAt()));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void boundsFreeTextDetailBeforePersistence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DeviceAuditService service = new DeviceAuditService(jdbc);

        DeviceAuditRecord receipt = service.record(
            "device", "CHECK_NOW", "owner@example.com", "NO_REPLY", "x".repeat(3000));

        assertEquals(2048, receipt.detail().length());
    }
}
