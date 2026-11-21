-- V56: a non-zero first survey station is a valid local tie-on.
-- This was historically registered as V50, but V50 now owns the shared
-- engineering configuration. Keep the data update idempotent and use its own
-- migration version so a fresh installation cannot skip it.
UPDATE drilling_trajectory_version v
JOIN (
  SELECT survey_version_id
    FROM drilling_survey_station
   GROUP BY survey_version_id
  HAVING COUNT(*) >= 2
) s ON s.survey_version_id=v.survey_version_id
   SET quality_status='READY',
       reference_json=JSON_SET(COALESCE(reference_json,JSON_OBJECT()),
         '$.reference_status','READY',
         '$.evaluation_eligibility','ELIGIBLE',
         '$.evaluation_message','当前版本可用于跨井评价')
 WHERE v.quality_status='REFERENCE_REQUIRED'
   AND JSON_UNQUOTE(JSON_EXTRACT(v.reference_json,'$.reference_status'))='MD0_HISTORY_REQUIRED';

INSERT IGNORE INTO drilling_schema_version
    (version_code,script_name,status,applied_by,notes)
VALUES
    ('V56','V56__allow_nonzero_md_tie_on.sql','APPLIED','application',
     '首测点可作为本地接点；非零 MD 不再作为防碰评价前置条件');
