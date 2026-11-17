package com.ruoyi.web.controller.operations;

import com.alibaba.fastjson.JSON;
import com.ruoyi.framework.web.domain.Server;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OperationsHealthService {
  private final OperationsSupport ops;
  private final GpuMonitorService gpus;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Value("${drilling.algorithm.url:http://localhost:8000}")
  private String algorithmUrl;

  public OperationsHealthService(OperationsSupport ops, GpuMonitorService gpus) {
    this.ops = ops;
    this.gpus = gpus;
  }

  public Map<String, Object> services() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("java", Map.of("status", "UP"));
    long start = System.nanoTime();
    try {
      ops.jdbc.queryForObject("SELECT 1", Integer.class);
      result.put(
          "database", Map.of("status", "UP", "latencyMs", (System.nanoTime() - start) / 1000000));
    } catch (Exception e) {
      result.put("database", Map.of("status", "DOWN", "message", "数据库连接失败"));
    }
    start = System.nanoTime();
    try {
      HttpResponse<String> r =
          client.send(
              HttpRequest.newBuilder(URI.create(algorithmUrl.replaceAll("/+$", "") + "/health"))
                  .timeout(Duration.ofSeconds(2))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      String state = "DOWN";
      if (r.statusCode() == 200) {
        try {
          Map<?, ?> body = JSON.parseObject(r.body(), Map.class);
          if (body != null
              && Set.of("ok", "healthy", "up")
                  .contains(String.valueOf(body.get("status")).toLowerCase())) state = "UP";
        } catch (Exception ignored) {
        }
      }
      result.put(
          "algorithm",
          Map.of(
              "status",
              state,
              "httpStatus",
              r.statusCode(),
              "latencyMs",
              (System.nanoTime() - start) / 1000000));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      result.put("algorithm", Map.of("status", "DOWN", "message", "健康检查已中断"));
    } catch (Exception e) {
      result.put("algorithm", Map.of("status", "DOWN", "message", "算法服务连接失败或超时"));
    }
    return result;
  }

  public Map<String, Object> snapshot() throws Exception {
    Map<String, Object> r = new LinkedHashMap<>();
    Server server = new Server();
    server.copyTo();
    r.put("server", server);
    r.put("gpu", gpus.snapshot());
    r.put("services", services());
    r.put("checkedAt", OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
    r.put("refreshSeconds", ops.intSetting("drilling.monitor.refreshSeconds", 30));
    r.put(
        "connectors",
        ops.jdbc.queryForList(
            "SELECT c.connector_id AS connectorId,c.connector_name AS"
                + " connectorName,c.connector_type AS connectorType,c.field_id AS"
                + " fieldId,f.field_name AS fieldName,c.status,c.last_heartbeat AS"
                + " lastHeartbeat,CASE WHEN c.last_error IS NULL OR c.last_error='' THEN FALSE ELSE"
                + " TRUE END AS hasError FROM drilling_realtime_connector c JOIN drilling_field f"
                + " ON f.field_id=c.field_id WHERE f.status='0' ORDER BY c.connector_id DESC"
                + " LIMIT 100"));
    r.put(
        "tasks",
        ops.jdbc.queryForList(
            "SELECT 'PREDICTION' AS taskType,status,COUNT(*) AS count FROM prediction_task GROUP BY"
                + " status UNION ALL SELECT 'MODEL_UPDATE',status,COUNT(*) FROM training_task GROUP"
                + " BY status"));
    return r;
  }
}
