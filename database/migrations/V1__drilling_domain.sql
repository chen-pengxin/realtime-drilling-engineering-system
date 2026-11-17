-- 钻井工程数字化系统领域表
-- 依赖 RuoYi 自带的 sys_user、sys_dept 表。领域表不复制用户、角色和菜单表。

CREATE TABLE IF NOT EXISTS drilling_block (
    block_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    block_code VARCHAR(64) NOT NULL,
    block_name VARCHAR(128) NOT NULL,
    basin VARCHAR(128) NULL,
    description VARCHAR(500) NULL,
    status CHAR(1) NOT NULL DEFAULT '0',
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) NULL,
    update_time DATETIME NULL,
    UNIQUE KEY uk_block_code (block_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='井区/区块';

CREATE TABLE IF NOT EXISTS drilling_field (
    field_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    block_id BIGINT NOT NULL,
    field_code VARCHAR(64) NOT NULL,
    field_name VARCHAR(128) NOT NULL,
    operator_name VARCHAR(128) NULL,
    description VARCHAR(500) NULL,
    status CHAR(1) NOT NULL DEFAULT '0',
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) NULL,
    update_time DATETIME NULL,
    UNIQUE KEY uk_field_code (field_code),
    KEY idx_field_block (block_id),
    CONSTRAINT fk_field_block FOREIGN KEY (block_id) REFERENCES drilling_block(block_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='井场';

CREATE TABLE IF NOT EXISTS drilling_project (
    project_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    field_id BIGINT NOT NULL,
    project_code VARCHAR(64) NOT NULL,
    project_name VARCHAR(128) NOT NULL,
    coordinate_system VARCHAR(128) NOT NULL DEFAULT 'LOCAL_ENU',
    length_unit VARCHAR(16) NOT NULL DEFAULT 'm',
    angle_unit VARCHAR(16) NOT NULL DEFAULT 'deg',
    tvd_positive CHAR(1) NOT NULL DEFAULT 'D',
    default_sf_threshold DECIMAL(8,3) NOT NULL DEFAULT 1.500,
    config_json JSON NULL,
    status CHAR(1) NOT NULL DEFAULT '0',
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) NULL,
    update_time DATETIME NULL,
    UNIQUE KEY uk_project_code (project_code),
    KEY idx_project_field (field_id),
    CONSTRAINT fk_project_field FOREIGN KEY (field_id) REFERENCES drilling_field(field_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钻井项目';

CREATE TABLE IF NOT EXISTS drilling_well (
    well_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    well_code VARCHAR(64) NOT NULL,
    well_name VARCHAR(128) NOT NULL,
    well_type VARCHAR(32) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PLANNED',
    wellhead_n DECIMAL(16,6) NOT NULL DEFAULT 0,
    wellhead_e DECIMAL(16,6) NOT NULL DEFAULT 0,
    wellhead_tvd DECIMAL(16,6) NOT NULL DEFAULT 0,
    magnetic_declination DECIMAL(10,6) NOT NULL DEFAULT 0,
    meridian_convergence DECIMAL(10,6) NOT NULL DEFAULT 0,
    metadata_json JSON NULL,
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) NULL,
    update_time DATETIME NULL,
    UNIQUE KEY uk_well_project_code (project_id, well_code),
    KEY idx_well_project (project_id),
    CONSTRAINT fk_well_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='井';

CREATE TABLE IF NOT EXISTS data_file (
    file_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NULL,
    owner_user_id BIGINT NULL,
    file_category VARCHAR(32) NOT NULL,
    business_type VARCHAR(64) NULL,
    original_name VARCHAR(255) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(128) NULL,
    file_size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    parent_file_id BIGINT NULL,
    is_template CHAR(1) NOT NULL DEFAULT '0',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_file_object (object_key),
    KEY idx_file_project_category (project_id, file_category),
    KEY idx_file_parent (parent_file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件及文件版本';

CREATE TABLE IF NOT EXISTS drilling_survey_version (
    survey_version_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    well_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    parent_version_id BIGINT NULL,
    source_file_id BIGINT NULL,
    import_mode VARCHAR(16) NOT NULL DEFAULT 'FULL',
    source_format VARCHAR(32) NULL,
    max_md DECIMAL(16,6) NOT NULL DEFAULT 0,
    is_current CHAR(1) NOT NULL DEFAULT '0',
    quality_summary_json JSON NULL,
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_survey_well_version (well_id, version_no),
    KEY idx_survey_well_current (well_id, is_current),
    CONSTRAINT fk_survey_well FOREIGN KEY (well_id) REFERENCES drilling_well(well_id),
    CONSTRAINT fk_survey_source_file FOREIGN KEY (source_file_id) REFERENCES data_file(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测斜轨迹版本';

CREATE TABLE IF NOT EXISTS drilling_survey_station (
    station_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    survey_version_id BIGINT NOT NULL,
    md DECIMAL(16,6) NOT NULL,
    inclination DECIMAL(10,6) NOT NULL,
    azimuth DECIMAL(10,6) NOT NULL,
    north DECIMAL(16,6) NOT NULL,
    east DECIMAL(16,6) NOT NULL,
    tvd DECIMAL(16,6) NOT NULL,
    dogleg DECIMAL(10,6) NULL,
    curvature DECIMAL(10,8) NULL,
    source_timestamp DATETIME NULL,
    raw_json JSON NULL,
    UNIQUE KEY uk_station_version_md (survey_version_id, md),
    KEY idx_station_version_md (survey_version_id, md),
    CONSTRAINT fk_station_version FOREIGN KEY (survey_version_id) REFERENCES drilling_survey_version(survey_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测斜站点';

CREATE TABLE IF NOT EXISTS drilling_target (
    target_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    target_name VARCHAR(128) NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    geometry_json JSON NOT NULL,
    tolerance_json JSON NULL,
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NULL,
    KEY idx_target_project (project_id),
    CONSTRAINT fk_target_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点靶或靶盒';

CREATE TABLE IF NOT EXISTS anti_collision_task (
    task_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    reference_well_id BIGINT NOT NULL,
    reference_survey_version_id BIGINT NOT NULL,
    evaluation_mode VARCHAR(32) NOT NULL,
    scan_step DECIMAL(10,3) NOT NULL DEFAULT 1.000,
    sf_k DECIMAL(8,3) NOT NULL DEFAULT 1.000,
    threshold_json JSON NOT NULL,
    error_model VARCHAR(32) NOT NULL DEFAULT 'SIMPLIFIED_RADIUS',
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    result_summary_json JSON NULL,
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time DATETIME NULL,
    KEY idx_collision_project_status (project_id, status),
    CONSTRAINT fk_collision_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id),
    CONSTRAINT fk_collision_ref_well FOREIGN KEY (reference_well_id) REFERENCES drilling_well(well_id),
    CONSTRAINT fk_collision_ref_survey FOREIGN KEY (reference_survey_version_id) REFERENCES drilling_survey_version(survey_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='防碰扫描任务';

-- 该表仅保存一次扫描任务实际使用的井集合，不通过人工维护“邻井关系”。
-- 邻井关系由项目、扫描任务和用户选择动态产生。
CREATE TABLE IF NOT EXISTS anti_collision_task_well (
    task_id BIGINT NOT NULL,
    well_id BIGINT NOT NULL,
    survey_version_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'NEIGHBOR',
    PRIMARY KEY (task_id, well_id),
    CONSTRAINT fk_collision_well_task FOREIGN KEY (task_id) REFERENCES anti_collision_task(task_id),
    CONSTRAINT fk_collision_well_well FOREIGN KEY (well_id) REFERENCES drilling_well(well_id),
    CONSTRAINT fk_collision_well_survey FOREIGN KEY (survey_version_id) REFERENCES drilling_survey_version(survey_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='防碰任务井集合';

CREATE TABLE IF NOT EXISTS anti_collision_result (
    result_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    neighbor_well_id BIGINT NOT NULL,
    min_distance DECIMAL(16,6) NOT NULL,
    horizontal_distance DECIMAL(16,6) NULL,
    normal_distance DECIMAL(16,6) NULL,
    reference_md DECIMAL(16,6) NULL,
    neighbor_md DECIMAL(16,6) NULL,
    sf DECIMAL(16,6) NULL,
    risk_level VARCHAR(16) NOT NULL,
    result_json JSON NULL,
    KEY idx_collision_result_task (task_id),
    CONSTRAINT fk_collision_result_task FOREIGN KEY (task_id) REFERENCES anti_collision_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='防碰结果';

CREATE TABLE IF NOT EXISTS trajectory_plan (
    plan_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    well_id BIGINT NOT NULL,
    current_survey_version_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL,
    solver VARCHAR(16) NOT NULL,
    scenario VARCHAR(32) NOT NULL,
    constraint_json JSON NOT NULL,
    objective_value DECIMAL(16,6) NULL,
    terminal_error DECIMAL(16,6) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    result_json JSON NULL,
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time DATETIME NULL,
    KEY idx_plan_project_status (project_id, status),
    CONSTRAINT fk_plan_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id),
    CONSTRAINT fk_plan_well FOREIGN KEY (well_id) REFERENCES drilling_well(well_id),
    CONSTRAINT fk_plan_survey FOREIGN KEY (current_survey_version_id) REFERENCES drilling_survey_version(survey_version_id),
    CONSTRAINT fk_plan_target FOREIGN KEY (target_id) REFERENCES drilling_target(target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轨迹规划任务';

CREATE TABLE IF NOT EXISTS trajectory_plan_segment (
    plan_id BIGINT NOT NULL,
    segment_no INT NOT NULL,
    segment_length DECIMAL(16,6) NOT NULL,
    curvature DECIMAL(16,8) NOT NULL,
    toolface DECIMAL(10,6) NOT NULL,
    start_md DECIMAL(16,6) NULL,
    end_md DECIMAL(16,6) NULL,
    start_north DECIMAL(16,6) NULL,
    start_east DECIMAL(16,6) NULL,
    start_tvd DECIMAL(16,6) NULL,
    end_north DECIMAL(16,6) NULL,
    end_east DECIMAL(16,6) NULL,
    end_tvd DECIMAL(16,6) NULL,
    dogleg DECIMAL(16,6) NULL,
    sliding_ratio DECIMAL(8,5) NULL,
    PRIMARY KEY (plan_id, segment_no),
    CONSTRAINT fk_plan_segment_plan FOREIGN KEY (plan_id) REFERENCES trajectory_plan(plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轨迹规划分段';

CREATE TABLE IF NOT EXISTS prediction_task (
    prediction_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    well_id BIGINT NOT NULL,
    input_file_id BIGINT NULL,
    prediction_step DECIMAL(10,3) NOT NULL DEFAULT 9.000,
    status VARCHAR(32) NOT NULL DEFAULT 'MODEL_NOT_CONFIGURED',
    result_json JSON NULL,
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_prediction_project (project_id),
    CONSTRAINT fk_prediction_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id),
    CONSTRAINT fk_prediction_well FOREIGN KEY (well_id) REFERENCES drilling_well(well_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钻头姿态预测业务任务';

CREATE TABLE IF NOT EXISTS training_dataset (
    dataset_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NULL,
    file_id BIGINT NOT NULL,
    dataset_name VARCHAR(128) NOT NULL,
    version_no INT NOT NULL DEFAULT 1,
    schema_json JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'READY',
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_dataset_project (project_id),
    CONSTRAINT fk_dataset_file FOREIGN KEY (file_id) REFERENCES data_file(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='训练数据集';

CREATE TABLE IF NOT EXISTS training_template (
    template_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NULL,
    file_id BIGINT NOT NULL,
    template_name VARCHAR(128) NOT NULL,
    framework VARCHAR(32) NULL,
    hyperparam_schema_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_template_file FOREIGN KEY (file_id) REFERENCES data_file(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='训练脚本模板';

CREATE TABLE IF NOT EXISTS training_task (
    training_task_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NULL,
    dataset_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    device VARCHAR(32) NOT NULL DEFAULT 'cpu',
    hyperparams_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    log_stream_key VARCHAR(255) NULL,
    model_version_id BIGINT NULL,
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time DATETIME NULL,
    KEY idx_training_status (status),
    CONSTRAINT fk_training_dataset FOREIGN KEY (dataset_id) REFERENCES training_dataset(dataset_id),
    CONSTRAINT fk_training_template FOREIGN KEY (template_id) REFERENCES training_template(template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='训练任务';

CREATE TABLE IF NOT EXISTS model_version (
    model_version_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NULL,
    model_name VARCHAR(128) NOT NULL,
    version_no INT NOT NULL,
    file_id BIGINT NOT NULL,
    metrics_json JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'READY',
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_version (project_id, model_name, version_no),
    CONSTRAINT fk_model_file FOREIGN KEY (file_id) REFERENCES data_file(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型版本';

CREATE TABLE IF NOT EXISTS gpu_device (
    gpu_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    host_name VARCHAR(128) NOT NULL,
    device_index INT NOT NULL,
    device_uuid VARCHAR(128) NULL,
    device_name VARCHAR(128) NULL,
    total_memory_mb BIGINT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    last_metrics_json JSON NULL,
    UNIQUE KEY uk_gpu_host_index (host_name, device_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GPU资源';
