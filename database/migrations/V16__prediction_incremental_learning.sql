-- Durable policy/checkpoint, created idempotently by the Java service as well.
CREATE TABLE IF NOT EXISTS drilling_incremental_learning (
    project_id BIGINT PRIMARY KEY,
    policy_json JSON NOT NULL,
    state_json JSON NOT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预测模型自动增量更新策略与进度';
