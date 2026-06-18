# 报价系统 Excel 导入 · 料号自动维护与生成 设计

> 日期：2026-06-15 | 状态：已确认需求，待实现
> 来源：`docs/table/报价系统Excel导入落库方案.md` 中 `[20260615更新]` 标记

---

## 一、背景与目标

报价系统 Excel 导入落库方案新增三处 `[20260615更新]` 规则（§3 物料BOM、§12 组成件BOM）：

1. **组成件料号需维护到料号表**：当 Excel 行的组成件/投入料号为空但名称有值时，按名称匹配料号表；匹配不到则按规则自动生成料号后落库。
2. **自动生成料号规则**：十位数 `9000000000` 递增（约定为当前规则，将来可能修改，需封装便于替换）。
3. **§12 工序回填**：组成件BOM 子表中工序编号为空、工序名称有值时，按工序名称查 `process_master` 取第一条，回填 `operation_no`。

目标：在现有 V6 报价导入链路（`MaterialBomMergeHandler` 等）中落地以上规则，并统一料号"匹配/生成"逻辑到单一可替换的解析器。

---

## 二、已确认的需求决策（11 项）

| # | 决策 | 结论 |
|:-:|------|------|
| 1 | 触发条件 | **料号为空、名称有值** 时触发"按名称匹配 / 匹配不到自动生成"。文档"料号名称为空"实指"料号为空"。 |
| 2 | 生成基准 | 取料号表中 `≥9000000000` 的最大 `material_no` **+1**；表中无 9 字头时第一个用 `9000000000`（自管理，不另建序列）。 |
| 3 | 同批同名去重 | 同一次导入内，多行料号为空且名称相同 → 视为同一料件，**只生成一个**新料号，同名行共用。 |
| 4 | 同名多料号 | 料号表已存在多条同名料件 → 取**第一条**（按 `material_no` 升序的稳定顺序）。 |
| 5 | 工序查不到 | 工序名称在 `process_master` 查不到 → `operation_no` **留空**，该行照常导入。 |
| 6 | §12 material_type | §12 同步料号表时 `material_type` 固定写 **3**（组成件）；**已存在则保留原值不覆盖**。 |
| 7 | 适用范围 | 规则适用于**所有同步料号表的 Sheet**（统一走解析器）。 |
| 8 | 无名称列 Sheet | 无名称列的 Sheet（§2/§18）料号为空 → **报错/跳过**记入失败明细（既无料号又无名称无法落库）。 |
| 9 | 已存在更新策略 | 料号已存在时，描述性字段 `material_name`/`material_type` **保留旧值、不覆盖**（仅旧值为空时回填）；各 Sheet 本职字段（如 §18 `unit_weight`）照常更新。 |
| 10 | "标记"含义 | 9 字头是当前约定规则，"作标记"指**该规则以后可能修改**；不加额外标识字段，但生成逻辑需**封装成可配置/易替换**的一处。 |
| 11 | 料号与名称都空 | 有名称列的 Sheet（§3/§12）某行料号、名称都空但非纯空行 → **报错/跳过**记入失败明细。 |

---

## 三、现状（代码事实）

- §3 / §12 由 `cpq-backend/.../basicdata/v6/quote/MaterialBomMergeHandler.java` 统一处理（同一 `REQUIRES_NEW` 事务的 `merge()` 方法内）。
  - §3 material 循环：**已**调用 `materialMasterRepo.upsertByMaterialNo(...)` 同步料号表；`投入料号` 为空时 `recordError` 跳过。
  - §12 assembly 循环：**完全没有**同步料号表，也不读工序名称。
- `MaterialMasterRepository.upsertByMaterialNo`（`cpq-backend/.../basicdata/v6/repository/MaterialMasterRepository.java`）当前 `ON CONFLICT` 用 **COALESCE(EXCLUDED.x, master.x)**，即"Excel 非空即覆盖旧值"——与决策 #9 相反。
- 料号表 `material_master`（V218）：业务键 `material_no VARCHAR(20)` UNIQUE，**无 `customer_no`**（全局唯一，按名称匹配天然全局）。无现成"按名称查"方法、无料号生成器。
- 工序主数据 `ProcessMaster` 实体 + `ProcessMasterRepository` 已存在；无"按 processName 取第一条"方法。
- §18（`Q18UnitWeightHandler`）、§2（`Q02CustomerMapHandler`）：均已有 `material_no 为空 → recordError`；§2 本就不写料号表。二者已符合决策 #8，**无需改动**。
- **`upsertByMaterialNo` 共 5 个调用点**（C1，跨报价 + 核价两线）：`MaterialBomMergeHandler`(§3)、`Q18UnitWeightHandler`(报价单重)、`P05CustomerMapHandler`(**核价**料号-客户关系，**传 `品名→material_name`**)、`P24UnitWeightHandler`(**核价**单重)、方法定义。§4.3 的覆盖策略调整必须避免影响 P05（详见 §4.3）。
- `material_master` 另有后加列（V265 `material_recipe_id` FK、V266/V284 `config_fingerprint` partial-unique）：自动生成的 9 字头料号**不写**这两列（FK 留空、fingerprint 留 NULL 不触发 partial unique）；9 字头与 `CFG-` 前缀料号（`merge()` 入口已拒）不冲突。
- 本设计**全程只碰 V6 表**（`material_master` / `process_master`），不触 V44 `mat_*`，不涉 AP-44 字段类型 17 检查点 / driver expansion / snapshot 链路，故**无前端 E2E 强制项**（C4）。

---

## 四、设计

### 4.1 新建 `MaterialNoResolver`（料号解析器，规则单一归处）

位置：`cpq-backend/.../basicdata/v6/service/MaterialNoResolver.java`，`@ApplicationScoped`。

核心方法（建议签名）：

解析器持有一个**事务级 `BatchState`**（调用方在 `merge()` 入口 new 一个、贯穿 §3/§12），内含：
- `Map<String,String> nameToNo`：name → 已解析/生成料号（决策 #3 同名去重）。
- `long batchMaxGenerated`：本批已生成的最大 9 字头号（初值 0）。**用于消除对"同事务 native 写立即可见"的依赖**（A2）。

```java
/**
 * 解析最终落库料号。
 * @param materialNo   Excel 料号（可空）
 * @param materialName Excel 料件名称（可空）
 * @param state        单次导入的事务级状态（nameToNo + batchMaxGenerated），调用方持有
 * @return 解析出的 material_no；当料号与名称都为空（isBlank）时抛 {@link MaterialNoUnresolvableException}
 */
String resolve(String materialNo, String materialName, BatchState state);
```

解析逻辑（入口对 materialNo/materialName 一律 `isBlank()` 判空，含纯空格，与现有 `recordError` 守卫口径统一 —— C3）：

| 场景 | 行为 |
|------|------|
| 料号非空 | 直接返回（trim 后）。 |
| 料号空、名称非空 | 1) `state.nameToNo` 命中 → 返回（决策 #3）；2) 否则按 `material_name` 全局查料号表，同名多条取**第一条**（`ORDER BY material_no ASC`，决策 #4）→ 命中则写入 `nameToNo` 后返回其料号；3) 未命中 → **生成新料号**（见下），写入 `nameToNo`，返回。 |
| 料号空、名称空 | 抛 `MaterialNoUnresolvableException`，调用方 `recordError` 跳过该行（决策 #8/#11）。 |

> **职责边界**：`MaterialNoResolver` 只负责"解析出料号号码"（匹配 / 缓存 / 生成），**不写 `material_master`**，因此不需要知道 `material_type`。料号表的 upsert（含 `material_name` + 正确的 `material_type`）一律由调用方（`MaterialBomMergeHandler`）在解析后执行。

**生成规则**（决策 #2、#10，封装在私有方法 `generateNextMaterialNo(BatchState)`，将来改规则只改此处）：

```sql
-- 持 advisory lock 后查 DB 当前最大 9 字头号
SELECT pg_advisory_xact_lock(:LOCK_KEY);                 -- B2，常量 LOCK_KEY 专用于料号生成
SELECT COALESCE(MAX(material_no::bigint), 8999999999)    -- 正则保证恰好 10 位、< bigint 上限，无溢出/截断
FROM material_master
WHERE material_no ~ '^9\d{9}$';
```

```text
nextNo = max(DB查到的MAX, state.batchMaxGenerated, 8999999999) + 1
state.batchMaxGenerated = nextNo            // 不依赖"上一行 upsert 是否已可见"，同批连续不同名也稳定递增（A2）
return String.valueOf(nextNo)
```

> 无 9 字头时 DB `MAX` 为 NULL → 回退 `8999999999`，+1 = `9000000000`。
> **B2 并发**：`pg_advisory_xact_lock`（事务级、提交/回滚自动释放）串行化跨导入事务的"读 MAX → 生成"窗口，避免两个并发导入各自算出同号后被 `ON CONFLICT DO UPDATE` 挤进同一行（两料件串号）。锁键用一个料号生成专用常量。

新增仓储方法：`MaterialMasterRepository.findFirstByMaterialName(String name)`（`ORDER BY material_no ASC LIMIT 1`）、`maxNineLeadingMaterialNo()`、以及取 advisory lock 的方法（或在生成方法内联）。

### 4.2 `MaterialBomMergeHandler` 改造

`merge()` 入口创建一个事务级 `BatchState`，§3 与 §12 两循环共用（保证跨两 Sheet 同名也只生成一次、递增连续）。

**§3 material 循环**：
- `投入料号` 为空但 `投入料号名称` 有值时，不再直接 `recordError`，改为 `resolver.resolve(投入料号, 投入料号名称, state)`。
- **先 resolve、再用解析出的料号**：① 作为 BOM 子行 `component_no`（写入 `matByMat` 的 **Map key**，C2 —— 否则多行空料号 key 冲突互相覆盖）；② 作为料号表键 upsert（`material_name=投入料号名称`，`material_type=digitsOnly(产出料号类型)`，走"保留旧值"模式）。
- 料号与名称都空 → `recordError` 跳过。

**§12 assembly 循环**（新增料号表同步 + 工序回填）：
- **先** `resolver.resolve(组成件料号, 组成件名称, state)`，解析出的料号**既作 `asmByMat` 的 Map key（C2）**、又写回 BOM 子行 `component_no`。
- 料号表 upsert：`material_name=组成件名称`，`material_type` 固定 **3**（决策 #6），走"保留旧值"模式 → 故 §12 仅对**新料件**落 type=3；已存在料件保留原值。
- 工序回填：读 `工序编号` 与 `组装工序`（工序名称列）。`工序编号` 空且 `组装工序` 有值 → `ProcessMasterRepository.findFirstByProcessName(组装工序)` → 命中填 `operation_no`，查不到留空（决策 #5）。
- 料号与名称都空 → `recordError` 跳过。

> **B1 §3/§12 交叉料件（已确认）**：同一料件既在物料BOM(§3) 又在组成件BOM(§12) 出现时，因 `merge()` 先跑 material 循环、§3 先写入 `material_type=digitsOnly(产出料号类型)`，§12 再来时按"保留旧值"保留 §3 的数字类型（拿不到 3）。**这是预期行为**：交叉料件保留 §3 的数字类型，纯组成件料件才落 type=3（决策 #6 "已存在保留原值"）。
>
> 解析、料号表 upsert、BOM 写入均在 `merge()` 的同一 `REQUIRES_NEW` 事务内，失败整体回滚一致。

新增仓储方法：`ProcessMasterRepository.findFirstByProcessName(String name)`。

### 4.3 `upsertByMaterialNo` 覆盖策略**参数化**（决策 #9 + A1：不回归核价侧）

`upsertByMaterialNo` 是**报价 + 核价两条线共用**的方法，共 5 个调用点：`MaterialBomMergeHandler`(§3，本次)、`Q18UnitWeightHandler`(报价单重)、`P05CustomerMapHandler`(**核价**料号-客户关系，**传 `品名→material_name`**)、`P24UnitWeightHandler`(**核价**单重)、方法定义。

**一刀切翻转 `material_name` 的 COALESCE 方向会连带改掉核价 P05 的品名更新行为（重新导入不再覆盖品名）——这是跨业务线回归，必须避免。** 故改为**参数化覆盖策略**，而非直接翻转：

- 给 `upsertByMaterialNo` 增加一个 `boolean preserveDescriptive` 形参（或新增重载 / `enum DescriptiveMergePolicy`）：
  - `preserveDescriptive=true`（**保留旧值**，旧值为空才回填）：
    ```sql
    material_name = COALESCE(material_master.material_name, EXCLUDED.material_name),
    material_type = COALESCE(material_master.material_type, EXCLUDED.material_type),
    ```
  - `preserveDescriptive=false`（**非空覆盖**，现状语义）：
    ```sql
    material_name = COALESCE(EXCLUDED.material_name, material_master.material_name),
    material_type = COALESCE(EXCLUDED.material_type, material_master.material_type),
    ```
- 调用方策略：
  - **§3 `MaterialBomMergeHandler` → `preserveDescriptive=true`**（满足决策 #9）。
  - **§12 `MaterialBomMergeHandler` → `preserveDescriptive=true`**（满足决策 #6 "已存在保留原值" + B1）。
  - **核价 `P05CustomerMapHandler` → `preserveDescriptive=false`**（保持现状，零回归）。
  - `Q18`/`P24` 单重只传 `unit_weight`、name/type 入参为 null，两种策略对它们等价；保持现有调用（可显式传 `false`）。

> 其余列（`specification`/`dimension`/`old_material_no`/`usage_property`/`unit_weight`/`standard_unit`）始终维持 `COALESCE(EXCLUDED.x, master.x)`（非空覆盖），不随该参数变化。
> ⚠️ **行为变更（限报价 §3）**：§3 原行为是后导入名称/类型覆盖旧值，改后（`preserveDescriptive=true`）不再覆盖。核价 P05 行为**不变**。

---

## 五、改动文件清单

| 文件 | 改动 |
|------|------|
| `basicdata/v6/service/MaterialNoResolver.java` | **新建** 解析器 + `BatchState` + 生成规则（含 advisory lock + `batchMaxGenerated`）。 |
| `basicdata/v6/service/MaterialNoUnresolvableException.java` | **新建** 料号/名称都空的运行时异常。 |
| `basicdata/v6/repository/MaterialMasterRepository.java` | 新增 `findFirstByMaterialName`、`maxNineLeadingMaterialNo`、advisory lock 取锁；`upsertByMaterialNo` 增加 `preserveDescriptive` 参数（A1，不翻转旧默认/不回归核价）。 |
| `basicdata/v6/repository/ProcessMasterRepository.java` | 新增 `findFirstByProcessName`。 |
| `basicdata/v6/quote/MaterialBomMergeHandler.java` | §3/§12 走解析器（先 resolve 再做 Map key）；§12 新增料号表同步(type=3) + 工序回填；两循环共用 `BatchState`；§3/§12 upsert 传 `preserveDescriptive=true`。 |
| `basicdata/v6/pricing/P05CustomerMapHandler.java` | **仅适配新签名**，显式传 `preserveDescriptive=false`，行为保持不变（零回归）。 |
| `basicdata/v6/quote/Q18UnitWeightHandler.java`、`basicdata/v6/pricing/P24UnitWeightHandler.java` | **仅适配新签名**（只传 unit_weight，两策略等价）。 |

§2（`Q02CustomerMapHandler`）业务逻辑**不改**（已符合决策 #8，且不调 `upsertByMaterialNo`）。

---

## 六、测试计划

后端单元/集成测试（`@QuarkusTest`，针对 `MaterialBomMergeHandler` / `MaterialNoResolver`）：

1. **§3 料号空+名称命中已有**：返回已有料号，BOM 子行 `component_no` = 已有料号，不生成新号。
2. **§3 料号空+名称未命中**：生成 `9000000000`（空表场景），料号表新增一条，BOM 子行指向新号。
3. **生成递增（表内已有号）**：已有 `9000000005` → 新生成 `9000000006`。
4. **同批同名去重**：同名 3 行料号空 → 只生成 1 个料号，3 行共用（决策 #3）。
5. **【A4】同批多行料号空、名称各不同、均需生成**：3 行不同名 → 生成 `9000000000/01/02` **互不重号**（验证 `batchMaxGenerated`，不依赖写入可见性）。
6. **同名多料号取第一条**：料号表两条同名 → 取 `material_no` 升序第一条（决策 #4）。
7. **料号+名称都空**：`recordError`，该行不落库（决策 #11）。
8. **【A3】§12 新料件 material_type=3**：纯组成件新料件落 `material_type=3`（**而非 `digitsOnly` 结果**）。
9. **§12 已存在保留原 type**：组成件料件已存在 → 保留原 `material_type`，不被改成 3（决策 #6）。
10. **【B1】§3/§12 交叉料件**：同料件先在 §3（数字 type）后在 §12（应写 3）出现 → 最终 `material_type` = §3 的数字类型（决策 #6 保留旧值）。
11. **§12 工序回填命中**：工序编号空+组装工序有值且 `process_master` 有匹配 → `operation_no` 填 `process_no`。
12. **§12 工序回填未命中**：查不到 → `operation_no` 留空，行照常导入（决策 #5）。
13. **upsert 不覆盖（报价 `preserveDescriptive=true`）**：已存在料号（有名称/类型）再导入不同名称/类型 → 旧值保留（决策 #9）。
14. **【C1 核价零回归】P05 `preserveDescriptive=false`**：核价料号已存在，P05 再导入新品名 → 品名**被覆盖**（保持现状行为，确认未被本次改动影响）。
15. **【C5 幂等】同一 Excel 连导两次**：第二次按名称命中第一次生成的 9 字头号，**不再新增**料号、号段不增长。
16. **纯空格边界**：料号/名称为纯空格 → 按 `isBlank` 视为空（C3）。
17. **§18 守卫回归**：料号空 → `recordError`（决策 #8，确认未被破坏）。

自检：后端 `touch` 触发 Quarkus 重启 → `/q/health` 200；相关 `@QuarkusTest` 全绿；如改动触达导入端点，跑一次真实小样本 Excel 导入验证料号表落库。完成后**回写 `docs/RECORD.md`**，并把 `docs/table/报价系统Excel导入落库方案.md` 中 `[20260615更新]` 处标注为已实现（C6）。

---

## 七、范围之外（YAGNI）

- 不新建独立序列表 / PG sequence（决策 #2 选自管理 MAX+1；跨导入并发用 advisory lock 而非 sequence）。
- 不新增 `is_auto_generated` 标识字段（决策 #10）。
- 不改 §2 写料号表（现状不写，保持）。
- 不改前端导入页面（纯后端落库规则）。
- 生成规则的"可配置化"仅做到"集中在一处方法便于改"，暂不做成数据库配置项/参数化。
- 不改核价 P05/单重的业务语义（仅适配 `upsertByMaterialNo` 新签名，行为保持）。

---

## 2026-06-17 推广续作

§3/§12 的料号匹配/生成规则已推广到全部「投入料号 / 组成件料号」键的报价页签，详见实现计划 `docs/superpowers/plans/2026-06-17-quote-import-materialno-autogen-extend.md`：
- 新建型（匹配/生成/登记 type=组成件）：§4 物料与元素BOM、§6 来料固定加工费、§7 来料其他费用、§8 来料年降、§9 来料回收折扣、§10 自制加工费、§13 组成件其他费用
- 更新型（仅匹配不生成）：§5 元素回收折扣
- 基础设施：`SheetRow.exact()` 精确表头读取 + `MaterialNoResolver.resolveMatchOnly()`。
