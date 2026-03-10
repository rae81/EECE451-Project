# Network Cell Analyzer Server

Clean Flask backend scaffold for the EECE 451 project.

## Included
- `app.py`: Flask app, routes, validation, and stats logic
- `models.py`: SQLAlchemy models for `CellData` and `DeviceLog`
- `config.py`: environment-driven configuration
- `templates/`: central dashboard and per-device stats pages
- `static/style.css`: shared dashboard styling
- `requirements.txt`: Python dependencies
- `Procfile` and `render.yaml`: deployment scaffolding

## Main routes
- `GET /`
- `GET /healthz`
- `POST /receive-data`
- `GET /get-stats`
- `GET /get-stats/avg-all`
- `GET /central-stats`
- `GET /device-stats`

## Local setup
```bash
cd /home/ramieid/Desktop/451proj/server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python3 app.py
```

## Notes
- SQLite is the default local database.
- Set `DATABASE_URL` for production or hosted deployment.
- Set `APP_TIMEZONE` if you want to change the default timezone parsing behavior.
