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

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "device_id": self.device_id,
            "operator": self.operator,
            "signal_power": self.signal_power,
            "snr": self.snr,
            "network_type": self.network_type,
            "frequency_band": self.frequency_band,
            "cell_id": self.cell_id,
            "timestamp": self.timestamp.isoformat(),
        }


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

    def to_dict(self) -> dict:
        return {
            "device_id": self.device_id,
            "ip_address": self.ip_address,
            "mac_address": self.mac_address,
            "first_seen": self.first_seen.isoformat(),
            "last_seen": self.last_seen.isoformat(),
            "is_active": self.is_active,
        }
