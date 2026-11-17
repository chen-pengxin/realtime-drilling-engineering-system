# A/B/C 演示测试输入

这里仅保存 `well_control_iscwsa` 的合成测试输入，不包含真实井场、生产测斜资料、模型权重或求解结果。

每个场景包含：

- `problem.json`：起点、目标点、原生轨迹模式、段数和约束；
- `actual_input/well_1.csv` ～ `well_10.csv`：实钻轨迹输入；
- `design_input/well_1.csv` ～ `well_10.csv`：设计轨迹输入。

`well_1` 用于模拟正在钻进的井，其余井作为已完成邻井。`SLSQP` 结果文件不随测试输入提交，启动时由系统重新计算。
