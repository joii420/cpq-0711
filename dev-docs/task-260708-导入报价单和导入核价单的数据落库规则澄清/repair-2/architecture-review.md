# 架构评审 · repair-2（报价单）：材质料号不入料号表 + characteristic=RECIPE per-component

> 评审人：cpq-architect ｜ 输入：`backtask.md`（4 项已定决策 A/B/C/D）｜ 只出设计，不改任何生产代码/视图/handler
> 结论先行：**建议拆两阶段落一个 PR**（视图/镜像先兜底、handler 后改），可在同一分支同一迁移批完成；**不能只改 handler 不改视图**——否则材质料号行会整条从渲染视图消失。最大风险点见 §5。

---

## 0. 评审中新发现的两条“比 backtask 更严重”的事实（必须纠正认知）

backtask §5 风险 2 把视图问题描述为“material_master join 断链 → 返 null（空名）”。**实际比这严重**：

1. **不是空名，是整行消失。** `v_composite_child_materials`（PG 视图，当前终态 V303）与镜像模板 `composite_child_materials_mirror`（`component_sql_view` 表，V283）选“材料行”用的谓词是 **`asy.characteristic IS NULL`**。决策 C 把材料行 characteristic 从 `NULL` 改成 `'RECIPE'` 后，这些行**不再命中过滤条件 → 从视图里彻底消失**（不是名字为空，是整条不返）。名字（`COALESCE(mm.material_name, asy.component_no)`）本来就会 fallback 到 component_no 原始码，不会是空字符串。所以**第一优先要修的是过滤谓词，其次才是品名兜底**。

2. **有两条并行渲染链路，不止 PG 视图。** 报价渲染同时走：
   - **PG 视图** `v_composite_child_*`（V233→V238→V280→V303）；
   - **`component_sql_view` 镜像/组件 SQL 模板**（`$view` 路径，V255 / V283 / V263 等），前端 `data_driver_path` / `basic_data_path` 走 `$<sql_view_name>` 引用（AP-53 规则）。
   两条链路里凡是用 `characteristic IS NULL` 选材料行的都要改；用 `characteristic IS NULL OR characteristic <> 'ASSEMBLY'` 的（如 V255 `v12_raw_bom`）已天然兼容 RECIPE，不用动。

**统一修法（贯穿全文）**：把所有“选材料行”的谓词从 `characteristic IS NULL` 收敛为 **`characteristic IS DISTINCT FROM 'ASSEMBLY'`**。它同时包含旧 `NULL`（存量）+ 新 `RECIPE`，排除 `ASSEMBLY`，**向后兼容 + 一次到位**。

---

## §1 影响面总览

### 1.1 受影响文件 / 视图 / 表清单

| 类别 | 对象 | 当前定义位置 | 改什么 | 是否新回归 |
|---|---|---|---|---|
| 写入器 | `VersionedV6Writer.writeVersionedMasterDetail(s)` | `v6/versioning/VersionedV6Writer.java` | characteristic 由「主表固定列强制覆盖子行」改为「子行自带、per-component」。**只需删/改 handler 里覆盖子行的一行；写入器本身可不改**（见 §3） | 否（结构不动） |
| 合并 handler | `MaterialBomMergeHandler.merge()` | `v6/quote/MaterialBomMergeHandler.java` | 物料BOM 分支：component_no 存原始码、删 accMaterialMaster、characteristic=RECIPE；组成件分支：命中材质料号集→同物料分支；per-component 打 characteristic | 是（核心改动） |
| 编排器 | `QuoteImportService`（step1 merge 早于 Q04） | `v6/quote/QuoteImportService.java` | **新增预扫**：merge() 前把两 sheet 材质料号灌入 `ctx.sharedCache`（见 §4，顺序是硬约束） | 是（新增） |
| 上下文 | `ImportContext.sharedCache` | `v6/parser/ImportContext.java` | 复用现有 `Map<String,Object> sharedCache`，无需改类 | 否 |
| 🔴 PG 视图 | `v_composite_child_materials` | V303（终态） | 过滤 `IS NULL`→`IS DISTINCT FROM 'ASSEMBLY'` + 品名/规格兜底 `material_recipe` | **是（不改则材料行消失）** |
| 🔴 镜像模板 | `composite_child_materials_mirror` | `component_sql_view`（V283 落库） | 同上（过滤 + 品名兜底） | **是** |
| ⚠️ 选配视图 | selopt 材料视图（`characteristic IS NULL`） | V263 | 若渲染材料品名同上；需确认是否报价链路在用 | 待确认 |
| ✅ 已兼容 | `v12_raw_bom`（`IS NULL OR <>'ASSEMBLY'`） | V255 | 无需改 | 否 |
| 🟡 旁证视图 | `v_composite_child_processes` / `_weights` | V280 | 材料行不进这俩（过滤 `=ASSEMBLY`）；材料重量本就走 master.unit_weight（材料从不写 master.unit_weight，历史即 NULL）→ **非本次新回归**，仅需验证 | 否（预存缺陷） |
| 🟡 元素视图 | `v_composite_child_elements` | V280 | join `ebi.material_no`（Q04 体系），品名兜底问题**预先存在**（Q04 从不登记材质料号进 master）→ 非本次新回归；可选一并兜底 | 否（预存） |
| 表约束 | `material_bom_item.component_no` | V219 | **无 FK 指向 material_master**（只有 `idx_material_bom_item_comp` 普通索引）→ 存原始码不会插失败 ✅；但列宽 `VARCHAR(20)` vs `material_recipe.code VARCHAR(64)`，需确认材质编号 ≤20 字符（见 §4 风险） | 需确认列宽 |

### 1.2 是否触及渲染基线的判定

**触及。** `docs/三大核心模块基线.md`（L130~134、L432、L609+）明确把 `v_composite_child_materials/_elements/_processes/_weights` 列为“选配-*”组件的渲染基线视图（`COMP-CFG-MATERIAL-RECIPE` 等组件 `dataDriverPath` 直指之）。**但破坏面可控**：本次改动是 **①放宽过滤谓词（`IS DISTINCT FROM 'ASSEMBLY'` 严格超集，存量 NULL 行行为不变）+ ②追加 `LEFT JOIN material_recipe` 兜底列（不改列结构、不删列）**，属**加法式、向后兼容**修改，不改视图输出列签名（`declared_columns` 不变），不触发下游 enrich/cache/字段类型协议（AP-44）联动。基线“不轻易改”条款需 architect 评估——本文即评估，结论：**允许改，走 `CREATE OR REPLACE`（materials 因 V303 是 `CREATE VIEW`，改列时才需 DROP；本次只改 WHERE/JOIN 可 `CREATE OR REPLACE`），DDL 后按 CLAUDE.md 纪律 touch java 重启清 `ImplicitJoinRewriter`/`CachedSqlCompiler` 缓存**。

---

## §2 视图兜底方案（逐视图改法 + 向后兼容论证）

### 2.1 join key 前置结论（实现前必须验证的假设）

component_no 现在存**原始材质料号**。品名/规格要从 `material_recipe` 取，join key = `material_recipe.code`（V318 注释：`code=材质编号 00001…`）。
**假设**：Excel「材质料号」列值 == `material_recipe.code`。**这是全方案的基石，实现方必须先用真实导入数据核对**（`SELECT code FROM material_recipe LIMIT 20` vs 物料BOM「材质料号」列样本）。若不等（例如材质料号是别名而 code 是编号），join key 要换成 `material_recipe.symbol` 或加映射，方案骨架不变但 key 要改。

### 2.2 `v_composite_child_materials`（🔴 必改，PG 视图）

第一个 UNION 分支（读 material_bom_item 的材料行）改两处：

```sql
-- 改前（V303）
   FROM material_bom_item asy
     LEFT JOIN material_master mm ON mm.material_no::text = asy.component_no::text
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text
    AND asy.characteristic IS NULL              -- ← ① 只命中旧 NULL 行
    AND asy.is_current = true

-- 改后
   FROM material_bom_item asy
     LEFT JOIN material_master mm ON mm.material_no::text = asy.component_no::text
     LEFT JOIN material_recipe  mr ON mr.code::text        = asy.component_no::text   -- ② 追加材质库兜底
     LEFT JOIN customer c ON c.code::text = asy.customer_no::text
  WHERE asy.system_type::text = 'QUOTE'::text
    AND asy.characteristic IS DISTINCT FROM 'ASSEMBLY'   -- ① NULL(存量)+RECIPE(新) 都命中，排除 ASSEMBLY
    AND asy.is_current = true
```

输出列品名/规格加 `mr.*` 兜底（保持列名/顺序不变）：
- `child_part_name`：`COALESCE(mm.material_name, mr.name, asy.component_no)`
- `material_name`：`COALESCE(asy.component_usage_type, mm.material_type, mr.name, mm.material_name)`
- `spec_label`：`COALESCE(mm.specification, mr.spec_label, asy.component_usage_type)`
- `chemical_symbol`：`mr.symbol`（原为 `NULL::varchar`，现可给真值）
- `recipe_id`：`mr.id`（原为 `NULL::uuid`，现可给真值）
- `recipe_type`：`COALESCE(asy.component_usage_type, mr.recipe_type)`

> 说明：`mr.name` 可能为 NULL（V318 放开非空，导入置 NULL），所以 fallback 链末尾仍保留 `asy.component_no`，任何情况下品名非空——满足 AC-7。

第二个 UNION 分支（material_master fallback，读 master 里没进任何 BOM 的料号）：材质料号已不在 master，此分支对材质料号天然不产出；把内部 `NOT EXISTS(... asy2.characteristic IS NULL ...)` 里的 `IS NULL` 同步改成 `IS DISTINCT FROM 'ASSEMBLY'` 以与第一分支去重口径一致（否则同一料号可能既进分支1又进分支2）。

**向后兼容论证**：`characteristic IS DISTINCT FROM 'ASSEMBLY'` ⊇ `characteristic IS NULL`，存量（未重导）NULL 行行为 100% 不变；`LEFT JOIN material_recipe` 对非材质行（mm 命中的普通料号）不匹配 mr → COALESCE 优先取 mm.* → 输出不变。**零回归于存量数据**。

### 2.3 `composite_child_materials_mirror`（🔴 必改，`component_sql_view` 镜像模板）

V283 把模板里 `AND asy.characteristic IS NULL` 由注释改为生效。本次需再把它改为 `AND asy.characteristic IS DISTINCT FROM 'ASSEMBLY'`，并同样追加 `LEFT JOIN material_recipe mr ON mr.code = asy.component_no` + 品名兜底列。做法与 §2.2 一致，用 `UPDATE component_sql_view SET sql_template=... WHERE sql_view_name='composite_child_materials_mirror'`。DDL/模板改完 touch java 触发 `BnfTableMetaSyncer` 重同步（V283 部署注同款）。
> `declared_columns` 若原含 `chemical_symbol/recipe_id/recipe_type` 就不用动；若要新暴露 mr 字段需同步 `declared_columns`（AP-53 契约）——建议**先只改过滤+品名兜底，不新增列**，把列签名改动降到零，最稳。

### 2.4 selopt 材料视图（V263，待确认）

V263 的 selopt 视图有 `characteristic IS NULL`（材料）/`= 'ASSEMBLY'`（组成件）两类分支。**实现方需先确认这些 selopt 视图是否在报价渲染链路被引用**（`grep data_driver_path`/`basic_data_path` 指向 selopt 视图名）。若在用材料分支，按 §2.2 同款改（`IS DISTINCT FROM 'ASSEMBLY'` + mr 兜底）；若只服务选配 Step2 且选配数据不走本次材质料号，则不动，仅记录。

### 2.5 不需改的视图（论证）

- `v_composite_child_processes`（V280 L102）/ `_weights`（L117）材料 BOM 分支过滤 `characteristic = 'ASSEMBLY'`：材料行本就不进（材料无组装工序），RECIPE 不影响。**验证项**：材料“重量”若期望渲染，历史是走 `material_master.unit_weight`，而合并 handler 从不写 master.unit_weight，故材料重量历史即 NULL——**属预存缺陷、非本次回归**，如需修另立（读 `material_bom_item.rough_weight/net_weight`）。
- `v_composite_child_elements`（V280）：join `ebi.material_no`（Q04 element_bom 体系），Q04 从不把材质料号登记进 master，品名 fallback 到 code 是**历史既有行为**，非本次新增。可选一并加 `material_recipe` 兜底以求一致，但**不在本次必改项**（Q04 明确 out of scope）。
- `v12_raw_bom`（V255 L76）：已用 `characteristic IS NULL OR characteristic <> 'ASSEMBLY'`，RECIPE 天然命中，**无需改**。

---

## §3 characteristic per-component 方案（写入器改法 + 唯一键/版本/is_current 影响）

### 3.1 characteristic 当前如何参与三处

1. **子表唯一键**：`uq_material_bom_item(system_type, customer_no, material_no, COALESCE(characteristic,''), COALESCE(bom_version,''), COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,''))`（V219→V293）。characteristic **已经在唯一键里**。
2. **主表唯一键 / 版本分区**：`uq_material_bom_v6(system_type, customer_no, material_no, bom_version, COALESCE(characteristic,''))`。主表 characteristic 参与主表唯一键。
3. **写入器传播**：handler 把 characteristic 放 `masterFixed`（主表固定列 L191/L247）**并**用 `for(r:childRows) r.put("characteristic", targetChar)`（L183/L237）**强制把整组子行 characteristic 覆盖为 master 级同一值**。这就是“按 material_no 整体判定”的根因。
4. **版本分组键（childGroupKey）**：`{system_type, customer_no, material_no}`，**不含 characteristic** → flip/load 按 material_no 整组翻转，characteristic 不参与分组。
5. **内容比较（CHILD_CONTENT）**：**故意不含 characteristic**（L48~51 + L234~236 注释），避免旧设计 `NULL→ASSEMBLY` 被误判为内容变化而空升版。

### 3.2 关键结论：**写入器本身不用改，只改 handler 的子行赋值**

per-component characteristic 的最小实现 = **在 handler 的 map 构建阶段就给每个子行打上自己的 characteristic，并删掉 L183/L237 那句 master 级覆盖**。因为：
- 写入器 L479~483（批量）/ L583~593（单条）插入子行时是 `all.putAll(row)`——**只要 row 里已带 `characteristic`，就会被逐行原样写入**，写入器天生支持 per-row characteristic，无需动结构。
- childGroupKey 不含 characteristic → flip 仍按 material_no 整组翻转（RECIPE 行与 ASSEMBLY 行一起翻），语义正确（同一料号升版整组换代）。
- uq 含 characteristic → 同 master 下 `RECIPE` 行与 `ASSEMBLY` 行 **唯一键天然可区分、不撞键**（cardinality 变大只会更安全）。

**主表 characteristic 怎么办（混合料号）**：master 只有一行、characteristic 只能一个值。建议 **master 保持现语义**：`isAssembly = 有无 ASSEMBLY 子行 ? "ASSEMBLY" : null`（即 masterFixed 的 bom_type/characteristic 不变）。它退化为“该料号有没有组装 BOM”的主表级标志，与子行 characteristic **解耦**。混合料号的 master = ASSEMBLY（因为它确有组装子行），其下 RECIPE 材料子行照常独立携带 RECIPE。**主表唯一键/版本分区完全不变，零风险**。

### 3.3 必须处理的两个陷阱

1. **内容比较漏掉 characteristic → 存量重导时 RECIPE 静默丢弃**（与历史 T5 “组合指纹漏维度致 qty/工序静默丢弃”同族）。
   - 现象：老行 characteristic=NULL、内容列（component/qty…）没变，新导入想改成 RECIPE。因 CHILD_CONTENT 不含 characteristic → `multisetEqual=true` → 写入器判“无变化”直接复用版本、**不重写 → characteristic 永远停在 NULL**。
   - 影响面：**仅“不清空、原地重导”路径**。本任务验收是「清空重导」（AC 前置），existing 为空 → 必写 → 无此问题。
   - 建议：**验收走清空重导即可，不改 CHILD_CONTENT**（改了会让历史 NULL→ASSEMBLY 场景重新触发空升版，得不偿失）。若产品要求“原地重导也能纠正 characteristic”，再单独把 `characteristic` 加进 CHILD_CONTENT 或加为 versionTrigger，并跑存量回归——**列为 follow-up，不塞进本次**。
2. **同 master 下同一 component_no 既是材料又是组成件（merge 撞 key）**：merge 按 component_no 归并（L172~181）。若某 component_no 在 matByMat（RECIPE）与 asmByMat 都出现，归并后要**确定性地取 RECIPE**（决策 D：∈ 材质料号集恒按材质处理）。实现时在 build 阶段就让两侧对该 component_no 都写 RECIPE，或归并后强制 `characteristic=RECIPE if component_no ∈ 材质料号集`。属边界，但要显式处理，别让 asm 的 ASSEMBLY 反向覆盖。

### 3.4 必回归项（§3 专属）

- **不撞键**：清空重导后，同一 material_no 同时含 RECIPE + ASSEMBLY 子行不报 uq 冲突（AC-4）。
- **is_current 单代**：重导两次，每个 material_no 的 material_bom_item 只有一代 is_current=true；RECIPE 与 ASSEMBLY 行同代同时 is_current。
- **版本号连续**：`material_bom.bom_version` 按 material_no max+1，不因 characteristic 拆分成两条序列（因 childGroupKey 不含 characteristic，主表 characteristic 也保持单值）。
- **RECIPE 不被 ASSEMBLY 覆盖**：混合料号里材料子行 characteristic 稳定为 RECIPE（AC-4/AC-5）。
- **真组成件不受影响**：非材质料号组成件仍 ASSEMBLY + 登记 master + 正常 resolve/铸号（AC-6）。

---

## §4 跨 handler 材质料号集 + component_no FK 结论

### 4.1 材质料号集的落法（顺序是硬约束，非可选）

**编排顺序实测**：`QuoteImportService` L105~118 先跑 `bomMerge.merge()`（step1），L64 的 handler 列表（含 q04）在 L147 `h.handle()` **之后**才跑。即 **merge() 早于 Q04**。

⚠️ 因此**不能靠 Q04 在处理时往集合里填料号**——决策 D 要在 merge() 的**组成件分支**（就在 merge() 内、Q04 之前）就用到「物料BOM ∪ 物料与元素BOM」全集。只有物料BOM 的材质料号能在 merge() 内自然收集到，Q04 的那半还没跑。

**推荐方案（A · 显式预扫，唯一稳妥）**：在 `QuoteImportService` 调 `bomMerge.merge()` **之前**，加一个轻量预扫：
```
Set<String> matSet = new LinkedHashSet<>();
// 物料BOM sheet：读「材质料号」列
// 物料与元素BOM sheet：读「材质料号」列（Q04 的源）
ctx.sharedCache.put("quoteMaterialNoSet", matSet);
```
- 存放位置：复用 `ImportContext.sharedCache`（已存在，注释即写“已写 material_master 的料号集…约定由 Handler 自管理”），**不改 ImportContext 类**。key 约定 `"quoteMaterialNoSet"`。
- merge() 物料BOM 分支：材质料号恒按材质处理（无需查集合），同时可 union 进集合（冗余安全）。
- merge() 组成件分支：`component ∈ ctx.sharedCache["quoteMaterialNoSet"]` → 按材质处理（RECIPE、原始码、不登记 master）；否则真组成件走原路径。
- Q04 later：无需再写集合（决策 D 已在 merge 内用完）；Q04 自身逻辑不动（out of scope）。

**备选方案 B（不推荐）**：调整 handler 顺序让 Q04 的收集早于 merge。否决理由：merge() 是 step1 且跨两 sheet 单事务的特殊编排，插到 Q04 后会打乱事务边界与进度步数语义，改动面远大于预扫，且脆弱。

### 4.2 component_no 是否有 FK → material_master

**无 FK。** `material_bom_item.component_no VARCHAR(20)` 仅有普通索引 `idx_material_bom_item_comp`（V219 L108），全库 grep 无 `FOREIGN KEY ... REFERENCES material_master`（唯一相关 FK 是 `material_master.material_recipe_id → material_recipe`，方向相反）。**故 component_no 存原始材质料号、且该料号不在 master，INSERT 不会失败**（AC-3 可达）。

⚠️ **列宽风险**：`component_no VARCHAR(20)` vs `material_recipe.code VARCHAR(64)`。若真实材质编号 ≤20 字符（V318 示例 `00001` 仅 5 字符）→ 安全；若存在 >20 字符的材质料号 → 原始码写 component_no 会**截断/报错**。实现方必须核对真实材质料号最大长度；若超 20，需先 `ALTER TABLE material_bom_item ALTER COLUMN component_no TYPE varchar(64)`（会牵连引用 component_no 的视图，需 DROP/rebuild，成本上升）——**列为实现前置检查项**。

---

## §5 风险与分阶段建议

### 5.1 能不能一把改？

**能，但内部要有序，落一个 PR / 一个迁移批。** 各改动的耦合方向：
- 视图/镜像的**过滤放宽**是严格超集，**单独部署对存量零影响**（NULL 行照旧）→ 可先落、可与 handler 同落，都安全。
- handler 写 RECIPE **依赖**视图已放宽过滤，否则材料行消失 → **视图改动必须 ≤ handler 改动同批上线**，绝不能出现“handler 已写 RECIPE、视图还在 `IS NULL`”的窗口。
- 因 dev 是清空重导、且视图改动向后兼容，**推荐单分支单 PR**，迁移文件内**先视图后 handler 逻辑无先后要求**（视图是 DDL 迁移、handler 是 Java），只要同批部署即可。

**推荐落地顺序（同一分支）**：
1. 先确认两个前置假设：`material_recipe.code == 材质料号`（§2.1）、材质料号长度 ≤20（§4.2）。任一不成立先解决再往下。
2. 迁移 Vxxx：`CREATE OR REPLACE VIEW v_composite_child_materials`（过滤 + mr 兜底）；`UPDATE component_sql_view` 改 `composite_child_materials_mirror`；（selopt 视图按 §2.4 确认后决定）。DDL 后 touch java 重启清缓存。
3. handler：预扫（QuoteImportService）+ 物料BOM 分支（原始码/删 master/RECIPE）+ 组成件分支（集合命中→材质处理）+ 删除 L183/L237 的 master 级 characteristic 覆盖、改为 per-component。
4. 跑 AC-1~AC-8 SQL + E2E。

### 5.2 哪些必须 E2E

**必须。** 改了 `v_composite_child_materials`（渲染基线视图）+ `composite_child_materials_mirror`（driver 镜像）+ handler 落库链路，命中 CLAUDE.md「协议级改动必跑 Playwright E2E」+ AP-53 视图链路纪律：
- `e2e/quotation-flow.spec.ts`（SIMPLE，材质料号自指行渲染）；
- `e2e/composite-product-flow.spec.ts`（COMPOSITE，混合 RECIPE+ASSEMBLY 子件材质渲染）；
- 断言：材质 Tab 各行有品名（非空、非“加载中”）、`'加载中' final count = 0`、材料行不因 RECIPE 消失。

### 5.3 最大风险点（单一）

**过滤谓词漏改任一条渲染链路 → 材料行整条消失（静默、无编译/运行错，只是 UI 少行）。** 具体是 `v_composite_child_materials`（PG）与 `composite_child_materials_mirror`（镜像模板）**两条都要改**，漏一条则对应链路的产品在材质 Tab 缺行。这类“少行”不报错、API/TS check 看不到，**只有 E2E + 逐 Tab 行数断言能抓**。次风险：`material_recipe.code == 材质料号` 假设不成立导致 join 全落空（品名仍 fallback 到 code，不消失但不达 AC-7）。

---

## §6 给实现方的明确 TODO 清单

> 顺序即建议实施顺序。每条括号标注对应 AC / 风险。

**P0 前置核对（写码前必做）**
- [ ] T0-1 用真实导入数据核对 `material_recipe.code` 是否等于物料BOM/物料与元素BOM「材质料号」列值（§2.1）。不等则先定 join key。
- [ ] T0-2 核对真实材质料号最大字符长度是否 ≤20（`material_bom_item.component_no VARCHAR(20)`）。超 20 先决定是否加宽列（§4.2）。

**P0 视图 / 镜像兜底（先落，向后兼容）**
- [ ] T1 新迁移 `CREATE OR REPLACE VIEW v_composite_child_materials`：①两处 `characteristic IS NULL` → `IS DISTINCT FROM 'ASSEMBLY'`（含第一分支 WHERE + 第二分支 `NOT EXISTS` 内谓词）；② `LEFT JOIN material_recipe mr ON mr.code = asy.component_no`；③ 品名/规格/symbol/recipe_id/recipe_type 按 §2.2 加 `COALESCE(..., mr.*, ...)` 兜底（列名顺序不变）。（AC-7 / 风险2）
- [ ] T2 `UPDATE component_sql_view SET sql_template=...` 改 `composite_child_materials_mirror`：同 T1 过滤 + mr 兜底；不新增 `declared_columns` 列（列签名零改动）。（AC-7 / 风险2）
- [ ] T3 确认 V263 selopt 材料视图是否在报价渲染链路使用；使用则同款改，不使用则仅在文档记录“已评估、无需改”。（§2.4）
- [ ] T4 迁移落 `db/migration/` 后 touch 一个 java 文件触发 Quarkus 重启（清 `ImplicitJoinRewriter`/`CachedSqlCompiler`/`BnfTableMetaSyncer` 缓存，CLAUDE.md DDL 纪律）。

**P0 编排预扫（顺序硬约束）**
- [ ] T5 `QuoteImportService` 在 `bomMerge.merge()` 调用**之前**预扫「物料BOM.材质料号」∪「物料与元素BOM.材质料号」→ `ctx.sharedCache.put("quoteMaterialNoSet", set)`（§4.1 方案 A）。不改 `ImportContext` 类。（决策 D / 风险3）

**P0 合并 handler 改造**
- [ ] T6 物料BOM 分支（`merge()` materialRows 循环）：component_no 取原始材质料号（去掉 `materialNoResolver.resolve(...)`，直接原值）；**删 L89 `accMaterialMaster(...)` + L90 recordWrite("material_master")**；子行 map 打 `characteristic="RECIPE"`。（AC-1/AC-2/AC-3）
- [ ] T7 组成件BOM 分支（assemblyRows 循环）：`if (component_no ∈ ctx.sharedCache["quoteMaterialNoSet"])` → 同 T6（原始码、不登记 master、RECIPE）；`else` → 保持现状（resolve、登记 master、`characteristic="ASSEMBLY"`）。（AC-5/AC-6）
- [ ] T8 删掉 `for (var r : childRows) r.put("characteristic", targetChar);`（L183 批量分支 + L237 单条分支），改为**子行携带自身 characteristic**；merge 归并时若同一 component_no 两侧冲突，强制 `RECIPE`（∈材质料号集优先）。（AC-4 / §3.3-2）
- [ ] T9 主表 masterFixed 保持 `bom_type/characteristic = 有 ASSEMBLY 子行 ? "ASSEMBLY" : null` 不变（master 级标志，与子行解耦）——**不要**试图让 master characteristic 表达混合。（§3.2）
- [ ] T10 写入器 `VersionedV6Writer` **不改**（确认 `insertRowsBatched`/`writeVersionedMasterDetail(s)` 已逐行 `putAll(row)` 支持 per-row characteristic）。若发现 CHILD_CONTENT 需含 characteristic 才能过某回归——**停下来找 architect**，别擅自加（会触发历史空升版，见 §3.3-1）。

**P0 验收**
- [ ] T11 AC-1~AC-8 逐条 SQL（清空重导后）：材质料号 0 入 master / RECIPE / 原始码 / RECIPE 不被覆盖 / 组成件命中集合也 RECIPE / 真组成件仍 ASSEMBLY+入 master / 视图材料行有名 / 撞键·is_current·版本回归不破。
- [ ] T12 跑 `quotation-flow.spec.ts` + `composite-product-flow.spec.ts`，材质 Tab 逐行有品名、`'加载中'=0`、材料行不消失。（§5.2）

**边界（本次不做，记录）**
- 核价侧不动；Q04 不动；`v_composite_child_elements/_processes/_weights` 的材料兜底属预存/另议；材质料号缺材质库的“缺库告警”另立（BL-0039 引用校验）；“原地不清空重导也纠正 characteristic”属 follow-up（§3.3-1）。
