package com.ruoyi.web.controller.drilling;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingDataScope;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NativeEngineSupport {
  private static final String BOOT = UUID.randomUUID().toString();
  private final JdbcTemplate jdbc;
  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  @Value("${drilling.algorithm.url:http://localhost:8000}")
  private String url;

  public NativeEngineSupport(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public String base() {
    String s = url.replaceAll("/$", "");
    return s.endsWith("/api/algorithm") ? s : s + "/api/algorithm";
  }

  public Map<String, Object> json(String path, Object body) {
    try {
      var builder =
          HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(path.equals("/engine/validate") ? 15 : path.equals("/survey/reduce") ? 30 : 180));
      var req =
          body == null
              ? builder.GET().build()
              : builder
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body)))
                  .build();
      var res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 300)
        throw new IllegalArgumentException(
            "计算服务拒绝请求：" + res.body().substring(0, Math.min(1000, res.body().length())));
      return JSON.parseObject(res.body(), Map.class);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("计算服务不可用，请启动算法服务", e);
    }
  }

  public byte[] binary(String path, Object body) {
    try {
      var req =
          HttpRequest.newBuilder(URI.create(base() + path))
              .timeout(Duration.ofSeconds(180))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body)))
              .build();
      var res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (res.statusCode() >= 300)
        throw new IllegalArgumentException(new String(res.body(), StandardCharsets.UTF_8));
      return res.body();
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("导出服务不可用", e);
    }
  }

  public Map<String, Object> inspect(String name, byte[] bytes) {
    try {
      String boundary = "source-" + UUID.randomUUID();
      var out = new java.io.ByteArrayOutputStream();
      out.write(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                  + name.replaceAll("[\\r\\n\"\\\\]", "_")
                  + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      out.write(bytes);
      out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      String endpoint = base().replaceAll("/api/algorithm$", "") + "/internal/sources/inspect";
      var req =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(240))
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
              .build();
      var res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 300)
        throw new IllegalArgumentException(
            "文档解析失败：" + res.body().substring(0, Math.min(500, res.body().length())));
      return JSON.parseObject(res.body(), Map.class);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("文档解析服务不可用", e);
    }
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> config(Long fieldId) {
    DrillingDataScope.current().requireAccess(fieldId);
    var rows =
        jdbc.queryForList(
            "SELECT config_version,algorithm_defaults_json FROM drilling_field_config WHERE"
                + " field_id=? AND status='0' AND effective_to IS NULL ORDER BY config_version"
                + " DESC LIMIT 1",
            fieldId);
    if (rows.isEmpty()) throw new IllegalArgumentException("井场没有有效配置");
    return rows.get(0);
  }

  public Map<String, Object> confirmation(Long fieldId) {
    var c = config(fieldId);
    String snapshot =
        JSON.toJSONString(DrillingAlgorithmDefaults.merged(c.get("algorithm_defaults_json")));
    var configuration=JSON.parseObject(snapshot,Map.class);
    var profiles=new TreeMap<String,Map<String,Object>>();
    for(var row:jdbc.queryForList("SELECT v.reference_json FROM drilling_trajectory_head h "
        + "JOIN drilling_trajectory_version v ON v.survey_version_id=h.survey_version_id "
        + "JOIN drilling_field_wellbore x ON x.wellbore_id=h.wellbore_id WHERE x.field_id=?",fieldId)) {
      var reference=SourceRegistryController.obj(row.get("reference_json"));
      var profile=SurveyVersionMetadata.uncertainty(reference,configuration);
      // Geometry/version IDs and confirmation timestamps are not parameter
      // changes. A new live measurement with the same tool does not re-arm it.
      var key=SurveyVersionMetadata.canonical(Map.of("model_version",profile.get("model_version"),"settings",profile.get("settings"),"tool",profile.get("tool")));
      profiles.putIfAbsent(key,profile);
    }
    var safetyPolicy=new LinkedHashMap<String,Object>(modelPayload(configuration));
    safetyPolicy.remove("curve_tolerance_m");
    String configHash = hash(SurveyVersionMetadata.canonical(Map.of("policy",safetyPolicy,"surveyProfiles",profiles.keySet())));
    String session = hash(BOOT + ":" + ShiroUtils.getSessionId());
    int count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM drilling_engine_confirmation WHERE field_id=? AND user_id=? AND"
                + " session_hash=? AND config_hash=?",
            Integer.class,
            fieldId,
            ShiroUtils.getUserId(),
            session,
            configHash);
    return Map.of(
        "confirmed",
        count > 0,
        "configHash",
        configHash,
        "configVersion",
        c.get("config_version"),
        "configuration",
        configuration,
        "surveyProfiles",
        new ArrayList<>(profiles.values()),
        "sessionHash",
        session);
  }

  public void confirm(Long fieldId, String expectedHash) {
    var c = confirmation(fieldId);
    if (!c.get("configHash").equals(expectedHash))
      throw new IllegalArgumentException("配置已切换，请重新核对并确认");
    validate((Map<String, Object>) c.get("configuration"));
    for(var item:(List<Map<String,Object>>)c.get("surveyProfiles")) {
      var model=new LinkedHashMap<String,Object>(modelPayload((Map<String,Object>)c.get("configuration")));
      model.put("model_version",item.get("model_version"));model.put("settings",item.get("settings"));
      json("/engine/validate",Map.of("error_model",model));
    }
    jdbc.update(
        "INSERT IGNORE INTO"
            + " drilling_engine_confirmation(field_id,user_id,session_hash,config_hash,config_version,snapshot_json)"
            + " VALUES(?,?,?,?,?,?)",
        fieldId,
        ShiroUtils.getUserId(),
        c.get("sessionHash"),
        c.get("configHash"),
        c.get("configVersion"),
        JSON.toJSONString(Map.of("configuration",c.get("configuration"),"surveyProfiles",c.get("surveyProfiles")),
            com.alibaba.fastjson.serializer.SerializerFeature.DisableCircularReferenceDetect));
  }

  public void requireConfirmed(Long fieldId) {
    if (!Boolean.TRUE.equals(confirmation(fieldId).get("confirmed")))
      throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "请先核对并确认当前误差模型、SF口径和参数");
  }

  public void validate(Map<String, Object> defaults) {
    Map<String,Object> request = new LinkedHashMap<>();
    // The mandatory confirmation is an anticollision safety gate. Planning
    // segment counts and solver settings are validated only when a planning
    // task is submitted, so unrelated stale planning fields cannot block it.
    request.put("error_model", modelPayload(defaults));
    json("/engine/validate", request);
  }

  public static Map<String, Object> modelPayload(Map<String, Object> defaults) {
    Map<?, ?> m = (Map<?, ?>) defaults.get("errorModel");
    if (m == null || !m.containsKey("settings"))
      throw new IllegalArgumentException("旧误差配置已停用，请在工程配置中选择新引擎模型");
    Map<String, Object> out = new LinkedHashMap<>();
    for (String[] pair :
        new String[][] {
          {"mode", "mode"},
          {"modelVersion", "model_version"},
          {"k", "k"},
          {"sfMethod", "sf_method"},
          {"settings", "settings"},
          {"radiiMarginM", "radii_margin_m"},
          {"sigmaPaM", "sigma_pa_m"},
          {"curveToleranceM", "curve_tolerance_m"}
        }) if (m.containsKey(pair[0])) out.put(pair[1], m.get(pair[0]));
    return out;
  }
}
