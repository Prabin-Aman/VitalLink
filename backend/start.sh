#!/bin/sh
# Container entry point. Render's free tier has no pre-deploy step, so migrations run here,
# before the server starts. If they fail, the container exits and Render keeps the old version.
set -e
python -m alembic upgrade head
exec python -m uvicorn app.main:app --host 0.0.0.0 --port "${PORT:-10000}" --proxy-headers
