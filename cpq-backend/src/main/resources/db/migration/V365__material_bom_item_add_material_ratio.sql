-- V365: material_bom_item 新增「材质占比」列 material_ratio
-- spec: dev-docs/task-0708-导入报价单和导入核价单的数据落库规则澄清/
--       docs/table/报价系统Excel导入落库方案.md §物料BOM
--
-- 背景: 报价侧「物料BOM」sheet 新增可选列「材质占比」。全表 50+ 列中无语义可复用的空闲列 ——
--       composition_qty(组成数量)/base_qty(底数)/scrap_rate/defect_rate 均已被 handler 占用;
--       upper_limit_pct / lower_limit_pct 虽全表无值, 但语义是 ERP 标准「用量上下限%」,
--       复用即语义错配(AP-52), 故新增独立列。
--
-- 口径: 小数占比, 0.3 = 30%(与 element_bom_item.content「组成含量」同口径同精度 numeric(18,6),
--       不是 scrap_rate/defect_rate 那种百分数值)。
-- 归属: 报价侧(system_type='QUOTE')物料BOM 三态中仅「材质行」(characteristic='RECIPE')有意义;
--       零件/外购件行由 handler 显式置 NULL。核价侧(PRICING)不写该列, 恒 NULL。
-- 必填: 否 —— nullable, 无 CHECK 约束。「同一销售料号下多材质占比合计=1」无法在库层强制
--       (非必填意味着部分行为空时约束必然误伤), 该校验属业务/公式层。
-- 唯一键: 不动 uq_material_bom_item —— 占比是内容列, 不是键列。
--
-- 幂等: IF NOT EXISTS —— 开发期共享库可能已由手工 DDL 预置该列, 重复应用不失败。

ALTER TABLE material_bom_item ADD COLUMN IF NOT EXISTS material_ratio numeric(18,6);

COMMENT ON COLUMN material_bom_item.material_ratio IS
    '材质占比(小数口径, 0.3=30%); 报价侧物料BOM 材质行(characteristic=RECIPE)可选填, 零件/外购件行与核价侧恒 NULL';
