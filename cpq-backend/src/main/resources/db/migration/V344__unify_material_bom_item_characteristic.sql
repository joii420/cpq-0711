-- V344: material_bom_item.characteristic 三态统一（RECIPE/ASSEMBLY/OUTSOURCED）
-- spec: docs/superpowers/specs/2026-07-20-material-bom-item-characteristic-三态统一-design.md
--
-- 存量回填 + cz_view 核价分支谓词修正。
-- ⚠️ 必须与同批代码改动（P06 childGk 移除 characteristic）一同上线，
--    否则迁移后 loadCurrentGroup/flip 匹配不到行 → 双 current。

-- ── 步骤 1: 删除语义矛盾的存量行 ──
-- PRICING + calc_type='元素' 但 component_no 命中料号主档、不命中材质库。
-- 已核实这批码既不在 material_recipe 也不在 element 主表，名称为"料2/料10"等测试数据，
-- 规则(元素→RECIPE)与命中表校正(料号→ASSEMBLY)给出相反答案 → 不猜，直接删。
DELETE FROM material_bom_item i
WHERE i.system_type = 'PRICING'
  AND i.calc_type = '元素'
  AND i.component_no IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM material_recipe r WHERE r.code = i.component_no)
  AND EXISTS (SELECT 1 FROM material_master m WHERE m.material_no = i.component_no);

-- ── 步骤 2: 规则回填 ──
-- 核价：calc_type 权威（'元素'→RECIPE，其余含 NULL→ASSEMBLY）
UPDATE material_bom_item
SET characteristic = CASE WHEN calc_type = '元素' THEN 'RECIPE' ELSE 'ASSEMBLY' END
WHERE system_type = 'PRICING'
  AND component_no IS NOT NULL;

-- 报价：存量 NULL 行来自 repair-2 之前的物料BOM 导入，语义等同 RECIPE
UPDATE material_bom_item
SET characteristic = 'RECIPE'
WHERE system_type = 'QUOTE'
  AND characteristic IS NULL
  AND component_no IS NOT NULL;

-- ── 步骤 3: 命中表交叉校正（仅报价侧）──
-- ⚠️ uq_material_bom_item 含 COALESCE(characteristic,'')。存量有 3 组历史重复行
--   （同一 component 同时在物料BOM 与组成件BOM，归并逻辑上线前写成两行，
--     靠 characteristic 不同才不撞键：0317-2607000006/1、0363-2607000009/1、0363-2607000009/2）。
--   若无差别校正，NULL 行会被校正成 ASSEMBLY 与兄弟行同键 → 唯一键冲突、迁移失败。
-- 规则：交叉校正只作用于"唯一键去掉 characteristic 后无兄弟行"的行；
--   有兄弟行者保持步骤 2 的 RECIPE，与兄弟行的 ASSEMBLY 天然区分。
-- 实际洗净 2 行：S-3120014539 的 991/992（标 ASSEMBLY 但在材质库）→ RECIPE。
-- OUTSOURCED 不参与校正（存量为 0，防御性排除，避免误翻新导入的外购件行）。
UPDATE material_bom_item i
SET characteristic = CASE
    WHEN EXISTS (SELECT 1 FROM material_recipe r WHERE r.code = i.component_no) THEN 'RECIPE'
    WHEN EXISTS (SELECT 1 FROM material_master m WHERE m.material_no = i.component_no) THEN 'ASSEMBLY'
    ELSE i.characteristic
  END
WHERE i.system_type = 'QUOTE'
  AND i.component_no IS NOT NULL
  AND i.characteristic IS DISTINCT FROM 'OUTSOURCED'
  AND NOT EXISTS (
    SELECT 1 FROM material_bom_item j
    WHERE j.id <> i.id
      AND j.system_type = i.system_type
      AND j.customer_no = i.customer_no
      AND j.material_no = i.material_no
      AND COALESCE(j.bom_version, '')  = COALESCE(i.bom_version, '')
      AND COALESCE(j.seq_no, 0)        = COALESCE(i.seq_no, 0)
      AND COALESCE(j.component_no, '') = COALESCE(i.component_no, '')
      AND COALESCE(j.part_no, '')      = COALESCE(i.part_no, '')
  );

-- 注：component_no IS NULL 的空壳历史行（is_current=f）不参与迁移，characteristic 保持 NULL。

-- ── 步骤 4: cz_view 核价分支谓词 ──
-- 改前谓词 characteristic IS NULL：核价全部行都是 NULL → 等价"全通过"，
-- 致「材质」页签混显元素行+材料行。回填后该谓词恒不命中 → 页签会空。
-- 收敛为 = 'RECIPE'，「材质」页签只显示元素行。
-- 全库仅 1 个模板含该串（已核实），replace 安全。
UPDATE component_sql_view
SET sql_template = replace(sql_template,
        'AND asy.characteristic IS NULL',
        'AND asy.characteristic = ''RECIPE'''),
    updated_at = now()
WHERE sql_view_name = 'cz_view'
  AND sql_template LIKE '%AND asy.characteristic IS NULL%';
