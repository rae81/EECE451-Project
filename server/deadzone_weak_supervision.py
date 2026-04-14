"""Snorkel-style weak supervision for dead-zone labeling.

Replaces the single-source (COST-231-circular) Tier-3 labeling with a
generative label model that aggregates multiple INDEPENDENT noisy
labeling functions (LFs) into a calibrated probabilistic label.

Design principles
-----------------
1. Each LF is an independent noisy voter. LFs can ABSTAIN (return -1).
2. Votes are aggregated with accuracy-weighted majority voting (a
   minimal Snorkel-style generative model without the Snorkel dep).
3. The output is a soft label in [0, 1] plus a confidence score.
4. Because LFs tap different physical signals (throughput vs path-loss
   vs population vs terrain), their errors are less correlated than
   multiple views of the same COST-231 formula — which breaks the
   circular leak.

Labeling functions provided
---------------------------
- LF_ookla_speed       : low nearby speedtest performance
- LF_p1812_propagation : predicted RSRP below 3GPP dead-zone threshold
- LF_tower_distance    : >threshold km from any tower AND unfavorable clutter
- LF_terrain_obstructed: large LoS obstruction score
- LF_building_density  : high building density but no telecom infrastructure
  (indoor-only-coverage proxy)

Each LF returns +1 (dead zone), 0 (not dead zone), or -1 (abstain).

References
----------
- Ratner et al. "Snorkel: Rapid Training Data Creation with Weak
  Supervision" VLDB 2017, arXiv:1711.10160.
- Dawid & Skene "Maximum Likelihood Estimation of Observer Error-Rates
  Using the EM Algorithm" 1979 — the theoretical backbone of generative
  label models.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Optional

import numpy as np
import pandas as pd


# LF vote constants
DEAD = 1
LIVE = 0
ABSTAIN = -1


# ── Default LF thresholds ──────────────────────────────────────────

@dataclass
class LFThresholds:
    # Ookla speed LF
    ookla_dead_kbps: float = 1000.0        # < 1 Mbps = dead
    ookla_live_kbps: float = 10000.0       # > 10 Mbps = live
    ookla_max_dist_km: float = 1.0         # ignore Ookla tiles > 1km away

    # P.1812 propagation LF
    p1812_dead_rsrp: float = -110.0        # 3GPP TS 36.133
    p1812_live_rsrp: float = -95.0

    # Tower distance LF
    tower_far_km: float = 5.0              # > 5 km AND unfavourable clutter
    tower_close_km: float = 0.5            # < 500 m in dense urban

    # Terrain obstruction LF
    terrain_obs_high: float = 0.65         # > 65% obstructed = dead
    terrain_obs_low: float = 0.15

    # Building density / clutter LF
    urban_building_threshold: float = 50.0  # buildings / km² above = urban
    urban_no_telecom_threshold: int = 0     # no telecom = coverage hole
    rural_building_threshold: float = 5.0


# ── Labeling functions ─────────────────────────────────────────────

def lf_ookla_speed(row: pd.Series, thr: LFThresholds) -> int:
    """Vote based on nearby Ookla speedtest aggregates."""
    dist = row.get("ookla_distance_km", np.inf)
    if pd.isna(dist) or dist > thr.ookla_max_dist_km:
        return ABSTAIN
    dl = row.get("ookla_avg_down_kbps", np.nan)
    lat = row.get("ookla_avg_latency_ms", np.nan)
    if pd.isna(dl):
        return ABSTAIN
    if dl < thr.ookla_dead_kbps:
        return DEAD
    if dl > thr.ookla_live_kbps and (pd.isna(lat) or lat < 100):
        return LIVE
    return ABSTAIN


def lf_p1812_propagation(row: pd.Series, thr: LFThresholds) -> int:
    """Vote based on ITU-R P.1812 predicted RSRP (from terrain profile)."""
    rsrp = row.get("p1812_rsrp_dbm", np.nan)
    if pd.isna(rsrp):
        return ABSTAIN
    if rsrp < thr.p1812_dead_rsrp:
        return DEAD
    if rsrp > thr.p1812_live_rsrp:
        return LIVE
    return ABSTAIN


def lf_tower_distance(row: pd.Series, thr: LFThresholds) -> int:
    """Vote based on distance to nearest tower + clutter class."""
    d = row.get("serving_tower_distance_km", np.nan)
    if pd.isna(d):
        return ABSTAIN
    density_3km = row.get("same_group_density_3km", 0)
    if d > thr.tower_far_km and density_3km < 2:
        return DEAD
    if d < thr.tower_close_km and density_3km > 3:
        return LIVE
    return ABSTAIN


def lf_terrain_obstructed(row: pd.Series, thr: LFThresholds) -> int:
    """Vote based on LoS obstruction score (DEM-derived)."""
    obs = row.get("los_obstruction_score", np.nan)
    if pd.isna(obs):
        return ABSTAIN
    if obs > thr.terrain_obs_high:
        return DEAD
    if obs < thr.terrain_obs_low:
        return LIVE
    return ABSTAIN


def lf_building_density(row: pd.Series, thr: LFThresholds) -> int:
    """Dense urban + no telecom infrastructure = likely indoor-only dead zone."""
    buildings = row.get("osm_building_density_1km", np.nan)
    telecom = row.get("osm_telecom_density_1km", np.nan)
    if pd.isna(buildings):
        return ABSTAIN
    if (buildings > thr.urban_building_threshold and
            not pd.isna(telecom) and telecom <= thr.urban_no_telecom_threshold):
        return DEAD
    if buildings > thr.urban_building_threshold and telecom >= 2:
        return LIVE
    if buildings < thr.rural_building_threshold:
        return ABSTAIN
    return ABSTAIN


DEFAULT_LFS: list[tuple[str, Callable[[pd.Series, LFThresholds], int], float]] = [
    # (name, function, prior_accuracy_estimate)
    ("lf_ookla_speed",        lf_ookla_speed,        0.85),
    ("lf_p1812_propagation",  lf_p1812_propagation,  0.75),
    ("lf_tower_distance",     lf_tower_distance,     0.70),
    ("lf_terrain_obstructed", lf_terrain_obstructed, 0.70),
    ("lf_building_density",   lf_building_density,   0.60),
]


# ── Generative label model ─────────────────────────────────────────

def apply_lfs(
    df: pd.DataFrame,
    lfs: Optional[list] = None,
    thresholds: Optional[LFThresholds] = None,
) -> pd.DataFrame:
    """Apply all LFs to each row. Returns (n_rows, n_lfs) matrix as DataFrame."""
    if lfs is None:
        lfs = DEFAULT_LFS
    if thresholds is None:
        thresholds = LFThresholds()

    votes = {}
    for name, fn, _ in lfs:
        votes[name] = df.apply(lambda r, f=fn: f(r, thresholds), axis=1)
    return pd.DataFrame(votes, index=df.index)


def majority_vote_soft_label(
    vote_matrix: pd.DataFrame,
    lfs: Optional[list] = None,
) -> pd.DataFrame:
    """Aggregate LF votes via accuracy-weighted soft majority voting.

    For each row:
        P(dead | votes) ∝ Π_i p_i^{1{vote_i=dead}} (1-p_i)^{1{vote_i=live}}

    Where p_i is the prior accuracy of LF i. Abstentions contribute 1.

    Returns a DataFrame with columns:
        soft_label    : calibrated P(dead zone) in [0, 1]
        vote_dead     : count of DEAD votes
        vote_live     : count of LIVE votes
        vote_abstain  : count of abstentions
        n_voters      : number of non-abstaining LFs
        confidence    : |soft_label - 0.5| * 2  (0 = uncertain, 1 = certain)
    """
    if lfs is None:
        lfs = DEFAULT_LFS

    names = [n for n, _, _ in lfs]
    accs = np.array([a for _, _, a in lfs])

    votes = vote_matrix[names].values  # (n_rows, n_lfs) in {-1, 0, 1}

    # Log-odds accumulator; prior 0.5 → logit 0
    n_rows = votes.shape[0]
    logits = np.zeros(n_rows)
    for j in range(len(names)):
        dead_mask = votes[:, j] == DEAD
        live_mask = votes[:, j] == LIVE
        p = accs[j]
        # log(p / (1-p)) per dead vote, opposite for live
        lo = np.log(p / (1.0 - p))
        logits[dead_mask] += lo
        logits[live_mask] -= lo

    soft = 1.0 / (1.0 + np.exp(-logits))

    vote_dead = (votes == DEAD).sum(axis=1)
    vote_live = (votes == LIVE).sum(axis=1)
    vote_abstain = (votes == ABSTAIN).sum(axis=1)
    n_voters = vote_dead + vote_live

    confidence = np.abs(soft - 0.5) * 2.0

    return pd.DataFrame({
        "soft_label": soft,
        "vote_dead": vote_dead,
        "vote_live": vote_live,
        "vote_abstain": vote_abstain,
        "n_voters": n_voters,
        "confidence": confidence,
    }, index=vote_matrix.index)


def weak_supervision_labels(
    features_df: pd.DataFrame,
    lfs: Optional[list] = None,
    thresholds: Optional[LFThresholds] = None,
    min_voters: int = 1,
    min_confidence: float = 0.0,
) -> pd.DataFrame:
    """End-to-end: apply LFs, aggregate, return labels.

    Parameters
    ----------
    features_df : must contain the columns referenced by the LFs.
    min_voters  : rows with fewer non-abstaining LFs are dropped.
    min_confidence : rows below this confidence get ``sample_weight``
        scaled down proportionally.

    Returns
    -------
    DataFrame indexed like features_df with columns:
        ws_is_deadzone       : hard label (0/1) from soft_label > 0.5
        ws_soft_label        : P(dead) in [0, 1]
        ws_confidence        : |soft - 0.5| * 2
        ws_sample_weight     : suggested sample weight (∝ confidence)
        ws_n_voters          : # LFs that voted (not abstained)
        ws_keep              : True if row passes min_voters threshold
    """
    votes = apply_lfs(features_df, lfs=lfs, thresholds=thresholds)
    agg = majority_vote_soft_label(votes, lfs=lfs)

    hard = (agg["soft_label"] > 0.5).astype(int)
    keep = agg["n_voters"] >= min_voters

    # Weight scales with confidence, but never above 1.0 or below 0.1
    sample_weight = np.clip(agg["confidence"], 0.1, 1.0)
    # Lightly boost rows that pass the confidence threshold
    sample_weight = np.where(
        agg["confidence"] >= min_confidence, sample_weight, sample_weight * 0.5
    )

    out = pd.DataFrame({
        "ws_is_deadzone": hard,
        "ws_soft_label": agg["soft_label"],
        "ws_confidence": agg["confidence"],
        "ws_sample_weight": sample_weight,
        "ws_n_voters": agg["n_voters"],
        "ws_keep": keep,
    }, index=features_df.index)

    # Also attach raw votes for diagnostic purposes
    for col in votes.columns:
        out[col] = votes[col]

    return out


def report_lf_coverage(vote_matrix: pd.DataFrame) -> pd.DataFrame:
    """Summarize LF coverage, overlap, and conflict rates.

    Returns a per-LF DataFrame with:
        coverage : fraction of rows where LF did NOT abstain
        frac_dead: fraction of those where LF voted DEAD
        frac_live: fraction where LF voted LIVE
    """
    rows = []
    for col in vote_matrix.columns:
        v = vote_matrix[col]
        non_abstain = v != ABSTAIN
        rows.append({
            "lf": col,
            "coverage": non_abstain.mean(),
            "frac_dead": (v == DEAD).sum() / max(non_abstain.sum(), 1),
            "frac_live": (v == LIVE).sum() / max(non_abstain.sum(), 1),
            "n_votes": int(non_abstain.sum()),
        })
    return pd.DataFrame(rows)
