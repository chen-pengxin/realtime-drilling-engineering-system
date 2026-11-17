package com.ruoyi.web.controller.operations;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingAccountPolicy;
import com.ruoyi.system.service.ISysConfigService;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

@Service
public class OperationsSupport {
  public final JdbcTemplate jdbc;
  private final DrillingAccountPolicy policy;
  private final ISysConfigService configs;
  public static final Set<String> SETTINGS =
      Set.of(
          "drilling.system.name",
          "drilling.session.timeoutMinutes",
          "drilling.maintenance.retentionDays",
          "drilling.monitor.refreshSeconds");

  public OperationsSupport(
      JdbcTemplate jdbc, DrillingAccountPolicy policy, ISysConfigService configs) {
    this.jdbc = jdbc;
    this.policy = policy;
    this.configs = configs;
  }

  @PostConstruct
  public void initialize() {
    jdbc.execute(
        (org.springframework.jdbc.core.ConnectionCallback<Void>)
            c -> {
              ScriptUtils.executeSqlScript(
                  c, new ClassPathResource("drilling/operations-console.sql"));
              return null;
            });
    configs.resetConfigCache();
  }

  public void admin() {
    if (!"ADMIN".equals(policy.tier(ShiroUtils.getUserId())))
      throw new org.apache.shiro.authz.UnauthorizedException("仅系统管理员可访问运行管理");
  }

  public String setting(String key) {
    return configs.selectConfigByKey(key);
  }

  public int intSetting(String key, int fallback) {
    try {
      return Integer.parseInt(setting(key));
    } catch (Exception e) {
      return fallback;
    }
  }

  public Map<String, Object> settings() {
    return Map.of(
        "systemName",
        setting("drilling.system.name"),
        "monitorRefreshSeconds",
        intSetting("drilling.monitor.refreshSeconds", 30),
        "sessionTimeoutMinutes",
        intSetting("drilling.session.timeoutMinutes", 30));
  }

  public static int page(Map<String, String> p) {
    return integer(p.get("pageNum"), 1, 1, 100000);
  }

  public static int size(Map<String, String> p) {
    return integer(p.get("pageSize"), 20, 1, 100);
  }

  public static int integer(Object value, int fallback, int min, int max) {
    try {
      int n = value == null ? fallback : Integer.parseInt(String.valueOf(value));
      if (n < min || n > max) throw new IllegalArgumentException();
      return n;
    } catch (Exception e) {
      throw new IllegalArgumentException("数值须在" + min + "～" + max + "之间");
    }
  }

  public static String text(Map<String, ?> p, String key, int max, boolean required) {
    Object raw = p.get(key);
    String v = raw == null ? "" : String.valueOf(raw).trim();
    if ((required && v.isEmpty()) || v.length() > max)
      throw new IllegalArgumentException(key + "为空或超过长度限制");
    return v;
  }

  public static String state(Map<String, ?> p) {
    String s = text(p, "status", 1, true);
    if (!Set.of("0", "1").contains(s)) throw new IllegalArgumentException("状态无效");
    return s;
  }

  public static void dates(
      Map<String, String> p, String column, StringBuilder where, List<Object> args) {
    for (String key : List.of("beginTime", "endTime")) {
      String v = p.get(key);
      if (v == null || v.isBlank()) continue;
      try {
        LocalDateTime d =
            v.length() == 10
                ? java.time.LocalDate.parse(v)
                    .atTime(
                        key.equals("endTime") ? java.time.LocalTime.MAX : java.time.LocalTime.MIN)
                : LocalDateTime.parse(v.replace(' ', 'T'));
        where.append(" AND ").append(column).append(key.equals("beginTime") ? ">=?" : "<=?");
        args.add(Timestamp.valueOf(d));
      } catch (Exception e) {
        throw new IllegalArgumentException("时间格式无效");
      }
    }
  }

  public static void like(
      Map<String, String> p, String key, String column, StringBuilder where, List<Object> args) {
    String v = p.get(key);
    if (v != null && !v.isBlank()) {
      where.append(" AND ").append(column).append(" LIKE ?");
      args.add("%" + v.trim() + "%");
    }
  }

  public AjaxResult pageQuery(
      String select,
      String fromWhere,
      List<Object> args,
      String order,
      Map<String, String> params) {
    Long total = jdbc.queryForObject("SELECT COUNT(*) " + fromWhere, Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size(params));
    pageArgs.add((page(params) - 1) * size(params));
    AjaxResult r = AjaxResult.success();
    r.put(
        "rows",
        jdbc.queryForList(
            select + " " + fromWhere + " " + order + " LIMIT ? OFFSET ?", pageArgs.toArray()));
    r.put("total", total);
    return r;
  }

  public static ResponseEntity<byte[]> csv(
      String file, List<String> headers, List<? extends List<?>> rows) {
    StringBuilder out = new StringBuilder("\uFEFF");
    out.append(String.join(",", headers)).append("\r\n");
    for (List<?> row : rows) {
      for (int i = 0; i < row.size(); i++) {
        if (i > 0) out.append(',');
        String s = Objects.toString(row.get(i), "");
        if (s.matches("(?s)^[\\s]*[=+@-].*")) s = "'" + s;
        out.append('"').append(s.replace("\"", "\"\"")).append('"');
      }
      out.append("\r\n");
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(out.toString().getBytes(StandardCharsets.UTF_8));
  }
}
