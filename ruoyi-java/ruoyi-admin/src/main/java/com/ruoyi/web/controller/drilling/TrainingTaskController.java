package com.ruoyi.web.controller.drilling;

import java.io.IOException;
import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.utils.ShiroUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Field-scoped training task persistence and FastAPI orchestration. */
@RestController
@RequestMapping("/api/train/task")
@RequiresAuthentication
public class TrainingTaskController
{
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final JdbcTemplate jdbc;

    public TrainingTaskController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Value("${drilling.algorithm.url:http://localhost:8000}")
    private String algorithmUrl;

    @PostMapping("/submit")
    @RequiresPermissions("drilling:training:run")
    @Transactional
    public ResponseEntity<String> submit(@RequestBody String body) throws IOException, InterruptedException
    {
        Map<String, Object> payload = object(body);
        Long fieldId = fieldId(payload);
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        Long datasetId = requiredLong(payload, "dataset_id", "datasetId");
        Long templateId = requiredLong(payload, "template_id", "templateId");
        Map<String, Object> dataset = catalogRow("training_dataset", "dataset_id", datasetId, fieldId);
        Map<String, Object> template = catalogRow("training_template", "template_id", templateId, fieldId);
        Long modelVersionId = optionalLong(payload, "model_version_id", "modelVersionId");
        if (modelVersionId != null) requireModelVersion(fieldId, modelVersionId);
        String device = text(payload.get("device"), "auto");
        String hyperparameters = json(payload.get("hyperparameters"));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO training_task
                    (field_id, dataset_id, template_id, device, hyperparams_json,
                     status, log_stream_key, model_version_id, create_by, update_time)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, CURRENT_TIMESTAMP)
                """, Statement.RETURN_GENERATED_KEYS);
            int index = 1;
            statement.setObject(index++, fieldId);
            statement.setObject(index++, datasetId);
            statement.setObject(index++, templateId);
            statement.setString(index++, device);
            statement.setString(index++, hyperparameters);
            statement.setString(index++, "drilling:training:pending:" + java.util.UUID.randomUUID());
            statement.setObject(index++, modelVersionId);
            statement.setString(index, ShiroUtils.getLoginName());
            return statement;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) throw new IllegalStateException("training task id was not generated");
        Long localId = generatedId.longValue();

        Map<String, Object> forwarded = new HashMap<>(payload);
        forwarded.put("field_id", fieldId);
        forwarded.put("template", text(template.get("runtime_path"), ""));
        forwarded.put("dataset", text(dataset.get("runtime_path"), ""));
        ResponseEntity<String> response;
        try { response = call("POST", "/api/algorithm/train/start", JSON.toJSONString(forwarded)); }
        catch (RuntimeException ex) { markFailed(localId, ex.getMessage()); throw ex; }
        if (!response.getStatusCode().is2xxSuccessful())
        {
            markFailed(localId, response.getBody());
            return response;
        }
        Map<String, Object> task = object(response.getBody());
        String externalId = text(task.get("task_id"), null);
        jdbc.update("""
                UPDATE training_task SET external_task_id=?, status=?, metrics_json=?,
                       log_stream_key=?, update_time=CURRENT_TIMESTAMP
                 WHERE training_task_id=?
                """, externalId, text(task.get("status"), "QUEUED"), json(task.get("metrics")),
                "drilling:task:" + externalId, localId);
        return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(JSON.toJSONString(publicTask(localId, task)));
    }

    @GetMapping("/list")
    @RequiresPermissions("drilling:training:list")
    public Map<String, Object> list(@RequestParam(required = false, name = "field_id") Long fieldId) throws IOException, InterruptedException
    {
        if (fieldId != null) requireFieldRole(fieldId, "VIEWER", "ENGINEER", "OWNER");
        List<Map<String, Object>> rows = visibleRows(fieldId);
        List<Object> tasks = new ArrayList<>();
        for (Map<String, Object> row : rows)
        {
            Map<String, Object> task = sync(row);
            tasks.add(publicTask(((Number) row.get("training_task_id")).longValue(), task));
        }
        return Map.of("tasks", tasks);
    }

    @GetMapping("/{taskId}")
    @RequiresPermissions("drilling:training:list")
    public ResponseEntity<String> status(@PathVariable String taskId) throws IOException, InterruptedException
    {
        Map<String, Object> row = findTask(taskId);
        requireFieldRole(((Number) row.get("field_id")).longValue(), "VIEWER", "ENGINEER", "OWNER");
        Map<String, Object> task = sync(row);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(JSON.toJSONString(publicTask(((Number) row.get("training_task_id")).longValue(), task)));
    }

    @PostMapping("/{taskId}/stop")
    @RequiresPermissions("drilling:training:run")
    public ResponseEntity<String> stop(@PathVariable String taskId) throws IOException, InterruptedException
    {
        Map<String, Object> row = findTask(taskId);
        requireFieldRole(((Number) row.get("field_id")).longValue(), "OWNER", "ENGINEER");
        String externalId = text(row.get("external_task_id"), null);
        if (externalId == null) throw new IllegalArgumentException("training task has no external worker id");
        ResponseEntity<String> response = call("POST", "/api/algorithm/train/stop/" + externalId, null);
        if (response.getStatusCode().is2xxSuccessful()) sync(row);
        return response;
    }

    private Map<String, Object> sync(Map<String, Object> row) throws IOException, InterruptedException
    {
        String externalId = text(row.get("external_task_id"), null);
        if (externalId == null) return Map.of("task_id", row.get("training_task_id"), "status", row.get("status"),
                "device", row.get("device"), "field_id", row.get("field_id"), "metrics", List.of(), "logs", List.of());
        ResponseEntity<String> response = call("GET", "/api/algorithm/train/status/" + externalId, null);
        if (!response.getStatusCode().is2xxSuccessful()) return Map.of("task_id", externalId, "status", row.get("status"),
                "device", row.get("device"), "field_id", row.get("field_id"), "metrics", List.of(), "logs", List.of());
        Map<String, Object> task = object(response.getBody());
        String status = text(task.get("status"), text(row.get("status"), "QUEUED"));
        jdbc.update("""
                UPDATE training_task SET status=?, metrics_json=?, error_message=?,
                       finish_time=CASE WHEN ? IN ('SUCCEEDED','FAILED','STOPPED') THEN COALESCE(finish_time, CURRENT_TIMESTAMP) ELSE finish_time END,
                       update_time=CURRENT_TIMESTAMP
                 WHERE training_task_id=?
                """, status, json(task.get("metrics")), text(task.get("error"), null), status,
                ((Number) row.get("training_task_id")).longValue());
        return task;
    }

    private Map<String, Object> publicTask(Long localId, Map<String, Object> task)
    {
        Map<String, Object> result = new HashMap<>(task);
        result.putIfAbsent("local_task_id", localId);
        return result;
    }

    private List<Map<String, Object>> visibleRows(Long fieldId)
    {
        if(fieldId!=null) com.ruoyi.framework.security.DrillingDataScope.current().requireAccess(fieldId);
        return jdbc.queryForList("SELECT t.* FROM training_task t WHERE (? IS NULL OR t.field_id=?) AND ("+com.ruoyi.framework.security.DrillingDataScope.current().predicate("t.field_id")+") ORDER BY t.training_task_id DESC",fieldId,fieldId);
    }

    private Map<String, Object> findTask(String id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM training_task WHERE external_task_id=? OR CAST(training_task_id AS CHAR)=?", id, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("training task not found");
        return rows.get(0);
    }

    private Map<String, Object> catalogRow(String table, String idColumn, Long id, Long fieldId)
    {
        // Catalog rows are only runnable while their source field file is
        // still active.  Match the stored runtime path against the immutable
        // object key so stale/edited catalog metadata cannot redirect a worker
        // to a different file.
        String category = "training_dataset".equals(table) ? "DATASET" : "TEMPLATE";
        String templateFlag = "training_dataset".equals(table) ? "0" : "1";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.* FROM %s c
                  JOIN data_file f ON f.file_id=c.file_id AND f.field_id=c.field_id
                 WHERE c.%s=? AND c.field_id=? AND c.status IN ('READY','ACTIVE')
                   AND f.file_category=? AND f.is_template=? AND f.status='ACTIVE'
                   AND c.runtime_path=f.object_key
                """.formatted(table, idColumn), id, fieldId, category, templateFlag);
        if (rows.isEmpty()) throw new IllegalArgumentException("training catalog item is not accessible in this field");
        Map<String, Object> row = rows.get(0);
        if (row.get("runtime_path") == null || String.valueOf(row.get("runtime_path")).isBlank())
            throw new IllegalArgumentException("training catalog item has no runtime_path");
        return row;
    }

    private void requireModelVersion(Long fieldId, Long id)
    {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM model_version WHERE model_version_id=? AND (field_id=? OR field_id IS NULL) AND status='READY'", Integer.class, id, fieldId);
        if (count == null || count == 0) throw new IllegalArgumentException("model version is not accessible in this field");
    }

    private void markFailed(Long id, String message)
    {
        jdbc.update("UPDATE training_task SET status='FAILED', error_message=?, finish_time=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP WHERE training_task_id=?", message, id);
    }

    private ResponseEntity<String> call(String method, String path, String body) throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(algorithmTarget(path)))
                .timeout(Duration.ofMinutes(10)).header("Accept", MediaType.APPLICATION_JSON_VALUE);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE).method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return ResponseEntity.status(response.statusCode()).contentType(MediaType.APPLICATION_JSON).body(response.body());
    }

    private String algorithmTarget(String path)
    {
        String base = algorithmUrl == null ? "" : algorithmUrl.replaceAll("/$", "");
        String apiBase = base.endsWith("/api/algorithm") ? base : base + "/api/algorithm";
        String normalized = path.startsWith("/api/algorithm") ? path.substring("/api/algorithm".length()) : path;
        return apiBase + (normalized.startsWith("/") ? normalized : "/" + normalized);
    }

    private void requireFieldRole(Long fieldId, String... allowedRoles)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireRole(fieldId, allowedRoles);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(String body)
    {
        if (body == null || body.isBlank()) throw new IllegalArgumentException("JSON request body is required");
        try { Map<String, Object> result = JSON.parseObject(body, Map.class); if (result == null) throw new IllegalArgumentException("JSON body must be an object"); return result; }
        catch (RuntimeException ex) { throw new IllegalArgumentException("invalid JSON request body", ex); }
    }

    private static Long fieldId(Map<String, Object> body) { return optionalLong(body, "field_id", "fieldId"); }
    private static Long requiredLong(Map<String, Object> body, String... keys)
    {
        Long value = optionalLong(body, keys);
        if (value == null) throw new IllegalArgumentException(keys[0] + " is required");
        return value;
    }
    private static Long optionalLong(Map<String, Object> body, String... keys)
    {
        for (String key : keys) if (body.get(key) != null && !String.valueOf(body.get(key)).isBlank())
        {
            try { Object raw = body.get(key); return raw instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(raw)); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " must be an integer", ex); }
        }
        return null;
    }
    private static String text(Object value, String fallback) { return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim(); }
    private static String json(Object value) { return value == null ? "{}" : value instanceof String ? String.valueOf(value) : JSON.toJSONString(value); }
}
