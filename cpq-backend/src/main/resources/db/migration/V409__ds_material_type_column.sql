-- ============================================================================
-- task-260902 · 交付后模型变更 D-26：料号类型从「BOM 行的属性」改为「料号的属性」
--
-- 依据：需求文档.md §③ R-1.6（料号类型列）+ 闸门 A0 裁决 D-26
-- 服务的 AC：AC-56, AC-57, AC-58
--
-- 🚦 本迁移含 DROP COLUMN，已获用户明确批准（CLAUDE.md §3.2 三步前置已走完）：
--    影响面 = 6 张表的 1 列，实测行数 ds_quote_material_bom 58 / ds_cost_basic_material_bom 15
--    / ds_cost_detail_material_bom 14（history 表当前为空），列值全部丢弃且不可恢复。
--    旧列语义（报价「元素/材料」、核价「料件/材质」）用户裁定「确实不要了，直接删」。
--
-- 🚫 不许改 V405~V408 —— 它们的 checksum 已锁死（success=true 已应用到共享库）。
--
-- 🚩 已知且预期的连带效应（AC-58 专门验它，不得规避）：
--    input_type / component_type 都是 R-3 行指纹的比对项，删列后现存行的指纹必然全变
--    ⇒ 下次导入这三张 BOM 表会整组升版（不是 unchanged）。
--    这是「指纹反映当前比对项集合」这条不变量的正常表现，
--    🚫 不得为了让导入显示 unchanged 而把旧列排除在指纹外、做兼容或回写 row_fingerprint。
-- ============================================================================

-- ── 1. 移除 BOM 行上的旧「类型」列（6 处：3 张主表 + 3 张 _history）──────────
ALTER TABLE ds_quote_material_bom               DROP COLUMN input_type;
ALTER TABLE ds_quote_material_bom_history       DROP COLUMN input_type;

ALTER TABLE ds_cost_basic_material_bom          DROP COLUMN component_type;
ALTER TABLE ds_cost_basic_material_bom_history  DROP COLUMN component_type;

ALTER TABLE ds_cost_detail_material_bom         DROP COLUMN component_type;
ALTER TABLE ds_cost_detail_material_bom_history DROP COLUMN component_type;

-- ── 2. 在物料表上新增料号级「类型」列（3 处，均为免版本表，无 _history）──────────
-- 🚨 一律可空：本项目既定设计 —— NOT NULL 只加在轴列与免版本表主键列上，
--    其余必填由 Registry required=true + 导入 Phase 1 校验兜底，
--    这样必填失败走 AC-6 的 400 友好报告，而不是让 DB 抛 500。
ALTER TABLE ds_quote_material       ADD COLUMN material_type varchar(128);
ALTER TABLE ds_cost_basic_material  ADD COLUMN material_type varchar(128);
ALTER TABLE ds_cost_detail_material ADD COLUMN material_type varchar(128);

COMMENT ON COLUMN ds_quote_material.material_type       IS '类型（零件 / 外购件）';
COMMENT ON COLUMN ds_cost_basic_material.material_type  IS '类型（零件 / 外购件）';
COMMENT ON COLUMN ds_cost_detail_material.material_type IS '类型（零件 / 外购件）';

-- ============================================================================
-- task-260902 · 交付后模型变更 D-27：报价物料表新增「产品分类」列
--
-- 依据：需求文档.md §③ R-1.7（产品分类列）+ 裁决 D-27
-- 服务的 AC：AC-59, AC-60, AC-61
--
-- 🚦 合并进 V409 而不另占号：本迁移尚未应用到共享库（实查 cpq_db_0724 的
--    flyway_schema_history 最大 version = 408），checksum 未锁死，合并是安全的。
--    🚫 V405~V408 已 success=true，一个字节都不许改。
--
-- 三点裁决（用户 2026-09-03 逐条选定，见 D-27）：
--   ① 分类语义复用 product_category（未登记的分类 Phase 1 整份拒收）
--   ② 存分类编码 code（product_category_code_key UNIQUE，改名不断链）
--   ③ 只加报价侧 ds_quote_material 一张表 —— 核价两套的物料表【不加】，
--      与 material_type「三套都加」不是同一批，勿顺手补齐。
--
-- 🚫 刻意【不】设列 DEFAULT '000000'：PlainTableWriter 是全列 INSERT、每列显式绑参
--    （空格子绑 NULL），PG 的列 DEFAULT 在这条路径上【永不触发】。
--    「产品默认落默认分类」由导入 Phase 1 解析时填 '000000' 实现，AC-60 专门验这条。
--    加 DEFAULT 只会制造「看起来配了、实际没用」的假象。
-- ============================================================================

-- 🚩 长度以「字段矩阵.md」为准 = varchar(128)（矩阵由 gen_matrix.py 从建表规范 Excel 机器生成，
--    DatasetStructureAcTest.ts02 直接拿 DB 比矩阵）。
--    🚫 别按「code 看着不长就给 64」凭感觉定 —— 那样 ts02 必红，且 Registry↔DDL 自检会让服务起不来。
ALTER TABLE ds_quote_material ADD COLUMN category_code varchar(128);

COMMENT ON COLUMN ds_quote_material.category_code IS '产品分类编码（product_category.code；空值由导入 Phase 1 填默认分类 000000）';

-- ── 存量回填（§3.2 三步前置已走完并写进 R-1.7）─────────────────────────────
--    影响面 = 加列瞬间全部现有行，实测 42 行；命中面明确（WHERE category_code IS NULL）；
--    可恢复 = UPDATE ds_quote_material SET category_code = NULL 即还原。
UPDATE ds_quote_material SET category_code = '000000' WHERE category_code IS NULL;
