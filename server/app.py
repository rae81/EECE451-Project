import csv
import io
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from statistics import mean
from zoneinfo import ZoneInfo

from flask import Flask, Response, jsonify, render_template, request
from flask_cors import CORS
from sqlalchemy import and_
from sqlalchemy import inspect, text

from config import Config
from models import CellData, DeviceLog, db


TIMESTAMP_FORMATS = (
    "%d %b %Y %I:%M %p",
    "%Y-%m-%d %H:%M:%S",
    "%Y-%m-%dT%H:%M:%S",
    "%Y-%m-%dT%H:%M:%S.%f",
)


def create_app(config_object=Config) -> Flask:
    app = Flask(__name__)
    app.config.from_object(config_object)

    CORS(app, resources={r"/receive-data": {"origins": "*"}, r"/get-stats*": {"origins": "*"}})
    db.init_app(app)

    with app.app_context():
        _ensure_instance_dir(app)
        db.create_all()
        _run_sqlite_migrations()

    register_routes(app)
    register_cli(app)
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
                latitude=_optional_float(payload.get("latitude")),
                longitude=_optional_float(payload.get("longitude")),
                location_accuracy_m=_optional_float(payload.get("location_accuracy_m")),
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
        signal_per_device = defaultdict(list)
        for row in rows:
            signal_per_device[row.device_id].append(row.signal_power)

        return jsonify(
            {
                "avg_signal_all_devices": round(mean(signal_values), 2),
                "avg_snr_all_devices": round(mean(snr_values), 2) if snr_values else None,
                "avg_signal_per_device": {
                    device_id: round(mean(values), 2)
                    for device_id, values in signal_per_device.items()
                },
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
            records=rows[-app.config["DEFAULT_PAGE_SAMPLE_LIMIT"] :],
            start=request.args.get("start", ""),
            end=request.args.get("end", ""),
        )

    @app.get("/api/history")
    def history():
        rows = _query_rows(
            device_id=request.args.get("device_id", "").strip() or None,
            start=request.args.get("start"),
            end=request.args.get("end"),
            timezone_name=app.config["TIMEZONE"],
            operator=request.args.get("operator"),
            network_type=request.args.get("network_type"),
            limit=_parse_limit(request.args.get("limit"), default=100, maximum=1000),
        )
        if isinstance(rows, tuple):
            return jsonify(rows[0]), rows[1]

        return jsonify(
            {
                "records": [row.to_dict() for row in rows],
                "count": len(rows),
            }
        )

    @app.get("/api/export.csv")
    def export_csv():
        rows = _query_rows(
            device_id=request.args.get("device_id", "").strip() or None,
            start=request.args.get("start"),
            end=request.args.get("end"),
            timezone_name=app.config["TIMEZONE"],
            operator=request.args.get("operator"),
            network_type=request.args.get("network_type"),
            require_location=request.args.get("require_location") == "true",
            limit=_parse_limit(request.args.get("limit"), default=5000, maximum=10000),
        )
        if isinstance(rows, tuple):
            return jsonify(rows[0]), rows[1]

        buffer = io.StringIO()
        writer = csv.DictWriter(
            buffer,
            fieldnames=[
                "id",
                "device_id",
                "operator",
                "signal_power",
                "snr",
                "network_type",
                "frequency_band",
                "cell_id",
                "latitude",
                "longitude",
                "location_accuracy_m",
                "timestamp",
            ],
        )
        writer.writeheader()
        for row in rows:
            writer.writerow(row.to_dict())

        return Response(
            buffer.getvalue(),
            mimetype="text/csv",
            headers={"Content-Disposition": "attachment; filename=network-cell-data.csv"},
        )

    @app.get("/api/handover-stats")
    def handover_stats():
        device_id = request.args.get("device_id", "").strip()
        if not device_id:
            return jsonify({"error": "device_id is required"}), 400

        rows = _query_rows(
            device_id=device_id,
            start=request.args.get("start"),
            end=request.args.get("end"),
            timezone_name=app.config["TIMEZONE"],
            limit=_parse_limit(request.args.get("limit"), default=5000, maximum=10000),
        )
        if isinstance(rows, tuple):
            return jsonify(rows[0]), rows[1]

        handovers = []
        ping_pong_count = 0
        last_key = None
        previous_key = None
        for previous, current in zip(rows, rows[1:]):
            previous_tuple = (previous.network_type, previous.cell_id)
            current_tuple = (current.network_type, current.cell_id)
            if previous_tuple != current_tuple:
                handovers.append(
                    {
                        "from_network_type": previous.network_type,
                        "from_cell_id": previous.cell_id,
                        "to_network_type": current.network_type,
                        "to_cell_id": current.cell_id,
                        "timestamp": current.timestamp.isoformat(),
                    }
                )
                if previous_key is not None and current_tuple == previous_key and previous_tuple == last_key:
                    ping_pong_count += 1
                previous_key = last_key
                last_key = current_tuple

        return jsonify(
            {
                "device_id": device_id,
                "handover_count": len(handovers),
                "ping_pong_count": ping_pong_count,
                "events": handovers[-100:],
            }
        )

    @app.get("/api/heatmap-data")
    def heatmap_data():
        rows = _query_rows(
            device_id=request.args.get("device_id", "").strip() or None,
            start=request.args.get("start"),
            end=request.args.get("end"),
            timezone_name=app.config["TIMEZONE"],
            operator=request.args.get("operator"),
            network_type=request.args.get("network_type"),
            require_location=True,
            limit=_parse_limit(request.args.get("limit"), default=5000, maximum=10000),
        )
        if isinstance(rows, tuple):
            return jsonify(rows[0]), rows[1]

        grid_size = _parse_grid_size(request.args.get("grid_size"))
        aggregated = _aggregate_heatmap_rows(rows, grid_size)

        return jsonify(
            {
                "count": len(aggregated),
                "grid_size": grid_size,
                "points": aggregated,
            }
        )

    @app.get("/heatmap")
    def heatmap_page():
        devices = [device["device_id"] for device in _fetch_devices(app.config["ACTIVE_DEVICE_MINUTES"])]
        operators = sorted(
            value[0]
            for value in db.session.query(CellData.operator).distinct().order_by(CellData.operator.asc()).all()
            if value[0]
        )
        return render_template(
            "heatmap.html",
            devices=devices,
            operators=operators,
        )


def register_cli(app: Flask) -> None:
    @app.cli.command("seed-demo-data")
    def seed_demo_data_command():
        """Populate the local database with a small, realistic demo dataset."""
        _seed_demo_data()
        print("Seeded demo data.")


def _ensure_instance_dir(app: Flask) -> None:
    instance_dir = app.config["SQLALCHEMY_DATABASE_URI"].removeprefix("sqlite:///")
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

    network_type = str(payload["network_type"]).strip().upper()
    if network_type not in {"2G", "3G", "4G", "5G"}:
        return "network_type must be one of: 2G, 3G, 4G, 5G"

    latitude = payload.get("latitude")
    longitude = payload.get("longitude")
    if (latitude in (None, "") and longitude not in (None, "")) or (
        longitude in (None, "") and latitude not in (None, "")
    ):
        return "latitude and longitude must be provided together"
    if latitude not in (None, ""):
        try:
            latitude = float(latitude)
            longitude = float(longitude)
        except (TypeError, ValueError):
            return "latitude and longitude must be numeric when provided"
        if not -90 <= latitude <= 90:
            return "latitude must be between -90 and 90"
        if not -180 <= longitude <= 180:
            return "longitude must be between -180 and 180"

    if payload.get("location_accuracy_m") not in (None, ""):
        try:
            accuracy = float(payload["location_accuracy_m"])
        except (TypeError, ValueError):
            return "location_accuracy_m must be numeric when provided"
        if accuracy < 0:
            return "location_accuracy_m must be non-negative"

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


def _base_query(device_id=None, operator=None, network_type=None, require_location=False):
    query = CellData.query
    if device_id:
        query = query.filter(CellData.device_id == device_id)
    if operator:
        query = query.filter(CellData.operator == operator.strip())
    if network_type:
        query = query.filter(CellData.network_type == network_type.strip().upper())
    if require_location:
        query = query.filter(CellData.latitude.isnot(None), CellData.longitude.isnot(None))
    return query


def _query_rows(
    device_id,
    start,
    end,
    timezone_name: str,
    operator=None,
    network_type=None,
    require_location=False,
    limit=None,
):
    scoped_query = _base_query(
        device_id=device_id,
        operator=operator,
        network_type=network_type,
        require_location=require_location,
    )
    first_row = scoped_query.order_by(CellData.timestamp.asc()).first()
    last_row = scoped_query.order_by(CellData.timestamp.desc()).first()
    if not first_row or not last_row:
        return {"message": "No data found"}, 404

    try:
        start_dt = _parse_timestamp(start, timezone_name) if start else first_row.timestamp
        end_dt = _parse_timestamp(end, timezone_name) if end else last_row.timestamp
    except ValueError as exc:
        return {"error": str(exc)}, 400

    if end_dt < start_dt:
        return {"error": "end must be after start"}, 400

    query = scoped_query.filter(and_(CellData.timestamp >= start_dt, CellData.timestamp <= end_dt)).order_by(
        CellData.timestamp.asc()
    )
    if limit:
        query = query.limit(limit)

    filtered_rows = query.all()
    if not filtered_rows:
        return {"message": "No data found in the requested time range"}, 404

    return filtered_rows


def _build_stats_payload(rows: list[CellData]) -> dict:
    total = len(rows)
    operator_counts = Counter(row.operator for row in rows)
    network_counts = Counter(row.network_type for row in rows)
    signal_by_network = defaultdict(list)
    snr_by_network = defaultdict(list)
    signal_by_device = defaultdict(list)

    for row in rows:
        signal_by_network[row.network_type].append(row.signal_power)
        signal_by_device[row.device_id].append(row.signal_power)
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
        "avg_signal_per_device": {
            key: round(mean(values), 2) for key, values in signal_by_device.items()
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


def _parse_limit(raw_value, default: int, maximum: int) -> int:
    if raw_value in (None, ""):
        return default
    try:
        value = int(raw_value)
    except (TypeError, ValueError):
        return default
    return max(1, min(value, maximum))


def _parse_grid_size(raw_value) -> int:
    if raw_value in (None, ""):
        return 3
    try:
        value = int(raw_value)
    except (TypeError, ValueError):
        return 3
    return max(1, min(value, 5))


def _aggregate_heatmap_rows(rows: list[CellData], grid_size: int) -> list[dict]:
    grouped = defaultdict(list)
    for row in rows:
        key = (round(row.latitude, grid_size), round(row.longitude, grid_size))
        grouped[key].append(row)

    aggregated = []
    for (lat, lon), items in grouped.items():
        signal_values = [item.signal_power for item in items]
        snr_values = [item.snr for item in items if item.snr is not None]
        aggregated.append(
            {
                "latitude": lat,
                "longitude": lon,
                "sample_count": len(items),
                "avg_signal_power": round(mean(signal_values), 2),
                "avg_snr": round(mean(snr_values), 2) if snr_values else None,
                "operators": sorted({item.operator for item in items}),
                "network_types": sorted({item.network_type for item in items}),
                "latest_timestamp": items[-1].timestamp.isoformat(),
                "heat_intensity": min(1.0, len(items) / 10),
            }
        )

    aggregated.sort(key=lambda item: item["sample_count"], reverse=True)
    return aggregated


def _run_sqlite_migrations() -> None:
    if db.engine.dialect.name != "sqlite":
        return

    inspector = inspect(db.engine)
    existing_columns = {column["name"] for column in inspector.get_columns("cell_data")}
    migrations = {
        "latitude": "ALTER TABLE cell_data ADD COLUMN latitude FLOAT",
        "longitude": "ALTER TABLE cell_data ADD COLUMN longitude FLOAT",
        "location_accuracy_m": "ALTER TABLE cell_data ADD COLUMN location_accuracy_m FLOAT",
    }
    for column_name, statement in migrations.items():
        if column_name not in existing_columns:
            db.session.execute(text(statement))
    db.session.commit()


def _seed_demo_data() -> None:
    now = datetime.now(timezone.utc)
    sample_rows = [
        {
            "device_id": "demo-phone-01",
            "operator": "Alfa",
            "signal_power": -84,
            "snr": 14.5,
            "network_type": "4G",
            "frequency_band": "Band 3 (1800MHz)",
            "cell_id": "37100-81937409",
            "latitude": 33.8938,
            "longitude": 35.5018,
            "location_accuracy_m": 8.0,
            "timestamp": now - timedelta(minutes=12),
            "mac_address": "02:00:00:00:00:01",
        },
        {
            "device_id": "demo-phone-01",
            "operator": "Alfa",
            "signal_power": -90,
            "snr": 11.8,
            "network_type": "3G",
            "frequency_band": "Band 1 (2100MHz)",
            "cell_id": "37100-81937000",
            "latitude": 33.8942,
            "longitude": 35.5025,
            "location_accuracy_m": 9.5,
            "timestamp": now - timedelta(minutes=8),
            "mac_address": "02:00:00:00:00:01",
        },
        {
            "device_id": "demo-phone-02",
            "operator": "Touch",
            "signal_power": -78,
            "snr": 17.1,
            "network_type": "4G",
            "frequency_band": "Band 20 (800MHz)",
            "cell_id": "37101-11112222",
            "latitude": 33.9007,
            "longitude": 35.4834,
            "location_accuracy_m": 12.0,
            "timestamp": now - timedelta(minutes=5),
            "mac_address": "02:00:00:00:00:02",
        },
        {
            "device_id": "demo-phone-02",
            "operator": "Touch",
            "signal_power": -98,
            "snr": None,
            "network_type": "2G",
            "frequency_band": "GSM 900",
            "cell_id": "37101-11110000",
            "latitude": 33.9015,
            "longitude": 35.484,
            "location_accuracy_m": 15.0,
            "timestamp": now - timedelta(minutes=2),
            "mac_address": "02:00:00:00:00:02",
        },
    ]

    for row in sample_rows:
        db.session.add(
            CellData(
                device_id=row["device_id"],
                operator=row["operator"],
                signal_power=row["signal_power"],
                snr=row["snr"],
                network_type=row["network_type"],
                frequency_band=row["frequency_band"],
                cell_id=row["cell_id"],
                latitude=row["latitude"],
                longitude=row["longitude"],
                location_accuracy_m=row["location_accuracy_m"],
                timestamp=row["timestamp"],
            )
        )
        device = DeviceLog.query.filter_by(device_id=row["device_id"]).first()
        if not device:
            device = DeviceLog(device_id=row["device_id"], first_seen=row["timestamp"])
            db.session.add(device)
        device.mac_address = row["mac_address"]
        device.last_seen = row["timestamp"]
        device.is_active = True

    db.session.commit()


app = create_app()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
