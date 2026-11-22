"""Local deployment backup worker. All input and output locations are server-owned."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import fcntl
import errno
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import subprocess
import tempfile
import uuid

BASE = Path(__file__).resolve().parents[2]
BIN = BASE / 'runtime/bin'
VAULT = BASE / 'backups'
RECOVERIES = BASE / 'recoveries'
NAME = re.compile(r'\d{8}T\d{6}Z-[0-9a-f]{8}')
SOURCES = {'uploads': BASE / 'uploads', 'models': BASE / 'app/.local-runtime/models'}


def identifier():
    return datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:8]


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def files(directory):
    return sorted(path for path in directory.rglob('*') if path.is_file())


class BackupError(Exception):
    """A diagnostic suitable for the operator, without command output or credentials."""


def now():
    return datetime.now(timezone.utc).isoformat()


def atomic_json(path, value):
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False) as stream:
        json.dump(value, stream, ensure_ascii=False)
        temporary = Path(stream.name)
    temporary.replace(path)


def scope_files(root):
    if root.is_symlink() or not root.is_dir():
        raise BackupError('源目录不存在或不可读取')
    result = {}
    def on_error(error):
        raise error
    for parent, dirs, names in os.walk(root, onerror=on_error):
        parent = Path(parent)
        dirs[:] = [name for name in dirs if not (parent / name).is_symlink()]
        for name in names:
            path = parent / name
            if not path.is_symlink() and path.is_file():
                st = path.stat()
                result[path.relative_to(root)] = (st.st_size, st.st_mtime_ns, st.st_ctime_ns, st.st_ino)
    return result


def backup_scopes(root, hashes):
    if not isinstance(hashes,dict) or 'database.sql' not in hashes or not (root/'database.sql').is_file() or (root/'database.sql').is_symlink():
        raise BackupError('缺少数据库备份文件')
    scopes = {'database': {'files': 1, 'bytes': (root/'database.sql').stat().st_size}}
    for name in SOURCES:
        items = scope_files(root/name)
        if {str(Path(name)/p) for p in items} != {p for p in hashes if p.startswith(name+'/')}:
            raise BackupError('备份文件与清单不一致')
        scopes[name] = {'files': len(items), 'bytes': sum(st[0] for st in items.values())}
    return scopes


def inventory():
    records = []
    for path in sorted(VAULT.iterdir(), reverse=True):
        if not NAME.fullmatch(path.name) or path.is_symlink():
            continue
        try:
            item = json.loads((path/'manifest.json').read_text())
            if item['id'] != path.name:
                raise ValueError('invalid manifest')
            scopes = backup_scopes(path, json.loads((path/'checksums.json').read_text()))
            if 'scopes' in item and item['scopes'] != scopes:
                raise BackupError('备份范围与清单不一致')
            item['scopes'] = scopes
            records.append(item)
        except (OSError, ValueError, KeyError, BackupError):
            records.append({'id':path.name, 'unavailable':True})
    return records


def history():
    directory = VAULT/'.operations'
    if not directory.is_dir():
        return []
    events = []
    for path in sorted(directory.glob('*.json'), key=lambda p:p.stat().st_mtime_ns, reverse=True)[:100]:
        event = json.loads(path.read_text())
        if event['outcome'] == 'RUNNING':
            # Check the per-attempt lock, not a recycled process id.
            with path.with_suffix('.lock').open('a') as handle:
                try:
                    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    pass
                else:
                    event = json.loads(path.read_text())
                    if event['outcome']=='RUNNING':
                        receipt = (VAULT/event['backupId']/'manifest.json' if event['action']=='backup'
                                   else RECOVERIES/event['operationId']/'recovery.json')
                        if receipt.is_file():
                            event.update(outcome='SUCCESS',finished=json.loads(receipt.read_text()).get('created'))
                            if event['action']=='restore':
                                event['recovery']=event['operationId']
                        else:
                            event.update(outcome='FAILURE',reason='任务已中断，未生成完整副本')
        events.append(event)
    return events


@contextmanager
def exclusive():
    with (VAULT / '.lock').open('a') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def copy_files(source, target):
    def exclude_links(directory, names):
        return [name for name in names if (Path(directory) / name).is_symlink()]
    if source.is_symlink() or not source.is_dir():
        raise ValueError('source directory unavailable')
    shutil.copytree(source, target, ignore=exclude_links)


def backup(days, event):
    if not 1 <= days <= 365:
        raise BackupError('保留天数必须在 1 至 365 天之间')
    key = event['backupId']
    password = os.environ.get('SPRING_DATASOURCE_DRUID_MASTER_PASSWORD') or os.environ.get('MYSQL_PASSWORD')
    if not password:
        raise BackupError('未配置数据库备份凭据')
    db_user = os.environ.get('SPRING_DATASOURCE_DRUID_MASTER_USERNAME') or os.environ.get('MYSQL_USER')
    if not db_user or not all(os.environ.get(k) for k in ('MYSQL_DATABASE','MYSQL_PORT')):
        raise BackupError('数据库备份连接配置不完整')
    with tempfile.TemporaryDirectory(dir=VAULT) as temp:
        stage = Path(temp) / key
        stage.mkdir()
        before = {}
        for name, path in SOURCES.items():
            event['stage'] = name
            before[name] = scope_files(path)
        event['stage'] = 'database'
        with (stage / 'database.sql').open('wb') as stream:
            subprocess.run([BIN / 'mysqldump', '--no-defaults', '--protocol=tcp', '-h127.0.0.1',
                            '-P' + os.environ['MYSQL_PORT'], '-u' + db_user,
                            '--single-transaction', '--quick', '--no-tablespaces', '--hex-blob',
                            '--set-gtid-purged=OFF', '--routines', '--events', os.environ['MYSQL_DATABASE']],
                           env={**os.environ, 'MYSQL_PWD': password}, stdout=stream, check=True)
        if not (stage/'database.sql').stat().st_size:
            raise BackupError('数据库导出文件为空')
        for name, path in SOURCES.items():
            event['stage'] = name
            copy_files(path, stage / name)
        hashes = {str(p.relative_to(stage)): digest(p) for p in files(stage)}
        for name, path in SOURCES.items():
            event['stage'] = name
            if scope_files(path) != before[name]:
                raise BackupError('备份期间源文件发生变化，请稍后重试')
        scopes = backup_scopes(stage, hashes)
        summary = dict(id=key, created=now(), files=len(hashes), scopes=scopes, actor=event['actor'],
                       bytes=sum(scope['bytes'] for scope in scopes.values()))
        atomic_json(stage/'checksums.json', hashes)
        atomic_json(stage/'manifest.json', summary)
        stage.rename(VAULT / key)
    cutoff = datetime.now(timezone.utc) - timedelta(days=days)
    try:
        for entry in inventory():
            if not entry.get('unavailable') and datetime.fromisoformat(entry['created']) < cutoff:
                shutil.rmtree(VAULT / entry['id'])
    except (OSError, ValueError):
        event['warning'] = '新备份已完成，部分过期备份未能清理'
    return summary


def restore(key, event):
    if not NAME.fullmatch(key):
        raise BackupError('备份编号格式错误')
    source = VAULT / key
    if source.is_symlink() or not source.is_dir():
        raise BackupError('指定备份不存在')
    hashes = json.loads((source / 'checksums.json').read_text())
    scopes = backup_scopes(source, hashes)
    if sum(item['files'] for item in scopes.values()) != len(hashes):
        raise BackupError('备份清单含有未识别的文件')

    for name, expected in hashes.items():
        path = (source / name).resolve()
        if not path.is_relative_to(source) or not path.is_file() or digest(path) != expected:
            raise BackupError('备份文件校验失败，未执行恢复')
    RECOVERIES.mkdir(exist_ok=True)
    # Restore into a separate MySQL data directory with a private local socket.
    result = event['operationId']
    event['stage'] = 'database'
    with tempfile.TemporaryDirectory(dir=RECOVERIES) as temp, tempfile.TemporaryDirectory(dir='/tmp') as sockets:
        stage = Path(temp) / result
        stage.mkdir()
        args = [BIN / 'mysqld', '--no-defaults', '--basedir=' + str(BASE / 'runtime'),
                '--datadir=' + str(stage / 'db'), '--log-error=' + str(stage / 'mysql.log')]
        subprocess.run([*args, '--initialize-insecure'], check=True)
        sock = '--socket=' + sockets + '/mysql.sock'
        subprocess.run([*args, '--skip-networking', '--mysqlx=OFF', '--event-scheduler=OFF', sock, '--daemonize'], check=True)
        client = [BIN / 'mysql', '--no-defaults', '--host=localhost', '--protocol=socket', sock, '-uroot']
        try:
            subprocess.run([*client, '-e', 'CREATE DATABASE ry CHARACTER SET utf8mb4'], check=True)
            with (source / 'database.sql').open('rb') as stream:
                subprocess.run([*client, 'ry'], stdin=stream, check=True)
            tables = subprocess.check_output([*client,'--batch','--skip-column-names','ry','-e','SHOW TABLES'])
            if not tables.strip():
                raise BackupError('导入后未找到业务表')
            for name in SOURCES:
                event['stage'] = name
                copy_files(source / name, stage / name)
                for path in files(stage / name):
                    if digest(path) != hashes[str(path.relative_to(stage))]:
                        raise BackupError('恢复后的文件校验失败')
            event['stage'] = 'verify'
            atomic_json(stage/'recovery.json',dict(backupId=key, recovery=result, created=now(),
                        actor=event['actor'], scopes=scopes, databaseTables=len(tables.splitlines())))
            # Preserve a private password for this offline copy.
            import secrets
            password = secrets.token_hex(32)
            sql = "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + password + "'"
            subprocess.run(client, input=sql.encode(), check=True)
            (stage / 'client.cnf').write_text('[client]\nuser=root\npassword=' + password + '\n')
        finally:
            config = stage / 'client.cnf'
            defaults = '--defaults-file=' + str(config) if config.exists() else '--no-defaults'
            subprocess.run([BIN / 'mysqladmin', defaults, '--host=localhost', '--protocol=socket', sock, '-uroot', 'shutdown'], check=True)
        stage.rename(RECOVERIES / result)
    return {'recovery': result, 'backupId': key}


def run_operation(action, value, confirmation=None):
    op = identifier()
    key = op if action=='backup' else value
    event = dict(operationId=op, backupId=key, action=action, started=now(), outcome='RUNNING',
                 actor=os.environ.get('DRILLING_BACKUP_ACTOR') or '本机维护 / '+getpass.getuser(),
                 stage='prepare')
    directory = VAULT/'.operations'
    directory.mkdir(exist_ok=True)
    journal = directory/(op+'.json')
    with journal.with_suffix('.lock').open('a') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        atomic_json(journal,event)
        try:
            if action=='restore' and (not NAME.fullmatch(key) or confirmation!=key):
                raise BackupError('请再次输入完整备份编号以确认恢复')
            with exclusive():
                if action=='backup':
                    result = backup(int(value),event)
                else:
                    event['stage']='verify'
                    result = restore(key,event)
            event.update(outcome='SUCCESS',stage='complete')
            if action=='restore':
                event['recovery']=result['recovery']
        except (OSError, ValueError, KeyError, BackupError, subprocess.SubprocessError) as exc:
            if isinstance(exc, BackupError):
                reason=str(exc)
            elif isinstance(exc, BlockingIOError):
                reason='已有备份或恢复正在执行'
            elif isinstance(exc, OSError) and exc.errno==errno.ENOSPC:
                reason='磁盘可用空间不足'
            else:
                reason={'database':'数据库操作失败，请检查连接、权限和可用空间',
                        'uploads':'工程文件读取或写入失败','models':'模型文件读取或写入失败',
                        'verify':'完整性校验失败，备份可能不完整'}.get(event['stage'],'备份目录或执行配置不可用')
            event.update(outcome='FAILURE',reason=reason)
            result={'error':reason}
        event['finished']=now()
        atomic_json(journal,event)
    return {**result, 'operationId':op, 'backupId':key, **({'warning':event['warning']} if 'warning' in event else {})}


if __name__ == '__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['list', 'status', 'backup', 'restore'])
    parser.add_argument('value', nargs='?')
    parser.add_argument('--confirm')
    args = parser.parse_args()
    VAULT.mkdir(exist_ok=True)
    if args.action in ('list','status'):
        result = inventory() if args.action=='list' else {'records':inventory(),'history':history()}
    else:
        result = run_operation(args.action,args.value or '',args.confirm)
    print(json.dumps(result))
    if isinstance(result, dict) and result.get('error'):
        sys.exit(1)
