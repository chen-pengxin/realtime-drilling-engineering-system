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


def convert_office(data: bytes, suffix: str, target: str, *, preserve_tables=False) -> bytes:
    """Convert a copy; never change an uploaded original or its file name.

    LibreOffice preserves tables and drawings in legacy Word/Excel files. The
    macOS textutil fallback is deliberately limited to DOC -> DOCX.
    """
    office = shutil.which('soffice') or shutil.which('libreoffice')
    if not office and Path('/Applications/LibreOffice.app/Contents/MacOS/soffice').is_file():
        office = '/Applications/LibreOffice.app/Contents/MacOS/soffice'
    if suffix in ('.doc','.xls') and data.startswith(bytes.fromhex('D0CF11E0')):
        import olefile
        try:
            with olefile.OleFileIO(io.BytesIO(data)):
                pass
        except (OSError, ValueError) as error:
            raise ValueError('原文件的OLE复合文档结构不完整，无法可靠读取表格；请重新提供可正常打开的原件') from error
    key = hashlib.sha256(data + suffix.encode() + target.encode() + b'office-fonts-v2' + bytes([bool(preserve_tables)])).hexdigest()
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
        if not preserve_tables and suffix in ('.doc', '.wps', '.rtf') and target == 'docx' and shutil.which('textutil'):
            command = ['textutil', '-convert', 'docx', '-output', str(output), str(source)]
        elif office:
            command = [office, '-env:UserInstallation=' + (directory / 'profile').as_uri(),
                       '--headless', '--convert-to', target, '--outdir', str(directory), str(source)]
        elif not preserve_tables and suffix in ('.doc', '.wps') and target == 'docx' and shutil.which('textutil'):
            command = ['textutil', '-convert', 'docx', '-output', str(output), str(source)]
        else:
            raise ValueError('Office文档转换需要安装LibreOffice（旧Word在macOS上也可使用textutil）')
        conversion_env=os.environ.copy()
        if target=='pdf' and Path('/System/Library/Fonts/Supplemental/Songti.ttc').is_file():
            # Isolated converter settings: substitute missing Microsoft CJK
            # families with installed native CJK fonts; originals stay intact.
            user=directory/'profile'/'user';user.mkdir(parents=True,exist_ok=True)
            substitutions=[('宋体','Songti SC'),('SimSun','Songti SC'),('黑体','Heiti SC'),('SimHei','Heiti SC'),('楷体','Kaiti SC'),('KaiTi','Kaiti SC'),('仿宋','STFangsong'),('FangSong','STFangsong')]
            items=''.join(f'<node oor:name="_{i}" oor:op="replace"><prop oor:name="Font"><value>{old}</value></prop><prop oor:name="ReplaceFont"><value>{new}</value></prop><prop oor:name="Always"><value>true</value></prop><prop oor:name="ScreenOnly"><value>false</value></prop></node>' for i,(old,new) in enumerate(substitutions))
            (user/'registrymodifications.xcu').write_text('<?xml version="1.0" encoding="UTF-8"?><oor:items xmlns:oor="http://openoffice.org/2001/registry"><item oor:path="/org.openoffice.Office.Common/Font/Substitution"><prop oor:name="Replacement"><value>true</value></prop><node oor:name="FontPairs">'+items+'</node></item></oor:items>')
            config=directory/'fonts.conf'
            config.write_text('<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd"><fontconfig><include ignore_missing="yes">/opt/homebrew/etc/fonts/fonts.conf</include><dir>/System/Library/Fonts</dir><dir>/System/Library/Fonts/Supplemental</dir><dir>/Library/Fonts</dir><cachedir>'+str(directory/'fontcache')+'</cachedir></fontconfig>')
            conversion_env['FONTCONFIG_FILE']=str(config)
        # Keep the conversion subprocess on the platform's posix_spawn path.
        # Forking a long-running Python process that has imported PROJ/SQLite
        # can crash on macOS before Office even starts.
        process = subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=conversion_env)
        try:
            process.communicate(timeout=60)
        except subprocess.TimeoutExpired as error:
            # Kill only the converter process; the user's Office application is
            # never attached to this process because an isolated profile is
            # supplied above.
            process.kill()
            process.communicate()
            raise ValueError('文档转换超过60秒，已停止本次转换；原文件已保留，可重新解析') from error
        result = process
        # Some legacy WPS/Word documents are accepted by textutil but fail in
        # LibreOffice's direct PDF filter.  Convert a private copy to DOCX,
        # then render that copy.  This remains an Office rendering fallback;
        # it never invokes the engineering parser or changes the original.
        if (result.returncode or not _valid_output(output, target)) and office and target == 'pdf' \
                and suffix in ('.doc', '.wps', '.rtf') and shutil.which('textutil'):
            fallback_source = directory / 'source-fallback.docx'
            fallback_pdf = directory / 'source-fallback.pdf'
            fallback_source.unlink(missing_ok=True)
            fallback_pdf.unlink(missing_ok=True)
            textutil = subprocess.run(
                ['textutil', '-convert', 'docx', '-output', str(fallback_source), str(source)],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
            if textutil.returncode == 0 and fallback_source.is_file() and fallback_source.stat().st_size:
                fallback_command = [office, '-env:UserInstallation=' + (directory / 'fallback-profile').as_uri(),
                                    '--headless', '--convert-to', 'pdf', '--outdir', str(directory), str(fallback_source)]
                fallback_process = subprocess.Popen(fallback_command, stdout=subprocess.PIPE,
                                                    stderr=subprocess.PIPE, env=conversion_env)
                try:
                    fallback_process.communicate(timeout=60)
                except subprocess.TimeoutExpired as error:
                    fallback_process.kill()
                    fallback_process.communicate()
                    raise ValueError('文档转换超过60秒，已停止本次转换；原文件已保留，可重新解析') from error
                if fallback_process.returncode == 0 and _valid_output(fallback_pdf, 'pdf'):
                    fallback_pdf.replace(output)
                    result = fallback_process
        if (result.returncode or not _valid_output(output,target)) and command[0].endswith('textutil') and office:
            output.unlink(missing_ok=True)
            command = [office, '-env:UserInstallation=' + (directory / 'profile').as_uri(),
                       '--headless', '--convert-to', target, '--outdir', str(directory), str(source)]
            process = subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=conversion_env)
            try: process.communicate(timeout=60)
            except subprocess.TimeoutExpired as error:
                process.kill();process.communicate();raise ValueError('文档转换超过60秒，已停止本次转换；原文件已保留，可重新解析') from error
            result = process
        if result.returncode or not _valid_output(output,target):
            raise ValueError('Office文档转换失败，请检查文件是否损坏或加密')
        return output.read_bytes()
