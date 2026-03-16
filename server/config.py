import os
from pathlib import Path


BASE_DIR = Path(__file__).resolve().parent


class Config:
    TESTING = False
    SECRET_KEY = os.getenv("SECRET_KEY", "dev-network-cell-analyzer-secret")
    SQLALCHEMY_DATABASE_URI = os.getenv(
        "DATABASE_URL",
        f"sqlite:///{BASE_DIR / 'instance' / 'network_cell_analyzer.db'}",
    )
    SQLALCHEMY_TRACK_MODIFICATIONS = False
    JSON_SORT_KEYS = False
    TIMEZONE = os.getenv("APP_TIMEZONE", "Asia/Beirut")
    ACTIVE_DEVICE_MINUTES = int(os.getenv("ACTIVE_DEVICE_MINUTES", "5"))
    DEFAULT_PAGE_SAMPLE_LIMIT = int(os.getenv("DEFAULT_PAGE_SAMPLE_LIMIT", "20"))
    HEATMAP_DEFAULT_POINT_LIMIT = int(os.getenv("HEATMAP_DEFAULT_POINT_LIMIT", "1200"))
    ACCESS_TOKEN_MAX_AGE_SECONDS = int(os.getenv("ACCESS_TOKEN_MAX_AGE_SECONDS", "86400"))
    REFRESH_TOKEN_MAX_AGE_SECONDS = int(os.getenv("REFRESH_TOKEN_MAX_AGE_SECONDS", "2592000"))
    DEADZONE_MODEL_PATH = os.getenv(
        "DEADZONE_MODEL_PATH",
        str(BASE_DIR / "instance" / "ml" / "deadzone_model.joblib"),
    )


class TestConfig(Config):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = "sqlite:///:memory:"
