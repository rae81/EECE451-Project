"""Tests for deadzone_features.py — feature engineering pipeline."""
import numpy as np
import pandas as pd
import pytest

from deadzone_features import (
    ALL_FEATURE_NAMES,
    CATEGORICAL_FEATURES_V3,
    NUMERIC_FEATURES_V3,
    FeatureContext,
    build_feature_dataframe,
    build_feature_row,
)


@pytest.fixture
def ref_cells():
    """Minimal reference cells DataFrame for FeatureContext."""
    return pd.DataFrame({
        "latitude": [33.89, 33.90, 33.895, 33.91, 34.05, 34.06],
        "longitude": [35.50, 35.51, 35.505, 35.52, 35.65, 35.66],
        "operator": ["Alfa", "Alfa", "Alfa", "Touch", "Touch", "Touch"],
        "network_type": ["4G", "4G", "4G", "4G", "4G", "4G"],
        "avg_signal": [-85, -82, -88, -90, -108, -105],
        "range_m": [500, 600, 550, 450, 1200, 1100],
        "samples": [20, 15, 12, 18, 5, 7],
        "days_since_update": [10, 12, 14, 8, 30, 25],
    })


@pytest.fixture
def ctx(ref_cells):
    """Basic FeatureContext with only tower data."""
    return FeatureContext(ref_cells=ref_cells)


class TestFeatureContext:
    def test_creates_all_tree(self, ctx):
        assert ctx.all_tree is not None

    def test_creates_group_trees(self, ctx):
        assert len(ctx.group_trees) >= 1


class TestBuildFeatureRow:
    def test_returns_all_features(self, ctx):
        row = build_feature_row(33.89, 35.50, "Alfa", "4G", ctx)
        for feat in NUMERIC_FEATURES_V3:
            assert feat in row, f"Missing feature: {feat}"
        for feat in CATEGORICAL_FEATURES_V3:
            assert feat in row, f"Missing categorical: {feat}"

    def test_tower_distance_is_reasonable(self, ctx):
        row = build_feature_row(33.89, 35.50, "Alfa", "4G", ctx)
        # Should be very close to the first tower
        assert row["serving_tower_distance_km"] < 1.0

    def test_propagation_features_populated(self, ctx):
        row = build_feature_row(33.89, 35.50, "Alfa", "4G", ctx)
        assert row["cost231_predicted_rsrp"] != 0
        assert row["frequency_mhz"] > 0

    def test_density_features(self, ctx):
        row = build_feature_row(33.90, 35.51, "Alfa", "4G", ctx)
        assert row["same_group_density_1km"] >= 0
        assert row["all_operator_density_1km"] >= 0

    def test_unknown_operator_still_works(self, ctx):
        row = build_feature_row(33.89, 35.50, "Unknown", "4G", ctx)
        assert "latitude" in row


class TestBuildFeatureDataframe:
    def test_returns_correct_shape(self, ref_cells, ctx):
        df = build_feature_dataframe(ref_cells, ctx)
        assert len(df) == len(ref_cells)
        assert len(df.columns) >= len(NUMERIC_FEATURES_V3) + len(CATEGORICAL_FEATURES_V3)

    def test_all_feature_columns_present(self, ref_cells, ctx):
        df = build_feature_dataframe(ref_cells, ctx)
        for feat in ALL_FEATURE_NAMES:
            assert feat in df.columns, f"Missing column: {feat}"


class TestFeatureNames:
    def test_no_overlap(self):
        overlap = set(NUMERIC_FEATURES_V3) & set(CATEGORICAL_FEATURES_V3)
        assert not overlap, f"Overlap between numeric and categorical: {overlap}"

    def test_all_features_matches_union(self):
        combined = set(NUMERIC_FEATURES_V3) | set(CATEGORICAL_FEATURES_V3)
        assert combined == set(ALL_FEATURE_NAMES)
