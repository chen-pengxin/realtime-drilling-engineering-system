# 离线验证工具

本目录用于在隔离服务和测试库中回放井眼版本、来源解析、增量防碰、再规划、预测模型更新和报告导出。脚本必须通过环境变量指定测试服务和账号，不写入正式井场数据库。

验证内容只覆盖系统契约、数据版本和接口行为；不将外部软件精度或现场预测精度作为系统验收条件。

`verify_system_test_cases.py` 使用当前登录会话，按普通井场与任务接口核验 A/B/C：10 口井及实钻/设计版本、井 1 钻进状态、9 个确认邻井、来源原件和表格回写、双方法扫描、重复扫描复用。加 `--solve` 时真正求解并保存再规划任务，核对实测末点、邻井输入和求解器，不读取预先保存的 SLSQP 结果。求解使用已确认的井场配置；报告中的求解资源参数取实际生效快照。

```bash
# 在仓库根目录，DRILLING_SESSION_ID 使用当前管理员会话的 JSESSIONID。
PYTHONPATH=algorithm-server algorithm-server/.venv/bin/python \
  algorithm-server/tools/verification/verify_system_test_cases.py \
  --solve --report docs/verification/system-test-cases.json
```

页面核验脚本为 `ruoyi-vue3/tests/engineering_test_cases_browser.cjs`，使用同一会话环境变量。可用 `PLAYWRIGHT_MODULE` 指定现有 Playwright 模块，`DRILLING_BROWSER_EXECUTABLE` 指定浏览器，`DRILLING_BROWSER_REPORT` 指定截图和报告目录。核验默认浅色、三个普通测试井场、11 条轨迹（实钻、原始设计、9 个邻井）、直线起始的段式选择，以及移动端宽度。
