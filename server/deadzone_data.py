"""Data loading, fusion, and labeling for dead-zone prediction v3.

Combines real measurement data from multiple sources into a unified
training dataset with proper labels derived from actual measurements.

Label philosophy:
- Labels come from REAL measurements only (Ookla speed, app signal)
- OpenCelliD towers are feature context, NOT training targets
- Physics models (COST-231) are FEATURES, never label sources
- Continuous quality score + binary threshold for classification

Data source hierarchy:
- Tier 1: App signal measurements (direct RSRP from device radio)
- Tier 2: Ookla speed tests (real throughput from real users)
- Context: OpenCelliD towers, OSM, DEM (features only, never labels)
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

try:
    import h3
except ImportError:
    h3 = None  # type: ignore[assignment]

from deadzone_propagation import (
    cost231_hata_path_loss,
    frequency_from_band_string,
    DEFAULT_EIRP_DBM,
)


# ── Signal quality thresholds (3GPP / ITU standards) ──────────────

# Tier 1: Direct signal measurements (3GPP TS 36.133 reference levels)
RSRP_DEADZONE = -110.0       # dBm — below = no usable service
RSRP_POOR = -100.0           # dBm — weak but some connectivity
RSRP_FAIR = -90.0            # dBm — acceptable service
RSRP_GOOD = -80.0            # dBm — strong signal
SNR_DEADZONE = 3.0           # dB — below = unusable link quality

# Tier 2: Speed-based dead-zone definition (ITU minimum broadband)
# ITU-T Y.1541 & GSMA MCR minimum acceptable mobile broadband
SPEED_DEADZONE_DL_KBPS = 1000.0     # < 1 Mbps = functional dead zone
SPEED_DEADZONE_LAT_MS = 100.0       # > 100ms = degraded experience
SPEED_POOR_DL_KBPS = 5000.0         # < 5 Mbps = poor
SPEED_GOOD_DL_KBPS = 25000.0        # > 25 Mbps = good service

# Empirical speed-to-RSRP mapping (3GPP TR 36.942, Shannon capacity curve)
# Used to create a continuous regression target from speed measurements
SPEED_TO_RSRP_ANCHORS = [
    # (download_kbps, estimated_rsrp_dBm)
    (100,    -120.0),   # Edge of coverage, almost no data
    (500,    -115.0),   # Bare minimum, deep dead zone
    (1000,   -110.0),   # ITU minimum broadband threshold
    (2000,   -105.0),   # Below usable for modern apps
    (5000,   -100.0),   # Poor but functional
    (10000,  -95.0),    # Fair service
    (25000,  -90.0),    # Good service
    (50000,  -85.0),    # Very good
    (100000, -75.0),    # Excellent
    (300000, -65.0),    # Peak performance
]

# Sample weights per tier (reflect label confidence)
TIER_WEIGHTS = {
    "app": 3.0,       # Direct measurement — highest confidence
    "ookla": 2.0,     # Real user speed tests — strong proxy
    "topology": 0.5,  # Physics estimate only — low confidence
}

# Regression target weights
REGRESSION_WEIGHT_MEASURED = 1.0    # Actual signal reading
REGRESSION_WEIGHT_ESTIMATED = 0.6   # Estimated from speed mapping


# ── Speed-to-RSRP mapping ─────────────────────────────────────────

def speed_to_estimated_rsrp(download_kbps: float) -> float:
    """Map download speed to estimated RSRP using piecewise linear interpolation.

    Based on empirical relationship between throughput and signal strength
    from 3GPP TR 36.942 and Shannon capacity curve adjustments for
    typical LTE deployments (10 MHz bandwidth, SISO).

    Returns estimated RSRP in dBm.
    """
    if pd.isna(download_kbps) or download_kbps <= 0:
        return np.nan

    anchors = SPEED_TO_RSRP_ANCHORS
    # Clamp to anchor range
    if download_kbps <= anchors[0][0]:
        return anchors[0][1]
    if download_kbps >= anchors[-1][0]:
        return anchors[-1][1]

    # Linear interpolation between anchors
    for i in range(len(anchors) - 1):
        s0, r0 = anchors[i]
        s1, r1 = anchors[i + 1]
        if s0 <= download_kbps <= s1:
            # Interpolate in log-speed space for better fit
            t = (np.log10(download_kbps) - np.log10(s0)) / (np.log10(s1) - np.log10(s0))
            return r0 + t * (r1 - r0)

    return -100.0  # fallback


def compute_quality_score(
    download_kbps: float | None = None,
    upload_kbps: float | None = None,
    latency_ms: float | None = None,
    signal_dbm: float | None = None,
) -> float:
    """Compute a continuous signal quality score from available measurements.

    Returns a score from 0.0 (perfect dead zone) to 1.0 (excellent service).
    Uses whichever measurements are available.
    """
    scores = []
    weights = []

    # Direct signal measurement (most reliable)
    if signal_dbm is not None and not np.isnan(signal_dbm):
        # Map -120..-65 dBm to 0..1
        s = np.clip((signal_dbm - (-120.0)) / ((-65.0) - (-120.0)), 0.0, 1.0)
        scores.append(s)
        weights.append(3.0)

    # Download speed
    if download_kbps is not None and not np.isnan(download_kbps):
        # Log-scale mapping: 100 kbps=0, 100 Mbps=1
        s = np.clip((np.log10(max(download_kbps, 1)) - np.log10(100)) /
                     (np.log10(100000) - np.log10(100)), 0.0, 1.0)
        scores.append(s)
        weights.append(2.0)

    # Latency (inverse — high latency = bad)
    if latency_ms is not None and not np.isnan(latency_ms):
        # Map 10ms=1.0, 500ms=0.0
        s = np.clip(1.0 - (latency_ms - 10.0) / (500.0 - 10.0), 0.0, 1.0)
        scores.append(s)
        weights.append(1.0)

    # Upload speed (supplementary)
    if upload_kbps is not None and not np.isnan(upload_kbps):
        s = np.clip((np.log10(max(upload_kbps, 1)) - np.log10(50)) /
                     (np.log10(50000) - np.log10(50)), 0.0, 1.0)
        scores.append(s)
        weights.append(0.5)

    if not scores:
        return np.nan

    return float(np.average(scores, weights=weights))


# ── App data export ─────────────────────────────────────────────────

def export_app_measurements(db_session) -> pd.DataFrame:
    """Query CellData table and return a DataFrame of geo-tagged measurements."""
    from models import CellData

    rows = db_session.query(CellData).filter(
        CellData.latitude.isnot(None),
        CellData.longitude.isnot(None),
        CellData.signal_power.isnot(None),
    ).all()

    if not rows:
        return pd.DataFrame()

    records = []
    for r in rows:
        records.append({
            "latitude": r.latitude,
            "longitude": r.longitude,
            "signal_power": r.signal_power,
            "snr": r.snr,
            "operator": r.operator,
            "network_type": r.network_type,
            "cell_id": r.cell_id,
            "frequency_band": r.frequency_band,
            "timestamp": r.timestamp,
            "device_id": r.device_id,
        })

    df = pd.DataFrame(records)
    df["operator"] = df["operator"].str.strip()
    df["network_type"] = df["network_type"].str.upper().str.strip()
    return df


# ── H3 aggregate precomputation ─────────────────────────────────────

def precompute_h3_aggregates(
    app_data: pd.DataFrame,
    resolution: int = 9,
) -> dict[str, dict]:
    """Group app measurements by H3 hex and compute aggregate stats."""
    if h3 is None or app_data is None or len(app_data) == 0:
        return {}

    app_data = app_data.copy()
    app_data["h3_idx"] = app_data.apply(
        lambda r: h3.latlng_to_cell(r["latitude"], r["longitude"], resolution), axis=1
    )

    aggregates = {}
    for idx, grp in app_data.groupby("h3_idx"):
        signals = grp["signal_power"].dropna()
        snrs = grp["snr"].dropna() if "snr" in grp.columns else pd.Series(dtype=float)

        aggregates[idx] = {
            "mean_signal": float(signals.mean()) if len(signals) > 0 else np.nan,
            "std_signal": float(signals.std()) if len(signals) > 1 else 0.0,
            "sample_count": len(grp),
            "mean_snr": float(snrs.mean()) if len(snrs) > 0 else np.nan,
            "deadzone_fraction": float(
                (signals < RSRP_DEADZONE).mean()
            ) if len(signals) > 0 else np.nan,
            "cell_id_count": int(grp["cell_id"].nunique()) if "cell_id" in grp.columns else 0,
        }

    # Also compute resolution-7 cell_id counts
    app_data["h3_r7"] = app_data.apply(
        lambda r: h3.latlng_to_cell(r["latitude"], r["longitude"], 7), axis=1
    )
    for idx, grp in app_data.groupby("h3_r7"):
        if idx not in aggregates:
            aggregates[idx] = {}
        aggregates[idx]["cell_id_count"] = int(
            grp["cell_id"].nunique() if "cell_id" in grp.columns else 0
        )

    return aggregates


# ── Measurement-based labeling ─────────────────────────────────────

def label_app_rows(df: pd.DataFrame) -> pd.DataFrame:
    """Label rows from app measurements using direct signal readings (Tier 1).

    This is ground truth — actual RSRP measured by the device radio.
    Thresholds follow 3GPP TS 36.133 reference signal levels.
    """
    df = df.copy()
    df["label_source"] = "app"
    df["sample_weight"] = TIER_WEIGHTS["app"]

    # Binary label from signal power
    conditions = [
        df["signal_power"] <= RSRP_DEADZONE,        # -110 dBm
        df["signal_power"] >= RSRP_FAIR,             # -90 dBm
    ]
    choices = [1, 0]
    df["is_deadzone"] = np.select(conditions, choices, default=-1)

    # Resolve ambiguous (-110 to -90) using SNR if available
    ambiguous = df["is_deadzone"] == -1
    if "snr" in df.columns:
        low_snr = ambiguous & (df["snr"].fillna(999) < SNR_DEADZONE)
        df.loc[low_snr, "is_deadzone"] = 1
        df.loc[ambiguous & ~low_snr, "is_deadzone"] = 0
    else:
        df.loc[ambiguous, "is_deadzone"] = 0

    # Regression target: actual measured signal power
    df["signal_target"] = df["signal_power"]
    df["regression_weight"] = REGRESSION_WEIGHT_MEASURED

    # Quality score from direct measurement
    df["quality_score"] = df.apply(
        lambda r: compute_quality_score(signal_dbm=r.get("signal_power")),
        axis=1,
    )

    return df


def label_ookla_rows(df: pd.DataFrame) -> pd.DataFrame:
    """Label rows from Ookla speed tests using real throughput data (Tier 2).

    Ookla tiles represent REAL speed tests run by REAL users on the actual
    network. A tile with 500 kbps download and 200ms latency is a measured
    dead zone — this is not synthetic.

    Dead-zone definition follows ITU-T Y.1541 and GSMA minimum coverage
    requirements: download < 1 Mbps is below minimum acceptable broadband.
    """
    df = df.copy()
    df["label_source"] = "ookla"
    df["sample_weight"] = TIER_WEIGHTS["ookla"]

    dl = df["avg_d_kbps"].fillna(99999) if "avg_d_kbps" in df.columns else pd.Series(99999, index=df.index)
    lat = df["avg_lat_ms"].fillna(0) if "avg_lat_ms" in df.columns else pd.Series(0, index=df.index)

    # Dead-zone: real speed below ITU minimum broadband (1 Mbps)
    # We use download alone as primary, latency as confirming signal
    conditions = [
        # Clear dead zone: very low speed
        dl < SPEED_DEADZONE_DL_KBPS,
        # Clear good: fast download AND low latency
        (dl > SPEED_GOOD_DL_KBPS) & (lat < 50),
        # Poor: below 5 Mbps or high latency
        (dl < SPEED_POOR_DL_KBPS) | (lat > SPEED_DEADZONE_LAT_MS),
    ]
    choices = [1, 0, -1]  # -1 = ambiguous poor zone
    df["is_deadzone"] = np.select(conditions, choices, default=0)

    # Resolve ambiguous: use latency as tiebreaker
    ambiguous = df["is_deadzone"] == -1
    df.loc[ambiguous & (lat > 150), "is_deadzone"] = 1
    df.loc[ambiguous & (lat <= 150), "is_deadzone"] = 0

    # Regression target: estimate RSRP from measured speed
    # This is a well-established mapping (3GPP TR 36.942)
    df["signal_target"] = dl.apply(speed_to_estimated_rsrp)
    df["regression_weight"] = REGRESSION_WEIGHT_ESTIMATED

    # Quality score from all available speed metrics
    df["quality_score"] = df.apply(
        lambda r: compute_quality_score(
            download_kbps=r.get("avg_d_kbps"),
            upload_kbps=r.get("avg_u_kbps"),
            latency_ms=r.get("avg_lat_ms"),
        ),
        axis=1,
    )

    return df


def label_topology_rows(df: pd.DataFrame, rsrp_col: str = "cost231_predicted_rsrp") -> pd.DataFrame:
    """Label gap-fill rows from a physics-model predicted RSRP column (Tier 3).

    Accepts any RSRP column name via ``rsrp_col`` so callers can plug
    in COST-231 (legacy), P.1812 (terrain-aware, preferred), or Sionna
    RT (ray-traced, research-grade) labels.

    These labels are physics-model estimates, NOT real measurements.
    They receive the lowest sample_weight to reflect this uncertainty.
    Used to fill geographic areas with zero real measurement coverage.

    Labeling is based purely on predicted RSRP from the propagation
    model — no tower density bias (gap-fill points are random locations,
    not tower sites).
    """
    df = df.copy()
    df["label_source"] = "topology"
    df["sample_weight"] = TIER_WEIGHTS["topology"]

    rsrp = (
        df[rsrp_col]
        if rsrp_col in df.columns
        else pd.Series(np.nan, index=df.index)
    )
    # Cap to physically realistic range: COST-231 Hata is invalid
    # below ~-130 dBm (model extrapolation beyond its design range)
    rsrp = rsrp.clip(lower=-130.0)

    # Label based on predicted RSRP thresholds (3GPP TS 36.133)
    conditions = [
        rsrp < RSRP_DEADZONE,      # -110 dBm: dead zone
        rsrp > RSRP_FAIR,          # -90 dBm: clearly good
    ]
    choices = [1, 0]
    # Default: ambiguous region → label as 0 (not dead zone) with
    # lower weight so these don't dominate training
    df["is_deadzone"] = np.select(conditions, choices, default=0)

    # Rows in the ambiguous -110 to -90 range get even lower weight
    ambiguous = (rsrp >= RSRP_DEADZONE) & (rsrp <= RSRP_FAIR)
    df.loc[ambiguous, "sample_weight"] = TIER_WEIGHTS["topology"] * 0.5

    df["signal_target"] = rsrp
    df["regression_weight"] = 0.3  # Very low — this is modeled, not measured

    df["quality_score"] = df.apply(
        lambda r: compute_quality_score(signal_dbm=r.get("cost231_predicted_rsrp")),
        axis=1,
    )

    return df


# ── Dataset fusion ──────────────────────────────────────────────────

def build_training_dataset(
    ookla_df: pd.DataFrame | None = None,
    app_df: pd.DataFrame | None = None,
    opencellid_df: pd.DataFrame | None = None,
    h3_resolution: int = 9,
    include_topology: bool = True,
    physics_backend: str = "cost231",
    dem_df: pd.DataFrame | None = None,
    sionna_rsrp_df: pd.DataFrame | None = None,
) -> pd.DataFrame:
    """Build training dataset from REAL measurements first.

    Priority order:
    1. App measurements (Tier 1) — real signal power from device
    2. Ookla speed tests (Tier 2) — real throughput from users
    3. OpenCelliD topology (Tier 3) — physics-model estimates (optional)

    Unlike the legacy fuse_datasets, this function:
    - Uses measurements as PRIMARY data, not tower locations
    - Does NOT aggressively H3-dedup across sources
    - Labels from real measurements, not physics models
    - Assigns confidence weights reflecting label quality
    """
    parts = []

    # ── Tier 1: App measurements (highest quality) ──
    if app_df is not None and len(app_df) > 0:
        app = app_df.copy()
        # Ensure required columns
        for col in ["operator", "network_type"]:
            if col not in app.columns:
                app[col] = "Unknown"
        app["source"] = "app"
        # Deduplicate within app data by H3 (average signal per hex)
        if h3 is not None:
            app["_h3"] = app.apply(
                lambda r: h3.latlng_to_cell(r["latitude"], r["longitude"], h3_resolution)
                if pd.notna(r["latitude"]) and pd.notna(r["longitude"]) else "",
                axis=1,
            )
            # Aggregate per hex: keep mean signal, max samples
            agg_cols = {"latitude": "mean", "longitude": "mean", "source": "first"}
            if "signal_power" in app.columns:
                agg_cols["signal_power"] = "mean"
            if "snr" in app.columns:
                agg_cols["snr"] = "mean"
            for col in ["operator", "network_type", "frequency_band"]:
                if col in app.columns:
                    agg_cols[col] = "first"
            app = app.groupby("_h3").agg(agg_cols).reset_index(drop=True)

        parts.append(label_app_rows(app))
        print(f"  Tier 1 (App measurements): {len(parts[-1]):,} rows")

    # ── Tier 2: Ookla speed tests (real measurements) ──
    if ookla_df is not None and len(ookla_df) > 0:
        ook = ookla_df.copy()
        ook["source"] = "ookla"
        for col in ["operator", "network_type"]:
            if col not in ook.columns:
                ook[col] = "Unknown"

        # Deduplicate Ookla within itself by H3
        if h3 is not None:
            ook["_h3"] = ook.apply(
                lambda r: h3.latlng_to_cell(r["latitude"], r["longitude"], h3_resolution)
                if pd.notna(r["latitude"]) and pd.notna(r["longitude"]) else "",
                axis=1,
            )
            # If an app measurement already covers this hex, skip the Ookla row
            if parts:
                app_hexes = set()
                for p in parts:
                    if h3 is not None:
                        p_h3 = p.apply(
                            lambda r: h3.latlng_to_cell(r["latitude"], r["longitude"], h3_resolution)
                            if pd.notna(r["latitude"]) and pd.notna(r["longitude"]) else "",
                            axis=1,
                        )
                        app_hexes.update(p_h3.values)
                ook = ook[~ook["_h3"].isin(app_hexes)]

            ook = ook.drop_duplicates(subset=["_h3"], keep="first")
            if "_h3" in ook.columns:
                ook = ook.drop(columns=["_h3"])

        parts.append(label_ookla_rows(ook))
        print(f"  Tier 2 (Ookla speed tests): {len(parts[-1]):,} rows")

    # ── Tier 3: Gap-fill points (lowest confidence, optional) ──
    # Sample random geographic points in areas NOT covered by Tier 1/2.
    # Unlike the old approach (using tower locations), this samples the
    # actual coverage landscape — including weak-signal areas far from
    # towers — giving the model both positive and negative examples.
    if include_topology and opencellid_df is not None and len(opencellid_df) > 0:
        # Determine geographic bounds from existing data
        all_lats = pd.concat([p["latitude"] for p in parts]) if parts else pd.Series(dtype=float)
        all_lons = pd.concat([p["longitude"] for p in parts]) if parts else pd.Series(dtype=float)

        if len(all_lats) > 0:
            lat_min, lat_max = all_lats.min() - 0.1, all_lats.max() + 0.1
            lon_min, lon_max = all_lons.min() - 0.1, all_lons.max() + 0.1
        else:
            # Lebanon defaults
            lat_min, lat_max = 33.05, 34.72
            lon_min, lon_max = 35.09, 36.68

        # Build set of hexes already covered by Tier 1/2
        covered_hexes = set()
        if h3 is not None and parts:
            for p in parts:
                p_h3 = p.apply(
                    lambda r: h3.latlng_to_cell(r["latitude"], r["longitude"], h3_resolution)
                    if pd.notna(r["latitude"]) and pd.notna(r["longitude"]) else "",
                    axis=1,
                )
                covered_hexes.update(p_h3.values)

        # Generate random points at various distances from towers.
        # Stratified sampling: 40% within 2 km, 30% 2-5 km, 30% 5-15 km
        # This avoids both over-sampling "obviously good" tower locations
        # AND over-sampling "obviously dead" remote mountains.
        rng = np.random.RandomState(42)
        n_target = min(len(opencellid_df) * 2, 3000)  # cap gap-fill count

        # Get tower positions for offset sampling
        tower_lats = opencellid_df["latitude"].values
        tower_lons = opencellid_df["longitude"].values
        n_towers = len(tower_lats)

        rand_lats = []
        rand_lons = []
        for dist_km, frac in [(2.0, 0.4), (5.0, 0.3), (15.0, 0.3)]:
            n_pts = int(n_target * frac)
            # Pick random towers and offset by random direction+distance
            tidx = rng.randint(0, n_towers, n_pts)
            angles = rng.uniform(0, 2 * np.pi, n_pts)
            dists = rng.uniform(0.5, dist_km, n_pts)
            # Approximate degree offset (1 degree ≈ 111 km)
            dlat = dists * np.cos(angles) / 111.0
            dlon = dists * np.sin(angles) / (111.0 * np.cos(np.radians(tower_lats[tidx])))
            rand_lats.extend(tower_lats[tidx] + dlat)
            rand_lons.extend(tower_lons[tidx] + dlon)

        rand_lats = np.array(rand_lats)
        rand_lons = np.array(rand_lons)

        # Clip to bounding box
        valid = (
            (rand_lats >= lat_min) & (rand_lats <= lat_max) &
            (rand_lons >= lon_min) & (rand_lons <= lon_max)
        )
        rand_lats = rand_lats[valid]
        rand_lons = rand_lons[valid]

        gap_rows = []
        for rlat, rlon in zip(rand_lats, rand_lons):
            if h3 is not None:
                hex_id = h3.latlng_to_cell(float(rlat), float(rlon), h3_resolution)
                if hex_id in covered_hexes:
                    continue
                covered_hexes.add(hex_id)  # avoid duplicates
            gap_rows.append({"latitude": float(rlat), "longitude": float(rlon)})

        if gap_rows:
            topo = pd.DataFrame(gap_rows)
            topo["source"] = "gap_fill"
            # Inherit operator/network_type from nearest tower for labeling
            from sklearn.neighbors import BallTree as _BT
            _tree = _BT(
                np.radians(opencellid_df[["latitude", "longitude"]].values),
                metric="haversine",
            )
            _q = np.radians(topo[["latitude", "longitude"]].values)
            _, _idx = _tree.query(_q, k=1)
            for col in ["operator", "network_type"]:
                if col in opencellid_df.columns:
                    topo[col] = opencellid_df.iloc[_idx.ravel()][col].values
                else:
                    topo[col] = "Unknown"

            # Compute physics-model predicted RSRP for each gap-fill
            # point. Three backends:
            #   - "cost231": legacy distance+frequency formula. LEAK-PRONE
            #                because the formula is algebraically reproducible
            #                from features already in the training matrix.
            #   - "p1812":   ITU-R P.1812-simplified with real DEM terrain
            #                profiles between tower and receiver (Bullington
            #                diffraction). Uses information not encoded in
            #                any single feature → breaks the circular leak.
            #   - "sionna":  pre-computed ray-traced RSRP supplied via
            #                ``sionna_rsrp_df``. Research-grade, requires
            #                Sionna RT + CUDA.
            from deadzone_propagation import compute_propagation_features

            _q2 = np.radians(topo[["latitude", "longitude"]].values)
            _, _idx2 = _tree.query(_q2, k=1)

            # Always compute COST-231 too — it remains as a FEATURE
            cost_vals = []
            for j in range(len(topo)):
                nr = opencellid_df.iloc[_idx2[j, 0]]
                pf = compute_propagation_features(
                    float(topo.iloc[j]["latitude"]),
                    float(topo.iloc[j]["longitude"]),
                    str(topo.iloc[j].get("operator", "")),
                    str(topo.iloc[j].get("network_type", "4G")),
                    topo.iloc[j].get("frequency_band"),
                    float(nr["latitude"]),
                    float(nr["longitude"]),
                )
                cost_vals.append(pf["cost231_predicted_rsrp"])
            topo["cost231_predicted_rsrp"] = cost_vals

            # Select labeling backend
            rsrp_col = "cost231_predicted_rsrp"
            if physics_backend == "p1812":
                try:
                    from deadzone_physics import compute_p1812_rsrp_batch
                    p1812_res = compute_p1812_rsrp_batch(
                        rx_points=topo, opencellid_df=opencellid_df,
                        dem_df=dem_df,
                    )
                    topo["p1812_rsrp_dbm"] = p1812_res["p1812_rsrp_dbm"].values
                    topo["p1812_distance_km"] = p1812_res["p1812_distance_km"].values
                    topo["p1812_diffraction_loss_db"] = p1812_res[
                        "p1812_diffraction_loss_db"].values
                    rsrp_col = "p1812_rsrp_dbm"
                    print(f"  Tier 3 labels: P.1812-simplified with DEM "
                          f"(RSRP range {topo['p1812_rsrp_dbm'].min():.0f} .. "
                          f"{topo['p1812_rsrp_dbm'].max():.0f} dBm)")
                except Exception as e:
                    print(f"  P.1812 backend failed ({e}), falling back to COST-231")
            elif physics_backend == "sionna" and sionna_rsrp_df is not None:
                if len(sionna_rsrp_df) == len(topo):
                    topo["sionna_rsrp_dbm"] = sionna_rsrp_df[
                        "sionna_rsrp_dbm"].values
                    rsrp_col = "sionna_rsrp_dbm"
                    print(f"  Tier 3 labels: Sionna RT ray-traced")
                else:
                    print("  Sionna rsrp_df length mismatch — using COST-231")

            parts.append(label_topology_rows(topo, rsrp_col=rsrp_col))
            print(f"  Tier 3 (Gap-fill, backend={physics_backend}): "
                  f"{len(parts[-1]):,} rows")

    if not parts:
        return pd.DataFrame()

    result = pd.concat(parts, ignore_index=True)
    result["is_deadzone"] = result["is_deadzone"].astype(int)

    # Report label quality
    measured = result["label_source"].isin(["app", "ookla"]).sum()
    total = len(result)
    print(f"  Total: {total:,} rows ({measured:,} from real measurements = "
          f"{measured/total:.0%})")

    return result


# Legacy alias for backward compatibility
def fuse_datasets(
    opencellid_df: pd.DataFrame,
    ookla_df: pd.DataFrame | None = None,
    app_df: pd.DataFrame | None = None,
    h3_resolution: int = 9,
) -> pd.DataFrame:
    """Legacy wrapper — delegates to build_training_dataset."""
    return build_training_dataset(
        ookla_df=ookla_df,
        app_df=app_df,
        opencellid_df=opencellid_df,
        h3_resolution=h3_resolution,
    )


def assign_tiered_labels(
    dataset: pd.DataFrame,
) -> pd.DataFrame:
    """Apply tiered labeling to a fused dataset.

    Dispatches to Tier 1/2/3 labeling based on the ``source`` column.
    Returns dataset with ``is_deadzone``, ``label_source``,
    ``sample_weight``, ``signal_target``, ``regression_weight`` columns.
    """
    parts = []

    app_mask = dataset["source"] == "app"
    if app_mask.any():
        parts.append(label_app_rows(dataset[app_mask]))

    ookla_mask = dataset["source"] == "ookla"
    if ookla_mask.any():
        parts.append(label_ookla_rows(dataset[ookla_mask]))

    topo_mask = dataset["source"] == "opencellid"
    if topo_mask.any():
        parts.append(label_topology_rows(dataset[topo_mask]))

    other_mask = ~(app_mask | ookla_mask | topo_mask)
    if other_mask.any():
        parts.append(label_topology_rows(dataset[other_mask]))

    if not parts:
        return dataset

    result = pd.concat(parts, ignore_index=True)
    result["is_deadzone"] = result["is_deadzone"].astype(int)
    return result


# ── Data loading helpers ─────────────────────────────────────────

def load_json_points(path: str | Path) -> pd.DataFrame:
    """Load a JSON file of [{latitude, longitude, ...}] dicts."""
    path = Path(path)
    if not path.exists():
        return pd.DataFrame()
    with open(path) as f:
        data = json.load(f)
    if isinstance(data, list):
        return pd.DataFrame(data)
    return pd.DataFrame()


def load_coastline(path: str | Path) -> pd.DataFrame:
    """Load coastline points from JSON."""
    return load_json_points(path)
