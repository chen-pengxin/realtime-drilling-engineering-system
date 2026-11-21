package com.ruoyi.web.controller.drilling;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrajectoryVisualizationSettingsTest {
    private Map<String, Object> config(Map<String, Object> style) {
        return Map.of("version", 1, "styles", Map.of("REFERENCE", Map.of("ACTUAL", style)),
                "wellbores", Map.of("31", Map.of("NEIGHBOR", Map.of("DESIGN", style))));
    }

    @Test void acceptsDefaultsAndPartialWellboreOverrides() {
        assertDoesNotThrow(() -> TrajectoryVisualizationSettings.validate(DrillingAlgorithmDefaults.create().get("visualization")));
        assertDoesNotThrow(() -> TrajectoryVisualizationSettings.validate(null));
        assertDoesNotThrow(() -> TrajectoryVisualizationSettings.validate(config(Map.of("color", "#123abc", "width", 2.5, "dash", "dot", "opacity", .5))));
    }

    @Test void rejectsInvalidAndUnsafePlotParameters() {
        for (var style : new Map[]{Map.of("width", 0), Map.of("width", Double.NaN), Map.of("width", 9),
                Map.of("opacity", 0), Map.of("opacity", 1.1), Map.of("nodeSize", 13),
                Map.of("color", "red"), Map.of("dash", "unknown"), Map.of("width", "wide"), Map.of("script", "bad")})
            assertThrows(IllegalArgumentException.class, () -> TrajectoryVisualizationSettings.validate(config(style)));
        assertThrows(IllegalArgumentException.class, () -> TrajectoryVisualizationSettings.validate(Map.of("version", 1, "wellbores", Map.of("neighbor-101", Map.of()))));
    }

    @Test void visualizationRemainsInVersionSnapshotsWithoutChangingOtherEngineeringDefaults() {
        var defaults = DrillingAlgorithmDefaults.merged(Map.of("visualization", config(Map.of("color", "#abcdef"))));
        var restored = DrillingAlgorithmDefaults.merged(DrillingAlgorithmDefaults.snapshot(defaults));
        assertEquals(defaults.get("visualization"), restored.get("visualization"));
        assertEquals(DrillingAlgorithmDefaults.create().get("errorModel"), defaults.get("errorModel"));
    }
}
