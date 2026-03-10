import os
from pathlib import Path


BASE_DIR = Path(__file__).resolve().parent


class Config:
    TESTING = False
    SQLALCHEMY_DATABASE_URI = os.getenv(
        "DATABASE_URL",
        f"sqlite:///{BASE_DIR / 'instance' / 'network_cell_analyzer.db'}",
    )
    SQLALCHEMY_TRACK_MODIFICATIONS = False
    JSON_SORT_KEYS = False
    TIMEZONE = os.getenv("APP_TIMEZONE", "Asia/Beirut")
    ACTIVE_DEVICE_MINUTES = int(os.getenv("ACTIVE_DEVICE_MINUTES", "5"))
    DEFAULT_PAGE_SAMPLE_LIMIT = int(os.getenv("DEFAULT_PAGE_SAMPLE_LIMIT", "20"))


class TestConfig(Config):
    TESTING = True
    SQLALCHEMY_DATABASE_URI = "sqlite:///:memory:"
