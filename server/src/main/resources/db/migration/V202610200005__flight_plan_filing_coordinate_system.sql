-- Keep historical locations unknown. Only a newly validated filing supplies this declaration.
ALTER TABLE flight_plan ADD COLUMN filing_coordinate_system VARCHAR(16);
ALTER TABLE flight_plan ADD CONSTRAINT ck_flight_filing_coordinate_system
    CHECK (filing_coordinate_system IS NULL OR filing_coordinate_system = 'WGS84');
