# 接口文档 · task-0801 页签连表公式配置优化

> 版本：v1.0 / 2026-08-01
> 需求基线：同目录 `需求说明.md`（含 §11 澄清纪要）
> **一句话结论：本次不新增、不修改、不删除任何后端接口。后端零代码改动。**

---

## 1. 变更总览

| 端点 | 方法 | 本次是否改动 | 前端调用状态变化 |
|---|---|---|---|
| `/api/cpq/components/{id}/tab-defs` | GET | ❌ 无改动 | ✅ **继续调用**（左栏页签矩阵数据源，唯一保留的调用） |
| `/api/cpq/components/{id}/sample-cards` | GET | ❌ 无改动 | 🔻 **停止调用**（随 `SampleCardPicker` 删除） |
| `/api/cpq/components/{id}/dry-run` | POST | ❌ 无改动 | 🔻 **停止调用**（随试算移除） |
| `/api/cpq/components/{id}/dry-run-token` | POST | ❌ 无改动 | 🔻 **停止调用**（随试算移除） |

> 「停止调用」 ≠ 「下线」。**后端端点原样保留可用**（澄清 C4），仅前端不再发起请求。
> 恢复路径：从 git 历史取回 `SampleCardPicker.tsx` 与 `tabJoinFormulaService` 的三个方法即可，无需后端任何配合。

**公式的保存**不经过独立 HTTP 端点：抽屉通过 `onSave` 回调把结果交给宿主页 `ComponentManagement.tsx`，由其既有的组件保存链路（`componentService` 更新 `formulas` / `excelColumns`）落库。本次**不触碰**该链路。

---

## 2. 保留调用的端点（唯一）

### 2.1 GET `/api/cpq/components/{id}/tab-defs`

同目录组件的页签定义集合，供抽屉左栏「页签组件与可选字段」渲染。

- **实现**：`ComponentTabJoinResource.tabDefs`（`:44-48`） → `ComponentTabDefService.tabDefsForComponent`
- **权限**：`@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`
- **路径参数**：`id` = 当前被编辑组件的 UUID（宿主组件）

**响应**（`ApiResponse` 信封，前端 `api` 拦截器已 `return response.data`，故调用层拿到 `{code, message, data}`，需手动 `.data` 解包）：

```jsonc
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "alias": "TL",                       // 页签别名（公式里 [别名.字段] 的别名兜底）
      "tabKey": "…",                       // 唯一键，React key 用
      "componentId": "uuid",
      "componentName": "投料",              // 公式标识优先用它（比 alias 可读）
      "componentType": "NORMAL",           // NORMAL | EXCEL | SUBTOTAL
      "sortOrder": 1,
      "rowKeyFields": ["料件"],             // 行键 → 决定与宿主是否「可比」
      "detailFields": ["金额", "数量"],      // 明细字段 chip
      "allFields": ["金额", "数量", "类型"], // 含文本字段，供 SUMIF 条件选取
      "subtotalCols": ["小计金额"],          // 小计列 chip
      "self": true                          // 是否为当前宿主组件（紫色「明细(本页签·同行)」）
    }
  ]
}
```

**前端消费点（本次改动后）**：

| 字段 | 用途 | 本次新增用途 |
|---|---|---|
| `componentName` / `alias` | 卡片标题、插入 token 的引用名 | 🆕 **搜索匹配范围之一** |
| `detailFields` / `subtotalCols` | 明细、小计 chip | 🆕 **搜索匹配范围之一**（字段名匹配） |
| `rowKeyFields` | 与宿主 `selfRowKeyFields` 做可比判定 → 置灰 | 不变（搜索不改变置灰语义） |
| `self` | 宿主自身字段插裸 `[字段]` | 不变 |
| `allFields` | SUMIF 条件字段下拉 | 不变 |

**错误处理（前端现状，不改）**：请求失败 → `message.error('页签定义加载失败，引用补全不可用')` 且 `setTabDefs([])`；左栏渲染「暂无页签定义数据（请检查模板配置）」。

---

## 3. 停止调用的端点（后端保留，前端删调用）

以下三个端点**代码不动、行为不变**，仅记录契约以备日后恢复试算。

### 3.1 GET `/api/cpq/components/{id}/sample-cards`

反查引用本组件的报价行（最多 50 条）。`ComponentTabJoinResource:56-60`。

响应：`data: [{quotationId, quotationNo, lineItemId, cardName}]`；无引用返空数组。

### 3.2 POST `/api/cpq/components/{id}/dry-run`（EXCEL 单值试算）

`ComponentTabJoinResource:69-93` → `ComponentSampleCardService.dryRunForComponent`。

请求：`{"lineItemId": "uuid|null", "column": {…列定义…}, "cardValuesJson": "可选"}`
响应：`data: {"value": <any>, "errors": ["…"]}`；无样本卡时返 `{"value":null,"errors":["试算不可用(无样本卡)…"]}`（**非 500**）。
校验：`column` 缺失或非对象 → 400；`lineItemId` 非法 UUID → 400。

### 3.3 POST `/api/cpq/components/{id}/dry-run-token`（NORMAL/SUBTOTAL 逐行试算）

`ComponentTabJoinResource:104-136` → `ComponentSampleCardService.dryRunTokenForComponent` → `CardSnapshotService.dryRunTokenRows`。

请求：`{"lineItemId": "uuid|null", "tokens": [...], "selfRowKeyFields": ["料件", …]}`
响应：`data: {"rows": [{"rowKey": "…", "value": 12.34|null}], "errors": ["…"]}`；无样本 / 内部异常均降级为 `{rows:[], errors:[msg]}`（**非 500**）。

> ⚠️ **该端点背后的 `CardSnapshotService.dryRunTokenRows` 不可删** —— `CardSnapshotDryRunParityTest` 断言「试算逐行值 == 渲染逐行值」，它实际保护的是**渲染路径**的正确性；且 `QuotePendingScopeOpenWhitelistTest` 把 `dryRunTokenRows` 列在 pending 域开放白名单内，删改会动到安全语义。

---

## 4. 前端 service 层改动（本次唯一的"接口侧"改动）

文件：`cpq-frontend/src/services/tabJoinFormulaService.ts`

| 导出项 | 处置 | 说明 |
|---|---|---|
| `interface TabDef` | ✅ **保留** | 左栏与公式渲染的核心类型 |
| `tabDefsByComponent()` | ✅ **保留** | 唯一保留的调用 |
| `interface SampleCard` | 🗑️ **删除** | 仅 `SampleCardPicker` 使用 |
| `sampleCardsByComponent()` | 🗑️ **删除** | 同上 |
| `dryRunByComponent()` | 🗑️ **删除** | 随试算移除 |
| `dryRunToken()` | 🗑️ **删除** | 随试算移除 |

删除后必须确认：`/usr/bin/grep -rn "sampleCardsByComponent\|dryRunByComponent\|dryRunToken\|SampleCard" cpq-frontend/src` **零命中**（`dryRunToken` 在后端 java 里的命中不算）。

> 若删除后 `tsc` 报某处仍引用，**不要**回填假实现——说明还有未清理的调用点，按 fronttask.md Task F3 的清单逐一处理。

---

## 5. 接口契约不变性声明（验收项）

前端工程师完成后，后端工程师/技术总监按以下方式确认后端未被误伤：

```bash
# 1) 端点仍在且鉴权正常（期望 401，不能是 404/500）
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' \
  http://localhost:8081/api/cpq/components/00000000-0000-0000-0000-000000000000/tab-defs

# 2) 本次 PR 不得含任何 cpq-backend 下的改动
git diff --stat master... -- cpq-backend/    # 期望：空输出
```
