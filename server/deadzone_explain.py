"""SHAP-based explainability for dead-zone predictions.

Generates human-readable reason strings from SHAP TreeExplainer values.
Used to populate the ``reasons`` field in ``DeadzonePrediction``.
"""
from __future__ import annotations

import numpy as np

try:
    import shap
    HAS_SHAP = True
except ImportError:
    shap = None  # type: ignore[assignment]
    HAS_SHAP = False


# ── Human-readable templates per feature ────────────────────────────

REASON_TEMPLATES: dict[str, str] = {
    # Propagation
    "cost231_predicted_rsrp": "physics model predicts {value:.0f} dBm signal ({direction} risk)",
    "cost231_path_loss_db": "path loss of {value:.0f} dB to nearest tower ({direction} risk)",
    "serving_tower_distance_km": "{value:.1f} km from nearest tower ({direction} risk)",
    "free_space_path_loss_db": "free-space loss of {value:.0f} dB ({direction} risk)",
    "excess_path_loss_db": "{value:.0f} dB extra loss from terrain/buildings ({direction} risk)",
    "frequency_mhz": "carrier frequency of {value:.0f} MHz ({direction} risk)",

    # Tower topology
    "same_group_density_1km": "{value:.0f} towers within 1 km ({direction} risk)",
    "same_group_density_3km": "{value:.0f} towers within 3 km ({direction} risk)",
    "same_group_nearest_distance_km": "nearest tower {value:.2f} km away ({direction} risk)",
    "all_operator_density_1km": "{value:.0f} total towers within 1 km ({direction} risk)",
    "tower_range_m": "tower range of {value:.0f} m ({direction} risk)",
    "tower_samples": "{value:.0f} measurement samples at nearest tower ({direction} risk)",
    "days_since_update": "tower data {value:.0f} days old ({direction} risk)",
    "same_group_mean_signal_5": "nearby towers avg signal {value:.0f} dBm ({direction} risk)",
    "same_group_mean_range_5": "nearby towers avg range {value:.0f} m ({direction} risk)",
    "cell_id_count_h3_res7": "{value:.0f} unique cells in area ({direction} risk)",

    # Terrain
    "terrain_elevation_m": "elevation of {value:.0f} m ({direction} risk)",
    "terrain_relief_3km_m": "terrain relief of {value:.0f} m in 3 km ({direction} risk)",
    "terrain_slope_deg": "terrain slope of {value:.1f}° ({direction} risk)",
    "terrain_relative_height_m": "{value:+.0f} m relative to surroundings ({direction} risk)",
    "los_obstruction_score": "{value:.0%} of path to tower obstructed ({direction} risk)",

    # Ookla
    "ookla_avg_down_kbps": "nearby speed tests show {value:.0f} kbps download ({direction} risk)",
    "ookla_avg_up_kbps": "nearby speed tests show {value:.0f} kbps upload ({direction} risk)",
    "ookla_avg_latency_ms": "nearby speed test latency {value:.0f} ms ({direction} risk)",
    "ookla_tests": "{value:.0f} speed tests nearby ({direction} risk)",
    "ookla_devices": "{value:.0f} devices tested nearby ({direction} risk)",
    "ookla_distance_km": "{value:.1f} km from nearest speed test ({direction} risk)",

    # Urban
    "osm_building_density_1km": "{value:.0f} buildings within 1 km ({direction} risk)",
    "osm_road_density_1km": "{value:.0f} road segments within 1 km ({direction} risk)",
    "osm_telecom_density_1km": "{value:.0f} telecom structures within 1 km ({direction} risk)",
    "osm_telecom_nearest_distance_km": "nearest telecom structure {value:.2f} km away ({direction} risk)",

    # Spatial
    "distance_to_coast_km": "{value:.1f} km from coast ({direction} risk)",
    "latitude": "latitude {value:.3f} ({direction} risk)",
    "longitude": "longitude {value:.3f} ({direction} risk)",

    # App aggregates
    "app_mean_signal_h3_res9": "app measurements show avg {value:.0f} dBm here ({direction} risk)",
    "app_std_signal_h3_res9": "signal variability of {value:.1f} dBm here ({direction} risk)",
    "app_sample_count_h3_res9": "{value:.0f} app measurements in this area ({direction} risk)",
    "app_mean_snr_h3_res9": "app measurements show avg SNR {value:.1f} dB ({direction} risk)",
    "app_deadzone_fraction_h3_res9": "{value:.0%} of app measurements were weak ({direction} risk)",
    "app_data_available": "app data {'available' if value else 'not available'} ({direction} risk)",

    # Stacking feature
    "signal_pred_oof": "predicted signal of {value:.0f} dBm ({direction} risk)",
}

# Fallback template for unknown features
_DEFAULT_TEMPLATE = "{feature} = {value:.2f} ({direction} risk)"


# ── Reason-string builders ────────────────────────────────────────────

def _format_reason(feature: str, value: float, shap_val: float) -> str:
    """Format a single SHAP-based reason string."""
    direction = "increases" if shap_val > 0 else "decreases"
    template = REASON_TEMPLATES.get(feature, _DEFAULT_TEMPLATE)
    try:
        return template.format(value=value, direction=direction, feature=feature)
    except (ValueError, KeyError):
        return f"{feature} = {value:.2f} ({direction} risk)"


# ── Public SHAP entry point ───────────────────────────────────────────

def compute_shap_reasons(
    classifier_pipeline,
    feature_row_transformed: np.ndarray,
    feature_names: list[str],
    top_k: int = 3,
) -> list[str]:
    """Compute top-k SHAP-based reason strings for a single prediction.

    Parameters
    ----------
    classifier_pipeline : sklearn Pipeline with a tree-based final step
    feature_row_transformed : 1-row numpy array AFTER preprocessing
    feature_names : list of feature names matching the transformed columns
    top_k : number of top contributing features to report

    Returns
    -------
    list of human-readable reason strings
    """
    if not HAS_SHAP:
        return _fallback_reasons(feature_names, feature_row_transformed, top_k)

    try:
        # Extract the final estimator from the pipeline
        estimator = classifier_pipeline
        if hasattr(estimator, "named_steps"):
            # Get the last step
            steps = list(estimator.named_steps.values())
            estimator = steps[-1]

        explainer = shap.TreeExplainer(estimator)
        shap_values = explainer.shap_values(feature_row_transformed)

        # Handle different SHAP output formats
        if isinstance(shap_values, list):
            # Binary classification: [class_0, class_1]
            sv = shap_values[1] if len(shap_values) > 1 else shap_values[0]
        else:
            sv = shap_values

        if sv.ndim > 1:
            sv = sv[0]

        # Get top-k by absolute SHAP value
        top_indices = np.argsort(np.abs(sv))[-top_k:][::-1]

        reasons = []
        for idx in top_indices:
            if idx < len(feature_names):
                feat_name = feature_names[idx]
                feat_val = float(feature_row_transformed[0, idx]) if feature_row_transformed.ndim > 1 else float(feature_row_transformed[idx])
                reasons.append(_format_reason(feat_name, feat_val, sv[idx]))

        return reasons if reasons else _fallback_reasons(feature_names, feature_row_transformed, top_k)

    except Exception:
        return _fallback_reasons(feature_names, feature_row_transformed, top_k)


# ── Fallback for when SHAP fails or isn't available ───────────────────

def _fallback_reasons(
    feature_names: list[str],
    feature_row: np.ndarray,
    top_k: int = 3,
) -> list[str]:
    """Generate simple heuristic reasons when SHAP is unavailable."""
    reasons = []
    row = feature_row[0] if feature_row.ndim > 1 else feature_row

    # Check key features by name
    priority_features = [
        "cost231_predicted_rsrp",
        "serving_tower_distance_km",
        "same_group_density_1km",
        "terrain_elevation_m",
        "ookla_avg_down_kbps",
        "los_obstruction_score",
    ]

    for feat in priority_features:
        if feat in feature_names and len(reasons) < top_k:
            idx = feature_names.index(feat)
            if idx < len(row):
                val = float(row[idx])
                if not np.isnan(val):
                    # Simple heuristic direction
                    if feat == "cost231_predicted_rsrp":
                        direction = "increases" if val < -100 else "decreases"
                    elif feat == "serving_tower_distance_km":
                        direction = "increases" if val > 2.0 else "decreases"
                    elif feat == "same_group_density_1km":
                        direction = "decreases" if val > 2 else "increases"
                    else:
                        direction = "increases"
                    reasons.append(_format_reason(feat, val, 1.0 if direction == "increases" else -1.0))

    return reasons[:top_k]
