import json
import sys
import time
from pathlib import Path

import pytest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.tasks.prediction.pose_contract import normalize_pose_result
from app.domain.inference import run_inference
from app.domain.training import TrainingManager


@pytest.fixture
def current():
    return {"md": 100, "inclination": 30, "azimuth": 20, "north": 5, "east": 8, "tvd": 80}


def test_pose_contract_minimum_curvature_and_sorted_future(current):
    result = normalize_pose_result({"predicted_stations": [
        {"md": 128, "inclination": 35, "azimuth": 25},
        {"md": 109, "inclination": 32, "azimuth": 22},
    ]}, {"current_pose": current})
    assert [row["md"] for row in result["predicted_stations"]] == [109, 128]
    assert result["predicted_pose"]["tvd"] > 80
    assert result["source"] == "TRAINED_MODEL"


@pytest.mark.parametrize("point", [
    {"md": 90, "inclination": 30, "azimuth": 20},
    {"md": 100, "inclination": 30, "azimuth": 20},
    {"md": 109, "inclination": None, "azimuth": 20},
    {"md": 109, "inclination": float('nan'), "azimuth": 20},
    {"md": 109, "inclination": 200, "azimuth": 20},
])
def test_pose_contract_rejects_invalid_output(current, point):
    with pytest.raises(ValueError):
        normalize_pose_result({"predicted_pose": point}, {"current_pose": current})


def test_pose_contract_does_not_fake_missing_predictions(current):
    with pytest.raises(ValueError):
        normalize_pose_result({"loss": 0.1}, {"current_pose": current})


def test_real_inference_adapter_receives_input_and_returns_pose(tmp_path, monkeypatch, current):
    templates = tmp_path / 'templates'; templates.mkdir()
    models = tmp_path / 'models'; models.mkdir()
    (models / 'model.json').write_text('{"offset": 2}')
    (templates / 'pose.py').write_text('''import argparse,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--model-path');p.add_argument('--input-json');p.add_argument('--device');a=p.parse_args()
x=json.loads(Path(a.input_json).read_text());w=json.loads(Path(a.model_path).read_text());c=x['current_pose']
print(json.dumps({'predicted_pose':{'md':c['md']+x['prediction_step'],'inclination':c['inclination']+w['offset'],'azimuth':c['azimuth']}}))
''')
    monkeypatch.setenv('TRAIN_TEMPLATE_ROOT', str(templates)); monkeypatch.setenv('MODEL_ROOT', str(models))
    result = run_inference('model.json', 'pose.py', {'contract':'drilling.pose-extrapolation.v1','current_pose':current,'prediction_step':9}, 'cpu', 5)
    assert result['result']['predicted_pose']['inclination'] == 32
    assert result['result']['predicted_pose']['md'] == 109
    assert result['result']['predicted_pose']['tvd'] > 80


def test_incremental_worker_receives_original_weights_and_hyperparameters(tmp_path, monkeypatch):
    root=tmp_path
    monkeypatch.setenv('TRAIN_TEMPLATE_ROOT',str(root));monkeypatch.setenv('TRAIN_DATA_ROOT',str(root));monkeypatch.setenv('MODEL_ROOT',str(root/'models'))
    monkeypatch.delenv('TRAINING_STATE_FILE',raising=False);monkeypatch.delenv('REDIS_URL',raising=False);monkeypatch.delenv('TASK_CALLBACK_URL',raising=False)
    (root/'models').mkdir();(root/'models'/'base.json').write_text('{"epochs":10}')
    (root/'data.csv').write_text('md,inclination,azimuth\n1,2,3\n')
    (root/'update.py').write_text('''import argparse,json
from pathlib import Path
p=argparse.ArgumentParser()
for key in ['dataset','output-dir','device','hyperparameters-json','base-model-path']:p.add_argument('--'+key,required=True)
a=p.parse_args();base=json.loads(Path(a.base_model_path).read_text());params=json.loads(a.hyperparameters_json)
assert params['learning_rate']==0.0001
Path(a.output_dir,'updated.json').write_text(json.dumps({'epochs':base['epochs']+params['epochs']}))
print(json.dumps({'epoch':1,'loss':0.2}))
''')
    manager=TrainingManager()
    task=manager.start('update.py','data.csv','cpu',{'epochs':3,'learning_rate':0.0001},1,'base.json')
    deadline=time.time()+5
    while not task.model_artifact_uri and task.status not in {'FAILED','STOPPED'} and time.time()<deadline:time.sleep(0.02)
    assert task.status=='SUCCEEDED', task.error
    assert json.loads(Path(task.output_dir,'updated.json').read_text())['epochs']==13
    assert json.loads((root/'models'/'base.json').read_text())['epochs']==10
    assert task.model_artifact_uri
    manager._callback_executor.shutdown(wait=True)


def test_incremental_worker_rejects_external_base_weights(tmp_path, monkeypatch):
    for name in ['TRAIN_TEMPLATE_ROOT','TRAIN_DATA_ROOT','MODEL_ROOT']:monkeypatch.setenv(name,str(tmp_path))
    monkeypatch.delenv('REDIS_URL',raising=False);monkeypatch.delenv('TRAINING_STATE_FILE',raising=False)
    (tmp_path/'update.py').write_text('');(tmp_path/'data.csv').write_text('')
    manager=TrainingManager()
    with pytest.raises(ValueError):manager.start('update.py','data.csv','cpu',{},1,'../not-model.json')
    manager._callback_executor.shutdown(wait=True)
