package com.ruoyi.web.controller.system;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.web.service.ConfigService;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysRoleService;

/**
 * 登录验证
 * 
 * @author ruoyi
 */
@Controller
public class SysLoginController extends BaseController
{
    /**
     * 是否开启记住我功能
     */
    @Value("${shiro.rememberMe.enabled: false}")
    private boolean rememberMe;

    @Autowired
    private ConfigService configService;

    @Autowired
    private ISysRoleService roleService;

    @Autowired
    private ISysMenuService menuService;

    @Autowired private com.ruoyi.framework.security.DrillingAccountPolicy accountPolicy;

    @GetMapping("/login")
    public String login(HttpServletRequest request, HttpServletResponse response, ModelMap mmap)
    {
        // 如果是Ajax请求，返回Json字符串。
        if (ServletUtils.isAjaxRequest(request))
        {
            return ServletUtils.renderString(response, "{\"code\":\"1\",\"msg\":\"未登录或登录超时。请重新登录\"}");
        }
        // 是否开启记住我
        mmap.put("isRemembered", rememberMe);
        // 是否开启用户注册
        mmap.put("isAllowRegister", false);
        return "login";
    }

    @PostMapping("/login")
    @ResponseBody
    public AjaxResult ajaxLogin(String username, String password, Boolean rememberMe)
    {
        // Older clients may omit this optional form field. Treat omission as
        // the normal non-persistent session instead of unboxing null.
        boolean shouldRemember = Boolean.TRUE.equals(rememberMe);
        UsernamePasswordToken token = new UsernamePasswordToken(username, password, shouldRemember);
        Subject subject = SecurityUtils.getSubject();
        try
        {
            subject.login(token);
            return success();
        }
        catch (AuthenticationException e)
        {
            String msg = "用户或密码错误";
            if (StringUtils.isNotEmpty(e.getMessage()))
            {
                msg = e.getMessage();
            }
            return error(msg);
        }
    }

    /**
     * Current-user payload consumed by the Vue application. Authentication is
     * backed by the Shiro JSESSIONID cookie, not a bearer token.
     */
    @GetMapping("/getInfo")
    @ResponseBody
    public AjaxResult getInfo()
    {
        SysUser currentUser = ShiroUtils.getSysUser();
        if (currentUser == null)
        {
            return AjaxResult.error("登录状态已失效，请重新登录");
        }

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("userId", currentUser.getUserId());
        userInfo.put("userName", currentUser.getLoginName());
        userInfo.put("nickName", currentUser.getUserName());
        userInfo.put("avatar", currentUser.getAvatar());

        Set<String> roles = Set.of(accountPolicy.tier(currentUser.getUserId()).toLowerCase(java.util.Locale.ROOT));
        Set<String> permissions = accountPolicy.permissions(currentUser.getUserId());
        AjaxResult result = AjaxResult.success();
        result.put("user", userInfo);
        result.putAll(accountPolicy.info(currentUser));
        result.put("roles", roles);
        result.put("permissions", permissions);
        return result;
    }

    @GetMapping("/unauth")
    public String unauth()
    {
        return "error/unauth";
    }
}
