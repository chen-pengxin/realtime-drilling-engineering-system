"""OCR for image-only source pages. Every recognized word keeps its box.

Linux/Windows use Tesseract. macOS can use the system Vision framework without
uploading engineering documents to an external service.
"""
from __future__ import annotations

import csv
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading

_lock = threading.Lock()
SWIFT_SOURCE = r'''
import Foundation
import Vision
import ImageIO
let url = URL(fileURLWithPath: CommandLine.arguments[1])
guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { exit(2) }
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.recognitionLanguages = ["zh-Hans", "en-US"]
request.usesLanguageCorrection = false
request.minimumTextHeight = 0.003
try VNImageRequestHandler(cgImage: image).perform([request])
var words: [[String: Any]] = []
for observation in request.results ?? [] {
  guard let candidate = observation.topCandidates(1).first else { continue }
  let text = candidate.string
  let regex = try NSRegularExpression(pattern: "\\S+")
  for match in regex.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
    guard let range = Range(match.range, in: text),
          let rectangle = try? candidate.boundingBox(for: range) else { continue }
    let box = rectangle.boundingBox
    words.append(["text": String(text[range]), "x0": box.minX, "x1": box.maxX,
                  "top": 1-box.maxY, "bottom": 1-box.minY, "confidence": candidate.confidence])
  }
}
let output = try JSONSerialization.data(withJSONObject: words)
FileHandle.standardOutput.write(output)
'''


def _vision(image):
    compiler = shutil.which('swiftc')
    if sys.platform != 'darwin' or not compiler:
        raise ValueError('扫描文档解析需要安装Tesseract及中文、英文语言包')
    digest = hashlib.sha256(SWIFT_SOURCE.encode()).hexdigest()[:16]
    directory = Path(tempfile.gettempdir()) / 'drilling-vision-ocr' / digest
    executable = directory / 'recognize'
    with _lock:
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        if not executable.exists():
            source = directory / 'recognize.swift'
            source.write_text(SWIFT_SOURCE)
            result = subprocess.run([compiler, str(source), '-o', str(executable)], capture_output=True, timeout=180)
            if result.returncode:
                raise ValueError('macOS文档识别组件编译失败')
    with tempfile.TemporaryDirectory() as td:
        path = Path(td) / 'page.png'
        image.save(path)
        result = subprocess.run([str(executable), str(path)], capture_output=True, timeout=90)
        if result.returncode:
            raise ValueError('扫描文档文字识别失败')
    return json.loads(result.stdout)


def recognize_page(page):
    return recognize_pillow_image(page.to_image(resolution=180).original, page=page)


def recognize_pillow_image(image, page=None):
    tesseract = shutil.which('tesseract')
    words = []
    if tesseract:
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / 'page.png'
            image.save(path)
            languages = subprocess.run([tesseract, '--list-langs'], capture_output=True, text=True, timeout=10).stdout
            selected = '+'.join(v for v in ('chi_sim', 'eng') if v in languages)
            if not selected:
                raise ValueError('Tesseract未安装中文或英文语言包')
            result = subprocess.run([tesseract, str(path), 'stdout', '-l', selected, '--psm', '3', 'tsv'], capture_output=True, timeout=90)
            if result.returncode:
                raise ValueError('扫描文档文字识别失败')
        for row in csv.DictReader(io.StringIO(result.stdout.decode()), delimiter='\t'):
            if row['text'].strip() and row['level'] == '5':
                x, y, w, h = [float(row[k]) for k in ('left', 'top', 'width', 'height')]
                words.append(dict(text=row['text'], x0=x/image.width, x1=(x+w)/image.width,
                                  top=y/image.height, bottom=(y+h)/image.height, confidence=float(row['conf'])/100))
    else:
        words = _vision(image)
    lines = []
    width, height = (float(page.width), float(page.height)) if page is not None else (image.width, image.height)
    for word in sorted(words, key=lambda w: (w['top'], w['x0'])):
        word = dict(word, x0=word['x0']*width, x1=word['x1']*width,
                    top=word['top']*height, bottom=word['bottom']*height)
        line = next((v for v in reversed(lines[-4:]) if abs(v['top']-word['top']) < 3), None)
        if line is None:
            line = dict(top=word['top'], words=[], ocr=True)
            lines.append(line)
        line['words'].append(word)
    for line in lines:
        line['words'].sort(key=lambda w: w['x0'])
        line['text'] = ' '.join(w['text'] for w in line['words'])
    return lines


def scanned_tables(page, lines):
    """Locate ruled tables in scans; keep OCR cells separate from source text."""
    import numpy as np
    from scipy.ndimage import binary_opening, label, find_objects
    image = page.to_image(resolution=130).original.convert('L')
    ink = np.asarray(image) < 160
    horizontal = binary_opening(ink, structure=np.ones((1, max(30, image.width//20)), bool))
    vertical = binary_opening(ink, structure=np.ones((max(20, image.height//100), 1), bool))
    regions, _ = label(horizontal | vertical)
    result = []

    def centers(values):
        groups = []
        for value in values:
            if not groups or value > groups[-1][-1]+2:
                groups.append([int(value)])
            else:
                groups[-1].append(int(value))
        return [sum(g)/len(g) for g in groups]

    for region in find_objects(regions):
        if region is None:
            continue
        ys, xs = region
        if xs.stop-xs.start < image.width*.35 or ys.stop-ys.start < 25:
            continue
        h = horizontal[ys, xs]
        v = vertical[ys, xs]
        yy = [y+ys.start for y in centers(np.where(h.sum(axis=1) > h.shape[1]*.5)[0])]
        xx = [x+xs.start for x in centers(np.where(v.sum(axis=0) > v.shape[0]*.2)[0])]
        if len(xx) < 3 or len(yy) < 2:
            continue
        rows = [['' for _ in xx[:-1]] for _ in yy[:-1]]
        for line in lines:
            for word in line['words']:
                cx = (word['x0']+word['x1'])/2/float(page.width)*image.width
                cy = (word['top']+word['bottom'])/2/float(page.height)*image.height
                ri = next((i for i in range(len(yy)-1) if yy[i] <= cy <= yy[i+1]), None)
                ci = next((i for i in range(len(xx)-1) if xx[i] <= cx <= xx[i+1]), None)
                if ri is not None and ci is not None:
                    rows[ri][ci] += (' ' if rows[ri][ci] else '') + word['text']
        if any(any(row) for row in rows):
            result.append(rows)
    return result
