"""Validate V20 twice on a disposable clone, including role-edit preservation."""
import json
import os
from pathlib import Path
import re
import subprocess

assert os.environ.get('ACCESS_QA_ALLOW_MUTATION') == '1'
config_path = Path(os.environ['CENTERS_QA_CONFIG'])
config = json.loads(config_path.read_text())
assert re.fullmatch(r'drilling_ops_qa_centers_[0-9]+', config['database'])
schema = config['database'] + '_migration'
root = Path(__file__).resolve().parents[2]
script = (root / 'database/migrations/V20__drilling_business_centers.sql').read_bytes()
assert script == (root / 'ruoyi-java/ruoyi-admin/src/main/resources/drilling/business-centers.sql').read_bytes()
checks = []


def sql(query, database=None):
    args = ['mysql', '-uroot', '--batch', '--skip-column-names']
    if database:
        args.append(database)
    return subprocess.check_output(args, input=query.encode() if isinstance(query, str) else query).decode().strip()


def check(ok, name):
    assert ok, name
    checks.append({'name': name, 'passed': True})
    print('PASS', name)


sql(f'CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci')
try:
    sql((config_path.parent / 'source.sql').read_bytes(), schema)
    check(sql("SELECT COUNT(*) FROM drilling_schema_version WHERE version_code='V20'", schema) == '0', 'migration starts from pre-V20 schema')
    sql(script, schema)
    check(sql("SELECT COUNT(*) FROM drilling_schema_version WHERE version_code='V20'", schema) == '1', 'fresh migration applies once')
    check(sql("SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 7101 AND 7106", schema) == '6', 'six menus created')
    for tier in ('ADMIN', 'ENGINEER', 'GUEST'):
        modules = json.loads(sql(f"SELECT modules_json FROM drilling_role_policy WHERE account_tier='{tier}'", schema))
        check(set(('alerts','history','quality','reports','connectors','versions')) <= set(modules), f'{tier} built-in module policy expanded')
    before = sql('SELECT COUNT(*) FROM sys_role_menu', schema)
    sql(script, schema)
    check(sql('SELECT COUNT(*) FROM sys_role_menu', schema) == before, 'repeated migration does not duplicate grants')
    # Simulate an administrator later revoking a built-in role module and action.
    sql("UPDATE drilling_role_module rm JOIN sys_role r ON r.role_id=rm.role_id SET rm.modules_json=JSON_ARRAY('history') WHERE r.role_key='engineer'; DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id WHERE r.role_key='engineer' AND rm.menu_id=7120", schema)
    sql(script, schema)
    check(sql("SELECT rm.modules_json FROM drilling_role_module rm JOIN sys_role r ON r.role_id=rm.role_id WHERE r.role_key='engineer'", schema) == '["history"]', 'restart preserves administrator module edits')
    check(sql("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id WHERE r.role_key='engineer' AND rm.menu_id=7120", schema) == '0', 'restart preserves revoked operation permission')
finally:
    sql(f'DROP DATABASE `{schema}`')

out = root / 'artifacts/business-centers'
out.mkdir(parents=True, exist_ok=True)
(out / 'migration-checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2))
print('CHECKS', len(checks))
