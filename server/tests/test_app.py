from config import TestConfig
from app import create_app
from datetime import datetime

from models import CellData, DeviceLog, User, db


def create_test_client():
    app = create_app(TestConfig)
    with app.app_context():
        db.create_all()
    return app.test_client(), app


def test_receive_data_and_stats_flow():
    client, app = create_test_client()

    payload = {
        "device_id": "phone-1",
        "operator": "Alfa",
        "signal_power": -85,
        "snr": 12.5,
        "network_type": "4G",
        "frequency_band": "Band 3 (1800MHz)",
        "cell_id": "37100-81937409",
        "lac": "42",
        "mcc": "415",
        "mnc": "03",
        "timestamp": "09 Mar 2026 02:30 PM",
        "mac_address": "AA:BB:CC:DD:EE:FF",
        "latitude": 33.8938,
        "longitude": 35.5018,
        "location_accuracy_m": 8.0,
    }

    response = client.post("/receive-data", json=payload)
    assert response.status_code == 201
    assert response.get_json()["success"] is True
    assert response.get_json()["message"] == "stored"

    stats_response = client.get("/get-stats", query_string={"device_id": "phone-1"})
    assert stats_response.status_code == 200
    body = stats_response.get_json()
    assert body["success"] is True
    assert body["avg_signal_device"] == -85
    assert body["avg_signal_per_device"]["phone-1"] == -85
    assert body["connectivity_per_operator"]["Alfa"] == "100.0%"
    assert body["operator_time"]["Alfa"] == 100.0

    avg_all_response = client.get("/get-stats/avg-all")
    assert avg_all_response.status_code == 200
    assert avg_all_response.get_json()["unique_devices"] == 1

    history_response = client.get("/api/history", query_string={"device_id": "phone-1"})
    assert history_response.status_code == 200
    assert history_response.get_json()["count"] == 1

    heatmap_response = client.get("/api/heatmap-data")
    assert heatmap_response.status_code == 200
    assert heatmap_response.get_json()["count"] == 1

    with app.app_context():
        assert CellData.query.count() == 1
        stored = CellData.query.first()
        assert stored.lac == "42"
        assert stored.mcc == "415"
        assert stored.mnc == "03"


def test_invalid_network_type_is_rejected():
    client, _app = create_test_client()

    response = client.post(
        "/receive-data",
        json={
            "device_id": "phone-1",
            "operator": "Alfa",
            "signal_power": -85,
            "network_type": "LTE",
            "cell_id": "cell-1",
        },
    )

    assert response.status_code == 400
    assert "network_type must be one of" in response.get_json()["error"]


def test_date_filtering_works():
    client, _app = create_test_client()

    payloads = [
        {
            "device_id": "phone-1",
            "operator": "Alfa",
            "signal_power": -80,
            "network_type": "4G",
            "cell_id": "cell-1",
            "timestamp": "09 Mar 2026 01:00 PM",
        },
        {
            "device_id": "phone-1",
            "operator": "Alfa",
            "signal_power": -100,
            "network_type": "3G",
            "cell_id": "cell-2",
            "timestamp": "09 Mar 2026 03:00 PM",
        },
    ]

    for payload in payloads:
        response = client.post("/receive-data", json=payload)
        assert response.status_code == 201

    stats_response = client.get(
        "/get-stats",
        query_string={
            "device_id": "phone-1",
            "start": "09 Mar 2026 12:00 PM",
            "end": "09 Mar 2026 02:00 PM",
        },
    )
    assert stats_response.status_code == 200
    body = stats_response.get_json()
    assert body["record_count"] == 1
    assert body["avg_signal_device"] == -80


def test_heatmap_requires_valid_coordinate_pair():
    client, _app = create_test_client()

    response = client.post(
        "/receive-data",
        json={
            "device_id": "phone-1",
            "operator": "Alfa",
            "signal_power": -85,
            "network_type": "4G",
            "cell_id": "cell-1",
            "latitude": 33.9,
        },
    )

    assert response.status_code == 400
    assert "latitude and longitude must be provided together" in response.get_json()["error"]


def test_batch_ingest_speed_test_and_alert_rules():
    client, _app = create_test_client()

    batch_response = client.post(
        "/receive-batch",
        json={
            "records": [
                {
                    "device_id": "phone-batch",
                    "operator": "Touch",
                    "signal_power": -79,
                    "network_type": "5G",
                    "cell_id": "cell-5g",
                    "sim_slot": 1,
                    "subscription_id": "sub-1",
                    "latitude": 33.90,
                    "longitude": 35.48,
                    "neighbor_cells": [
                        {
                            "network_type": "4G",
                            "cell_id": "neighbor-1",
                            "signal_power": -88,
                            "is_registered": False,
                        }
                    ],
                }
            ]
        },
    )
    assert batch_response.status_code == 201
    assert batch_response.get_json()["stored_count"] == 1

    neighbors_response = client.get("/api/neighbor-cells", query_string={"device_id": "phone-batch"})
    assert neighbors_response.status_code == 200
    assert neighbors_response.get_json()["neighbors"][0]["cell_id"] == "neighbor-1"

    upload_probe = client.post("/api/speed-test/upload", data=b"x" * 1024)
    assert upload_probe.status_code == 200
    assert upload_probe.get_json()["received_bytes"] == 1024

    save_speed = client.post(
        "/api/speed-test/result",
        json={
            "device_id": "phone-batch",
            "operator": "Touch",
            "network_type": "5G",
            "signal_power": -79,
            "download_mbps": 55.2,
            "upload_mbps": 12.4,
            "latency_ms": 22.0,
        },
    )
    assert save_speed.status_code == 201

    stats_response = client.get("/api/speed-test/stats", query_string={"device_id": "phone-batch"})
    assert stats_response.status_code == 200
    assert stats_response.get_json()["avg_download_mbps"] == 55.2

    alert_rule = client.post(
        "/api/alert-rules",
        json={"device_id": "phone-batch", "min_signal_power": -90, "trigger_on_network_downgrade": True},
    )
    assert alert_rule.status_code == 201
    listed_rules = client.get("/api/alert-rules", query_string={"device_id": "phone-batch"})
    assert listed_rules.status_code == 200
    assert len(listed_rules.get_json()["rules"]) == 1


def test_android_contract_aliases_and_auth_flow():
    client, app = create_test_client()

    register_response = client.post(
        "/auth/register",
        json={
            "name": "Test User",
            "email": "user@example.com",
            "password": "secret123",
            "device_id": "android-device-1",
        },
    )
    assert register_response.status_code == 200
    register_body = register_response.get_json()
    assert register_body["success"] is True
    assert register_body["token"]
    assert register_body["refresh_token"]

    login_response = client.post(
        "/auth/login",
        json={
            "email": "user@example.com",
            "password": "secret123",
            "device_id": "android-device-1",
        },
    )
    assert login_response.status_code == 200
    login_body = login_response.get_json()
    assert login_body["success"] is True

    refresh_response = client.post(
        "/auth/refresh",
        json={"refresh_token": login_body["refresh_token"]},
    )
    assert refresh_response.status_code == 200
    assert refresh_response.get_json()["success"] is True

    batch_response = client.post(
        "/receive-data/batch",
        json={
            "device_id": "android-device-1",
            "data": [
                {
                    "device_id": "android-device-1",
                    "operator": "Touch",
                    "signal_power": -88,
                    "snr": 11.2,
                    "network_type": "4G",
                    "frequency_band": "1800",
                    "cell_id": "12345",
                    "lac": "42",
                    "mcc": "415",
                    "mnc": "03",
                    "latitude": 33.8938,
                    "longitude": 35.5018,
                    "timestamp": "2026-03-10T14:12:00.000Z",
                    "sim_slot": 0,
                }
            ],
        },
    )
    assert batch_response.status_code == 201
    assert batch_response.get_json()["success"] is True

    from_ms = str(1_773_150_000_000)
    to_ms = str(1_773_157_200_000)
    stats_response = client.get(
        "/get-stats",
        query_string={"device_id": "android-device-1", "from": from_ms, "to": to_ms},
    )
    assert stats_response.status_code == 200
    stats_body = stats_response.get_json()
    assert stats_body["success"] is True
    assert "operator_time" in stats_body
    assert "network_type_time" in stats_body

    speed_test_response = client.post(
        "/speed-test",
        json={
            "downloadMbps": 45.2,
            "uploadMbps": 13.7,
            "latencyMs": 32,
            "timestamp": 1_773_151_800_000,
            "device_id": "android-device-1",
        },
    )
    assert speed_test_response.status_code == 201
    assert speed_test_response.get_json()["success"] is True

    profile_response = client.get("/device/profile", query_string={"device_id": "android-device-1"})
    assert profile_response.status_code == 200
    assert profile_response.get_json()["success"] is True

    with app.app_context():
        assert User.query.count() == 1


def test_prediction_and_pdf_report_routes():
    client, _app = create_test_client()

    records = []
    for index in range(10):
        records.append(
            {
                "device_id": f"predict-{index % 2}",
                "operator": "Alfa",
                "signal_power": -80 - index,
                "network_type": "4G",
                "cell_id": f"cell-{index}",
                "latitude": 33.89 + (index * 0.0001),
                "longitude": 35.50 + (index * 0.0001),
                "timestamp": f"09 Mar 2026 0{(index % 9) + 1}:00 PM",
            }
        )

    batch_response = client.post("/receive-batch", json={"records": records})
    assert batch_response.status_code == 201

    predict_response = client.get(
        "/predict",
        query_string={
            "latitude": 33.891,
            "longitude": 35.501,
            "operator": "Alfa",
            "network_type": "4G",
        },
    )
    assert predict_response.status_code == 200
    body = predict_response.get_json()
    assert "predicted_signal_power" in body
    assert "confidence" in body

    pdf_response = client.get("/api/report.pdf")
    assert pdf_response.status_code == 200
    assert pdf_response.headers["Content-Type"] == "application/pdf"


def test_dashboard_handles_naive_device_datetimes():
    client, app = create_test_client()

    with app.app_context():
        db.session.add(
            DeviceLog(
                device_id="naive-device",
                first_seen=datetime(2026, 3, 9, 12, 0, 0),
                last_seen=datetime(2026, 3, 9, 12, 5, 0),
                is_active=True,
            )
        )
        db.session.commit()

    dashboard_response = client.get("/central-stats")
    assert dashboard_response.status_code == 200

    heatmap_response = client.get("/heatmap")
    assert heatmap_response.status_code == 200
