package com.ruoyi.web.controller.drilling;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.utils.ShiroUtils;

/** Field-scoped metadata for training datasets and executable templates. */
@RestController
@RequestMapping("/api/drilling/fields/{fieldId}/training")
@RequiresAuthentication
public class TrainingCatalogController
{
    private static final Pattern RELATIVE_PATH = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,498}$");
    private final JdbcTemplate jdbc;

    public TrainingCatalogController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/datasets")
    @RequiresPermissions("drilling:training:list")
    public List<Map<String, Object>> datasets(@PathVariable Long fieldId)
    {
        requireFieldAccess(fieldId);
        return jdbc.queryForList("""
                SELECT d.dataset_id, d.field_id, d.file_id, d.dataset_name, d.version_no,
                       d.runtime_path, d.schema_json, d.status, d.create_by, d.create_time,
                       f.original_name, f.sha256, f.file_size, f.version_no AS file_version_no
                  FROM training_dataset d JOIN data_file f ON f.file_id=d.file_id
                 WHERE d.field_id=? AND d.status='READY' AND f.status='ACTIVE'
                 ORDER BY d.dataset_id DESC
                """, fieldId);
    }

    @PostMapping("/datasets")
    @RequiresPermissions("drilling:training:catalog")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createDataset(@PathVariable Long fieldId, @RequestBody Map<String, Object> body)
    {
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        Long fileId = requiredLong(body, "fileId");
        Map<String, Object> file = requireFile(fieldId, fileId, "DATASET", false);
        String name = requiredText(body, "datasetName");
        String runtimePath = runtimePath(fieldId, file, false);
        int version = number(body.get("versionNo"), 1);
        if (version < 1) throw new IllegalArgumentException("versionNo must be positive");
        jdbc.update("""
                INSERT INTO training_dataset
                    (field_id, file_id, runtime_path, dataset_name, version_no, schema_json, status, create_by)
                VALUES (?, ?, ?, ?, ?, ?, 'READY', ?)
                """, fieldId, fileId, runtimePath, name, version,
                json(body.get("schema")), ShiroUtils.getLoginName());
        Long id = jdbc.queryForObject("SELECT dataset_id FROM training_dataset WHERE field_id=? AND file_id=? AND dataset_name=? ORDER BY dataset_id DESC LIMIT 1",
                Long.class, fieldId, fileId, name);
        return jdbc.queryForMap("SELECT * FROM training_dataset WHERE dataset_id=?", id);
    }

    @GetMapping("/templates")
    @RequiresPermissions("drilling:training:list")
    public List<Map<String, Object>> templates(@PathVariable Long fieldId)
    {
        requireFieldAccess(fieldId);
        return jdbc.queryForList("""
                SELECT t.template_id, t.field_id, t.file_id, t.template_name, t.runtime_path,
                       t.framework, t.hyperparam_schema_json, t.status, t.create_by, t.create_time,
                       f.original_name, f.sha256, f.file_size, f.version_no AS file_version_no
                  FROM training_template t JOIN data_file f ON f.file_id=t.file_id
                 WHERE t.field_id=? AND t.status='ACTIVE' AND f.status='ACTIVE'
                 ORDER BY t.template_id DESC
                """, fieldId);
    }

    @PostMapping("/templates")
    @RequiresPermissions("drilling:training:catalog")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createTemplate(@PathVariable Long fieldId, @RequestBody Map<String, Object> body)
    {
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        Long fileId = requiredLong(body, "fileId");
        Map<String, Object> file = requireFile(fieldId, fileId, "TEMPLATE", true);
        String name = requiredText(body, "templateName");
        String runtimePath = runtimePath(fieldId, file, true);
        String framework = text(body.get("framework"), null);
        jdbc.update("""
                INSERT INTO training_template
                    (field_id, file_id, runtime_path, template_name, framework,
                     hyperparam_schema_json, status, create_by)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                """, fieldId, fileId, runtimePath, name, framework,
                jsonOrEmpty(body.get("hyperparamSchema")), ShiroUtils.getLoginName());
        Long id = jdbc.queryForObject("SELECT template_id FROM training_template WHERE field_id=? AND file_id=? AND template_name=? ORDER BY template_id DESC LIMIT 1",
                Long.class, fieldId, fileId, name);
        return jdbc.queryForMap("SELECT * FROM training_template WHERE template_id=?", id);
    }

    private Map<String, Object> requireFile(Long fieldId, Long fileId, String category, boolean template)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT file_id, file_category, is_template, status, object_key
                  FROM data_file WHERE file_id=? AND field_id=? AND status='ACTIVE'
                """, fileId, fieldId);
        if (rows.isEmpty()) throw new IllegalArgumentException("file is not accessible in this fieldId");
        Map<String, Object> row = rows.get(0);
        if (!category.equalsIgnoreCase(String.valueOf(row.get("file_category"))))
            throw new IllegalArgumentException("file category must be " + category);
        boolean markedTemplate = "1".equals(String.valueOf(row.get("is_template")));
        if (markedTemplate != template)
            throw new IllegalArgumentException(template ? "template file must be uploaded with template=true" : "dataset file cannot be marked as a template");
        return row;
    }

    /** Runtime paths point to the uploaded fieldId file on the shared read-only worker volume. */
    private static String runtimePath(Long fieldId, Map<String, Object> file, boolean template)
    {
        String value = text(file.get("object_key"), null);
        String expectedPrefix = "drilling-files/" + fieldId + "/";
        if (value == null || !value.startsWith(expectedPrefix) || !RELATIVE_PATH.matcher(value).matches()
                || value.contains("..") || value.startsWith("/") || value.startsWith("\\"))
            throw new IllegalArgumentException("fieldId file does not have a safe worker runtime path");
        if (template && !value.toLowerCase().endsWith(".py"))
            throw new IllegalArgumentException("training template file must end with .py");
        return value;
    }

    private void requireFieldAccess(Long fieldId)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireAccess(fieldId);
    }

    private void requireFieldRole(Long fieldId, String... roles)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireRole(fieldId, roles);
    }

    private static Long requiredLong(Map<String, Object> body, String key)
    {
        Object raw = body.get(key);
        if (raw == null) throw new IllegalArgumentException(key + " is required");
        try { return raw instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(raw)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " must be an integer", ex); }
    }

    private static String requiredText(Map<String, Object> body, String key)
    {
        String value = text(body.get(key), null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String text(Object value, String fallback)
    {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private static int number(Object value, int fallback)
    {
        if (value == null) return fallback;
        try { return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("versionNo must be an integer", ex); }
    }

    private static String json(Object value) { return value == null ? null : value instanceof String ? String.valueOf(value) : JSON.toJSONString(value); }
    private static String jsonOrEmpty(Object value) { return value == null ? "{}" : json(value); }
}
