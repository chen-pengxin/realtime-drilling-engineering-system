-- Persist versioned commercial-software benchmark comparisons by project.
-- Results are prototype evidence only; configured tolerances are not acceptance approval.
-- Prerequisites for an existing database: V1 (drilling_project), V3
-- (sys_menu/sys_role_menu), and V9 (drilling_schema_version) through V11
-- must already be audited/applied. The statements are safe to replay for the
-- same schema; they do not repair a partially upgraded database.

CREATE TABLE IF NOT EXISTS drilling_benchmark_run (
    benchmark_run_id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    benchmark_id VARCHAR(128) NOT NULL,
    benchmark_name VARCHAR(200) NOT NULL,
    software_name VARCHAR(128) NOT NULL,
    software_version VARCHAR(128) NOT NULL,
    dataset_name VARCHAR(200) NOT NULL,
    dataset_version VARCHAR(128) NULL,
    source_sha256 CHAR(64) NULL,
    coordinate_system VARCHAR(128) NULL,
    alignment_method VARCHAR(32) NOT NULL,
    scope_json JSON NOT NULL,
    well_count INT NOT NULL,
    observation_count INT NOT NULL,
    assessment_status VARCHAR(40) NOT NULL DEFAULT 'PROTOTYPE_COMPARISON_ONLY',
    result_json JSON NOT NULL,
    create_by BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (benchmark_run_id),
    KEY idx_benchmark_project_time (project_id, create_time),
    KEY idx_benchmark_project_key (project_id, benchmark_id),
    CONSTRAINT fk_benchmark_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商业软件对标及多井验证记录';

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible,
     is_refresh, perms, icon, create_by, create_time, remark)
VALUES
    (2037, '商业软件对标查询', 2000, 37, '#', '', 'F', '1', '1', 'drilling:benchmark:list', 'fa fa-bar-chart', 'admin', NOW(), '项目商业软件和多井对标记录查询权限'),
    (2038, '商业软件对标运行', 2000, 38, '#', '', 'F', '1', '1', 'drilling:benchmark:run', 'fa fa-balance-scale', 'admin', NOW(), '项目商业软件对标比较运行权限')
ON DUPLICATE KEY UPDATE
    menu_name=VALUES(menu_name), parent_id=VALUES(parent_id), order_num=VALUES(order_num),
    menu_type=VALUES(menu_type), visible=VALUES(visible), is_refresh=VALUES(is_refresh),
    perms=VALUES(perms), icon=VALUES(icon), update_by='admin', update_time=NOW(), remark=VALUES(remark);

-- Ordinary users may inspect benchmark history; running comparisons remains
-- an OWNER/ENGINEER project operation enforced by the Java gateway.
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, menu_id FROM sys_menu WHERE menu_id = 2037;

-- V11 originally used occupied menu IDs 2019-2021. Restore those original
-- permissions so rerunning V11 on older installations cannot hide them.
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible,
     is_refresh, perms, icon, create_by, create_time, remark)
VALUES
    (2019, '预测任务登记', 2000, 19, '#', '', 'F', '1', '1', 'drilling:prediction:run', 'fa fa-play', 'admin', NOW(), '登记和取消姿态预测业务任务'),
    (2020, '区块查询', 2000, 20, '#', '', 'F', '1', '1', 'drilling:block:list', 'fa fa-map', 'admin', NOW(), '区块查询权限'),
    (2021, '区块新增', 2000, 21, '#', '', 'F', '1', '1', 'drilling:block:add', 'fa fa-plus', 'admin', NOW(), '区块新增权限')
ON DUPLICATE KEY UPDATE
    menu_name=VALUES(menu_name), parent_id=VALUES(parent_id), order_num=VALUES(order_num),
    menu_type=VALUES(menu_type), visible=VALUES(visible), is_refresh=VALUES(is_refresh),
    perms=VALUES(perms), icon=VALUES(icon), update_by='admin', update_time=NOW(), remark=VALUES(remark);

INSERT IGNORE INTO drilling_schema_version
    (version_code, script_name, status, applied_by, notes)
VALUES ('V12', 'V12__commercial_benchmark_runs.sql', 'APPLIED', 'compose-bootstrap', '商业软件对标记录与20口测试井流程');
