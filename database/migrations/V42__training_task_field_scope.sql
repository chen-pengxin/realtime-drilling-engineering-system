-- Normalize the training task scope after the project hierarchy removal.
-- Existing tasks inherit their field from the immutable dataset catalog.
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'training_task' AND column_name = 'field_id'),
    'SELECT 1',
    'ALTER TABLE `training_task` ADD COLUMN `field_id` BIGINT NULL AFTER `training_task_id`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V42','V42__training_task_field_scope.sql','APPLIED','migration','训练任务按井场归属并从训练数据集回填');
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
