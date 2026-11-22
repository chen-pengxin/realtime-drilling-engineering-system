package com.ruoyi.web.controller.drilling;

import com.alibaba.fastjson.JSON;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingDataScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import org.apache.shiro.authz.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/drilling/fields/{fieldId}/registry")
@RequiresAuthentication
public class SourceRegistryController {
  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  public org.springframework.http.ResponseEntity<Map<String, Object>> conflict(
      org.springframework.web.server.ResponseStatusException error) {
    return org.springframework.http.ResponseEntity.status(error.getStatusCode())
        .body(
            Map.of(
                "code",
                error.getStatusCode().value(),
                "msg",
                Objects.requireNonNullElse(error.getReason(), "请求被拒绝")));
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public org.springframework.http.ResponseEntity<Map<String,Object>> storageError(org.springframework.dao.DataAccessException error){
    org.slf4j.LoggerFactory.getLogger(getClass()).error("工程资料数据库操作失败",error);
    return org.springframework.http.ResponseEntity.status(503).body(Map.of("code",503,"msg","工程资料读取或保存失败，请刷新后重试"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public org.springframework.http.ResponseEntity<Map<String, Object>> invalid(
      IllegalArgumentException error) {
    return org.springframework.http.ResponseEntity.unprocessableEntity()
        .body(Map.of("code", 422, "msg", Objects.requireNonNullElse(error.getMessage(), "版本参数无效")));
  }

  private final JdbcTemplate db;
  private final NativeEngineSupport engine;
  private final DrillingFileController files;
  private final TrajectoryVersionSupport trajectories;
  private final TrajectoryInputSupport trajectoryInputs;
  private final EngineeringLibraryService engineeringLibrary;
  private final SourceContentService sourceContents;
  private final EngineeringTableService engineeringTables;
  private final org.springframework.transaction.support.TransactionTemplate uploadTransactions;

  public SourceRegistryController(
      SourceRegistrySupport support,
      NativeEngineSupport engine,
      DrillingFileController files,
      TrajectoryVersionSupport trajectories,
      TrajectoryInputSupport trajectoryInputs,
      EngineeringLibraryService engineeringLibrary,
      SourceContentService sourceContents,
      org.springframework.transaction.PlatformTransactionManager transactionManager) {
    this.db = support.jdbc;
    this.engine = engine;
    this.files = files;
    this.trajectories = trajectories;
    this.trajectoryInputs = trajectoryInputs;
    this.engineeringLibrary = engineeringLibrary;
    this.sourceContents = sourceContents;
    this.engineeringTables=new EngineeringTableService(support);
    this.uploadTransactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    this.uploadTransactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.uploadTransactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  private void read(Long p) {
    DrillingDataScope.current().requireAccess(p);
  }

  private void write(Long p) {
    DrillingDataScope.current().requireRole(p, "OWNER", "ENGINEER");
  }

  private void writeBore(Long fieldId, Long wellbore) {
    write(fieldId);
    var b = bore(fieldId, wellbore);
    Long owner =
        db.queryForObject(
            "SELECT field_id FROM drilling_well WHERE well_id=?",
            Long.class,
            id(b.get("well_id")));
    // Referencing a neighbor grants access to its snapshots, not the right to
    // change the asset's shared current head from an unrelated fieldId.
    DrillingDataScope.current().requireRole(owner, "OWNER", "ENGINEER");
  }

  private Map<String, Object> bore(Long p, Long b) {
    read(p);
    var rows =
        db.queryForList(
            "SELECT b.* FROM drilling_wellbore b JOIN drilling_field_wellbore x ON"
                + " x.wellbore_id=b.wellbore_id JOIN drilling_well w ON w.well_id=b.well_id"
                + " WHERE x.field_id=? AND b.wellbore_id=? AND NOT EXISTS(SELECT 1"
                + " FROM drilling_engineering_node en WHERE en.field_id=w.field_id"
                + " AND en.well_id=w.well_id AND en.node_type='WELL'"
                + " AND en.entity_kind='PLANNED_RELIEF' AND en.status='ACTIVE')",
            p,
            b);
    if (rows.isEmpty()) throw new IllegalArgumentException("井眼不属于当前井场");
    return rows.get(0);
  }

  static Map<String, Object> obj(Object x) {
    if (x == null) return new LinkedHashMap<>();
    if (x instanceof Map<?, ?> m) {
      Map<String, Object> r = new LinkedHashMap<>();
      m.forEach((k, v) -> r.put(k.toString(), v));
      return r;
    }
    return JSON.parseObject(x.toString(), Map.class);
  }

  static Long id(Object x) {
    return x == null ? null : Long.valueOf(x.toString());
  }

  static String text(Map<String, Object> p, String k, String def) {
    return Objects.toString(p.get(k), def);
  }

  static String required(Map<String, Object> p, String k, int max) {
    String s = text(p, k, "").trim();
    if (s.isEmpty() || s.length() > max) throw new IllegalArgumentException(k + "为空或过长");
    return s;
  }

  /**
   * Explain the evidence gate instead of collapsing every import diagnostic
   * into "坐标未确认".  A source may contain X/Y values while its projection
   * reference or vertical datum is still absent.
   */
  static String referenceStatus(Map<String, Object> ref, List<Map<String, Object>> stations) {
    boolean coordinate = ref.get("origin_north") != null && ref.get("origin_east") != null;
    boolean crs = ref.get("crs") != null && !Objects.toString(ref.get("crs"), "").isBlank();
    boolean confirmedLocal = !Objects.toString(ref.get("local_frame_id"), "").isBlank()
        && "LOCAL_ENU".equals(ref.get("coordinate_system"))
        && Set.of("READY","CONFIRMED").contains(Objects.toString(ref.get("reference_status"), ""));
    boolean vertical = ref.get("depth_datum_elevation_m") != null
        && !Objects.toString(ref.get("depth_datum"), "").isBlank();
    boolean validSurvey = stations != null && stations.size() >= 2;
    double previousMd = -1d;
    if (validSurvey) {
      for (var station : stations) {
        try {
          double md = ((Number) station.get("md")).doubleValue();
          double inc = ((Number) station.get("inclination")).doubleValue();
          double azi = ((Number) station.get("azimuth")).doubleValue();
          double north = ((Number) station.get("north")).doubleValue();
          double east = ((Number) station.get("east")).doubleValue();
          double tvd = ((Number) station.get("tvd")).doubleValue();
          if (!Double.isFinite(md) || md < 0 || md <= previousMd
              || !Double.isFinite(inc) || inc < 0 || inc > 180
              || !Double.isFinite(azi) || !Double.isFinite(north)
              || !Double.isFinite(east) || !Double.isFinite(tvd)) {
            validSurvey = false;
            break;
          }
          previousMd = md;
        } catch (RuntimeException invalid) {
          validSurvey = false;
          break;
        }
      }
    }
    if (!coordinate) return "COORDINATE_MISSING";
    if (!crs && !confirmedLocal) return ref.get("coordinate_datum_name") != null
        ? "DATUM_NAMED_PROJECTION_REQUIRED" : "COORDINATE_REFERENCE_UNCONFIRMED";
    if (!vertical) return "VERTICAL_DATUM_UNCONFIRMED";
    if (!validSurvey) return "SURVEY_HISTORY_INVALID";
    // A survey may begin at any positive measured depth.  The first real
    // station is the local tie-on for propagated uncertainty; no synthetic
    // MD=0 station is invented or required for evaluation.
    return "READY";
  }

  static String referenceMessage(Map<String, Object> ref, String status) {
    return switch (status) {
      case "COORDINATE_MISSING" -> "来源文件未读取井口投影坐标 X/Y，不能进行跨井安全评价";
      case "DATUM_NAMED_PROJECTION_REQUIRED" -> "已读取坐标基准名称（" + ref.get("coordinate_datum_name")
          + "），但尚未确认投影分带/中央经线，不能进行跨井安全评价";
      case "COORDINATE_REFERENCE_UNCONFIRMED" -> "已读取井口投影坐标 X/Y，但来源文件未明确完整坐标参考系";
      case "VERTICAL_DATUM_UNCONFIRMED" -> "已读取平面坐标，但深度基准名称或基准高程尚未确认";
      case "SURVEY_HISTORY_INVALID" -> "测斜版本至少需要两个有效且井深递增的真实测点";
      default -> "当前版本可用于跨井评价";
    };
  }

  private void audit(Long p, String action, Object detail) {
    db.update(
        "INSERT INTO"
            + " drilling_audit_log(field_id,action,resource_type,resource_id,detail_json,user_id)"
            + " VALUES(?,?,'REGISTRY',NULL,?,?)",
        p,
        action,
        JSON.toJSONString(detail),
        ShiroUtils.getUserId());
  }

  @GetMapping("/wellbores")
  @RequiresPermissions("drilling:well:list")
  public List<Map<String, Object>> wellbores(@PathVariable Long fieldId) {
    read(fieldId);
    return db.queryForList(
        """
        SELECT b.*,w.well_id,w.well_code,w.well_name,w.wellhead_n,w.wellhead_e,w.wellhead_tvd,x.purpose,
        a.survey_version_id AS actual_version_id,d.survey_version_id AS design_version_id,
        av.quality_status AS actual_quality_status,dv.quality_status AS design_quality_status,
        f.field_id,f.field_name,k.block_id,k.block_name,w.field_id AS owner_field_id,x.field_id AS context_field_id
        FROM drilling_field_wellbore x JOIN drilling_wellbore b ON b.wellbore_id=x.wellbore_id JOIN drilling_well w ON w.well_id=b.well_id
        LEFT JOIN drilling_well_catalog c ON c.well_id=w.well_id LEFT JOIN drilling_field f ON f.field_id=c.field_id LEFT JOIN drilling_block k ON k.block_id=f.block_id
        LEFT JOIN drilling_trajectory_head a ON a.wellbore_id=b.wellbore_id AND a.trajectory_kind='ACTUAL'
        LEFT JOIN drilling_trajectory_head d ON d.wellbore_id=b.wellbore_id AND d.trajectory_kind='DESIGN'
        LEFT JOIN drilling_trajectory_version av ON av.survey_version_id=a.survey_version_id
        LEFT JOIN drilling_trajectory_version dv ON dv.survey_version_id=d.survey_version_id
        WHERE x.field_id=? AND w.status<>'ARCHIVED' AND LEFT(w.well_code,17)<>'__ARCHIVED_ALIAS_' AND b.engineering_status<>'ABANDONED'
          AND NOT EXISTS(SELECT 1 FROM drilling_engineering_node en WHERE en.field_id=w.field_id
            AND en.well_id=w.well_id AND en.node_type='WELL' AND en.entity_kind='PLANNED_RELIEF' AND en.status='ACTIVE')
        ORDER BY w.well_code,b.wellbore_code
        """,
        fieldId);
  }

  @PostMapping("/wellbores")
  @RequiresPermissions("drilling:well:add")
  @Transactional
  public Map<String, Object> createBore(
      @PathVariable Long fieldId, @RequestBody Map<String, Object> body) {
    write(fieldId);
    String code = required(body, "wellCode", 64), bcode = required(body, "wellboreCode", 128);
    var p =
        db.queryForMap(
            "SELECT field_id FROM drilling_field WHERE field_id=? FOR UPDATE", fieldId);
    Long field = id(p.get("field_id"));
    Long well =
        db.query(
            "SELECT well_id FROM drilling_well_catalog WHERE field_id=? AND identity_key=?",
            rs -> rs.next() ? rs.getLong(1) : null,
            field,
            code);
    if (well == null) {
      db.update(
          "INSERT INTO drilling_well(field_id,well_code,well_name,status,create_by)"
              + " VALUES(?,?,?,'PLANNED',?)",
          fieldId,
          code,
          text(body, "wellName", code),
          ShiroUtils.getLoginName());
      well =
          db.queryForObject(
              "SELECT well_id FROM drilling_well WHERE field_id=? AND well_code=?",
              Long.class,
              fieldId,
              code);
      db.update(
          "INSERT INTO drilling_well_catalog(well_id,field_id,identity_key) VALUES(?,?,?)",
          well,
          field,
          code);
    } else if (!ShiroUtils.isAdmin(ShiroUtils.getUserId())) {
      Long owner =
          db.queryForObject(
              "SELECT field_id FROM drilling_well WHERE well_id=?", Long.class, well);
      DrillingDataScope.current().requireRole(owner, "OWNER", "ENGINEER");
    }
    Long parent = id(body.get("parentWellboreId"));
    if (parent != null && !Objects.equals(id(bore(fieldId, parent).get("well_id")), well))
      throw new IllegalArgumentException("侧钻井眼的父井眼必须属于同一物理井");
    db.update(
        "INSERT INTO"
            + " drilling_wellbore(well_id,parent_wellbore_id,wellbore_code,wellbore_name,reference_json)"
            + " VALUES(?,?,?,?,?)",
        well,
        parent,
        bcode,
        text(body, "wellboreName", bcode),
        JSON.toJSONString(obj(body.get("reference"))));
    Long bid =
        db.queryForObject(
            "SELECT wellbore_id FROM drilling_wellbore WHERE well_id=? AND wellbore_code=?",
            Long.class,
            well,
            bcode);
    db.update(
        "INSERT INTO drilling_field_wellbore(field_id,wellbore_id) VALUES(?,?)",
        fieldId,
        bid);
    audit(fieldId, "CREATE_WELLBORE", Map.of("wellboreId", bid));
    return bore(fieldId, bid);
  }

  @PostMapping("/wellbores/{wellboreId}/link")
  @RequiresPermissions("drilling:well:add")
  public void link(@PathVariable Long fieldId, @PathVariable Long wellboreId) {
    write(fieldId);
    var row =
        db.queryForMap(
            "SELECT w.field_id FROM drilling_wellbore b JOIN drilling_well w ON"
                + " w.well_id=b.well_id WHERE b.wellbore_id=?",
            wellboreId);
    read(id(row.get("field_id")));
    db.update(
        "INSERT IGNORE INTO drilling_field_wellbore(field_id,wellbore_id,purpose)"
            + " VALUES(?,?,'NEIGHBOR')",
        fieldId,
        wellboreId);
    engineeringLibrary.registerWellboreReference(fieldId,wellboreId);
    audit(fieldId, "LINK_EXISTING_WELLBORE", Map.of("wellboreId", wellboreId));
  }

  @GetMapping("/available-wellbores")
  @RequiresPermissions("drilling:well:list")
  public List<Map<String, Object>> available(@PathVariable Long fieldId) {
    read(fieldId);
    return db.queryForList(
        "SELECT b.wellbore_id,b.wellbore_code,w.well_code,w.well_name FROM drilling_wellbore b JOIN"
            + " drilling_well w ON w.well_id=b.well_id WHERE w.status<>'ARCHIVED' AND"
            + " NOT EXISTS(SELECT 1 FROM drilling_engineering_node en WHERE en.field_id=w.field_id"
            + " AND en.well_id=w.well_id AND en.node_type='WELL' AND en.entity_kind='PLANNED_RELIEF' AND en.status='ACTIVE') AND ("
            + DrillingDataScope.current().predicate("w.field_id")
            + ") AND NOT EXISTS(SELECT 1 FROM drilling_field_wellbore x WHERE x.field_id=? AND"
            + " x.wellbore_id=b.wellbore_id) ORDER BY w.well_code",
        fieldId);
  }

  @PutMapping("/wellbores/{wellboreId}")
  @RequiresPermissions("drilling:well:edit")
  public void updateBore(
      @PathVariable Long fieldId,
      @PathVariable Long wellboreId,
      @RequestBody Map<String, Object> body) {
    writeBore(fieldId,wellboreId);
    var current=bore(fieldId, wellboreId);
    String status = required(body, "engineeringStatus", 32);
    EngineeringUsageGuard.requireStatusChange(db,wellboreId,Objects.toString(current.get("engineering_status"),"UNKNOWN"),status);
    if (!Set.of(
            "UNPLANNED", "PLANNED", "DRILLING", "SUSPENDED", "COMPLETED", "ABANDONED", "UNKNOWN")
        .contains(status)) throw new IllegalArgumentException("无效施工状态");
    db.update(
        "UPDATE drilling_wellbore SET"
            + " engineering_status=?,reference_json=COALESCE(?,reference_json),update_time=NOW()"
            + " WHERE wellbore_id=?",
        status,
        body.containsKey("reference") ? JSON.toJSONString(body.get("reference")) : null,
        wellboreId);
    audit(fieldId, "UPDATE_WELLBORE", body);
  }

  @GetMapping("/wellbores/{wellboreId}/versions")
  @RequiresPermissions("drilling:well:list")
  public List<Map<String, Object>> versions(
      @PathVariable Long fieldId,
      @PathVariable Long wellboreId,
      @RequestParam(defaultValue = "") String kind) {
    bore(fieldId, wellboreId);
    if (!kind.isEmpty() && !Set.of("ACTUAL", "DESIGN").contains(kind))
      throw new IllegalArgumentException("轨道类型无效");
    return db.queryForList(
        """
        SELECT t.*,t.trajectory_version_no AS version_no,s.max_md,s.parent_version_id,s.source_file_id,s.import_mode,s.config_version,
          s.create_by,s.create_time AS imported_at,COALESCE(h.survey_version_id=t.survey_version_id,0) AS is_active,
          f.original_name,f.display_name,p.trajectory_version_no AS parent_version_no,COALESCE(r.revision_type,s.import_mode) AS revision_type,
          r.changed_from_md,r.changed_to_md,r.summary_json,COALESCE(sr.status,'PENDING') AS source_review_status
        FROM drilling_trajectory_version t JOIN drilling_survey_version s ON s.survey_version_id=t.survey_version_id
        LEFT JOIN drilling_trajectory_head h ON h.wellbore_id=t.wellbore_id AND h.trajectory_kind=t.trajectory_kind
        LEFT JOIN drilling_trajectory_version p ON p.survey_version_id=s.parent_version_id
        LEFT JOIN drilling_trajectory_revision r ON r.survey_version_id=t.survey_version_id
        LEFT JOIN data_file f ON f.file_id=s.source_file_id
        LEFT JOIN drilling_source_review sr ON sr.source_id=(SELECT sd.source_id FROM drilling_source_document sd WHERE sd.file_id=s.source_file_id ORDER BY sd.source_id DESC LIMIT 1)
        WHERE t.wellbore_id=? AND (?='' OR t.trajectory_kind=?) ORDER BY t.trajectory_version_no DESC
        """,
        wellboreId,
        kind,
        kind);
  }

  @GetMapping("/versions/{versionId}")
  @RequiresPermissions("drilling:well:list")
  public Map<String, Object> version(@PathVariable Long fieldId, @PathVariable Long versionId,
      @RequestParam(required=false) Long viewReferenceId,
      @RequestParam(required=false) Double viewStep) {
    var rows =
        db.queryForList(
            """
            SELECT t.*,t.trajectory_version_no AS version_no,s.well_id,s.source_file_id,s.parent_version_id,s.import_mode,s.max_md,
              s.config_snapshot_json,s.create_by,COALESCE(r.revision_type,s.import_mode) AS revision_type,
              r.changed_from_md,r.changed_to_md,r.summary_json,COALESCE(h.survey_version_id=t.survey_version_id,0) AS is_active,
              COALESCE(sr.status,'PENDING') AS source_review_status
            FROM drilling_trajectory_version t JOIN drilling_survey_version s ON s.survey_version_id=t.survey_version_id
            LEFT JOIN drilling_trajectory_revision r ON r.survey_version_id=t.survey_version_id
            LEFT JOIN drilling_trajectory_head h ON h.wellbore_id=t.wellbore_id AND h.trajectory_kind=t.trajectory_kind
            LEFT JOIN drilling_source_review sr ON sr.source_id=(SELECT sd.source_id FROM drilling_source_document sd WHERE sd.file_id=s.source_file_id ORDER BY sd.source_id DESC LIMIT 1)
            WHERE t.survey_version_id=?
            """,
            versionId);
    if (rows.isEmpty()) throw new IllegalArgumentException("轨迹版本不存在");
    var r = rows.get(0);
    bore(fieldId, id(r.get("wellbore_id")));
    r.put(
        "stations",
        db.queryForList(
            "SELECT md,inclination,azimuth,north,east,tvd,dogleg,curvature,raw_json FROM"
                + " drilling_survey_station WHERE survey_version_id=? ORDER BY md",
            versionId));
    @SuppressWarnings("unchecked")
    var stationRows=(List<Map<String,Object>>)r.get("stations");
    for(var station:stationRows){
      Double n=numberOrNull(station.get("north")),e=numberOrNull(station.get("east"));
      Object raw=station.get("raw_json");if(raw!=null){var extra=obj(raw);for(String key:List.of("remark","closure_distance","closure_azimuth","horizontal_length","apparent_horizontal_length","is_key_node"))if(extra.get(key)!=null)station.put(key,extra.get(key));}
    }
    var reference = obj(r.get("reference_json"));
    @SuppressWarnings("unchecked")
    var stations = (List<Map<String, Object>>) r.get("stations");
    // Recalculate the derived eligibility from the immutable evidence so
    // legacy MD0_HISTORY_REQUIRED records are corrected on read as well.
    String referenceStatus = referenceStatus(reference, stations);
    reference.put("reference_status", referenceStatus);
    reference.put("evaluation_eligibility", "READY".equals(referenceStatus) ? "ELIGIBLE" : "BLOCKED");
    reference.put("evaluation_message", referenceMessage(reference, referenceStatus));
    r.put("reference", reference);
    r.put("effective_uncertainty",SurveyVersionMetadata.uncertainty(reference,DrillingAlgorithmDefaults.merged(engine.config(fieldId).get("algorithm_defaults_json"))));
    r.put("revision_summary", obj(r.get("summary_json")));
    if(viewStep!=null) {
      if(!Double.isFinite(viewStep)||viewStep<1||viewStep>100)throw new IllegalArgumentException("绘图间距必须为1～100 m");
      Map<String,Object> sample=new LinkedHashMap<>();
      if(viewReferenceId!=null&&!viewReferenceId.equals(versionId)) {
        var target=trajectoryInputs.version(fieldId,viewReferenceId);
        sample.putAll(trajectoryInputs.prepare(trajectoryInputs.version(fieldId,versionId),obj(target.get("reference"))));
      } else {
        sample.put("stations",r.get("stations"));sample.put("canonical_coordinates",true);
        var points=(List<Map<String,Object>>)r.get("stations");
        if(!points.isEmpty())for(var axis:List.of("north","east","tvd"))sample.put("origin_"+axis,points.get(0).get(axis));
      }
      if("DESIGN".equals(r.get("trajectory_kind"))){var tableData=engineeringTables.data(fieldId,id(r.get("wellbore_id")),versionId);if(tableData.get("design_nodes") instanceof List<?> nodes)sample.put("key_mds",nodes.stream().map(n->obj(n).get("md")).toList());}
      sample.put("step",viewStep);
      r.put("plot_stations",engine.json("/survey/sample",sample).get("stations"));
    }
    return r;
  }

  private static Double numberOrNull(Object value){try{return value==null?null:Double.valueOf(value.toString());}catch(Exception e){return null;}}

  // Internal callers need the immutable data, without view resampling.
  public Map<String,Object> version(Long fieldId,Long versionId){return version(fieldId,versionId,null,null);}

  @GetMapping("/versions/{versionId}/reduction-export")
  @RequiresPermissions("drilling:well:list")
  public org.springframework.http.ResponseEntity<byte[]> reductionExport(
      @PathVariable Long fieldId, @PathVariable Long versionId) {
    var v = version(fieldId, versionId);
    var payload = new LinkedHashMap<String, Object>();
    payload.put("rows", v.get("stations"));
    var ref = obj(v.get("reference_json"));
    ref.put("north_reference", "GRID_NORTH");
    ref.put("length_unit", "m");
    ref.put("angle_unit", "deg");
    var rows = (List<Map<String, Object>>) v.get("stations");
    if (!rows.isEmpty()) {
      var first = rows.get(0);
      ref.put("origin_north", first.get("north"));
      ref.put("origin_east", first.get("east"));
      ref.put("origin_tvd", first.get("tvd"));
    }
    payload.put("reference", ref);
    payload.put("format", "xlsx");
    payload.put("kind", v.get("trajectory_kind"));
    payload.put("name", v.get("label"));
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=trajectory-reduction.xlsx")
        .contentType(
            org.springframework.http.MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(engine.binary("/survey/reduce", payload));
  }

  @PutMapping("/versions/{versionId}/activate")
  @RequiresPermissions("drilling:well:survey:import")
  @Transactional
  public Map<String, Object> activate(
      @PathVariable Long fieldId,
      @PathVariable Long versionId,
      @RequestBody(required = false) Map<String, Object> body) {
    var v = version(fieldId, versionId);
    writeBore(fieldId, id(v.get("wellbore_id")));
    Long well = id(v.get("well_id")), bid = id(v.get("wellbore_id"));
    String kind = v.get("trajectory_kind").toString();
    trajectories.lockWell(well);
    Long previous = trajectories.head(bid, kind);
    if (body != null
        && body.containsKey("expectedCurrentVersionId")
        && !Objects.equals(previous, id(body.get("expectedCurrentVersionId"))))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "当前版本已变化，请刷新后重新操作");
    trajectories.setHead(bid, kind, versionId, well);
    var detail = new LinkedHashMap<String, Object>();
    detail.put("previousVersionId", previous);
    detail.put("versionId", versionId);
    detail.put("wellboreId", bid);
    detail.put("kind", kind);
    audit(fieldId, "ACTIVATE_TRAJECTORY", detail);
    return version(fieldId, versionId);
  }

  @GetMapping("/sources")
  @RequiresPermissions("drilling:file:list")
  public Map<String, Object> sources(
      @PathVariable Long fieldId,
      @RequestParam(defaultValue = "1") int pageNum,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "false") boolean includeAccessible) {
    read(fieldId);
    pageSize = Math.max(1, Math.min(100, pageSize));
    pageNum = Math.max(1, pageNum);
    String where =
        " FROM drilling_source_document s JOIN data_file f ON f.file_id=s.file_id"
            + " LEFT JOIN drilling_source_review sr ON sr.field_id=s.field_id AND sr.source_id=s.source_id WHERE"
            + (includeAccessible ? " ("+DrillingDataScope.current().predicate("s.field_id")+")" : " s.field_id=?")
            + " AND (f.original_name LIKE ? OR f.display_name LIKE ?) AND (?='' OR s.parse_status=?)";
    var args = new ArrayList<Object>();
    if(!includeAccessible)args.add(fieldId);
    args.add("%" + keyword + "%");args.add("%" + keyword + "%");args.add(status);args.add(status);
    Long total = db.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
    args.add(pageSize);args.add((pageNum-1)*pageSize);
    var rows =
        db.queryForList(
            "SELECT s.source_id,s.field_id,s.file_id,s.source_path,s.source_format,s.parse_status,s.create_time,s.diagnostics_json,JSON_REMOVE(s.metadata_json,'$.engineering_tables') AS metadata_json,f.original_name,f.display_name,f.sha256,f.file_size,COALESCE(sr.status,'PENDING') AS review_status"
                + where
                + " ORDER BY s.source_id DESC LIMIT ? OFFSET ?",
            args.toArray());
    return Map.of("rows", rows, "total", total);
  }

  @PostMapping("/sources")
  @RequiresPermissions("drilling:well:survey:import")
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Map<String, Object> upload(
      @PathVariable Long fieldId, @RequestPart("file") MultipartFile file,
      @RequestParam(required=false) Long nodeId,
      @RequestParam(required=false) Long wellboreId,
      @RequestParam(defaultValue="REFERENCE") String documentRole,
      @RequestParam(defaultValue="") String trajectoryKind,
      @RequestParam(required=false) Long documentId,
      @RequestParam(required=false) Integer expectedEditionNo,
      @RequestParam(required=false) String displayName,
      @RequestParam(defaultValue="false") boolean deferTrajectory) throws Exception {
    var target=engineeringLibrary.uploadTarget(fieldId,nodeId,wellboreId,documentRole,trajectoryKind,documentId,expectedEditionNo);
    if(target!=null)target.put("defer_trajectory",deferTrajectory);
    return importSource(fieldId,file,target,documentRole,trajectoryKind,displayName);
  }

  private Map<String,Object> finishSourceUpload(Long fieldId,Map<String,Object> source,
      Map<String,Object> target,String role,String kind) {
    Long sourceId=id(source.get("source_id"));
    var result=new LinkedHashMap<String,Object>(source);
    if(target!=null){
      var association=new LinkedHashMap<String,Object>();association.put("documentRole",role);association.put("trajectoryKind",kind);
      if(target.get("upload_document_id")!=null){association.put("documentId",target.get("upload_document_id"));association.put("revisionId",target.get("upload_revision_id"));association.put("expectedEditionNo",target.get("upload_expected_edition_no"));}
      var attached=engineeringLibrary.attachFile(id(target.get("node_id")),sourceId,association);
      result.put("document_id",attached.get("documentId"));result.put("edition_no",attached.get("editionNo"));result.put("file_unchanged",attached.get("alreadyLinked"));
      if(Boolean.TRUE.equals(target.get("defer_trajectory"))&&!Boolean.TRUE.equals(attached.get("alreadyLinked")))db.update("UPDATE drilling_engineering_document_file SET usage_state='DRAFT' WHERE document_id=? AND source_id=? AND edition_no=?",attached.get("documentId"),sourceId,attached.get("editionNo"));
      if(!Boolean.TRUE.equals(attached.get("alreadyLinked"))){
        String generated=engineeringSystemName(target,role,kind,((Number)attached.get("editionNo")).intValue());
        if(generated!=null)db.update("UPDATE data_file SET display_name=? WHERE file_id=?",generated,source.get("file_id"));
      }
      if(!Boolean.TRUE.equals(attached.get("alreadyLinked")) && target.get("wellbore_id")!=null && !kind.isBlank() && !Boolean.TRUE.equals(target.get("defer_trajectory"))) {
        var publication=db.execute((org.springframework.jdbc.core.ConnectionCallback<java.sql.Savepoint>) connection->connection.setSavepoint());
        try {
          var version=autoCreateTrajectoryVersion(fieldId,target,sourceId,kind);
          if(version!=null) {
            result.put("trajectory_version_id",version.get("survey_version_id"));
            result.put("trajectory_version_no",version.get("version_no"));
            result.put("trajectory_status","CONFIRMED");
            String generated=engineeringSystemName(target,role,kind,((Number)version.get("version_no")).intValue());
            if(generated!=null) db.update("UPDATE data_file SET display_name=? WHERE file_id=?",generated,source.get("file_id"));
          }
          db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{connection.releaseSavepoint(publication);return null;});
        } catch (RuntimeException autoImportFailure) {
          db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{connection.rollback(publication);connection.releaseSavepoint(publication);return null;});
          // Keep the source and its parsed tables available for the explicit
          // import dialog when a safe trajectory cannot be built from the
          // source metadata alone.
          result.put("trajectory_status","PENDING_CONFIRMATION");
          result.put("trajectory_message",Objects.toString(autoImportFailure.getMessage(),"请在轨迹导入窗口核对来源参数"));
        }
      }
    }
    if(target!=null&&target.get("wellbore_id")!=null)engineeringTables.importTargets(fieldId,id(target.get("wellbore_id")),obj(source.get("metadata_json")));
    var file=db.queryForMap("SELECT original_name,display_name FROM data_file WHERE file_id=?",source.get("file_id"));
    result.put("original_name",file.get("original_name"));result.put("display_name",file.get("display_name"));
    result.put("associations",engineeringLibrary.sourceAssociations(sourceId));
    result.put("review_status",db.queryForObject("SELECT status FROM drilling_source_review WHERE field_id=? AND source_id=?",String.class,fieldId,sourceId));
    if(target!=null)result.put("upload_node_id",target.get("node_id"));
    return result;
  }

  /** Build and publish a trajectory immediately when a source upload contains
   * a complete, confirmed trajectory table.  Explicit imports remain the
   * fallback for documents with incomplete source metadata. */
  @SuppressWarnings("unchecked")
  private Map<String,Object> autoCreateTrajectoryVersion(Long fieldId,Map<String,Object> target,Long sourceId,String kind) {
    var entries=db.queryForList("SELECT extraction_id,payload_json FROM drilling_source_extraction WHERE source_id=? ORDER BY extraction_id",sourceId);
    Map<String,Object> chosen=null; Long extractionId=null;
    for(var entry:entries){var payload=obj(entry.get("payload_json"));if(payload.get("rows") instanceof List<?> rows && rows.size()>=2 && (chosen==null||rows.size()>((List<?>)chosen.get("rows")).size())){chosen=payload;extractionId=id(entry.get("extraction_id"));}}
    if(chosen==null||extractionId==null)return null;
    var reference=obj(chosen.get("reference"));
    var review=sourceReview(fieldId,sourceId);var confirmed=obj(review.get("confirmed"));reference.putAll(obj(confirmed.get("reference")));
    var tool=obj(reference.get("survey_tool"));if(Objects.toString(tool.get("name"),"").isBlank())throw new IllegalArgumentException("来源文件未注明测斜工具");
    var uncertainty=obj(reference.get("uncertainty"));if(uncertainty.get("model_version")==null||uncertainty.get("settings")==null){var defaults=DrillingAlgorithmDefaults.merged(engine.config(fieldId).get("algorithm_defaults_json"));uncertainty.put("model_version",obj(defaults.get("errorModel")).get("modelVersion"));uncertainty.put("settings",obj(defaults.get("errorModel")).get("settings"));}
    uncertainty.put("confirmed",true);reference.put("uncertainty",uncertainty);
    var body=new LinkedHashMap<String,Object>();body.put("wellboreId",target.get("wellbore_id"));body.put("kind",kind);Long parent=db.queryForObject("SELECT MAX(survey_version_id) FROM drilling_trajectory_head WHERE wellbore_id=? AND trajectory_kind=?",Long.class,target.get("wellbore_id"),kind);
    String mode="FULL";if(parent!=null&&"ACTUAL".equals(kind)){
      double max=db.queryForObject("SELECT max_md FROM drilling_survey_version WHERE survey_version_id=?",Double.class,parent);
      double first=((Number)obj(((List<?>)chosen.get("rows")).get(0)).get("md")).doubleValue();mode=first>=max-0.000001?"INCREMENTAL":"RESURVEY";
    }
    body.put("parentVersionId",parent);body.put("expectedCurrentVersionId",parent);body.put("importMode",mode);body.put("reference",reference);body.put("policy",Map.of("duplicate_tolerance",0.000001,"max_dls_deg_per_30m",30,"remove_anomalies",false));body.put("activate",true);body.put("label","");body.put("note","文件导入自动生成轨迹版本");body.put("documentRole","TRAJECTORY_DATA");
    return commit(fieldId,extractionId,body);
  }

  private String engineeringSystemName(Map<String,Object> target,String role,String kind,int version){
    Object well=target.get("well_id"); if(well==null)return null;
    String wellName=db.queryForObject("SELECT well_name FROM drilling_well WHERE well_id=?",String.class,well);
    String type="DESIGN".equals(kind)?"设计方案":"ACTUAL".equals(kind)?"实测测斜":switch(role){case "ENGINEERING_REPORT"->"工程报告";case "WELL_SUMMARY"->"井总结";default->"工程资料";};
    return (wellName==null||wellName.isBlank()?"井":wellName)+"_"+type+"_"+version;
  }

  private Map<String,Object> importSource(Long fieldId,MultipartFile file,
      Map<String,Object> target,String documentRole,String trajectoryKind,String displayName) throws Exception {
    write(fieldId);
    if (file.getSize() > 256L * 1024 * 1024) throw new IllegalArgumentException("单文件上限256MiB");
    String name = DrillingFileController.safeName(file.getOriginalFilename());
    // System names are generated from the confirmed well, trajectory kind and
    // edition.  The original filename remains the source filename.
    String alias = null;
    String hash =
        HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(file.getBytes()));
    engineeringLibrary.validateFileEdition(target,hash);
    // A repeated file edition does not create another original merely because
    // the local upload filename changed. The retained edition keeps its name.
    if(target!=null&&target.get("upload_document_id")!=null){
      var repeated=db.queryForList("SELECT sd.source_id FROM drilling_engineering_document_file df JOIN drilling_source_document sd ON sd.source_id=df.source_id JOIN data_file f ON f.file_id=sd.file_id WHERE df.document_id=? AND f.sha256=? ORDER BY df.edition_no DESC LIMIT 1",target.get("upload_document_id"),hash);
      if(!repeated.isEmpty()){
        Long sourceId=id(repeated.get(0).get("source_id"));
        if(!sourceContents.exists(sourceId))saveInspectedContent(sourceId,engine.inspect(name,file.getBytes()));
        return finishSourceUpload(fieldId,db.queryForMap("SELECT * FROM drilling_source_document WHERE source_id=?",sourceId),target,documentRole,trajectoryKind);
      }
    }
    if(target!=null&&target.get("wellbore_id")!=null){
      var sameTrajectory=db.queryForList("SELECT sd.source_id FROM drilling_engineering_document_file df JOIN drilling_engineering_document d ON d.document_id=df.document_id JOIN drilling_engineering_node n ON n.node_id=d.node_id JOIN drilling_source_document sd ON sd.source_id=df.source_id JOIN data_file f ON f.file_id=sd.file_id WHERE sd.field_id=? AND n.wellbore_id=? AND f.sha256=? AND d.document_role=? AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(df.properties_json,'$.trajectory_kind')),CASE n.node_type WHEN 'PLAN' THEN 'DESIGN' WHEN 'SURVEY' THEN 'ACTUAL' END)=? ORDER BY df.edition_no DESC LIMIT 1",fieldId,target.get("wellbore_id"),hash,documentRole,trajectoryKind);
      if(!sameTrajectory.isEmpty())throw new IllegalArgumentException("同一井眼、同一用途已有相同内容，未重复导入");
    }
    var existing =
        db.queryForList(
            "SELECT s.source_id,s.file_id,s.field_id,s.source_path,s.source_key,s.source_format,s.parse_status,s.metadata_json,s.diagnostics_json,s.create_time,s.create_time AS source_created_at,f.original_name,f.display_name,f.sha256,f.file_size"
                + " FROM drilling_source_document s JOIN data_file f ON f.file_id=s.file_id"
                + " WHERE s.field_id=? AND f.sha256=? AND BINARY f.original_name=BINARY ? LIMIT 1",
            fieldId,
            hash,name);
    if (!existing.isEmpty()) {
      Long sourceId=id(existing.get(0).get("source_id"));
      if(!sourceContents.exists(sourceId))saveInspectedContent(sourceId,engine.inspect(name,file.getBytes()));
      return finishSourceUpload(fieldId,existing.get(0),target,documentRole,trajectoryKind);
    }
    var parsed = engine.inspect(name, file.getBytes());
    var stored = files.upload(fieldId, file, "SURVEY", "SOURCE_DOCUMENT", null, false);
    Long fid = id(stored.containsKey("file_id") ? stored.get("file_id") : stored.get("fileId"));
    if(alias!=null)db.update("UPDATE data_file SET display_name=? WHERE file_id=?",alias,fid);
    String sourceKey=NativeEngineSupport.hash(name+":"+hash);
    var sourceMetadata = obj(parsed.get("metadata"));
    if (parsed.get("engineering") != null) sourceMetadata.put("engineering", parsed.get("engineering"));
    // Keep a bounded text excerpt with source metadata so PDF/Word/TXT files
    // can be reviewed without modifying or replacing the original binary.
    Object excerpt = parsed.get("text_excerpt");
    if (excerpt != null) {
      String textExcerpt = Objects.toString(excerpt, "");
      sourceMetadata.put("text_excerpt", textExcerpt.substring(0, Math.min(textExcerpt.length(), 250000)));
    }
    db.update(
        "INSERT INTO"
            + " drilling_source_document(file_id,field_id,source_path,source_key,source_format,parse_status,metadata_json,diagnostics_json)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        fid,
        fieldId,
        name,
        sourceKey,
        parsed.get("format"),
        parsed.get("status"),
        JSON.toJSONString(sourceMetadata),
        JSON.toJSONString(parsed.get("diagnostics")));
    Long source =
        db.queryForObject(
            "SELECT source_id FROM drilling_source_document WHERE field_id=? AND source_key=?",
            Long.class,
            fieldId,
            sourceKey);
    db.update(
        "INSERT IGNORE INTO drilling_source_review(field_id,source_id,status,proposed_json,confirmed_json,reviewed_by,reviewed_at) VALUES(?,?, 'CONFIRMED',?,?,?,NOW())",
        fieldId, source, JSON.toJSONString(sourceMetadata), JSON.toJSONString(sourceMetadata), ShiroUtils.getLoginName());
    saveInspectedContent(source,parsed);
    audit(
        fieldId,
        "IMPORT_SOURCE",
        Map.of("sourceId", source, "sha256", Objects.toString(parsed.get("sha256"))));
    return finishSourceUpload(fieldId,db.queryForMap("SELECT * FROM drilling_source_document WHERE source_id=?", source),target,documentRole,trajectoryKind);
  }

  private void saveInspectedContent(Long sourceId,Map<String,Object> parsed){
    sourceContents.save(sourceId,null,parsed);
    if(parsed.get("trajectories") instanceof List<?> tables)for(Object value:tables){
      var table=obj(value);
      db.update("INSERT IGNORE INTO drilling_source_extraction(source_id,locator,payload_json) VALUES(?,?,?)",sourceId,table.get("locator"),JSON.toJSONString(table));
    }
  }

  /** Upload a bounded batch while retaining one source record per original file. */
  @PostMapping(value = "/sources/batch", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  @RequiresPermissions("drilling:well:survey:import")
  public Map<String,Object> uploadBatch(@PathVariable Long fieldId, @RequestPart("files") MultipartFile[] files,
      @RequestParam(required=false) Long nodeId,@RequestParam(required=false) Long wellboreId,
      @RequestParam(defaultValue="REFERENCE") String documentRole,
      @RequestParam(defaultValue="") String trajectoryKind,
      @RequestParam(required=false) Long documentId,
      @RequestParam(required=false) Integer expectedEditionNo) throws Exception {
    write(fieldId);
    // Reject invalid ownership before any file in the batch can be stored.
    engineeringLibrary.uploadTarget(fieldId,nodeId,wellboreId,documentRole,trajectoryKind,documentId,expectedEditionNo);
    if (files == null || files.length == 0 || files.length > 200) throw new IllegalArgumentException("批量文件数量必须为1～200");
    if(documentId!=null&&files.length!=1)throw new IllegalArgumentException("更新已有文件时一次只能上传一个文件");
    var summary=new LinkedHashMap<String,Object>(); var rows=new ArrayList<Map<String,Object>>(); int ok=0,failed=0;
    for (MultipartFile file:files) {
      try { rows.add(uploadTransactions.execute(status->{
        try {
          var target=engineeringLibrary.uploadTarget(fieldId,nodeId,wellboreId,documentRole,trajectoryKind,documentId,expectedEditionNo);
          return importSource(fieldId,file,target,documentRole,trajectoryKind,null);
        } catch(Exception ex){throw new IllegalArgumentException(Objects.toString(ex.getMessage(),"文件导入失败"),ex);}
      })); ok++; }
      catch (Exception ex) { failed++; rows.add(Map.of("name",Objects.toString(file.getOriginalFilename(),"source"),"status","FAILED","message",Objects.toString(ex.getMessage(),ex.getClass().getSimpleName()))); }
    }
    summary.put("total",files.length); summary.put("succeeded",ok); summary.put("failed",failed); summary.put("rows",rows);
    audit(fieldId,"IMPORT_SOURCE_BATCH",summary); return summary;
  }

  @GetMapping("/sources/{sourceId}/extractions")
  @RequiresPermissions("drilling:well:list")
  public List<Map<String, Object>> extractions(
      @PathVariable Long fieldId, @PathVariable Long sourceId) {
    read(fieldId);
    return db.queryForList(
        "SELECT e.* FROM drilling_source_extraction e JOIN drilling_source_document s ON"
            + " s.source_id=e.source_id WHERE s.field_id=? AND e.source_id=? ORDER BY"
            + " extraction_id",
        fieldId,
        sourceId);
  }

  @GetMapping("/sources/{sourceId}/original")
  @RequiresPermissions("drilling:file:list")
  public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> original(
      @PathVariable Long fieldId, @PathVariable Long sourceId) throws java.io.IOException {
    read(fieldId);
    var rows=db.queryForList("SELECT file_id FROM drilling_source_document WHERE source_id=? AND field_id=?",sourceId,fieldId);
    if(rows.isEmpty()) throw new IllegalArgumentException("来源文件不存在");
    return files.download(fieldId,id(rows.get(0).get("file_id")));
  }

  @PostMapping("/sources/{sourceId}/mapping")
  @RequiresPermissions("drilling:well:survey:import")
  @Transactional
  public Map<String,Object> mapSource(@PathVariable Long fieldId,@PathVariable Long sourceId,
      @RequestBody Map<String,Object> selection) throws java.io.IOException {
    write(fieldId);
    var rows=db.queryForList("SELECT s.file_id,f.original_name FROM drilling_source_document s JOIN data_file f ON f.file_id=s.file_id WHERE s.field_id=? AND s.source_id=?",fieldId,sourceId);
    if(rows.isEmpty())throw new IllegalArgumentException("来源资料不存在");
    var source=rows.get(0);
    var request=new LinkedHashMap<String,Object>();
    request.put("original_base64",Base64.getEncoder().encodeToString(files.readBytes(fieldId,id(source.get("file_id")))));
    request.put("original_name",source.get("original_name"));
    if(!selection.isEmpty())request.put("selection",selection);
    var result=engine.json("/source/map",request);
    if(result.containsKey("extraction")) {
      var extraction=obj(result.get("extraction"));
      // Mapping revisions are append-only. Existing version provenance never
      // changes when an engineer later maps the same original differently.
      db.update("INSERT IGNORE INTO drilling_source_extraction(source_id,locator,payload_json) VALUES(?,?,?)",sourceId,extraction.get("locator"),JSON.toJSONString(extraction));
      result.put("extraction_id",db.queryForObject("SELECT extraction_id FROM drilling_source_extraction WHERE source_id=? AND locator=?",Long.class,sourceId,extraction.get("locator")));
      db.update("UPDATE drilling_source_document SET parse_status='PARSED' WHERE source_id=?",sourceId);
      audit(fieldId,"MAP_SOURCE_FIELDS",Map.of("sourceId",sourceId,"extractionId",result.get("extraction_id"),"mapping",selection));
    }
    return result;
  }

  /**
   * Export the selected immutable trajectory through the original source
   * adapter. ORIGINAL is byte-for-byte; PATCHED keeps the source family and
   * replaces only the selected trajectory table. The original file remains an
   * immutable audit object in data_file.
   */
  @GetMapping("/versions/{versionId}/source-export")
  @RequiresPermissions("drilling:file:list")
  public org.springframework.http.ResponseEntity<byte[]> sourceExport(
      @PathVariable Long fieldId,
      @PathVariable Long versionId,
      @RequestParam(defaultValue = "PATCHED") String mode, @RequestParam(defaultValue="") String tableRole)
      throws java.io.IOException {
    var version = version(fieldId, versionId);
    String exportMode = mode == null ? "PATCHED" : mode.toUpperCase(Locale.ROOT);
    if (!Set.of("ORIGINAL", "PATCHED").contains(exportMode))
      throw new IllegalArgumentException("导出模式必须是 ORIGINAL 或 PATCHED");
    var extractionRows = db.queryForList(
        "SELECT e.payload_json,s.file_id,f.field_id AS storage_field_id,f.original_name,f.content_type FROM drilling_source_extraction e"
            + " JOIN drilling_source_document s ON s.source_id=e.source_id JOIN data_file f ON f.file_id=s.file_id"
            + " WHERE e.extraction_id=(SELECT MAX(x.extraction_id) FROM drilling_source_extraction x"
            + " JOIN drilling_source_document d ON d.source_id=x.source_id WHERE d.file_id=? AND x.locator=?)",
        version.get("source_file_id"), version.get("source_locator"));
    Map<String, Object> source;
    if (extractionRows.isEmpty() && "ORIGINAL".equals(exportMode)) {
      // A scanned PDF, legacy DOC/XLS or a report without a trajectory table
      // can still be exported exactly as stored.  ORIGINAL is deliberately
      // independent of parser success; PATCHED still requires a locator.
      var originals = db.queryForList(
          "SELECT s.source_file_id AS file_id,f.field_id AS storage_field_id,f.original_name,f.content_type "
              + "FROM drilling_survey_version s JOIN data_file f ON f.file_id=s.source_file_id "
              + "WHERE s.survey_version_id=? LIMIT 1", versionId);
      if (originals.isEmpty()) throw new IllegalArgumentException("该版本没有可下载的来源原件");
      source = new LinkedHashMap<>(originals.get(0));
    } else {
      if (extractionRows.isEmpty()) throw new IllegalArgumentException("该版本没有可回写的来源文件");
      source = new LinkedHashMap<>(extractionRows.get(0));
    }
    var tableData=engineeringTables.data(fieldId,id(version.get("wellbore_id")),versionId);
    if(!"ORIGINAL".equals(exportMode)&&Set.of("designNodes","designDetails").contains(tableRole)){
      Object locator=obj(tableData.get("locators")).get("designNodes".equals(tableRole)?"design_nodes":"design_details");
      if(locator!=null){var entries=db.queryForList("SELECT payload_json FROM drilling_source_extraction WHERE extraction_id=(SELECT MAX(e.extraction_id) FROM drilling_source_extraction e JOIN drilling_source_document s ON s.source_id=e.source_id WHERE s.file_id=? AND e.locator=?)",version.get("source_file_id"),locator);if(!entries.isEmpty())source.put("payload_json",entries.get(0).get("payload_json"));}
    }
    byte[] original = files.readBytes(id(source.get("storage_field_id")), id(source.get("file_id")));
    String originalName = Objects.toString(source.get("original_name"), "trajectory");
    String contentType = Objects.toString(source.get("content_type"), "application/octet-stream");
    if ("ORIGINAL".equals(exportMode)) {
      return org.springframework.http.ResponseEntity.ok()
          .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment()
              .filename(originalName, java.nio.charset.StandardCharsets.UTF_8).build().toString())
          .contentType(safeMediaType(contentType)).body(original);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("original_base64", Base64.getEncoder().encodeToString(original));
    payload.put("original_name", originalName);
    payload.put("mode", exportMode);
    payload.put("extraction", obj(source.get("payload_json")));
    var reference = obj(version.get("reference"));
    var sourceRef=reference.get("source_reference") instanceof Map<?,?>?obj(reference.get("source_reference")):obj(obj(source.get("payload_json")).get("reference"));
    String headers=JSON.toJSONString(obj(source.get("payload_json")).get("headers"));
    boolean offsets=headers.matches("(?s).*(北坐标|东坐标|南北位移|东西位移|（N）值|（E）值).*");
    if(offsets){sourceRef.put("coordinates_are_offsets",true);sourceRef.put("origin_north",reference.getOrDefault("wellhead_north",reference.getOrDefault("origin_north",0)));sourceRef.put("origin_east",reference.getOrDefault("wellhead_east",reference.getOrDefault("origin_east",0)));}
    var exportStations=(List<Map<String,Object>>)version.get("stations");
    if("designNodes".equals(tableRole)&&tableData.get("design_nodes") instanceof List<?> nodes&&!nodes.isEmpty()){
      exportStations=new ArrayList<>();for(Object node:nodes){var row=obj(node);if(!Boolean.FALSE.equals(tableData.get("nodes_are_offsets"))){row.put("north",((Number)row.get("north")).doubleValue()+((Number)sourceRef.getOrDefault("origin_north",0)).doubleValue());row.put("east",((Number)row.get("east")).doubleValue()+((Number)sourceRef.getOrDefault("origin_east",0)).doubleValue());}exportStations.add(row);}
    }
    var mapped=obj(source.get("payload_json"));var mapping=obj(mapped.get("mapping"));
    if(mapped.get("headers") instanceof List<?> labels)for(int i=0;i<labels.size();i++){
      String label=Objects.toString(labels.get(i),"").replaceAll("\\s","").toLowerCase(Locale.ROOT);
      for(var field:Map.of("remark","备注|remark|comment","dogleg","井眼曲率|全角变化率|dogleg|dls","closure_distance","水平位移|闭合距","closure_azimuth","平移方位|闭合方位","horizontal_length","水平长度","apparent_horizontal_length","视平长|视平移").entrySet())if(label.matches(".*("+field.getValue()+").*"))mapping.put(field.getKey(),i);
    }
    mapped.put("mapping",mapping);source.put("payload_json",mapped);payload.put("extraction",mapped);
    var outputRows=new ArrayList<Map<String,Object>>();for(var row:exportStations){var copy=new LinkedHashMap<String,Object>(row);if(copy.get("curvature") instanceof Number c)copy.put("dogleg",c.doubleValue()*180/Math.PI*30);outputRows.add(copy);}exportStations=outputRows;
    payload.put("source_reference",sourceRef);payload.put("stations",exportStations);
    byte[] output;
    if(originalName.toLowerCase(Locale.ROOT).endsWith(".xls")) {
      output=SourceSpreadsheetExporter.patch(original,obj(source.get("payload_json")),exportStations,sourceRef);
    } else output = engine.binary("/source/export", payload);
    String name = originalName;
    if ("PATCHED".equals(exportMode)) {
      // A source without an extension must remain extensionless.  Adding a
      // synthetic .dat changes the user's source name and breaks round-trip
      // export for legacy files whose format is identified by content.
      int dot = name.lastIndexOf('.');
      boolean hasExtension = dot > 0 && dot < name.length() - 1;
      String suffix = hasExtension ? name.substring(dot) : "";
      String stem = hasExtension ? name.substring(0, dot) : name;
      name = stem + "-exported" + suffix;
    }
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment().filename(name, java.nio.charset.StandardCharsets.UTF_8).build().toString())
        .contentType(safeMediaType(contentType))
        .body(output);
  }

  private static org.springframework.http.MediaType safeMediaType(String value) {
    try { return org.springframework.http.MediaType.parseMediaType(value); }
    catch (IllegalArgumentException ignored) { return org.springframework.http.MediaType.APPLICATION_OCTET_STREAM; }
  }

  private Map<String, Object> extraction(Long p, Long e) {
    read(p);
    var rows =
        db.queryForList(
            "SELECT e.*,s.file_id,s.source_format,s.field_id AS source_field_id FROM drilling_source_extraction e JOIN"
                + " drilling_source_document s ON s.source_id=e.source_id WHERE e.extraction_id=?",
            e);
    if (rows.isEmpty()) throw new IllegalArgumentException("无权访问提取表");
    read(id(rows.get(0).get("source_field_id")));
    return rows.get(0);
  }

  private Map<String,Object> sourceReview(Long fieldId, Long sourceId) {
    read(fieldId);
    var rows = db.queryForList(
        "SELECT r.*,s.source_path,s.parse_status,s.metadata_json FROM drilling_source_review r "
            + "JOIN drilling_source_document s ON s.source_id=r.source_id WHERE r.field_id=? AND r.source_id=?",
        fieldId, sourceId);
    if (rows.isEmpty()) {
      var source = db.queryForList("SELECT source_id,field_id,source_path,parse_status,metadata_json FROM drilling_source_document WHERE source_id=? AND field_id=?", sourceId, fieldId);
      if (source.isEmpty()) throw new IllegalArgumentException("来源资料不存在");
      db.update("INSERT IGNORE INTO drilling_source_review(field_id,source_id,status,proposed_json) VALUES(?,?, 'PENDING',?)",
          fieldId, sourceId, Objects.toString(source.get(0).get("metadata_json"), "{}"));
      rows = db.queryForList(
          "SELECT r.*,s.source_path,s.parse_status,s.metadata_json FROM drilling_source_review r JOIN drilling_source_document s ON s.source_id=r.source_id WHERE r.field_id=? AND r.source_id=?",
          fieldId, sourceId);
    }
    var result = new LinkedHashMap<String,Object>(rows.get(0));
    result.put("proposed", obj(result.get("proposed_json")));
    result.put("confirmed", obj(result.get("confirmed_json")));
    result.remove("proposed_json"); result.remove("confirmed_json");
    result.put("metadata", obj(result.get("metadata_json")));
    result.put("associations",engineeringLibrary.sourceAssociations(sourceId));
    // Keep the parser output visible in the review form.  These are source
    // extraction facts only; no extraction is considered usable until the
    // review itself is confirmed.
    var extractionRows = db.queryForList(
        "SELECT extraction_id,locator,import_status,payload_json FROM drilling_source_extraction "
            + "WHERE source_id=? ORDER BY extraction_id", sourceId);
    var extractionSummary = new ArrayList<Map<String,Object>>();
    for (var extraction : extractionRows) {
      var payload = obj(extraction.get("payload_json"));
      var summary = new LinkedHashMap<String,Object>();
      summary.put("extraction_id", extraction.get("extraction_id"));
      summary.put("locator", extraction.get("locator"));
      summary.put("import_status", extraction.get("import_status"));
      summary.put("kind", payload.get("kind"));
      summary.put("label", payload.get("label"));
      summary.put("mapping", payload.get("mapping"));
      summary.put("reference", payload.get("reference"));
      Object rowsValue = payload.get("rows");
      if (rowsValue instanceof List<?> stations && !stations.isEmpty()) {
        summary.put("row_count", stations.size());
        summary.put("first_md", obj(stations.get(0)).get("md"));
        summary.put("last_md", obj(stations.get(stations.size() - 1)).get("md"));
      } else {
        summary.put("row_count", 0);
      }
      extractionSummary.add(summary);
    }
    result.put("extractions", extractionSummary);
    enrichReviewCandidates(fieldId, result);
    return result;
  }

  /**
   * The parser proposes names only.  This read builds the editable fieldId
   * side candidate list; it deliberately writes no neighbour relationship.
   * The relationship is materialised only by confirmReview after the engineer
   * has reviewed the form.
   */
  private void enrichReviewCandidates(Long fieldId, Map<String, Object> review) {
    Map<String, Object> selected =
        "CONFIRMED".equalsIgnoreCase(Objects.toString(review.get("status"), ""))
            ? obj(review.get("confirmed"))
            : obj(review.get("proposed"));
    Map<String, Object> engineering = obj(selected.get("engineering"));
    String referenceCode = Objects.toString(
        selected.get("well"), Objects.toString(engineering.get("well"), "")).trim();
    Set<String> proposed = new LinkedHashSet<>();
    Object values = selected.get("neighbor_candidates");
    if (!(values instanceof List<?>)) values = engineering.get("neighbor_candidates");
    if (values instanceof List<?> list) {
      for (Object value : list) {
        String code = Objects.toString(value, "").trim();
        if (!code.isBlank()) proposed.add(code);
      }
    }
    var rows = db.queryForList(
        "SELECT b.wellbore_id,b.well_id,w.well_code,w.well_name,b.wellbore_code,b.wellbore_name,"
            + "b.engineering_status,w.wellhead_n,w.wellhead_e,f.field_id,f.field_name,k.block_id,k.block_name "
            + "FROM drilling_field_wellbore x JOIN drilling_wellbore b ON b.wellbore_id=x.wellbore_id "
            + "JOIN drilling_well w ON w.well_id=b.well_id LEFT JOIN drilling_well_catalog wc ON wc.well_id=w.well_id "
            + "LEFT JOIN drilling_field f ON f.field_id=wc.field_id LEFT JOIN drilling_block k ON k.block_id=f.block_id WHERE x.field_id=? "
            + "AND w.status<>'ARCHIVED' AND NOT EXISTS(SELECT 1 FROM drilling_engineering_node en"
            + " WHERE en.field_id=w.field_id AND en.well_id=w.well_id AND en.node_type='WELL'"
            + " AND en.entity_kind='PLANNED_RELIEF' AND en.status='ACTIVE') ORDER BY w.well_code,b.wellbore_code",
        fieldId);
    var options = new ArrayList<Map<String, Object>>();
    var unresolved = new ArrayList<String>();
    var matched = new HashSet<String>();
    for (var row : rows) {
      String wellCode = Objects.toString(row.get("well_code"), "");
      String boreCode = Objects.toString(row.get("wellbore_code"), "");
      if (!referenceCode.isBlank()
          && (referenceCode.equals(wellCode) || referenceCode.equals(boreCode))) continue;
      boolean suggested = proposed.contains(wellCode) || proposed.contains(boreCode)
          || proposed.contains(Objects.toString(row.get("well_name"), ""))
          || proposed.contains(Objects.toString(row.get("wellbore_name"), ""));
      if (suggested) matched.add(proposed.contains(wellCode) ? wellCode : boreCode);
      var option = new LinkedHashMap<String, Object>(row);
      option.put("suggested", suggested);
      option.put("selected", suggested);
      options.add(option);
    }
    for (String value : proposed) if (!matched.contains(value)) unresolved.add(value);
    review.put("neighbor_options", options);
    review.put("unresolved_neighbor_candidates", unresolved);
    review.put("neighbor_confirmation_required", true);
    review.put("neighbor_confirmation_message", unresolved.isEmpty()
        ? "自动识别结果已映射到当前井场井眼，可在表单中修改后人工确认"
        : "存在未映射的候选井，请从表单中删除或先将该井眼纳入当前井场后再确认");
  }

  @GetMapping("/sources/{sourceId}/review")
  @RequiresPermissions("drilling:file:list")
  public Map<String,Object> review(@PathVariable Long fieldId, @PathVariable Long sourceId) {
    return sourceReview(fieldId, sourceId);
  }

  @PutMapping("/sources/{sourceId}/review")
  @RequiresPermissions("drilling:well:survey:import")
  @Transactional
  public Map<String,Object> confirmReview(@PathVariable Long fieldId, @PathVariable Long sourceId,
      @RequestBody Map<String,Object> body) {
    write(fieldId);
    var current = sourceReview(fieldId, sourceId);
    String status = text(body, "status", "CONFIRMED").toUpperCase(Locale.ROOT);
    if (!Set.of("CONFIRMED", "REJECTED", "PENDING").contains(status))
      throw new IllegalArgumentException("解析确认状态无效");
    Map<String,Object> confirmed = obj(body.get("confirmed"));
    if (confirmed.isEmpty()) confirmed = obj(current.get("proposed"));
    if ("CONFIRMED".equals(status)) {
      String well = Objects.toString(confirmed.get("well"), "").trim();
      String scope = Objects.toString(confirmed.get("document_scope"), "").trim().toUpperCase(Locale.ROOT);
      if (well.isBlank())
        throw new IllegalArgumentException("确认前必须填写归属井；来源文件必须归入井或井眼");

      // Normalize the editable form to one canonical candidate list.  Never
      // silently accept a parser name that is not an actual fieldId wellbore.
      Map<String,Object> engineering = obj(confirmed.get("engineering"));
      Object candidateValue = confirmed.get("neighbor_candidates");
      if (!(candidateValue instanceof List<?>)) candidateValue = engineering.get("neighbor_candidates");
      var candidates = new ArrayList<String>();
      if (candidateValue instanceof List<?> list) {
        for (Object value : list) {
          String code = Objects.toString(value, "").trim();
          if (!code.isBlank() && !candidates.contains(code)) candidates.add(code);
        }
      }
      confirmed.put("neighbor_candidates", candidates);
      if (!engineering.isEmpty()) {
        engineering.put("neighbor_candidates", candidates);
        confirmed.put("engineering", engineering);
      }
      if (!well.isBlank()) {
        var reference = db.queryForList(
            "SELECT b.wellbore_id FROM drilling_well w JOIN drilling_wellbore b ON b.well_id=w.well_id "
                + "JOIN drilling_field_wellbore x ON x.wellbore_id=b.wellbore_id "
                + "WHERE x.field_id=? AND (w.well_code=? OR w.well_name=? OR b.wellbore_code=? OR b.wellbore_name=?) "
                + "ORDER BY b.wellbore_id", fieldId, well, well, well, well);
        if (reference.isEmpty()) throw new IllegalArgumentException("归属井/井眼不属于当前工程目录，请先在工程层级中建立或关联");
      }
      var unresolved = new ArrayList<String>();
      for (String code : candidates) {
        var neighbor = db.queryForList(
            "SELECT b.wellbore_id FROM drilling_well w JOIN drilling_wellbore b ON b.well_id=w.well_id "
                + "JOIN drilling_field_wellbore x ON x.wellbore_id=b.wellbore_id "
                + "WHERE x.field_id=? AND (w.well_code=? OR w.well_name=? OR b.wellbore_code=? OR b.wellbore_name=?) "
                + "ORDER BY b.wellbore_id", fieldId, code, code, code, code);
        if (neighbor.isEmpty()) unresolved.add(code);
      }
      if (!unresolved.isEmpty())
        throw new IllegalArgumentException("邻井候选未映射到当前工程井眼：" + String.join("、", unresolved)
            + "。请在表单中删除，或先关联对应井眼后再确认");
    }
    db.update("UPDATE drilling_source_review SET status=?,confirmed_json=?,reviewed_by=?,reviewed_at=NOW(),review_note=? WHERE field_id=? AND source_id=?",
        status, JSON.toJSONString(confirmed), ShiroUtils.getLoginName(), text(body,"note",""), fieldId, sourceId);
    db.update("UPDATE drilling_source_document SET metadata_json=JSON_SET(COALESCE(metadata_json,JSON_OBJECT()),'$.review_status',?,'$.confirmed_engineering',?) WHERE field_id=? AND source_id=?",
        status, JSON.toJSONString(confirmed), fieldId, sourceId);
    if ("CONFIRMED".equals(status)) {
      String wellCode = Objects.toString(confirmed.get("well"), "").trim();
      String wellboreCode = Objects.toString(confirmed.get("wellbore"), "").trim();
      var reference = db.queryForList(
          "SELECT b.wellbore_id FROM drilling_well w JOIN drilling_wellbore b ON b.well_id=w.well_id "
              + "JOIN drilling_field_wellbore x ON x.wellbore_id=b.wellbore_id WHERE x.field_id=? "
              + "AND (w.well_code=? OR w.well_name=? OR b.wellbore_code=? OR b.wellbore_name=?) "
              + "AND (?='' OR b.wellbore_code=? OR b.wellbore_name=?) ORDER BY b.wellbore_id",
          fieldId, wellCode, wellCode, wellCode, wellCode, wellboreCode, wellboreCode, wellboreCode);
      Object candidatesValue = confirmed.get("neighbor_candidates");
      if (!reference.isEmpty()) {
        // The edited form is authoritative for this source confirmation. A
        // previously selected candidate that was removed in the form must no
        // longer participate in a default scan.
        Long referenceBore=id(reference.get(0).get("wellbore_id"));
        db.update("UPDATE drilling_neighbor_selection SET included=FALSE WHERE field_id=? AND reference_wellbore_id=?",
            fieldId, referenceBore);
        if (!(candidatesValue instanceof List<?>)) candidatesValue = List.of();
      }
      if (!reference.isEmpty() && candidatesValue instanceof List<?> candidates) {
        for (Object candidate : candidates) {
          String code = Objects.toString(candidate, "").trim();
          if (code.isBlank()) continue;
          var neighbor = db.queryForList(
              "SELECT b.wellbore_id FROM drilling_well w JOIN drilling_wellbore b ON b.well_id=w.well_id "
                  + "JOIN drilling_field_wellbore x ON x.wellbore_id=b.wellbore_id WHERE x.field_id=? "
                  + "AND (w.well_code=? OR w.well_name=? OR b.wellbore_code=? OR b.wellbore_name=?) "
                  + "ORDER BY b.wellbore_id", fieldId, code, code, code, code);
          if (neighbor.isEmpty()) continue; // pre-validation rejects this case
          Long referenceBore=id(reference.get(0).get("wellbore_id")), neighborBore=id(neighbor.get(0).get("wellbore_id"));
          if (referenceBore.equals(neighborBore)) continue;
          db.update("INSERT INTO drilling_neighbor_selection(field_id,reference_wellbore_id,neighbor_wellbore_id,included,version_policy) VALUES(?,?,?,TRUE,'CURRENT_ACTUAL') ON DUPLICATE KEY UPDATE included=TRUE,version_policy='CURRENT_ACTUAL',pinned_version_id=NULL", fieldId, referenceBore, neighborBore);
        }
      }
    }
    audit(fieldId, "CONFIRM_SOURCE_REVIEW", Map.of("sourceId",sourceId,"status",status,"confirmed",confirmed));
    return sourceReview(fieldId, sourceId);
  }

  private Map<String, Object> previewPayload(
      Long fieldId, Long extractionId, Map<String, Object> body) {
    var entry = extraction(fieldId, extractionId);
    var payload = obj(entry.get("payload_json"));
    payload.put("extracted_reference",new LinkedHashMap<>(obj(payload.get("reference"))));
    var review = sourceReview(id(entry.get("source_field_id")), id(entry.get("source_id")));
    if ("CONFIRMED".equalsIgnoreCase(Objects.toString(review.get("status"), ""))) {
      var confirmed = obj(review.get("confirmed"));
      var confirmedReference = obj(confirmed.get("reference"));
      var sourceReference = obj(payload.get("reference"));
      sourceReference.putAll(confirmedReference);
      for (String key : List.of("crs", "depth_datum", "depth_datum_elevation_m", "origin_north", "origin_east", "origin_tvd",
          "coordinate_system_text", "coordinate_datum_name", "wellhead_north", "wellhead_east"))
        if (confirmed.get(key) != null) sourceReference.put(key, confirmed.get(key));
      payload.put("reference", sourceReference);
      payload.put("confirmed_engineering", confirmed);
    }
    String kind = required(body, "kind", 16), mode = text(body, "importMode", "FULL");
    if (!Set.of("ACTUAL", "DESIGN").contains(kind)
        || !Set.of("FULL", "INCREMENTAL", "RESURVEY").contains(mode))
      throw new IllegalArgumentException("轨道类型或导入方式无效");
    Long bid = id(body.get("wellboreId"));
    bore(fieldId, bid);
    var reference = obj(payload.get("reference"));
    reference.putAll(obj(body.get("reference")));
    payload.put("reference", reference);
    payload.put("kind", kind);
    payload.put("policy", obj(body.get("policy")));
    payload.put("revision_type", mode);
    payload.put("replace_from_md", body.get("replaceFromMd"));
    payload.put("replace_to_md", body.get("replaceToMd"));
    Long parent = id(body.get("parentVersionId"));
    if (parent != null) {
      var pv = version(fieldId, parent);
      if (!bid.equals(id(pv.get("wellbore_id"))) || !kind.equals(pv.get("trajectory_kind")))
        throw new IllegalArgumentException("父版本必须属于同一井眼和轨道类型");
      if (!"FULL".equals(mode)) {
        payload.put("existing", pv.get("stations"));
        payload.put("parent_reference", pv.get("reference"));
      }
    }
    if (!"FULL".equals(mode) && parent == null)
      throw new IllegalArgumentException("增量/复测导入必须明确父版本");
    if ("RESURVEY".equals(mode) && !"ACTUAL".equals(kind))
      throw new IllegalArgumentException("复测修订只适用于实钻数据");
    return payload;
  }

  @PostMapping("/extractions/{extractionId}/preview")
  @RequiresPermissions("drilling:well:survey:import")
  public Map<String, Object> preview(
      @PathVariable Long fieldId,
      @PathVariable Long extractionId,
      @RequestBody Map<String, Object> body) {
    write(fieldId);
    return engine.json("/source/preview", previewPayload(fieldId, extractionId, body));
  }

  @PostMapping("/extractions/{extractionId}/commit")
  @RequiresPermissions("drilling:well:survey:import")
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Map<String, Object> commit(
      @PathVariable Long fieldId,
      @PathVariable Long extractionId,
      @RequestBody Map<String, Object> body) {
    write(fieldId);
    Long bid = id(body.get("wellboreId"));
    writeBore(fieldId, bid);
    var b = bore(fieldId, bid);
    Long well = id(b.get("well_id"));
    trajectories.lockWell(well);
    String kind = required(body, "kind", 16), mode = text(body, "importMode", "FULL");
    Long parent = id(body.get("parentVersionId"));
    var payload = previewPayload(fieldId, extractionId, body);
    String note = text(body, "note", "").trim();
    if (note.length() > 500) throw new IllegalArgumentException("修订说明最长500字");
    if ("RESURVEY".equals(mode) && note.isBlank())
      throw new IllegalArgumentException("复测修订必须填写修订原因");
    // A confirmed trajectory upload is immediately the current version.  The
    // immutable parent remains available for version switching and audit.
    boolean activate = true;
    Long previous = trajectories.head(bid, kind);
    if (activate && !"FULL".equals(mode) && !Objects.equals(parent, previous))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "当前版本已变化，请重新选择父版本或保存为非当前分支");
    if (activate
        && body.containsKey("expectedCurrentVersionId")
        && !Objects.equals(previous, id(body.get("expectedCurrentVersionId"))))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "当前版本已变化，请刷新预览后再保存");
    var calculated = engine.json("/source/preview", payload);
    if (Boolean.TRUE.equals(calculated.get("has_error")))
      throw new IllegalArgumentException("存在冲突或非法点，请处理后提交");
    var stations = (List<Map<String, Object>>) calculated.get("stations");
    if (stations == null || stations.size() < 2) throw new IllegalArgumentException("至少需要两个有效测点");
    var entry = extraction(fieldId, extractionId);
    var review = sourceReview(id(entry.get("source_field_id")), id(entry.get("source_id")));
    if (!"CONFIRMED".equalsIgnoreCase(Objects.toString(review.get("status"), "")))
      throw new IllegalArgumentException("来源资料的自动解析结果尚未完成工程人员确认，不能生成轨迹版本");
    var ref = obj(calculated.get("normalized_reference"));
    ref.put("engineering_table_role","FULL".equals(mode)?"SOURCE":"surveyData");
    var structured=db.queryForList("SELECT metadata_json FROM drilling_source_document WHERE source_id=?",entry.get("source_id"));
    if(!structured.isEmpty()){
      var tables=obj(obj(structured.get(0).get("metadata_json")).get("engineering_tables"));
      if(tables.get("design_nodes") instanceof List<?> nodes&&!nodes.isEmpty()){ref.put("design_nodes",nodes);ref.put("design_nodes_are_offsets",true);}
    }

    var profile=obj(ref.get("uncertainty"));
    if(Objects.toString(obj(ref.get("survey_tool")).get("name"),"").isBlank())throw new IllegalArgumentException("请填写本版本的测斜工具名称");
    if(!Boolean.TRUE.equals(profile.get("confirmed")) || profile.get("settings")==null || profile.get("model_version")==null)
      throw new IllegalArgumentException("请在导入窗口核对并确认本版本的坐标、测斜工具及误差参数");
    SurveyVersionMetadata.requireCommonModel(profile,DrillingAlgorithmDefaults.merged(engine.config(fieldId).get("algorithm_defaults_json")));
    profile.put("confirmed_by",ShiroUtils.getLoginName());
    profile.put("confirmed_at",java.time.Instant.now().toString());
    ref.put("uncertainty",profile);
    var canonical =
        stations.stream()
            .map(r -> List.of(r.get("md"), r.get("inclination"), r.get("azimuth")))
            .toList();
    var frame = new TreeMap<String, Object>();
    for (String key :
        List.of(
            "crs",
            "depth_datum",
            "depth_datum_elevation_m",
            "origin_north",
            "origin_east",
            "origin_tvd",
            "coordinate_system_text",
            "coordinate_datum_name",
            "basin",
            "structural_position",
            "oilfield",
            "development_block",
            "administrative_location",
            "ground_elevation_m",
            "kelly_bushing_elevation_m",
            "latitude",
            "longitude",
            "formation")) frame.put(key, ref.get(key));
    frame.putAll(SurveyVersionMetadata.identity(ref));
    String fingerprint = NativeEngineSupport.hash(SurveyVersionMetadata.canonical(List.of(canonical, frame)));
    Long duplicate = null;
    var prior =
        db.queryForList(
            "SELECT survey_version_id FROM drilling_trajectory_version WHERE wellbore_id=? AND"
                + " trajectory_kind=? AND content_sha256=?",
            bid,
            kind,
            fingerprint);
    if (!prior.isEmpty()) duplicate = id(prior.get(0).get("survey_version_id"));
    if (parent != null
        && !"FULL".equals(mode)
        && ((Number) obj(calculated.get("change_summary")).get("changed_points")).intValue() == 0
        && SurveyVersionMetadata.canonical(SurveyVersionMetadata.identity(obj(version(fieldId,parent).get("reference")))).equals(SurveyVersionMetadata.canonical(SurveyVersionMetadata.identity(ref))))
      duplicate = parent;
    if (duplicate != null) {
      db.update(
          "UPDATE drilling_source_extraction SET import_status='IMPORTED',survey_version_id=? WHERE"
              + " extraction_id=?",
          duplicate,
          extractionId);
      engineeringLibrary.registerImportedTrajectory(fieldId,well,bid,duplicate,id(entry.get("source_id")),kind,
          Objects.toString(body.get("label"),""),Objects.toString(body.get("businessRevision"),""),
          Objects.toString(body.get("documentRole"),"TRAJECTORY_DATA"));
      audit(
          fieldId,
          "DUPLICATE_TRAJECTORY",
          Map.of("versionId", duplicate, "source", extractionId));
      var result = version(fieldId, duplicate);
      result.put("duplicate", true);
      return result;
    }
    int no = trajectories.nextSnapshotNumber(well);
    var config = engine.config(fieldId);
    double max = ((Number) stations.get(stations.size() - 1).get("md")).doubleValue();
    String label = text(body, "label", "").trim();
    if (label.length() > 255) throw new IllegalArgumentException("版本名称最长255字");
    int trajectoryNumber=trajectories.nextNumber(bid,kind);
    if (label.isEmpty()) label = ("ACTUAL".equals(kind) ? "实钻" : "设计") + " v" + trajectoryNumber;
    db.update(
        "INSERT INTO"
            + " drilling_survey_version(well_id,version_no,parent_version_id,source_file_id,config_version,import_mode,source_format,max_md,is_current,quality_summary_json,config_snapshot_json,create_by)"
            + " VALUES(?,?,?,?,?,?,?,?, '0',?,?,?)",
        well,
        no,
        parent,
        entry.get("file_id"),
        config.get("config_version"),
        "RESURVEY".equals(mode) ? "REPLACE" : mode,
        entry.get("source_format"),
        max,
        JSON.toJSONString(calculated.get("issues")),
        JSON.toJSONString(config),
        ShiroUtils.getLoginName());
    Long v =
        db.queryForObject(
            "SELECT survey_version_id FROM drilling_survey_version WHERE well_id=? AND"
                + " version_no=?",
            Long.class,
            well,
            no);
    List<Object[]> batch = new ArrayList<>();
    for (var r : stations)
      batch.add(
          new Object[] {
            v,
            r.get("md"),
            r.get("inclination"),
            r.get("azimuth"),
            r.get("north"),
            r.get("east"),
            r.get("tvd"),
            r.get("dogleg"),
            r.get("curvature"),
            JSON.toJSONString(r)
          });
    db.batchUpdate(
        "INSERT INTO"
            + " drilling_survey_station(survey_version_id,md,inclination,azimuth,north,east,tvd,dogleg,curvature,raw_json)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        batch);
    String referenceStatus = referenceStatus(ref, stations);
    ref.put("reference_status", referenceStatus);
    ref.put("evaluation_eligibility", "READY".equals(referenceStatus) ? "ELIGIBLE" : "BLOCKED");
    ref.put("evaluation_message", referenceMessage(ref, referenceStatus));
    boolean ready = "READY".equals(referenceStatus);
    db.update(
        "INSERT INTO"
            + " drilling_trajectory_version(survey_version_id,wellbore_id,trajectory_kind,trajectory_version_no,source_locator,content_sha256,reference_json,quality_status,label,revision_note)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        v,
        bid,
        kind,
        trajectoryNumber,
        entry.get("locator"),
        fingerprint,
        JSON.toJSONString(ref),
        ready ? "READY" : "REFERENCE_REQUIRED",
        label,
        note);
    var summary = obj(calculated.get("downstream_recalculation"));
    summary.put("change_summary", calculated.get("change_summary"));
    summary.put("replacement_range", calculated.get("replacement_range"));
    db.update(
        "INSERT INTO"
            + " drilling_trajectory_revision(survey_version_id,revision_type,parent_version_id,changed_from_md,changed_to_md,summary_json)"
            + " VALUES(?,?,?,?,?,?)",
        v,
        mode,
        parent,
        calculated.get("incremental_from_md"),
        calculated.get("changed_to_md"),
        JSON.toJSONString(summary));
    if (activate) trajectories.setHead(bid, kind, v, well);
    db.update("UPDATE drilling_engineering_document_file df JOIN drilling_engineering_document d ON d.document_id=df.document_id JOIN drilling_engineering_node n ON n.node_id=d.node_id SET df.usage_state='ACTIVE' WHERE df.source_id=? AND n.wellbore_id=? AND df.usage_state='DRAFT'",entry.get("source_id"),bid);
    db.update(
        "UPDATE drilling_source_extraction SET import_status='IMPORTED',survey_version_id=? WHERE"
            + " extraction_id=?",
        v,
        extractionId);
    engineeringLibrary.registerImportedTrajectory(fieldId,well,bid,v,id(entry.get("source_id")),kind,label,
        Objects.toString(body.get("businessRevision"),""),Objects.toString(body.get("documentRole"),"TRAJECTORY_DATA"));
    if("ACTUAL".equals(kind)&&!structured.isEmpty())engineeringTables.importActualTargets(fieldId,bid,v,obj(structured.get(0).get("metadata_json")));
    for (Object item : (List<?>) calculated.getOrDefault("issues", List.of())) {
      var issue = obj(item);
      db.update(
          "INSERT INTO"
              + " drilling_data_quality_issue(field_id,well_id,survey_version_id,source_file_id,issue_type,severity,raw_json,create_by)"
              + " VALUES(?,?,?,?,?,?,?,?)",
          fieldId,
          well,
          v,
          entry.get("file_id"),
          issue.get("type"),
          issue.get("severity"),
          JSON.toJSONString(issue),
          ShiroUtils.getLoginName());
    }
    var detail = new LinkedHashMap<String, Object>();
    detail.put("versionId", v);
    detail.put("kind", kind);
    detail.put("wellboreId", bid);
    detail.put("source", extractionId);
    detail.put("parentVersionId", parent);
    detail.put("revisionType", mode);
    detail.put("note", note);
    detail.put("activated", activate);
    detail.put("impact", summary);
    audit(fieldId, "COMMIT_TRAJECTORY", detail);
    return version(fieldId, v);
  }

  /** Correct a historical version's measuring program without re-importing or
   * rolling its stations back to an older source file. */
  @PostMapping("/versions/{versionId}/measurement-parameters")
  @RequiresPermissions("drilling:well:survey:import")
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Map<String,Object> reviseMeasurementParameters(@PathVariable Long fieldId,@PathVariable Long versionId,@RequestBody Map<String,Object> body) {
    write(fieldId);
    var original=version(fieldId,versionId);
    Long bid=id(original.get("wellbore_id")),well=id(original.get("well_id"));
    writeBore(fieldId,bid);trajectories.lockWell(well);
    String kind=original.get("trajectory_kind").toString();
    Long previous=trajectories.head(bid,kind);
    boolean activate=!Boolean.FALSE.equals(body.get("activate"));
    if(activate&&(!body.containsKey("expectedCurrentVersionId")||!Objects.equals(previous,id(body.get("expectedCurrentVersionId")))))
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"当前版本已变化，请刷新后重试");
    String note=required(body,"note",500);
    var tool=obj(body.get("surveyTool"));
    if(Objects.toString(tool.get("name"),"").isBlank())throw new IllegalArgumentException("请填写测斜工具名称");
    var profile=obj(body.get("uncertainty"));
    if(!Boolean.TRUE.equals(profile.get("confirmed")))throw new IllegalArgumentException("请核对并确认本版本全井段的测斜工具及误差参数");
    var defaults=DrillingAlgorithmDefaults.merged(engine.config(fieldId).get("algorithm_defaults_json"));
    SurveyVersionMetadata.requireCommonModel(profile,defaults);
    var ref=obj(original.get("reference"));ref.put("survey_tool",tool);ref.put("uncertainty",profile);
    var effective=SurveyVersionMetadata.uncertainty(ref,defaults);
    var model=new LinkedHashMap<String,Object>(NativeEngineSupport.modelPayload(defaults));
    model.put("model_version",effective.get("model_version"));model.put("settings",effective.get("settings"));
    engine.json("/engine/validate",Map.of("error_model",model));
    if(SurveyVersionMetadata.canonical(SurveyVersionMetadata.identity(obj(original.get("reference")))).equals(SurveyVersionMetadata.canonical(SurveyVersionMetadata.identity(ref)))) {
      original.put("duplicate",true);return original;
    }
    String fingerprint=NativeEngineSupport.hash(versionId+":"+SurveyVersionMetadata.canonical(SurveyVersionMetadata.identity(ref)));
    var duplicates=db.queryForList("SELECT survey_version_id FROM drilling_trajectory_version WHERE wellbore_id=? AND trajectory_kind=? AND content_sha256=?",bid,kind,fingerprint);
    if(!duplicates.isEmpty()) {
      Long existing=id(duplicates.get(0).get("survey_version_id"));
      if(activate)trajectories.setHead(bid,kind,existing,well);
      var duplicate=version(fieldId,existing);duplicate.put("duplicate",true);return duplicate;
    }
    profile.put("confirmed_by",ShiroUtils.getLoginName());profile.put("confirmed_at",java.time.Instant.now().toString());
    int number=trajectories.nextSnapshotNumber(well);
    int trajectoryNumber=trajectories.nextNumber(bid,kind);
    db.update("INSERT INTO drilling_survey_version(well_id,version_no,parent_version_id,source_file_id,config_version,import_mode,source_format,max_md,is_current,quality_summary_json,config_snapshot_json,create_by) "
        + "SELECT well_id,?,?,source_file_id,config_version,'FULL',source_format,max_md,'0',quality_summary_json,config_snapshot_json,? FROM drilling_survey_version WHERE survey_version_id=?",number,versionId,ShiroUtils.getLoginName(),versionId);
    Long created=db.queryForObject("SELECT survey_version_id FROM drilling_survey_version WHERE well_id=? AND version_no=?",Long.class,well,number);
    db.update("INSERT INTO drilling_survey_station(survey_version_id,md,inclination,azimuth,north,east,tvd,dogleg,curvature,raw_json) SELECT ?,md,inclination,azimuth,north,east,tvd,dogleg,curvature,raw_json FROM drilling_survey_station WHERE survey_version_id=?",created,versionId);
    db.update("INSERT INTO drilling_trajectory_version(survey_version_id,wellbore_id,trajectory_kind,trajectory_version_no,source_locator,content_sha256,reference_json,quality_status,label,revision_note) VALUES(?,?,?,?,?,?,?,?,?,?)",
        created,bid,kind,trajectoryNumber,original.get("source_locator"),fingerprint,JSON.toJSONString(ref),original.get("quality_status"),"测量参数修订 v"+trajectoryNumber,note);
    var stations=(List<Map<String,Object>>)original.get("stations");
    Object from=stations.isEmpty()?0:stations.get(0).get("md"),to=original.get("max_md");
    db.update("INSERT INTO drilling_trajectory_revision(survey_version_id,revision_type,parent_version_id,changed_from_md,changed_to_md,summary_json) VALUES(?,'FULL',?,?,?,?)",
        created,versionId,from,to,JSON.toJSONString(Map.of("revision_scope","MEASUREMENT_PARAMETERS","geometry_changed",false,"covariance_recalculation_from_md",from)));
    if(activate)trajectories.setHead(bid,kind,created,well);
    var sources=db.queryForList("SELECT source_id FROM drilling_source_document WHERE file_id=? ORDER BY source_id DESC LIMIT 1",original.get("source_file_id"));
    if(!sources.isEmpty())engineeringLibrary.registerImportedTrajectory(fieldId,well,bid,created,id(sources.get(0).get("source_id")),kind,"测量参数修订 v"+trajectoryNumber,"","TRAJECTORY_DATA");
    audit(fieldId,"REVISE_MEASUREMENT_PARAMETERS",Map.of("versionId",created,"parentVersionId",versionId,"before",SurveyVersionMetadata.identity(obj(original.get("reference"))),"after",SurveyVersionMetadata.identity(ref),"note",note));
    return version(fieldId,created);
  }

  /** Save a manually edited DESIGN or (while drilling) ACTUAL table as a new immutable version. */
  @PostMapping("/versions/{versionId}/stations")
  @RequiresPermissions("drilling:well:survey:import")
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Map<String,Object> saveDesignStations(@PathVariable Long fieldId,@PathVariable Long versionId,@RequestBody Map<String,Object> body) {
    write(fieldId);
    var original=version(fieldId,versionId);
    String kind=Objects.toString(original.get("trajectory_kind"),"");
    if (!Set.of("DESIGN","ACTUAL").contains(kind)) throw new IllegalArgumentException("轨迹类别不支持编辑");
    Long bid=id(original.get("wellbore_id")), well=id(original.get("well_id"));
    writeBore(fieldId,bid); trajectories.lockWell(well);
    String engineeringStatus=db.queryForObject("SELECT engineering_status FROM drilling_wellbore WHERE wellbore_id=?",String.class,bid);
    if ("ACTUAL".equals(kind) && !Set.of("DRILLING","SUSPENDED").contains(engineeringStatus))
      throw new IllegalArgumentException("只有正在钻进的井眼可以修改实测测斜数据");
    if ("DESIGN".equals(kind) && !Set.of("UNPLANNED","PLANNED","DRILLING","SUSPENDED").contains(engineeringStatus))
      throw new IllegalArgumentException("当前施工状态不允许修改设计轨道数据");
    Long expected=id(body.get("expectedCurrentVersionId"));
    if(expected!=null&&!Objects.equals(expected,trajectories.head(bid,kind)))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"当前轨迹版本已变更，请刷新后修改");
    Object value=body.get("stations");
    if (!(value instanceof List<?> raw) || raw.size()<2) throw new IllegalArgumentException("设计轨道至少需要两个节点");
    var stations=new ArrayList<Map<String,Object>>(); double previous=-1d;
    for (Object item:raw) {
      Map<String,Object> row=obj(item);
      double md=finite(row.get("md"),"MD"), inc=finite(row.get("inclination"),"井斜"), azi=finite(row.get("azimuth"),"方位");
      if (md<0 || md<=previous) throw new IllegalArgumentException("节点 MD 必须严格递增");
      if (inc<0 || inc>180) throw new IllegalArgumentException("井斜必须在 0～180°");
      if (!Double.isFinite(azi)) throw new IllegalArgumentException("方位必须为有效数值");
      Map<String,Object> normalized=new LinkedHashMap<>(); normalized.put("md",md); normalized.put("inclination",inc); normalized.put("azimuth",azi);
      for (String key:List.of("north","east","tvd","dogleg","curvature")) if(row.get(key)!=null&&!Objects.toString(row.get(key),"").isBlank()) normalized.put(key,finite(row.get(key),key));
      if(!normalized.containsKey("north")||!normalized.containsKey("east")||!normalized.containsKey("tvd")) {
        throw new IllegalArgumentException("节点必须填写 N、E、TVD");
      }
      for(String key:List.of("closure_distance","closure_azimuth","horizontal_length","apparent_horizontal_length"))if(row.get(key)!=null)normalized.put(key,finite(row.get(key),key));
      normalized.put("remark",Objects.toString(row.get("remark"),""));
      if(Boolean.TRUE.equals(row.get("is_key_node")))normalized.put("is_key_node",true);
      stations.add(normalized); previous=md;
    }
    for(int i=1;i<stations.size();i++){
      var a=stations.get(i-1);var z=stations.get(i);double chord2=0;
      for(String axis:List.of("north","east","tvd")){double delta=finite(z.get(axis),axis)-finite(a.get(axis),axis);chord2+=delta*delta;}
      double length=finite(z.get("md"),"井深")-finite(a.get("md"),"井深");
      if(Math.sqrt(chord2)>length+.1)throw new IllegalArgumentException("井深 "+a.get("md")+"～"+z.get("md")+" m 的节点间距超过段长，请核对坐标和井深");
    }
    var calculation=new LinkedHashMap<String,Object>();calculation.put("stations",stations);calculation.put("canonical_coordinates",true);
    var measures=(List<Map<String,Object>>)engine.json("/survey/calculate",calculation).get("stations");
    for(int i=0;i<stations.size();i++){stations.get(i).put("dogleg",measures.get(i).get("dogleg"));stations.get(i).put("curvature",measures.get(i).get("curvature"));}
    var reference=obj(original.get("reference"));
    String tableRole=text(body,"tableRole","surveyData");reference.put("engineering_table_role",tableRole);
    if("DESIGN".equals(kind)){
      var data=engineeringTables.data(fieldId,bid,versionId);
      var before="designNodes".equals(tableRole)?data.get("design_nodes"):data.get("design_details");
      if(Set.of("DRILLING","SUSPENDED").contains(engineeringStatus)&&before instanceof List<?> locked){
        double drilled=((Number)data.get("drilled_md")).doubleValue();
        double n0=Objects.requireNonNullElse(numberOrNull(reference.get("wellhead_north")),Objects.requireNonNullElse(numberOrNull(reference.get("origin_north")),0d));
        double e0=Objects.requireNonNullElse(numberOrNull(reference.get("wellhead_east")),Objects.requireNonNullElse(numberOrNull(reference.get("origin_east")),0d));
        for(Object item:locked){var old=obj(item);double md=finite(old.get("md"),"井深");if(md>drilled)continue;Map<String,Object> same=null;for(var candidate:stations)if(Math.abs(finite(candidate.get("md"),"井深")-md)<1e-6){same=candidate;break;}
          if(same==null)throw new IllegalArgumentException("已钻井段的设计点不能删除");
          for(String key:List.of("inclination","azimuth","tvd","north","east")){if(old.get(key)==null)continue;boolean offsets=!"designNodes".equals(tableRole)||!Boolean.FALSE.equals(data.get("nodes_are_offsets"));double target=finite(old.get(key),key)+(offsets?("north".equals(key)?n0:"east".equals(key)?e0:0):0);if(Math.abs(finite(same.get(key),key)-target)>.002)throw new IllegalArgumentException("已钻井段的设计点不能修改");}
        }
      }
      if("designNodes".equals(tableRole)){
        reference.put("design_nodes",new ArrayList<>(stations));reference.put("design_nodes_are_offsets",false);
        var sample=new LinkedHashMap<String,Object>();sample.put("stations",stations);sample.put("canonical_coordinates",true);sample.put("step",10);sample.put("key_mds",stations.stream().map(p->p.get("md")).toList());
        stations=new ArrayList<>((List<Map<String,Object>>)engine.json("/survey/sample",sample).get("stations"));
      }else if(data.get("design_nodes") instanceof List<?> nodes){reference.put("design_nodes",nodes);reference.put("design_nodes_are_offsets",data.getOrDefault("nodes_are_offsets",true));}
    }
    calculation.put("stations",stations);measures=(List<Map<String,Object>>)engine.json("/survey/calculate",calculation).get("stations");
    for(int i=0;i<stations.size();i++){stations.get(i).put("dogleg",measures.get(i).get("dogleg"));stations.get(i).put("curvature",measures.get(i).get("curvature"));}
    String revisionType="DESIGN".equals(kind)?"DESIGN_NODE_EDIT":"ACTUAL_TABLE_EDIT";
    String fingerprint=NativeEngineSupport.hash(SurveyVersionMetadata.canonical(List.of(stations,SurveyVersionMetadata.identity(reference),revisionType)));
    var duplicate=db.queryForList("SELECT survey_version_id FROM drilling_trajectory_version WHERE wellbore_id=? AND trajectory_kind=? AND content_sha256=?",bid,kind,fingerprint);
    if(!duplicate.isEmpty()){Long existing=id(duplicate.get(0).get("survey_version_id"));trajectories.setHead(bid,kind,existing,well);return version(fieldId,existing);}
    int snapshot=trajectories.nextSnapshotNumber(well), trajectoryNumber=trajectories.nextNumber(bid,kind);
    var config=engine.config(fieldId); double max=((Number)stations.get(stations.size()-1).get("md")).doubleValue();
    String note=text(body,"note",""); if(note.length()>500)throw new IllegalArgumentException("修订说明最长500字");
    db.update("INSERT INTO drilling_survey_version(well_id,version_no,parent_version_id,source_file_id,config_version,import_mode,source_format,max_md,is_current,quality_summary_json,config_snapshot_json,create_by) SELECT well_id,?,?,source_file_id,config_version,'FULL',source_format,?,'0',quality_summary_json,config_snapshot_json,? FROM drilling_survey_version WHERE survey_version_id=?",snapshot,versionId,max,ShiroUtils.getLoginName(),versionId);
    Long created=db.queryForObject("SELECT survey_version_id FROM drilling_survey_version WHERE well_id=? AND version_no=?",Long.class,well,snapshot);
    var batch=new ArrayList<Object[]>(); for(var row:stations)batch.add(new Object[]{created,row.get("md"),row.get("inclination"),row.get("azimuth"),row.get("north"),row.get("east"),row.get("tvd"),row.get("dogleg"),row.get("curvature"),JSON.toJSONString(row)});
    db.batchUpdate("INSERT INTO drilling_survey_station(survey_version_id,md,inclination,azimuth,north,east,tvd,dogleg,curvature,raw_json) VALUES(?,?,?,?,?,?,?,?,?,?)",batch);
    db.update("INSERT INTO drilling_trajectory_version(survey_version_id,wellbore_id,trajectory_kind,trajectory_version_no,source_locator,content_sha256,reference_json,quality_status,label,revision_note) VALUES(?,?,?,?,?,?,?,?,?,?)",created,bid,kind,trajectoryNumber,original.get("source_locator"),fingerprint,JSON.toJSONString(reference),original.get("quality_status"),("DESIGN".equals(kind)?"设计轨道编辑 v":"实测测斜编辑 v")+trajectoryNumber,note);
    db.update("INSERT INTO drilling_trajectory_revision(survey_version_id,revision_type,parent_version_id,changed_from_md,changed_to_md,summary_json) VALUES(?,'FULL',?,?,?,?)",created,versionId,stations.get(0).get("md"),max,JSON.toJSONString(Map.of("revision_scope",revisionType,"station_count",stations.size())));
    trajectories.setHead(bid,kind,created,well);
    audit(fieldId,"EDIT_TRAJECTORY_TABLE",Map.of("versionId",created,"parentVersionId",versionId,"trajectoryKind",kind,"stationCount",stations.size()));
    return version(fieldId,created);
  }

  private static double finite(Object value,String label){
    try { double number=value instanceof Number n?n.doubleValue():Double.parseDouble(Objects.toString(value,"")); if(!Double.isFinite(number))throw new NumberFormatException(); return number; }
    catch(Exception e){throw new IllegalArgumentException(label+"必须为有效数值");}
  }

  @GetMapping("/versions/{versionId}/compare")
  @RequiresPermissions("drilling:well:list")
  public Map<String, Object> compare(
      @PathVariable Long fieldId, @PathVariable Long versionId, @RequestParam Long otherId) {
    var a = version(fieldId, versionId);
    var b = version(fieldId, otherId);
    if (!a.get("wellbore_id").equals(b.get("wellbore_id"))
        || !a.get("trajectory_kind").equals(b.get("trajectory_kind")))
      throw new IllegalArgumentException("只能比较同一井眼和类型的版本");
    var old = new TreeMap<Double, Map<String, Object>>();
    var next = new TreeMap<Double, Map<String, Object>>();
    for (var r : (List<Map<String, Object>>) a.get("stations"))
      old.put(((Number) r.get("md")).doubleValue(), r);
    for (var r : (List<Map<String, Object>>) b.get("stations"))
      next.put(((Number) r.get("md")).doubleValue(), r);
    var depths = new TreeSet<Double>(old.keySet());
    depths.addAll(next.keySet());
    List<Map<String, Object>> changes = new ArrayList<>();
    for (double md : depths) {
      var x = old.get(md);
      var y = next.get(md);
      String type =
          x == null
              ? "ADDED"
              : y == null ? "REMOVED" : measurementsDiffer(x, y) ? "RESURVEY" : "UNCHANGED";
      if (!"UNCHANGED".equals(type)) {
        var row = new LinkedHashMap<String, Object>();
        row.put("md", md);
        row.put("change", type);
        row.put("before", x);
        row.put("after", y);
        changes.add(row);
      }
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("fromVersion", versionId);
    result.put("toVersion", otherId);
    result.put("changes", changes);
    Double first = changes.isEmpty() ? null : ((Number) changes.get(0).get("md")).doubleValue();
    Double previous = first == null ? null : depths.lower(first);
    result.put("affected_from_md", previous == null ? first : previous);
    result.put("affected_to_md", first == null || next.isEmpty() ? null : next.lastKey());
    result.put("added", changes.stream().filter(r -> "ADDED".equals(r.get("change"))).count());
    result.put("removed", changes.stream().filter(r -> "REMOVED".equals(r.get("change"))).count());
    result.put(
        "resurvey", changes.stream().filter(r -> "RESURVEY".equals(r.get("change"))).count());
    return result;
  }

  private boolean measurementsDiffer(Map<String, Object> a, Map<String, Object> b) {
    double da =
        Math.abs(
                ((Number) a.get("azimuth")).doubleValue()
                    - ((Number) b.get("azimuth")).doubleValue())
            % 360;
    return Math.abs(
                ((Number) a.get("inclination")).doubleValue()
                    - ((Number) b.get("inclination")).doubleValue())
            > 0.0000005
        || Math.min(da, 360 - da) > 0.0000005;
  }

  @GetMapping("/workspace/{task}")
  @RequiresPermissions("drilling:field:view")
  public Map<String, Object> draft(@PathVariable Long fieldId, @PathVariable String task) {
    read(fieldId);
    var rows =
        db.queryForList(
            "SELECT title,payload_json,update_time FROM drilling_workspace_draft WHERE field_id=?"
                + " AND user_id=? AND task_type=?",
            fieldId,
            ShiroUtils.getUserId(),
            task);
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }

  @PutMapping("/workspace/{task}")
  @RequiresPermissions("drilling:field:view")
  public void saveDraft(
      @PathVariable Long fieldId,
      @PathVariable String task,
      @RequestBody Map<String, Object> body) {
    write(fieldId);
    if (!Set.of("anticollision", "replanning", "prediction").contains(task))
      throw new IllegalArgumentException("无效任务");
    db.update(
        "INSERT INTO drilling_workspace_draft(field_id,user_id,task_type,title,payload_json)"
            + " VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " title=VALUES(title),payload_json=VALUES(payload_json),update_time=NOW()",
        fieldId,
        ShiroUtils.getUserId(),
        task,
        required(body, "title", 128),
        JSON.toJSONString(body.get("payload")));
  }

  @GetMapping("/wellbores/{wellboreId}/neighbors")
  @RequiresPermissions("drilling:well:list")
  public List<Map<String, Object>> neighbors(
      @PathVariable Long fieldId, @PathVariable Long wellboreId) {
    bore(fieldId, wellboreId);
    var rows = db.queryForList(
        """
        WITH available AS (
          SELECT tv.*,tv.trajectory_version_no AS version_no,
            COALESCE(sr.status,'PENDING') AS source_review_status
          FROM drilling_trajectory_version tv
          JOIN drilling_survey_version sv ON sv.survey_version_id=tv.survey_version_id
          LEFT JOIN drilling_source_document sd ON sd.source_id=(SELECT MAX(latest.source_id)
            FROM drilling_source_document latest WHERE latest.file_id=sv.source_file_id)
          LEFT JOIN drilling_source_review sr ON sr.source_id=sd.source_id
          WHERE tv.quality_status='READY'
        ), selected AS (
          SELECT b.wellbore_id,w.well_id,w.well_code,w.well_name,b.wellbore_code,b.wellbore_name,
            f.field_id,f.field_name,k.block_id,k.block_name,n.included AS selection_included,
            CASE WHEN av.survey_version_id IS NOT NULL THEN 1 ELSE 0 END has_actual,
            CASE WHEN n.version_policy='PINNED' AND pv.survey_version_id IS NOT NULL
              AND (av.survey_version_id IS NULL OR pv.trajectory_kind='ACTUAL') THEN 'PINNED'
              WHEN av.survey_version_id IS NOT NULL THEN 'CURRENT_ACTUAL' ELSE 'CURRENT_DESIGN' END version_policy,
            CASE WHEN n.version_policy='PINNED' AND pv.survey_version_id IS NOT NULL
              AND (av.survey_version_id IS NULL OR pv.trajectory_kind='ACTUAL') THEN pv.survey_version_id
              ELSE COALESCE(av.survey_version_id,dv.survey_version_id) END survey_version_id
          FROM drilling_field_wellbore x
          JOIN drilling_wellbore b ON b.wellbore_id=x.wellbore_id
          JOIN drilling_well w ON w.well_id=b.well_id
          LEFT JOIN drilling_well_catalog wc ON wc.well_id=w.well_id
          LEFT JOIN drilling_field f ON f.field_id=wc.field_id
          LEFT JOIN drilling_block k ON k.block_id=f.block_id
          LEFT JOIN drilling_neighbor_selection n ON n.field_id=x.field_id
            AND n.reference_wellbore_id=? AND n.neighbor_wellbore_id=b.wellbore_id
          LEFT JOIN drilling_trajectory_head ah ON ah.wellbore_id=b.wellbore_id AND ah.trajectory_kind='ACTUAL'
          LEFT JOIN drilling_trajectory_head dh ON dh.wellbore_id=b.wellbore_id AND dh.trajectory_kind='DESIGN'
          LEFT JOIN available av ON av.survey_version_id=ah.survey_version_id
          LEFT JOIN available dv ON dv.survey_version_id=dh.survey_version_id
          LEFT JOIN available pv ON pv.survey_version_id=n.pinned_version_id
          WHERE x.field_id=? AND b.wellbore_id<>? AND w.status<>'ARCHIVED'
            AND LEFT(w.well_code,17)<>'__ARCHIVED_ALIAS_' AND b.engineering_status<>'ABANDONED'
            AND NOT EXISTS(SELECT 1 FROM drilling_engineering_node en WHERE en.field_id=w.field_id
              AND en.well_id=w.well_id AND en.node_type='WELL'
              AND en.entity_kind='PLANNED_RELIEF' AND en.status='ACTIVE')
        )
        SELECT c.*,v.trajectory_kind,v.version_no,v.quality_status,v.source_review_status
        FROM selected c JOIN available v ON v.survey_version_id=c.survey_version_id
        ORDER BY c.block_name,c.field_name,c.well_name,c.wellbore_name
        """, wellboreId, fieldId, wellboreId);
    var heads = db.queryForList("SELECT h.survey_version_id FROM drilling_trajectory_head h "
        + "JOIN drilling_trajectory_version v ON v.survey_version_id=h.survey_version_id "
        + "WHERE h.wellbore_id=? AND v.quality_status='READY' ORDER BY h.trajectory_kind", wellboreId);
    if (heads.isEmpty()) return List.of();
    var reference = trajectoryInputs.version(fieldId,id(heads.get(0).get("survey_version_id")));
    var target = obj(reference.get("reference"));
    var defaults = DrillingAlgorithmDefaults.merged(engine.config(fieldId).get("algorithm_defaults_json"));
    // The active version is the coordinate authority for neighbor discovery.
    // Discover against the entire known drilling corridor (actual plus its design continuation).
    // Each neighbor still contributes exactly one actual-preferred trajectory, never both versions.
    Double[] refBounds = null;
    for (var head : heads) {
      Long headId=id(head.get("survey_version_id"));
      var headVersion=trajectoryInputs.version(fieldId,headId);
      if(TrajectoryReferenceFrames.incompatibility(obj(headVersion.get("reference")),target)==null)
        refBounds=union(refBounds,bounds(fieldId,headId,target));
    }
    double margin = NeighborSelectionPolicy.automaticMargin(defaults);
    double radius = NeighborSelectionPolicy.manualRadius(defaults);
    List<Map<String,Object>> filtered = new ArrayList<>();
    for (var row : rows) {
      Long selectedVersion = id(row.get("survey_version_id"));
      boolean explicit = row.get("selection_included") != null;
      boolean manuallyIncluded = Boolean.TRUE.equals(row.get("selection_included"))
          || "1".equals(Objects.toString(row.get("selection_included"), ""))
          || row.get("selection_included") instanceof Number n && n.intValue() != 0;
      var candidateVersion=trajectoryInputs.version(fieldId,selectedVersion);
      String frameIssue=TrajectoryReferenceFrames.incompatibility(obj(candidateVersion.get("reference")),target);
      if(frameIssue!=null){
        row.put("coordinate_compatible",false);row.put("evaluation_message",frameIssue);
        row.put("requested_included",explicit&&manuallyIncluded);
        row.put("usable",false);row.put("included",false);row.put("auto_candidate",false);
        row.put("relationship_confirmed",explicit);row.put("relationship_source",explicit?"MANUAL":"AVAILABLE");
        row.put("within_search_range",false);row.put("manual_search_radius_m",radius);
        row.remove("selection_included");filtered.add(row);continue;
      }
      row.put("coordinate_compatible",true);
      Double[] candidateBounds = bounds(fieldId,selectedVersion,target);
      boolean usable = "READY".equals(Objects.toString(row.get("quality_status"), ""))
          && "CONFIRMED".equals(Objects.toString(row.get("source_review_status"), ""));
      var decision = NeighborSelectionPolicy.evaluate(refBounds, candidateBounds, margin, radius,
          explicit ? manuallyIncluded : null, usable);
      if (!decision.visible()) continue;
      row.put("usable", usable && decision.inRange());
      row.put("included", decision.included());
      row.put("auto_candidate", decision.automatic());
      row.put("relationship_confirmed", decision.confirmed());
      row.put("relationship_source", explicit ? "MANUAL" : decision.automatic() ? "AUTO" : "AVAILABLE");
      row.put("within_search_range", decision.inRange());
      row.put("horizontal_box_distance_m", decision.distance());
      row.put("candidate_search_radius_m", margin);
      row.put("manual_search_radius_m", radius);
      row.remove("selection_included");
      filtered.add(row);
    }
    return filtered;
  }

  private final Map<String,Double[]> horizontalBoundsCache = Collections.synchronizedMap(
      new LinkedHashMap<String,Double[]>(128,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Double[]> entry) { return size()>512; }
      });

  private static Double[] union(Double[] a,Double[] b) {
    if(a==null)return b;if(b==null)return a;
    return new Double[]{Math.min(a[0],b[0]),Math.max(a[1],b[1]),Math.min(a[2],b[2]),Math.max(a[3],b[3])};
  }

  private Double[] bounds(Long fieldId,Long versionId,Map<String,Object> target) {
    if(versionId==null)return null;
    // Authorization is always checked, including cache hits. Versions are immutable.
    read(fieldId);
    String key=versionId+":"+NativeEngineSupport.hash(JSON.toJSONString(target,
        com.alibaba.fastjson.serializer.SerializerFeature.MapSortField));
    var cached=horizontalBoundsCache.get(key);
    if(cached!=null)return cached;
    var version=trajectoryInputs.version(fieldId,versionId);
    var rows=(List<Map<String,Object>>)version.get("stations");
    if(rows.isEmpty())return null;
    var source=obj(version.get("reference"));
    source.put("north_reference","GRID_NORTH");source.put("length_unit","m");source.put("angle_unit","deg");
    for(String axis:List.of("north","east","tvd"))source.put("origin_"+axis,rows.get(0).get(axis));
    var response=engine.json("/survey/horizontal-bounds",Map.of("rows",rows,"reference",source,"target_reference",target));
    var values=(List<Number>)response.get("bounds");
    var result=new Double[]{values.get(0).doubleValue(),values.get(1).doubleValue(),values.get(2).doubleValue(),values.get(3).doubleValue()};
    horizontalBoundsCache.put(key,result);
    return result;
  }

  @PutMapping("/wellbores/{wellboreId}/neighbors")
  @RequiresPermissions("drilling:well:edit")
  @Transactional
  public void selectNeighbors(
      @PathVariable Long fieldId,
      @PathVariable Long wellboreId,
      @RequestBody List<Map<String, Object>> rows) {
    write(fieldId);
    bore(fieldId, wellboreId);
    if (rows.size() > 200) throw new IllegalArgumentException("最多200个邻井井眼");
    // Use the same geometric/range policy on writes; a crafted request cannot
    // select a trajectory that the neighbour tree does not permit.
    var allowed = new HashMap<Long, Map<String, Object>>();
    for (var neighbor : neighbors(fieldId, wellboreId))
      allowed.put(id(neighbor.get("wellbore_id")), neighbor);
    for (var r : rows) {
      Long n = id(r.get("wellboreId"));
      bore(fieldId, n);
      if (n.equals(wellboreId)) throw new IllegalArgumentException("不能选择当前井眼");
      boolean included = Boolean.TRUE.equals(r.get("included"));
      if (included && (allowed.get(n) == null || !Boolean.TRUE.equals(allowed.get(n).get("usable"))))
        throw new IllegalArgumentException("邻井超出搜索范围或没有可用轨迹版本");
      String policy = text(r, "versionPolicy", "CURRENT_ACTUAL");
      if(!Set.of("CURRENT_ACTUAL","CURRENT_DESIGN","PINNED").contains(policy))
        throw new IllegalArgumentException("无效邻井版本策略");
      boolean hasActual=db.queryForObject("SELECT COUNT(*) FROM drilling_trajectory_head h "
          + "JOIN drilling_trajectory_version t ON t.survey_version_id=h.survey_version_id "
          + "JOIN drilling_survey_version sv ON sv.survey_version_id=t.survey_version_id "
          + "JOIN drilling_source_review sr ON sr.source_id=(SELECT MAX(sd.source_id) FROM drilling_source_document sd WHERE sd.file_id=sv.source_file_id) "
          + "WHERE h.wellbore_id=? AND h.trajectory_kind='ACTUAL' AND t.quality_status='READY' AND sr.status='CONFIRMED'",Integer.class,n)>0;
      if(hasActual && "CURRENT_DESIGN".equals(policy))policy="CURRENT_ACTUAL";
      Long pinned = id(r.get("pinnedVersionId"));
      if ("PINNED".equals(policy)) {
        var pv = version(fieldId, pinned);
        if(hasActual && !"ACTUAL".equals(pv.get("trajectory_kind")))
          throw new IllegalArgumentException("邻井已有可用实钻轨迹，请选择实钻版本");
        if (!n.equals(id(pv.get("wellbore_id")))) throw new IllegalArgumentException("固定版本与邻井井眼不符");
        if (included) {
          var heads = db.queryForList("SELECT h.survey_version_id FROM drilling_trajectory_head h "
              + "JOIN drilling_trajectory_version v ON v.survey_version_id=h.survey_version_id "
              + "WHERE h.wellbore_id=? AND v.quality_status='READY' ORDER BY h.trajectory_kind", wellboreId);
          if (heads.isEmpty()) throw new IllegalArgumentException("当前井眼没有可用轨迹版本");
          var target = obj(trajectoryInputs.version(fieldId, id(heads.get(0).get("survey_version_id"))).get("reference"));
          Double[] referenceBounds = null;
          for (var head : heads) referenceBounds = union(referenceBounds, bounds(fieldId, id(head.get("survey_version_id")), target));
          var defaults = DrillingAlgorithmDefaults.merged(engine.config(fieldId).get("algorithm_defaults_json"));
          var pinnedRange = NeighborSelectionPolicy.evaluate(referenceBounds, bounds(fieldId, pinned, target),
              NeighborSelectionPolicy.automaticMargin(defaults), NeighborSelectionPolicy.manualRadius(defaults), true,
              "READY".equals(pv.get("quality_status")) && "CONFIRMED".equals(pv.get("source_review_status")));
          if (!pinnedRange.included()) throw new IllegalArgumentException("所选历史版本超出搜索范围或不可用");
        }
      } else pinned = null;
      db.update(
          "INSERT INTO"
              + " drilling_neighbor_selection(field_id,reference_wellbore_id,neighbor_wellbore_id,included,version_policy,pinned_version_id)"
              + " VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
              + " included=VALUES(included),version_policy=VALUES(version_policy),pinned_version_id=VALUES(pinned_version_id)",
          fieldId,
          wellboreId,
          n,
          included,
          policy,
          pinned);
    }
    audit(fieldId, "SELECT_NEIGHBORS", rows);
  }

  @GetMapping("/engine-confirmation")
  @RequiresPermissions("drilling:field:view")
  public Map<String, Object> confirmation(@PathVariable Long fieldId) {
    var r = new LinkedHashMap<>(engine.confirmation(fieldId));
    r.remove("sessionHash");
    return r;
  }

  @PostMapping("/engine-confirmation")
  @RequiresPermissions("drilling:field:view")
  public void confirm(@PathVariable Long fieldId, @RequestBody Map<String, Object> body) {
    write(fieldId);
    engine.confirm(fieldId, required(body, "configHash", 64));
  }
}
