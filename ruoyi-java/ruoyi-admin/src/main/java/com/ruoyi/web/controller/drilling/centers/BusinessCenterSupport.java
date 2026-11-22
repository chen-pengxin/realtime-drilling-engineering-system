package com.ruoyi.web.controller.drilling.centers;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingDataScope;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

/** Bounded server-side lists and fieldId-scoped batches shared by the six centers. */
@Service
@DependsOn({"drillingDataScope", "operationsSupport"})
public class BusinessCenterSupport {
  public final JdbcTemplate jdbc;

  public BusinessCenterSupport(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @PostConstruct
  public void initialize() {
    jdbc.execute(
        (org.springframework.jdbc.core.ConnectionCallback<Void>)
            c -> {
              ScriptUtils.executeSqlScript(
                  c, new ClassPathResource("drilling/business-centers.sql"));
              return null;
            });
  }

  public void access(Long fieldId) {
    DrillingDataScope.current().requireAccess(fieldId);
  }

  public void write(Long fieldId) {
    DrillingDataScope.current().requireRole(fieldId, "OWNER", "ENGINEER");
  }

  public static int integer(Object v, int def, int min, int max) {
    try {
      int n = v == null ? def : Integer.parseInt(v.toString());
      if (n < min || n > max) throw new Exception();
      return n;
    } catch (Exception e) {
      throw new IllegalArgumentException("数值范围应为 " + min + "～" + max);
    }
  }

  public static String text(Map<String, ?> p, String key, int max, boolean required) {
    String s = Objects.toString(p.get(key), "").trim();
    if ((required && s.isEmpty()) || s.length() > max)
      throw new IllegalArgumentException(key + "为空或超过长度限制");
    return s;
  }

  public static Map<String, Object> object(Object v) {
    if (v == null) return new LinkedHashMap<>();
    if (v instanceof Map<?, ?> m) {
      Map<String, Object> r = new LinkedHashMap<>();
      m.forEach((k, x) -> r.put(k.toString(), x));
      return r;
    }
    return JSON.parseObject(v.toString(), Map.class);
  }

  public static List<String> keys(Map<String, Object> b) {
    if (!(b.get("ids") instanceof List<?> ids) || ids.isEmpty() || ids.size() > 100)
      throw new IllegalArgumentException("每次请选择1～100条记录");
    List<String> result = ids.stream().map(String::valueOf).distinct().toList();
    if (result.stream().anyMatch(k -> !k.matches("[A-Z_]+:[1-9][0-9]*|[1-9][0-9]*")))
      throw new IllegalArgumentException("记录编号无效");
    return result;
  }

  public Map<String, Object> page(
      String source,
      Long fieldId,
      Map<String, String> p,
      Map<String, String> filters,
      Map<String, String> sorts) {
    access(fieldId);
    StringBuilder where = new StringBuilder(" FROM (" + source + ") q WHERE q.field_id=?");
    List<Object> args = new ArrayList<>(List.of(fieldId));
    for (var f : filters.entrySet()) {
      String value = text(p, f.getKey(), 200, false);
      if (value.isEmpty()) continue;
      where
          .append(" AND ")
          .append(f.getValue())
          .append(f.getKey().equals("keyword") ? " LIKE ? ESCAPE '='" : " = ?");
      args.add(
          f.getKey().equals("keyword")
              ? "%" + value.replace("=", "==").replace("%", "=%").replace("_", "=_") + "%"
              : value);
    }
    for (String key : List.of("beginTime", "endTime")) {
      String v = text(p, key, 32, false);
      if (v.isEmpty()) continue;
      try {
        LocalDateTime d =
            v.length() == 10
                ? java.time.LocalDate.parse(v)
                    .atTime(
                        key.equals("endTime") ? java.time.LocalTime.MAX : java.time.LocalTime.MIN)
                : LocalDateTime.parse(v.replace(' ', 'T'));
        where.append(" AND q.created_at ").append(key.equals("beginTime") ? ">=?" : "<=?");
        args.add(Timestamp.valueOf(d));
      } catch (Exception e) {
        throw new IllegalArgumentException("查询时间格式无效");
      }
    }
    String sort = text(p, "sort", 40, false);
    if (sort.isEmpty()) sort = "created_at";
    if (!sorts.containsKey(sort)) throw new IllegalArgumentException("不支持此排序字段");
    String direction = text(p, "order", 10, false);
    if (direction.isEmpty()) direction = "desc";
    if (!Set.of("asc", "desc").contains(direction))
      throw new IllegalArgumentException("排序方向必须为asc或desc");
    long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
    int page = integer(p.get("pageNum"), 1, 1, 1000000),
        size = integer(p.get("pageSize"), 20, 1, 100);
    args.add(size);
    args.add((page - 1) * size);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT q.*"
                + where
                + " ORDER BY "
                + sorts.get(sort)
                + " "
                + direction
                + ", q.record_key ASC LIMIT ? OFFSET ?",
            args.toArray());
    return Map.of("code", 200, "rows", rows, "total", total, "pageNum", page, "pageSize", size);
  }

  public Map<String, Object> row(String source, Long fieldId, String key, boolean lock) {
    access(fieldId);
    var rows =
        jdbc.queryForList(
            "SELECT * FROM ("
                + source
                + ") q WHERE field_id=? AND record_key=?"
                + (lock ? " FOR UPDATE" : ""),
            fieldId,
            key);
    if (rows.isEmpty()) throw new IllegalArgumentException("记录不存在或不属于当前井场");
    return rows.get(0);
  }

  public void audit(Long fieldId, String action, String type, String id, Object detail) {
    jdbc.update(
        "INSERT INTO"
            + " drilling_audit_log(field_id,user_id,action,resource_type,resource_id,detail_json)"
            + " VALUES(?,?,?,?,?,?)",
        fieldId,
        ShiroUtils.getUserId(),
        action,
        type,
        id,
        com.ruoyi.web.controller.drilling.BusinessAuditMetadata.json(detail));
  }

  public static ResponseEntity<byte[]> attachment(byte[] bytes, String name, String type) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(type))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentLength(bytes.length)
        .body(bytes);
  }

  public static ResponseEntity<byte[]> json(Object value, String name) {
    return attachment(JSON.toJSONBytes(value), name, "application/json");
  }

  public static ResponseEntity<byte[]> csv(List<Map<String, Object>> rows, String name) {
    LinkedHashSet<String> columns = new LinkedHashSet<>();
    rows.forEach(r -> columns.addAll(r.keySet()));
    StringBuilder s = new StringBuilder("\uFEFF");
    s.append(String.join(",", columns)).append('\n');
    for (var r : rows) {
      List<String> cells = new ArrayList<>();
      for (String c : columns) {
        String v = Objects.toString(r.get(c), "");
        if (v.matches("^[=+@\\-\\t\\r].*")) v = "'" + v;
        cells.add("\"" + v.replace("\"", "\"\"") + "\"");
      }
      s.append(String.join(",", cells)).append('\n');
    }
    return attachment(
        s.toString().getBytes(StandardCharsets.UTF_8), name, "text/csv;charset=UTF-8");
  }
}
