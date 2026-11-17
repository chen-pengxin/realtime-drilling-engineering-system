"""Shared test configuration. Every current test module is collected by default."""

from pathlib import Path
import sys


# Make ``pytest`` work from the repository directory without requiring callers
# to export PYTHONPATH manually.
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
