package com.ruoyi.web.controller.drilling;

import com.alibaba.fastjson.JSON;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One engineering policy; survey reference frames and instrument budgets remain versioned. */
@Service
public class EngineeringConfigService {
  private final JdbcTemplate db;
  private volatile Map<String,Object> currentCache;
  public EngineeringConfigService(JdbcTemplate db) { this.db=db; }

  @jakarta.annotation.PostConstruct
  public void initialize() {
    db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) c -> {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
          new org.springframework.core.io.ClassPathResource("drilling/engineering-config.sql"));
      return null;
    });
    if (db.queryForObject("SELECT COUNT(*) FROM drilling_engineering_config",Integer.class)==0)
      db.update("INSERT INTO drilling_engineering_config(algorithm_defaults_json,create_by) VALUES(?,'system')",DrillingAlgorithmDefaults.snapshot(null));
    currentCache=null;
  }

  public Map<String,Object> current() {
    Map<String,Object> cached=currentCache;
    if (cached!=null) return new LinkedHashMap<>(cached);
    Map<String,Object> loaded=db.queryForMap("SELECT * FROM drilling_engineering_config ORDER BY config_version DESC LIMIT 1");
    currentCache=new LinkedHashMap<>(loaded);
    return new LinkedHashMap<>(loaded);
  }
  public Map<String,Object> defaults() { return DrillingAlgorithmDefaults.merged(current().get("algorithm_defaults_json")); }

  /** Coordinates are legacy field defaults only; numerical policy comes from the shared revision. */
  public Map<String,Object> effective(Map<String,Object> field) {
    var result=new LinkedHashMap<String,Object>(field);
    var common=current();
    result.put("engineering_config_version",common.get("config_version"));
    result.put("algorithm_defaults_json",DrillingAlgorithmDefaults.snapshot(common.get("algorithm_defaults_json")));
    return result;
  }

  @Transactional
  public Map<String,Object> save(Object defaults, Long expected, String by) {
    var active=db.queryForMap("SELECT * FROM drilling_engineering_config ORDER BY config_version DESC LIMIT 1 FOR UPDATE");
    if (expected==null || ((Number)active.get("config_version")).longValue()!=expected)
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"工程参数已更新，请刷新后重试");
    db.update("INSERT INTO drilling_engineering_config(algorithm_defaults_json,create_by) VALUES(?,?)",DrillingAlgorithmDefaults.snapshot(defaults),by);
    currentCache=null;
    return current();
  }

  @Transactional
  public void activateTrainedModel(Object previous,Long modelId) {
    var active=db.queryForMap("SELECT * FROM drilling_engineering_config ORDER BY config_version DESC LIMIT 1 FOR UPDATE");
    var defaults=DrillingAlgorithmDefaults.merged(active.get("algorithm_defaults_json"));
    var prediction=(Map<String,Object>)defaults.get("prediction");
    if (!Objects.equals(String.valueOf(prediction.get("modelVersionId")),String.valueOf(previous))) return;
    prediction.put("modelVersionId",modelId);
    db.update("INSERT INTO drilling_engineering_config(algorithm_defaults_json,create_by) VALUES(?,'incremental-learning')",JSON.toJSONString(defaults));
    currentCache=null;
  }
}
