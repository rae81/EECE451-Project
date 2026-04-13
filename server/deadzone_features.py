"""Feature engineering pipeline for dead-zone prediction v3.

Computes 43 features from tower topology, radio propagation physics,
terrain, urban context, Ookla performance, and app-collected aggregates.
Supports both single-point inference and batch training computation.
"""
from __future__ import annotations

import math
from typing import Any

import numpy as np
import pandas as pd

try:
    import h3
except ImportError:  # allow tests to run without h3 installed
    h3 = None  # type: ignore[assignment]

from sklearn.neighbors import BallTree

from deadzone_propagation import (
    EARTH_RADIUS_KM,
    classify_environment,
    compute_los_obstruction,
    compute_propagation_features,
    environment_for_propagation,
    frequency_from_band_string,
    haversine_km,
    haversine_km_vec,
)


# ── Feature name constants ──────────────────────────────────────────

NUMERIC_FEATURES_V3 = [
    # Spatial / Location (3 numeric + 2 categorical H3)
    "latitude",
    "longitude",
    "distance_to_coast_km",
    # Tower Topology (10)
    "same_group_density_1km",
    "same_group_density_3km",
    "same_group_nearest_distance_km",
    "same_group_mean_signal_5",
    "same_group_mean_range_5",
    "all_operator_density_1km",
    "tower_range_m",
    "tower_samples",
    "days_since_update",
    "cell_id_count_h3_res7",
    # Propagation Physics (6)
    "cost231_path_loss_db",
    "cost231_predicted_rsrp",
    "free_space_path_loss_db",
    "excess_path_loss_db",
    "serving_tower_distance_km",
    "frequency_mhz",
    # Terrain / DEM (5)
    "terrain_elevation_m",
    "terrain_relief_3km_m",
    "terrain_slope_deg",
    "terrain_relative_height_m",
    "los_obstruction_score",
    # Ookla Performance (6)
    "ookla_avg_down_kbps",
    "ookla_avg_up_kbps",
    "ookla_avg_latency_ms",
    "ookla_tests",
    "ookla_devices",
    "ookla_distance_km",
    # OSM Urban Context (3 numeric + 1 categorical)
    "osm_telecom_density_1km",
    "osm_telecom_nearest_distance_km",
    "osm_building_density_1km",
    "osm_road_density_1km",
    # App-Collected Aggregates (6)
    "app_mean_signal_h3_res9",
    "app_std_signal_h3_res9",
    "app_sample_count_h3_res9",
    "app_mean_snr_h3_res9",
    "app_deadzone_fraction_h3_res9",
    "app_data_available",
]

CATEGORICAL_FEATURES_V3 = [
    "operator",
    "network_type",
    "h3_res7",
    "h3_res9",
    "urban_class",
]

ALL_FEATURE_NAMES = NUMERIC_FEATURES_V3 + CATEGORICAL_FEATURES_V3


# ── H3 helpers ──────────────────────────────────────────────────────

def h3_index(lat: float, lon: float, resolution: int) -> str:
    """Return H3 cell index, or empty string if h3 not installed."""
    if h3 is None:
        return ""
    return h3.latlng_to_cell(lat, lon, resolution)


# ── Spatial tree builders ───────────────────────────────────────────

def build_ball_tree(lats: np.ndarray, lons: np.ndarray) -> BallTree:
    """Build a BallTree from lat/lon arrays (haversine, radians)."""
    coords = np.deg2rad(np.column_stack([lats, lons]))
    return BallTree(coords, metric="haversine")


# ── Tower topology features ─────────────────────────────────────────

def _query_tree_radius(tree: BallTree, lat: float, lon: float, radius_km: float) -> np.ndarray:
    """Return indices of points within radius_km of (lat, lon)."""
    q = np.deg2rad([[lat, lon]])
    radius_rad = radius_km / EARTH_RADIUS_KM
    indices = tree.query_radius(q, r=radius_rad)[0]
    return indices


def compute_tower_topology_features(
    lat: float,
    lon: float,
    operator: str,
    network_type: str,
    ref_cells: pd.DataFrame,
    group_tree: BallTree | None,
    all_tree: BallTree | None,
    group_mask: np.ndarray | None = None,
) -> dict[str, float]:
    """Compute tower topology features for a single point.

    Parameters
    ----------
    ref_cells : DataFrame with columns latitude, longitude, avg_signal,
                range_m, samples, days_since_update, operator, network_type
    group_tree : BallTree built from same-operator+network_type rows
    all_tree : BallTree built from ALL reference cells
    group_mask : boolean mask into ref_cells for same operator+network
    """
    result: dict[str, float] = {
        "same_group_density_1km": 0.0,
        "same_group_density_3km": 0.0,
        "same_group_nearest_distance_km": 50.0,
        "same_group_mean_signal_5": -100.0,
        "same_group_mean_range_5": 1000.0,
        "all_operator_density_1km": 0.0,
        "tower_range_m": 1000.0,
        "tower_samples": 0.0,
        "days_since_update": 365.0,
    }

    if group_tree is not None and group_mask is not None:
        group_cells = ref_cells.loc[group_mask]
        q = np.deg2rad([[lat, lon]])

        # Density within 1 km
        idx_1km = group_tree.query_radius(q, r=1.0 / EARTH_RADIUS_KM)[0]
        result["same_group_density_1km"] = float(len(idx_1km))

        # Density within 3 km
        idx_3km = group_tree.query_radius(q, r=3.0 / EARTH_RADIUS_KM)[0]
        result["same_group_density_3km"] = float(len(idx_3km))

        # Nearest distance + top-5 stats
        dists, idxs = group_tree.query(q, k=min(5, len(group_cells)))
        dists_km = dists[0] * EARTH_RADIUS_KM
        if len(dists_km) > 0:
            result["same_group_nearest_distance_km"] = float(dists_km[0])
            nearest_rows = group_cells.iloc[idxs[0]]
            if "avg_signal" in nearest_rows.columns:
                vals = nearest_rows["avg_signal"].values
                valid = vals[~np.isnan(vals)]
                if len(valid) > 0:
                    result["same_group_mean_signal_5"] = float(np.mean(valid))
            if "range_m" in nearest_rows.columns:
                vals = nearest_rows["range_m"].values
                valid = vals[~np.isnan(vals)]
                if len(valid) > 0:
                    result["same_group_mean_range_5"] = float(np.mean(valid))

            # Nearest tower details
            nearest = nearest_rows.iloc[0]
            result["tower_range_m"] = float(nearest.get("range_m", 1000.0) or 1000.0)
            result["tower_samples"] = float(nearest.get("samples", 0.0) or 0.0)
            result["days_since_update"] = float(nearest.get("days_since_update", 365.0) or 365.0)

    if all_tree is not None:
        q = np.deg2rad([[lat, lon]])
        idx_all_1km = all_tree.query_radius(q, r=1.0 / EARTH_RADIUS_KM)[0]
        result["all_operator_density_1km"] = float(len(idx_all_1km))

    return result


# ── Terrain features ────────────────────────────────────────────────

def compute_terrain_features(
    lat: float,
    lon: float,
    dem_lats: np.ndarray | None,
    dem_lons: np.ndarray | None,
    dem_elevations: np.ndarray | None,
    dem_tree: BallTree | None,
) -> dict[str, float]:
    """Compute terrain features from a DEM grid."""
    result = {
        "terrain_elevation_m": 0.0,
        "terrain_relief_3km_m": 0.0,
        "terrain_slope_deg": 0.0,
        "terrain_relative_height_m": 0.0,
    }

    if dem_tree is None or dem_elevations is None or len(dem_elevations) == 0:
        return result

    q = np.deg2rad([[lat, lon]])

    # Elevation at query point (nearest neighbor)
    _, idx = dem_tree.query(q, k=1)
    result["terrain_elevation_m"] = float(dem_elevations[idx.ravel()[0]])

    # Points within 3 km for relief and relative height
    radius_3km = 3.0 / EARTH_RADIUS_KM
    nearby_idx = dem_tree.query_radius(q, r=radius_3km)[0]

    if len(nearby_idx) > 1:
        nearby_elev = dem_elevations[nearby_idx]
        result["terrain_relief_3km_m"] = float(np.max(nearby_elev) - np.min(nearby_elev))
        result["terrain_relative_height_m"] = result["terrain_elevation_m"] - float(np.mean(nearby_elev))

    # Points within 1 km for slope estimate
    radius_1km = 1.0 / EARTH_RADIUS_KM
    slope_idx = dem_tree.query_radius(q, r=radius_1km)[0]

    if len(slope_idx) >= 3:
        slope_lats = dem_lats[slope_idx]
        slope_lons = dem_lons[slope_idx]
        slope_elev = dem_elevations[slope_idx]

        # Estimate slope from elevation gradient
        if np.std(slope_lats) > 0 or np.std(slope_lons) > 0:
            # Compute distances from center in meters
            dx = haversine_km_vec(
                np.full_like(slope_lats, lat), slope_lons, lat, lon
            ) * 1000.0
            dy = haversine_km_vec(
                slope_lats, np.full_like(slope_lons, lon), lat, lon
            ) * 1000.0
            dists_m = np.sqrt(dx ** 2 + dy ** 2)
            dists_m = np.maximum(dists_m, 1.0)  # avoid division by zero

            elev_diffs = np.abs(slope_elev - result["terrain_elevation_m"])
            slopes = np.degrees(np.arctan2(elev_diffs, dists_m))
            result["terrain_slope_deg"] = float(np.mean(slopes))

    return result


# ── Ookla features ──────────────────────────────────────────────────

def compute_ookla_features(
    lat: float,
    lon: float,
    ookla_df: pd.DataFrame | None,
    ookla_tree: BallTree | None,
) -> dict[str, float]:
    """IDW-interpolated Ookla features from nearest tiles."""
    result = {
        "ookla_avg_down_kbps": np.nan,
        "ookla_avg_up_kbps": np.nan,
        "ookla_avg_latency_ms": np.nan,
        "ookla_tests": np.nan,
        "ookla_devices": np.nan,
        "ookla_distance_km": np.nan,
    }

    if ookla_tree is None or ookla_df is None or len(ookla_df) == 0:
        return result

    q = np.deg2rad([[lat, lon]])
    k = min(5, len(ookla_df))
    dists, idxs = ookla_tree.query(q, k=k)
    dists_km = dists[0] * EARTH_RADIUS_KM

    result["ookla_distance_km"] = float(dists_km[0])

    if dists_km[0] > 20.0:
        return result  # too far, no meaningful data

    # IDW weighting
    weights = 1.0 / np.maximum(dists_km, 0.01)
    weights /= weights.sum()

    rows = ookla_df.iloc[idxs[0]]
    for col, key in [
        ("avg_d_kbps", "ookla_avg_down_kbps"),
        ("avg_u_kbps", "ookla_avg_up_kbps"),
        ("avg_lat_ms", "ookla_avg_latency_ms"),
        ("tests", "ookla_tests"),
        ("devices", "ookla_devices"),
    ]:
        if col in rows.columns:
            vals = rows[col].values.astype(float)
            valid = ~np.isnan(vals)
            if valid.any():
                w = weights[valid]
                result[key] = float(np.average(vals[valid], weights=w))

    return result


# ── OSM urban context features ──────────────────────────────────────

def compute_urban_features(
    lat: float,
    lon: float,
    osm_telecom_tree: BallTree | None,
    osm_buildings_tree: BallTree | None,
    osm_roads_tree: BallTree | None,
    n_telecom: int = 0,
    n_buildings: int = 0,
    n_roads: int = 0,
) -> dict[str, Any]:
    """Compute OSM-derived urban context features."""
    result: dict[str, Any] = {
        "osm_telecom_density_1km": 0.0,
        "osm_telecom_nearest_distance_km": 50.0,
        "osm_building_density_1km": 0.0,
        "osm_road_density_1km": 0.0,
        "urban_class": "rural",
    }
    q = np.deg2rad([[lat, lon]])
    radius_1km = 1.0 / EARTH_RADIUS_KM

    if osm_telecom_tree is not None and n_telecom > 0:
        idx = osm_telecom_tree.query_radius(q, r=radius_1km)[0]
        result["osm_telecom_density_1km"] = float(len(idx))
        dists, _ = osm_telecom_tree.query(q, k=1)
        result["osm_telecom_nearest_distance_km"] = float(dists[0][0] * EARTH_RADIUS_KM)

    if osm_buildings_tree is not None and n_buildings > 0:
        idx = osm_buildings_tree.query_radius(q, r=radius_1km)[0]
        result["osm_building_density_1km"] = float(len(idx))

    if osm_roads_tree is not None and n_roads > 0:
        idx = osm_roads_tree.query_radius(q, r=radius_1km)[0]
        result["osm_road_density_1km"] = float(len(idx))

    result["urban_class"] = classify_environment(
        result["osm_building_density_1km"],
        result["osm_road_density_1km"],
    )

    return result


# ── Coastline distance ──────────────────────────────────────────────

def compute_coast_distance(
    lat: float,
    lon: float,
    coast_tree: BallTree | None,
) -> float:
    """Distance in km to nearest coastline point."""
    if coast_tree is None:
        return 20.0  # default mid-Lebanon
    q = np.deg2rad([[lat, lon]])
    dists, _ = coast_tree.query(q, k=1)
    return float(dists[0][0] * EARTH_RADIUS_KM)


# ── App aggregate features ──────────────────────────────────────────

def compute_app_aggregate_features(
    h3_res9: str,
    h3_aggregates: dict[str, dict] | pd.DataFrame | None,
) -> dict[str, float]:
    """Look up precomputed app measurement aggregates for an H3 cell."""
    result = {
        "app_mean_signal_h3_res9": np.nan,
        "app_std_signal_h3_res9": np.nan,
        "app_sample_count_h3_res9": 0.0,
        "app_mean_snr_h3_res9": np.nan,
        "app_deadzone_fraction_h3_res9": np.nan,
        "app_data_available": 0.0,
    }

    if h3_aggregates is None or not h3_res9:
        return result

    # Support both dict and DataFrame
    if isinstance(h3_aggregates, pd.DataFrame):
        if h3_res9 in h3_aggregates.index:
            row = h3_aggregates.loc[h3_res9]
            result.update({
                "app_mean_signal_h3_res9": float(row.get("mean_signal", np.nan)),
                "app_std_signal_h3_res9": float(row.get("std_signal", np.nan)),
                "app_sample_count_h3_res9": float(row.get("sample_count", 0)),
                "app_mean_snr_h3_res9": float(row.get("mean_snr", np.nan)),
                "app_deadzone_fraction_h3_res9": float(row.get("deadzone_fraction", np.nan)),
                "app_data_available": 1.0,
            })
    elif isinstance(h3_aggregates, dict):
        if h3_res9 in h3_aggregates:
            agg = h3_aggregates[h3_res9]
            result.update({
                "app_mean_signal_h3_res9": float(agg.get("mean_signal", np.nan)),
                "app_std_signal_h3_res9": float(agg.get("std_signal", np.nan)),
                "app_sample_count_h3_res9": float(agg.get("sample_count", 0)),
                "app_mean_snr_h3_res9": float(agg.get("mean_snr", np.nan)),
                "app_deadzone_fraction_h3_res9": float(agg.get("deadzone_fraction", np.nan)),
                "app_data_available": 1.0,
            })

    return result


# ── Full feature row (single-point inference) ───────────────────────

class FeatureContext:
    """Holds all precomputed trees, DataFrames, and lookups needed to
    compute features for a single prediction point.

    Initialised once when the model bundle is loaded; reused across requests.
    """

    def __init__(
        self,
        ref_cells: pd.DataFrame,
        ookla_df: pd.DataFrame | None = None,
        dem_lats: np.ndarray | None = None,
        dem_lons: np.ndarray | None = None,
        dem_elevations: np.ndarray | None = None,
        osm_telecom_df: pd.DataFrame | None = None,
        osm_buildings_df: pd.DataFrame | None = None,
        osm_roads_df: pd.DataFrame | None = None,
        coast_df: pd.DataFrame | None = None,
        h3_aggregates: dict | pd.DataFrame | None = None,
    ):
        self.ref_cells = ref_cells
        self.h3_aggregates = h3_aggregates

        # DEM arrays
        self.dem_lats = dem_lats
        self.dem_lons = dem_lons
        self.dem_elevations = dem_elevations

        # Build BallTrees
        self.all_tree = build_ball_tree(
            ref_cells["latitude"].values, ref_cells["longitude"].values
        ) if len(ref_cells) > 0 else None

        # Group trees: keyed by (operator, network_type)
        self.group_trees: dict[tuple[str, str], tuple[BallTree, np.ndarray]] = {}
        for (op, nt), grp in ref_cells.groupby(["operator", "network_type"]):
            if len(grp) >= 3:
                mask = (ref_cells["operator"] == op) & (ref_cells["network_type"] == nt)
                tree = build_ball_tree(grp["latitude"].values, grp["longitude"].values)
                self.group_trees[(op, nt)] = (tree, mask.values)

        # Ookla tree
        self.ookla_df = ookla_df
        self.ookla_tree = None
        if ookla_df is not None and len(ookla_df) > 0:
            self.ookla_tree = build_ball_tree(
                ookla_df["latitude"].values, ookla_df["longitude"].values
            )

        # DEM tree
        self.dem_tree = None
        if dem_lats is not None and len(dem_lats) > 0:
            self.dem_tree = build_ball_tree(dem_lats, dem_lons)

        # OSM trees
        self.osm_telecom_tree = None
        self.n_telecom = 0
        if osm_telecom_df is not None and len(osm_telecom_df) > 0:
            self.osm_telecom_tree = build_ball_tree(
                osm_telecom_df["latitude"].values, osm_telecom_df["longitude"].values
            )
            self.n_telecom = len(osm_telecom_df)

        self.osm_buildings_tree = None
        self.n_buildings = 0
        if osm_buildings_df is not None and len(osm_buildings_df) > 0:
            self.osm_buildings_tree = build_ball_tree(
                osm_buildings_df["latitude"].values, osm_buildings_df["longitude"].values
            )
            self.n_buildings = len(osm_buildings_df)

        self.osm_roads_tree = None
        self.n_roads = 0
        if osm_roads_df is not None and len(osm_roads_df) > 0:
            self.osm_roads_tree = build_ball_tree(
                osm_roads_df["latitude"].values, osm_roads_df["longitude"].values
            )
            self.n_roads = len(osm_roads_df)

        # Coast tree
        self.coast_tree = None
        if coast_df is not None and len(coast_df) > 0:
            self.coast_tree = build_ball_tree(
                coast_df["latitude"].values, coast_df["longitude"].values
            )

    def get_group_tree(self, operator: str, network_type: str):
        """Return (BallTree, mask) for a specific operator+network, or None."""
        return self.group_trees.get((operator, network_type))


def build_feature_row(
    lat: float,
    lon: float,
    operator: str,
    network_type: str,
    ctx: FeatureContext,
    frequency_band: str | None = None,
) -> dict[str, Any]:
    """Compute all 43 features for a single prediction point.

    Returns a flat dict suitable for creating a 1-row DataFrame.
    """
    features: dict[str, Any] = {}

    # ── Spatial / Location ──
    features["latitude"] = lat
    features["longitude"] = lon
    res7 = h3_index(lat, lon, 7)
    res9 = h3_index(lat, lon, 9)
    features["h3_res7"] = res7
    features["h3_res9"] = res9
    features["distance_to_coast_km"] = compute_coast_distance(lat, lon, ctx.coast_tree)

    # ── Tower Topology ──
    group_info = ctx.get_group_tree(operator, network_type)
    group_tree = group_info[0] if group_info else None
    group_mask = group_info[1] if group_info else None

    topo = compute_tower_topology_features(
        lat, lon, operator, network_type,
        ctx.ref_cells, group_tree, ctx.all_tree, group_mask,
    )
    features.update(topo)

    # Cell ID count from app data in H3 res7 hex
    features["cell_id_count_h3_res7"] = 0.0
    if ctx.h3_aggregates is not None and res7:
        if isinstance(ctx.h3_aggregates, dict) and res7 in ctx.h3_aggregates:
            features["cell_id_count_h3_res7"] = float(
                ctx.h3_aggregates[res7].get("cell_id_count", 0)
            )

    # ── Propagation Physics ──
    # Find nearest tower location for propagation calc
    nearest_tower_lat, nearest_tower_lon = lat, lon  # fallback
    if group_tree is not None and group_mask is not None:
        q = np.deg2rad([[lat, lon]])
        _, idx = group_tree.query(q, k=1)
        group_cells = ctx.ref_cells.loc[group_mask]
        nearest = group_cells.iloc[idx[0][0]]
        nearest_tower_lat = float(nearest["latitude"])
        nearest_tower_lon = float(nearest["longitude"])
    elif ctx.all_tree is not None:
        q = np.deg2rad([[lat, lon]])
        _, idx = ctx.all_tree.query(q, k=1)
        nearest = ctx.ref_cells.iloc[idx[0][0]]
        nearest_tower_lat = float(nearest["latitude"])
        nearest_tower_lon = float(nearest["longitude"])

    # Use urban_class for environment classification
    urban_feats = compute_urban_features(
        lat, lon,
        ctx.osm_telecom_tree, ctx.osm_buildings_tree, ctx.osm_roads_tree,
        ctx.n_telecom, ctx.n_buildings, ctx.n_roads,
    )
    features.update(urban_feats)

    env = environment_for_propagation(features["urban_class"])
    prop = compute_propagation_features(
        lat, lon, operator, network_type, frequency_band,
        nearest_tower_lat, nearest_tower_lon, env,
    )
    features.update(prop)

    # ── LOS Obstruction ──
    features["los_obstruction_score"] = compute_los_obstruction(
        lat, lon, nearest_tower_lat, nearest_tower_lon,
        ctx.dem_lats, ctx.dem_lons, ctx.dem_elevations, ctx.dem_tree,
    )

    # ── Terrain ──
    terrain = compute_terrain_features(
        lat, lon, ctx.dem_lats, ctx.dem_lons, ctx.dem_elevations, ctx.dem_tree,
    )
    features.update(terrain)

    # ── Ookla ──
    ookla = compute_ookla_features(lat, lon, ctx.ookla_df, ctx.ookla_tree)
    features.update(ookla)

    # ── App aggregates ──
    app_feats = compute_app_aggregate_features(res9, ctx.h3_aggregates)
    features.update(app_feats)

    # ── Categorical ──
    features["operator"] = operator
    features["network_type"] = network_type

    return features


def build_feature_dataframe(
    dataset: pd.DataFrame,
    ctx: FeatureContext,
) -> pd.DataFrame:
    """Batch-compute all features for a training dataset.

    ``dataset`` must have columns: latitude, longitude, operator,
    network_type.  Optional: frequency_band.

    Returns a DataFrame with all 43 feature columns.
    """
    rows = []
    total = len(dataset)
    for i, (_, row) in enumerate(dataset.iterrows()):
        feat = build_feature_row(
            lat=float(row["latitude"]),
            lon=float(row["longitude"]),
            operator=str(row["operator"]),
            network_type=str(row["network_type"]),
            ctx=ctx,
            frequency_band=row.get("frequency_band"),
        )
        rows.append(feat)
        if (i + 1) % 500 == 0:
            print(f"  Features: {i+1}/{total}", end="\r")

    if total > 500:
        print()

    return pd.DataFrame(rows)
