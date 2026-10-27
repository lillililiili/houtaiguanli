CREATE FUNCTION prevent_measured_handoff_basis_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Measured punishment basis is append only';
END;
$$;
CREATE TRIGGER trg_measured_handoff_basis_immutable BEFORE UPDATE OR DELETE ON handoff_measured_basis
FOR EACH ROW EXECUTE FUNCTION prevent_measured_handoff_basis_mutation();
