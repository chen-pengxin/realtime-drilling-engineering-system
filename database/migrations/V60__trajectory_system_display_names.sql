-- 轨迹资料在系统内统一使用“井名_文件类型_版本号”，原始文件名保持不变。
UPDATE data_file f
JOIN drilling_source_document s ON s.file_id=f.file_id
JOIN drilling_engineering_document_file df ON df.source_id=s.source_id
JOIN drilling_engineering_document d ON d.document_id=df.document_id
JOIN drilling_engineering_node n ON n.node_id=d.node_id
JOIN drilling_well w ON w.well_id=n.well_id
SET f.display_name=CONCAT(w.well_name,'_',CASE COALESCE(JSON_UNQUOTE(JSON_EXTRACT(df.properties_json,'$.trajectory_kind')),CASE n.node_type WHEN 'PLAN' THEN 'DESIGN' WHEN 'SURVEY' THEN 'ACTUAL' END) WHEN 'DESIGN' THEN '设计方案' WHEN 'ACTUAL' THEN '实测测斜' ELSE '工程资料' END,'_',df.edition_no)
WHERE d.document_role IN ('TRAJECTORY_DATA','TRAJECTORY_REPORT');
INSERT IGNORE INTO drilling_schema_version(version_code,script_name,status,applied_by,notes)
VALUES('V60','V60__trajectory_system_display_names.sql','APPLIED','application','统一轨迹资料系统名称，原始文件名不变');
