# 后端任务 —— repair-0803 公式 SUM 内引用宿主页签字段

- 关联：`需求文档.md`（FR-1~FR-12 / AC-1~AC-17）、`api.md`
- 分支：`fix/repair-0803-sum-host-field`（待建，从 `master` HEAD）

---

## 0. 数据模型变更

**无**。不涉及表 / 视图 / 字段变更，**无 Flyway 迁移**。

---

## 1. 任务清单

### B1. `b_field` 取值链补回退（FR-1 / FR-2）

- 文件：`quotation/service/FormulaCalculator.java`，`case "b_field"`（`:195-200`）
- 改为：

```java
case "b_field": {
    String n = token.has("value") ? token.path("value").asText() : token.path("name").asText();
    Object raw = ctx.currentRowRaw.get(n);
    // 键存在（含显式置空 ""）→ 尊重原始行，不回落
    Double v = (raw != null) ? toNumber(raw) : ctx.fieldValues.get(n);
    expr.append(numStr(v != null ? v : 0.0));
    break;
}
```

- **判空口径**：`currentRowRaw` 中**键存在但为空串**视为命中，不回落（与 `fillInputDefaultSourceByFieldName:1866`「仅键缺失才补」对称）
- ⚠️ **不得**把 FORMULA 结果写回 `currentRowRaw`（决策 D-2：会污染内层 KSUM 的 match 键 + 绕过 `:871` 的单位换算）

- [ ] B1 完成

### B2. 依赖收集递归 targetExpr（FR-3）

- 文件：同上，`addExprFieldDeps`（`:1567-1575`）
- 在原「顶层 `type=="field"`」基础上递归进 `cross_tab_ref.targetExpr`，识别 `b_field` + `field`
- **递归终止**：遇 `projectToHostKey == true` 的子 token **停止下探**（KSUM 内层白名单本就拒 `b_field`，见需求文档 §5.2.4）
- 自引用不建边；去重仍走 `dedupeEdges`（**不去重会误报环**，`FormulaCycleDetectionTest` 已锁）
- ✅ 结构性前提：`topoOrder:2253`、`cyclicFormulaNodes:1634`、`describeFormulaCycles:2152` **共用** `buildFormulaDeps` → 改这一处三处同时生效。**不得**为它们各写一份收集逻辑

- [ ] B2 完成

### B3. 环检测结构化输出（FR-8 / FR-9）

- 文件：同上，`describeFormulaCycles`（`:2148`）+ `renderCycle`（`:2173`）
- 新增结构化返回（保留现有字符串版本供既有调用方，不破坏）：
  - 复用现有 Tarjan `stronglyConnected` + `cyclePathIn`，**不重写找环算法**
  - 每个环产出 `nodes[]` + `edges[]`，`viaDesc` 直接取现有 `DepEdge.via`
- 组件名称由调用方注入（`FormulaCalculator` 不知道组件名）

- [ ] B3 完成

### B4. 新增 `FormulaCycleException`（FR-8）

- 新建：`common/exception/FormulaCycleException.java`
- **照抄** `RowKeyConflictException` 形态（构造 message + 结构化 payload，`getCycles()`）
- 放 `common/` 而非 `component/`：component 与 template 两侧共用
- ⚠️ **`BusinessException` 基类不动**

- [ ] B4 完成

### B5. `GlobalExceptionMapper` 加分支（FR-8）

- 文件：`common/exception/GlobalExceptionMapper.java`（`:24-34` 已有两例）
- 加：

```java
if (e instanceof FormulaCycleException fce) {
    return Response.status(e.getCode())
            .entity(ApiResponse.error(e.getCode(), e.getMessage(),
                    Map.of("errorType", "FORMULA_CYCLE", "cycles", fce.getCycles())))
            .build();
}
```

- ⚠️ **该文件当前有并发会话 WIP**，进场前先确认状态

- [ ] B5 完成

### B6. 组件保存改抛结构化异常 + 删除重复校验（FR-10 / D-9）

- 文件：`component/service/ComponentService.java`
- 改 `:1032-1048`：`describeFormulaCycles` 结构化结果 → `FormulaCycleException`（注入组件名称）
- **删除** `:1197` 的 `dfsCycleDetect` 及其调用（零定位信息，能力被完全覆盖）
- 保留 `:1045-1048` 的 `cyclicFormulaNodes` 兜底（描述器提不出路径时仍须拦截）
- ⚠️ **该文件当前有并发会话 WIP**

- [ ] B6 完成

### B7. 模板发布页签级环结构化（FR-11）

- 文件：`template/service/TemplateService.java` `validateCrossTabRefs`（`:956`）
- 现状只查 `cross_tab_ref` 且只报缺失/成环文本 → 改为产出 `scope=TAB` 的结构化环并抛 `FormulaCycleException`
- 节点为**组件名称**（`namesById` 参数已有，直接用）

- [ ] B7 完成

### B8. 渲染期文案去 UUID（FR-12）

- 文件：`quotation/service/CrossTabComponentOrder.java` `topoOrder:41`
- 现状：`"页签公式存在循环引用: " + cyc`（cyc 是 componentId 集合）
- 改为按链路顺序输出组件名称：`页签公式存在循环引用: 产品 → 物料 → 产品`
- 需调用方传入 `Map<String,String> cidToName`：
  - `CardSnapshotService`（`:1636-1663` 附近，tab 节点有 `componentCode` / `tabName`）⚠️ **该文件有并发会话 WIP**
  - `ConfigureSnapshotService`（`:1170` 附近）
- **签名零破坏**：新增带映射的重载，旧签名 delegate 传空映射 → 回落原 id 文案

- [ ] B8 完成

### ~~B9. 存量 8 条公式改写~~ —— **已移出本期范围**（2026-08-04 用户裁决 D-4）

- 见需求文档 §5.4 清单（COMP-0157 ×3 公式、COMP-0032 ×1 公式，各引用 2 个 FORMULA 字段）
- **逐条与业务确认意图**后决定：保持（每行一次）或提到 SUM 外（整单一次）
- 改写走**配置**（组件公式），不写 Flyway
- 改前/改后值逐条记录进 `test-report.md`

- [x] B9 取消（引擎按公式忠实计算即可，配置改写归业务侧）

---

## 2. 事务 / 并发 / 性能

| 项 | 结论 |
|---|---|
| 事务边界 | 无变化（纯计算 + 校验，不新增写操作） |
| 幂等 | 无写操作，天然幂等 |
| 并发 | `FormulaCalculator` 每行新建 `RowContext`，无共享可变状态；**不得**并行化求值层（历史实证竞态，见记忆 `cpq-expand-layer-not-threadsafe`） |
| 性能 | 依赖收集递归深度 = targetExpr 嵌套层数（现网 ≤1），节点规模为单组件公式字段数（≤ 数十），影响可忽略 |

---

## 3. 自检项

- [ ] `./mvnw -o -q compile` 0 错误
- [ ] `./mvnw -o test -Dtest='FormulaCalculator*Test,CrossTabComponentOrder*Test,ComponentService*Test,TemplateCrossTab*Test'` 全绿
- [ ] 全量 `./mvnw -o test`，失败集与干净基线 **A/B 逐条一致**（基线已知 6 项：`QuotePendingScopeOpenWhitelistTest` ×1 / `SessionLifecycleTest` ×1 / `DataLoaderTest` ×4，见 BL-0094/0095）
- [ ] **AC-6 跑批**：改前/改后两版引擎对全库 87 组件同批输入求值，差异清单**恰好等于 §5.4 那 8 条**
- [ ] 无 Flyway 迁移 → 不需要验 `flyway_schema_history`
- [ ] 端点自检：`curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` 期望 401
- [ ] worktree 起临时 Quarkus 验证时加 `-Dquarkus.flyway.validate-on-migrate=false -Dquarkus.flyway.migrate-at-start=false`（V368+ 未提交迁移，**禁止跑 `flyway repair`**）

---

## 4. 已知坑位

| 坑 | 规避 |
|---|---|
| javadoc 里 `INPUT_*/xxx` 的 `*/` 会提前闭合注释块 | 中文注释列举类型时不用 `*/` 相邻写法（repair-0803 实际踩过） |
| 依赖边不去重 → 入度归不了零 → 误报环 | 保留 `dedupeEdges`（COMP-0112 事故） |
| 4 个待改文件有并发会话 WIP | 进场前确认归属；合并沿用 patch 避让手法 |
| 主仓 `target/` 残留旧 class 致 CDI 解析失败 | 删除 `dfsCycleDetect` 后在主仓重新编译并跑测试，别只信 worktree 绿 |
