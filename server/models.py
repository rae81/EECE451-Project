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
    sim_slot = db.Column(db.Integer, nullable=True)
    subscription_id = db.Column(db.String(64), nullable=True)
    latitude = db.Column(db.Float, nullable=True, index=True)
    longitude = db.Column(db.Float, nullable=True, index=True)
    location_accuracy_m = db.Column(db.Float, nullable=True)
    timestamp = db.Column(
        db.DateTime(timezone=True),
        nullable=False,
        index=True,
        default=lambda: datetime.now(timezone.utc),
    )
    neighbor_cells = db.relationship(
        "NeighborCellData",
        back_populates="cell_record",
        cascade="all, delete-orphan",
        lazy="selectin",
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
            "sim_slot": self.sim_slot,
            "subscription_id": self.subscription_id,
            "latitude": self.latitude,
            "longitude": self.longitude,
            "location_accuracy_m": self.location_accuracy_m,
            "timestamp": self.timestamp.isoformat(),
            "neighbor_cells": [cell.to_dict() for cell in self.neighbor_cells],
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


class NeighborCellData(db.Model):
    __tablename__ = "neighbor_cell_data"

    id = db.Column(db.Integer, primary_key=True)
    cell_data_id = db.Column(db.Integer, db.ForeignKey("cell_data.id"), nullable=False, index=True)
    network_type = db.Column(db.String(10), nullable=True)
    cell_id = db.Column(db.String(64), nullable=True)
    signal_power = db.Column(db.Float, nullable=True)
    is_registered = db.Column(db.Boolean, nullable=False, default=False)

    cell_record = db.relationship("CellData", back_populates="neighbor_cells")

    def to_dict(self) -> dict:
        return {
            "network_type": self.network_type,
            "cell_id": self.cell_id,
            "signal_power": self.signal_power,
            "is_registered": self.is_registered,
        }


class SpeedTestResult(db.Model):
    __tablename__ = "speed_test_results"

    id = db.Column(db.Integer, primary_key=True)
    device_id = db.Column(db.String(64), nullable=False, index=True)
    operator = db.Column(db.String(50), nullable=True)
    network_type = db.Column(db.String(10), nullable=True)
    signal_power = db.Column(db.Integer, nullable=True)
    download_mbps = db.Column(db.Float, nullable=True)
    upload_mbps = db.Column(db.Float, nullable=True)
    latency_ms = db.Column(db.Float, nullable=True)
    timestamp = db.Column(
        db.DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
        index=True,
    )

    def to_dict(self) -> dict:
        return {
            "device_id": self.device_id,
            "operator": self.operator,
            "network_type": self.network_type,
            "signal_power": self.signal_power,
            "download_mbps": self.download_mbps,
            "upload_mbps": self.upload_mbps,
            "latency_ms": self.latency_ms,
            "timestamp": self.timestamp.isoformat(),
        }


class AlertRule(db.Model):
    __tablename__ = "alert_rules"

    id = db.Column(db.Integer, primary_key=True)
    device_id = db.Column(db.String(64), nullable=True, index=True)
    min_signal_power = db.Column(db.Integer, nullable=True)
    trigger_on_network_downgrade = db.Column(db.Boolean, nullable=False, default=False)
    is_enabled = db.Column(db.Boolean, nullable=False, default=True)
    created_at = db.Column(
        db.DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "device_id": self.device_id,
            "min_signal_power": self.min_signal_power,
            "trigger_on_network_downgrade": self.trigger_on_network_downgrade,
            "is_enabled": self.is_enabled,
            "created_at": self.created_at.isoformat(),
        }
