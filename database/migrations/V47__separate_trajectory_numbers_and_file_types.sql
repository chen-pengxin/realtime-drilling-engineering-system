-- Public numbering is scoped to wellbore + trajectory kind. Snapshot IDs and
-- legacy well-wide sequence numbers remain untouched for historical references.
SET @numbering_column = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_trajectory_version' AND column_name='trajectory_version_no');
SET @numbering_sql = IF(@numbering_column=0,'ALTER TABLE drilling_trajectory_version ADD COLUMN trajectory_version_no INT NULL','SELECT 1');
PREPARE numbering_stmt FROM @numbering_sql;
EXECUTE numbering_stmt;
DEALLOCATE PREPARE numbering_stmt;
DROP TEMPORARY TABLE IF EXISTS drilling_missing_trajectory_numbers;
CREATE TEMPORARY TABLE drilling_missing_trajectory_numbers AS
 SELECT missing.survey_version_id,
   COALESCE(existing.maximum_no,0)+ROW_NUMBER() OVER(PARTITION BY missing.wellbore_id,missing.trajectory_kind ORDER BY missing.survey_version_id) AS number_to_assign
 FROM drilling_trajectory_version missing
 LEFT JOIN (SELECT wellbore_id,trajectory_kind,MAX(trajectory_version_no) maximum_no FROM drilling_trajectory_version GROUP BY wellbore_id,trajectory_kind) existing
   ON existing.wellbore_id=missing.wellbore_id AND existing.trajectory_kind=missing.trajectory_kind
 WHERE missing.trajectory_version_no IS NULL;
UPDATE drilling_trajectory_version t JOIN drilling_missing_trajectory_numbers n ON n.survey_version_id=t.survey_version_id SET t.trajectory_version_no=n.number_to_assign;
DROP TEMPORARY TABLE drilling_missing_trajectory_numbers;
SET @numbering_nullable = (SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drilling_trajectory_version' AND column_name='trajectory_version_no');
SET @numbering_sql = IF(@numbering_nullable='YES','ALTER TABLE drilling_trajectory_version MODIFY COLUMN trajectory_version_no INT NOT NULL','SELECT 1');
PREPARE numbering_stmt FROM @numbering_sql;
EXECUTE numbering_stmt;
DEALLOCATE PREPARE numbering_stmt;
SET @numbering_index = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='drilling_trajectory_version' AND index_name='uk_trajectory_number');
SET @numbering_sql = IF(@numbering_index=0,'ALTER TABLE drilling_trajectory_version ADD UNIQUE KEY uk_trajectory_number(wellbore_id,trajectory_kind,trajectory_version_no)','SELECT 1');
PREPARE numbering_stmt FROM @numbering_sql;
EXECUTE numbering_stmt;
DEALLOCATE PREPARE numbering_stmt;

-- Fill only missing/null file types from the explicit engineering result node.
UPDATE drilling_engineering_document_file df
 JOIN drilling_engineering_document d ON d.document_id=df.document_id
 JOIN drilling_engineering_node n ON n.node_id=d.node_id
 SET df.properties_json=JSON_SET(COALESCE(df.properties_json,JSON_OBJECT()),'$.trajectory_kind',IF(n.node_type='PLAN','DESIGN','ACTUAL'))
 WHERE d.document_role IN ('TRAJECTORY_DATA','TRAJECTORY_REPORT') AND n.node_type IN ('PLAN','SURVEY')
 AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(df.properties_json,'$.trajectory_kind')),'null') IN ('','null');

INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes) VALUES('V47','V47__separate_trajectory_numbers_and_file_types.sql','APPLIED','application','Independent design and actual version numbering; explicit file classification');
