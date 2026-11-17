package com.ruoyi.web.controller.operations;

import static com.ruoyi.web.controller.operations.OperationsSupport.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingDataScope;
import com.ruoyi.framework.shiro.util.AuthorizationUtils;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** REST administration of the existing RuoYi department/post/role-department tables. */
@RestController
@RequestMapping("/api/operations/organization")
public class OrganizationController {
  private final OperationsSupport ops;
  private final DrillingDataScope scope;

  public OrganizationController(OperationsSupport ops, DrillingDataScope scope) {
    this.ops = ops;
    this.scope = scope;
  }

  @GetMapping("/departments")
  public AjaxResult departments() {
    ops.admin();
    return AjaxResult.success(
        ops.jdbc.queryForList(
            "SELECT dept_id AS deptId,parent_id AS parentId,ancestors,dept_name AS"
                + " deptName,order_num AS orderNum,leader,phone,email,status FROM sys_dept WHERE"
                + " del_flag='0' ORDER BY order_num,dept_id"));
  }

  @PostMapping("/departments")
  @Transactional
  public AjaxResult createDept(@RequestBody Map<String, Object> b) {
    ops.admin();
    lockDepartments();
    validateDept(null, b);
    Long parent = parent(b);
    String ancestors = ancestors(parent);
    ops.jdbc.update(
        "INSERT INTO"
            + " sys_dept(parent_id,ancestors,dept_name,order_num,leader,phone,email,status,create_by,create_time)"
            + " VALUES(?,?,?,?,?,?,?,?,?,NOW())",
        parent,
        ancestors,
        text(b, "deptName", 30, true),
        integer(b.get("orderNum"), 0, 0, 9999),
        text(b, "leader", 20, false),
        text(b, "phone", 11, false),
        text(b, "email", 50, false),
        state(b),
        ShiroUtils.getLoginName());
    return AjaxResult.success();
  }

  @PutMapping("/departments/{id}")
  @Transactional
  public AjaxResult updateDept(@PathVariable Long id, @RequestBody Map<String, Object> b) {
    ops.admin();
    lockDepartments();
    dept(id);
    validateDept(id, b);
    String a = ancestors(parent(b));
    if (a.length() > 50) throw new IllegalArgumentException("部门层级过深，请调整上级部门");
    ops.jdbc.update(
        "UPDATE sys_dept SET"
            + " parent_id=?,ancestors=?,dept_name=?,order_num=?,leader=?,phone=?,email=?,status=?,update_by=?,update_time=NOW()"
            + " WHERE dept_id=?",
        parent(b),
        a,
        text(b, "deptName", 30, true),
        integer(b.get("orderNum"), 0, 0, 9999),
        text(b, "leader", 20, false),
        text(b, "phone", 11, false),
        text(b, "email", 50, false),
        state(b),
        ShiroUtils.getLoginName(),
        id);
    rebuild(id, a, new HashSet<>());
    AuthorizationUtils.clearAllCachedAuthorizationInfo();
    return AjaxResult.success();
  }

  @DeleteMapping("/departments/{id}")
  @Transactional
  public AjaxResult deleteDept(@PathVariable Long id) {
    ops.admin();
    lockDepartments();
    dept(id);
    if (count("SELECT COUNT(*) FROM sys_dept WHERE parent_id=? AND del_flag='0'", id) > 0
        || count("SELECT COUNT(*) FROM sys_user WHERE dept_id=? AND del_flag='0'", id) > 0
        || count("SELECT COUNT(*) FROM drilling_field_organization WHERE dept_id=?", id) > 0
        || count("SELECT COUNT(*) FROM sys_role_dept WHERE dept_id=?", id) > 0)
      throw new IllegalArgumentException("部门仍有下级、账号、井场或角色数据授权，请先调整关联");
    ops.jdbc.update(
        "UPDATE sys_dept SET del_flag='2',update_by=?,update_time=NOW() WHERE dept_id=?",
        ShiroUtils.getLoginName(),
        id);
    return AjaxResult.success();
  }

  private void lockDepartments() {
    ops.jdbc.queryForList("SELECT dept_id FROM sys_dept ORDER BY dept_id FOR UPDATE");
  }

  private Map<String, Object> dept(Long id) {
    var r = ops.jdbc.queryForList("SELECT * FROM sys_dept WHERE dept_id=? AND del_flag='0'", id);
    if (r.isEmpty()) throw new IllegalArgumentException("部门不存在");
    return r.get(0);
  }

  private Long parent(Map<String, Object> b) {
    return b.get("parentId") == null ? 0L : Long.valueOf(String.valueOf(b.get("parentId")));
  }

  private String ancestors(Long p) {
    if (p == 0) return "0";
    var d = dept(p);
    String result = d.get("ancestors") + "," + p;
    if (result.length() > 50) throw new IllegalArgumentException("部门层级过深，请调整上级部门");
    return result;
  }

  private void validateDept(Long id, Map<String, Object> b) {
    Long p = parent(b);
    if (p < 0) throw new IllegalArgumentException("上级部门无效");
    String a = ancestors(p);
    if (id != null && (id.equals(p) || Arrays.asList(a.split(",")).contains(String.valueOf(id))))
      throw new IllegalArgumentException("不能选择自身或下级部门作为上级");
    if (state(b).equals("0") && p != 0 && !scope.activeDepartment(p))
      throw new IllegalArgumentException("上级部门已停用");
    if (id != null
        && state(b).equals("1")
        && count(
                "SELECT COUNT(*) FROM sys_dept WHERE FIND_IN_SET(?,ancestors)>0 AND status='0' AND"
                    + " del_flag='0'",
                id)
            > 0) throw new IllegalArgumentException("存在正常下级部门，不能停用");
    if (count(
            "SELECT COUNT(*) FROM sys_dept WHERE parent_id=? AND dept_name=? AND del_flag='0' AND"
                + " (? IS NULL OR dept_id<>?)",
            p,
            text(b, "deptName", 30, true),
            id,
            id)
        > 0) throw new IllegalArgumentException("同级部门名称已存在");
    String phone = text(b, "phone", 11, false), email = text(b, "email", 50, false);
    if (!phone.isEmpty() && !phone.matches("1[3-9][0-9]{9}"))
      throw new IllegalArgumentException("手机号格式无效");
    if (!email.isEmpty() && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw new IllegalArgumentException("邮箱格式无效");
  }

  private void rebuild(Long id, String a, Set<Long> seen) {
    if (!seen.add(id)) throw new IllegalArgumentException("部门层级循环");
    for (Long child :
        ops.jdbc.queryForList(
            "SELECT dept_id FROM sys_dept WHERE parent_id=? AND del_flag='0'", Long.class, id)) {
      String next = a + "," + id;
      if (next.length() > 50) throw new IllegalArgumentException("部门层级过深，请调整上级部门");
      ops.jdbc.update(
          "UPDATE sys_dept SET ancestors=?,update_time=NOW() WHERE dept_id=?", next, child);
      rebuild(child, next, seen);
    }
  }

  @GetMapping("/posts")
  public AjaxResult posts() {
    ops.admin();
    return AjaxResult.success(
        ops.jdbc.queryForList(
            "SELECT post_id AS postId,post_code AS postCode,post_name AS postName,post_sort AS"
                + " postSort,status,remark,create_time AS createTime FROM sys_post ORDER BY"
                + " post_sort,post_id"));
  }

  @PostMapping("/posts")
  @Transactional
  public AjaxResult createPost(@RequestBody Map<String, Object> b) {
    ops.admin();
    validatePost(null, b);
    ops.jdbc.update(
        "INSERT INTO sys_post(post_code,post_name,post_sort,status,remark,create_by,create_time)"
            + " VALUES(?,?,?,?,?,?,NOW())",
        text(b, "postCode", 64, true),
        text(b, "postName", 50, true),
        integer(b.get("postSort"), 0, 0, 9999),
        state(b),
        text(b, "remark", 500, false),
        ShiroUtils.getLoginName());
    return AjaxResult.success();
  }

  @PutMapping("/posts/{id}")
  @Transactional
  public AjaxResult updatePost(@PathVariable Long id, @RequestBody Map<String, Object> b) {
    ops.admin();
    if (count("SELECT COUNT(*) FROM sys_post WHERE post_id=?", id) == 0)
      throw new IllegalArgumentException("岗位不存在");
    validatePost(id, b);
    ops.jdbc.update(
        "UPDATE sys_post SET"
            + " post_code=?,post_name=?,post_sort=?,status=?,remark=?,update_by=?,update_time=NOW()"
            + " WHERE post_id=?",
        text(b, "postCode", 64, true),
        text(b, "postName", 50, true),
        integer(b.get("postSort"), 0, 0, 9999),
        state(b),
        text(b, "remark", 500, false),
        ShiroUtils.getLoginName(),
        id);
    return AjaxResult.success();
  }

  @DeleteMapping("/posts/{id}")
  @Transactional
  public AjaxResult deletePost(@PathVariable Long id) {
    ops.admin();
    if (count(
            "SELECT COUNT(*) FROM sys_user_post p JOIN sys_user u ON u.user_id=p.user_id WHERE"
                + " p.post_id=? AND u.del_flag='0'",
            id)
        > 0) throw new IllegalArgumentException("岗位已分配账号，请先解除关联");
    ops.jdbc.update("DELETE FROM sys_user_post WHERE post_id=?", id);
    ops.jdbc.update("DELETE FROM sys_post WHERE post_id=?", id);
    return AjaxResult.success();
  }

  private void validatePost(Long id, Map<String, Object> b) {
    ops.jdbc.queryForList("SELECT post_id FROM sys_post ORDER BY post_id FOR UPDATE");
    if (!text(b, "postCode", 64, true).matches("[A-Za-z][A-Za-z0-9_-]{1,63}"))
      throw new IllegalArgumentException("岗位编码须为2～64位字母、数字、下划线或短横线，以字母开头");
    if (count(
            "SELECT COUNT(*) FROM sys_post WHERE (post_code=? OR post_name=?) AND (? IS NULL OR"
                + " post_id<>?)",
            text(b, "postCode", 64, true),
            text(b, "postName", 50, true),
            id,
            id)
        > 0) throw new IllegalArgumentException("岗位编码或名称已存在");
  }

  @GetMapping("/fields")
  public AjaxResult fields() {
    ops.admin();
    return AjaxResult.success(
        ops.jdbc.queryForList(
            "SELECT f.field_id AS fieldId,f.field_code AS fieldCode,f.field_name AS fieldName,o.dept_id AS"
                + " deptId,d.dept_name AS deptName,o.owner_user_id AS ownerUserId,u.user_name AS"
                + " ownerName FROM drilling_field f LEFT JOIN drilling_field_organization o ON"
                + " o.field_id=f.field_id LEFT JOIN sys_dept d ON d.dept_id=o.dept_id LEFT JOIN"
                + " sys_user u ON u.user_id=o.owner_user_id WHERE f.status='0' ORDER BY"
                + " f.field_id DESC"));
  }

  @GetMapping("/owners")
  public AjaxResult owners() {
    ops.admin();
    return AjaxResult.success(
        ops.jdbc.queryForList(
            "SELECT u.user_id AS userId,u.user_name AS userName,u.login_name AS loginName FROM"
                + " sys_user u JOIN drilling_account_policy p ON p.user_id=u.user_id WHERE"
                + " u.del_flag='0' AND u.status='0' AND p.account_tier IN ('ADMIN','ENGINEER')"
                + " ORDER BY u.user_id"));
  }

  @PutMapping("/fields/{id}")
  @Transactional
  public AjaxResult fieldOrganization(@PathVariable Long id, @RequestBody Map<String, Object> b) {
    ops.admin();
    scope.requireAccess(id);
    Long dept = DrillingDataScope.number(b.get("deptId")),
        owner = DrillingDataScope.number(b.get("ownerUserId"));
    if (dept != null && !scope.activeDepartment(dept))
      throw new IllegalArgumentException("井场所属部门无效或停用");
    if (owner != null
        && count(
                "SELECT COUNT(*) FROM sys_user u JOIN drilling_account_policy p ON"
                    + " p.user_id=u.user_id WHERE u.user_id=? AND u.del_flag='0' AND u.status='0'"
                    + " AND p.account_tier IN ('ADMIN','ENGINEER')",
                owner)
            == 0) throw new IllegalArgumentException("负责人须为正常管理员或工程师");
    ops.jdbc.update(
        "INSERT INTO drilling_field_organization(field_id,dept_id,owner_user_id,update_by)"
            + " VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " dept_id=VALUES(dept_id),owner_user_id=VALUES(owner_user_id),update_by=VALUES(update_by),update_time=NOW()",
        id,
        dept,
        owner,
        ShiroUtils.getLoginName());
    return AjaxResult.success();
  }

  private int count(String sql, Object... args) {
    return ops.jdbc.queryForObject(sql, Integer.class, args);
  }
}
