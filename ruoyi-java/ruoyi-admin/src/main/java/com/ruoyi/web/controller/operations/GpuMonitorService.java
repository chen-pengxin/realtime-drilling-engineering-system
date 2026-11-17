package com.ruoyi.web.controller.operations;

import com.alibaba.fastjson.JSON;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;

/** Shows algorithm telemetry separately from the business host's hardware inventory. */
@Service
public class GpuMonitorService {
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @Value("${drilling.algorithm.url:http://localhost:8000}")
  private String algorithmUrl;

  private volatile Map<String, Object> local;

  public Map<String, Object> snapshot() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("business", business());
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(algorithmUrl.replaceAll("/+$", "") + "/api/algorithm/resources"))
                  .timeout(Duration.ofSeconds(6))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new IllegalStateException();
      Map<?, ?> payload = JSON.parseObject(response.body(), Map.class);
      if (payload.get("gpu_monitor") instanceof Map<?, ?> gpu
          && gpu.get("devices") instanceof List<?>) result.put("algorithm", gpu);
      else if (payload.get("gpus") instanceof List<?> rows)
        result.put(
            "algorithm",
            Map.of(
                "status",
                rows.isEmpty() ? "UNKNOWN" : "UP",
                "devices",
                rows,
                "message",
                rows.isEmpty() ? "算法服务未提供 GPU 监控信息" : ""));
      else throw new IllegalStateException();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      result.put("algorithm", unavailable("GPU 信息采集已中断"));
    } catch (Exception e) {
      result.put("algorithm", unavailable("算法节点 GPU 信息暂不可用"));
    }
    return result;
  }

  private Map<String, Object> unavailable(String message) {
    return Map.of("status", "UNAVAILABLE", "devices", List.of(), "message", message);
  }

  private Map<String, Object> business() {
    if (local != null) return local;
    synchronized (this) {
      if (local != null) return local;
      List<Map<String, Object>> rows = new ArrayList<>();
      try {
        for (var card : new SystemInfo().getHardware().getGraphicsCards()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("index", rows.size());
          row.put("name", card.getName());
          row.put("vendor", card.getVendor());
          row.put(
              "driver_version",
              "unknown".equalsIgnoreCase(card.getVersionInfo()) ? null : card.getVersionInfo());
          boolean sharedMemory =
              System.getProperty("os.name", "").startsWith("Mac")
                  && card.getName().startsWith("Apple");
          row.put("shared_memory", sharedMemory);
          row.put("memory_total_mb", card.getVRam() > 0 ? card.getVRam() / 1048576.0 : null);
          row.put("status", "DETECTED");
          row.put("backend", "OSHI");
          rows.add(row);
        }
        local =
            Map.of(
                "status",
                rows.isEmpty() ? "NO_DEVICE" : "UP",
                "devices",
                rows,
                "message",
                rows.isEmpty() ? "未检测到 GPU 设备" : "");
      } catch (Exception e) {
        local = unavailable("业务节点 GPU 硬件信息暂不可用");
      }
      return local;
    }
  }
}
