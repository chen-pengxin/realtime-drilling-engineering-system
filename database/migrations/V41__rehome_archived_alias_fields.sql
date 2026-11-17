-- Rehome archived alias wells before removing the obsolete placeholder field.
-- Alias rows remain as identity history; only their field-scope links move to
-- the canonical well's field so the engineering tree has no empty branch.
UPDATE drilling_well w
JOIN drilling_well_alias a ON a.alias_well_id=w.well_id
JOIN drilling_well c ON c.well_id=a.canonical_well_id
SET w.field_id=c.field_id
WHERE w.field_id=(SELECT field_id FROM drilling_field WHERE field_code='COALBED-UNSPECIFIED-SITE' LIMIT 1);

UPDATE drilling_well_catalog wc
JOIN drilling_well_alias a ON a.alias_well_id=wc.well_id
JOIN drilling_well c ON c.well_id=a.canonical_well_id
SET wc.field_id=c.field_id
WHERE wc.field_id=(SELECT field_id FROM drilling_field WHERE field_code='COALBED-UNSPECIFIED-SITE' LIMIT 1);

UPDATE drilling_field_wellbore x
JOIN drilling_wellbore b ON b.wellbore_id=x.wellbore_id
JOIN drilling_well w ON w.well_id=b.well_id
SET x.field_id=w.field_id
WHERE x.field_id=(SELECT field_id FROM drilling_field WHERE field_code='COALBED-UNSPECIFIED-SITE' LIMIT 1);

SET @orphan_field_id := (
  SELECT field_id FROM drilling_field
   WHERE field_code='COALBED-UNSPECIFIED-SITE'
     AND NOT EXISTS (SELECT 1 FROM drilling_well WHERE field_id=drilling_field.field_id)
     AND NOT EXISTS (SELECT 1 FROM drilling_source_document WHERE field_id=drilling_field.field_id)
     AND NOT EXISTS (SELECT 1 FROM drilling_field_wellbore WHERE field_id=drilling_field.field_id)
   LIMIT 1
);
DELETE FROM drilling_field_config WHERE field_id=@orphan_field_id;
DELETE FROM drilling_field_member WHERE field_id=@orphan_field_id;
DELETE FROM drilling_field_organization WHERE field_id=@orphan_field_id;
DELETE FROM drilling_field WHERE field_id=@orphan_field_id;

INSERT IGNORE INTO drilling_schema_version
  (version_code,script_name,status,applied_by,notes)
VALUES
  ('V41','V41__rehome_archived_alias_fields.sql','APPLIED','migration','归档别名井迁回正式井场并删除空占位井场');
