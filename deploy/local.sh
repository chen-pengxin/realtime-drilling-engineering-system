#!/bin/sh
set -eu
DEPLOY_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -x "$DEPLOY_DIR/../algorithm-server/.venv/bin/python" ]; then
  exec "$DEPLOY_DIR/../algorithm-server/.venv/bin/python" "$DEPLOY_DIR/local_services.py" "$@"
fi
exec python3 "$DEPLOY_DIR/local_services.py" "$@"
