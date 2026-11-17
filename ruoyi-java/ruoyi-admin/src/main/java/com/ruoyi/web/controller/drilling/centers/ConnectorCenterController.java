package com.ruoyi.web.controller.drilling.centers;

import static com.ruoyi.web.controller.drilling.centers.BusinessCenterSupport.*;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingAccountPolicy;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.web.controller.drilling.AlgorithmGatewayController;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.*;
import org.apache.shiro.subject.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drilling/centers/connectors")
public class ConnectorCenterController {
  public static final String SOURCE =
      """
      SELECT CAST(c.connector_id AS CHAR) record_key,c.connector_id record_id,c.field_id,c.well_id,w.well_name,
        c.connector_name title,c.connector_type record_type,c.endpoint_uri,c.status,c.last_heartbeat,c.last_error,
        c.create_by operator,c.create_time created_at,c.update_time,
        IF(c.credential_ref IS NULL OR c.credential_ref='',FALSE,TRUE) credential_configured
        FROM drilling_realtime_connector c LEFT JOIN drilling_well w ON w.well_id=c.well_id
      """;
  private final BusinessCenterSupport s;
  private final BusinessEventService events;
  private final AlgorithmGatewayController gateway;
  private final DrillingAccountPolicy accounts;
  private final ISysUserService users;
  private final Set<Long> running = ConcurrentHashMap.newKeySet();
  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();

  @Value("${drilling.algorithm.url:http://localhost:8000}")
  private String algorithmUrl;

  @Value("${drilling.connector.token:${DRILLING_CONNECTOR_TOKEN:}}")
  private String workerToken;

  private static final Set<String> TYPES =
      Set.of(
          "WITS_TCP",
          "SERIAL",
          "WITSML_1_4_1",
          "WITSML_2_0",
          "CSV",
          "EXCEL",
          "MYSQL",
          "SQL_SERVER");

  public ConnectorCenterController(
      BusinessCenterSupport s,
      BusinessEventService events,
      AlgorithmGatewayController gateway,
      DrillingAccountPolicy accounts,
      ISysUserService users) {
    this.s = s;
    this.events = events;
    this.gateway = gateway;
    this.accounts = accounts;
    this.users = users;
  }

  @GetMapping("/files")
  @RequiresPermissions("drilling:connectors:list")
  public List<Map<String, Object>> files(@RequestParam Long fieldId) {
    s.access(fieldId);
    return s.jdbc.queryForList(
        "SELECT file_id,original_name,display_name,file_size FROM data_file WHERE field_id=? AND"
            + " status='ACTIVE' AND file_category IN ('SURVEY','DATASET') ORDER BY file_id DESC",
        fieldId);
  }

  @GetMapping
  @RequiresPermissions("drilling:connectors:list")
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
            "wellId",
            "q.well_id"),
        Map.of(
            "created_at",
            "q.created_at",
            "record_id",
            "q.record_id",
            "status",
            "q.status",
            "last_heartbeat",
            "q.last_heartbeat"));
  }

  @GetMapping("/{id}")
  @RequiresPermissions("drilling:connectors:list")
  public Map<String, Object> detail(@RequestParam Long fieldId, @PathVariable Long id) {
    s.row(SOURCE, fieldId, id.toString(), false);
    var r = new LinkedHashMap<>(load(fieldId, id));
    r.remove("credential_ref");
    r.put("config", object(r.remove("config_json")));
    r.put("cursor", object(r.remove("cursor_json")));
    r.put("credentialConfigured", load(fieldId, id).get("credential_ref") != null);
    r.put(
        "runs",
        s.jdbc.queryForList(
            "SELECT run_id,outcome,record_count,survey_version_id,message,create_by,create_time"
                + " FROM drilling_connector_run WHERE connector_id=? AND field_id=? ORDER BY"
                + " run_id DESC LIMIT 100",
            id,
            fieldId));
    return r;
  }

  @PostMapping
  @RequiresPermissions("drilling:connectors:save")
  @Transactional
  public Map<String, Object> save(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) {
    s.write(fieldId);
    Long id = b.get("id") == null ? null : Long.valueOf(b.get("id").toString());
    Map<String, Object> previous = id == null ? Map.of() : load(fieldId, id);
    if (id != null && running.contains(id)) throw new IllegalArgumentException("连接器仍在完成本次读取，请稍后修改");
    if (id != null && !"DISABLED".equals(previous.get("status")))
      throw new IllegalArgumentException("请先停用连接器再修改配置");
    String name = text(b, "name", 128, true),
        type = text(b, "type", 32, true),
        endpoint = text(b, "endpoint", 512, false),
        credential = text(b, "credentialRef", 255, false);
    if (!TYPES.contains(type)) throw new IllegalArgumentException("连接器类型无效");
    if (endpoint.matches("(?i).*(password|passwd|pwd|token|secret|authorization)[=:].*")
        || endpoint.matches(".*://[^/]*@.*"))
      throw new IllegalArgumentException("连接地址不能包含账号或密钥，请使用服务端环境变量引用");
    if (!credential.isEmpty() && !credential.matches("env:[A-Z][A-Z0-9_]{2,100}"))
      throw new IllegalArgumentException("凭据必须使用 env:环境变量名 引用");
    Long well = com.ruoyi.framework.security.DrillingDataScope.number(b.get("wellId"));
    if (well == null
        || s.jdbc.queryForObject(
                "SELECT COUNT(*) FROM drilling_well WHERE field_id=? AND well_id=? AND"
                    + " status<>'ARCHIVED'",
                Integer.class,
                fieldId,
                well)
            == 0) throw new IllegalArgumentException("请选择当前井场的有效井");
    Map<String, Object> c = object(b.get("config"));
    Set<String> allowed =
        Set.of(
            "pollSeconds",
            "timeoutSeconds",
            "batchSize",
            "reorderWindow",
            "wellboreId",
            "baudrate",
            "table",
            "mdColumn",
            "incColumn",
            "aziColumn",
            "username",
            "fileId");
    if (!allowed.containsAll(c.keySet())) throw new IllegalArgumentException("配置包含不支持的字段或明文凭据");
    Long wellbore=com.ruoyi.framework.security.DrillingDataScope.number(c.get("wellboreId"));
    if(wellbore==null) {
      var candidates=s.jdbc.queryForList("SELECT b.wellbore_id FROM drilling_wellbore b JOIN drilling_field_wellbore x ON x.wellbore_id=b.wellbore_id WHERE x.field_id=? AND b.well_id=?",fieldId,well);
      if(candidates.size()!=1)throw new IllegalArgumentException("请为连接器明确选择接入井眼");
      wellbore=com.ruoyi.framework.security.DrillingDataScope.number(candidates.get(0).get("wellbore_id"));
    }
    if(s.jdbc.queryForObject("SELECT COUNT(*) FROM drilling_field_wellbore x JOIN drilling_wellbore b ON b.wellbore_id=x.wellbore_id WHERE x.field_id=? AND b.well_id=? AND b.wellbore_id=?",Integer.class,fieldId,well,wellbore)!=1)throw new IllegalArgumentException("连接器井眼与井场或井不符");
    c.put("wellboreId",wellbore);
    c.put("pollSeconds", integer(c.get("pollSeconds"), 30, 5, 3600));
    c.put("timeoutSeconds", integer(c.get("timeoutSeconds"), 5, 1, 10));
    c.put("batchSize", integer(c.get("batchSize"), 500, 1, 2000));
    c.put("baudrate", integer(c.get("baudrate"), 9600, 1200, 921600));
    double window =
        c.get("reorderWindow") == null ? 3 : Double.parseDouble(c.get("reorderWindow").toString());
    if (!Double.isFinite(window) || window < 0 || window > 100)
      throw new IllegalArgumentException("乱序窗口应在0～100 m之间");
    c.put("reorderWindow", window);
    if (Set.of("MYSQL", "SQL_SERVER").contains(type))
      for (String field : List.of("table", "mdColumn", "incColumn", "aziColumn")) {
        String value =
            Objects.toString(
                c.get(field),
                switch (field) {
                  case "mdColumn" -> "md";
                  case "incColumn" -> "inclination";
                  case "aziColumn" -> "azimuth";
                  default -> "";
                });
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
          throw new IllegalArgumentException("SQL表名和字段名只能使用简单标识符");
        c.put(field, value);
      }
    if (type.equals("MYSQL")
        && !endpoint.matches("jdbc:mysql://[A-Za-z0-9.\\-]+:[0-9]+/[A-Za-z0-9_]+"))
      throw new IllegalArgumentException("MySQL地址格式为jdbc:mysql://主机:端口/数据库，不允许附加连接参数");
    if (type.startsWith("WITSML")) {
      URI u = URI.create(endpoint);
      if (!Set.of("http", "https").contains(Objects.toString(u.getScheme(), ""))
          || u.getHost() == null) throw new IllegalArgumentException("请配置HTTP(S)轨迹XML接口");
    }
    if (type.equals("WITS_TCP")) {
      URI u = URI.create(endpoint);
      if (!"tcp".equals(u.getScheme()) || u.getHost() == null || u.getPort() < 1)
        throw new IllegalArgumentException("TCP地址格式为tcp://主机:端口");
    }
    if (type.equals("SERIAL")
        && !(endpoint.startsWith("/dev/") || endpoint.matches("(?i)COM[0-9]+")))
      throw new IllegalArgumentException("串口应为/dev/...或COM端口");
    if (Set.of("CSV", "EXCEL").contains(type)) {
      Long file = com.ruoyi.framework.security.DrillingDataScope.number(c.get("fileId"));
      if (file == null
          || s.jdbc.queryForObject(
                  "SELECT COUNT(*) FROM data_file WHERE field_id=? AND file_id=? AND"
                      + " status='ACTIVE' AND file_category IN ('SURVEY','DATASET')",
                  Integer.class,
                  fieldId,
                  file)
              == 0) throw new IllegalArgumentException("请选择当前井场的有效数据文件");
    }
    String ref =
        credential.isEmpty() ? Objects.toString(previous.get("credential_ref"), null) : credential;
    if (Boolean.TRUE.equals(b.get("clearCredential"))) ref = null;
    if (id == null) {
      s.jdbc.update(
          "INSERT INTO"
              + " drilling_realtime_connector(field_id,well_id,connector_name,connector_type,endpoint_uri,credential_ref,config_json,create_by)"
              + " VALUES(?,?,?,?,?,?,?,?)",
          fieldId,
          well,
          name,
          type,
          endpoint,
          ref,
          JSON.toJSONString(c),
          ShiroUtils.getLoginName());
      id =
          s.jdbc.queryForObject(
              "SELECT connector_id FROM drilling_realtime_connector WHERE field_id=? AND"
                  + " connector_name=?",
              Long.class,
              fieldId,
              name);
    } else
      s.jdbc.update(
          "UPDATE drilling_realtime_connector SET"
              + " well_id=?,connector_name=?,connector_type=?,endpoint_uri=?,credential_ref=?,config_json=?,cursor_json=NULL,last_error=NULL,update_by=?,update_time=NOW()"
              + " WHERE field_id=? AND connector_id=?",
          well,
          name,
          type,
          endpoint,
          ref,
          JSON.toJSONString(c),
          ShiroUtils.getLoginName(),
          fieldId,
          id);
    s.audit(
        fieldId,
        "SAVE",
        "CONNECTOR",
        id.toString(),
        Map.of("type", type, "credentialConfigured", ref != null));
    return Map.of("code", 200, "id", id);
  }

  @PostMapping("/state")
  @RequiresPermissions("drilling:connectors:save")
  @Transactional
  public Map<String, Object> state(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) {
    s.write(fieldId);
    String action = text(b, "action", 16, true);
    if (!Set.of("enable", "disable").contains(action)) throw new IllegalArgumentException("操作无效");
    var ids = keys(b);
    for (String id : ids) {
      s.jdbc.queryForList(
          "SELECT connector_id FROM drilling_realtime_connector WHERE field_id=? AND"
              + " connector_id=? FOR UPDATE",
          fieldId,
          id);
      s.row(SOURCE, fieldId, id, false);
    }
    if (action.equals("enable")) ShiroUtils.getSubject().checkPermission("drilling:connectors:run");
    for (String id : ids) {
      s.jdbc.update(
          "UPDATE drilling_realtime_connector SET"
              + " status=?,last_error=NULL,update_by=?,update_time=NOW() WHERE field_id=? AND"
              + " connector_id=?",
          action.equals("enable") ? "ENABLED" : "DISABLED",
          ShiroUtils.getLoginName(),
          fieldId,
          id);
      s.audit(fieldId, action.toUpperCase(), "CONNECTOR", id, Map.of());
    }
    return Map.of("code", 200, "count", ids.size());
  }

  @PostMapping("/poll")
  @RequiresPermissions("drilling:connectors:run")
  public Map<String, Object> poll(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) {
    s.write(fieldId);
    List<Map<String, Object>> results = new ArrayList<>();
    var ids = keys(b);
    if (ids.size() > 5) throw new IllegalArgumentException("立即读取每批最多5个连接器；启用后由后台持续轮询");
    for (String id : ids) s.row(SOURCE, fieldId, id, false);
    for (String id : ids) results.add(run(fieldId, Long.valueOf(id), ShiroUtils.getLoginName()));
    return Map.of("code", 200, "results", results);
  }

  @PostMapping("/export")
  @RequiresPermissions("drilling:connectors:export")
  public ResponseEntity<byte[]> export(
      @RequestParam Long fieldId, @RequestBody Map<String, Object> b) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String id : keys(b)) rows.add(s.row(SOURCE, fieldId, id, false));
    s.audit(fieldId, "EXPORT", "CONNECTOR", null, b);
    return csv(rows, "connectors.csv");
  }

  private Map<String, Object> load(Long fieldId, Long id) {
    var rows =
        s.jdbc.queryForList(
            "SELECT * FROM drilling_realtime_connector WHERE connector_id=? AND field_id=?",
            id,
            fieldId);
    if (rows.isEmpty()) throw new IllegalArgumentException("连接器不存在或不属于当前井场");
    return rows.get(0);
  }

  private Map<String, Object> run(Long fieldId, Long id, String actor) {
    if (!running.add(id)) return Map.of("id", id, "outcome", "BUSY");
    try {
      var c = load(fieldId, id);
      if ("DISABLED".equals(c.get("status"))) return Map.of("id", id, "outcome", "DISABLED");
      Map<String, Object> config = object(c.get("config_json")),
          cursor = object(c.get("cursor_json"));
      String credential = "";
      if (c.get("credential_ref") != null) {
        String ref = c.get("credential_ref").toString();
        if (!ref.startsWith("env:"))
          throw new IllegalArgumentException("credential reference not configured");
        credential = Objects.toString(System.getenv(ref.substring(4)), "");
        if (credential.isEmpty())
          throw new IllegalArgumentException("credential environment variable missing");
      }
      List<Map<String, Object>> records;
      Map<String, Object> nextCursor;
      if (cursor.get("pendingRecords") instanceof List<?> pending) {
        records = pending.stream().map(BusinessCenterSupport::object).toList();
        nextCursor = object(cursor.get("nextCursor"));
      } else {
        Map<String, Object> answer;
        if ("MYSQL".equals(c.get("connector_type")))
          answer = readMysql(c, config, cursor, credential);
        else {
          Map<String, Object> request = new LinkedHashMap<>();
          request.put("type", c.get("connector_type"));
          request.put("endpoint", c.get("endpoint_uri"));
          request.put("config", config);
          request.put("cursor", cursor);
          request.put("credential", credential);
          if (Set.of("CSV", "EXCEL").contains(c.get("connector_type"))) {
            var file =
                s.jdbc.queryForMap(
                    "SELECT object_key,original_name,file_size FROM data_file WHERE field_id=?"
                        + " AND file_id=? AND status='ACTIVE'",
                    fieldId,
                    config.get("fileId"));
            if (((Number) file.get("file_size")).longValue() > 8 * 1024 * 1024)
              throw new IllegalArgumentException("source file exceeds 8 MB");
            Path
                root =
                    Path.of(RuoYiConfig.getProfile(), "drilling-files", fieldId.toString())
                        .toAbsolutePath()
                        .normalize(),
                path =
                    Path.of(RuoYiConfig.getProfile())
                        .resolve(file.get("object_key").toString())
                        .toAbsolutePath()
                        .normalize();
            if (!path.startsWith(root)) throw new IllegalArgumentException("file path invalid");
            request.put(
                "contentBase64", Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            request.put("suffix", file.get("original_name").toString().replaceAll("^.*\\.", ""));
          }
          String base = algorithmUrl.replaceAll("/api/algorithm/?$", "").replaceAll("/$", "");
          var call =
              HttpRequest.newBuilder(URI.create(base + "/internal/connectors/read"))
                  .timeout(Duration.ofSeconds(30))
                  .header("Content-Type", "application/json")
                  .header("X-Connector-Token", workerToken)
                  .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(request)))
                  .build();
          var response = client.send(call, HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() != 200)
            throw new IllegalArgumentException(
                "connector worker returned HTTP " + response.statusCode());
          answer = object(response.body());
        }
        records =
            ((List<?>) answer.getOrDefault("records", List.of()))
                .stream().map(BusinessCenterSupport::object).toList();
        nextCursor = object(answer.get("cursor"));
        // Persist received transport data before normalization. Retry resumes this batch, not the
        // live socket.
        s.jdbc.update(
            "UPDATE drilling_realtime_connector SET cursor_json=? WHERE connector_id=?",
            JSON.toJSONString(Map.of("pendingRecords", records, "nextCursor", nextCursor)),
            id);
      }
      Map<String, Object> result =
          records.isEmpty()
              ? Map.of()
              : gateway.ingestConnectorBatch(
                  Map.of(
                      "field_id",
                      fieldId,
                      "well_id",
                      c.get("well_id"),
                      "wellbore_id",
                      config.getOrDefault("wellboreId",0),
                      "source",
                      "CONNECTOR:" + id,
                      "events",
                      records,
                      "reorder_window",
                      config.getOrDefault("reorderWindow", 3),
                      "flush",
                      true),
                  actor);
      // A concurrent disable takes precedence over completion of an in-flight read.
      s.jdbc.update(
          "UPDATE drilling_realtime_connector SET"
              + " status=IF(status='DISABLED','DISABLED','CONNECTED'),cursor_json=?,last_heartbeat=NOW(),last_error=NULL"
              + " WHERE connector_id=?",
          JSON.toJSONString(nextCursor),
          id);
      s.jdbc.update(
          "INSERT INTO"
              + " drilling_connector_run(connector_id,field_id,outcome,record_count,survey_version_id,message,create_by)"
              + " VALUES(?,?,'SUCCESS',?,?,?,?)",
          id,
          fieldId,
          records.size(),
          result.get("persisted_survey_version_id"),
          "已读取并完成持久化检查",
          actor);
      return Map.of("id", id, "outcome", "SUCCESS", "count", records.size());
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      String message = "接入失败（" + e.getClass().getSimpleName() + "），请检查服务、协议、凭据引用及基线测斜版本";
      var c = load(fieldId, id);
      Map<String, Object> cursor = object(c.get("cursor_json"));
      int failures = integer(cursor.get("failures"), 0, 0, 1000000) + 1;
      cursor.put("failures", failures);
      cursor.put(
          "nextAttemptAt",
          System.currentTimeMillis() + Math.min(300, 5L * (1L << Math.min(failures, 6))) * 1000);
      s.jdbc.update(
          "UPDATE drilling_realtime_connector SET"
              + " status=IF(status='DISABLED','DISABLED','RECONNECTING'),last_error=?,cursor_json=?"
              + " WHERE connector_id=?",
          message,
          JSON.toJSONString(cursor),
          id);
      s.jdbc.update(
          "INSERT INTO drilling_connector_run(connector_id,field_id,outcome,message,create_by)"
              + " VALUES(?,?,'FAILED',?,?)",
          id,
          fieldId,
          message,
          actor);
      events.alert(
          fieldId,
          BusinessEventService.number(c.get("well_id")),
          "CONNECTION_FAILURE",
          "CONNECTOR_RUN:" + id + ":" + failures,
          "连接异常：" + c.get("connector_name"),
          Map.of("connector_id", id, "error", message));
      return Map.of("id", id, "outcome", "FAILED", "message", message);
    } finally {
      running.remove(id);
    }
  }

  private Map<String, Object> readMysql(
      Map<String, Object> c,
      Map<String, Object> config,
      Map<String, Object> cursor,
      String password)
      throws Exception {
    String table = config.get("table").toString(),
        md = config.get("mdColumn").toString(),
        inc = config.get("incColumn").toString(),
        azi = config.get("aziColumn").toString();
    for (String name : List.of(table, md, inc, azi))
      if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
        throw new IllegalArgumentException("SQL identifier invalid");
    Properties props = new Properties();
    props.setProperty("user", Objects.toString(config.get("username"), ""));
    props.setProperty("password", password);
    props.setProperty("connectTimeout", "5000");
    props.setProperty("socketTimeout", "10000");
    props.setProperty("allowMultiQueries", "false");
    List<Map<String, Object>> records = new ArrayList<>();
    double last = cursor.get("md") == null ? -1 : Double.parseDouble(cursor.get("md").toString());
    try (Connection conn = DriverManager.getConnection(c.get("endpoint_uri").toString(), props)) {
      conn.setReadOnly(true);
      try (PreparedStatement ps =
          conn.prepareStatement(
              "SELECT `"
                  + md
                  + "`,`"
                  + inc
                  + "`,`"
                  + azi
                  + "` FROM `"
                  + table
                  + "` WHERE `"
                  + md
                  + "`>? ORDER BY `"
                  + md
                  + "` LIMIT ?")) {
        ps.setDouble(1, last);
        ps.setInt(2, integer(config.get("batchSize"), 500, 1, 2000));
        ps.setQueryTimeout(10);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("md", rs.getObject(1));
            row.put("inclination", rs.getObject(2));
            row.put("azimuth", rs.getObject(3));
            records.add(row);
            last = rs.getDouble(1);
          }
        }
      }
    }
    return Map.of("records", records, "cursor", Map.of("md", last));
  }

  @Scheduled(initialDelay = 20000, fixedDelay = 5000)
  public void pollEnabled() {
    for (var c :
        s.jdbc.queryForList(
            "SELECT * FROM drilling_realtime_connector WHERE status IN"
                + " ('ENABLED','CONNECTED','RECONNECTING')")) {
      Long fieldId = BusinessEventService.number(c.get("field_id")),
          id = BusinessEventService.number(c.get("connector_id"));
      Map<String, Object> cursor = object(c.get("cursor_json")),
          config = object(c.get("config_json"));
      long now = System.currentTimeMillis();
      if (cursor.get("nextAttemptAt") instanceof Number n && n.longValue() > now) continue;
      java.util.Date heartbeat = c.get("last_heartbeat") instanceof java.util.Date d ? d : null;
      if (heartbeat != null
          && now - heartbeat.getTime() < integer(config.get("pollSeconds"), 30, 5, 3600) * 1000L)
        continue;
      String actor = Objects.toString(c.get("update_by"), Objects.toString(c.get("create_by"), ""));
      var user = users.selectUserByLoginName(actor);
      if (user == null
          || accounts.unavailable(user) != null
          || accounts.mustChange(user)
          || !accounts.modules(user.getUserId()).contains("connectors")) {
        disableRevoked(id);
        continue;
      }
      Subject subject =
          new Subject.Builder(SecurityUtils.getSecurityManager())
              .principals(new SimplePrincipalCollection(user, "UserRealm"))
              .authenticated(true)
              .sessionCreationEnabled(false)
              .buildSubject();
      try {
        subject.execute(
            (java.util.concurrent.Callable<Void>)
                () -> {
                  ShiroUtils.getSubject().checkPermission("drilling:connectors:run");
                  s.write(fieldId);
                  run(fieldId, id, actor);
                  return null;
                });
      } catch (Exception e) {
        disableRevoked(id);
      }
    }
  }

  private void disableRevoked(Long id) {
    s.jdbc.update(
        "UPDATE drilling_realtime_connector SET"
            + " status='DISABLED',last_error='操作账号已失效或权限已撤销，请重新授权启用' WHERE connector_id=?",
        id);
  }
}
