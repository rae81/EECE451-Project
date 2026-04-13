#!/usr/bin/env python3
"""Download a dense SRTM-30m DEM grid for Lebanon via OpenTopoData API.

Generates a ~500m-spaced grid across Lebanon's bounding box and queries
the OpenTopoData SRTM30m endpoint in batches of 100 locations.

Usage
-----
    python data/scripts/download_dem.py [--output data/raw/lebanon_dem_srtm30m.csv]
                                         [--spacing 0.005]

Rate limit: 1 request per second (free tier).  Full Lebanon grid at
0.005° spacing ≈ 1060 requests ≈ 18 minutes.
"""
from __future__ import annotations

import argparse
import csv
import sys
import time
from pathlib import Path

import requests

# Lebanon bounding box
LAT_MIN, LON_MIN = 33.05, 35.09
LAT_MAX, LON_MAX = 34.72, 36.68

API_URL = "https://api.opentopodata.org/v1/srtm30m"
BATCH_SIZE = 100  # max locations per request
RATE_LIMIT_S = 1.1  # seconds between requests (free tier: 1 req/s)


def generate_grid(lat_min, lat_max, lon_min, lon_max, spacing):
    """Yield (lat, lon) points on a regular grid."""
    import numpy as np
    lats = np.arange(lat_min, lat_max + spacing / 2, spacing)
    lons = np.arange(lon_min, lon_max + spacing / 2, spacing)
    for lat in lats:
        for lon in lons:
            yield round(float(lat), 6), round(float(lon), 6)


def query_batch(points: list[tuple[float, float]], retries=3) -> list[dict]:
    """Query OpenTopoData for a batch of points.  Returns list of dicts."""
    locations = "|".join(f"{lat},{lon}" for lat, lon in points)
    for attempt in range(retries):
        try:
            resp = requests.get(API_URL, params={"locations": locations}, timeout=30)
            if resp.status_code == 429:
                wait = 5 * (attempt + 1)
                print(f"  Rate limited, waiting {wait}s...")
                time.sleep(wait)
                continue
            resp.raise_for_status()
            data = resp.json()
            if data.get("status") == "OK":
                return data["results"]
            print(f"  API returned status={data.get('status')}, retrying...")
        except Exception as e:
            print(f"  Request error: {e}, retrying ({attempt+1}/{retries})...")
            time.sleep(2)
    return []


def main():
    parser = argparse.ArgumentParser(description="Download SRTM DEM grid for Lebanon")
    parser.add_argument("--output", default="data/raw/lebanon_dem_srtm30m.csv")
    parser.add_argument("--spacing", type=float, default=0.005,
                        help="Grid spacing in degrees (default 0.005 ≈ 500m)")
    parser.add_argument("--resume", action="store_true",
                        help="Skip points already in output file")
    args = parser.parse_args()

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)

    # Load existing points if resuming
    existing = set()
    if args.resume and output.exists():
        with open(output) as f:
            reader = csv.DictReader(f)
            for row in reader:
                existing.add((float(row["latitude"]), float(row["longitude"])))
        print(f"Resuming: {len(existing)} points already downloaded")

    points = [p for p in generate_grid(LAT_MIN, LAT_MAX, LON_MIN, LON_MAX, args.spacing)
              if p not in existing]

    total = len(points)
    n_batches = (total + BATCH_SIZE - 1) // BATCH_SIZE
    print(f"Grid: {total} points in {n_batches} batches (spacing={args.spacing}°)")

    mode = "a" if args.resume and existing else "w"
    with open(output, mode, newline="") as f:
        writer = csv.DictWriter(f, fieldnames=["latitude", "longitude", "elevation_m"])
        if mode == "w":
            writer.writeheader()

        downloaded = 0
        for i in range(0, total, BATCH_SIZE):
            batch = points[i:i + BATCH_SIZE]
            results = query_batch(batch)

            for res in results:
                loc = res.get("location", {})
                elev = res.get("elevation")
                if elev is not None:
                    writer.writerow({
                        "latitude": round(loc.get("lat", 0), 6),
                        "longitude": round(loc.get("lng", 0), 6),
                        "elevation_m": round(elev, 1),
                    })
                    downloaded += 1

            batch_num = i // BATCH_SIZE + 1
            print(f"  Batch {batch_num}/{n_batches}: {downloaded} points downloaded", end="\r")
            f.flush()

            if batch_num < n_batches:
                time.sleep(RATE_LIMIT_S)

    print(f"\nDone: {downloaded} elevation points saved to {output}")


if __name__ == "__main__":
    main()
