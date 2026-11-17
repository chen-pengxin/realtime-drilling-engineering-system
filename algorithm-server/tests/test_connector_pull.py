import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import base64
import io
import socket
import threading

import pytest
from fastapi import HTTPException
from starlette.requests import Request

from app.adapters.connector_pull import read_connector
from app.main import connector_read

def request(token=""):
    return Request({"type":"http","client":("127.0.0.1",12345),"headers":[(b"x-connector-token",token.encode())]})


def test_file_paging_and_invalid_record_preservation():
    content = base64.b64encode(b'md,inc,azi\n100,5,90\nbad,-1,90\n120,6,90\n').decode()
    payload = {'type': 'CSV', 'contentBase64': content, 'config': {'batchSize': 2}}
    first = read_connector(payload)
    assert len(first['records']) == 2
    assert first['records'][1]['md'] == 'bad'  # Java isolates it rather than silently deleting it.
    second = read_connector(dict(payload, cursor=first['cursor']))
    assert second['records'][0]['md'] == '120'
    assert read_connector(dict(payload, cursor=second['cursor']))['records'] == []


def test_excel_input_is_real_workbook():
    import pandas as pd
    output = io.BytesIO()
    pd.DataFrame([{'md': 10, 'inc': 2, 'azi': 90}]).to_excel(output, index=False)
    data = read_connector({'type': 'EXCEL', 'suffix': 'xlsx', 'contentBase64': base64.b64encode(output.getvalue()).decode()})
    assert data['records'][0]['md'] == 10


def test_tcp_reads_actual_socket_json_lines():
    with socket.socket() as server:
        server.bind(('127.0.0.1', 0)); server.listen()
        address = server.getsockname()
        def feed():
            connection, _ = server.accept()
            with connection:
                connection.sendall(b'{"md":100,"inc":5,"azi":90}\n')
        worker = threading.Thread(target=feed); worker.start()
        data = read_connector({'type': 'WITS_TCP', 'endpoint': f'tcp://127.0.0.1:{address[1]}'})
        worker.join(timeout=2)
    assert data['records'] == [{'md': 100, 'inc': 5, 'azi': 90}]


@pytest.mark.parametrize('version', ['WITSML_1_4_1', 'WITSML_2_0'])
def test_witsml_namespace_reader(monkeypatch, version):
    import app.adapters.connector_pull as module
    content = b'<trajectories xmlns="urn:trajectory"><trajectoryStation><md uom="m">100</md><incl uom="dega">5</incl><azi uom="dega">90</azi></trajectoryStation></trajectories>'
    monkeypatch.setattr(module, 'urlopen', lambda *a, **k: io.BytesIO(content))
    data = read_connector({'type': version, 'endpoint': 'https://source.invalid/trajectory'})
    assert data['records'] == [{'md': '100', 'inclination': '5', 'azimuth': '90'}]


def test_witsml_rejects_external_entities(monkeypatch):
    import app.adapters.connector_pull as module
    monkeypatch.setattr(module, 'urlopen', lambda *a, **k: io.BytesIO(b'<!DOCTYPE data [<!ENTITY x SYSTEM "file:///etc/passwd">]><data/>'))
    with pytest.raises(ValueError, match='unsafe'):
        read_connector({'type': 'WITSML_2_0', 'endpoint': 'https://source.invalid/trajectory'})


def test_witsml_converts_units_before_ingestion(monkeypatch):
    import app.adapters.connector_pull as module
    content = b'<trajectory><station><md uom="ft">100</md><incl uom="rad">0.5</incl><azi uom="rad">1</azi></station></trajectory>'
    monkeypatch.setattr(module, 'urlopen', lambda *a, **k: io.BytesIO(content))
    row = read_connector({'type': 'WITSML_2_0', 'endpoint': 'https://source.invalid/trajectory'})['records'][0]
    assert row['md'] == pytest.approx(30.48)
    assert row['inclination'] == pytest.approx(28.6478897565)
    assert row['azimuth'] == pytest.approx(57.295779513)


def test_witsml_rejects_unknown_unit(monkeypatch):
    import app.adapters.connector_pull as module
    content = b'<trajectory><station><md uom="unknown">100</md><incl>5</incl><azi>90</azi></station></trajectory>'
    monkeypatch.setattr(module, 'urlopen', lambda *a, **k: io.BytesIO(content))
    with pytest.raises(ValueError, match='unsupported trajectory unit'):
        read_connector({'type': 'WITSML_2_0', 'endpoint': 'https://source.invalid/trajectory'})


def test_serial_single_csv_row_is_not_dropped(monkeypatch):
    import types
    import sys
    class Port(io.BytesIO):
        def read_until(self, *args):
            return self.read()
    monkeypatch.setitem(sys.modules, 'serial', types.SimpleNamespace(Serial=lambda *a, **kw: Port(b'120,6,90\n')))
    assert read_connector({'type': 'SERIAL', 'endpoint': '/dev/test'})['records'] == [{'md': '120', 'inc': '6', 'azi': '90'}]


def test_internal_worker_requires_shared_token_when_configured(monkeypatch):
    monkeypatch.setenv('DRILLING_CONNECTOR_TOKEN', 'private-test-token')
    payload = {'type': 'CSV', 'contentBase64': base64.b64encode(b'md,inc,azi\n100,5,90').decode()}
    with pytest.raises(HTTPException) as error:
        connector_read(payload, request())
    assert error.value.status_code == 403
    assert connector_read(payload, request('private-test-token'))['records'][0]['md'] == '100'


def test_driver_errors_do_not_leak_credentials(monkeypatch):
    import app.adapters.connector_pull as module
    def bad(*a, **kw):
        raise RuntimeError('password=secret-value')
    monkeypatch.setenv('DRILLING_CONNECTOR_TOKEN', 'token')
    monkeypatch.setattr(module, 'read_connector', bad)
    with pytest.raises(HTTPException) as error:
        connector_read({'type':'SQL_SERVER'}, request('token'))
    assert error.value.status_code == 422
    assert 'secret-value' not in error.value.detail


@pytest.mark.parametrize('size', [0, 2001, -1])
def test_batch_limit(size):
    with pytest.raises(ValueError):
        read_connector({'type': 'CSV', 'config': {'batchSize': size}})
