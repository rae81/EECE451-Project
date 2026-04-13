"""Tests for deadzone_propagation.py — COST-231 Hata and utilities."""
import numpy as np
import pytest
from sklearn.neighbors import BallTree

from deadzone_propagation import (
    DEFAULT_EIRP_DBM,
    classify_environment,
    compute_los_obstruction,
    compute_propagation_features,
    cost231_hata_path_loss,
    environment_for_propagation,
    excess_path_loss,
    free_space_path_loss,
    frequency_from_band_string,
    haversine_km,
    predicted_rsrp,
)


class TestHaversine:
    def test_same_point_zero_distance(self):
        assert haversine_km(33.89, 35.50, 33.89, 35.50) == pytest.approx(0.0, abs=1e-6)

    def test_known_distance(self):
        # Beirut to Tripoli ≈ 67 km
        d = haversine_km(33.89, 35.50, 34.44, 35.83)
        assert 60 < d < 75


class TestFrequencyFromBand:
    def test_network_type_default(self):
        assert frequency_from_band_string(None, "2G") == 900
        assert frequency_from_band_string(None, "4G") == 1800

    def test_with_band_string(self):
        freq = frequency_from_band_string("3", "4G")
        assert freq > 0

    def test_unknown_band_returns_network_default(self):
        freq = frequency_from_band_string("unknown_xyz", "4G")
        assert freq > 0


class TestCost231Hata:
    def test_returns_positive_loss(self):
        loss = cost231_hata_path_loss(2.0, 1800)
        assert loss > 0

    def test_urban_greater_than_suburban(self):
        urban = cost231_hata_path_loss(2.0, 1800, environment="urban")
        suburban = cost231_hata_path_loss(2.0, 1800, environment="suburban")
        assert urban > suburban

    def test_loss_increases_with_distance(self):
        close = cost231_hata_path_loss(0.5, 1800)
        far = cost231_hata_path_loss(5.0, 1800)
        assert far > close

    def test_loss_increases_with_frequency(self):
        low = cost231_hata_path_loss(2.0, 900)
        high = cost231_hata_path_loss(2.0, 2100)
        assert high > low


class TestFreeSpacePathLoss:
    def test_known_value(self):
        # FSPL at 1 km, 1800 MHz ≈ 97.6 dB
        loss = free_space_path_loss(1.0, 1800)
        assert 95 < loss < 100


class TestPredictedRSRP:
    def test_higher_distance_lower_rsrp(self):
        close = predicted_rsrp(0.5, 1800)
        far = predicted_rsrp(5.0, 1800)
        assert close > far

    def test_uses_default_eirp(self):
        rsrp = predicted_rsrp(2.0, 1800)
        assert -200 < rsrp < 0


class TestExcessPathLoss:
    def test_excess_is_non_negative(self):
        ep = excess_path_loss(2.0, 1800)
        assert ep >= 0


class TestClassifyEnvironment:
    def test_dense_urban(self):
        env = classify_environment(building_density_1km=600, road_density_1km=100)
        assert env == "dense_urban"

    def test_urban(self):
        env = classify_environment(building_density_1km=200, road_density_1km=20)
        assert env == "urban"

    def test_rural(self):
        env = classify_environment(building_density_1km=0, road_density_1km=0)
        assert env == "rural"


class TestEnvironmentForPropagation:
    def test_maps_dense_urban(self):
        assert environment_for_propagation("dense_urban") == "urban"

    def test_maps_rural(self):
        assert environment_for_propagation("rural") == "rural"


class TestComputeLosObstruction:
    def test_no_dem_returns_zero(self):
        score = compute_los_obstruction(
            33.89, 35.50, 33.90, 35.51,
            dem_lats=np.array([]), dem_lons=np.array([]),
            dem_elevations=np.array([]), dem_tree=None,
        )
        assert score == 0.0

    def test_flat_terrain_no_obstruction(self):
        lats = np.linspace(33.89, 33.91, 50)
        lons = np.linspace(35.50, 35.52, 50)
        elevs = np.full(50, 100.0)
        tree = BallTree(np.deg2rad(np.column_stack([lats, lons])), metric="haversine")
        score = compute_los_obstruction(
            33.89, 35.50, 33.91, 35.52,
            dem_lats=lats, dem_lons=lons, dem_elevations=elevs, dem_tree=tree,
        )
        assert score == pytest.approx(0.0)

    def test_mountain_between_causes_obstruction(self):
        lats = np.linspace(33.89, 33.91, 50)
        lons = np.linspace(35.50, 35.52, 50)
        elevs = np.full(50, 100.0)
        elevs[20:30] = 500.0  # mountain in the middle
        tree = BallTree(np.deg2rad(np.column_stack([lats, lons])), metric="haversine")
        score = compute_los_obstruction(
            33.89, 35.50, 33.91, 35.52,
            dem_lats=lats, dem_lons=lons, dem_elevations=elevs, dem_tree=tree,
        )
        assert score > 0


class TestComputePropagationFeatures:
    def test_returns_expected_keys(self):
        result = compute_propagation_features(
            lat=33.89, lon=35.50,
            operator="Alfa", network_type="4G",
            frequency_band=None,
            nearest_tower_lat=33.90, nearest_tower_lon=35.51,
        )
        assert "cost231_path_loss_db" in result
        assert "cost231_predicted_rsrp" in result
        assert "free_space_path_loss_db" in result
        assert "excess_path_loss_db" in result
        assert "serving_tower_distance_km" in result
        assert "frequency_mhz" in result

    def test_close_tower_low_loss(self):
        result = compute_propagation_features(
            lat=33.89, lon=35.50,
            operator="Alfa", network_type="4G",
            frequency_band=None,
            nearest_tower_lat=33.8905, nearest_tower_lon=35.5005,
        )
        assert result["cost231_path_loss_db"] < 150
        assert result["cost231_predicted_rsrp"] > -120
