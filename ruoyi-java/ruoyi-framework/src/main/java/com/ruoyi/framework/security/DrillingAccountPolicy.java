package com.ruoyi.framework.security;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.framework.shiro.service.SysPasswordService;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

/** Database-backed account policy. Checked on every request, including existing sessions. */
@Service
public class DrillingAccountPolicy {
    public static final String INITIAL_PASSWORD = "123456";
    public static final List<String> MODULES = List.of("realtime", "anticollision", "prediction", "models", "replanning",
            "catalog", "surveys", "realtime-data", "config", "files", "alerts", "history", "quality", "reports", "connectors", "versions");
    public static final Map<String, String> LABELS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        String[] labels = {"实时大屏", "防碰评价", "预测展示", "模型仓库", "轨迹再规划", "工程资料目录", "井与测斜数据", "实时数据接入", "系统配置", "文件与审计", "告警中心", "任务历史", "数据质量中心", "报告中心", "连接器管理", "版本与变更记录"};
        for (int i=0; i<MODULES.size(); i++) m.put(MODULES.get(i), labels[i]);
        LABELS = Collections.unmodifiableMap(m);
    }
    private final JdbcTemplate jdbc;
    private final SysPasswordService passwords;
    @org.springframework.beans.factory.annotation.Autowired private com.ruoyi.system.service.ISysConfigService systemConfigs;
    public DrillingAccountPolicy(JdbcTemplate jdbc, SysPasswordService passwords) { this.jdbc=jdbc; this.passwords=passwords; }
    @PostConstruct
    public void initialize() {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) c -> {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("drilling/account-security.sql")); return null;
        });

    }
    public void auditDenied(String path,String method,String ip) {
        try {
            jdbc.update("INSERT INTO drilling_system_audit(actor_user_id,actor_name,action,request_id,resource_path,http_method,outcome,client_ip) VALUES(?,?,'权限拒绝',?,?,?,'FAILURE',?)",
                com.ruoyi.common.utils.ShiroUtils.getUserId(),com.ruoyi.common.utils.ShiroUtils.getLoginName(),UUID.randomUUID().toString(),path.substring(0,Math.min(500,path.length())),method,ip);
        } catch (org.springframework.dao.DataAccessException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Unable to record authorization denial");
        }
    }

    public boolean sessionRevoked(String sessionId) {
        try {
            String fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return jdbc.queryForObject("SELECT COUNT(*) FROM drilling_revoked_session WHERE session_key=? AND expires_at>NOW()",Integer.class,fingerprint)>0;
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    public long sessionTimeoutMillis() {
        try {int minutes=Integer.parseInt(systemConfigs.selectConfigByKey("drilling.session.timeoutMinutes"));return Math.max(5,Math.min(240,minutes))*60000L;}
        catch(Exception e){return 30*60000L;}
    }
    public Long resolveUserId(String identifier) {
        List<Long> ids=jdbc.queryForList("SELECT user_id FROM drilling_account_identifier WHERE identifier=?",Long.class,identifier);
        return ids.isEmpty()?null:ids.get(0);
    }
    /** Locks the owning account and reserves its name in the same namespace as immutable IDs. */
    public void rename(Long userId,String name) {
        String login=jdbc.queryForObject("SELECT login_name FROM sys_user WHERE user_id=? AND del_flag='0' FOR UPDATE",String.class,userId);
        Long owner=resolveUserId(name);
        if(owner!=null && !owner.equals(userId)) throw new IllegalArgumentException("账号名称已被使用，请选择其他名称");
        jdbc.update("DELETE FROM drilling_account_identifier WHERE user_id=? AND identifier<>?",userId,login);
        jdbc.update("INSERT IGNORE INTO drilling_account_identifier(identifier,user_id) VALUES (?,?)",name,userId);
        if(!userId.equals(resolveUserId(name))) throw new IllegalArgumentException("账号名称已被使用，请选择其他名称");
        jdbc.update("UPDATE sys_user SET user_name=?,update_time=NOW() WHERE user_id=?",name,userId);
    }
    public Map<String,Object> policy(Long id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM drilling_account_policy WHERE user_id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("账号未配置权限，请联系系统管理员");
        return rows.get(0);
    }
    public String tier(Long id) { return String.valueOf(policy(id).get("account_tier")); }
    public boolean mustChange(SysUser user) {
        Object v=policy(user.getUserId()).get("must_change_password");
        return Boolean.TRUE.equals(v) || (v instanceof Number n && n.intValue()!=0) || passwords.matches(user, INITIAL_PASSWORD);
    }
    public String unavailable(SysUser user) {
        if (user==null || !"0".equals(user.getDelFlag()) || !"0".equals(user.getStatus())) return "账号已停用或删除，请联系系统管理员";
        if (jdbc.queryForObject("SELECT COUNT(*) FROM drilling_account_lock WHERE user_id=?",Integer.class,user.getUserId())>0) return "账号已锁定，请联系管理员";
        Map<String,Object> p=policy(user.getUserId());
        if ("GUEST".equals(p.get("account_tier"))) {
            LocalDateTime now=LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            LocalDateTime from=date(p.get("valid_from")), until=date(p.get("valid_until"));
            if (from==null || until==null) return "游客账号未设置有效访问时段，请联系系统管理员";
            if (now.isBefore(from)) return "游客账号尚未到可访问时间";
            if (!now.isBefore(until)) return "游客账号已超过有效访问时间";
        }
        return null;
    }
    public static LocalDateTime date(Object v) {
        if(v==null) return null;
        if(v instanceof Timestamp t) return t.toLocalDateTime();
        if(v instanceof LocalDateTime t) return t;
        return LocalDateTime.parse(String.valueOf(v).replace(' ', 'T'));
    }
    public static List<String> strings(Object value) {
        if(value==null) return List.of();
        Object v=value instanceof String s ? JSON.parse(s) : value;
        if(!(v instanceof Collection<?> c)) throw new IllegalArgumentException("功能权限应为模块列表");
        return c.stream().map(String::valueOf).distinct().toList();
    }
    public List<String> modules(Long id) {
        Map<String,Object> p=policy(id); String tier=String.valueOf(p.get("account_tier"));
        if ("ADMIN".equals(tier)) return MODULES;
        List<String> role=strings(jdbc.queryForObject("SELECT modules_json FROM drilling_role_policy WHERE account_tier=?", String.class, tier));
        List<String> own="GUEST".equals(tier)?strings(p.get("modules_json")):MODULES;
        Set<String> assigned=new HashSet<>();
        for(String json:jdbc.queryForList("SELECT rm.modules_json FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id AND r.status='0' AND r.del_flag='0' JOIN drilling_role_module rm ON rm.role_id=r.role_id WHERE ur.user_id=? AND rm.account_tier=?",String.class,id,tier)) assigned.addAll(strings(json));
        return MODULES.stream().filter(x->role.contains(x)&&own.contains(x)&&assigned.contains(x)&&moduleEnabled(x)).toList();
    }
    private boolean moduleEnabled(String module) {
        return jdbc.queryForObject("WITH RECURSIVE chain AS (SELECT s.menu_id,m.parent_id,s.status FROM drilling_menu_setting s JOIN sys_menu m ON m.menu_id=s.menu_id WHERE s.module_key=? UNION ALL SELECT a.menu_id,m.parent_id,a.status FROM chain c JOIN drilling_menu_setting a ON a.menu_id=c.parent_id JOIN sys_menu m ON m.menu_id=a.menu_id) SELECT COUNT(*) FROM chain WHERE status='1'",Integer.class,module)==0;
    }
    public Set<String> permissions(Long id) {
        if ("ADMIN".equals(tier(id))) return Set.of("*:*:*");
        if ("GUEST".equals(tier(id))) return Set.of("drilling:demo:view");
        // Field visibility is enforced independently by DrillingDataScope.
        return new HashSet<>(jdbc.queryForList("WITH RECURSIVE inactive AS (SELECT m.menu_id FROM sys_menu m JOIN drilling_menu_setting s ON s.menu_id=m.menu_id WHERE s.status='1' UNION ALL SELECT m.menu_id FROM sys_menu m JOIN inactive i ON m.parent_id=i.menu_id) SELECT DISTINCT m.perms FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id AND r.status='0' AND r.del_flag='0' JOIN drilling_role_module rm ON rm.role_id=r.role_id AND rm.account_tier='ENGINEER' JOIN sys_role_menu rmenu ON rmenu.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rmenu.menu_id LEFT JOIN drilling_menu_setting ms ON ms.menu_id=m.menu_id WHERE (ms.status IS NULL OR ms.status='0') AND ur.user_id=? AND m.perms LIKE 'drilling:%' AND NOT EXISTS(SELECT 1 FROM sys_menu denied JOIN inactive ds ON ds.menu_id=denied.menu_id WHERE denied.perms=m.perms)", String.class,id));
    }
    public Map<String,Object> info(SysUser user) {
        Map<String,Object> p=policy(user.getUserId()), r=new LinkedHashMap<>();
        r.put("accountTier",p.get("account_tier")); r.put("mustChangePassword",mustChange(user));
        r.put("allowedModules",modules(user.getUserId())); r.put("demoOnly","GUEST".equals(p.get("account_tier")));
        r.put("validFrom",p.get("valid_from")==null?null:date(p.get("valid_from")).toString());
        r.put("validUntil",p.get("valid_until")==null?null:date(p.get("valid_until")).toString());
        r.put("timezone","Asia/Shanghai"); return r;
    }
    /** Module mapping for engineering API requests. Unmapped business paths fail closed. */
    public List<String> requiredModules(String path) {
        if(path.equals("/api/drilling/library/tree")) return List.of("catalog","surveys","versions");
        if(path.startsWith("/api/drilling/scoped-surveys")) return List.of("catalog","surveys","versions");
        if(path.startsWith("/api/drilling/centers/")) {
            String key=path.substring("/api/drilling/centers/".length()).split("/")[0];
            return Set.of("alerts","history","quality","reports","connectors","versions").contains(key)?List.of(key):List.of();
        }
        if(path.startsWith("/api/train/")) return List.of("models");
        if(path.startsWith("/api/algorithm/anti-collision")) return List.of("anticollision", "realtime", "replanning");
        if(path.startsWith("/api/algorithm/trajectory-plan")) return List.of("replanning");
        if(path.startsWith("/api/algorithm/realtime")) return List.of("realtime", "realtime-data");
        if(path.startsWith("/api/algorithm/survey")) return List.of("surveys", "anticollision", "replanning");
        if(path.startsWith("/api/algorithm/report")) return List.of("anticollision", "replanning", "surveys");
        if(path.startsWith("/api/algorithm/resources")) return List.of("prediction", "models", "replanning");
        if(path.startsWith("/api/drilling/predictions")) return List.of("prediction", "realtime");
        if(path.matches("/api/drilling/fields/\\d+/registry(/.*)?")) return List.of("catalog", "surveys", "versions", "anticollision", "replanning", "prediction", "realtime");
        if(path.matches("/api/drilling/fields/\\d+/(models|training)(/.*)?")) return List.of("models");
        if(path.matches("/api/drilling/fields/\\d+/configs(/.*)?")) return MODULES;
        if(path.matches("/api/drilling/fields/\\d+/(files|audit)(/.*)?")) return List.of("files", "models", "surveys");
        if(path.matches("/api/drilling/fields/\\d+/library(/.*)?")) return List.of("catalog", "surveys", "versions", "files");
        if(path.matches("/api/drilling/fields/\\d+/members(/.*)?")) return List.of("catalog");
        if(path.matches("/api/drilling/fields/\\d+/wells(/.*)?")) return MODULES;
        if(path.matches("/api/drilling/(blocks(/\\d+)?|fields(/\\d+)?)")) return MODULES;
        return List.of();
    }
    public List<String> requiredWriteModules(String path) {
        if(path.startsWith("/api/algorithm/anti-collision")) return List.of("anticollision", "realtime");
        if(path.startsWith("/api/algorithm/trajectory-plan")) return List.of("replanning");
        if(path.startsWith("/api/algorithm/realtime")) return List.of("realtime-data", "realtime");
        if(path.startsWith("/api/algorithm/survey")) return List.of("surveys");
        if(path.startsWith("/api/drilling/predictions")) return List.of("prediction");
        if(path.matches("/api/drilling/fields/\\d+/registry(/.*)?")) return List.of("catalog", "surveys", "versions");
        if(path.startsWith("/api/drilling/centers/")) return requiredModules(path);
        if(path.matches("/api/drilling/fields/\\d+/configs(/.*)?")) return List.of("config");
        if(path.matches("/api/drilling/fields/\\d+/wells(/.*)?")) return List.of("surveys");
        if(path.matches("/api/drilling/fields/\\d+/files(/.*)?")) return List.of("files", "models");
        if(path.matches("/api/drilling/fields/\\d+/library(/.*)?")) return List.of("catalog", "surveys", "versions", "files");
        if(path.matches("/api/drilling/(blocks|fields)(/.*)?") && !path.contains("/models") && !path.contains("/training")) return List.of("catalog");
        return requiredModules(path);
    }
}
