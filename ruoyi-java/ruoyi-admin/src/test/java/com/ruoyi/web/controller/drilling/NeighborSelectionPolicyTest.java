package com.ruoyi.web.controller.drilling;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NeighborSelectionPolicyTest {
  private final Double[] reference = {0., 100., 0., 100.};

  @Test void overlappingBoundsAreAutomaticallySelectedAndConfirmed() {
    var result = decide(new Double[]{50., 150., 50., 150.}, null, true);
    assertTrue(result.automatic()); assertTrue(result.included()); assertTrue(result.confirmed());
  }

  @Test void configuredAutomaticMarginAppliesToTheWholeHorizontalBox() {
    assertTrue(decide(new Double[]{600., 650., 600., 650.}, null, true).automatic());
    assertFalse(decide(new Double[]{600.001, 650., 600., 650.}, null, true).automatic());
  }

  @Test void outsideAutomaticBoxRemainsAvailableForManualSelection() {
    var result = decide(new Double[]{1100., 1200., 0., 100.}, null, true);
    assertFalse(result.automatic()); assertTrue(result.visible());
    assertTrue(result.inRange()); assertFalse(result.included()); assertFalse(result.confirmed());
    var manual = decide(new Double[]{1100., 1200., 0., 100.}, true, true);
    assertTrue(manual.included()); assertTrue(manual.confirmed());
  }

  @Test void manualExclusionSurvivesAutomaticDiscovery() {
    var result = decide(new Double[]{0., 100., 0., 100.}, false, true);
    assertTrue(result.automatic()); assertTrue(result.confirmed()); assertFalse(result.included());
  }

  @Test void manualRadiusIncludes3000ButExcludesLargerDistances() {
    assertTrue(decide(new Double[]{3100., 3200., 0., 100.}, null, true).visible());
    assertFalse(decide(new Double[]{3100.001, 3200., 0., 100.}, null, true).visible());
    // Euclidean horizontal gap avoids including diagonal corners outside 3 km.
    assertFalse(decide(new Double[]{2300., 2400., 2300., 2400.}, null, true).visible());
  }

  @Test void previouslySelectedOutOfRangeRelationshipCanBeRemovedButNotEvaluated() {
    var result = decide(new Double[]{4000., 4100., 0., 100.}, true, true);
    assertTrue(result.visible()); assertFalse(result.inRange()); assertFalse(result.included());
  }

  @Test void automaticRelationshipsDoNotOverrideUnusableVersions() {
    var result = decide(new Double[]{0., 100., 0., 100.}, null, false);
    assertTrue(result.automatic()); assertFalse(result.included());
  }

  @Test void missingBoundsCannotBeAutomaticallyConfirmedOrEvaluated() {
    assertFalse(decide(null, null, true).visible());
    var result = decide(null, true, true);
    assertTrue(result.visible()); assertFalse(result.included()); assertFalse(result.inRange());
  }

  @Test void defaultAndCustomSearchDistancesAreValidated() {
    assertEquals(3000., NeighborSelectionPolicy.manualRadius(Map.of()));
    assertEquals(500., NeighborSelectionPolicy.automaticMargin(Map.of()));
    assertEquals(1500., NeighborSelectionPolicy.manualRadius(Map.of("neighborManualSearchRadiusM", 1500)));
    for (Object value : new Object[]{-1, Double.NaN, Double.POSITIVE_INFINITY, "invalid"})
      assertThrows(IllegalArgumentException.class,
          () -> NeighborSelectionPolicy.manualRadius(Map.of("neighborManualSearchRadiusM", value)));
  }

  private NeighborSelectionPolicy.Decision decide(Double[] other, Boolean manual, boolean usable) {
    return NeighborSelectionPolicy.evaluate(reference, other, 500., 3000., manual, usable);
  }
}
