# Codebase Rework Plan

## Scope

1. **Cleanup** — delete obsolete files and consolidate the repo to two top-level code folders plus reference material. Done in Phase 1.
2. **Organisation** — add clear section headers, module docstrings, and inline citations across the whole codebase. Keep behaviour identical.
3. **Open-source integration** — where an existing file implements a well-known algorithm or pattern, replace the ad-hoc implementation with a call into a well-maintained open-source library (or a short, clearly-cited adaptation of an open-source reference implementation). Limited almost entirely to the **extra features**; required features stay as original code with citations where concepts were borrowed.

Model bundle and training notebook are **not touched**.

## Required vs extra, per the EECE 451 brief

The graded requirements are:

| Requirement (% of grade) | Where it lives |
|---|---|
| 2G/3G/4G cell info querying (30%) | `android-app/.../helpers/CellInfoHelper.java`, `services/CellMonitorService.java` |
| Server design + database + stats (20%) | `server/app.py`, `server/models.py` |
| Mobile-server communication (10%) | `server/app.py` REST endpoints, `android-app/.../network/*` |
| Real-time + statistical services in the app (10%) | `fragments/DashboardFragment.java`, `fragments/StatisticsFragment.java`, `fragments/HistoryFragment.java` |
| Mobile UI design (10%) | `res/layout/*.xml`, `res/values/*.xml` |
| Source code quality + presentation (20%) | everything — this is what the rework improves |

Anything else is an **extra feature** (bonus-worthy but not scored by the rubric).

## Per-file disposition

### Backend (`server/`)

| File | LoC | Category | Action |
|---|---:|---|---|
| `app.py` | 2151 | required | Split into Flask blueprints (auth, devices, measurements, stats, deadzone) for clarity; keep endpoints identical. No open-source swaps. |
| `models.py` | 194 | required | Section headers, docstrings. No code changes. |
| `config.py` | 31 | required | Docstring. No code changes. |
| `gunicorn.conf.py` | 23 | deploy | Leave as-is. |
| `deadzone_model.py` | 1973 | extra (runtime wrapper) | Section headers, citations for SHAP / LightGBM. Behaviour unchanged. |
| `deadzone_data.py` | 723 | extra | Cite COST-231 (3GPP TR 36.942), ITU-R P.1812, Haversine. Consider swapping custom Haversine for `geopy.distance.geodesic` where used. |
| `deadzone_features.py` | 645 | extra | Cite H3 (Uber), OSM Overpass API. Swap custom H3 helpers for direct `h3` library calls where custom implementations exist. |
| `deadzone_training.py` | 608 | extra | Cite sklearn GroupKFold, LightGBM. Already uses library APIs correctly; comment-only improvements. |
| `deadzone_physics.py` | 333 | extra | Cite ITU-R P.1812-6 + Bullington diffraction. Swap custom knife-edge solver for the `itur` library (if license fits) or keep the current implementation with a clear standard reference. |
| `deadzone_propagation.py` | 365 | extra | Cite COST-231 Hata + free-space path loss. Comment-only. |
| `deadzone_weak_supervision.py` | 311 | extra | Cite Ratner et al. (Snorkel, VLDB 2017) + Dawid-Skene (1979). Keep custom generative model — the MIT-licensed `snorkel` library is not a drop-in replacement here and would add a heavy dep. |
| `deadzone_transfer.py` | 418 | extra | Cite Berlin V2X paper, OpenCelliD, Fraunhofer HHI. Comment-only. |
| `deadzone_sionna.py` | 307 | extra | Cite Sionna RT (Hoydis et al.) + Geo2SigMap. Comment-only; already a thin Sionna wrapper. |
| `deadzone_explain.py` | 188 | extra | Cite SHAP (Lundberg & Lee, NIPS 2017). Already uses `shap` library directly. |

### Android (`android-app/NetworkCellAnalyzer/`)

| File | LoC | Category | Action |
|---|---:|---|---|
| `helpers/CellInfoHelper.java` | 375 | required | Section headers + citation to Android `TelephonyManager` docs. No code swaps. |
| `services/CellMonitorService.java` | 342 | required | Section headers, foreground-service pattern citation (Android dev guide). No code swaps. |
| `fragments/DashboardFragment.java` | 687 | required | Section headers, split UI binding from data binding. No code swaps. |
| `fragments/StatisticsFragment.java` | 692 | required | Section headers. No code swaps. |
| `fragments/HistoryFragment.java` | 376 | required | Section headers. No code swaps. |
| `activities/MainActivity.java` | 323 | required | Section headers. No code swaps. |
| `activities/LoginActivity.java` | 632 | required | Section headers. No code swaps. |
| `activities/SplashActivity.java` | 147 | required | Section headers. No code swaps. |
| `network/RetrofitClient.java` | 214 | required | Cite Retrofit/OkHttp. Keep current code. |
| `network/AuthInterceptor.java` | 198 | required | Keep. |
| `network/ApiService.java` | 142 | required | Keep. |
| `network/models/*.java` | — | required | Keep. |
| `database/CellDataEntity.java` | 200 | required | Cite Room (Android Jetpack). Keep. |
| `models/CellDataEntry.java` | 206 | required | Keep. |
| `utils/PreferenceManager.java` | 344 | required | Keep. |
| `helpers/HandoverDetector.java` | 227 | extra | Cite any adapted patterns. |
| `fragments/HeatmapFragment.java` | 1446 | extra | **Prime candidate for open-source swap.** Replace hand-rolled heatmap drawing with **`MPAndroidChart`** (already a dep) or **`osmdroid`**/**`maplibre`** bubble-overlay. Cite the library. |
| `fragments/SpeedTestFragment.java` | 646 | extra | Replace custom speed-test logic with **`speedtest-cli`**/Ookla's public server list + well-known TCP download measurement pattern. Cite. |
| `fragments/DiagnosticsFragment.java` | 322 | extra | Keep; minor citations. |
| `fragments/TowerClustersFragment.java` | 137 | extra | Cite clustering algorithm (k-means / DBSCAN) — swap custom loop for **`smile-core`** or keep if simple. |
| `utils/ExportHelper.java` | 538 | extra | Replace custom CSV writer with **Apache Commons CSV**. Cite. |
| `utils/NetworkInsightEngine.java` | 191 | extra | Section headers. |
| `utils/NotificationHelper.java` | 177 | extra | Cite Android notifications guide. |
| `utils/NetworkIdentityHelper.java` | 178 | extra | Keep. |
| `services/OfflineSyncWorker.java` | 187 | extra | Cite WorkManager pattern. |
| `adapters/*Adapter.java` | — | required/extra mix | Section headers. |

## Candidate open-source swaps (extras only)

| Current code | Replace with | License | Why |
|---|---|---|---|
| Custom Haversine in `deadzone_data.py`, `deadzone_features.py` | `geopy.distance.geodesic` | MIT | Battle-tested, handles WGS-84 ellipsoid correctly. |
| Custom H3 wrapping helpers (if any) | direct `h3` library calls | Apache-2.0 | Already a dep, simpler. |
| Custom knife-edge diffraction solver in `deadzone_physics.py` | Keep but cite ITU-R P.1812-6 explicitly; optional use of `itur` library behind a feature flag. | MIT (`itur`) | Library adds ~20 MB of dependencies; worth it if we commit to it. |
| Hand-rolled CSV export in `utils/ExportHelper.java` | Apache Commons CSV | Apache-2.0 | 30-line wins. |
| Heatmap canvas code in `HeatmapFragment.java` | MPAndroidChart BubbleChart or osmdroid HeatmapOverlay | Apache-2.0 | Reduces the 1446-line file by hundreds of lines. |
| Speedtest throughput calc in `SpeedTestFragment.java` | Cloudflare speedtest client pattern (MIT reference) | MIT | Well-known pattern. |

Every swap will carry an inline comment of the form:

```java
// Source: Apache Commons CSV 1.10 (Apache-2.0).
// https://commons.apache.org/proper/commons-csv/
// Adapted for: writing session export rows.
```

## Phases

1. **Phase 1 — Cleanup.** Done on disk; will commit alongside this plan.
2. **Phase 2 — Organisation and citations (no behaviour change).** Single commit per top-level folder (`server/`, `android-app/`).
3. **Phase 3 — Open-source swaps for extras.** One commit per swap so each is reviewable independently.

Each phase ends with: app builds, server tests pass, prediction smoke test on Beirut unchanged.
