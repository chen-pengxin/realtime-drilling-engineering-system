package com.ruoyi.web.controller.drilling;

import com.ruoyi.framework.security.DrillingDataScope;
import java.util.*;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/drilling/fields/{fieldId}/library")
public class EngineeringLibraryController {
  private final EngineeringLibraryService library;
  private final DrillingFileController files;
  private final EngineeringTableService tables;
  public EngineeringLibraryController(EngineeringLibraryService library, DrillingFileController files,EngineeringTableService tables){this.library=library;this.files=files;this.tables=tables;}

  @GetMapping("/wellbores/{wellboreId}/engineering-data") @RequiresPermissions("drilling:well:list")
  public Map<String,Object> engineeringData(@PathVariable Long fieldId,@PathVariable Long wellboreId,@RequestParam(required=false) Long versionId){return tables.data(fieldId,wellboreId,versionId);}
  @PutMapping("/wellbores/{wellboreId}/basic-data") @RequiresPermissions("drilling:well:edit")
  public void saveBasic(@PathVariable Long fieldId,@PathVariable Long wellboreId,@RequestBody Map<String,Object> body){tables.saveBasic(fieldId,wellboreId,body);}

  @GetMapping("/documents/{documentId}") @RequiresPermissions("drilling:file:list")
  public Map<String,Object> document(@PathVariable Long fieldId,@PathVariable Long documentId){return library.documentContext(fieldId,documentId,false);}

  @ExceptionHandler(ResponseStatusException.class)
  public org.springframework.http.ResponseEntity<Map<String,Object>> conflict(ResponseStatusException e){return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("code",e.getStatusCode().value(),"msg",Objects.requireNonNullElse(e.getReason(),"请求被拒绝")));}

  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  public org.springframework.http.ResponseEntity<Map<String,Object>> duplicate(org.springframework.dao.DuplicateKeyException e){return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code",409,"msg","名称、编码或所选版本中的靶点名称已存在，请修改后保存"));}

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public org.springframework.http.ResponseEntity<Map<String,Object>> storageError(org.springframework.dao.DataAccessException error){
    org.slf4j.LoggerFactory.getLogger(getClass()).error("工程资料数据库操作失败",error);
    return org.springframework.http.ResponseEntity.status(503).body(Map.of("code",503,"msg","工程资料读取或保存失败，请刷新后重试"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public org.springframework.http.ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException e){return org.springframework.http.ResponseEntity.badRequest().body(Map.of("code",400,"msg",Objects.requireNonNullElse(e.getMessage(),"请检查工程资料信息")));}

  @GetMapping("/tree") @RequiresPermissions("drilling:well:list")
  public List<Map<String,Object>> tree(@PathVariable Long fieldId){
    DrillingDataScope.current().requireAccess(fieldId);
    return library.tree(fieldId);
  }
  @GetMapping("/nodes/{nodeId}") @RequiresPermissions("drilling:well:list")
  public Map<String,Object> contents(@PathVariable Long fieldId,@PathVariable Long nodeId,
      @RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String role,
      @RequestParam(defaultValue="")String trajectoryKind,
      @RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,
      @RequestParam(defaultValue="date")String sort,@RequestParam(defaultValue="desc")String order){
    if(!trajectoryKind.isEmpty()&&!Set.of("DESIGN","ACTUAL").contains(trajectoryKind))throw new IllegalArgumentException("轨迹类别无效");
    var node=library.nodeInField(fieldId,nodeId,false);
    var result=new LinkedHashMap<String,Object>(library.scopedContents(fieldId,nodeId,keyword,role,trajectoryKind,pageNum,pageSize,sort,order));result.put("tree",library.tree(fieldId));return result;
  }
  @PostMapping("/nodes") @RequiresPermissions("drilling:well:add") @Transactional
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String,Object> createNode(@PathVariable Long fieldId,@RequestBody Map<String,Object>b){if(!fieldId.equals(EngineeringLibraryService.id(b.get("fieldId"))))throw new IllegalArgumentException("fieldId与路径不一致");return library.createNode(b);}
  @PutMapping("/nodes/{nodeId}") @RequiresPermissions("drilling:well:edit") @Transactional
  public Map<String,Object> updateNode(@PathVariable Long fieldId,@PathVariable Long nodeId,@RequestBody Map<String,Object>b){var old=library.nodeInField(fieldId,nodeId,true);if(nodeId<0)throw new IllegalArgumentException("区块和井场请在当前目录中直接管理");return library.updateNode(nodeId,b);}
  @DeleteMapping("/nodes/{nodeId}") @RequiresPermissions("drilling:well:remove") @Transactional
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void archiveNode(@PathVariable Long fieldId,@PathVariable Long nodeId){library.nodeInField(fieldId,nodeId,true);library.archiveNode(nodeId);}
  @GetMapping("/nodes/{nodeId}/delete-impact") @RequiresPermissions("drilling:well:remove")
  public Map<String,Object> deleteImpact(@PathVariable Long fieldId,@PathVariable Long nodeId){return library.deletionImpact(fieldId,nodeId);}
  @GetMapping("/wellbores/{wellboreId}/targets") @RequiresPermissions("drilling:well:list")
  public List<Map<String,Object>> targets(@PathVariable Long fieldId,@PathVariable Long wellboreId){ return library.targets(fieldId,wellboreId); }
  @PostMapping("/wellbores/{wellboreId}/targets") @RequiresPermissions("drilling:well:edit") @Transactional
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String,Object> createTarget(@PathVariable Long fieldId,@PathVariable Long wellboreId,@RequestBody Map<String,Object> body){ return library.createTarget(fieldId,wellboreId,body); }
  @PutMapping("/targets/{targetId}") @RequiresPermissions("drilling:well:edit") @Transactional
  public Map<String,Object> updateTarget(@PathVariable Long fieldId,@PathVariable Long targetId,@RequestBody Map<String,Object> body){ return library.updateTarget(fieldId,targetId,body); }
  @DeleteMapping("/targets/{targetId}") @RequiresPermissions("drilling:well:edit") @Transactional
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void archiveTarget(@PathVariable Long fieldId,@PathVariable Long targetId){ library.archiveTarget(fieldId,targetId); }
  @GetMapping("/wellbores/{wellboreId}/trajectory-target-points") @RequiresPermissions("drilling:well:list")
  public List<Map<String,Object>> trajectoryTargetPoints(@PathVariable Long fieldId,@PathVariable Long wellboreId,@RequestParam(required=false) Long versionId){ return library.trajectoryTargetPoints(fieldId,wellboreId,versionId); }
  @PostMapping("/wellbores/{wellboreId}/trajectory-target-points") @RequiresPermissions("drilling:well:edit")
  public Map<String,Object> createTrajectoryTargetPoint(@PathVariable Long fieldId,@PathVariable Long wellboreId,@RequestBody Map<String,Object> body){return library.createTrajectoryTargetPoint(fieldId,wellboreId,body);}
  @PutMapping("/wellbores/{wellboreId}/trajectory-target-points/{targetPointId}") @RequiresPermissions("drilling:well:edit") @Transactional
  public Map<String,Object> updateTrajectoryTargetPoint(@PathVariable Long fieldId,@PathVariable Long wellboreId,@PathVariable Long targetPointId,@RequestBody Map<String,Object> body){ return library.updateTrajectoryTargetPoint(fieldId,wellboreId,targetPointId,body); }
  @DeleteMapping("/wellbores/{wellboreId}/trajectory-target-points/{targetPointId}") @RequiresPermissions("drilling:well:edit") @Transactional
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void archiveTrajectoryTargetPoint(@PathVariable Long fieldId,@PathVariable Long wellboreId,@PathVariable Long targetPointId,@RequestParam(required=false) Long versionId){ library.archiveTrajectoryTargetPoint(fieldId,wellboreId,targetPointId,versionId); }
  @PostMapping("/nodes/{nodeId}/documents") @RequiresPermissions("drilling:file:add") @Transactional
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String,Object> attachDocument(@PathVariable Long fieldId,@PathVariable Long nodeId,@RequestBody Map<String,Object>b){var n=library.fileTarget(fieldId,nodeId);return library.attachFile(EngineeringLibraryService.id(n.get("node_id")),EngineeringLibraryService.id(b.get("sourceId")),b);}
  @PostMapping("/nodes/{nodeId}/revisions") @RequiresPermissions("drilling:well:survey:import") @Transactional
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String,Object> createRevision(@PathVariable Long fieldId,@PathVariable Long nodeId,@RequestBody Map<String,Object>b){var n=library.nodeInField(fieldId,nodeId,true);if(nodeId<0)throw new IllegalArgumentException("请先选择设计或实测成果节点");return library.createRevision(nodeId,b);}
  @PostMapping("/revisions/{revisionId}/data") @RequiresPermissions("drilling:well:survey:import") @Transactional
  public Map<String,Object> attachData(@PathVariable Long fieldId,@PathVariable Long revisionId,@RequestBody Map<String,Object>b){var r=library.revision(revisionId,true);var n=library.node(EngineeringLibraryService.id(r.get("node_id")),true);if(!fieldId.equals(EngineeringLibraryService.id(n.get("field_id"))))throw new IllegalArgumentException("成果不属于当前工程");library.attachData(revisionId,EngineeringLibraryService.id(b.get("surveyVersionId")),Objects.toString(b.get("label"),"轨迹数据"));return library.detail(revisionId);}
  @GetMapping("/revisions/{revisionId}") @RequiresPermissions("drilling:well:list")
  public Map<String,Object> revision(@PathVariable Long fieldId,@PathVariable Long revisionId){var r=library.revision(revisionId,false);var n=library.node(EngineeringLibraryService.id(r.get("node_id")),false);if(!fieldId.equals(EngineeringLibraryService.id(n.get("field_id"))))throw new IllegalArgumentException("成果不属于当前工程");return library.detail(revisionId);}
  @PutMapping("/revisions/{revisionId}/activate") @RequiresPermissions("drilling:well:survey:import") @Transactional
  public Map<String,Object> activate(@PathVariable Long fieldId,@PathVariable Long revisionId,@RequestBody Map<String,Object>b){var r=library.revision(revisionId,true);var n=library.node(EngineeringLibraryService.id(r.get("node_id")),true);if(!fieldId.equals(EngineeringLibraryService.id(n.get("field_id"))))throw new IllegalArgumentException("成果不属于当前工程");return library.activate(revisionId,b);}
  @GetMapping("/documents/{documentId}/history") @RequiresPermissions("drilling:file:list")
  public List<Map<String,Object>> documentHistory(@PathVariable Long fieldId,@PathVariable Long documentId){
    library.documentContext(fieldId,documentId,false);
    return library.documentHistory(documentId);
  }
  @PutMapping("/documents/{documentId}/status") @RequiresPermissions("drilling:file:upload") @Transactional
  public void updateFileStatus(@PathVariable Long fieldId,@PathVariable Long documentId,@RequestBody Map<String,Object> body){
    library.updateFileStatus(fieldId,documentId,Objects.toString(body.get("usageState"),""));
  }

  @GetMapping("/documents/{documentId}/impact") @RequiresPermissions("drilling:file:list")
  public Map<String,Object> fileImpact(@PathVariable Long fieldId,@PathVariable Long documentId){return library.fileImpact(fieldId,documentId);}
  @DeleteMapping("/documents/{documentId}") @RequiresPermissions("drilling:file:remove")
  public void deleteDocument(@PathVariable Long fieldId,@PathVariable Long documentId){library.deleteDocument(fieldId,documentId);}


}
