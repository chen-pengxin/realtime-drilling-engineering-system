package com.ruoyi.web.controller.drilling;

import java.util.ArrayList;
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

/** Field-scoped well CRUD and immutable survey version persistence. */
@RestController
@RequestMapping("/api/drilling/fields/{fieldId}/wells")
public class DrillingWellController
{
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private TrajectoryVersionSupport trajectoryVersions;
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.web.controller.drilling.centers.BusinessEventService versionEvents;

    public DrillingWellController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @RequiresPermissions("drilling:well:list")
    public List<Map<String, Object>> list(@PathVariable Long fieldId)
    {
        requireFieldAccess(fieldId);
        return jdbc.queryForList("""
                SELECT well_id, field_id, well_code, well_name, well_type, status,
                       wellhead_n, wellhead_e, wellhead_tvd, magnetic_declination,
                       meridian_convergence, metadata_json, create_time, update_time
                  FROM drilling_well w WHERE field_id=? AND status <> 'ARCHIVED'
                   AND NOT EXISTS (SELECT 1 FROM drilling_engineering_node en
                                    WHERE en.field_id=w.field_id AND en.well_id=w.well_id
                                      AND en.node_type='WELL' AND en.entity_kind='PLANNED_RELIEF'
                                      AND en.status='ACTIVE')
                 ORDER BY well_id DESC
                """, fieldId);
    }

    @PostMapping
    @RequiresPermissions("drilling:well:add")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable Long fieldId, @RequestBody Map<String, Object> body)
    {
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        String code = requiredText(body, "wellCode");
        String name = requiredText(body, "wellName");
        jdbc.update("""
                INSERT INTO drilling_well
                    (field_id, well_code, well_name, well_type, status, wellhead_n, wellhead_e,
                     wellhead_tvd, magnetic_declination, meridian_convergence, metadata_json, create_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, fieldId, code, name, text(body, "wellType", null), text(body, "status", "PLANNED"),
                decimal(body, "wellheadN", 0), decimal(body, "wellheadE", 0), decimal(body, "wellheadTvd", 0),
                decimal(body, "magneticDeclination", 0), decimal(body, "meridianConvergence", 0),
                json(body.get("metadata")), currentUserName());
        Long wellId = jdbc.queryForObject("SELECT well_id FROM drilling_well WHERE field_id=? AND well_code=?",
                Long.class, fieldId, code);
        return getWell(fieldId, wellId);
    }

    @GetMapping("/{wellId}")
    @RequiresPermissions("drilling:well:list")
    public Map<String, Object> get(@PathVariable Long fieldId, @PathVariable Long wellId)
    {
        requireFieldAccess(fieldId);
        return getWell(fieldId, wellId);
    }

    @PutMapping("/{wellId}")
    @RequiresPermissions("drilling:well:edit")
    public Map<String, Object> update(@PathVariable Long fieldId, @PathVariable Long wellId,
                                      @RequestBody Map<String, Object> body)
    {
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        requireWell(fieldId, wellId);
        jdbc.update("""
                UPDATE drilling_well SET well_name=COALESCE(?, well_name), well_type=COALESCE(?, well_type),
                       wellhead_n=COALESCE(?, wellhead_n), wellhead_e=COALESCE(?, wellhead_e),
                       wellhead_tvd=COALESCE(?, wellhead_tvd), magnetic_declination=COALESCE(?, magnetic_declination),
                       meridian_convergence=COALESCE(?, meridian_convergence), metadata_json=COALESCE(?, metadata_json),
                       update_by=?, update_time=CURRENT_TIMESTAMP WHERE field_id=? AND well_id=?
                """, optionalText(body, "wellName"), optionalText(body, "wellType"), optionalDecimal(body, "wellheadN"),
                optionalDecimal(body, "wellheadE"), optionalDecimal(body, "wellheadTvd"),
                optionalDecimal(body, "magneticDeclination"), optionalDecimal(body, "meridianConvergence"),
                body.containsKey("metadata") ? json(body.get("metadata")) : null,
                currentUserName(), fieldId, wellId);
        return getWell(fieldId, wellId);
    }

    @DeleteMapping("/{wellId}")
    @RequiresPermissions("drilling:well:remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable Long fieldId, @PathVariable Long wellId)
    {
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        requireWell(fieldId, wellId);
        jdbc.update("UPDATE drilling_well SET status='ARCHIVED', update_by=?, update_time=CURRENT_TIMESTAMP WHERE field_id=? AND well_id=?",
                currentUserName(), fieldId, wellId);
    }

    @GetMapping("/{wellId}/surveys")
    @RequiresPermissions("drilling:well:list")
    public List<Map<String, Object>> surveys(@PathVariable Long fieldId, @PathVariable Long wellId)
    {
        requireFieldAccess(fieldId);
        requireWell(fieldId, wellId);
        return jdbc.queryForList("""
                SELECT v.survey_version_id, v.well_id, COALESCE(t.trajectory_version_no,v.version_no) AS version_no, v.parent_version_id, v.source_file_id,
                       v.config_version, v.config_snapshot_json, v.import_mode, v.source_format, v.max_md, v.is_current, v.quality_summary_json,
                       v.create_by, v.create_time,t.wellbore_id,t.trajectory_kind,r.revision_type
                  FROM drilling_survey_version v
                  LEFT JOIN drilling_trajectory_version t ON t.survey_version_id=v.survey_version_id
                  LEFT JOIN drilling_trajectory_revision r ON r.survey_version_id=v.survey_version_id
                  WHERE v.well_id=? AND (t.trajectory_kind='ACTUAL' OR t.trajectory_kind IS NULL) ORDER BY v.version_no DESC
                """, wellId);
    }

    /**
     * Persist a complete, already calculated survey as a new immutable version.
     * Each station must include md, inclination, azimuth and calculated north/east/tvd.
     */
    @PostMapping("/{wellId}/surveys")
    @RequiresPermissions("drilling:well:survey:import")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> createSurveyVersion(@PathVariable Long fieldId, @PathVariable Long wellId,
                                                   @RequestBody Map<String, Object> body)
    {
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        requireWell(fieldId, wellId);
        String importMode = text(body, "importMode", "FULL").toUpperCase();
        if (!List.of("FULL", "INCREMENTAL").contains(importMode))
            throw new IllegalArgumentException("importMode must be FULL or INCREMENTAL");
        Integer configVersion = requiredInteger(body, "configVersion");
        List<Map<String, Object>> configRows = jdbc.queryForList("SELECT * FROM drilling_field_config WHERE field_id=? AND config_version=?",
                fieldId, configVersion);
        if (configRows.isEmpty()) throw new IllegalArgumentException("configVersion is not a valid fieldId configuration");
        Map<String, Object> configSnapshot = configRows.get(0);
        Object rawStations = body.get("stations");
        if (!(rawStations instanceof List<?> rows) || rows.isEmpty())
            throw new IllegalArgumentException("stations must be a non-empty array of calculated stations");
        List<Map<String, Object>> stations = new ArrayList<>();
        double previousMd = -1;
        double maxMd = 0;
        for (Object row : rows)
        {
            if (!(row instanceof Map<?, ?> raw)) throw new IllegalArgumentException("each station must be an object");
            @SuppressWarnings("unchecked") Map<String, Object> station = (Map<String, Object>) raw;
            double md = requiredDecimal(station, "md");
            if (md <= previousMd) throw new IllegalArgumentException("station md values must be strictly increasing");
            requiredDecimal(station, "inclination");
            requiredDecimal(station, "azimuth");
            requiredDecimal(station, "north");
            requiredDecimal(station, "east");
            requiredDecimal(station, "tvd");
            previousMd = md;
            maxMd = md;
            stations.add(station);
        }
        lockWell(wellId);
        Long sourceFileId = optionalLong(body, "sourceFileId");
        requireFieldFile(fieldId, sourceFileId);
        Integer version = trajectoryVersions.nextSnapshotNumber(wellId);
        Long parentId = jdbc.query("SELECT survey_version_id FROM drilling_survey_version WHERE well_id=? AND is_current='1' LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, wellId);
        if ("INCREMENTAL".equals(importMode) && parentId == null)
            throw new IllegalArgumentException("incremental import requires an existing current survey version");
        jdbc.update("UPDATE drilling_survey_version SET is_current='0' WHERE well_id=? AND is_current='1'", wellId);
        jdbc.update("""
                INSERT INTO drilling_survey_version
                    (well_id, version_no, parent_version_id, source_file_id, config_version, import_mode,
                     source_format, max_md, is_current, quality_summary_json, config_snapshot_json, create_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '1', ?, ?, ?)
                """, wellId, version, parentId, sourceFileId, configVersion,
                importMode, text(body, "sourceFormat", null), maxMd, json(body.get("qualitySummary")),
                JSON.toJSONString(configSnapshot), currentUserName());
        Long surveyVersionId = jdbc.queryForObject("SELECT survey_version_id FROM drilling_survey_version WHERE well_id=? AND version_no=?",
                Long.class, wellId, version);
        for (Map<String, Object> station : stations)
        {
            jdbc.update("""
                    INSERT INTO drilling_survey_station
                        (survey_version_id, md, inclination, azimuth, north, east, tvd,
                         dogleg, curvature, raw_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, surveyVersionId, requiredDecimal(station, "md"), requiredDecimal(station, "inclination"),
                    requiredDecimal(station, "azimuth"), requiredDecimal(station, "north"),
                    requiredDecimal(station, "east"), requiredDecimal(station, "tvd"),
                    optionalDecimal(station, "dogleg"), optionalDecimal(station, "curvature"), JSON.toJSONString(station));
        }
        trajectoryVersions.registerActualSnapshot(surveyVersionId, parentId, importMode, null);
        persistQualityIssues(fieldId, wellId, surveyVersionId, sourceFileId, body.get("qualityIssues"));
        var created = survey(fieldId, wellId, surveyVersionId);
        versionEvents.versionChange(fieldId,currentUserName(),"CREATE","SURVEY","SURVEY:"+surveyVersionId,Map.of("well_id",wellId,"version_no",created.get("version_no"),"config_version",configVersion));
        return created;
    }

    @GetMapping("/{wellId}/surveys/{surveyVersionId}")
    @RequiresPermissions("drilling:well:list")
    public Map<String, Object> survey(@PathVariable Long fieldId, @PathVariable Long wellId,
                                      @PathVariable Long surveyVersionId)
    {
        requireFieldAccess(fieldId);
        requireWell(fieldId, wellId);
        Map<String, Object> result = jdbc.queryForMap("""
                SELECT v.survey_version_id, v.well_id, COALESCE(t.trajectory_version_no,v.version_no) AS version_no, v.parent_version_id,
                       v.source_file_id, v.config_version, v.config_snapshot_json, v.import_mode,
                       v.source_format, v.max_md, v.is_current, v.quality_summary_json,
                       v.create_by, v.create_time
                  FROM drilling_survey_version v LEFT JOIN drilling_trajectory_version t ON t.survey_version_id=v.survey_version_id WHERE v.well_id=? AND v.survey_version_id=?
                """, wellId, surveyVersionId);
        result.put("stations", jdbc.queryForList("""
                SELECT station_id, md, inclination, azimuth, north, east, tvd, dogleg, curvature, raw_json
                  FROM drilling_survey_station WHERE survey_version_id=? ORDER BY md
                """, surveyVersionId));
        return result;
    }

    /** Restore a historical immutable survey snapshot as the active version. */
    @PutMapping("/{wellId}/surveys/{surveyVersionId}/activate")
    @RequiresPermissions("drilling:well:survey:import")
    @Transactional
    public Map<String, Object> activateSurvey(@PathVariable Long fieldId, @PathVariable Long wellId,
                                               @PathVariable Long surveyVersionId)
    {
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        requireWell(fieldId, wellId);
        lockWell(wellId);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM drilling_survey_version WHERE survey_version_id=? AND well_id=?",
                Integer.class, surveyVersionId, wellId);
        if (count == null || count == 0) throw new IllegalArgumentException("survey version does not belong to this well");
        var registered = jdbc.queryForList("SELECT wellbore_id,trajectory_kind FROM drilling_trajectory_version WHERE survey_version_id=?", surveyVersionId);
        if (registered.isEmpty()) throw new IllegalArgumentException("历史版本尚未关联井眼，请在井与测斜数据页面完成关联");
        var meta = registered.get(0);
        trajectoryVersions.setHead(((Number)meta.get("wellbore_id")).longValue(),meta.get("trajectory_kind").toString(),surveyVersionId,wellId);
        versionEvents.versionChange(fieldId,currentUserName(),"ACTIVATE","SURVEY","SURVEY:"+surveyVersionId,Map.of("well_id",wellId));
        return survey(fieldId, wellId, surveyVersionId);
    }

    private void persistQualityIssues(Long fieldId, Long wellId, Long versionId, Long defaultSourceFileId, Object rawIssues)
    {
        if (rawIssues == null) return;
        if (!(rawIssues instanceof List<?> issues)) throw new IllegalArgumentException("qualityIssues must be an array");
        for (Object item : issues)
        {
            if (!(item instanceof Map<?, ?> raw)) throw new IllegalArgumentException("each quality issue must be an object");
            @SuppressWarnings("unchecked") Map<String, Object> issue = (Map<String, Object>) raw;
            Long issueWellId = optionalLong(issue, "wellId");
            if (issueWellId != null && !issueWellId.equals(wellId))
                throw new IllegalArgumentException("quality issue wellId does not belong to the survey well");
            Long issueVersionId = optionalLong(issue, "surveyVersionId");
            if (issueVersionId != null && !issueVersionId.equals(versionId))
                throw new IllegalArgumentException("quality issue surveyVersionId does not belong to the new survey version");
            Long issueFileId = optionalLong(issue, "sourceFileId");
            if (issueFileId == null) issueFileId = defaultSourceFileId;
            requireFieldFile(fieldId, issueFileId);
            Long stationId = optionalLong(issue, "stationId");
            if (stationId != null)
            {
                Integer stationCount = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM drilling_survey_station
                         WHERE station_id=? AND survey_version_id=?
                        """, Integer.class, stationId, versionId);
                if (stationCount == null || stationCount == 0)
                    throw new IllegalArgumentException("quality issue stationId does not belong to the new survey version");
            }
            jdbc.update("""
                    INSERT INTO drilling_data_quality_issue
                        (field_id, well_id, survey_version_id, station_id, source_file_id,
                         issue_type, severity, status, md, raw_json, create_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'ISOLATED', ?, ?, ?)
                    """, fieldId, wellId, versionId, stationId, issueFileId,
                    text(issue, "issueType", "UNSPECIFIED"), text(issue, "severity", "WARNING"),
                    optionalDecimal(issue, "md"),
                    JSON.toJSONString(issue), currentUserName());
        }
    }

    private Map<String, Object> getWell(Long fieldId, Long wellId)
    {
        requireWell(fieldId, wellId);
        return jdbc.queryForMap("SELECT * FROM drilling_well WHERE field_id=? AND well_id=?", fieldId, wellId);
    }

    private void requireWell(Long fieldId, Long wellId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM drilling_well w WHERE w.field_id=? AND w.well_id=?
                  AND NOT EXISTS (SELECT 1 FROM drilling_engineering_node en
                                   WHERE en.field_id=w.field_id AND en.well_id=w.well_id
                                     AND en.node_type='WELL' AND en.entity_kind='PLANNED_RELIEF'
                                     AND en.status='ACTIVE')
                """,
                Integer.class, fieldId, wellId);
        if (count == null || count == 0) throw new IllegalArgumentException("well is not accessible or does not exist");
    }

    private void lockWell(Long wellId)
    {
        Long lockedId = jdbc.query("""
                SELECT well_id FROM drilling_well WHERE well_id=? FOR UPDATE
                """, rs -> rs.next() ? rs.getLong(1) : null, wellId);
        if (lockedId == null) throw new IllegalArgumentException("well does not exist");
    }

    private void requireFieldFile(Long fieldId, Long fileId)
    {
        if (fileId == null) return;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM data_file
                 WHERE file_id=? AND field_id=? AND status <> 'DELETED'
                """, Integer.class, fileId, fieldId);
        if (count == null || count == 0)
            throw new IllegalArgumentException("source file does not belong to the fieldId");
    }

    private void requireFieldAccess(Long fieldId)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireAccess(fieldId);
    }

    private void requireFieldRole(Long fieldId, String... allowedRoles)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireRole(fieldId, allowedRoles);
    }

    private String currentUserName() { return ShiroUtils.getLoginName(); }

    private static String requiredText(Map<String, Object> body, String key)
    {
        String value = optionalText(body, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String optionalText(Map<String, Object> body, String key)
    {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String text(Map<String, Object> body, String key, String fallback)
    {
        String value = optionalText(body, key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double requiredDecimal(Map<String, Object> body, String key)
    {
        Double value = optionalDecimal(body, key);
        if (value == null) throw new IllegalArgumentException(key + " is required and must be numeric");
        return value;
    }

    private static Integer requiredInteger(Map<String, Object> body, String key)
    {
        double value = requiredDecimal(body, key);
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException(key + " must be an integer");
        return (int) value;
    }

    private static Double optionalDecimal(Map<String, Object> body, String key)
    {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return value instanceof Number n ? n.doubleValue() : Double.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " must be numeric"); }
    }

    private static Long optionalLong(Map<String, Object> body, String key)
    {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " must be an integer"); }
    }

    private static double decimal(Map<String, Object> body, String key, double fallback)
    {
        Double value = optionalDecimal(body, key);
        return value == null ? fallback : value;
    }

    private static String json(Object value)
    {
        return value == null ? null : value instanceof String ? String.valueOf(value) : JSON.toJSONString(value);
    }
}
