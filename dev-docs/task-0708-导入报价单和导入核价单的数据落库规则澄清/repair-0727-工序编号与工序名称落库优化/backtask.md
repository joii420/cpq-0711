# 后端任务书 —— 工序编号与工序名称落库优化

> 上游：`需求文档.md`（第四轮定稿，**必读 §4.0 两阶段架构 + §4.3 全部七条规则 + §13 纠正清单**）
> 下游：`test.md`（测试任务）
> **本次前端零改动、接口零变更、无 Flyway 迁移、无实体改动。**

---

## 0. 一句话目标

把报价 Excel「组装工序」列填的**工序名称**，在 **Phase 1** 解析成 `process_master` 里的**真工序编号**再落库；解析不了就整单拦下，不许把名称写进编号列。

---

## 1. 开工前必读（三条会导致返工的前提）

### 1.1 报价导入是两阶段架构，`recordError` ≡ 整单失败

| 阶段 | 实现 | 行为 |
|---|---|---|
| Phase 1 | `QuoteImportValidator.validate()` | 只读校验、**零写库**。任一 sheet `failedRows > 0` → 整单 `FAILED`，**不进 Phase 2** |
| Phase 2 | `QuoteImportService.writeAll()` | 单一事务，全 handler `MANDATORY` join。任一 `recordError` → 抛异常 → **整单回滚** |

代码依据：`QuoteImportService.java:123-133` / `:186-188` / `:233-239`。

> ❌ **不要**在 Q14/Q15 handler 里写「解析失败就 `recordError` 跳过该行、其他行照常」——那不是"跳过"，那是**整单回滚**。
> ✅ **业务校验一律落在 Phase 1**。Phase 2 handler 只负责取已解析好的结果落库。

### 1.2 `validate()` 末尾有个"兜底计数循环"，新增 validate 方法必须自己维护计数

`QuoteImportValidator.java:80-85`：

```java
for (Map.Entry<String, List<SheetRow>> e : sheetsByName.entrySet()) {
    if (out.bySheet.containsKey(e.getKey())) continue;   // ← 已被专项校验的 sheet 会跳过
    SheetImportResult r = result(out, e.getKey());
    r.totalRows = e.getValue().size();
    r.successRows = e.getValue().size();
}
```

一旦你为「组装加工费」新增专项 validate，该 sheet 就进了 `out.bySheet`，**不再走这个兜底赋值**。所以你的 validate 方法里必须自己 `r.totalRows++` / `r.successRows++`（照抄 `validateMaterialBom` 的写法），否则导入报告里该 sheet 的行数会变成 0。

### 1.3 Q14 与 Q15 的版本化语义**不同**，不能照抄

| | Q14 `capacity` | Q15 `unit_price` |
|---|---|---|
| `groupKey` | `(material_no, resource_group_no)` | `(system_type, customer_no, price_type, cost_type, code, finished_material_no, **operation_no**)` |
| 工序列位置 | `process_no` ∈ `CONTENT` + `VERSION_TRIGGER` | `operation_no` ∈ **groupKey** |
| 改值后果 | 正常升版，老版本 `is_current` 切走 ✅ | **建全新组，老组 `is_current` 不切走 → 双 current** ❌ |

`COMPONENT_REDUCTION` 当前 0 行，所以本次改动不会立刻产生双 current；但**必须在测试中断言**（见 §5 AC-6b）。

---

## 2. 任务分解

### T1 新增 `ProcessNoResolver`

**文件**：`cpq-backend/src/main/java/com/cpq/basicdata/v6/service/ProcessNoResolver.java`（与 `MaterialNoResolver` 同包）

**职责**：把 Excel「组装工序」列的原始值解析为 `(process_no, process_name)`。

**实现要点**：

1. `@ApplicationScoped`，`@Inject ProcessMasterRepository repo`（已存在，Panache `PanacheRepositoryBase<ProcessMaster, UUID>`）。

2. **全表索引，一次导入只查一次库**（AC-11 性能要求，禁止逐行查库）：

   ```java
   public static final class Index {
       final Map<String, ProcessMaster> byNo   = new HashMap<>();          // process_no → row
       final Map<String, List<ProcessMaster>> byName = new HashMap<>();    // process_name → rows(已按 process_no 升序)
   }
   public Index buildIndex() { /* repo.listAll() 一次性载入，key 全部 strip() */ }
   ```

   `byName` 的每个 value 列表**必须按 `processNo` 升序排好**（`Comparator.comparing(p -> p.processNo)`），保证"取第一条"的结果可复现、不依赖 DB 返回顺序。

3. **两段匹配**（顺序不能颠倒）：

   | # | 规则 | 结果 |
   |---|---|---|
   | 1 | 原始值 `strip()` 后查 `byNo` | 命中 → 返回该行 `(processNo, processName)` |
   | 2 | 再查 `byName` | 唯一命中 → 返回该行 `(processNo, processName)` |
   | 3 | `byName` 命中**多条** | 取**列表第一条**（= `process_no` 升序最小），并 `Log.warn` 记录：被选中的编号 + **全部候选编号** + 原始值 |
   | 4 | 两段都不命中 | 返回失败，携带可直接展示的原因文本 |

4. **返回类型**：建议 `record Resolved(String processNo, String processName, String failReason)`，或 `Optional<Resolved>` + 分离的失败原因。**失败原因文本固定为**：

   ```
   工序「<原始值>」未在工序主数据中登记，请先在 主数据维护 → 工序 中录入或导入
   ```

5. **匹配前统一 `strip()`；全半角、大小写差异不做归一**（R3：避免误匹配，由错误文案引导业务对齐）。

6. `null` / 空白输入直接返回失败（复用既有必填校验的语义，不要 NPE）。

**不要做的事**：
- ❌ 不要复用 `ProcessMasterRepository.findFirstByProcessName()` —— 它的排序不受本任务控制，且是逐行查库，违反 AC-11。
- ❌ 不要在 resolver 里访问 `ImportContext` / `SheetImportResult`（保持纯函数式，便于单测）。

---

### T2 `QuoteImportValidator` 新增两个 Phase 1 校验（本次核心落点）

**文件**：`cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportValidator.java`

**照抄范式**：`validateSelfProcessFee()`（`:130-150`）—— 它已经是"Phase 1 校验 + 把结果收进 `Outcome` → Phase 2 经 `sharedCache` 消费"的完整链路。

#### T2.1 `Outcome` 新增收集容器

```java
public static final class Outcome {
    ...
    /** repair-0727：组装工序解析结果。key=(sheetName, 销售料号, Excel原始工序值) → (process_no, process_name) */
    public final Map<List<String>, ProcessNoResolver.Resolved> assemblyProcessNo = new LinkedHashMap<>();
}
```

> key 的设计要点：**必须能让 Phase 2 的 Q14 / Q15 用各自手上的字段精确取回**。Q14 手上有 `(销售料号, 组装工序原始值)`，Q15 手上有 `(code, 组装工序原始值)`（其 `code` 与 `finished_material_no` 同源）。若两 sheet 用同一 key 空间会撞键，故 key 第一段放 sheetName 区分；具体形状实现者可调整，但**必须在 T3/T4 两侧保持一致**，并在类注释里写明契约。

#### T2.2 `validate()` 主流程挂载

在 `:76` `validateCustomerMap(...)` 之后追加两行：

```java
validateAssemblyProcess(sheetsByName.getOrDefault("组装加工费", List.of()), out);
validateAssemblyAnnualDiscount(sheetsByName.getOrDefault("组装加工费年降", List.of()), out);
```

**索引只建一次**：在 `validate()` 开头（或首次需要时）调一次 `processNoResolver.buildIndex()`，两个 validate 方法共用同一个 `Index` 实例。

#### T2.3 `validateAssemblyProcess`（组装加工费）

```java
private void validateAssemblyProcess(List<SheetRow> rows, ProcessNoResolver.Index idx, Outcome out) {
    SheetImportResult r = result(out, "组装加工费");
    // 1) 先按料号分组（规则三：错误粒度 = 料号）
    // 2) 逐行解析，失败的按料号聚合
    // 3) 某料号只要有任一行失败 → 该料号整体记一条 recordError
    // 4) 全部成功的行 → 写入 out.assemblyProcessNo
}
```

**必须遵守**：

- **自己维护 `r.totalRows++` / `r.successRows++`**（见 §1.2）。
- **既有必填校验保留**：`销售料号` 为空 / `组装工序` 为空 → 照旧 `recordError`（与 Q14 handler `:54-57` 现有语义一致，只是提前到 Phase 1）。
- **错误按料号聚合**（规则三 N1）：同一销售料号下有 N 道工序解析失败，**只报一条**，文案形如：

  ```
  销售料号「10110002」的组装工序「点胶」未在工序主数据中登记，请先在 主数据维护 → 工序 中录入或导入
  ```

  若该料号有多道都失败，文案列出全部失败的工序名（顿号分隔）。`recordError` 的 `rowNo` 取该料号第一条失败行的行号，`column` 取 `"组装工序"`。
- **不变量**：绝不允许出现「该料号部分工序落库、部分被丢弃」。由于 Phase 1 拦截即整单失败，此不变量天然成立 —— **但不要因此在 Phase 2 再补一层"跳过坏行"的逻辑**，那会破坏它。

#### T2.4 `validateAssemblyAnnualDiscount`（组装加工费年降）

同 T2.3，差异：

- sheet 名 `组装加工费年降`，料号列 `销售料号`/`宏丰料号`（见 `Q15:48`）。
- `组装工序` 列**允许为空**吗？—— 看 `Q15:51` 现状是 `row.getStr("组装工序")` 可能返回 `null` 且不校验，`operation_no` 允许 NULL。**保持现状：为空则跳过解析、不记错、不进 `assemblyProcessNo`**，Phase 2 落 NULL。只有"填了但解析不了"才报错。
- 结果同样收进 `out.assemblyProcessNo`（key 第一段用 `"组装加工费年降"` 区分）。

---

### T3 `QuoteImportService` 把解析结果放进 `sharedCache`

**文件**：`cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/QuoteImportService.java`

在 `:134-144` 现有的 `sharedCache.put` 群组里追加一行（与 `partTypeIndex` / `selfProcessOperationNo` / `materialNoBatchState` 同处）：

```java
ctx.sharedCache.put("assemblyProcessNo", vo.assemblyProcessNo);
```

> 位置必须在 `if (vo.hasErrors()) { ... return; }`（`:123-133`）**之后** —— 有错误时根本不该进 Phase 2。

---

### T4 `Q14AssemblyProcessFeeHandler` 改造

**文件**：`cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q14AssemblyProcessFeeHandler.java`

| 改动 | 原 | 新 |
|---|---|---|
| `CONTENT`（`:36-38`） | `process_no, seq_no, production_type, fixed_cost, currency, capacity_unit, default_defect_rate, is_effective` | **加 `process_name`** |
| `VERSION_TRIGGER`（`:41`） | `("process_no", "seq_no")` | **不变**（`process_name` 是内容列，改名走原地更新不升版） |
| 取值（`:53`, `:68`） | `row.getStr("组装工序","工序编号")` 原样写 `process_no` | 从 `ctx.sharedCache.get("assemblyProcessNo")` 取 Phase 1 已解析的 `Resolved`，写 `process_no=resolved.processNo()`、`process_name=resolved.processName()` |

**关键约束**：

- **Phase 2 不再解析、不再查库**。若 `sharedCache` 里取不到对应 key（理论上不可能，Phase 1 已全量拦截），按现有兜底纪律 `recordError`（会触发整单回滚，符合 `:234` 注释「Phase 2 出现的任何 recordError 级问题都必须整体回滚」）。
- **单测友好**：`MaterialNoResolver:51-55` 有个现成做法 —— 单测直调 handler 时 `sharedCache` 无此 key，会自建一个空的兜底。**本次不要照抄这个兜底**（那会让"取不到就静默降级"，掩盖 bug）；单测请显式往 `ctx.sharedCache` 预置解析结果。
- `setBased` 两条分支（`:79-101`）都要覆盖，别只改一条。

---

### T5 `Q15AssemblyAnnualDiscountHandler` 改造

**文件**：`cpq-backend/src/main/java/com/cpq/basicdata/v6/quote/Q15AssemblyAnnualDiscountHandler.java`

- `:51` `String operationNo = row.getStr("组装工序");` → 改为从 `sharedCache` 取 Phase 1 解析结果的 `processNo`。
- 原始值为空时保持 `operationNo = null`（见 T2.4）。
- **不写名称**（`unit_price` 无工序名称列，需求文档规则四）。
- `CONTENT`（`:37-38`）**不变**。
- groupKey（`:52`/`:61`）**结构不变**，只是 `operationNo` 的值从中文名变成真编号 —— 这正是 §1.3 双 current 风险的来源，实现上不需要额外处理（当前 0 行），但 **PR 描述里必须写明这条时序约束**。

---

### T6 `zz_view` 配置更新

**对象**：`component_sql_view` 表中 `sql_view_name = 'zz_view'` 的记录。

**⚠️ 动手前先重查条数**（配置表可能被其他会话变更）：

```sql
SELECT id, component_id, sql_view_name FROM component_sql_view WHERE sql_view_name='zz_view';
```

技术总监 2026-07-27 实测为 **1 条**（`id=8ca940dd-…`，`component_id=f170b0a8-…`）。**查出几条就改几条，一条都不能漏**（历史教训：`$view` 缓存 key 含 componentId，同名视图跨组件会串号）。

**改动**：

```sql
-- 原
COALESCE(NULLIF(c.process_name, ''), c.process_no) AS _工序
-- 新
COALESCE(pm.process_name, NULLIF(c.process_name, ''), c.process_no) AS _工序
-- 并在 FROM 子句追加
LEFT JOIN process_master pm ON pm.process_no = c.process_no
```

口径与 `jg_view` 统一：**主数据优先 → 库内冗余名次之 → 编号兜底**。

> 改完须验证：`zz_view` 属配置表内容（非 Flyway 视图），但仍受 `ImplicitJoinRewriter.tableColumnsCache` 等进程级缓存影响 —— 改完 **touch 一个 java 文件强制 Quarkus 重启**，再验渲染。

---

### T7 文档纠正

**文件**：`docs/table/报价系统Excel导入落库方案.md` §14 / §15

- §14「组装工序 → `process_no` ✅ 工序编号（取工序编号对应值）」：补充说明**解析规则**（两段匹配、来源 `process_master`、Phase 1 拦截）与 `process_name` 的新增落库。
- §15：同步补 Q15 的 `operation_no` 解析规则。

---

## 3. 单元测试要求

| 测试类 | 用例 |
|---|---|
| `ProcessNoResolverTest` | ① 按编号命中；② 按名称唯一命中；③ 同名多条 → 返回 `process_no` 升序第一条（如 `Z100`/`Z205` 返 `Z100`）；④ 未登记 → 返回失败且原因文本含「未在工序主数据中登记」 |
| `QuoteImportValidatorTest` | ① 组装加工费全部可解析 → `hasErrors()=false` 且 `assemblyProcessNo` 填充正确；② 某料号一道工序未登记 → `recordError` **按料号聚合只报一条**、`hasErrors()=true`；③ 「组装加工费年降」工序列为空 → 不报错、不进 map |
| `Q14AssemblyProcessFeeHandlerTest` | ① 预置 `sharedCache` → 落 `process_no=真编号` + `process_name=规范名`；② `CONTENT` 含 `process_name` 且 `VERSION_TRIGGER` **不含** |
| `Q15AssemblyAnnualDiscountHandlerTest` | ① 预置 `sharedCache` → `operation_no` 落真编号 |

**测试纪律**：`sharedCache` 一律**显式预置**，禁止依赖静默兜底（见 T4）。

---

## 4. 自检清单（提交前逐项打勾，缺一不可）

- [ ] `cd cpq-backend && ./mvnw -q compile` 通过
- [ ] `./mvnw test -Dtest='ProcessNoResolverTest,QuoteImportValidatorTest,Q14*Test,Q15*Test'` 全绿
- [ ] `./mvnw test -Dtest='*Quote*,*Capacity*,*Process*'` 全绿（AC-9 回归）
- [ ] `touch` 一个 java 文件强制 Quarkus dev 重启 → 等 5-7 秒
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` → **401**（应用在跑、鉴权正常；`/q/health` 返 404 不是健康探针）
- [ ] `zz_view` 改动条数 = 改动前 `SELECT count(*)` 查出的条数
- [ ] Phase 1 新增校验**未引入逐行查库**（`process_master` 全表只载入一次）—— 用日志或断点确认
- [ ] PR 描述写明：**Q15 双 current 时序约束**（必须在 `COMPONENT_REDUCTION` 产生数据前上线）

**提交时必须附一行"已自检"声明**，例如：
> 「`mvnw compile` ✅；`ProcessNoResolverTest` 4/4 ✅；`*Quote*,*Capacity*,*Process*` 全绿 ✅；后端 `/api/cpq/components` → 401 ✅；`zz_view` 1 条已更新 ✅」

没有这行声明的"完成" = 未完成。

---

## 5. 验收对照（供自测，完整版见 `需求文档.md` §8 与 `test.md`）

| # | 断言 |
|---|---|
| AC-1 | 主数据含 `Z100=焊接` → 导入后 `capacity.process_no='Z100'`、`process_name='焊接'`，状态 `SUCCESS` |
| AC-2 | Excel 改填 `Z100` → 同样落 `Z100` + `焊接`（第一段命中） |
| AC-3 | 不导工序主数据 → **整单 `FAILED`**，错误文案含工序名与「请先在 主数据维护 → 工序 中录入或导入」，**所有表零变动** |
| AC-4 | 造 `Z100=焊接`、`Z205=焊接` → 导入**成功**，落 `Z100`，后端日志有 `WARN` 列出两个候选 |
| AC-5 | AC-3 场景下 `material_bom_item` / `unit_price` / `capacity` 等各表计数导入前后**逐字节一致** |
| AC-6 | Q15 `operation_no` 落真编号 |
| AC-6b | 导入后 `COMPONENT_REDUCTION` 无双 current（同 groupKey 的 `is_current` 行数 ≤ 1） |
| AC-7 | 报价单组装页签「工序」列显示 `焊接`（不是 `Z100`） |
| AC-8 | 首次重导 `capacity` 升一版且老版本 `is_current=false`；**再导一次版本号不再增长** |
| AC-9 | `*Quote*,*Capacity*,*Process*` 测试全绿；核价侧 P08 的 `PRICING_DEFAULT` 行 `process_no` 仍为 `Z008` 等 |
| AC-11 | 导入端到端耗时较改动前增幅 **< 5%** |

---

## 6. 明确不要做的事

- ❌ 不改任何 DDL（`capacity.process_no` 的 NOT NULL、`uq_capacity`、实体注解都不动）
- ❌ 不写 Flyway 迁移
- ❌ 不迁移库内 20 行 `QUOTE_ASSEMBLY` 脏数据（重导覆盖）
- ❌ 不新增 `warnings` 通道 / 不改 `SheetImportResult` / `SheetResultDTO` / 不动任何接口 / 不动前端
- ❌ 不改两阶段导入架构、不引入"部分成功"语义
- ❌ 不动自制加工费 Q10、不动核价侧 P08、不动 Q13
- ❌ 不做 BL-0042（Q14 触发列拉平）
