-- 迁入旧后端的提前结束规则；仅替换无表依赖的函数定义，原迁移不变。
CREATE OR REPLACE FUNCTION prevent_stage9_airspace_version_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- 历史空域版本是已保存研判的输入证据：删除会让引用它的结论失去依据。
        RAISE EXCEPTION 'airspace versions cannot be deleted' USING ERRCODE = '23514';
    END IF;
    -- 唯一放开的写法：把这一版的失效时刻**往前收**（valid_to NULL → 非 NULL，或非 NULL → 更早的时刻）。
    -- 只放开这一列，是因为它表达的是"这一版从何时起不再生效"，属于新增事实；
    -- 几何、高度带、种类、生效起点一旦被研判引用就不能再改，改了等于篡改历史输入。
    -- 2026-09-13：原规则只允许 NULL → 非 NULL，于是"临时管制区提前结束"无法落库——
    -- 这类空域建立时就带结束时间，提前结束正是把它收短。往前收与关闭一个开放版本是同一件事
    -- （都让某一时刻之后不再生效），所以按同一条规则处理；延长与改回 NULL 仍然拒绝，
    -- 那是把一段已经结束的生效期重新打开。
    IF NEW.valid_to IS NULL OR NOT (OLD.valid_to IS NULL OR NEW.valid_to < OLD.valid_to) THEN
        RAISE EXCEPTION 'airspace versions are immutable except closing valid_to' USING ERRCODE = '23514';
    END IF;
    IF (NEW.airspace_version_id, NEW.airspace_id, NEW.version_no, NEW.kind_code, NEW.boundary,
        NEW.min_altitude_m, NEW.max_altitude_m, NEW.altitude_datum, NEW.valid_from, NEW.change_reason, NEW.created_at)
       IS DISTINCT FROM
       (OLD.airspace_version_id, OLD.airspace_id, OLD.version_no, OLD.kind_code, OLD.boundary,
        OLD.min_altitude_m, OLD.max_altitude_m, OLD.altitude_datum, OLD.valid_from, OLD.change_reason, OLD.created_at) THEN
        RAISE EXCEPTION 'only valid_to may change on an airspace version' USING ERRCODE = '23514';
    END IF;
    -- 关闭时刻必须晚于生效起点，否则会出现零长度或倒挂的生效区间。
    IF NEW.valid_to <= NEW.valid_from THEN
        RAISE EXCEPTION 'airspace version valid_to must be later than valid_from' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;
