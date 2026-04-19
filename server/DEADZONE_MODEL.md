# Dead-Zone Model

This project now supports a trained dead-zone risk model that can feed:

- `GET /predict`
- `GET /api/heatmap-data`
- the Android heatmap reliability view

## Recommended dataset stack

### 1. OpenCelliD

Use this as the cellular reference layer.

- Official site: https://opencellid.org/downloads
- Format reference: https://wiki.opencellid.org/wiki/Database_format
- Lebanon stats page: https://opencellid.org/stats.php

Use the Lebanon export when possible. For cross-country stress testing, add nearby countries only as secondary experiments, not as the deployed Lebanon model.

Expected columns include:

- `radio`
- `mcc`
- `net`
- `lon`
- `lat`
- `range`
- `samples`
- `updated`
- `averageSignal`

Lebanon-specific note from the real run:

- the downloaded export is headerless, so the loader maps the columns explicitly
- `averageSignal` was effectively unusable in this file because it was `0` across the export
- the deployed model therefore uses topology plus Ookla performance and derives a signal proxy during training

### 2. Ookla Open Data

Use this as the weak-label performance layer.

- Official repo and license: https://github.com/teamookla/ookla-open-data
- Example mobile parquet path:
  `https://ookla-open-data.s3.amazonaws.com/parquet/performance/type=mobile/year=2025/quarter=4/2025-10-01_performance_mobile_tiles.parquet`

Useful fields:

- `avg_d_kbps`
- `avg_u_kbps`
- `avg_lat_ms`
- `tests`
- `devices`
- geometry or quadkey

The pipeline can read:

- parquet
- csv
- json/geojson

It also handles coordinates from:

- `latitude` and `longitude`
- `lat` and `lon`
- WKT geometry
- quadkey/tile

### 3. Optional enrichment

These are useful, but not required for the current pipeline:

- Geofabrik Lebanon extract: https://download.geofabrik.de/asia/lebanon.html
- Overpass telecom extracts for:
  - `man_made=mast`
  - `tower:type=communication`
  - `communication:mobile_phone=yes`
- OpenStreetMap telecom tags:
  - `man_made=mast`
  - `tower:type=communication`
  - `communication:mobile_phone=yes`
- SRTM-backed elevation grids, for example via OpenTopoData:
  - https://www.opentopodata.org/

Use these later if you want road density, building density, or telecom-structure features.

## What to use for Lebanon

For the deployed model, prefer:

1. Lebanon OpenCelliD export
2. Lebanon-filtered Ookla mobile tiles
3. Optional local server samples from your own app later for fine-tuning

Do not mix multiple countries into the production Lebanon model unless you are explicitly doing pretraining or robustness testing.

## Similar open-source work

### Useful GitHub references

- `teamookla/ookla-open-data`
  - Official open performance dataset
  - https://github.com/teamookla/ookla-open-data

- `geoai-lab/PyGRF`
  - Spatial random forest ideas for geographically varying relationships
  - https://github.com/geoai-lab/PyGRF

- `PengfeiCui99/Multiscale_Geographical_Random_Forest_MGRF`
  - Multiscale spatial random forest reference
  - https://github.com/PengfeiCui99/Multiscale_Geographical_Random_Forest_MGRF

These are useful for model ideas, not for direct copy-paste into this repo.

### Reddit findings

Deep Reddit search was not very strong for reusable implementation material. Most threads were data requests or unrelated radio/security tooling.

The two Reddit-style leads you found are not the right foundation:

- `Rayhunter`
  - useful for cellular spying detection
  - not useful for dead-zone prediction or coverage modeling

- `Cellular automata` toy projects
  - useful for experimentation or simulation exercises
  - not useful for practical cellular dead-zone prediction with real geospatial data

## Current model design

The model in `deadzone_model.py` trains a spatial classifier over OpenCelliD reference rows, optionally fused with nearby Ookla performance tiles.

Features:

- latitude
- longitude
- operator
- network type
- average signal
- estimated range
- sample count
- days since update
- nearby same-operator density
- nearest same-operator cell distance
- nearby same-operator signal/range averages
- nearby Ookla download/upload/latency/tests/devices
- nearby mapped telecom density / nearest telecom distance
- terrain elevation and local relief

Label:

- positive dead-zone class is derived from weak signal and poor nearby performance

Output:

- `deadzone_risk`
- `deadzone_label`
- `confidence`
- `predicted_signal_power`
- short reason list

The runtime now supports **specialized subgroup variants** inside the same artifact, and currently targets exact `operator + network_type` matches such as:

- `Alfa::4G`
- `Touch::4G`

## Current real Lebanon run

The current artifact was trained on:

- `data/raw/opencellid_lebanon_415.csv.gz`
- `data/raw/ookla_mobile_q4_2025.parquet`
- `data/raw/osm_telecom_lebanon.json`
- `data/raw/lebanon_elevation_grid.csv`

Prepared dataset after filtering and feature generation:

- `1720` rows
- positive rate: `17.67%`
- operators: `Alfa 855`, `Touch 865`
- network types: `2G 101`, `3G 441`, `4G 1178`
- OSM telecom features: `135`
- elevation grid points: `272`
- specialized subgroup models: `Alfa::4G`, `Touch::4G`

Holdout metrics from the saved artifact:

- accuracy: `0.9506`
- ROC AUC: `0.9924`
- average precision: `0.9660`
- precision: `0.7778`
- recall: `1.0000`

Important caveat:

- these are weak-supervision metrics against derived labels, not ground-truth drive-test labels
- the model is suitable for a dead-zone risk overlay and demo integration, but not for carrier-grade coverage claims

## Training steps

### 1. Put raw files somewhere local

Example:

```text
server/data/raw/opencellid_lebanon_415.csv.gz
server/data/raw/ookla_mobile_q4_2025.parquet
server/data/raw/osm_telecom_lebanon.json
server/data/raw/lebanon_elevation_grid.csv
```

### 2. Train the model

Run from `server/`:

```bash
. .venv/bin/activate
python deadzone_model.py \
  --opencellid data/raw/opencellid_lebanon_415.csv.gz \
  --ookla data/raw/ookla_mobile_q4_2025.parquet \
  --osm-context data/raw/osm_telecom_lebanon.json \
  --dem-grid data/raw/lebanon_elevation_grid.csv \
  --lebanon \
  --specialize-groups \
  --specialize-network-type 4G \
  --group-min-rows 120 \
  --output-model instance/ml/deadzone_model.joblib \
  --output-dataset instance/ml/prepared_deadzone_dataset.csv \
  --report-dir instance/ml/reports
```

### 3. Start the backend with the trained artifact

```bash
export DEADZONE_MODEL_PATH="$(pwd)/instance/ml/deadzone_model.joblib"
flask --app app run --host 0.0.0.0 --port 5000
```

### 4. Validate manually

Prediction endpoint:

```bash
curl "http://127.0.0.1:5000/predict?latitude=33.8938&longitude=35.5018&operator=Alfa&network_type=4G"
```

Heatmap endpoint:

```bash
curl "http://127.0.0.1:5000/api/heatmap-data?device_id=<your-device-id>"
```

## Artifacts produced

- `deadzone_model.joblib`
- `prepared_deadzone_dataset.csv`
- `reports/summary.json`
- `reports/eda_overview.png`

## Practical recommendation

For your submission/demo:

- train one Lebanon model first
- prioritize exact `Alfa 4G` and `Touch 4G` subgroup variants inside the artifact
- keep the global model as fallback for other RATs
- use other countries only for later stress tests
