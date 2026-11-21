-- V52: remove the retired application-level planning scenario.
-- The supplied algorithm owns trajectory geometry (mode and segment count);
-- the business database must not retain a second, unused scenario selector.
SET @old_foreign_key_checks = @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

DROP PROCEDURE IF EXISTS drilling_remove_replanning_scenario;
DELIMITER $$
CREATE PROCEDURE drilling_remove_replanning_scenario()
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'trajectory_plan'
       AND column_name = 'scenario'
  ) THEN
    ALTER TABLE trajectory_plan DROP COLUMN scenario;
  END IF;
END$$
DELIMITER ;
CALL drilling_remove_replanning_scenario();
DROP PROCEDURE drilling_remove_replanning_scenario;

INSERT IGNORE INTO drilling_schema_version
    (version_code, script_name, status, applied_by, notes)
VALUES
    ('V52','V52__remove_replanning_scenario.sql','APPLIED','application',
     '删除已废弃的业务规划场景字段，轨迹模式和段数由原生算法配置负责');

SET FOREIGN_KEY_CHECKS = @old_foreign_key_checks;
