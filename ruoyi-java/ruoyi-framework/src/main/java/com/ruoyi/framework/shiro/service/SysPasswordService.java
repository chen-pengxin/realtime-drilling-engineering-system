package com.ruoyi.framework.shiro.service;

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.CacheManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.constant.ShiroConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.user.UserPasswordNotMatchException;
import com.ruoyi.common.exception.user.UserPasswordRetryLimitExceedException;
import com.ruoyi.common.utils.MessageUtils;
import com.ruoyi.common.utils.security.Md5Utils;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import jakarta.annotation.PostConstruct;

/**
 * 登录密码方法
 * 
 * @author ruoyi
 */
@Component
public class SysPasswordService
{
    @Autowired
    private CacheManager cacheManager;

    private Cache<String, AtomicInteger> loginRecordCache;

    @Value(value = "${user.password.maxRetryCount}")
    private String maxRetryCount;

    @PostConstruct
    public void init()
    {
        loginRecordCache = cacheManager.getCache(ShiroConstants.LOGIN_RECORD_CACHE);
    }

    private int failureLimit()
    {
        String configured = com.ruoyi.common.utils.spring.SpringUtils.getBean(com.ruoyi.system.service.ISysConfigService.class).selectConfigByKey("drilling.login.maxFailures");
        return Integer.parseInt(configured == null || configured.isBlank() ? maxRetryCount : configured);
    }

    public boolean isFailureLocked(String loginName)
    {
        AtomicInteger count = loginRecordCache.get(loginName);
        return count != null && count.get() >= failureLimit();
    }

    public synchronized boolean unlockFailures(String loginName)
    {
        if (!isFailureLocked(loginName)) return false;
        clearLoginRecordCache(loginName);
        return true;
    }

    public synchronized void validate(SysUser user, String password)
    {
        int limit = failureLimit();
        String loginName = user.getLoginName();

        AtomicInteger retryCount = loginRecordCache.get(loginName);

        if (retryCount == null)
        {
            retryCount = new AtomicInteger(0);
            loginRecordCache.put(loginName, retryCount);
        }
        if (retryCount.get() >= limit)
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(loginName, Constants.LOGIN_FAIL, MessageUtils.message("user.password.retry.limit.exceed", limit)));
            throw new UserPasswordRetryLimitExceedException(limit);
        }

        if (!matches(user, password))
        {
            retryCount.incrementAndGet();
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(loginName, Constants.LOGIN_FAIL, MessageUtils.message("user.password.retry.limit.count", retryCount)));
            loginRecordCache.put(loginName, retryCount);
            throw new UserPasswordNotMatchException();
        }
        else
        {
            clearLoginRecordCache(loginName);
        }
    }

    public boolean matches(SysUser user, String newPassword)
    {
        return user.getPassword().equals(encryptPassword(user.getLoginName(), newPassword, user.getSalt()));
    }

    public synchronized void clearLoginRecordCache(String loginName)
    {
        loginRecordCache.remove(loginName);
    }

    public String encryptPassword(String loginName, String password, String salt)
    {
        return Md5Utils.hash(loginName + password + salt);
    }
}
