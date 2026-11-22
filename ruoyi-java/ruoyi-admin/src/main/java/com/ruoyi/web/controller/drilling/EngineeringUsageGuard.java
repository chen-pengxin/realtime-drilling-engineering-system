package com.ruoyi.web.controller.drilling;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Shared, fail-closed dependency checks for destructive engineering operations. */
final class EngineeringUsageGuard {
  private EngineeringUsageGuard(){}
  static Map<String,Object> file(JdbcTemplate db,Long fileId){
    int heads=count(db,"SELECT COUNT(*) FROM drilling_trajectory_head h JOIN drilling_survey_version s ON s.survey_version_id=h.survey_version_id WHERE s.source_file_id=?",fileId);
    int pinned=count(db,"SELECT COUNT(*) FROM drilling_neighbor_selection n JOIN drilling_survey_version s ON s.survey_version_id=n.pinned_version_id WHERE n.included=1 AND s.source_file_id=?",fileId);
    int evaluations=count(db,"SELECT COUNT(*) FROM anti_collision_task_well w JOIN drilling_survey_version s ON s.survey_version_id=w.survey_version_id WHERE s.source_file_id=?",fileId);
    int plans=count(db,"SELECT COUNT(*) FROM trajectory_plan p JOIN drilling_survey_version s ON s.survey_version_id=p.current_survey_version_id WHERE s.source_file_id=?",fileId);
    int predictions=count(db,"SELECT COUNT(*) FROM prediction_task p LEFT JOIN drilling_survey_version s ON s.survey_version_id=p.survey_version_id WHERE s.source_file_id=? OR p.input_file_id=?",fileId,fileId);
    String reason=heads>0?"文件关联当前轨迹版本，请先切换到可用版本":pinned>0?"文件关联邻井固定版本":evaluations+plans+predictions>0?"文件已用于防碰评价、预测或再规划，需保留原件及历史数据":"";
    return Map.of("currentVersions",heads,"neighborReferences",pinned,"evaluations",evaluations,"plans",plans,"predictions",predictions,"blocked",!reason.isBlank(),"reason",reason);
  }
  static void requireFileUnused(JdbcTemplate db,Long fileId){var use=file(db,fileId);if(Boolean.TRUE.equals(use.get("blocked")))throw new ResponseStatusException(HttpStatus.CONFLICT,use.get("reason")+"，禁止删除或停用");}
  static void requireBoreAvailable(JdbcTemplate db,Long bore){
    int links=count(db,"SELECT COUNT(*) FROM drilling_neighbor_selection WHERE included=1 AND (reference_wellbore_id=? OR neighbor_wellbore_id=?)",bore,bore);
    int active=count(db,"SELECT COUNT(*) FROM drilling_trajectory_head WHERE wellbore_id=? AND trajectory_kind='ACTUAL'",bore);
    int completed=count(db,"SELECT COUNT(*) FROM drilling_wellbore WHERE wellbore_id=? AND engineering_status='COMPLETED'",bore);
    if(completed+active>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"井眼已有实钻数据或已完钻，禁止删除、归档或废弃");
    if(links>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"井眼存在邻井关系，禁止删除、归档或废弃；请先处理相关引用");
  }
  static void requireStatusChange(JdbcTemplate db,Long bore,String oldStatus,String next){
    if(Objects.equals(oldStatus,next))return;
    if(Set.of("ABANDONED","UNPLANNED","PLANNED","UNKNOWN").contains(next))requireBoreAvailable(db,bore);
    int tasks=count(db,"SELECT (SELECT COUNT(*) FROM trajectory_plan WHERE well_id=(SELECT well_id FROM drilling_wellbore WHERE wellbore_id=?) AND status IN ('QUEUED','RUNNING','PENDING'))+(SELECT COUNT(*) FROM prediction_task WHERE well_id=(SELECT well_id FROM drilling_wellbore WHERE wellbore_id=?) AND status IN ('QUEUED','RUNNING','PENDING'))+(SELECT COUNT(*) FROM anti_collision_task t JOIN anti_collision_task_well w ON w.task_id=t.task_id JOIN drilling_trajectory_version v ON v.survey_version_id=w.survey_version_id WHERE v.wellbore_id=? AND t.status IN ('QUEUED','RUNNING','PENDING'))",bore,bore,bore);
    if(tasks>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"井眼仍有执行中的评价、预测或再规划任务，暂不能修改施工状态");
  }
  private static int count(JdbcTemplate db,String sql,Object...args){return Objects.requireNonNullElse(db.queryForObject(sql,Integer.class,args),0);}
}
