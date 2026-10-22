-- 反制方式在申请时保存；旧授权保持 NULL，不回填或改写历史动作。
ALTER TABLE disposal_authorization ADD COLUMN countermeasure_mode VARCHAR(24);
ALTER TABLE disposal_authorization ADD CONSTRAINT ck_disposal_countermeasure_mode
    CHECK (countermeasure_mode IS NULL OR
        (countermeasure_mode IN ('FORCE_LAND', 'DRIVE_AWAY') AND action_type = 'COUNTERMEASURE'
            AND channel IN ('COUNTERMEASURE_4CH', 'LINGYUN_B')));
