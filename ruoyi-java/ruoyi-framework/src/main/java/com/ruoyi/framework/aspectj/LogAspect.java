package com.ruoyi.framework.aspectj;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NamedThreadLocal;
import org.springframework.stereotype.Component;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.enums.BusinessStatus;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.manager.AsyncManager;
import com.ruoyi.framework.manager.factory.AsyncFactory;
import com.ruoyi.system.domain.SysOperLog;

/**
 * 操作日志记录处理
 * 
 * @author ruoyi
 */
@Aspect
@Component
public class LogAspect
{
    private static final Logger log = LoggerFactory.getLogger(LogAspect.class);

    /** 计算操作消耗时间 */
    private static final ThreadLocal<Long> TIME_THREADLOCAL = new NamedThreadLocal<Long>("Cost Time");

    /**
     * 处理请求前执行
     */
    @Before(value = "@annotation(controllerLog)")
    public void doBefore(JoinPoint joinPoint, Log controllerLog)
    {
        TIME_THREADLOCAL.set(System.currentTimeMillis());
    }

    /**
     * 处理完请求后执行
     *
     * @param joinPoint 切点
     */
    @AfterReturning(pointcut = "@annotation(controllerLog)", returning = "jsonResult")
    public void doAfterReturning(JoinPoint joinPoint, Log controllerLog, Object jsonResult)
    {
        handleLog(joinPoint, controllerLog, null, jsonResult);
    }

    /**
     * 拦截异常操作
     * 
     * @param joinPoint 切点
     * @param e 异常
     */
    @AfterThrowing(value = "@annotation(controllerLog)", throwing = "e")
    public void doAfterThrowing(JoinPoint joinPoint, Log controllerLog, Exception e)
    {
        handleLog(joinPoint, controllerLog, e, null);
    }

    protected void handleLog(final JoinPoint joinPoint, Log controllerLog, final Exception e, Object jsonResult)
    {
        try
        {
            // 获取当前的用户
            SysUser currentUser = ShiroUtils.getSysUser();

            // *========数据库日志=========*//
            SysOperLog operLog = new SysOperLog();
            operLog.setStatus(BusinessStatus.SUCCESS.ordinal());
            // 请求的地址
            String ip = ShiroUtils.getIp();
            operLog.setOperIp(ip);
            operLog.setOperUrl(StringUtils.substring(ServletUtils.getRequest().getRequestURI(), 0, 255));
            if (currentUser != null)
            {
                operLog.setOperName(currentUser.getLoginName());
                if (StringUtils.isNotNull(currentUser.getDept())
                        && StringUtils.isNotEmpty(currentUser.getDept().getDeptName()))
                {
                    operLog.setDeptName(currentUser.getDept().getDeptName());
                }
            }

            if (e != null)
            {
                operLog.setStatus(BusinessStatus.FAIL.ordinal());
                operLog.setErrorMsg(e.getClass().getSimpleName());
            }
            // 设置方法名称
            String className = joinPoint.getTarget().getClass().getName();
            String methodName = joinPoint.getSignature().getName();
            operLog.setMethod(className + "." + methodName + "()");
            // 设置请求方式
            operLog.setRequestMethod(ServletUtils.getRequest().getMethod());
            // 处理设置注解上的参数
            getControllerMethodDescription(joinPoint, controllerLog, operLog, jsonResult);
            // 设置消耗时间
            operLog.setCostTime(System.currentTimeMillis() - TIME_THREADLOCAL.get());
            // 保存数据库
            AsyncManager.me().execute(AsyncFactory.recordOper(operLog));
        }
        catch (Exception exp)
        {
            // 记录本地异常日志
            log.error("操作日志写入失败 ({})", exp.getClass().getSimpleName());
        }
        finally
        {
            TIME_THREADLOCAL.remove();
        }
    }

    /**
     * 获取注解中对方法的描述信息 用于Controller层注解
     * 
     * @param log 日志
     * @param operLog 操作日志
     * @throws Exception
     */
    public void getControllerMethodDescription(JoinPoint joinPoint, Log log, SysOperLog operLog, Object jsonResult) throws Exception
    {
        // 设置action动作
        operLog.setBusinessType(log.businessType().ordinal());
        // 设置标题
        operLog.setTitle(log.title());
        // 设置操作人类别
        operLog.setOperatorType(log.operatorType().ordinal());
    }
}
