package com.ruoyi.framework.shiro.web.filter;

import java.io.IOException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.shiro.web.filter.authc.UserFilter;
import com.ruoyi.common.utils.ServletUtils;

/**
 * Return a machine-readable unauthorized response for API calls while keeping
 * the traditional redirect behavior for browser page requests.
 */
public class ApiUserFilter extends UserFilter
{
    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception
    {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        if (ServletUtils.isAjaxRequest(httpRequest))
        {
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setCharacterEncoding("UTF-8");
            httpResponse.setContentType("application/json;charset=UTF-8");
            httpResponse.getWriter().write("{\"code\":401,\"msg\":\"登录状态已失效，请重新登录\"}");
            return false;
        }
        return super.onAccessDenied(request, response);
    }
}
