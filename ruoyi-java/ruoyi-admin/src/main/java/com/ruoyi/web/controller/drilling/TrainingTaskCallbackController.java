package com.ruoyi.web.controller.drilling;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.PreparedStatement;
import java.sql.Statement;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal, authenticated-by-token callback endpoint for FastAPI training
 * events.  It deliberately has no Shiro user context: the worker is a service
 * principal and the event table provides the audit trail and idempotency.
 */
@RestController
@RequestMapping("/api/train/task")
public class TrainingTaskCallbackController
{
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final List<String> TERMINAL = List.of("SUCCEEDED", "FAILED", "STOPPED", "LOST");
    private static final List<String> STATUSES = List.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "STOPPED", "LOST");

    private final JdbcTemplate jdbc;

    @Value("${drilling.training.callback-token:}")
    private String callbackToken;

    public TrainingTaskCallbackController(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    @PostMapping("/callback")
    @Anonymous
    @Transactional
    public ResponseEntity<Map<String, Object>> callback(
            @RequestHeader(value = "X-Drilling-Callback-Token", required = false) String suppliedToken,
            @RequestBody Map<String, Object> payload)
    {
        if (callbackToken == null || callbackToken.isBlank() || suppliedToken == null
                || !constantTimeEquals(callbackToken, suppliedToken))
        {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("accepted", false, "error", "invalid callback token"));
        }
        String externalTaskId = text(payload.get("task_id"));
        String eventType = text(payload.get("type"));
        String idempotencyKey = text(payload.get("idempotency_key"));
        if (externalTaskId == null || eventType == null || idempotencyKey == null)
        {
            return ResponseEntity.badRequest().body(Map.of("accepted", false,
                    "error", "task_id, type and idempotency_key are required"));
        }
        List<Map<String, Object>> tasks = jdbc.queryForList(
                "SELECT training_task_id, field_id FROM training_task WHERE external_task_id=?",
                externalTaskId);
        if (tasks.isEmpty())
        {
            // A worker can publish RUNNING before the submit HTTP response has
            // been persisted.  A 404 makes the worker retry the event.
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("accepted", false, "error", "training task is not registered yet"));
        }
        Map<String, Object> task = tasks.get(0);
        Long fieldId = task.get("field_id") == null ? null : ((Number) task.get("field_id")).longValue();
        String eventJson = JSON.toJSONString(payload);
        int inserted = jdbc.update("""
                INSERT IGNORE INTO drilling_task_event
                    (field_id, task_type, task_id, event_type, event_json, idempotency_key)
                VALUES (?, 'TRAINING', ?, ?, ?, ?)
                """, fieldId, externalTaskId, eventType, eventJson, idempotencyKey);
        if (inserted == 0)
        {
            return ResponseEntity.ok(Map.of("accepted", true, "duplicate", true));
        }

        Long localId = ((Number) task.get("training_task_id")).longValue();
        persistEvent(localId, payload, eventType);
        return ResponseEntity.ok(Map.of("accepted", true, "duplicate", false));
    }

    private void persistEvent(Long localId, Map<String, Object> payload, String eventType)
    {
        // Model events use AVAILABLE for the artifact, not for the training
        // lifecycle. Preserve SUCCEEDED instead of replacing it with that
        // artifact state (or a status attached to a metric/log event).
        String status = "status".equalsIgnoreCase(eventType) ? text(payload.get("status")) : null;
        if (status != null)
        {
            status = status.toUpperCase();
            if (!STATUSES.contains(status)) status = "FAILED";
        }
        String error = text(payload.get("error"));
        String modelUri = null;
        String modelSha = null;
        Long modelSize = null;
        Object model = payload.get("model");
        if (model instanceof Map<?, ?> modelMap)
        {
            modelUri = text(modelMap.get("uri"));
            modelSha = text(modelMap.get("sha256"));
            Object size = modelMap.get("size_bytes");
            if (size != null) try { modelSize = size instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(size)); }
            catch (NumberFormatException ignored) { /* metadata remains nullable */ }
        }
        String metrics = metricsJson(localId, payload, eventType);
        jdbc.update("""
                UPDATE training_task
                   SET status=COALESCE(?, status), metrics_json=COALESCE(?, metrics_json),
                       error_message=COALESCE(?, error_message), model_artifact_uri=COALESCE(?, model_artifact_uri),
                       model_artifact_sha256=COALESCE(?, model_artifact_sha256),
                       model_artifact_size_bytes=COALESCE(?, model_artifact_size_bytes),
                       finish_time=CASE WHEN ? IN ('SUCCEEDED','FAILED','STOPPED','LOST')
                                        THEN COALESCE(finish_time, CURRENT_TIMESTAMP) ELSE finish_time END,
                       last_callback_at=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP
                 WHERE training_task_id=?
                """, status, metrics, error, modelUri, modelSha, modelSize, status, localId);

        // The worker publishes the terminal status before it archives the
        // output directory, so model metadata arrives as a separate event.
        // Register it here after the event has been made idempotent. The
        // binary remains in the algorithm service/MinIO; Java stores the
        // field-scoped metadata and the model_version foreign key.
        if ("model".equalsIgnoreCase(eventType) && model instanceof Map<?, ?> modelMap)
        {
            Map<String, Object> task = jdbc.queryForMap(
                    "SELECT field_id, create_by, metrics_json FROM training_task WHERE training_task_id=?", localId);
            Long fieldId = task.get("field_id") == null ? null : ((Number) task.get("field_id")).longValue();
            if (fieldId != null)
            {
                String effectiveMetrics = metrics != null ? metrics : text(task.get("metrics_json"));
                registerModelVersion(localId, fieldId, task.get("create_by"), modelMap, effectiveMetrics);
            }
        }
    }

    void registerModelVersion(Long trainingTaskId, Long fieldId, Object createBy,
                                      Map<?, ?> model, String metricsJson)
    {
        // The scheduler and a retried worker callback may finish the same task.
        // Serialize registration before reading its artifact/version metadata.
        jdbc.queryForList("SELECT training_task_id FROM training_task WHERE training_task_id=? FOR UPDATE", trainingTaskId);
        jdbc.queryForList("SELECT field_id FROM drilling_field WHERE field_id=? FOR UPDATE", fieldId);
        String uri = text(model.get("uri"));
        if (uri == null) throw new IllegalArgumentException("model callback is missing uri");
        if (uri.length() > 512) throw new IllegalArgumentException("model callback uri is too long");
        if (!validArtifactUri(uri)) throw new IllegalArgumentException("model callback uri is not a supported artifact reference");

        String suppliedName = text(model.get("name"));
        String modelName = modelName(suppliedName == null ? uri : suppliedName);
        Map<String,Object> training = jdbc.queryForMap("SELECT hyperparams_json FROM training_task WHERE training_task_id=?",trainingTaskId);
        Map<String,Object> params = IncrementalLearningService.object(training.get("hyperparams_json"));
        if ("INCREMENTAL".equals(params.get("training_mode")) && params.get("parent_model_version_id") != null) {
            modelName = jdbc.queryForObject("SELECT model_name FROM model_version WHERE model_version_id=? AND field_id=?",String.class,params.get("parent_model_version_id"),fieldId);
        }
        String originalName = suppliedName == null ? modelName + ".zip" : fileName(suppliedName);
        String sha256 = text(model.get("sha256"));
        Long sizeBytes = longValue(model.get("size_bytes"));
        if (sha256 != null && !SHA256.matcher(sha256).matches())
            throw new IllegalArgumentException("model callback sha256 must be a 64-character hexadecimal digest");
        if (sizeBytes != null && sizeBytes < 0)
            throw new IllegalArgumentException("model callback size_bytes must be non-negative");
        String contentType = text(model.get("content_type"));
        if (contentType == null) contentType = "application/zip";

        // A retried callback may reach this method after the event table was
        // restored from a backup. The object key is the stable artifact
        // identity, so reuse its data_file row rather than creating another
        // file/version pair.
        Long fileId = findFileId(uri, fieldId);
        if (fileId == null)
        {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            final String finalOriginalName = originalName;
            final String finalSha = sha256 == null ? "" : sha256;
            final long finalSize = sizeBytes == null ? 0L : sizeBytes;
            final String finalContentType = contentType;
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                    INSERT IGNORE INTO data_file
                        (field_id, file_category, business_type, original_name, object_key,
                         content_type, file_size, sha256, version_no, is_template, status, create_by)
                    VALUES (?, 'MODEL', 'TRAINING_MODEL', ?, ?, ?, ?, ?, 1, '0', 'ACTIVE', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, fieldId);
                statement.setString(2, finalOriginalName);
                statement.setString(3, uri);
                statement.setString(4, finalContentType);
                statement.setLong(5, finalSize);
                statement.setString(6, finalSha);
                statement.setString(7, createBy == null ? "training-worker" : String.valueOf(createBy));
                return statement;
            }, keyHolder);
            Number generated = keyHolder.getKey();
            fileId = generated == null ? findFileId(uri, fieldId) : generated.longValue();
        }
        if (fileId == null) throw new IllegalStateException("model metadata file was not created");

        // The same artifact can be announced more than once (for example
        // after a worker retry or a restored callback-event table). Reuse the
        // existing version keyed by its field, logical name and file before
        // allocating a new version number.
        List<Map<String, Object>> existingVersions = jdbc.queryForList("""
            SELECT model_version_id FROM model_version
             WHERE field_id=? AND model_name=? AND file_id=?
             ORDER BY model_version_id DESC LIMIT 1 FOR UPDATE
            """, fieldId, modelName, fileId);
        if (!existingVersions.isEmpty())
        {
            Long existingId = ((Number) existingVersions.get(0).get("model_version_id")).longValue();
            jdbc.update("UPDATE training_task SET model_version_id=?, update_time=CURRENT_TIMESTAMP WHERE training_task_id=?",
                    existingId, trainingTaskId);
            return;
        }

        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) + 1 FROM model_version WHERE field_id=? AND model_name=? FOR UPDATE",
                Integer.class, fieldId, modelName);
        int nextVersion = version == null ? 1 : version;
        Long modelVersionId = null;
        for (int attempt = 0; attempt < 3 && modelVersionId == null; attempt++)
        {
            jdbc.update("""
                INSERT IGNORE INTO model_version
                    (field_id, model_name, version_no, file_id, metrics_json, status, create_by)
                VALUES (?, ?, ?, ?, ?, 'READY', ?)
                """, fieldId, modelName, nextVersion, fileId, metricsJson,
                    createBy == null ? "training-worker" : String.valueOf(createBy));
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT model_version_id FROM model_version
                 WHERE field_id=? AND model_name=? AND version_no=? AND file_id=?
                """, fieldId, modelName, nextVersion, fileId);
            if (!rows.isEmpty()) modelVersionId = ((Number) rows.get(0).get("model_version_id")).longValue();
            else
            {
                Integer latest = jdbc.queryForObject(
                        "SELECT COALESCE(MAX(version_no), 0) + 1 FROM model_version WHERE field_id=? AND model_name=? FOR UPDATE",
                        Integer.class, fieldId, modelName);
                nextVersion = latest == null ? nextVersion + 1 : latest;
            }
        }
        if (modelVersionId == null) throw new DuplicateKeyException("unable to allocate model version");
        jdbc.update("UPDATE training_task SET model_version_id=?, update_time=CURRENT_TIMESTAMP WHERE training_task_id=?",
                modelVersionId, trainingTaskId);
    }

    private Long findFileId(String objectKey, Long fieldId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT file_id, field_id FROM data_file WHERE object_key=? FOR UPDATE", objectKey);
        if (rows.isEmpty()) return null;
        Object rowField = rows.get(0).get("field_id");
        if (rowField != null && !fieldId.equals(((Number) rowField).longValue()))
            throw new IllegalArgumentException("model artifact is already registered to another field");
        return ((Number) rows.get(0).get("file_id")).longValue();
    }

    private static String modelName(String value)
    {
        String name = fileName(value);
        if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
        if (name.isBlank()) name = "model";
        return name.length() > 128 ? name.substring(0, 128) : name;
    }

    private static String fileName(String value)
    {
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.isBlank() ? "model.zip" : name;
    }

    private static Long longValue(Object value)
    {
        if (value == null) return null;
        try { return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static boolean validArtifactUri(String value)
    {
        // Local archives are emitted as a single generated filename. Remote
        // archives use the explicit s3://bucket/object form; accepting an
        // arbitrary URL here would let a callback redirect the authorized
        // download proxy to an unrelated host.
        if (value.indexOf('\\') >= 0 || value.chars().anyMatch(Character::isWhitespace)
                || value.contains("..")) return false;
        if (!value.startsWith("s3://")) return value.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.zip");
        try
        {
            URI parsed = new URI(value);
            return "s3".equalsIgnoreCase(parsed.getScheme())
                    && parsed.getHost() != null && !parsed.getHost().isBlank()
                    && parsed.getPath() != null && parsed.getPath().length() > 1;
        }
        catch (URISyntaxException ignored)
        {
            return false;
        }
    }

    private String metricsJson(Long localId, Map<String, Object> payload, String eventType)
    {
        if (!"metric".equalsIgnoreCase(eventType) && payload.get("metrics") == null) return null;
        List<Object> values = new ArrayList<>();
        try
        {
            String existing = jdbc.query("SELECT metrics_json FROM training_task WHERE training_task_id=?",
                    rs -> rs.next() ? rs.getString(1) : null, localId);
            if (existing != null && !existing.isBlank())
            {
                Object parsed = JSON.parse(existing);
                if (parsed instanceof List<?> list) values.addAll(list);
            }
        }
        catch (RuntimeException ignored) { /* invalid legacy metrics are replaced below */ }
        Object metric = "metric".equalsIgnoreCase(eventType) ? payload : payload.get("metrics");
        if (metric instanceof List<?> list) values.addAll(list);
        else if (metric != null) values.add(metric);
        return JSON.toJSONString(values);
    }

    private static String text(Object value)
    {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean constantTimeEquals(String expected, String supplied)
    {
        byte[] left = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }
}
