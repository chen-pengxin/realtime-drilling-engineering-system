package com.ruoyi.web.controller.drilling;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.alibaba.fastjson.JSON;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.io.InputStreamResource;

import com.ruoyi.common.utils.ShiroUtils;

/** Field-scoped model metadata and authorized artifact downloads. */
@RestController
@RequestMapping("/api/drilling/fields/{fieldId}/models")
@RequiresAuthentication
public class ModelRepositoryController
{
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private EngineeringConfigService engineeringConfigs;
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.web.controller.drilling.centers.BusinessEventService versionEvents;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${drilling.algorithm.url:http://localhost:8000}")
    private String algorithmUrl;

    private final IncrementalLearningService learning;
    public ModelRepositoryController(JdbcTemplate jdbc, IncrementalLearningService learning) { this.jdbc = jdbc; this.learning = learning; }

    @GetMapping("/incremental-learning")
    @RequiresPermissions("drilling:model:list")
    public Map<String,Object> learning(@PathVariable Long fieldId) {
        requireFieldAccess(fieldId); return learning.get(fieldId);
    }

    @org.springframework.web.bind.annotation.PutMapping("/incremental-learning")
    @RequiresPermissions("drilling:training:run")
    public Map<String,Object> updateLearning(@PathVariable Long fieldId,@RequestBody Map<String,Object> body) {
        requireEngineer(fieldId); return learning.save(fieldId,body);
    }

    @PostMapping("/register")
    @RequiresPermissions("drilling:training:run")
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> register(@PathVariable Long fieldId,@RequestBody Map<String,Object> body) throws Exception {
        requireEngineer(fieldId);
        String name=String.valueOf(body.getOrDefault("modelName","")).trim();
        if(name.isEmpty() || name.length()>100)throw new IllegalArgumentException("模型名称需要为 1–100 个字符");
        Map<String,Object> source=jdbc.queryForMap("SELECT * FROM data_file WHERE file_id=? AND field_id=? AND file_category='MODEL' AND status='ACTIVE'",body.get("fileId"),fieldId);
        Map<String,Object> model=learning.call("POST","/models/import",Map.of("field_id",fieldId,"source_path",source.get("object_key")));
        jdbc.queryForList("SELECT field_id FROM drilling_field WHERE field_id=? FOR UPDATE",fieldId);
        Integer version=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM model_version WHERE field_id=? AND model_name=?",Integer.class,fieldId,name);
        jdbc.update("""
            INSERT INTO data_file(field_id,file_category,business_type,original_name,object_key,content_type,file_size,sha256,parent_file_id,create_by)
            VALUES (?,'MODEL','IMPORTED_PREDICTION_MODEL',?,?,?,?,?,?,?)
            """,fieldId,source.get("original_name"),model.get("uri"),source.get("content_type"),model.get("size_bytes"),model.get("sha256"),source.get("file_id"),ShiroUtils.getLoginName());
        Long fileId=jdbc.queryForObject("SELECT file_id FROM data_file WHERE object_key=?",Long.class,model.get("uri"));
        jdbc.update("INSERT INTO model_version(field_id,model_name,version_no,file_id,status,create_by) VALUES (?,?,?,?,'READY',?)",fieldId,name,version,fileId,ShiroUtils.getLoginName());
        Long modelId=jdbc.queryForObject("SELECT model_version_id FROM model_version WHERE field_id=? AND model_name=? AND version_no=?",Long.class,fieldId,name,version);
        versionEvents.versionChange(fieldId,ShiroUtils.getLoginName(),"REGISTER","MODEL","MODEL:"+modelId,Map.of("model_name",name,"version_no",version,"file_id",fileId));
        return jdbc.queryForMap("SELECT * FROM model_version WHERE field_id=? AND model_name=? AND version_no=?",fieldId,name,version);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{modelVersionId}")
    @RequiresPermissions("drilling:training:run")
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> archive(@PathVariable Long fieldId,@PathVariable Long modelVersionId) {
        requireEngineer(fieldId);
        jdbc.queryForList("SELECT field_id FROM drilling_field WHERE field_id=? FOR UPDATE",fieldId);
        Map<String,Object> current=jdbc.queryForMap("SELECT algorithm_defaults_json FROM drilling_field_config WHERE field_id=? AND status='0' AND effective_to IS NULL ORDER BY config_version DESC LIMIT 1",fieldId);
        Map<String,Object> prediction=IncrementalLearningService.object(engineeringConfigs.defaults().get("prediction"));
        if(String.valueOf(modelVersionId).equals(String.valueOf(prediction.get("modelVersionId"))))throw new IllegalArgumentException("请先切换正在使用的预测模型");
        if(jdbc.update("UPDATE model_version SET status='ARCHIVED' WHERE field_id=? AND model_version_id=?",fieldId,modelVersionId)==0)throw new IllegalArgumentException("model not found");
        versionEvents.versionChange(fieldId,ShiroUtils.getLoginName(),"ARCHIVE","MODEL","MODEL:"+modelVersionId,Map.of("status","ARCHIVED"));
        return Map.of("archived",true);
    }

    private void requireEngineer(Long fieldId) {
        com.ruoyi.framework.security.DrillingDataScope.current().requireRole(fieldId,"OWNER","ENGINEER");
    }

    @GetMapping
    @RequiresPermissions("drilling:model:list")
    public Map<String, Object> list(@PathVariable Long fieldId,
                                    @RequestParam(required = false) String status)
    {
        requireFieldAccess(fieldId);
        String normalized = status == null || status.isBlank() ? "READY" : status.toUpperCase(Locale.ROOT);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT mv.model_version_id, mv.field_id, mv.model_name, mv.version_no,
                   mv.file_id, mv.metrics_json, mv.status, mv.create_by, mv.create_time,
                   f.original_name, f.object_key, f.content_type, f.file_size, f.sha256
              FROM model_version mv
              JOIN data_file f ON f.file_id=mv.file_id
             WHERE mv.field_id=? AND mv.status=? AND f.status='ACTIVE'
             ORDER BY mv.model_name, mv.version_no DESC, mv.model_version_id DESC
            """, fieldId, normalized);
        for (Map<String, Object> row : rows)
        {
            String objectKey = row.get("object_key") == null ? "" : String.valueOf(row.get("object_key"));
            row.put("path", artifactName(objectKey));
            row.put("name", row.get("original_name"));
            // Keep the API shape consumed by the Vue table independent from
            // the database column naming used by data_file.
            row.put("size_bytes", row.get("file_size"));
            row.put("download_url", "/api/drilling/fields/" + fieldId + "/models/"
                    + row.get("model_version_id") + "/download");
        }
        return Map.of("models", rows);
    }

    @GetMapping("/{modelVersionId}/download")
    @RequiresPermissions("drilling:model:list")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long fieldId,
                                                          @PathVariable Long modelVersionId) throws IOException
    {
        requireFieldAccess(fieldId);
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT f.original_name, f.object_key, f.content_type
              FROM model_version mv JOIN data_file f ON f.file_id=mv.file_id
             WHERE mv.model_version_id=? AND mv.field_id=? AND mv.status='READY' AND f.status='ACTIVE'
            """, modelVersionId, fieldId);
        String artifact = artifactName(String.valueOf(row.get("object_key")));
        if (artifact.isBlank()) throw new IllegalArgumentException("model artifact is missing");
        String target = algorithmBaseUrl() + "/models/"
                + URLEncoder.encode(artifact, StandardCharsets.UTF_8).replace("+", "%20")
                + "?field_id=" + fieldId;
        HttpURLConnection connection = (HttpURLConnection) URI.create(target).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10 * 60 * 1000);
        connection.setRequestProperty("Accept", "application/zip");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300)
        {
            connection.disconnect();
            return ResponseEntity.status(status).build();
        }
        String originalName = row.get("original_name") == null ? artifact : String.valueOf(row.get("original_name"));
        String contentType = row.get("content_type") == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : String.valueOf(row.get("content_type"));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(originalName).build().toString());
        try { headers.setContentType(MediaType.parseMediaType(contentType)); }
        catch (IllegalArgumentException ignored) { headers.setContentType(MediaType.APPLICATION_OCTET_STREAM); }
        long length = connection.getContentLengthLong();
        if (length >= 0) headers.setContentLength(length);
        InputStream stream=connection.getInputStream();
        // Use a regular resource response so the Shiro filter does not need
        // to restore a security context during Spring's async dispatch.
        InputStreamResource resource=new InputStreamResource(new FilterInputStream(stream) {
            @Override public void close() throws IOException {
                try { super.close(); } finally { connection.disconnect(); }
            }
        });
        return ResponseEntity.status(status).headers(headers).body(resource);
    }

    /**
     * Field-scoped inference smoke test.  Java authorizes the model and
     * forwards the model-specific script request to FastAPI; it never imports
     * a ML runtime or executes a user script itself.
     */
    @PostMapping("/{modelVersionId}/infer-test")
    @RequiresPermissions("drilling:model:list")
    public ResponseEntity<String> inferTest(@PathVariable Long fieldId,
                                            @PathVariable Long modelVersionId,
                                            @RequestBody Map<String, Object> body) throws IOException, InterruptedException
    {
        requireFieldAccess(fieldId);
        return infer(modelVersionId,fieldId,body);
    }

    private ResponseEntity<String> infer(Long modelVersionId,Long fieldId,Map<String,Object> body) throws IOException,InterruptedException {
        Map<String, Object> model = jdbc.queryForMap("""
            SELECT f.object_key, f.status AS file_status, mv.status AS model_status
              FROM model_version mv JOIN data_file f ON f.file_id=mv.file_id
             WHERE mv.model_version_id=? AND (? IS NULL OR mv.field_id=?)
            """, modelVersionId, fieldId, fieldId);
        if (!"READY".equalsIgnoreCase(String.valueOf(model.get("model_status")))
                || !"ACTIVE".equalsIgnoreCase(String.valueOf(model.get("file_status"))))
            throw new IllegalArgumentException("model version is not ready");
        String script = text(body.get("script"));
        if (script == null || script.isBlank() || script.contains("..") || script.startsWith("/")
                || script.startsWith("\\") || !script.toLowerCase(Locale.ROOT).endsWith(".py"))
            throw new IllegalArgumentException("script must be a safe relative .py path");
        Integer registeredTemplate = jdbc.queryForObject("""
            SELECT COUNT(*) FROM training_template t
              JOIN data_file f ON f.file_id=t.file_id
             WHERE (? IS NULL OR t.field_id=?) AND t.runtime_path=? AND t.status='ACTIVE'
               AND f.field_id=t.field_id AND f.file_category='TEMPLATE'
               AND f.is_template='1' AND f.status='ACTIVE'
            """, Integer.class, fieldId, fieldId, script);
        if (registeredTemplate == null || registeredTemplate == 0)
            throw new IllegalArgumentException("inference script must be an active template registered in this field");
        Map<String, Object> forwarded = new java.util.HashMap<>();
        forwarded.put("model_path", artifactName(String.valueOf(model.get("object_key"))));
        forwarded.put("script", script);
        forwarded.put("input", body.get("input") instanceof Map<?, ?> ? body.get("input") : Map.of());
        forwarded.put("device", text(body.get("device")) == null ? "cpu" : text(body.get("device")));
        Object timeout = body.get("timeout_seconds");
        forwarded.put("timeout_seconds", timeout == null ? 1 : timeout);
        HttpRequest request = HttpRequest.newBuilder(URI.create(algorithmBaseUrl() + "/infer"))
                .timeout(Duration.ofMinutes(6)).header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(forwarded))).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return ResponseEntity.status(response.statusCode()).contentType(MediaType.APPLICATION_JSON).body(response.body());
    }

    // Called only by an authorized prediction task, after its source version is bound.
    // A shared engineering model is usable from every authorized wellbore.
    ResponseEntity<String> inferConfigured(Long modelId, Map<String,Object> body) throws IOException, InterruptedException {
        return infer(modelId,null,body);
    }

    private String algorithmBaseUrl()
    {
        String base = algorithmUrl == null ? "" : algorithmUrl.replaceAll("/$", "");
        return base.endsWith("/api/algorithm") ? base : base + "/api/algorithm";
    }

    private void requireFieldAccess(Long fieldId)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireAccess(fieldId);
    }

    private static String artifactName(String value)
    {
        String normalized = value == null ? "" : value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String text(Object value)
    {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isBlank() ? null : result;
    }
}
