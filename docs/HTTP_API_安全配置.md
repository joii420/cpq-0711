# HTTP_API 数据源 — 安全配置指南

> Phase I3 引入 (2026-05-18). HTTP_API 是配置中心 DATA_SOURCE 的 4 种类型之一, 允许字段值从外部 REST API 取. **默认完全关闭**, 必须显式 opt-in.

## 默认拒绝原则

`HttpApiResolver` 在以下任一情况返 null + LOG.warn 不抛:

- `cpq.http-api.allowed-hosts` 未配置（**默认状态**, 整个 HTTP_API resolver 关闭）
- 目标 host 不在白名单
- 目标 host DNS 解析到私有 IP / loopback / link-local
- URL 不是 HTTPS 且 host 不在 `cpq.http-api.allow-http-hosts`
- `auth_token_env` 指定的环境变量未设置
- HTTP 状态码非 2xx
- 5 秒超时

## UI 配置路径 (J2 已落地)

1. 「组件管理」→ 编辑组件 → INPUT_NUMBER 字段「+ 默认值来源」→ 选 HTTP_API
2. 填 URL 模板 / response_path / auth_token_env 三个字段
3. 点底部「测试解析」按钮 (J3): 用 driverRow JSON 调 `/api/cpq/data-sources/resolve` 验证配置
4. 保存
5. DATA_SOURCE 字段同样支持: 字段配置「内容/配置」列 → 类型选 HTTP API → 「配置 HTTP API」按钮 → inline Modal

## 启用步骤（生产环境）

### Step 1: 配置 application.properties 或环境变量

```properties
# 必须 — 至少一个白名单 host
cpq.http-api.allowed-hosts=api.example.com,pricing.internal.example.com

# 可选 — 允许 http (非 https) 的特定 host (默认全部要求 https)
# cpq.http-api.allow-http-hosts=internal-dev.example.com
```

或者环境变量：

```bash
CPQ_HTTP_API_ALLOWED_HOSTS=api.example.com,pricing.internal.example.com
```

### Step 2: 配置鉴权 token（如需）

**Token 必须放环境变量, 严禁存数据库或代码**：

```bash
export EXAMPLE_API_TOKEN=sk-xxx-yyy-zzz
```

在字段配置里：

```json
{
  "field_type": "DATA_SOURCE",
  "datasource_binding": {
    "type": "HTTP_API",
    "api_config": {
      "url_template": "https://api.example.com/price/{partNo}",
      "response_path": "data.unit_price",
      "auth_token_env": "EXAMPLE_API_TOKEN"
    }
  }
}
```

Resolver 通过 `System.getenv("EXAMPLE_API_TOKEN")` 读取，作为 `Authorization: Bearer ...` 头发出。

### Step 3: 验证白名单生效

```bash
curl -X POST http://localhost:8081/api/cpq/data-sources/resolve \
  -H "Content-Type: application/json" \
  -H "Cookie: <auth>" \
  -d '{
    "type": "HTTP_API",
    "config": {
      "api_config": {
        "url_template": "https://api.example.com/price/{partNo}",
        "response_path": "data.unit_price"
      }
    },
    "driverRow": {"partNo": "3120012574"}
  }'
```

## URL 模板语法

`{name}` 占位符 → 从 `driverRow.get(name)` 取值, URL-encode 后替换。

```
url_template = "https://api.example.com/price/{partNo}?customer={customerId}"
driverRow    = {partNo: "ABC-123", customerId: "8de8f8b0-..."}
            ↓
最终 URL    = https://api.example.com/price/ABC-123?customer=8de8f8b0-...
```

driverRow 缺字段时返 null（不会发请求）。

## response_path 提取

简单 dot-path：

```
response_path = "data.unit_price"
JSON 响应    = {"data": {"unit_price": 12.5, "currency": "CNY"}}
            ↓
返回         = 12.5  (Number)
```

支持 Number / String / Boolean。`null` / 缺路径 → 返 null。如需复杂提取（数组下标、JSONPath 表达式），加 follow-up issue。

## 缓存

- Caffeine, 5 分钟 TTL after-write, max 1000 条目
- Key = url + bearer_token_hash
- 外部 API 变化时, **不会**自动失效（5 分钟内仍返旧值）
- 业务上要求实时性的场景**不要用 HTTP_API** — 考虑用 BASIC_DATA / GLOBAL_VARIABLE

## 已知限制

- **GET only** — 不支持 POST/PUT/PATCH（防误用作业务变更）
- **不跟随重定向** — 防 SSRF
- **5 秒超时硬编码** — 后续可配置化
- **无重试** — 失败即返 null（避免雪崩）
- **无熔断** — 单次失败不影响后续调用（cache miss 才会再发）

## 不变量

| 检查 | 行为 |
|---|---|
| 白名单未配置 | 整个 resolver 关闭返 null |
| host 不在白名单 | 拒绝, LOG.warn |
| DNS 解析到内网 IP | 拒绝（防 SSRF） |
| 非 HTTPS 且未在 allow-http-hosts | 拒绝 |
| auth_token_env 未设置环境变量 | 拒绝 |
| HTTP 非 2xx | 返 null |
| 5 秒超时 | 返 null |
| 重定向 | 拒绝 |
| 响应非 JSON 或 path 不命中 | 返 null |

## 关联

- 配置中心架构: [配置中心架构.md](./配置中心架构.md)
- 数据源类型扩展: [数据源类型扩展指南.md](./数据源类型扩展指南.md)
- 反模式: 暂无 HTTP_API 相关条目（新功能，待踩坑后补充）
