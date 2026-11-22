"""Bounded native Word 97-2003 reader, built with Maven alongside the app."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def read_word(data: bytes, picture: int | None = None):
    helper = Path(os.environ.get('DRILLING_LEGACY_WORD_READER',
        str(Path(__file__).resolve().parents[2] / 'tools/legacy-office-reader/target/legacy-office-reader.jar')))
    java = shutil.which('java')
    if not helper.is_file() or not java:
        raise ValueError('旧版Word读取组件未安装，请执行启动脚本的 --build 或构建 tools/legacy-office-reader')
    with tempfile.TemporaryDirectory(prefix='drilling-doc-') as td:
        path = Path(td) / 'source.doc'
        path.write_bytes(data)
        command = [java, '-Xmx768m', '-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory',
                   '-jar', str(helper), 'inspect' if picture is None else 'asset', str(path)]
        if picture is not None:
            if not isinstance(picture, int) or picture < 0:
                raise ValueError('原件插图序号无效')
            command.append(str(picture))
        try:
            result = subprocess.run(command, capture_output=True, timeout=120)
        except subprocess.TimeoutExpired as error:
            raise ValueError('旧版Word解析超过120秒，原件已保存，可重新解析') from error
    if result.returncode:
        # Preserve a useful diagnostic without exposing server paths/stack traces.
        message = '旧版Word复合文档或表格结构无法读取'
        if b'EncryptedDocumentException' in result.stderr:
            message = 'Word文档已加密，请上传未加密的原件'
        raise ValueError(message)
    if picture is not None:
        return result.stdout
    parsed = json.loads(result.stdout)
    if not parsed.get('blocks'):
        raise ValueError('Word文档未读取出正文、表格或插图')
    return parsed


def word_blocks(data: bytes, kind: str):
    from .document_structure import table_block
    parsed = read_word(data)
    blocks = []
    for block in parsed['blocks']:
        if block['type'] == 'TABLE':
            block = dict(block)
            rows = block.pop('rows')
            locator, title = block.pop('locator'), block.pop('title')
            block.pop('type')
            block = table_block(locator, title, rows, kind=kind, **block)
        else:
            block['semantic_type'] = 'FIGURE' if block['type'] == 'FIGURE' else 'TEXT'
        blocks.append(block)
    return blocks, dict(reader=parsed['reader'])


def patch_word(data,table_index,rows):
    helper=Path(os.environ.get('DRILLING_LEGACY_WORD_READER',str(Path(__file__).resolve().parents[2]/'tools/legacy-office-reader/target/legacy-office-reader.jar')))
    with tempfile.TemporaryDirectory(prefix='drilling-doc-export-') as directory:
        source=Path(directory)/'source.doc';source.write_bytes(data)
        payload=Path(directory)/'table.json';payload.write_text(json.dumps({'table_index':table_index,'rows':rows},ensure_ascii=False))
        result=subprocess.run([shutil.which('java') or 'java','-Xmx768m','-Dlog4j2.loggerContextFactory=org.apache.logging.log4j.simple.SimpleLoggerContextFactory','-jar',str(helper),'patch',str(source),str(payload)],capture_output=True,timeout=120)
    if result.returncode or not result.stdout.startswith(bytes.fromhex('D0CF11E0')):raise ValueError('旧Word来源表回写失败；原件已保留，请核对表格行数或提供可编辑的Word原件')
    verified=read_word(result.stdout)
    table=next((b for b in verified['blocks'] if b.get('locator')==f'legacy:table:{table_index}'),None)
    expected=[[str(v).strip() for v in row] for row in rows]
    if table is None or table.get('rows')!=expected:raise ValueError('旧Word导出复核发现表格结构不一致，已停止下载；原件已保留')
    return result.stdout
