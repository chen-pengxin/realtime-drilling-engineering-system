INSERT IGNORE INTO drilling_trajectory_version(survey_version_id,wellbore_id,trajectory_kind,trajectory_version_no,content_sha256,reference_json,quality_status,label)
 SELECT s.survey_version_id,b.wellbore_id,'ACTUAL',COALESCE((SELECT MAX(tn.trajectory_version_no) FROM drilling_trajectory_version tn WHERE tn.wellbore_id=b.wellbore_id AND tn.trajectory_kind='ACTUAL'),0)+ROW_NUMBER() OVER(PARTITION BY b.wellbore_id ORDER BY s.survey_version_id),SHA2(CONCAT('legacy:',s.survey_version_id),256),JSON_OBJECT('reference_status','LEGACY_REVIEW_REQUIRED'),'REVIEW_REQUIRED',CONCAT('历史版本 ',s.version_no)
 FROM drilling_survey_version s JOIN drilling_wellbore b ON b.well_id=s.well_id AND b.wellbore_code='MAIN'
 WHERE NOT EXISTS(SELECT 1 FROM drilling_trajectory_version t WHERE t.survey_version_id=s.survey_version_id);
INSERT IGNORE INTO drilling_trajectory_head(wellbore_id,trajectory_kind,survey_version_id)
 SELECT t.wellbore_id,t.trajectory_kind,t.survey_version_id FROM drilling_trajectory_version t JOIN drilling_survey_version s ON s.survey_version_id=t.survey_version_id WHERE s.is_current='1';
-- A legacy import keeps its original snapshot; only add missing revision metadata.
INSERT IGNORE INTO drilling_trajectory_revision(survey_version_id,revision_type,parent_version_id,changed_from_md,changed_to_md)
 SELECT t.survey_version_id,IF(s.import_mode='INCREMENTAL','INCREMENTAL','FULL'),
 IF(p.wellbore_id=t.wellbore_id AND p.trajectory_kind=t.trajectory_kind,s.parent_version_id,NULL),
 IF(s.import_mode='INCREMENTAL',ps.max_md,NULL),s.max_md
 FROM drilling_trajectory_version t JOIN drilling_survey_version s ON s.survey_version_id=t.survey_version_id
 LEFT JOIN drilling_trajectory_version p ON p.survey_version_id=s.parent_version_id
 LEFT JOIN drilling_survey_version ps ON ps.survey_version_id=p.survey_version_id;
