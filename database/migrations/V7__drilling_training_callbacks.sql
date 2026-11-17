-- Training callback and model artifact metadata.
-- The artifact URI is metadata: the binary remains in the algorithm model
-- volume or object storage and is never copied into MySQL. DDL operations are
-- guarded by information_schema so this migration is safe to replay.

SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'training_task' AND column_name = 'model_artifact_uri'),
    'SELECT 1',
    'ALTER TABLE `training_task` ADD COLUMN `model_artifact_uri` VARCHAR(1024) NULL AFTER `error_message`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'training_task' AND column_name = 'model_artifact_sha256'),
    'SELECT 1',
    'ALTER TABLE `training_task` ADD COLUMN `model_artifact_sha256` CHAR(64) NULL AFTER `model_artifact_uri`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'training_task' AND column_name = 'model_artifact_size_bytes'),
    'SELECT 1',
    'ALTER TABLE `training_task` ADD COLUMN `model_artifact_size_bytes` BIGINT NULL AFTER `model_artifact_sha256`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, url, target, menu_type, visible,
     is_refresh, perms, icon, create_by, create_time, remark)
VALUES
    (2033, '模型仓库', 2000, 33, '#', '', 'F', '1', '1',
     'drilling:model:list', 'fa fa-archive', 'admin', NOW(), '训练模型归档元数据查询权限')
ON DUPLICATE KEY UPDATE
    menu_name=VALUES(menu_name), parent_id=VALUES(parent_id), order_num=VALUES(order_num),
    perms=VALUES(perms), icon=VALUES(icon), update_by='admin', update_time=NOW(),
    remark=VALUES(remark);
