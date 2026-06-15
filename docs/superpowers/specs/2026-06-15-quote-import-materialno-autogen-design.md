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

---

## 四、设计

### 4.1 新建 `MaterialNoResolver`（料号解析器，规则单一归处）

位置：`cpq-backend/.../basicdata/v6/service/MaterialNoResolver.java`，`@ApplicationScoped`。

核心方法（建议签名）：

```java
/**
 * 解析最终落库料号。
 * @param materialNo   Excel 料号（可空）
 * @param materialName Excel 料件名称（可空）
 * @param batchCache   单次导入内的 name→已生成料号 缓存（调用方持有，事务级）
 * @param importedBy   导入人
 * @return 解析出的 material_no；当料号与名称都为空时抛 {@link MaterialNoUnresolvableException}
 */
String resolve(String materialNo, String materialName,
               Map<String,String> batchCache, UUID importedBy);
```

解析逻辑：

| 场景 | 行为 |
|------|------|
| 料号非空 | 直接返回（trim 后）。 |
| 料号空、名称非空 | 1) `batchCache` 命中 → 返回缓存料号（决策 #3）；2) 否则按 `material_name` 全局查料号表，同名多条取**第一条**（`ORDER BY material_no ASC`，决策 #4）→ 命中则写入 `batchCache` 后返回其料号；3) 未命中 → **生成新料号**（`MAX(9 字头)+1`），写入 `batchCache`，返回。 |
| 料号空、名称空 | 抛 `MaterialNoUnresolvableException`，调用方 `recordError` 跳过该行（决策 #8/#11）。 |

> **职责边界**：`MaterialNoResolver` 只负责"解析出料号号码"（匹配 / 缓存 / 生成 `MAX+1`），**不写 `material_master`**，因此不需要知道 `material_type`。料号表的 upsert（含 `material_name` + 正确的 `material_type`）一律由调用方（`MaterialBomMergeHandler`）在**行循环内、解析后立即**执行。这样下一行的 `MAX`/按名称查能读到本行刚写入的料号（同事务 read-your-writes），保证递增与同名去重正确；`batchCache` 作为显式兜底，避免依赖写入可见性时序。

**生成规则**（决策 #2、#10，封装在此服务私有方法 `generateNextMaterialNo()`）：

```sql
SELECT COALESCE(MAX(material_no::bigint), 8999999999) + 1
FROM material_master
WHERE material_no ~ '^9\d{9}$'
```

> 无 9 字头时 `MAX` 为 NULL，`COALESCE` 回退 `8999999999`，+1 = `9000000000`。
> 规则集中在该方法一处，将来改规则只改此处。

**并发安全**：调用方紧随解析做料号表 `upsert`（`ON CONFLICT DO UPDATE`，幂等），跨事务并发若撞同号由 `material_no` UNIQUE + upsert 语义自然收敛；`batchCache` 仅解决单事务内同名去重。

新增仓储方法：`MaterialMasterRepository.findFirstByMaterialName(String name)`（`ORDER BY material_no ASC LIMIT 1`）。

### 4.2 `MaterialBomMergeHandler` 改造

`merge()` 入口创建一个事务级 `Map<String,String> batchCache`，§3 与 §12 两循环共用（保证跨两 Sheet 同名也只生成一次）。

**§3 material 循环**：
- `投入料号` 为空但 `投入料号名称` 有值时，不再直接 `recordError`，改为 `resolver.resolve(投入料号, 投入料号名称, batchCache, importedBy)`。
- 解析出的料号同时：① 作为 BOM 子行 `component_no` 写入；② 作为料号表键 upsert（`material_name=投入料号名称`，`material_type=digitsOnly(产出料号类型)`）。
- 料号与名称都空 → `recordError` 跳过。

**§12 assembly 循环**（新增料号表同步 + 工序回填）：
- `resolver.resolve(组成件料号, 组成件名称, batchCache, importedBy)`，解析料号写回 BOM 子行 `component_no`。
- 料号表 upsert：`material_name=组成件名称`，`material_type` 固定 **3**（决策 #6）。
- 工序回填：读 `工序编号` 与 `组装工序`（工序名称列）。`工序编号` 空且 `组装工序` 有值 → `ProcessMasterRepository.findFirstByProcessName(组装工序)` → 命中填 `operation_no`，查不到留空（决策 #5）。
- 料号与名称都空 → `recordError` 跳过。

> `MaterialNoResolver` 与料号表 upsert 均在 `merge()` 的 `REQUIRES_NEW` 事务内执行（`resolver` 注入的 `MaterialMasterRepository` 不开新事务），保证递增/查名可读到本事务已写入的料号，且失败整体回滚一致。

新增仓储方法：`ProcessMasterRepository.findFirstByProcessName(String name)`。

### 4.3 `MaterialMasterRepository.upsertByMaterialNo` 覆盖方向翻转（决策 #9）

`ON CONFLICT (material_no) DO UPDATE SET` 中，仅将下列两列改为"保留旧值、旧值为空才回填"：

```sql
material_name = COALESCE(material_master.material_name, EXCLUDED.material_name),
material_type = COALESCE(material_master.material_type, EXCLUDED.material_type),
```

其余列（`specification`/`dimension`/`old_material_no`/`usage_property`/`unit_weight`/`standard_unit`）维持现有 `COALESCE(EXCLUDED.x, master.x)`（非空覆盖）不变。

> ⚠️ **行为变更**：§3 现有行为是后导入的名称/类型覆盖旧值，改后不再覆盖。符合决策 #9。

---

## 五、改动文件清单

| 文件 | 改动 |
|------|------|
| `basicdata/v6/service/MaterialNoResolver.java` | **新建** 解析器 + 生成规则 + 同批缓存 + 唯一冲突重试。 |
| `basicdata/v6/service/MaterialNoUnresolvableException.java` | **新建** 料号/名称都空的受检/运行时异常。 |
| `basicdata/v6/repository/MaterialMasterRepository.java` | 新增 `findFirstByMaterialName`；翻转 `upsertByMaterialNo` 的 `material_name`/`material_type` 覆盖方向。 |
| `basicdata/v6/repository/ProcessMasterRepository.java` | 新增 `findFirstByProcessName`。 |
| `basicdata/v6/quote/MaterialBomMergeHandler.java` | §3 走解析器；§12 新增料号表同步 + 工序回填；两循环共用 `batchCache`。 |

§2（`Q02CustomerMapHandler`）、§18（`Q18UnitWeightHandler`）**不改**（已符合决策 #8）。

---

## 六、测试计划

后端单元/集成测试（`@QuarkusTest`，针对 `MaterialBomMergeHandler` / `MaterialNoResolver`）：

1. **§3 料号空+名称命中已有**：返回已有料号，BOM 子行 `component_no` = 已有料号，不生成新号。
2. **§3 料号空+名称未命中**：生成 `9000000000`（空表场景），料号表新增一条，BOM 子行指向新号。
3. **生成递增**：已有 `9000000005` → 新生成 `9000000006`。
4. **同批同名去重**：同名 3 行料号空 → 只生成 1 个料号，3 行共用（决策 #3）。
5. **同名多料号取第一条**：料号表两条同名 → 取 `material_no` 升序第一条（决策 #4）。
6. **料号+名称都空**：`recordError`，该行不落库（决策 #11）。
7. **§12 料号表同步**：assembly 行落 `material_master`，`material_type=3`；已存在保留原 `material_type`（决策 #6）。
8. **§12 工序回填命中**：工序编号空+组装工序有值且 `process_master` 有匹配 → `operation_no` 填 `process_no`。
9. **§12 工序回填未命中**：查不到 → `operation_no` 留空，行照常导入（决策 #5）。
10. **upsert 不覆盖**：已存在料号（有名称/类型）再导入带不同名称/类型 → 旧值保留（决策 #9）。
11. **§18 守卫回归**：料号空 → `recordError`（决策 #8，确认未被破坏）。

自检：后端 `touch` 触发 Quarkus 重启 → `/q/health` 200；相关 `@QuarkusTest` 全绿；如改动触达导入端点，跑一次真实小样本 Excel 导入验证料号表落库。

---

## 七、范围之外（YAGNI）

- 不新建独立序列表 / PG sequence（决策 #2 选自管理 MAX+1）。
- 不新增 `is_auto_generated` 标识字段（决策 #10）。
- 不改 §2 写料号表（现状不写，保持）。
- 不改前端导入页面（纯后端落库规则）。
- 生成规则的"可配置化"仅做到"集中在一处方法便于改"，暂不做成数据库配置项/参数化。
