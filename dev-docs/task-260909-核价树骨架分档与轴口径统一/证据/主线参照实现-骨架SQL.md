# 主线独立参照实现 · COST_BASIC 骨架递归 SQL

> 用途：**不采信子代理**。B-9 交上来时逐行对照这份的实跑结果。
> 主线 2026-09-09 在 `cpq_db_0724` 实跑，只读，未写库。

## 关键发现：三列分属两个料号空间

| 列 | 空间 | 示例 |
|---|---|---|
| `root_no` | **销售料号（种子原值，不翻译）** | `S0001` |
| `material_no` / `parent_no` / `node_path` | **生产料号（翻译后）** | `300012` / `300001` |

**依据（实查代码）**：
- `BomTreeRenderService:447-454` `rootToLineItemIds` 按 `li.productPartNoSnapshot`（**销售料号**）建键
- `BomTreeRenderService:665-667` `g.cardMaterialNo.getOrDefault(root, 空集)`
- `CostingTreeGrouping:24` `cardMat.computeIfAbsent(r.rootNo, ...)` 按 SQL 的 `root_no` 分组

⇒ `root_no` 若输出生产料号 → `getOrDefault("S0001")` 空集 → **该卡片一行都渲不出、且不报错**。
这条 `backtask.md B-9` 初稿漏写，2026-09-09 补入为第 7 条硬约束，并已同步给后端子代理。

## 参照 SQL（`:versionFilter` 宏此处按渲染期无 override 展开为 `is_current`）

```sql
WITH RECURSIVE seed AS (
  SELECT dqm.material_no::text AS sales_no, dqm.production_no::text AS pno
  FROM ds_quote_material dqm
  WHERE dqm.material_no = ANY(:production_part_nos) AND dqm.customer_no = :customerCode
), bom AS (
  SELECT s.sales_no AS root_no, s.pno AS material_no,
         (SELECT bv.version_no::text FROM v_ds_cost_basic_material_bom_all bv
           WHERE bv.production_no = s.pno AND :versionFilter(bv.is_current, bv.version_no::text, bv.production_no)
          LIMIT 1) AS bom_version,
         NULL::text AS parent_no, s.pno AS node_path
  FROM seed s
  UNION ALL
  SELECT b.root_no, ch.component_no::text, ch.version_no::text, ch.production_no::text,
         (b.node_path || '/' || ch.component_no)::text
  FROM v_ds_cost_basic_material_bom_all ch JOIN bom b ON ch.production_no = b.material_no
  WHERE :versionFilter(ch.is_current, ch.version_no::text, ch.production_no) AND ch.component_no IS NOT NULL
) CYCLE material_no SET is_cyc USING cyc_path
SELECT root_no, material_no, bom_version, parent_no, node_path FROM bom
```

## 实跑输出（seed=S0001/S0004/S0008/S0012，customerCode=CUST-0004）

```
root_no | material_no | bom_version | parent_no | node_path
S0001   | 300001      | 3           |           | 300001
S0001   | 300012      | 3           | 300001    | 300001/300012
S0001   | 300015      | 1           | 300012    | 300001/300012/300015
S0001   | 992         | 1           | 300015    | 300001/300012/300015/992
S0001   | 300013      | 3           | 300001    | 300001/300013
S0001   | 991         | 1           | 300013    | 300001/300013/991
S0001   | 300014      | 3           | 300001    | 300001/300014
S0004   | 300021      |             |           | 300021
S0008   | 300031      |             |           | 300031
S0012   | 300041      |             |           | 300041
(10 rows)
```

与 AC-7（7 行 4 层：`300001→300012→300015→992`）、AC-8（其余三个各 1 行）**逐行吻合**。

⚠️ `S0004/S0008/S0012` 的 `bom_version` 为空是**正确的**：它们在 `ds_cost_basic_material_bom` 里没有任何行。
