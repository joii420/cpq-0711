# material_bom_item 版本化（子表多版本保留 + 主从版本对齐）设计方案

> 日期：2026-06-04 ｜ 状态：已实现（计划 docs/superpowers/plans/2026-06-04-material_bom_item-版本化.md，2026-06-04 完成）
> 关联：`docs/table/报价系统Excel导入落库方案.md`（V3.2 去重合并）、`docs/反模式.md` AP-22 / AP-53

---

## 1. 背景与问题

`material_bom`（主表）有版本列 `bom_version`（2000、2001…），升版时旧主行 `is_current=false` **保留为历史**；但 `material_bom_item`（子表）**没有版本列**，升版走 `VersionedV6Writer` 的 `childVersionColumn=null` 分支（`upsert` 覆盖当前 + `deleteNonCurrent` 物理删旧子行），**只保留当前版本明细**。

由此产生**主子不对称**：

- 主表能查到 `bom_version=2000、2001、2002` 一串历史主行；
- 子表却只剩最新版的子件明细，**前几版的 BOM 子件清单无法回溯**。

对比 `element_bom_item`：它的 `characteristic`（NOT NULL、在 uq 内）**充当版本号**，各版子行靠不同 characteristic 共存，`is_current` 标当前 → 元素BOM 子表**完整保留历史**。

> ⚠️ `characteristic` 在两套 BOM 里语义不同：
> - **元素BOM**：`characteristic` = 版本号（2000→2001 递增），主表无 `bom_version`。
> - **物料BOM**：`characteristic` = 类型标记（`NULL`=MATERIAL / `'ASSEMBLY'`=组成件），版本号在独立的 `bom_version` 列。

**目标**：给 `material_bom_item` 加版本能力，做到主子版本对齐 + **going-forward 多版本保留**（历史明细可回溯）。历史存量明细**不 backfill**。

---

## 2. 设计决策（已与需求方确认）

| # | 决策 | 取值 | 理由 / 约束 |
|---|------|------|-------------|
| D1 | 子表保留模型 | **全量多版本保留**（对齐 `element_bom_item`） | 满足"记录历史版本数据"诉求；代价 = 所有读取点须补 `is_current` 过滤 |
| D2 | 选配子表写入 | **统一走 `bom_version` path** | 移除 `material_bom_item` 的 null-path 特例，设计最干净；选配重存累积历史（读取侧 is_current 过滤后无功能影响） |
| D3 | 版本作用域 | **保持 per-`(料号, characteristic)`**（不改 writer） | 选配 `ConfigureProductService` 对 COMBO 料号调 `writeVersionedMasterDetail` **两次**（NULL + ASSEMBLY）产生双 `is_current=true` 主行；版本作用域含 characteristic 才能让两次调用不互翻，否则 409 / mirror 断链 |
| D4 | 历史存量 | **不 backfill 历史明细**；仅一次性对齐现存当前行 `bom_version` | 满足 uq 含 `bom_version` 后的一致性，不重建历史 |
| D5 | 读取侧联动范围 | **仅组件配置 SQL（`component_sql_view`），不碰 PG 库 `CREATE VIEW`** | 运行时渲染走配置 SQL（BNF 模板）；PG 视图 `v_composite_child_*` 经确认为非运行时路径，排除 |

---

## 3. 受影响实体盘点

- **表**：`material_bom`（主，已有 `bom_version`）、`material_bom_item`（子，本次加 `bom_version`）。
- **写入器**：`VersionedV6Writer`（移除 material_bom_item null-path 特例）。
- **报价导入**：`Q03MaterialBomHandler` / `Q12AssemblyBomHandler` → 合并为 `MaterialBomMergeHandler`（与 V3.2 去重合并方案一体实现）。
- **核价导入**：`P06MaterialBomHandler`（核价侧只有物料BOM、`characteristic=NULL`，**无 ASSEMBLY、无合并**）。
- **选配**：`ConfigureProductService`（两处 `writeVersionedMasterDetail`，`:568`、`:584`）。
- **读取侧**：9 个 `component_sql_view` 配置 SQL（见 §9 清单）。

---

## 4. 模块 1 · 数据模型（Flyway 新迁移）

1. `ALTER TABLE material_bom_item ADD COLUMN IF NOT EXISTS bom_version VARCHAR(20);`（先可空，容纳存量行）。
2. 重建唯一索引，把版本维度并入：
   ```sql
   DROP INDEX IF EXISTS uq_material_bom_item;
   CREATE UNIQUE INDEX uq_material_bom_item ON material_bom_item(
       system_type, customer_no, material_no,
       COALESCE(characteristic,''),
       COALESCE(bom_version,''),        -- 新增版本维度
       COALESCE(seq_no,0),
       COALESCE(component_no,''),
       COALESCE(part_no,'')
   );
   ```
3. **一次性对齐存量当前行**（不补历史）：
   ```sql
   UPDATE material_bom_item ci
   SET bom_version = m.bom_version
   FROM material_bom m
   WHERE m.is_current = TRUE
     AND m.system_type = ci.system_type
     AND m.customer_no = ci.customer_no
     AND m.material_no = ci.material_no
     AND m.characteristic IS NOT DISTINCT FROM ci.characteristic
     AND ci.bom_version IS NULL;
   ```
   存量子行本就只有当前版（旧设计只留当前），补上对应 master 当前版本号即对齐。
4. `is_current` 列已由 V277 存在，无需新增。

> DDL 后 `touch` 一个 java 文件触发 Quarkus 重启（Flyway + 缓存重建）。

---

## 5. 模块 2 · 写入器 `VersionedV6Writer`

- **移除 `material_bom_item` 的 `CHILD_UQ` 登记 + null-path 特殊处理**（`upsert` / `deleteNonCurrent`）。
- `material_bom_item` 改走与 `element_bom_item` 相同的 **`childVersionColumn != null` 多版本插入分支**：写版本列、各版本插入新行、**不删历史**。
  - 区别仅在列名：`element_bom_item` 版本列 = `characteristic`；`material_bom_item` 版本列 = `bom_version`（其 `characteristic` 仍是类型标记）。
- **`nextVersionOf` / `flip` 的作用域保持含 `characteristic`，不改**（D3：选配双行依赖）。
- 入口校验（`requireSystemType` 等）维持不变。

**写入语义（material_bom_item，同一 characteristic 组内）**：
1. `loadCurrentGroup` 比对当前子行集；完全相同 → 复用版本、不写。
2. 不同 → `nextVersionOf` 主表 max+1；
3. `flip` 主 + 子当前组 → `is_current=false`（保留为历史）；
4. 写主表新版本行；写子表各行（带 `bom_version=newVersion`、`is_current=true`）；
5. **不调 `deleteNonCurrent`**（历史保留）。

---

## 6. 模块 3 · 报价导入 `MaterialBomMergeHandler`（去重合并 + 版本对齐）

沿用 `docs/table/报价系统Excel导入落库方案.md` V3.2【去重合并实现细则】，叠加版本化两处变化：

- 调 `writeVersionedMasterDetail` 时 `childVersionColumn = "bom_version"`。
- **第 4 步「反向 characteristic 旧行下线」在保留模型下改语义**：
  - V3.2 原为"物理 `DELETE` 反向 characteristic 子行"；
  - **现改为"`FLIP is_current=false` 留历史"**（主表 + 子表都翻），不再删除。
  - 仍**只按单料号**作用域、**不碰 `CFG-` 料号**。
- 同 characteristic 内升版由 writer 的 flip+insert 负责（历史保留，§5）。
- `material_master` upsert 副作用保留（仅物料BOM 投入料号；组成件BOM 料号不写）。

> 这与 V3.2 文档需同步：把第 4 步从 DELETE 改为 FLIP（回写 V3.2 实现细则）。

---

## 7. 模块 4 · 核价导入 `P06MaterialBomHandler`

- `childVersionColumn` 由 `null` → `"bom_version"`。
- 核价侧**无 ASSEMBLY、无合并、无第 4 步**（`PricingImportService` 无组成件BOM handler，`characteristic` 恒为 `NULL`）。
- 仅子表跟随主表版本对齐 + 多版本保留。

---

## 8. 模块 5 · 选配 `ConfigureProductService`

- 两处 `writeVersionedMasterDetail`（`:568` ASSEMBLY、`:584` MATERIAL/NULL）的 `childVersionColumn` 由 `null` → `"bom_version"`。
- **双 `is_current=true` 主行契约不变**（per-characteristic 作用域，两次调用不互翻）。
- 重存配置时旧版转 `is_current=false` 累积历史；读取侧统一 is_current 过滤后无功能影响。

---

## 9. 模块 6 · 读取侧 `is_current` 联动（仅组件配置 SQL）

> **风险核心 + 最大工作量。** material_bom_item 变多版本后，读取点若不过滤 `is_current` → 升版后立刻返历史重复行（AP-22「(共 N 项)」族）。范围**仅 `component_sql_view` 配置 SQL**，不碰 PG `CREATE VIEW`（D5）。

### 9.1 需补 `is_current` 的配置视图（9 个）

| 配置视图 | material_bom_item 别名 | 用途 |
|---|---|---|
| `v12_raw_bom` | `bi` | 报价 来料BOM 卡片 |
| `zcj_bom` | `asy` | 子件 driver |
| `zcj_view` | `asy` | 组成件视图 |
| `v12_raw_element_bom` | `mbi` | 来料与元素BOM |
| `ys_view` | `mbi`（仅此别名的那一处；`mbt` 处已有，勿动） | 元素视图 |
| `composite_child_materials_mirror` | `asy` | 选配-材质 mirror |
| `composite_child_processes_mirror` | `bom` | 选配-工序 mirror |
| `composite_child_weights_mirror` | `asy` + `asy2`（两处） | 选配-单重 mirror |
| `composite_child_elements_mirror` | `parent` | 选配-元素 mirror |

### 9.2 已带 `is_current`、无需改（仅核对）

`cz_view`（`asy` ×2）、`zpj_view`（`mbt` ×2）、`ys_view` 的 `mbt` 那处。

### 9.3 实现方式（照搬 V281）

- 新 Flyway 用 `regexp_replace` 给每个目标模板的 mbi 别名追加 `AND <别名>.is_current = true`。
- **每锚点 dry-run `count=1` 验证唯一命中**（多处出现的 weights/ys 逐处改、改完读回核对）。
- 落库后 `touch` java 触发 Quarkus 重启 → `BnfTableMetaSyncer` 重新同步、清 BNF 缓存。
- mirror 视图（`composite_child_*`）含 `MAX(characteristic)` 逻辑，需确认与新增 `is_current` 谓词组合正确。

### 9.4 PG 视图说明

`v_composite_child_materials` / `v_composite_child_processes` 等 PG 视图**经确认为非运行时渲染路径**（渲染走 `composite_child_*_mirror` 配置 SQL），故排除在本次范围外。

---

## 10. 模块 7 · 历史数据

- 不 backfill 历史明细。
- 仅模块 1 的一次性当前行 `bom_version` 对齐（满足新 uq 一致性）。

---

## 11. 实施顺序（关键）

1. **模块 6 先行**：把 9 个配置 SQL 的 `is_current` 全部补好并验证（读取侧护栏先到位）。
2. **模块 1 + 2**：加列 / 改 uq / 存量对齐 + writer 去特例。
3. **模块 3 / 4 / 5**：报价合并、核价、选配三条写入链改 `childVersionColumn`。
4. 回写 `报价系统Excel导入落库方案.md` V3.2 第 4 步（DELETE → FLIP）。

> 顺序倒置（先写入多版本、后补读取）= 升版瞬间出重复行。必须读取侧先行。

---

## 12. 验证

- **单元** `VersionedV6WriterTest`：material_bom_item 多版本用例——升版保留旧版（`is_current=false`）、当前版 `is_current=true` 唯一、子行带正确 `bom_version`；内容相同复用版本不写。
- **集成**：
  - 报价合并升版**正反向**：先 NULL 后 ASSEMBLY（旧 NULL 转历史、当前 ASSEMBLY 唯一）；反向亦然。
  - P06 升版：子表多版本、主子版本对齐。
  - 选配重存：双 current 行保留、旧版转历史。
- **E2E 双 spec**：`quotation-flow.spec.ts` + `composite-product-flow.spec.ts`，8 Tab `'加载中' final count = 0`。
- **回溯查询**：同 `(料号, characteristic)` 查到多版本，`is_current=true` 唯一；各历史版子行明细可查。
- **读取点 is_current**：升版后逐个配置视图验证不返重复行（SQL count / F12 Network）。

---

## 13. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 漏改读取点 → AP-22 重复行 | §9 穷举 9 个 + 核对 3 个；E2E 双 spec 兜底 |
| 改 writer 误伤选配双行 | D3 不动版本作用域；选配 E2E 回归 4 Tab |
| 新 uq 与存量 NULL `bom_version` 冲突 | uq 用 `COALESCE(bom_version,'')`；模块 1 一次性对齐存量当前行 |
| 视图 DDL/模板改后缓存残留 | 每次 `touch` java 重启清 `BnfTableMetaSyncer` 缓存 |
| 选配重存历史累积膨胀 | 读取侧 is_current 过滤；如需可后续加清理任务（非本次范围） |

---

*文档完*
