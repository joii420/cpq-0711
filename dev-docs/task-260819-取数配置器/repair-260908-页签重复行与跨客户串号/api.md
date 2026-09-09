# api.md · 接口契约

> 立项文档：`./问题说明.md`

---

## 结论：**无新增接口；一个既有接口做向后兼容的加法式扩展**

---

## 1. 变更清单

| 端点 | 变更类型 | 说明 |
|---|---|---|
| `POST /api/cpq/config-center/refresh-all-snapshots` | **加法式扩展**（新增一个**可选** body 字段） | 见 §2 |

**没有任何端点的方法、路径、既有参数、响应结构、错误码发生变化。**

---

## 2. `POST /api/cpq/config-center/refresh-all-snapshots`

**用途**：强制重新对齐已发布模板快照到当前活组件配置。**明确破坏不可变性，仅供运维紧急通道使用。**
（该端点与该定位为既有，非本次引入 —— 见 `ConfigCenterResource:112-126` 原注释）

**鉴权**：`@RoleAllowed({"SYSTEM_ADMIN"})`（不变）

### Request Body

| 字段 | 类型 | 必填 | 缺省 | 说明 |
|---|---|---|---|---|
| `templateIds` | `string[]`（UUID） | 否 | 不传 / 空 = 全部 `PUBLISHED` + `ARCHIVED` | **不变** |
| `confirm` | `boolean` | 否 | `false` | `false` = **仅预览零写入**；`true` = 真正执行并写 `operation_log` 审计。**不变** |
| **`recompile`** | `boolean` | 否 | **`false`** | 🆕 **本次新增**。`true` 时在快照对齐**之前**，先按各视图的 `component_sql_view.builder_config` 重放 `SemanticCompiler`，写回 `sql_template` + `builder_version` |

🔑 **`recompile` 缺省 `false` ⇒ 不传该字段时行为与改动前逐位相同**，存量调用方零影响。

### 语义

```
recompile=false（缺省）：  实时 component_sql_view  ──对齐──►  template.sql_views_snapshot
recompile=true         ：  builder_config ──重编译──► 实时 component_sql_view ──对齐──► snapshot
```

⚠️ **两个动作的事务边界**：重编译与快照对齐**必须同生共死**。既有实现已把
「快照重写 + `operation_log` 审计」委派给 `TemplateService` 的单一 `@Transactional` 方法
（`ConfigCenterResource:146-150` 原注释：不在本类内部自调用 `protected @Transactional`，
否则 CDI self-invocation 会静默跳过拦截器）。**重编译必须进同一个事务边界**，
不得留下「视图改了、快照没对齐」的中间态。

### Response（`confirm=false` 预览）

```jsonc
{
  "success": true,
  "data": {
    "resolvedTemplates": 5,          // 将被对齐的模板数
    "recompileViews": 28,            // 🆕 recompile=true 时：将被重编译的视图数（false 时为 0）
    "recompileChanged": 22,          // 🆕 其中 sql_template 实际会变化的数量
    "affectedComponents": 0,         // 既有字段，不变
    "written": 0                     // 预览恒为 0
  }
}
```

🚨 **`recompileChanged` 是 `AC-14` 的直接判据** —— 预览必须给出「有多少个会变」的数字，
否则 `CLAUDE.md §3.2` 第 1 步「先量化影响面」拿不到数据，用户没法批准。

### Response（`confirm=true` 执行）

字段同上，`written` / `recompileChanged` 为实际写入数，并按受影响模板各写一行 `operation_log`。

### 错误码

| 码 | 条件 | 变化 |
|---|---|---|
| 403 | 非 `SYSTEM_ADMIN` | 不变 |
| 400 | `templateIds` 含非法 UUID | 不变 |
| 500 | 🆕 `recompile=true` 且某视图的 `builder_config` 缺失 / 无法反序列化 | **新增**。🚫 **不许跳过该视图继续** —— 静默跳过会留下「一部分视图是新口径、一部分是旧口径」的混合态 |

---

## 3. 未变更但需在回归中确认的相关接口

| 端点 | 为什么要确认 | 对照 AC |
|---|---|---|
| `POST /api/cpq/builder/preview` | 走 `BuilderService.bindLiterals()` 把 `:customerCode` 替换成**字面量**，不经 `SqlViewExecutor.rewriteNamedParams` ⇒ B-2 的硬阻断**不应**影响它 | AC-5 |
| 报价单 driver 展开（`expand-driver` 系） | 返回的 `rows` 变少，但 `ExpandDriverResponse` 结构不变 | AC-1, AC-3 |
| 报价单提交校验 | 跨客户重复行消失后不再返「行键重复」422 | AC-2 |

---

## 4. `main-api.md` 回写

按 `task-docs.md §2.5`：**测试完成后、合并 master 之前**，把 §2 的
`POST /api/cpq/config-center/refresh-all-snapshots` 整段覆盖回 `dev-docs/main-api.md`
对应的 `ConfigCenterResource` 小节，并在末尾加：

```
> 来源任务：`task-260819-取数配置器/repair-260908-页签重复行与跨客户串号`｜回写日期：<实际日期>
```

🚫 **未回写不得进入合并环节。**
