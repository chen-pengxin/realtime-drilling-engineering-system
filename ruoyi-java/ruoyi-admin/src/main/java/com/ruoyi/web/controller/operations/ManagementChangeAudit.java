package com.ruoyi.web.controller.operations;

import com.alibaba.fastjson.JSON;
import java.util.*;
import java.util.regex.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads only display settings and identifiers. The row lock ties the snapshots to this write. */
@Service
public class ManagementChangeAudit {
  private final JdbcTemplate jdbc;
  public ManagementChangeAudit(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  private enum Resource {
    MENU("菜单", "menuId", "SELECT m.menu_id,m.menu_name,m.parent_id,m.order_num,m.visible,m.icon,m.perms,s.status FROM sys_menu m LEFT JOIN drilling_menu_setting s ON s.menu_id=m.menu_id WHERE m.menu_id=?"),
    SETTING("平台参数", "configId", "SELECT config_id,config_key,config_name,config_value FROM sys_config WHERE config_id=?"),
    DICTIONARY("字典", "dictId", "SELECT dict_id,dict_type,dict_name,status FROM sys_dict_type WHERE dict_id=?"),
    ITEM("字典项", "dictCode", "SELECT dict_code,dict_type,dict_label,dict_value,dict_sort,status FROM sys_dict_data WHERE dict_code=?"),
    NOTICE("公告", "noticeId", "SELECT notice_id,status FROM sys_notice WHERE notice_id=?"),
    PREFERENCE("个人界面偏好", "userId", "SELECT preferences FROM drilling_ui_preference WHERE user_id=?");

    final String label, key, sql;
    Resource(String label, String key, String sql) { this.label=label; this.key=key; this.sql=sql; }
  }
  private static final Pattern ROUTE = Pattern.compile("/api/operations/(menus|settings|dictionaries|dictionary-items|notices)(?:/([0-9]+))?(/items)?");
  record Change(Resource type, Long id) {}
  static Change resolve(String method, String path, Long userId) {
    if ("GET".equals(method)) return null;
    if (path.equals("/api/access/me/preferences")) return new Change(Resource.PREFERENCE, userId);
    Matcher m = ROUTE.matcher(path);
    if (!m.matches()) return null;
    Resource resource = switch (m.group(1)) {
      case "menus" -> Resource.MENU;
      case "settings" -> Resource.SETTING;
      case "dictionaries" -> m.group(3)==null ? Resource.DICTIONARY : Resource.ITEM;
      case "dictionary-items" -> Resource.ITEM;
      default -> Resource.NOTICE;
    };
    return new Change(resource, "POST".equals(method) || m.group(2)==null ? null : Long.valueOf(m.group(2)));
  }

  private Map<String,Object> snapshot(Resource resource, Long id) {
    if (id == null) return Map.of();
    var rows = jdbc.queryForList(resource.sql+" FOR UPDATE", id);
    if (rows.isEmpty()) return Map.of();
    var row = rows.get(0);
    if (resource == Resource.SETTING && !OperationsSupport.SETTINGS.contains(row.get("config_key")))
      row.remove("config_value");
    if (resource == Resource.PREFERENCE) {
      var prefs = JSON.parseObject(String.valueOf(row.get("preferences")));
      prefs.keySet().retainAll(Set.of("preset","density","tags","breadcrumb"));
      return prefs;
    }
    return row;
  }

  @Transactional(rollbackFor=Throwable.class)
  public Object perform(Change change, String method, ProceedingJoinPoint join, Map<String,Object> details) throws Throwable {
    Resource type = change.type();
    Long id = change.id();
    String verb = switch(method) { case "POST" -> "新增"; case "DELETE" -> "删除"; default -> "修改"; };
    details.put("action", verb+type.label);
    if (id != null) details.put(type.key, id);
    Map<String,Object> before = snapshot(type,id);
    Object result = join.proceed();
    if (id == null && result instanceof Map<?,?> response && response.get("data") instanceof Map<?,?> data
        && data.get(type.key) instanceof Number n) id = n.longValue();
    Map<String,Object> after = snapshot(type,id);
    if (id != null) details.put(type.key, id);
    details.put("before", before);
    details.put("after", after);
    if (type == Resource.NOTICE && !after.isEmpty() && !Objects.equals(before.get("status"), after.get("status"))) {
      details.put("action",switch (String.valueOf(after.get("status"))) {
        case "0" -> "发布公告"; case "1" -> "撤回公告"; default -> "保存公告草稿";
      });
    }
    return result;
  }
}
