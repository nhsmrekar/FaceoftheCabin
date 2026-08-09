package com.cabin.orchestrator.ontology;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyAuthContractTest {

    private static final Set<String> AUTH_ENTITY_IDS = Set.of(
        "orchestration_hub_owner_identity",
        "platform_authenticated_principal",
        "platform_auth_session",
        "platform_session_credential",
        "app_wide_auth_gate",
        "post_auth_landing_intent");

    @Test
    @SuppressWarnings("unchecked")
    void authContinuityDefinitionsAreParseableUniqueAndInternallyLinked() throws IOException {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(findOntology())) {
            root = new Yaml().load(in);
        }

        assertEquals("0.4.2", root.get("version"));
        List<Map<String, Object>> elements = (List<Map<String, Object>>) root.get("elements");
        assertNotNull(elements);

        Set<String> ids = new HashSet<>();
        for (Map<String, Object> element : elements) {
            String id = String.valueOf(element.get("id"));
            assertTrue(ids.add(id), () -> "Duplicate ontology id: " + id);
        }
        assertTrue(ids.containsAll(AUTH_ENTITY_IDS));

        for (Map<String, Object> element : elements) {
            String id = String.valueOf(element.get("id"));
            if (!AUTH_ENTITY_IDS.contains(id)) continue;
            assertEquals("0.4.2", element.get("schema_version"), id);
            for (Map<String, Object> relationship :
                    (List<Map<String, Object>>) element.getOrDefault("relationships", List.of())) {
                String target = String.valueOf(relationship.get("target"));
                assertTrue(ids.contains(target), () -> id + " has unknown relationship target " + target);
            }
        }

        Map<String, Object> owner = elements.stream()
            .filter(element -> "orchestration_hub_owner_identity".equals(element.get("id")))
            .findFirst().orElseThrow();
        assertEquals("candidate", owner.get("lifecycle_status"));
        Map<String, Object> ownerConstraint = (Map<String, Object>) owner.get("constraint");
        assertTrue(String.valueOf(ownerConstraint.get("description"))
            .contains("Exactly one explicitly configured"));
    }

    private Path findOntology() {
        for (Path candidate : List.of(
                Path.of("docs", "ontology.yaml"),
                Path.of("..", "..", "docs", "ontology.yaml"))) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) return normalized;
        }
        throw new IllegalStateException("Cannot locate docs/ontology.yaml from " + Path.of("").toAbsolutePath());
    }
}
