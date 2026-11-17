-- V36: remove the retired commercial-software benchmark artifact.
--
-- Benchmark runs are outside the confirmed delivery boundary.  Import
-- diagnostics remain in drilling_data_quality_issue because they are needed
-- to explain duplicate or invalid source rows during version creation.
SET FOREIGN_KEY_CHECKS=0;
DROP TABLE IF EXISTS drilling_benchmark_run;
INSERT IGNORE INTO drilling_schema_version
  (version_code,script_name,status,applied_by,notes)
VALUES
  ('V36','V36__remove_benchmark_artifacts.sql','APPLIED','migration','删除商业软件对标运行记录表，保留来源导入诊断记录');
SET FOREIGN_KEY_CHECKS=1;
