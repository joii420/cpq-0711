-- V106: 全局变量变更日志 (P2: 行级审计)
--
-- 决策依据 (用户已确认):
--   #1 行级粒度 (一次值变=一条记录) 与"保存即生效"对齐
--   #2 仅 PRICING_MANAGER+ 可写, 由 Resource 层 @RoleAllowed 保障
--   #3 二次确认 modal — 前端 UI 行为
--   #7 直接生效 (不审批) — 服务层无审批门槛
--
-- 用途: 给「全局变量配置」页底部的变更历史面板供查询.
-- 写入时机: 任何对 costing_element_price / costing_material_price / costing_exchange_rate
-- 当前默认 PUBLISHED 版本明细的 INSERT / UPDATE / DELETE 都同步落一条日志.

CREATE TABLE IF NOT EXISTS global_variable_change_log (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    var_code      VARCHAR(64)  NOT NULL,         -- 引用 global_variable_definition.code
    key_id        VARCHAR(200) NOT NULL,         -- 复合 key 拼接: "element_code=Cu" 或 "from_currency=CNY;to_currency=USD"
    action        VARCHAR(20)  NOT NULL,         -- INSERT | UPDATE | DELETE
    old_value     NUMERIC(20, 10),
    new_value     NUMERIC(20, 10),
    changed_by    UUID,                          -- 关联 "user".id (NULL = 系统)
    changed_by_name VARCHAR(100),                -- 冗余存名字, 避免外连 user 表
    note          TEXT,                          -- 用户填写的变更原因
    changed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_gvcl_action CHECK (action IN ('INSERT', 'UPDATE', 'DELETE'))
);

CREATE INDEX IF NOT EXISTS idx_gvcl_var_code_changed_at
    ON global_variable_change_log (var_code, changed_at DESC);

CREATE INDEX IF NOT EXISTS idx_gvcl_var_code_key_id
    ON global_variable_change_log (var_code, key_id, changed_at DESC);

COMMENT ON TABLE global_variable_change_log IS
    'V106: 全局变量行级变更日志. 给"全局变量配置"页变更历史面板查询用';
COMMENT ON COLUMN global_variable_change_log.key_id IS
    'key 列拼接字符串. 单键: "element_code=Cu"; 复合键: "from_currency=CNY;to_currency=USD"';
