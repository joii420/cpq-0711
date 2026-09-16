# 后端任务分解 · task-260915 组件导出导入往返保真

> 🚫 **只按本文件做。** AC 原文在 `需求文档.md`，本文件只标 AC 编号不复制原文。
> 🚫 遇 `CLAUDE.md §3.2` 不可逆操作红线（DROP / TRUNCATE / 无 WHERE 的 DELETE·UPDATE / 清库 / 改已应用迁移）**停下报主线**，你没有批准权。
> ⚠️ **本任务无 DB 迁移**：8 个字段在表中均已存在，只是导出/导入没带上。**不要写迁移文件。**

---

## B-1 · 导出契约 DTO 补齐字段

**服务的 AC**：AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-9

`cpq-backend/src/main/java/com/cpq/component/dto/ComponentExportBundle.java`

1. `Item` 增加 5 个字段：
   - `public JsonNode treeConfig;`（对应 `component.tree_config`）
   - `public Boolean bomRecursiveExpand;`（对应 `component.bom_recursive_expand`）
   - `public String elementCodeField;`
   - `public String elementPriceField;`
   - `public String elementCurrencyField;`
2. `SqlView` 增加 3 个字段：
   - `public JsonNode builderConfig;`（对应 `component_sql_view.builder_config`，JSONB）
   - `public Integer builderVersion;`
   - `public String status;`
3. `bundleVersion` 默认值 `"1.0"` → `"1.1"`
4. 每个新字段**必须写 javadoc**，说明：对应哪一列、丢失后的可观测后果、老 bundle 无此字段时为 null。照既有 `tabType` / `sortField` 字段的注释风格写。

⚠️ 字段命名用 camelCase，与既有字段一致（Jackson 默认序列化，不加 `@JsonProperty`）。

---

## B-2 · 导出端补齐赋值

**服务的 AC**：AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-9

`cpq-backend/src/main/java/com/cpq/component/service/ComponentExportService.java`

在既有 `item.xxx = c.xxx` 序列后补齐 B-1 新增的 5 个组件级字段；在 `sv.xxx = v.xxx` 序列后补齐 3 个视图级字段。

- `treeConfig` / `builderConfig` 是 JSONB，用既有的 `readJson(...)` 转 `JsonNode`，**与 `rowKeyFields` 同样处理空值**：源为空/空串则保持 `null`，不要落成空对象
- `builderVersion` 直接赋 `Integer`（可为 null）
- `status` 直接赋 `v.status`

⚠️ **不要改 `computeChecksum`**。已实查：它对 `bundle.components` 的实际对象序列化，新字段自动纳入计算，无需特殊处理。

---

## B-3 · 导入端补齐恢复

**服务的 AC**：AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-18

`cpq-backend/src/main/java/com/cpq/component/service/ComponentImportService.java`

1. 组件 INSERT 段（现约 `:344-360`）补齐 5 个字段的赋值
2. 视图 INSERT 段（现约 `:391-401`）补齐 3 个字段：
   - `v.builderConfig = nodeToJson(sv.builderConfig);`
   - `v.builderVersion = sv.builderVersion;`
   - `v.status = sv.status == null ? "ACTIVE" : sv.status;` ← **老包无此字段时才默认 ACTIVE**，不能无条件硬编码
3. `bomRecursiveExpand` 的赋值逻辑改写：
   - **优先**用包里带来的 `it.bomRecursiveExpand`（1.1 包）
   - 包里为 null（1.0 老包）时，回落现行的 `if (isLegacyTreeTabType(it.tabType))` 推导
   - ⚠️ 保留回落分支，**不要直接删掉** —— 老包靠它

⚠️ **RENAME 策略下只改 `code`**，8 个新字段一律照搬（AC-18）。

---

## B-4 · 导入校验判据与配置期对齐

**服务的 AC**：AC-8, AC-13

`ComponentImportService` 现约 `:477`：

```java
componentService.assertTreeTokenGates(c.tabType, c.formulas, c.fields);   // 单判据，只认 tab_type=='BOM'
```

改为与组件新建/更新同口径的**双判据**入口 `assertTreeTokenGatesFor`（`ComponentService:326`，`ComponentService:793`/`:914` 已在用）。

**N+1 硬指标（`CLAUDE.md §2.2` 后端行）**：🚫 不许在导入循环里逐个组件查库。

用现成的 `TabSemanticResolver.isTreeTabBatch(Map<UUID,String>)`（自带承诺：固定 ≤2 条 SQL，与入参个数无关）：

1. 在进入第三遍校验循环**之前**，一次性收集本批所有 `componentId → tabType`
2. 调 `isTreeTabBatch` 拿回 `Map<UUID, Boolean>`
3. 循环里按 id 取用，逐个调三道闸的判定体

⚠️ **必须先验证的前提**：第一遍循环 `persist()` 的 `ComponentSqlView`，在第三遍 `isTreeTabBatch` 内部的 Panache 查询里**查得到**（同事务 Hibernate auto-flush）。
🚦 **如果实测查不到**，停下报主线 —— 那意味着要显式 `flush()` 或调整循环结构，属于方案调整，不要自己改设计。**请在回报里附上实测证据（查到/查不到的实际输出），不要只说结论。**

⚠️ 保留 `assertTreeTokenGates(String, String, String)` 这个单判据重载，纯单测仍在用，**不要删**。

---

## B-5 · 包版本兼容与报错文案

**服务的 AC**：AC-9, AC-10, AC-11

1. 导入端识别 `bundleVersion`：`"1.1"` 走完整恢复；`"1.0"` 或缺失走降级（新字段全 null，行为与改动前一致）
2. 🚫 **老包不得整包拒绝**（AC-10）：不含树 token 的组件必须照常导入成功
3. 树闸门报错文案改写（`ComponentService` 现约 `:380-383` 与 `:408-412` 两处）：
   - 当**判定为非树页签** 且 **该组件有 builder 视图但 `builder_config` 为空** 且 **包版本 < 1.1** 时，文案要同时给出两层信息：
     - 这个组件是取数配置器建的树页签，导入包是旧格式（`bundleVersion 1.0`）不含配置器信息
     - 怎么办：在源库升级到含本次修复的版本后重新导出
   - 其余情况保留现有文案，**不要改动**（组件新建/更新路径共用这两处，改错会波及 AC 之外的行为）

⚠️ 文案判定需要"包版本"这个上下文，而 `assertTreeTokenGates` 目前拿不到。**允许加参数传入，但不要改变既有调用点的行为**；如果传参会污染 `ComponentService` 的职责边界，停下报主线讨论落点。

---

## B-6 · 导入预览增加配置器坐标可解析性

**服务的 AC**：AC-12, AC-17

`ImportPreviewResult` 的每个 `ComponentPlan` 增加一项，与既有 `formulaBinding` **并列**：

- 字段名：`builderCoord`
- 取值三选一：
  - `NOT_BUILDER` —— 该组件没有 builder 视图（`builderConfig` 为 null）
  - `RESOLVED` —— `builder_config` 的三段坐标（`tabType` / `variantKey` / `dialect`）在目标库 `semantic_tab_view` 中能解析到
  - `UNRESOLVABLE` —— 解析不到，附一句人话原因（缺哪个坐标）
- 🚫 **`UNRESOLVABLE` 不得进 `blockers`、不得让 `canCommit` 变 false**（AC-17：如实报出但不阻断）

复用 `TabSemanticResolver` 既有的坐标解析实现，🚫 **不要另写一份坐标解析** —— 那是 `TabSemanticResolver` javadoc 明确警告过的漂移点（"各写一份必然漂移"）。若现有实现是 private，抽成可复用方法而不是复制。

⚠️ 预览是**只读**的，🚫 绝对不许写库。

---

## B-7 · 反射契约测试（防复发机制，本任务的核心交付物）

**服务的 AC**：AC-7

新增测试（建议 `cpq-backend/src/test/java/com/cpq/component/dto/ExportBundleFieldCoverageTest.java`），纯反射不打 DB：

1. 反射取 `Component` 实体的持久化字段集（排除 `static` / `transient` / Panache 基类字段）
2. 与 `ComponentExportBundle.Item` 的字段集比对
3. `ComponentSqlView` 与 `ComponentExportBundle.SqlView` 同理
4. **白名单**写死在测试里，每条**必须带一行注释说明为什么不导**：
   - `component`：`id` / `directoryId` / `code`（导入端按冲突策略重新分配）/ `createdAt` / `updatedAt`
   - `componentSqlView`：`id` / `componentId` / `createdBy` / `createdAt` / `updatedAt`
5. 失败信息必须**点名具体字段**，并提示"带上它，或加入白名单并注明理由"

🚫 **不许用"字段数量相等"做断言** —— 加一列同时删一列就骗过去了。必须是**集合差集为空**。

⚠️ 实体字段与 DTO 字段命名可能不同（如实体 `tabType` ↔ DTO `tabType` 一致，但要确认全部 8 个新字段都对得上）。若存在命名不一致的历史字段，用显式映射表，**映射表也要带注释**。

---

## B-8 · 后端强制自检

**服务的 AC**：全部

按 `docs/rules/backend.md` 跑完自检并在回报里逐条附**命令与其输出**：

- `./mvnw -q compile` 通过
- 新增/改动的测试全绿（在 **worktree 的 `cpq-backend/`** 下跑，不要 cd 主仓 —— 见 `CLAUDE.md` 记载的踩坑）
- 导出/导入接口手工 curl 一次，附真实响应片段
- 🚫 **不许只写「已自检 ✅」** —— 没有命令输出的自检声明视为未自检

---

## 回报要求

1. 每个 `B-x` 逐项回报：改了哪些文件的哪些行、实测结果
2. **B-4 的前提验证结果必须单独说明**（auto-flush 到底成不成立，附实际输出）
3. 途中发现 AC 与实际数据对不上、契约有歧义、或需要扩范围 → **停下报主线，不要自行决定**
4. 🚫 不要提交 git，不要合并分支 —— 由主线统一处理
