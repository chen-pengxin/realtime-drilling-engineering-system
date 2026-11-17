package com.ruoyi.web.controller.operations;

import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages = "com.ruoyi.web.controller.operations")
public class OperationsExceptionAdvice {
  @ExceptionHandler({IllegalArgumentException.class, DuplicateKeyException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public AjaxResult invalid(Exception e) {
    return AjaxResult.error(e instanceof DuplicateKeyException ? "名称或编码已存在" : e.getMessage())
        .put("code", 400);
  }
}
