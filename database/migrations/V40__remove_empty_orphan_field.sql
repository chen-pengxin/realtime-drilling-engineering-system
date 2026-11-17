-- Remove the bootstrap field that has no well, wellbore, source or document.
-- A real field is retained even when it currently has no files; this cleanup
-- only targets the known empty import placeholder and is idempotent.
SET @orphan_field_id := (
  SELECT field_id FROM drilling_field
   WHERE field_code='COALBED-UNSPECIFIED-SITE'
     AND NOT EXISTS (SELECT 1 FROM drilling_well WHERE field_id=drilling_field.field_id)
     AND NOT EXISTS (SELECT 1 FROM drilling_source_document WHERE field_id=drilling_field.field_id)
   LIMIT 1
);
DELETE FROM drilling_field_config WHERE field_id=@orphan_field_id;
DELETE FROM drilling_field_member WHERE field_id=@orphan_field_id;
DELETE FROM drilling_field_organization WHERE field_id=@orphan_field_id;
DELETE FROM drilling_field_wellbore WHERE field_id=@orphan_field_id;
DELETE FROM drilling_field WHERE field_id=@orphan_field_id;

INSERT IGNORE INTO drilling_schema_version
  (version_code,script_name,status,applied_by,notes)
VALUES
  ('V40','V40__remove_empty_orphan_field.sql','APPLIED','migration','删除无井、无来源的空井场占位记录');
