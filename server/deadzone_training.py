"""Training pipeline for dead-zone prediction v3.

Dual LightGBM (regressor + stacked classifier) with spatial
cross-validation, Optuna hyperparameter tuning, and comprehensive
evaluation metrics.
"""
from __future__ import annotations

import json
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    f1_score,
    mean_absolute_error,
    mean_squared_error,
    precision_recall_fscore_support,
    r2_score,
    roc_auc_score,
)
from sklearn.model_selection import GroupKFold
from sklearn.neighbors import BallTree
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, OrdinalEncoder

try:
    import h3
except ImportError:
    h3 = None  # type: ignore[assignment]

try:
    import lightgbm as lgb
    HAS_LIGHTGBM = True
except ImportError:
    lgb = None  # type: ignore[assignment]
    HAS_LIGHTGBM = False

try:
    import optuna
    HAS_OPTUNA = True
except ImportError:
    optuna = None  # type: ignore[assignment]
    HAS_OPTUNA = False

from deadzone_features import CATEGORICAL_FEATURES_V3, NUMERIC_FEATURES_V3

EARTH_RADIUS_KM = 6371.0088


# ── Spatial cross-validation ────────────────────────────────────────

def assign_spatial_fold_groups(
    lats: np.ndarray,
    lons: np.ndarray,
    h3_resolution: int = 5,
) -> np.ndarray:
    """Assign each point to an H3 hex at given resolution for fold grouping.

    Resolution 5 ≈ 253 km² hexagons — large enough to ensure spatial
    separation between train and test folds.
    """
    if h3 is None:
        # Fallback: grid-based grouping
        lat_bins = np.floor(lats * 2).astype(int)
        lon_bins = np.floor(lons * 2).astype(int)
        return np.array([f"{a}_{b}" for a, b in zip(lat_bins, lon_bins)])

    groups = []
    for lat, lon in zip(lats, lons):
        try:
            groups.append(h3.latlng_to_cell(float(lat), float(lon), h3_resolution))
        except Exception:
            groups.append("unknown")
    return np.array(groups)


def spatial_cross_validate(
    X: pd.DataFrame,
    y: np.ndarray,
    groups: np.ndarray,
    weights: np.ndarray | None = None,
    n_splits: int = 5,
    buffer_km: float = 2.0,
    model_factory=None,
    metric_fn=None,
) -> list[dict]:
    """Spatial GroupKFold with optional buffer exclusion.

    Parameters
    ----------
    model_factory : callable returning (model, fit_kwargs_dict)
    metric_fn : callable(y_true, y_pred_proba) -> float

    Returns list of per-fold metric dicts.
    """
    # Ensure enough unique groups for n_splits
    unique_groups = np.unique(groups)
    n_splits = min(n_splits, len(unique_groups))
    if n_splits < 2:
        return []

    gkf = GroupKFold(n_splits=n_splits)
    fold_results = []

    for fold_i, (train_idx, test_idx) in enumerate(gkf.split(X, y, groups)):
        # Buffer exclusion: remove training points within buffer_km of test points
        if buffer_km > 0 and "latitude" in X.columns and "longitude" in X.columns:
            test_coords = np.deg2rad(
                X.iloc[test_idx][["latitude", "longitude"]].values
            )
            train_coords = np.deg2rad(
                X.iloc[train_idx][["latitude", "longitude"]].values
            )
            if len(test_coords) > 0 and len(train_coords) > 0:
                test_tree = BallTree(test_coords, metric="haversine")
                radius = buffer_km / EARTH_RADIUS_KM
                too_close = test_tree.query_radius(train_coords, r=radius)
                exclude = np.array([len(tc) > 0 for tc in too_close])
                train_idx = train_idx[~exclude]

        if len(train_idx) < 10 or len(test_idx) < 5:
            continue

        X_train, X_test = X.iloc[train_idx], X.iloc[test_idx]
        y_train, y_test = y[train_idx], y[test_idx]
        w_train = weights[train_idx] if weights is not None else None

        if model_factory is not None:
            model, fit_kwargs = model_factory()
            if w_train is not None:
                fit_kwargs["sample_weight"] = w_train
            model.fit(X_train, y_train, **fit_kwargs)

            if metric_fn is not None:
                if hasattr(model, "predict_proba"):
                    pred = model.predict_proba(X_test)[:, 1]
                else:
                    pred = model.predict(X_test)
                score = metric_fn(y_test, pred)
            else:
                score = model.score(X_test, y_test)

            fold_results.append({
                "fold": fold_i,
                "train_size": len(train_idx),
                "test_size": len(test_idx),
                "score": float(score),
            })

    return fold_results


# ── Preprocessing pipeline ──────────────────────────────────────────

def build_preprocessor(
    numeric_features: list[str] | None = None,
    categorical_features: list[str] | None = None,
) -> ColumnTransformer:
    """Build a sklearn ColumnTransformer for the v3 feature set."""
    if numeric_features is None:
        numeric_features = NUMERIC_FEATURES_V3
    if categorical_features is None:
        categorical_features = CATEGORICAL_FEATURES_V3

    return ColumnTransformer(
        transformers=[
            ("num", SimpleImputer(strategy="median"), numeric_features),
            ("cat", OrdinalEncoder(handle_unknown="use_encoded_value", unknown_value=-1),
             categorical_features),
        ],
        remainder="drop",
        verbose_feature_names_out=False,
    )


# ── LightGBM model factories ───────────────────────────────────────

def default_regressor_params() -> dict:
    """Default LightGBM regressor hyperparameters."""
    return {
        "objective": "huber",
        "n_estimators": 500,
        "max_depth": 7,
        "learning_rate": 0.05,
        "num_leaves": 47,
        "min_child_samples": 15,
        "subsample": 0.8,
        "colsample_bytree": 0.8,
        "reg_alpha": 0.1,
        "reg_lambda": 1.0,
        "random_state": 42,
        "verbose": -1,
        "n_jobs": -1,
    }


def default_classifier_params(pos_rate: float = 0.15) -> dict:
    """Default LightGBM classifier hyperparameters."""
    scale = max(1.0, (1.0 - pos_rate) / max(pos_rate, 0.01))
    return {
        "objective": "binary",
        "n_estimators": 500,
        "max_depth": 6,
        "learning_rate": 0.05,
        "num_leaves": 31,
        "min_child_samples": 20,
        "subsample": 0.8,
        "colsample_bytree": 0.8,
        "reg_alpha": 0.1,
        "reg_lambda": 1.0,
        "scale_pos_weight": round(scale, 2),
        "random_state": 42,
        "verbose": -1,
        "n_jobs": -1,
    }


# ── Optuna hyperparameter tuning ────────────────────────────────────

def tune_regressor(
    X: pd.DataFrame,
    y: np.ndarray,
    groups: np.ndarray,
    weights: np.ndarray | None = None,
    n_trials: int = 50,
    n_splits: int = 5,
) -> dict:
    """Optuna study for LGBMRegressor. Returns best params."""
    if not HAS_OPTUNA or not HAS_LIGHTGBM:
        return default_regressor_params()

    def objective(trial):
        params = {
            "objective": "huber",
            "n_estimators": trial.suggest_int("n_estimators", 200, 1000),
            "max_depth": trial.suggest_int("max_depth", 4, 10),
            "learning_rate": trial.suggest_float("learning_rate", 0.01, 0.15, log=True),
            "num_leaves": trial.suggest_int("num_leaves", 15, 63),
            "min_child_samples": trial.suggest_int("min_child_samples", 10, 50),
            "subsample": trial.suggest_float("subsample", 0.6, 0.9),
            "colsample_bytree": trial.suggest_float("colsample_bytree", 0.6, 0.9),
            "reg_alpha": trial.suggest_float("reg_alpha", 1e-3, 10.0, log=True),
            "reg_lambda": trial.suggest_float("reg_lambda", 1e-3, 10.0, log=True),
            "random_state": 42,
            "verbose": -1,
            "n_jobs": -1,
        }

        preprocessor = build_preprocessor()

        def factory():
            pipe = Pipeline([
                ("pre", preprocessor),
                ("reg", lgb.LGBMRegressor(**params)),
            ])
            return pipe, {}

        def metric(y_true, y_pred):
            return -np.sqrt(mean_squared_error(y_true, y_pred))  # negative RMSE

        folds = spatial_cross_validate(
            X, y, groups, weights, n_splits=n_splits,
            model_factory=factory, metric_fn=metric,
        )
        if not folds:
            return float("inf")
        return -np.mean([f["score"] for f in folds])  # minimize RMSE

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        study = optuna.create_study(direction="minimize")
        study.optimize(objective, n_trials=n_trials, show_progress_bar=False)

    best = default_regressor_params()
    best.update(study.best_params)
    return best


def tune_classifier(
    X: pd.DataFrame,
    y: np.ndarray,
    groups: np.ndarray,
    weights: np.ndarray | None = None,
    n_trials: int = 50,
    n_splits: int = 5,
    pos_rate: float = 0.15,
) -> dict:
    """Optuna study for LGBMClassifier. Returns best params."""
    if not HAS_OPTUNA or not HAS_LIGHTGBM:
        return default_classifier_params(pos_rate)

    def objective(trial):
        params = {
            "objective": "binary",
            "n_estimators": trial.suggest_int("n_estimators", 200, 1000),
            "max_depth": trial.suggest_int("max_depth", 4, 8),
            "learning_rate": trial.suggest_float("learning_rate", 0.01, 0.15, log=True),
            "num_leaves": trial.suggest_int("num_leaves", 15, 63),
            "min_child_samples": trial.suggest_int("min_child_samples", 10, 50),
            "subsample": trial.suggest_float("subsample", 0.6, 0.9),
            "colsample_bytree": trial.suggest_float("colsample_bytree", 0.6, 0.9),
            "reg_alpha": trial.suggest_float("reg_alpha", 1e-3, 10.0, log=True),
            "reg_lambda": trial.suggest_float("reg_lambda", 1e-3, 10.0, log=True),
            "scale_pos_weight": trial.suggest_float("scale_pos_weight", 1.0, 10.0),
            "random_state": 42,
            "verbose": -1,
            "n_jobs": -1,
        }

        preprocessor = build_preprocessor()

        def factory():
            pipe = Pipeline([
                ("pre", preprocessor),
                ("cls", lgb.LGBMClassifier(**params)),
            ])
            return pipe, {}

        def metric(y_true, y_pred_proba):
            return average_precision_score(y_true, y_pred_proba)

        folds = spatial_cross_validate(
            X, y, groups, weights, n_splits=n_splits,
            model_factory=factory, metric_fn=metric,
        )
        if not folds:
            return 0.0
        return np.mean([f["score"] for f in folds])

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        study = optuna.create_study(direction="maximize")
        study.optimize(objective, n_trials=n_trials, show_progress_bar=False)

    best = default_classifier_params(pos_rate)
    best.update(study.best_params)
    return best


# ── Dual-model training ─────────────────────────────────────────────

def generate_oof_predictions(
    X: pd.DataFrame,
    y_reg: np.ndarray,
    groups: np.ndarray,
    reg_params: dict,
    weights: np.ndarray | None = None,
    n_splits: int = 5,
) -> np.ndarray:
    """Generate out-of-fold regressor predictions for stacking.

    Returns an array of OOF predictions aligned with the input indices.
    """
    if not HAS_LIGHTGBM:
        return np.full(len(X), -90.0)

    oof = np.full(len(X), np.nan)
    unique_groups = np.unique(groups)
    n_splits = min(n_splits, len(unique_groups))
    if n_splits < 2:
        return np.full(len(X), -90.0)

    gkf = GroupKFold(n_splits=n_splits)
    preprocessor = build_preprocessor()

    for train_idx, test_idx in gkf.split(X, y_reg, groups):
        X_tr, X_te = X.iloc[train_idx], X.iloc[test_idx]
        y_tr = y_reg[train_idx]
        w_tr = weights[train_idx] if weights is not None else None

        pipe = Pipeline([
            ("pre", preprocessor),
            ("reg", lgb.LGBMRegressor(**reg_params)),
        ])
        fit_kwargs = {}
        if w_tr is not None:
            fit_kwargs["reg__sample_weight"] = w_tr
        pipe.fit(X_tr, y_tr, **fit_kwargs)
        oof[test_idx] = pipe.predict(X_te)

    # Fill any NaNs with median
    median_val = np.nanmedian(oof)
    oof = np.where(np.isnan(oof), median_val, oof)
    return oof


def train_dual_model(
    feature_df: pd.DataFrame,
    labels: pd.DataFrame,
    reg_params: dict | None = None,
    cls_params: dict | None = None,
    tune: bool = False,
    n_optuna_trials: int = 50,
) -> dict:
    """Train the dual LightGBM model (regressor + stacked classifier).

    Parameters
    ----------
    feature_df : DataFrame with all v3 feature columns
    labels : DataFrame with columns: is_deadzone, signal_target,
             sample_weight, regression_weight, label_source

    Returns
    -------
    dict with keys: regressor, classifier, preprocessor, metadata, oof_predictions
    """
    if not HAS_LIGHTGBM:
        raise ImportError("lightgbm is required for v3 model training")

    # Prepare targets
    y_cls = labels["is_deadzone"].values.astype(int)
    y_reg = labels["signal_target"].values.astype(float)
    w_cls = labels["sample_weight"].values.astype(float)
    w_reg = labels["regression_weight"].values.astype(float)

    # Only train regressor on rows with valid signal targets
    reg_valid = ~np.isnan(y_reg)

    # Spatial fold groups
    groups = assign_spatial_fold_groups(
        feature_df["latitude"].values,
        feature_df["longitude"].values,
    )

    pos_rate = float(y_cls.mean()) if len(y_cls) > 0 else 0.15

    # ── Step 1: Tune / set regressor params ──
    if tune and reg_params is None:
        print("Tuning regressor...")
        reg_params = tune_regressor(
            feature_df[reg_valid], y_reg[reg_valid],
            groups[reg_valid], w_reg[reg_valid],
            n_trials=n_optuna_trials,
        )
    elif reg_params is None:
        reg_params = default_regressor_params()

    # ── Step 2: Tune / set classifier params ──
    if tune and cls_params is None:
        print("Tuning classifier...")
        cls_params = tune_classifier(
            feature_df, y_cls, groups, w_cls,
            n_trials=n_optuna_trials, pos_rate=pos_rate,
        )
    elif cls_params is None:
        cls_params = default_classifier_params(pos_rate)

    # ── Step 3: Generate OOF predictions for stacking ──
    print("Generating out-of-fold regressor predictions...")
    oof_preds = generate_oof_predictions(
        feature_df[reg_valid], y_reg[reg_valid],
        groups[reg_valid], reg_params, w_reg[reg_valid],
    )
    # Map OOF back to full dataset
    full_oof = np.full(len(feature_df), np.nanmedian(oof_preds) if len(oof_preds) > 0 else -90.0)
    full_oof[reg_valid] = oof_preds

    # ── Step 4: Train final regressor on all valid data ──
    print("Training final regressor...")
    preprocessor = build_preprocessor()

    regressor_pipe = Pipeline([
        ("pre", preprocessor),
        ("reg", lgb.LGBMRegressor(**reg_params)),
    ])
    reg_fit_kwargs = {"reg__sample_weight": w_reg[reg_valid]}
    regressor_pipe.fit(feature_df[reg_valid], y_reg[reg_valid], **reg_fit_kwargs)

    # ── Step 5: Add OOF as feature, train classifier ──
    print("Training final classifier with stacked signal prediction...")
    cls_feature_df = feature_df.copy()
    cls_feature_df["signal_pred_oof"] = full_oof

    # Update feature lists for classifier
    cls_numeric = NUMERIC_FEATURES_V3 + ["signal_pred_oof"]
    cls_preprocessor = build_preprocessor(
        numeric_features=cls_numeric,
        categorical_features=CATEGORICAL_FEATURES_V3,
    )

    classifier_pipe = Pipeline([
        ("pre", cls_preprocessor),
        ("cls", lgb.LGBMClassifier(**cls_params)),
    ])
    cls_fit_kwargs = {"cls__sample_weight": w_cls}
    classifier_pipe.fit(cls_feature_df, y_cls, **cls_fit_kwargs)

    # ── Step 6: Evaluate ──
    print("Evaluating...")
    metrics = evaluate_dual_model(
        regressor_pipe, classifier_pipe, feature_df, cls_feature_df,
        y_reg, y_cls, reg_valid, groups, labels,
    )

    return {
        "regressor": regressor_pipe,
        "classifier": classifier_pipe,
        "reg_params": reg_params,
        "cls_params": cls_params,
        "metrics": metrics,
        "oof_predictions": full_oof,
        "pos_rate": pos_rate,
        "training_row_count": len(feature_df),
    }


# ── Evaluation ──────────────────────────────────────────────────────

def evaluate_dual_model(
    regressor_pipe,
    classifier_pipe,
    feature_df: pd.DataFrame,
    cls_feature_df: pd.DataFrame,
    y_reg: np.ndarray,
    y_cls: np.ndarray,
    reg_valid: np.ndarray,
    groups: np.ndarray,
    labels: pd.DataFrame,
) -> dict:
    """Compute comprehensive evaluation metrics for both models."""
    metrics: dict = {}

    # ── Regressor metrics (on valid rows) ──
    if reg_valid.any():
        reg_pred = regressor_pipe.predict(feature_df[reg_valid])
        y_reg_valid = y_reg[reg_valid]
        valid_mask = ~np.isnan(y_reg_valid)
        if valid_mask.any():
            rp = reg_pred[valid_mask]
            yr = y_reg_valid[valid_mask]
            metrics["regressor"] = {
                "rmse": float(np.sqrt(mean_squared_error(yr, rp))),
                "mae": float(mean_absolute_error(yr, rp)),
                "r2": float(r2_score(yr, rp)),
                "median_ae": float(np.median(np.abs(yr - rp))),
            }

    # ── Classifier metrics ──
    if len(y_cls) > 0 and len(np.unique(y_cls)) > 1:
        cls_proba = classifier_pipe.predict_proba(cls_feature_df)[:, 1]
        cls_pred = (cls_proba >= 0.5).astype(int)

        prec, rec, f1, _ = precision_recall_fscore_support(
            y_cls, cls_pred, average="binary", zero_division=0
        )
        metrics["classifier"] = {
            "accuracy": float(accuracy_score(y_cls, cls_pred)),
            "roc_auc": float(roc_auc_score(y_cls, cls_proba)),
            "pr_auc": float(average_precision_score(y_cls, cls_proba)),
            "precision": float(prec),
            "recall": float(rec),
            "f1": float(f1),
        }

        # Per-tier metrics
        for tier in labels["label_source"].unique():
            tier_mask = labels["label_source"].values == tier
            if tier_mask.sum() > 10 and len(np.unique(y_cls[tier_mask])) > 1:
                tier_proba = cls_proba[tier_mask]
                tier_y = y_cls[tier_mask]
                metrics[f"classifier_tier_{tier}"] = {
                    "pr_auc": float(average_precision_score(tier_y, tier_proba)),
                    "count": int(tier_mask.sum()),
                    "positive_rate": float(tier_y.mean()),
                }

    # ── Spatial CV score (quick, 3-fold) ──
    if HAS_LIGHTGBM and len(np.unique(groups)) >= 3:
        def factory():
            pipe = Pipeline([
                ("pre", build_preprocessor()),
                ("cls", lgb.LGBMClassifier(**default_classifier_params())),
            ])
            return pipe, {}

        cv_results = spatial_cross_validate(
            feature_df, y_cls, groups, n_splits=min(3, len(np.unique(groups))),
            model_factory=factory,
            metric_fn=lambda yt, yp: average_precision_score(yt, yp)
            if len(np.unique(yt)) > 1 else 0.0,
        )
        if cv_results:
            metrics["spatial_cv_pr_auc"] = float(
                np.mean([f["score"] for f in cv_results])
            )
            metrics["spatial_cv_folds"] = cv_results

    return metrics


def save_training_report(metrics: dict, report_dir: str | Path) -> None:
    """Save metrics to a JSON summary file."""
    report_dir = Path(report_dir)
    report_dir.mkdir(parents=True, exist_ok=True)
    with open(report_dir / "summary.json", "w") as f:
        json.dump(metrics, f, indent=2, default=str)
    print(f"Report saved to {report_dir / 'summary.json'}")
