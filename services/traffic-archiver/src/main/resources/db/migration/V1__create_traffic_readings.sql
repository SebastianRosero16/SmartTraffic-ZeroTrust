CREATE TABLE traffic_readings (
    event_id VARCHAR(255) PRIMARY KEY,
    sensor_id VARCHAR(255) NOT NULL,
    zone_id VARCHAR(255) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    vehicle_count INTEGER NOT NULL,
    CONSTRAINT ck_traffic_readings_event_id_non_blank CHECK (btrim(event_id) <> ''),
    CONSTRAINT ck_traffic_readings_sensor_id_non_blank CHECK (btrim(sensor_id) <> ''),
    CONSTRAINT ck_traffic_readings_zone_id_non_blank CHECK (btrim(zone_id) <> ''),
    CONSTRAINT ck_traffic_readings_vehicle_count_non_negative CHECK (vehicle_count >= 0)
);