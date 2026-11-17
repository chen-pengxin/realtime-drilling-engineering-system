-- 首次部署时登记领域迁移版本，避免仅靠人工记忆判断 V1--V13 是否执行。
-- 对已有数据库执行本脚本前，应先核对实际表结构；本脚本只登记清单，
-- 不会补执行缺失迁移。新库由 Compose 按顺序执行 V1--V13。

CREATE TABLE IF NOT EXISTS drilling_schema_version (
    version_code VARCHAR(32) NOT NULL,
    script_name VARCHAR(255) NOT NULL,
    script_sha256 CHAR(64) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'APPLIED',
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_by VARCHAR(64) NULL,
    notes VARCHAR(500) NULL,
    PRIMARY KEY (version_code),
    KEY idx_schema_version_time (applied_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钻井领域迁移执行记录';

-- These rows are a bootstrap manifest for a fresh Compose database.  INSERT
-- IGNORE keeps the table idempotent while preserving any audited record in an
-- existing database.
INSERT IGNORE INTO drilling_schema_version
    (version_code, script_name, status, applied_by, notes)
VALUES
    ('V1', 'V1__drilling_domain.sql', 'APPLIED', 'compose-bootstrap', '领域基础表'),
    ('V2', 'V2__drilling_operations.sql', 'APPLIED', 'compose-bootstrap', '运行层与实时连接器'),
    ('V3', 'V3__ruoyi_drilling_menu.sql', 'APPLIED', 'compose-bootstrap', 'RuoYi 菜单权限'),
    ('V4', 'V4__drilling_contracts.sql', 'APPLIED', 'compose-bootstrap', '业务契约字段'),
    ('V5', 'V5__drilling_catalog_files.sql', 'APPLIED', 'compose-bootstrap', '训练目录与文件目录'),
    ('V6', 'V6__drilling_training_import_snapshots.sql', 'APPLIED', 'compose-bootstrap', '训练导入快照'),
    ('V7', 'V7__drilling_training_callbacks.sql', 'APPLIED', 'compose-bootstrap', '训练回调与模型归档'),
    ('V8', 'V8__collision_mode_upgrade.sql', 'APPLIED', 'compose-bootstrap', '防碰模式约束'),
    ('V9', 'V9__drilling_migration_history.sql', 'APPLIED', 'compose-bootstrap', '迁移历史表');
