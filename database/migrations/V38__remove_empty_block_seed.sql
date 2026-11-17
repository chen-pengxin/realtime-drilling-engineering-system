-- V38: remove the unused empty block seed left by the original demo import.
-- Blocks with fields are preserved; historical wells remain under their
-- archived field and are not deleted by this cleanup.
DELETE b FROM drilling_block b
LEFT JOIN drilling_field f ON f.block_id=b.block_id
WHERE f.block_id IS NULL;

INSERT IGNORE INTO drilling_schema_version
  (version_code,script_name,status,applied_by,notes)
VALUES
  ('V38','V38__remove_empty_block_seed.sql','APPLIED','migration','删除无井场的空区块种子记录');
