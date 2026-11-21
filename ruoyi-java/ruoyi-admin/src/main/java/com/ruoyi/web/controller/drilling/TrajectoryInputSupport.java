package com.ruoyi.web.controller.drilling;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.ruoyi.framework.security.DrillingDataScope;

/** Binds every production calculation to immutable, authorized survey versions. */
@Service
public class TrajectoryInputSupport {
  private final JdbcTemplate db;
  private final NativeEngineSupport engine;
  /**
   * Survey reduction is deterministic for an immutable survey version and its
   * calculation reference.  Replanning repeatedly uses the same nine
   * neighbors, so keeping the reduced stations avoids another HTTP round trip
   * for every task.  The cache is bounded by coarse eviction; version IDs are
   * immutable and a new import receives a new ID.
   */
  private final ConcurrentMap<String, List<Map<String, Object>>> reducedCache =
      new ConcurrentHashMap<>();
  public TrajectoryInputSupport(JdbcTemplate db, NativeEngineSupport engine) { this.db=db; this.engine=engine; }

  public Map<String,Object> version(Long fieldId, Long version) {
    DrillingDataScope.current().requireAccess(fieldId);
    var rows=db.queryForList("""
      SELECT t.*,s.well_id,t.trajectory_version_no AS version_no FROM drilling_trajectory_version t
      JOIN drilling_survey_version s ON s.survey_version_id=t.survey_version_id
      JOIN drilling_field_wellbore x ON x.wellbore_id=t.wellbore_id
      WHERE x.field_id=? AND t.survey_version_id=?
      """,fieldId,version);
    if(rows.size()!=1)throw new IllegalArgumentException("所选轨迹版本不属于当前井场");
    var v=rows.get(0);
    // quality_status records import/calculation usability. It is not a
    // separate quality-management module, but the neighbor selector and
    // task pages expose only READY and source-confirmed versions.
    v.put("reference",SourceRegistryController.obj(v.get("reference_json")));
    v.put("stations",db.queryForList("SELECT md,inclination,azimuth,north,east,tvd FROM drilling_survey_station WHERE survey_version_id=? ORDER BY md",version));
    return v;
  }

  @SuppressWarnings("unchecked")
  public Map<String,Object> prepare(Map<String,Object> v, Map<String,Object> target) {
    return prepare(v,target,null);
  }

  @SuppressWarnings("unchecked")
  public Map<String,Object> prepare(Map<String,Object> v, Map<String,Object> target, Map<String,Object> defaults) {
    var rows=(List<Map<String,Object>>)v.get("stations");
    if(rows.size()<2)throw new IllegalArgumentException("轨迹至少需要两个测点");
    var ref=SourceRegistryController.obj(v.get("reference"));
    ref.put("north_reference","GRID_NORTH");ref.put("length_unit","m");ref.put("angle_unit","deg");
    for(var axis:List.of("north","east","tvd"))ref.put("origin_"+axis,rows.get(0).get(axis));
    var stations = reducedStations(v, rows, ref, target);
    var out=new LinkedHashMap<String,Object>();
    out.put("stations",stations);out.put("canonical_coordinates",true);
    if(defaults!=null)out.put("uncertainty",SurveyVersionMetadata.uncertainty(SourceRegistryController.obj(v.get("reference")),defaults));
    for(var axis:List.of("north","east","tvd"))out.put("origin_"+axis,stations.get(0).get(axis));
    return out;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String,Object>> reducedStations(Map<String,Object> version,
      List<Map<String,Object>> rows, Map<String,Object> reference, Map<String,Object> target) {
    Object id = version.get("survey_version_id");
    String key = String.valueOf(id) + ":" + NativeEngineSupport.hash(SourceRegistryController.obj(target).toString());
    var cached = reducedCache.get(key);
    if (cached != null) return copyStations(cached);

    // Imported trajectory versions are reduced once during ingestion and the
    // canonical N/E/TVD coordinates are stored with every station. Re-running
    // the FastAPI reduction endpoint for every replanning request adds a
    // network round trip per version (nine neighbors can cost several
    // seconds). A READY reference plus complete stored coordinates is already
    // the same minimum-curvature result, so bind it directly and cache it.
    Object referenceStatus = reference.get("reference_status");
    boolean canonical = "READY".equalsIgnoreCase(String.valueOf(referenceStatus))
        || "CONFIRMED".equalsIgnoreCase(String.valueOf(referenceStatus));
    if (canonical && TrajectoryReferenceFrames.sameFrame(reference,target) && rows.stream().allMatch(row -> row.get("north") != null
        && row.get("east") != null && row.get("tvd") != null)) {
      var immutable = copyStations(rows);
      if (reducedCache.size() >= 512) reducedCache.clear();
      reducedCache.putIfAbsent(key, immutable);
      return copyStations(immutable);
    }
    var reduced=engine.json("/survey/reduce",Map.of("rows",rows,"reference",reference,"target_reference",target,"preserve_stored",true,"include_table",false));
    var stations=(List<Map<String,Object>>)reduced.get("stations");
    var immutable = copyStations(stations);
    if (reducedCache.size() >= 512) reducedCache.clear();
    reducedCache.putIfAbsent(key, immutable);
    return copyStations(immutable);
  }

  private static List<Map<String,Object>> copyStations(List<Map<String,Object>> rows) {
    var copy = new ArrayList<Map<String,Object>>(rows.size());
    for (var row : rows) copy.add(new LinkedHashMap<>(row));
    return copy;
  }

  private List<Map<String,Object>> prepareNeighbors(List<Map<String,Object>> versions,
      Map<String,Object> target, Map<String,Object> defaults) {
    if (versions.isEmpty()) return List.of();
    var futures = new ArrayList<CompletableFuture<Map<String,Object>>>(versions.size());
    for (var version : versions)
      futures.add(CompletableFuture.supplyAsync(() -> prepare(version, target, defaults)));
    var result = new ArrayList<Map<String,Object>>(versions.size());
    for (var future : futures) {
      try { result.add(future.join()); }
      catch (CompletionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RuntimeException runtime) throw runtime;
        throw ex;
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private void appendPredictedExtension(Map<String,Object> prepared, Object requested, Map<String,Object> target) {
    if (!(requested instanceof Map<?,?> raw) || !(raw.get("stations") instanceof List<?> list) || list.isEmpty()) return;
    var actual=(List<Map<String,Object>>)prepared.get("stations");
    int measuredCount=actual.size();
    double last=((Number)actual.get(actual.size()-1).get("md")).doubleValue();
    double previous=last;
    for(Object item:list) {
      if (!(item instanceof Map<?,?> r) || r.get("md")==null) continue;
      double md=Double.parseDouble(String.valueOf(r.get("md")));
      // The client may supply only a future extension generated by a selected
      // model.  Stored measured history is always the authority.
      if (md<=last+1e-7) continue;
      if (md<=previous) throw new IllegalArgumentException("预测扩展测深必须严格递增");
      String source = r.get("source") == null ? "" : String.valueOf(r.get("source")).toUpperCase(Locale.ROOT);
      boolean trainedModelPoint = "TRAINED_MODEL".equals(source) || "PREDICTED_TRAINED_MODEL".equals(source);
      if (!(r.get("predicted") instanceof Boolean b && b) || !trainedModelPoint)
        throw new IllegalArgumentException("新增扫描点必须来自已配置模型的预测结果");
      for(String k:List.of("inclination","azimuth","north","east","tvd")) if(r.get(k)==null) throw new IllegalArgumentException("预测点缺少 "+k);
      var row=new LinkedHashMap<String,Object>();
      for(String k:List.of("md","inclination","azimuth","north","east","tvd")) row.put(k,r.get(k));
      row.put("source", "PREDICTED_TRAINED_MODEL"); row.put("predicted", true);
      actual.add(row); previous=md;
    }
    // Re-run the supplied minimum-curvature reducer on the complete sequence;
    // client coordinates are retained only as an audit input, never as the
    // authoritative future geometry.
    // Preserve the immutable measured prefix exactly. Only the future suffix
    // is reduced, anchored at the authoritative last measured coordinate.
    var anchor=actual.get(measuredCount-1);
    var extensionReference=new LinkedHashMap<String,Object>(target);
    extensionReference.put("north_reference","GRID_NORTH");
    extensionReference.put("length_unit","m");extensionReference.put("angle_unit","deg");
    for(String axis:List.of("north","east","tvd"))extensionReference.put("origin_"+axis,anchor.get(axis));
    var reduced=engine.json("/survey/reduce",Map.of("rows",new ArrayList<>(actual.subList(measuredCount-1,actual.size())),"reference",extensionReference,"preserve_stored",false));
    var nativeRows=(List<Map<String,Object>>)reduced.get("stations");
    var combined=new ArrayList<Map<String,Object>>(actual.subList(0,measuredCount));
    for(int i=1;i<nativeRows.size();i++) {
      var original=actual.get(measuredCount-1+i);
      nativeRows.get(i).put("source",original.get("source"));nativeRows.get(i).put("predicted",true);
      combined.add(nativeRows.get(i));
    }
    prepared.put("stations",combined);
  }

  @SuppressWarnings("unchecked")
  public void bind(String path, Map<String,Object> payload, Map<String,Object> defaults) {
    Long fieldId=SourceRegistryController.id(payload.get("field_id"));
    boolean planning=path.startsWith("/trajectory-plan/");
    var reference=version(fieldId,SourceRegistryController.id(payload.get(planning?"current_survey_version_id":"reference_survey_version_id")));
    var target=SourceRegistryController.obj(reference.get("reference"));
    // The selected version defines the common calculation frame. Never stamp
    // field defaults over a source CRS, convergence angle or datum elevation.
    var prepared=prepare(reference,target,defaults);
    // Realtime pages may append model-produced future points.  They are
    // accepted only after the immutable measured prefix is bound above.
    if (!planning) appendPredictedExtension(prepared, payload.get("reference"), target);
    List<Map<String,Object>> versions=new ArrayList<>();
    versions.add(snapshot("REFERENCE",reference,target,defaults));
    if(planning) {
      if(!"ACTUAL".equals(reference.get("trajectory_kind")))throw new IllegalArgumentException("再规划起点必须来自明确的实钻版本");
      payload.put("history",prepared);
      var stations=(List<Map<String,Object>>)prepared.get("stations");var last=stations.get(stations.size()-1);
      for(var k:List.of("md","north","east","tvd","inclination","azimuth"))payload.put("start_"+k,last.get(k));
      var selected=(List<Map<String,Object>>)payload.getOrDefault("obstacle_versions",List.of());
      if(!selected.isEmpty()) {
        var obstacleVersions=new ArrayList<Map<String,Object>>();
        for(var item:selected){var v=version(fieldId,SourceRegistryController.id(item.get("survey_version_id")));if(v.get("wellbore_id").equals(reference.get("wellbore_id")))throw new IllegalArgumentException("当前井眼不能作为自身邻井"); obstacleVersions.add(v); versions.add(snapshot("NEIGHBOR",v,target,defaults));}
        var preparedNeighbors = prepareNeighbors(obstacleVersions, target, defaults);
        var obstacles=new ArrayList<Map<String,Object>>(preparedNeighbors.size());
        for (int i=0; i<preparedNeighbors.size(); i++) { var obstacle=preparedNeighbors.get(i); var v=obstacleVersions.get(i); obstacle.put("well_id",v.get("well_id")); obstacle.put("survey_version_id",v.get("survey_version_id")); obstacles.add(obstacle); }
        payload.put("obstacles",obstacles);
      } else if(payload.get("obstacles") instanceof List<?> list && !list.isEmpty())throw new IllegalArgumentException("邻井约束必须提供明确的 obstacle_versions，不接受客户端自报轨迹");
    } else {
      payload.put("reference",prepared);
      if(path.endsWith("/network-scan")) {
        var neighbors=(List<Map<String,Object>>)payload.get("neighbors");var neighborVersions=new ArrayList<Map<String,Object>>();
        for(var item:neighbors){var v=version(fieldId,SourceRegistryController.id(item.get("survey_version_id")));if(v.get("wellbore_id").equals(reference.get("wellbore_id")))throw new IllegalArgumentException("不可扫描同一井眼的两个版本"); neighborVersions.add(v); versions.add(snapshot("NEIGHBOR",v,target,defaults));}
        var bound=new ArrayList<Map<String,Object>>();
        var preparedNeighbors = prepareNeighbors(neighborVersions, target, defaults);
        for (int i=0; i<preparedNeighbors.size(); i++) { var n=preparedNeighbors.get(i); var v=neighborVersions.get(i); n.put("well_id",v.get("well_id")); n.put("survey_version_id",v.get("survey_version_id")); bound.add(n); }
        payload.put("neighbors",bound);
      } else {var v=version(fieldId,SourceRegistryController.id(payload.get("neighbor_survey_version_id")));payload.put("neighbor",prepare(v,target,defaults));versions.add(snapshot("NEIGHBOR",v,target,defaults));}
    }
    // Keep immutable identities in the task input snapshot.  The numerical
    // geometry is still taken exclusively from the versions above.
    payload.put("input_versions", versions);
    payload.remove("obstacle_versions");
  }

  private Map<String,Object> snapshot(String role,Map<String,Object> version,Map<String,Object> target,Map<String,Object> defaults) {
    var out=new LinkedHashMap<String,Object>();
    out.put("role",role);out.put("wellbore_id",version.get("wellbore_id"));out.put("survey_version_id",version.get("survey_version_id"));
    out.put("trajectory_kind",version.get("trajectory_kind"));out.put("source_reference",version.get("reference"));
    out.put("calculation_reference",target);out.put("uncertainty",SurveyVersionMetadata.uncertainty(SourceRegistryController.obj(version.get("reference")),defaults));
    return out;
  }
}
