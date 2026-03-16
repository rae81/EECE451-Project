from __future__ import annotations

import argparse
import gzip
import json
import math
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

import joblib
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.metrics import accuracy_score, average_precision_score, precision_recall_fscore_support, roc_auc_score
from sklearn.model_selection import GroupShuffleSplit, train_test_split
from sklearn.neighbors import BallTree
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder


EARTH_RADIUS_KM = 6371.0088
DEFAULT_LEBANON_BBOX = (33.05, 35.09, 34.72, 36.68)
MODEL_VERSION = 2

NUMERIC_FEATURES = [
    "latitude",
    "longitude",
    "avg_signal",
    "range_m",
    "samples",
    "days_since_update",
    "same_group_density_1km",
    "same_group_nearest_distance_km",
    "same_group_mean_signal_5",
    "same_group_mean_range_5",
    "ookla_avg_down_kbps",
    "ookla_avg_up_kbps",
    "ookla_avg_latency_ms",
    "ookla_tests",
    "ookla_devices",
    "ookla_distance_km",
    "osm_telecom_density_1km",
    "osm_telecom_nearest_distance_km",
    "terrain_elevation_m",
    "terrain_relief_3km_m",
]

CATEGORICAL_FEATURES = ["operator", "network_type"]

LEBANON_OPERATOR_MAP = {
    ("415", "01"): "Alfa",
    ("415", "03"): "Touch",
}

NETWORK_ALIASES = {
    "GSM": "2G",
    "GPRS": "2G",
    "EDGE": "2G",
    "CDMA": "2G",
    "UMTS": "3G",
    "HSPA": "3G",
    "HSPA+": "3G",
    "WCDMA": "3G",
    "TD-SCDMA": "3G",
    "LTE": "4G",
    "LTE-A": "4G",
    "LTE+": "4G",
    "NR": "5G",
    "NRNSA": "5G",
    "NR-SA": "5G",
}

OPERATOR_ALIASES = {
    "ALFA": "Alfa",
    "ALFA MIC1": "Alfa",
    "MIC1": "Alfa",
    "TOUCH": "Touch",
    "MIC2": "Touch",
}

_RUNTIME_CACHE: dict[str, object] = {"path": None, "mtime": None, "runtime": None}


def group_model_key(operator: str, network_type: str) -> str:
    return f"{operator}::{network_type}"


@dataclass
class DeadzonePrediction:
    predicted_signal_power: float
    predicted_quality: str
    deadzone_risk: float
    deadzone_label: str
    confidence: float
    training_sample_count: int
    nearest_sample_count: int
    reasons: list[str]
    model_source: str = "deadzone-model"
    model_variant: str = "global"

    def to_dict(self) -> dict:
        return {
            "predicted_signal_power": round(self.predicted_signal_power, 2),
            "predicted_quality": self.predicted_quality,
            "deadzone_risk": round(self.deadzone_risk, 4),
            "deadzone_label": self.deadzone_label,
            "confidence": round(self.confidence, 4),
            "training_sample_count": self.training_sample_count,
            "nearest_sample_count": self.nearest_sample_count,
            "reasons": self.reasons,
            "model_source": self.model_source,
            "model_variant": self.model_variant,
        }


class DeadzoneRuntime:
    def __init__(self, bundle: dict):
        self.bundle = bundle
        self.model = bundle["model"]
        self.metadata = bundle["metadata"]
        self.reference_cells = bundle["reference_cells"].copy()
        self.ookla_tiles = bundle.get("ookla_tiles")
        self.osm_context = bundle.get("osm_context")
        self.dem_grid = bundle.get("dem_grid")
        self.group_trees = self._build_group_trees(self.reference_cells)
        self.specialized_variants: dict[str, dict] = {}
        for key, sub_bundle in bundle.get("group_models", {}).items():
            reference_cells = sub_bundle["reference_cells"].copy()
            self.specialized_variants[key] = {
                "model": sub_bundle["model"],
                "metadata": sub_bundle["metadata"],
                "reference_cells": reference_cells,
                "group_trees": self._build_group_trees(reference_cells),
            }

        self.ookla_tree = self._build_tree(self.ookla_tiles)
        self.telecom_context = None
        self.telecom_tree = None
        if self.osm_context is not None and not self.osm_context.empty:
            telecom = self.osm_context[self.osm_context["feature_kind"] == "telecom"].reset_index(drop=True)
            if not telecom.empty:
                self.telecom_context = telecom
                self.telecom_tree = self._build_tree(telecom)
        self.dem_tree = self._build_tree(self.dem_grid)

    @staticmethod
    def _build_tree(frame: pd.DataFrame | None) -> BallTree | None:
        if frame is None or frame.empty:
            return None
        coords = np.radians(frame[["latitude", "longitude"]].to_numpy(dtype=float))
        if len(coords) == 0:
            return None
        return BallTree(coords, metric="haversine")

    @staticmethod
    def _build_group_trees(reference_cells: pd.DataFrame) -> dict[tuple[str, str], tuple[pd.DataFrame, BallTree]]:
        group_trees: dict[tuple[str, str], tuple[pd.DataFrame, BallTree]] = {}
        for (operator, network_type), group in reference_cells.groupby(["operator", "network_type"], dropna=False):
            clean_group = group.reset_index(drop=True)
            tree = DeadzoneRuntime._build_tree(clean_group)
            if tree is None:
                continue
            group_trees[(str(operator), str(network_type))] = (clean_group, tree)
        return group_trees

    def predict(self, latitude: float, longitude: float, operator: str, network_type: str) -> dict | None:
        normalized_operator = normalize_operator_name(operator)
        normalized_network = normalize_network_type(network_type)
        variant = self.specialized_variants.get(group_model_key(normalized_operator, normalized_network))
        model = variant["model"] if variant else self.model
        metadata = variant["metadata"] if variant else self.metadata
        group_trees = variant["group_trees"] if variant else self.group_trees
        variant_name = group_model_key(normalized_operator, normalized_network) if variant else "global"

        feature_row, support_count, predicted_signal = self._build_feature_row(
            latitude,
            longitude,
            normalized_operator,
            normalized_network,
            group_trees=group_trees,
        )
        if feature_row is None:
            return None

        frame = pd.DataFrame([feature_row], columns=NUMERIC_FEATURES + CATEGORICAL_FEATURES)
        probabilities = model.predict_proba(frame)[0]
        classes = getattr(model, "classes_", None)
        if classes is None:
            classes = model.named_steps["classifier"].classes_
        if len(classes) == 1:
            risk = float(probabilities[0]) if classes[0] == 1 else 0.0
        else:
            positive_class_index = list(classes).index(1)
            risk = float(probabilities[positive_class_index])
        reasons = build_prediction_reasons(feature_row, predicted_signal, risk)
        confidence = max(risk, 1.0 - risk)
        label = deadzone_label_for_probability(risk)
        quality = quality_for_signal(predicted_signal)

        return DeadzonePrediction(
            predicted_signal_power=predicted_signal,
            predicted_quality=quality,
            deadzone_risk=risk,
            deadzone_label=label,
            confidence=confidence,
            training_sample_count=int(metadata.get("training_row_count", 0)),
            nearest_sample_count=support_count,
            reasons=reasons,
            model_variant=variant_name,
        ).to_dict()

    def _build_feature_row(
        self,
        latitude: float,
        longitude: float,
        operator: str,
        network_type: str,
        *,
        group_trees: dict[tuple[str, str], tuple[pd.DataFrame, BallTree]],
    ) -> tuple[dict | None, int, float]:
        group = group_trees.get((operator, network_type))

        if group is None:
            fallback_keys = [
                key
                for key in group_trees
                if key[1] == network_type or key[0] == operator
            ]
            if not fallback_keys:
                return None, 0, float("nan")
            merged = pd.concat([group_trees[key][0] for key in fallback_keys], ignore_index=True)
            coords = np.radians(merged[["latitude", "longitude"]].to_numpy(dtype=float))
            tree = BallTree(coords, metric="haversine")
            group = (merged, tree)

        reference_rows, tree = group
        if reference_rows.empty:
            return None, 0, float("nan")

        query = np.radians([[latitude, longitude]])
        neighbor_count = min(6, len(reference_rows))
        distances, indices = tree.query(query, k=neighbor_count)
        distances_km = distances[0] * EARTH_RADIUS_KM
        indices = indices[0]
        nearest_rows = reference_rows.iloc[indices].copy()

        weights = 1.0 / np.maximum(distances_km, 0.05)
        predicted_signal = weighted_average(nearest_rows["avg_signal"], weights)
        feature_row = {
            "latitude": latitude,
            "longitude": longitude,
            "operator": operator,
            "network_type": network_type,
            "avg_signal": predicted_signal,
            "range_m": weighted_average(nearest_rows["range_m"], weights),
            "samples": weighted_average(nearest_rows["samples"], weights),
            "days_since_update": weighted_average(nearest_rows["days_since_update"], weights),
            "same_group_density_1km": float(np.sum(distances_km <= 1.0)),
            "same_group_nearest_distance_km": float(distances_km[0]) if len(distances_km) else 99.0,
            "same_group_mean_signal_5": float(nearest_rows["avg_signal"].head(5).mean()),
            "same_group_mean_range_5": float(nearest_rows["range_m"].head(5).mean()),
            "ookla_avg_down_kbps": np.nan,
            "ookla_avg_up_kbps": np.nan,
            "ookla_avg_latency_ms": np.nan,
            "ookla_tests": 0.0,
            "ookla_devices": 0.0,
            "ookla_distance_km": np.nan,
            "osm_telecom_density_1km": 0.0,
            "osm_telecom_nearest_distance_km": np.nan,
            "terrain_elevation_m": np.nan,
            "terrain_relief_3km_m": np.nan,
        }

        if self.ookla_tree is not None:
            ookla_distances, ookla_indices = self.ookla_tree.query(query, k=1)
            distance_km = float(ookla_distances[0][0] * EARTH_RADIUS_KM)
            tile = self.ookla_tiles.iloc[int(ookla_indices[0][0])]
            feature_row.update(
                {
                    "ookla_avg_down_kbps": numeric_or_nan(tile.get("avg_d_kbps")),
                    "ookla_avg_up_kbps": numeric_or_nan(tile.get("avg_u_kbps")),
                    "ookla_avg_latency_ms": numeric_or_nan(tile.get("avg_lat_ms")),
                    "ookla_tests": numeric_or_nan(tile.get("tests"), default=0.0),
                    "ookla_devices": numeric_or_nan(tile.get("devices"), default=0.0),
                    "ookla_distance_km": distance_km,
                }
            )

        if self.telecom_tree is not None and self.telecom_context is not None:
            telecom_distances, telecom_indices = self.telecom_tree.query(query, k=1)
            nearest_distance_km = float(telecom_distances[0][0] * EARTH_RADIUS_KM)
            density = self.telecom_tree.query_radius(query, r=1.0 / EARTH_RADIUS_KM, count_only=True)[0]
            feature_row["osm_telecom_density_1km"] = float(density)
            feature_row["osm_telecom_nearest_distance_km"] = nearest_distance_km

        if self.dem_tree is not None and self.dem_grid is not None:
            neighbor_count = min(9, len(self.dem_grid))
            dem_distances, dem_indices = self.dem_tree.query(query, k=neighbor_count)
            nearest_dem = self.dem_grid.iloc[dem_indices[0]].copy()
            dem_weights = 1.0 / np.maximum(dem_distances[0] * EARTH_RADIUS_KM, 0.05)
            elevations = pd.to_numeric(nearest_dem["elevation_m"], errors="coerce")
            feature_row["terrain_elevation_m"] = weighted_average(elevations, dem_weights)
            if elevations.notna().any():
                feature_row["terrain_relief_3km_m"] = float(elevations.max() - elevations.min())

        return feature_row, int(len(nearest_rows)), float(predicted_signal)


def normalize_operator_name(value: str | None, mcc: str | None = None, mnc: str | None = None) -> str:
    mapped = LEBANON_OPERATOR_MAP.get((clean_code(mcc), clean_code(mnc)))
    if mapped:
        return mapped
    text = (value or "").strip()
    if not text:
        return "Unknown"
    key = text.upper()
    return OPERATOR_ALIASES.get(key, text.title())


def normalize_network_type(value: str | None) -> str:
    if value is None:
        return "Unknown"
    text = str(value).strip().upper()
    if text in {"2G", "3G", "4G", "5G"}:
        return text
    return NETWORK_ALIASES.get(text, text)


def clean_code(value: str | int | float | None) -> str | None:
    if value in (None, "", "nan"):
        return None
    text = str(value).strip()
    if not text:
        return None
    if text.endswith(".0"):
        text = text[:-2]
    return text.zfill(2) if text.isdigit() and len(text) <= 2 else text


def numeric_or_nan(value, default=np.nan):
    try:
        if value in (None, "", "nan"):
            return default
        return float(value)
    except (TypeError, ValueError):
        return default


def weighted_average(series: Iterable, weights: np.ndarray) -> float:
    values = pd.to_numeric(pd.Series(list(series)), errors="coerce").to_numpy(dtype=float)
    valid = ~np.isnan(values)
    if not valid.any():
        return float("nan")
    return float(np.average(values[valid], weights=weights[valid]))


def quality_for_signal(signal_power: float) -> str:
    if signal_power >= -85:
        return "strong"
    if signal_power >= -100:
        return "fair"
    return "weak"


def deadzone_label_for_probability(probability: float) -> str:
    if probability >= 0.7:
        return "high"
    if probability >= 0.45:
        return "moderate"
    return "low"


def build_prediction_reasons(feature_row: dict, predicted_signal: float, risk: float) -> list[str]:
    reasons = []
    if predicted_signal <= -105:
        reasons.append("predicted signal is below -105 dBm")
    elif predicted_signal <= -98:
        reasons.append("predicted signal is weak for stable coverage")

    nearest_distance = feature_row.get("same_group_nearest_distance_km")
    if nearest_distance is not None and nearest_distance >= 1.5:
        reasons.append("nearest matching cell is relatively far away")

    density = feature_row.get("same_group_density_1km")
    if density is not None and density <= 1:
        reasons.append("nearby same-operator cell density is sparse")

    telecom_density = feature_row.get("osm_telecom_density_1km")
    if telecom_density is not None and telecom_density <= 1:
        reasons.append("few mapped telecom structures are nearby")

    terrain_relief = feature_row.get("terrain_relief_3km_m")
    if terrain_relief is not None and not math.isnan(terrain_relief) and terrain_relief >= 180:
        reasons.append("local terrain relief suggests harder radio propagation")

    latency = feature_row.get("ookla_avg_latency_ms")
    if latency is not None and not math.isnan(latency) and latency >= 120:
        reasons.append("nearby open speed-test tiles show high latency")

    down_kbps = feature_row.get("ookla_avg_down_kbps")
    if down_kbps is not None and not math.isnan(down_kbps) and down_kbps <= 1500:
        reasons.append("nearby open speed-test tiles show low download throughput")

    if not reasons:
        reasons.append("nearby reference cells and performance tiles look usable")
    if risk >= 0.8 and len(reasons) == 1:
        reasons.append("model confidence is high because multiple weak indicators align")
    return reasons[:3]


def load_opencellid_reference(path: str | Path, bbox: tuple[float, float, float, float] | None = None) -> pd.DataFrame:
    path = Path(path)
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as handle:
        first_line = handle.readline().strip()
    has_header = "lat" in first_line.lower() and "lon" in first_line.lower()
    if has_header:
        frame = pd.read_csv(path)
    else:
        frame = pd.read_csv(
            path,
            header=None,
            names=[
                "radio",
                "mcc",
                "net",
                "area",
                "cell",
                "unit",
                "lon",
                "lat",
                "range",
                "samples",
                "changeable",
                "created",
                "updated",
                "averageSignal",
            ],
        )
    rename_map = {column: column.strip().lower() for column in frame.columns}
    frame = frame.rename(columns=rename_map)

    if "lon" in frame.columns and "longitude" not in frame.columns:
        frame["longitude"] = frame["lon"]
    if "lat" in frame.columns and "latitude" not in frame.columns:
        frame["latitude"] = frame["lat"]
    if "net" in frame.columns and "mnc" not in frame.columns:
        frame["mnc"] = frame["net"]
    if "averagesignal" in frame.columns and "avg_signal" not in frame.columns:
        frame["avg_signal"] = frame["averagesignal"]
    if "range" in frame.columns and "range_m" not in frame.columns:
        frame["range_m"] = frame["range"]

    required = {"latitude", "longitude"}
    missing = required.difference(frame.columns)
    if missing:
        raise ValueError(f"OpenCelliD file is missing required columns: {', '.join(sorted(missing))}")

    frame["latitude"] = pd.to_numeric(frame["latitude"], errors="coerce")
    frame["longitude"] = pd.to_numeric(frame["longitude"], errors="coerce")
    frame["samples"] = pd.to_numeric(frame.get("samples"), errors="coerce").fillna(0.0)
    frame["range_m"] = pd.to_numeric(frame.get("range_m"), errors="coerce").fillna(np.nan)
    frame["avg_signal"] = pd.to_numeric(frame.get("avg_signal"), errors="coerce").fillna(np.nan)
    frame.loc[frame["avg_signal"] >= 0, "avg_signal"] = np.nan
    frame["mcc"] = frame.get("mcc").map(clean_code) if "mcc" in frame.columns else None
    frame["mnc"] = frame.get("mnc").map(clean_code) if "mnc" in frame.columns else None
    frame["operator"] = [
        normalize_operator_name(
            row.get("operator"),
            row.get("mcc"),
            row.get("mnc"),
        )
        for row in frame.to_dict(orient="records")
    ]
    network_source = frame["radio"] if "radio" in frame.columns else frame.get("network_type")
    if network_source is None:
        frame["network_type"] = "Unknown"
    else:
        frame["network_type"] = network_source.map(normalize_network_type)
    frame["updated_at"] = parse_datetime_series(frame.get("updated"))
    frame["days_since_update"] = days_since_now(frame["updated_at"])

    frame = frame.dropna(subset=["latitude", "longitude"]).copy()
    frame = frame[(frame["latitude"].between(-90, 90)) & (frame["longitude"].between(-180, 180))]
    if bbox is not None:
        frame = filter_bbox(frame, bbox)

    selected = frame[
        [
            "latitude",
            "longitude",
            "operator",
            "network_type",
            "avg_signal",
            "range_m",
            "samples",
            "days_since_update",
            "mcc",
            "mnc",
        ]
    ].copy()
    selected["avg_signal"] = selected["avg_signal"].fillna(selected["avg_signal"].median())
    selected["range_m"] = selected["range_m"].fillna(selected["range_m"].median())
    selected["days_since_update"] = selected["days_since_update"].fillna(selected["days_since_update"].median())
    return selected.reset_index(drop=True)


def load_ookla_tiles(path: str | Path, bbox: tuple[float, float, float, float] | None = None) -> pd.DataFrame:
    raw_path = Path(path)
    suffix = raw_path.suffix.lower()
    if suffix == ".parquet":
        frame = pd.read_parquet(raw_path)
    elif suffix in {".csv", ".txt"}:
        frame = pd.read_csv(raw_path)
    elif suffix in {".json", ".geojson"}:
        frame = pd.read_json(raw_path)
    else:
        raise ValueError("Unsupported Ookla input format. Use parquet, csv, json, or geojson.")

    rename_map = {column: column.strip().lower() for column in frame.columns}
    frame = frame.rename(columns=rename_map)
    latitude, longitude = extract_ookla_coordinates(frame)
    frame["latitude"] = latitude
    frame["longitude"] = longitude

    metric_aliases = {
        "avg_d_kbps": ["avg_d_kbps", "avg_download_kbps", "download_kbps", "avg_download"],
        "avg_u_kbps": ["avg_u_kbps", "avg_upload_kbps", "upload_kbps", "avg_upload"],
        "avg_lat_ms": ["avg_lat_ms", "avg_latency_ms", "latency_ms", "avg_latency"],
        "tests": ["tests", "test_count"],
        "devices": ["devices", "device_count"],
    }
    normalized = pd.DataFrame({"latitude": frame["latitude"], "longitude": frame["longitude"]})
    for output_name, candidates in metric_aliases.items():
        normalized[output_name] = select_first_numeric_column(frame, candidates)

    normalized = normalized.dropna(subset=["latitude", "longitude"]).copy()
    if bbox is not None:
        normalized = filter_bbox(normalized, bbox)
    return normalized.reset_index(drop=True)


def load_osm_context(path: str | Path, bbox: tuple[float, float, float, float] | None = None) -> pd.DataFrame:
    raw_path = Path(path)
    suffix = raw_path.suffix.lower()
    if suffix in {".json", ".geojson"}:
        payload = json.loads(raw_path.read_text(encoding="utf-8"))
        records = []
        if isinstance(payload, dict) and "elements" in payload:
            for element in payload["elements"]:
                latitude = element.get("lat") or ((element.get("center") or {}).get("lat"))
                longitude = element.get("lon") or ((element.get("center") or {}).get("lon"))
                if latitude is None or longitude is None:
                    continue
                records.append(
                    {
                        "latitude": latitude,
                        "longitude": longitude,
                        "feature_kind": classify_osm_feature(element.get("tags") or {}),
                    }
                )
        elif isinstance(payload, dict) and "features" in payload:
            for feature in payload["features"]:
                geometry = feature.get("geometry") or {}
                centroid = geometry_centroid(geometry)
                if centroid is None:
                    continue
                records.append(
                    {
                        "latitude": centroid[0],
                        "longitude": centroid[1],
                        "feature_kind": classify_osm_feature(feature.get("properties") or {}),
                    }
                )
        else:
            frame = pd.read_json(raw_path)
            return normalize_osm_frame(frame, bbox=bbox)
        frame = pd.DataFrame.from_records(records)
        if frame.empty:
            return pd.DataFrame(columns=["latitude", "longitude", "feature_kind"])
        return normalize_osm_frame(frame, bbox=bbox)

    if suffix in {".csv", ".txt", ".parquet"}:
        frame = pd.read_parquet(raw_path) if suffix == ".parquet" else pd.read_csv(raw_path)
        return normalize_osm_frame(frame, bbox=bbox)

    raise ValueError("Unsupported OSM context format. Use csv, parquet, json, or geojson.")


def normalize_osm_frame(frame: pd.DataFrame, bbox: tuple[float, float, float, float] | None = None) -> pd.DataFrame:
    rename_map = {column: column.strip().lower() for column in frame.columns}
    frame = frame.rename(columns=rename_map)
    latitude, longitude = extract_context_coordinates(frame)
    normalized = pd.DataFrame({"latitude": latitude, "longitude": longitude})
    if "feature_kind" in frame.columns:
        normalized["feature_kind"] = frame["feature_kind"].fillna("other").astype(str).str.lower()
    else:
        normalized["feature_kind"] = [
            classify_osm_feature(row)
            for row in frame.fillna("").to_dict(orient="records")
        ]
    normalized = normalized.dropna(subset=["latitude", "longitude"]).copy()
    if bbox is not None:
        normalized = filter_bbox(normalized, bbox)
    return normalized.reset_index(drop=True)


def load_dem_grid(path: str | Path, bbox: tuple[float, float, float, float] | None = None) -> pd.DataFrame:
    raw_path = Path(path)
    suffix = raw_path.suffix.lower()
    if suffix == ".parquet":
        frame = pd.read_parquet(raw_path)
    elif suffix in {".csv", ".txt"}:
        frame = pd.read_csv(raw_path)
    elif suffix in {".json", ".geojson"}:
        payload = json.loads(raw_path.read_text(encoding="utf-8"))
        if isinstance(payload, dict) and "features" in payload:
            records = []
            for feature in payload["features"]:
                centroid = geometry_centroid(feature.get("geometry") or {})
                if centroid is None:
                    continue
                properties = feature.get("properties") or {}
                records.append(
                    {
                        "latitude": centroid[0],
                        "longitude": centroid[1],
                        "elevation_m": first_matching_numeric(
                            properties,
                            ["elevation_m", "elevation", "elev", "dem", "z"],
                        ),
                    }
                )
            frame = pd.DataFrame.from_records(records)
            if frame.empty:
                return pd.DataFrame(columns=["latitude", "longitude", "elevation_m"])
        else:
            frame = pd.read_json(raw_path)
    else:
        raise ValueError("Unsupported DEM input format. Use parquet, csv, json, or geojson.")

    rename_map = {column: column.strip().lower() for column in frame.columns}
    frame = frame.rename(columns=rename_map)
    latitude, longitude = extract_context_coordinates(frame)
    normalized = pd.DataFrame({"latitude": latitude, "longitude": longitude})
    normalized["elevation_m"] = select_first_numeric_column(frame, ["elevation_m", "elevation", "elev", "dem", "z"])
    normalized = normalized.dropna(subset=["latitude", "longitude", "elevation_m"]).copy()
    if bbox is not None:
        normalized = filter_bbox(normalized, bbox)
    return normalized.reset_index(drop=True)


def extract_context_coordinates(frame: pd.DataFrame) -> tuple[pd.Series, pd.Series]:
    for lat_column, lon_column in (("latitude", "longitude"), ("lat", "lon")):
        if lat_column in frame.columns and lon_column in frame.columns:
            return (
                pd.to_numeric(frame[lat_column], errors="coerce"),
                pd.to_numeric(frame[lon_column], errors="coerce"),
            )

    geometry_column = next((column for column in ("geometry", "geom", "wkt") if column in frame.columns), None)
    if geometry_column:
        coordinates = frame[geometry_column].map(
            lambda value: parse_wkt_centroid(value) if isinstance(value, str) else geometry_centroid(value)
        )
        return (
            coordinates.map(lambda value: value[0] if value else np.nan),
            coordinates.map(lambda value: value[1] if value else np.nan),
        )

    raise ValueError("Context file is missing coordinates. Expected latitude/longitude, lat/lon, or geometry.")


def geometry_centroid(geometry: dict | None) -> tuple[float, float] | None:
    if not geometry:
        return None
    geometry_type = str(geometry.get("type") or "").lower()
    coordinates = geometry.get("coordinates")
    if coordinates is None:
        return None
    flattened = flatten_coordinate_pairs(coordinates)
    if not flattened:
        return None
    longitudes = [pair[0] for pair in flattened]
    latitudes = [pair[1] for pair in flattened]
    return (sum(latitudes) / len(latitudes), sum(longitudes) / len(longitudes))


def flatten_coordinate_pairs(value) -> list[tuple[float, float]]:
    if value is None:
        return []
    if isinstance(value, (list, tuple)) and len(value) >= 2 and all(isinstance(item, (int, float)) for item in value[:2]):
        return [(float(value[0]), float(value[1]))]
    pairs: list[tuple[float, float]] = []
    if isinstance(value, (list, tuple)):
        for item in value:
            pairs.extend(flatten_coordinate_pairs(item))
    return pairs


def classify_osm_feature(tags: dict) -> str:
    normalized = {str(key).lower(): str(value).lower() for key, value in tags.items()}
    if (
        normalized.get("man_made") == "mast"
        or normalized.get("tower:type") == "communication"
        or normalized.get("communication:mobile_phone") == "yes"
        or normalized.get("telecom") in {"tower", "mast"}
    ):
        return "telecom"
    if "building" in normalized:
        return "building"
    if "highway" in normalized:
        return "road"
    if normalized.get("landuse") in {"residential", "commercial", "industrial"} or "place" in normalized:
        return "urban"
    return "other"


def first_matching_numeric(values: dict, candidates: list[str]):
    for candidate in candidates:
        if candidate in values:
            return numeric_or_nan(values.get(candidate))
    return np.nan


def extract_ookla_coordinates(frame: pd.DataFrame) -> tuple[pd.Series, pd.Series]:
    for lat_column, lon_column in (("latitude", "longitude"), ("lat", "lon")):
        if lat_column in frame.columns and lon_column in frame.columns:
            return (
                pd.to_numeric(frame[lat_column], errors="coerce"),
                pd.to_numeric(frame[lon_column], errors="coerce"),
            )

    geometry_column = next((column for column in ("geometry", "geom", "wkt") if column in frame.columns), None)
    if geometry_column:
        coordinates = frame[geometry_column].map(parse_wkt_centroid)
        return (
            coordinates.map(lambda value: value[0] if value else np.nan),
            coordinates.map(lambda value: value[1] if value else np.nan),
        )

    quadkey_column = next((column for column in ("quadkey", "tile") if column in frame.columns), None)
    if quadkey_column:
        coordinates = frame[quadkey_column].map(lambda value: quadkey_to_center(str(value)) if pd.notna(value) else None)
        return (
            coordinates.map(lambda value: value[0] if value else np.nan),
            coordinates.map(lambda value: value[1] if value else np.nan),
        )

    raise ValueError("Could not find coordinates in Ookla file. Expected lat/lon, geometry, or quadkey columns.")


def parse_wkt_centroid(value: str | None) -> tuple[float, float] | None:
    if value in (None, "", "nan"):
        return None
    text = str(value).strip()
    if text.upper().startswith("POINT"):
        numbers = [float(number) for number in re.findall(r"-?\d+(?:\.\d+)?", text)]
        if len(numbers) >= 2:
            lon, lat = numbers[0], numbers[1]
            return lat, lon
        return None
    coordinates = re.findall(r"(-?\d+(?:\.\d+)?) (-?\d+(?:\.\d+)?)", text)
    if not coordinates:
        return None
    longitudes = [float(pair[0]) for pair in coordinates]
    latitudes = [float(pair[1]) for pair in coordinates]
    return (sum(latitudes) / len(latitudes), sum(longitudes) / len(longitudes))


def quadkey_to_center(quadkey: str) -> tuple[float, float] | None:
    if not quadkey or any(char not in "0123" for char in quadkey):
        return None

    tile_x = 0
    tile_y = 0
    level_of_detail = len(quadkey)
    for index, digit in enumerate(quadkey):
        mask = 1 << (level_of_detail - index - 1)
        if digit in {"1", "3"}:
            tile_x |= mask
        if digit in {"2", "3"}:
            tile_y |= mask

    pixel_x = (tile_x + 0.5) * 256
    pixel_y = (tile_y + 0.5) * 256
    map_size = 256 << level_of_detail
    x = (clip(pixel_x, 0, map_size - 1) / map_size) - 0.5
    y = 0.5 - (clip(pixel_y, 0, map_size - 1) / map_size)
    latitude = 90.0 - 360.0 * math.atan(math.exp(-y * 2.0 * math.pi)) / math.pi
    longitude = 360.0 * x
    return latitude, longitude


def clip(value: float, minimum: float, maximum: float) -> float:
    return min(max(value, minimum), maximum)


def select_first_numeric_column(frame: pd.DataFrame, candidates: list[str]) -> pd.Series:
    for candidate in candidates:
        if candidate in frame.columns:
            return pd.to_numeric(frame[candidate], errors="coerce")
    return pd.Series(np.nan, index=frame.index)


def parse_datetime_series(series) -> pd.Series:
    if series is None:
        return pd.Series(pd.NaT)
    numeric = pd.to_numeric(series, errors="coerce")
    if numeric.notna().any():
        max_value = numeric.max()
        if max_value > 1_000_000_000_000:
            return pd.to_datetime(numeric, errors="coerce", utc=True, unit="ms")
        if max_value > 1_000_000_000:
            return pd.to_datetime(numeric, errors="coerce", utc=True, unit="s")
    return pd.to_datetime(series, errors="coerce", utc=True)


def days_since_now(series: pd.Series) -> pd.Series:
    if series.empty:
        return pd.Series(dtype=float)
    now = pd.Timestamp.now(tz="UTC")
    delta = now - series
    return delta.dt.total_seconds().div(86400.0)


def filter_bbox(frame: pd.DataFrame, bbox: tuple[float, float, float, float]) -> pd.DataFrame:
    min_lat, min_lon, max_lat, max_lon = bbox
    return frame[
        frame["latitude"].between(min_lat, max_lat)
        & frame["longitude"].between(min_lon, max_lon)
    ].copy()


def build_training_dataset(
    reference_cells: pd.DataFrame,
    ookla_tiles: pd.DataFrame | None = None,
    osm_context: pd.DataFrame | None = None,
    dem_grid: pd.DataFrame | None = None,
) -> pd.DataFrame:
    dataset = reference_cells.copy().reset_index(drop=True)
    dataset = attach_same_group_features(dataset)
    dataset = attach_ookla_features(dataset, ookla_tiles)
    dataset = attach_osm_features(dataset, osm_context)
    dataset = attach_dem_features(dataset, dem_grid)
    dataset["avg_signal"] = build_signal_proxy(dataset)
    dataset["same_group_mean_signal_5"] = dataset["same_group_mean_signal_5"].fillna(dataset["avg_signal"])
    dataset["weak_deadzone_score"] = compute_weak_deadzone_score(dataset)
    dataset["is_deadzone"] = derive_deadzone_labels(dataset)
    return dataset


def build_signal_proxy(dataset: pd.DataFrame) -> pd.Series:
    actual = pd.to_numeric(dataset["avg_signal"], errors="coerce")
    proxy = pd.Series(-118.0, index=dataset.index, dtype=float)

    download = np.log10(dataset["ookla_avg_down_kbps"].clip(lower=1).fillna(1.0))
    upload = np.log10(dataset["ookla_avg_up_kbps"].clip(lower=1).fillna(1.0))
    latency = dataset["ookla_avg_latency_ms"].fillna(dataset["ookla_avg_latency_ms"].median() if dataset["ookla_avg_latency_ms"].notna().any() else 50.0)
    density = dataset["same_group_density_1km"].fillna(0.0)
    nearest = dataset["same_group_nearest_distance_km"].fillna(2.0)
    range_m = dataset["range_m"].fillna(dataset["range_m"].median() if dataset["range_m"].notna().any() else 1000.0)
    samples = dataset["samples"].fillna(0.0)
    telecom_density = dataset["osm_telecom_density_1km"].fillna(0.0)
    telecom_distance = dataset["osm_telecom_nearest_distance_km"].fillna(5.0)
    terrain_relief = dataset["terrain_relief_3km_m"].fillna(0.0)

    proxy += np.minimum(30.0, download * 7.5)
    proxy += np.minimum(10.0, upload * 2.2)
    proxy -= np.minimum(22.0, np.maximum(0.0, latency - 20.0) * 0.18)
    proxy += np.minimum(12.0, density * 2.0)
    proxy += np.minimum(8.0, telecom_density * 1.8)
    proxy -= np.minimum(8.0, np.maximum(0.0, telecom_distance - 0.2) * 5.0)
    proxy -= np.minimum(18.0, np.maximum(0.0, nearest - 0.15) * 12.0)
    proxy -= np.minimum(10.0, np.maximum(0.0, range_m - 1000.0) / 180.0)
    proxy -= np.minimum(12.0, terrain_relief / 35.0)
    proxy += np.minimum(8.0, np.log1p(samples) * 1.4)
    proxy = proxy.clip(lower=-120.0, upper=-75.0)

    return actual.where(actual.notna(), proxy)


def attach_same_group_features(dataset: pd.DataFrame) -> pd.DataFrame:
    density_values = np.zeros(len(dataset), dtype=float)
    nearest_values = np.full(len(dataset), 99.0, dtype=float)
    signal_mean_values = np.zeros(len(dataset), dtype=float)
    range_mean_values = np.zeros(len(dataset), dtype=float)

    for (_operator, _network_type), group in dataset.groupby(["operator", "network_type"], sort=False):
        coords = np.radians(group[["latitude", "longitude"]].to_numpy(dtype=float))
        indices = group.index.to_numpy()
        tree = BallTree(coords, metric="haversine")
        k = min(6, len(group))
        distances, neighbor_indices = tree.query(coords, k=k)
        radius_counts = tree.query_radius(coords, r=1.0 / EARTH_RADIUS_KM, count_only=True) - 1

        for offset, row_index in enumerate(indices):
            neighbors = []
            distances_km = []
            for candidate_offset, candidate_distance in zip(neighbor_indices[offset], distances[offset] * EARTH_RADIUS_KM):
                if candidate_offset == offset:
                    continue
                neighbors.append(candidate_offset)
                distances_km.append(candidate_distance)

            density_values[row_index] = max(0.0, float(radius_counts[offset]))
            nearest_values[row_index] = distances_km[0] if distances_km else 5.0
            if neighbors:
                neighbor_rows = group.iloc[neighbors[:5]]
                signal_mean_values[row_index] = float(neighbor_rows["avg_signal"].mean())
                range_mean_values[row_index] = float(neighbor_rows["range_m"].mean())
            else:
                signal_mean_values[row_index] = float(group.iloc[offset]["avg_signal"])
                range_mean_values[row_index] = float(group.iloc[offset]["range_m"])

    dataset["same_group_density_1km"] = density_values
    dataset["same_group_nearest_distance_km"] = nearest_values
    dataset["same_group_mean_signal_5"] = signal_mean_values
    dataset["same_group_mean_range_5"] = range_mean_values
    return dataset


def attach_ookla_features(dataset: pd.DataFrame, ookla_tiles: pd.DataFrame | None) -> pd.DataFrame:
    dataset = dataset.copy()
    default_columns = {
        "ookla_avg_down_kbps": np.nan,
        "ookla_avg_up_kbps": np.nan,
        "ookla_avg_latency_ms": np.nan,
        "ookla_tests": 0.0,
        "ookla_devices": 0.0,
        "ookla_distance_km": np.nan,
    }
    for column, value in default_columns.items():
        dataset[column] = value

    if ookla_tiles is None or ookla_tiles.empty:
        return dataset

    tree = BallTree(np.radians(ookla_tiles[["latitude", "longitude"]].to_numpy(dtype=float)), metric="haversine")
    distances, indices = tree.query(np.radians(dataset[["latitude", "longitude"]].to_numpy(dtype=float)), k=1)
    nearest = ookla_tiles.iloc[indices[:, 0]].reset_index(drop=True)
    dataset["ookla_avg_down_kbps"] = nearest["avg_d_kbps"].to_numpy(dtype=float)
    dataset["ookla_avg_up_kbps"] = nearest["avg_u_kbps"].to_numpy(dtype=float)
    dataset["ookla_avg_latency_ms"] = nearest["avg_lat_ms"].to_numpy(dtype=float)
    dataset["ookla_tests"] = nearest["tests"].fillna(0.0).to_numpy(dtype=float)
    dataset["ookla_devices"] = nearest["devices"].fillna(0.0).to_numpy(dtype=float)
    dataset["ookla_distance_km"] = distances[:, 0] * EARTH_RADIUS_KM
    return dataset


def attach_osm_features(dataset: pd.DataFrame, osm_context: pd.DataFrame | None) -> pd.DataFrame:
    dataset = dataset.copy()
    dataset["osm_telecom_density_1km"] = 0.0
    dataset["osm_telecom_nearest_distance_km"] = np.nan

    if osm_context is None or osm_context.empty:
        return dataset

    telecom = osm_context[osm_context["feature_kind"] == "telecom"].reset_index(drop=True)
    if telecom.empty:
        return dataset

    tree = BallTree(np.radians(telecom[["latitude", "longitude"]].to_numpy(dtype=float)), metric="haversine")
    query = np.radians(dataset[["latitude", "longitude"]].to_numpy(dtype=float))
    distances, _indices = tree.query(query, k=1)
    densities = tree.query_radius(query, r=1.0 / EARTH_RADIUS_KM, count_only=True)
    dataset["osm_telecom_density_1km"] = densities.astype(float)
    dataset["osm_telecom_nearest_distance_km"] = distances[:, 0] * EARTH_RADIUS_KM
    return dataset


def attach_dem_features(dataset: pd.DataFrame, dem_grid: pd.DataFrame | None) -> pd.DataFrame:
    dataset = dataset.copy()
    dataset["terrain_elevation_m"] = np.nan
    dataset["terrain_relief_3km_m"] = np.nan

    if dem_grid is None or dem_grid.empty:
        return dataset

    tree = BallTree(np.radians(dem_grid[["latitude", "longitude"]].to_numpy(dtype=float)), metric="haversine")
    query = np.radians(dataset[["latitude", "longitude"]].to_numpy(dtype=float))
    neighbor_count = min(9, len(dem_grid))
    distances, indices = tree.query(query, k=neighbor_count)
    elevation_values = []
    relief_values = []
    for row_distances, row_indices in zip(distances, indices):
        nearest_dem = dem_grid.iloc[row_indices]
        elevations = pd.to_numeric(nearest_dem["elevation_m"], errors="coerce")
        weights = 1.0 / np.maximum(row_distances * EARTH_RADIUS_KM, 0.05)
        elevation_values.append(weighted_average(elevations, weights))
        relief_values.append(float(elevations.max() - elevations.min()) if elevations.notna().any() else np.nan)
    dataset["terrain_elevation_m"] = elevation_values
    dataset["terrain_relief_3km_m"] = relief_values
    return dataset


def compute_weak_deadzone_score(dataset: pd.DataFrame) -> pd.Series:
    down_rank = dataset["ookla_avg_down_kbps"].fillna(0.0).rank(pct=True, method="average")
    up_rank = dataset["ookla_avg_up_kbps"].fillna(0.0).rank(pct=True, method="average")
    latency_rank = dataset["ookla_avg_latency_ms"].fillna(dataset["ookla_avg_latency_ms"].median() if dataset["ookla_avg_latency_ms"].notna().any() else 50.0).rank(pct=True, method="average")
    signal_rank = dataset["avg_signal"].fillna(-120.0).rank(pct=True, method="average")
    density_rank = dataset["same_group_density_1km"].fillna(0.0).rank(pct=True, method="average")
    distance_rank = dataset["same_group_nearest_distance_km"].fillna(99.0).rank(pct=True, method="average")
    range_rank = dataset["range_m"].fillna(dataset["range_m"].median() if dataset["range_m"].notna().any() else 1000.0).rank(pct=True, method="average")
    telecom_density_rank = dataset["osm_telecom_density_1km"].fillna(0.0).rank(pct=True, method="average")
    telecom_distance_rank = dataset["osm_telecom_nearest_distance_km"].fillna(99.0).rank(pct=True, method="average")
    relief_rank = dataset["terrain_relief_3km_m"].fillna(0.0).rank(pct=True, method="average")

    return (
        (1.0 - down_rank) * 0.24
        + (1.0 - up_rank) * 0.12
        + latency_rank * 0.16
        + (1.0 - signal_rank) * 0.16
        + (1.0 - density_rank) * 0.10
        + distance_rank * 0.08
        + range_rank * 0.04
        + (1.0 - telecom_density_rank) * 0.04
        + telecom_distance_rank * 0.03
        + relief_rank * 0.03
    )


def derive_deadzone_labels(dataset: pd.DataFrame) -> pd.Series:
    avg_signal = dataset["avg_signal"].fillna(-130.0)
    weak_signal = avg_signal <= -103.0

    tests = dataset["ookla_tests"].fillna(0.0)
    poor_performance = (
        ((dataset["ookla_avg_down_kbps"].fillna(np.inf) <= 3000.0)
         | (dataset["ookla_avg_up_kbps"].fillna(np.inf) <= 512.0)
         | (dataset["ookla_avg_latency_ms"].fillna(0.0) >= 90.0))
        & (tests >= 1.0)
    )
    sparse_topology = (
        (dataset["same_group_density_1km"].fillna(0.0) <= 2.0)
        | (dataset["same_group_nearest_distance_km"].fillna(99.0) >= 0.9)
    )
    sparse_support = (
        (dataset["osm_telecom_density_1km"].fillna(0.0) <= 1.0)
        | (dataset["osm_telecom_nearest_distance_km"].fillna(99.0) >= 1.2)
    )
    rough_terrain = dataset["terrain_relief_3km_m"].fillna(0.0) >= 180.0
    severe_rule = (
        weak_signal
        | (poor_performance & sparse_topology)
        | (poor_performance & (avg_signal <= -96.0))
        | (poor_performance & sparse_support)
        | (weak_signal & rough_terrain)
    )
    risk_score = dataset["weak_deadzone_score"] if "weak_deadzone_score" in dataset.columns else compute_weak_deadzone_score(dataset)
    percentile_rule = risk_score >= risk_score.quantile(0.90)
    labels = severe_rule | percentile_rule

    if labels.nunique() < 2:
        adaptive_cutoff = float(avg_signal.quantile(0.25))
        labels = avg_signal <= adaptive_cutoff
    return labels.astype(int)


def filter_reference_scope(
    reference_cells: pd.DataFrame,
    *,
    operator: str | None = None,
    network_type: str | None = None,
) -> pd.DataFrame:
    frame = reference_cells.copy()
    if operator:
        frame = frame[frame["operator"] == normalize_operator_name(operator)]
    if network_type:
        frame = frame[frame["network_type"] == normalize_network_type(network_type)]
    return frame.reset_index(drop=True)


def train_model_variant(dataset: pd.DataFrame) -> tuple[Pipeline, dict]:
    if len(dataset) < 20:
        raise ValueError("Training dataset is too small after preprocessing. Provide more reference rows.")
    train_frame, test_frame = split_spatial_train_test(dataset)
    model = build_training_pipeline()
    model.fit(train_frame[NUMERIC_FEATURES + CATEGORICAL_FEATURES], train_frame["is_deadzone"])
    metrics = evaluate_model(model, train_frame, test_frame)
    metadata = {
        "training_row_count": int(len(train_frame)),
        "test_row_count": int(len(test_frame)),
        "total_row_count": int(len(dataset)),
        "positive_count": int(dataset["is_deadzone"].sum()),
        "positive_rate": round(float(dataset["is_deadzone"].mean()), 4),
        "operators": sorted(dataset["operator"].dropna().unique().tolist()),
        "network_types": sorted(dataset["network_type"].dropna().unique().tolist()),
        "numeric_features": NUMERIC_FEATURES,
        "categorical_features": CATEGORICAL_FEATURES,
        "labeling_strategy": {
            "weak_signal_dbm_threshold": -103.0,
            "poor_download_kbps_threshold": 3000.0,
            "poor_upload_kbps_threshold": 512.0,
            "poor_latency_ms_threshold": 90.0,
            "low_density_1km_threshold": 2.0,
            "far_nearest_cell_km_threshold": 0.9,
            "risk_score_percentile_threshold": 0.90,
        },
        "metrics": metrics,
    }
    return model, metadata


def reference_subset_for_runtime(dataset: pd.DataFrame) -> pd.DataFrame:
    return dataset[
        [
            "latitude",
            "longitude",
            "operator",
            "network_type",
            "avg_signal",
            "range_m",
            "samples",
            "days_since_update",
        ]
    ].copy()


def train_deadzone_model(
    opencellid_path: str | Path,
    output_model_path: str | Path,
    *,
    ookla_path: str | Path | None = None,
    osm_path: str | Path | None = None,
    dem_path: str | Path | None = None,
    bbox: tuple[float, float, float, float] | None = None,
    operator: str | None = None,
    network_type: str | None = None,
    specialize_groups: bool = False,
    specialize_network_type: str | None = None,
    group_min_rows: int = 120,
    output_dataset_path: str | Path | None = None,
    report_dir: str | Path | None = None,
) -> dict:
    reference_cells = load_opencellid_reference(opencellid_path, bbox=bbox)
    reference_cells = filter_reference_scope(reference_cells, operator=operator, network_type=network_type)
    ookla_tiles = load_ookla_tiles(ookla_path, bbox=bbox) if ookla_path else None
    osm_context = load_osm_context(osm_path, bbox=bbox) if osm_path else None
    dem_grid = load_dem_grid(dem_path, bbox=bbox) if dem_path else None
    dataset = build_training_dataset(reference_cells, ookla_tiles, osm_context=osm_context, dem_grid=dem_grid)

    model, metadata = train_model_variant(dataset)
    metadata.update(
        {
            "bbox": bbox,
            "scope": {
                "operator": normalize_operator_name(operator) if operator else None,
                "network_type": normalize_network_type(network_type) if network_type else None,
            },
            "sources": {
                "opencellid_path": str(opencellid_path),
                "ookla_path": str(ookla_path) if ookla_path else None,
                "osm_path": str(osm_path) if osm_path else None,
                "dem_path": str(dem_path) if dem_path else None,
            },
        }
    )

    specialized_models = {}
    target_specialize_network = normalize_network_type(specialize_network_type) if specialize_network_type else None
    if specialize_groups:
        for (group_operator, group_network_type), group_dataset in dataset.groupby(["operator", "network_type"], sort=False):
            if target_specialize_network and group_network_type != target_specialize_network:
                continue
            if len(group_dataset) < group_min_rows or group_dataset["is_deadzone"].nunique() < 2:
                continue
            group_model, group_metadata = train_model_variant(group_dataset)
            group_metadata["variant_scope"] = {
                "operator": group_operator,
                "network_type": group_network_type,
            }
            specialized_models[group_model_key(group_operator, group_network_type)] = {
                "model": group_model,
                "metadata": group_metadata,
                "reference_cells": reference_subset_for_runtime(group_dataset),
            }

    bundle = {
        "model_version": MODEL_VERSION,
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "model": model,
        "feature_columns": NUMERIC_FEATURES + CATEGORICAL_FEATURES,
        "metadata": metadata,
        "reference_cells": reference_subset_for_runtime(dataset),
        "ookla_tiles": ookla_tiles,
        "osm_context": osm_context,
        "dem_grid": dem_grid,
        "group_models": specialized_models,
    }
    bundle["metadata"]["group_model_keys"] = sorted(specialized_models.keys())
    bundle["metadata"]["group_model_count"] = len(specialized_models)

    output_model_path = Path(output_model_path)
    output_model_path.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(bundle, output_model_path)

    if output_dataset_path:
        output_dataset = Path(output_dataset_path)
        output_dataset.parent.mkdir(parents=True, exist_ok=True)
        dataset.to_csv(output_dataset, index=False)

    if report_dir:
        generate_eda_report(dataset, metadata["metrics"], report_dir)

    return {"model_path": str(output_model_path), "metrics": metadata["metrics"], "row_count": int(len(dataset))}


def split_spatial_train_test(dataset: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    grouped = dataset.copy()
    grouped["lat_bin"] = (grouped["latitude"] * 20).round().astype(int)
    grouped["lon_bin"] = (grouped["longitude"] * 20).round().astype(int)
    groups = grouped["lat_bin"].astype(str) + "_" + grouped["lon_bin"].astype(str)
    if groups.nunique() < 2 or len(grouped) < 10:
        test_size = max(1, int(len(grouped) * 0.2))
        return grouped.iloc[:-test_size].copy(), grouped.iloc[-test_size:].copy()
    splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=42)
    train_indices, test_indices = next(splitter.split(grouped, grouped["is_deadzone"], groups=groups))
    train_frame = grouped.iloc[train_indices].copy()
    test_frame = grouped.iloc[test_indices].copy()
    if train_frame["is_deadzone"].nunique() < 2 or test_frame["is_deadzone"].nunique() < 2:
        train_frame, test_frame = train_test_split(
            grouped,
            test_size=0.2,
            random_state=42,
            stratify=grouped["is_deadzone"],
        )
    return train_frame.copy(), test_frame.copy()


def build_training_pipeline() -> Pipeline:
    numeric_pipeline = Pipeline([("imputer", SimpleImputer(strategy="median"))])
    categorical_pipeline = Pipeline(
        [
            ("imputer", SimpleImputer(strategy="most_frequent")),
            ("encoder", OneHotEncoder(handle_unknown="ignore", sparse_output=False)),
        ]
    )
    preprocessor = ColumnTransformer(
        [
            ("num", numeric_pipeline, NUMERIC_FEATURES),
            ("cat", categorical_pipeline, CATEGORICAL_FEATURES),
        ]
    )
    classifier = RandomForestClassifier(
        n_estimators=320,
        max_depth=12,
        min_samples_leaf=2,
        class_weight="balanced_subsample",
        random_state=42,
        n_jobs=-1,
    )
    return Pipeline([("preprocessor", preprocessor), ("classifier", classifier)])


def evaluate_model(model: Pipeline, train_frame: pd.DataFrame, test_frame: pd.DataFrame) -> dict:
    if test_frame.empty:
        return {"accuracy": None, "roc_auc": None, "average_precision": None, "precision": None, "recall": None}

    feature_columns = NUMERIC_FEATURES + CATEGORICAL_FEATURES
    proba_frame = model.predict_proba(test_frame[feature_columns])
    classes = getattr(model, "classes_", None)
    if classes is None:
        classes = model.named_steps["classifier"].classes_
    if len(classes) == 1:
        positive_class_index = 0
    else:
        positive_class_index = list(classes).index(1)
    probabilities = proba_frame[:, positive_class_index]
    predictions = (probabilities >= 0.5).astype(int)
    precision, recall, _f1, _support = precision_recall_fscore_support(
        test_frame["is_deadzone"],
        predictions,
        average="binary",
        zero_division=0,
    )
    roc_auc = None
    if test_frame["is_deadzone"].nunique() > 1:
        roc_auc = roc_auc_score(test_frame["is_deadzone"], probabilities)
    return {
        "accuracy": round(float(accuracy_score(test_frame["is_deadzone"], predictions)), 4),
        "roc_auc": round(float(roc_auc), 4) if roc_auc is not None else None,
        "average_precision": round(float(average_precision_score(test_frame["is_deadzone"], probabilities)), 4),
        "precision": round(float(precision), 4),
        "recall": round(float(recall), 4),
        "train_rows": int(len(train_frame)),
        "test_rows": int(len(test_frame)),
    }


def generate_eda_report(dataset: pd.DataFrame, metrics: dict, report_dir: str | Path) -> None:
    report_path = Path(report_dir)
    report_path.mkdir(parents=True, exist_ok=True)

    summary = {
        "rows": int(len(dataset)),
        "positive_rate": round(float(dataset["is_deadzone"].mean()), 4),
        "operators": dataset["operator"].value_counts(dropna=False).to_dict(),
        "network_types": dataset["network_type"].value_counts(dropna=False).to_dict(),
        "metrics": metrics,
        "missing_rates": dataset.isna().mean().round(4).to_dict(),
    }
    (report_path / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    figure, axes = plt.subplots(2, 2, figsize=(12, 9))
    dataset["is_deadzone"].value_counts().sort_index().plot(kind="bar", ax=axes[0, 0], color=["#2E7D32", "#C62828"])
    axes[0, 0].set_title("Class Balance")
    axes[0, 0].set_xticklabels(["usable", "deadzone"], rotation=0)

    dataset.boxplot(column="avg_signal", by="is_deadzone", ax=axes[0, 1])
    axes[0, 1].set_title("Average Signal by Label")
    axes[0, 1].set_xlabel("deadzone")
    figure.suptitle("")

    dataset["operator"].value_counts().head(8).plot(kind="bar", ax=axes[1, 0], color="#1565C0")
    axes[1, 0].set_title("Top Operators")
    axes[1, 0].tick_params(axis="x", rotation=30)

    scatter = axes[1, 1].scatter(
        dataset["ookla_avg_down_kbps"].fillna(0.0),
        dataset["ookla_avg_latency_ms"].fillna(0.0),
        c=dataset["is_deadzone"],
        cmap="RdYlGn_r",
        alpha=0.45,
        s=12,
    )
    axes[1, 1].set_title("Ookla Download vs Latency")
    axes[1, 1].set_xlabel("Download (kbps)")
    axes[1, 1].set_ylabel("Latency (ms)")
    figure.colorbar(scatter, ax=axes[1, 1])

    figure.tight_layout()
    figure.savefig(report_path / "eda_overview.png", dpi=160)
    plt.close(figure)


def get_deadzone_runtime(model_path: str | Path | None) -> DeadzoneRuntime | None:
    if not model_path:
        return None
    path = Path(model_path)
    if not path.exists():
        return None
    mtime = path.stat().st_mtime
    cached_path = _RUNTIME_CACHE.get("path")
    cached_mtime = _RUNTIME_CACHE.get("mtime")
    if cached_path == str(path) and cached_mtime == mtime and _RUNTIME_CACHE.get("runtime") is not None:
        return _RUNTIME_CACHE["runtime"]  # type: ignore[return-value]

    bundle = joblib.load(path)
    runtime = DeadzoneRuntime(bundle)
    _RUNTIME_CACHE.update({"path": str(path), "mtime": mtime, "runtime": runtime})
    return runtime


def predict_deadzone(
    model_path: str | Path | None,
    *,
    latitude: float,
    longitude: float,
    operator: str,
    network_type: str,
) -> dict | None:
    runtime = get_deadzone_runtime(model_path)
    if runtime is None:
        return None
    return runtime.predict(latitude=float(latitude), longitude=float(longitude), operator=operator, network_type=network_type)


def bbox_from_string(value: str | None) -> tuple[float, float, float, float] | None:
    if not value:
        return None
    parts = [segment.strip() for segment in value.split(",")]
    if len(parts) != 4:
        raise ValueError("bbox must contain min_lat,min_lon,max_lat,max_lon")
    min_lat, min_lon, max_lat, max_lon = [float(part) for part in parts]
    return (min_lat, min_lon, max_lat, max_lon)


def main() -> None:
    parser = argparse.ArgumentParser(description="Train a dead-zone risk model for the Flask backend.")
    parser.add_argument("--opencellid", required=True, help="Path to an OpenCelliD export CSV.")
    parser.add_argument("--output-model", required=True, help="Where to store the trained joblib model.")
    parser.add_argument("--ookla", help="Path to an Ookla mobile performance file (parquet/csv/json).")
    parser.add_argument("--osm-context", help="Optional OSM-derived context file (csv/parquet/json/geojson).")
    parser.add_argument("--dem-grid", help="Optional DEM/elevation grid file (csv/parquet/json/geojson).")
    parser.add_argument("--bbox", help="Optional min_lat,min_lon,max_lat,max_lon filter.")
    parser.add_argument("--lebanon", action="store_true", help="Apply the default Lebanon bounding box.")
    parser.add_argument("--operator", help="Optional operator scope for the base model.")
    parser.add_argument("--network-type", help="Optional network type scope for the base model.")
    parser.add_argument("--specialize-groups", action="store_true", help="Train exact operator/network subgroup variants.")
    parser.add_argument("--specialize-network-type", help="Restrict subgroup variants to one network type, for example 4G.")
    parser.add_argument("--group-min-rows", type=int, default=120, help="Minimum rows required for a subgroup model.")
    parser.add_argument("--output-dataset", help="Optional path for the prepared training dataset CSV.")
    parser.add_argument("--report-dir", help="Optional directory for EDA outputs.")
    args = parser.parse_args()

    bbox = DEFAULT_LEBANON_BBOX if args.lebanon else bbox_from_string(args.bbox)
    result = train_deadzone_model(
        args.opencellid,
        args.output_model,
        ookla_path=args.ookla,
        osm_path=args.osm_context,
        dem_path=args.dem_grid,
        bbox=bbox,
        operator=args.operator,
        network_type=args.network_type,
        specialize_groups=args.specialize_groups,
        specialize_network_type=args.specialize_network_type,
        group_min_rows=args.group_min_rows,
        output_dataset_path=args.output_dataset,
        report_dir=args.report_dir,
    )
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
