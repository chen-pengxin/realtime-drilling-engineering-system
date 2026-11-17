package com.ruoyi.framework.security;

import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import jakarta.annotation.PostConstruct;
import java.util.*;
import org.apache.shiro.authz.UnauthorizedException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

/** Applies RuoYi data scopes to engineering fields and their descendant records. */
@Service
public class DrillingDataScope {
  private final JdbcTemplate jdbc;
  private final DrillingAccountPolicy accounts;

  public DrillingDataScope(JdbcTemplate jdbc, DrillingAccountPolicy accounts) {
    this.jdbc = jdbc;
    this.accounts = accounts;
  }

  public static DrillingDataScope current() {
    return SpringUtils.getBean(DrillingDataScope.class);
  }

  @PostConstruct
  public void initialize() {
    jdbc.execute(
        (org.springframework.jdbc.core.ConnectionCallback<Void>)
            c -> {
              ScriptUtils.executeSqlScript(c, new ClassPathResource("drilling/organization.sql"));
              return null;
            });
  }

  /** Column names come only from Java call sites. Values are generated numeric IDs. */
  public String predicate(String fieldColumn) {
    if (!fieldColumn.matches("[A-Za-z_][A-Za-z0-9_.]*"))
      throw new IllegalArgumentException("invalid field column");
    long uid = ShiroUtils.getUserId();
    String tier = accounts.tier(uid);
    if (tier.equals("ADMIN")) return "1=1";
    if (!tier.equals("ENGINEER")) return "1=0";
    List<Map<String, Object>> roles =
        jdbc.queryForList(
            "SELECT r.role_id,r.data_scope FROM sys_user_role ur JOIN sys_role r ON"
                + " r.role_id=ur.role_id AND r.status='0' AND r.del_flag='0' JOIN"
                + " drilling_role_module rm ON rm.role_id=r.role_id AND rm.account_tier='ENGINEER'"
                + " WHERE ur.user_id=?",
            uid);
    Long dept =
        jdbc.queryForObject("SELECT dept_id FROM sys_user WHERE user_id=?", Long.class, uid);
    Set<Long> departments = new HashSet<>();
    List<String> grants = new ArrayList<>();
    for (var role : roles) {
      switch (String.valueOf(role.get("data_scope"))) {
        case "1":
          return fieldColumn
              + " IN (SELECT f.field_id FROM drilling_field f JOIN drilling_block b ON"
              + " b.block_id=f.block_id WHERE f.status='0' AND b.status='0')";
        case "2":
          departments.addAll(
              jdbc.queryForList(
                  "SELECT dept_id FROM sys_role_dept WHERE role_id=?",
                  Long.class,
                  role.get("role_id")));
          break;
        case "3":
          if (dept != null) departments.add(dept);
          break;
        case "4":
          if (dept != null)
            departments.addAll(
                jdbc.queryForList(
                    "SELECT dept_id FROM sys_dept WHERE dept_id=? OR FIND_IN_SET(?,ancestors)>0",
                    Long.class,
                    dept,
                    dept));
          break;
        case "5":
          grants.add(
              "(o.owner_user_id="
                  + uid
                  + " OR f.create_by COLLATE utf8mb4_unicode_ci=(SELECT login_name COLLATE"
                  + " utf8mb4_unicode_ci FROM sys_user WHERE user_id="
                  + uid
                  + "))");
          break;
        case "6":
          grants.add("f.field_id IN (SELECT m.field_id FROM drilling_field_member m WHERE m.user_id="
              + uid + " AND m.status='0')");
          break;
        default:
          break;
      }
    }
    departments.removeIf(id -> !activeDepartment(id));
    if (!departments.isEmpty())
      grants.add(
          "o.dept_id IN ("
              + departments.stream()
                  .map(String::valueOf)
                  .collect(java.util.stream.Collectors.joining(","))
              + ")");
    if (grants.isEmpty()) return "1=0";
    return fieldColumn
        + " IN (SELECT f.field_id FROM drilling_field f JOIN drilling_block b ON"
        + " b.block_id=f.block_id LEFT JOIN drilling_field_organization"
        + " o ON o.field_id=f.field_id WHERE f.status='0' AND b.status='0' AND ("
        + String.join(" OR ", grants)
        + "))";
  }

  public boolean activeDepartment(Long id) {
    return id != null
        && jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_dept d WHERE d.dept_id=? AND d.status='0' AND"
                    + " d.del_flag='0' AND NOT EXISTS(SELECT 1 FROM sys_dept a WHERE"
                    + " FIND_IN_SET(a.dept_id,d.ancestors)>0 AND (a.status<>'0' OR"
                    + " a.del_flag<>'0'))",
                Integer.class,
                id)
            > 0;
  }

  public void requireAccess(Long id) {
    if (id == null
        || jdbc.queryForObject(
                "SELECT COUNT(*) FROM drilling_field f JOIN drilling_block b ON b.block_id=f.block_id"
                    + " WHERE f.status='0' AND b.status='0' AND f.field_id=? AND ("
                    + predicate("field_id")
                    + ")",
                Integer.class,
                id)
            == 0) throw new UnauthorizedException("井场不存在或超出数据权限范围");
  }

  public void requireRole(Long id, String... allowed) {
    requireAccess(id);
    String tier = accounts.tier(ShiroUtils.getUserId());
    if (tier.equals("ADMIN")) return;
    if (!tier.equals("ENGINEER") || allowed.length == 0)
      throw new UnauthorizedException("当前账号无权修改该井场数据");
  }

  public void validateAssignments(Map<String, Object> b) {
    if (b.get("deptId") != null && !activeDepartment(number(b.get("deptId"))))
      throw new IllegalArgumentException("请选择有效且启用的部门");
    for (Long id : ids(b.get("postIds")))
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM sys_post WHERE post_id=? AND status='0'", Integer.class, id)
          == 0) throw new IllegalArgumentException("岗位不存在或已停用");
  }

  public void saveAssignments(Long uid, Map<String, Object> b) {
    validateAssignments(b);
    if (b.containsKey("deptId"))
      jdbc.update("UPDATE sys_user SET dept_id=? WHERE user_id=?", number(b.get("deptId")), uid);
    if (b.containsKey("postIds")) {
      jdbc.update("DELETE FROM sys_user_post WHERE user_id=?", uid);
      for (Long id : ids(b.get("postIds")))
        jdbc.update("INSERT INTO sys_user_post(user_id,post_id) VALUES(?,?)", uid, id);
    }
  }

  public void saveScope(Long rid, Map<String, Object> b, String tier) {
    if (!b.containsKey("dataScope")) return;
    String scope = String.valueOf(b.get("dataScope"));
    if (!Set.of("1", "2", "3", "4", "5", "6").contains(scope))
      throw new IllegalArgumentException("数据范围无效");
    if (tier.equals("GUEST")) scope = "6";
    List<Long> depts = scope.equals("2") ? ids(b.get("deptIds")) : List.of();
    if (scope.equals("2") && depts.isEmpty()) throw new IllegalArgumentException("请选择自定义数据范围的部门");
    for (Long d : depts)
      if (!activeDepartment(d)) throw new IllegalArgumentException("数据范围包含停用或不存在的部门");
    jdbc.update("UPDATE sys_role SET data_scope=?,update_time=NOW() WHERE role_id=?", scope, rid);
    jdbc.update("DELETE FROM sys_role_dept WHERE role_id=?", rid);
    for (Long d : depts)
      jdbc.update("INSERT INTO sys_role_dept(role_id,dept_id) VALUES(?,?)", rid, d);
  }

  public void initializeField(Long fieldId) {
    jdbc.update(
        "INSERT INTO drilling_field_organization(field_id,dept_id,owner_user_id) SELECT"
            + " ?,dept_id,user_id FROM sys_user WHERE user_id=? ON DUPLICATE KEY UPDATE"
            + " dept_id=VALUES(dept_id),owner_user_id=VALUES(owner_user_id)",
        fieldId,
        ShiroUtils.getUserId());
  }

  public static Long number(Object v) {
    if (v == null || "".equals(v)) return null;
    try {
      long n = Long.parseLong(String.valueOf(v));
      if (n < 1) throw new Exception();
      return n;
    } catch (Exception e) {
      throw new IllegalArgumentException("ID须为正整数");
    }
  }

  public static List<Long> ids(Object v) {
    if (v == null) return List.of();
    if (!(v instanceof Collection<?> c)) throw new IllegalArgumentException("请提供ID列表");
    return c.stream().map(DrillingDataScope::number).filter(Objects::nonNull).distinct().toList();
  }
}
