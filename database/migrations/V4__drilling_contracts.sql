-- 运行层契约补充迁移。
-- V1/V2 保留基础领域表；本迁移只补齐版本关系、任务外部 ID、预测业务
-- 可缺省井和项目级配置校验所需的字段。
--
-- 本脚本可以安全重放：每个新增列、索引和约束都会先查询
-- information_schema。若数据库已执行过不含 ERROR_MODEL_DIFFERENCE 的旧版
-- V4，请继续执行 V8__collision_mode_upgrade.sql；V4 会保留已有旧约束，V8
-- 负责重建它。

-- 自引用文件版本关系。
SET @ddl := IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'data_file'
          AND constraint_name = 'fk_file_parent_file'
    ),
    'SELECT 1',
    'ALTER TABLE `data_file` ADD CONSTRAINT `fk_file_parent_file` FOREIGN KEY (`parent_file_id`) REFERENCES `data_file` (`file_id`)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 测斜导入模式约束。
SET @ddl := IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'drilling_survey_version'
          AND constraint_name = 'chk_survey_import_mode'
    ),
    'SELECT 1',
    'ALTER TABLE `drilling_survey_version` ADD CONSTRAINT `chk_survey_import_mode` CHECK (`import_mode` IN (''FULL'', ''INCREMENTAL'', ''REPLACE''))'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 防碰评价模式约束。旧版约束由 V8 统一升级。
SET @ddl := IF(
    EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE() AND table_name = 'anti_collision_task'
          AND constraint_name = 'chk_collision_mode'
    ),
    'SELECT 1',
    'ALTER TABLE `anti_collision_task` ADD CONSTRAINT `chk_collision_mode` CHECK (`evaluation_mode` IN (''SPACING'', ''SEPARATION_FACTOR'', ''ERROR_MODEL_DIFFERENCE'', ''ERROR_MODEL''))'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- prediction_task 兼容姿态预测业务契约（姿态模型本身仍可配置为未启用）。
ALTER TABLE `prediction_task` MODIFY COLUMN `well_id` BIGINT NULL;

SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'prediction_task' AND column_name = 'task_id'),
    'SELECT 1',
    'ALTER TABLE `prediction_task` ADD COLUMN `task_id` VARCHAR(128) NULL AFTER `prediction_id`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'prediction_task' AND column_name = 'survey_version_id'),
    'SELECT 1',
    'ALTER TABLE `prediction_task` ADD COLUMN `survey_version_id` BIGINT NULL AFTER `input_file_id`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'prediction_task' AND column_name = 'model_version'),
    'SELECT 1',
    'ALTER TABLE `prediction_task` ADD COLUMN `model_version` VARCHAR(200) NULL AFTER `prediction_step`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'prediction_task' AND column_name = 'device'),
    'SELECT 1',
    'ALTER TABLE `prediction_task` ADD COLUMN `device` VARCHAR(32) NOT NULL DEFAULT ''auto'' AFTER `model_version`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'prediction_task' AND column_name = 'input_summary_json'),
    'SELECT 1',
    'ALTER TABLE `prediction_task` ADD COLUMN `input_summary_json` JSON NULL AFTER `device`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'prediction_task' AND column_name = 'update_time'),
    'SELECT 1',
    'ALTER TABLE `prediction_task` ADD COLUMN `update_time` DATETIME NULL AFTER `create_time`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'prediction_task' AND index_name = 'uk_prediction_external_task'),
    'SELECT 1',
    'ALTER TABLE `prediction_task` ADD UNIQUE KEY `uk_prediction_external_task` (`task_id`)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'prediction_task' AND index_name = 'idx_prediction_survey'),
    'SELECT 1',
    'ALTER TABLE `prediction_task` ADD KEY `idx_prediction_survey` (`survey_version_id`)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'prediction_task' AND constraint_name = 'fk_prediction_survey'),
    'SELECT 1',
    'ALTER TABLE `prediction_task` ADD CONSTRAINT `fk_prediction_survey` FOREIGN KEY (`survey_version_id`) REFERENCES `drilling_survey_version` (`survey_version_id`)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 训练任务外部回调契约。
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'training_task' AND column_name = 'external_task_id'),
    'SELECT 1',
    'ALTER TABLE `training_task` ADD COLUMN `external_task_id` VARCHAR(128) NULL AFTER `training_task_id`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'training_task' AND column_name = 'metrics_json'),
    'SELECT 1',
    'ALTER TABLE `training_task` ADD COLUMN `metrics_json` JSON NULL AFTER `log_stream_key`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'training_task' AND column_name = 'last_callback_at'),
    'SELECT 1',
    'ALTER TABLE `training_task` ADD COLUMN `last_callback_at` DATETIME NULL AFTER `finish_time`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'training_task' AND index_name = 'uk_training_external_task'),
    'SELECT 1',
    'ALTER TABLE `training_task` ADD UNIQUE KEY `uk_training_external_task` (`external_task_id`)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'drilling_task_event' AND index_name = 'idx_task_event_idempotency_time'),
    'SELECT 1',
    'ALTER TABLE `drilling_task_event` ADD KEY `idx_task_event_idempotency_time` (`idempotency_key`, `create_time`)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible,
     is_refresh, perms, icon, create_by, create_time, remark)
VALUES
    (2019, '预测任务登记', 2000, 19, '#', '', 'F', '1', '1',
     'drilling:prediction:run', 'fa fa-play', 'admin', NOW(), '登记和取消姿态预测业务任务')
ON DUPLICATE KEY UPDATE perms=VALUES(perms), menu_name=VALUES(menu_name), remark=VALUES(remark);
