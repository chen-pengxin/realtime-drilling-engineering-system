# 实时钻井系统技术实现契约

本文约束当前代码实现。工程层级只使用区块、井场、井和井眼；来源资料中的 `Project`、目录名和算法项目名只作为原始元数据，不生成工程节点或权限范围。

## 技术边界

前端使用 RuoYi Vue3 和 Element Plus，Java 使用 Spring Boot 多模块 Maven 工程，Python 使用 FastAPI。Java 负责登录、权限、工程树、来源文件、井眼版本、任务快照、配置确认、审计和报告归档；Python 负责最小曲率归算、ISCWSA/误差模型、连续防碰、原生轨迹求解和文档解析；MySQL 保存结构化事实，对象存储保存原件和模型包。

## 工程对象

| 对象 | 关系 | 系统用途 |
| --- | --- | --- |
| 区块 | 包含多个井场 | 地理/构造范围，例如“鄂尔多斯盆地伊陕斜坡” |
| 井场 | 属于一个区块，包含多个井 | 权限、参数和实时数据范围，例如“Volve F” |
| 井 | 属于一个井场，代表井口实体 | 井号和井名管理 |
| 井眼 | 属于一口井，可有主井眼和侧钻井眼 | 设计、实钻、预测、防碰和再规划的唯一业务对象 |

任务界面默认选择井眼，显示完整路径“区块 / 井场 / 井 / 井眼”。

## 轨迹和防碰

所有轨迹归算、插值、二维投影和再规划状态转移统一使用最小曲率法。防碰扫描默认 1 m 步长，先在水平面和 N-TVD 面用误差扩展闭包盒粗筛，再对候选井眼对执行连续中心距和分离系数计算。井间距法和分离系数法每次同时返回。

默认误差模型为 `iscwsa_rev0`，默认求解器为算法项目提供的 `SLSQP`。工程配置页面只显示当前模型实际需要的参数，并将不确定性放大系数 K 放在首项；分离系数区域只显示公式和参数含义，不显示内部方法名称。

再规划的碰撞约束固定为 `SEPARATION_FACTOR`。轨迹模式、段数和求解器来自 `well_control_iscwsa` 原生目录，支持圆弧、圆弧—直线和直线—圆弧；全局段长/曲率约束和分段约束二选一。测试案例 A、B、C 只读展示算法项目保存的输入和结果。

## 来源文件和导出

原件上传后按 SHA-256 去重保存，并记录来源路径、用途、井眼、解析表、工作表、页码、行号和字段映射。支持 Excel、CSV、TXT（含无后缀文本）、WITSML、DOC/DOCX 和可识别的 PDF 表格。

导出分为四类：

1. **原件下载**：直接返回入库字节，保证 Standard Survey Report、Well Summary Report、采气二厂 XLS 及其图片/公式/工作表和无后缀 TXT 与原件一致。
2. **来源格式修订导出**：对 XLSX、XLSM、DOCX、CSV/TXT 和 WITSML 按人工确认的 locator 回写选定轨迹表，保留未选中的内容。
3. **标准归算表**：生成最小曲率 MD、井斜、方位、N、E、TVD、狗腿度等工程表。
4. **版本资料包**：包含原件、CSV、标准归算 XLSX、三种 SVG 投影、解析结果和 manifest。

旧 XLS、DOC、WPS 和 PDF 不进行不安全的版式重建，提供原件和标准归算表。`SY/T 5435-2012` 已作为系统级参考资料登记，原件按 SHA-256 保存；附录 A 的 A.1–A.6 六个表格以页码和字段目录入库，扫描页内容以 PDF 原件为准，下载不做重排或重绘。

## 接口约束

Java 来源接口前缀为 `/api/drilling/fields/{fieldId}/registry`；算法接口包括 `/api/algorithm/source/inspect`、`/source/map`、`/source/export`、`/survey/reduce`、`/collision/network` 和 `/planning/solve`。任务请求必须携带井眼、具体版本、邻井版本和配置快照，禁止只传井名或可变文件路径。

## 数据库约束

运行库不保留 `drilling_project*` 表，也不保留 `project_id` 字段。来源诊断表只用于记录重复点、异常点和导入隔离，不提供独立质量管理入口。报告、任务历史、连接运行和版本审计均从工程资料或对应任务页面访问。

## 验证

Python 使用 `pytest -q` 和 `python -m compileall app tools`；Java 使用 `mvn -pl ruoyi-admin -am package -DskipTests`；Vue 使用 `npm run build:prod`。数据库检查 `drilling_schema_version`、`drilling_project%` 和 `project_id` 列；浏览器检查井眼级联、三栏工作区、第四列可视化设置、全屏、导出和浅色若依主题。
