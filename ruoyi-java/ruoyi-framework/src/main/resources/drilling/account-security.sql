-- 三种账号等级及游客策略；不重置既有账号密码。可重复执行。
CREATE TABLE IF NOT EXISTS drilling_account_policy (
 user_id BIGINT PRIMARY KEY,
 account_tier VARCHAR(16) NOT NULL DEFAULT 'ENGINEER',
 must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
 valid_from DATETIME NULL,
 valid_until DATETIME NULL,
 modules_json JSON NOT NULL,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS drilling_role_policy (
 account_tier VARCHAR(16) PRIMARY KEY,
 modules_json JSON NOT NULL,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS drilling_account_audit (
 audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 actor_id BIGINT NOT NULL,
 target_id BIGINT NULL,
 action VARCHAR(40) NOT NULL,
 detail_json JSON NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO sys_role(role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time,remark)
 SELECT '工程师','engineer',2,'1','0','0','system',NOW(),'石油钻井业务操作'
 WHERE NOT EXISTS(SELECT 1 FROM sys_role WHERE role_key='engineer');
INSERT INTO sys_role(role_name,role_key,role_sort,data_scope,status,del_flag,create_by,create_time,remark)
 SELECT '游客','guest',3,'1','0','0','system',NOW(),'限时模块演示'
 WHERE NOT EXISTS(SELECT 1 FROM sys_role WHERE role_key='guest');
UPDATE sys_role SET role_name='系统管理员' WHERE role_key='admin';
UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.registerUser';
INSERT IGNORE INTO drilling_account_policy(user_id,account_tier,modules_json)
 SELECT u.user_id, IF(u.user_id=1 OR EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND r.role_key='admin' AND r.status='0'),'ADMIN','ENGINEER'), JSON_ARRAY()
 FROM sys_user u WHERE u.del_flag='0';

CREATE TABLE IF NOT EXISTS drilling_role_module (
 role_id BIGINT PRIMARY KEY,
 account_tier VARCHAR(16) NOT NULL,
 modules_json JSON NOT NULL,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT IGNORE INTO drilling_role_policy(account_tier,modules_json) VALUES
 ('ADMIN','["realtime", "anticollision", "prediction", "models", "replanning", "catalog", "surveys", "realtime-data", "config", "files"]'),('ENGINEER','["realtime", "anticollision", "prediction", "models", "replanning", "catalog", "surveys", "realtime-data", "config", "files"]'),('GUEST','["realtime", "anticollision", "prediction", "models", "replanning", "catalog", "surveys", "realtime-data", "config", "files"]');
-- Seed only once. A later administrator permission edit must survive restart.
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
 SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.perms LIKE 'drilling:%'
 WHERE r.role_key='engineer' AND r.del_flag='0'
 AND NOT EXISTS(SELECT 1 FROM drilling_role_module rm WHERE rm.role_id=r.role_id);
INSERT IGNORE INTO drilling_role_module(role_id,account_tier,modules_json)
 SELECT role_id, UPPER(role_key), '["realtime", "anticollision", "prediction", "models", "replanning", "catalog", "surveys", "realtime-data", "config", "files"]' FROM sys_role
 WHERE role_key IN ('admin','engineer','guest') AND del_flag='0';
INSERT IGNORE INTO sys_user_role(user_id,role_id)
 SELECT p.user_id,r.role_id FROM drilling_account_policy p
 JOIN sys_role r ON r.role_key COLLATE utf8mb4_general_ci=LOWER(p.account_tier) COLLATE utf8mb4_general_ci AND r.del_flag='0'
 WHERE NOT EXISTS(SELECT 1 FROM sys_user_role ur JOIN drilling_role_module rm ON rm.role_id=ur.role_id
 WHERE ur.user_id=p.user_id AND rm.account_tier=p.account_tier);

-- Immutable login ID and mutable account name share one unique login namespace.
CREATE TABLE IF NOT EXISTS drilling_account_identifier (
 identifier VARCHAR(30) NOT NULL,
 user_id BIGINT NOT NULL,
 PRIMARY KEY(identifier), KEY idx_account_identifier_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
UPDATE sys_user u SET user_name=login_name WHERE user_id IN (1,2) AND user_name='若依' AND NOT EXISTS(SELECT 1 FROM drilling_account_identifier a WHERE a.user_id=u.user_id);
INSERT IGNORE INTO drilling_account_identifier(identifier,user_id)
 SELECT login_name,user_id FROM sys_user WHERE del_flag='0' ORDER BY user_id;
INSERT IGNORE INTO drilling_account_identifier(identifier,user_id)
 SELECT user_name,user_id FROM sys_user WHERE del_flag='0' AND user_name<>'' ORDER BY user_id;
-- Preserve old immutable IDs. Resolve legacy duplicate display names to their own IDs.
UPDATE sys_user u JOIN drilling_account_identifier a
 ON a.identifier=u.user_name COLLATE utf8mb4_general_ci AND a.user_id<>u.user_id
 SET u.user_name=u.login_name WHERE u.del_flag='0';
UPDATE sys_user SET user_name=login_name WHERE del_flag='0' AND user_name='';
