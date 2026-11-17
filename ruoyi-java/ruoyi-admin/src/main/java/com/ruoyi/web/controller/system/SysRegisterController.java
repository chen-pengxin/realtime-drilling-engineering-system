package com.ruoyi.web.controller.system;
import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.web.bind.annotation.*;
/** Accounts are provisioned exclusively by a system administrator. */
@RestController
public class SysRegisterController {
    @RequestMapping(value="/register", method={RequestMethod.GET,RequestMethod.POST})
    public AjaxResult disabled() { return AjaxResult.error("本系统不开放注册，请联系系统管理员添加账号").put("code",403); }
}
