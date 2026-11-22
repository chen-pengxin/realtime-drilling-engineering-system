package com.ruoyi.web.controller.operations;

import static com.ruoyi.web.controller.operations.OperationsSupport.*;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingAccountPolicy;
import com.ruoyi.framework.shiro.util.AuthorizationUtils;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class MenuManagementController {
  private final OperationsSupport ops;
  private final DrillingAccountPolicy accounts;
  private static final Set<String> ESSENTIAL =
      Set.of(
          "/access",
          "/access/organization",
          "/access/permissions",
          "/access/users",
          "/access/roles",
          "/access/menus");

  public MenuManagementController(OperationsSupport ops, DrillingAccountPolicy accounts) {
    this.ops = ops;
    this.accounts = accounts;
  }

  @GetMapping("/api/operations/menus")
  public AjaxResult list() {
    ops.admin();
    return AjaxResult.success(rows());
  }

  private List<Map<String, Object>> rows() {
    return ops.jdbc.queryForList(
        "SELECT m.menu_id AS menuId,m.parent_id AS parentId,m.menu_name AS menuName,m.order_num AS"
            + " orderNum,m.menu_type AS menuType,m.visible,m.perms,m.icon,s.route_path AS"
            + " routePath,s.module_key AS moduleKey,s.status,s.builtin FROM sys_menu m JOIN"
            + " drilling_menu_setting s ON s.menu_id=m.menu_id ORDER BY m.order_num,m.menu_id");
  }

  @GetMapping("/api/reference/navigation")
  public AjaxResult navigation() {
    boolean admin = "ADMIN".equals(accounts.tier(ShiroUtils.getUserId()));
    List<Map<String, Object>> all = rows();
    Set<Long> disabled = new HashSet<>();
    for (var m : all)
      if ("1".equals(m.get("status"))) disabled.add(((Number) m.get("menuId")).longValue());
    boolean changed;
    do {
      changed = false;
      for (var m : all)
        if (disabled.contains(((Number) m.get("parentId")).longValue()))
          changed |= disabled.add(((Number) m.get("menuId")).longValue());
    } while (changed);
    for (var m : all)
      m.put(
          "effectiveStatus", disabled.contains(((Number) m.get("menuId")).longValue()) ? "1" : "0");
    return AjaxResult.success(
        all.stream()
            .filter(
                m ->
                    m.get("routePath") != null
                        && (admin
                            || "/index".equals(m.get("routePath"))
                            || String.valueOf(m.get("routePath")).startsWith("/drilling")
                            || String.valueOf(m.get("routePath")).startsWith("/navigation")))
            .toList());
  }

  @PostMapping("/api/operations/menus")
  @Transactional
  public AjaxResult create(@RequestBody Map<String, Object> b) {
    ops.admin();
    lock();
    validate(null, b);
    String type = text(b, "menuType", 1, true);
    if (!Set.of("M", "F").contains(type))
      throw new IllegalArgumentException("业务页面已注册，请修改现有页面；可新增目录或操作权限");
    Long id =
        ops.jdbc.queryForObject(
            "SELECT GREATEST(8000,COALESCE(MAX(menu_id),0)+1) FROM sys_menu", Long.class);
    String path = type.equals("M") ? "/navigation/" + id : null;
    ops.jdbc.update(
        "INSERT INTO"
            + " sys_menu(menu_id,menu_name,parent_id,order_num,url,target,menu_type,visible,is_refresh,perms,icon,create_by,create_time)"
            + " VALUES(?,?,?,?,?,'',?,?,'1',?,?,?,NOW())",
        id,
        text(b, "menuName", 50, true),
        parent(b),
        integer(b.get("orderNum"), 0, 0, 9999),
        path == null ? "#" : path,
        type,
        text(b, "visible", 1, true),
        type.equals("F") ? text(b, "perms", 100, true) : "",
        text(b, "icon", 100, false),
        ShiroUtils.getLoginName());
    ops.jdbc.update(
        "INSERT INTO drilling_menu_setting(menu_id,route_path,status) VALUES(?,?,?)",
        id,
        path,
        state(b));
    AuthorizationUtils.clearAllCachedAuthorizationInfo();
    return AjaxResult.success(Map.of("menuId", id));
  }

  @PutMapping("/api/operations/menus/{id}")
  @Transactional
  public AjaxResult update(@PathVariable Long id, @RequestBody Map<String, Object> b) {
    ops.admin();
    lock();
    Map<String, Object> old = menu(id);
    validate(old, b);
    if (!old.get("menuType").equals(text(b, "menuType", 1, true)))
      throw new IllegalArgumentException("菜单类型不可修改");
    if (ESSENTIAL.contains(String.valueOf(old.get("routePath")))
        && (!state(b).equals("0")
            || !text(b, "visible", 1, true).equals("0")
            || parent(b).longValue() != ((Number) old.get("parentId")).longValue()))
      throw new IllegalArgumentException("账号、角色和菜单管理入口必须保留且可见");
    ops.jdbc.update(
        "UPDATE sys_menu SET"
            + " menu_name=?,parent_id=?,order_num=?,visible=?,icon=?,perms=?,update_by=?,update_time=NOW()"
            + " WHERE menu_id=?",
        text(b, "menuName", 50, true),
        parent(b),
        integer(b.get("orderNum"), 0, 0, 9999),
        text(b, "visible", 1, true),
        text(b, "icon", 100, false),
        "F".equals(old.get("menuType"))
            ? text(b, "perms", 100, true)
            : Objects.toString(old.get("perms"), ""),
        ShiroUtils.getLoginName(),
        id);
    ops.jdbc.update("UPDATE drilling_menu_setting SET status=? WHERE menu_id=?", state(b), id);
    AuthorizationUtils.clearAllCachedAuthorizationInfo();
    return AjaxResult.success();
  }

  @DeleteMapping("/api/operations/menus/{id}")
  @Transactional
  public AjaxResult delete(@PathVariable Long id) {
    ops.admin();
    lock();
    var m = menu(id);
    if (Boolean.TRUE.equals(m.get("builtin"))
        || (m.get("builtin") instanceof Number n && n.intValue() != 0))
      throw new IllegalArgumentException("已实现的业务页面可配置显隐和启停，不可删除路由");
    if (ops.jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_menu WHERE parent_id=?", Integer.class, id)
        > 0) throw new IllegalArgumentException("请先移除下级菜单");
    if (ops.jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_role_menu WHERE menu_id=?", Integer.class, id)
        > 0) throw new IllegalArgumentException("权限仍被角色使用，请先解除角色授权");
    ops.jdbc.update("DELETE FROM drilling_menu_setting WHERE menu_id=?", id);
    ops.jdbc.update("DELETE FROM sys_menu WHERE menu_id=?", id);
    AuthorizationUtils.clearAllCachedAuthorizationInfo();
    return AjaxResult.success();
  }

  private void lock() {
    ops.jdbc.queryForList("SELECT menu_id FROM drilling_menu_setting ORDER BY menu_id FOR UPDATE");
  }

  private Map<String, Object> menu(Long id) {
    return rows().stream()
        .filter(m -> ((Number) m.get("menuId")).longValue() == id)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("菜单不存在"));
  }

  private Long parent(Map<String, Object> b) {
    try {
      return Long.parseLong(String.valueOf(b.getOrDefault("parentId", 0)));
    } catch (Exception e) {
      throw new IllegalArgumentException("上级菜单无效");
    }
  }

  private void validate(Map<String, Object> existing, Map<String, Object> b) {
    Long id = existing == null ? null : ((Number) existing.get("menuId")).longValue();
    String type = text(b, "menuType", 1, true);
    if (!Set.of("M", "C", "F").contains(type)) throw new IllegalArgumentException("菜单类型无效");
    if (!Set.of("0", "1").contains(text(b, "visible", 1, true)))
      throw new IllegalArgumentException("显示状态无效");
    state(b);
    text(b, "menuName", 50, true);
    Set<Long> seen = new HashSet<>();
    Long p = parent(b);
    boolean managementParent = false;
    while (p != 0) {
      if (Objects.equals(p, id) || !seen.add(p)) throw new IllegalArgumentException("菜单层级不能循环");
      var m = menu(p);
      String ancestorPath = Objects.toString(m.get("routePath"), "");
      if (ancestorPath.equals("/access") || ancestorPath.equals("/operations") || ancestorPath.startsWith("/access/") || ancestorPath.startsWith("/operations/"))
        managementParent = true;
      if ("F".equals(m.get("menuType"))) throw new IllegalArgumentException("操作权限不能包含下级菜单");
      p = ((Number) m.get("parentId")).longValue();
    }
    if (type.equals("F")) {
      if (parent(b) == 0) throw new IllegalArgumentException("操作权限须归属业务菜单");
      String perm = text(b, "perms", 100, true);
      if (!perm.matches("drilling:(?:[A-Za-z0-9_-]+:)+[A-Za-z0-9_-]+"))
        throw new IllegalArgumentException("权限标识须以 drilling 开头，后接资源和操作，资源可分多级");
    }
    if (!type.equals("F") && parent(b) != 0) {
      var parentMenu = menu(parent(b));
      boolean navigation = existing == null || existing.get("routePath") != null;
      if (!"M".equals(parentMenu.get("menuType")))
        throw new IllegalArgumentException("目录和页面的上级须为目录");
      if (navigation && parentMenu.get("routePath") == null)
        throw new IllegalArgumentException("导航菜单不能放在业务操作权限分组下");
    }
    if (managementParent && id != null && parent(b).longValue() != ((Number)existing.get("parentId")).longValue()) {
      // The moved directory may be a custom folder containing registered engineering pages.
      long engineering = ops.jdbc.queryForObject("""
          WITH RECURSIVE subtree AS (
            SELECT menu_id FROM sys_menu WHERE menu_id=?
            UNION ALL SELECT m.menu_id FROM sys_menu m JOIN subtree t ON m.parent_id=t.menu_id
          ) SELECT COUNT(*) FROM subtree t JOIN drilling_menu_setting s ON s.menu_id=t.menu_id
            WHERE s.route_path='/drilling' OR s.route_path LIKE '/drilling/%'
          """, Long.class, id);
      if (engineering > 0) throw new IllegalArgumentException("工程功能须保留在系统管理和运行维护之外");
    }
    // Admin pages cannot be moved under an engineering-only navigation directory.
    if (id != null) {
      String route = Objects.toString(existing.get("routePath"), "");
      if ((route.startsWith("/access/") || route.startsWith("/operations/")) && parent(b) != 0) {
        String parentPath = Objects.toString(menu(parent(b)).get("routePath"), "");
        if (!(parentPath.startsWith("/access") || parentPath.startsWith("/operations")))
          throw new IllegalArgumentException("管理页面须保留在系统管理或运行维护内");
      }
    }
  }
}
