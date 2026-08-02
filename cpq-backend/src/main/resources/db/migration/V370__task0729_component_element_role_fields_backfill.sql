-- =============================================================================
-- task-0729 · B0 前置（临时替代 B7 完整推导算法）：为 8 个已接价格策略的组件
-- 回填三个角色字段 element_code_field / element_price_field / element_currency_field
-- =============================================================================
-- 背景：B1（V368）已加这三列，但值一直未填 —— B7（组件三项显式绑定 + 保存期校验 +
-- 推导预填算法）尚未实现。B0（升版重算通道）S2「定位价格字段」硬性要求"直接读组件
-- 三个角色字段，运行期禁止正则解析 SQL"，若三列为空 S2 无法工作，B0 被阻塞。
--
-- 技术总监裁定（2026-08-01）：先按 backtask.md B7 给出的期望值临时手工回填，解除 B0
-- 阻塞；完整的"迁移期自动推导算法 + 保存期校验 + GET .../element-binding-suggest 端点"
-- 仍是 B7 的正式范围，本迁移不替代 B7，只是让 B0 现在能跑起来。
--
-- 值来源：逐个查询 8 个组件当前 fields JSON 确认（非凭空猜测）：
--   COMP-0049（核价·物料与元素BOM）：字段名"元素代码"(BASIC_DATA) / "元素单价"(INPUT_NUMBER,
--     is_amount=true) / 无货币字段 —— 与 backtask B7 表列出的期望值完全一致。
--   COMP-0021（报价·材料成本）：字段名"_元素"对应的业务字段名是"元素"（SQL 别名 _元素，
--     字段名去掉下划线前缀）/ "元素单价" / 无货币列。
--   COMP-0027/0090（同为报价·材料成本，闭包形态）：字段名同为"元素" / "元素单价" / 无货币列。
--   COMP-0102/0122/0130/0133（报价·材料成本，闭包形态 + 已配货币列）：实测这 4 个组件的
--     sql_template 均有 `cep.currency AS 货币` 输出列，且字段名"元素" / "元素单价" / "货币"
--     三者都在 fields 数组里（逐个已用 SQL 核实字段名与 SQL 输出列一致，非猜测）。
--
-- 【可逆】UPDATE 前值均为 NULL（B1 刚加列时的默认状态），回滚只需再置回 NULL：
--   UPDATE component SET element_code_field=NULL, element_price_field=NULL,
--     element_currency_field=NULL WHERE code IN ('COMP-0021','COMP-0027','COMP-0049',
--     'COMP-0090','COMP-0102','COMP-0122','COMP-0130','COMP-0133');
-- 【幂等】所有 UPDATE 都带 WHERE element_code_field IS NULL 判断，可安全重跑。
-- =============================================================================

UPDATE component
   SET element_code_field = '元素代码',
       element_price_field = '元素单价',
       element_currency_field = NULL,
       updated_at = now()
 WHERE code = 'COMP-0049'
   AND element_code_field IS NULL;

UPDATE component
   SET element_code_field = '元素',
       element_price_field = '元素单价',
       element_currency_field = NULL,
       updated_at = now()
 WHERE code IN ('COMP-0021', 'COMP-0027', 'COMP-0090')
   AND element_code_field IS NULL;

UPDATE component
   SET element_code_field = '元素',
       element_price_field = '元素单价',
       element_currency_field = '货币',
       updated_at = now()
 WHERE code IN ('COMP-0102', 'COMP-0122', 'COMP-0130', 'COMP-0133')
   AND element_code_field IS NULL;
