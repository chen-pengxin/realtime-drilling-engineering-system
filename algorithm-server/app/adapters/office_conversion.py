"""Read-only Office conversion, shared by import and archived-figure viewing."""
from __future__ import annotations

import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import signal
import zipfile
import xml.etree.ElementTree as ET

_lock = threading.Lock()


def _valid_output(path, target):
    if not path.is_file() or not path.stat().st_size:
        return False
    if target == 'docx':
        try:
            with zipfile.ZipFile(path) as archive:
                root = ET.fromstring(archive.read('word/document.xml'))
                return any((element.text or '').strip() for element in root.iter() if element.tag.endswith('}t')) or any(n.startswith('word/media/') for n in archive.namelist())
        except (KeyError, ValueError, zipfile.BadZipFile, ET.ParseError):
            return False
    return True


def convert_office(data: bytes, suffix: str, target: str) -> bytes:
    """Convert a copy; never change an uploaded original or its file name.

    LibreOffice preserves tables and drawings in legacy Word/Excel files. The
    macOS textutil fallback is deliberately limited to DOC -> DOCX.
    """
    office = shutil.which('soffice') or shutil.which('libreoffice')
    if suffix in ('.doc','.xls') and data.startswith(bytes.fromhex('D0CF11E0')):
        import olefile
        try:
            with olefile.OleFileIO(io.BytesIO(data)):
                pass
        except (OSError, ValueError) as error:
            raise ValueError('原文件的OLE复合文档结构不完整，无法可靠读取表格；请重新提供可正常打开的原件') from error
    key = hashlib.sha256(data + suffix.encode() + target.encode()).hexdigest()
    directory = Path(tempfile.gettempdir()) / 'drilling-office-cache' / key
    output = directory / ('source.' + target)
    with _lock:
        if _valid_output(output, target):
            return output.read_bytes()
        output.unlink(missing_ok=True)
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        source = directory / ('source' + suffix)
        source.write_bytes(data)
        # macOS textutil handles many legacy Word reports faster than a fresh
        # LibreOffice profile. If it produces an empty OOXML package, retry
        # with LibreOffice instead of reporting a false successful parse.
        if suffix in ('.doc', '.wps', '.rtf') and target == 'docx' and shutil.which('textutil'):
            command = ['textutil', '-convert', 'docx', '-output', str(output), str(source)]
        elif office:
            command = [office, '-env:UserInstallation=' + (directory / 'profile').as_uri(),
                       '--headless', '--convert-to', target, '--outdir', str(directory), str(source)]
        elif suffix in ('.doc', '.wps') and target == 'docx' and shutil.which('textutil'):
            command = ['textutil', '-convert', 'docx', '-output', str(output), str(source)]
        else:
            raise ValueError('Office文档转换需要安装LibreOffice（旧Word在macOS上也可使用textutil）')
        process = subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
        try:
            process.communicate(timeout=60)
        except subprocess.TimeoutExpired as error:
            # Kill this converter's process group, never the user's Office app.
            os.killpg(process.pid,signal.SIGKILL)
            process.communicate()
            raise ValueError('文档转换超过60秒，已停止本次转换；原文件已保留，可重新解析') from error
        result = process
        if (result.returncode or not _valid_output(output,target)) and command[0].endswith('textutil') and office:
            output.unlink(missing_ok=True)
            command = [office, '-env:UserInstallation=' + (directory / 'profile').as_uri(),
                       '--headless', '--convert-to', target, '--outdir', str(directory), str(source)]
            process = subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
            try: process.communicate(timeout=60)
            except subprocess.TimeoutExpired as error:
                os.killpg(process.pid,signal.SIGKILL);process.communicate();raise ValueError('文档转换超过60秒，已停止本次转换；原文件已保留，可重新解析') from error
            result = process
        if result.returncode or not _valid_output(output,target):
            raise ValueError('Office文档转换失败，请检查文件是否损坏或加密')
        return output.read_bytes()
