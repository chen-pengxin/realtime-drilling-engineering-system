package com.ruoyi.web.controller.drilling;

import com.alibaba.fastjson.JSON;
import com.ruoyi.framework.security.DrillingDataScope;
import java.io.IOException;
import java.util.*;
import org.apache.shiro.authz.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Reads saved content, not a fresh best-effort table extraction on each click. */
@RestController
@RequiresAuthentication
@RequestMapping("/api/drilling/fields/{fieldId}/registry/sources/{sourceId}")
public class SourceContentController {
  private final JdbcTemplate db;
  private final SourceContentService contents;
  private final DrillingFileController files;
  private final NativeEngineSupport engine;

  public SourceContentController(SourceRegistrySupport schema,SourceContentService contents,
      DrillingFileController files,NativeEngineSupport engine){
    this.db=schema.jdbc;this.contents=contents;this.files=files;this.engine=engine;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException error){
    return ResponseEntity.unprocessableEntity().body(Map.of("code",422,"msg",Objects.toString(error.getMessage(),"来源内容无效")));
  }

  private Map<String,Object> source(Long fieldId,Long sourceId,boolean write){
    if(write)DrillingDataScope.current().requireRole(fieldId,"OWNER","ENGINEER");
    else DrillingDataScope.current().requireAccess(fieldId);
    var rows=db.queryForList("SELECT s.*,f.original_name,f.display_name,f.sha256 FROM drilling_source_document s JOIN data_file f ON f.file_id=s.file_id WHERE s.field_id=? AND s.source_id=? AND f.status='ACTIVE'",fieldId,sourceId);
    if(rows.isEmpty())throw new IllegalArgumentException("来源文件不存在或不属于当前井场");
    return rows.get(0);
  }

  @GetMapping("/content")
  @RequiresPermissions("drilling:file:list")
  public Map<String,Object> content(@PathVariable Long fieldId,@PathVariable Long sourceId,@RequestParam(required=false) Long parseId){
    var file=source(fieldId,sourceId,false);var result=contents.manifest(sourceId,null,parseId);
    result.put("original_name",file.get("original_name"));result.put("display_name",file.get("display_name"));return result;
  }

  @GetMapping("/original-preview") @RequiresPermissions("drilling:file:list")
  public ResponseEntity<byte[]> originalPreview(@PathVariable Long fieldId,@PathVariable Long sourceId) throws IOException{
    var file=source(fieldId,sourceId,false);var payload=new LinkedHashMap<String,Object>();
    payload.put("original_base64",Base64.getEncoder().encodeToString(files.readBytes(fieldId,((Number)file.get("file_id")).longValue())));
    payload.put("original_name",file.get("original_name"));
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("Cache-Control","private, max-age=3600").body(engine.binary("/source/original-preview",payload));
  }

  @GetMapping("/content/{sectionId}")
  @RequiresPermissions("drilling:file:list")
  public Map<String,Object> section(@PathVariable Long fieldId,@PathVariable Long sourceId,@PathVariable Long sectionId,
      @RequestParam Long parseId,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="50") int pageSize,@RequestParam(defaultValue="") String keyword){
    source(fieldId,sourceId,false);return contents.section(sourceId,null,parseId,sectionId,pageNum,pageSize,keyword);
  }

  @GetMapping("/content/{sectionId}/asset")
  @RequiresPermissions("drilling:file:list")
  public ResponseEntity<byte[]> asset(@PathVariable Long fieldId,@PathVariable Long sourceId,@PathVariable Long sectionId,@RequestParam Long parseId) throws IOException{
    var file=source(fieldId,sourceId,false);var asset=contents.asset(sourceId,null,parseId,sectionId);
    var request=new LinkedHashMap<String,Object>();request.put("original_base64",Base64.getEncoder().encodeToString(files.readBytes(fieldId,((Number)file.get("file_id")).longValue())));
    request.put("original_name",file.get("original_name"));request.put("asset",asset);
    return assetResponse(engine.binary("/source/asset",request),asset);
  }

  static ResponseEntity<byte[]> assetResponse(byte[] bytes,Map<String,Object> asset){
    String type="image/png";
    if(bytes.length>=4&&bytes[0]=='%'&&bytes[1]=='P'&&bytes[2]=='D'&&bytes[3]=='F')type="application/pdf";
    else if(bytes.length>=3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255)type="image/jpeg";
    else if(bytes.length>=3&&bytes[0]=='G'&&bytes[1]=='I'&&bytes[2]=='F')type="image/gif";
    else if(bytes.length>=12&&bytes[8]=='W'&&bytes[9]=='E'&&bytes[10]=='B'&&bytes[11]=='P')type="image/webp";
    else if(bytes.length>=2&&bytes[0]=='I'&&bytes[1]=='I'||bytes.length>=2&&bytes[0]=='M'&&bytes[1]=='M')type="image/tiff";
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header("X-Content-Type-Options","nosniff")
        .header("Cache-Control","private, max-age=3600").body(bytes);
  }

  @PostMapping("/reparse")
  @RequiresPermissions("drilling:well:survey:import")
  @Transactional
  public Map<String,Object> reparse(@PathVariable Long fieldId,@PathVariable Long sourceId) throws IOException{
    var file=source(fieldId,sourceId,true);
    var inspected=engine.inspect(Objects.toString(file.get("original_name")),files.readBytes(fieldId,((Number)file.get("file_id")).longValue()));
    Long parseId=contents.save(sourceId,null,inspected);
    if(parseId==null)throw new IllegalArgumentException("未能读取正文或表格，请检查文件格式、加密状态及解析诊断");
    // Candidate extractions are append-only; no reviewed measurements, file
    // editions, review confirmations or active trajectory heads are replaced.
    if(inspected.get("trajectories") instanceof List<?> tables)for(Object value:tables){
      var table=SourceRegistryController.obj(value);
      db.update("INSERT IGNORE INTO drilling_source_extraction(source_id,locator,payload_json) VALUES(?,?,?)",sourceId,table.get("locator"),JSON.toJSONString(table));
    }
    db.update("UPDATE drilling_source_document SET parse_status=?,diagnostics_json=? WHERE source_id=?",inspected.get("status"),JSON.toJSONString(inspected.get("diagnostics")),sourceId);
    var result=contents.manifest(sourceId,null,parseId);result.put("original_name",file.get("original_name"));result.put("display_name",file.get("display_name"));return result;
  }
}
