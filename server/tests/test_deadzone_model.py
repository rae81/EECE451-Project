from pathlib import Path

import joblib
import pandas as pd
import pytest

from app import create_app
from config import TestConfig
from deadzone_model import predict_deadzone, train_deadzone_model
from models import db


def create_test_client():
    app = create_app(TestConfig)
    with app.app_context():
        db.create_all()
    return app.test_client(), app


def write_synthetic_training_inputs(tmp_path: Path) -> tuple[Path, Path, Path, Path]:
    opencellid_rows = []
    cluster_specs = [
        {"net": 1, "base_lon": 35.5000, "base_lat": 33.8900, "range": 450, "samples": 18, "signal": -82},
        {"net": 1, "base_lon": 35.6500, "base_lat": 34.0500, "range": 1250, "samples": 6, "signal": -107},
        {"net": 3, "base_lon": 35.5150, "base_lat": 33.9050, "range": 520, "samples": 17, "signal": -84},
        {"net": 3, "base_lon": 35.5400, "base_lat": 33.9300, "range": 1300, "samples": 4, "signal": -108},
    ]
    for spec in cluster_specs:
        for index in range(12):
            opencellid_rows.append(
                {
                    "radio": "LTE",
                    "mcc": 415,
                    "net": spec["net"],
                    "lon": spec["base_lon"] + (index * 0.0003),
                    "lat": spec["base_lat"] + (index * 0.0003),
                    "range": spec["range"],
                    "samples": spec["samples"] + (index % 4),
                    "updated": "2026-03-01T00:00:00Z",
                    "averageSignal": spec["signal"] - (index % 4),
                }
            )

    ookla_rows = [
        {
            "latitude": 33.8915,
            "longitude": 35.5015,
            "avg_d_kbps": 18500,
            "avg_u_kbps": 4200,
            "avg_lat_ms": 28,
            "tests": 120,
            "devices": 61,
        },
        {
            "latitude": 33.9065,
            "longitude": 35.5165,
            "avg_d_kbps": 15400,
            "avg_u_kbps": 3900,
            "avg_lat_ms": 30,
            "tests": 110,
            "devices": 59,
        },
        {
            "latitude": 34.0515,
            "longitude": 35.6515,
            "avg_d_kbps": 900,
            "avg_u_kbps": 180,
            "avg_lat_ms": 180,
            "tests": 34,
            "devices": 18,
        },
        {
            "latitude": 33.9315,
            "longitude": 35.5415,
            "avg_d_kbps": 650,
            "avg_u_kbps": 120,
            "avg_lat_ms": 210,
            "tests": 45,
            "devices": 24,
        },
    ]
    osm_rows = [
        {"latitude": 33.8910, "longitude": 35.5010, "feature_kind": "telecom"},
        {"latitude": 33.8918, "longitude": 35.5018, "feature_kind": "telecom"},
        {"latitude": 33.8924, "longitude": 35.5022, "feature_kind": "telecom"},
        {"latitude": 33.9055, "longitude": 35.5155, "feature_kind": "telecom"},
        {"latitude": 33.9062, "longitude": 35.5162, "feature_kind": "telecom"},
        {"latitude": 33.9500, "longitude": 35.5800, "feature_kind": "telecom"},
    ]
    dem_rows = [
        {"latitude": 33.8900, "longitude": 35.5000, "elevation_m": 90},
        {"latitude": 33.8910, "longitude": 35.5010, "elevation_m": 93},
        {"latitude": 33.8920, "longitude": 35.5020, "elevation_m": 95},
        {"latitude": 33.8930, "longitude": 35.5030, "elevation_m": 94},
        {"latitude": 33.8940, "longitude": 35.5040, "elevation_m": 96},
        {"latitude": 33.8950, "longitude": 35.5050, "elevation_m": 98},
        {"latitude": 33.9050, "longitude": 35.5150, "elevation_m": 102},
        {"latitude": 33.9060, "longitude": 35.5160, "elevation_m": 104},
        {"latitude": 33.9070, "longitude": 35.5170, "elevation_m": 106},
        {"latitude": 33.9080, "longitude": 35.5180, "elevation_m": 108},
        {"latitude": 33.9090, "longitude": 35.5190, "elevation_m": 109},
        {"latitude": 33.9100, "longitude": 35.5200, "elevation_m": 111},
        {"latitude": 34.0500, "longitude": 35.6500, "elevation_m": 180},
        {"latitude": 34.0510, "longitude": 35.6510, "elevation_m": 240},
        {"latitude": 34.0520, "longitude": 35.6520, "elevation_m": 310},
        {"latitude": 34.0530, "longitude": 35.6530, "elevation_m": 280},
        {"latitude": 34.0540, "longitude": 35.6540, "elevation_m": 360},
        {"latitude": 34.0550, "longitude": 35.6550, "elevation_m": 220},
        {"latitude": 33.9300, "longitude": 35.5400, "elevation_m": 210},
        {"latitude": 33.9310, "longitude": 35.5410, "elevation_m": 260},
        {"latitude": 33.9320, "longitude": 35.5420, "elevation_m": 330},
        {"latitude": 33.9330, "longitude": 35.5430, "elevation_m": 290},
        {"latitude": 33.9340, "longitude": 35.5440, "elevation_m": 360},
        {"latitude": 33.9350, "longitude": 35.5450, "elevation_m": 240},
    ]

    opencellid_path = tmp_path / "opencellid_lebanon.csv"
    ookla_path = tmp_path / "ookla_lebanon.csv"
    osm_path = tmp_path / "osm_context.csv"
    dem_path = tmp_path / "dem_grid.csv"
    pd.DataFrame(opencellid_rows).to_csv(opencellid_path, index=False)
    pd.DataFrame(ookla_rows).to_csv(ookla_path, index=False)
    pd.DataFrame(osm_rows).to_csv(osm_path, index=False)
    pd.DataFrame(dem_rows).to_csv(dem_path, index=False)
    return opencellid_path, ookla_path, osm_path, dem_path


def test_v2_training_pipeline_and_runtime_prediction(tmp_path):
    """Legacy v2 RandomForest training and prediction."""
    opencellid_path, ookla_path, osm_path, dem_path = write_synthetic_training_inputs(tmp_path)
    model_path = tmp_path / "deadzone_model.joblib"
    report_dir = tmp_path / "reports"
    dataset_path = tmp_path / "prepared.csv"

    result = train_deadzone_model(
        opencellid_path,
        model_path,
        ookla_path=ookla_path,
        osm_path=osm_path,
        dem_path=dem_path,
        specialize_groups=True,
        specialize_network_type="4G",
        group_min_rows=8,
        output_dataset_path=dataset_path,
        report_dir=report_dir,
        use_v3=False,
    )

    assert model_path.exists()
    assert dataset_path.exists()
    assert (report_dir / "summary.json").exists()
    assert (report_dir / "eda_overview.png").exists()
    assert result["row_count"] >= 20
    bundle = joblib.load(model_path)
    assert "Alfa::4G" in bundle["group_models"]
    assert "Touch::4G" in bundle["group_models"]
    assert bundle["metadata"]["group_model_count"] >= 2

    good_prediction = predict_deadzone(
        model_path,
        latitude=33.8912,
        longitude=35.5012,
        operator="Alfa",
        network_type="4G",
    )
    bad_prediction = predict_deadzone(
        model_path,
        latitude=33.9312,
        longitude=35.5412,
        operator="Touch",
        network_type="4G",
    )

    assert good_prediction is not None
    assert bad_prediction is not None
    assert good_prediction["deadzone_risk"] < bad_prediction["deadzone_risk"]
    assert bad_prediction["deadzone_label"] in {"moderate", "high"}


def test_v3_training_pipeline_and_prediction(tmp_path):
    """v3 dual LightGBM training and prediction."""
    pytest.importorskip("lightgbm")
    opencellid_path, ookla_path, osm_path, dem_path = write_synthetic_training_inputs(tmp_path)
    model_path = tmp_path / "deadzone_model_v3.joblib"
    report_dir = tmp_path / "reports_v3"

    result = train_deadzone_model(
        opencellid_path,
        model_path,
        ookla_path=ookla_path,
        osm_path=osm_path,
        dem_path=dem_path,
        report_dir=report_dir,
        use_v3=True,
    )

    assert model_path.exists()
    assert result["model_version"] == 3
    assert result["row_count"] >= 5  # H3 dedup reduces rows significantly

    bundle = joblib.load(model_path)
    assert bundle["model_version"] == 3
    assert bundle["regressor"] is not None

    # Predict with v3 model
    pred = predict_deadzone(
        model_path,
        latitude=33.8912,
        longitude=35.5012,
        operator="Alfa",
        network_type="4G",
    )
    assert pred is not None
    assert pred["model_source"] == "deadzone-model-v3"
    assert "deadzone_risk" in pred
    assert "predicted_signal_power" in pred
    assert "reasons" in pred
    assert 0.0 <= pred["deadzone_risk"] <= 1.0


def test_flask_predict_and_heatmap_include_deadzone_scores(tmp_path):
    opencellid_path, ookla_path, osm_path, dem_path = write_synthetic_training_inputs(tmp_path)
    model_path = tmp_path / "deadzone_model.joblib"
    train_deadzone_model(
        opencellid_path,
        model_path,
        ookla_path=ookla_path,
        osm_path=osm_path,
        dem_path=dem_path,
        specialize_groups=True,
        specialize_network_type="4G",
        group_min_rows=8,
        use_v3=False,
    )

    client, app = create_test_client()
    app.config["DEADZONE_MODEL_PATH"] = str(model_path)

    response = client.post(
        "/api/cell/ingest",
        json={
            "device_id": "phone-1",
            "operator": "Alfa",
            "signal_power": -84,
            "network_type": "4G",
            "cell_id": "cell-1",
            "latitude": 33.8912,
            "longitude": 35.5012,
            "timestamp": "2026-03-09T13:00:00Z",
        },
    )
    assert response.status_code == 201

    predict_response = client.get(
        "/predict",
        query_string={
            "latitude": 33.9312,
            "longitude": 35.5412,
            "operator": "Touch",
            "network_type": "4G",
        },
    )
    assert predict_response.status_code == 200
    body = predict_response.get_json()
    assert body["model_source"] == "deadzone-model"
    assert "deadzone_risk" in body
    assert "reasons" in body

    heatmap_response = client.get("/api/heatmap-data", query_string={"device_id": "phone-1"})
    assert heatmap_response.status_code == 200
    heatmap_body = heatmap_response.get_json()
    assert heatmap_body["points"]
    assert "deadzone_risk" in heatmap_body["points"][0]
