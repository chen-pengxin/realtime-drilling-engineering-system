package com.ruoyi.web.controller.drilling;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.Comparator;
import java.util.Set;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson.JSON;
import com.ruoyi.common.utils.ShiroUtils;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Java 业务层的算法服务网关。
 *
 * Java 负责认证、井场数据权限和审计；轨迹计算与训练进程始终留在 FastAPI。
 * Nginx 可以直接转发 /api/algorithm，在需要统一鉴权的部署中则使用此网关。
 */
@RestController
@RequestMapping("/api/algorithm")
@RequiresAuthentication
public class AlgorithmGatewayController
{
    private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private TrajectoryVersionSupport trajectoryVersions;
    private final TransactionTemplate transactions;
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.web.controller.drilling.centers.CenterTaskSnapshots centerSnapshots;
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.web.controller.drilling.centers.BusinessEventService businessEvents;

    public AlgorithmGatewayController(JdbcTemplate jdbc, PlatformTransactionManager transactionManager)
    {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Value("${drilling.algorithm.url:http://localhost:8000}")
    private String algorithmUrl;

    @org.springframework.beans.factory.annotation.Autowired private NativeEngineSupport nativeEngine;
    @org.springframework.beans.factory.annotation.Autowired private TrajectoryInputSupport trajectoryInputs;

    @GetMapping("/**")
    public ResponseEntity<byte[]> get(HttpServletRequest request, @RequestHeader(value = "Authorization", required = false) String authorization)
            throws IOException, InterruptedException
    {
        return forward(request, "GET", null, authorization);
    }

    @GetMapping("/anti-collision/tasks")
    @RequiresAuthentication
    public List<Map<String, Object>> collisionTasks(@RequestParam Long fieldId)
    {
        ShiroUtils.getSubject().checkPermission("drilling:field:view");
        requireFieldAccess(fieldId);
        return jdbc.queryForList("""
                SELECT task_id, field_id, reference_well_id, reference_survey_version_id,
                       evaluation_mode, scan_step, sf_k, threshold_json, error_model, status,
                       result_summary_json, create_by, create_time, finish_time
                  FROM anti_collision_task WHERE field_id=? ORDER BY task_id DESC
                """, fieldId);
    }

    @GetMapping("/anti-collision/alarms")
    public List<Map<String, Object>> collisionAlarms(@RequestParam Long fieldId,
            @RequestParam(required = false) String status)
    {
        ShiroUtils.getSubject().checkPermission("drilling:alarm:list");
        requireFieldAccess(fieldId);
        String normalizedStatus = status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase();
        if (!List.of("ALL", "ACTIVE", "OPEN", "ACKNOWLEDGED", "RESOLVED").contains(normalizedStatus))
            throw new IllegalArgumentException("status must be ALL, ACTIVE, OPEN, ACKNOWLEDGED or RESOLVED");
        String statusClause = switch (normalizedStatus)
        {
            case "ACTIVE" -> " AND a.status IN ('OPEN', 'ACKNOWLEDGED')";
            case "ALL" -> "";
            default -> " AND a.status=?";
        };
        String sql = """
                SELECT a.alarm_id, a.field_id, a.task_id, a.result_id, a.well_id,
                       a.alarm_code, a.previous_level, a.current_level, a.status, a.message,
                       a.acknowledged_by, a.acknowledged_at, a.resolved_by, a.resolved_at,
                       a.rule_snapshot_json, a.create_by, a.create_time,
                       ref.well_code AS reference_well_code, neighbor.well_code AS neighbor_well_code,
                       r.min_distance, r.sf, r.reference_md, r.neighbor_md
                  FROM drilling_alarm a
                  LEFT JOIN anti_collision_task_well n
                    ON n.task_id=a.task_id AND n.role='NEIGHBOR'
                  LEFT JOIN drilling_well ref ON ref.well_id=a.well_id
                  LEFT JOIN drilling_well neighbor ON neighbor.well_id=n.well_id
                  LEFT JOIN anti_collision_result r ON r.result_id=a.result_id
                 WHERE a.field_id=? AND a.alarm_code IN ('ANTI_COLLISION_RISK', 'ANTI_COLLISION_RED')
                """ + statusClause + " ORDER BY a.create_time DESC, a.alarm_id DESC LIMIT 500";
        return "ALL".equals(normalizedStatus) || "ACTIVE".equals(normalizedStatus)
                ? jdbc.queryForList(sql, fieldId) : jdbc.queryForList(sql, fieldId, normalizedStatus);
    }

    @PostMapping("/anti-collision/alarms/{alarmId}/acknowledge")
    @Transactional
    public Map<String, Object> acknowledgeCollisionAlarm(@PathVariable Long alarmId)
    {
        ShiroUtils.getSubject().checkPermission("drilling:alarm:ack");
        Map<String, Object> alarm = lockCollisionAlarm(alarmId);
        Long fieldId = ((Number) alarm.get("field_id")).longValue();
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        String status = String.valueOf(alarm.get("status"));
        if ("OPEN".equals(status))
        {
            jdbc.update("""
                    UPDATE drilling_alarm SET status='ACKNOWLEDGED', acknowledged_by=?,
                           acknowledged_at=CURRENT_TIMESTAMP WHERE alarm_id=? AND status='OPEN'
                    """, ShiroUtils.getLoginName(), alarmId);
            writeAlarmAudit(fieldId, alarmId, "ACKNOWLEDGE", "OPEN", "ACKNOWLEDGED");
            status = "ACKNOWLEDGED";
        }
        else if (!"ACKNOWLEDGED".equals(status))
            throw new IllegalArgumentException("only an open collision alarm can be acknowledged");
        return Map.of("alarm_id", alarmId, "status", status);
    }

    @PostMapping("/anti-collision/alarms/{alarmId}/resolve")
    @Transactional
    public Map<String, Object> resolveCollisionAlarm(@PathVariable Long alarmId)
    {
        ShiroUtils.getSubject().checkPermission("drilling:alarm:resolve");
        Map<String, Object> alarm = lockCollisionAlarm(alarmId);
        Long fieldId = ((Number) alarm.get("field_id")).longValue();
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        String status = String.valueOf(alarm.get("status"));
        if ("OPEN".equals(status) || "ACKNOWLEDGED".equals(status))
        {
            jdbc.update("""
                    UPDATE drilling_alarm SET status='RESOLVED', resolved_by=?,
                           resolved_at=CURRENT_TIMESTAMP WHERE alarm_id=? AND status IN ('OPEN', 'ACKNOWLEDGED')
                    """, ShiroUtils.getLoginName(), alarmId);
            writeAlarmAudit(fieldId, alarmId, "RESOLVE", status, "RESOLVED");
            status = "RESOLVED";
        }
        return Map.of("alarm_id", alarmId, "status", status);
    }

    private Map<String, Object> lockCollisionAlarm(Long alarmId)
    {
        if (alarmId == null || alarmId <= 0) throw new IllegalArgumentException("alarm_id must be positive");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT alarm_id, field_id, status FROM drilling_alarm WHERE alarm_id=? FOR UPDATE", alarmId);
        if (rows.isEmpty()) throw new IllegalArgumentException("collision alarm was not found");
        return rows.get(0);
    }

    private void writeAlarmAudit(Long fieldId, Long alarmId, String action, String from, String to)
    {
        jdbc.update("""
                INSERT INTO drilling_audit_log
                    (field_id, user_id, action, resource_type, resource_id, detail_json)
                VALUES (?, ?, ?, 'DRILLING_ALARM', ?, ?)
                """, fieldId, ShiroUtils.getUserId(), action, String.valueOf(alarmId),
                JSON.toJSONString(Map.of("from", from, "to", to)));
    }

    @GetMapping("/trajectory-plan/tasks")
    @RequiresAuthentication
    public List<Map<String, Object>> planningTasks(@RequestParam Long fieldId,
            @RequestParam(defaultValue="1") int pageNum,
            @RequestParam(defaultValue="20") int pageSize)
    {
        ShiroUtils.getSubject().checkPermission("drilling:field:view");
        requireFieldAccess(fieldId);
        if(pageNum<1 || pageNum>1000000 || pageSize<1 || pageSize>100)
            throw new IllegalArgumentException("任务页码必须为正数，每页数量须为1～100");
        // Sort only compact IDs. MySQL filesort over whole trajectory JSON
        // can exhaust sort_buffer even when the field has just a few plans.
        var ids=jdbc.queryForList("SELECT plan_id FROM trajectory_plan WHERE field_id=? ORDER BY plan_id DESC LIMIT ? OFFSET ?",
                Long.class,fieldId,pageSize,(long)(pageNum-1)*pageSize);
        if(ids.isEmpty())return List.of();
        var args=new ArrayList<Object>();args.add(fieldId);args.addAll(ids);
        var rows=jdbc.queryForList("""
                SELECT plan_id, field_id, well_id, current_survey_version_id, target_id,
                       solver, constraint_json, objective_value, terminal_error,
                       status, result_json, create_by, create_time, finish_time
                  FROM trajectory_plan WHERE field_id=? AND plan_id IN (
                """+String.join(",",java.util.Collections.nCopies(ids.size(),"?"))+")", args.toArray());
        rows.sort(Comparator.comparingLong((Map<String,Object> row)->((Number)row.get("plan_id")).longValue()).reversed());
        return rows;
    }

    /**
     * Persist accepted live stations as a new immutable survey version. Raw
     * events are first recorded as PENDING so a FastAPI restart or a failed
     * calculation can be recovered by replaying the same source stream.
     */
    @PostMapping("/realtime/ingest")
    public ResponseEntity<byte[]> ingestRealtime(HttpServletRequest request,
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = "Authorization", required = false) String authorization)
            throws IOException, InterruptedException
    {
        Map<String, Object> payload = parseJsonBody(body);
        authorizePath("/realtime/ingest", "POST", body, null);
        return ingestRealtimePayload(payload, outbound -> forward(request, "POST", outbound, authorization), ShiroUtils.getLoginName());
    }

    @FunctionalInterface
    private interface RealtimeSender { ResponseEntity<byte[]> send(byte[] body) throws IOException, InterruptedException; }

    /** Internal connector worker entry; callers enforce connector/fieldId authorization first. */
    public Map<String,Object> ingestConnectorBatch(Map<String,Object> payload, String actor) throws IOException, InterruptedException {
        ResponseEntity<byte[]> response=ingestRealtimePayload(payload, outbound -> {
            HttpRequest call=HttpRequest.newBuilder(URI.create(algorithmBaseUrl()+"/realtime/ingest"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(outbound)).build();
            HttpResponse<byte[]> answer=httpClient.send(call,HttpResponse.BodyHandlers.ofByteArray());
            return ResponseEntity.status(answer.statusCode()).body(answer.body());
        }, actor);
        if(!response.getStatusCode().is2xxSuccessful())throw new IOException("realtime normalization failed");
        return parseJsonBody(response.getBody());
    }

    private ResponseEntity<byte[]> ingestRealtimePayload(Map<String,Object> payload, RealtimeSender sender, String actor)
        throws IOException, InterruptedException {
        Long fieldId = fieldId(payload);
        Long wellId = requiredLong(payload, "well_id");
        Long requestedBore=optionalLong(payload.get("wellbore_id"));
        if(requestedBore!=null&&requestedBore<=0)requestedBore=null;
        final Long boreId;
        if(requestedBore==null){
            List<Long> available=jdbc.queryForList("SELECT b.wellbore_id FROM drilling_field_wellbore x JOIN drilling_wellbore b ON b.wellbore_id=x.wellbore_id JOIN drilling_trajectory_head h ON h.wellbore_id=b.wellbore_id AND h.trajectory_kind='ACTUAL' WHERE x.field_id=? AND b.well_id=?",Long.class,fieldId,wellId);
            if(available.size()!=1)throw new IllegalArgumentException("实时接入必须明确已有实钻基线的 wellbore_id；当前井场可接入井眼数为 "+available.size());
            boreId=available.get(0);
        }else boreId=requestedBore;
        if(boreId!=null) {
            Integer bound=jdbc.queryForObject("SELECT COUNT(*) FROM drilling_field_wellbore x JOIN drilling_wellbore b ON b.wellbore_id=x.wellbore_id JOIN drilling_trajectory_head h ON h.wellbore_id=b.wellbore_id AND h.trajectory_kind='ACTUAL' WHERE x.field_id=? AND b.well_id=? AND b.wellbore_id=?",Integer.class,fieldId,wellId,boreId);
            if(bound==null||bound!=1)throw new IllegalArgumentException("实时接入必须绑定当前井场下已有实钻基线的井眼");
        }
        String source = textOr(payload.get("source"), "").trim();
        if (source.isEmpty()) throw new IllegalArgumentException("source is required");
        if(boreId!=null)source += ":wellbore:"+boreId;
        if (source.length() > 255) throw new IllegalArgumentException("source must be at most 255 characters");
        final String streamSource=source;
        int repeatedCount = persistRealtimeInputs(payload, fieldId, wellId, source);

        List<Map<String, Object>> pendingEvents = jdbc.queryForList("""
                SELECT raw_event_json FROM drilling_realtime_ingest_event
                 WHERE field_id=? AND well_id=? AND source_name=? AND status='PENDING'
                 ORDER BY event_id
                """, fieldId, wellId, source);
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> row : pendingEvents)
            events.add(JSON.parseObject(jsonText(row.get("raw_event_json")), Map.class));

        Map<String, Object> outbound = new LinkedHashMap<>(payload);
        outbound.remove("wellbore_id");
        outbound.put("source",source);
        outbound.put("events", events);
        // The inbox is the durable source of truth.  Ask FastAPI to replay
        // this complete pending batch with a fresh ordering window so a
        // previous request that buffered only part of the batch cannot make
        // a retry look like a duplicate forever.
        outbound.put("replay", true);
        outbound.put("flush", true);
        byte[] outboundBody = JSON.toJSONBytes(outbound);
        ResponseEntity<byte[]> response = sender.send(outboundBody);
        if (!response.getStatusCode().is2xxSuccessful()) return response;

        Map<String, Object> result = parseJsonBody(response.getBody());
        markRealtimeRejectedEvents(result, fieldId, wellId, source, events);
        List<Map<String, Object>> accepted = realtimeEvents(result);
        markRealtimeEventsReady(fieldId, wellId, source, accepted);
        accepted.addAll(loadRealtimeReadyEvents(fieldId, wellId, source));
        List<Map<String, Object>> eventsToCommit = uniqueRealtimeEvents(accepted, source, wellId);
        Long committedVersion = eventsToCommit.isEmpty() ? null : transactions.execute(status ->
                persistRealtimeSurvey(fieldId, wellId, boreId, streamSource, eventsToCommit, actor));
        result.put("duplicate_events", number(result.get("duplicates"), 0.0) + repeatedCount);
        result.put("persisted_survey_version_id", committedVersion);
        result.put("persistence_status", accepted.isEmpty() ? "BUFFERED" :
                committedVersion == null ? "NO_NEW_STATIONS" : "COMMITTED");
        byte[] resultBytes=JSON.toJSONBytes(result);
        HttpHeaders resultHeaders=new HttpHeaders();resultHeaders.putAll(response.getHeaders());resultHeaders.setContentLength(resultBytes.length);
        return ResponseEntity.status(response.getStatusCode()).headers(resultHeaders).body(resultBytes);
    }

    @PostMapping("/**")
    public ResponseEntity<byte[]> post(HttpServletRequest request, @RequestBody(required = false) byte[] body,
                                       @RequestHeader(value = "Authorization", required = false) String authorization)
            throws IOException, InterruptedException
    {
        String path = request.getRequestURI().substring("/api/algorithm".length());
        if ("/anti-collision/scan".equals(path) || "/anti-collision/network-scan".equals(path)
                || "/trajectory-plan/solve".equals(path))
        {
            Map<String, Object> payload = parseJsonBody(body);
            if ("/anti-collision/scan".equals(path)) validateCollisionBinding(payload);
            else if ("/anti-collision/network-scan".equals(path)) validateCollisionNetworkBinding(payload);
            else validatePlanningBinding(payload);

            // Persist exactly the fieldId-controlled settings sent to the
            // algorithm service, alongside its returned result.
            byte[] effectiveBody = applyFieldAlgorithmConfiguration(path, "POST", body);
            @SuppressWarnings("unchecked") Map<String, Object> effectivePayload = JSON.parseObject(effectiveBody, Map.class);
            authorizePath(path, "POST", effectiveBody, null);
            String centerType = path.startsWith("/anti-collision") ? "COLLISION" : "REPLANNING";
            long executionId = centerSnapshots.start(centerType, effectivePayload);
            effectivePayload.put("_center_execution_id", executionId);
            ResponseEntity<byte[]> response;
            try { response = forward(request, "POST", effectiveBody, authorization, request.getContentType(), false); }
            catch (IOException | InterruptedException | RuntimeException ex) { centerSnapshots.fail(executionId, "算法调用失败: " + ex.getClass().getSimpleName()); throw ex; }
            if (!response.getStatusCode().is2xxSuccessful()) centerSnapshots.fail(executionId, "算法服务返回 HTTP " + response.getStatusCode().value());
            if (response.getStatusCode().is2xxSuccessful())
            {
                Map<String, Object> result = parseJsonBody(response.getBody());
                // Network/CPU waits must not hold a database transaction or
                // connection. Only the short result commit is transactional.
                try {
                    transactions.executeWithoutResult(status -> {
                        if ("/anti-collision/scan".equals(path)) persistCollision(effectivePayload, result);
                        else if ("/anti-collision/network-scan".equals(path)) persistCollisionNetwork(effectivePayload, result);
                        else persistPlan(effectivePayload, result);
                    });
                } catch (RuntimeException ex) {
                    centerSnapshots.fail(executionId, "结果保存失败，输入快照已保留");
                    throw ex;
                }
                if ("/anti-collision/network-scan".equals(path))
                {
                    byte[] resultBody = JSON.toJSONBytes(result);
                    HttpHeaders resultHeaders = new HttpHeaders();
                    resultHeaders.putAll(response.getHeaders());
                    // Persistence and incremental merging may change the JSON bytes.
                    // The original upstream length would leave clients waiting for missing bytes.
                    resultHeaders.setContentLength(resultBody.length);
                    return new ResponseEntity<>(resultBody, resultHeaders, response.getStatusCode());
                }
            }
            return response;
        }
        return forward(request, "POST", body, authorization);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex)
    {
        return ResponseEntity.badRequest().body(Map.of("code", HttpStatus.BAD_REQUEST.value(),
                "message", ex.getMessage() == null ? "invalid request" : ex.getMessage()));
    }

    /**
     * Multipart requests are parsed by Spring's MultipartResolver before a
     * generic @RequestBody byte[] handler can read them.  Rebuild the small
     * multipart envelope explicitly so Excel/CSV/WITSML uploads reach
     * FastAPI byte-for-byte and retain the form configuration fields.
     */
    @PostMapping(value = "/survey/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> importSurvey(HttpServletRequest request,
                                               @RequestPart("file") MultipartFile file,
                                               @RequestHeader(value = "Authorization", required = false) String authorization)
            throws IOException, InterruptedException
    {
        String fieldId = request.getParameter("field_id");
        authorizePath("/survey/import", "POST", null, fieldId);
        String boundary = "----RuoYiAlgorithm" + java.util.UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Map<String, Object> fieldConfig = currentImportConfig(fieldId);
        Set<String> managedFields = Set.of("coordinate_system", "length_unit", "angle_unit",
                "north_reference", "tvd_positive", "magnetic_declination", "meridian_convergence",
                "origin_north", "origin_east", "origin_tvd");
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet())
        {
            if (managedFields.contains(entry.getKey())) continue;
            for (String value : entry.getValue())
            {
                writeMultipartField(output, boundary, entry.getKey(), value);
            }
        }
        for (String field : managedFields)
        {
            Object value = fieldConfig.get(field);
            if (value != null) writeMultipartField(output, boundary, field, String.valueOf(value));
        }
        writeMultipartFile(output, boundary, "file", file.getOriginalFilename(),
                file.getContentType(), file.getBytes());
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return forward(request, "POST", output.toByteArray(), authorization,
                MediaType.MULTIPART_FORM_DATA_VALUE + "; boundary=" + boundary);
    }

    @PutMapping("/**")
    public ResponseEntity<byte[]> put(HttpServletRequest request, @RequestBody(required = false) byte[] body,
                                      @RequestHeader(value = "Authorization", required = false) String authorization)
            throws IOException, InterruptedException
    {
        return forward(request, "PUT", body, authorization);
    }

    @DeleteMapping("/**")
    public ResponseEntity<byte[]> delete(HttpServletRequest request,
                                         @RequestHeader(value = "Authorization", required = false) String authorization)
            throws IOException, InterruptedException
    {
        return forward(request, "DELETE", null, authorization);
    }

    private ResponseEntity<byte[]> forward(HttpServletRequest request, String method, byte[] body, String authorization)
            throws IOException, InterruptedException
    {
        return forward(request, method, body, authorization, request.getContentType());
    }

    private ResponseEntity<byte[]> forward(HttpServletRequest request, String method, byte[] body, String authorization,
                                           String contentTypeOverride)
            throws IOException, InterruptedException
    {
        String path = request.getRequestURI().substring("/api/algorithm".length());
        authorizePath(path, method, body, request.getParameter("field_id"));
        byte[] effectiveBody = applyFieldAlgorithmConfiguration(path, method, body);
        return forward(request, method, effectiveBody, authorization, contentTypeOverride, false);
    }

    /**
     * Forward a request after the caller has already applied the fieldId
     * snapshot. This keeps the effective payload used for persistence exactly
     * equal to the payload sent to FastAPI.
     */
    private ResponseEntity<byte[]> forward(HttpServletRequest request, String method, byte[] body, String authorization,
                                           String contentTypeOverride, boolean applyConfiguration)
            throws IOException, InterruptedException
    {
        String path = request.getRequestURI().substring("/api/algorithm".length());
        authorizePath(path, method, body, request.getParameter("field_id"));
        byte[] effectiveBody = applyConfiguration ? applyFieldAlgorithmConfiguration(path, method, body) : body;
        String query = request.getQueryString();
        // FastAPI exposes its public routes below /api/algorithm.  Keep the
        // configuration value as an origin (http://host:8000) so it is also
        // usable by the dedicated training controller, and add the prefix in
        // one place.  A value that already contains the prefix remains valid.
        String target = algorithmBaseUrl() + path + (query == null ? "" : "?" + query);
        // 使用原始字节转发 multipart、Excel、Word、PDF 等内容，不能先解码为字符串。
        HttpRequest.BodyPublisher publisher = effectiveBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(effectiveBody);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target)).timeout(path.equals("/trajectory-plan/solve") ? Duration.ofSeconds(330) : Duration.ofMinutes(10))
                .header("Accept", MediaType.APPLICATION_JSON_VALUE).method(method, publisher);
        String contentType = contentTypeOverride;
        if (contentType != null && !(effectiveBody != null && isJsonPath(path)))
        {
            builder.header("Content-Type", contentType);
        }
        if (effectiveBody != null && isJsonPath(path))
        {
            builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        }
        if (authorization != null)
        {
            builder.header("Authorization", authorization);
        }
        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        HttpHeaders headers = new HttpHeaders();
        String responseType = response.headers().firstValue("content-type").orElse(MediaType.APPLICATION_JSON_VALUE);
        try
        {
            headers.setContentType(MediaType.parseMediaType(responseType));
        }
        catch (IllegalArgumentException ignored)
        {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        response.headers().firstValue("content-disposition").ifPresent(value -> headers.add("Content-Disposition", value));
        response.headers().firstValue("content-length").ifPresent(value -> headers.add("Content-Length", value));
        return new ResponseEntity<>(response.body(), headers, response.statusCode());
    }

    private void validateCollisionBinding(Map<String, Object> payload)
    {
        Long fieldId = fieldId(payload);
        Long referenceWellId = requiredLong(payload, "reference_well_id");
        Long referenceSurveyId = requiredLong(payload, "reference_survey_version_id");
        Long neighborWellId = requiredLong(payload, "neighbor_well_id");
        Long neighborSurveyId = requiredLong(payload, "neighbor_survey_version_id");
        if (referenceSurveyId.equals(neighborSurveyId))
            throw new IllegalArgumentException("reference and neighbor wells must be different");
        if (!"BOTH".equalsIgnoreCase(textOr(payload.get("mode"), "BOTH")))
            throw new IllegalArgumentException("anti-collision scans must calculate SPACING and SEPARATION_FACTOR together");
        requireWellSurvey(fieldId, referenceWellId, referenceSurveyId);
        requireWellSurvey(fieldId, neighborWellId, neighborSurveyId);
    }

    private void validateCollisionNetworkBinding(Map<String, Object> payload)
    {
        Long fieldId = fieldId(payload);
        Long referenceWellId = requiredLong(payload, "reference_well_id");
        Long referenceSurveyId = requiredLong(payload, "reference_survey_version_id");
        if (!(payload.get("neighbors") instanceof List<?> neighbors) || neighbors.isEmpty())
            throw new IllegalArgumentException("neighbors must contain at least one fieldId well");
        if (!"BOTH".equalsIgnoreCase(textOr(payload.get("mode"), "BOTH")))
            throw new IllegalArgumentException("anti-collision scans must calculate SPACING and SEPARATION_FACTOR together");
        requireWellSurvey(fieldId, referenceWellId, referenceSurveyId);
        Set<Long> unique = new HashSet<>();
        for (Object value : neighbors)
        {
            if (!(value instanceof Map<?, ?> neighbor))
                throw new IllegalArgumentException("each neighbor must be an object");
            Long wellId = requiredLongFromValue(neighbor.get("well_id"));
            Long surveyId = requiredLongFromValue(neighbor.get("survey_version_id"));
            if (referenceSurveyId.equals(surveyId)) throw new IllegalArgumentException("reference well cannot be included in neighbors");
            if (!unique.add(surveyId)) throw new IllegalArgumentException("neighbor wells must be unique");
            requireWellSurvey(fieldId, wellId, surveyId);
        }
        // A network scan may intentionally use a selected subset of fieldId
        // neighbors.  The anticollision workbench defaults to all active
        // neighbors, while the operator can narrow the set for a focused
        // review; membership and duplicate checks above remain mandatory.
    }

    private void validatePlanningBinding(Map<String, Object> payload)
    {
        Long fieldId = fieldId(payload);
        Long wellId = requiredLong(payload, "well_id");
        Long surveyId = requiredLong(payload, "current_survey_version_id");
        requireWellSurvey(fieldId, wellId, surveyId);
        String collisionConstraint = textOr(payload.get("collision_constraint"), "SEPARATION_FACTOR");
        if (!"SEPARATION_FACTOR".equalsIgnoreCase(collisionConstraint))
            throw new IllegalArgumentException("trajectory planning only supports SEPARATION_FACTOR collision constraints");
        Long targetId = optionalLong(payload.get("target_id"));
        if (targetId != null)
        {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM drilling_target WHERE target_id=? AND field_id=?",
                    Integer.class, targetId, fieldId);
            if (count == null || count == 0) throw new IllegalArgumentException("target_id is not part of this fieldId");
        }
        else if (!(payload.get("target") instanceof Map<?, ?>))
            throw new IllegalArgumentException("target geometry is required");
    }

    private void requireWellSurvey(Long fieldId, Long wellId, Long surveyId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM drilling_well w
                  JOIN drilling_survey_version s ON s.well_id=w.well_id
                  JOIN drilling_trajectory_version tv ON tv.survey_version_id=s.survey_version_id
                 WHERE EXISTS(SELECT 1 FROM drilling_field_wellbore x WHERE x.field_id=? AND x.wellbore_id=tv.wellbore_id)
                   AND w.well_id=? AND s.survey_version_id=?
                   AND NOT EXISTS(SELECT 1 FROM drilling_engineering_node en WHERE en.field_id=w.field_id
                     AND en.well_id=w.well_id AND en.node_type='WELL' AND en.entity_kind='PLANNED_RELIEF'
                     AND en.status='ACTIVE')
                   AND COALESCE((SELECT sr.status FROM drilling_source_review sr JOIN drilling_source_document sd ON sd.source_id=sr.source_id WHERE sd.file_id=s.source_file_id ORDER BY sr.review_id DESC LIMIT 1),'PENDING')='CONFIRMED'
                """, Integer.class, fieldId, wellId, surveyId);
        if (count == null || count == 0)
            throw new IllegalArgumentException("井眼和轨迹版本必须属于当前井场，且来源解析结果必须先完成人工确认");
    }

    private void persistCollision(Map<String, Object> payload, Map<String, Object> result)
    {
        Long fieldId = fieldId(payload);
        Long referenceWellId = requiredLong(payload, "reference_well_id");
        Long referenceSurveyId = requiredLong(payload, "reference_survey_version_id");
        Long neighborWellId = requiredLong(payload, "neighbor_well_id");
        Long neighborSurveyId = requiredLong(payload, "neighbor_survey_version_id");
        Map<?, ?> errorModel = map(payload.get("error_model"));
        Map<?, ?> thresholds = map(payload.get("thresholds"));
        String mode = "BOTH";
        String thresholdJson = JSON.toJSONString(thresholds);
        jdbc.update("""
                INSERT INTO anti_collision_task
                    (field_id, reference_well_id, reference_survey_version_id, evaluation_mode,
                     scan_step, sf_k, threshold_json, error_model, status, result_summary_json,
                     create_by, finish_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SUCCESS', ?, ?, CURRENT_TIMESTAMP)
                """, fieldId, referenceWellId, referenceSurveyId, mode,
                number(payload.get("step"), 1.0), number(errorModel.get("k"), 1.0), thresholdJson,
                textOr(errorModel.get("mode"), "ISCWSA"), JSON.toJSONString(result),
                ShiroUtils.getLoginName());
        Long taskId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (payload.get("_center_execution_id") instanceof Number execution) centerSnapshots.complete(execution.longValue(), "COLLISION", taskId);
        jdbc.update("INSERT INTO anti_collision_task_well(task_id, well_id, survey_version_id, role) VALUES (?, ?, ?, 'REFERENCE')",
                taskId, referenceWellId, referenceSurveyId);
        jdbc.update("INSERT INTO anti_collision_task_well(task_id, well_id, survey_version_id, role) VALUES (?, ?, ?, 'NEIGHBOR')",
                taskId, neighborWellId, neighborSurveyId);

        // The selected display mode may be SPACING (which intentionally has
        // no method-specific SF) or the legacy ERROR_MODEL diagnostic. Persist
        // and alarm on the common operational SF rule so those views cannot
        // hide an orange/red separation risk.
        String risk = textOr(result.get("operating_risk_level"),
                textOr(result.get("risk_level"), "BLUE")).toUpperCase();
        jdbc.update("""
                INSERT INTO anti_collision_result
                    (task_id, neighbor_well_id, min_distance, horizontal_distance, normal_distance,
                     reference_md, neighbor_md, sf, risk_level, result_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, taskId, neighborWellId, number(result.get("min_distance"), 0.0),
                nullableNumber(result.get("horizontal_distance")), nullableNumber(result.get("normal_distance")),
                nullableNumber(result.get("reference_md")), nullableNumber(result.get("neighbor_md")),
                nullableNumber(result.get("sf") == null ? result.get("operating_sf") : result.get("sf")),
                risk, JSON.toJSONString(result));
        Long resultId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        persistCollisionAlarm(fieldId, taskId, resultId, referenceWellId, neighborWellId,
                risk, number(result.get("min_distance"), 0.0), thresholdJson);
    }

    private void persistCollisionNetwork(Map<String, Object> payload, Map<String, Object> result)
    {
        Long fieldId = fieldId(payload);
        Long referenceWellId = requiredLong(payload, "reference_well_id");
        Long referenceSurveyId = requiredLong(payload, "reference_survey_version_id");
        Map<?, ?> errorModel = map(payload.get("error_model"));
        Map<?, ?> thresholds = map(payload.get("thresholds"));
        List<?> neighbors = (List<?>) payload.get("neighbors");
        jdbc.update("""
                INSERT INTO anti_collision_task
                    (field_id, reference_well_id, reference_survey_version_id, evaluation_mode,
                     scan_step, sf_k, threshold_json, error_model, status, result_summary_json,
                     create_by, finish_time)
                VALUES (?, ?, ?, 'BOTH', ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, fieldId, referenceWellId, referenceSurveyId, number(payload.get("step"), 1.0),
                number(errorModel.get("k"), 1.0), JSON.toJSONString(thresholds),
                textOr(errorModel.get("mode"), "ISCWSA"), "COMPLETED".equals(result.get("status"))?"SUCCESS":"INCOMPLETE", JSON.toJSONString(result), ShiroUtils.getLoginName());
        Long taskId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (payload.get("_center_execution_id") instanceof Number execution) centerSnapshots.complete(execution.longValue(), "COLLISION", taskId);
        jdbc.update("INSERT INTO anti_collision_task_well(task_id, well_id, survey_version_id, role) VALUES (?, ?, ?, 'REFERENCE')",
                taskId, referenceWellId, referenceSurveyId);

        Map<Long, Map<?, ?>> evaluated = new HashMap<>();
        if (result.get("results") instanceof List<?> rows)
            for (Object row : rows)
                if (row instanceof Map<?, ?> value)
                    evaluated.put(requiredLongFromValue(value.get("neighbor_survey_version_id")), value);
        String thresholdJson = JSON.toJSONString(thresholds);
        for (Object value : neighbors)
        {
            Map<?, ?> neighbor = (Map<?, ?>) value;
            Long neighborWellId = requiredLongFromValue(neighbor.get("well_id"));
            Long neighborSurveyId = requiredLongFromValue(neighbor.get("survey_version_id"));
            jdbc.update("INSERT INTO anti_collision_task_well(task_id, well_id, survey_version_id, role) VALUES (?, ?, ?, 'NEIGHBOR')",
                    taskId, neighborWellId, neighborSurveyId);
            Map<?, ?> scan = evaluated.get(neighborSurveyId);
            if (scan == null) continue;
            String risk = textOr(scan.get("operating_risk_level"), textOr(scan.get("risk_level"), "BLUE")).toUpperCase();
            jdbc.update("""
                    INSERT INTO anti_collision_result
                        (task_id, neighbor_well_id, min_distance, horizontal_distance, normal_distance,
                         reference_md, neighbor_md, sf, risk_level, result_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, taskId, neighborWellId, number(scan.get("min_distance"), 0.0),
                    nullableNumber(scan.get("horizontal_distance")), nullableNumber(scan.get("normal_distance")),
                    nullableNumber(scan.get("reference_md")), nullableNumber(scan.get("neighbor_md")),
                    nullableNumber(scan.get("sf") == null ? scan.get("operating_sf") : scan.get("sf")),
                    risk, JSON.toJSONString(scan));
            Long resultId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            persistCollisionAlarm(fieldId, taskId, resultId, referenceWellId, neighborWellId,
                    risk, number(scan.get("min_distance"), 0.0), thresholdJson);
            persistPredictedAlarm(payload,scan,fieldId,taskId,resultId,referenceWellId,neighborWellId,thresholdJson);
        }
    }

    private void mergeIncrementalNetworkResult(Map<String, Object> payload, Map<String, Object> result)
    {
        if ("wellpath-engine-1.0.0".equals(result.get("engine"))) return; // Native cache already returns the complete dependency-checked result.
        Long fieldId = fieldId(payload);
        Long referenceWellId = requiredLong(payload, "reference_well_id");
        double fromMd = number(payload.get("incremental_from_md"), 0.0);
        double toMd = number(payload.get("incremental_to_md"), Double.MAX_VALUE);
        Map<Long, Map<String, Object>> latest = new HashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.neighbor_well_id, r.result_json
                  FROM anti_collision_result r
                  JOIN anti_collision_task t ON t.task_id=r.task_id
                 WHERE t.field_id=? AND t.reference_well_id=? AND t.status='SUCCESS'
                 ORDER BY t.task_id DESC, r.result_id DESC
                """, fieldId, referenceWellId);
        for (Map<String, Object> row : rows)
        {
            Long wellId = ((Number) row.get("neighbor_well_id")).longValue();
            latest.putIfAbsent(wellId, JSON.parseObject(String.valueOf(row.get("result_json")), Map.class));
        }
        Map<Long, Map<String, Object>> current = new HashMap<>();
        if (result.get("results") instanceof List<?> items)
            for (Object item : items)
            {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Long wellId = requiredLongFromValue(raw.get("neighbor_well_id"));
                Map<String, Object> next = new LinkedHashMap<>();
                raw.forEach((key, value) -> next.put(String.valueOf(key), value));
                Map<String, Object> previous = latest.get(wellId);
                if (previous != null) current.put(wellId, mergeCollisionProfile(previous, next, fromMd, toMd));
                else current.put(wellId, next);
            }
        for (Map.Entry<Long, Map<String, Object>> entry : latest.entrySet()) current.putIfAbsent(entry.getKey(), entry.getValue());
        List<Map<String, Object>> merged = new ArrayList<>(current.values());
        merged.sort(Comparator.comparingDouble(item -> number(item.get("min_distance"), Double.MAX_VALUE)));
        Map<String, Object> summary = new LinkedHashMap<>(result);
        summary.put("results", merged);
        summary.put("candidate_count", merged.size());
        summary.put("prefiltered_count", Math.max(0, (int) number(result.get("neighbor_count"), merged.size()) - merged.size()));
        summary.put("minimum_distance", merged.stream().mapToDouble(item -> number(item.get("min_distance"), Double.MAX_VALUE)).min().orElse(Double.NaN));
        double minimumProfileSf = merged.stream().flatMap(item -> listOf(item.get("profile")).stream())
                .filter(Map.class::isInstance).map(Map.class::cast)
                .mapToDouble(item -> number(item.get("operating_sf"), Double.MAX_VALUE))
                .min().orElse(Double.NaN);
        summary.put("minimum_sf", minimumProfileSf);
        summary.put("operational_risk_level", merged.stream().map(item -> textOr(item.get("operating_risk_level"), "BLUE")).max(Comparator.comparingInt(AlgorithmGatewayController::riskRank)).orElse("BLUE"));
        summary.put("nearest_pair", merged.stream().min(Comparator.comparingDouble(item -> number(item.get("min_distance"), Double.MAX_VALUE))).orElse(null));
        result.clear(); result.putAll(summary);
    }

    private static Map<String, Object> mergeCollisionProfile(Map<String, Object> previous, Map<String, Object> next,
                                                              double fromMd, double toMd)
    {
        Map<String, Object> merged = new LinkedHashMap<>(previous);
        double oldDistance = number(previous.get("min_distance"), Double.MAX_VALUE);
        double newDistance = number(next.get("min_distance"), Double.MAX_VALUE);
        double oldMd = number(previous.get("reference_md"), Double.NaN);
        boolean oldMinimumRescanned = Double.isFinite(oldMd) && oldMd >= fromMd && oldMd <= toMd;
        if (oldMinimumRescanned || newDistance < oldDistance)
        {
            for (String key : List.of("min_distance", "horizontal_distance", "normal_distance", "reference_md", "neighbor_md", "reference_point", "neighbor_point", "sf", "operating_sf", "operating_risk_level", "risk_level", "error_envelope_radius", "net_distance", "error_model"))
                if (next.containsKey(key)) merged.put(key, next.get(key));
        }
        List<Map<String, Object>> profile = new ArrayList<>();
        for (Object row : listOf(previous.get("profile")))
        {
            if (!(row instanceof Map<?, ?> raw)) continue;
            Map<String, Object> sample = stringMap(raw);
            double md = number(sample.get("md"), Double.NaN);
            if (!Double.isFinite(md) || md < fromMd || md > toMd) profile.add(sample);
        }
        for (Object row : listOf(next.get("profile"))) if (row instanceof Map<?, ?> raw) profile.add(stringMap(raw));
        Map<Double, Map<String, Object>> byMd = new TreeMap<>();
        for (Map<String, Object> row : profile) byMd.put(number(row.get("md"), 0), row);
        List<Map<String, Object>> mergedProfile = new ArrayList<>(byMd.values());
        merged.put("profile", mergedProfile);
        merged.put("operating_risk_level", mergedProfile.stream()
                .map(row -> textOr(row.get("operating_risk_level"), textOr(row.get("risk_level"), "BLUE")))
                .max(Comparator.comparingInt(AlgorithmGatewayController::riskRank)).orElse("BLUE"));
        merged.put("risk_segments", mergeRiskSegments(mergedProfile));
        merged.put("incremental", true);
        return merged;
    }

    private static List<Map<String, Object>> mergeRiskSegments(List<Map<String, Object>> profile)
    {
        List<Map<String, Object>> segments = new ArrayList<>();
        if (profile.isEmpty()) return segments;
        int start = 0;
        String level = textOr(profile.get(0).get("risk_level"), "BLUE");
        for (int index = 1; index <= profile.size(); index++)
        {
            String nextLevel = index < profile.size() ? textOr(profile.get(index).get("risk_level"), "BLUE") : null;
            if (java.util.Objects.equals(level, nextLevel)) continue;
            List<Map<String, Object>> rows = profile.subList(start, index);
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("start_md", number(rows.get(0).get("md"), 0.0));
            segment.put("end_md", number(rows.get(rows.size() - 1).get("md"), 0.0));
            segment.put("risk_level", level);
            segment.put("min_center_distance", rows.stream().mapToDouble(row -> number(row.get("center_distance"), Double.MAX_VALUE)).min().orElse(Double.NaN));
            segment.put("min_sf", rows.stream().map(row -> row.get("operating_sf")).filter(Number.class::isInstance).map(Number.class::cast).mapToDouble(Number::doubleValue).min().orElse(Double.NaN));
            segment.put("point_count", rows.size());
            segments.add(segment);
            start = index;
            level = nextLevel;
        }
        return segments;
    }

    private static int riskRank(String level)
    {
        return switch (String.valueOf(level).toUpperCase()) { case "RED" -> 3; case "ORANGE" -> 2; case "YELLOW" -> 1; default -> 0; };
    }

    private static List<?> listOf(Object value) { return value instanceof List<?> list ? list : List.of(); }

    private static Map<String, Object> stringMap(Map<?, ?> source)
    {
        Map<String, Object> target = new LinkedHashMap<>(); source.forEach((key, value) -> target.put(String.valueOf(key), value)); return target;
    }

    /** Persist the future interval separately so its alert retains the measured/predicted boundary. */
    private void persistPredictedAlarm(Map<String, Object> payload, Map<?, ?> scan, Long fieldId,
            Long task, Long resultId, Long well, Long neighbor, String rules)
    {
        Map<?, ?> reference = map(payload.get("reference"));
        double measuredMd = -1;
        double predictedMd = -1;
        Set<String> predictionSources = Set.of("TRAINED_MODEL", "PREDICTED_TRAINED_MODEL",
                "ATTITUDE_EXTRAPOLATION", "PREDICTED_ATTITUDE_EXTRAPOLATION",
                "TANGENT_EXTRAPOLATION", "TANGENT_EXTRAPOLATION_FALLBACK",
                "PREDICTED_TANGENT_EXTRAPOLATION", "PREDICTED_TANGENT_EXTRAPOLATION_FALLBACK");
        for (Object raw : listOf(reference.get("stations")))
        {
            if (!(raw instanceof Map<?, ?> station)) continue;
            double md = number(station.get("md"), -1);
            if (predictionSources.contains(textOr(station.get("source"), "")))
                predictedMd = Math.max(predictedMd, md);
            else measuredMd = Math.max(measuredMd, md);
        }
        if (predictedMd <= measuredMd || measuredMd < 0) return;
        String risk = "BLUE";
        double distance = Double.POSITIVE_INFINITY;
        boolean evaluated = false;
        for (Object raw : listOf(scan.get("profile")))
        {
            if (!(raw instanceof Map<?, ?> row) || number(row.get("md"), -1) <= measuredMd) continue;
            evaluated = true;
            String level = textOr(row.get("operating_risk_level"), textOr(row.get("risk_level"), "BLUE")).toUpperCase();
            if (riskRank(level) > riskRank(risk)) risk = level;
            distance = Math.min(distance, number(row.get("center_distance"), Double.POSITIVE_INFINITY));
        }
        if (!evaluated) return;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("thresholds", JSON.parse(rules));
        snapshot.put("measured_md", measuredMd);
        snapshot.put("predicted_md", predictedMd);
        snapshot.put("neighbor_well_id", neighbor);
        persistCollisionAlarm(fieldId, task, resultId, well, neighbor, risk,
                Double.isFinite(distance) ? distance : 0, JSON.toJSONString(snapshot), "PREDICTED_COLLISION");
    }

    /** One open alarm per well pair and alarm type; escalation preserves the previous level. */
    private void persistCollisionAlarm(Long fieldId, Long taskId, Long resultId,
            Long referenceWellId, Long neighborWellId, String risk, double distance, String thresholdJson)
    {
        persistCollisionAlarm(fieldId,taskId,resultId,referenceWellId,neighborWellId,risk,distance,thresholdJson,"ANTI_COLLISION_RISK");
    }
    private void persistCollisionAlarm(Long fieldId, Long taskId, Long resultId,
            Long referenceWellId, Long neighborWellId, String risk, double distance, String thresholdJson,String alarmCode)
    {
        // Serialize scans for this pair, including the first scan where no alarm row exists yet.
        long firstWell = Math.min(referenceWellId, neighborWellId);
        long secondWell = Math.max(referenceWellId, neighborWellId);
        jdbc.queryForList("SELECT well_id FROM drilling_well WHERE well_id IN (?, ?) ORDER BY well_id FOR UPDATE",
                Long.class, firstWell, secondWell);

        List<Map<String, Object>> latestRows = jdbc.queryForList("""
                SELECT a.alarm_id, a.current_level, a.status
                  FROM drilling_alarm a
                  JOIN anti_collision_task_well r ON r.task_id=a.task_id AND r.role='REFERENCE'
                  JOIN anti_collision_result n ON n.result_id=a.result_id
                 WHERE a.field_id=? AND ((?='ANTI_COLLISION_RISK' AND a.alarm_code IN ('ANTI_COLLISION_RISK','ANTI_COLLISION_RED')) OR a.alarm_code=?)
                   AND ((r.well_id=? AND n.neighbor_well_id=?) OR (r.well_id=? AND n.neighbor_well_id=?))
                 ORDER BY a.alarm_id DESC LIMIT 1 FOR UPDATE
                """, fieldId, alarmCode, alarmCode, referenceWellId, neighborWellId, neighborWellId, referenceWellId);
        List<Map<String, Object>> activeRows = jdbc.queryForList("""
                SELECT a.alarm_id, a.current_level, a.status
                  FROM drilling_alarm a
                  JOIN anti_collision_task_well r ON r.task_id=a.task_id AND r.role='REFERENCE'
                  JOIN anti_collision_result n ON n.result_id=a.result_id
                 WHERE a.field_id=? AND ((?='ANTI_COLLISION_RISK' AND a.alarm_code IN ('ANTI_COLLISION_RISK','ANTI_COLLISION_RED')) OR a.alarm_code=?)
                   AND a.status IN ('OPEN', 'ACKNOWLEDGED')
                   AND ((r.well_id=? AND n.neighbor_well_id=?) OR (r.well_id=? AND n.neighbor_well_id=?))
                 ORDER BY a.alarm_id DESC LIMIT 1 FOR UPDATE
                """, fieldId, alarmCode, alarmCode, referenceWellId, neighborWellId, neighborWellId, referenceWellId);
        Map<String, Object> latest = latestRows.isEmpty() ? null : latestRows.get(0);
        Map<String, Object> active = activeRows.isEmpty() ? null : activeRows.get(0);
        String previous = latest == null ? null : String.valueOf(latest.get("current_level"));
        String message = (alarmCode.equals("PREDICTED_COLLISION")?"预测井段与邻井最近中心距 ":"正钻井与邻井最近中心距 ") + distance + " m";

        if ("BLUE".equals(risk))
        {
            if (active != null)
            {
                jdbc.update("""
                        UPDATE drilling_alarm
                           SET previous_level=current_level, current_level='BLUE', status='RESOLVED',
                               task_id=?, result_id=?, well_id=?, message=?, resolved_by='system',
                               resolved_at=CURRENT_TIMESTAMP
                         WHERE alarm_id=?
                        """, taskId, resultId, referenceWellId, message + "；风险已解除", active.get("alarm_id"));
            }
            return;
        }

        if (active != null && risk.equalsIgnoreCase(String.valueOf(active.get("current_level"))))
        {
            // Repeated scans refresh the evidence but do not reopen an acknowledged alarm.
            jdbc.update("""
                    UPDATE drilling_alarm SET task_id=?, result_id=?, well_id=?, message=?, rule_snapshot_json=?
                     WHERE alarm_id=?
                    """, taskId, resultId, referenceWellId, message, thresholdJson, active.get("alarm_id"));
            return;
        }

        if (active != null)
        {
            jdbc.update("""
                    UPDATE drilling_alarm SET status='RESOLVED', resolved_by='system',
                           resolved_at=CURRENT_TIMESTAMP
                     WHERE alarm_id=?
                    """, active.get("alarm_id"));
            previous = String.valueOf(active.get("current_level"));
        }

        jdbc.update("""
                INSERT INTO drilling_alarm
                    (field_id, task_id, result_id, well_id, alarm_code, previous_level,
                     current_level, status, message, rule_snapshot_json, create_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)
                """, fieldId, taskId, resultId, referenceWellId, alarmCode, previous, risk,
                message, thresholdJson, ShiroUtils.getLoginName());
    }

    private void persistPlan(Map<String, Object> payload, Map<String, Object> result)
    {
        Long fieldId = fieldId(payload);
        Long wellId = requiredLong(payload, "well_id");
        Long surveyId = requiredLong(payload, "current_survey_version_id");
        Long targetId = optionalLong(payload.get("target_id"));
        Map<?, ?> target = map(payload.get("target"));
        if (targetId == null)
        {
            String kind = textOr(target.get("kind"), "POINT").toUpperCase();
            if (!List.of("POINT", "RECTANGLE", "ELLIPSE").contains(kind))
                throw new IllegalArgumentException("target kind must be POINT, RECTANGLE or ELLIPSE");
            Map<String, Object> tolerance = new LinkedHashMap<>();
            for (String key : List.of("tolerance_north", "tolerance_east", "tolerance_tvd",
                    "shape_north", "shape_east", "shape_tvd"))
                if (target.get(key) != null) tolerance.put(key, target.get(key));
            jdbc.update("""
                    INSERT INTO drilling_target
                        (field_id, target_name, target_type, geometry_json, tolerance_json, create_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, fieldId, "规划请求-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                    kind, JSON.toJSONString(target), JSON.toJSONString(tolerance), ShiroUtils.getLoginName());
            targetId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }

        String status = textOr(result.get("status"), "BEST_EFFORT").toUpperCase();
        if (!List.of("SUCCESS", "INFEASIBLE", "BEST_EFFORT").contains(status))
            throw new IllegalArgumentException("algorithm returned an unsupported planning status: " + status);
        String solver = textOr(payload.get("solver"), "SLSQP").toUpperCase();
        String scenario = "NATIVE_PROFILE";
        jdbc.update("""
                INSERT INTO trajectory_plan
                    (field_id, well_id, current_survey_version_id, target_id, solver, scenario,
                     constraint_json, objective_value, terminal_error, status, result_json, create_by, finish_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, fieldId, wellId, surveyId, targetId, solver, scenario,
                JSON.toJSONString(payload), nullableNumber(result.get("total_length")),
                nullableNumber(result.get("target_error")), status, JSON.toJSONString(result),
                ShiroUtils.getLoginName());
        Long planId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (payload.get("_center_execution_id") instanceof Number execution) centerSnapshots.complete(execution.longValue(), "REPLANNING", planId);
        Object segmentValue = result.get("segments");
        if (!(segmentValue instanceof List<?> segments)) return;
        List<?> trajectory = result.get("trajectory") instanceof List<?> points ? points : List.of();
        for (Object item : segments)
        {
            if (!(item instanceof Map<?, ?> segment)) continue;
            int segmentNo = (int) number(segment.get("segment_no"), 0);
            // Dense output contains many rows per segment; array indices are
            // not segment numbers. Bind persisted endpoints by their true MD.
            double startMd=number(segment.get("start_md"),Double.NaN),endMd=number(segment.get("end_md"),Double.NaN);
            Map<?,?> start=Map.of(),end=Map.of();
            double sd=Double.POSITIVE_INFINITY,ed=Double.POSITIVE_INFINITY;
            for(Object point:trajectory) {
                Map<?,?> row=map(point);double md=number(row.get("md"),Double.NaN);
                if(Math.abs(md-startMd)<sd){sd=Math.abs(md-startMd);start=row;}
                if(Math.abs(md-endMd)<ed){ed=Math.abs(md-endMd);end=row;}
            }
            jdbc.update("""
                    INSERT INTO trajectory_plan_segment
                        (plan_id, segment_no, segment_length, curvature, toolface, start_md, end_md,
                         start_north, start_east, start_tvd, end_north, end_east, end_tvd, dogleg, sliding_ratio)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, planId, segmentNo, number(segment.get("segment_length"), 0.0),
                    number(segment.get("curvature"),number(segment.get("curvature_signed_rad_per_m"), number(segment.get("curvature_rad_per_m"), 0.0))),
                    number(segment.get("toolface_deg"), 0.0), nullableNumber(segment.get("start_md")),
                    nullableNumber(segment.get("end_md")), nullableNumber(start.get("north")),
                    nullableNumber(start.get("east")), nullableNumber(start.get("tvd")),
                    nullableNumber(end.get("north")), nullableNumber(end.get("east")), nullableNumber(end.get("tvd")),
                    nullableNumber(segment.get("dogleg_deg_per_30m")), nullableNumber(segment.get("sliding_ratio")));
        }
    }

    private void requireCurrentSurvey(Long fieldId, Long wellId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM drilling_well w
                  JOIN drilling_survey_version v ON v.well_id=w.well_id AND v.is_current='1'
                 WHERE w.field_id=? AND w.well_id=? AND w.status <> 'ARCHIVED'
                   AND NOT EXISTS(SELECT 1 FROM drilling_engineering_node en WHERE en.field_id=w.field_id
                     AND en.well_id=w.well_id AND en.node_type='WELL' AND en.entity_kind='PLANNED_RELIEF'
                     AND en.status='ACTIVE')
                """, Integer.class, fieldId, wellId);
        if (count == null || count == 0)
            throw new IllegalArgumentException("realtime ingest requires an active survey version for this well");
    }

    /** Save raw events before calling FastAPI; PENDING rows are replayed on the next request. */
    private int persistRealtimeInputs(Map<String, Object> payload, Long fieldId, Long wellId, String source)
    {
        Object rawEvents = payload.get("events");
        if (rawEvents != null && !(rawEvents instanceof List<?>))
            throw new IllegalArgumentException("events must be an array");
        int duplicates = 0;
        if (!(rawEvents instanceof List<?> events)) return duplicates;
        for (Object raw : events)
        {
            if (!(raw instanceof Map<?, ?> sourceEvent))
                throw new IllegalArgumentException("each realtime event must be an object");
            Map<String, Object> event = new LinkedHashMap<>();
            sourceEvent.forEach((key, value) -> event.put(String.valueOf(key), value));
            // The enclosing authenticated connector defines stream identity.
            // A device-provided source must not bypass wellbore isolation or
            // leave durable inbox rows permanently in PENDING state.
            if(event.containsKey("source"))event.put("device_source",event.get("source"));
            event.put("source",source);
            Object eventWellId = first(event, "well_id", "well", "wellName");
            if (eventWellId != null && !wellId.equals(requiredLongFromValue(eventWellId)))
                throw new IllegalArgumentException("event well_id must match the numeric well_id in the request");
            event.put("well_id", String.valueOf(wellId));
            try {
                double md = eventNumber(event, "md", "measured_depth", "measuredDepth");
                double inclination = eventNumber(event, "inclination", "inc");
                eventNumber(event, "azimuth", "azi");
                if (md < 0 || inclination < 0 || inclination > 180)
                    throw new IllegalArgumentException("realtime MD or inclination is outside its valid range");
            } catch (IllegalArgumentException ex) {
                businessEvents.quality(fieldId, wellId, null, "INVALID", "INVALID:" + source + ":" + JSON.toJSONString(event), event, ex.getMessage());
                continue;
            }
            String key = realtimeEventKey(source, wellId, event);
            int inserted = jdbc.update("""
                    INSERT IGNORE INTO drilling_realtime_ingest_event
                        (field_id, well_id, source_name, event_key, raw_event_json, status)
                    VALUES (?, ?, ?, ?, ?, 'PENDING')
                    """, fieldId, wellId, source, key, JSON.toJSONString(event));
            if (inserted == 0) {
                duplicates++;
                businessEvents.quality(fieldId, wellId, null, "DUPLICATE", "DUPLICATE:"+source+":"+key, event, "重复事件已忽略，原记录保留");
            }
        }
        return duplicates;
    }

    private void markRealtimeRejectedEvents(Map<String, Object> result, Long fieldId, Long wellId,
                                            String source, List<Map<String, Object>> submitted)
    {
        Object rawInvalid = result.get("invalid");
        if (rawInvalid instanceof List<?> invalidRows)
        {
            for (Object item : invalidRows)
            {
                if (!(item instanceof Map<?, ?> invalid)) continue;
                int index = (int) number(invalid.get("index"), -1);
                if (index < 0 || index >= submitted.size()) continue;
                String key = realtimeEventKey(source, wellId, submitted.get(index));
                updateRealtimeEvent(fieldId, wellId, source, key, "INVALID",
                        textOr(invalid.get("reason"), "invalid event"), null);
            }
        }
        Object rawLate = result.get("out_of_order_events");
        if (rawLate instanceof List<?> lateRows)
        {
            for (Object item : lateRows)
            {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> event = stringMap(raw);
                updateRealtimeEvent(fieldId, wellId, source, realtimeEventKey(source, wellId, event),
                        "LATE", "outside the emitted MD watermark; retained for audit", null);
            }
        }
    }

    private Long persistRealtimeSurvey(Long fieldId, Long wellId, Long boreId, String source,
                                       List<Map<String, Object>> accepted, String actor)
    {
        lockWell(wellId);
        Map<String, Object> current = boreId==null ? jdbc.queryForMap("""
                SELECT survey_version_id, version_no, config_version, config_snapshot_json
                  FROM drilling_survey_version
                 WHERE well_id=? AND is_current='1' FOR UPDATE
                """, wellId) : jdbc.queryForMap("SELECT v.survey_version_id,v.version_no,v.config_version,v.config_snapshot_json FROM drilling_trajectory_head h JOIN drilling_survey_version v ON v.survey_version_id=h.survey_version_id WHERE h.wellbore_id=? AND h.trajectory_kind='ACTUAL' AND v.well_id=? FOR UPDATE",boreId,wellId);
        Long currentVersionId = ((Number) current.get("survey_version_id")).longValue();
        List<Map<String, Object>> previous = jdbc.queryForList("""
                SELECT station_id, md, inclination, azimuth, north, east, tvd, dogleg, curvature,
                       source_timestamp, raw_json
                  FROM drilling_survey_station WHERE survey_version_id=? ORDER BY md
                """, currentVersionId);
        if (previous.isEmpty())
            throw new IllegalArgumentException("active survey version has no stations; import a baseline survey first");

        Map<BigDecimal, Map<String, Object>> byMd = new java.util.TreeMap<>();
        double maxMd = -1;
        for (Map<String, Object> station : previous)
        {
            byMd.put(decimalKey(station.get("md")), station);
            maxMd = Math.max(maxMd, number(station.get("md"), 0));
        }
        List<Map<String, Object>> ordered = new ArrayList<>(accepted);
        ordered.sort((left, right) -> Double.compare(eventNumber(left, "md"), eventNumber(right, "md")));
        List<Map<String, Object>> additions = new ArrayList<>();
        Map<BigDecimal, Map<String, Object>> additionByMd = new java.util.TreeMap<>();
        for (Map<String, Object> event : ordered)
        {
            String eventSource = textOr(event.get("source"), source);
            String eventKey = realtimeEventKey(eventSource, wellId, event);
            BigDecimal mdKey = decimalKey(eventNumber(event, "md"));
            Map<String, Object> existing = byMd.get(mdKey);
            if (existing != null)
            {
                boolean same = close(number(existing.get("inclination"), 0), eventNumber(event, "inclination"))
                        && close(number(existing.get("azimuth"), 0), eventNumber(event, "azimuth"));
                updateRealtimeEvent(fieldId, wellId, eventSource, eventKey,
                        same ? "COMMITTED" : "CONFLICT",
                        same ? "station already exists with the same angles" : "MD already exists with different angles",
                        same ? currentVersionId : null);
                continue;
            }
            Map<String, Object> sameMdAddition = additionByMd.get(mdKey);
            if (sameMdAddition != null)
            {
                boolean same = close(eventNumber(sameMdAddition, "inclination"), eventNumber(event, "inclination"))
                        && close(eventNumber(sameMdAddition, "azimuth"), eventNumber(event, "azimuth"));
                updateRealtimeEvent(fieldId, wellId, eventSource, eventKey,
                        same ? "COMMITTED" : "CONFLICT",
                        same ? "duplicate station in realtime batch" : "conflicting stations share the same MD",
                        same ? currentVersionId : null);
                continue;
            }
            if (eventNumber(event, "md") <= maxMd)
            {
                updateRealtimeEvent(fieldId, wellId, eventSource, eventKey,
                        "CONFLICT", "station MD does not extend the current survey version", null);
                continue;
            }
            additionByMd.put(mdKey, event);
            additions.add(event);
            maxMd = eventNumber(event, "md");
        }
        if (additions.isEmpty()) return null;

        // Keep the immutable prefix exactly as stored (including database rounding).
        // Only calculate new MC segments from the final corrected parent station.
        Map<String, Object> firstStation = previous.get(previous.size()-1);
        List<Map<String, Object>> inputStations = new ArrayList<>();
        inputStations.add(Map.of("md",firstStation.get("md"),"inclination",firstStation.get("inclination"),"azimuth",firstStation.get("azimuth")));
        for (Map<String, Object> event : additions)
            inputStations.add(Map.of("md", eventNumber(event, "md"),
                    "inclination", eventNumber(event, "inclination"), "azimuth", eventNumber(event, "azimuth")));
        Map<String, Object> calculation = new LinkedHashMap<>();
        calculation.put("stations", inputStations);
        calculation.put("origin_north", firstStation.get("north"));
        calculation.put("origin_east", firstStation.get("east"));
        calculation.put("origin_tvd", firstStation.get("tvd"));
        Map<String, Object> calculated = calculateRealtimeStations(calculation);
        if (!(calculated.get("stations") instanceof List<?> calculatedRows)
                || calculatedRows.size() != inputStations.size())
            throw new IllegalStateException("algorithm service returned an incomplete calculated survey");

        Integer nextVersion = trajectoryVersions.nextSnapshotNumber(wellId);
        jdbc.update("UPDATE drilling_survey_version SET is_current='0' WHERE well_id=? AND is_current='1'", wellId);
        double finalMd = eventNumber(additions.get(additions.size() - 1), "md");
        jdbc.update("""
                INSERT INTO drilling_survey_version
                    (well_id, version_no, parent_version_id, config_version, import_mode,
                     source_format, max_md, is_current, quality_summary_json, config_snapshot_json, create_by)
                VALUES (?, ?, ?, ?, 'INCREMENTAL', 'REALTIME', ?, '1', ?, ?, ?)
                """, wellId, nextVersion, currentVersionId, current.get("config_version"), finalMd,
                JSON.toJSONString(Map.of("source", source, "appended_stations", additions.size(),
                        "ingest_events", additions.size())), current.get("config_snapshot_json"),
                actor);
        Long versionId = jdbc.queryForObject("""
                SELECT survey_version_id FROM drilling_survey_version WHERE well_id=? AND version_no=?
                """, Long.class, wellId, nextVersion);

        Map<BigDecimal, Map<String, Object>> eventByMd = new java.util.TreeMap<>();
        for (Map<String, Object> event : additions) eventByMd.put(decimalKey(eventNumber(event, "md")), event);
        Map<BigDecimal, Map<String, Object>> oldByMd = new java.util.TreeMap<>();
        for (Map<String, Object> station : previous) oldByMd.put(decimalKey(station.get("md")), station);
        List<Object> nextSnapshot = new ArrayList<>(previous);
        nextSnapshot.addAll(calculatedRows.subList(1,calculatedRows.size()));
        for (Object row : nextSnapshot)
        {
            if (!(row instanceof Map<?, ?> raw)) throw new IllegalStateException("calculated station must be an object");
            Map<String, Object> station = stringMap(raw);
            BigDecimal mdKey = decimalKey(station.get("md"));
            Map<String, Object> event = eventByMd.get(mdKey);
            Object rawSnapshot;
            Timestamp sourceTimestamp = null;
            if (event != null)
            {
                Map<String, Object> stationRaw = new LinkedHashMap<>(station);
                stationRaw.put("realtime", event);
                stationRaw.put("source", source);
                rawSnapshot = JSON.toJSONString(stationRaw);
                sourceTimestamp = parseSourceTimestamp(event.get("timestamp"));
            }
            else
            {
                Map<String, Object> old = oldByMd.get(mdKey);
                rawSnapshot = old == null ? JSON.toJSONString(station) : old.get("raw_json");
                Object timestamp = old == null ? null : old.get("source_timestamp");
                if (timestamp instanceof Timestamp ts) sourceTimestamp = ts;
            }
            jdbc.update("""
                    INSERT INTO drilling_survey_station
                        (survey_version_id, md, inclination, azimuth, north, east, tvd,
                         dogleg, curvature, source_timestamp, raw_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, versionId, eventNumber(station, "md"), eventNumber(station, "inclination"),
                    eventNumber(station, "azimuth"), eventNumber(station, "north"), eventNumber(station, "east"),
                    eventNumber(station, "tvd"), nullableNumber(station.get("dogleg")),
                    nullableNumber(station.get("curvature")), sourceTimestamp,
                    rawSnapshot == null ? JSON.toJSONString(station) : rawSnapshot);
        }
        trajectoryVersions.registerActualSnapshot(versionId,currentVersionId,"INCREMENTAL",eventNumber(firstStation,"md"));
        for (Map<String, Object> event : additions)
            updateRealtimeEvent(fieldId, wellId, textOr(event.get("source"), source),
                    realtimeEventKey(textOr(event.get("source"), source), wellId, event),
                    "COMMITTED", "survey version created", versionId);
        businessEvents.versionChange(fieldId,actor,"INCREMENTAL","SURVEY","SURVEY:"+versionId,Map.of("parent_version_id",currentVersionId,"source",source,"appended_stations",additions.size()));
        return versionId;
    }

    /** Serialize concurrent incremental versions for one well. */
    private void lockWell(Long wellId)
    {
        Long locked = jdbc.queryForObject(
                "SELECT well_id FROM drilling_well WHERE well_id=? FOR UPDATE", Long.class, wellId);
        if (locked == null)
            throw new IllegalArgumentException("well does not exist");
    }

    private Map<String, Object> calculateRealtimeStations(Map<String, Object> body)
    {
        try
        {
            HttpRequest request = HttpRequest.newBuilder(URI.create(algorithmBaseUrl() + "/survey/calculate"))
                    .timeout(Duration.ofMinutes(2)).header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body))).build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("survey calculation failed: " + new String(response.body(), StandardCharsets.UTF_8));
            return parseJsonBody(response.body());
        }
        catch (IOException ex) { throw new IllegalStateException("survey calculation service is unavailable", ex); }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("survey calculation was interrupted", ex);
        }
    }

    private void updateRealtimeEvent(Long fieldId, Long wellId, String source, String eventKey,
                                     String status, String message, Long surveyVersionId)
    {
        jdbc.update("""
                UPDATE drilling_realtime_ingest_event
                   SET status=?, status_message=?, survey_version_id=?, resolved_time=CURRENT_TIMESTAMP
                 WHERE field_id=? AND well_id=? AND source_name=? AND event_key=? AND status IN ('PENDING','READY')
                """, status, message, surveyVersionId, fieldId, wellId, source, eventKey);
    }

    private void markRealtimeEventsReady(Long fieldId, Long wellId, String source,
                                        List<Map<String, Object>> accepted)
    {
        for (Map<String, Object> event : accepted)
        {
            String eventSource = textOr(event.get("source"), source);
            String eventKey = realtimeEventKey(eventSource, wellId, event);
            Map<String, Object> stored = new LinkedHashMap<>(event);
            stored.put("source", eventSource);
            stored.put("well_id", String.valueOf(wellId));
            jdbc.update("""
                    UPDATE drilling_realtime_ingest_event
                       SET status='READY', raw_event_json=?, status_message='normalized by algorithm service'
                     WHERE field_id=? AND well_id=? AND source_name=? AND event_key=? AND status='PENDING'
                    """, JSON.toJSONString(stored), fieldId, wellId, eventSource, eventKey);
        }
    }

    private List<Map<String, Object>> loadRealtimeReadyEvents(Long fieldId, Long wellId, String source)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT raw_event_json FROM drilling_realtime_ingest_event
                 WHERE field_id=? AND well_id=? AND source_name=? AND status='READY' ORDER BY event_id
                """, fieldId, wellId, source);
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> row : rows)
        {
            Map<String, Object> event = JSON.parseObject(jsonText(row.get("raw_event_json")), Map.class);
            event.put("source", source);
            event.put("well_id", String.valueOf(wellId));
            events.add(event);
        }
        return events;
    }

    private List<Map<String, Object>> uniqueRealtimeEvents(List<Map<String, Object>> input,
                                                           String source, Long wellId)
    {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> event : input)
        {
            String eventSource = textOr(event.get("source"), source);
            unique.putIfAbsent(realtimeEventKey(eventSource, wellId, event), event);
        }
        return new ArrayList<>(unique.values());
    }

    private List<Map<String, Object>> realtimeEvents(Map<String, Object> result)
    {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (String field : List.of("accepted", "flushed"))
        {
            Object value = result.get(field);
            if (!(value instanceof List<?> rows)) continue;
            for (Object row : rows)
            {
                if (!(row instanceof Map<?, ?> raw)) continue;
                Map<String, Object> event = stringMap(raw);
                String source = textOr(event.get("source"), textOr(result.get("source"), ""));
                unique.putIfAbsent(realtimeEventKey(source, requiredLongFromValue(event.get("well_id")), event), event);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private String realtimeEventKey(String source, Long wellId, Map<String, Object> event)
    {
        double md = eventNumber(event, "md", "measured_depth", "measuredDepth");
        String sequence = textOr(first(event, "sequence", "seq"), "");
        String canonicalMd = BigDecimal.valueOf(md).setScale(6, RoundingMode.HALF_EVEN).toPlainString();
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (source + "\n" + wellId + "\n" + sequence + "\n" + canonicalMd).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }

    private static Long requiredLongFromValue(Object value)
    {
        Long parsed = optionalLong(value);
        if (parsed == null || parsed <= 0) throw new IllegalArgumentException("event well_id must be a positive numeric well id");
        return parsed;
    }

    private static double eventNumber(Map<?, ?> event, String... names)
    {
        Object value = first(event, names);
        if (value == null) throw new IllegalArgumentException(names[0] + " is required for each realtime event");
        double number = number(value, Double.NaN);
        if (!Double.isFinite(number)) throw new IllegalArgumentException(names[0] + " must be finite");
        return number;
    }

    private static Object first(Map<?, ?> event, String... names)
    {
        for (String name : names)
        {
            Object value = event.get(name);
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return null;
    }

    private static BigDecimal decimalKey(Object value)
    {
        return BigDecimal.valueOf(number(value, 0)).setScale(6, RoundingMode.HALF_EVEN);
    }

    private static boolean close(double left, double right)
    {
        return Math.abs(left - right) <= 1e-6;
    }

    private static Timestamp parseSourceTimestamp(Object value)
    {
        if (value == null || String.valueOf(value).isBlank()) return null;
        String text = String.valueOf(value).trim();
        try { return Timestamp.from(Instant.parse(text)); }
        catch (DateTimeParseException ignored) { }
        try { return Timestamp.from(OffsetDateTime.parse(text).toInstant()); }
        catch (DateTimeParseException ignored) { }
        try { return Timestamp.valueOf(LocalDateTime.parse(text)); }
        catch (DateTimeParseException | IllegalArgumentException ignored) { }
        try { return Timestamp.valueOf(text); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static String jsonText(Object value)
    {
        if (value == null) throw new IllegalArgumentException("stored realtime event is empty");
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonBody(byte[] body)
    {
        if (body == null || body.length == 0) throw new IllegalArgumentException("JSON request body is required");
        try { return JSON.parseObject(body, Map.class); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("invalid JSON request body", ex); }
    }

    private static Long requiredLong(Map<String, Object> payload, String key)
    {
        Long value = optionalLong(payload.get(key));
        if (value == null || value <= 0) throw new IllegalArgumentException(key + " is required and must be positive");
        return value;
    }

    private static Long optionalLong(Object value)
    {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try
        {
            BigDecimal decimal = value instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(value));
            if (decimal.stripTrailingZeros().scale() > 0
                    || decimal.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0
                    || decimal.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0)
                throw new IllegalArgumentException("numeric identifier must be an integer");
            return decimal.longValueExact();
        }
        catch (NumberFormatException | ArithmeticException ex)
        {
            throw new IllegalArgumentException("numeric identifier is invalid");
        }
    }

    private static Double nullableNumber(Object value)
    {
        return value == null ? null : number(value, 0.0);
    }

    private static double number(Object value, double fallback)
    {
        if (value == null) return fallback;
        try { return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("numeric algorithm value is invalid"); }
    }

    private static String textOr(Object value, String fallback)
    {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    /**
     * Expand algorithm requests from the active fieldId snapshot.  Geometry,
     * target and survey data still come from the request; operational controls
     * cannot be changed by a client that bypasses the fieldId configuration UI.
     */
    private byte[] applyFieldAlgorithmConfiguration(String path, String method, byte[] body)
    {
        if (!"POST".equalsIgnoreCase(method) || body == null || body.length == 0 || !isJsonPath(path)) return body;
        if (!(path.startsWith("/anti-collision/") || path.startsWith("/trajectory-plan/"))) return body;
        @SuppressWarnings("unchecked") Map<String, Object> payload = JSON.parseObject(body, Map.class);
        Long fieldId = fieldId(payload);
        Map<String, Object> defaults = loadFieldDefaults(fieldId);
        nativeEngine.requireConfirmed(fieldId);
        if (path.startsWith("/anti-collision/")) {
            payload.put("mode","BOTH");
            payload.putIfAbsent("step",value(defaults,"scanStep",1.0));
            payload.putIfAbsent("thresholds",collisionThresholds(defaults));
        } else {
            Map<?,?> p=map(defaults.get("planning"));
            boolean perSegment="PER_SEGMENT".equalsIgnoreCase(textOr(payload.get("constraint_mode"),"GLOBAL"));
            Set<String> globalBounds=Set.of("max_dogleg_deg_per_30m","min_dogleg_deg_per_30m","min_segment_length","max_segment_length");
            for(String[] keys:new String[][]{{"solver","solver"},{"max_dogleg_deg_per_30m","maxDoglegDegPer30m"},{"min_dogleg_deg_per_30m","minDoglegDegPer30m"},{"min_segment_length","minSegmentLength"},{"max_segment_length","maxSegmentLength"},{"max_toolface_change_deg","maxToolfaceChangeDeg"},{"minimum_sf","minimumSf"},{"target_tolerance_m","targetToleranceM"},{"max_iterations","maxIterations"}})
                if(!(perSegment&&globalBounds.contains(keys[0]))&&p.get(keys[1])!=null)payload.putIfAbsent(keys[0],p.get(keys[1]));
            Map<String,Object> nativeOptions = new LinkedHashMap<>();
            Object configuredOptions=p.get("nativeOptions");
            if(configuredOptions instanceof Map<?,?> configured) configured.forEach((k,v)->nativeOptions.put(String.valueOf(k),v));
            nativeOptions.remove("scenario_inclination_change_deg");
            nativeOptions.remove("scenario_azimuth_change_deg");
            Object requestedOptions=payload.get("native_options");
            if(requestedOptions instanceof Map<?,?> requested) {
                // Only native geometry selectors may be changed per task; all
                // other engine budgets remain fieldId configuration values.
                if(requested.get("trajectory_mode")!=null) nativeOptions.put("trajectory_mode",requested.get("trajectory_mode"));
                if(requested.get("n_segments")!=null) nativeOptions.put("n_segments",requested.get("n_segments"));
            }
            payload.put("native_options",nativeOptions);
            // Geometry and segment layout are owned by the supplied engine
            // profile; legacy task presets cannot override this snapshot.
            payload.remove("scenario");
            payload.remove("segment_count");
            payload.put("collision_constraint","SEPARATION_FACTOR");
            int taskSeconds=(int)number(p.get("taskTimeoutSeconds"),120);
            if(taskSeconds<5||taskSeconds>300)throw new IllegalArgumentException("再规划任务时限必须为5～300秒");
            payload.put("execution_timeout_seconds",taskSeconds);
            payload.putIfAbsent("scan_step",value(defaults,"scanStep",1.0));
        }
        // Confirmed fieldId error budgets cannot be silently overridden by a task request.
        payload.put("error_model",errorModel(defaults));
        trajectoryInputs.bind(path,payload,defaults);
        // Repeated source/settings maps are values, not JSON references.
        // Python and archived snapshots require each version's full budget.
        return JSON.toJSONString(payload,com.alibaba.fastjson.serializer.SerializerFeature.DisableCircularReferenceDetect).getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> loadFieldDefaults(Long fieldId)
    {
        if (fieldId == null || fieldId <= 0) throw new IllegalArgumentException("field_id is required");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT algorithm_defaults_json FROM drilling_field_config
                 WHERE field_id=? AND status='0' AND effective_to IS NULL
                 ORDER BY config_version DESC LIMIT 1
                """, fieldId);
        if (rows.isEmpty() || rows.get(0).get("algorithm_defaults_json") == null)
            throw new IllegalArgumentException("an active fieldId configuration is required before this algorithm request");
        return DrillingAlgorithmDefaults.merged(rows.get(0).get("algorithm_defaults_json"));
    }

    private static Long fieldId(Map<String, Object> payload)
    {
        Object raw = payload.get("field_id");
        if (raw == null) raw = payload.get("fieldId");
        if (raw == null) throw new IllegalArgumentException("field_id is required");
        try { return raw instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(raw)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("field_id must be an integer"); }
    }

    private static Map<String, Object> collisionThresholds(Map<String, Object> defaults)
    {
        Map<?, ?> source = map(defaults.get("thresholds"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("red_sf", value(source, "redSf", 1.0));
        result.put("orange_sf", value(source, "orangeSf", 1.5));
        result.put("yellow_sf", value(source, "yellowSf", 2.5));
        result.put("red_distance", value(source, "redDistance", 5.0));
        result.put("error_net_ratio_orange", value(source, "errorNetRatioOrange", 0.5));
        result.put("error_net_ratio_yellow", value(source, "errorNetRatioYellow", 1.0));
        return result;
    }

    private static Map<String, Object> errorModel(Map<String, Object> defaults)
    {
        return NativeEngineSupport.modelPayload(defaults);
    }

    private static Map<String, Object> snakeCaseMap(Map<?, ?> source)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet())
        {
            String key = String.valueOf(entry.getKey());
            StringBuilder snake = new StringBuilder();
            for (int i = 0; i < key.length(); i++)
            {
                char ch = key.charAt(i);
                if (Character.isUpperCase(ch)) snake.append('_').append(Character.toLowerCase(ch));
                else snake.append(ch);
            }
            result.put(snake.toString(), entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> snakeCaseMapWithFallback(Object source, Object fallback)
    {
        Map<String, Object> result = snakeCaseMap(map(fallback));
        result.putAll(snakeCaseMap(map(source)));
        return result;
    }

    private static boolean isJsonPath(String path)
    {
        return path.startsWith("/anti-collision/") || path.startsWith("/trajectory-plan/");
    }

    private static Map<?, ?> map(Object value)
    {
        return value instanceof Map<?, ?> result ? result : Map.of();
    }

    private static Object value(Map<?, ?> map, String key, Object fallback)
    {
        return map.get(key) == null ? fallback : map.get(key);
    }

    private static void writeMultipartField(ByteArrayOutputStream output, String boundary, String name, String value)
            throws IOException
    {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + escapeHeader(name) + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeMultipartFile(ByteArrayOutputStream output, String boundary, String name, String filename,
                                           String contentType, byte[] content) throws IOException
    {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + escapeHeader(name) + "\"; filename=\""
                + escapeHeader(filename == null ? "upload.bin" : filename) + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + (contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType)
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(content == null ? new byte[0] : content);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeHeader(String value)
    {
        return String.valueOf(value).replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private String algorithmBaseUrl()
    {
        String base = algorithmUrl == null ? "" : algorithmUrl.replaceAll("/$", "");
        return base.endsWith("/api/algorithm") ? base : base + "/api/algorithm";
    }

    /**
     * The gateway is the only production entry point to FastAPI.  Keep a small
     * path-to-permission map here so a logged-in user cannot call a different
     * algorithm simply by changing the URL.  Field-level ownership checks
     * remain in the domain controllers because generic algorithm payloads may
     * contain different fieldId reference shapes.
     */
    private void authorizePath(String path, String method, byte[] body, String fieldIdParameter)
    {
        // Task, file, and inference APIs in FastAPI are worker-facing global
        // interfaces.  User-facing access must go through the Java domain
        // controllers, which bind every task/file/model to an authorized
        // fieldId before making the internal service call.
        if (path.startsWith("/internal/") || path.startsWith("/connectors/"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "internal worker endpoint");
        if ("/prediction".equals(path) || path.startsWith("/prediction/"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "use the fieldId-scoped prediction task API");
        if (path.startsWith("/train/") || path.startsWith("/files/") || "/files".equals(path)
                || "/infer".equals(path) || "/models".equals(path) || path.startsWith("/models/"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "use the fieldId-scoped training, file, model, or inference API");

        if (path.startsWith("/anti-collision"))
        {
            ShiroUtils.getSubject().checkPermission(path.startsWith("/anti-collision/tasks")
                    ? "drilling:field:view" : "drilling:collision:run");
            if ("POST".equalsIgnoreCase(method)) requireFieldRoleFromBody(body, "OWNER", "ENGINEER");
        }
        else if (path.startsWith("/trajectory-plan"))
        {
            ShiroUtils.getSubject().checkPermission(path.startsWith("/trajectory-plan/tasks")
                    ? "drilling:field:view" : "drilling:plan:run");
            if ("POST".equalsIgnoreCase(method)) requireFieldRoleFromBody(body, "OWNER", "ENGINEER");
        }
        else if (path.startsWith("/prediction"))
        {
            ShiroUtils.getSubject().checkPermission("drilling:prediction:list");
            if ("POST".equalsIgnoreCase(method) && path.startsWith("/prediction/start"))
                requireFieldRoleFromBody(body, "OWNER", "ENGINEER");
        }
        else if (path.startsWith("/train") || path.startsWith("/resources") || path.startsWith("/models"))
        {
            ShiroUtils.getSubject().checkPermission("drilling:training:list");
            if (path.startsWith("/models")) requireFieldAccessFromParameter(fieldIdParameter);
            if ("POST".equalsIgnoreCase(method) && (path.startsWith("/train/start") || path.startsWith("/train/stop")))
                ShiroUtils.getSubject().checkPermission("drilling:training:run");
        }
        else if (path.startsWith("/survey"))
        {
            if ("POST".equalsIgnoreCase(method) && path.startsWith("/survey/import"))
            {
                ShiroUtils.getSubject().checkPermission("drilling:well:survey:import");
                requireFieldRoleFromParameter(fieldIdParameter, "OWNER", "ENGINEER");
            }
            else
                ShiroUtils.getSubject().checkPermission("drilling:well:list");
        }
        else if (path.startsWith("/realtime"))
        {
            ShiroUtils.getSubject().checkPermission("drilling:well:survey:import");
            if ("POST".equalsIgnoreCase(method)) requireFieldRoleFromBody(body, "OWNER", "ENGINEER");
        }
        else if (path.startsWith("/benchmark"))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "benchmark comparison is not a system feature");
        else if (path.startsWith("/report"))
        {
            // Reports contain fieldId-derived trajectory or plan
            // data. Standalone FastAPI reports remain useful for algorithm
            // tests, but gateway exports must be bound to an accessible
            // fieldId.
            ShiroUtils.getSubject().checkPermission("drilling:field:view");
            if ("POST".equalsIgnoreCase(method)) requireFieldAccessFromBody(body);
        }
        else
        {
            ShiroUtils.getSubject().checkPermission("drilling:field:view");
        }
    }

    private void requireFieldAccessFromParameter(String rawFieldId)
    {
        if (rawFieldId == null || rawFieldId.isBlank())
            throw new IllegalArgumentException("field_id is required for model access");
        final Long fieldId;
        try { fieldId = Long.valueOf(rawFieldId); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("field_id must be an integer"); }
        requireFieldAccess(fieldId);
    }

    private void requireFieldAccess(Long fieldId)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireAccess(fieldId);
    }

    private void requireFieldRole(Long fieldId, String... allowedRoles)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireRole(fieldId, allowedRoles);
    }

    private void requireFieldRoleFromParameter(String rawFieldId, String... allowedRoles)
    {
        if (rawFieldId == null || rawFieldId.isBlank())
            throw new IllegalArgumentException("field_id is required for survey import");
        final Long fieldId;
        try { fieldId = Long.valueOf(rawFieldId); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("field_id must be an integer"); }
        requireFieldRole(fieldId, allowedRoles);
    }

    private Map<String, Object> currentImportConfig(String rawFieldId)
    {
        if (rawFieldId == null || rawFieldId.isBlank())
            throw new IllegalArgumentException("field_id is required for survey import");
        final Long fieldId;
        try { fieldId = Long.valueOf(rawFieldId); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("field_id must be an integer"); }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT coordinate_system, length_unit, angle_unit, north_reference,
                       tvd_positive, magnetic_declination, meridian_convergence,
                       origin_north, origin_east, origin_tvd
                  FROM drilling_field_config
                 WHERE field_id=? AND status='0' AND effective_to IS NULL
                 ORDER BY config_version DESC LIMIT 1
                """, fieldId);
        if (rows.isEmpty())
            throw new IllegalArgumentException("an active fieldId configuration is required before the first survey import");
        return rows.get(0);
    }

    private void requireFieldRoleFromBody(byte[] body, String... allowedRoles)
    {
        if (body == null || body.length == 0) throw new IllegalArgumentException("field_id is required");
        @SuppressWarnings("unchecked") java.util.Map<String, Object> payload = JSON.parseObject(body, java.util.Map.class);
        Object raw = payload.get("field_id");
        if (raw == null) raw = payload.get("fieldId");
        if (raw == null) throw new IllegalArgumentException("field_id is required");
        Long fieldId = optionalLong(raw);
        if (fieldId == null || fieldId <= 0)
            throw new IllegalArgumentException("field_id must be a positive integer");
        requireFieldRole(fieldId, allowedRoles);
    }

    private void requireFieldAccessFromBody(byte[] body)
    {
        if (body == null || body.length == 0) throw new IllegalArgumentException("field_id is required");
        @SuppressWarnings("unchecked") java.util.Map<String, Object> payload = JSON.parseObject(body, java.util.Map.class);
        Object raw = payload.get("field_id");
        if (raw == null) raw = payload.get("fieldId");
        Long fieldId = optionalLong(raw);
        if (fieldId == null || fieldId <= 0)
            throw new IllegalArgumentException("field_id must be a positive integer");
        requireFieldAccess(fieldId);
    }
}
