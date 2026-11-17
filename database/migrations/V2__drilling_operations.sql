-- 运行层补充迁移：项目配置、项目成员、质量隔离、实时连接器、报警与审计。
-- 该迁移不创建人工维护的 neighbor_relation 表；一次扫描使用的井集合仍由
-- anti_collision_task_well 保存。所有表均可在初始化脚本重复执行。

CREATE TABLE IF NOT EXISTS drilling_project_config (
    project_id BIGINT NOT NULL,
    config_version INT NOT NULL DEFAULT 1,
    coordinate_system VARCHAR(128) NOT NULL,
    north_reference VARCHAR(32) NOT NULL DEFAULT 'TRUE_NORTH',
    length_unit VARCHAR(16) NOT NULL DEFAULT 'm',
    angle_unit VARCHAR(16) NOT NULL DEFAULT 'deg',
    tvd_positive CHAR(1) NOT NULL DEFAULT 'D',
    origin_north DECIMAL(16,6) NOT NULL DEFAULT 0,
    origin_east DECIMAL(16,6) NOT NULL DEFAULT 0,
    origin_tvd DECIMAL(16,6) NOT NULL DEFAULT 0,
    magnetic_declination DECIMAL(10,6) NOT NULL DEFAULT 0,
    meridian_convergence DECIMAL(10,6) NOT NULL DEFAULT 0,
    algorithm_defaults_json JSON NULL,
    effective_from DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    effective_to DATETIME NULL,
    status CHAR(1) NOT NULL DEFAULT '0',
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) NULL,
    update_time DATETIME NULL,
    PRIMARY KEY (project_id, config_version),
    KEY idx_project_config_current (project_id, status, effective_to),
    CONSTRAINT fk_project_config_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目坐标与算法配置版本';

CREATE TABLE IF NOT EXISTS drilling_project_member (
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_code VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    status CHAR(1) NOT NULL DEFAULT '0',
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) NULL,
    update_time DATETIME NULL,
    PRIMARY KEY (project_id, user_id),
    KEY idx_project_member_user (user_id, status),
    CONSTRAINT fk_project_member_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户—角色—项目授权';

CREATE TABLE IF NOT EXISTS drilling_data_quality_issue (
    issue_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    well_id BIGINT NULL,
    survey_version_id BIGINT NULL,
    station_id BIGINT NULL,
    source_file_id BIGINT NULL,
    issue_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'WARNING',
    status VARCHAR(16) NOT NULL DEFAULT 'ISOLATED',
    md DECIMAL(16,6) NULL,
    raw_json JSON NULL,
    resolution_note VARCHAR(500) NULL,
    resolved_by VARCHAR(64) NULL,
    resolved_at DATETIME NULL,
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_quality_project_status (project_id, status),
    KEY idx_quality_well_md (well_id, md),
    CONSTRAINT fk_quality_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id),
    CONSTRAINT fk_quality_well FOREIGN KEY (well_id) REFERENCES drilling_well(well_id),
    CONSTRAINT fk_quality_survey FOREIGN KEY (survey_version_id) REFERENCES drilling_survey_version(survey_version_id),
    CONSTRAINT fk_quality_station FOREIGN KEY (station_id) REFERENCES drilling_survey_station(station_id),
    CONSTRAINT fk_quality_file FOREIGN KEY (source_file_id) REFERENCES data_file(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测斜质量问题隔离区';

CREATE TABLE IF NOT EXISTS drilling_realtime_connector (
    connector_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    well_id BIGINT NULL,
    connector_name VARCHAR(128) NOT NULL,
    connector_type VARCHAR(32) NOT NULL,
    endpoint_uri VARCHAR(512) NULL,
    credential_ref VARCHAR(255) NULL,
    config_json JSON NULL,
    cursor_json JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DISABLED',
    last_heartbeat DATETIME NULL,
    last_error VARCHAR(1000) NULL,
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(64) NULL,
    update_time DATETIME NULL,
    UNIQUE KEY uk_connector_project_name (project_id, connector_name),
    KEY idx_connector_status (project_id, status),
    CONSTRAINT fk_connector_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id),
    CONSTRAINT fk_connector_well FOREIGN KEY (well_id) REFERENCES drilling_well(well_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WITS/WITSML/数据库实时连接器';

CREATE TABLE IF NOT EXISTS drilling_alarm (
    alarm_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    task_id BIGINT NULL,
    result_id BIGINT NULL,
    well_id BIGINT NULL,
    alarm_code VARCHAR(64) NOT NULL,
    previous_level VARCHAR(16) NULL,
    current_level VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    message VARCHAR(1000) NOT NULL,
    acknowledged_by VARCHAR(64) NULL,
    acknowledged_at DATETIME NULL,
    resolved_by VARCHAR(64) NULL,
    resolved_at DATETIME NULL,
    rule_snapshot_json JSON NULL,
    create_by VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_alarm_project_status (project_id, status, create_time),
    KEY idx_alarm_task_result (task_id, result_id),
    CONSTRAINT fk_alarm_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id),
    CONSTRAINT fk_alarm_task FOREIGN KEY (task_id) REFERENCES anti_collision_task(task_id),
    CONSTRAINT fk_alarm_result FOREIGN KEY (result_id) REFERENCES anti_collision_result(result_id),
    CONSTRAINT fk_alarm_well FOREIGN KEY (well_id) REFERENCES drilling_well(well_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='防碰风险报警';

CREATE TABLE IF NOT EXISTS drilling_audit_log (
    audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NULL,
    user_id BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NULL,
    request_id VARCHAR(64) NULL,
    detail_json JSON NULL,
    client_ip VARCHAR(64) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_audit_project_time (project_id, create_time),
    KEY idx_audit_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钻井领域审计日志';

CREATE TABLE IF NOT EXISTS drilling_task_event (
    event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NULL,
    task_type VARCHAR(32) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    event_json JSON NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_event_idempotency (idempotency_key),
    KEY idx_task_event_task (task_type, task_id, create_time),
    CONSTRAINT fk_task_event_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='算法/训练任务幂等事件';

