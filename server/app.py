from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from statistics import mean
from zoneinfo import ZoneInfo

from flask import Flask, jsonify, render_template, request
from flask_cors import CORS

from config import Config
from models import CellData, DeviceLog, db


TIMESTAMP_FORMATS = (
    "%d %b %Y %I:%M %p",
    "%Y-%m-%d %H:%M:%S",
    "%Y-%m-%dT%H:%M:%S",
    "%Y-%m-%dT%H:%M:%S.%f",
)


def create_app() -> Flask:
    app = Flask(__name__)
    app.config.from_object(Config)

    CORS(app, resources={r"/receive-data": {"origins": "*"}, r"/get-stats*": {"origins": "*"}})
    db.init_app(app)

    with app.app_context():
        _ensure_instance_dir()
        db.create_all()

    register_routes(app)
    return app


def register_routes(app: Flask) -> None:
    @app.get("/")
    def home():
        return jsonify(
            {
                "service": "network-cell-analyzer-server",
                "status": "ok",
                "message": "Flask backend is running.",
            }
        )

    @app.get("/healthz")
    def healthcheck():
        return jsonify({"status": "healthy"}), 200

    @app.post("/receive-data")
    def receive_data():
        payload = request.get_json(silent=True)
        if not payload:
            return jsonify({"error": "Expected a JSON request body"}), 400

        validation_error = _validate_payload(payload)
        if validation_error:
            return jsonify({"error": validation_error}), 400

        try:
            cell_record = CellData(
                device_id=payload["device_id"].strip(),
                operator=payload["operator"].strip(),
                signal_power=int(payload["signal_power"]),
                snr=_optional_float(payload.get("snr")),
                network_type=str(payload["network_type"]).strip().upper(),
                frequency_band=_clean_optional_string(payload.get("frequency_band")),
                cell_id=str(payload["cell_id"]).strip(),
                timestamp=_parse_timestamp(
                    payload.get("timestamp"),
                    app.config["TIMEZONE"],
                ),
            )
            db.session.add(cell_record)
            _upsert_device_log(payload)
            db.session.commit()
        except ValueError as exc:
            db.session.rollback()
            return jsonify({"error": str(exc)}), 400
        except Exception:
            db.session.rollback()
            return jsonify({"error": "Failed to store cell data"}), 500

        return jsonify({"message": "Data received"}), 201

    @app.get("/get-stats")
    def get_stats():
        device_id = request.args.get("device_id", "").strip()
        if not device_id:
            return jsonify({"error": "device_id is required"}), 400

        rows = _query_rows(
            device_id=device_id,
            start=request.args.get("start"),
            end=request.args.get("end"),
            timezone_name=app.config["TIMEZONE"],
        )
        if isinstance(rows, tuple):
            return jsonify(rows[0]), rows[1]

        return jsonify(_build_stats_payload(rows)), 200

    @app.get("/get-stats/avg-all")
    def get_avg_all():
        rows = _query_rows(
            device_id=None,
            start=request.args.get("start"),
            end=request.args.get("end"),
            timezone_name=app.config["TIMEZONE"],
        )
        if isinstance(rows, tuple):
            return jsonify(rows[0]), rows[1]

        signal_values = [row.signal_power for row in rows]
        snr_values = [row.snr for row in rows if row.snr is not None]

        return jsonify(
            {
                "avg_signal_all_devices": round(mean(signal_values), 2),
                "avg_snr_all_devices": round(mean(snr_values), 2) if snr_values else None,
                "record_count": len(rows),
                "unique_devices": len({row.device_id for row in rows}),
            }
        )

    @app.get("/central-stats")
    def central_stats():
        devices = _fetch_devices(active_window_minutes=app.config["ACTIVE_DEVICE_MINUTES"])
        recent_records = (
            CellData.query.order_by(CellData.timestamp.desc()).limit(10).all()
        )

        return render_template(
            "dashboard.html",
            devices=devices,
            recent_records=recent_records,
            total_devices=len(devices),
            active_devices=sum(1 for device in devices if device["is_active"]),
            total_records=CellData.query.count(),
        )

    @app.get("/device-stats")
    def device_stats():
        device_id = request.args.get("device_id", "").strip()
        if not device_id:
            return jsonify({"error": "device_id is required"}), 400

        rows = _query_rows(
            device_id=device_id,
            start=request.args.get("start"),
            end=request.args.get("end"),
            timezone_name=app.config["TIMEZONE"],
        )
        if isinstance(rows, tuple):
            return jsonify(rows[0]), rows[1]

        return render_template(
            "device_stats.html",
            device_id=device_id,
            stats=_build_stats_payload(rows),
            sample_count=len(rows),
            records=rows[-20:],
        )


def _ensure_instance_dir() -> None:
    instance_dir = Config.SQLALCHEMY_DATABASE_URI.removeprefix("sqlite:///")
    if instance_dir.endswith(".db"):
        from pathlib import Path

        Path(instance_dir).parent.mkdir(parents=True, exist_ok=True)


def _validate_payload(payload: dict) -> str | None:
    required_fields = (
        "device_id",
        "operator",
        "signal_power",
        "network_type",
        "cell_id",
    )
    missing = [field for field in required_fields if payload.get(field) in (None, "")]
    if missing:
        return f"Missing required fields: {', '.join(missing)}"

    try:
        int(payload["signal_power"])
    except (TypeError, ValueError):
        return "signal_power must be an integer"

    if payload.get("snr") not in (None, ""):
        try:
            float(payload["snr"])
        except (TypeError, ValueError):
            return "snr must be numeric when provided"

    if payload.get("timestamp") not in (None, ""):
        try:
            _parse_timestamp(payload["timestamp"], Config.TIMEZONE)
        except ValueError as exc:
            return str(exc)

    return None


def _parse_timestamp(value, timezone_name: str) -> datetime:
    if value in (None, ""):
        return datetime.now(timezone.utc)

    if isinstance(value, datetime):
        return value.astimezone(timezone.utc) if value.tzinfo else value.replace(tzinfo=timezone.utc)

    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value, tz=timezone.utc)

    text = str(value).strip()
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00")).astimezone(timezone.utc)
    except ValueError:
        pass

    local_zone = ZoneInfo(timezone_name)
    for fmt in TIMESTAMP_FORMATS:
        try:
            naive = datetime.strptime(text, fmt)
            return naive.replace(tzinfo=local_zone).astimezone(timezone.utc)
        except ValueError:
            continue

    raise ValueError("timestamp format is invalid")


def _optional_float(value):
    if value in (None, ""):
        return None
    return float(value)


def _clean_optional_string(value):
    if value in (None, ""):
        return None
    return str(value).strip()


def _upsert_device_log(payload: dict) -> None:
    device_id = payload["device_id"].strip()
    client_ip = request.headers.get("X-Forwarded-For", request.remote_addr or "")
    if "," in client_ip:
        client_ip = client_ip.split(",", 1)[0].strip()

    now = datetime.now(timezone.utc)
    device = DeviceLog.query.filter_by(device_id=device_id).first()
    if not device:
        device = DeviceLog(
            device_id=device_id,
            first_seen=now,
            last_seen=now,
        )
        db.session.add(device)

    device.ip_address = client_ip or payload.get("ip_address")
    device.mac_address = _clean_optional_string(payload.get("mac_address"))
    device.last_seen = now
    device.is_active = True


def _query_rows(device_id, start, end, timezone_name: str):
    query = CellData.query.order_by(CellData.timestamp.asc())
    if device_id:
        query = query.filter_by(device_id=device_id)

    rows = query.all()
    if not rows:
        return {"message": "No data found"}, 404

    try:
        start_dt = _parse_timestamp(start, timezone_name) if start else rows[0].timestamp
        end_dt = _parse_timestamp(end, timezone_name) if end else rows[-1].timestamp
    except ValueError as exc:
        return {"error": str(exc)}, 400

    if end_dt < start_dt:
        return {"error": "end must be after start"}, 400

    filtered_rows = [row for row in rows if start_dt <= row.timestamp <= end_dt]
    if not filtered_rows:
        return {"message": "No data found in the requested time range"}, 404

    return filtered_rows


def _build_stats_payload(rows: list[CellData]) -> dict:
    total = len(rows)
    operator_counts = Counter(row.operator for row in rows)
    network_counts = Counter(row.network_type for row in rows)
    signal_by_network = defaultdict(list)
    snr_by_network = defaultdict(list)

    for row in rows:
        signal_by_network[row.network_type].append(row.signal_power)
        if row.snr is not None:
            snr_by_network[row.network_type].append(row.snr)

    return {
        "connectivity_per_operator": {
            key: _percentage(value, total) for key, value in operator_counts.items()
        },
        "connectivity_per_network_type": {
            key: _percentage(value, total) for key, value in network_counts.items()
        },
        "avg_signal_per_network_type": {
            key: round(mean(values), 2) for key, values in signal_by_network.items()
        },
        "avg_snr_per_network_type": {
            key: round(mean(values), 2) for key, values in snr_by_network.items()
        },
        "avg_signal_device": round(mean([row.signal_power for row in rows]), 2),
        "record_count": total,
        "first_timestamp": rows[0].timestamp.isoformat(),
        "last_timestamp": rows[-1].timestamp.isoformat(),
    }


def _percentage(count: int, total: int) -> str:
    return f"{round((count / total) * 100, 2)}%"


def _fetch_devices(active_window_minutes: int) -> list[dict]:
    now = datetime.now(timezone.utc)
    threshold = now - timedelta(minutes=active_window_minutes)
    devices = []

    for device in DeviceLog.query.order_by(DeviceLog.last_seen.desc()).all():
        is_active = device.last_seen >= threshold
        if device.is_active != is_active:
            device.is_active = is_active
            db.session.add(device)

        devices.append(
            {
                "device_id": device.device_id,
                "ip_address": device.ip_address,
                "mac_address": device.mac_address,
                "first_seen": device.first_seen,
                "last_seen": device.last_seen,
                "is_active": is_active,
            }
        )

    db.session.commit()
    return devices


app = create_app()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
