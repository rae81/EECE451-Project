"""Lebanon land-polygon used to mask dead-zone prediction grids.

The Android app sends a rectangular grid of prediction candidates covering
Lebanon's bounding box, which leaks into the Mediterranean, Syria, Israel
and the Golan — places where the LightGBM model has no towers and no
training data, so any score it emits there is nonsense.

This module defines a ~30-vertex simplified polygon that hugs Lebanon's
actual border (coast on the west, political borders on the east/south)
plus a point-in-polygon test so the server can drop off-country grid
points before running them through the model.

Polygon vertices were hand-picked from OpenStreetMap's administrative
boundary (relation 184843) — coarse enough to stay cheap, tight enough
to exclude sea and foreign territory at the app's 15 km grid spacing.
"""

from __future__ import annotations

# Clockwise polygon — (latitude, longitude) pairs hugging Lebanon's border.
LEBANON_LAND_POLYGON: tuple[tuple[float, float], ...] = (
    (34.692, 35.980),  # NW corner: Arida, coastal border w/ Syria
    (34.660, 36.100),  # N Akkar coastal plain
    (34.650, 36.310),  # Wadi Khaled
    (34.440, 36.580),  # NE: Hermel
    (34.100, 36.620),  # Baalbek-Hermel east ridge
    (33.800, 36.450),  # Qaa / Hermel south
    (33.600, 36.250),  # east Bekaa floor
    (33.500, 36.050),  # Rashaya plateau
    (33.400, 35.900),  # Mt Hermon S approach
    (33.260, 35.880),  # Shebaa farms
    (33.150, 35.770),  # Khiam
    (33.080, 35.600),  # southern border E
    (33.085, 35.350),  # Rmeich area
    (33.095, 35.130),  # SW coast: Naqoura
    (33.270, 35.195),  # Tyre
    (33.420, 35.280),  # Sarafand
    (33.560, 35.370),  # Saida
    (33.720, 35.450),  # Damour
    (33.895, 35.485),  # Beirut
    (34.015, 35.620),  # Jounieh
    (34.120, 35.655),  # Byblos
    (34.260, 35.660),  # Batroun
    (34.370, 35.740),  # Chekka
    (34.440, 35.830),  # Tripoli
    (34.570, 35.950),  # Halba
    (34.692, 35.980),  # close
)


def is_in_lebanon(latitude: float, longitude: float) -> bool:
    """Return True if (lat, lon) lies inside the Lebanon land polygon.

    Standard ray-casting algorithm on the simplified border polygon
    above. Cheap enough to run per grid point (≤150 checks per request)
    without measurable overhead.
    """
    inside = False
    poly = LEBANON_LAND_POLYGON
    n = len(poly)
    j = n - 1
    for i in range(n):
        lat_i, lon_i = poly[i]
        lat_j, lon_j = poly[j]
        intersects = ((lat_i > latitude) != (lat_j > latitude)) and (
            longitude
            < (lon_j - lon_i) * (latitude - lat_i) / (lat_j - lat_i + 1e-12) + lon_i
        )
        if intersects:
            inside = not inside
        j = i
    return inside
