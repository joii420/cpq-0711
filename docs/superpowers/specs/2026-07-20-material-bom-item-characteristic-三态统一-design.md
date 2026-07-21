# material_bom_item.characteristic 三态统一 — 设计

- **日期**：2026-07-20
- **状态**：设计已确认，待写实现计划
- **范围**：`material_bom_item.characteristic` 的写入语义统一 + 存量数据迁移 + 下游视图对齐
- **不在范围**：`material_bom`（主表）的 `characteristic` 语义；`element_bom_item.characteristic`（核价侧被复用为版本列 `view_version`，与本设计无关）

---

## 1. 背景与动机

`material_bom_item.characteristic` 当前语义不统一，两条系统线各用各的判别列：

| 系统线 | 实际判别列 | 材质 | 组成件 |
|---|---|---|---|
| `system_type='QUOTE'` | `characteristic` | `'RECIPE'`（含存量 `NULL`） | `'ASSEMBLY'` |
| `system_type='PRICING'` | `calc_type` | `'元素'` | `'材料'` |

核价侧 `characteristic` **恒为 NULL**，判别完全依赖 `calc_type`。这导致：

1. **同一列在两条线含义不同**，任何跨线消费都要写分支。
2. **核价「材质」页签把元素行和材料行混在一起显示**——`cz_view` 核价分支谓词是 `characteristic IS NULL`，而核价 64 行全是 NULL，等价于"全通过"（19 元素行 + 43 材料行 + 2 空壳行）。
3. **报价侧缺少"外购件"这一业务概念**，外购件当前只能混在组成件里。

本设计将 `characteristic` 统一为三态，并让两条线都以它作为唯一判别列。

---

## 2. 语义定义

| 值 | 含义 | 判定来源 |
|---|---|---|
| `RECIPE` | 材质 | 核价：`calc_type='元素'`<br>报价：物料BOM sheet 的行 |
| `ASSEMBLY` | 组成件·零件 | 核价：`calc_type='材料'`<br>报价：组成件BOM sheet `组成类型='零件'` |
| `OUTSOURCED` | 外购件 | 报价：组成件BOM sheet `组成类型='外购件'` |

**已确认的边界条件**：

- 报价侧**物料BOM sheet** 的行维持恒 `RECIPE`（repair-2 决策 A/B/C 的现状），本次不改。
- `OUTSOURCED` 是全新概念，**存量 0 行**，只能通过新模板导入产生。
- 核价侧**不产生** `OUTSOURCED`（核价物料BOM sheet 不加"组成类型"列）。

---

## 3. Excel 模板变更

**报价侧「组成件BOM」sheet 新增一列：「组成类型」**

- 值域：`零件` / `外购件`（仅此两值）
- 必填

映射：

```
组成类型 = 零件   → characteristic = 'ASSEMBLY'
组成类型 = 外购件 → characteristic = 'OUTSOURCED'
```

**校验口径（严格路线，已确认）**：

| 情形 | 处理 |
|---|---|
| 整列缺失（旧模板文件） | `recordError` 拒导该行 |
| 列存在但单元格为空 | `recordError` 拒导该行 |
| 值不在 `{零件, 外购件}` 内 | `recordError` 拒导该行 |
| 料号命中本次导入的材质料号集（决策 D）| `recordError` 拒导该行 |

---

## 4. 写入侧改动

### 4.1 `P06MaterialBomHandler`（核价）

**文件**：`cpq-backend/src/main/java/com/cpq/basicdata/v6/pricing/P06MaterialBomHandler.java`

1. 每行 childRow 增加 `characteristic`：

   ```java
   c.put("characteristic", "元素".equals(calcType) ? "RECIPE" : "ASSEMBLY");
   ```

   注：`calc_type` 为 NULL 的行按 `ASSEMBLY` 处理（存量仅 2 行 `is_current=f` 空壳行，见 §5.4）。

2. **从 `childGk` 移除 `characteristic`**（`:108`、`:140` 两处的 `childGk.put("characteristic", null)`）。
   这一步是**强制**的，理由见 §6 风险 B。改后 childGk = `(system_type, customer_no, material_no)`，与报价侧 `MaterialBomMergeHandler` 对齐。

3. `CHILD_CONTENT`（`:39-42`）**加入** `characteristic`，理由见 §6 风险 A。

### 4.2 `MaterialBomMergeHandler`（报价）

**文件**：`cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/MaterialBomMergeHandler.java`

**物料BOM 分支（`:74-102`）**：不改，维持 `characteristic='RECIPE'`。

**组成件BOM 分支（`:104-154`）**：

1. 新读「组成类型」列，按 §3 校验并映射到 `ASSEMBLY` / `OUTSOURCED`。
2. 决策 D 冲突处理：原 `:113-131` 的 `isMaterialHit → RECIPE` 分支改为 `recordError` 拒导该行。

   > **推论（已确认接受）**：组成类型必填且值域不含材质 ⇒ 决策 D 的 RECIPE 分支成为**死代码**。
   > repair-2 的防线仍然有效——材质料号依旧不会被铸报价料号、不会登记 `material_master`——
   > 只是从"静默纠正"变成"显式报错"，符合本次的严格路线。

3. 主表推导（`:201`、`:261`）：

   ```java
   // 改前
   boolean isAssembly = childRows.stream().anyMatch(r -> "ASSEMBLY".equals(r.get("characteristic")));
   // 改后
   boolean isAssembly = childRows.stream().anyMatch(r ->
       "ASSEMBLY".equals(r.get("characteristic")) || "OUTSOURCED".equals(r.get("characteristic")));
   ```

   使"只有外购件子行"的料号主表仍判为 `ASSEMBLY`，让 `ll_view`（来料，走主表 characteristic）能捞到它。

4. `CHILD_CONTENT`（`:47-51`）**加入** `characteristic`，理由见 §6 风险 A。
   同时删除 `:259-260` 已失效的注释（该注释解释的是"为何不加入"）。

5. 归并阶段的决策 D 兜底（`:192-196`、`:250-254`）：保留，防御性无害。

---

## 5. 存量数据迁移（Flyway V344）

存量共 109 行（PRICING 64 / QUOTE 45；其中 `is_current=t` 的 PRICING 30 / QUOTE 41）。执行顺序：

### 5.1 删除矛盾数据（11 行）

`PRICING` + `calc_type='元素'` 但 `component_no` 命中 `material_master`、不命中 `material_recipe` 的 11 行。

已核实：这批码（`3110520789`、`3112230066`、`3112230067`、`2101110225`、`2111410069`）**既不在材质库也不在元素主表**，只在料号主档且名称为"料2/料10/料11"这类测试数据 —— 语义本身错误，规则与交叉校正给出相反答案。

```sql
DELETE FROM material_bom_item i
WHERE i.system_type = 'PRICING'
  AND i.calc_type = '元素'
  AND NOT EXISTS (SELECT 1 FROM material_recipe r WHERE r.code = i.component_no)
  AND EXISTS (SELECT 1 FROM material_master m WHERE m.material_no = i.component_no);
```

**后果（已确认接受）**：`2120011658`、`2120011659`、`3110520789` 三个料号的**当前 BOM 被清空**。

### 5.2 规则回填

```sql
UPDATE material_bom_item SET characteristic =
  CASE WHEN calc_type = '元素' THEN 'RECIPE' ELSE 'ASSEMBLY' END
WHERE system_type = 'PRICING' AND component_no IS NOT NULL;

UPDATE material_bom_item SET characteristic = 'RECIPE'
WHERE system_type = 'QUOTE' AND characteristic IS NULL AND component_no IS NOT NULL;
```

### 5.3 命中表交叉校正（QUOTE 侧，洗净 5 行脏数据）

⚠️ **唯一索引约束（2026-07-20 实测发现）**：`uq_material_bom_item` 含 `COALESCE(characteristic,'')`：

```
UNIQUE (system_type, customer_no, material_no, COALESCE(characteristic,''),
        COALESCE(bom_version,''), COALESCE(seq_no,0),
        COALESCE(component_no,''), COALESCE(part_no,''))
```

存量有 **3 组历史重复行**——同一 component 同时出现在物料BOM 与组成件BOM，在 `MaterialBomMergeHandler` 归并逻辑上线前被写成两行，靠 `characteristic` 不同（`''` vs `ASSEMBLY`）才不撞键：

| material_no | seq | component_no | 物料BOM 行 | 组成件BOM 行 |
|---|---|---|---|---|
| 0317-2607000006 | 1 | 0317-2607000005 | usage_type=Ag | 用量 2.0 |
| 0363-2607000009 | 1 | 0363-2607000007 | usage_type=AgCu | 用量 1.0 |
| 0363-2607000009 | 2 | 0363-2607000008 | usage_type=AgNi | 用量 1.0 |

若无差别地做交叉校正，这 3 组的 NULL 行会被校正成 `ASSEMBLY`，与兄弟行同键 → **V344 唯一键冲突、迁移失败**。

**规则（已确认）**：交叉校正**只作用于"唯一键去掉 characteristic 后无兄弟行"的行**。有兄弟行的保持步骤 2 的规则回填结果（NULL→RECIPE），与兄弟行的 `ASSEMBLY` 天然区分开。

```sql
UPDATE material_bom_item i SET characteristic =
  CASE
    WHEN EXISTS (SELECT 1 FROM material_recipe r WHERE r.code = i.component_no) THEN 'RECIPE'
    WHEN EXISTS (SELECT 1 FROM material_master m WHERE m.material_no = i.component_no) THEN 'ASSEMBLY'
    ELSE i.characteristic
  END
WHERE i.system_type = 'QUOTE'
  AND i.component_no IS NOT NULL
  AND i.characteristic IS DISTINCT FROM 'OUTSOURCED'   -- 外购件不参与校正（存量为 0，防御性）
  -- 有兄弟行者跳过：校正会使其与兄弟行同键，撞 uq_material_bom_item
  AND NOT EXISTS (
    SELECT 1 FROM material_bom_item j
    WHERE j.id <> i.id
      AND j.system_type = i.system_type
      AND j.customer_no = i.customer_no
      AND j.material_no = i.material_no
      AND COALESCE(j.bom_version, '') = COALESCE(i.bom_version, '')
      AND COALESCE(j.seq_no, 0)       = COALESCE(i.seq_no, 0)
      AND COALESCE(j.component_no, '')= COALESCE(i.component_no, '')
      AND COALESCE(j.part_no, '')     = COALESCE(i.part_no, '')
  );
```

实际洗净 2 行（已实测验证该规则不误伤应洗的脏数据）：
- `S-3120014539` 的 `991`/`992`：标着 `ASSEMBLY` 但在材质库 → 改 `RECIPE`

保持规则回填结果的 3 行（有兄弟行，不校正）：
- `0317-2607000005`/`0363-2607000007`/`0363-2607000008` 的物料BOM 行 → 保持 `RECIPE`

> 副作用：这 3 个 component 会同时以 `RECIPE`（物料BOM 行）和 `ASSEMBLY`（组成件BOM 行）存在，
> 在材质页签与子配件页签各出现一次。这是**存量历史重复行的固有形态**，非本次引入；
> 用现行 merge handler 重导该客户数据即自动归并为单行。

### 5.4 排除项

`component_no IS NULL` 的 2 行（`S-3120014539`、`is_current=f`、`bom_version=2003` 的空壳历史行）**不参与迁移**，`characteristic` 保持 NULL。已由上述各步的 `component_no IS NOT NULL` 条件排除。

### 5.5 迁移后校验

```sql
-- 期望：无 component_no 非空却 characteristic 为 NULL 的行
SELECT count(*) FROM material_bom_item
WHERE component_no IS NOT NULL AND characteristic IS NULL;   -- 期望 0

-- 期望分布（全量，含历史版本行）：
--   PRICING RECIPE=8  / ASSEMBLY=43
--   QUOTE   RECIPE=34 / ASSEMBLY=11
SELECT system_type, characteristic, count(*) FROM material_bom_item
WHERE component_no IS NOT NULL GROUP BY 1,2 ORDER BY 1,2;

-- 期望分布（仅 is_current=t，决定页签实际渲染行数）：
--   PRICING RECIPE=5  / ASSEMBLY=18
--   QUOTE   RECIPE=30 / ASSEMBLY=11
SELECT system_type, characteristic, count(*) FROM material_bom_item
WHERE component_no IS NOT NULL AND is_current GROUP BY 1,2 ORDER BY 1,2;

-- 期望 0：迁移不得引入唯一键冲突（uq_material_bom_item 含 COALESCE(characteristic,'')）
SELECT count(*) AS 撞键组数 FROM (
  SELECT 1 FROM material_bom_item WHERE component_no IS NOT NULL
  GROUP BY system_type, customer_no, material_no, COALESCE(characteristic,''),
           COALESCE(bom_version,''), COALESCE(seq_no,0),
           COALESCE(component_no,''), COALESCE(part_no,'')
  HAVING count(*) > 1
) t;
```

---

## 6. 两个必须处理的风险

### 风险 A：内容比较漏掉 characteristic → 组成类型改了等于没改（静默失败）

`VersionedV6Writer.java:637`：

```java
List<Map<String, Object>> existingChild = loadCurrentGroup(childTable, childGroupKey, childContentColumns);
if (multisetEqual(existingChild, childRows, childContentColumns)) {
    return currentVersionOf(...);   // 完全不写库
}
```

`multisetEqual` 只比对 `childContentColumns`。若 `characteristic` 不在其中，业务把某行「组成类型」从零件改成外购件、其他列不变 → 判定内容无变化 → **完全不写库**，改动丢失且无任何报错。

**解法**：把 `characteristic` 加入两个 handler 的 `CHILD_CONTENT`。

原先刻意不加的理由（`MaterialBomMergeHandler:259-260` 注释：怕历史 `NULL` → 新值被误判为内容变化触发空升版）在 §5 迁移把存量 NULL 全部回填后**自动消失**。

> **顺序依赖**：迁移必须先于（或同批于）该改动生效。

### 风险 B：childGk 含 characteristic → 迁移后产生双 current 行

若 §5 迁移后 `P06` 的 `childGk` 仍含 `characteristic=null`：

1. `loadCurrentGroup` 的 NULL 安全 where（`VersionedV6Writer:687-694`）会按 `characteristic IS NOT DISTINCT FROM NULL` 过滤 → 迁移后已无 NULL 行 → **匹配不到任何行**
2. `multisetEqual(空, childRows)` = false → 每次导入都升版
3. `flip(childTable, childGroupKey)` 同样按 NULL 过滤 → **翻不到旧行**
4. 结果：旧行与新行**同时 `is_current=t`** → 下游视图行数翻倍（AP-22「X (共N项)」族）

**解法**：§4.1 第 2 步的 childGk 改动与 §5 迁移**必须同批原子上线**，不可分两次发布。

---

## 7. 下游视图

| 视图 | 组件 | 当前谓词 | 改动 |
|---|---|---|---|
| `cz_view`（核价分支）| 材质 ×4 ACTIVE | `characteristic IS NULL` | → `= 'RECIPE'` |
| `ll_view` | 来料 ×4 ACTIVE | **主表** `mb.characteristic = 'ASSEMBLY'` | 不改（靠 §4.2-3 主表推导覆盖外购件）|
| `zpj_view` | 子配件 ×4 ACTIVE | 子表 `characteristic = 'ASSEMBLY'` | **本期不动**（见 §9）|
| `ys_view` | 元素 ×N ACTIVE | 子表 `= 'ASSEMBLY'` (2处) + `ebi.characteristic` 作版本列 | 不改 |
| `wl_ys_bom_view` | 物料与元素BOM | `ebi.characteristic` 作版本列 | 不改 |
| `v_composite_child_materials` | （无 ACTIVE 引用）| `IS DISTINCT FROM 'ASSEMBLY'` | 不改（仍正确：RECIPE 命中、ASSEMBLY/OUTSOURCED 排除）|

**`cz_view` 的可见行为变更**：核价「材质」页签渲染行数 **30 → 5**（`is_current=t` 口径）。原本混入的 18 行材料行不再显示；元素行中 7 行 `is_current=t` 的已被 §5.1 删除，剩 5 行。这是修正，不是回归。

---

## 8. 已知取舍（已拍板，记录备查）

1. **删除治标不治本**：规则 3 只给报价侧组成件BOM sheet 加列，核价侧物料BOM sheet 未加列 → 核价 Excel 可照常重导，§5.1 删掉的 11 行会以 `RECIPE` 回来（且因不在材质库，`cz_view` 的 `LEFT JOIN material_recipe` 全空，显示为 `component_no` 兜底值）。已决定**不在 P06 加源头校验**。
2. **首次重导会有一轮空升版**：迁移口径（交叉校正）与导入器口径（组成类型列）对同一行可能给出不同 `characteristic` → 加入 CHILD_CONTENT 后被判为内容变化 → 升版。仅发生一次，可接受。
3. **旧 Excel 无法重导**：报价侧组成件BOM 的存量文件因缺「组成类型」列会整表拒导，业务必须先改模板。

---

## 9. 本期不做（转 Backlog）

### 9.1 `OUTSOURCED` 的渲染归属

**本期下游视图零改动**，外购件行的实际可见性如下（已核实视图模板，非推测）：

| 页签 | 视图 | 子表谓词 | 外购件行 |
|---|---|---|---|
| 子配件 | `zpj_view` QUOTE 分支 | `characteristic = 'ASSEMBLY'` | ❌ 不显示 |
| 来料 | `ll_view` | 子表 join **不过滤 characteristic**，仅按 `material_no` 关联 | ✅ **会显示** |

> ⚠️ **已知取舍（业务须知）**：`ll_view` 捞的是"主表判为 `ASSEMBLY` 的料号下**全部** `is_current` 子行"，
> 不区分三态；叠加 §4.2-3 的主表推导改动后，纯外购件料号也会命中。
> 因此外购件**会混在「来料」页签里，且与零件在视觉上无法区分**——
> `ll_view` 不输出 `characteristic` 列，其「类型」列取的是 `component_usage_type`
> 的映射（银点类/非银点类/组成件），与本次的三态无关。
>
> 净效果：业务在 Excel 里区分了"零件 vs 外购件"，数据层正确落库，
> 但**报价单展示上看不出区别**。已确认本期接受。

二期方案（三选一）：
- `ll_view` 增加一列输出 `characteristic` 的中文映射（改动最小）
- `zpj_view` 放宽为 `IN ('ASSEMBLY','OUTSOURCED')`，外购件并入子配件页签
- 新开独立视图 + 组件 + 模板绑定，外购件单独成页签

优先级：P1｜前置条件：本期交付｜预估规模：S（前两案）/ M（独立页签）

---

## 10. 影响面与自检清单

**改动文件**：

- `cpq-backend/.../pricing/P06MaterialBomHandler.java`
- `cpq-backend/.../quote/MaterialBomMergeHandler.java`
- `cpq-backend/src/main/resources/db/migration/V344__unify_material_bom_item_characteristic.sql`
- `component_sql_view` 中 `cz_view` 的核价分支模板（随 V344 迁移一并 UPDATE）

**不在本次代码交付范围**：

- 报价侧「组成件BOM」Excel 模板文件的「组成类型」列 —— **由业务侧提供新模板**。
  开发侧只负责解析器按 §3 读取该列并校验；模板文件本身不由本次改动产出。
  ⚠️ 上线依赖：业务新模板未到位前，报价侧组成件BOM **全部无法导入**（§8-3 严格路线），
  故本期上线需与业务确认新模板就绪时点。

**自检项**：

- [ ] V344 `success=t`，§5.5 两条校验 SQL 结果符合期望
- [ ] `SELECT count(*) FROM material_bom_item WHERE is_current` 迁移前后行数差 = 11（仅删除，无意外增减）
- [ ] **无双 current 行**：按 `(system_type, customer_no, material_no, bom_version)` 分组无重复 current 集
- [ ] Quarkus 重启（V344 含视图模板 UPDATE，需清 `ImplicitJoinRewriter` 等进程级缓存）
- [ ] 核价「材质」页签渲染行数 = 5，非空且无「加载中…」
- [ ] 核价「来料」「子配件」「元素」页签无回归
- [ ] 重导一次核价 Excel：不产生双 current 行，版本号正确 +1
- [ ] 重导一次报价 Excel（新模板带「组成类型」列）：`ASSEMBLY`/`OUTSOURCED` 正确落库
- [ ] 旧模板报价 Excel 导入：整表拒导且错误信息可读

**E2E**：本次未触及 `docs/E2E测试方法.md` 列举的协议级前端文件，但改动了 BOM driver 数据源与视图谓词，建议跑 `quotation-flow.spec.ts` 做回归对照（注意：干净 master 上该 spec 已有 3 个因夹具漂移的既有失败，判定回归须 A/B 同型对比）。
