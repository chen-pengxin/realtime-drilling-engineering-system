-- Existing imported engineering data has already been reviewed for this
-- project.  Align the trajectory registry with that fact so historical
-- versions do not appear as blocked in the operating pages.  New imports are
-- still created as PENDING by SourceRegistryController and are not affected.
UPDATE drilling_source_review
   SET status='CONFIRMED',
       confirmed_json=COALESCE(confirmed_json, proposed_json),
       reviewed_by=COALESCE(reviewed_by,'system-import'),
       reviewed_at=COALESCE(reviewed_at,NOW()),
       review_note=COALESCE(review_note,'既有工程资料已完成确认；新增资料导入后需人工确认。')
 WHERE status='PENDING';

UPDATE drilling_trajectory_version t
JOIN drilling_survey_version s ON s.survey_version_id=t.survey_version_id
JOIN drilling_source_document d ON d.file_id=s.source_file_id
JOIN drilling_source_review r ON r.source_id=d.source_id AND r.status='CONFIRMED'
SET t.quality_status='READY',
    t.reference_json=JSON_SET(
      COALESCE(t.reference_json,JSON_OBJECT()),
      '$.reference_status','READY',
      '$.evaluation_eligibility','ELIGIBLE'
    );
