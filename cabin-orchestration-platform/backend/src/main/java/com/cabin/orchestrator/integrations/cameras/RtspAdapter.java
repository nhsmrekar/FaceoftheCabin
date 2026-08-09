package com.cabin.orchestrator.integrations.cameras;

import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Active liveness adapter for RTSP camera descriptors.
 *
 * Ontology contract: rtsp_stream_uri -> rtsp_socket_reachable. This is a
 * bounded TCP connect-and-drop only. It deliberately does not claim valid
 * credentials, RTSP negotiation, decodable frames, or camera FPS.
 */
@Component
public class RtspAdapter implements ProtocolAdapter {

    private static final int DEFAULT_RTSP_PORT = 554;
    private final int connectTimeoutMs;

    public RtspAdapter(@Value("${cabin.devices.cameras.rtspConnectTimeoutMs:2000}") int connectTimeoutMs) {
        this.connectTimeoutMs = Math.max(100, Math.min(connectTimeoutMs, 10_000));
    }

    @Override
    public String adapterType() {
        return "rtsp";
    }

    @Override
    public Optional<DeviceStatus> fetchState(DeviceDescriptor descriptor) {
        if (!socketReachable(descriptor.connectionString())) return Optional.empty();
        return Optional.of(new DeviceStatus(
            descriptor.deviceId(), descriptor.type(), descriptor.name(), "ONLINE",
            Instant.now(), Map.of("rtspSocketReachable", true), descriptor.location()));
    }

    boolean socketReachable(String connectionString) {
        try {
            URI uri = URI.create(connectionString);
            if (!"rtsp".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
            int port = uri.getPort() == -1 ? DEFAULT_RTSP_PORT : uri.getPort();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), connectTimeoutMs);
                return true;
            }
        } catch (Exception ignored) {
            // Never log connectionString: existing descriptors may contain credentials.
            return false;
        }
    }

    @Override
    public boolean sendCommand(DeviceDescriptor descriptor, String command, Object payload) {
        return false;
    }
}
