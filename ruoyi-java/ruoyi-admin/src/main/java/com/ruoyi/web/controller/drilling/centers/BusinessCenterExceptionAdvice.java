package com.ruoyi.web.controller.drilling.centers;

import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages = "com.ruoyi.web.controller.drilling.centers")
public class BusinessCenterExceptionAdvice {
  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  public org.springframework.http.ResponseEntity<Map<String, Object>> status(
      org.springframework.web.server.ResponseStatusException e) {
    return org.springframework.http.ResponseEntity.status(e.getStatusCode())
        .body(
            Map.of(
                "code", e.getStatusCode().value(), "msg", Objects.toString(e.getReason(), "请求失败")));
  }

  @ExceptionHandler(org.apache.shiro.authz.AuthorizationException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public Map<String, Object> forbidden(Exception e) {
    return Map.of("code", 403, "msg", "无权访问此井场或执行此操作");
  }

  @ExceptionHandler({IllegalArgumentException.class, DuplicateKeyException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, Object> invalid(Exception e) {
    return Map.of(
        "code", 400, "msg", e instanceof DuplicateKeyException ? "名称或编号已存在" : e.getMessage());
  }
}
