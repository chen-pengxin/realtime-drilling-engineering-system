package com.ruoyi.web.controller.drilling;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.framework.security.DrillingDataScope;
import java.util.*;
import org.apache.shiro.authz.UnauthorizedException;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Read individual wellbores without granting access to sibling assets at the field. */
@RestController
@RequestMapping("/api/drilling/scoped-surveys")
public class ScopedSurveyController {
    private final DrillingDataScope scope;
    private final JdbcTemplate db;
    public ScopedSurveyController(DrillingDataScope scope,JdbcTemplate db){this.scope=scope;this.db=db;}

    private List<Map<String,Object>> limited() {
        var full = new HashSet<>(db.queryForList("SELECT f.field_id FROM drilling_field f WHERE "+scope.predicate("f.field_id"),Long.class));
        return scope.actualScope(ShiroUtils.getUserId()).stream().filter(r->r.get("boreId")!=null && !full.contains(((Number)r.get("fieldId")).longValue())).toList();
    }

    @GetMapping
    @RequiresPermissions("drilling:well:list")
    public AjaxResult list(){return AjaxResult.success(limited());}

    @GetMapping("/{bore}/versions")
    @RequiresPermissions("drilling:well:list")
    public AjaxResult versions(@PathVariable long bore){
        require(bore);
        return AjaxResult.success(db.queryForList("SELECT survey_version_id AS id,trajectory_kind AS kind,trajectory_version_no AS version FROM drilling_trajectory_version WHERE wellbore_id=? ORDER BY trajectory_kind,trajectory_version_no DESC",bore));
    }

    private void require(long bore){
        if(limited().stream().noneMatch(r->((Number)r.get("boreId")).longValue()==bore)) throw new UnauthorizedException("无权读取该井眼");
    }

    @GetMapping("/{bore}/versions/{version}")
    @RequiresPermissions("drilling:well:list")
    public AjaxResult stations(@PathVariable long bore,@PathVariable long version,@RequestParam(defaultValue="1") int page){
        require(bore);
        var rows=db.queryForList("SELECT s.md,s.inclination,s.azimuth,s.north,s.east,s.tvd FROM drilling_survey_station s JOIN drilling_trajectory_version v ON v.survey_version_id=s.survey_version_id WHERE v.wellbore_id=? AND v.survey_version_id=? ORDER BY s.md LIMIT 101 OFFSET ?",bore,version,(long)(Math.max(page,1)-1)*100);
        return AjaxResult.success(Map.of("rows",rows.subList(0,Math.min(100,rows.size())),"more",rows.size()>100));
    }
}
