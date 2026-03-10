# Network Cell Analyzer Server

Clean Flask backend scaffold for the EECE 451 project.

## Included
- `app.py`: Flask app, routes, validation, and stats logic
- `models.py`: SQLAlchemy models for `CellData` and `DeviceLog`
- `config.py`: environment-driven configuration
- `API_CONTRACT.md`: stable contract for the Android integration
- `templates/`: central dashboard and per-device stats pages
- `static/style.css`: shared dashboard styling
- `requirements.txt`: Python dependencies
- `requirements-dev.txt`: test dependencies
- `gunicorn.conf.py`: production worker/thread configuration
- `Procfile` and `render.yaml`: deployment scaffolding

## Main routes
- `GET /`
- `GET /healthz`
- `POST /receive-data`
- `GET /get-stats`
- `GET /get-stats/avg-all`
- `GET /central-stats`
- `GET /device-stats`
- `POST /receive-batch`
- `GET /predict`
- `GET /api/history`
- `GET /api/export.csv`
- `GET /api/report.pdf`
- `GET /api/handover-stats`
- `GET /api/neighbor-cells`
- `GET /api/heatmap-data`
- `GET /api/speed-test/download`
- `POST /api/speed-test/upload`
- `POST /api/speed-test/result`
- `GET /api/speed-test/stats`
- `GET /api/alert-rules`
- `POST /api/alert-rules`
- `GET /heatmap`

## Local setup
```bash
cd /home/ramieid/Desktop/451proj/server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python3 app.py
```

## Local testing
```bash
cd /home/ramieid/Desktop/451proj/server
source .venv/bin/activate
pip install -r requirements-dev.txt
pytest
```

## Seed local demo data
```bash
cd /home/ramieid/Desktop/451proj/server
source .venv/bin/activate
flask --app app seed-demo-data
```

## Notes
- SQLite is the default local database.
- Set `DATABASE_URL` for production or hosted deployment.
- Set `APP_TIMEZONE` if you want to change the default timezone parsing behavior.
- Render deploys use an ephemeral filesystem, so hosted persistence should move to PostgreSQL later.
- Heatmap support is implemented on the server side. The Android app must send `latitude` and `longitude` with readings to make it useful in production.
- Dual-SIM-ready fields (`sim_slot`, `subscription_id`) are accepted by the backend.
- Neighbor-cell payloads are accepted and aggregated by the backend when the Android app sends them.
- Offline sync is supported server-side through `POST /receive-batch`.
