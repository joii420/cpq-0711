-- 基础核价 BOM 树骨架（usage=COST_BASIC，task-260909 B-9）
--
-- 三列的料号空间不同，改之前先看清楚：
--   root_no                 = 销售料号（种子原值，恒不翻译）
--   material_no / parent_no = 生产料号（翻译后）
--   node_path               = 生产料号拼接
-- root_no 必须留在销售料号空间：BomTreeRenderService 的 rootToLineItemIds 按
-- quotation_line_item.product_part_no_snapshot（销售料号）建键，CostingTreeGrouping 又按本 SQL
-- 输出的 root_no 分组 —— 输出成生产料号会让该卡片的料号集合变成空集，整张卡片一行都渲不出来，
-- 而且不报任何错。
--
-- 客户隔离只在种子处发生一次（子查询里的 customer_no 等值条件）：核价三张基础表本身没有
-- customer_no 维度，销售到生产的翻译是全树唯一的客户接触点。
--
-- 注意：版本宏的名字不要出现在注释里 —— VersionFilterMacro 是在【原文】上扫描的（不屏蔽注释），
-- 注释里写一次就会被当成一处真实宏去解析实参，直接把整段 SQL 解坏。
WITH RECURSIVE bom AS (
  -- 种子：本单成品的销售料号 → 生产料号，整棵树里唯一一次翻译
  SELECT
    s.sales_no                                     AS root_no,
    s.production_no::text                          AS material_no,
    (SELECT bv.version_no::text
       FROM v_ds_cost_basic_material_bom_all bv
      WHERE bv.production_no = s.production_no
        AND :versionFilter(bv.is_current, bv.version_no::text, bv.production_no)
      LIMIT 1)                                     AS bom_version,
    NULL::text                                     AS parent_no,
    s.production_no::text                          AS node_path
  FROM (
    SELECT DISTINCT p::text AS sales_no, dqm.production_no
      FROM unnest(:production_part_nos) AS p
      JOIN ds_quote_material dqm
        ON dqm.material_no = p
       AND dqm.customer_no = :customerCode
     WHERE dqm.production_no IS NOT NULL
  ) s

  UNION ALL

  -- 递归体：父 = production_no，子 = component_no（核价 BOM 全程生产料号，无客户维度）
  -- bom_version 取【这条边所属 BOM 的版本】= ch.version_no，与版本宏的版本维度（按父件
  -- production_no 分档）同源；不要改成"子件自己那张 BOM 的版本"，那会让版本列与版本切换的
  -- 作用对象对不上。
  SELECT
    b.root_no,
    ch.component_no::text                          AS material_no,
    ch.version_no::text                            AS bom_version,
    ch.production_no::text                         AS parent_no,
    (b.node_path || '/' || ch.component_no)::text  AS node_path
  FROM v_ds_cost_basic_material_bom_all ch
  JOIN bom b ON ch.production_no = b.material_no
  WHERE :versionFilter(ch.is_current, ch.version_no::text, ch.production_no)
    AND ch.component_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path
FROM bom
