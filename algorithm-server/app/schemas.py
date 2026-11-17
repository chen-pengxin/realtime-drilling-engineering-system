from __future__ import annotations

import math

from .tasks.replanning.native_profiles import ensure_native_profiles
ensure_native_profiles()

from pydantic import AliasChoices, BaseModel, Field, ConfigDict, model_validator


class StationInput(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)
    north: float | None = None
    east: float | None = None
    tvd: float | None = None
    md: float = Field(ge=0)
    inclination: float = Field(ge=0, le=180)
    azimuth: float
    # Measured stations are immutable; prediction extensions are explicitly
    # marked so the realtime gateway can audit and alarm on them.
    source: str = "MEASURED"
    predicted: bool = False


class SurveyUncertaintyInput(BaseModel):
    model_config = ConfigDict(extra='forbid')
    model_version: str
    settings: dict
    parameter_source: str = 'VERSION_CONFIRMED'
    tool: dict = Field(default_factory=dict)


class SurveyInput(BaseModel):
    canonical_coordinates: bool = False
    stations: list[StationInput] = Field(min_length=1)
    origin_north: float = 0
    origin_east: float = 0
    origin_tvd: float = 0
    uncertainty: SurveyUncertaintyInput | None = None


class SurveyQualityInput(BaseModel):
    """导入前质量检查契约；问题只登记，不对原始站点自动纠偏。"""
    stations: list[StationInput] = Field(min_length=1)
    frozen_tolerance: float = Field(default=1e-12, ge=0)
    jump_threshold_deg: float = Field(default=45.0, gt=0, le=180)


class IncrementalSurveyInput(BaseModel):
    """增量导入预览；持久化版本和质量问题由 Java 事务保存。"""
    existing: SurveyInput
    incoming: list[StationInput] = Field(min_length=1)
    md_tolerance: float = Field(default=1e-6, gt=0)


class RealtimeIngestInput(BaseModel):
    """批量实时测斜事件；井场事务由 Java 网关提交和持久化。"""
    model_config = ConfigDict(extra="forbid")
    field_id: int | None = Field(default=None, gt=0)
    source: str = Field(min_length=1, max_length=255)
    # Java and the public example use the numeric drilling_well primary key,
    # while connector adapters may supply an external string identifier.
    # Accept both at the HTTP boundary and normalize to text before the
    # in-process deduplicator builds its stream key.
    well_id: str | int = Field(default="")
    events: list[dict[str, object]] = Field(default_factory=list, max_length=2000)
    reorder_window: float | None = Field(default=None, ge=0, le=1000)
    flush: bool = False
    # The authenticated Java gateway uses replay for events read back from
    # its durable inbox.  Replay is deliberately stateless at the algorithm
    # boundary so a FastAPI restart or a partially committed batch can be
    # retried without colliding with the in-process de-duplication cache.
    replay: bool = False

    @model_validator(mode="after")
    def normalize_well_identifier(self):
        self.well_id = str(self.well_id)
        if len(self.well_id) > 128:
            raise ValueError("well_id must be at most 128 characters")
        return self


class ErrorModelInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: str = "ISCWSA"
    model_version: str = "iscwsa_rev0"
    k: float = Field(default=1.0, gt=0)
    sf_method: str = Field(default="major_axis_sum", pattern="^(major_axis_sum|directional_sum|project_rss)$")
    settings: dict = Field(default_factory=dict)
    radii_margin_m: float = Field(default=0, ge=0)
    sigma_pa_m: float = Field(default=0, ge=0)
    curve_tolerance_m: float = Field(default=0.01, gt=0)


class ThresholdInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    red_sf: float = Field(default=1.0, ge=0)
    orange_sf: float = Field(default=1.5, ge=0)
    yellow_sf: float = Field(default=2.5, ge=0)
    red_distance: float = Field(default=5.0, ge=0)
    error_net_ratio_orange: float = Field(default=0.5, ge=0)
    error_net_ratio_yellow: float = Field(default=1.0, ge=0)

    @model_validator(mode="after")
    def validate_error_thresholds(self):
        if not self.red_sf <= self.orange_sf <= self.yellow_sf:
            raise ValueError('SF 阈值必须满足红色 ≤ 橙色 ≤ 黄色')
        if self.error_net_ratio_yellow < self.error_net_ratio_orange:
            raise ValueError("error_net_ratio_yellow must be >= error_net_ratio_orange")
        return self


class CollisionInput(BaseModel):
    # Optional for direct, isolated algorithm tests.  The Java gateway requires
    # this field for production field-scoped collision requests.
    field_id: int | None = Field(default=None, gt=0)
    reference_well_id: int | None = Field(default=None, gt=0)
    reference_survey_version_id: int | None = Field(default=None, gt=0)
    neighbor_well_id: int | None = Field(default=None, gt=0)
    neighbor_survey_version_id: int | None = Field(default=None, gt=0)
    reference: SurveyInput
    neighbor: SurveyInput
    step: float = Field(default=1.0, gt=0)
    mode: str = Field(default="BOTH", pattern="^BOTH$")
    error_model: ErrorModelInput = ErrorModelInput()
    thresholds: ThresholdInput = ThresholdInput()


class CollisionNeighborInput(SurveyInput):
    """One field-scoped neighbor included in a network scan."""
    well_id: int = Field(gt=0)
    survey_version_id: int = Field(gt=0)


class CollisionNetworkInput(BaseModel):
    """Full-neighbor scan with optional incremental MD window."""
    model_config = ConfigDict(extra="forbid")
    field_id: int | None = Field(default=None, gt=0)
    reference_well_id: int = Field(gt=0)
    reference_survey_version_id: int = Field(gt=0)
    reference: SurveyInput
    neighbors: list[CollisionNeighborInput] = Field(min_length=1, max_length=200)
    step: float = Field(default=1.0, gt=0)
    mode: str = Field(default="BOTH", pattern="^BOTH$")
    error_model: ErrorModelInput = ErrorModelInput()
    thresholds: ThresholdInput = ThresholdInput()
    incremental_from_md: float | None = Field(default=None, ge=0)
    incremental_to_md: float | None = Field(default=None, ge=0)
    # Immutable version identities are attached by the Java gateway.  They are
    # kept in the task result for audit and are never interpreted as geometry.
    input_versions: list[dict[str, object]] = Field(default_factory=list, max_length=201)

    @model_validator(mode="after")
    def validate_neighbors(self):
        ids = [item.survey_version_id for item in self.neighbors]
        if self.reference_survey_version_id in ids:
            raise ValueError("reference well cannot be included in neighbors")
        if len(ids) != len(set(ids)):
            raise ValueError("neighbor wells must be unique")
        if self.incremental_to_md is not None and self.incremental_from_md is not None \
                and self.incremental_to_md < self.incremental_from_md:
            raise ValueError("incremental_to_md must be >= incremental_from_md")
        return self


class ObstacleSurveyInput(SurveyInput):
    well_id: int | None = Field(default=None, gt=0)
    survey_version_id: int | None = Field(default=None, gt=0)


class TargetInput(BaseModel):
    kind: str = "POINT"
    north: float
    east: float
    tvd: float
    tolerance_north: float = Field(default=0.5, gt=0)
    tolerance_east: float = Field(default=0.5, gt=0)
    tolerance_tvd: float = Field(default=0.5, gt=0)
    shape_north: float | None = Field(default=None, gt=0)
    shape_east: float | None = Field(default=None, gt=0)
    shape_tvd: float | None = Field(default=None, gt=0)


class PlanningInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    # Optional at the pure algorithm boundary; the authenticated Java gateway
    # requires it before forwarding a production planning request.
    field_id: int | None = Field(default=None, gt=0)
    well_id: int | None = Field(default=None, gt=0)
    current_survey_version_id: int | None = Field(default=None, gt=0)
    target_id: int | None = Field(default=None, gt=0)
    start_md: float = Field(ge=0)
    start_north: float
    start_east: float
    start_tvd: float
    start_inclination: float = Field(ge=0, le=180)
    start_azimuth: float
    target: TargetInput
    solver: str = "SLSQP"
    # Geometry and segment layout use the native well_control_iscwsa fields in
    # native_options; no application-defined planning scenario is accepted.
    max_dogleg_deg_per_30m: float = Field(default=6.0, gt=0)
    min_dogleg_deg_per_30m: float = Field(default=0.0, ge=0)
    min_segment_length: float = Field(default=3.0, gt=0)
    max_segment_length: float = Field(default=30.0, gt=0)
    constraint_mode: str = Field(default="GLOBAL", pattern="^(GLOBAL|PER_SEGMENT)$")
    max_toolface_change_deg: float = Field(default=180.0, ge=0, le=360)
    scan_step: float = Field(default=1.0, gt=0)
    minimum_sf: float = Field(default=1.5, gt=0)
    collision_constraint: str = Field(default="SEPARATION_FACTOR", pattern="^SEPARATION_FACTOR$")
    target_tolerance_m: float = Field(default=0.5, gt=0)
    target_inclination: float | None = Field(default=None, gt=0, lt=180)
    target_azimuth: float | None = None
    history: SurveyInput | None = None
    segment_bounds: list[dict] = Field(default_factory=list)
    native_options: dict = Field(default_factory=lambda: {'trajectory_mode': 'arc-straight', 'n_segments': 3})
    error_model: ErrorModelInput = ErrorModelInput()
    obstacles: list[ObstacleSurveyInput] = Field(default_factory=list, max_length=100)
    random_seed: int = 17
    max_iterations: int = Field(default=500, ge=1, le=5000)
    execution_timeout_seconds: int = Field(default=120, ge=5, le=300)
    input_versions: list[dict[str, object]] = Field(default_factory=list, max_length=101)

    @model_validator(mode='after')
    def ordered_bounds(self):
        if self.min_segment_length > self.max_segment_length:
            raise ValueError('最小段长不能大于最大段长')
        if self.min_dogleg_deg_per_30m > self.max_dogleg_deg_per_30m:
            raise ValueError('最小狗腿度不能大于最大狗腿度')
        if self.constraint_mode == 'GLOBAL' and self.segment_bounds:
            raise ValueError('全局约束模式不能同时提交分段约束')
        mode = self.native_options.get('trajectory_mode')
        count = self.native_options.get('n_segments')
        modes = ensure_native_profiles()
        if mode not in modes:
            raise ValueError(f'trajectory_mode must be one of {modes}')
        if not isinstance(count, int) or isinstance(count, bool) or count not in range(1, 6):
            raise ValueError('n_segments must be an integer from the supplied planning profiles (1-5)')
        if len(self.segment_bounds) > count:
            raise ValueError('分段约束数不能超过轨迹段数')
        if self.segment_bounds and len(self.segment_bounds) != count:
            raise ValueError('分段约束必须为每个轨迹段提供一个对象，未覆盖段使用空对象')
        if self.constraint_mode == 'PER_SEGMENT' and len(self.segment_bounds) != count:
            raise ValueError('分段约束模式必须为每个轨迹段提供约束')
        for row in self.segment_bounds:
            if set(row)-{'length_m','dls_deg_per_30m','start_toolface_deg'}:
                raise ValueError('不支持的逐段约束字段')
            for key,bounds in row.items():
                if not isinstance(bounds,(list,tuple)) or len(bounds)!=2 or not all(isinstance(v,(float,int)) and math.isfinite(v) for v in bounds) or bounds[0]>bounds[1]:
                    raise ValueError('逐段约束必须为有限的[下限,上限]')
                if key=='length_m' and bounds[0]<=0 or key=='dls_deg_per_30m' and bounds[0]<0:
                    raise ValueError('逐段段长必须为正，狗腿度不能为负')
        return self


class TrainingStartInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    template: str = Field(min_length=1, max_length=200)
    dataset: str = Field(min_length=1, max_length=500)
    field_id: int = Field(gt=0)
    device: str = "auto"
    hyperparameters: dict[str, object] = Field(default_factory=dict)
    base_model_path: str | None = None


class InferenceInput(BaseModel):
    model_path: str = Field(min_length=1, max_length=1000)
    script: str = Field(min_length=1, max_length=200)
    input: dict[str, object] = Field(default_factory=dict)
    device: str = "cpu"
    timeout_seconds: int = Field(default=30, ge=1, le=300)


class PredictionStartInput(BaseModel):
    """预测业务契约；模型接入前只登记任务，不执行算法。"""
    model_config = ConfigDict(extra="forbid")
    prediction_step: float = Field(default=9.0, gt=0, le=1000)
    device: str = "auto"
    model_version: str | None = Field(default=None, max_length=200)
    field_id: int | None = Field(default=None, gt=0)
    well_id: int = Field(gt=0)
    survey_version_id: int = Field(gt=0)
    input_file_id: int = Field(gt=0)
    input_summary: dict[str, object] = Field(default_factory=dict)


class ReportInput(BaseModel):
    # Standalone FastAPI report tests may omit this value. The authenticated
    # Java gateway requires it for field-scoped exports.
    field_id: int | None = Field(default=None, gt=0)
    title: str = Field(default="井眼轨迹评价与规划报告", max_length=200)
    format: str = Field(default="bundle", pattern="^(bundle|all|xlsx|excel|docx|pdf|json)$")
    payload: dict[str, object] = Field(default_factory=dict)


class SurveyExportInput(SurveyInput):
    field_id: int | None = Field(default=None, gt=0)
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    format: str = Field(default="csv", pattern="^(csv|json|witsml)$")
    # Presets reserve vendor-facing Compass/WellPlan labels without changing
    # the canonical N/E/TVD data contract.  ``field_mapping`` allows a site to
    # provide its exact licensed export labels when a preset is insufficient.
    # ``schema`` is retained as an input alias for clients of the first API
    # version.  The Python attribute is named ``export_schema`` so it does not
    # shadow BaseModel.schema and does not emit a Pydantic warning.
    export_schema: str = Field(
        default="CANONICAL",
        pattern="^(CANONICAL|COMPASS|WELLPLAN)$",
        validation_alias=AliasChoices("schema", "export_schema"),
        serialization_alias="schema",
    )
    field_mapping: dict[str, str] = Field(default_factory=dict, max_length=20)
