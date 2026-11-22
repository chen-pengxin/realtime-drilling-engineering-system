package com.ruoyi.framework.security;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.system.service.ISysUserService;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import org.apache.shiro.web.filter.AccessControlFilter;

/** Enforces password, expiry, tier and demo boundaries before any controller executes. */
public class DrillingAccountFilter extends AccessControlFilter {
    private final DrillingAccountPolicy policy;
    private final ISysUserService users;
    public DrillingAccountFilter(DrillingAccountPolicy policy, ISysUserService users) { this.policy=policy; this.users=users; }
    @Override protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) throws Exception {
        HttpServletRequest req=(HttpServletRequest)request;
        SysUser principal=(SysUser)ShiroUtils.getSubject().getPrincipal();
        if(principal==null) return reject(req,response,401,"登录状态已失效，请重新登录");
        var subject = ShiroUtils.getSubject();
        var session = subject.getSession(false);
        long timeout = policy.sessionTimeoutMillis();
        if (!subject.isAuthenticated() || session == null || DrillingSessionActivity.expired(session, timeout, System.currentTimeMillis())) {
            ShiroUtils.logout();
            return reject(req,response,401,"长时间未操作，请重新登录");
        }
        if (session.getTimeout() != timeout) session.setTimeout(timeout);
        if(policy.sessionRevoked(ShiroUtils.getSessionId())) {ShiroUtils.logout();return reject(req,response,401,"此登录会话已由管理员退出，请重新登录");}
        SysUser fresh=users.selectUserById(principal.getUserId());
        String reason=policy.unavailable(fresh);
        if(reason!=null) { ShiroUtils.logout(); return reject(req,response,401,reason); }
        if(!Objects.equals(fresh.getPassword(),principal.getPassword())) {
            ShiroUtils.logout(); return reject(req,response,401,"密码已变更，请重新登录");
        }
        request.setAttribute("drilling.currentUser",fresh);
        String tier=policy.tier(fresh.getUserId());
        request.setAttribute("drilling.accountTier",tier);
        String path=req.getServletPath().replaceAll("/+$", "");
        String method=req.getMethod();
        boolean read="GET".equals(method);
        boolean info=read && (path.equals("/getInfo") || path.equals("/api/access/me"));
        boolean password=path.equals("/api/access/me/password") && "PUT".equals(method);
        if (DrillingSessionActivity.PATH.equals(path) && "POST".equals(method)) return true;
        if(policy.mustChange(fresh) && !info && !password)
            return reject(req,response,460,"当前仍为初始密码，必须修改密码后才能继续操作");
        if(info || password) return true;
        if ((read && path.startsWith("/profile/avatar/") && path.equals(fresh.getAvatar())) ||
            ("PUT".equals(method) && path.equals("/api/access/me/profile")) ||
            (read && path.equals("/api/access/me/details")) ||
            (read && path.equals("/api/operations/sessions/self")) ||
            ("POST".equals(method) && path.equals("/api/operations/sessions/self/revoke-selected")) ||
            ("PUT".equals(method) && path.equals("/api/access/me/preferences")) ||
            ("POST".equals(method) && (path.equals("/api/operations/sessions/self/revoke-others") || path.equals("/api/access/me/avatar")))) return true;
        if(path.equals("/api/access/me/name") && "PUT".equals(method)) return true;
        if(path.startsWith("/api/access/demo/") && read) {
            String module=path.substring("/api/access/demo/".length());
            return policy.modules(fresh.getUserId()).contains(module) || reject(req,response,403,"未获授权访问此演示模块");
        }
        // Guests never enter real business controllers, even for GET exports or GET endpoints with side effects.
        if("GUEST".equals(tier)) return reject(req,response,403,"游客仅可访问已授权的演示页面，不能读取真实井场数据或执行操作");
        if(path.startsWith("/api/operations/"))
            return "ADMIN".equals(tier) || reject(req,response,403,"仅系统管理员可访问系统管理与运行维护");
        if(path.equals("/api/notices") || path.startsWith("/api/notices/") || path.startsWith("/api/reference/"))
            return read || ("POST".equals(method) && path.matches("/api/notices/(read-all|[0-9]+/read)")) || reject(req,response,403,"不允许此操作");
        if(path.startsWith("/api/access/")) {
            if(path.equals("/api/access/me/profile") && "PUT".equals(method)) return true;
            return "ADMIN".equals(tier) || reject(req,response,403,"仅系统管理员可管理账号、权限和账号审计");
        }
        // Retire legacy identity mutation endpoints to prevent bypassing uniform initial-password policy.
        if(path.startsWith("/system/") || path.startsWith("/monitor/") || path.startsWith("/tool/")) {
            if(!"ADMIN".equals(tier)) return reject(req,response,403,"仅系统管理员可访问系统管理");
            if(!read || path.startsWith("/system/user/profile") || path.contains("/remove"))
                return reject(req,response,403,"请使用石油钻井系统的账号管理和个人中心接口");
            return true;
        }
        if("ADMIN".equals(tier)) return true;
        if(path.startsWith("/api/")) {
            List<String> needed=read?policy.requiredModules(path):policy.requiredWriteModules(path);
            List<String> allowed=policy.modules(fresh.getUserId());
            if(needed.stream().noneMatch(allowed::contains)) return reject(req,response,403,"当前工程师角色未获授权使用此功能");
            return true;
        }
        return reject(req,response,403,"无权访问此接口");
    }
    private boolean reject(HttpServletRequest req,ServletResponse response,int code,String message) throws Exception {
        if(code==460) req.setAttribute("drilling.passwordGate",true);
        HttpServletResponse res=(HttpServletResponse)response;
        res.setStatus(code==401?401:403); res.setCharacterEncoding("UTF-8"); res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(JSON.toJSONString(Map.of("code",code,"msg",message))); return false;
    }
    @Override public void afterCompletion(ServletRequest request,ServletResponse response,Exception cause) throws Exception {
        HttpServletRequest req=(HttpServletRequest)request;
        if (((HttpServletResponse)response).getStatus()==403 && req.getAttribute("drilling.passwordGate")==null && ShiroUtils.getSysUser()!=null)
            policy.auditDenied(req.getServletPath().replaceAll("/sessions/[a-f0-9]{64}/","/sessions/{device}/"),req.getMethod(),req.getRemoteAddr());
        super.afterCompletion(request,response,cause);
    }
    @Override protected boolean onAccessDenied(ServletRequest request,ServletResponse response) { return false; }
}
