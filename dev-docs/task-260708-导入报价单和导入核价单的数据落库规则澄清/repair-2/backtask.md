# 后端修复任务 · repair-2(报价单):材质料号不入料号表 + characteristic=RECIPE

> 负责人:cpq-backend｜优先级:P0｜🔴 **触及渲染基线视图,须先走 cpq-architect 评估**（见 §5 风险 1/2）
> 来源:用户 repair-2 第 3 项（报价单存储逻辑纠偏）。技术总监深挖当前存储逻辑 + 与用户确认 4 项决策。

---

## 0. 核心思想（一句话)

**材质料号不存进料号表 `material_master`**——材质有独立材质库(`material_recipe`),BOM 里用 `characteristic="RECIPE"` 标识"这是材质料号"。

---

## 1. 当前报价单存储逻辑（深挖结果，修改依据)

| Sheet | Handler | 材质料号去向 | 登记 material_master? | material_bom_item.characteristic |
|-------|---------|------------|:---:|------|
| **物料BOM** | `MaterialBomMergeHandler` | `resolve(材质料号)`→报价料号→`component_no` | ❌ **登记(第 89 行)** | NULL(若同 material_no 有组成件BOM → 被整体覆盖成 ASSEMBLY) |
| **组成件BOM** | `MaterialBomMergeHandler` | `resolve(组成件料号)`→`component_no` | ❌ **登记(第 120 行)** | ASSEMBLY |
| **物料与元素BOM** | `Q04ElementBomHandler` | 材质料号→`element_bom.material_part_no` | ✅ **登记的是销售料号(成品)、非材质料号**(第 59 行) | (element_bom 体系) |

关键事实:
- material_master 的材质料号污染**只来自 `MaterialBomMergeHandler`**(89 行物料BOM + 120 行组成件BOM);**Q04 干净**(登记成品销售料号,材质料号只进 element_bom)。
- `characteristic` 现由 `masterFixed` 主表级固定(第 191 行):`isAssembly = 该material_no有无组成件BOM ? "ASSEMBLY" : null` → **整体判定**,这就是"被组成件BOM 覆盖"。
- 材质库存在:`material_recipe` + `material_recipe_element`。

---

## 2. 已定决策（与用户确认)

| # | 决策 |
|---|------|
| **A** | 材质料号**不登记 `material_master`**(它在材质库 material_recipe)。 |
| **B** | 材质料号在 `material_bom_item.component_no` 存**原始材质料号**——**不 resolve、不铸报价料号**(直接引用材质库)。 |
| **C** | 材质料号记录 `characteristic="RECIPE"`;真组成件 `characteristic="ASSEMBLY"`。**characteristic 判定从"按 material_no 整体"改为"按每个组件"** → 同一 material_no 子行可混合 RECIPE+ASSEMBLY,**RECIPE 不被 ASSEMBLY 覆盖**。 |
| **D** | 组成件BOM 里识别材质料号 = 组件 ∈ **本次导入材质料号集**(物料BOM + 物料与元素BOM 的材质料号列并集)。命中 → 按材质料号处理(不登记 master + RECIPE + 原始码)。 |
| **E**(2026-07-09 补) | **材质料号 = `material_recipe.code`(按列规则约定)**;渲染视图品名/规格兜底 join `material_recipe ON code = component_no`,**品名取 `material_recipe.name`**。⚠️ **现有 excel 测试数据的材质料号(如 2101110225)是错误数据、不在 material_recipe——按规则实现,不参考错误数据**;验收须用**材质料号=真实 material_recipe.code 的正确报价测试文件**(否则 AC-7 品名兜底验不了,只会 fallback 到原始码)。架构师方案 §2.1 join-key 假设据此**确认成立**。 |

---

## 3. 改动点

### 3.1 建立"本次导入材质料号集"（跨 handler)
- 集合 = `物料BOM.材质料号` ∪ `物料与元素BOM.材质料号`(Q04)。
- 两张 sheet 在**不同 handler**(MaterialBomMergeHandler / Q04),须**共享**该集合 → 放 `ImportContext`。
- **必须在组成件BOM 识别前收集齐**(先扫两张材质 sheet 填集合,再处理组成件BOM)。注意导入顺序/预扫。

### 3.2 MaterialBomMergeHandler · 物料BOM 分支
- `component_no` = **原始材质料号**(去掉 `materialNoResolver.resolve(...)`,直接取材质料号原值);
- **删第 89 行 `accMaterialMaster(...)`**(材质料号不登记 master);
- 该子行 `characteristic="RECIPE"`。

### 3.3 MaterialBomMergeHandler · 组成件BOM 分支
- 若 `component_no ∈ 材质料号集`(决策 D)→ 同 3.2:原始码、不登记 master、`characteristic="RECIPE"`;
- 否则(真组成件)→ 保持现有:`resolve`、登记 master、`characteristic="ASSEMBLY"`。

### 3.4 characteristic per-component 化（🔴 硬骨头,见风险 1）
- 现 `characteristic` 是 `masterFixed` 主表固定列 → 改为**按子行**携带(RECIPE / ASSEMBLY);
- 版本分区/写入器 `VersionedV6Writer` 需支持同一 master 下子行混合 characteristic;
- 回归撞键 / is_current / 版本分组必过。

### 3.5 渲染视图兜底（🔴 涟漪,见风险 2）
- `V233/V238/V280/V303` 等视图 `LEFT JOIN material_master ON material_no=component_no` 取品名/规格 → 材质料号行返 null。
- 需给这些视图对**材质料号行**改读/兜底 `material_recipe`(材质库),否则渲染出空名。
- 涉及组合产品渲染视图(渲染基线)→ **须 architect 评估视图改法**。

---

## 4. 边界（本次不做)
- **核价侧**:本 repair-2 只做**报价单**;核价侧材质料号 1.2 用户已定"只清残留、不改代码"(已清)。核价 handler 不动。
- Q04(物料与元素BOM)已干净,不改。
- 材质料号 → material_recipe 的**新建/校验**(材质料号若不在材质库怎么办)属另一议题,本次假设材质料号已在材质库;若需"缺库告警"另立(参考 [[BL-0039]] 引用校验)。

---

## 5. 风险清单（实现/评审必读)

1. **🔴 characteristic per-component = 动版本化写入器**:characteristic 现是主表固定列 + 进 `idx_material_bom_item_parent` 索引维度。改成子行级、同 master 混合 RECIPE+ASSEMBLY,是 `VersionedV6Writer` 的结构性改动,高风险,必回归撞键/is_current/版本分组。
2. **🔴 component_no 存原始材质料号 + 不进 master → 视图 join 断链**:`V233/V238/V280/V303` 等 `LEFT JOIN material_master ON material_no=component_no` 返 null。属渲染基线视图,须 architect 定视图兜底 material_recipe 的改法。
3. **跨 handler 材质料号集**:MaterialBomMergeHandler 与 Q04 两 handler 共享集合,须 ImportContext + 顺序保证(材质 sheet 先扫)。
4. **component_no 类型/约束**:确认 material_bom_item.component_no 无 FK 强制指向 material_master(材质料号不在 master,若有 FK 会插失败)。
5. **发号影响**:材质料号不再走 resolve/铸号——确认不影响真组成件的铸号路径(仅材质料号旁路)。

---

## 6. 验收标准（供 QA 出用例)

> 用报价测试文件(物料BOM 材质料号 + 组成件BOM + 物料与元素BOM)清空重导后断言。

| 编号 | 断言 | 预期 |
|------|------|------|
| AC-1 | 材质料号不在 material_master | 物料BOM/物料与元素BOM 的材质料号 `SELECT count(*) FROM material_master WHERE material_no IN (材质料号...)` = **0** |
| AC-2 | 材质料号在 material_bom_item 且 characteristic=RECIPE | `SELECT component_no,characteristic FROM material_bom_item WHERE system_type='QUOTE' AND component_no=材质料号` → characteristic=**RECIPE** |
| AC-3 | component_no=原始材质料号(非铸号) | component_no 不是 `XXXX-YYMMNNNNNN` 格式,= Excel 原始材质料号 |
| AC-4 | RECIPE 不被 ASSEMBLY 覆盖 | 同一 material_no 既有材质料号(RECIPE)又有组成件(ASSEMBLY)时,两类子行 characteristic 各自保留、不互相覆盖 |
| AC-5 | 组成件BOM 里的材质料号也 RECIPE + 不进 master | 组成件BOM 中命中材质料号集的组件 → characteristic=RECIPE、不在 material_master |
| AC-6 | 真组成件不受影响 | 组成件BOM 非材质料号组件仍 ASSEMBLY + 登记 master + 正常 resolve/铸号 |
| AC-7 | 渲染视图材质料号行有名 | 组合产品渲染视图对材质料号行品名/规格不为空(改读 material_recipe 兜底后) |
| AC-8 | 回归 | 撞键/is_current/element_bom material_part_no/task-0708 硬指标不破 |

---

## 7. 自检 & 交付
- 自检:AC-1~AC-8 逐条 SQL + E2E(协议级改动,渲染视图动了必跑 quotation-flow E2E)。
- 交付附一行自检声明(材质料号 0 入 master ✅ / RECIPE ✅ / 不被覆盖 ✅ / 视图有名 ✅ / 回归不破 ✅)。
- 因触及渲染基线视图(风险 2)+ 写入器(风险 1),**建议先 architect 出视图/写入器改法,再 subagent 实现**。
