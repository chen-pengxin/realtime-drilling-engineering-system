-- Training tasks are field-scoped. Older installations only had the dataset
-- relation, so add the denormalized scope used by task history and access
-- checks, then backfill it from the immutable dataset catalog.
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'training_task' AND column_name = 'field_id'),
    'SELECT 1',
    'ALTER TABLE `training_task` ADD COLUMN `field_id` BIGINT NULL AFTER `training_task_id`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
UPDATE training_task t
JOIN training_dataset d ON d.dataset_id=t.dataset_id
SET t.field_id=d.field_id
WHERE t.field_id IS NULL;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'training_task' AND index_name = 'idx_training_field_status'),
    'SELECT 1',
    'ALTER TABLE `training_task` ADD KEY `idx_training_field_status` (`field_id`, `status`)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Durable policy/checkpoint, created idempotently by the Java service as well.
CREATE TABLE IF NOT EXISTS drilling_incremental_learning (
    field_id BIGINT PRIMARY KEY,
    policy_json JSON NOT NULL,
    state_json JSON NOT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预测模型自动增量更新策略与进度';

INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V42','V42__training_task_field_scope.sql','APPLIED','application','训练任务按井场归属并从训练数据集回填');
