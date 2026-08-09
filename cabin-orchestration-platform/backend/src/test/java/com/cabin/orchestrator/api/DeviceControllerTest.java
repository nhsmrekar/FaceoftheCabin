package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.audit.DeviceAuditRecord;
import com.cabin.orchestrator.devices.audit.DeviceAuditService;
import com.cabin.orchestrator.devices.display.DeviceDisplayConfigService;
import com.cabin.orchestrator.devices.model.CheckinStatus;
import com.cabin.orchestrator.devices.model.DeviceLivenessCheckResult;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import com.cabin.orchestrator.security.DeviceLifecycleAccessPolicy;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class DeviceControllerTest {

    @Test
    void checkNowReturnsTheDurableAttributedReceipt() {
        DeviceRegistry registry = mock(DeviceRegistry.class);
        Zigbee2MqttAdapter z2m = mock(Zigbee2MqttAdapter.class);
        DeviceHealthMonitor monitor = mock(DeviceHealthMonitor.class);
        DeviceDisplayConfigService display = mock(DeviceDisplayConfigService.class);
        DeviceLifecycleAccessPolicy lifecycle = mock(DeviceLifecycleAccessPolicy.class);
        DeviceAuditService audit = mock(DeviceAuditService.class);
        DeviceController controller = new DeviceController(
            registry, z2m, monitor, display, lifecycle, audit);
        Instant checkedAt = Instant.parse("2026-08-09T19:00:00Z");
        DeviceLivenessCheckResult result = new DeviceLivenessCheckResult(
            "z2m-door", "CHECK_NOW", DeviceLivenessCheckResult.Outcome.REACHABLE,
            CheckinStatus.LATE, CheckinStatus.ON_SCHEDULE, checkedAt, "Device answered");
        when(monitor.checkNow("z2m-door")).thenReturn(result);
        when(audit.record("z2m-door", "CHECK_NOW", "owner@example.com", "REACHABLE", "Device answered"))
            .thenReturn(new DeviceAuditRecord("receipt-1", "z2m-door", "CHECK_NOW",
                "owner@example.com", "REACHABLE", "Device answered", 1L));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, "owner@example.com");

        DeviceLivenessCheckResult response = controller.checkNow("z2m-door", request);

        assertEquals("receipt-1", response.receiptId());
        verify(monitor).checkNow("z2m-door");
        verify(audit).record("z2m-door", "CHECK_NOW", "owner@example.com", "REACHABLE", "Device answered");
    }
}
