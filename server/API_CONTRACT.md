# API Contract

This document is the backend contract for the Android app.

## Base assumptions
- Content type for POST requests: `application/json`
- Default timezone for human-readable timestamps: `Asia/Beirut`
- Preferred timestamp format from the plan: `09 Mar 2026 02:30 PM`
- Also accepted:
  - `2026-03-09 14:30:00`
  - `2026-03-09T14:30:00`
  - ISO 8601 timestamps with timezone

## `POST /receive-data`

Stores a single cell reading and updates device activity.

### Required JSON fields
```json
{
  "device_id": "abc123-def456",
  "operator": "Alfa",
  "signal_power": -85,
  "network_type": "4G",
  "cell_id": "37100-81937409"
}
```

### Optional JSON fields
```json
{
  "snr": 12.5,
  "frequency_band": "Band 3 (1800MHz)",
  "timestamp": "09 Mar 2026 02:30 PM",
  "mac_address": "AA:BB:CC:DD:EE:FF"
}
```

### Success response
```json
{
  "message": "Data received"
}
```

### Error examples
```json
{
  "error": "Missing required fields: device_id, operator"
}
```

```json
{
  "error": "network_type must be one of: 2G, 3G, 4G, 5G"
}
```

## `GET /get-stats`

Returns per-device statistics.

### Query parameters
- `device_id` required
- `start` optional
- `end` optional

### Example
`/get-stats?device_id=abc123-def456&start=09 Mar 2026 12:00 PM&end=09 Mar 2026 03:00 PM`

### Success response
```json
{
  "connectivity_per_operator": {
    "Alfa": "75.0%",
    "Touch": "25.0%"
  },
  "connectivity_per_network_type": {
    "4G": "70.0%",
    "3G": "25.0%",
    "2G": "5.0%"
  },
  "avg_signal_per_network_type": {
    "4G": -82.3,
    "3G": -91.5,
    "2G": -98.1
  },
  "avg_snr_per_network_type": {
    "4G": 15.2
  },
  "avg_signal_per_device": {
    "abc123-def456": -85.7
  },
  "avg_signal_device": -85.7,
  "record_count": 40,
  "first_timestamp": "2026-03-09T12:00:00+00:00",
  "last_timestamp": "2026-03-09T15:00:00+00:00"
}
```

## `GET /get-stats/avg-all`

Returns cross-device aggregates.

### Query parameters
- `start` optional
- `end` optional

### Success response
```json
{
  "avg_signal_all_devices": -86.3,
  "avg_snr_all_devices": 13.4,
  "avg_signal_per_device": {
    "demo-phone-01": -87.0,
    "demo-phone-02": -88.0
  },
  "record_count": 100,
  "unique_devices": 2
}
```

## `GET /central-stats`

Returns the server dashboard HTML page.

## `GET /device-stats`

Returns the per-device statistics HTML page.

### Query parameters
- `device_id` required
- `start` optional
- `end` optional

## `GET /healthz`

Simple health check.

### Success response
```json
{
  "status": "healthy"
}
```
