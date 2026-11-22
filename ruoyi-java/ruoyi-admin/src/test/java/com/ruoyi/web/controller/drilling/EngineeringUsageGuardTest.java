package com.ruoyi.web.controller.drilling;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataAccessResourceFailureException;
class EngineeringUsageGuardTest {
 static class Database extends JdbcTemplate {
   String match="";boolean fail;List<String> reads=new ArrayList<>();
   @Override public <T>T queryForObject(String sql,Class<T> type,Object...args){reads.add(sql);if(fail)throw new DataAccessResourceFailureException("不可用");return type.cast(!match.isEmpty()&&sql.contains(match)?1:0);}
 }
 @Test void currentNeighborAndHistoricalCalculationReferencesBlockFileRemoval(){
   for(String table:List.of("drilling_trajectory_head","drilling_neighbor_selection","anti_collision_task_well","trajectory_plan","prediction_task")){
     var db=new Database();db.match=table;
     assertThrows(ResponseStatusException.class,()->EngineeringUsageGuard.requireFileUnused(db,11L),table);
   }
 }
 @Test void unusedFilesPassAndDatabaseFailureDoesNotPermitDeletion(){var db=new Database();assertDoesNotThrow(()->EngineeringUsageGuard.requireFileUnused(db,11L));assertEquals(5,db.reads.size());db.fail=true;assertThrows(DataAccessResourceFailureException.class,()->EngineeringUsageGuard.requireFileUnused(db,11L));}
 @Test void boreWithHeadOrNeighborCannotBeDisabled(){for(String table:List.of("drilling_trajectory_head","drilling_neighbor_selection")){var db=new Database();db.match=table;assertThrows(ResponseStatusException.class,()->EngineeringUsageGuard.requireBoreAvailable(db,2L));}}
}
