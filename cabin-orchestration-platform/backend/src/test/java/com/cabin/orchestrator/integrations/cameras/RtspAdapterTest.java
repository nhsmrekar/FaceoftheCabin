package com.cabin.orchestrator.integrations.cameras;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RtspAdapterTest {

    private DeviceDescriptor descriptor(String uri) {
        return new DeviceDescriptor(
            "camera-test", "Test Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM), "rtsp", uri, true, "home");
    }

    @Test
    void openRtspPortProducesOnlineReachabilityState() throws Exception {
        RtspAdapter adapter = new RtspAdapter(200);
        try (ServerSocket server = new ServerSocket(0)) {
            var status = adapter.fetchState(descriptor(
                "rtsp://user:secret@127.0.0.1:" + server.getLocalPort() + "/stream")).orElseThrow();

            assertEquals("ONLINE", status.state());
            assertEquals(Boolean.TRUE, status.attributes().get("rtspSocketReachable"));
        }
    }

    @Test
    void closedPortReturnsNoState() throws Exception {
        RtspAdapter adapter = new RtspAdapter(50);
        int closedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            closedPort = server.getLocalPort();
        }

        assertTrue(adapter.fetchState(descriptor(
            "rtsp://127.0.0.1:" + closedPort + "/stream")).isEmpty());
    }

    @Test
    void invalidOrNonRtspConfigurationFailsClosed() {
        RtspAdapter adapter = new RtspAdapter(50);

        assertTrue(adapter.fetchState(descriptor("not a uri")).isEmpty());
        assertTrue(adapter.fetchState(descriptor("http://127.0.0.1:554/stream")).isEmpty());
        assertTrue(adapter.fetchState(descriptor("rtsp:///missing-host")).isEmpty());
    }

    @Test
    void commandsAreUnsupported() {
        RtspAdapter adapter = new RtspAdapter(50);
        assertFalse(adapter.sendCommand(descriptor("rtsp://127.0.0.1/stream"), "start", null));
    }
}
