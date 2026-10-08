-- Keep the incident identities so closing one of several incidents is not mistaken for a new fault.
ALTER TABLE flight_device_check_fault ALTER COLUMN incident_key SET DATA TYPE TEXT;
