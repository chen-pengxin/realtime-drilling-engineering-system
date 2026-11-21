package com.ruoyi.web.controller.drilling;

import java.util.Map;

/** Selection uses whole-trajectory bounds in one horizontal coordinate frame. */
final class NeighborSelectionPolicy {
  private NeighborSelectionPolicy() { }

  static double automaticMargin(Map<String, Object> defaults) {
    return distanceSetting(defaults, "neighborSearchMarginM", 500.0);
  }

  static double manualRadius(Map<String, Object> defaults) {
    return distanceSetting(defaults, "neighborManualSearchRadiusM", 3000.0);
  }

  private static double distanceSetting(Map<String, Object> defaults, String key, double fallback) {
    double value;
    try { value = Double.parseDouble(String.valueOf(defaults.getOrDefault(key, fallback))); }
    catch (RuntimeException ex) { throw new IllegalArgumentException("邻井搜索距离必须为非负有限数"); }
    if (!Double.isFinite(value) || value < 0)
      throw new IllegalArgumentException("邻井搜索距离必须为非负有限数");
    return value;
  }

  static Decision evaluate(Double[] reference, Double[] neighbor, double margin,
                           double radius, Boolean manualSelection, boolean versionUsable) {
    if (reference == null || neighbor == null)
      return new Decision(false, manualSelection != null, false, false, null, manualSelection != null);
    double northGap = Math.max(0, Math.max(reference[0] - neighbor[1], neighbor[0] - reference[1]));
    double eastGap = Math.max(0, Math.max(reference[2] - neighbor[3], neighbor[2] - reference[3]));
    double distance = Math.hypot(northGap, eastGap);
    boolean automatic = northGap <= margin && eastGap <= margin;
    boolean inRange = automatic || distance <= radius;
    boolean confirmed = manualSelection != null || automatic;
    boolean included = versionUsable && inRange && (manualSelection != null ? manualSelection : automatic);
    return new Decision(automatic, confirmed, included, inRange, distance, inRange || manualSelection != null);
  }

  record Decision(boolean automatic, boolean confirmed, boolean included, boolean inRange,
                  Double distance, boolean visible) { }
}
