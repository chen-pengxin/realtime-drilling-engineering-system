-- Training catalog paths and immutable survey import configuration snapshots.
-- Each DDL operation checks information_schema so the version can be safely
-- replayed when an existing database needs a schema audit or repair.

SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'drilling_survey_version' AND column_name = 'config_version'),
    'SELECT 1',
    'ALTER TABLE `drilling_survey_version` ADD COLUMN `config_version` INT NULL AFTER `source_file_id`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'drilling_survey_version' AND column_name = 'config_snapshot_json'),
    'SELECT 1',
    'ALTER TABLE `drilling_survey_version` ADD COLUMN `config_snapshot_json` JSON NULL AFTER `quality_summary_json`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'drilling_survey_version' AND index_name = 'idx_survey_config'),
    'SELECT 1',
    'ALTER TABLE `drilling_survey_version` ADD KEY `idx_survey_config` (`well_id`, `config_version`)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'training_dataset' AND column_name = 'runtime_path'),
    'SELECT 1',
    'ALTER TABLE `training_dataset` ADD COLUMN `runtime_path` VARCHAR(500) NULL AFTER `file_id`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'training_dataset' AND index_name = 'idx_dataset_runtime'),
    'SELECT 1',
    'ALTER TABLE `training_dataset` ADD KEY `idx_dataset_runtime` (`project_id`, `runtime_path`)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'training_template' AND column_name = 'runtime_path'),
    'SELECT 1',
    'ALTER TABLE `training_template` ADD COLUMN `runtime_path` VARCHAR(500) NULL AFTER `file_id`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'training_template' AND index_name = 'idx_template_runtime'),
    'SELECT 1',
    'ALTER TABLE `training_template` ADD KEY `idx_template_runtime` (`project_id`, `runtime_path`)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'training_task' AND column_name = 'update_time'),
    'SELECT 1',
    'ALTER TABLE `training_task` ADD COLUMN `update_time` DATETIME NULL AFTER `finish_time`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'training_task' AND column_name = 'error_message'),
    'SELECT 1',
    'ALTER TABLE `training_task` ADD COLUMN `error_message` VARCHAR(1000) NULL AFTER `update_time`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible,
     is_refresh, perms, icon, create_by, create_time, remark)
VALUES
    (2031, '训练数据集目录', 2000, 31, '#', '', 'F', '1', '1',
     'drilling:training:catalog', 'fa fa-database', 'admin', NOW(), '项目级训练数据集元数据'),
    (2032, '训练模板目录', 2000, 32, '#', '', 'F', '1', '1',
     'drilling:training:catalog', 'fa fa-code', 'admin', NOW(), '项目级训练脚本模板元数据')
ON DUPLICATE KEY UPDATE
    menu_name=VALUES(menu_name), parent_id=VALUES(parent_id), order_num=VALUES(order_num),
    perms=VALUES(perms), icon=VALUES(icon), update_by='admin', update_time=NOW(),
    remark=VALUES(remark);
