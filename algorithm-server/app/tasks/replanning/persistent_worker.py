"""Long-lived replanning worker protocol.

The parent process sends one JSON request per line and receives one JSON
response per line.  Imports stay resident between tasks; stdout is reserved
for the protocol and diagnostics go to stderr.
"""
import contextlib
import faulthandler
import json
import sys
import time

from .worker import calculate


def main():
    faulthandler.enable()
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            payload = json.loads(line)
            with contextlib.redirect_stdout(sys.stderr):
                try:
                    response = {"result": calculate(payload)}
                except (ValueError, RuntimeError) as exc:
                    response = {"error": str(exc)}
            if "result" in response:
                started = time.perf_counter()
                response["worker_timing"] = {
                    "worker_json_serialization_seconds": time.perf_counter() - started
                }
                # Serialize the outer envelope once, so the parent can read a
                # single complete line without another framing protocol.
                print(json.dumps(response, allow_nan=False), flush=True)
            else:
                print(json.dumps(response, allow_nan=False), flush=True)
        except Exception as exc:  # keep the daemon alive for the next request
            print(json.dumps({"error": f"worker protocol error ({type(exc).__name__})"}), flush=True)


if __name__ == "__main__":
    main()
