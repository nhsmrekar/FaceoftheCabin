package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.catalog.*;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.security.DeviceLifecycleAccessPolicy;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeviceCatalogControllerTest {

    @Test
    void operatorEntryResponseNeverContainsConnectionMaterial() throws Exception {
        DeviceCatalogService catalog = DeviceCatalogService.inMemory();
        catalog.ensureAvailableEntry(new DeviceCatalogEntry(
            "camera-private", "camera_private", "Private Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM), "rtsp",
            "rtsp://admin:secret@example.invalid/live", "cabin",
            DeviceIdentityAssurance.UNRESOLVED, DeviceAdmissionStatus.AVAILABLE,
            DeviceConfigurationStatus.READY_TO_CONFIGURE,
            DeviceEnablementStatus.DISABLED, Instant.now()));
        DeviceCatalogController controller = new DeviceCatalogController(catalog,
            new DeviceLifecycleAccessPolicy("owner@example.com"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, "owner@example.com");

        List<DeviceCatalogController.DeviceCatalogEntryView> entries = controller.entries(request);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(entries);

        assertEquals(1, entries.size());
        assertTrue(entries.getFirst().connectionConfigured());
        assertFalse(json.contains("connectionString"));
        assertFalse(json.contains("secret"));
        assertFalse(json.contains("admin"));
    }
}
