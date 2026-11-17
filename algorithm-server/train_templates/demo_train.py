"""Minimal training-template contract used for local smoke tests.

Real PyTorch/TensorFlow templates can use the same arguments. Each epoch emits
one JSON object so the service can stream metrics without importing a framework.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import time


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--hyperparameters-json", default="{}")
    args = parser.parse_args()
    params = json.loads(args.hyperparameters_json)
    epochs = max(1, min(int(params.get("epochs", 3)), 1000))
    output = Path(args.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    for epoch in range(1, epochs + 1):
        loss = 1.0 / epoch
        metric = 1.0 - loss
        print(json.dumps({"epoch": epoch, "loss": loss, "metric": metric, "device": args.device}), flush=True)
        time.sleep(float(params.get("sleep_seconds", 0)))
    (output / "model.json").write_text(json.dumps({"template": "demo", "dataset": args.dataset,
                                                     "device": args.device, "epochs": epochs}), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
