-- Replay device simulator also registers meteorological and countermeasure devices.
-- Keep the original migration immutable and widen the binding contract for those
-- simulator-only device identities (and the already supported protocol families).
ALTER TABLE mqtt_device_binding DROP CONSTRAINT IF EXISTS ck_mqtt_device_type_abbr;
ALTER TABLE mqtt_device_binding ALTER COLUMN device_type_abbr TYPE VARCHAR(16);
ALTER TABLE mqtt_device_binding ADD CONSTRAINT ck_mqtt_device_type_abbr
    CHECK (device_type_abbr IN (
        'radar', '5ga', 'tdoa', 'aoa', 'dcd', 'rid', 'oe', 'dec', 'ifr', 'bsc',
        'weather', 'countermeasure'
    ));
