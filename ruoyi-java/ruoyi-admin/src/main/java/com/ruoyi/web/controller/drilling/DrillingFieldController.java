package com.ruoyi.web.controller.drilling;

import java.util.Map;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read access to the field scope used by engineering APIs. */
@RestController
@RequestMapping("/api/drilling/fields")
public class DrillingFieldController {
  private final JdbcTemplate jdbc;

  public DrillingFieldController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/{fieldId}")
  @RequiresPermissions("drilling:field:view")
  public Map<String, Object> get(@PathVariable Long fieldId) {
    com.ruoyi.framework.security.DrillingDataScope.current().requireAccess(fieldId);
    return jdbc.queryForMap(
        "SELECT f.field_id,f.block_id,b.block_name,f.field_code,f.field_name,f.operator_name,"
            + "f.description,f.status FROM drilling_field f JOIN drilling_block b ON"
            + " b.block_id=f.block_id WHERE f.field_id=? AND f.status='0' AND b.status='0'",
        fieldId);
  }
}
