package com.ruoyi.web.controller.system;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingAccountPolicy;
import com.ruoyi.framework.security.DrillingDataScope;
import com.ruoyi.framework.shiro.service.SysPasswordService;
import com.ruoyi.framework.shiro.util.AuthorizationUtils;
import com.ruoyi.system.service.ISysUserService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Vue REST adapter for the existing RuoYi users, roles, menu permissions and Shiro identity. */
@RestController
@RequestMapping("/api/access")
public class DrillingAccessController {
    private final JdbcTemplate jdbc;
    private final ISysUserService users;
    private final SysPasswordService passwords;
    private final DrillingAccountPolicy policy;
    public DrillingAccessController(JdbcTemplate jdbc,ISysUserService users,SysPasswordService passwords,DrillingAccountPolicy policy) {
        this.jdbc=jdbc; this.users=users; this.passwords=passwords; this.policy=policy;
    }
    @GetMapping("/me") public AjaxResult me() {
        SysUser u=users.selectUserById(ShiroUtils.getUserId());
        Map<String,Object> info=new LinkedHashMap<>(policy.info(u));
        info.put("userId",u.getUserId());info.put("loginName",u.getLoginName());info.put("userName",u.getUserName());
        info.put("email",u.getEmail());info.put("phonenumber",u.getPhonenumber());
        return AjaxResult.success(info);
    }
    @PutMapping("/me/password") @Transactional
    public AjaxResult changePassword(@RequestBody Map<String,Object> body) {
        SysUser u=users.selectUserById(ShiroUtils.getUserId());
        String old=text(body,"oldPassword"), next=text(body,"newPassword");
        if(old==null || !passwords.matches(u,old)) throw new IllegalArgumentException("旧密码错误");
        if(next==null || !next.matches("[A-Za-z0-9!@#$%^&*()_+=.?,~\\-]{8,20}")
                || !next.matches(".*[A-Za-z].*") || !next.matches(".*[0-9].*"))
            throw new IllegalArgumentException("新密码须为8～20位，必须同时包含字母和数字，可包含!@#$%^&*()_+=.?,~-，不允许空白或汉字");
        if(DrillingAccountPolicy.INITIAL_PASSWORD.equals(next)) throw new IllegalArgumentException("新密码不能是初始密码123456");
        if(passwords.matches(u,next)) throw new IllegalArgumentException("新密码不能与旧密码相同");
        u.setSalt(ShiroUtils.randomSalt());u.setPassword(passwords.encryptPassword(u.getLoginName(),next,u.getSalt()));
        users.resetUserPwd(u);
        jdbc.update("UPDATE drilling_account_policy SET must_change_password=FALSE WHERE user_id=?",u.getUserId());
        ShiroUtils.setSysUser(users.selectUserById(u.getUserId()));
        audit(u.getUserId(),"CHANGE_PASSWORD",Map.of()); return AjaxResult.success();
    }
    @PutMapping("/me/profile") @Transactional
    public AjaxResult profile(@RequestBody Map<String,Object> b) {
        Long id=ShiroUtils.getUserId();
        policy.rename(id,displayName(b));
        jdbc.update("UPDATE sys_user SET email=?,phonenumber=?,update_time=NOW() WHERE user_id=?",
            optional(b,"email",50),optional(b,"phonenumber",11),id);
        audit(id,"UPDATE_PROFILE",Map.of());return AjaxResult.success();
    }
    @PutMapping("/me/name") @Transactional public AjaxResult rename(@RequestBody Map<String,Object> b) {
        Long id=ShiroUtils.getUserId(); String name=displayName(b);
        policy.rename(id,name);audit(id,"RENAME_ACCOUNT",Map.of("accountName",name));return AjaxResult.success();
    }
    @GetMapping("/options") public AjaxResult options() {
        admin(); Map<String,Object> r=new LinkedHashMap<>();
        r.put("modules",DrillingAccountPolicy.LABELS);r.put("roles",roleRows());
        r.put("permissions",jdbc.queryForList("SELECT menu_id AS menuId,menu_name AS menuName,perms FROM sys_menu WHERE perms LIKE 'drilling:%' ORDER BY menu_id"));
        r.put("departments",jdbc.queryForList("SELECT dept_id AS deptId,parent_id AS parentId,dept_name AS deptName,status FROM sys_dept WHERE del_flag='0' ORDER BY order_num,dept_id"));
        r.put("posts",jdbc.queryForList("SELECT post_id AS postId,post_name AS postName,status FROM sys_post ORDER BY post_sort,post_id"));
        r.put("fields",jdbc.queryForList("SELECT field_id AS fieldId,field_name AS fieldName FROM drilling_field WHERE status='0'"));
        return AjaxResult.success(r);
    }
    @GetMapping("/users") public AjaxResult list(@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) Long deptId,@RequestParam(defaultValue="") String tier,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        admin(); int size=Math.min(100,Math.max(1,pageSize)),offset=(Math.max(1,pageNum)-1)*size;
        String where=" FROM sys_user u JOIN drilling_account_policy p ON p.user_id=u.user_id WHERE u.del_flag='0' AND (u.login_name LIKE ? OR u.user_name LIKE ?) AND (?='' OR p.account_tier=?) AND (?='' OR u.status=?)";
        if(deptId!=null)where+=" AND (u.dept_id="+deptId+" OR u.dept_id IN (SELECT dept_id FROM sys_dept WHERE FIND_IN_SET("+deptId+",ancestors)>0 AND del_flag='0'))";
        String q="%"+keyword+"%";
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT u.dept_id AS deptId,(SELECT dept_name FROM sys_dept d WHERE d.dept_id=u.dept_id) AS deptName,u.user_id AS userId,u.login_name AS loginName,u.user_name AS userName,u.email,u.phonenumber,u.status,u.login_date AS loginDate,u.create_time AS createTime,p.account_tier AS accountTier,p.valid_from AS validFrom,p.valid_until AS validUntil"+where+" ORDER BY u.user_id LIMIT ? OFFSET ?",q,q,tier,tier,status,status,size,offset);
        for(Map<String,Object> row:rows) {
            Long id=((Number)row.get("userId")).longValue();
            row.put("postNames",jdbc.queryForList("SELECT p.post_name FROM sys_user_post up JOIN sys_post p ON p.post_id=up.post_id WHERE up.user_id=?",String.class,id));
            row.put("mustChangePassword",policy.mustChange(users.selectUserById(id)));
            row.put("allowedModules",policy.modules(id));
        }
        AjaxResult result=AjaxResult.success();result.put("rows",rows);result.put("total",jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,q,q,tier,tier,status,status));return result;
    }
    @GetMapping("/users/{id}") public AjaxResult user(@PathVariable Long id) {
        admin();SysUser u=exists(id);Map<String,Object> r=new LinkedHashMap<>(policy.info(u));
        r.put("userId",id);r.put("loginName",u.getLoginName());r.put("userName",u.getUserName());r.put("email",u.getEmail());r.put("phonenumber",u.getPhonenumber());r.put("status",u.getStatus());r.put("remark",u.getRemark());
        r.put("allowedModules",DrillingAccountPolicy.strings(policy.policy(id).get("modules_json")));
        r.put("deptId",u.getDeptId());r.put("postIds",jdbc.queryForList("SELECT post_id FROM sys_user_post WHERE user_id=?",Long.class,id));
        r.put("roleIds",jdbc.queryForList("SELECT ur.role_id FROM sys_user_role ur JOIN drilling_role_module rm ON rm.role_id=ur.role_id WHERE ur.user_id=?",Long.class,id));
        r.put("fieldIds",jdbc.queryForList("SELECT field_id FROM drilling_field_member WHERE user_id=? AND status='0'",Long.class,id));
        return AjaxResult.success(r);
    }
    @PostMapping("/users") @Transactional public AjaxResult create(@RequestBody Map<String,Object> b) {
        admin();String login=randomAccountId();
        String tier=accountTier(b); validatePolicy(b,tier); Long[] roles=roles(b,tier);
        SysUser u=new SysUser();u.setLoginName(login);u.setUserName(login);u.setUserType("00");
        u.setEmail(optional(b,"email",50));u.setPhonenumber(optional(b,"phonenumber",11));u.setStatus(status(b));u.setRoleIds(roles);u.setCreateBy(ShiroUtils.getLoginName());
        u.setSalt(ShiroUtils.randomSalt());u.setPassword(passwords.encryptPassword(login,DrillingAccountPolicy.INITIAL_PASSWORD,u.getSalt()));
        DrillingDataScope.current().validateAssignments(b);
        users.insertUser(u);DrillingDataScope.current().saveAssignments(u.getUserId(),b);jdbc.update("INSERT INTO drilling_account_identifier(identifier,user_id) VALUES(?,?)",login,u.getUserId());savePolicy(u.getUserId(),b,tier,true);members(u.getUserId(),b,tier);
        audit(u.getUserId(),"CREATE_USER",Map.of("tier",tier));AuthorizationUtils.clearAllCachedAuthorizationInfo();return AjaxResult.success(Map.of("userId",u.getUserId(),"accountId",login,"accountName",login));
    }
    @PutMapping("/users/{id}") @Transactional public AjaxResult update(@PathVariable Long id,@RequestBody Map<String,Object> b) {
        admin(); SysUser existing=exists(id);
        if(b.containsKey("loginName") && !existing.getLoginName().equals(text(b,"loginName"))) throw new IllegalArgumentException("账号ID由系统生成，不允许修改");
        String tier=accountTier(b),status=status(b);validatePolicy(b,tier);
        if((id==1L || id.equals(ShiroUtils.getUserId())) && (!tier.equals("ADMIN") || !status.equals("0"))) throw new IllegalArgumentException("不能停用或降低当前管理员及内置管理员的等级");
        Long[] roles=roles(b,tier);
        policy.rename(id,displayName(b));
        jdbc.update("UPDATE sys_user SET email=?,phonenumber=?,status=?,remark=?,update_by=?,update_time=NOW() WHERE user_id=?",
            optional(b,"email",50),optional(b,"phonenumber",11),status,optional(b,"remark",500),ShiroUtils.getLoginName(),id);
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=?",id);
        for(Long role:roles) jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,role);
        DrillingDataScope.current().saveAssignments(id,b);
        savePolicy(id,b,tier,false);members(id,b,tier);audit(id,"UPDATE_USER",Map.of("tier",tier,"status",status));AuthorizationUtils.clearAllCachedAuthorizationInfo();return AjaxResult.success();
    }
    @PutMapping("/users/{id}/reset-password") @Transactional public AjaxResult reset(@PathVariable Long id) {
        admin(); SysUser u=exists(id);if(id.equals(ShiroUtils.getUserId())) throw new IllegalArgumentException("请在个人中心修改自己的密码");
        u.setSalt(ShiroUtils.randomSalt());u.setPassword(passwords.encryptPassword(u.getLoginName(),DrillingAccountPolicy.INITIAL_PASSWORD,u.getSalt()));users.resetUserPwd(u);
        jdbc.update("UPDATE drilling_account_policy SET must_change_password=TRUE WHERE user_id=?",id);
        audit(id,"RESET_PASSWORD",Map.of()); return AjaxResult.success("密码已重置为123456，用户下次登录后必须修改");
    }
    @DeleteMapping("/users/{id}") @Transactional public AjaxResult delete(@PathVariable Long id) {
        admin();exists(id);if(id==1L || id.equals(ShiroUtils.getUserId())) throw new IllegalArgumentException("不能删除内置管理员或当前账号");
        users.deleteUserById(id);jdbc.update("UPDATE drilling_field_member SET status='1' WHERE user_id=?",id);
        audit(id,"DELETE_USER",Map.of());AuthorizationUtils.clearAllCachedAuthorizationInfo();return AjaxResult.success();
    }
    @GetMapping("/roles") public AjaxResult roles() { admin();return AjaxResult.success(roleRows()); }
    private List<Map<String,Object>> roleRows() {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT r.role_id AS roleId,r.role_name AS roleName,r.role_key AS roleKey,r.status,r.remark,r.data_scope AS dataScope,rm.account_tier AS accountTier,rm.modules_json FROM sys_role r JOIN drilling_role_module rm ON rm.role_id=r.role_id WHERE r.del_flag='0' ORDER BY r.role_sort,r.role_id");
        for(Map<String,Object> row:rows) {
            row.put("allowedModules",DrillingAccountPolicy.strings(row.remove("modules_json")));
            row.put("menuIds",jdbc.queryForList("SELECT menu_id FROM sys_role_menu WHERE role_id=?",Long.class,row.get("roleId")));
            row.put("deptIds",jdbc.queryForList("SELECT dept_id FROM sys_role_dept WHERE role_id=?",Long.class,row.get("roleId")));
            row.put("builtin",List.of("admin","engineer","guest").contains(String.valueOf(row.get("roleKey"))));
        }return rows;
    }
    @PostMapping("/roles") @Transactional public AjaxResult createRole(@RequestBody Map<String,Object> b) {
        admin();String key=required(b,"roleKey",100);
        if(!key.matches("[a-zA-Z][a-zA-Z0-9_]{1,63}") || List.of("admin","engineer","guest","common").contains(key)) throw new IllegalArgumentException("请输入唯一的2～64位角色权限标识");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role WHERE role_key=?",Integer.class,key)>0) throw new IllegalArgumentException("角色标识已存在");
        String tier=accountTier(b);if(tier.equals("ADMIN")) throw new IllegalArgumentException("系统管理权限请通过系统管理员等级分配");
        jdbc.update("INSERT INTO sys_role(role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time,remark) VALUES(?,?,10,'6',?,'0',?,NOW(),?)",required(b,"roleName",30),key,status(b),ShiroUtils.getLoginName(),optional(b,"remark",500));
        Long id=jdbc.queryForObject("SELECT role_id FROM sys_role WHERE role_key=?",Long.class,key);
        saveRole(id,b,tier);audit(id,"CREATE_ROLE",Map.of("roleKey",key));return AjaxResult.success();
    }
    @PutMapping("/roles/{id}") @Transactional public AjaxResult updateRole(@PathVariable Long id,@RequestBody Map<String,Object> b) {
        admin();Map<String,Object> old=role(id);String key=String.valueOf(old.get("role_key"));
        if(key.equals("admin")) throw new IllegalArgumentException("系统管理员角色固定拥有管理权限");
        String tier=String.valueOf(old.get("account_tier"));
        if(!tier.equals(accountTier(b))) throw new IllegalArgumentException("角色所属等级不能修改，请新建角色后重新分配");
        if(List.of("engineer","guest").contains(key) && !status(b).equals("0")) throw new IllegalArgumentException("默认角色不能停用");
        jdbc.update("UPDATE sys_role SET role_name=?,status=?,remark=?,update_by=?,update_time=NOW() WHERE role_id=?",required(b,"roleName",30),status(b),optional(b,"remark",500),ShiroUtils.getLoginName(),id);
        saveRole(id,b,tier);audit(id,"UPDATE_ROLE",Map.of("tier",tier));AuthorizationUtils.clearAllCachedAuthorizationInfo();return AjaxResult.success();
    }
    @DeleteMapping("/roles/{id}") @Transactional public AjaxResult deleteRole(@PathVariable Long id) {
        admin();Map<String,Object> r=role(id);
        if(List.of("admin","engineer","guest").contains(String.valueOf(r.get("role_key")))) throw new IllegalArgumentException("不能删除内置角色");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role ur JOIN sys_user u ON u.user_id=ur.user_id WHERE ur.role_id=? AND u.del_flag='0'",Integer.class,id)>0) throw new IllegalArgumentException("此角色仍有关联账号，请先调整账号角色");
        jdbc.update("UPDATE sys_role SET del_flag='2',update_time=NOW() WHERE role_id=?",id);jdbc.update("DELETE FROM sys_role_menu WHERE role_id=?",id);
        jdbc.update("DELETE FROM sys_role_dept WHERE role_id=?",id);
        audit(id,"DELETE_ROLE",Map.of());AuthorizationUtils.clearAllCachedAuthorizationInfo();return AjaxResult.success();
    }
    @GetMapping("/audit") public AjaxResult auditList() { admin();return AjaxResult.success(jdbc.queryForList("SELECT a.audit_id,a.actor_id,u.login_name AS actor_name,a.target_id,a.action,a.detail_json,a.create_time FROM drilling_account_audit a LEFT JOIN sys_user u ON u.user_id=a.actor_id ORDER BY a.audit_id DESC LIMIT 300")); }
    private void saveRole(Long id,Map<String,Object> b,String tier) {
        DrillingDataScope.current().saveScope(id,b,tier);
        List<String> modules=moduleList(b);List<Long> menuIds=ids(b.get("menuIds"));
        if(tier.equals("GUEST")) menuIds=List.of();
        for(Long menu:menuIds) if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE menu_id=? AND perms LIKE 'drilling:%'",Integer.class,menu)==0) throw new IllegalArgumentException("只可分配钻井业务功能权限");
        jdbc.update("INSERT INTO drilling_role_module(role_id,account_tier,modules_json) VALUES(?,?,?) ON DUPLICATE KEY UPDATE modules_json=VALUES(modules_json)",id,tier,JSON.toJSONString(modules));
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=?",id);
        for(Long menu:menuIds) jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(?,?)",id,menu);
    }
    private Map<String,Object> role(Long id) {
        List<Map<String,Object>> r=jdbc.queryForList("SELECT r.role_key,rm.account_tier FROM sys_role r JOIN drilling_role_module rm ON rm.role_id=r.role_id WHERE r.role_id=? AND r.del_flag='0'",id);
        if(r.isEmpty()) throw new IllegalArgumentException("角色不存在");return r.get(0);
    }
    private Long[] roles(Map<String,Object> b,String tier) {
        if(tier.equals("ADMIN")) return new Long[]{jdbc.queryForObject("SELECT role_id FROM sys_role WHERE role_key='admin' AND del_flag='0' LIMIT 1",Long.class)};
        List<Long> ids=ids(b.get("roleIds"));if(ids.isEmpty()) throw new IllegalArgumentException("请至少分配一个角色");
        for(Long id:ids) if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role r JOIN drilling_role_module rm ON rm.role_id=r.role_id WHERE r.role_id=? AND r.del_flag='0' AND r.status='0' AND rm.account_tier=?",Integer.class,id,tier)==0) throw new IllegalArgumentException("角色与账号等级不匹配，或角色已停用");
        return ids.toArray(Long[]::new);
    }
    private void validatePolicy(Map<String,Object> b,String tier) {
        moduleList(b);
        if(tier.equals("GUEST")) {
            LocalDateTime from=parse(b.get("validFrom")),until=parse(b.get("validUntil"));
            if(from==null || until==null || !until.isAfter(from)) throw new IllegalArgumentException("游客必须设置有效的访问起止时间（北京时间），结束时间须晚于开始时间");
            if(moduleList(b).isEmpty()) throw new IllegalArgumentException("请至少授权一个游客演示模块");
        }
    }
    private void savePolicy(Long id,Map<String,Object>b,String tier,boolean created) {
        Object from=tier.equals("GUEST")?Timestamp.valueOf(parse(b.get("validFrom"))):null;
        Object until=tier.equals("GUEST")?Timestamp.valueOf(parse(b.get("validUntil"))):null;
        jdbc.update("INSERT INTO drilling_account_policy(user_id,account_tier,must_change_password,valid_from,valid_until,modules_json) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE account_tier=VALUES(account_tier),valid_from=VALUES(valid_from),valid_until=VALUES(valid_until),modules_json=VALUES(modules_json)",id,tier,created,from,until,JSON.toJSONString(moduleList(b)));
    }
    private void members(Long id,Map<String,Object>b,String tier) {
        if(!b.containsKey("fieldIds")) return;
        List<Long> fields=tier.equals("ENGINEER")?ids(b.get("fieldIds")):List.of();
        for(Long fieldId:fields) if(jdbc.queryForObject("SELECT COUNT(*) FROM drilling_field WHERE field_id=? AND status='0'",Integer.class,fieldId)==0) throw new IllegalArgumentException("井场不存在");
        jdbc.update("UPDATE drilling_field_member SET status='1' WHERE user_id=?",id);
        for(Long fieldId:fields) jdbc.update("INSERT INTO drilling_field_member(field_id,user_id,role_code,status,create_by) VALUES(?,?,'ENGINEER','0',?) ON DUPLICATE KEY UPDATE status='0',role_code=IF(role_code='OWNER','OWNER','ENGINEER')",fieldId,id,ShiroUtils.getLoginName());
    }
    private List<String> moduleList(Map<String,Object>b) {
        List<String> values=DrillingAccountPolicy.strings(b.get("allowedModules"));
        if(!DrillingAccountPolicy.MODULES.containsAll(values)) throw new IllegalArgumentException("包含未知功能模块");return values;
    }
    private static List<Long> ids(Object value) {
        if(value==null) return List.of();if(!(value instanceof Collection<?> c)) throw new IllegalArgumentException("请提供ID列表");
        try{return c.stream().map(x->Long.valueOf(String.valueOf(x))).distinct().toList();}catch(Exception e){throw new IllegalArgumentException("ID无效");}
    }
    private LocalDateTime parse(Object value) { try{return DrillingAccountPolicy.date(value);}catch(Exception e){throw new IllegalArgumentException("访问时段格式无效");} }
    private String accountTier(Map<String,Object>b) {String t=required(b,"accountTier",16);if(!List.of("ADMIN","ENGINEER","GUEST").contains(t))throw new IllegalArgumentException("账号等级无效");return t;}
    private String status(Map<String,Object>b) {String s=String.valueOf(b.getOrDefault("status","0"));if(!List.of("0","1").contains(s))throw new IllegalArgumentException("状态无效");return s;}
    private SysUser exists(Long id) {SysUser u=users.selectUserById(id);if(u==null || !"0".equals(u.getDelFlag()))throw new IllegalArgumentException("账号不存在");return u;}
    private void admin() {if(!policy.tier(ShiroUtils.getUserId()).equals("ADMIN"))throw new org.apache.shiro.authz.UnauthorizedException("仅系统管理员可操作");}
    private String randomAccountId() {
        java.security.SecureRandom random=new java.security.SecureRandom();
        for(int attempt=0;attempt<20;attempt++) {
            String id=String.valueOf(1_000_000_000L+random.nextLong(9_000_000_000L));
            if(policy.resolveUserId(id)==null && jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE login_name=?",Integer.class,id)==0) return id;
        }
        throw new IllegalArgumentException("账号ID生成繁忙，请重试");
    }
    private String displayName(Map<String,Object> b) {
        String name=required(b,"userName",40);
        if(name.codePointCount(0,name.length())<2 || name.codePointCount(0,name.length())>20
                || !name.matches("[\\p{IsHan}A-Za-z0-9_·-]+"))
            throw new IllegalArgumentException("账号名称须为2～20个汉字、字母、数字、下划线、短横线或中点");
        return name;
    }
    private String text(Map<String,Object>b,String k) {Object v=b.get(k);return v==null?null:String.valueOf(v);}
    private String optional(Map<String,Object>b,String k,int max) {String v=text(b,k);if(v==null)return "";if(v.length()>max)throw new IllegalArgumentException(k+"超过最大长度");return v;}
    private String required(Map<String,Object>b,String k,int max) {String v=optional(b,k,max).trim();if(v.isEmpty())throw new IllegalArgumentException(k+"不能为空");return v;}
    private void audit(Long target,String action,Map<String,Object> detail) {jdbc.update("INSERT INTO drilling_account_audit(actor_id,target_id,action,detail_json) VALUES(?,?,?,?)",ShiroUtils.getUserId(),target,action,JSON.toJSONString(detail));}
    @ExceptionHandler({IllegalArgumentException.class,DuplicateKeyException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public AjaxResult invalid(Exception e) {return AjaxResult.error(e instanceof DuplicateKeyException?"账号名称、ID或角色标识已存在":e.getMessage()).put("code",400);}
}
