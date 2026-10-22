-- Remove the requested vendor wording from display metadata only.
-- Keep protocol identifiers, source relationships, rule parameters and all other data intact.
UPDATE ops_device
SET channel = TRIM(REPLACE(channel, '凌云', ''))
WHERE channel LIKE '%凌云%';

UPDATE source_type_catalog
SET spec_ref = REPLACE(spec_ref, '凌云', '')
WHERE spec_ref LIKE '%凌云%';

UPDATE fusion_config
SET note = REPLACE(note, '凌云', '')
WHERE note LIKE '%凌云%';
