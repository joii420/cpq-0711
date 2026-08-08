# api · 价格调整更新任务性能优化

> 前后端交接契约。任何一方改契约必须**先改本文件并通知另一方**。
> 本任务是后端性能优化，**接口面极小**：无新增端点、无路径变更、无鉴权变更，仅既有设置端点的请求/响应体各多一个布尔字段。
> 测试通过后本文件的**最终契约**须按端点回写 `dev-docs/main-api.md`（任务平台规则 §2.4 / §4 步 9）。

---

## 变更总览

| # | 方法 + 路径 | 变更性质 | 对应 FR |
|---|---|---|---|
| §1 | `GET /api/cpq/price-adjust/settings` | 响应体**新增 1 字段** | FR-9 |
| §2 | `PUT /api/cpq/price-adjust/settings` | 请求体 + 响应体**各新增 1 字段** | FR-9 |

**无变更但受影响的既有端点**（行为不变、只是变快，列出供回归确认）：

| 方法 + 路径 | 说明 |
|---|---|
| `POST /api/cpq/price-adjust/reviews/approve` | 触发 job 异步执行；**响应体与状态码不变**，仅后台执行更快 |
| `GET /api/cpq/price-adjust/jobs` / `/jobs/{jobId}` | 进度查询；**JobDTO / JobItemDTO 结构不变** |
| `POST /api/cpq/price-adjust/jobs/{jobId}/retry`、`.../items/{itemId}/retry` | 重试；语义不变（FAILED / CONFLICT / STALE 三态判定不变） |

---

## §1 `GET /api/cpq/price-adjust/settings`

读取调价系统参数。

- **鉴权**：`PRICING_MANAGER` | `SYSTEM_ADMIN`（**不变**）
- **请求参数**：无

### 响应 `200`

```json
{
  "subtotalGuardThreshold": 0.01,
  "subtotalGuardEnabled": false,
  "updatedAt": "2026-08-06T12:34:56+08:00"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `subtotalGuardThreshold` | `BigDecimal` | 是 | L3 升版口径守卫阈值（元）。**既有字段，语义不变** |
| `subtotalGuardEnabled` | `boolean` | 是 | 🆕 **本次新增**。S0 L3 口径守卫总开关。`false` = 升版时跳过 S0 旧价重算（默认）；`true` = 每项都跑守卫，行为与本任务改造前逐位一致 |
| `updatedAt` | `OffsetDateTime` | 否 | 回显用 |

> 🔒 `subtotalGuardEnabled=false` 时 `subtotalGuardThreshold` **仍然返回且仍然有意义** —— 开关打开后立即按该阈值判定，两字段独立。

---

## §2 `PUT /api/cpq/price-adjust/settings`

写入调价系统参数。**即时生效，无需重启服务**（沿用既有阈值字段的口径，task-0729 验收 #70④）。

- **鉴权**：`SYSTEM_ADMIN`（**不变**，写权限不放宽）

### 请求体

```json
{
  "subtotalGuardThreshold": 0.01,
  "subtotalGuardEnabled": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `subtotalGuardThreshold` | `BigDecimal` | 否 | 省略 = 不改动该项 |
| `subtotalGuardEnabled` | `boolean` | 否 | 🆕 **本次新增**。省略 = 不改动该项 |
| `updatedAt` | — | — | 请求体里**忽略**（服务端填充后回显），沿用既有 DTO 约定 |

> ⚠️ **两字段均可单独提交**。实现时不得因为某字段为 `null` 就把它写成默认值 —— 那会让"只改阈值"的调用顺手把开关重置掉。这是 `null = 不改` 与 `null = 置默认` 的经典分叉，必须按前者实现，并有回归用例。

### 响应 `200`

与 §1 响应体结构完全一致（写入后的最新值回显）。

### 错误码

| 码 | 含义 |
|---|---|
| `401` | 未登录 / 会话失效 |
| `403` | 角色不是 `SYSTEM_ADMIN` |
| `400` | `subtotalGuardThreshold` 为负数或非法数值（**既有校验，不变**） |

---

## 前端影响

**无。** 全前端零引用 `price-adjust/settings`（已实测确认：`cpq-frontend/src` 对该路径命中 0 处），本期不新建配置页。开关经 API / DB 配置。详见 `fronttask.md`。
