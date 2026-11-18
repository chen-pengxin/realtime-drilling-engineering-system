-- MD=0 is not an evaluation prerequisite.  Existing imports that were marked
-- blocked solely by that obsolete gate are promoted while retaining their
-- immutable stations and source metadata.
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
   AND JSON_UNQUOTE(JSON_EXTRACT(v.reference_json,'$.reference_status'))='MD0_HISTORY_REQUIRED'
   AND NOT EXISTS(SELECT 1 FROM drilling_schema_version WHERE version_code='V50');
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
 VALUES('V50','V50__allow_nonzero_md_tie_on.sql','APPLIED','application','Use first real station as local tie-on; MD=0 is not required');
