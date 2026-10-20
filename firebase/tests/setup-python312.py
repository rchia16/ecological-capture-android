"""Portable Windows Python for local checks when the machine only has Python 3.14.

Downloads the last official 3.12 Windows embeddable maintenance build over HTTPS.
This is a local test tool, not the Python runtime deployed to Cloud Functions.
"""
import hashlib
from pathlib import Path
import urllib.request
import zipfile

root=Path(__file__).resolve().parents[1]/'.tools'/'python312'
root.mkdir(parents=True,exist_ok=True)
archive=root.parent/'python-3.12.10-embed-amd64.zip'
if not archive.exists():
    with urllib.request.urlopen('https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip',timeout=60) as response,archive.open('xb') as output:
        while block:=response.read(1024*1024):output.write(block)
# Official release-page checksum; HTTPS protects the download, this detects truncation/corruption.
with archive.open('rb') as stream:
    if hashlib.file_digest(stream,'md5').hexdigest()!='fe8ef205f2e9c3ba44d0cf9954e1abd3':raise ValueError('PYTHON_CHECKSUM_MISMATCH')
with zipfile.ZipFile(archive) as package:
    if any(not (root/item.filename).resolve().is_relative_to(root.resolve()) for item in package.infolist()):raise ValueError('INVALID_ARCHIVE')
    package.extractall(root)
(root/'python312._pth').write_text('python312.zip\n.\nLib/site-packages\nimport site\n',encoding='utf-8')
print('Portable Python 3.12 prepared under firebase/.tools')
