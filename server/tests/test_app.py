from config import TestConfig
from app import create_app
from models import CellData, db


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
        "timestamp": "09 Mar 2026 02:30 PM",
        "mac_address": "AA:BB:CC:DD:EE:FF",
        "latitude": 33.8938,
        "longitude": 35.5018,
        "location_accuracy_m": 8.0,
    }

    response = client.post("/receive-data", json=payload)
    assert response.status_code == 201
    assert response.get_json()["message"] == "Data received"

    stats_response = client.get("/get-stats", query_string={"device_id": "phone-1"})
    assert stats_response.status_code == 200
    body = stats_response.get_json()
    assert body["avg_signal_device"] == -85
    assert body["avg_signal_per_device"]["phone-1"] == -85
    assert body["connectivity_per_operator"]["Alfa"] == "100.0%"

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
