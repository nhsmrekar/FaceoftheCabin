package com.cabin.orchestrator.events;

import java.util.Map;

/**
 * Classifies a device message's severity from its raw attributes, replacing
 * the "INFO" literal MqttBridgeService and Zigbee2MqttAdapter both used to
 * pass to every CabinEvent regardless of payload content.
 *
 * MVP rule set only — no armed/presence awareness yet. Those live signals are
 * separate ontology concepts with distinct unknown states and require their
 * own explicit severity design; they must not be smuggled into this raw-
 * attribute classifier as an incidental call-site behavior.
 */
public final class AlertSeverityClassifier {

    private AlertSeverityClassifier() {}

    public static String classify(Map<String, Object> attrs) {
        if (isTrue(attrs.get("water_leak")) || isTrue(attrs.get("smoke")) || isTrue(attrs.get("alarm"))) {
            return "CRITICAL";
        }
        if (isTrue(attrs.get("tamper")) || isTrue(attrs.get("battery_low")) || isFalse(attrs.get("contact"))) {
            return "WARN";
        }
        return "INFO";
    }

    private static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static boolean isFalse(Object value) {
        return Boolean.FALSE.equals(value);
    }
}
