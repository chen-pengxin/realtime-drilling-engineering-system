-- 实时测斜事件幂等收件箱。请在 V1--V9 已完成且
-- drilling_schema_version 已存在后执行；脚本只新增收件箱并登记 V10。
CREATE TABLE IF NOT EXISTS drilling_realtime_ingest_event (
    event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    well_id BIGINT NOT NULL,
    source_name VARCHAR(255) NOT NULL,
    event_key CHAR(64) NOT NULL,
    raw_event_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    status_message VARCHAR(512) NULL,
    survey_version_id BIGINT NULL,
    received_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_time DATETIME NULL,
    UNIQUE KEY uk_realtime_event (project_id, well_id, source_name, event_key),
    KEY idx_realtime_pending (project_id, well_id, source_name, status, event_id),
    CONSTRAINT fk_realtime_project FOREIGN KEY (project_id) REFERENCES drilling_project(project_id),
    CONSTRAINT fk_realtime_well FOREIGN KEY (well_id) REFERENCES drilling_well(well_id),
    CONSTRAINT fk_realtime_survey FOREIGN KEY (survey_version_id) REFERENCES drilling_survey_version(survey_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实时测斜事件幂等接入与恢复记录';

INSERT IGNORE INTO drilling_schema_version
    (version_code, script_name, status, applied_by, notes)
VALUES
    ('V10', 'V10__realtime_ingest_events.sql', 'APPLIED', 'compose-bootstrap', '实时事件幂等收件箱');
