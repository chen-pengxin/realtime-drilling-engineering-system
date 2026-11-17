-- ISCWSA 默认模型补齐。
--
-- 新建项目由 Java 网关写入同一份快照；本迁移负责已经存在的项目，避免
-- 旧库继续使用空配置或把新防碰任务默认为井深相关半径模型。现场批准的工具码
-- 和误差参数仍可在项目配置页覆盖这些可运行默认值。

SET @iscwsa_defaults := JSON_OBJECT(
    'evaluationMode', 'BOTH',
    'thresholds', JSON_OBJECT(
        'redSf', 1.0,
        'orangeSf', 1.5,
        'yellowSf', 2.5,
        'redDistance', 5.0,
        'errorNetRatioOrange', 0.5,
        'errorNetRatioYellow', 1.0
    ),
    'errorModel', JSON_OBJECT(
        'mode', 'ISCWSA',
        'k', 1.0,
        'radiusUnder500', 2.0,
        'radiusGrowthPerM', 0.003,
        'modelVersion', 'ISCWSA-REV4-GENERIC-DEFAULT',
        'confidenceLevel', 0.95,
        'confidenceFactor', SQRT(6.0),
        'correlation', 0.0,
        'covarianceFrame', 'ENU',
        'useCrossCovariance', TRUE,
        'sigmaFloor', 0.000001,
        'toolErrorParameters', JSON_OBJECT(
            'depthSigmaM', 0.0,
            'inclinationSigmaDeg', 0.05,
            'azimuthSigmaDeg', 0.10,
            'instrumentSigmaM', 1.1547005383792515,
            'randomWalkSigmaMPerSqrtM', 0.0,
            'systematicSigmaM', 0.0
        ),
        'positionErrorParameters', JSON_OBJECT(
            'northSigmaM', 0.0,
            'eastSigmaM', 0.0,
            'tvdSigmaM', 0.0
        ),
        'correlationParameters', JSON_OBJECT(
            'wellPairCorrelation', 0.0,
            'sameWellCorrelation', 1.0
        )
    ),
    'planning', JSON_OBJECT(
        'solver', 'SLSQP',
        'maxDoglegDegPer30m', 6.0,
        'minDoglegDegPer30m', 0.0,
        'minSegmentLength', 3.0,
        'maxSegmentLength', 30.0,
        'maxToolfaceChangeDeg', 180.0,
        'minimumSf', 1.5,
        'targetToleranceM', 0.5,
        'segmentCount', 8,
        'maxIterations', 500
    )
);

-- Projects created before versioned configuration was introduced get an active
-- version.  If only archived versions exist, continue their version sequence.
INSERT INTO drilling_project_config
    (project_id, config_version, coordinate_system, north_reference,
     length_unit, angle_unit, tvd_positive, origin_north, origin_east,
     origin_tvd, magnetic_declination, meridian_convergence,
     algorithm_defaults_json, status, create_by)
SELECT p.project_id,
       COALESCE((SELECT MAX(c.config_version) + 1
                   FROM drilling_project_config c
                  WHERE c.project_id = p.project_id), 1),
       p.coordinate_system, 'TRUE_NORTH', p.length_unit, p.angle_unit,
       p.tvd_positive, 0, 0, 0, 0, 0,
       @iscwsa_defaults, '0', COALESCE(p.create_by, 'iscwsa-migration')
  FROM drilling_project p
 WHERE NOT EXISTS (
       SELECT 1
         FROM drilling_project_config c
        WHERE c.project_id = p.project_id
          AND c.status = '0'
          AND c.effective_to IS NULL
   );

-- Deep merge preserves any approved/project-specific values while filling
-- missing ISCWSA fields. Empty and old simplified snapshots are normalized to
-- the now-required ISCWSA default; the compatibility mode remains selectable
-- in a later versioned project edit when it is explicitly needed.
UPDATE drilling_project_config
   SET algorithm_defaults_json = JSON_SET(
           JSON_MERGE_PATCH(@iscwsa_defaults, COALESCE(algorithm_defaults_json, JSON_OBJECT())),
           '$.errorModel.mode',
           CASE
             WHEN JSON_UNQUOTE(JSON_EXTRACT(algorithm_defaults_json, '$.errorModel.mode')) IS NULL
               OR JSON_UNQUOTE(JSON_EXTRACT(algorithm_defaults_json, '$.errorModel.mode')) IN
                  ('SIMPLIFIED_RADIUS', 'PAPER_SIMPLIFIED', 'ISCWSA_APPROX')
             THEN 'ISCWSA'
             ELSE JSON_UNQUOTE(JSON_EXTRACT(algorithm_defaults_json, '$.errorModel.mode'))
           END
       );

UPDATE drilling_project
   SET config_json = JSON_SET(
           JSON_MERGE_PATCH(@iscwsa_defaults, COALESCE(config_json, JSON_OBJECT())),
           '$.errorModel.mode',
           CASE
             WHEN JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.errorModel.mode')) IS NULL
               OR JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.errorModel.mode')) IN
                  ('SIMPLIFIED_RADIUS', 'PAPER_SIMPLIFIED', 'ISCWSA_APPROX')
             THEN 'ISCWSA'
             ELSE JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.errorModel.mode'))
           END
       );

ALTER TABLE anti_collision_task
    MODIFY COLUMN error_model VARCHAR(32) NOT NULL DEFAULT 'ISCWSA';

INSERT IGNORE INTO drilling_schema_version
    (version_code, script_name, status, applied_by, notes)
VALUES ('V13', 'V13__iscwsa_project_defaults.sql', 'APPLIED',
        'compose-bootstrap', '补齐项目 ISCWSA 默认配置并切换防碰任务默认模型');
