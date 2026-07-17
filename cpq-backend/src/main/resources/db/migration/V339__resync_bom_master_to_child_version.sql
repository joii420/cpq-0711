-- V339__resync_bom_master_to_child_version.sql
-- 修复 BOM「主表 current 版本落后于子表 current 版本」的失步（A 数据修复；配套 VersionedV6Writer 的 B 改动）。
--
-- 背景：task-0713 的测试数据迁移 V333 用裸 SQL 只升了子表（material_bom_item→bom_version 2001 /
--   element_bom_item→characteristic 2001）来造多版本，**未同步升主表**（material_bom / element_bom 仍停在
--   2000）。主/子版本号失步后，核价导入（VersionedV6Writer.writeVersionedMasterDetails 原按"主表 max+1"算
--   升版号）会算出一个子表已占用的版本号 → 插子表撞 uq_material_bom_item（本次报错根因）。
--
-- 本迁移把「主表 current 版本 < 该组子表 current max 版本」的组补齐：按子表 current max 版本补插一条主表行
-- （复制原主行内容、换版本列、is_current=true），并下线旧的落后 current 主行 → 主/子回到同一 current 版本。
--
-- 通用 + 幂等：
--   * 通用：不硬编码料号，扫描所有失步组（当前库仅 material_bom S-3120014539 + element_bom
--     S-2120011658/3112230066 两组命中，均 V333 造成）。
--   * 幂等：INSERT 带 NOT EXISTS(已有该版本主行) 守卫；下线只针对"已存在更高版本 current 兄弟"的旧主行。
--     DB 已同步时整迁移 no-op（可安全重放，含共享库 churn 重跑）。
--
-- 注：新版本号 = 子表 current max（对齐"主子一同到同一版本"）。B 改动保证后续导入再升版时取
--   max(主,子)+1，不再复现失步撞键。

-- ── 1) material_bom（版本列 bom_version；组键 system_type+customer_no+material_no+characteristic）──
INSERT INTO material_bom (
    system_type, customer_no, bom_type, bom_version, bom_status, plant, valid_from, valid_to,
    material_no, characteristic, batch_qty, production_unit, created_at, updated_at,
    created_by, updated_by, is_current, production_no, source)
SELECT
    mb.system_type, mb.customer_no, mb.bom_type, cc.cver::text, mb.bom_status, mb.plant,
    mb.valid_from, mb.valid_to, mb.material_no, mb.characteristic, mb.batch_qty, mb.production_unit,
    now(), now(), mb.created_by, mb.updated_by, true, mb.production_no, mb.source
FROM material_bom mb
JOIN (
    SELECT system_type, customer_no, material_no,
           MAX(CASE WHEN bom_version ~ '^[0-9]+$' THEN bom_version::int END) AS cver
    FROM material_bom_item WHERE is_current
    GROUP BY 1, 2, 3
) cc ON cc.system_type = mb.system_type AND cc.customer_no = mb.customer_no
    AND cc.material_no = mb.material_no
WHERE mb.is_current
  AND cc.cver IS NOT NULL
  AND (CASE WHEN mb.bom_version ~ '^[0-9]+$' THEN mb.bom_version::int END) < cc.cver
  AND NOT EXISTS (
      SELECT 1 FROM material_bom x
      WHERE x.system_type = mb.system_type AND x.customer_no = mb.customer_no
        AND x.material_no = mb.material_no AND x.bom_version = cc.cver::text
        AND COALESCE(x.characteristic, '') = COALESCE(mb.characteristic, ''));

UPDATE material_bom mb SET is_current = false, updated_at = now()
WHERE mb.is_current
  AND EXISTS (
      SELECT 1 FROM material_bom x
      WHERE x.system_type = mb.system_type AND x.customer_no = mb.customer_no
        AND x.material_no = mb.material_no
        AND COALESCE(x.characteristic, '') = COALESCE(mb.characteristic, '')
        AND x.is_current AND x.id <> mb.id
        AND (CASE WHEN x.bom_version ~ '^[0-9]+$' THEN x.bom_version::int END)
          > (CASE WHEN mb.bom_version ~ '^[0-9]+$' THEN mb.bom_version::int END));

-- ── 2) element_bom（版本列 characteristic；组键 +material_part_no）──
INSERT INTO element_bom (
    system_type, customer_no, bom_type, bom_status, plant, valid_from, valid_to,
    material_no, characteristic, batch_qty, production_unit, created_at, updated_at,
    created_by, updated_by, is_current, production_no, material_part_no, source)
SELECT
    eb.system_type, eb.customer_no, eb.bom_type, eb.bom_status, eb.plant, eb.valid_from, eb.valid_to,
    eb.material_no, cc.cver::text, eb.batch_qty, eb.production_unit, now(), now(),
    eb.created_by, eb.updated_by, true, eb.production_no, eb.material_part_no, eb.source
FROM element_bom eb
JOIN (
    SELECT system_type, customer_no, material_no, COALESCE(material_part_no, '') AS mpn,
           MAX(CASE WHEN characteristic ~ '^[0-9]+$' THEN characteristic::int END) AS cver
    FROM element_bom_item WHERE is_current
    GROUP BY 1, 2, 3, 4
) cc ON cc.system_type = eb.system_type AND cc.customer_no = eb.customer_no
    AND cc.material_no = eb.material_no AND cc.mpn = COALESCE(eb.material_part_no, '')
WHERE eb.is_current
  AND cc.cver IS NOT NULL
  AND (CASE WHEN eb.characteristic ~ '^[0-9]+$' THEN eb.characteristic::int END) < cc.cver
  AND NOT EXISTS (
      SELECT 1 FROM element_bom x
      WHERE x.system_type = eb.system_type AND x.customer_no = eb.customer_no
        AND x.material_no = eb.material_no
        AND COALESCE(x.material_part_no, '') = COALESCE(eb.material_part_no, '')
        AND x.characteristic = cc.cver::text);

UPDATE element_bom eb SET is_current = false, updated_at = now()
WHERE eb.is_current
  AND EXISTS (
      SELECT 1 FROM element_bom x
      WHERE x.system_type = eb.system_type AND x.customer_no = eb.customer_no
        AND x.material_no = eb.material_no
        AND COALESCE(x.material_part_no, '') = COALESCE(eb.material_part_no, '')
        AND x.is_current AND x.id <> eb.id
        AND (CASE WHEN x.characteristic ~ '^[0-9]+$' THEN x.characteristic::int END)
          > (CASE WHEN eb.characteristic ~ '^[0-9]+$' THEN eb.characteristic::int END));
