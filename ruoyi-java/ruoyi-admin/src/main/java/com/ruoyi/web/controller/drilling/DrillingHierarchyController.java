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
        return jdbc.queryForList("SELECT block_id, block_code, block_name, basin, location, operator_name, structural_position, remark, source_reference, description, status, create_time, update_time FROM drilling_block b WHERE status='0' AND ("+(ShiroUtils.isAdmin(ShiroUtils.getUserId())?"1=1":"EXISTS(SELECT 1 FROM drilling_field f WHERE f.block_id=b.block_id AND f.status='0' AND ("+com.ruoyi.framework.security.DrillingDataScope.current().predicate("f.field_id")+"))")+") ORDER BY block_name");
    }

    @PostMapping("/blocks")
    @RequiresPermissions("drilling:block:add")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createBlock(@RequestBody Map<String, Object> body)
    {
        requireCatalogAdmin();
        String code = required(body, "blockCode");
        String name = required(body, "blockName");
        jdbc.update("INSERT INTO drilling_block(block_code, block_name, basin, location, operator_name, structural_position, remark, source_reference, description, create_by) VALUES(?,?,?,?,?,?,?,?,?,?)",
                code, name, text(body, "basin"), text(body, "location"), text(body, "operatorName"), text(body, "structuralPosition"), text(body, "remark"), text(body, "sourceReference"), text(body, "description"), ShiroUtils.getLoginName());
        return jdbc.queryForMap("SELECT * FROM drilling_block WHERE block_code=?", code);
    }

    @PutMapping("/blocks/{blockId}")
    @RequiresPermissions("drilling:block:edit")
    @Transactional
    public Map<String, Object> updateBlock(@PathVariable Long blockId, @RequestBody Map<String, Object> body)
    {
        requireCatalogAdmin();
        requireActiveBlock(blockId);
        jdbc.update("UPDATE drilling_block SET block_name=COALESCE(?,block_name), basin=COALESCE(?,basin), location=COALESCE(?,location), operator_name=COALESCE(?,operator_name), structural_position=COALESCE(?,structural_position), remark=COALESCE(?,remark), source_reference=COALESCE(?,source_reference), description=COALESCE(?,description), update_by=?, update_time=CURRENT_TIMESTAMP WHERE block_id=? AND status='0'",
                optional(body, "blockName"), optional(body, "basin"), optional(body, "location"), optional(body, "operatorName"), optional(body, "structuralPosition"), optional(body, "remark"), optional(body, "sourceReference"), optional(body, "description"), ShiroUtils.getLoginName(), blockId);
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
        List<Long> fields=jdbc.queryForList("SELECT field_id FROM drilling_field WHERE block_id=? AND status='0'",Long.class,blockId);
        ensureDeletionAllowed(fields);
        if(!fields.isEmpty())cascadeFields(fields);
        jdbc.update("UPDATE drilling_block SET status='1', update_by=?, update_time=CURRENT_TIMESTAMP WHERE block_id=?", ShiroUtils.getLoginName(), blockId);
    }

    @GetMapping("/fields")
    @RequiresPermissions("drilling:field:list")
    public List<Map<String, Object>> fields(@RequestParam(required=false) Long blockId)
    {
        return jdbc.queryForList("SELECT f.field_id, f.block_id, b.block_name, f.field_code, f.field_name, f.operator_name, f.location, f.structural_position, f.remark, f.source_reference, f.description, f.status, f.create_time, f.update_time FROM drilling_field f JOIN drilling_block b ON b.block_id=f.block_id WHERE f.status='0' AND b.status='0' AND (? IS NULL OR f.block_id=?) AND ("+(ShiroUtils.isAdmin(ShiroUtils.getUserId())?"1=1":com.ruoyi.framework.security.DrillingDataScope.current().predicate("f.field_id"))+") ORDER BY f.field_name", blockId, blockId);
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
        jdbc.update("INSERT INTO drilling_field(block_id, field_code, field_name, operator_name, location, structural_position, remark, source_reference, description, create_by) VALUES(?,?,?,?,?,?,?,?,?,?)",
                blockId, code, required(body, "fieldName"), text(body, "operatorName"), text(body, "location"), text(body, "structuralPosition"), text(body, "remark"), text(body, "sourceReference"), text(body, "description"), ShiroUtils.getLoginName());
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
        jdbc.update("UPDATE drilling_field SET block_id=COALESCE(?,block_id), field_name=COALESCE(?,field_name), operator_name=COALESCE(?,operator_name), location=COALESCE(?,location), structural_position=COALESCE(?,structural_position), remark=COALESCE(?,remark), source_reference=COALESCE(?,source_reference), description=COALESCE(?,description), update_by=?, update_time=CURRENT_TIMESTAMP WHERE field_id=? AND status='0'",
                blockId, optional(body, "fieldName"), optional(body, "operatorName"), optional(body, "location"), optional(body, "structuralPosition"), optional(body, "remark"), optional(body, "sourceReference"), optional(body, "description"), ShiroUtils.getLoginName(), fieldId);
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
        ensureDeletionAllowed(List.of(fieldId));
        cascadeFields(List.of(fieldId));
        jdbc.update("UPDATE drilling_field SET status='1', update_by=?, update_time=CURRENT_TIMESTAMP WHERE field_id=?", ShiroUtils.getLoginName(), fieldId);
    }

    private void ensureDeletionAllowed(List<Long> fields){
        if(fields.isEmpty())return;
        String marks=String.join(",",java.util.Collections.nCopies(fields.size(),"?"));
        Integer drilled=jdbc.queryForObject("SELECT COUNT(*) FROM drilling_well WHERE field_id IN ("+marks+") AND status IN ('COMPLETED','ABANDONED')",Integer.class,fields.toArray());
        if(drilled!=null&&drilled>0)throw new IllegalArgumentException("下辖已有已钻井，禁止删除");
        Integer completedBores=jdbc.queryForObject("SELECT COUNT(*) FROM drilling_wellbore b JOIN drilling_well w ON w.well_id=b.well_id WHERE w.field_id IN ("+marks+") AND b.engineering_status='COMPLETED'",Integer.class,fields.toArray());
        if(completedBores!=null&&completedBores>0)throw new IllegalArgumentException("下辖已有已钻井，禁止删除");
        Integer risky=jdbc.queryForObject("SELECT COUNT(*) FROM anti_collision_result r JOIN anti_collision_task t ON t.task_id=r.task_id WHERE r.sf<=1.5 AND (t.reference_well_id IN (SELECT well_id FROM drilling_well WHERE field_id IN ("+marks+")) OR r.neighbor_well_id IN (SELECT well_id FROM drilling_well WHERE field_id IN ("+marks+")))",Integer.class,java.util.stream.Stream.concat(fields.stream(),fields.stream()).toArray());
        if(risky!=null&&risky>0)throw new IllegalArgumentException("下辖井参与了分离系数≤1.5的防碰关系，禁止删除");
        for(Long bore:jdbc.queryForList("SELECT b.wellbore_id FROM drilling_wellbore b JOIN drilling_well w ON w.well_id=b.well_id WHERE w.field_id IN ("+marks+")",Long.class,fields.toArray()))EngineeringUsageGuard.requireBoreAvailable(jdbc,bore);
    }
    private void cascadeFields(List<Long> fields){
        String marks=String.join(",",java.util.Collections.nCopies(fields.size(),"?"));
        jdbc.update("UPDATE drilling_engineering_node SET status='ARCHIVED',update_time=NOW() WHERE field_id IN ("+marks+") AND status='ACTIVE'",fields.toArray());
        jdbc.update("UPDATE drilling_wellbore b JOIN drilling_well w ON w.well_id=b.well_id SET b.engineering_status='ABANDONED',b.update_time=NOW() WHERE w.field_id IN ("+marks+")",fields.toArray());
        jdbc.update("UPDATE drilling_well SET status='ARCHIVED',update_by=?,update_time=NOW() WHERE field_id IN ("+marks+") AND status<>'ARCHIVED'",java.util.stream.Stream.concat(java.util.stream.Stream.of(ShiroUtils.getLoginName()),fields.stream()).toArray());
        jdbc.update("UPDATE drilling_field SET status='1',update_by=?,update_time=NOW() WHERE field_id IN ("+marks+")",java.util.stream.Stream.concat(java.util.stream.Stream.of(ShiroUtils.getLoginName()),fields.stream()).toArray());
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
