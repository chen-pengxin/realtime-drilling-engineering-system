package com.ruoyi.web.controller.operations;

import com.alibaba.fastjson.JSON;
import com.ruoyi.framework.web.domain.Server;
import com.ruoyi.web.controller.drilling.centers.ConnectorPollingHealth;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class OperationsHealthService {
  private final OperationsSupport ops;
  private final GpuMonitorService gpus;
  private final ConnectorPollingHealth worker;
  private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(2)).build();
  private final ConcurrentMap<String,String> lastSuccess = new ConcurrentHashMap<>();
  private final ScheduledExecutorService sampler = Executors.newScheduledThreadPool(2, runnable -> {
    Thread thread = new Thread(runnable,"hardware-monitor");
    thread.setDaemon(true);
    return thread;
  });
  private record Sample(Object value, Instant checked, String error) {}
  private volatile Sample host, graphics;

  @Value("${drilling.algorithm.url:http://localhost:8000}") private String algorithmUrl;
  private String javaUrl = "http://127.0.0.1:8080/login";
  @Value("${server.ssl.enabled:false}") private boolean tls;

  public OperationsHealthService(OperationsSupport ops, GpuMonitorService gpus, ConnectorPollingHealth worker) {
    this.ops = ops;
    this.gpus = gpus;
    this.worker = worker;
  }

  @EventListener
  public void serverStarted(ServletWebServerInitializedEvent event) {
    javaUrl = (tls ? "https" : "http") + "://127.0.0.1:" + event.getWebServer().getPort()
        + event.getApplicationContext().getServletContext().getContextPath() + "/login";
  }

  @EventListener(ApplicationReadyEvent.class)
  public void startSampling() {
    sampler.scheduleWithFixedDelay(() -> host = sample(() -> {
      Server server = new Server();
      server.copyTo();
      return server;
    }),0,5,TimeUnit.SECONDS);
    sampler.scheduleWithFixedDelay(() -> graphics = sample(gpus::snapshot),0,10,TimeUnit.SECONDS);
  }

  private static Sample sample(Callable<?> collect) {
    try { return new Sample(collect.call(),Instant.now(),null); }
    catch (Exception ex) { return new Sample(null,Instant.now(),"采集失败"); }
  }

  @jakarta.annotation.PreDestroy
  public void stopSampling() { sampler.shutdownNow(); }

  private Map<String,Object> observation(String key, long start, String error) {
    String now = Instant.now().toString();
    var result = new LinkedHashMap<String,Object>();
    if (error == null) lastSuccess.merge(key,now,(a,b)->Instant.parse(a).isAfter(Instant.parse(b)) ? a : b);
    result.put("status",error == null ? "UP" : "DOWN");
    result.put("latencyMs",(System.nanoTime()-start)/1_000_000);
    result.put("checkedAt",now);
    result.put("lastSuccessAt",lastSuccess.get(key));
    if (error != null) result.put("message",error);
    return result;
  }

  private CompletableFuture<Map<String,Object>> http(String key, String url, Predicate<String> valid) {
    long start = System.nanoTime();
    try {
      var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2));
      if (key.equals("java")) builder.header("X-Requested-With","XMLHttpRequest");
      return client.sendAsync(builder.GET().build(),HttpResponse.BodyHandlers.ofString())
          .handle((r,ex)->{
            String problem = null;
            if (ex != null) problem = "连接失败或检查超时";
            else if (r.statusCode()!=200) problem = "健康检查返回 HTTP "+r.statusCode();
            else {
              try { if (!valid.test(r.body())) problem = "健康检查返回了异常内容"; }
              catch (RuntimeException invalid) { problem = "健康检查响应格式异常"; }
            }
            return observation(key,start,problem);
          });
    } catch (RuntimeException invalid) {
      return CompletableFuture.completedFuture(observation(key,start,"健康检查地址无效"));
    }
  }

  public Map<String,Object> services() {
    var java = http("java",javaUrl,body -> "1".equals(JSON.parseObject(body).getString("code")));
    var python = http("algorithm",algorithmUrl.replaceAll("/+$","")+"/health",
        body -> Set.of("up","ok","healthy").contains(String.valueOf(JSON.parseObject(body).get("status")).toLowerCase(Locale.ROOT)));
    var frontend = http("frontend",System.getenv().getOrDefault("DRILLING_FRONTEND_URL","http://nginx/"),
        body -> body.contains("id=\"app\""));
    long start = System.nanoTime();
    String error = null;
    try { ops.jdbc.queryForObject("SELECT 1",Integer.class); }
    catch (RuntimeException ex) { error = "数据库连接失败"; }
    return Map.of("database",observation("database",start,error),"java",java.join(),
        "frontend",frontend.join(),"algorithm",python.join(),"realtime",worker.snapshot());
  }

  private static final String CONNECTORS = """
      SELECT c.connector_id AS connectorId,c.connector_name AS connectorName,c.connector_type AS connectorType,
             f.field_name AS fieldName,c.last_heartbeat AS lastHeartbeat,
             CASE WHEN c.status='CONNECTED' AND (c.last_heartbeat IS NULL OR TIMESTAMPDIFF(SECOND,c.last_heartbeat,NOW())>
                  %s)
                  THEN 'STALE' ELSE c.status END AS status,
             CASE WHEN c.last_error LIKE '接入失败（%%）%%' THEN CONCAT(LEFT(SUBSTRING_INDEX(c.last_error,'）',1),100),'）')
                  WHEN c.last_error LIKE '操作账号已失效%%' THEN '接入账号失效或权限已撤销'
                  WHEN NULLIF(c.last_error,'') IS NOT NULL THEN '上次接入失败，请检查连接配置和数据源'
                  ELSE NULL END AS lastError
        FROM drilling_realtime_connector c JOIN drilling_field f ON f.field_id=c.field_id
       WHERE f.status='0' ORDER BY c.connector_id DESC
      """.formatted(ConnectorPollingHealth.HEARTBEAT_LIMIT_SQL);
  private static final String TASKS = """
      SELECT taskType,status,SUM(n) AS count FROM (
        SELECT CONVERT('PREDICTION' USING utf8mb4) COLLATE utf8mb4_general_ci taskType,CONVERT(status USING utf8mb4) COLLATE utf8mb4_general_ci AS status,COUNT(*) n FROM prediction_task GROUP BY status
        UNION ALL SELECT CONVERT('TRAINING' USING utf8mb4) COLLATE utf8mb4_general_ci,CONVERT(status USING utf8mb4) COLLATE utf8mb4_general_ci,COUNT(*) FROM training_task GROUP BY status
        UNION ALL SELECT CONVERT('REPLANNING' USING utf8mb4) COLLATE utf8mb4_general_ci,CONVERT(status USING utf8mb4) COLLATE utf8mb4_general_ci,COUNT(*) FROM trajectory_plan GROUP BY status
        UNION ALL SELECT CONVERT(task_type USING utf8mb4) COLLATE utf8mb4_general_ci,CONVERT(status USING utf8mb4) COLLATE utf8mb4_general_ci,COUNT(*) FROM drilling_center_execution
          WHERE task_type IN ('PREDICTION','REPLANNING') AND source_id IS NULL AND status IN ('RUNNING','FAILED')
          GROUP BY task_type,status
      ) observations GROUP BY taskType,status
      """;

  private static void addSample(Map<String,Object> result,String key,Sample sample) {
    if (sample == null) { result.put(key+"Error","正在首次采集"); return; }
    result.put(key+"CheckedAt",sample.checked().toString());
    if (sample.error()!=null) result.put(key+"Error",sample.error());
    else if (sample.checked().plusSeconds(30).isBefore(Instant.now())) result.put(key+"Error","采样超时");
    else result.put(key,sample.value());
  }

  public Map<String,Object> snapshot() {
    Map<String,Object> r = new LinkedHashMap<>();
    var services = services();
    r.put("services",services);
    addSample(r,"server",host);
    addSample(r,"gpu",graphics);
    boolean dbOk = "UP".equals(((Map<?,?>)services.get("database")).get("status"));
    for (String key : List.of("connectors","tasks")) {
      if (!dbOk) { r.put(key+"Error","数据库不可用"); continue; }
      try { r.put(key,ops.jdbc.queryForList(key.equals("tasks") ? TASKS : CONNECTORS)); }
      catch (RuntimeException ex) { r.put(key+"Error","查询失败"); }
    }
    r.put("refreshSeconds",dbOk ? ops.intSetting("drilling.monitor.refreshSeconds",30) : 30);
    r.put("checkedAt",Instant.now().toString());
    return r;
  }
}
