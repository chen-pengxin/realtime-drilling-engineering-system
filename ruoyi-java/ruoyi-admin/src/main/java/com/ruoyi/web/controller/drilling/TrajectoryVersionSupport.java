package com.ruoyi.web.controller.drilling;

import com.alibaba.fastjson.JSON;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Shares version heads with the legacy import/realtime APIs without changing old snapshots. */
@Service
public class TrajectoryVersionSupport {
  private final JdbcTemplate db;

  public TrajectoryVersionSupport(SourceRegistrySupport registry) {
    db = registry.jdbc;
  }

  public void lockWell(Long well) {
    if (db.queryForList("SELECT well_id FROM drilling_well WHERE well_id=? FOR UPDATE", well)
        .isEmpty()) throw new IllegalArgumentException("井不存在");
  }

  public Long head(Long bore, String kind) {
    return db.query(
        "SELECT survey_version_id FROM drilling_trajectory_head WHERE wellbore_id=? AND"
            + " trajectory_kind=? FOR UPDATE",
        rs -> rs.next() ? rs.getLong(1) : null,
        bore,
        kind);
  }

  /** Call under the owning well lock in the same transaction as the insert. */
  public int nextNumber(Long bore, String kind) {
    var latest=db.queryForList("SELECT trajectory_version_no FROM drilling_trajectory_version WHERE wellbore_id=? AND trajectory_kind=? ORDER BY trajectory_version_no DESC LIMIT 1 FOR UPDATE",Integer.class,bore,kind);
    return latest.isEmpty()?1:latest.get(0)+1;
  }

  /** Internal storage sequence; never shown as the public trajectory number. */
  public int nextSnapshotNumber(Long well) {
    var latest=db.queryForList("SELECT version_no FROM drilling_survey_version WHERE well_id=? ORDER BY version_no DESC LIMIT 1 FOR UPDATE",Integer.class,well);
    return latest.isEmpty()?1:latest.get(0)+1;
  }

  /** Call under the well lock, inside the same transaction as the version write. */
  public void setHead(Long bore, String kind, Long version, Long well) {
    Integer valid =
        db.queryForObject(
            "SELECT COUNT(*) FROM drilling_trajectory_version t JOIN drilling_survey_version s ON"
                + " s.survey_version_id=t.survey_version_id WHERE t.survey_version_id=? AND"
                + " t.wellbore_id=? AND t.trajectory_kind=? AND s.well_id=?",
            Integer.class,
            version,
            bore,
            kind,
            well);
    if (valid == null || valid != 1) throw new IllegalArgumentException("当前版本与井眼/轨道类型不符");
    db.update(
        "INSERT INTO drilling_trajectory_head(wellbore_id,trajectory_kind,survey_version_id)"
            + " VALUES(?,?,?) ON DUPLICATE KEY UPDATE survey_version_id=VALUES(survey_version_id)",
        bore,
        kind,
        version);
    // is_current remains the selected actual bore for legacy realtime clients.
    // Other bores' independent ACTUAL/DESIGN heads are never removed.
    if ("ACTUAL".equals(kind)) {
      db.update(
          "UPDATE drilling_survey_version SET is_current='0' WHERE well_id=? AND is_current='1'",
          well);
      db.update(
          "UPDATE drilling_survey_version SET is_current='1' WHERE survey_version_id=?", version);
    }
  }

  /** Every old import/stream append must also be visible to the new version page. */
  public void registerActualSnapshot(Long version, Long parent, String mode, Double affectedFrom) {
    var snapshot =
        db.queryForMap("SELECT * FROM drilling_survey_version WHERE survey_version_id=?", version);
    Long well = SourceRegistryController.id(snapshot.get("well_id"));
    Long bore = null;
    Map<String, Object> reference = new LinkedHashMap<>();
    String quality = "REVIEW_REQUIRED";
    if (parent != null) {
      var inherited =
          db.queryForList(
              "SELECT wellbore_id,reference_json,quality_status FROM drilling_trajectory_version"
                  + " WHERE survey_version_id=? AND trajectory_kind='ACTUAL'",
              parent);
      if (!inherited.isEmpty()) {
        bore = SourceRegistryController.id(inherited.get(0).get("wellbore_id"));
        reference = SourceRegistryController.obj(inherited.get(0).get("reference_json"));
        if ("INCREMENTAL".equals(mode)) quality = inherited.get(0).get("quality_status").toString();
      }
    }
    if (bore == null) {
      var bores =
          db.queryForList("SELECT wellbore_id FROM drilling_wellbore WHERE well_id=?", well);
      if (bores.size() > 1) throw new IllegalArgumentException("同一井有多个井眼，请在井与测斜数据页面明确选择井眼后导入");
      if (bores.isEmpty()) {
        db.update(
            "INSERT IGNORE INTO drilling_well_catalog(well_id,field_id,identity_key) SELECT"
                + " w.well_id,p.field_id,w.well_code FROM drilling_well w JOIN drilling_field p"
                + " ON p.field_id=w.field_id WHERE w.well_id=?",
            well);
        db.update(
            "INSERT INTO drilling_wellbore(well_id,wellbore_code,wellbore_name,engineering_status)"
                + " SELECT well_id,'MAIN',well_name,'UNKNOWN' FROM drilling_well WHERE well_id=?",
            well);
        bore =
            db.queryForObject(
                "SELECT wellbore_id FROM drilling_wellbore WHERE well_id=?", Long.class, well);
      } else bore = SourceRegistryController.id(bores.get(0).get("wellbore_id"));
      reference.put("reference_status", "LEGACY_REVIEW_REQUIRED");
    }
    db.update(
        "INSERT IGNORE INTO drilling_field_wellbore(field_id,wellbore_id) SELECT field_id,?"
            + " FROM drilling_well WHERE well_id=?",
        bore,
        well);
    int publicNumber=nextNumber(bore,"ACTUAL");
    db.update(
        "INSERT INTO"
            + " drilling_trajectory_version(survey_version_id,wellbore_id,trajectory_kind,trajectory_version_no,content_sha256,reference_json,quality_status,label)"
            + " VALUES(?,?,'ACTUAL',?,?,?,?,?)",
        version,
        bore,
        publicNumber,
        NativeEngineSupport.hash("legacy:" + version),
        JSON.toJSONString(reference),
        quality,
        "实钻 v" + publicNumber);
    db.update(
        "INSERT INTO"
            + " drilling_trajectory_revision(survey_version_id,revision_type,parent_version_id,changed_from_md,changed_to_md)"
            + " VALUES(?,?,?,?,?)",
        version,
        mode,
        parent,
        affectedFrom,
        snapshot.get("max_md"));
    setHead(bore, "ACTUAL", version, well);
  }
}
