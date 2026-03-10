from datetime import datetime, timezone

from flask_sqlalchemy import SQLAlchemy


db = SQLAlchemy()


class CellData(db.Model):
    __tablename__ = "cell_data"

    id = db.Column(db.Integer, primary_key=True)
    device_id = db.Column(db.String(64), nullable=False, index=True)
    operator = db.Column(db.String(50), nullable=False)
    signal_power = db.Column(db.Integer, nullable=False)
    snr = db.Column(db.Float, nullable=True)
    network_type = db.Column(db.String(10), nullable=False, index=True)
    frequency_band = db.Column(db.String(50), nullable=True)
    cell_id = db.Column(db.String(64), nullable=False)
    timestamp = db.Column(
        db.DateTime(timezone=True),
        nullable=False,
        index=True,
        default=lambda: datetime.now(timezone.utc),
    )


class DeviceLog(db.Model):
    __tablename__ = "device_log"

    id = db.Column(db.Integer, primary_key=True)
    device_id = db.Column(db.String(64), nullable=False, unique=True, index=True)
    ip_address = db.Column(db.String(50), nullable=True)
    mac_address = db.Column(db.String(20), nullable=True)
    first_seen = db.Column(
        db.DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )
    last_seen = db.Column(
        db.DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )
    is_active = db.Column(db.Boolean, nullable=False, default=True)

