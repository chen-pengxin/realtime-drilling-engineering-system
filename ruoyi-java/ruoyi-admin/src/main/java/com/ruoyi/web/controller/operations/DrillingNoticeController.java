package com.ruoyi.web.controller.operations;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.system.domain.SysNotice;
import com.ruoyi.system.service.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class DrillingNoticeController {
  private final OperationsSupport ops;
  private final ISysNoticeService notices;
  private final ISysNoticeReadService reads;
  private final ISysDictTypeService dictionaries;

  public DrillingNoticeController(
      OperationsSupport ops,
      ISysNoticeService notices,
      ISysNoticeReadService reads,
      ISysDictTypeService dictionaries) {
    this.ops = ops;
    this.notices = notices;
    this.reads = reads;
    this.dictionaries = dictionaries;
  }

  @GetMapping("/api/reference/settings")
  public AjaxResult settings() {
    return AjaxResult.success(ops.settings());
  }

  @GetMapping("/api/reference/dictionaries/{type}")
  public AjaxResult dictionary(@PathVariable String type) {
    if (!type.matches("[a-z][a-z0-9_]{1,99}")) throw new IllegalArgumentException("字典编码无效");
    var t = dictionaries.selectDictTypeByType(type);
    if (t == null || !"0".equals(t.getStatus())) return AjaxResult.success(List.of());
    return AjaxResult.success(dictionaries.selectDictDataByType(type));
  }

  @GetMapping("/api/notices")
  public AjaxResult inbox(@RequestParam Map<String, String> p) {
    List<Object> a = new ArrayList<>(List.of(ShiroUtils.getUserId()));
    StringBuilder w =
        new StringBuilder(
            "FROM sys_notice n LEFT JOIN sys_notice_read r ON r.notice_id=n.notice_id AND"
                + " r.user_id=? WHERE n.status='0'");
    OperationsSupport.like(p, "keyword", "n.notice_title", w, a);
    if ("true".equals(p.get("unreadOnly"))) w.append(" AND r.read_id IS NULL");
    AjaxResult result =
        ops.pageQuery(
            "SELECT n.notice_id AS noticeId,n.notice_title AS noticeTitle,n.notice_type AS"
                + " noticeType,n.create_by AS createBy,n.create_time AS createTime,IF(r.read_id IS"
                + " NULL,FALSE,TRUE) AS isRead",
            w.toString(),
            a,
            "ORDER BY n.notice_id DESC",
            p);
    result.put("unreadCount", reads.selectUnreadCount(ShiroUtils.getUserId()));
    return result;
  }

  private SysNotice published(Long id) {
    SysNotice n = notices.selectNoticeById(id);
    if (n == null || !"0".equals(n.getStatus())) throw new IllegalArgumentException("公告不存在或已撤回");
    return n;
  }

  @GetMapping("/api/notices/{id}")
  public AjaxResult detail(@PathVariable Long id) {
    return AjaxResult.success(published(id));
  }

  @PostMapping("/api/notices/{id}/read")
  public AjaxResult read(@PathVariable Long id) {
    published(id);
    reads.markRead(id, ShiroUtils.getUserId());
    return AjaxResult.success();
  }

  @PostMapping("/api/notices/read-all")
  public AjaxResult readAll() {
    ops.jdbc.update(
        "INSERT IGNORE INTO sys_notice_read(notice_id,user_id,read_time) SELECT notice_id,?,NOW()"
            + " FROM sys_notice WHERE status='0'",
        ShiroUtils.getUserId());
    return AjaxResult.success();
  }

  @GetMapping("/api/operations/notices")
  public AjaxResult list(@RequestParam Map<String, String> p) {
    ops.admin();
    List<Object> a = new ArrayList<>();
    StringBuilder w = new StringBuilder("FROM sys_notice n WHERE 1=1");
    OperationsSupport.like(p, "keyword", "n.notice_title", w, a);
    String s = p.get("status");
    if (s != null && !s.isBlank()) {
      if (!Set.of("0", "1").contains(s)) throw new IllegalArgumentException("公告状态无效");
      w.append(" AND n.status=?");
      a.add(s);
    }
    return ops.pageQuery(
        "SELECT n.notice_id AS noticeId,n.notice_title AS noticeTitle,n.notice_type AS"
            + " noticeType,n.status,n.create_by AS createBy,n.create_time AS createTime,(SELECT"
            + " COUNT(*) FROM sys_notice_read r WHERE r.notice_id=n.notice_id) AS readCount",
        w.toString(),
        a,
        "ORDER BY n.notice_id DESC",
        p);
  }

  @GetMapping("/api/operations/notices/{id}")
  public AjaxResult edit(@PathVariable Long id) {
    ops.admin();
    SysNotice n = notices.selectNoticeById(id);
    if (n == null) throw new IllegalArgumentException("公告不存在");
    return AjaxResult.success(n);
  }

  private SysNotice body(Map<String, Object> b, Long id) {
    SysNotice n = new SysNotice();
    n.setNoticeId(id);
    n.setNoticeTitle(OperationsSupport.text(b, "noticeTitle", 50, true));
    String t = OperationsSupport.text(b, "noticeType", 1, true);
    if (!Set.of("1", "2").contains(t)) throw new IllegalArgumentException("公告类型无效");
    n.setNoticeType(t);
    n.setStatus(OperationsSupport.state(b));
    String content = OperationsSupport.text(b, "noticeContent", 20000, true);
    n.setNoticeContent(content);
    n.setRemark(OperationsSupport.text(b, "remark", 255, false));
    return n;
  }

  @PostMapping("/api/operations/notices")
  public AjaxResult add(@RequestBody Map<String, Object> b) {
    ops.admin();
    SysNotice n = body(b, null);
    n.setCreateBy(ShiroUtils.getLoginName());
    notices.insertNotice(n);
    return AjaxResult.success(Map.of("noticeId", n.getNoticeId()));
  }

  @PutMapping("/api/operations/notices/{id}")
  @Transactional
  public AjaxResult update(@PathVariable Long id, @RequestBody Map<String, Object> b) {
    ops.admin();
    SysNotice old = notices.selectNoticeById(id);
    if (old == null) throw new IllegalArgumentException("公告不存在");
    SysNotice n = body(b, id);
    n.setUpdateBy(ShiroUtils.getLoginName());
    notices.updateNotice(n);
    if (!Objects.equals(old.getNoticeTitle(), n.getNoticeTitle())
        || !Objects.equals(old.getNoticeContent(), n.getNoticeContent()))
      reads.deleteByNoticeIds(id.toString());
    return AjaxResult.success();
  }

  @DeleteMapping("/api/operations/notices/{id}")
  @Transactional
  public AjaxResult remove(@PathVariable Long id) {
    ops.admin();
    reads.deleteByNoticeIds(id.toString());
    notices.deleteNoticeByIds(id.toString());
    return AjaxResult.success();
  }

  @GetMapping("/api/operations/notices/{id}/readers")
  public AjaxResult readers(@PathVariable Long id, @RequestParam Map<String, String> p) {
    ops.admin();
    if (notices.selectNoticeById(id) == null) throw new IllegalArgumentException("公告不存在");
    return ops.pageQuery(
        "SELECT u.login_name AS loginName,u.user_name AS userName,r.read_time AS readTime",
        "FROM sys_notice_read r JOIN sys_user u ON u.user_id=r.user_id WHERE r.notice_id=?",
        new ArrayList<>(List.of(id)),
        "ORDER BY r.read_time DESC",
        p);
  }
}
