package com.ruoyi.web.controller.drilling.centers;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Observations from the actual connector polling loop, including an empty successful scan. */
@Component
public class ConnectorPollingHealth {
  public static final String HEARTBEAT_LIMIT_SQL =
      "GREATEST(120,COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(c.config_json,'$.pollSeconds')) AS UNSIGNED),30)*2+30)";

  private final Instant started = Instant.now();
  private volatile Instant pulse = started;
  private volatile Map<String,Object> last = Map.of("status","PENDING");
  private Instant lastSuccess;

  public void pulse() { pulse = Instant.now(); }

  public void finish(long startNanos, boolean success) {
    Instant now = Instant.now();
    pulse = now;
    if (success) lastSuccess = now;
    Map<String,Object> sample = new LinkedHashMap<>();
    sample.put("status",success ? "UP" : "DOWN");
    sample.put("checkedAt",now.toString());
    sample.put("latencyMs",(System.nanoTime()-startNanos)/1_000_000);
    if (lastSuccess!=null) sample.put("lastSuccessAt",lastSuccess.toString());
    if (!success) sample.put("message","实时连接轮询失败，请检查数据库及接入服务");
    last = Map.copyOf(sample);
  }

  public Map<String,Object> snapshot() {
    var result = new LinkedHashMap<>(last);
    Instant beat = pulse;
    if (!beat.equals(started)) result.put("heartbeatAt",beat.toString());
    if (beat.plusSeconds(120).isBefore(Instant.now())) {
      result.put("status","DOWN");
      result.put("message","超过 120 秒未收到轮询心跳");
    }
    return result;
  }
}
