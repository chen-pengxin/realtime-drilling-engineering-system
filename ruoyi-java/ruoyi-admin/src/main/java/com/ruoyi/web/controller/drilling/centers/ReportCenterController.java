package com.ruoyi.web.controller.drilling.centers;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.utils.ShiroUtils;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import org.apache.shiro.authz.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/drilling/centers/reports")
@RequiresAuthentication
public class ReportCenterController {
  public static final String SOURCE =
      "SELECT CAST(report_id AS CHAR) record_key,report_id record_id,field_id,title,report_type"
          + " record_type,source_key,format,status,file_size,sha256,create_by operator,create_time"
          + " created_at,archived_by,archived_at FROM drilling_center_report";
  private final BusinessCenterSupport s;
  private final org.springframework.transaction.support.TransactionTemplate transactions;
  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();

  @Value("${drilling.algorithm.url:http://localhost:8000}")
  private String algorithmUrl;

  public ReportCenterController(
      BusinessCenterSupport s, org.springframework.transaction.PlatformTransactionManager manager) {
    this.s = s;
    transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
  }

  @GetMapping("/sources")
  @RequiresPermissions("drilling:reports:create")
  public Map<String, Object> sources(
      @RequestParam Long fieldId, @RequestParam Map<String, String> p) {
    String type = BusinessCenterSupport.text(p, "type", 32, true);
    if (!Set.of("SURVEY", "COLLISION", "REPLANNING", "PREDICTION", "TRAINING").contains(type))
      throw new IllegalArgumentException("来源类型无效");
    Map<String, String> params = new HashMap<>(p);
    params.put("type", type);
    return s.page(
        type.equals("SURVEY") ? VersionCenterController.SOURCE : TaskHistoryController.SOURCE,
        fieldId,
        params,
        Map.of("type", "q.record_type", "keyword", "q.title"),
        Map.of("created_at", "q.created_at"));
  }

  @GetMapping("/{id}")
  @RequiresPermissions("drilling:reports:list")
  public Map<String, Object> detail(@RequestParam Long fieldId, @PathVariable Long id) {
    s.row(SOURCE, fieldId, id.toString(), false);
    Map<String, Object> row =
        new LinkedHashMap<>(
            s.jdbc.queryForMap(
                "SELECT * FROM drilling_center_report WHERE field_id=? AND report_id=?",
                fieldId,
                id));
    row.remove("object_key");
    return row;
  }

  @GetMapping
  @RequiresPermissions("drilling:reports:list")
  public Map<String, Object> list(
      @RequestParam Long fieldId, @RequestParam Map<String, String> p) {
    return s.page(
        SOURCE,
        fieldId,
        p,
        Map.of(
            "keyword",
            "q.title",
            "type",
            "q.record_type",
            "status",
            "q.status",
            "format",
            "q.format"),
        Map.of(
            "created_at",
            "q.created_at",
            "record_id",
            "q.record_id",
            "file_size",
            "q.file_size",
            "status",
            "q.status"));
  }

  @PostMapping
  @RequiresPermissions("drilling:reports:create")
  public Map<String, Object> create(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) throws Exception {
    s.write(fieldId);
    String title = BusinessCenterSupport.text(b, "title", 128, true),
        type = BusinessCenterSupport.text(b, "type", 32, true),
        format = BusinessCenterSupport.text(b, "format", 16, true),
        key = BusinessCenterSupport.text(b, "sourceKey", 128, false);
    if (!Set.of("pdf", "docx", "xlsx", "bundle").contains(format))
      throw new IllegalArgumentException("报告格式无效");
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("field_id", fieldId);
    if (type.equals("FIELD")) {
      snapshot.put(
          "field",
          s.jdbc.queryForMap(
              "SELECT field_code,field_name FROM drilling_field WHERE field_id=?",
              fieldId));
      snapshot.put(
          "wells",
          s.jdbc.queryForList(
              "SELECT well_id,well_name,well_type,status FROM drilling_well WHERE field_id=? AND"
                  + " status<>'ARCHIVED' AND NOT EXISTS(SELECT 1 FROM drilling_engineering_node en"
                  + " WHERE en.field_id=drilling_well.field_id AND en.well_id=drilling_well.well_id"
                  + " AND en.node_type='WELL' AND en.entity_kind='PLANNED_RELIEF' AND en.status='ACTIVE')",
              fieldId));
      snapshot.put(
          "task_summary",
          s.jdbc.queryForList(
              "SELECT record_type,status,COUNT(*) count FROM ("
                  + TaskHistoryController.SOURCE
                  + ") q WHERE field_id=? GROUP BY record_type,status",
              fieldId));
      snapshot.put(
          "alarms",
          s.jdbc.queryForList(
              "SELECT alarm_code,current_level,status,COUNT(*) count FROM drilling_alarm WHERE"
                  + " field_id=? GROUP BY alarm_code,current_level,status",
              fieldId));
    } else if (type.equals("SURVEY")) {
      var detail = VersionCenterController.details(s, fieldId, key);
      if (!"SURVEY".equals(BusinessCenterSupport.object(detail.get("summary")).get("record_type")))
        throw new IllegalArgumentException("请选择测斜版本");
      snapshot.putAll(detail);
      snapshot.put(
          "trajectory",
          s.jdbc.queryForList(
              "SELECT md,inclination,azimuth,north,east,tvd,dogleg FROM drilling_survey_station"
                  + " WHERE survey_version_id=? ORDER BY md",
              BusinessCenterSupport.object(detail.get("summary")).get("record_id")));
    } else if (Set.of("COLLISION", "REPLANNING", "PREDICTION", "TRAINING").contains(type)) {
      var detail = TaskHistoryController.details(s, fieldId, key);
      if (!type.equals(BusinessCenterSupport.object(detail.get("summary")).get("record_type")))
        throw new IllegalArgumentException("报告类型与任务不匹配");
      snapshot.putAll(detail);
      var record = BusinessCenterSupport.object(detail.get("record"));
      snapshot.putAll(
          BusinessCenterSupport.object(
              record.getOrDefault("result_json", record.get("result_summary_json"))));
    } else throw new IllegalArgumentException("报告类型无效");
    byte[] bytes;
    try {
      String base = algorithmUrl.replaceAll("/$", "");
      if (!base.endsWith("/api/algorithm")) base += "/api/algorithm";
      Map<String, Object> printable = new LinkedHashMap<>(snapshot);
      if (snapshot.get("record") instanceof Map<?, ?> raw) {
        Map<String, Object> record = BusinessCenterSupport.object(raw);
        printable.remove("record");
        printable.remove("snapshot");
        printable.put("input_snapshot", snapshot.get("snapshot"));
        printable.put(
            "task_id",
            record.getOrDefault(
                "task_id", record.getOrDefault("plan_id", record.get("prediction_id"))));
      }
      var request =
          HttpRequest.newBuilder(URI.create(base + "/report/export"))
              .timeout(Duration.ofSeconds(120))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      JSON.toJSONString(
                          Map.of(
                              "title",
                              title,
                              "format",
                              format,
                              "field_id",
                              fieldId,
                              "payload",
                              printable),
                          com.alibaba.fastjson.serializer.SerializerFeature
                              .DisableCircularReferenceDetect)))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200)
        throw new IOException("report service returned " + response.statusCode());
      bytes = response.body();
      if (bytes.length == 0) throw new IOException("empty report");
    } catch (Exception e) {
      s.audit(fieldId, "REPORT_FAILED", "REPORT", key, Map.of("type", type, "format", format));
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "报告生成失败，请检查算法服务后重试");
    }
    String ext = format.equals("bundle") ? "zip" : format;
    String name = UUID.randomUUID() + "." + ext;
    Path root =
        Path.of(RuoYiConfig.getProfile(), "drilling-reports", fieldId.toString())
            .toAbsolutePath()
            .normalize();
    Files.createDirectories(root);
    Path file = root.resolve(name);
    Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
    String sha =
        HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    String contentType = contentType(ext);
    try {
      return transactions.execute(
          status -> {
            GeneratedKeyHolder holder = new GeneratedKeyHolder();
            s.jdbc.update(
                c -> {
                  var ps =
                      c.prepareStatement(
                          "INSERT INTO"
                              + " drilling_center_report(field_id,title,report_type,source_key,format,object_key,content_type,file_size,sha256,snapshot_json,create_by)"
                              + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                          java.sql.Statement.RETURN_GENERATED_KEYS);
                  ps.setObject(1, fieldId);
                  ps.setString(2, title);
                  ps.setString(3, type);
                  ps.setString(4, key);
                  ps.setString(5, format);
                  ps.setString(6, name);
                  ps.setString(7, contentType);
                  ps.setLong(8, bytes.length);
                  ps.setString(9, sha);
                  ps.setString(10, JSON.toJSONString(snapshot));
                  ps.setString(11, ShiroUtils.getLoginName());
                  return ps;
                },
                holder);
            String id = holder.getKey().toString();
            s.audit(fieldId, "CREATE", "REPORT", id, Map.of("sourceKey", key, "format", format));
            return s.row(SOURCE, fieldId, id, false);
          });
    } catch (RuntimeException e) {
      Files.deleteIfExists(file);
      throw e;
    }
  }

  @GetMapping("/{id}/download")
  @RequiresPermissions("drilling:reports:download")
  public ResponseEntity<byte[]> download(@RequestParam Long fieldId, @PathVariable Long id)
      throws IOException {
    var row = report(fieldId, id.toString());
    var bytes = Files.readAllBytes(path(fieldId, row));
    s.audit(fieldId, "DOWNLOAD", "REPORT", id.toString(), Map.of());
    return BusinessCenterSupport.attachment(
        bytes, filename(row), row.get("content_type").toString());
  }

  @PostMapping("/download")
  @RequiresPermissions("drilling:reports:download")
  public ResponseEntity<byte[]> downloadBatch(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) throws IOException {
    var rows = new ArrayList<Map<String, Object>>();
    long total = 0;
    for (String id : BusinessCenterSupport.keys(b)) {
      var r = report(fieldId, id);
      rows.add(r);
      total += ((Number) r.get("file_size")).longValue();
    }
    if (total > 256L * 1024 * 1024) throw new IllegalArgumentException("批量下载总量不能超过256 MB");
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (var r : rows) {
        zip.putNextEntry(new ZipEntry(r.get("report_id") + "-" + filename(r)));
        Files.copy(path(fieldId, r), zip);
        zip.closeEntry();
      }
    }
    s.audit(fieldId, "DOWNLOAD_BATCH", "REPORT", null, b);
    return BusinessCenterSupport.attachment(bytes.toByteArray(), "reports.zip", "application/zip");
  }

  @PostMapping("/archive")
  @RequiresPermissions("drilling:reports:archive")
  @Transactional
  public Map<String, Object> archive(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) {
    s.write(fieldId);
    var ids = BusinessCenterSupport.keys(b);
    for (String id : ids) {
      s.jdbc.queryForList(
          "SELECT report_id FROM drilling_center_report WHERE field_id=? AND report_id=? FOR"
              + " UPDATE",
          fieldId,
          id);
      s.row(SOURCE, fieldId, id, false);
    }
    for (String id : ids) {
      s.jdbc.update(
          "UPDATE drilling_center_report SET status='ARCHIVED',archived_by=?,archived_at=NOW()"
              + " WHERE field_id=? AND report_id=? AND status='READY'",
          ShiroUtils.getLoginName(),
          fieldId,
          id);
      s.audit(fieldId, "ARCHIVE", "REPORT", id, Map.of());
    }
    return Map.of("code", 200, "count", ids.size());
  }

  private Map<String, Object> report(Long p, String id) {
    s.access(p);
    var r =
        s.jdbc.queryForList(
            "SELECT * FROM drilling_center_report WHERE field_id=? AND report_id=? AND"
                + " status='READY'",
            p,
            id);
    if (r.isEmpty()) throw new IllegalArgumentException("报告不存在或已归档");
    return r.get(0);
  }

  private Path path(Long p, Map<String, Object> r) {
    Path
        root =
            Path.of(RuoYiConfig.getProfile(), "drilling-reports", p.toString())
                .toAbsolutePath()
                .normalize(),
        path = root.resolve(r.get("object_key").toString()).normalize();
    if (!path.startsWith(root)) throw new IllegalArgumentException("报告路径无效");
    return path;
  }

  private static String filename(Map<String, Object> r) {
    return r.get("title").toString().replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_")
        + "."
        + (r.get("format").equals("bundle") ? "zip" : r.get("format"));
  }

  private static String contentType(String ext) {
    return switch (ext) {
      case "pdf" -> "application/pdf";
      case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
      default -> "application/zip";
    };
  }
}
