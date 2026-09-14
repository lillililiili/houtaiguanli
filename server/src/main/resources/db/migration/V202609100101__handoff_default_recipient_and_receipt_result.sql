-- 阶段 18（决策 18-14）：飞行风险的流程到"通知上级"为止，回执"已驱离"即闭环，风险不进处置授权。
--
-- 两处改动：
-- 1) 接收方可以标默认。风险通知在页面上不该再让值班员选接收方——上级就那一个，
--    每次都问一遍既慢又容易选错。处罚移送仍必须显式指定（移送给谁是案件的一部分，不能默认）。
-- 2) 回执带结果。原来只知道"送到了/被签收了"，但风险闭环要的是"驱离了没有"——
--    签收不等于处理完，把这两件事混成一个状态，页面上就永远回答不了"这条风险结束了吗"。

ALTER TABLE handoff_recipient ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

-- 升级库上已经有接收方了：把风险通知那一路的现有接收方标成默认，否则升级后"不传接收方"会直接 400。
-- 只标一个：多个默认等于没有默认，服务端还得再猜一次。
UPDATE handoff_recipient SET is_default = TRUE
WHERE handoff_type = 'RISK_NOTICE' AND enabled = TRUE
  AND recipient_id = (SELECT MIN(recipient_id) FROM handoff_recipient
                      WHERE handoff_type = 'RISK_NOTICE' AND enabled = TRUE);

ALTER TABLE handoff ADD COLUMN receipt_result VARCHAR(32);

-- 取值只对风险通知有意义：DISPERSED 已驱离、NOT_DISPERSED 未驱离；其余交接类型留空。
-- 约束写成"要么为空、要么是这两个之一"，而不是允许任意字符串——回执是闭环判据，混进一个拼错的值
-- 会让这条风险永远结不了案，而且从页面上看不出哪里不对。
ALTER TABLE handoff ADD CONSTRAINT ck_stage18_handoff_receipt_result
    CHECK (receipt_result IS NULL OR receipt_result IN ('DISPERSED', 'NOT_DISPERSED'));
