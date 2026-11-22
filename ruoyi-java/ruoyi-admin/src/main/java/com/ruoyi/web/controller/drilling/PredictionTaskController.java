package com.ruoyi.web.controller.drilling;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson.JSON;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import com.ruoyi.common.utils.ShiroUtils;

/**
 * 使用井场启用的模型对实测序列执行姿态推理，并持久化真实结果与来源。
 * 未启用模型时保存 MODEL_NOT_CONFIGURED，不生成替代预测值。
 */
@RestController
@RequestMapping("/api/drilling/predictions")
@RequiresAuthentication
public class PredictionTaskController
{
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final JdbcTemplate jdbc;
    private final ModelRepositoryController models;
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.web.controller.drilling.centers.CenterTaskSnapshots centerSnapshots;
    @org.springframework.beans.factory.annotation.Autowired private TransactionTemplate transactions;

    public PredictionTaskController(JdbcTemplate jdbc, ModelRepositoryController models)
    {
        this.jdbc = jdbc;
        this.models = models;
    }

    @Value("${drilling.algorithm.url:http://localhost:8000}")
    private String algorithmUrl;

    @PostMapping
    @RequiresPermissions("drilling:prediction:run")
    public ResponseEntity<String> create(@RequestBody String body) throws IOException, InterruptedException
    {
        Map<String, Object> payload = object(body);
        Long fieldId = fieldId(payload);
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        Long wellId = requiredLong(payload, "well_id");
        Long surveyVersionId = requiredLong(payload, "survey_version_id");
        Long inputFileId = longValue(payload.get("input_file_id"));
        List<Map<String,Object>> sources = jdbc.queryForList("""
                SELECT b.wellbore_id,t.quality_status
                  FROM drilling_trajectory_version t
                  JOIN drilling_wellbore b ON b.wellbore_id=t.wellbore_id
                  JOIN drilling_field_wellbore pb ON pb.wellbore_id=b.wellbore_id
                  JOIN drilling_well w ON w.well_id=b.well_id
                 WHERE pb.field_id=? AND b.well_id=? AND t.survey_version_id=?
                   AND t.trajectory_kind='ACTUAL' AND w.status<>'ARCHIVED'
                   AND NOT EXISTS(SELECT 1 FROM drilling_engineering_node en WHERE en.field_id=w.field_id
                     AND en.well_id=w.well_id AND en.node_type='WELL' AND en.entity_kind='PLANNED_RELIEF'
                     AND en.status='ACTIVE')
                """, fieldId, wellId, surveyVersionId);
        if (sources.size()!=1)
            throw new IllegalArgumentException("请选择当前井场拥有或引用的井眼实钻版本");
        Map<String,Object> source=sources.get(0);
        if (payload.get("wellbore_id")!=null && !String.valueOf(payload.get("wellbore_id")).equals(String.valueOf(source.get("wellbore_id"))))
            throw new IllegalArgumentException("井眼与实钻版本不一致");
        payload.put("wellbore_id",source.get("wellbore_id"));
        Integer fileCount = inputFileId == null ? 1 : jdbc.queryForObject("""
                SELECT COUNT(*) FROM data_file
                 WHERE file_id=? AND field_id=? AND status='ACTIVE'
                   AND file_category IN ('SURVEY', 'DATASET')
                """, Integer.class, inputFileId, fieldId);
        if (fileCount == null || fileCount == 0)
            throw new IllegalArgumentException("input_file_id must reference an active SURVEY or DATASET file in this field");
        Map<String,Object> configRow=jdbc.queryForMap("SELECT * FROM drilling_field_config WHERE field_id=? AND status='0' AND effective_to IS NULL ORDER BY config_version DESC LIMIT 1",fieldId);
        Map<String,Object> prediction=IncrementalLearningService.object(DrillingAlgorithmDefaults.merged(configRow.get("algorithm_defaults_json")).get("prediction"));
        double step=number(payload.get("prediction_step"),number(prediction.get("predictionStep"),9));
        if(!Double.isFinite(step)||step<=0||step>1000)throw new IllegalArgumentException("预测步长必须在 0–1000 m 范围内");
        payload.put("prediction_step",step);
        payload.put("field_id", fieldId);
        if (prediction.get("modelVersionId") != null)
            payload.put("model_version",String.valueOf(prediction.get("modelVersionId")));
        long execution = centerSnapshots.startWithConfig("PREDICTION",fieldId,payload,configRow);
        try {
            ResponseEntity<String> response = computePrediction(fieldId,wellId,surveyVersionId,payload,prediction,step);
            if (!response.getStatusCode().is2xxSuccessful()) {
                centerSnapshots.fail(execution,"预测服务返回 HTTP " + response.getStatusCode().value());
                return response;
            }
            transactions.executeWithoutResult(tx -> savePrediction(execution,fieldId,wellId,surveyVersionId,inputFileId,payload,response));
            return response;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            centerSnapshots.fail(execution,"预测计算或结果保存失败（" + ex.getClass().getSimpleName() + "）");
            throw ex;
        }
    }

    private ResponseEntity<String> computePrediction(Long fieldId, Long wellId, Long surveyVersionId,
            Map<String,Object> payload, Map<String,Object> prediction, double step) throws IOException, InterruptedException {
        ResponseEntity<String> response;
        if(prediction.get("modelVersionId") != null && !String.valueOf(prediction.getOrDefault("templateRuntimePath","")).isBlank()) {
            List<Map<String,Object>> rawStations=jdbc.queryForList("SELECT md,inclination,azimuth,north,east,tvd FROM drilling_survey_station WHERE survey_version_id=? ORDER BY md",surveyVersionId);
            // Normalize JDBC column labels before passing the contract to a
            // user supplied adapter. Drivers may expose labels in upper case.
            List<Map<String,Object>> stations=rawStations.stream().map(PredictionTaskController::normalizeStation).toList();
            if(stations.isEmpty())throw new IllegalArgumentException("测斜版本没有有效实测数据");
            Map<String,Object> inference=new java.util.LinkedHashMap<>();
            inference.put("script",prediction.get("templateRuntimePath"));
            inference.put("device",payload.getOrDefault("device",prediction.get("device")));
            inference.put("timeout_seconds",prediction.get("timeoutSeconds"));
            // Fastjson encodes repeated object identities as $ref. The model
            // contract is ordinary JSON, so the current pose must be a copy.
            inference.put("input",Map.of("contract","drilling.pose-extrapolation.v1","prediction_step",step,"prediction_steps",List.of(9,28,step),"stations",stations,"current_pose",new java.util.LinkedHashMap<>(stations.get(stations.size()-1))));
            long modelId=Long.parseLong(String.valueOf(prediction.get("modelVersionId")));
            ResponseEntity<String> inferred=models.inferTest(fieldId,modelId,inference);
            if(!inferred.getStatusCode().is2xxSuccessful()) return inferred;
            Map<String,Object> modelResult=object(inferred.getBody());
            Map<String,Object> task=new java.util.LinkedHashMap<>();
            task.put("task_id",java.util.UUID.randomUUID().toString()); task.put("status","SUCCEEDED");
            task.put("field_id",fieldId); task.put("well_id",wellId); task.put("survey_version_id",surveyVersionId);
            task.put("prediction_step",step); task.put("model_version",String.valueOf(modelId));
            task.put("result",modelResult.get("result")); task.put("created_at",java.time.Instant.now().toString());
            response=ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(JSON.toJSONString(task));
        } else {
            Map<String,Object> task=new java.util.LinkedHashMap<>();
            task.put("task_id",java.util.UUID.randomUUID().toString()); task.put("status","MODEL_NOT_CONFIGURED");
            task.put("result",Map.of("code","MODEL_NOT_CONFIGURED","message","请在模型仓库启用模型并选择推理适配器；当前未生成预测结果"));
            task.put("created_at",java.time.Instant.now().toString());
            response=ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(JSON.toJSONString(task));
        }
        return response;
    }

    private void savePrediction(long execution, Long fieldId, Long wellId, Long surveyVersionId,
            Long inputFileId, Map<String,Object> payload, ResponseEntity<String> response) {
        Map<String, Object> task = object(response.getBody());
        String externalId = String.valueOf(task.get("task_id"));
        jdbc.update("""
                INSERT INTO prediction_task
                    (task_id, field_id, well_id, input_file_id, survey_version_id,
                     prediction_step, model_version, device, status, input_summary_json,
                     result_json, create_by, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE status=VALUES(status), result_json=VALUES(result_json),
                    update_time=CURRENT_TIMESTAMP
                """, externalId, fieldId, wellId,
                inputFileId, surveyVersionId,
                number(payload.get("prediction_step"), 9.0), text(payload.get("model_version")),
                textOr(payload.get("device"), "auto"), textOr(task.get("status"), "MODEL_NOT_CONFIGURED"),
                json(payload.get("input_summary")), JSON.toJSONString(task), ShiroUtils.getLoginName());
        Long centerSourceId=jdbc.queryForObject("SELECT prediction_id FROM prediction_task WHERE task_id=?",Long.class,externalId);
        centerSnapshots.complete(execution,"PREDICTION",centerSourceId);
    }

    @GetMapping
    @RequiresPermissions("drilling:prediction:list")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long fieldId)
    {
        if (fieldId != null) requireFieldAccess(fieldId);
        return jdbc.queryForList("SELECT t.* FROM prediction_task t WHERE (? IS NULL OR t.field_id=?) AND ("+com.ruoyi.framework.security.DrillingDataScope.current().predicate("t.field_id")+") ORDER BY t.prediction_id DESC",fieldId,fieldId);
    }

    @GetMapping("/{taskId}")
    @RequiresPermissions("drilling:prediction:list")
    public ResponseEntity<String> status(@PathVariable String taskId) throws IOException, InterruptedException
    {
        Map<String, Object> row = find(taskId);
        requireFieldAccess(((Number) row.get("field_id")).longValue());
        if (row.get("result_json") != null) return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(String.valueOf(row.get("result_json")));
        ResponseEntity<String> response = call("GET", "/prediction/status/" + taskId, null);
        if (response.getStatusCode().is2xxSuccessful()) persistStatus(taskId, response.getBody());
        else return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(JSON.toJSONString(row));
        return response;
    }

    @PostMapping("/{taskId}/cancel")
    @RequiresPermissions("drilling:prediction:run")
    public ResponseEntity<String> cancel(@PathVariable String taskId) throws IOException, InterruptedException
    {
        Map<String, Object> row = find(taskId);
        requireFieldRole(((Number) row.get("field_id")).longValue(), "OWNER", "ENGINEER");
        if (List.of("SUCCEEDED", "FAILED", "STOPPED", "MODEL_NOT_CONFIGURED").contains(String.valueOf(row.get("status"))))
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(String.valueOf(row.get("result_json")));
        ResponseEntity<String> response = call("POST", "/prediction/cancel/" + taskId, null);
        if (response.getStatusCode().is2xxSuccessful()) persistStatus(taskId, response.getBody());
        return response;
    }

    @GetMapping(value = "/{taskId}/export", produces = "text/csv")
    @RequiresPermissions("drilling:prediction:list")
    public ResponseEntity<byte[]> exportTrajectory(@PathVariable String taskId, jakarta.servlet.http.HttpServletRequest request)
    {
        Map<String,Object> row = find(taskId);
        long fieldId = ((Number)row.get("field_id")).longValue();
        requireFieldAccess(fieldId);
        request.setAttribute("audit.fieldId",fieldId);
        request.setAttribute("audit.wellId",row.get("well_id"));
        Object result = row.get("result_json")==null ? null : object(String.valueOf(row.get("result_json"))).get("result");
        if (!(result instanceof Map<?,?> output)) throw new IllegalArgumentException("该任务没有预测结果");
        List<?> stations = output.get("predicted_stations") instanceof List<?> list ? list
                : output.get("predicted_pose") instanceof Map<?,?> pose ? List.of(pose) : List.of();
        if (stations.isEmpty()) throw new IllegalArgumentException("该任务没有预测轨迹");
        List<String> keys = List.of("md","inclination","azimuth","north","east","tvd");
        StringBuilder csv = new StringBuilder("\ufeff").append(String.join(",", keys)).append('\n');
        for (Object station : stations) {
            if (!(station instanceof Map<?,?> point)) throw new IllegalArgumentException("预测站点格式无效");
            var cells = new java.util.StringJoiner(",");
            for (String key : keys) {
                Object value = point.get(key);
                cells.add(value == null ? "" : new java.math.BigDecimal(value.toString()).toPlainString());
            }
            csv.append(cells).append('\n');
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=prediction-trajectory.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    @RequiresPermissions("drilling:prediction:list")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long fieldId)
    {
        List<Map<String, Object>> rows = list(fieldId);
        StringBuilder csv = new StringBuilder("task_id,field_id,well_id,prediction_step,device,status,create_time\n");
        for (Map<String, Object> row : rows)
        {
            csv.append(csv(row.get("task_id"))).append(',').append(csv(row.get("field_id"))).append(',')
               .append(csv(row.get("well_id"))).append(',').append(csv(row.get("prediction_step"))).append(',')
               .append(csv(row.get("device"))).append(',').append(csv(row.get("status"))).append(',')
               .append(csv(row.get("create_time"))).append('\n');
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=prediction-tasks.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex)
    {
        return ResponseEntity.badRequest().body(Map.of("code", HttpStatus.BAD_REQUEST.value(),
                "message", textOr(ex.getMessage(), "invalid request")));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> statusError(ResponseStatusException ex)
    {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("code", ex.getStatusCode().value(),
                "message", textOr(ex.getReason(), "request failed")));
    }

    private ResponseEntity<String> call(String method, String path, String body) throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(algorithmTarget(path)))
                .timeout(Duration.ofMinutes(10)).header("Accept", MediaType.APPLICATION_JSON_VALUE);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return ResponseEntity.status(response.statusCode()).contentType(MediaType.APPLICATION_JSON).body(response.body());
    }

    private String algorithmTarget(String path)
    {
        String base = algorithmUrl == null ? "" : algorithmUrl.replaceAll("/$", "");
        String apiBase = base.endsWith("/api/algorithm") ? base : base + "/api/algorithm";
        return apiBase + (path.startsWith("/") ? path : "/" + path);
    }

    private void persistStatus(String taskId, String body)
    {
        Map<String, Object> value = object(body);
        jdbc.update("UPDATE prediction_task SET status=?, result_json=?, update_time=CURRENT_TIMESTAMP WHERE task_id=?",
                textOr(value.get("status"), "MODEL_NOT_CONFIGURED"), JSON.toJSONString(value), taskId);
    }

    private Map<String, Object> find(String taskId)
    {
        try
        {
            return jdbc.queryForMap("SELECT * FROM prediction_task WHERE task_id=?", taskId);
        }
        catch (EmptyResultDataAccessException ex)
        {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "prediction task not found", ex);
        }
    }

    private void requireFieldAccess(Long fieldId)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireAccess(fieldId);
    }

    private void requireFieldRole(Long fieldId, String... roles)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireRole(fieldId, roles);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(String body)
    {
        if (body == null || body.isBlank()) throw new IllegalArgumentException("JSON request body is required");
        try { return JSON.parseObject(body, Map.class); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("invalid JSON request body", ex); }
    }

    private static Long fieldId(Map<String, Object> payload) { return longValue(payload.get("field_id")) != null ? longValue(payload.get("field_id")) : longValue(payload.get("fieldId")); }
    private static Long requiredLong(Map<String, Object> payload, String key) { Long value = longValue(payload.get(key)); if (value == null || value <= 0) throw new IllegalArgumentException(key + " is required and must be positive"); return value; }
    private static Long longValue(Object value) { if (value == null || String.valueOf(value).isBlank()) return null; try { return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ex) { throw new IllegalArgumentException("numeric value is invalid"); } }
    private static double number(Object value, double fallback) { if (value == null) return fallback; try { return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value)); } catch (NumberFormatException ex) { throw new IllegalArgumentException("numeric value is invalid"); } }
    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static String textOr(Object value, String fallback) { String valueText = text(value); return valueText == null || valueText.isBlank() ? fallback : valueText; }
    private static String json(Object value) { return value == null ? null : value instanceof String ? String.valueOf(value) : JSON.toJSONString(value); }
    private static String csv(Object value) { String text = value == null ? "" : String.valueOf(value); return "\"" + text.replace("\"", "\"\"") + "\""; }
    private static Map<String,Object> normalizeStation(Map<String,Object> row) {
        Map<String,Object> normalized=new java.util.LinkedHashMap<>();
        for(String key: List.of("md","inclination","azimuth","north","east","tvd")) {
            Object value=row.get(key);
            if(value==null) value=row.get(key.toUpperCase(java.util.Locale.ROOT));
            if(value==null) throw new IllegalArgumentException("测斜站点缺少 "+key);
            normalized.put(key, value instanceof Number ? ((Number)value).doubleValue() : Double.parseDouble(String.valueOf(value)));
        }
        return normalized;
    }
}
