# 模型适配器契约

井眼轨迹预测包含“预测展示”和“模型仓库”两个页面，不提供独立训练页面。已有模型在仓库导入、启用，通过推理适配器返回真实结果；自动增量更新由 Java 后台调度，浏览器关闭后仍会检查新增实测数据。具体网络结构、预处理和权重加载由模型适配器提供。

## 推理适配器

脚本接受以下参数：

- `--model-path <path>`：已启用的模型包或权重文件，可能为 ZIP、PT/PTH、ONNX 或 JSON；ZIP 由适配器在临时目录解包。
- `--input-json <path>`：UTF-8 JSON 文件，使用 `drilling.pose-extrapolation.v1` 契约。
- `--device cpu|cuda:<index>`：经服务解析后的设备。

输入示例：

```json
{
  "contract": "drilling.pose-extrapolation.v1",
  "prediction_step": 9,
  "prediction_steps": [9, 28],
  "stations": [
    {"md": 1200, "inclination": 35.2, "azimuth": 128.4, "north": 100.1, "east": 88.4, "tvd": 980.2}
  ],
  "current_pose": {"md": 1200, "inclination": 35.2, "azimuth": 128.4, "north": 100.1, "east": 88.4, "tvd": 980.2}
}
```

最后一行 stdout 必须为 JSON 对象，包含 `predicted_stations` 数组或单个 `predicted_pose`。每点必须包含未来 MD、井斜（0–180°）、方位，全部为有限数值。服务按 MD 排序，拒绝重复/过去 MD，并用共享最小曲率法从当前实测位置逐段计算 N/E/TVD；不会补造未返回的 9 m 或 28 m 结果。可附加模型指标和置信度。

```json
{
  "predicted_stations": [
    {"md": 1209, "inclination": 36.1, "azimuth": 129.0},
    {"md": 1228, "inclination": 37.0, "azimuth": 129.4}
  ],
  "model_version": "pose-model-v1"
}
```

以上是接口示例，不是内置预测结果。模型未配置时展示 `MODEL_NOT_CONFIGURED`。实时防碰仍可按井场配置显式采用几何回退，其来源标签与训练模型预测区分。

## 自动增量训练适配器

脚本必须接受：

- `--dataset <path>`：本次实测窗口 CSV，只读。
- `--base-model-path <path>`：当前已启用的原模型包，只读；必须加载其中权重继续训练。
- `--output-dir <path>`：将更新后的可推理模型写入此目录，不改写原权重。
- `--device cpu|cuda:<index>`。
- `--hyperparameters-json <object>`：本次项目策略和来源快照。

CSV 字段为 `md,inclination,azimuth,north,east,tvd,is_new`。窗口按 MD 升序，`is_new=1` 表示超出上次成功训练的 MD，`0` 为窗口内历史实测点。所有点来自当前已提交测斜版本，预测点不得作为标签。

超参数提供 `epochs`、`learning_rate`、`batch_size`、`validation_ratio`、`replay_ratio`、`windowSize`，同时保留 UI 驼峰字段以供版本追踪。还包括 `training_mode=INCREMENTAL`、`parent_model_version_id`、`survey_version_id`、`from_md` 和 `to_md`。适配器负责执行学习率、批大小、按时间划分的验证集以及历史样本回放策略；不足以更新或无法加载原权重时应非零退出并报告原因，不得静默从零训练。

每个 Epoch 向 stdout 输出一行指标 JSON，例如 `{"epoch":1,"loss":0.2,"val_loss":0.24}`；普通日志也可逐行输出。服务采集日志/指标，并将输出目录归档为新的 ZIP 模型版本。输出必须能被同一推理适配器再次读取；下一次增量更新的原模型可能是这个 ZIP。

任务成功且归档元数据完整时，Java 登记新版本并推进实测 MD 游标；失败/终止保留旧模型与游标。开启自动启用时，仅当当前模型仍为提交时的父版本才切换，避免覆盖用户的手动选择。

## 目录与登记

Java `RUOYI_PROFILE` 与 FastAPI `TRAIN_DATA_ROOT`、`TRAIN_TEMPLATE_ROOT` 指向同一上传内容；FastAPI 的 `MODEL_ROOT` 保存导入的原模型与增量产物。Compose 已配置共享只读输入卷。

在“模型仓库 → 登记模型适配器”上传 `.py`，选择“模型推理”或“增量更新”用途；前者用于模型启用，后者用于自动更新设置。模型版本、策略和日志均按井场权限读取。ONNX 文件可用于推理；继续训练所需的可训练权重与依赖应包含在对应模型包中。
