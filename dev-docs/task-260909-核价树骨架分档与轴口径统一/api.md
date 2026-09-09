# api · 接口契约

> 本次**不新增端点、不删端点、不改任何路径与方法**。变更只有三类：①`usage` 值域扩展 ②`publish` 响应加法式扩展 ③角色收紧。
> 🚨 前后端两路子代理**只以本文件为准**。开工后若本文件变更，主线会显式通知两侧；**不要自行推断契约**。

---

## §1 `/api/cpq/costing-bom-tree-config` —— `usage` 值域扩展

**路径 / 方法一律不变**（注意是**单数** `costing-bom-tree-config`）：

| 方法 | 路径 | 变更 |
|---|---|---|
| `GET` | `/api/cpq/costing-bom-tree-config?usage=<usage>` | `usage` 值域 |
| `POST` | `/api/cpq/costing-bom-tree-config` | body `usage` 值域 + 拒绝 `COSTING` |
| `PUT` | `/api/cpq/costing-bom-tree-config/{id}` | 同上 |
| `POST` | `/api/cpq/costing-bom-tree-config/{id}/activate` | 无契约变更 |
| `DELETE` | `/api/cpq/costing-bom-tree-config/{id}` | 无契约变更 |

### 1.1 `usage` 值域

| 值 | 中文 | 读 | 写 |
|---|---|---|---|
| `QUOTE` | 报价 | ✅ | ✅ |
| `COST_BASIC` | 基础核价 | ✅ | ✅ |
| `COST_DETAIL` | 详细核价 | ✅ | ✅ |
| `COSTING` | （已停用） | ✅ **只读兼容别名，等价 `COST_BASIC`** | 🚫 **拒绝** |

- `GET` 不传 `usage` → 返回全部（**向后兼容口径不变**）
- `GET ?usage=COSTING` → 按 `COST_BASIC` 处理并返回其记录（存量客户端不会碎）
- 写入 `usage=COSTING` → **400**，`message` = `COSTING 已停用，请选择 COST_BASIC 或 COST_DETAIL`
- 写入非四值之一 → **400**，`message` 列出三个合法写入值
- `POST` **未传 `usage`** → 400（原「兜底 COSTING」的默认行为**取消**：三套并存后静默兜底就是「用户选了详细核价、系统写成基础核价」那类静默故障）

### 1.2 响应体（形状不变）

```jsonc
{ "success": true, "data": [
  { "id": "uuid", "name": "基础核价BOM树-v1", "sqlTemplate": "WITH RECURSIVE …",
    "isActive": true, "usage": "COST_BASIC",
    "createdAt": "2026-09-09T…", "updatedAt": "2026-09-09T…" } ] }
```

### 1.3 保存期校验（沿用 `CostingTreeSqlValidator`，不变）

| 情形 | 状态码 | message |
|---|---|---|
| 缺 `:production_part_nos` | 400 | `递归 SQL 必须引用 :production_part_nos` |
| 缺任一输出列 | 400 | `递归 SQL 缺输出列: <列名>` |
| `:versionFilter` 宏语法错 | 400 | `递归 SQL 的 :versionFilter 宏语法错误: …` |
| SQL 不可执行 | 400 | `递归 SQL 无法执行: …` |

> ⚠️ `BL-0227` 已登记「递归 SQL 校验失败返 500 而非 400」，**本期不修**（不在范围内）；测试遇到 500 按已知缺陷记录，不算本任务回归。

---

## §2 角色收紧

| 端点 | 改动前 | 改动后 |
|---|---|---|
| `CostingBomTreeConfigResource` **类级全部端点** | `@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})` | `@RoleAllowed({"SYSTEM_ADMIN"})` |

- `SALES_MANAGER` 访问任一端点（含 `GET`）→ **403**
- 不新增方法级注解；不改其它 Resource

---

## §3 `POST /api/cpq/templates/{id}/publish` —— 响应加法式扩展

**路径 / 方法 / 请求体 / 角色一律不变**（`@RoleAllowed({"SALES_MANAGER","SYSTEM_ADMIN"})` 保持）。

`ApiResponse<TemplateDTO>` 的 `TemplateDTO` **新增一个字段**：

```jsonc
{ "success": true, "data": {
    "id": "…", "name": "核价模板1", "version": "v1.0", "status": "PUBLISHED",
    "warnings": ["本模板含 1 个非本数据集页签：加工费(QUOTE)"]     // ← 新增
} }
```

| 项 | 规定 |
|---|---|
| 类型 | `string[]`，**恒非 null**；无告警时为 `[]` |
| 填充范围 | **仅 `publish` 填充**；`TemplateDTO` 的其它产出端点恒 `[]`（加法式，不影响既有消费方） |
| 文案 | `本模板含 {N} 个非本数据集页签：{页签名}({方言})[、{页签名}({方言})…]` |
| 判定基准 | 该模板**树页签组件的方言**；无树页签 → 不判，`warnings` 为 `[]` |
| 🚫 语义 | **只告警不拦**，`publish` 仍返 **200**。**不得**因告警抛异常或回滚 |

> 既有字段一个不改、不删、不改类型 ⇒ 存量前端零改动即可继续工作（本次前端也**不消费** `warnings`，`fronttask.md` 无对应 `F-x`；它的消费方是主线亲验与测试用例）。

---

## §4 无契约变更但受本次影响的端点（供测试选取回归口）

| 端点 | 为什么列在这里 |
|---|---|
| `POST /api/cpq/components/{id}/expand-driver` | 轴口径改了 ⇒ 同一组件同一入参的返回**行数会变**。这是**预期变化**，不是回归；AC-10/11/12 的后端证据取自这里 |
| `GET /api/cpq/quotations/{id}` | 核价卡片值来源。AC-6~AC-9 的最终可观测面在 UI，但取证可用它 |
| `POST /api/cpq/quotations/{id}/refresh-snapshot` | AC-20 的「刷新基础数据」入口 |

🚫 以上三个端点**本次不改任何契约**，列出只为让测试知道去哪里取证。
