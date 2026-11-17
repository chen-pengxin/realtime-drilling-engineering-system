package com.ruoyi.web.controller.drilling;

import java.util.List;
import java.util.Map;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drilling/library")
public class EngineeringCatalogController {
  private final EngineeringLibraryService library;

  public EngineeringCatalogController(EngineeringLibraryService library) {
    this.library = library;
  }

  @GetMapping("/tree")
  @RequiresPermissions(value={"drilling:block:list","drilling:well:list"},logical=org.apache.shiro.authz.annotation.Logical.OR)
  public List<Map<String, Object>> tree() {
    return library.catalogTree();
  }
}
