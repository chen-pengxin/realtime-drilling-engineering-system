package com.ruoyi.web.controller.system;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.framework.security.DrillingAccountPolicy;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Isolated presentation fixtures. Never queries engineering tables or calls the algorithm service. */
@RestController
@RequestMapping("/api/access/demo")
public class DrillingDemoController {
    @GetMapping("/{module}") public AjaxResult demo(@PathVariable String module) {
        if(!DrillingAccountPolicy.MODULES.contains(module)) throw new IllegalArgumentException("未知演示模块");
        Map<String,Object> result=new LinkedHashMap<>();result.put("module",module);result.put("title",DrillingAccountPolicy.LABELS.get(module));
        result.put("demoOnly",true);result.put("notice","独立演示数据，仅用于界面展示；不读取真实工程资料，不执行算法或数据写入。");
        double[][] samples={{0,0,0,0},{100,0,0,100},{200,1,2,200},{300,5,12,299},{400,12,35,396},{500,24,70,489},{600,42,119,574}};
        List<Map<String,Object>> points=new ArrayList<>(),neighbor=new ArrayList<>();
        for(double[] p:samples) {
            points.add(Map.of("md",p[0],"east",p[1],"north",p[2],"tvd",p[3]));
            neighbor.add(Map.of("md",p[0],"east",p[1]+35,"north",p[2]+18,"tvd",p[3]));
        }
        result.put("points",points);result.put("neighbor",neighbor);
        List<Map<String,Object>> rows=switch(module) {
            case "anticollision" -> List.of(Map.of("name","演示井 A / 演示邻井 B","value","中心距 39.4 m","status","示意数据","detail","MD 500～600 m；SF 1.62（仅用于演示显示）"));
            case "prediction" -> List.of(Map.of("name","预测状态","value","演示视图","status","未运行模型","detail","展示实测轨迹示意；模型推理与未来姿态字段不生成真实结果"));
            case "replanning" -> List.of(Map.of("name","绕障方案 A","value","三段式","status","未执行求解","detail","总长度 / 工具面 / 滑动比例仅在真实求解成功后产生"));
            case "models" -> List.of(Map.of("name","示例模型版本 v1","value","演示占位","status","不可导入 / 激活","detail","权重文件与自动增量训练参数仅由授权工程师管理"));
            case "catalog" -> List.of(Map.of("name","演示区块","value","演示井场 / 演示井 / 演示井眼","status","只读","detail","区块 → 井场 → 井 → 井眼四级目录示意"));
            case "realtime-data" -> List.of(Map.of("name","MWD 演示数据源","value","WITSML 2.0","status","演示连接","detail","不建立真实连接，不接收或写入测斜数据"));
            case "config" -> List.of(Map.of("name","扫描步长","value","1 m","status","演示默认值","detail","最小曲率法；SF阈值 1.5；ISCWSA误差模型（参数仅展示）"));
            case "files" -> List.of(Map.of("name","演示测斜数据.csv","value","示例文件","status","不可上传 / 下载","detail","不包含真实工程文件和审计记录"));
            case "alerts" -> List.of(Map.of("name","示例防碰告警","value","关注","status","仅演示","detail","告警确认、解除和再规划跳转仅在真实授权页面执行"));
            case "history" -> List.of(Map.of("name","示例任务记录","value","防碰评价 / 姿态预测 / 轨迹再规划 / 模型更新","status","只读演示","detail","不读取真实任务输入、配置快照或结果"));
            case "quality" -> List.of(Map.of("name","示例重复事件","value","测斜数据质量检查","status","隔离示意","detail","不修改测斜数据或执行问题处理"));
            case "reports" -> List.of(Map.of("name","示例工程报告","value","PDF / Word / Excel","status","不可生成或下载","detail","不包含真实工程数据"));
            case "connectors" -> List.of(Map.of("name","示例连接器","value","WITS TCP / WITSML / 文件 / SQL","status","演示状态","detail","不建立外部连接、不读取设备或数据库"));
            case "versions" -> List.of(Map.of("name","示例版本记录","value","配置 / 测斜 / 模型 / 方案","status","只读演示","detail","不读取真实版本或变更记录"));
            default -> List.of(Map.of("name","演示井 A","value","MD 600 m","status","演示数据","detail","测斜版本 v1；数据不来自实时钻井"));
        };
        result.put("rows",rows);return AjaxResult.success(result);
    }
}
