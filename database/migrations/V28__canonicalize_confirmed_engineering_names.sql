-- Confirmed engineering names and source-evidenced Volve aliases.
-- This migration is deliberately additive: source files, survey stations,
-- trajectory versions and task history keep their original IDs and paths.

UPDATE drilling_block
   SET block_code='COALBED-YISHAN-SLOPE',
       block_name='鄂尔多斯盆地伊陕斜坡',
       basin='鄂尔多斯盆地',
       description='采气二厂资料的工程构造位置；区块编码依据来源资料核对结果。'
 WHERE block_id=3;

UPDATE drilling_project p JOIN drilling_field f ON f.field_id=p.field_id
   SET p.project_name=CONCAT('采气二厂煤岩气完井资料—',REPLACE(f.field_name,'井场',''))
 WHERE p.project_id BETWEEN 4 AND 11;

-- Canonical Volve identity: the source text says F-* while the imported
-- directory prefix was 15_9-F-*. Archive only the empty duplicate entity and
-- preserve its original identifier in drilling_well_alias.
CREATE TEMPORARY TABLE _volve_well_alias (
  alias_well_id BIGINT PRIMARY KEY,
  canonical_well_id BIGINT NOT NULL,
  alias_code VARCHAR(128) NOT NULL,
  canonical_code VARCHAR(128) NOT NULL,
  directory_code VARCHAR(128) NOT NULL
);
INSERT INTO _volve_well_alias VALUES
 (40,12,'F-1','F-1','15_9-F-1'),
 (63,28,'F-10','F-10','15_9-F-10'),
 (46,19,'F-11','F-11','15_9-F-11'),
 (51,29,'F-12','F-12','15_9-F-12'),
 (43,17,'F-13','F-13','15_9-F-13'),
 (41,14,'F-14','F-14','15_9-F-14'),
 (38,11,'F-15','F-15','15_9-F-15'),
 (44,3,'F-2','F-2','15_9-F-2'),
 (37,6,'F-3','F-3','15_9-F-3'),
 (53,32,'F-4','F-4','15_9-F-4'),
 (52,30,'F-5','F-5','15_9-F-5'),
 (56,34,'F-6','F-6','15_9-F-6'),
 (42,15,'F-7','F-7','15_9-F-7'),
 (48,25,'F-8','F-8','15_9-F-8'),
 (49,27,'F-9','F-9','15_9-F-9'),
 (47,20,'15/9-19','15/9-19','15_9-19');

-- Free the unique project/code and catalog keys before renaming canonical
-- entities. The old code remains recoverable from the alias table.
UPDATE drilling_well w JOIN _volve_well_alias a ON a.alias_well_id=w.well_id
   SET w.well_code=CONCAT('__ARCHIVED_ALIAS_',w.well_id,'__',w.well_code),
       w.status='ARCHIVED',
       w.metadata_json=JSON_SET(COALESCE(w.metadata_json,JSON_OBJECT()),
         '$.canonical_well_id',a.canonical_well_id,'$.original_well_code',a.alias_code)
 WHERE LEFT(w.well_code,17) <> '__ARCHIVED_ALIAS_';
UPDATE drilling_well_catalog c JOIN _volve_well_alias a ON a.alias_well_id=c.well_id
   SET c.identity_key=CONCAT('__ARCHIVED_ALIAS_',c.well_id,'__',c.identity_key)
 WHERE LEFT(c.identity_key,17) <> '__ARCHIVED_ALIAS_';

INSERT INTO drilling_well_alias(alias_well_id,canonical_well_id,alias_code,reason,evidence_json)
SELECT alias_well_id,canonical_well_id,alias_code,
       '来源正文井名与目录井名核对后的历史别名归并',
       JSON_OBJECT('source_text_name',alias_code,'directory_name',directory_code,
                   'canonical_has_trajectory',TRUE,'duplicate_has_trajectory',FALSE)
  FROM _volve_well_alias
 ON DUPLICATE KEY UPDATE canonical_well_id=VALUES(canonical_well_id),
   alias_code=VALUES(alias_code),reason=VALUES(reason),evidence_json=VALUES(evidence_json);

UPDATE drilling_well w JOIN _volve_well_alias a ON a.canonical_well_id=w.well_id
   SET w.well_code=a.canonical_code,
       w.well_name=a.canonical_code,
       w.metadata_json=JSON_SET(COALESCE(w.metadata_json,JSON_OBJECT()),
         '$.canonical_code',a.canonical_code,'$.directory_alias',CONCAT('15_9-',a.alias_code),
         '$.identity_source','Volve source text metadata');
UPDATE drilling_well_catalog c JOIN _volve_well_alias a ON a.canonical_well_id=c.well_id
   SET c.identity_key=a.canonical_code;

-- Canonical wellbore labels follow the canonical well name. Relief bores keep
-- their descriptive names; only the imported 15_9-F-* prefix is normalized.
UPDATE drilling_wellbore b JOIN _volve_well_alias a ON a.canonical_well_id=b.well_id
   SET b.wellbore_code=REPLACE(b.wellbore_code,a.directory_code,a.canonical_code),
       b.wellbore_name=REPLACE(b.wellbore_name,a.directory_code,a.canonical_code);
UPDATE drilling_wellbore b JOIN _volve_well_alias a ON a.alias_well_id=b.well_id
   SET b.wellbore_code=REPLACE(b.wellbore_code,a.directory_code,a.alias_code),
       b.wellbore_name=REPLACE(b.wellbore_name,a.directory_code,a.alias_code);

UPDATE drilling_engineering_node n JOIN drilling_well w ON w.well_id=n.well_id
   SET n.node_name=w.well_name,n.node_code=w.well_code
 WHERE n.project_id=1 AND n.node_type='WELL' AND n.status='ACTIVE';
UPDATE drilling_engineering_node n JOIN _volve_well_alias a ON a.alias_well_id=n.well_id
   SET n.status='ARCHIVED'
 WHERE n.project_id=1 AND n.node_type='WELL' AND n.status='ACTIVE';
UPDATE drilling_engineering_node n
  JOIN drilling_engineering_node parent ON parent.node_id=n.parent_id
  JOIN _volve_well_alias a ON a.alias_well_id=parent.well_id
   SET n.status='ARCHIVED'
 WHERE parent.project_id=1 AND parent.node_type='WELL' AND n.status='ACTIVE';
UPDATE drilling_engineering_node n JOIN drilling_wellbore b ON b.wellbore_id=n.wellbore_id
   SET n.node_name=b.wellbore_name,n.node_code=b.wellbore_code
 WHERE n.project_id=1 AND n.node_type='WELLBORE' AND n.status='ACTIVE';

DROP TEMPORARY TABLE _volve_well_alias;
