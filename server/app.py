import csv
import io
import math
import os
import secrets
from collections import Counter, defaultdict, deque
from datetime import datetime, timedelta, timezone
from statistics import mean
from zoneinfo import ZoneInfo

from flask import Flask, Response, current_app, jsonify, render_template, request
from flask_cors import CORS
from flask_socketio import SocketIO
from itsdangerous import BadSignature, SignatureExpired, URLSafeTimedSerializer
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas
from sqlalchemy import and_
from sqlalchemy import inspect, text
from werkzeug.security import check_password_hash, generate_password_hash

from config import Config
from models import AlertRule, CellData, DeviceLog, NeighborCellData, SpeedTestResult, User, db


TIMESTAMP_FORMATS = (
    "%d %b %Y %I:%M %p",
    "%Y-%m-%d %H:%M:%S",
    "%Y-%m-%dT%H:%M:%S",
    "%Y-%m-%dT%H:%M:%S.%f",
)

NETWORK_RANK = {"2G": 1, "3G": 2, "4G": 3, "5G": 4}
socketio = SocketIO(cors_allowed_origins="*", async_mode="threading")
recent_alert_events = deque(maxlen=25)


def create_app(config_object=Config) -> Flask:
    app = Flask(__name__)
    app.config.from_object(config_object)

    CORS(app, resources={r"/*": {"origins": "*"}})
    db.init_app(app)
    socketio.init_app(app)

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

    @app.post("/auth/register")
    def register_user():
        payload = request.get_json(silent=True)
        if not payload:
            return jsonify({"success": False, "message": "Expected a JSON request body"}), 400

        missing = [
            field
            for field in ("name", "email", "password", "device_id")
            if payload.get(field) in (None, "")
        ]
        if missing:
            return jsonify({"success": False, "message": f"Missing required fields: {', '.join(missing)}"}), 422

        email = str(payload["email"]).strip().lower()
        existing_user = User.query.filter(db.func.lower(User.email) == email).first()
        if existing_user:
            return jsonify({"success": False, "message": "email already registered"}), 409

        user = User(
            name=str(payload["name"]).strip(),
            email=email,
            password_hash=generate_password_hash(str(payload["password"])),
            device_id=str(payload["device_id"]).strip(),
            last_login_at=datetime.now(timezone.utc),
        )
        db.session.add(user)
        db.session.commit()
        return jsonify(_build_auth_response(user, "registered"))

    @app.post("/auth/login")
    def login_user():
        payload = request.get_json(silent=True)
        if not payload:
            return jsonify({"success": False, "message": "Expected a JSON request body"}), 400

        missing = [field for field in ("email", "password", "device_id") if payload.get(field) in (None, "")]
        if missing:
            return jsonify({"success": False, "message": f"Missing required fields: {', '.join(missing)}"}), 422

        email = str(payload["email"]).strip().lower()
        user = User.query.filter(db.func.lower(User.email) == email).first()
        if not user:
            return jsonify({"success": False, "message": "user not found"}), 404
        if not check_password_hash(user.password_hash, str(payload["password"])):
            return jsonify({"success": False, "message": "invalid credentials"}), 401

        user.device_id = str(payload["device_id"]).strip()
        user.last_login_at = datetime.now(timezone.utc)
        db.session.commit()
        return jsonify(_build_auth_response(user, "logged in"))

    @app.post("/auth/refresh")
    def refresh_token():
        payload = request.get_json(silent=True)
        if not payload or payload.get("refresh_token") in (None, ""):
            return jsonify({"success": False, "message": "refresh_token is required"}), 400

        token_data = _decode_token(
            payload["refresh_token"],
            max_age=app.config["REFRESH_TOKEN_MAX_AGE_SECONDS"],
            expected_type="refresh",
        )
        if isinstance(token_data, tuple):
            return jsonify(token_data[0]), token_data[1]

        user = db.session.get(User, token_data["user_id"])
        if not user:
            return jsonify({"success": False, "message": "user not found"}), 404

        if token_data.get("device_id"):
            user.device_id = token_data["device_id"]
            db.session.commit()
        return jsonify(_build_auth_response(user, "refreshed"))

    @app.get("/predict")
    def predict_signal():
        required = ["latitude", "longitude", "operator", "network_type"]
        missing = [field for field in required if request.args.get(field) in (None, "")]
        if missing:
            return jsonify({"error": f"Missing query parameters: {', '.join(missing)}"}), 400

        prediction = _predict_signal_quality(
            latitude=request.args.get("latitude"),
            longitude=request.args.get("longitude"),
            operator=request.args.get("operator"),
            network_type=request.args.get("network_type"),
            timestamp=request.args.get("timestamp"),
            timezone_name=app.config["TIMEZONE"],
        )
        if isinstance(prediction, tuple):
            return jsonify(prediction[0]), prediction[1]
        return jsonify(prediction)

    @app.post("/receive-data")
    def receive_data():
        payload = request.get_json(silent=True)
        if not payload:
            return jsonify({"success": False, "error": "Expected a JSON request body"}), 400

        try:
            cell_record = _store_cell_payload(payload, app.config["TIMEZONE"])
            db.session.commit()
            _emit_realtime_updates(cell_record)
        except ValueError as exc:
            db.session.rollback()
            return jsonify({"success": False, "error": str(exc)}), 400
        except Exception:
            db.session.rollback()
            return jsonify({"success": False, "error": "Failed to store cell data"}), 500

        return jsonify({"success": True, "message": "stored"}), 201

    @app.post("/receive-batch")
    @app.post("/receive-data/batch")
    def receive_batch():
        payload = request.get_json(silent=True)
        records = None
        if isinstance(payload, dict):
            records = payload.get("records")
            if records is None:
                records = payload.get("data")
        if not isinstance(payload, dict) or not isinstance(records, list):
            return jsonify({"success": False, "error": "Expected a JSON body with a records array"}), 400

        stored = 0
        try:
            for record in records:
                _store_cell_payload(record, app.config["TIMEZONE"])
                stored += 1
            db.session.commit()
        except ValueError as exc:
            db.session.rollback()
            return jsonify({"success": False, "error": str(exc), "stored_before_error": stored}), 400
        except Exception:
            db.session.rollback()
            return jsonify({"success": False, "error": "Failed to store batch data", "stored_before_error": stored}), 500

        socketio.emit("batch_ingested", {"stored_count": stored})
        return jsonify({"success": True, "message": "batch stored", "stored_count": stored}), 201

    @app.get("/get-stats")
    def get_stats():
        device_id = request.args.get("device_id", "").strip()
        if not device_id:
            return jsonify({"success": False, "error": "device_id is required"}), 400

        rows = _query_rows(
            device_id=device_id,
            start=_get_time_filter("start", "from"),
            end=_get_time_filter("end", "to"),
            timezone_name=app.config["TIMEZONE"],
        )
        if isinstance(rows, tuple):
            return jsonify(rows[0]), rows[1]

        return jsonify(_build_stats_payload(rows)), 200

    @app.get("/get-stats/avg-all")
    def get_avg_all():
        rows = _query_rows(
            device_id=None,
            start=_get_time_filter("start", "from"),
            end=_get_time_filter("end", "to"),
            timezone_name=app.config["TIMEZONE"],
        )
        if isinstance(rows, tuple):
            return jsonify(rows[0]), rows[1]

        signal_values = [row.signal_power for row in rows]
        snr_values = [row.snr for row in rows if row.snr is not None]
        signal_per_device = defaultdict(list)
        for row in rows:
            signal_per_device[row.device_id].append(row.signal_power)

        payload = _build_stats_payload(rows)
        payload.update(
            {
                "avg_signal_all_devices": round(mean(signal_values), 2),
                "avg_snr_all_devices": round(mean(snr_values), 2) if snr_values else None,
                "record_count": len(rows),
                "unique_devices": len({row.device_id for row in rows}),
            }
        )
        return jsonify(payload)

    @app.get("/central-stats")
    def central_stats():
        devices = _fetch_devices(active_window_minutes=app.config["ACTIVE_DEVICE_MINUTES"])
        recent_records = (
            CellData.query.order_by(CellData.timestamp.desc()).limit(10).all()
        )

        if _wants_json_response():
            return jsonify(
                {
                    "success": True,
                    "total_devices": len(devices),
                    "active_devices": sum(1 for device in devices if device["is_active"]),
                    "devices": [
                        {
                            **device,
                            "first_seen": device["first_seen"].isoformat() if device["first_seen"] else None,
                            "last_seen": device["last_seen"].isoformat() if device["last_seen"] else None,
                        }
                        for device in devices
                    ],
                }
            )

        return render_template(
            "dashboard.html",
            devices=devices,
            recent_records=recent_records,
            total_devices=len(devices),
            active_devices=sum(1 for device in devices if device["is_active"]),
            total_records=CellData.query.count(),
        )

    @app.get("/api/dashboard-summary")
    def dashboard_summary():
        recent_rows = CellData.query.order_by(CellData.timestamp.desc()).limit(500).all()
        operator_counts = Counter(row.operator for row in recent_rows)
        network_counts = Counter(row.network_type for row in recent_rows)
        geo_count = sum(1 for row in recent_rows if row.latitude is not None and row.longitude is not None)
        avg_signal = round(mean([row.signal_power for row in recent_rows]), 2) if recent_rows else None

        speed_rows = SpeedTestResult.query.order_by(SpeedTestResult.timestamp.desc()).limit(100).all()
        handover_total = 0
        per_device = defaultdict(list)
        for row in recent_rows:
            per_device[row.device_id].append(row)
        for rows in per_device.values():
            for previous, current in zip(rows, rows[1:]):
                if (previous.network_type, previous.cell_id) != (current.network_type, current.cell_id):
                    handover_total += 1

        return jsonify(
            {
                "total_records": CellData.query.count(),
                "known_devices": DeviceLog.query.count(),
                "geo_tagged_records": geo_count,
                "avg_signal_power": avg_signal,
                "operator_distribution": dict(operator_counts.most_common()),
                "network_distribution": dict(network_counts.most_common()),
                "speed_test_count": len(speed_rows),
                "avg_download_mbps": round(mean([row.download_mbps for row in speed_rows]), 2)
                if speed_rows
                else None,
                "avg_upload_mbps": round(mean([row.upload_mbps for row in speed_rows]), 2)
                if speed_rows
                else None,
                "handover_event_count": handover_total,
                "alert_count": len(recent_alert_events),
                "recent_alerts": list(recent_alert_events),
            }
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

    @app.get("/device/profile")
    def device_profile():
        device_id = request.args.get("device_id", "").strip()
        if not device_id:
            return jsonify({"success": False, "error": "device_id is required"}), 400

        device = DeviceLog.query.filter_by(device_id=device_id).first()
        if not device:
            return jsonify({"success": False, "error": "device not found"}), 404

        rows = CellData.query.filter_by(device_id=device_id).order_by(CellData.timestamp.asc()).all()
        if not rows:
            return jsonify({"success": False, "error": "no records found"}), 404

        per_type = Counter(row.network_type for row in rows)
        return jsonify(
            {
                "success": True,
                "device_id": device_id,
                "name": device_id,
                "total_records": len(rows),
                "first_seen": _as_utc(device.first_seen).isoformat() if device.first_seen else None,
                "last_seen": _as_utc(device.last_seen).isoformat() if device.last_seen else None,
                "records_per_type": dict(per_type),
            }
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
                "lac",
                "mcc",
                "mnc",
                "sim_slot",
                "subscription_id",
                "latitude",
                "longitude",
                "location_accuracy_m",
                "timestamp",
                "neighbor_cells",
            ],
            extrasaction="ignore",
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
        aggregated = _aggregate_heatmap_rows(
            rows,
            grid_size,
            max_points=_parse_limit(
                request.args.get("max_points"),
                default=app.config["HEATMAP_DEFAULT_POINT_LIMIT"],
                maximum=5000,
            ),
        )

        return jsonify(
            {
                "count": len(aggregated),
                "grid_size": grid_size,
                "points": aggregated,
            }
        )

    @app.get("/api/neighbor-cells")
    def neighbor_cells():
        query = (
            db.session.query(
                NeighborCellData.cell_id,
                NeighborCellData.network_type,
                db.func.avg(NeighborCellData.signal_power),
                db.func.count(NeighborCellData.id),
            )
            .join(CellData, NeighborCellData.cell_data_id == CellData.id)
            .group_by(NeighborCellData.cell_id, NeighborCellData.network_type)
            .order_by(db.func.count(NeighborCellData.id).desc())
        )
        device_id = request.args.get("device_id", "").strip()
        if device_id:
            query = query.filter(CellData.device_id == device_id)
        operator = request.args.get("operator", "").strip()
        if operator:
            query = query.filter(CellData.operator == operator)

        rows = query.limit(_parse_limit(request.args.get("limit"), default=50, maximum=500)).all()
        return jsonify(
            {
                "neighbors": [
                    {
                        "cell_id": row[0],
                        "network_type": row[1],
                        "avg_signal_power": round(float(row[2]), 2) if row[2] is not None else None,
                        "seen_count": row[3],
                    }
                    for row in rows
                ]
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

    @app.get("/api/speed-test/download")
    @app.get("/speedtest/download")
    def speed_test_download():
        size_mb = _parse_limit(request.args.get("size_mb"), default=5, maximum=25)
        data = os.urandom(size_mb * 1024 * 1024)
        return Response(
            data,
            mimetype="application/octet-stream",
            headers={
                "Content-Disposition": f"attachment; filename=speed-test-{size_mb}mb.bin",
                "Content-Length": str(len(data)),
            },
        )

    @app.post("/api/speed-test/upload")
    @app.post("/speedtest/upload")
    def speed_test_upload():
        started = datetime.now(timezone.utc)
        if request.files:
            incoming = next(iter(request.files.values()))
            content = incoming.read()
        else:
            content = request.get_data() or b""
        duration_ms = (datetime.now(timezone.utc) - started).total_seconds() * 1000
        return jsonify(
            {
                "received_bytes": len(content),
                "server_processing_ms": round(duration_ms, 2),
            }
        )

    @app.post("/api/speed-test/result")
    def speed_test_result():
        payload = request.get_json(silent=True)
        if not payload:
            return jsonify({"error": "Expected a JSON request body"}), 400
        result, status_code = _store_speed_test_payload(payload, app.config["TIMEZONE"])
        if status_code != 201:
            return jsonify(result), status_code
        return jsonify({"success": True, "message": "stored"}), 201

    @app.post("/speed-test")
    def speed_test_result_android():
        payload = request.get_json(silent=True)
        if not payload:
            return jsonify({"success": False, "error": "Expected a JSON request body"}), 400

        translated_payload = {
            "device_id": payload.get("device_id") or request.args.get("device_id") or _extract_device_id_from_auth(),
            "operator": payload.get("operator"),
            "network_type": payload.get("network_type"),
            "signal_power": payload.get("signal_power"),
            "download_mbps": payload.get("download_mbps", payload.get("downloadMbps")),
            "upload_mbps": payload.get("upload_mbps", payload.get("uploadMbps")),
            "latency_ms": payload.get("latency_ms", payload.get("latencyMs", payload.get("latency"))),
            "timestamp": payload.get("timestamp"),
        }

        result, status_code = _store_speed_test_payload(translated_payload, app.config["TIMEZONE"])
        if status_code != 201:
            return jsonify(result), status_code
        return jsonify({"success": True, "message": "stored"}), 201

    @app.get("/api/speed-test/stats")
    def speed_test_stats():
        query = SpeedTestResult.query
        device_id = request.args.get("device_id", "").strip()
        operator = request.args.get("operator", "").strip()
        network_type = request.args.get("network_type", "").strip().upper()
        if device_id:
            query = query.filter(SpeedTestResult.device_id == device_id)
        if operator:
            query = query.filter(SpeedTestResult.operator == operator)
        if network_type:
            query = query.filter(SpeedTestResult.network_type == network_type)
        rows = query.order_by(SpeedTestResult.timestamp.asc()).all()
        if not rows:
            return jsonify({"message": "No speed test results found"}), 404

        by_network = defaultdict(list)
        by_operator = defaultdict(list)
        for row in rows:
            if row.network_type:
                by_network[row.network_type].append(row.download_mbps)
            if row.operator:
                by_operator[row.operator].append(row.download_mbps)
        return jsonify(
            {
                "record_count": len(rows),
                "avg_download_mbps": round(mean([row.download_mbps for row in rows]), 2),
                "avg_upload_mbps": round(mean([row.upload_mbps for row in rows]), 2),
                "avg_latency_ms": round(mean([row.latency_ms for row in rows if row.latency_ms is not None]), 2)
                if any(row.latency_ms is not None for row in rows)
                else None,
                "avg_download_by_network_type": {
                    key: round(mean(values), 2) for key, values in by_network.items()
                },
                "avg_download_by_operator": {
                    key: round(mean(values), 2) for key, values in by_operator.items()
                },
            }
        )

    @app.get("/api/report.pdf")
    def export_pdf_report():
        rows = _query_rows(
            device_id=request.args.get("device_id", "").strip() or None,
            start=request.args.get("start"),
            end=request.args.get("end"),
            timezone_name=app.config["TIMEZONE"],
            operator=request.args.get("operator"),
            network_type=request.args.get("network_type"),
            limit=_parse_limit(request.args.get("limit"), default=5000, maximum=10000),
        )
        if isinstance(rows, tuple):
            return jsonify(rows[0]), rows[1]

        pdf_data = _build_pdf_report(rows)
        return Response(
            pdf_data,
            mimetype="application/pdf",
            headers={"Content-Disposition": "attachment; filename=network-report.pdf"},
        )

    @app.route("/api/alert-rules", methods=["GET", "POST"])
    def alert_rules():
        if request.method == "GET":
            device_id = request.args.get("device_id", "").strip()
            query = AlertRule.query.filter_by(is_enabled=True)
            if device_id:
                query = query.filter((AlertRule.device_id == device_id) | (AlertRule.device_id.is_(None)))
            return jsonify({"rules": [rule.to_dict() for rule in query.order_by(AlertRule.created_at.desc()).all()]})

        payload = request.get_json(silent=True)
        if not payload:
            return jsonify({"error": "Expected a JSON request body"}), 400
        rule = AlertRule(
            device_id=_clean_optional_string(payload.get("device_id")),
            min_signal_power=_optional_int(payload.get("min_signal_power")),
            trigger_on_network_downgrade=bool(payload.get("trigger_on_network_downgrade", False)),
            is_enabled=bool(payload.get("is_enabled", True)),
        )
        db.session.add(rule)
        db.session.commit()
        return jsonify({"message": "Alert rule stored", "rule": rule.to_dict()}), 201


def register_cli(app: Flask) -> None:
    return None


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

    if payload.get("sim_slot") not in (None, ""):
        try:
            int(payload["sim_slot"])
        except (TypeError, ValueError):
            return "sim_slot must be an integer when provided"

    if payload.get("neighbor_cells") is not None and not isinstance(payload.get("neighbor_cells"), list):
        return "neighbor_cells must be a list when provided"

    return None


def _parse_timestamp(value, timezone_name: str) -> datetime:
    if value in (None, ""):
        return datetime.now(timezone.utc)

    if isinstance(value, datetime):
        return value.astimezone(timezone.utc) if value.tzinfo else value.replace(tzinfo=timezone.utc)

    if isinstance(value, (int, float)):
        numeric_value = float(value)
        if numeric_value > 1_000_000_000_000:
            numeric_value /= 1000.0
        return datetime.fromtimestamp(numeric_value, tz=timezone.utc)

    text = str(value).strip()
    if text.isdigit():
        numeric_value = float(text)
        if numeric_value > 1_000_000_000_000:
            numeric_value /= 1000.0
        return datetime.fromtimestamp(numeric_value, tz=timezone.utc)
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


def _as_utc(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _get_serializer() -> URLSafeTimedSerializer:
    return URLSafeTimedSerializer(current_app.config["SECRET_KEY"], salt="network-cell-analyzer-auth")


def _encode_token(user: User, token_type: str) -> str:
    return _get_serializer().dumps(
        {
            "user_id": user.id,
            "email": user.email,
            "device_id": user.device_id,
            "type": token_type,
            "nonce": secrets.token_hex(8),
        }
    )


def _decode_token(token: str, max_age: int, expected_type: str) -> dict | tuple[dict, int]:
    try:
        payload = _get_serializer().loads(token, max_age=max_age)
    except SignatureExpired:
        return {"success": False, "message": "token expired"}, 401
    except BadSignature:
        return {"success": False, "message": "invalid token"}, 401

    if payload.get("type") != expected_type:
        return {"success": False, "message": "invalid token type"}, 401
    return payload


def _build_auth_response(user: User, message: str) -> dict:
    return {
        "success": True,
        "token": _encode_token(user, "access"),
        "refresh_token": _encode_token(user, "refresh"),
        "user_name": user.name,
        "user_email": user.email,
        "message": message,
    }


def _extract_device_id_from_auth() -> str | None:
    authorization = request.headers.get("Authorization", "")
    if not authorization.startswith("Bearer "):
        return None
    token = authorization.removeprefix("Bearer ").strip()
    payload = _decode_token(
        token,
        max_age=current_app.config["ACCESS_TOKEN_MAX_AGE_SECONDS"],
        expected_type="access",
    )
    if isinstance(payload, tuple):
        return None
    return payload.get("device_id")


def _get_time_filter(*names: str):
    for name in names:
        value = request.args.get(name)
        if value not in (None, ""):
            return value
    return None


def _wants_json_response() -> bool:
    response_format = request.args.get("format", "").strip().lower()
    if response_format == "json":
        return True
    accept = request.headers.get("Accept", "")
    return "application/json" in accept and "text/html" not in accept


def _optional_float(value):
    if value in (None, ""):
        return None
    return float(value)


def _optional_int(value):
    if value in (None, ""):
        return None
    return int(value)


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


def _store_cell_payload(payload: dict, timezone_name: str) -> CellData:
    validation_error = _validate_payload(payload)
    if validation_error:
        raise ValueError(validation_error)

    cell_record = CellData(
        device_id=payload["device_id"].strip(),
        operator=payload["operator"].strip(),
        signal_power=int(payload["signal_power"]),
        snr=_optional_float(payload.get("snr")),
        network_type=str(payload["network_type"]).strip().upper(),
        frequency_band=_clean_optional_string(payload.get("frequency_band")),
        cell_id=str(payload["cell_id"]).strip(),
        lac=_clean_optional_string(payload.get("lac")),
        mcc=_clean_optional_string(payload.get("mcc")),
        mnc=_clean_optional_string(payload.get("mnc")),
        sim_slot=_optional_int(payload.get("sim_slot")),
        subscription_id=_clean_optional_string(payload.get("subscription_id")),
        latitude=_optional_float(payload.get("latitude")),
        longitude=_optional_float(payload.get("longitude")),
        location_accuracy_m=_optional_float(payload.get("location_accuracy_m")),
        timestamp=_parse_timestamp(payload.get("timestamp"), timezone_name),
    )
    db.session.add(cell_record)
    db.session.flush()
    _store_neighbor_cells(cell_record, payload.get("neighbor_cells"))
    _upsert_device_log(payload)
    return cell_record


def _store_neighbor_cells(cell_record: CellData, neighbor_cells) -> None:
    if not neighbor_cells:
        return
    if not isinstance(neighbor_cells, list):
        raise ValueError("neighbor_cells must be a list when provided")
    for neighbor in neighbor_cells:
        if not isinstance(neighbor, dict):
            raise ValueError("neighbor_cells entries must be objects")
        db.session.add(
            NeighborCellData(
                cell_data_id=cell_record.id,
                network_type=_clean_optional_string(neighbor.get("network_type")),
                cell_id=_clean_optional_string(neighbor.get("cell_id")),
                signal_power=_optional_float(neighbor.get("signal_power")),
                is_registered=bool(neighbor.get("is_registered", False)),
            )
        )


def _emit_realtime_updates(cell_record: CellData) -> None:
    payload = cell_record.to_dict()
    socketio.emit("new_cell_data", payload)
    socketio.emit(
        "dashboard_summary",
        {
            "total_records": CellData.query.count(),
            "device_count": DeviceLog.query.count(),
            "last_device_id": cell_record.device_id,
        },
    )
    for alert in _evaluate_alerts(cell_record):
        recent_alert_events.appendleft(alert)
        socketio.emit("signal_alert", alert)


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
        start_dt = _parse_timestamp(start, timezone_name) if start else _as_utc(first_row.timestamp)
        end_dt = _parse_timestamp(end, timezone_name) if end else _as_utc(last_row.timestamp)
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

    for row in filtered_rows:
        row.timestamp = _as_utc(row.timestamp)

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

    operator_percentages = {
        key: round((value / total) * 100, 2) for key, value in operator_counts.items()
    }
    network_percentages = {
        key: round((value / total) * 100, 2) for key, value in network_counts.items()
    }
    avg_signal_per_type = {
        key: round(mean(values), 2) for key, values in signal_by_network.items()
    }
    avg_snr_per_type = {
        key: round(mean(values), 2) for key, values in snr_by_network.items()
    }
    avg_signal_per_device = {
        key: round(mean(values), 2) for key, values in signal_by_device.items()
    }
    first_timestamp = rows[0].timestamp.isoformat()
    last_timestamp = rows[-1].timestamp.isoformat()

    return {
        "success": True,
        "connectivity_per_operator": {
            key: _percentage(value, total) for key, value in operator_counts.items()
        },
        "connectivity_per_network_type": {
            key: _percentage(value, total) for key, value in network_counts.items()
        },
        "avg_signal_per_network_type": avg_signal_per_type,
        "avg_snr_per_network_type": avg_snr_per_type,
        "avg_signal_per_device": avg_signal_per_device,
        "avg_signal_device": round(mean([row.signal_power for row in rows]), 2),
        "record_count": total,
        "first_timestamp": first_timestamp,
        "last_timestamp": last_timestamp,
        "operator_time": operator_percentages,
        "network_type_time": network_percentages,
        "avg_signal_per_type": avg_signal_per_type,
        "avg_snr_per_type": avg_snr_per_type,
        "total_records": total,
        "from_date": first_timestamp[:10],
        "to_date": last_timestamp[:10],
    }


def _percentage(count: int, total: int) -> str:
    return f"{round((count / total) * 100, 2)}%"


def _fetch_devices(active_window_minutes: int) -> list[dict]:
    now = datetime.now(timezone.utc)
    threshold = now - timedelta(minutes=active_window_minutes)
    devices = []

    for device in DeviceLog.query.order_by(DeviceLog.last_seen.desc()).all():
        first_seen = _as_utc(device.first_seen)
        last_seen = _as_utc(device.last_seen)
        is_active = bool(last_seen and last_seen >= threshold)
        if device.is_active != is_active:
            device.is_active = is_active
            db.session.add(device)
        if device.first_seen != first_seen:
            device.first_seen = first_seen
            db.session.add(device)
        if device.last_seen != last_seen:
            device.last_seen = last_seen
            db.session.add(device)

        devices.append(
            {
                "device_id": device.device_id,
                "ip_address": device.ip_address,
                "mac_address": device.mac_address,
                "first_seen": first_seen,
                "last_seen": last_seen,
                "is_active": is_active,
                "total_records": CellData.query.filter_by(device_id=device.device_id).count(),
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


def _aggregate_heatmap_rows(rows: list[CellData], grid_size: int, max_points: int) -> list[dict]:
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
    return aggregated[:max_points]


def _store_speed_test_payload(payload: dict, timezone_name: str) -> tuple[dict, int]:
    required = ["device_id", "download_mbps", "upload_mbps"]
    missing = [field for field in required if payload.get(field) in (None, "")]
    if missing:
        return {"success": False, "error": f"Missing required fields: {', '.join(missing)}"}, 400

    try:
        result = SpeedTestResult(
            device_id=str(payload["device_id"]).strip(),
            operator=_clean_optional_string(payload.get("operator")),
            network_type=_clean_optional_string(payload.get("network_type")),
            signal_power=_optional_int(payload.get("signal_power")),
            download_mbps=float(payload["download_mbps"]),
            upload_mbps=float(payload["upload_mbps"]),
            latency_ms=_optional_float(payload.get("latency_ms")),
            timestamp=_parse_timestamp(payload.get("timestamp"), timezone_name),
        )
    except (TypeError, ValueError):
        return {"success": False, "error": "Speed test payload contains invalid numeric values"}, 400

    db.session.add(result)
    db.session.commit()
    return {"success": True, "message": "stored"}, 201


def _predict_signal_quality(latitude, longitude, operator, network_type, timestamp, timezone_name: str):
    training_rows = (
        CellData.query.filter(
            CellData.latitude.isnot(None),
            CellData.longitude.isnot(None),
            CellData.signal_power.isnot(None),
        )
        .order_by(CellData.timestamp.asc())
        .all()
    )
    if len(training_rows) < 10:
        return {"error": "Not enough geo-tagged samples to train a prediction model yet"}, 400

    target_time = _parse_timestamp(timestamp, timezone_name) if timestamp else datetime.now(timezone.utc)
    target_lat = float(latitude)
    target_lon = float(longitude)
    target_operator = operator.strip()
    target_network = network_type.strip().upper()

    scored_rows = []
    for row in training_rows:
        distance = _geo_distance_km(target_lat, target_lon, row.latitude, row.longitude)
        hour_gap = min(abs(target_time.hour - row.timestamp.hour), 24 - abs(target_time.hour - row.timestamp.hour))
        score = distance
        if row.operator != target_operator:
            score += 3
        if row.network_type != target_network:
            score += 2
        score += hour_gap * 0.1
        scored_rows.append((score, row))

    nearest_rows = [row for _score, row in sorted(scored_rows, key=lambda item: item[0])[:20]]
    prediction = float(mean([row.signal_power for row in nearest_rows]))
    deviation = mean([abs(row.signal_power - prediction) for row in nearest_rows]) if nearest_rows else 0.0
    confidence = max(0.05, min(0.99, 1 - (deviation / 40.0)))

    if prediction >= -85:
        quality = "strong"
    elif prediction >= -100:
        quality = "fair"
    else:
        quality = "weak"

    return {
        "predicted_signal_power": round(prediction, 2),
        "predicted_quality": quality,
        "confidence": round(confidence, 2),
        "training_sample_count": len(training_rows),
        "nearest_sample_count": len(nearest_rows),
    }


def _geo_distance_km(lat1, lon1, lat2, lon2) -> float:
    lat1_rad = math.radians(lat1)
    lon1_rad = math.radians(lon1)
    lat2_rad = math.radians(lat2)
    lon2_rad = math.radians(lon2)

    delta_lat = lat2_rad - lat1_rad
    delta_lon = lon2_rad - lon1_rad
    a = (
        math.sin(delta_lat / 2) ** 2
        + math.cos(lat1_rad) * math.cos(lat2_rad) * math.sin(delta_lon / 2) ** 2
    )
    return 6371 * (2 * math.atan2(math.sqrt(a), math.sqrt(1 - a)))


def _evaluate_alerts(cell_record: CellData) -> list[dict]:
    rules = AlertRule.query.filter_by(is_enabled=True).all()
    previous = (
        CellData.query.filter(
            CellData.device_id == cell_record.device_id,
            CellData.id != cell_record.id,
        )
        .order_by(CellData.timestamp.desc())
        .first()
    )
    alerts = []
    for rule in rules:
        if rule.device_id and rule.device_id != cell_record.device_id:
            continue
        if rule.min_signal_power is not None and cell_record.signal_power <= rule.min_signal_power:
            alerts.append(
                {
                    "type": "signal_threshold",
                    "device_id": cell_record.device_id,
                    "message": f"Signal dropped to {cell_record.signal_power} dBm",
                    "timestamp": cell_record.timestamp.isoformat(),
                }
            )
        if (
            rule.trigger_on_network_downgrade
            and previous is not None
            and NETWORK_RANK.get(cell_record.network_type, 0) < NETWORK_RANK.get(previous.network_type, 0)
        ):
            alerts.append(
                {
                    "type": "network_downgrade",
                    "device_id": cell_record.device_id,
                    "message": f"Network downgraded from {previous.network_type} to {cell_record.network_type}",
                    "timestamp": cell_record.timestamp.isoformat(),
                }
            )
    return alerts


def _build_pdf_report(rows: list[CellData]) -> bytes:
    stats = _build_stats_payload(rows)
    buffer = io.BytesIO()
    pdf = canvas.Canvas(buffer, pagesize=A4)
    width, height = A4
    y = height - 48

    def draw_line(text, step=18):
        nonlocal y
        pdf.drawString(40, y, text)
        y -= step
        if y < 60:
            pdf.showPage()
            y = height - 48

    pdf.setTitle("Network Cell Analyzer Report")
    pdf.setFont("Helvetica-Bold", 16)
    draw_line("Network Cell Analyzer Report", 24)
    pdf.setFont("Helvetica", 10)
    draw_line(f"Generated at: {datetime.now(timezone.utc).isoformat()}")
    draw_line(f"Records: {len(rows)}")
    draw_line("")
    draw_line("Connectivity per operator:")
    for key, value in stats["connectivity_per_operator"].items():
        draw_line(f"  {key}: {value}")
    draw_line("Connectivity per network type:")
    for key, value in stats["connectivity_per_network_type"].items():
        draw_line(f"  {key}: {value}")
    draw_line("Average signal per network type:")
    for key, value in stats["avg_signal_per_network_type"].items():
        draw_line(f"  {key}: {value} dBm")
    draw_line("Recent samples:")
    for row in rows[-15:]:
        draw_line(
            f"  {row.timestamp.isoformat()} | {row.device_id} | {row.operator} | "
            f"{row.network_type} | {row.signal_power} dBm | {row.cell_id}"
        )
    pdf.save()
    return buffer.getvalue()


def _run_sqlite_migrations() -> None:
    if db.engine.dialect.name != "sqlite":
        return

    inspector = inspect(db.engine)
    existing_columns = {column["name"] for column in inspector.get_columns("cell_data")}
    migrations = {
        "lac": "ALTER TABLE cell_data ADD COLUMN lac VARCHAR(32)",
        "mcc": "ALTER TABLE cell_data ADD COLUMN mcc VARCHAR(8)",
        "mnc": "ALTER TABLE cell_data ADD COLUMN mnc VARCHAR(8)",
        "sim_slot": "ALTER TABLE cell_data ADD COLUMN sim_slot INTEGER",
        "subscription_id": "ALTER TABLE cell_data ADD COLUMN subscription_id VARCHAR(64)",
        "latitude": "ALTER TABLE cell_data ADD COLUMN latitude FLOAT",
        "longitude": "ALTER TABLE cell_data ADD COLUMN longitude FLOAT",
        "location_accuracy_m": "ALTER TABLE cell_data ADD COLUMN location_accuracy_m FLOAT",
    }
    for column_name, statement in migrations.items():
        if column_name not in existing_columns:
            db.session.execute(text(statement))
    db.session.commit()


app = create_app()


if __name__ == "__main__":
    socketio.run(app, host="0.0.0.0", port=5000, debug=True)
