-- V213: 修正 global_variable_definition.source_view NOT NULL 约束 (V190 遗漏)
--
-- 背景:
--   V104 原 schema: source_view VARCHAR(100) NOT NULL  (当时所有 GV 都是 COSTING_VIEW 类型)
--   V190 引入 value_source_type ∈ {KV_TABLE, COSTING_VIEW} 分流, 设计意图为 KV_TABLE 类型
--   不需要 source_view (见 GlobalVariableDefinition.java:23 注释 "KV_TABLE 模式下可空"),
--   但 V190 ALTER 只加新列, 遗漏了把 source_view 改为 nullable.
--
-- 现象:
--   用户在 /global-variables UI 新建变量时, GlobalVariableService.createDefinition
--   的 INSERT 把 source_view 写死为 NULL (因为新建只走 KV_TABLE), 撞 NOT NULL 约束
--   → Hibernate ConstraintViolationException → GlobalExceptionMapper 包装为
--   409 + "Required field cannot be null".
--
-- 修复 (KISS, 单行 DDL):
--   仅 KV_TABLE 类型允许 source_view = NULL; COSTING_VIEW 类型应用层保证非空
--   (createDefinition 当前强制 KV_TABLE; COSTING_VIEW 仅由 Flyway V104/V173/V184 注入,
--   都显式提供了 source_view).
--
-- 影响:
--   - 现有 5 个 GV (ELEM_PRICE/MAT_PRICE/EXCHANGE_RATE/PROCESS_DEFAULT_PRICE/PROCESS_DEFAULT_YIELD)
--     的 source_view 列均已有值 (V104/V173/V184 种子设过), 不受影响
--   - 此后新建的 KV_TABLE 类型 GV 可以正确以 source_view=NULL 落库
--
-- 反向回滚 (如果需要):
--   ALTER TABLE global_variable_definition ALTER COLUMN source_view SET NOT NULL;
--   注意: 回滚前必须先把所有 source_view IS NULL 的行清掉或填值, 否则 SET NOT NULL 会失败.

ALTER TABLE global_variable_definition
    ALTER COLUMN source_view DROP NOT NULL;

COMMENT ON COLUMN global_variable_definition.source_view IS
    'V213: 物理视图/表名. COSTING_VIEW 类型必填, KV_TABLE 类型可空 (走 global_variable_value 单表).';
