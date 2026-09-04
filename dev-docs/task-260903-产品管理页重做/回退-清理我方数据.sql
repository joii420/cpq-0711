-- ═══════════════════════════════════════════════════════════════════════════
-- task-260903 · 回退：清理我方灌入的样例数据
--
-- 用途：把 ds_quote_* 恢复到「我方零数据」状态。
--       典型场景 = 重开 AC-13 空态窗口（它验的是「表 0 行时页面显示空态」）。
--
-- 🚨 条件写死为 `样例-报价数据.xlsx` 的 42 个料号 / 17 个客户料号主键，**正向 IN 列举**。
-- 🚫 严禁改成反向条件（`NOT LIKE 'TEST-DS-%'` / `NOT IN (...)` / 无 WHERE）——
--    那会把 task-260902 及他人的数据一起带走，属 CLAUDE.md §3.2 数据销毁红线。
-- 🚫 严禁清表类 DDL/DML。
--
-- ⚠️ 前提：本脚本只回收**我方 42 个料号**。执行前若发现表里有不属于该集合的行，
--    **停下报主线，不要扩大删除条件** —— 那说明有别的会话又往这批表里写了东西。
--    脚本内已内置该自检，命中会 RAISE 中止。
--
-- 🚩 2026-09-03 实测教训（本文件存在的理由）：
--    源表 material_master 会因**真实业务活动**漂移 —— 当天 11:52 新增了料号
--    0526-2609000001，导致「实时查源表」的灌数脚本灌出 43/18/59/50 而不是 42/17/58/48。
--    ⇒ 基线必须**冻结成显式列表**，不能实时查源表。本文件就是那份冻结列表。
--
-- 影响面（2026-09-03 实测）：material 42 / customer_part 17 / material_bom 58
--                          / element_bom 48 / material_bom_history 9
-- 可恢复性：可由 seed 脚本 + 本目录 `样例-报价数据.xlsx` 完整重建。
-- ═══════════════════════════════════════════════════════════════════════════
BEGIN;

CREATE TEMP TABLE _ours_material (material_no varchar(128) PRIMARY KEY) ON COMMIT DROP;
INSERT INTO _ours_material VALUES
    ('1630010773'), ('2101110225'), ('2111410069'),
    ('2120011658'), ('2120011659'), ('3110520789'),
    ('3110520790'), ('3111320634'), ('3111320635'),
    ('3111320636'), ('3111320637'), ('3112230066'),
    ('3112230067'), ('3120018220'), ('COMP1'),
    ('COMP2'), ('S-1630010773'), ('S-2120011658'),
    ('S-2120011659'), ('S-3110520789'), ('S-3110520790'),
    ('S-3111320634'), ('S-3111320635'), ('S-3111320636'),
    ('S-3111320637'), ('S-3120014539'), ('S-3120018220'),
    ('TEST0813-COMP01'), ('TEST0813-COMP01-BD1'), ('TEST0813-P01'),
    ('TEST0813-P01-BD1'), ('TEST-P06-MAT'), ('TEST-Q13-CODE'),
    ('VS-FG01'), ('VS-RM01'), ('VS-RM02'),
    ('VS-RM03'), ('VS-RM04'), ('VS-RM05'),
    ('VS-RM06'), ('VS-SA02'), ('VS-SA03');

CREATE TEMP TABLE _ours_custpart (customer_no varchar(20), customer_product_no varchar(128)) ON COMMIT DROP;
INSERT INTO _ours_custpart VALUES
    ('C1','CP-TEST-Q13-CODE'), ('CUST-0001','CP-S-80011'),
    ('CUST-0001','CP-W-1001'), ('CUST-0002','11001101'),
    ('CUST-0004','CP-0028-2609000001'), ('CUST-0004','CP-0028-2609000002'),
    ('CUST-0004','CP-0028-2609000003'), ('CUST-0004','CP-0028-2609000004'),
    ('CUST-0004','CP-0028-2609000005'), ('CUST-0004','CP-0028-2609000006'),
    ('CUST-0004','CP-0028-2609000007'), ('CUST-0004','CP-0028-2609000008'),
    ('CUST-0004','CP-0028-2609000009'), ('CUST-0004','CP-0028-2609000010'),
    ('CUST-0004','CP-0028-2609000011'), ('Q13CUST0617','CP-0046-2609000001'),
    ('Q13CUST0617','CP-0046-2609000002');

-- 执行前自检：不属于我方集合的行必须为 0，否则中止并报主线
DO $$
DECLARE alien int;
BEGIN
  SELECT count(*) INTO alien FROM ds_quote_material m
   WHERE NOT EXISTS (SELECT 1 FROM _ours_material o WHERE o.material_no = m.material_no);
  IF alien > 0 THEN
    RAISE EXCEPTION '🚨 停下报告：ds_quote_material 有 % 行不属于我方 42 个料号集合。'
                    '不要扩大删除条件，先查清是谁写的。', alien;
  END IF;
END $$;

DELETE FROM ds_quote_material_bom_history
 WHERE material_no IN (SELECT material_no FROM _ours_material);
DELETE FROM ds_quote_material_bom
 WHERE material_no IN (SELECT material_no FROM _ours_material);
DELETE FROM ds_quote_element_bom
 WHERE material_no IN (SELECT material_no FROM _ours_material);
DELETE FROM ds_quote_customer_part cp
 USING _ours_custpart o
 WHERE cp.customer_no = o.customer_no AND cp.customer_product_no = o.customer_product_no;
DELETE FROM ds_quote_material
 WHERE material_no IN (SELECT material_no FROM _ours_material);

-- 回退后自检
SELECT 'material' t, count(*) FROM ds_quote_material
UNION ALL SELECT 'customer_part', count(*) FROM ds_quote_customer_part
UNION ALL SELECT 'material_bom',  count(*) FROM ds_quote_material_bom
UNION ALL SELECT 'element_bom',   count(*) FROM ds_quote_element_bom
UNION ALL SELECT 'bom_history',   count(*) FROM ds_quote_material_bom_history;

COMMIT;

-- 🚫 本脚本不碰：ds_cost_*（核价侧，task-260902）、ds_quote_plating_scheme（对方 AC-50 的 2 行）
