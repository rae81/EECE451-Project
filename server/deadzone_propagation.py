"""Radio propagation models for dead-zone prediction.

Implements COST-231 Hata, free-space path loss, predicted RSRP,
line-of-sight obstruction scoring, and frequency band lookup tables
for Lebanese operators (Alfa / Touch).
"""
from __future__ import annotations

import math
from typing import Sequence

import numpy as np

EARTH_RADIUS_KM = 6371.0088

# ── Lebanon operator frequency defaults (MHz) ──────────────────────
# Used when the app doesn't report a frequency band.
LEBANON_BAND_DEFAULTS: dict[tuple[str, str], float] = {
    ("Alfa", "2G"): 900.0,
    ("Alfa", "3G"): 2100.0,
    ("Alfa", "4G"): 1800.0,   # Band 3
    ("Alfa", "5G"): 3500.0,
    ("Touch", "2G"): 900.0,
    ("Touch", "3G"): 2100.0,
    ("Touch", "4G"): 1800.0,  # Band 3
    ("Touch", "5G"): 3500.0,
}

# Network-type fallback when operator is unknown
NETWORK_TYPE_FREQ_DEFAULTS: dict[str, float] = {
    "2G": 900.0,
    "3G": 2100.0,
    "4G": 1800.0,
    "5G": 3500.0,
}

# Typical EIRP (dBm) per network generation
DEFAULT_EIRP_DBM: dict[str, float] = {
    "2G": 43.0,
    "3G": 43.0,
    "4G": 46.0,
    "5G": 49.0,
}

# EARFCN → MHz mapping for common LTE bands in Lebanon
_LTE_BAND_CENTER: dict[int, float] = {
    1: 2140.0,   # Band 1 (FDD 2100)
    3: 1842.5,   # Band 3 (FDD 1800) – primary for both operators
    7: 2655.0,   # Band 7 (FDD 2600)
    8: 942.5,    # Band 8 (FDD 900)
    20: 806.0,   # Band 20 (FDD 800)
}


# ── Frequency resolution ────────────────────────────────────────────

def frequency_from_band_string(
    band_str: str | None,
    network_type: str,
    operator: str = "",
) -> float:
    """Return carrier frequency in MHz from a band descriptor string.

    Handles formats like ``"3"``, ``"Band 3"``, ``"20 (800MHz)"``,
    ``"1800"``, or ``None``.  Falls back to operator/network defaults.
    """
    if band_str is not None:
        band_str = str(band_str).strip()
        if band_str:
            # Try to extract a pure number
            import re
            nums = re.findall(r"[\d.]+", band_str)
            if nums:
                val = float(nums[0])
                # If the number looks like a frequency already (> 100)
                if val > 100:
                    return val
                # Otherwise treat as LTE band number
                band_int = int(val)
                if band_int in _LTE_BAND_CENTER:
                    return _LTE_BAND_CENTER[band_int]

    # Fallback chain: operator+network → network → 1800
    key = (operator, network_type)
    if key in LEBANON_BAND_DEFAULTS:
        return LEBANON_BAND_DEFAULTS[key]
    return NETWORK_TYPE_FREQ_DEFAULTS.get(network_type, 1800.0)


# ── Path loss models ────────────────────────────────────────────────

def free_space_path_loss(distance_km: float, frequency_mhz: float) -> float:
    """Free-Space Path Loss (FSPL) in dB.

    FSPL = 20·log₁₀(d_km) + 20·log₁₀(f_MHz) + 32.44
    """
    if distance_km <= 0 or frequency_mhz <= 0:
        return 0.0
    return (
        20.0 * math.log10(distance_km)
        + 20.0 * math.log10(frequency_mhz)
        + 32.44
    )


def cost231_hata_path_loss(
    distance_km: float,
    frequency_mhz: float,
    h_base: float = 30.0,
    h_mobile: float = 1.5,
    environment: str = "urban",
) -> float:
    """COST-231 Hata path loss in dB.

    Valid range: 150–2000 MHz, 1–20 km, h_base 30–200 m, h_mobile 1–10 m.
    Extended here with clamping for practical use outside strict bounds.

    Parameters
    ----------
    distance_km : float
        Distance between transmitter and receiver in km.
    frequency_mhz : float
        Carrier frequency in MHz.
    h_base : float
        Base station effective antenna height in meters.
    h_mobile : float
        Mobile antenna height in meters.
    environment : str
        One of ``"urban"``, ``"suburban"``, ``"rural"``.

    Returns
    -------
    float
        Path loss in dB.  Returns 0.0 for degenerate inputs.
    """
    if distance_km <= 0 or frequency_mhz <= 0:
        return 0.0

    # Clamp to model validity ranges (soft extension)
    d = max(distance_km, 0.02)  # avoid log(0) for very close points
    f = max(frequency_mhz, 150.0)
    hb = max(h_base, 1.0)
    hm = max(h_mobile, 1.0)

    log_f = math.log10(f)
    log_hb = math.log10(hb)
    log_d = math.log10(d)

    # Mobile antenna correction factor a(h_m)
    if environment == "urban":
        # Large city model (f >= 400 MHz approximation)
        if f >= 400:
            a_hm = 3.2 * (math.log10(11.75 * hm)) ** 2 - 4.97
        else:
            a_hm = (1.1 * log_f - 0.7) * hm - (1.56 * log_f - 0.8)
    else:
        # Suburban / rural
        a_hm = (1.1 * log_f - 0.7) * hm - (1.56 * log_f - 0.8)

    # Metropolitan correction C_m
    if environment == "urban":
        c_m = 3.0
    else:
        c_m = 0.0

    path_loss = (
        46.3
        + 33.9 * log_f
        - 13.82 * log_hb
        - a_hm
        + (44.9 - 6.55 * log_hb) * log_d
        + c_m
    )

    # Suburban / rural corrections
    if environment == "suburban":
        path_loss -= 2.0 * (math.log10(f / 28.0)) ** 2 - 5.4
    elif environment == "rural":
        path_loss -= 4.78 * log_f ** 2 + 18.33 * log_f - 40.94

    return path_loss


def predicted_rsrp(
    distance_km: float,
    frequency_mhz: float,
    eirp_dbm: float | None = None,
    network_type: str = "4G",
    environment: str = "urban",
    h_base: float = 30.0,
    h_mobile: float = 1.5,
) -> float:
    """Predict received signal power in dBm using COST-231 Hata.

    ``RSRP_est = EIRP - PathLoss``
    """
    if eirp_dbm is None:
        eirp_dbm = DEFAULT_EIRP_DBM.get(network_type, 46.0)
    pl = cost231_hata_path_loss(distance_km, frequency_mhz, h_base, h_mobile, environment)
    if pl == 0.0:
        return -80.0  # safe default for degenerate cases
    return eirp_dbm - pl


def excess_path_loss(distance_km: float, frequency_mhz: float, **kwargs) -> float:
    """Extra loss above free-space, capturing terrain/urban effects."""
    fspl = free_space_path_loss(distance_km, frequency_mhz)
    cost = cost231_hata_path_loss(distance_km, frequency_mhz, **kwargs)
    if fspl == 0.0 or cost == 0.0:
        return 0.0
    return cost - fspl


# ── Spatial helpers ─────────────────────────────────────────────────

def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance in km between two points."""
    rlat1, rlon1, rlat2, rlon2 = map(math.radians, (lat1, lon1, lat2, lon2))
    dlat = rlat2 - rlat1
    dlon = rlon2 - rlon1
    a = math.sin(dlat / 2) ** 2 + math.cos(rlat1) * math.cos(rlat2) * math.sin(dlon / 2) ** 2
    return 2.0 * EARTH_RADIUS_KM * math.asin(math.sqrt(a))


def haversine_km_vec(
    lat1: np.ndarray, lon1: np.ndarray,
    lat2: float, lon2: float,
) -> np.ndarray:
    """Vectorised haversine – array of lats/lons vs. one point."""
    rlat1 = np.radians(lat1)
    rlon1 = np.radians(lon1)
    rlat2 = math.radians(lat2)
    rlon2 = math.radians(lon2)
    dlat = rlat2 - rlat1
    dlon = rlon2 - rlon1
    a = np.sin(dlat / 2) ** 2 + np.cos(rlat1) * math.cos(rlat2) * np.sin(dlon / 2) ** 2
    return 2.0 * EARTH_RADIUS_KM * np.arcsin(np.sqrt(a))


# ── Line-of-sight obstruction ──────────────────────────────────────

def compute_los_obstruction(
    query_lat: float,
    query_lon: float,
    tower_lat: float,
    tower_lon: float,
    dem_lats: np.ndarray,
    dem_lons: np.ndarray,
    dem_elevations: np.ndarray,
    dem_tree,  # sklearn BallTree in radians
    n_profile_points: int = 20,
    tower_height_m: float = 30.0,
    mobile_height_m: float = 1.5,
) -> float:
    """Fraction of terrain-profile points that exceed the LOS line.

    Samples ``n_profile_points`` along the great-circle path between
    the query location and the nearest tower.  At each sample point,
    the terrain elevation is looked up from the DEM via nearest-neighbour.
    A point is "obstructing" if its terrain elevation exceeds the
    straight-line height between (tower + tower_height) and
    (query + mobile_height).

    Returns a float in [0, 1].  Returns 0.0 if DEM data is unavailable.
    """
    if dem_tree is None or len(dem_elevations) == 0:
        return 0.0

    total_dist_km = haversine_km(query_lat, query_lon, tower_lat, tower_lon)
    if total_dist_km < 0.05:  # < 50 m, no meaningful obstruction
        return 0.0

    # Sample points along the path
    fracs = np.linspace(0.1, 0.9, n_profile_points)
    sample_lats = query_lat + fracs * (tower_lat - query_lat)
    sample_lons = query_lon + fracs * (tower_lon - query_lon)

    # Query DEM at each sample point
    sample_rad = np.deg2rad(np.column_stack([sample_lats, sample_lons]))
    dists, idxs = dem_tree.query(sample_rad, k=1)
    terrain_elevs = dem_elevations[idxs.ravel()]

    # Elevation at endpoints (query from DEM)
    q_rad = np.deg2rad([[query_lat, query_lon]])
    t_rad = np.deg2rad([[tower_lat, tower_lon]])
    _, q_idx = dem_tree.query(q_rad, k=1)
    _, t_idx = dem_tree.query(t_rad, k=1)
    elev_query = dem_elevations[q_idx.ravel()[0]] + mobile_height_m
    elev_tower = dem_elevations[t_idx.ravel()[0]] + tower_height_m

    # LOS line height at each sample fraction
    los_heights = elev_query + fracs * (elev_tower - elev_query)

    obstructed = np.sum(terrain_elevs > los_heights)
    return float(obstructed) / n_profile_points


# ── Environment classification ──────────────────────────────────────

def classify_environment(
    building_density_1km: float,
    road_density_1km: float,
) -> str:
    """Classify location as dense_urban / urban / suburban / rural.

    Thresholds are calibrated for OSM feature counts within 1 km radius
    in Lebanese context.
    """
    if building_density_1km > 500 and road_density_1km > 80:
        return "dense_urban"
    if building_density_1km > 100 or road_density_1km > 30:
        return "urban"
    if building_density_1km > 20 or road_density_1km > 10:
        return "suburban"
    return "rural"


def environment_for_propagation(urban_class: str) -> str:
    """Map our 4-class urban label to COST-231's 3-class scheme."""
    if urban_class in ("dense_urban", "urban"):
        return "urban"
    if urban_class == "suburban":
        return "suburban"
    return "rural"


# ── Batch propagation features ──────────────────────────────────────

def compute_propagation_features(
    lat: float,
    lon: float,
    operator: str,
    network_type: str,
    frequency_band: str | None,
    nearest_tower_lat: float,
    nearest_tower_lon: float,
    environment: str = "urban",
) -> dict[str, float]:
    """Compute all 6 propagation-physics features for one point.

    Returns a dict with keys:
        cost231_path_loss_db, cost231_predicted_rsrp,
        free_space_path_loss_db, excess_path_loss_db,
        serving_tower_distance_km, frequency_mhz
    """
    freq = frequency_from_band_string(frequency_band, network_type, operator)
    dist = haversine_km(lat, lon, nearest_tower_lat, nearest_tower_lon)
    dist = max(dist, 0.01)  # floor to 10 m

    eirp = DEFAULT_EIRP_DBM.get(network_type, 46.0)
    env = environment_for_propagation(environment) if environment not in ("urban", "suburban", "rural") else environment

    cost_pl = cost231_hata_path_loss(dist, freq, environment=env)
    fspl = free_space_path_loss(dist, freq)
    rsrp = eirp - cost_pl if cost_pl > 0 else -80.0
    excess = cost_pl - fspl if cost_pl > 0 and fspl > 0 else 0.0

    return {
        "cost231_path_loss_db": round(cost_pl, 2),
        "cost231_predicted_rsrp": round(rsrp, 2),
        "free_space_path_loss_db": round(fspl, 2),
        "excess_path_loss_db": round(excess, 2),
        "serving_tower_distance_km": round(dist, 4),
        "frequency_mhz": freq,
    }
