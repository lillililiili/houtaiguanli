-- 阶段 14：处罚案件域的 PostgreSQL 专属约束（版本化迁移，H2 测试不加载本目录）。
--
-- 为什么是版本化而不是 R__（决策 13-32 修订）：R__ 只在校验和变化时重跑，
-- 若某个库先停在 0102 之前的版本，带守卫的 R__ 会被记为"已应用"而跳过，之后再前进也不会重跑——
-- 触发器就此静默缺失，事后没有任何报错或日志。版本化迁移按序在 0102 之后只跑一次，表必然已存在，不需要守卫。

-- 案件事件流只增。办案过程是有法律后果的事实链：谁立的案、谁定的裁量、谁复核的，
-- 都可能成为行政诉讼里的证据。改一行等于改卷宗，所以在库层堵死，而不是只靠应用层自觉。
CREATE OR REPLACE FUNCTION prevent_stage14_case_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'punishment case events are append-only' USING ERRCODE = '23514';
END;
$$;

DROP TRIGGER IF EXISTS trg_stage14_case_event_append_only ON punishment_case_event;
CREATE TRIGGER trg_stage14_case_event_append_only
    BEFORE UPDATE OR DELETE ON punishment_case_event
    FOR EACH ROW EXECUTE FUNCTION prevent_stage14_case_event_mutation();

-- 复核只增：复核结论是案件定性的依据，改一条等于改结论。
CREATE OR REPLACE FUNCTION prevent_stage14_review_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'punishment reviews are append-only' USING ERRCODE = '23514';
END;
$$;

DROP TRIGGER IF EXISTS trg_stage14_review_append_only ON punishment_review;
CREATE TRIGGER trg_stage14_review_append_only
    BEFORE UPDATE OR DELETE ON punishment_review
    FOR EACH ROW EXECUTE FUNCTION prevent_stage14_review_mutation();

-- 决定书出具后内容冻结：只允许作废相关的列发生变化（决策 14-10）。
-- 不能整表禁写——作废是合法且必要的动作；也不能整表放开——那样"已出具的文书"可以被悄悄改掉金额或事由，
-- 而下载出去的那份纸已经在外面了。逐列比对是这两者之间唯一诚实的位置。
CREATE OR REPLACE FUNCTION prevent_stage14_document_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'decision documents cannot be deleted' USING ERRCODE = '23514';
    END IF;
    -- 状态只能从 ISSUED 走到 REVOKED（决策 14-23）：反向"复活"一份已作废的文书，
    -- 等于让一份已经对外说过"作废"的处罚决定重新生效，当事人那边无从对账。
    IF OLD.status = 'REVOKED' AND NEW.status <> 'REVOKED' THEN
        RAISE EXCEPTION 'revoked decision documents cannot be reinstated' USING ERRCODE = '23514';
    END IF;
    -- 作废痕迹一经写下就不可再动（决策 14-30）：不只是不能清空，**改成别的值也不行**。
    -- 只堵清空的话，把理由从"金额计算有误"改成"当事人申请撤销"照样能过——
    -- 那比清空更隐蔽：卷宗里仍有一条作废记录，只是它说的已经不是当时那件事了。
    IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
        RAISE EXCEPTION 'revocation timestamp is immutable once recorded' USING ERRCODE = '23514';
    END IF;
    IF OLD.revoke_reason IS NOT NULL AND NEW.revoke_reason IS DISTINCT FROM OLD.revoke_reason THEN
        RAISE EXCEPTION 'revocation reason is immutable once recorded' USING ERRCODE = '23514';
    END IF;
    IF (NEW.document_id, NEW.document_no, NEW.case_id, NEW.discretion_id, NEW.template_version,
        NEW.fields::text, NEW.rendered_sha256, NEW.issued_by, NEW.issued_by_name, NEW.issued_at)
       IS DISTINCT FROM
       (OLD.document_id, OLD.document_no, OLD.case_id, OLD.discretion_id, OLD.template_version,
        OLD.fields::text, OLD.rendered_sha256, OLD.issued_by, OLD.issued_by_name, OLD.issued_at) THEN
        RAISE EXCEPTION 'issued decision documents are immutable except revocation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_stage14_document_immutable ON penalty_decision_document;
CREATE TRIGGER trg_stage14_document_immutable
    BEFORE UPDATE OR DELETE ON penalty_decision_document
    FOR EACH ROW EXECUTE FUNCTION prevent_stage14_document_mutation();

-- 已确认的裁量是文书的依据，冻结后不得再改（决策 14-9）；只允许被置为 SUPERSEDED（复核要求重做时）。
CREATE OR REPLACE FUNCTION prevent_stage14_discretion_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'penalty discretions cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'CONFIRMED' AND NEW.status <> 'SUPERSEDED' THEN
        RAISE EXCEPTION 'confirmed discretions are immutable except being superseded'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'CONFIRMED' AND (NEW.fine_amount, NEW.penalty_type, NEW.violation_code, NEW.rule_code)
       IS DISTINCT FROM (OLD.fine_amount, OLD.penalty_type, OLD.violation_code, OLD.rule_code) THEN
        RAISE EXCEPTION 'confirmed discretion figures are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_stage14_discretion_immutable ON penalty_discretion;
CREATE TRIGGER trg_stage14_discretion_immutable
    BEFORE UPDATE OR DELETE ON penalty_discretion
    FOR EACH ROW EXECUTE FUNCTION prevent_stage14_discretion_mutation();
