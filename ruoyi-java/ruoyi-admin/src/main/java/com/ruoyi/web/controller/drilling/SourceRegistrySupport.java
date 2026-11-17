package com.ruoyi.web.controller.drilling;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;

@Service
@DependsOn("businessCenterSupport")
public class SourceRegistrySupport {
  public final JdbcTemplate jdbc;
  public SourceRegistrySupport(JdbcTemplate jdbc) { this.jdbc=jdbc; }
  @PostConstruct public void initialize() {
    jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) c->{
      ScriptUtils.executeSqlScript(c,new ClassPathResource("drilling/source-registry.sql"));
      ScriptUtils.executeSqlScript(c,new ClassPathResource("drilling/trajectory-numbering.sql"));
      ScriptUtils.executeSqlScript(c,new ClassPathResource("drilling/legacy-trajectory-seed.sql"));
      ScriptUtils.executeSqlScript(c,new ClassPathResource("drilling/file-display-names.sql"));
      ScriptUtils.executeSqlScript(c,new ClassPathResource("drilling/document-content.sql")); return null;
    });
    Integer cols=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='anti_collision_task_well' AND index_name='PRIMARY'",Integer.class);
    if(cols!=null&&cols==2)jdbc.execute("ALTER TABLE anti_collision_task_well DROP PRIMARY KEY, ADD PRIMARY KEY(task_id,well_id,survey_version_id)");
  }
}
