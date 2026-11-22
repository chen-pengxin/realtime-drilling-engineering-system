package com.ruoyi.web.controller.operations;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.utils.ShiroUtils;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.service.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operations")
public class OperationsCatalogController {
  private final OperationsSupport ops;
  private final ISysConfigService configs;
  private final ISysDictTypeService types;
  private final ISysDictDataService data;

  public OperationsCatalogController(
      OperationsSupport ops,
      ISysConfigService configs,
      ISysDictTypeService types,
      ISysDictDataService data) {
    this.ops = ops;
    this.configs = configs;
    this.types = types;
    this.data = data;
  }

  @PostMapping("/branding")
  public AjaxResult uploadBranding(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws Exception {
    ops.admin();
    return AjaxResult.success(Map.of("path",PlatformBranding.save(file)));
  }

  @GetMapping("/settings")
  public AjaxResult settings(@RequestParam Map<String, String> p) {
    ops.admin();
    List<Object> a = new ArrayList<>();
    StringBuilder w = new StringBuilder("FROM sys_config WHERE config_key LIKE 'drilling.%'");
    OperationsSupport.like(p, "keyword", "CONCAT(config_key,' ',config_name)", w, a);
    return ops.pageQuery(
        "SELECT config_id AS configId,config_name AS configName,config_key AS"
            + " configKey,config_value AS configValue,config_type AS configType,remark,update_time"
            + " AS updateTime",
        w.toString(),
        a,
        "ORDER BY config_id",
        p);
  }

  private void validateSetting(SysConfig c) {
    String k = c.getConfigKey(), v = c.getConfigValue();
    if (OperationsSupport.SETTINGS.contains(k)) {
      switch (k) {
        case "drilling.ui.density" -> choice(v,"small","default","large");
        case "drilling.ui.preset" -> choice(v,"light","ocean");
        case "drilling.ui.tags","drilling.ui.cache","drilling.ui.breadcrumb" -> choice(v,"true","false");
        case "drilling.ui.pageSize" -> choice(v,"10","20","50","100");
        case "drilling.ui.timezone" -> choice(v,"Asia/Shanghai","UTC");
        case "drilling.ui.dateStyle" -> choice(v,"zh-CN","en-GB");
        case "drilling.ui.home" -> choice(v,"/drilling/realtime","/user/profile");
        case "drilling.ui.logo","drilling.ui.loginImage","drilling.ui.favicon" -> {
          if (!v.equals("内置") && !v.matches("/branding/[a-zA-Z0-9_-]+[.](png|ico|jpg|webp)"))
            throw new IllegalArgumentException("请选择有效的平台图片");
        }
        case "drilling.backup.days" -> OperationsSupport.integer(v,30,1,365);
        case "drilling.login.captchaSeconds" -> OperationsSupport.integer(v,120,30,600);
        case "drilling.login.maxFailures" -> OperationsSupport.integer(v,5,1,10);
        case "drilling.system.name" -> {
          if (v.length() < 2 || v.length() > 30 || v.matches(".*[<>\\r\\n].*"))
            throw new IllegalArgumentException("系统名称须为2～30个字符，不含尖括号与换行");
        }
        case "drilling.session.timeoutMinutes" -> OperationsSupport.integer(v, 30, 5, 240);
        case "drilling.maintenance.retentionDays" -> OperationsSupport.integer(v, 30, 1, 365);
        case "drilling.monitor.refreshSeconds" -> OperationsSupport.integer(v, 30, 10, 300);
      }
    } else if (!k.matches("drilling\\.custom\\.[a-zA-Z][a-zA-Z0-9_.]{0,80}"))
      throw new IllegalArgumentException("自定义配置键须以drilling.custom.开头");
  }

  private static void choice(String value,String... valid) {
    if (!List.of(valid).contains(value)) throw new IllegalArgumentException("参数值不在可选范围内");
  }

  private SysConfig settingBody(Map<String, Object> b, Long id) {
    SysConfig c = new SysConfig();
    c.setConfigId(id);
    c.setConfigKey(OperationsSupport.text(b, "configKey", 100, true));
    c.setConfigName(OperationsSupport.text(b, "configName", 100, true));
    c.setConfigValue(OperationsSupport.text(b, "configValue", 500, true));
    c.setRemark(OperationsSupport.text(b, "remark", 500, false));
    c.setConfigType(OperationsSupport.SETTINGS.contains(c.getConfigKey()) ? "Y" : "N");
    validateSetting(c);
    if (!configs.checkConfigKeyUnique(c)) throw new IllegalArgumentException("配置键已存在");
    return c;
  }

  @PostMapping("/settings")
  public AjaxResult addSetting(@RequestBody Map<String, Object> b) {
    ops.admin();
    SysConfig c = settingBody(b, null);
    if (OperationsSupport.SETTINGS.contains(c.getConfigKey()))
      throw new IllegalArgumentException("内置配置已存在");
    c.setCreateBy(ShiroUtils.getLoginName());
    configs.insertConfig(c);
    return AjaxResult.success(Map.of("configId", c.getConfigId()));
  }

  @PutMapping("/settings/{id}")
  public AjaxResult updateSetting(@PathVariable Long id, @RequestBody Map<String, Object> b) {
    ops.admin();
    SysConfig old = configs.selectConfigById(id);
    if (old == null || !old.getConfigKey().startsWith("drilling."))
      throw new IllegalArgumentException("配置不存在");
    SysConfig c = settingBody(b, id);
    if (!old.getConfigKey().equals(c.getConfigKey())) throw new IllegalArgumentException("配置键不能修改");
    c.setUpdateBy(ShiroUtils.getLoginName());
    configs.updateConfig(c);
    return AjaxResult.success();
  }

  @DeleteMapping("/settings/{id}")
  public AjaxResult deleteSetting(@PathVariable Long id) {
    ops.admin();
    SysConfig c = configs.selectConfigById(id);
    if (c == null || !c.getConfigKey().startsWith("drilling.custom."))
      throw new IllegalArgumentException("内置配置不能删除");
    configs.deleteConfigByIds(id.toString());
    return AjaxResult.success();
  }

  @PostMapping("/settings/refresh")
  public AjaxResult refreshSettings() {
    ops.admin();
    configs.resetConfigCache();
    return AjaxResult.success();
  }

  @GetMapping("/dictionaries")
  public AjaxResult dictionaries(@RequestParam Map<String, String> p) {
    ops.admin();
    List<Object> a = new ArrayList<>();
    StringBuilder w = new StringBuilder("FROM sys_dict_type WHERE 1=1");
    OperationsSupport.like(p, "keyword", "CONCAT(dict_name,' ',dict_type)", w, a);
    return ops.pageQuery(
        "SELECT dict_id AS dictId,dict_name AS dictName,dict_type AS"
            + " dictType,status,remark,create_time AS createTime,IF(dict_type LIKE 'sys_%' OR"
            + " (dict_type='drilling_data_source' OR LEFT(dict_type,12)='drilling_ui_'),TRUE,FALSE) AS builtin",
        w.toString(), a, "ORDER BY dict_id", p);
  }

  private boolean builtin(String type) {
    return type.startsWith("sys_") || type.startsWith("drilling_ui_") || type.equals("drilling_data_source");
  }

  private SysDictType type(Long id) {
    SysDictType t = types.selectDictTypeById(id);
    if (t == null) throw new IllegalArgumentException("字典不存在");
    return t;
  }

  private SysDictType typeBody(Map<String, Object> b, Long id) {
    SysDictType t = new SysDictType();
    t.setDictId(id);
    t.setDictName(OperationsSupport.text(b, "dictName", 100, true));
    t.setDictType(OperationsSupport.text(b, "dictType", 100, true));
    if (!t.getDictType().matches("drilling_[a-z][a-z0-9_]{0,80}"))
      throw new IllegalArgumentException("新字典编码须以drilling_开头，使用小写字母、数字和下划线");
    t.setStatus(OperationsSupport.state(b));
    t.setRemark(OperationsSupport.text(b, "remark", 500, false));
    if (!types.checkDictTypeUnique(t)) throw new IllegalArgumentException("字典编码已存在");
    return t;
  }

  @PostMapping("/dictionaries")
  public AjaxResult addType(@RequestBody Map<String, Object> b) {
    ops.admin();
    SysDictType t = typeBody(b, null);
    if (builtin(t.getDictType())) throw new IllegalArgumentException("不能新增内置字典");
    t.setCreateBy(ShiroUtils.getLoginName());
    types.insertDictType(t);
    return AjaxResult.success(Map.of("dictId", t.getDictId()));
  }

  @PutMapping("/dictionaries/{id}")
  public AjaxResult updateType(@PathVariable Long id, @RequestBody Map<String, Object> b) {
    ops.admin();
    SysDictType old = type(id);
    if (builtin(old.getDictType())) throw new IllegalArgumentException("内置字典类型不可更改");
    SysDictType t = typeBody(b, id);
    if (!old.getDictType().equals(t.getDictType())) throw new IllegalArgumentException("字典编码不能修改");
    t.setUpdateBy(ShiroUtils.getLoginName());
    types.updateDictType(t);
    return AjaxResult.success();
  }

  @DeleteMapping("/dictionaries/{id}")
  public AjaxResult deleteType(@PathVariable Long id) {
    ops.admin();
    SysDictType t = type(id);
    if (builtin(t.getDictType())) throw new IllegalArgumentException("内置字典不能删除");
    if (ops.jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_dict_data WHERE dict_type=?", Integer.class, t.getDictType())
        > 0) throw new IllegalArgumentException("请先删除字典数据");
    types.deleteDictTypeByIds(id.toString());
    return AjaxResult.success();
  }

  @GetMapping("/dictionaries/{id}/items")
  public AjaxResult items(@PathVariable Long id, @RequestParam Map<String, String> p) {
    ops.admin();
    String type = type(id).getDictType();
    List<Object> a = new ArrayList<>(List.of(type));
    StringBuilder w = new StringBuilder("FROM sys_dict_data WHERE dict_type=?");
    OperationsSupport.like(p, "keyword", "CONCAT(dict_label,' ',dict_value)", w, a);
    return ops.pageQuery(
        "SELECT dict_code AS dictCode,dict_sort AS dictSort,dict_label AS dictLabel,dict_value AS"
            + " dictValue,dict_type AS dictType,status,is_default AS isDefault,remark",
        w.toString(),
        a,
        "ORDER BY dict_sort,dict_code",
        p);
  }

  private SysDictData itemBody(Map<String, Object> b, Long id, String type) {
    SysDictData d = new SysDictData();
    d.setDictCode(id);
    d.setDictType(type);
    d.setDictLabel(OperationsSupport.text(b, "dictLabel", 100, true));
    d.setDictValue(OperationsSupport.text(b, "dictValue", 100, true));
    d.setDictSort((long) OperationsSupport.integer(b.get("dictSort"), 0, 0, 9999));
    d.setStatus(OperationsSupport.state(b));
    d.setIsDefault("N");
    d.setRemark(OperationsSupport.text(b, "remark", 500, false));
    if (ops.jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_dict_data WHERE dict_type=? AND dict_value=? AND"
                + " dict_code<>?",
            Integer.class,
            type,
            d.getDictValue(),
            id == null ? -1 : id)
        > 0) throw new IllegalArgumentException("该字典的数据值已存在");
    return d;
  }

  @PostMapping("/dictionaries/{id}/items")
  public AjaxResult addItem(@PathVariable Long id, @RequestBody Map<String, Object> b) {
    ops.admin();
    String t = type(id).getDictType();
    if (builtin(t)) throw new IllegalArgumentException("固定编码字典不能增加数据");
    SysDictData d = itemBody(b, null, t);
    d.setCreateBy(ShiroUtils.getLoginName());
    data.insertDictData(d);
    return AjaxResult.success(Map.of("dictCode", d.getDictCode()));
  }

  @PutMapping("/dictionary-items/{id}")
  public AjaxResult updateItem(@PathVariable Long id, @RequestBody Map<String, Object> b) {
    ops.admin();
    SysDictData old = data.selectDictDataById(id);
    if (old == null) throw new IllegalArgumentException("字典数据不存在");
    if (old.getDictType().startsWith("sys_")) throw new IllegalArgumentException("系统字典仅供查看");
    SysDictData d = itemBody(b, id, old.getDictType());
    if (builtin(old.getDictType())
        && (!old.getDictValue().equals(d.getDictValue())))
      throw new IllegalArgumentException("固定字典仅可修改显示名称和排序");
    d.setUpdateBy(ShiroUtils.getLoginName());
    data.updateDictData(d);
    return AjaxResult.success();
  }

  @DeleteMapping("/dictionary-items/{id}")
  public AjaxResult deleteItem(@PathVariable Long id) {
    ops.admin();
    SysDictData d = data.selectDictDataById(id);
    if (d == null || builtin(d.getDictType())) throw new IllegalArgumentException("固定字典数据不能删除");
    data.deleteDictDataByIds(id.toString());
    return AjaxResult.success();
  }

  @PostMapping("/dictionaries/refresh")
  public AjaxResult refreshDict() {
    ops.admin();
    types.resetDictCache();
    return AjaxResult.success();
  }
}
