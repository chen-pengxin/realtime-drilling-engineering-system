package com.ruoyi.web.controller.drilling;

import java.util.List;
import java.util.Map;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.alibaba.fastjson.JSON;
import com.ruoyi.common.utils.ShiroUtils;

/** Versioned coordinate and algorithm configuration for a drilling field. */
@RestController
@RequestMapping("/api/drilling/fields/{fieldId}/configs")
public class DrillingFieldConfigController
{
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private NativeEngineSupport nativeEngine;
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.web.controller.drilling.centers.BusinessEventService versionEvents;

    public DrillingFieldConfigController(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    @GetMapping
    @RequiresPermissions("drilling:field:view")
    public List<Map<String, Object>> list(@PathVariable Long fieldId)
    {
        requireFieldAccess(fieldId);
        return jdbc.queryForList("""
                SELECT field_id, config_version, coordinate_system, north_reference,
                       length_unit, angle_unit, tvd_positive, origin_north, origin_east,
                       origin_tvd, magnetic_declination, meridian_convergence,
                       algorithm_defaults_json, effective_from, effective_to, status,
                       create_by, create_time, update_by, update_time
                  FROM drilling_field_config WHERE field_id=?
                 ORDER BY config_version DESC
                """, fieldId);
    }

    @GetMapping("/current")
    @RequiresPermissions("drilling:field:view")
    public Map<String, Object> current(@PathVariable Long fieldId)
    {
        requireFieldAccess(fieldId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT field_id, config_version, coordinate_system, north_reference,
                       length_unit, angle_unit, tvd_positive, origin_north, origin_east,
                       origin_tvd, magnetic_declination, meridian_convergence,
                       algorithm_defaults_json, effective_from, effective_to, status
                  FROM drilling_field_config
                 WHERE field_id=? AND status='0' AND effective_to IS NULL
                 ORDER BY config_version DESC LIMIT 1
                """, fieldId);
        if (!rows.isEmpty()) return rows.get(0);
        return jdbc.queryForMap("""
                SELECT field_id, 1 AS config_version, coordinate_system,
                       'TRUE_NORTH' AS north_reference, length_unit, angle_unit, tvd_positive,
                       0 AS origin_north, 0 AS origin_east, 0 AS origin_tvd,
                       0 AS magnetic_declination, 0 AS meridian_convergence,
                       config_json AS algorithm_defaults_json, create_time AS effective_from,
                       NULL AS effective_to, status
                  FROM drilling_field WHERE field_id=? AND status='0'
                """, fieldId);
    }

    @PostMapping
    @RequiresPermissions("drilling:field:config")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createVersion(@PathVariable Long fieldId, @RequestBody Map<String, Object> body)
    {
        requireFieldRole(fieldId, "OWNER");
        lockField(fieldId);
        Map<String, Object> current = currentConfig(fieldId);
        String algorithmDefaultsJson = DrillingAlgorithmDefaults.snapshot(
                body.containsKey("algorithmDefaults") ? body.get("algorithmDefaults") : current.get("algorithm_defaults_json"));
        // Persist a complete snapshot even when a client submits only the
        // fields it changed.  The gateway uses this snapshot as authority.
        validateAlgorithmDefaults(JSON.parseObject(algorithmDefaultsJson));
        nativeEngine.validate(JSON.parseObject(algorithmDefaultsJson,Map.class));
        validatePredictionResources(fieldId, JSON.parseObject(algorithmDefaultsJson));
        Map<String, Object> validation = new java.util.HashMap<>(body);
        validation.put("coordinateSystem", text(body, "coordinateSystem", String.valueOf(current.get("coordinate_system"))));
        validation.put("northReference", text(body, "northReference", String.valueOf(current.get("north_reference"))));
        validation.put("lengthUnit", text(body, "lengthUnit", String.valueOf(current.get("length_unit"))));
        validation.put("angleUnit", text(body, "angleUnit", String.valueOf(current.get("angle_unit"))));
        validation.put("tvdPositive", text(body, "tvdPositive", String.valueOf(current.get("tvd_positive"))));
        validation.put("algorithmDefaults", JSON.parseObject(algorithmDefaultsJson));
        validate(validation);
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(config_version), 0) + 1 FROM drilling_field_config WHERE field_id=?",
                Integer.class, fieldId);
        jdbc.update("""
                UPDATE drilling_field_config SET status='1', effective_to=CURRENT_TIMESTAMP,
                       update_by=?, update_time=CURRENT_TIMESTAMP
                 WHERE field_id=? AND status='0' AND effective_to IS NULL
                """, currentUserName(), fieldId);
        jdbc.update("""
                INSERT INTO drilling_field_config
                    (field_id, config_version, coordinate_system, north_reference,
                     length_unit, angle_unit, tvd_positive, origin_north, origin_east,
                     origin_tvd, magnetic_declination, meridian_convergence,
                     algorithm_defaults_json, status, create_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '0', ?)
                """, fieldId, version, text(body, "coordinateSystem", String.valueOf(current.get("coordinate_system"))),
                text(body, "northReference", String.valueOf(current.get("north_reference"))),
                text(body, "lengthUnit", String.valueOf(current.get("length_unit"))),
                text(body, "angleUnit", String.valueOf(current.get("angle_unit"))),
                text(body, "tvdPositive", String.valueOf(current.get("tvd_positive"))),
                numberOrCurrent(body, "originNorth", current.get("origin_north")),
                numberOrCurrent(body, "originEast", current.get("origin_east")),
                numberOrCurrent(body, "originTvd", current.get("origin_tvd")),
                numberOrCurrent(body, "magneticDeclination", current.get("magnetic_declination")),
                numberOrCurrent(body, "meridianConvergence", current.get("meridian_convergence")),
                algorithmDefaultsJson, currentUserName());
        // Keep the field summary aligned with the active version for list screens.
        jdbc.update("""
                UPDATE drilling_field SET coordinate_system=?, length_unit=?, angle_unit=?,
                       tvd_positive=?, config_json=?, update_by=?, update_time=CURRENT_TIMESTAMP
                 WHERE field_id=?
                """, text(body, "coordinateSystem", String.valueOf(current.get("coordinate_system"))), text(body, "lengthUnit", String.valueOf(current.get("length_unit"))),
                text(body, "angleUnit", String.valueOf(current.get("angle_unit"))), text(body, "tvdPositive", String.valueOf(current.get("tvd_positive"))),
                algorithmDefaultsJson, currentUserName(), fieldId);
        versionEvents.versionChange(fieldId,currentUserName(),"CREATE","CONFIG","CONFIG:"+version,Map.of("previous_version",current.get("config_version"),"version",version));
        return jdbc.queryForMap("SELECT * FROM drilling_field_config WHERE field_id=? AND config_version=?",
                fieldId, version);
    }

    @PutMapping("/{version}/activate")
    @RequiresPermissions("drilling:field:config")
    @Transactional
    public Map<String, Object> activate(@PathVariable Long fieldId, @PathVariable Integer version)
    {
        requireFieldRole(fieldId, "OWNER");
        lockField(fieldId);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM drilling_field_config WHERE field_id=? AND config_version=?",
                Integer.class, fieldId, version);
        if (count == null || count == 0) throw new IllegalArgumentException("configuration version not found");
        jdbc.update("""
                UPDATE drilling_field_config SET status='1', effective_to=CURRENT_TIMESTAMP,
                       update_by=?, update_time=CURRENT_TIMESTAMP
                 WHERE field_id=? AND status='0'
                """, currentUserName(), fieldId);
        jdbc.update("""
                UPDATE drilling_field_config SET status='0', effective_from=CURRENT_TIMESTAMP,
                       effective_to=NULL, update_by=?, update_time=CURRENT_TIMESTAMP
                 WHERE field_id=? AND config_version=?
                """, currentUserName(), fieldId, version);
        jdbc.update("""
                UPDATE drilling_field SET coordinate_system=(SELECT coordinate_system FROM drilling_field_config WHERE field_id=? AND config_version=?),
                       length_unit=(SELECT length_unit FROM drilling_field_config WHERE field_id=? AND config_version=?),
                       angle_unit=(SELECT angle_unit FROM drilling_field_config WHERE field_id=? AND config_version=?),
                       tvd_positive=(SELECT tvd_positive FROM drilling_field_config WHERE field_id=? AND config_version=?),
                       update_by=?, update_time=CURRENT_TIMESTAMP WHERE field_id=?
                """, fieldId, version, fieldId, version, fieldId, version, fieldId, version,
                currentUserName(), fieldId);
        versionEvents.versionChange(fieldId,currentUserName(),"ACTIVATE","CONFIG","CONFIG:"+version,Map.of("version",version));
        return jdbc.queryForMap("SELECT * FROM drilling_field_config WHERE field_id=? AND config_version=?",
                fieldId, version);
    }

    @DeleteMapping("/{version}")
    @RequiresPermissions("drilling:field:config")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable Long fieldId, @PathVariable Integer version)
    {
        requireFieldRole(fieldId, "OWNER");
        lockField(fieldId);
        int changed = jdbc.update("""
                UPDATE drilling_field_config SET status='1', effective_to=COALESCE(effective_to, CURRENT_TIMESTAMP),
                       update_by=?, update_time=CURRENT_TIMESTAMP
                 WHERE field_id=? AND config_version=? AND NOT (status='0' AND effective_to IS NULL)
                """, currentUserName(), fieldId, version);
        if (changed == 0)
        {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM drilling_field_config WHERE field_id=? AND config_version=?",
                    Integer.class, fieldId, version);
            if (exists == null || exists == 0) throw new IllegalArgumentException("configuration version not found");
            throw new IllegalArgumentException("activate another configuration before archiving the current version");
        }
    }

    /** Read the active version and fall back to the field summary. */
    private Map<String, Object> currentConfig(Long fieldId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT config_version, coordinate_system, north_reference, length_unit, angle_unit, tvd_positive,
                       origin_north, origin_east, origin_tvd, magnetic_declination, meridian_convergence,
                       algorithm_defaults_json
                  FROM drilling_field_config
                 WHERE field_id=? AND status='0' AND effective_to IS NULL
                 ORDER BY config_version DESC LIMIT 1
                """, fieldId);
        if (!rows.isEmpty()) return rows.get(0);
        rows = jdbc.queryForList("""
                SELECT 0 AS config_version, coordinate_system, 'TRUE_NORTH' AS north_reference, length_unit, angle_unit,
                       tvd_positive, 0 AS origin_north, 0 AS origin_east, 0 AS origin_tvd,
                       0 AS magnetic_declination, 0 AS meridian_convergence,
                       config_json AS algorithm_defaults_json
                  FROM drilling_field WHERE field_id=? AND status='0'
                """, fieldId);
        if (rows.isEmpty()) throw new IllegalArgumentException("field is not accessible");
        return rows.get(0);
    }

    private void requireFieldAccess(Long fieldId)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireAccess(fieldId);
    }

    private void requireFieldRole(Long fieldId, String... allowedRoles)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireRole(fieldId, allowedRoles);
    }

    /** Serialize version allocation and active-version switching for one field. */
    private void lockField(Long fieldId)
    {
        // Lock the actual InnoDB field row.  COUNT(*) does not reliably lock
        // the row used by the following MAX(config_version) allocation across
        // MySQL versions and isolation levels.
        Long lockedId = jdbc.query("""
                SELECT field_id FROM drilling_field
                 WHERE field_id=? AND status='0' FOR UPDATE
                """, rs -> rs.next() ? rs.getLong(1) : null, fieldId);
        if (lockedId == null) throw new IllegalArgumentException("field is not accessible");
    }

    private String currentUserName() { return ShiroUtils.getLoginName(); }

    private void validatePredictionResources(Long fieldId, Map<String, Object> defaults)
    {
        Object rawPrediction = defaults.get("prediction");
        if (!(rawPrediction instanceof Map<?, ?> prediction)) return;
        Object modelVersionId = prediction.get("modelVersionId");
        if (modelVersionId != null)
        {
            Long id;
            try { id = modelVersionId instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(modelVersionId)); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("prediction.modelVersionId must be a positive integer"); }
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM model_version mv JOIN data_file f ON f.file_id=mv.file_id
                     WHERE mv.model_version_id=? AND mv.field_id=? AND mv.status='READY' AND f.status='ACTIVE'
                    """, Integer.class, id, fieldId);
            if (count == null || count == 0)
                throw new IllegalArgumentException("prediction.modelVersionId must refer to a READY model in this field");
        }
        Object rawTemplate = prediction.get("templateRuntimePath");
        boolean hasTemplate = rawTemplate != null && !String.valueOf(rawTemplate).isBlank();
        if (modelVersionId != null && !hasTemplate)
            throw new IllegalArgumentException("prediction.templateRuntimePath is required when a model version is configured");
        if (hasTemplate)
        {
            String runtimePath = String.valueOf(rawTemplate);
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM training_template t JOIN data_file f ON f.file_id=t.file_id
                     WHERE t.field_id=? AND t.runtime_path=? AND t.status='ACTIVE'
                       AND f.field_id=t.field_id AND f.file_category='TEMPLATE'
                       AND f.is_template='1' AND f.status='ACTIVE'
                    """, Integer.class, fieldId, runtimePath);
            if (count == null || count == 0)
                throw new IllegalArgumentException("prediction.templateRuntimePath must refer to an active template in this field");
        }
    }

    private static void validate(Map<String, Object> body)
    {
        String unit = text(body, "lengthUnit", "m");
        String angle = text(body, "angleUnit", "deg");
        String tvd = text(body, "tvdPositive", "D");
        String north = text(body, "northReference", "TRUE_NORTH");
        if (!List.of("m", "ft").contains(unit)) throw new IllegalArgumentException("lengthUnit must be m or ft");
        if (!List.of("deg", "rad").contains(angle)) throw new IllegalArgumentException("angleUnit must be deg or rad");
        if (!List.of("D", "U").contains(tvd)) throw new IllegalArgumentException("tvdPositive must be D or U");
        if (!List.of("TRUE_NORTH", "GRID_NORTH", "MAGNETIC_NORTH").contains(north))
            throw new IllegalArgumentException("northReference must be TRUE_NORTH, GRID_NORTH or MAGNETIC_NORTH");
        requiredText(body, "coordinateSystem");
        validateAlgorithmDefaults(body.get("algorithmDefaults"));
    }

    private static String requiredText(Map<String, Object> body, String key)
    {
        String value = text(body, key, "");
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String text(Map<String, Object> body, String key, String fallback)
    {
        Object value = body.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private static double decimal(Map<String, Object> body, String key, double fallback)
    {
        Object value = body.get(key);
        if (value == null) return fallback;
        try { return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " must be numeric"); }
    }

    private static Number numberOrCurrent(Map<String, Object> body, String key, Object current)
    {
        if (body.containsKey(key)) return decimal(body, key, 0);
        if (current == null) return 0;
        return current instanceof Number n ? n : decimal(Map.of(key, current), key, 0);
    }

    private static String json(Object value)
    {
        return value == null ? null : value instanceof String ? String.valueOf(value) : JSON.toJSONString(value);
    }

    /** Validate the stable subset consumed by FastAPI before storing JSON. */
    @SuppressWarnings("unchecked")
    static void validateAlgorithmDefaults(Object raw)
    {
        if (raw == null) return;
        Object value = raw;
        if (value instanceof String text && !text.isBlank())
        {
            try { value = JSON.parseObject(text, Map.class); }
            catch (RuntimeException ex) { throw new IllegalArgumentException("algorithmDefaults must be valid JSON"); }
        }
        if (!(value instanceof Map<?, ?> defaults))
            throw new IllegalArgumentException("algorithmDefaults must be an object");
        Object mode = defaults.get("evaluationMode");
        if (mode != null && !"BOTH".equalsIgnoreCase(String.valueOf(mode)))
            throw new IllegalArgumentException("evaluationMode must be BOTH; the system always calculates spacing and separation factor together");
        if (defaults.containsKey("scanStep")) positive(defaults, "scanStep", 1.0);
        Map<?, ?> thresholds = nestedMap(defaults.get("thresholds"), "thresholds");
        if (thresholds != null)
        {
            double red = nonNegative(thresholds, "redSf", 1.0);
            double orange = nonNegative(thresholds, "orangeSf", 1.5);
            double yellow = nonNegative(thresholds, "yellowSf", 2.5);
            if (!(red <= orange && orange <= yellow)) throw new IllegalArgumentException("SF thresholds must be nondecreasing");
            nonNegative(thresholds, "redDistance", 5.0);
            double errorOrange = nonNegative(thresholds, "errorNetRatioOrange", 0.5);
            double errorYellow = nonNegative(thresholds, "errorNetRatioYellow", 1.0);
            if (errorYellow < errorOrange) throw new IllegalArgumentException("error net thresholds must be nondecreasing");
        }
        Map<?, ?> error = nestedMap(defaults.get("errorModel"), "errorModel");
        if (error != null)
        {
            positive(error,"k",1.0);
            positive(error,"curveToleranceM",0.01);
            nonNegative(error,"radiiMarginM",0.0);
            nonNegative(error,"sigmaPaM",0.0);
            String method=String.valueOf(defaultIfNull(error.get("sfMethod"),"major_axis_sum"));
            if(!List.of("major_axis_sum","directional_sum","project_rss").contains(method)) throw new IllegalArgumentException("未知SF计算方法");
            if(!(error.get("settings") instanceof Map<?,?> settings) || !(settings.get("uncertainty_model") instanceof Map<?,?>)) throw new IllegalArgumentException("必须配置新引擎误差模型参数");
        }

        Map<?, ?> planning = nestedMap(defaults.get("planning"), "planning");
        if (planning != null)
        {
            double maxDogleg = positive(planning, "maxDoglegDegPer30m", 6.0);
            double minDogleg = nonNegative(planning, "minDoglegDegPer30m", 0.0);
            if (minDogleg > maxDogleg) throw new IllegalArgumentException("planning.minDoglegDegPer30m must be <= maxDoglegDegPer30m");
            double min = positive(planning, "minSegmentLength", 3.0);
            double max = positive(planning, "maxSegmentLength", 30.0);
            if (max < min) throw new IllegalArgumentException("planning.maxSegmentLength must be >= minSegmentLength");
            double toolface = nonNegative(planning, "maxToolfaceChangeDeg", 180.0);
            if (toolface > 360) throw new IllegalArgumentException("planning.maxToolfaceChangeDeg must be <= 360");
            positive(planning, "minimumSf", 1.5);
            positive(planning, "targetToleranceM", 0.5);
            Map<?, ?> nativeOptions = nestedMap(planning.get("nativeOptions"), "planning.nativeOptions");
            if (nativeOptions != null)
            {
                String trajectoryMode = String.valueOf(defaultIfNull(nativeOptions.get("trajectory_mode"), "arc-straight"));
                if (!List.of("arc", "arc-straight", "straight-arc").contains(trajectoryMode))
                    throw new IllegalArgumentException("planning.nativeOptions.trajectory_mode must be arc, arc-straight or straight-arc");
                double segmentCount = number(nativeOptions, "n_segments", 3);
                if (!Double.isFinite(segmentCount) || segmentCount < 1 || segmentCount > 5 || segmentCount != Math.rint(segmentCount))
                    throw new IllegalArgumentException("planning.nativeOptions.n_segments must be an integer between 1 and 5");
            }
            String solver = String.valueOf(defaultIfNull(planning.get("solver"), "SLSQP"));
            if (!List.of("SLSQP", "trust-constr", "COBYLA", "COBYQA").contains(solver)) throw new IllegalArgumentException("求解器必须来自wellpath-engine支持列表");
            double maxIterations = number(planning, "maxIterations", 500);
            if (!Double.isFinite(maxIterations) || maxIterations < 1 || maxIterations > 5000 || maxIterations != Math.rint(maxIterations))
                throw new IllegalArgumentException("planning.maxIterations must be an integer between 1 and 5000");
        }
        Map<?, ?> prediction = nestedMap(defaults.get("prediction"), "prediction");
        if (prediction != null)
        {
            positive(prediction, "predictionStep", 9.0);
            double timeout = number(prediction, "timeoutSeconds", 30.0);
            if (!Double.isFinite(timeout) || timeout < 1 || timeout > 300 || timeout != Math.rint(timeout))
                throw new IllegalArgumentException("prediction.timeoutSeconds must be an integer between 1 and 300");
            Object modelVersionId = prediction.get("modelVersionId");
            if (modelVersionId != null && (!String.valueOf(modelVersionId).matches("[0-9]+") || Long.parseLong(String.valueOf(modelVersionId)) <= 0))
                throw new IllegalArgumentException("prediction.modelVersionId must be a positive integer or null");
            String device = String.valueOf(defaultIfNull(prediction.get("device"), "auto")).toLowerCase();
            if (!List.of("auto", "cpu", "cuda:0").contains(device))
                throw new IllegalArgumentException("prediction.device must be auto, cpu or cuda:0");
            String fallback = String.valueOf(defaultIfNull(prediction.get("fallbackMode"), "TANGENT_EXTRAPOLATION"));
            if (!"TANGENT_EXTRAPOLATION".equalsIgnoreCase(fallback))
                throw new IllegalArgumentException("prediction.fallbackMode must be TANGENT_EXTRAPOLATION");
            String alert = String.valueOf(defaultIfNull(prediction.get("alertMinimumLevel"), "YELLOW")).toUpperCase();
            if (!List.of("YELLOW", "ORANGE", "RED").contains(alert))
                throw new IllegalArgumentException("prediction.alertMinimumLevel must be YELLOW, ORANGE or RED");
            Object script = prediction.get("templateRuntimePath");
            if (script != null && (!String.valueOf(script).isBlank() && (!String.valueOf(script).endsWith(".py") || String.valueOf(script).contains("..") || String.valueOf(script).startsWith("/"))))
                throw new IllegalArgumentException("prediction.templateRuntimePath must be a safe relative .py path");
        }
    }

    private static Map<?, ?> nestedMap(Object value, String key)
    {
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(key + " must be an object");
        return map;
    }

    private static double nonNegative(Map<?, ?> map, String key, double fallback)
    {
        double value = number(map, key, fallback);
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(key + " must be non-negative");
        return value;
    }

    private static double positive(Map<?, ?> map, String key, double fallback)
    {
        double value = number(map, key, fallback);
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }

    private static double number(Map<?, ?> map, String key, double fallback)
    {
        Object value = map.get(key);
        if (value == null) return fallback;
        try { return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " must be numeric"); }
    }

    private static void validateParameterMap(Object raw, String key)
    {
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?> values)) throw new IllegalArgumentException(key + " must be an object");
        for (Map.Entry<?, ?> entry : values.entrySet())
        {
            double value = number(values, String.valueOf(entry.getKey()), 0.0);
            if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " values must be finite");
        }
    }

    private static Object defaultIfNull(Object value, Object fallback)
    {
        return value == null ? fallback : value;
    }
}
