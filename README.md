# 实时钻井系统

系统使用 RuoYi Java、Vue 3 / Element Plus、FastAPI 和 MySQL。业务围绕三个独立任务：**防碰评价、井眼轨迹预测、轨迹再规划**。首页是实时数据展示大屏，数据接入配置在单独的基础功能页面。

功能依据为《项目要求.docx》及用户后续确认；《钻井工程数字化系统方案.md》只作为设计文档格式参考。当前数值核心固定使用用户提供的 `well_control_iscwsa` 对应 `wellpath-engine-1.0.0` wheel，默认误差模型 `iscwsa_rev0`、求解器 `SLSQP`。不提供独立轨迹设计、商业软件对标、20井验收、套管磨损或井壁稳定模块。

## 业务入口

| 入口 | 内容 |
| --- | --- |
| 实时大屏 | 左侧最新测斜与预测来源，中间三维，右侧上下投影、全屏、风险和报警记录 |
| 任务中心 / 防碰评价 | 默认全部有效邻井、版本选择、双方法评价、粗筛、增量扫描、井对和风险井段、导出 |
| 任务中心 / 井眼轨迹预测 | 预测展示、模型仓库；自动增量更新位于模型仓库内，无独立训练页 |
| 任务中心 / 轨迹再规划 | 原生轨迹模式、段式、目标点和分段约束、结果参数、原始方案、二维/三维、轨道表和防碰结果 |
| 基础功能 / 工程资料目录 | 按区块、井场、井、井眼逐级进入，管理工程目录与数据权限 |
| 基础功能 / 井与测斜数据 | 井/井眼、来源资料、设计/实钻版本、复测、增量、导入诊断与版本比较 |
| 基础功能 / 实时数据接入 | 批次接入、连接器、真实心跳、重连、隔离与增量版本 |
| 基础功能 / 系统配置 | 误差模型、参数、SF公式和阈值、求解默认值、预测与报警策略 |
| 基础功能 / 文件与审计 | 文件、井场审计、任务历史、报告归档 |
| 系统管理 / 运行维护 | 用户、组织、角色、菜单、数据权限、个人中心、日志、会话、CPU/GPU等 |

原六项业务管理功能仍有独立权限和后端接口，前端按业务上下文归入页签；旧地址重定向，不再增加六个平级菜单。

## 资料与版本

实体关系为“区块 → 井场 → 物理井 → 井眼”。井场是配置、作业和数据授权范围；侧钻属于物理井内的独立井眼，可记录父井眼。井场内的任务复用井眼和测斜版本，不重复导入原件。邻井选择可跟随当前实钻/设计版本，或固定历史版本。

每个井眼分别维护 `ACTUAL` 和 `DESIGN` 当前指针。完整导入、增量导入、复测修订、分支和回退均保留不可变历史；当前指针切换使用预期版本检查，过期并发请求返回409。复测影响从前一最小曲率段开始，并考虑下游位置和协方差依赖。

来源库保留原件、SHA-256、源表定位、字段映射、原始行号和诊断。支持Excel、CSV/TXT及有表格的Word/PDF等来源解析；未知表头可以手工列映射。损坏、图片扫描件、无测斜表的附件可归档，不生成假测点。清洗预览识别重复、同MD冲突和异常点，由工程人员选择处理策略。

原件下载保持字节一致；原格式回写只修改映射轨迹单元格或文本数据行，保留其他表格、备注、样式和页尾。历史二进制Excel由Java POI回写。无法安全定位或不支持编辑的容器明确拒绝回写，仍可下载原件及标准化归算表，不静默转换格式。

正式库现有资料已完成归档入库。原件缺少CRS、深度基准高程或Rev.0所需测量历史的版本保持待复核，不能直接加入跨井评价。数量与验证证据见[交付检查](docs/十五项功能交付检查.md)。

## 算法与展示

- 防碰请求固定 `BOTH`：井间距与分离系数同时返回；中心距最近点和最小SF点分别保存。
- 默认步长1m，可修改；测量归算、扫描插值、预测位置和再规划状态转移统一最小曲率。
- 工程配置读取原生模型目录和参数结构。默认 `iscwsa_rev0`、K=1；分离系数页面只显示当前公式及参数含义。旧自研误差和规划实现不作为回退。
- 全邻井先保守包围盒粗筛，再详细评价。分块缓存验证几何、误差配置及协方差依赖，报告复用与重算区间；历史修订会使受影响区间和相关下游失效。
- 再规划不设置业务场景。轨迹模式与段数从 `well_control_iscwsa` 原生目录配置（默认 `arc-straight`、3段），求解器为 `SLSQP / trust-constr / COBYLA / COBYQA`；碰撞约束仅 `SEPARATION_FACTOR`，优化目标为总长度最小化。结果包含分段几何、工具面、滑动/复合导向建议和真实约束复核。
- 轨迹图统一Plotly，保留图例、测点圆点、深度向下、剖面方位、风险段、最近点与PNG/SVG导出。统计或运维趋势图可继续使用原组件。
- 预测模型未配置时返回 `MODEL_NOT_CONFIGURED`，不生成替代角度。实时防碰可显式开启几何外推，页面明确标为非模型预测；关闭后未来字段为空。
- 新模型通过可信推理/增量适配器接入。新增实测数据达到阈值后，Java后台提交原权重和实测窗口，Python继续训练，保存日志、指标和新模型；失败保留原模型与成功游标。契约见[模型适配器](algorithm-server/train_templates/README.md)。
- 实时未来井段风险按配置弹窗及响铃，携带井眼、实钻/邻井版本和预测MD进入绕障再规划。浏览器音频需要首次用户点击启用。

## 本地完整启动

在仓库根目录操作。默认端口：前端5173、Java8080、算法8000；MySQL3306须已启动。**浏览器打开前端端口**，不要把前端路由地址直接交给Java静态资源服务。

首次安装依赖：

```bash
python3.12 -m venv algorithm-server/.venv
(cd algorithm-server && .venv/bin/python -m pip install -r requirements-dev.txt)
(cd ruoyi-vue3 && npm ci)
(cd ruoyi-java && mvn -DskipTests package)
```

需要JDK17、Maven、Node.js、Python 3.11+和已初始化的MySQL数据库。算法依赖包含仓库内固定wheel；SQL Server连接还需系统ODBC驱动。新环境可执行 `MYSQL_PASSWORD='你的密码' ./deploy/init-local-db.sh`，脚本会创建 `ry`、导入脱敏的 RuoYi 基础表及全部领域迁移，并拒绝覆盖已有非空数据库。默认账号为 `admin`，初始密码为 `123456`，首次登录必须修改密码。现有 `ry` 库请先备份，不要直接执行初始化脚本。

启动和日常管理：

```bash
export MYSQL_DATABASE=ry
export MYSQL_USER=root
# 不设置密码环境变量时，交互启动会安全提示输入，不回显、不写文件。
./deploy/local.sh start --build
./deploy/local.sh status
./deploy/local.sh stop
./deploy/local.sh restart
```

非交互运行通过 `MYSQL_PASSWORD` 或 `SPRING_DATASOURCE_DRUID_MASTER_PASSWORD` 提供密码。修改Java或前端后用 `restart --build`；只改Python也须重启算法服务。脚本依次启动Python、Java和Vite，统一设置代理、共享上传目录、模型目录和回调令牌，日志及进程记录在 `.local-runtime/`，不会结束占用端口的未知程序。`stop`只终止脚本记录且进程身份仍匹配的本项目进程组；MySQL由本机服务单独管理。

Java从 `.local-runtime/java-builds/` 下按内容哈希保存的只读JAR快照运行，后续Maven构建不会覆盖运行中的依赖。启动成功要求算法健康检查、Java验证码图片和前端登录页均返回正常响应，不能仅凭端口打开判断服务可用。

可在启动前设置：

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `MYSQL_HOST / MYSQL_PORT` | `127.0.0.1 / 3306` | 数据库地址 |
| `MYSQL_DATABASE / MYSQL_USER` | `ry / root` | 库与用户 |
| `ALGORITHM_PORT / JAVA_PORT / FRONTEND_PORT` | `8000 / 8080 / 5173` | 三项服务端口 |
| `RUOYI_PROFILE` | `~/.ruoyi/uploadPath` | Java上传、Python只读训练输入的共同根目录 |
| `DRILLING_CALLBACK_TOKEN` | 本地随机生成并以0600保存 | Java/Python服务回调鉴权 |
| `REDIS_URL` | 未设置 | 可选训练队列；未设置时单机进程执行 |

任务配置确认弹窗是计算前检查流程，不能靠关闭弹窗绕过。账号ID由系统生成且不可修改，可用ID或唯一账号名称登录；新建账号初始密码123456，必须修改后才能使用业务功能。账号只能由管理员创建，游客还有时间与演示功能限制。详见[账号管理](docs/账号与权限管理.md)。

## 部署和接口

Docker配置在 `deploy/docker-compose.yml`，复制 `deploy/.env.example` 为私有 `.env` 并填写凭据，然后执行：

```bash
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env，替换所有 replace-with-* 值
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up --build
```

Compose 首个空卷会按顺序初始化脱敏基础库、全部领域迁移和 A/B/C 合成测试输入；`frontend-build` 会在启动 Nginx 前自动执行 `npm ci` 和生产构建，构建结果写入 Docker 卷，不需要提交 `dist/`。后续升级先备份，不删除数据卷。FastAPI 仅在内部网络开放，用户请求由 Java 检查账号、操作权限和井场数据范围。生产 TLS 示例见 `deploy/nginx.tls.conf.example`。

| 用途 | Java业务接口 |
| --- | --- |
| 工程目录 | `/api/drilling/blocks`、`/api/drilling/fields`、`/api/drilling/fields/{fieldId}/wells` |
| 井眼、来源、版本、邻井、配置确认、工作草稿 | `/api/drilling/fields/{fieldId}/registry/*` |
| 文件与工程配置 | `/api/drilling/fields/{fieldId}/files`、`/configs` |
| 防碰 | `POST /api/algorithm/anti-collision/network-scan` |
| 再规划 | `POST /api/algorithm/trajectory-plan/solve` |
| 实时事件 | `POST /api/algorithm/realtime/ingest` |
| 预测任务 | `/api/drilling/predictions` |
| 模型、增量策略 | `/api/drilling/fields/{fieldId}/models`、`/models/incremental-learning` |
| 训练适配器目录、任务与服务回调 | `/api/drilling/fields/{fieldId}/training/templates`、`/api/train/task/*` |
| 历史、质量、报告、连接器等 | `/api/drilling/centers/{module}`，以`fieldId`绑定井场 |

浏览器不能直接调用Python内部来源解析、训练和存储接口。可信适配器会执行Python代码，井场权限和只读输入不构成不可信代码沙箱；只登记已审核脚本。WITS网关行格式、WITSML XML轮询和SQL连接边界见[业务管理说明](docs/业务管理六模块.md)。

## 代码结构与验证

```text
ruoyi-vue3/src/views/drilling/
  tasks/anticollision/     防碰任务
  tasks/prediction/        预测展示、模型和增量设置
  tasks/replanning/        再规划任务
  basic/                  基础业务与来源/版本管理
  layout/                 公共工程工作区及实时报警
  composables/            共享业务状态
  centers/                被基础页面复用的管理页签
algorithm-server/app/
  tasks/anticollision/     原生防碰适配与缓存
  tasks/prediction/        预测契约
  tasks/replanning/        原生再规划适配
  shared/trajectory/      最小曲率、归算、插值、修订
  adapters/               来源与现场数据适配
  domain/                 报告、训练进程与通用领域支持
algorithm-server/vendor/  固定数值引擎wheel及来源记录
ruoyi-java/               授权、业务持久化、版本事务、后台调度
```

```bash
(cd algorithm-server && .venv/bin/python -m pytest -q)
algorithm-server/.venv/bin/python -m compileall -q algorithm-server/app
(cd ruoyi-java && mvn -pl ruoyi-admin -am package -DskipTests=false)
(cd ruoyi-vue3 && npm run build:prod)
git diff --check
```

集成脚本位于 `algorithm-server/tools/verification/`，必须显式指定隔离QA服务和账号；不要对正式库执行测试写入。本轮数据导入、接口、复测、UI、报警、原格式导出及自动增量链路结果见[十五项交付检查](docs/十五项功能交付检查.md)。商业软件精度、缺失现场资料和生产模型不能用构建成功代替。

完整功能设计：[实时钻井系统设计内容.md](实时钻井系统设计内容.md)。技术契约：[technical-implementation-plan.md](docs/technical-implementation-plan.md)。

### A/B/C 测试井

本地启动脚本和容器的一次性数据初始化服务会幂等导入算法附带的原始 CSV，形成“测试区块 → 测试案例 A / B / C → 井1～井10 → 主井眼”。每个井场的井1正在钻进，其余9口井已完成；每口井均关联实钻和设计两个独立版本及来源文件。重复启动不会重置已有版本指针、配置或邻井选择。

在实时大屏、防碰评价、预测展示和轨迹再规划页面顶部选择测试井场，随后使用普通井眼选择、参数配置、计算、历史和导出功能。再规划从所选实钻版本末点开始，默认目标、段约束和误差参数来自对应案例；保存的 SLSQP 文件只保留为算法原始参考，不作为系统新任务的计算结果。模型仓库未启用预测模型时，预测页保持 `MODEL_NOT_CONFIGURED`。

单独补导可执行（先设置 MYSQL_PASSWORD）：

```bash
cd algorithm-server
PYTHONPATH=. .venv/bin/python tools/import_test_cases.py
```

## 同事启动与提交

同事从 `main` 拉取后，在自己的功能分支开发：

```bash
git clone git@github.com:chen-pengxin/realtime-drilling-engineering-system.git
cd realtime-drilling-engineering-system
git switch -c feat/system-management
```

本地开发可按“数据库、算法服务、Java、前端”的顺序启动；完整命令、端口、验证码检查和常见错误见[同事正式启动与代码提交指南](docs/同事正式启动与代码提交指南.md)。完成后只提交源码、SQL 迁移、脱敏测试输入和文档，不提交 `.env`、数据库导出、上传文件、模型权重、日志、`target/`、`node_modules/` 或 `dist/`：

```bash
git status --short
git add -p
git diff --cached --check
git commit -m "feat: implement system management"
git push -u origin feat/system-management
```

同事只推送自己的功能分支并创建 Pull Request，由项目负责人审查后合并到 `main`。
