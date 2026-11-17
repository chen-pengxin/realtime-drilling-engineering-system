package com.ruoyi.web.controller.drilling;

import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.utils.ShiroUtils;

/** Field-scoped audit history for the drilling basic-function page. */
@RestController
@RequestMapping("/api/drilling/fields/{fieldId}/audit")
public class DrillingAuditController
{
    private final JdbcTemplate jdbc;

    public DrillingAuditController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @RequiresPermissions("drilling:field:view")
    public List<Map<String, Object>> list(@PathVariable Long fieldId,
                                          @RequestParam(defaultValue = "200") int limit)
    {
        requireFieldAccess(fieldId);
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return jdbc.queryForList("""
                SELECT audit_id, field_id, user_id, action, resource_type,
                       resource_id, request_id, detail_json, client_ip, create_time
                  FROM drilling_audit_log
                 WHERE field_id=?
                 ORDER BY audit_id DESC
                 LIMIT ?
                """, fieldId, safeLimit);
    }

    private void requireFieldAccess(Long fieldId)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireAccess(fieldId);
    }
}
