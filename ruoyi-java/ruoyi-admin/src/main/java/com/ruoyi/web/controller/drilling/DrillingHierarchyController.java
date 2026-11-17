package com.ruoyi.web.controller.drilling;

import java.util.List;
import java.util.Map;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.utils.ShiroUtils;

/** CRUD for the shared block -> field hierarchy. */
@RestController
@RequestMapping("/api/drilling")
public class DrillingHierarchyController
{
    private final JdbcTemplate jdbc;

    public DrillingHierarchyController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/blocks")
    @RequiresPermissions("drilling:block:list")
    public List<Map<String, Object>> blocks()
    {
        return jdbc.queryForList("SELECT block_id, block_code, block_name, basin, description, status, create_time, update_time FROM drilling_block b WHERE status='0' AND ("+(ShiroUtils.isAdmin(ShiroUtils.getUserId())?"1=1":"EXISTS(SELECT 1 FROM drilling_field f WHERE f.block_id=b.block_id AND f.status='0' AND ("+com.ruoyi.framework.security.DrillingDataScope.current().predicate("f.field_id")+"))")+") ORDER BY block_name");
    }

    @PostMapping("/blocks")
    @RequiresPermissions("drilling:block:add")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createBlock(@RequestBody Map<String, Object> body)
    {
        requireCatalogAdmin();
        String code = required(body, "blockCode");
        String name = required(body, "blockName");
        jdbc.update("INSERT INTO drilling_block(block_code, block_name, basin, description, create_by) VALUES(?,?,?,?,?)",
                code, name, text(body, "basin"), text(body, "description"), ShiroUtils.getLoginName());
        return jdbc.queryForMap("SELECT * FROM drilling_block WHERE block_code=?", code);
    }

    @PutMapping("/blocks/{blockId}")
    @RequiresPermissions("drilling:block:edit")
    @Transactional
    public Map<String, Object> updateBlock(@PathVariable Long blockId, @RequestBody Map<String, Object> body)
    {
        requireCatalogAdmin();
        requireActiveBlock(blockId);
        jdbc.update("UPDATE drilling_block SET block_name=COALESCE(?,block_name), basin=COALESCE(?,basin), description=COALESCE(?,description), update_by=?, update_time=CURRENT_TIMESTAMP WHERE block_id=? AND status='0'",
                optional(body, "blockName"), optional(body, "basin"), optional(body, "description"), ShiroUtils.getLoginName(), blockId);
        return jdbc.queryForMap("SELECT * FROM drilling_block WHERE block_id=?", blockId);
    }

    @DeleteMapping("/blocks/{blockId}")
    @RequiresPermissions("drilling:block:remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void archiveBlock(@PathVariable Long blockId)
    {
        requireCatalogAdmin();
        requireActiveBlock(blockId);
        Integer activeFields = jdbc.queryForObject("SELECT COUNT(*) FROM drilling_field WHERE block_id=? AND status='0'", Integer.class, blockId);
        if (activeFields != null && activeFields > 0)
            throw new IllegalArgumentException("cannot archive a block while it has active fields; archive its fields first");
        jdbc.update("UPDATE drilling_block SET status='1', update_by=?, update_time=CURRENT_TIMESTAMP WHERE block_id=?", ShiroUtils.getLoginName(), blockId);
    }

    @GetMapping("/fields")
    @RequiresPermissions("drilling:field:list")
    public List<Map<String, Object>> fields(@RequestParam(required=false) Long blockId)
    {
        return jdbc.queryForList("SELECT f.field_id, f.block_id, b.block_name, f.field_code, f.field_name, f.operator_name, f.description, f.status, f.create_time, f.update_time FROM drilling_field f JOIN drilling_block b ON b.block_id=f.block_id WHERE f.status='0' AND b.status='0' AND (? IS NULL OR f.block_id=?) AND ("+(ShiroUtils.isAdmin(ShiroUtils.getUserId())?"1=1":com.ruoyi.framework.security.DrillingDataScope.current().predicate("f.field_id"))+") ORDER BY f.field_name", blockId, blockId);
    }

    @PostMapping("/fields")
    @RequiresPermissions("drilling:field:add")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createField(@RequestBody Map<String, Object> body)
    {
        requireCatalogAdmin();
        Long blockId = longValue(body.get("blockId"));
        if (blockId == null) throw new IllegalArgumentException("blockId is required");
        requireActiveBlock(blockId);
        String code = required(body, "fieldCode");
        jdbc.update("INSERT INTO drilling_field(block_id, field_code, field_name, operator_name, description, create_by) VALUES(?,?,?,?,?,?)",
                blockId, code, required(body, "fieldName"), text(body, "operatorName"), text(body, "description"), ShiroUtils.getLoginName());
        Long fieldId=jdbc.queryForObject("SELECT field_id FROM drilling_field WHERE field_code=?",Long.class,code);
        com.ruoyi.framework.security.DrillingDataScope.current().initializeField(fieldId);
        return jdbc.queryForMap("SELECT * FROM drilling_field WHERE field_id=?", fieldId);
    }

    @PutMapping("/fields/{fieldId}")
    @RequiresPermissions("drilling:field:edit")
    @Transactional
    public Map<String, Object> updateField(@PathVariable Long fieldId, @RequestBody Map<String, Object> body)
    {
        requireCatalogAdmin();
        requireActiveField(fieldId);
        Long blockId = longValue(body.get("blockId"));
        if (blockId != null) requireActiveBlock(blockId);
        jdbc.update("UPDATE drilling_field SET block_id=COALESCE(?,block_id), field_name=COALESCE(?,field_name), operator_name=COALESCE(?,operator_name), description=COALESCE(?,description), update_by=?, update_time=CURRENT_TIMESTAMP WHERE field_id=? AND status='0'",
                blockId, optional(body, "fieldName"), optional(body, "operatorName"), optional(body, "description"), ShiroUtils.getLoginName(), fieldId);
        return jdbc.queryForMap("SELECT * FROM drilling_field WHERE field_id=?", fieldId);
    }

    @DeleteMapping("/fields/{fieldId}")
    @RequiresPermissions("drilling:field:remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void archiveField(@PathVariable Long fieldId)
    {
        requireCatalogAdmin();
        requireActiveField(fieldId);
        Integer activeWells = jdbc.queryForObject("SELECT COUNT(*) FROM drilling_well WHERE field_id=? AND status<>'ARCHIVED'", Integer.class, fieldId);
        if (activeWells != null && activeWells > 0)
            throw new IllegalArgumentException("cannot archive a field while it has active wells; archive its wells first");
        jdbc.update("UPDATE drilling_field SET status='1', update_by=?, update_time=CURRENT_TIMESTAMP WHERE field_id=?", ShiroUtils.getLoginName(), fieldId);
    }

    private void requireActiveBlock(Long blockId)
    {
        Long activeId = jdbc.query("SELECT block_id FROM drilling_block WHERE block_id=? AND status='0' FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : null, blockId);
        if (activeId == null) throw new IllegalArgumentException("active block does not exist");
    }

    private void requireActiveField(Long fieldId)
    {
        Long activeId = jdbc.query("SELECT f.field_id FROM drilling_field f JOIN drilling_block b ON b.block_id=f.block_id WHERE f.field_id=? AND f.status='0' AND b.status='0' FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : null, fieldId);
        if (activeId == null) throw new IllegalArgumentException("active field does not exist");
    }

    /** Blocks and fields are shared catalog data, so only RuoYi administrators can mutate them. */
    private void requireCatalogAdmin()
    {
        Long userId = ShiroUtils.getUserId();
        if (userId == null || !ShiroUtils.isAdmin(userId))
            throw new IllegalArgumentException("shared block and field catalogs require administrator privileges");
    }

    private static String required(Map<String,Object> body, String key)
    {
        String value = optional(body, key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private static String text(Map<String,Object> body, String key) { return optional(body, key); }
    private static String optional(Map<String,Object> body, String key) { Object value = body.get(key); return value == null ? null : String.valueOf(value).trim(); }
    private static Long longValue(Object value) { if (value == null || String.valueOf(value).isBlank()) return null; try { return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value)); } catch (NumberFormatException e) { throw new IllegalArgumentException("numeric id is invalid"); } }
}
