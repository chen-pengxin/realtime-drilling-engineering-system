package com.ruoyi.web.controller.drilling;

import com.ruoyi.framework.security.DrillingDataScope;
import com.alibaba.fastjson.JSON;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
  public EngineeringLibraryController(EngineeringLibraryService library, DrillingFileController files){this.library=library;this.files=files;}

  @GetMapping("/documents/{documentId}") @RequiresPermissions("drilling:file:list")
  public Map<String,Object> document(@PathVariable Long fieldId,@PathVariable Long documentId){return library.documentContext(fieldId,documentId,false);}

  @ExceptionHandler(ResponseStatusException.class)
  public org.springframework.http.ResponseEntity<Map<String,Object>> conflict(ResponseStatusException e){return org.springframework.http.ResponseEntity.status(e.getStatusCode()).body(Map.of("code",e.getStatusCode().value(),"msg",Objects.requireNonNullElse(e.getReason(),"请求被拒绝")));}

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
  @PostMapping("/nodes/{nodeId}/documents") @RequiresPermissions("drilling:file:add") @Transactional
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String,Object> attachDocument(@PathVariable Long fieldId,@PathVariable Long nodeId,@RequestBody Map<String,Object>b){var n=library.nodeInField(fieldId,nodeId,true);if(nodeId<0)throw new IllegalArgumentException("请先选择井场、井、井眼或成果节点");return library.attachFile(nodeId,EngineeringLibraryService.id(b.get("sourceId")),b);}
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

  /** Download the original bytes below a node without format conversion. */
  @GetMapping("/nodes/{nodeId}/original-package") @RequiresPermissions("drilling:file:list")
  public org.springframework.http.ResponseEntity<byte[]> originalPackage(
      @PathVariable Long fieldId, @PathVariable Long nodeId) throws IOException {
    var rows=library.sourceFilesForExport(fieldId,nodeId);
    if(rows.isEmpty()) throw new IllegalArgumentException("当前工程节点没有可导出的原件");
    var manifest=new ArrayList<Map<String,Object>>();
    var output=new ByteArrayOutputStream();
    try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){
      for(var row:rows){
        String original=Objects.toString(row.get("original_name"),"工程资料");
        String leaf=DrillingFileController.safeName(original);
        // Separate directories keep duplicate original names intact as well.
        String archivePath="original/"+row.get("source_id")+"/"+leaf;
        byte[] bytes=files.readBytes(EngineeringLibraryService.id(row.get("storage_field_id")),EngineeringLibraryService.id(row.get("file_id")));
        zip.putNextEntry(new ZipEntry(archivePath));zip.write(bytes);zip.closeEntry();
        manifest.add(new LinkedHashMap<>(Map.of("sourceId",row.get("source_id"),"sourcePath",row.get("source_path"),"originalName",original,"sha256",row.get("sha256"),"size",row.get("file_size"),"archivePath",archivePath)));
      }
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(JSON.toJSONString(Map.of("fieldId",fieldId,"nodeId",nodeId,"originals",manifest,"policy","原件按入库字节导出；未进行格式转换")).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition",org.springframework.http.ContentDisposition.attachment().filename("engineering-originals.zip",StandardCharsets.UTF_8).build().toString())
        .contentType(org.springframework.http.MediaType.parseMediaType("application/zip")).body(output.toByteArray());
  }
}
