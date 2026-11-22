package com.ruoyi.web.controller.operations;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.ShiroUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operations/backups")
public class BackupController {
    private final OperationsSupport ops;
    private static final String BACKUP_ID = "[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}";
    public BackupController(OperationsSupport ops) { this.ops = ops; }

    private JSONObject run(String action, String value, boolean confirmed) throws Exception {
        String worker = System.getenv("DRILLING_BACKUP_WORKER");
        String python = System.getenv("DRILLING_BACKUP_PYTHON");
        if (worker == null || python == null) throw new IllegalStateException("本部署尚未配置备份程序");
        var command = new ArrayList<>(List.of(python,worker,action,value));
        if (confirmed) command.addAll(List.of("--confirm",value));
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("DRILLING_BACKUP_ACTOR",ShiroUtils.getLoginName());
        Process process;
        try { process = builder.start(); }
        catch (IOException ex) { throw new IllegalStateException("备份程序启动失败，请检查部署配置"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        JSONObject result;
        try { result = JSON.parseObject(output); }
        catch (RuntimeException invalid) { result = null; }
        if (!action.equals("status") && result != null) {
            Map<String,Object> detail = new LinkedHashMap<>();
            for (String key : List.of("operationId","backupId","recovery","error","warning"))
                if (result.get(key) instanceof String text) detail.put(key,text);
            ServletUtils.getRequest().setAttribute("drilling.backupDetail",detail);
        }
        if (result == null || exit != 0 || result.containsKey("error"))
            throw new IllegalStateException(result != null && result.getString("error") != null
                    ? result.getString("error") : "备份程序未能完成操作，请检查目录权限和可用空间");
        return result;
    }

    @GetMapping
    public AjaxResult list() throws Exception {
        ops.admin();
        JSONObject status;
        try { status = run("status","",false); }
        catch (IOException | IllegalStateException unavailable) {
            status = new JSONObject();
            status.put("records",new com.alibaba.fastjson.JSONArray());
            status.put("history",new com.alibaba.fastjson.JSONArray());
            status.put("inventoryError","备份目录或备份程序不可用，请检查部署配置");
        }
        status.put("days",ops.intSetting("drilling.backup.days",30));
        // Include application failures that occurred before the worker could create a journal.
        var history = status.getJSONArray("history");
        history.addAll(ops.jdbc.queryForList("""
            SELECT IF(resource_path LIKE '%/restore','restore','backup') AS action,actor_name AS actor,
                   create_time AS finished,outcome,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.error')),'请求未能完成，请检查备份配置和权限') AS reason
              FROM drilling_system_audit WHERE http_method='POST' AND outcome='FAILURE'
               AND (resource_path='/api/operations/backups' OR resource_path LIKE '/api/operations/backups/%/restore')
               AND JSON_EXTRACT(detail_json,'$.operationId') IS NULL
             ORDER BY event_id DESC LIMIT 100
            """));
        return AjaxResult.success(status);
    }

    @PostMapping
    public AjaxResult create() throws Exception {
        ops.admin();
        return AjaxResult.success(run("backup",Integer.toString(ops.intSetting("drilling.backup.days",30)),false));
    }

    @PostMapping("/{id}/restore")
    public AjaxResult restore(@PathVariable String id, @RequestBody Map<String,String> body) throws Exception {
        ops.admin();
        if (!id.matches(BACKUP_ID)) throw new IllegalArgumentException("备份编号格式错误");
        return AjaxResult.success(run("restore",id,id.equals(body.get("confirmation"))));
    }
}
