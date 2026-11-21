package com.ruoyi.web.controller.drilling;

import java.util.LinkedHashMap;
import java.util.Map;
import com.alibaba.fastjson.JSON;

/**
 * Backend-owned example defaults for the drilling algorithms.
 *
 * The values are deliberately ordinary field configuration, rather than
 * hidden algorithm constants.  A field gets a complete snapshot at
 * creation time and every algorithm request is later expanded from that
 * snapshot by the Java gateway.
 */
final class DrillingAlgorithmDefaults
{
    private DrillingAlgorithmDefaults() { }

    static Map<String, Object> create()
    {
        try (var input = new org.springframework.core.io.ClassPathResource("drilling/native-defaults.json").getInputStream()) {
            return JSON.parseObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),Map.class);
        } catch (java.io.IOException ex) { throw new IllegalStateException("缺少固定版本引擎默认配置",ex); }
    }

    static String json(Object value)
    {
        return value == null ? JSON.toJSONString(create())
                : value instanceof String ? String.valueOf(value) : JSON.toJSONString(value);
    }

    static String snapshot(Object value)
    {
        return JSON.toJSONString(merged(value));
    }

    /** Deep-merge a stored partial configuration over the complete defaults. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> merged(Object raw)
    {
        Map<String, Object> result = create();
        if (raw == null) return result;
        Object parsed = raw;
        if (parsed instanceof String text)
        {
            if (text.isBlank()) return result;
            parsed = JSON.parseObject(text, Map.class);
        }
        if (!(parsed instanceof Map<?, ?> map))
            throw new IllegalArgumentException("algorithm defaults must be a JSON object");
        // Legacy parameter names cannot be combined with a native model budget.
        Map<String,Object> compatible=new LinkedHashMap<>();
        map.forEach((k,v)->compatible.put(String.valueOf(k),v));
        if (!(compatible.get("errorModel") instanceof Map<?,?> em) || !em.containsKey("settings")) compatible.remove("errorModel");
        mergeMap(result, compatible);
        // Each uncertainty model has a distinct schema. Its settings are an
        // atomic profile, not a partial override of the Rev.0 source budget.
        // A recursive merge would add Rev.0-only keys to every other model.
        if (compatible.get("errorModel") instanceof Map<?, ?> error
                && error.get("settings") instanceof Map<?, ?> settings)
        {
            ((Map<String, Object>) result.get("errorModel")).put("settings",
                    JSON.parseObject(JSON.toJSONString(settings), Map.class));
        }
        Object predictionValue = map.get("prediction");
        if (predictionValue instanceof Map<?, ?> prediction && prediction.containsKey("modelVersionId")
                && prediction.get("modelVersionId") == null)
        {
            // A configured model must be explicitly clearable when a field
            // returns to the unconfigured state. Clearing a model never
            // enables a geometric prediction fallback.
            ((Map<String, Object>) result.get("prediction")).put("modelVersionId", null);
        }
        Map<String, Object> planning = (Map<String, Object>) result.get("planning");
        planning.remove("segmentCount");
        // Replanning has one safety contract.  Historical snapshots that
        // stored the former SF-only value are migrated in memory so the
        // configuration shown to the operator and the request sent to the
        // native engine cannot disagree.
        Object planningConstraint = planning.get("collisionConstraint");
        if (!"SEPARATION_FACTOR_AND_CENTER_DISTANCE".equalsIgnoreCase(
                String.valueOf(planningConstraint)))
            planning.put("collisionConstraint", "SEPARATION_FACTOR_AND_CENTER_DISTANCE");
        Object nativeValue = planning.get("nativeOptions");
        if (nativeValue instanceof Map<?, ?> nativeOptions)
        {
            ((Map<String, Object>) nativeOptions).remove("scenario_inclination_change_deg");
            ((Map<String, Object>) nativeOptions).remove("scenario_azimuth_change_deg");
            // Configurations created before the combined replanning rule did
            // not have a centre-distance limit and stored zero.  Promote that
            // legacy value to the current engineering default so an old
            // snapshot cannot silently disable the second hard constraint.
            Object centre = ((Map<?, ?>) nativeOptions).get("centerline_min_distance_m");
            if (centre instanceof Number number && number.doubleValue() <= 0.0)
                ((Map<String, Object>) nativeOptions).put("centerline_min_distance_m", 5.0);
        }
        // A live prediction is valid only when it comes from the configured
        // trained model. Never revive the removed geometric fallback from an
        // old field snapshot or a client payload.
        if (result.get("prediction") instanceof Map<?, ?> prediction)
        {
            ((Map<String, Object>) prediction).put("allowFallback", false);
            // This pre-production display option is removed from the
            // effective snapshot so old rows cannot revive it.
            ((Map<String, Object>) prediction).remove("fallbackMode");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void mergeMap(Map<String, Object> target, Map<?, ?> source)
    {
        for (Map.Entry<?, ?> entry : source.entrySet())
        {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            Object current = target.get(key);
            if (current instanceof Map<?, ?> currentMap && value instanceof Map<?, ?> valueMap)
            {
                mergeMap((Map<String, Object>) currentMap, valueMap);
            }
            else if (value != null)
            {
                target.put(key, value);
            }
        }
    }
}
