"""Cross-city transfer validation on the Berlin V2X dataset.

Berlin V2X (Fraunhofer HHI) is a CC0-licensed real-world drive-test
dataset with RSRP + GPS measurements at 1-second resolution across
Berlin. We use it as an EXTERNAL, REAL validation set to get honest
RSRP-prediction metrics that do NOT depend on any Lebanon ground truth.

Data source
-----------
GitHub : https://github.com/fraunhoferhhi/BerlinV2X
License: CC0-1.0 (public domain)

Typical usage
-------------
>>> berlin = download_berlin_v2x(cache_dir='./data/raw/berlin_v2x')
>>> berlin_feats = compute_transfer_features(berlin, towers_berlin)
>>> metrics = evaluate_transfer(classifier, berlin_feats, berlin['rsrp'])

Because Berlin has different carriers, bands, antenna patterns and
terrain than Lebanon, this is a HARD transfer test. Performance here
places a lower bound on what the model can achieve in any new city.
"""
from __future__ import annotations

import os
import urllib.request
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd

# Berlin V2X public data — the repo exposes a processed parquet
BERLIN_V2X_PARQUET_URL = (
    "https://github.com/fraunhoferhhi/BerlinV2X/releases/download/"
    "v1.0/cellular_dataframe.parquet"
)


def download_berlin_v2x(
    cache_dir: str | Path = "./data/raw/berlin_v2x",
    force: bool = False,
) -> pd.DataFrame:
    """Download and cache the Berlin V2X cellular dataframe.

    Returns a DataFrame with at least the columns:
        latitude, longitude, rsrp, rsrq, timestamp (if available)

    The upstream parquet may use slightly different column names; we
    normalize them here. If the release URL is unreachable we raise
    a clear error so the caller can fall back.
    """
    cache_dir = Path(cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    local_path = cache_dir / "cellular_dataframe.parquet"

    if not local_path.exists() or force:
        print(f"Downloading Berlin V2X from {BERLIN_V2X_PARQUET_URL} ...")
        try:
            urllib.request.urlretrieve(BERLIN_V2X_PARQUET_URL, local_path)
        except Exception as e:
            raise RuntimeError(
                f"Failed to download Berlin V2X parquet: {e}. "
                "Manual download from https://github.com/fraunhoferhhi/BerlinV2X "
                "required — place cellular_dataframe.parquet in "
                f"{cache_dir}"
            ) from e

    df = pd.read_parquet(local_path)

    # Normalize column names (Berlin V2X has slight variations across releases)
    rename = {}
    for src, dst in [
        ("Latitude", "latitude"), ("lat", "latitude"), ("LATITUDE", "latitude"),
        ("Longitude", "longitude"), ("lng", "longitude"), ("lon", "longitude"),
        ("LONGITUDE", "longitude"),
        ("RSRP", "rsrp"), ("rsrp_dbm", "rsrp"),
        ("RSRQ", "rsrq"),
        ("SINR", "sinr"), ("RSSNR", "sinr"),
        ("Timestamp", "timestamp"), ("time", "timestamp"),
    ]:
        if src in df.columns and dst not in df.columns:
            rename[src] = dst
    df = df.rename(columns=rename)

    # Keep rows with valid coordinates and RSRP
    keep = pd.Series(True, index=df.index)
    for c in ["latitude", "longitude", "rsrp"]:
        if c in df.columns:
            keep &= df[c].notna()
    df = df[keep].copy()

    # Clip to Berlin bbox (sanity; upstream is already Berlin-only)
    if "latitude" in df.columns:
        df = df[(df["latitude"] > 52.3) & (df["latitude"] < 52.7) &
                 (df["longitude"] > 13.1) & (df["longitude"] < 13.8)]

    print(f"Loaded Berlin V2X: {len(df):,} rows")
    return df.reset_index(drop=True)


def get_berlin_opencellid(cache_dir: str | Path = "./data/raw") -> pd.DataFrame:
    """Return Berlin tower positions from OpenCelliD.

    For the transfer experiment we need German tower positions (MCC 262).
    Uses OpenCelliD's public cell database — the caller must have placed
    a Germany extract at ``cache_dir / 'opencellid_berlin_262.csv.gz'``.
    If not present, we construct a minimal synthetic tower grid over
    Berlin so the transfer pipeline can still run (less accurate).
    """
    cache_dir = Path(cache_dir)
    path = cache_dir / "opencellid_berlin_262.csv.gz"
    if path.exists():
        df = pd.read_csv(path, compression="gzip")
        rename = {"lat": "latitude", "lon": "longitude"}
        for s, d in rename.items():
            if s in df.columns and d not in df.columns:
                df = df.rename(columns={s: d})
        df = df[(df["latitude"] > 52.3) & (df["latitude"] < 52.7) &
                 (df["longitude"] > 13.1) & (df["longitude"] < 13.8)]
        return df.reset_index(drop=True)

    # Synthetic fallback: a 10 x 10 grid over Berlin (~2 km spacing)
    print("WARNING: no OpenCelliD Berlin file found, using synthetic grid")
    lats = np.linspace(52.4, 52.6, 10)
    lons = np.linspace(13.2, 13.7, 10)
    rows = [{"latitude": float(a), "longitude": float(o),
              "range": 2000, "operator": "Unknown",
              "network_type": "4G", "frequency_band": "LTE_1800"}
             for a in lats for o in lons]
    return pd.DataFrame(rows)


# ── Feature computation for transfer set ───────────────────────────

def compute_transfer_features(
    measurements: pd.DataFrame,
    towers: pd.DataFrame,
    feature_context=None,
    max_rows: int = 5000,
) -> pd.DataFrame:
    """Compute the same feature set used for Lebanon training.

    Sub-samples to ``max_rows`` for tractable feature computation on
    Colab. Returns a DataFrame with the same columns as the training
    feature matrix.

    If ``feature_context`` is a ``FeatureContext`` instance from
    ``deadzone_features``, we use it; otherwise we import and build
    one on the fly.
    """
    if len(measurements) > max_rows:
        measurements = measurements.sample(max_rows, random_state=42).reset_index(drop=True)

    from deadzone_features import FeatureContext, build_feature_dataframe

    # Build minimal context: towers only. OSM/DEM not required for the
    # propagation+topology features we actually want to test transfer on.
    if feature_context is None:
        feature_context = FeatureContext(
            ref_cells=towers,
            ookla_df=None,
            dem_lats=None, dem_lons=None, dem_elevations=None,
            osm_telecom_df=None, osm_buildings_df=None, osm_roads_df=None,
            coastline_df=None, app_h3_aggregates={},
        )

    # Normalize measurement frame to the shape build_feature_dataframe expects
    m = measurements.copy()
    if "operator" not in m.columns:
        m["operator"] = "Unknown"
    if "network_type" not in m.columns:
        m["network_type"] = "4G"
    if "frequency_band" not in m.columns:
        m["frequency_band"] = "LTE_1800"
    if "is_deadzone" not in m.columns:
        # Placeholder — the feature builder doesn't use it
        m["is_deadzone"] = 0
    feats = build_feature_dataframe(m, feature_context)
    return feats


# ── Transfer evaluation ─────────────────────────────────────────────

def evaluate_transfer(
    regressor,
    classifier,
    features_df: pd.DataFrame,
    true_rsrp: np.ndarray,
    dead_threshold_dbm: float = -110.0,
) -> dict:
    """Compute RSRP regression and dead-zone classification metrics on
    an external real-measurement set.

    Returns a dict with keys:
        rsrp_rmse, rsrp_mae, rsrp_r2
        cls_roc_auc, cls_pr_auc, cls_accuracy
        n_samples
    """
    from sklearn.metrics import (
        accuracy_score, mean_absolute_error, mean_squared_error,
        r2_score, roc_auc_score, average_precision_score,
    )

    out = {"n_samples": len(features_df)}

    # Regression: predict RSRP
    try:
        pred_rsrp = regressor.predict(features_df)
        out["rsrp_rmse"] = float(np.sqrt(mean_squared_error(true_rsrp, pred_rsrp)))
        out["rsrp_mae"] = float(mean_absolute_error(true_rsrp, pred_rsrp))
        out["rsrp_r2"] = float(r2_score(true_rsrp, pred_rsrp))
    except Exception as e:
        out["rsrp_error"] = str(e)

    # Classification: predict dead-zone probability
    try:
        y_true = (true_rsrp < dead_threshold_dbm).astype(int)
        proba = classifier.predict_proba(features_df)[:, 1]
        pred_bin = (proba > 0.5).astype(int)
        out["cls_accuracy"] = float(accuracy_score(y_true, pred_bin))
        if len(np.unique(y_true)) > 1:
            out["cls_roc_auc"] = float(roc_auc_score(y_true, proba))
            out["cls_pr_auc"] = float(average_precision_score(y_true, proba))
        else:
            out["cls_roc_auc"] = None
            out["cls_pr_auc"] = None
        out["dead_rate"] = float(y_true.mean())
    except Exception as e:
        out["cls_error"] = str(e)

    return out


# ── Ookla-agreement metric for Lebanon (no RSRP labels needed) ────

def ookla_agreement_metric(
    classifier,
    features_df: pd.DataFrame,
    ookla_aligned: pd.DataFrame,
    speed_threshold_kbps: float = 1000.0,
) -> dict:
    """Compute the model's agreement with real Ookla low-speed tiles.

    This is an HONEST validation metric for Lebanon: it doesn't need
    RSRP ground truth. It measures the rate at which the classifier's
    'dead zone' predictions coincide with locations that Ookla users
    actually measured as slow (< ``speed_threshold_kbps`` kbps).

    Parameters
    ----------
    features_df    : feature matrix aligned with ookla_aligned rows.
    ookla_aligned  : DataFrame with at least 'avg_d_kbps' column,
                     rows aligned 1:1 with features_df.
    """
    from sklearn.metrics import roc_auc_score, average_precision_score

    if "avg_d_kbps" not in ookla_aligned.columns:
        return {"error": "no avg_d_kbps column"}

    y_true = (ookla_aligned["avg_d_kbps"].values < speed_threshold_kbps).astype(int)
    proba = classifier.predict_proba(features_df)[:, 1]

    out = {
        "n_samples": len(features_df),
        "ookla_slow_rate": float(y_true.mean()),
    }
    if len(np.unique(y_true)) > 1:
        out["agreement_roc_auc"] = float(roc_auc_score(y_true, proba))
        out["agreement_pr_auc"] = float(average_precision_score(y_true, proba))
    else:
        out["agreement_roc_auc"] = None
        out["agreement_pr_auc"] = None

    # Binary agreement at threshold 0.5
    pred = (proba > 0.5).astype(int)
    out["binary_agreement"] = float((pred == y_true).mean())
    out["true_positive_rate"] = float(
        ((pred == 1) & (y_true == 1)).sum() / max(y_true.sum(), 1)
    )
    out["false_positive_rate"] = float(
        ((pred == 1) & (y_true == 0)).sum() / max((1 - y_true).sum(), 1)
    )
    return out
