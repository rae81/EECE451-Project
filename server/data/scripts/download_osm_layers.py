#!/usr/bin/env python3
"""Download OSM building and road layers for Lebanon via Overpass API.

Produces JSON files with building centroids and road centroids suitable
for density computation in the feature engineering pipeline.

Usage
-----
    python data/scripts/download_osm_layers.py [--output-dir data/raw]
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import requests

OVERPASS_URL = "https://overpass-api.de/api/interpreter"

# Lebanon bounding box (south, west, north, east)
BBOX = "33.05,35.09,34.72,36.68"

QUERIES = {
    "buildings": f"""
[out:json][timeout:300][bbox:{BBOX}];
(
  way["building"];
  relation["building"];
);
out center qt 50000;
""",
    "roads": f"""
[out:json][timeout:300][bbox:{BBOX}];
way["highway"~"motorway|trunk|primary|secondary|tertiary|residential"];
out center qt;
""",
    "coastline": f"""
[out:json][timeout:120][bbox:{BBOX}];
way["natural"="coastline"];
out geom qt;
""",
}


def run_query(name: str, query: str, retries: int = 3) -> dict | None:
    """Execute an Overpass query with retries."""
    for attempt in range(retries):
        try:
            print(f"  Querying {name}...")
            resp = requests.post(
                OVERPASS_URL,
                data={"data": query.strip()},
                timeout=360,
            )
            if resp.status_code == 429:
                wait = 30 * (attempt + 1)
                print(f"  Rate limited, waiting {wait}s...")
                time.sleep(wait)
                continue
            resp.raise_for_status()
            return resp.json()
        except Exception as e:
            print(f"  Error: {e}, retrying ({attempt+1}/{retries})...")
            time.sleep(10)
    print(f"  FAILED to download {name} after {retries} attempts")
    return None


def extract_centroids(data: dict) -> list[dict]:
    """Extract lat/lon centroids from Overpass elements."""
    centroids = []
    for el in data.get("elements", []):
        lat = el.get("lat") or (el.get("center", {}) or {}).get("lat")
        lon = el.get("lon") or (el.get("center", {}) or {}).get("lon")
        if lat is not None and lon is not None:
            entry = {"latitude": round(lat, 6), "longitude": round(lon, 6)}
            tags = el.get("tags", {})
            if tags.get("building"):
                entry["type"] = tags["building"]
            if tags.get("highway"):
                entry["type"] = tags["highway"]
            centroids.append(entry)
    return centroids


def extract_coastline(data: dict) -> list[dict]:
    """Extract coastline as a list of coordinate points."""
    points = []
    seen = set()
    for el in data.get("elements", []):
        geom = el.get("geometry", [])
        for pt in geom:
            key = (round(pt["lat"], 5), round(pt["lon"], 5))
            if key not in seen:
                seen.add(key)
                points.append({"latitude": key[0], "longitude": key[1]})
    return points


def main():
    parser = argparse.ArgumentParser(description="Download OSM layers for Lebanon")
    parser.add_argument("--output-dir", default="data/raw")
    args = parser.parse_args()

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    # Buildings
    data = run_query("buildings", QUERIES["buildings"])
    if data:
        centroids = extract_centroids(data)
        path = out_dir / "osm_buildings_lebanon.json"
        with open(path, "w") as f:
            json.dump(centroids, f)
        print(f"  Saved {len(centroids)} building centroids → {path}")

    time.sleep(5)  # be polite to Overpass

    # Roads
    data = run_query("roads", QUERIES["roads"])
    if data:
        centroids = extract_centroids(data)
        path = out_dir / "osm_roads_lebanon.json"
        with open(path, "w") as f:
            json.dump(centroids, f)
        print(f"  Saved {len(centroids)} road centroids → {path}")

    time.sleep(5)

    # Coastline
    data = run_query("coastline", QUERIES["coastline"])
    if data:
        points = extract_coastline(data)
        path = out_dir / "lebanon_coastline.json"
        with open(path, "w") as f:
            json.dump(points, f)
        print(f"  Saved {len(points)} coastline points → {path}")

    print("\nDone.")


if __name__ == "__main__":
    main()
