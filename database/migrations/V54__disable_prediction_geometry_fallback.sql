-- Live future positions must come from the configured trained model.  Older
-- snapshots exposed a geometric fallback switch; normalize those snapshots so
-- a missing or failed model never becomes a prediction input.
UPDATE drilling_engineering_config
   SET algorithm_defaults_json = JSON_SET(
       COALESCE(algorithm_defaults_json, JSON_OBJECT()),
       '$.prediction.allowFallback', FALSE);

UPDATE drilling_field_config
   SET algorithm_defaults_json = JSON_SET(
       COALESCE(algorithm_defaults_json, JSON_OBJECT()),
       '$.prediction.allowFallback', FALSE)
 WHERE algorithm_defaults_json IS NOT NULL;

INSERT IGNORE INTO drilling_schema_version
    (version_code,script_name,status,applied_by,notes)
VALUES
    ('V54','V54__disable_prediction_geometry_fallback.sql','APPLIED','application',
     '禁用未配置模型时的几何外推，未来位置只来自训练模型');
