-- 反制完成后自动接信号干扰：子授权指向来源反制，同一来源只接一次。
ALTER TABLE disposal_authorization
    ADD chained_from_authorization_id VARCHAR(36);

ALTER TABLE disposal_authorization
    ADD CONSTRAINT fk_stage13_authorization_chained_from
        FOREIGN KEY (chained_from_authorization_id) REFERENCES disposal_authorization (authorization_id) ON DELETE RESTRICT;

-- 可空列上的 UNIQUE：未链式的行都是 NULL，PG/H2 都允许多个 NULL。
ALTER TABLE disposal_authorization
    ADD CONSTRAINT uk_stage13_authorization_chained_from UNIQUE (chained_from_authorization_id);
