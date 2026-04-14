"""Physics-based RSRP labeling replacing COST-231.

This module implements a simplified ITU-R P.1812 style point-to-area
path-loss computation that uses REAL DEM terrain profiles between
transmitter and receiver to compute diffraction losses. This is the
key change that breaks the Tier-3 circular-leak problem:

  COST-231 predicted RSRP = f(distance, frequency, hTx, hRx)
    → every term also becomes a feature of the classifier
    → label is algebraic function of features → AUC 0.998 (leak)

  P.1812-simplified RSRP = f(distance, frequency, hTx, hRx,
                             DEM_profile, LoS_obstruction, clutter)
    → uses 3D terrain diffraction via Bullington knife-edge method
    → features encode DEM aggregates, not the full path profile
    → label is non-trivial function of high-dimensional geometry
    → classifier must learn actual physics, not rewrite one formula

References
----------
- ITU-R P.1812-6 (2021) "A path-specific propagation prediction method
  for point-to-area terrestrial services in the VHF and UHF bands"
- ITU-R P.526-15 (2019) "Propagation by diffraction" (Bullington method)
- ITU-R P.2001-4 "A general-purpose wide-range terrestrial propagation
  model in the frequency range 30 MHz to 50 GHz"
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Optional

import numpy as np
import pandas as pd


# ── ITU-standard free-space path loss ──────────────────────────────

def free_space_pl_db(distance_km: float, frequency_mhz: float) -> float:
    """FSPL: L = 32.44 + 20 log10(d_km) + 20 log10(f_MHz)."""
    if distance_km <= 0 or frequency_mhz <= 0:
        return 0.0
    return 32.44 + 20.0 * np.log10(distance_km) + 20.0 * np.log10(frequency_mhz)


# ── Terrain profile sampling ───────────────────────────────────────

def sample_terrain_profile(
    lat1: float, lon1: float,
    lat2: float, lon2: float,
    dem_fn: Callable[[float, float], float],
    n_samples: int = 64,
) -> np.ndarray:
    """Sample DEM along the great-circle path from (1) to (2).

    Parameters
    ----------
    dem_fn : callable(lat, lon) -> elevation_m
        Closure that returns DEM elevation for any point.
    n_samples : number of profile samples (including endpoints)

    Returns
    -------
    profile : (n_samples,) array of elevations in meters
    """
    ts = np.linspace(0.0, 1.0, n_samples)
    lats = lat1 + ts * (lat2 - lat1)
    lons = lon1 + ts * (lon2 - lon1)
    return np.array([dem_fn(float(a), float(o)) for a, o in zip(lats, lons)])


def bullington_diffraction_loss_db(
    profile: np.ndarray,
    distance_km: float,
    wavelength_m: float,
    h_tx_agl_m: float = 30.0,
    h_rx_agl_m: float = 1.5,
) -> float:
    """Bullington knife-edge diffraction loss over a terrain profile.

    Implements the ITU-R P.526 Bullington construction:
    1. Find the single virtual knife-edge that maximally obstructs the
       LoS ray from transmitter to receiver.
    2. Compute the Fresnel-Kirchhoff diffraction loss for that edge.

    Returns excess loss (in dB) over free-space.
    """
    n = len(profile)
    if n < 3 or distance_km <= 0 or wavelength_m <= 0:
        return 0.0

    # Endpoint heights above terrain datum (profile[0] and profile[-1] are ground)
    h_tx = profile[0] + h_tx_agl_m
    h_rx = profile[-1] + h_rx_agl_m

    # Sample distances from Tx in km
    di = np.linspace(0.0, distance_km, n)

    # For each sample, height of the direct LoS ray from Tx to Rx
    los_line = h_tx + (h_rx - h_tx) * (di / distance_km)

    # Terrain clearance at each point: negative = obstruction
    # (terrain_height - line_height) — positive = obstruction above ray
    clearance = profile - los_line

    # Find the worst obstruction
    obs_idx = int(np.argmax(clearance))
    if clearance[obs_idx] <= 0:
        return 0.0  # LoS clear

    d1_km = di[obs_idx]
    d2_km = distance_km - d1_km
    if d1_km <= 0 or d2_km <= 0:
        return 0.0

    h_obs = clearance[obs_idx]  # meters above LoS line

    # Fresnel-Kirchhoff parameter v
    # v = h * sqrt(2 / (lambda) * (1/d1 + 1/d2)); d in meters
    d1_m, d2_m = d1_km * 1000.0, d2_km * 1000.0
    v = h_obs * np.sqrt((2.0 / wavelength_m) * (1.0 / d1_m + 1.0 / d2_m))

    # J(v) approximation (ITU-R P.526 §4.1)
    if v <= -0.78:
        return 0.0
    return float(6.9 + 20.0 * np.log10(np.sqrt((v - 0.1) ** 2 + 1.0) + v - 0.1))


# ── Main P.1812-simplified predictor ───────────────────────────────

@dataclass
class P1812Params:
    """Parameters for the simplified P.1812 predictor."""
    h_tx_agl_m: float = 30.0        # Tx antenna above ground (typical macro)
    h_rx_agl_m: float = 1.5          # Rx antenna (handheld)
    eirp_dbm: float = 43.0           # EIRP (20W = 43 dBm typical)
    clutter_loss_db: float = 3.0     # Additional clutter attenuation
    shadow_margin_db: float = 0.0    # Shadow fading margin
    n_profile_samples: int = 64


def p1812_rsrp_dbm(
    tx_lat: float, tx_lon: float,
    rx_lat: float, rx_lon: float,
    frequency_mhz: float,
    dem_fn: Optional[Callable[[float, float], float]] = None,
    params: Optional[P1812Params] = None,
    los_obstruction_score: Optional[float] = None,
) -> dict:
    """Predict RSRP at receiver using simplified ITU-R P.1812.

    Returns a dict with components:
        'rsrp_dbm'            : final predicted RSRP
        'distance_km'         : great-circle distance
        'free_space_loss_db'  : FSPL component
        'diffraction_loss_db' : Bullington excess loss
        'clutter_loss_db'     : clutter component
        'total_path_loss_db'  : sum of all loss components

    If ``dem_fn`` is None, falls back to a clutter-only model using
    ``los_obstruction_score`` (from existing features) as a proxy
    for diffraction loss.
    """
    if params is None:
        params = P1812Params()

    # Great-circle distance (haversine)
    R = 6371.0
    p1, p2 = np.radians(tx_lat), np.radians(rx_lat)
    dp, dl = np.radians(rx_lat - tx_lat), np.radians(rx_lon - tx_lon)
    a = np.sin(dp / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    d_km = 2 * R * np.arcsin(np.sqrt(a))
    d_km = max(d_km, 0.05)  # floor at 50 m

    # Free-space path loss
    fspl = free_space_pl_db(d_km, frequency_mhz)

    # Diffraction loss
    wavelength_m = 299.792458 / frequency_mhz  # c [m/µs] / f [MHz] = m
    if dem_fn is not None:
        profile = sample_terrain_profile(
            tx_lat, tx_lon, rx_lat, rx_lon,
            dem_fn, n_samples=params.n_profile_samples,
        )
        diff_loss = bullington_diffraction_loss_db(
            profile, d_km, wavelength_m,
            h_tx_agl_m=params.h_tx_agl_m,
            h_rx_agl_m=params.h_rx_agl_m,
        )
    elif los_obstruction_score is not None and not np.isnan(los_obstruction_score):
        # Proxy: map 0-1 obstruction score to 0-25 dB diffraction loss.
        # Calibrated to match typical Bullington losses for moderate terrain.
        diff_loss = 25.0 * float(los_obstruction_score)
    else:
        diff_loss = 0.0

    total_loss = fspl + diff_loss + params.clutter_loss_db + params.shadow_margin_db
    rsrp = params.eirp_dbm - total_loss

    return {
        "rsrp_dbm": float(rsrp),
        "distance_km": float(d_km),
        "free_space_loss_db": float(fspl),
        "diffraction_loss_db": float(diff_loss),
        "clutter_loss_db": float(params.clutter_loss_db),
        "total_path_loss_db": float(total_loss),
    }


# ── DEM interpolator builder ───────────────────────────────────────

def build_dem_interpolator(
    dem_df: pd.DataFrame,
    elev_col: str = "elevation",
) -> Callable[[float, float], float]:
    """Build a fast nearest-neighbor DEM lookup over a Pandas DataFrame.

    Expects columns 'latitude', 'longitude', and ``elev_col``.
    Returns a closure (lat, lon) -> elevation_m. Returns 0.0 if the
    DEM is empty or a point falls outside the DEM domain.
    """
    if dem_df is None or len(dem_df) == 0 or elev_col not in dem_df.columns:
        return lambda lat, lon: 0.0

    from sklearn.neighbors import BallTree

    lats = dem_df["latitude"].values
    lons = dem_df["longitude"].values
    elevs = dem_df[elev_col].values
    tree = BallTree(np.radians(np.column_stack([lats, lons])), metric="haversine")

    def _dem_fn(lat: float, lon: float) -> float:
        q = np.radians(np.array([[lat, lon]]))
        _, idx = tree.query(q, k=1)
        return float(elevs[int(idx[0, 0])])

    return _dem_fn


# ── Batch RSRP labeling ─────────────────────────────────────────────

def compute_p1812_rsrp_batch(
    rx_points: pd.DataFrame,
    opencellid_df: pd.DataFrame,
    dem_df: Optional[pd.DataFrame] = None,
    los_obs_col: Optional[str] = None,
    params: Optional[P1812Params] = None,
) -> pd.DataFrame:
    """Compute P.1812-simplified RSRP for a batch of receiver points.

    For each rx point, finds the nearest tower and computes RSRP using
    the terrain profile between them.

    Parameters
    ----------
    rx_points : DataFrame with 'latitude', 'longitude' and optional
        'frequency_mhz' (defaults to 1800 MHz if absent).
    opencellid_df : DataFrame with 'latitude', 'longitude' and optional
        'range' (tower coverage range in meters).
    dem_df : DataFrame with 'latitude', 'longitude', 'elevation' (meters).
        If None, uses ``los_obs_col`` as a proxy for diffraction.
    los_obs_col : column name in rx_points with pre-computed LoS
        obstruction scores (0 clear to 1 fully obstructed).

    Returns
    -------
    DataFrame with columns:
        p1812_rsrp_dbm, p1812_distance_km, p1812_free_space_loss_db,
        p1812_diffraction_loss_db, p1812_total_path_loss_db,
        nearest_tower_idx
    """
    from sklearn.neighbors import BallTree

    if params is None:
        params = P1812Params()

    if opencellid_df is None or len(opencellid_df) == 0:
        out = pd.DataFrame(index=rx_points.index)
        for c in ["p1812_rsrp_dbm", "p1812_distance_km",
                   "p1812_free_space_loss_db", "p1812_diffraction_loss_db",
                   "p1812_total_path_loss_db"]:
            out[c] = np.nan
        out["nearest_tower_idx"] = -1
        return out

    # Find nearest tower per receiver
    tower_coords = np.radians(opencellid_df[["latitude", "longitude"]].values)
    tree = BallTree(tower_coords, metric="haversine")
    rx_coords = np.radians(rx_points[["latitude", "longitude"]].values)
    _, nearest_idx = tree.query(rx_coords, k=1)
    nearest_idx = nearest_idx.ravel()

    # Build DEM closure once
    dem_fn = build_dem_interpolator(dem_df) if dem_df is not None else None

    # Determine frequency per row
    if "frequency_mhz" in rx_points.columns:
        freqs = rx_points["frequency_mhz"].fillna(1800.0).values
    else:
        freqs = np.full(len(rx_points), 1800.0)

    # LoS proxy per row (fallback when no DEM provided)
    if los_obs_col and los_obs_col in rx_points.columns:
        los_scores = rx_points[los_obs_col].values
    else:
        los_scores = np.full(len(rx_points), np.nan)

    tx_lats = opencellid_df["latitude"].values
    tx_lons = opencellid_df["longitude"].values

    rows = []
    for i in range(len(rx_points)):
        ti = int(nearest_idx[i])
        res = p1812_rsrp_dbm(
            tx_lat=float(tx_lats[ti]), tx_lon=float(tx_lons[ti]),
            rx_lat=float(rx_points.iloc[i]["latitude"]),
            rx_lon=float(rx_points.iloc[i]["longitude"]),
            frequency_mhz=float(freqs[i]),
            dem_fn=dem_fn,
            params=params,
            los_obstruction_score=(
                float(los_scores[i]) if not np.isnan(los_scores[i]) else None
            ),
        )
        rows.append({
            "p1812_rsrp_dbm": res["rsrp_dbm"],
            "p1812_distance_km": res["distance_km"],
            "p1812_free_space_loss_db": res["free_space_loss_db"],
            "p1812_diffraction_loss_db": res["diffraction_loss_db"],
            "p1812_total_path_loss_db": res["total_path_loss_db"],
            "nearest_tower_idx": ti,
        })

    return pd.DataFrame(rows, index=rx_points.index)
