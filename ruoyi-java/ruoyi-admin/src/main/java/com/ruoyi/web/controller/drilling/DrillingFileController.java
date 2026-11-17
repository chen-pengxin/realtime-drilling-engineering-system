package com.ruoyi.web.controller.drilling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.utils.ShiroUtils;

/** Field-scoped file storage, version metadata and download authorization. */
@RestController
@RequestMapping("/api/drilling/fields/{fieldId}/files")
@RequiresAuthentication
public class DrillingFileController
{
    private static final Set<String> CATEGORIES = Set.of("DATASET", "SURVEY", "MODEL", "REPORT", "TEMPLATE", "OTHER");
    private static final Set<String> EXTENSIONS = Set.of("csv", "tsv", "txt", "xlsx", "xls", "sqlite", "db", "xml", "witsml", "json", "py", "zip", "pt", "pth", "onnx", "docx", "doc", "wps", "pdf", "", "asc", "dat", "mwd", "survey", "jpg", "tif", "md");
    private final JdbcTemplate jdbc;
    private final DrillingObjectStorage objectStorage;

    public DrillingFileController(JdbcTemplate jdbc, DrillingObjectStorage objectStorage) {
        this.jdbc = jdbc;
        this.objectStorage = objectStorage;
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> conflict(org.springframework.web.server.ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("code",error.getStatusCode().value(),"msg",java.util.Objects.requireNonNullElse(error.getReason(),"文件操作被拒绝")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException error) {
        return ResponseEntity.unprocessableEntity().body(Map.of("code",422,"msg",java.util.Objects.requireNonNullElse(error.getMessage(),"文件参数无效")));
    }

    @GetMapping
    @RequiresPermissions("drilling:file:list")
    public List<Map<String,Object>> list(@PathVariable Long fieldId,
                                         @RequestParam(required=false) String category,
                                         @RequestParam(defaultValue="false") boolean templates)
    {
        requireFieldAccess(fieldId);
        String normalized = category == null || category.isBlank() ? null : category.toUpperCase(Locale.ROOT);
        return jdbc.queryForList("""
                SELECT file_id, field_id, file_category, business_type, original_name, display_name, object_key, content_type,
                       file_size, sha256, version_no, parent_file_id, is_template, status, create_by, create_time
                  FROM data_file
                 WHERE field_id=? AND status='ACTIVE' AND (? IS NULL OR file_category=?)
                   AND is_template=?
                 ORDER BY create_time DESC, file_id DESC
                """, fieldId, normalized, normalized, templates ? "1" : "0");
    }

    @PostMapping
    @RequiresPermissions("drilling:file:upload")
    @Transactional
    public Map<String,Object> upload(@PathVariable Long fieldId, @RequestPart("file") MultipartFile file,
                                     @RequestParam(defaultValue="DATASET") String category,
                                     @RequestParam(required=false) String businessType,
                                     @RequestParam(required=false) Long parentFileId,
                                     @RequestParam(defaultValue="false") boolean template) throws IOException
    {
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("file is empty");
        String group = category.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(group)) throw new IllegalArgumentException("unsupported file category");
        String originalName = safeName(file.getOriginalFilename());
        String extension = extension(originalName);
        if (!EXTENSIONS.contains(extension)) throw new IllegalArgumentException("unsupported file extension: " + extension);
        Map<String,Object> parent = null;
        if (parentFileId != null)
        {
            parent = jdbc.query("SELECT file_id, field_id, file_category, original_name, version_no FROM data_file WHERE file_id=? AND field_id=? AND status='ACTIVE'",
                    rs -> rs.next() ? Map.of("fileId", rs.getLong("file_id"), "category", rs.getString("file_category"), "name", rs.getString("original_name"), "version", rs.getInt("version_no")) : null,
                    parentFileId, fieldId);
            if (parent == null) throw new IllegalArgumentException("parent file is not accessible in this field");
            if (!group.equals(parent.get("category"))) throw new IllegalArgumentException("file versions must keep the same category");
        }

        Path fieldRoot = Path.of(RuoYiConfig.getProfile(), "drilling-files", String.valueOf(fieldId)).toAbsolutePath().normalize();
        Files.createDirectories(fieldRoot);
        Path temporary = Files.createTempFile(fieldRoot, ".upload-", ".tmp");
        String hash;
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream source = file.getInputStream(); DigestInputStream hashing = new DigestInputStream(source, digest))
            {
                Files.copy(hashing, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            hash = HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) { Files.deleteIfExists(temporary); throw new IllegalStateException("SHA-256 is unavailable", impossible); }

        String storedName = UUID.randomUUID() + "." + extension;
        Path destination = fieldRoot.resolve(storedName).normalize();
        if (!destination.startsWith(fieldRoot)) { Files.deleteIfExists(temporary); throw new IllegalArgumentException("invalid file path"); }
        // Some Windows volumes (and a number of network-mounted development
        // directories) do not implement an atomic rename.  Keep the atomic
        // path where it is available, but do not make an otherwise valid
        // upload fail solely because the filesystem lacks that capability.
        try
        {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException ex)
        {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        String objectKey = "drilling-files/" + fieldId + "/" + storedName;
        // Keep the profile copy for local development and disaster recovery,
        // while uploading the same immutable key to MinIO when configured.
        // A failed object upload aborts the metadata transaction and removes
        // the local temporary object so callers never receive a false success.
        if (objectStorage.enabled())
        {
            try (InputStream content = Files.newInputStream(destination))
            {
                objectStorage.put(objectKey, content, Files.size(destination), file.getContentType());
            }
            catch (IOException ex)
            {
                Files.deleteIfExists(destination);
                // A failed/timeouted PUT may have reached MinIO just before
                // the client observed the error.  Best-effort removal keeps
                // the immutable object namespace aligned with data_file.
                try { objectStorage.remove(objectKey); }
                catch (IOException cleanup) { ex.addSuppressed(cleanup); }
                throw ex;
            }
        }
        int version = parent == null ? 1 : ((Number) parent.get("version")).intValue() + 1;
        try
        {
            jdbc.update("""
                    INSERT INTO data_file(field_id, owner_user_id, file_category, business_type, original_name,
                        object_key, content_type, file_size, sha256, version_no, parent_file_id, is_template,
                        status, create_by)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?, 'ACTIVE',?)
                    """, fieldId, ShiroUtils.getUserId(), group, businessType, originalName, objectKey,
                    file.getContentType(), Files.size(destination), hash, version, parentFileId,
                    template ? "1" : "0", ShiroUtils.getLoginName());
        }
        catch (RuntimeException ex)
        {
            Files.deleteIfExists(destination);
            if (objectStorage.enabled())
            {
                try { objectStorage.remove(objectKey); }
                catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            }
            throw ex;
        }
        return jdbc.queryForMap("SELECT file_id, field_id, file_category, business_type, original_name, display_name, content_type, file_size, sha256, version_no, parent_file_id, is_template, status, create_by, create_time FROM data_file WHERE object_key=?", objectKey);
    }

    @GetMapping("/{fileId}/download")
    @RequiresPermissions("drilling:file:list")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long fieldId, @PathVariable Long fileId) throws IOException
    {
        requireFieldAccess(fieldId);
        Map<String,Object> row = jdbc.queryForMap("SELECT original_name, object_key, content_type FROM data_file WHERE file_id=? AND field_id=? AND status='ACTIVE'", fileId, fieldId);
        // Ownership can be corrected without moving immutable archived bytes.
        // The exact file/field authorization above remains authoritative.
        Path root = Path.of(RuoYiConfig.getProfile(), "drilling-files").toAbsolutePath().normalize();
        Path file = Path.of(RuoYiConfig.getProfile()).resolve(String.valueOf(row.get("object_key"))).toAbsolutePath().normalize();
        String objectKey = String.valueOf(row.get("object_key"));
        String contentType = row.get("content_type") == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : String.valueOf(row.get("content_type"));

        // MinIO is the primary source when configured.  If a local recovery
        // copy exists, retain it as a transparent fallback during a storage
        // outage so old development installations remain usable.
        if (objectStorage.enabled())
        {
            try
            {
                DrillingObjectStorage.RemoteObject remote = objectStorage.get(objectKey);
                if (remote != null)
                {
                    String remoteType = remote.contentType() == null || remote.contentType().isBlank() ? contentType : remote.contentType();
                    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                                    ContentDisposition.attachment().filename(String.valueOf(row.get("original_name")), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                            .contentType(parseMediaType(remoteType)).contentLength(remote.size())
                            .body(new InputStreamResource(remote.stream()));
                }
            }
            catch (IOException ignored)
            {
                // Fall through to the local recovery copy below.
            }
        }
        if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new IllegalArgumentException("file content is missing");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(String.valueOf(row.get("original_name")), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .contentType(parseMediaType(contentType)).contentLength(Files.size(file))
                .body(new InputStreamResource(Files.newInputStream(file)));
    }

    /** Read an authorized immutable source object for an internal export. */
    public byte[] readBytes(Long fieldId, Long fileId) throws IOException
    {
        ResponseEntity<InputStreamResource> response = download(fieldId, fileId);
        InputStreamResource resource = response.getBody();
        if (resource == null) throw new IOException("file content is missing");
        try (InputStream input = resource.getInputStream())
        {
            return input.readAllBytes();
        }
    }

    @DeleteMapping("/{fileId}")
    @RequiresPermissions("drilling:file:remove")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void archive(@PathVariable Long fieldId, @PathVariable Long fileId)
    {
        requireFieldRole(fieldId, "OWNER", "ENGINEER");
        Map<String, Object> row = jdbc.query("SELECT object_key FROM data_file WHERE file_id=? AND field_id=? AND status='ACTIVE'",
                rs -> rs.next() ? Map.of("objectKey", rs.getString(1)) : null, fileId, fieldId);
        if (row == null) return;
        Integer sourceReferences = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM drilling_source_document WHERE file_id=?)+(SELECT COUNT(*) FROM drilling_survey_version WHERE source_file_id=?)", Integer.class, fileId, fileId);
        if (sourceReferences != null && sourceReferences > 0) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "来源原件或历史测斜版本仍在引用该文件，不能删除；请在工程资料目录管理关联和版本");
        if (objectStorage.enabled())
        {
            try { objectStorage.remove(String.valueOf(row.get("objectKey"))); }
            catch (IOException ex) { throw new IllegalStateException("unable to archive MinIO object", ex); }
        }
        jdbc.update("UPDATE data_file SET status='DELETED' WHERE file_id=? AND field_id=? AND status='ACTIVE'", fileId, fieldId);
        Path local = Path.of(RuoYiConfig.getProfile()).resolve(String.valueOf(row.get("objectKey"))).toAbsolutePath().normalize();
        Path root = Path.of(RuoYiConfig.getProfile(), "drilling-files", String.valueOf(fieldId)).toAbsolutePath().normalize();
        if (local.startsWith(root))
        {
            try { Files.deleteIfExists(local); } catch (IOException ignored) { /* metadata is already archived */ }
        }
    }

    private void requireFieldAccess(Long fieldId)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireAccess(fieldId);
    }

    private void requireFieldRole(Long fieldId, String... roles)
    {
        com.ruoyi.framework.security.DrillingDataScope.current().requireRole(fieldId, roles);
    }

    static String safeName(String input)
    {
        if (input == null) throw new IllegalArgumentException("原始文件名不能为空");
        String base = input.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        if (base.isBlank() || base.equals(".") || base.equals("..") || base.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("原始文件名无效");
        if (base.length() > 255) throw new IllegalArgumentException("原始文件名不能超过255个字符");
        return base;
    }

    static String validateDisplayName(String name) {
        if (name == null || name.isBlank()) return null;
        String value = name.strip();
        if (value.length() > 255 || value.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("系统名称不能超过255个字符，且不能包含控制字符");
        return value;
    }

    @PutMapping("/{fileId}/display-name")
    @RequiresPermissions("drilling:file:upload")
    @Transactional
    public Map<String,Object> renameDisplayName(@PathVariable Long fieldId, @PathVariable Long fileId,
                                                @RequestBody Map<String,Object> body) {
        requireFieldRole(fieldId,"OWNER","ENGINEER");
        if (!body.containsKey("expectedDisplayName")) throw new IllegalArgumentException("请先刷新文件名称");
        var rows=jdbc.queryForList("SELECT file_id,original_name,display_name FROM data_file WHERE file_id=? AND field_id=? AND status='ACTIVE' FOR UPDATE",fileId,fieldId);
        if(rows.isEmpty())throw new IllegalArgumentException("文件不属于当前井场或已归档");
        var row=rows.get(0);
        if(!java.util.Objects.toString(row.get("display_name"),"").equals(java.util.Objects.toString(body.get("expectedDisplayName"),"")))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"系统名称已更新，请刷新后重试");
        String displayName=validateDisplayName(java.util.Objects.toString(body.get("displayName"),""));
        jdbc.update("UPDATE data_file SET display_name=? WHERE file_id=?",displayName,fileId);
        jdbc.update("INSERT INTO drilling_audit_log(field_id,user_id,action,resource_type,resource_id,detail_json) VALUES(?,?,'RENAME_FILE_DISPLAY','FILE',?,?)",fieldId,ShiroUtils.getUserId(),fileId,com.alibaba.fastjson.JSON.toJSONString(Map.of("originalName",row.get("original_name"),"previousDisplayName",java.util.Objects.toString(row.get("display_name"),""),"displayName",java.util.Objects.toString(displayName,""))));
        return jdbc.queryForMap("SELECT file_id,original_name,display_name FROM data_file WHERE file_id=?",fileId);
    }
    private static String extension(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
    private static MediaType parseMediaType(String value) { try { return MediaType.parseMediaType(value); } catch (IllegalArgumentException e) { return MediaType.APPLICATION_OCTET_STREAM; } }
}
