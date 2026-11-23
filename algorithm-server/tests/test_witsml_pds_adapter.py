import pytest

from app.adapters.witsml_pds import inspect_witsml, stations_from_witsml


def test_witsml_adapter_reads_141_and_normalizes_units():
    xml = '''<?xml version="1.0"?><trajectory xmlns="http://www.witsml.org/schemas/1series" uid="t1">
      <name>15/9-19 A</name><trajectoryStation uid="s1"><md uom="ft">0</md><incl uom="dega">0</incl><azi uom="dega">0</azi></trajectoryStation>
      <trajectoryStation uid="s2"><md uom="ft">328.0839895</md><incl uom="dega">10</incl><azi uom="dega">90</azi></trajectoryStation>
    </trajectory>'''
    document = inspect_witsml(xml)
    assert document['version'] == '1.4.1.1'
    rows = stations_from_witsml(xml)
    assert len(rows) == 2 and rows[1]['md'] == pytest.approx(100.0, abs=1e-5)
    assert rows[1]['inclination'] == 10 and rows[1]['azimuth'] == 90


def test_witsml_adapter_rejects_external_entities():
    with pytest.raises(ValueError, match='DOCTYPE|ENTITY'):
        inspect_witsml('<!DOCTYPE trajectory [<!ENTITY x SYSTEM "file:///tmp/x">]><trajectory>&x;</trajectory>')


def test_witsml_adapter_supports_multiple_trajectory_objects():
    xml = '''<witsml xmlns="http://www.energistics.org/schemas/2.0" version="2.0">
      <trajectory uid="a"><trajectoryStation><md>0</md><incl>0</incl><azi>0</azi></trajectoryStation></trajectory>
      <trajectory uid="b"><trajectoryStation><md>10</md><incl>1</incl><azi>2</azi></trajectoryStation></trajectory>
    </witsml>'''
    document = inspect_witsml(xml)
    assert document['version'] == '2.0'
    assert len(document['trajectories']) == 2
