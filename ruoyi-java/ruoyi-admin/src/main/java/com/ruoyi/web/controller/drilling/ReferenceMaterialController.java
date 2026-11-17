package com.ruoyi.web.controller.drilling;

import com.ruoyi.common.config.RuoYiConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Global technical references; these are intentionally outside the field tree. */
@RestController
@RequiresAuthentication
@RequestMapping("/api/drilling/reference-materials")
public class ReferenceMaterialController {
  private final JdbcTemplate db;
  private final DrillingObjectStorage storage;
  private final SourceContentService contents;
  private final NativeEngineSupport engine;

  public ReferenceMaterialController(JdbcTemplate db, DrillingObjectStorage storage,SourceContentService contents,NativeEngineSupport engine) {
    this.db = db;
    this.storage = storage;
    this.contents=contents;this.engine=engine;
  }

  @GetMapping("/{referenceId}/content")
  @RequiresPermissions("drilling:file:list")
  public Map<String,Object> content(@PathVariable Long referenceId,@org.springframework.web.bind.annotation.RequestParam(required=false) Long parseId){
    var reference=db.queryForMap("SELECT f.original_name,f.display_name FROM drilling_reference_material r JOIN data_file f ON f.file_id=r.file_id WHERE r.reference_id=? AND r.status='ACTIVE' AND f.status='ACTIVE'",referenceId);
    var result=contents.manifest(null,referenceId,parseId);result.putAll(reference);return result;
  }

  @GetMapping("/{referenceId}/content/{sectionId}")
  @RequiresPermissions("drilling:file:list")
  public Map<String,Object> section(@PathVariable Long referenceId,@PathVariable Long sectionId,
      @org.springframework.web.bind.annotation.RequestParam Long parseId,@org.springframework.web.bind.annotation.RequestParam(defaultValue="1") int pageNum,
      @org.springframework.web.bind.annotation.RequestParam(defaultValue="50") int pageSize,@org.springframework.web.bind.annotation.RequestParam(defaultValue="") String keyword){
    content(referenceId,parseId);return contents.section(null,referenceId,parseId,sectionId,pageNum,pageSize,keyword);
  }

  @org.springframework.web.bind.annotation.PostMapping("/{referenceId}/reparse")
  @RequiresPermissions("drilling:well:survey:import")
  public Map<String,Object> reparse(@PathVariable Long referenceId) throws IOException{
    var row=db.queryForMap("SELECT f.original_name FROM drilling_reference_material r JOIN data_file f ON f.file_id=r.file_id WHERE r.reference_id=? AND r.status='ACTIVE' AND f.status='ACTIVE'",referenceId);
    try(InputStream input=download(referenceId).getBody().getInputStream()){
      var inspected=engine.inspect(String.valueOf(row.get("original_name")),input.readAllBytes());
      Long parseId=contents.save(null,referenceId,inspected);return content(referenceId,parseId);
    }
  }

  @GetMapping("/{referenceId}/content/{sectionId}/asset")
  @RequiresPermissions("drilling:file:list")
  public ResponseEntity<byte[]> asset(@PathVariable Long referenceId,@PathVariable Long sectionId,@org.springframework.web.bind.annotation.RequestParam Long parseId) throws IOException{
    var manifest=content(referenceId,parseId);var asset=contents.asset(null,referenceId,parseId,sectionId);
    try(InputStream input=download(referenceId).getBody().getInputStream()){
      return SourceContentController.assetResponse(engine.binary("/source/asset",Map.of("original_name",manifest.get("original_name"),"original_base64",java.util.Base64.getEncoder().encodeToString(input.readAllBytes()),"asset",asset)),asset);
    }
  }

  @GetMapping
  @RequiresPermissions("drilling:file:list")
  public List<Map<String, Object>> list() {
    return db.queryForList(
        "SELECT r.reference_id,r.material_code,r.title,r.standard_no,r.material_type,r.page_count,"
            + "r.appendix_tables_json,r.source_note,r.create_time,f.file_id,f.original_name,f.file_size,f.sha256,f.content_type "
            + "FROM drilling_reference_material r JOIN data_file f ON f.file_id=r.file_id "
            + "WHERE r.status='ACTIVE' AND f.status='ACTIVE' ORDER BY r.title");
  }

  @GetMapping("/{referenceId}/download")
  @RequiresPermissions("drilling:file:list")
  public ResponseEntity<InputStreamResource> download(@PathVariable Long referenceId) throws IOException {
    Map<String, Object> row = db.queryForMap(
        "SELECT f.object_key,f.original_name,f.content_type,f.file_size FROM drilling_reference_material r "
            + "JOIN data_file f ON f.file_id=r.file_id WHERE r.reference_id=? AND r.status='ACTIVE' AND f.status='ACTIVE'",
        referenceId);
    String objectKey = String.valueOf(row.get("object_key"));
    String contentType = row.get("content_type") == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : String.valueOf(row.get("content_type"));
    if (storage.enabled()) {
      DrillingObjectStorage.RemoteObject remote = storage.get(objectKey);
      if (remote != null) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(String.valueOf(row.get("original_name"))).build().toString())
            .contentType(parseType(remote.contentType() == null ? contentType : remote.contentType()))
            .contentLength(remote.size()).body(new InputStreamResource(remote.stream()));
      }
    }
    Path root = Path.of(RuoYiConfig.getProfile(), "reference-materials").toAbsolutePath().normalize();
    Path file = Path.of(RuoYiConfig.getProfile()).resolve(objectKey).toAbsolutePath().normalize();
    if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new IllegalArgumentException("参考资料原件不存在");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(String.valueOf(row.get("original_name"))).build().toString())
        .contentType(parseType(contentType)).contentLength(Files.size(file))
        .body(new InputStreamResource(Files.newInputStream(file)));
  }

  private static MediaType parseType(String value) {
    try { return MediaType.parseMediaType(value); }
    catch (IllegalArgumentException ex) { return MediaType.APPLICATION_OCTET_STREAM; }
  }
}

