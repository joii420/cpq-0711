# CPQ 系统接口总览文档（main-api.md）

> 本文件由技术总监扫描 `cpq-backend` 全部 JAX-RS Resource 自动生成，覆盖 **87 个 Resource 类、约 413 个 HTTP 端点**，按业务模块分为 12 大类。
> 生成日期：2026-07-08 ｜ 数据来源：`cpq-backend/src/main/java/com/cpq/**/resource/*.java` 及其引用的 DTO / 实体。
> 用途：前后端接口契约基线、联调对照、新接口设计参照。字段说明取自源码 javadoc / 注释，无注释处据字段名与类型推断。

---

## 📌 全局约定（所有接口通用，正文不再逐条重复）

### 1. 基础信息

| 项 | 值 |
|----|----|
| 后端基址 | `http://localhost:8081` |
| 前端开发代理 | 浏览器访问 `http://localhost:5174`，`/api` 经 Vite proxy 转发至后端 |
| 数据格式 | 默认 `application/json`（UTF-8）；文件上传为 `multipart/form-data`；导出类端点直返二进制流 |
| 路径前缀 | 无全局 root-path 覆盖，端点路径即类/方法 `@Path` 声明值（业务 API 以 `/api/cpq/` 开头，系统管理 API 以 `/api/system/` 开头） |

### 2. 鉴权机制

- 采用**基于会话 Cookie** 的鉴权（非 Bearer Token）。调用 `POST /api/cpq/auth/login` 成功后，服务端通过 `Set-Cookie` 下发会话；后续受保护端点须在请求头携带该 `Cookie`。
- 鉴权由全局过滤器 `RoleFilter`（`@Priority(AUTHENTICATION)`）统一拦截，规则：
  1. 仅拦截 `/api/cpq/**` 与 `/api/system/**` 路径；
  2. 公开路径直接放行：`/api/cpq/health`、`/api/cpq/auth/login`、`/api/cpq/auth/forgot-password`、`/api/cpq/auth/reset-password`；
  3. 方法或类上标注了 `@RoleAllowed` 才校验：未登录返 **401 未登录**，角色不匹配返 **403 无权限访问**；
  4. **未标注 `@RoleAllowed` 的端点不做角色校验**（正文标注为"不校验"，但仍可能依赖会话定位当前用户）。
- 可通过配置 `cpq.security.rbac.enabled=false` 全局关闭 RBAC（仅用于本地调试）。

### 3. 角色枚举

| 角色码 | 含义 |
|--------|------|
| SALES_REP | 销售代表 |
| SALES_MANAGER | 销售经理 |
| PRICING_MANAGER | 核价/定价经理 |
| SYSTEM_ADMIN | 系统管理员 |

> 规律：**读操作**通常放开给四角色；**写 / 删除 / 发布 / 归档 / 洗数据**类动作在方法级收窄（多为 `PRICING_MANAGER` / `SALES_MANAGER` / `SYSTEM_ADMIN`），正文各端点已据实标注方法级覆盖差异。

### 4. 统一响应包装

绝大多数端点返回统一包装 `ApiResponse<T>`：

```json
{
  "code": 200,
  "message": "success",
  "data": { }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | int | 状态码，成功 200（部分实体/自定义 Response 用 `code=0` 表示成功，正文已标注） |
| message | string | 提示信息，成功为 "success"，失败为中文错误原因 |
| data | T | 业务数据，可能为对象 / 数组 / 分页壳 / null（`NON_NULL` 序列化，null 字段不输出） |

**例外**：以下端点**不走** `ApiResponse` 包装，正文均已单独标注——
- 导出类：报价单/核价比对/定价策略 的 PDF、Excel、HTML —— 直返 `jakarta.ws.rs.core.Response`（二进制 / 文本流，带 `Content-Disposition`）；
- 产品配置 / 物料配方（第十一章）、部分选配器实体、`PartVersionResource` —— 直返实体 / DTO / 手工构造 `Response`；
- 部分 `create` 返 **201 Created**、`delete` 返 **204 No Content**（无响应体）。

### 5. 分页约定

分页端点响应 `data` 多为分页壳（不同模块命名有 `PageResult` / `PageResult<T>` 两种，字段一致）：

| 字段 | 类型 | 说明 |
|------|------|------|
| total | long | 记录总数 |
| list / content | T[] | 当页数据列表 |
| page | int | 当前页码（部分模块返回） |
| size | int | 每页条数（部分模块返回） |

分页请求通用查询参数：`page`（页码，默认 0）、`size`（每页条数，默认 20）。

### 6. 常见状态码

| HTTP | 语义 |
|------|------|
| 200 | 成功 |
| 201 | 创建成功（部分 create 端点） |
| 204 | 成功无响应体（部分 delete 端点） |
| 400 | 请求参数错误 / 业务校验失败 |
| 401 | 未登录（缺少或失效的会话 Cookie） |
| 403 | 无权限（角色不匹配） |
| 404 | 资源不存在 |
| 429 | 触发限流（如登录接口按 IP / 用户名限流） |
| 500 | 服务端异常 |

---

## 📖 模块目录

| 序号 | 模块 | 端点数(约) | 主要路径前缀 |
|------|------|-----------|-------------|
| 一 | 认证与系统管理 | 43 | `/api/cpq/auth`、`/api/cpq/users`、`/api/system/*` |
| 二 | 组件管理 | 21 | `/api/cpq/components`、`/api/cpq/component-*` |
| 三 | 模板管理 | 33 | `/api/cpq/templates`、`/api/cpq/template-*` |
| 四 | 报价单 | 44 | `/api/cpq/quotations`、`/api/cpq/costing-orders` |
| 五 | 核价 | 47 | `/api/cpq/costing-basic`、`/api/cpq/costing-part`、`/api/cpq/costing-summary` |
| 六 | 基础资料与主数据 | 22 | `/api/cpq/material-masters`、`/api/cpq/v6/*`、`/api/cpq/master-data` |
| 七 | 客户与销售线索 | 22 | `/api/cpq/customers`、`/api/cpq/customer-leads`、`/api/cpq/industries` |
| 八 | 配置中心与数据源 | 33 | `/api/cpq/config-*`、`/api/cpq/data-sources`、`/api/cpq/global-variables` |
| 九 | Excel 导入 | 21 | `/api/cpq/imports`、`/api/cpq/excel-templates`、`/api/cpq/import-*` |
| 十 | 3D 配置器与选配 | 79 | `/api/cpq/configurator-*`、`/api/cpq/feature-library`、`/api/cpq/part-*`、`/api/cpq/sel-*` |
| 十一 | 产品配置与物料配方 | 21 | `/api/cpq/configure-*`、`/api/cpq/material-recipes` |
| 十二 | 产品、定价与其他 | 27 | `/api/cpq/products`、`/api/cpq/pricing-*`、`/api/cpq/element-prices` |

> 端点数为估算（按独立 HTTP 路由方法计），以正文实际列举为准。

---
## 一、认证与系统管理

### 1.1 AuthResource（认证：登录/登出/当前用户/密码）

类级 @Path：`/api/cpq/auth`；Produces/Consumes 均为 `application/json`。

#### 用户登录
- **功能**: 校验账号密码，创建会话并返回用户信息（带每 IP / 每用户名限流，超阈值返 429）
- **方法**: POST
- **路径**: `/api/cpq/auth/login`
- **鉴权**: 公开
- **请求头**: `Content-Type: application/json`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | string | 是 | 用户名（不可空白） |
| password | string | 是 | 密码（不可空白） |

- **响应内容**: `ApiResponse<LoginResponse>`，data 字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 用户 ID |
| username | string | 用户名 |
| fullName | string | 姓名 |
| role | string | 角色（SALES_REP/SALES_MANAGER/PRICING_MANAGER/SYSTEM_ADMIN） |
| forceChangePassword | boolean | 是否强制修改密码（首次登录） |

- **说明**: 登录成功后通过 Set-Cookie 下发会话 Cookie，后续鉴权端点需携带。

#### 用户登出
- **功能**: 销毁当前会话
- **方法**: POST
- **路径**: `/api/cpq/auth/logout`
- **鉴权**: 不校验（无 @RoleAllowed，但依赖会话 Cookie 定位当前会话）
- **请求头**: 接受任意 Content-Type（无请求体）
- **响应内容**: `ApiResponse<Void>`，data 为 null

#### 修改密码
- **功能**: 校验当前密码后修改为新密码
- **方法**: POST
- **路径**: `/api/cpq/auth/change-password`
- **鉴权**: 需登录（内部通过会话取当前用户 ID）
- **请求头**: `Content-Type: application/json`；须携带登录 Cookie
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| currentPassword | string | 是 | 当前密码（不可空白） |
| newPassword | string | 是 | 新密码（至少 8 位，须同时含字母和数字） |

- **响应内容**: `ApiResponse<Void>`，data 为 null

#### 忘记密码（申请重置）
- **功能**: 通过邮箱申请密码重置链接；无论邮箱是否存在均返成功（防用户枚举）
- **方法**: POST
- **路径**: `/api/cpq/auth/forgot-password`
- **鉴权**: 公开
- **请求头**: `Content-Type: application/json`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| email | string | 是 | 邮箱（须为合法邮箱格式） |

- **响应内容**: `ApiResponse<Void>`，data 为 null

#### 重置密码
- **功能**: 使用有效重置令牌设置新密码
- **方法**: POST
- **路径**: `/api/cpq/auth/reset-password`
- **鉴权**: 公开
- **请求头**: `Content-Type: application/json`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| token | string | 是 | 重置令牌（不可空白） |
| newPassword | string | 是 | 新密码（至少 8 位，须同时含字母和数字） |

- **响应内容**: `ApiResponse<Void>`，data 为 null

#### 获取当前用户信息
- **功能**: 返回当前登录用户的基本信息；未登录抛 401
- **方法**: GET
- **路径**: `/api/cpq/auth/me`
- **鉴权**: 需登录（会话指向的用户若已被删除则销毁会话并返 401）
- **请求头**: 须携带登录 Cookie
- **响应内容**: `ApiResponse<Map<String,Object>>`，data 字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 用户 ID |
| username | string | 用户名 |
| fullName | string | 姓名 |
| role | string | 角色 |
| forceChangePassword | boolean | 是否需要强制改密（首次登录） |

---

### 1.2 HealthResource（健康检查）

类级 @Path：`/api/cpq/health`；Produces `application/json`。

#### 健康检查
- **功能**: 返回服务存活状态
- **方法**: GET
- **路径**: `/api/cpq/health`
- **鉴权**: 公开
- **响应内容**: `ApiResponse<Map<String,String>>`，data 字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| status | string | 固定 "UP" |
| service | string | 固定 "CPQ Quotation System" |

---

### 1.3 SystemConfigResource（系统配置管理）

类级 @Path：`/api/system/configs`；Produces/Consumes 均为 `application/json`。所有端点内部调用 `requireSystemAdmin`，即需 SYSTEM_ADMIN 权限。

#### 配置列表
- **功能**: 按分类查询系统配置列表
- **方法**: GET
- **路径**: `/api/system/configs`
- **鉴权**: 需登录 + 系统管理员（requireSystemAdmin）
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| category | string | 否 | 配置分类过滤（validation/import/retention/element_price/business） |

- **响应内容**: `ApiResponse<List<SystemConfigDTO>>`，元素结构见下方 SystemConfigDTO

#### 配置详情
- **功能**: 按配置键查询单条配置
- **方法**: GET
- **路径**: `/api/system/configs/{key}`
- **鉴权**: 需登录 + 系统管理员
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| key | string | 配置键（形如 category.key） |

- **响应内容**: `ApiResponse<SystemConfigDTO>`

**SystemConfigDTO 字段（响应通用）**:

| 字段 | 类型 | 说明 |
|------|------|------|
| configKey | string | 配置键 |
| configValue | string | 当前值 |
| defaultValue | string | 默认值 |
| dataType | string | 数据类型（STRING/NUMBER/BOOLEAN/JSON） |
| category | string | 分类 |
| description | string | 描述 |
| modifiableBy | string | 可修改角色（SYSTEM_ADMIN/SALES_MANAGER） |
| createdAt | datetime | 创建时间 |
| updatedAt | datetime | 更新时间 |

#### 新建配置
- **功能**: 创建一条系统配置
- **方法**: POST
- **路径**: `/api/system/configs`
- **鉴权**: 需登录 + 系统管理员
- **请求头**: `Content-Type: application/json`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| configKey | string | 是 | 配置键，格式须为 `category.key`（小写字母/数字/下划线） |
| configValue | string | 是 | 配置值 |
| defaultValue | string | 是 | 默认值 |
| dataType | string | 是 | 数据类型，须为 STRING/NUMBER/BOOLEAN/JSON |
| category | string | 是 | 分类，须为 validation/import/retention/element_price/business |
| description | string | 否 | 描述（≤500 字符） |
| modifiableBy | string | 否 | 可修改角色，SYSTEM_ADMIN 或 SALES_MANAGER（默认 SYSTEM_ADMIN） |

- **响应内容**: HTTP 201，`ApiResponse<SystemConfigDTO>`（code=201）

#### 更新配置
- **功能**: 按配置键更新配置值/描述
- **方法**: PUT
- **路径**: `/api/system/configs/{key}`
- **鉴权**: 需登录 + 系统管理员
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| key | string | 配置键 |

- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| configValue | string | 是 | 新配置值（不可空白） |
| description | string | 否 | 描述 |

- **响应内容**: `ApiResponse<SystemConfigDTO>`

#### 删除配置
- **功能**: 按配置键删除配置
- **方法**: DELETE
- **路径**: `/api/system/configs/{key}`
- **鉴权**: 需登录 + 系统管理员
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| key | string | 配置键 |

- **响应内容**: `ApiResponse<Void>`

---

### 1.4 DdlExtensionResource（运行时字段扩展 / DDL 加列）

类级 @Path：`/api/system/ddl`；Produces/Consumes 均为 `application/json`。全部端点需 SYSTEM_ADMIN 角色。

#### 扩展列（ALTER TABLE ADD COLUMN）
- **功能**: 对目标表执行运行时加列；成功返回可复制到 git 的 migration 内容
- **方法**: POST
- **路径**: `/api/system/ddl/extend-column`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **请求头**: `Content-Type: application/json`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| tableName | string | 是 | 目标表名（≤64，须在可扩展白名单内） |
| columnName | string | 是 | 列名（≤64，小写字母开头，仅小写字母/数字/下划线） |
| dataType | string | 是 | 列类型（VARCHAR(N)/TEXT/DECIMAL(p,s)/INTEGER/BOOLEAN/DATE/TIMESTAMPTZ） |
| defaultValue | string | 是 | 默认值字面量（不可为 null，供旧行保持一致性；空串对 VARCHAR/TEXT 合法） |
| importance | string | 否 | 重要度 CRITICAL/IMPORTANT/NORMAL（默认 NORMAL） |
| affectsCalculation | boolean | 否 | 是否影响计算（默认 false） |

- **响应内容**: `ApiResponse<DdlOperationDTO>`（结构见下）；异常码：400 校验失败 / 409 DDL 锁占用 / 423 导入锁激活 / 500 ALTER 失败（尝试补偿 DROP COLUMN）

#### DDL 操作历史
- **功能**: 分页查询 DDL 操作历史（按时间倒序）
- **方法**: GET
- **路径**: `/api/system/ddl/history`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 0 基页码（默认 0） |
| size | int | 否 | 页大小（默认 20） |
| status | string | 否 | 状态过滤：SUCCESS/FAILED |

- **响应内容**: `ApiResponse<List<DdlOperationDTO>>`

**DdlOperationDTO 字段**:

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 操作 ID |
| tableName | string | 表名 |
| columnName | string | 列名 |
| dataType | string | 列类型 |
| defaultValue | string | 默认值 |
| importance | string | 重要度 |
| affectsCalculation | boolean | 是否影响计算 |
| status | string | 状态（SUCCESS/FAILED） |
| errorMessage | string | 错误信息（失败时） |
| migrationContent | string | 完整 ALTER TABLE + COMMENT SQL（UI 展示"复制 migration"） |
| flywayVersionHint | string | Flyway 版本号建议 |
| createdBy | UUID | 操作人 ID |
| createdByName | string | 操作人姓名 |
| createdAt | datetime | 操作时间 |

#### 可扩展表列表
- **功能**: 列出允许运行时加列的 15 张表
- **方法**: GET
- **路径**: `/api/system/ddl/extensible-tables`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **响应内容**: `ApiResponse<List<String>>`，data 为表名字符串数组

#### 查询表已有列
- **功能**: 读取 information_schema，返回指定表现有列名（供前端向导检测重名）
- **方法**: GET
- **路径**: `/api/system/ddl/columns/{tableName}`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| tableName | string | 表名（须在可扩展白名单内） |

- **响应内容**: `ApiResponse<List<String>>`，data 为列名字符串数组

---

### 1.5 ImportLockResource（导入锁 - 持锁人操作）

类级 @Path：`/api/cpq/import/locks`；Produces/Consumes 均为 `application/json`。

#### 锁心跳续期
- **功能**: 心跳续期，延长锁 TTL（仅持锁人可调）
- **方法**: POST
- **路径**: `/api/cpq/import/locks/{id}/heartbeat`
- **鉴权**: 需登录（内部取当前用户校验为持锁人）
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 锁 ID（lockId） |

- **响应内容**: `ApiResponse<Void>`

---

### 1.6 LockMonitorResource（锁监控管理 - 管理员）

类级 @Path：`/api/system/locks`；Produces/Consumes 均为 `application/json`。所有端点 requireSystemAdmin。

#### 产品导入锁列表
- **功能**: 列出当前活跃的产品导入锁
- **方法**: GET
- **路径**: `/api/system/locks/product-imports`
- **鉴权**: 需登录 + 系统管理员
- **响应内容**: `ApiResponse<List<ProductImportLockDTO>>`（结构见下）

**ProductImportLockDTO 字段**:

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 锁 ID |
| customerId | UUID | 客户 ID |
| partNo | string | 料号 |
| granularity | string | 锁粒度（枚举名） |
| lockedBy | UUID | 持锁人 ID |
| importRecordId | UUID | 导入记录 ID |
| lockedAt | datetime | 加锁时间 |
| lastHeartbeatAt | datetime | 最近心跳时间 |
| expiresAt | datetime | 过期时间 |
| status | string | 锁状态（枚举名） |
| releasedAt | datetime | 释放时间 |
| releaseReason | string | 释放原因 |

#### 强制释放产品导入锁
- **功能**: 管理员强制释放指定产品导入锁
- **方法**: POST
- **路径**: `/api/system/locks/product-imports/{id}/release`
- **鉴权**: 需登录 + 系统管理员
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 锁 ID |

- **请求体**（ReleaseLockRequest）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| reason | string | 否 | 释放原因，须为 COMPLETED/CANCELLED/TIMEOUT/ADMIN_FORCE/ERROR（默认 COMPLETED） |

- **响应内容**: `ApiResponse<Void>`

#### DDL 锁状态
- **功能**: 查询 DDL 全局锁当前状态
- **方法**: GET
- **路径**: `/api/system/locks/ddl`
- **鉴权**: 需登录 + 系统管理员
- **响应内容**: `ApiResponse<DdlLockStatusDTO>`：

| 字段 | 类型 | 说明 |
|------|------|------|
| locked | boolean | 是否已锁 |
| lockedBy | UUID | 持锁人 ID |
| lockedAt | datetime | 加锁时间 |
| expiresAt | datetime | 过期时间 |
| operationDesc | string | 操作描述 |

#### 强制释放 DDL 锁
- **功能**: 管理员强制释放 DDL 全局锁
- **方法**: POST
- **路径**: `/api/system/locks/ddl/release`
- **鉴权**: 需登录 + 系统管理员
- **响应内容**: `ApiResponse<Void>`

---

### 1.7 DepartmentResource（部门管理）

类级 @Path：`/api/cpq/departments`；类级 @RoleAllowed [SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]。Produces/Consumes 均为 `application/json`。

#### 部门列表
- **功能**: 分页查询部门列表
- **方法**: GET
- **路径**: `/api/cpq/departments`
- **鉴权**: 需登录 + 角色 [SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 0 基页码（默认 0） |
| size | int | 否 | 页大小（默认 50） |

- **响应内容**: `ApiResponse<PageResult<DepartmentDTO>>`

**DepartmentDTO 字段**:

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 部门 ID |
| parentId | UUID | 上级部门 ID |
| code | string | 部门编码（必填，创建时校验） |
| name | string | 部门名称（必填） |
| sortOrder | int | 排序序号 |
| status | string | 状态 |
| createdAt | datetime | 创建时间 |
| children | List&lt;DepartmentDTO&gt; | 子部门（树形时使用） |

#### 新建部门
- **功能**: 创建部门
- **方法**: POST
- **路径**: `/api/cpq/departments`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **请求头**: `Content-Type: application/json`
- **请求体**: DepartmentDTO（code、name 必填，可含 parentId/sortOrder/status）
- **响应内容**: `ApiResponse<DepartmentDTO>`

#### 更新部门
- **功能**: 更新部门信息
- **方法**: PUT
- **路径**: `/api/cpq/departments/{id}`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 部门 ID |

- **请求体**: DepartmentDTO
- **响应内容**: `ApiResponse<DepartmentDTO>`

#### 更新部门状态
- **功能**: 仅更新部门状态（启用/停用）
- **方法**: PATCH
- **路径**: `/api/cpq/departments/{id}`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 部门 ID |

- **请求体**: DepartmentDTO（仅取 status 字段）
- **响应内容**: `ApiResponse<DepartmentDTO>`

---

### 1.8 OperationLogResource（操作日志查询）

类级 @Path：`/api/cpq/operation-logs`；类级 @RoleAllowed [SALES_MANAGER, SYSTEM_ADMIN]。Produces/Consumes 均为 `application/json`。

#### 操作日志列表
- **功能**: 按条件分页查询操作日志（按创建时间倒序）
- **方法**: GET
- **路径**: `/api/cpq/operation-logs`
- **鉴权**: 需登录 + 角色 [SALES_MANAGER, SYSTEM_ADMIN]
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| operationType | string | 否 | 操作类型过滤 |
| targetType | string | 否 | 目标类型过滤 |
| startDate | string | 否 | 起始日期（yyyy-MM-dd，含当日） |
| endDate | string | 否 | 结束日期（yyyy-MM-dd，查询 < 次日零点） |
| page | int | 否 | 0 基页码（默认 0） |
| size | int | 否 | 页大小（默认 20） |

- **响应内容**: `ApiResponse<PageResult<OperationLog>>`，元素（OperationLog）字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 日志 ID |
| operatorId | UUID | 操作人 ID |
| operationType | string | 操作类型 |
| targetType | string | 目标类型 |
| targetId | UUID | 目标 ID |
| summary | string | 操作摘要 |
| createdAt | datetime | 创建时间 |

---

### 1.9 RegionResource（区域管理）

类级 @Path：`/api/cpq/regions`；类级 @RoleAllowed [SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]。Produces/Consumes 均为 `application/json`。

#### 区域列表
- **功能**: 分页查询区域列表
- **方法**: GET
- **路径**: `/api/cpq/regions`
- **鉴权**: 需登录 + 角色 [SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 0 基页码（默认 0） |
| size | int | 否 | 页大小（默认 50） |

- **响应内容**: `ApiResponse<PageResult<RegionDTO>>`

**RegionDTO 字段**:

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 区域 ID |
| code | string | 区域编码（必填） |
| name | string | 区域名称（必填） |
| sortOrder | int | 排序序号 |
| status | string | 状态 |
| createdAt | datetime | 创建时间 |

#### 新建区域
- **功能**: 创建区域
- **方法**: POST
- **路径**: `/api/cpq/regions`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **请求体**: RegionDTO（code、name 必填）
- **响应内容**: `ApiResponse<RegionDTO>`

#### 更新区域
- **功能**: 更新区域信息
- **方法**: PUT
- **路径**: `/api/cpq/regions/{id}`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 区域 ID |

- **请求体**: RegionDTO
- **响应内容**: `ApiResponse<RegionDTO>`

#### 更新区域状态
- **功能**: 仅更新区域状态
- **方法**: PATCH
- **路径**: `/api/cpq/regions/{id}`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 区域 ID |

- **请求体**: RegionDTO（仅取 status 字段）
- **响应内容**: `ApiResponse<RegionDTO>`

---

### 1.10 UserResource（用户管理）

类级 @Path：`/api/cpq/users`；类级 @RoleAllowed [SYSTEM_ADMIN]。Produces/Consumes 均为 `application/json`。

#### 用户列表
- **功能**: 按条件分页查询用户
- **方法**: GET
- **路径**: `/api/cpq/users`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 0 基页码（默认 0） |
| size | int | 否 | 页大小（默认 50） |
| role | string | 否 | 角色过滤 |
| status | string | 否 | 状态过滤 |
| keyword | string | 否 | 关键字（用户名/姓名/邮箱） |

- **响应内容**: `ApiResponse<PageResult<UserDTO>>`

**UserDTO 字段**:

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 用户 ID |
| username | string | 用户名 |
| fullName | string | 姓名 |
| email | string | 邮箱 |
| role | string | 角色 |
| regionId | UUID | 区域 ID |
| departmentId | UUID | 部门 ID |
| status | string | 状态 |
| isFirstLogin | boolean | 是否首次登录 |
| createdAt | datetime | 创建时间 |
| updatedAt | datetime | 更新时间 |
| initialPassword | string | 初始密码（仅创建响应返回） |

#### 新建用户
- **功能**: 创建用户（返回初始密码）
- **方法**: POST
- **路径**: `/api/cpq/users`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **请求头**: `Content-Type: application/json`
- **请求体**（CreateUserRequest）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | string | 是 | 用户名（不可空白） |
| fullName | string | 是 | 姓名（不可空白） |
| email | string | 是 | 邮箱（合法邮箱格式） |
| role | string | 是 | 角色，须为 SALES_REP/SALES_MANAGER/PRICING_MANAGER/SYSTEM_ADMIN |
| regionId | UUID | 否 | 区域 ID |
| departmentId | UUID | 否 | 部门 ID |

- **响应内容**: `ApiResponse<UserDTO>`（含 initialPassword）

#### 更新用户
- **功能**: 更新用户信息
- **方法**: PUT
- **路径**: `/api/cpq/users/{id}`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 用户 ID |

- **请求体**（UpdateUserRequest）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| fullName | string | 否 | 姓名 |
| email | string | 否 | 邮箱 |
| role | string | 否 | 角色 |
| regionId | UUID | 否 | 区域 ID |
| departmentId | UUID | 否 | 部门 ID |
| status | string | 否 | 状态 |

- **响应内容**: `ApiResponse<UserDTO>`

#### 更新用户状态
- **功能**: 仅更新用户状态（启用/停用）
- **方法**: PATCH
- **路径**: `/api/cpq/users/{id}`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 用户 ID |

- **请求体**: UpdateUserRequest（仅取 status 字段）
- **响应内容**: `ApiResponse<UserDTO>`

#### 重置用户密码
- **功能**: 重置指定用户密码，返回新初始密码
- **方法**: POST
- **路径**: `/api/cpq/users/{id}/reset-password`
- **鉴权**: 需登录 + 角色 [SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 用户 ID |

- **响应内容**: `ApiResponse<Map<String,String>>`，data 字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| initialPassword | string | 重置后的初始密码 |

---

### 1.11 NotificationResource（消息通知）

类级 @Path：`/api/cpq/notifications`；类级 @RoleAllowed [SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]。Produces/Consumes 均为 `application/json`。

#### 通知列表
- **功能**: 分页查询当前用户的通知
- **方法**: GET
- **路径**: `/api/cpq/notifications`
- **鉴权**: 需登录 + 角色 [SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 0 基页码（默认 0） |
| size | int | 否 | 页大小（默认 20） |

- **响应内容**: `ApiResponse<List<Notification>>`，元素（Notification）字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 通知 ID |
| recipientId | UUID | 接收人 ID |
| type | string | 通知类型 |
| title | string | 标题 |
| content | string | 正文 |
| link | string | 跳转链接 |
| relatedType | string | 关联对象类型 |
| relatedId | UUID | 关联对象 ID |
| isRead | boolean | 是否已读 |
| readAt | datetime | 已读时间 |
| createdAt | datetime | 创建时间 |

#### 未读数量
- **功能**: 查询当前用户未读通知数
- **方法**: GET
- **路径**: `/api/cpq/notifications/unread-count`
- **鉴权**: 需登录 + 上述角色
- **响应内容**: `ApiResponse<Map<String,Long>>`，data 字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| count | long | 未读数量 |

#### 标记单条已读
- **功能**: 将指定通知标记为已读
- **方法**: PUT
- **路径**: `/api/cpq/notifications/{id}/read`
- **鉴权**: 需登录 + 上述角色
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 通知 ID |

- **响应内容**: `ApiResponse<Void>`

#### 标记单条已读（别名）
- **功能**: 同上，API.md 别名端点
- **方法**: POST
- **路径**: `/api/cpq/notifications/{id}/mark-read`
- **鉴权**: 需登录 + 上述角色
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 通知 ID |

- **响应内容**: `ApiResponse<Void>`

#### 全部标记已读
- **功能**: 将当前用户全部通知标记已读
- **方法**: PUT
- **路径**: `/api/cpq/notifications/read-all`
- **鉴权**: 需登录 + 上述角色
- **响应内容**: `ApiResponse<Void>`

#### 全部标记已读（别名）
- **功能**: 同上，API.md 别名端点
- **方法**: POST
- **路径**: `/api/cpq/notifications/mark-all-read`
- **鉴权**: 需登录 + 上述角色
- **响应内容**: `ApiResponse<Void>`

---

### 1.12 ChangeLogResource（基础数据变更日志）

类级 @Path：`/api/cpq/change-log`；类级 @RoleAllowed [SALES_REP, SALES_MANAGER, SYSTEM_ADMIN]。Produces `application/json`。

#### 变更日志搜索
- **功能**: 分页搜索基础数据变更日志
- **方法**: GET
- **路径**: `/api/cpq/change-log/search`
- **鉴权**: 需登录 + 角色 [SALES_REP, SALES_MANAGER, SYSTEM_ADMIN]
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | string(UUID) | 否 | 客户 UUID |
| hfPartNo | string | 否 | HF 料号 |
| tableName | string | 否 | 表名过滤 |
| fieldName | string | 否 | 字段名过滤 |
| changedAtFrom | string | 否 | 起始时间（ISO-8601，含） |
| changedAtTo | string | 否 | 结束时间（ISO-8601，含） |
| importance | string | 否 | 重要度：CRITICAL/IMPORTANT/NORMAL |
| changeSource | string | 否 | 变更来源：V5_IMPORT/MANUAL_EDIT/SYSTEM_INIT/SYNC |
| page | int | 否 | 0 基页码（默认 0，不可为负） |
| size | int | 否 | 页大小，范围 [1,200]（默认 50） |

- **响应内容**: `ApiResponse<PageResult<ChangeLogEntryDTO>>`，元素字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 日志 ID |
| tableName | string | 表名 |
| recordId | UUID | 记录 ID |
| customerId | UUID | 客户 ID |
| hfPartNo | string | HF 料号 |
| fieldName | string | 字段名 |
| oldValue | string | 旧值 |
| newValue | string | 新值 |
| importance | string | 重要度 |
| affectsCalculation | boolean | 是否影响计算 |
| changeSource | string | 变更来源 |
| note | string | 备注 |
| changedAt | string | 变更时间 |
| changedBy | UUID | 变更人 ID |
| changedByName | string | 变更人姓名 |
| importRecordId | UUID | 导入记录 ID |

- **说明**: size 超出 [1,200] 或 page<0 返回 400（BusinessException）。

#### 变更日志导出
- **功能**: 流式导出变更日志（CSV/XLSX）
- **方法**: GET
- **路径**: `/api/cpq/change-log/export`
- **鉴权**: 需登录 + 角色 [SALES_REP, SALES_MANAGER, SYSTEM_ADMIN]
- **查询参数**: 与 /search 相同的过滤参数（customerId/hfPartNo/tableName/fieldName/changedAtFrom/changedAtTo/importance/changeSource），另加：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| format | string | 否 | 导出格式 csv 或 xlsx（默认 csv） |

- **响应内容**: 二进制文件流（非 ApiResponse）。响应头 `Content-Disposition: attachment; filename="change_log.csv|xlsx"`；csv → `text/csv;charset=UTF-8`，xlsx → `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`。
## 二、组件管理

> **全局基准**
> - 基址：`http://localhost:8081`
> - 鉴权：会话 Cookie；带 `@RoleAllowed` 注解的端点需登录且角色匹配，请求头须携带登录 Cookie；无注解端点不校验。
> - 统一响应体 `ApiResponse<T>` = `{ code:int, message:string, data:T }`；部分端点直返实体或 `Response`（下载流），据实标注。
> - **核复说明**：本章所有字段均已逐字对照真实 `.java` 源码；DTO 均为纯 public 字段类（无 getter/setter）。**全模块请求体 DTO 均未使用 `@NotNull/@NotBlank/@Valid` 等 Bean Validation 注解**——「必填」列据 javadoc 语义 / 实体 `nullable=false` / Service 校验推断标注，运行期 REST 层不做强制拦截（多为 Service 内部 NPE 或 dry-run 校验兜底）。

---

### 2.1 ComponentDirectoryResource（组件目录树 + 组件导入导出）

类级 `@Path`: `/api/cpq/component-directories`
类级鉴权：`@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})`（本类所有端点均需登录且角色匹配）

#### 导出目录组件包（P1）
- **功能**: 把该目录**直属**组件（含 fields/formulas/dataDriverPath/excelColumns/component_sql_view + 依赖清单）打包为 JSON bundle 下载，纯只读。
- **方法**: GET
- **路径**: `/api/cpq/component-directories/{id}/export`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 目录 ID |

- **响应内容**: 直返 `ComponentExportBundle` 实体（非 ApiResponse 包裹），并附响应头 `Content-Disposition: attachment; filename="components-{id}.json"` 触发浏览器下载。

`ComponentExportBundle` 字段（真实源码核对）：

| 字段 | 类型 | 说明 |
|------|------|------|
| bundleVersion | String | bundle 格式版本号，默认 `"1.0"`，导入端据此判兼容性 |
| exportedAt | String | 导出时间（ISO-8601 字符串，非 Instant） |
| source | Source | 来源目录信息（仅供追溯，导入时不依赖） |
| components | List\<Item\> | 该目录直属组件条目（本期不递归子目录） |
| dependencies | Dependencies | 依赖清单：组件引用但不随 bundle 走的外部对象，供导入端校验存在性 |
| checksum | String | 内容校验和（sha256，基于 source+components+dependencies 规范 JSON），防损坏/篡改 |

`ComponentExportBundle.Source`：

| 字段 | 类型 | 说明 |
|------|------|------|
| directoryId | String | 来源目录 ID |
| directoryName | String | 来源目录名称 |

`ComponentExportBundle.Item`（逐条组件）：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 原组件 id（UUID 字符串），供导入端重映射跨组件引用；老 bundle 无此字段则为 null |
| code | String | 组件 code |
| name | String | 组件名称 |
| componentType | String | 组件类型 |
| columnCount | Integer | 列数 |
| status | String | 状态 |
| dataDriverPath | String | 数据驱动路径 |
| fields | JsonNode | 字段定义（原 JSONB，内嵌真实 JSON 节点） |
| formulas | JsonNode | 公式定义（原 JSONB） |
| excelColumns | JsonNode | EXCEL 组件列定义（原 JSONB） |
| sqlViews | List\<SqlView\> | 组件 SQL 视图（component_sql_view，随组件走） |

`ComponentExportBundle.SqlView`：

| 字段 | 类型 | 说明 |
|------|------|------|
| sqlViewName | String | 视图逻辑名 |
| sqlTemplate | String | SQL 模板 |
| declaredColumns | JsonNode | 声明列签名（原 JSONB） |
| requiredVariables | List\<String\> | 占位符变量清单 |
| scope | String | 作用域 COMPONENT / GLOBAL |
| description | String | 描述 |

`ComponentExportBundle.Dependencies`：

| 字段 | 类型 | 说明 |
|------|------|------|
| globalVariables | List\<String\> | 引用到的全局变量 code（GLOBAL_VARIABLE 绑定） |
| datasources | List\<String\> | 引用到的数据源 code（DATABASE_QUERY / HTTP_API 绑定） |

#### 导入预览（P2, dry-run 不写库）
- **功能**: 校验依赖存在性 + 按冲突策略生成每组件动作计划。该端点恒为预览（不写库），实际写入走 `/import/commit`。
- **方法**: POST
- **路径**: `/api/cpq/component-directories/{id}/import`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 目标目录 ID |

- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| conflictPolicy | String | 否 | 冲突策略，`@DefaultValue("RENAME")` |

- **请求体**: `ComponentExportBundle`（导出的 bundle JSON，字段同上「导出」小节）
- **响应内容**: `ApiResponse<ImportPreviewResult>`

`ImportPreviewResult` 字段（真实源码核对，非动态结构）：

| 字段 | 类型 | 说明 |
|------|------|------|
| bundleVersion | String | bundle 版本号 |
| checksumValid | boolean | bundle.checksum 与重算值是否一致（false=可能被改动/损坏，警告但不一定阻止） |
| targetDirectoryId | String | 目标目录 ID |
| targetDirectoryName | String | 目标目录名称 |
| conflictPolicy | String | 实际采用的冲突策略：RENAME / SKIP / ABORT |
| summary | Summary | 汇总计数 |
| components | List\<ComponentPlan\> | 每组件动作计划 |
| dependencies | DependencyCheck | 依赖存在性校验结果 |
| canCommit | boolean | 是否允许提交（P3）；缺依赖或 ABORT 策略下有冲突 → false |
| blockers | List\<String\> | 阻止提交的原因（人类可读） |

`ImportPreviewResult.Summary`：`total`(int) / `toCreate`(int) / `toRename`(int) / `toSkip`(int) / `conflicts`(int)。

`ImportPreviewResult.ComponentPlan`：

| 字段 | 类型 | 说明 |
|------|------|------|
| code | String | 组件 code |
| name | String | 组件名称 |
| action | String | CREATE / RENAME / SKIP |
| newCode | String | RENAME 时的新 code（加后缀） |
| conflict | boolean | 与现有组件 code 冲突 |
| sqlViewCount | int | 随组件的 SQL 视图数 |

`ImportPreviewResult.DependencyCheck`：`globalVariables`(List\<DepItem\>) / `datasources`(List\<DepItem\>) / `missingCount`(int)。
`ImportPreviewResult.DepItem`：`code`(String) / `exists`(boolean)。

#### 导入提交（P3, 单事务写库）
- **功能**: 单事务按计划 INSERT 新组件 + 其 component_sql_view（全新 UUID），不动任何现有数据、不绑定模板。依赖缺失默认阻止。
- **方法**: POST
- **路径**: `/api/cpq/component-directories/{id}/import/commit`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 目标目录 ID |

- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| conflictPolicy | String | 否 | 冲突策略，`@DefaultValue("RENAME")` |
| ignoreMissingDeps | boolean | 否 | `@DefaultValue("false")`；为 `true` 时显式忽略缺失依赖强制写入 |

- **请求体**: `ComponentExportBundle`
- **响应内容**: `ApiResponse<ImportCommitResult>`

`ImportCommitResult` 字段（真实源码核对，非动态结构）：

| 字段 | 类型 | 说明 |
|------|------|------|
| targetDirectoryId | String | 目标目录 ID |
| targetDirectoryName | String | 目标目录名称 |
| conflictPolicy | String | 冲突策略 |
| createdCount | int | 实际新建组件数 |
| skippedCount | int | 被跳过组件数 |
| sqlViewsCreated | int | 生成的 SQL 视图总数 |
| created | List\<CreatedItem\> | 新建明细 |
| skipped | List\<String\> | 被跳过的原始 code（SKIP 策略下冲突项） |

`ImportCommitResult.CreatedItem`：

| 字段 | 类型 | 说明 |
|------|------|------|
| originalCode | String | bundle 里的原始 code |
| finalCode | String | 实际落库的 code（重命名时与 original 不同） |
| componentId | String | 新建组件的 id |
| renamed | boolean | 是否被重命名 |
| sqlViewCount | int | 该组件随建的 SQL 视图数 |

#### 目录树查询
- **功能**: 返回组件目录树（可按关键字过滤）。
- **方法**: GET
- **路径**: `/api/cpq/component-directories`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | String | 否 | 名称模糊匹配关键字 |

- **响应内容**: `ApiResponse<List<ComponentDirectoryDTO>>`

`ComponentDirectoryDTO` 字段（真实源码核对）：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 目录 ID |
| parentId | UUID | 父目录 ID（顶级为 null） |
| name | String | 目录名称 |
| sortOrder | Integer | 同级排序序号 |
| createdAt | OffsetDateTime | 创建时间 |
| children | List\<ComponentDirectoryDTO\> | 子目录（递归树结构），默认空数组 |
| components | List\<ComponentDTO\> | 该目录直属组件（DTO 见 2.2），默认空数组 |

> 更正：草稿中的 `componentCount` 字段**不存在**；目录含 `children` + `components` 两个子集合，无独立计数字段。

#### 新建目录
- **功能**: 新建组件目录。
- **方法**: POST
- **路径**: `/api/cpq/component-directories`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **请求体**: `CreateComponentDirectoryRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | 是（据命名推断，无校验注解） | 目录名称 |
| parentId | UUID | 否 | 父目录 ID（顶级留空） |
| sortOrder | Integer | 否 | 同级排序序号 |

- **响应内容**: `ApiResponse<ComponentDirectoryDTO>`（新建后的目录，字段同上）

#### 更新目录
- **功能**: 更新目录名称与排序（不改父级）。Resource 仅取 `request.name` 与 `request.sortOrder` 传给 Service，`parentId` 被忽略。
- **方法**: PUT
- **路径**: `/api/cpq/component-directories/{id}`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 目录 ID |

- **请求体**: `CreateComponentDirectoryRequest`（仅用 name 与 sortOrder，parentId 忽略）
- **响应内容**: `ApiResponse<ComponentDirectoryDTO>`

#### 删除目录
- **功能**: 删除指定目录。
- **方法**: DELETE
- **路径**: `/api/cpq/component-directories/{id}`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 目录 ID |

- **响应内容**: `ApiResponse<Void>`（data 为空）

---

### 2.2 ComponentResource（组件 CRUD + 行驱动展开）

类级 `@Path`: `/api/cpq/components`
类级鉴权：`@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})`（未单独标注的端点沿用此角色集）

#### 组件列表
- **功能**: 列出组件，可按目录 + 关键字过滤。
- **方法**: GET
- **路径**: `/api/cpq/components`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| directoryId | UUID | 否 | 按目录过滤 |
| keyword | String | 否 | 名称/编码模糊匹配（据命名推断） |

- **响应内容**: `ApiResponse<List<ComponentDTO>>`（字段见下方 ComponentDTO）

#### 组件详情
- **功能**: 按 ID 获取组件完整定义。
- **方法**: GET
- **路径**: `/api/cpq/components/{id}`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |

- **响应内容**: `ApiResponse<ComponentDTO>`

`ComponentDTO` 字段（真实源码核对，与草稿差异较大）：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |
| directoryId | UUID | 所属目录 ID |
| name | String | 组件名称（如 投料/回料/加工） |
| code | String | 组件编码 |
| columnCount | Integer | 列数（据命名推断） |
| fields | List\<Map\<String,Object\>\> | 字段定义列表（JSONB 解析）：每项含 name/field_type/default_source(含 path)/config 等，定义列结构、字段类型（固定值/数据源/输入/公式）与取数路径 |
| formulas | List\<Map\<String,Object\>\> | 公式配置（JSONB 解析）：含计算列表达式、cross_tab_ref 跨页签引用、component_subtotal 页签合计等 |
| componentType | String | 组件类型（如 EXCEL 等，据命名推断枚举） |
| dataDriverPath | String | Y1.5 行驱动路径（可选，`$<sql_view_name>` 唯一真源，决定行驱动展开行数） |
| rowKeyFields | List\<String\> | 行键字段（组件级，草稿重刷按此对齐编辑值）；entity 存 JSON 字符串，DTO 解析为 List；null/空 → null |
| treeConfig | Map\<String,Object\> | 树表配置（纯展示）；entity 存 JSON 字符串，DTO 透传为 Map（null=非树表） |
| bomRecursiveExpand | Boolean | 核价 BOM 递归展开开关（默认 false 兜底，仅核价侧生效；与 treeConfig 正交） |
| status | String | 状态（ACTIVE/INACTIVE，据命名推断枚举值） |
| excelColumns | String | EXCEL 类型组件的列配置 JSON（数组，原始字符串透传） |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

> 更正：草稿中的 `tabName`、`sortOrder`、`componentSqlView`（内嵌视图 DTO）三字段在真实 `ComponentDTO` 中**均不存在**；`rowKeyFields` 是 `List<String>` 而非 JSONB 结构；另有草稿遗漏的 `columnCount / componentType / treeConfig / bomRecursiveExpand / excelColumns / createdAt / updatedAt`。

#### 新建组件
- **功能**: 新建组件。
- **方法**: POST
- **路径**: `/api/cpq/components`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **请求体**: `CreateComponentRequest`（无任何校验注解，必填据 javadoc/Service 推断）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | 是（据命名推断） | 组件名称 |
| code | String | 否 | 组件编码（省略时后端生成，据命名推断） |
| directoryId | UUID | 是（据命名推断） | 所属目录 ID |
| componentType | String | 否 | 组件类型 |
| dataDriverPath | String | 否 | Y1.5 行驱动 BNF 路径 |
| status | String | 否 | 状态 |
| fields | List\<Map\<String,Object\>\> | 否 | 字段定义列表（结构同 ComponentDTO.fields） |
| formulas | List\<Map\<String,Object\>\> | 否 | 公式配置（结构同 ComponentDTO.formulas） |
| rowKeyFields | List\<String\> | 否 | 行键配置：fields[].name 中存在的名称，如 `["子件","元素"]` 或哨兵 `["__seq_no__"]`；null=未配置（新建需要时 Service 硬拦，更新仅告警） |
| treeConfig | Map\<String,Object\> | 否 | 树表配置：`{idField, parentField, defaultExpanded}` |
| bomRecursiveExpand | Boolean | 否 | 核价 BOM 递归展开开关（默认 true，勾选才按 material_bom_item 闭包递归展开） |
| excelColumns | String | 否 | EXCEL 类型组件列配置 JSON（数组） |

- **响应内容**: `ApiResponse<ComponentDTO>`

#### 更新组件
- **功能**: 更新组件；更新后自动同步引用该组件的模板 snapshot（Service 内部）。
- **方法**: PUT
- **路径**: `/api/cpq/components/{id}`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |

- **请求体**: `CreateComponentRequest`（字段同「新建组件」）
- **响应内容**: `ApiResponse<ComponentDTO>`

#### 设置/清空驱动视图
- **功能**: 设置或清空组件的驱动视图（`data_driver_path` 唯一真源），sqlViewName 为 null/空表示取消驱动。
- **方法**: PUT
- **路径**: `/api/cpq/components/{id}/driver-view`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |

- **请求体**: `SetDriverViewRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sqlViewName | String | 否 | 本组件 SQL 视图名（不含 `$` 前缀）；null 或空串 = 取消驱动 |

- **响应内容**: `ApiResponse<ComponentDTO>`

#### 切换启停用状态
- **功能**: 在 ACTIVE / INACTIVE 间切换组件状态（据命名推断）。
- **方法**: PATCH
- **路径**: `/api/cpq/components/{id}/toggle-status`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |

- **响应内容**: `ApiResponse<ComponentDTO>`（切换后的组件）

#### 删除组件
- **功能**: 删除指定组件。
- **方法**: DELETE
- **路径**: `/api/cpq/components/{id}`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |

- **响应内容**: `ApiResponse<Void>`

#### 审计 BASIC_DATA 路径（C1，只读）
- **功能**: 全库 BASIC_DATA path↔视图列名一致性审计（只读不改数据）。遍历所有组件的 `fields[].default_source.path`，检出 `$view.col` 中 col 与该组件 `component_sql_view.declared_columns` 不一致的可疑项，并给下划线前缀差异修正建议。
- **方法**: GET
- **路径**: `/api/cpq/components/audit-basicdata-paths`
- **鉴权**: 需登录+角色[SYSTEM_ADMIN, PRICING_MANAGER]（端点级 `@RoleAllowed` 覆盖类级）
- **响应内容**: `ApiResponse<List<Map<String,Object>>>` —— **动态结构，无固定 DTO**。每项为一条可疑路径记录（Service 层组装的 Map，含组件标识、字段名、当前 path、声明列名、建议修正值等键）；全部正常时返回空列表。

#### 刷新模板快照（H1，手工触发）
- **功能**: 手工同步所有引用该组件的模板 snapshot，用于历史模板（V184 之前发布）修复 / 数据迁移补偿 / 管理脚本。组件 update 已自动调用同款逻辑。
- **方法**: POST
- **路径**: `/api/cpq/components/{id}/refresh-template-snapshots`
- **鉴权**: 需登录+角色[PRICING_MANAGER, SYSTEM_ADMIN]（端点级覆盖）
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |

- **响应内容**: `ApiResponse<List<UUID>>`（受影响的 template id 列表）

#### 行驱动展开（Y1.5）
- **功能**: 按组件 dataDriverPath 取 N 行，每行隐式 JOIN 求值所有 BASIC_DATA 字段。无 dataDriverPath 则返回 rowCount=0（前端按单行渲染兜底）。
- **方法**: POST
- **路径**: `/api/cpq/components/{id}/expand-driver`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |

- **请求体**: `ExpandDriverRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | UUID | 否 | 客户 ID（视图谓词维度之一） |
| partNo | String | 否 | 料号（视图谓词维度之一） |
| partVersion | Integer | 否 | 料号版本；传入后 ImplicitJoinRewriter 注入 `AND part_version=N` 谓词，null=不注入 |
| debugSql | boolean | 否 | 为 true 时响应回传 driver 改写后最终 SQL（debugSql 字段），默认 false |

- **响应内容**: `ApiResponse<ExpandDriverResponse>`

`ExpandDriverResponse` 字段（真实源码核对，rows 为嵌套 Row 而非扁平 Map）：

| 字段 | 类型 | 说明 |
|------|------|------|
| rowCount | int | 驱动展开的行数（driver 权威行数） |
| driverPath | String | 实际生效的驱动路径（如 `mat_bom[...]`；快照读命中时为 `snapshot`） |
| rows | List\<Row\> | 逐行结果（结构见下） |
| debugSql | String | 仅 debugSql=true 时回填，driver 改写后含 `?` 占位符+参数注释的最终 SQL；默认 null |

`ExpandDriverResponse.Row`：

| 字段 | 类型 | 说明 |
|------|------|------|
| driverRow | Map\<String,Object\> | driver 路径返回的整行（客户端记 K-V） |
| basicDataValues | Map\<String,Object\> | key=字段原始路径（含花括号），value=求值结果（可能为 null / 原值 / FormulaError 字符串） |

> 更正：草稿把 `rows` 写成 `List<Map<String,Object>>`（扁平字段→值），真实为 `List<Row>`，每行分 `driverRow` + `basicDataValues` 两个子 Map。

#### 行键候选
- **功能**: 根据 driver `$视图` 的 declaredColumns 返回每个字段是否可作行键。
- **方法**: POST
- **路径**: `/api/cpq/components/{id}/row-key-candidates`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |

- **请求体**: `RowKeyCandidatesRequest`（用前端当前编辑态，支持未保存）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| dataDriverPath | String | 否 | 组件当前的 driver 路径（编辑态，覆盖组件已存值） |
| fields | List\<Map\<String,Object\>\> | 否 | 当前字段列表（loose：至少含 name / field_type / basic_data_path） |

- **响应内容**: `ApiResponse<RowKeyCandidatesResponse>`

`RowKeyCandidatesResponse` 字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| candidates | List\<Candidate\> | 各字段的行键候选评估 |

`RowKeyCandidatesResponse.Candidate`（真实源码核对）：

| 字段 | 类型 | 说明 |
|------|------|------|
| fieldName | String | 字段名（前端按此匹配勾选框所属字段） |
| displayName | String | 字段显示名（当前与 fieldName 同；预留 label 区分） |
| resolvedColumn | String | 反查出的 driver 真实列名（leaf）；不可解析时为 null |
| eligible | boolean | 是否可作行键（true 才允许勾选） |
| reason | String | 不可勾选原因（eligible=false 时给前端 hover 提示）；可勾选时为 null |
| source | String | 行键来源：`driver`（取自 driver 列）/ `input`（取自手填输入字段）；eligible=false 时为 null |

#### 批量行驱动展开
- **功能**: 一次 HTTP 请求服务多个 `(componentId, customerId, partNo)` 组合。每 task 独立处理、单个失败不影响其他；内部自动 snapshot 预取 + 合桶 expandMulti 优化。单次上限 **5000** 个 task（超限抛 `BusinessException(400)`；注意 DTO javadoc 仍写「100」为过时注释）。
- **方法**: POST
- **路径**: `/api/cpq/components/batch-expand`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **请求体**: `BatchExpandDriverRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| tasks | List\<Task\> | 是（据语义；null 时返回空 results，不报错） | 展开任务列表（上限 5000） |
| debugSql | boolean | 否 | 为 true 时各 task 结果回填执行 SQL（data.debugSql），默认 false |

`BatchExpandDriverRequest.Task` 字段（真实源码核对）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| componentId | UUID | 是（javadoc 标必填，无注解） | 组件 ID |
| customerId | UUID | 否 | 客户 ID |
| partNo | String | 否 | 料号 |
| partVersion | Integer | 否 | 料号版本；传入后注入 `AND part_version=N`，null=不注入 |
| overrideDataDriverPath | String | 否 | V195 hotfix：非空则 expand 用此 driver path 覆盖 component.dataDriverPath（组合产品模板 snapshot 场景） |
| overrideFieldsJson | String | 否 | V195 hotfix：非空则按此 fields JSON 收集 BASIC_DATA paths 覆盖 component 表 |
| lineItemId | UUID | 否 | 报价行 UUID；非空时版本化表优先返回 quotation_line_item_id=lineItemId 的行，fallback 到 IS NULL 主数据 |
| compositeType | String | 否 | lineItem 类型：`COMPOSITE`（父级聚合视图，跳过 lineItemId 注入）/ `SIMPLE` 或 null（注入 lineItemId 限定专属工序行） |
| childLineItemIds | List\<UUID\> | 否 | COMPOSITE 父级的子件 lineItem UUID 列表；注入 `quotation_line_item_id IN (...) OR IS NULL` 谓词，null/空=不注入 |
| quotationId | UUID | 否 | 报价单 id；入口绑到 ThreadLocal QuotationIdContext，视图经 `:quotationId` 占位符使用；空可兼容旧前端 |

- **响应内容**: `ApiResponse<BatchExpandDriverResponse>`

`BatchExpandDriverResponse` 字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| results | List\<Result\> | 与 tasks 同序（按 index 配对）的结果列表 |

`BatchExpandDriverResponse.Result` 字段（真实源码核对）：

| 字段 | 类型 | 说明 |
|------|------|------|
| key | String | 结果 key，格式 `componentId:customerId:partNo`（含 partVersion；null 用 `_` 占位），与前端缓存 key 一致 |
| status | String | `OK` / `ERROR` |
| data | ExpandDriverResponse | status=OK 时的展开结果（字段见上方 ExpandDriverResponse） |
| error | String | status=ERROR 时的错误信息 |
| debugSql | String | debugSql=true 时填充；放在 Result 顶层，即便 status=ERROR（data=null）也能看到失败的那条 SQL |

#### 目录级导入引用补救（G4）
- **功能**: 扫描指定目录内所有组件的 formulas，将仍指向目录外源组件的跨组件引用重映射为同目录内对应副本（按 base code 匹配）。同 base 多副本（__imp1/__imp2）时按 code 升序取第一个；无法解析记为 unresolved 并跳过。
- **方法**: POST
- **路径**: `/api/cpq/components/directories/{dirId}/remap-imported-refs`
- **鉴权**: 需登录+角色[SYSTEM_ADMIN]（端点级覆盖）
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| dirId | UUID | 目标目录 ID |

- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| dryRun | boolean | 否 | `@DefaultValue("true")`（只返回将重映射清单不改库）；`false` = 实际写库 |

- **响应内容**: `ApiResponse<ComponentImportService.DirRemapResult>`

`DirRemapResult` 字段（真实源码核对，`ComponentImportService` 内部类）：

| 字段 | 类型 | 说明 |
|------|------|------|
| directoryId | String | 目标目录 ID |
| dryRun | boolean | 本次是否为 dry-run |
| totalComponents | int | 扫描的组件总数 |
| remappedComponents | int | 发生重映射的组件数 |
| unresolvedComponents | int | 含未解析引用的组件数 |
| components | List\<ComponentResult\> | 逐组件明细 |

`DirRemapResult.ComponentResult`：`code`(String) / `remapped`(List\<String\>，每条 `"old → new"` 描述) / `unresolved`(List\<String\>，无副本或组件不存在的引用)。

---

### 2.3 ComponentSqlViewResource（组件级 SQL 视图 CRUD）

类级 `@Path`: `/api/cpq/components/{cid}/sql-views`
类级鉴权：`@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})`

#### SQL 视图列表
- **功能**: 列出本组件全部 SQL 视图。
- **方法**: GET
- **路径**: `/api/cpq/components/{cid}/sql-views`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| cid | UUID | 组件 ID |

- **响应内容**: `ApiResponse<List<ComponentSqlViewDTO>>`

`ComponentSqlViewDTO` 字段（真实源码核对，字段名与草稿差异明显）：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 视图 ID |
| componentId | UUID | 所属组件 ID |
| componentCode | String | 所属组件 code（业务标识符）；跨组件 BNF 引用 `$$<componentCode>.<sql_view_name>` 用此字段，由 Service enrich 传入 |
| sqlViewName | String | 视图逻辑名（`$` 引用时用，如 `$element_view`） |
| sqlTemplate | String | SQL 模板（含 `:customerId`/`:partVersion` 等命名占位符） |
| declaredColumns | List\<Map\<String,Object\>\> | 列签名数组（从 entity raw JSONB 字符串反序列化）；每项形如 `{name, dataType, nullable}` |
| requiredVariables | List\<String\> | 从 sql_template 解析出的 `:xxx` 占位符清单（不含 `:hfPartNos`） |
| scope | String | 作用域：COMPONENT（组件私有）/ GLOBAL（跨组件可引用） |
| status | String | 状态（ACTIVE / INACTIVE 软删除） |
| description | String | 描述 |
| createdBy | UUID | 创建人 ID |
| createdAt | LocalDateTime | 创建时间 |
| updatedAt | LocalDateTime | 更新时间 |

> 更正：草稿的 `placeholders` 字段名有误，真实为 `requiredVariables`；草稿遗漏 `description / createdBy / createdAt / updatedAt`。DTO 无草稿所写的 `componentCode 仅部分场景回填` 之外语义差异——列表端点不回填 componentCode（`from(entity)` 单参，componentCode=null），仅 GlobalSqlViewResource（2.6）会回填。

#### SQL 视图详情
- **功能**: 按 ID 获取单个 SQL 视图。
- **方法**: GET
- **路径**: `/api/cpq/components/{cid}/sql-views/{id}`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| cid | UUID | 组件 ID |
| id | UUID | 视图 ID |

- **响应内容**: `ApiResponse<ComponentSqlViewDTO>`

#### 新建 SQL 视图
- **功能**: 新建 SQL 视图，同时 dry-run 校验，校验失败抛 400。（createdBy 暂未接通 SecurityContext，写 null）
- **方法**: POST
- **路径**: `/api/cpq/components/{cid}/sql-views`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| cid | UUID | 组件 ID |

- **请求体**: `CreateComponentSqlViewRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sqlViewName | String | 是（javadoc 标必填） | BNF 引用名，同组件内唯一（小写字母、下划线、数字） |
| sqlTemplate | String | 是（javadoc 标必填） | 含命名占位符的 SELECT SQL |
| scope | String | 否 | 命名空间：`COMPONENT`（DTO 默认值）或 `GLOBAL` |
| description | String | 否 | 可选描述 |

> 更正：草稿把 `declaredColumns` 列为请求字段有误——请求体**不含** declaredColumns（列签名由后端 dry-run 自动提取），也无 `requiredVariables` 入参。

- **响应内容**: `ApiResponse<ComponentSqlViewDTO>`

#### 更新 SQL 视图
- **功能**: 更新 SQL 视图，同时 dry-run 校验，校验失败抛 400。
- **方法**: PUT
- **路径**: `/api/cpq/components/{cid}/sql-views/{id}`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| cid | UUID | 组件 ID |
| id | UUID | 视图 ID |

- **请求体**: `CreateComponentSqlViewRequest`（字段同「新建」）
- **响应内容**: `ApiResponse<ComponentSqlViewDTO>`

#### 删除 SQL 视图（软删除）
- **功能**: 软删除视图（status → INACTIVE）。
- **方法**: DELETE
- **路径**: `/api/cpq/components/{cid}/sql-views/{id}`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| cid | UUID | 组件 ID |
| id | UUID | 视图 ID |

- **响应内容**: `ApiResponse<Void>`

#### Dry-run 校验
- **功能**: 仅校验 SQL 不落库，返回列签名 + 占位符清单。（Resource 仅取 `body.sqlTemplate` 传给 Service，`cid` 不参与校验）
- **方法**: POST
- **路径**: `/api/cpq/components/{cid}/sql-views/dry-run`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| cid | UUID | 组件 ID（当前实现仅取 body.sqlTemplate 校验，cid 不参与） |

- **请求体**: `DryRunSqlViewRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sqlTemplate | String | 是（据语义） | 待校验的 SQL 模板（含命名占位符） |

- **响应内容**: `ApiResponse<DryRunSqlViewResponse>`

`DryRunSqlViewResponse` 字段（真实源码核对，字段名与草稿不同）：

| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | dry-run 是否通过 |
| declaredColumns | List\<ColumnMeta\> | 提取的列签名（success=true 时返回） |
| requiredVariables | List\<String\> | 从 SQL 解析出的 `:xxx` 占位符列表（不含 `:hfPartNos`） |
| error | String | 错误信息（success=false 时返回，单条字符串） |

`DryRunSqlViewResponse.ColumnMeta`：`name`(String) / `dataType`(String) / `nullable`(boolean)。

> 更正：草稿的 `columns / placeholders / valid / errors(List)` 四字段名均不对；真实为 `declaredColumns / requiredVariables / success / error(单 String)`。

---

### 2.4 ComponentTabJoinResource（组件级 TAB_JOIN_FORMULA 配置支撑）

类级 `@Path`: `/api/cpq/components`
类级鉴权：`@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})`

> 注：本类与 ComponentResource 共用 `/api/cpq/components` 前缀，服务 TabJoinFormulaDrawer（公式编辑器）的「可引用页签矩阵」与试算。本类 4 个端点请求/响应均为动态 `Map` / `List<Map>`，**无固定 DTO**。

#### 页签定义矩阵
- **功能**: 返回同目录组件的页签定义，供公式编辑器「可引用页签矩阵」消费；返回 shape 与模板级 `/templates/{id}/excel-view-config/tab-defs` 一致。
- **方法**: GET
- **路径**: `/api/cpq/components/{id}/tab-defs`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |

- **响应内容**: `ApiResponse<List<Map<String,Object>>>` —— **动态结构，无固定 DTO**。每项为一个页签定义（Service 组装，含 tabName、componentId、字段列表等键，供矩阵渲染可引用列）。

#### 样本卡片列表
- **功能**: 反查引用本组件的报价行（最多 50 条），供抽屉选样本试算。无引用返回空列表（抽屉据此禁用试算，仅允许保存表达式）。
- **方法**: GET
- **路径**: `/api/cpq/components/{id}/sample-cards`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |

- **响应内容**: `ApiResponse<List<Map<String,Object>>>` —— **动态结构，无固定 DTO**。javadoc 声明每项键（据 javadoc，非编译期强约束）：

| 键 | 类型 | 说明 |
|------|------|------|
| quotationId | UUID | 报价单 ID |
| quotationNo | String | 报价单号 |
| lineItemId | UUID | 报价行 ID |
| cardName | String | 卡片名称 |

#### 组件级试算（EXCEL 列）
- **功能**: 给样本 lineItem + TAB_JOIN_FORMULA 列配置返回单值。复用模板级试算内核（ExcelViewService#dryRunTabFormula），上下文来源换成组件。无 lineItemId / 无样本返回 `{value:null, errors:[...]}`（非 500）。
- **方法**: POST
- **路径**: `/api/cpq/components/{id}/dry-run`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |

- **请求体**（`Map<String,Object>`，**无固定 DTO**；Resource 内手工取键并校验）:

| 键 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lineItemId | String(UUID) | 否 | 样本报价行 ID；非空且非法 UUID 格式抛 400，空/缺省视为无样本 |
| column | Object(Map) | 是 | TAB_JOIN_FORMULA 列定义；缺失抛 400「column is required」，非对象抛 400 |
| cardValuesJson | String | 否 | 卡片值 JSON（预置渲染上下文），转字符串使用 |

- **响应内容**: `ApiResponse<Map<String,Object>>` —— **动态结构**。Service 返回 Map，典型键 `value`（试算单值，不可用时 null）/ `errors`（List\<String\> 错误提示）。

#### 组件级 token 试算（NORMAL/SUBTOTAL 连表公式）
- **功能**: 走 token 引擎、复用真实卡片渲染装配，使「试算逐行值 == 渲染逐行值」。无 lineItemId / 无样本 / 内部异常均返回 `{rows:[], errors:[...]}`（非 500）。
- **方法**: POST
- **路径**: `/api/cpq/components/{id}/dry-run-token`
- **鉴权**: 需登录+角色[SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组件 ID |

- **请求体**（`Map<String,Object>`，**无固定 DTO**；body 为 null 时按空 Map 处理）:

| 键 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lineItemId | String(UUID) | 否 | 样本报价行 ID；非空且非法格式抛 400 |
| tokens | Array | 否 | 公式 token 数组（缺省 → 空数组，内部 `valueToTree` 成 JsonNode） |
| selfRowKeyFields | Array\<String\> | 否 | 本页签行键字段（连表逐行匹配维度）；非 List 则忽略为 null |

- **响应内容**: `ApiResponse<Map<String,Object>>` —— **动态结构**。Service 返回 Map，典型键 `rows`（逐行试算结果 List）/ `errors`（List\<String\>）。

---

### 2.5 CostingBomTreeConfigResource（核价树递归 SQL 配置）

类级 `@Path`: `/api/cpq/costing-bom-tree-config`
类级鉴权：`@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})`

> 全局可配置「核价树递归 SQL」配置的 CRUD + 设为生效。递归 SQL 契约：输入具名参数 `:production_part_nos`（text[]），输出列逐字 `root_no / material_no / bom_version / parent_no`。全局同一时刻最多一条生效（DB 部分唯一索引保障）。

#### 配置列表
- **功能**: 列出全部核价树递归 SQL 配置。
- **方法**: GET
- **路径**: `/api/cpq/costing-bom-tree-config`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **响应内容**: `ApiResponse<List<CostingBomTreeConfig>>`（直接序列化实体）

`CostingBomTreeConfig` 实体字段（真实源码核对，JPA 实体）：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 配置 ID（`@GeneratedValue`） |
| name | String | 配置名称（`nullable=false`） |
| sqlTemplate | String | 递归 SQL 模板（`nullable=false`，TEXT，核价 BOM 树展开逻辑） |
| isActive | boolean | 是否当前生效（Java 字段名 `isActive`，DB 列 `is_active`，默认 false；全局唯一一条生效） |
| createdAt | OffsetDateTime | 创建时间（`@PrePersist` 填充） |
| updatedAt | OffsetDateTime | 更新时间（`@PrePersist`/`@PreUpdate` 填充） |

> 更正：草稿写 `active`，真实 Java public 字段名为 `isActive`（Jackson 序列化沿用字段名）。

#### 新建配置
- **功能**: 新建核价树 SQL 配置。
- **方法**: POST
- **路径**: `/api/cpq/costing-bom-tree-config`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **请求体**（`Map<String,String>`，**无固定 DTO**；Resource 取 `name`/`sqlTemplate` 两键）:

| 键 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | 是（实体 nullable=false） | 配置名称 |
| sqlTemplate | String | 是（实体 nullable=false） | 递归 SQL 模板 |

- **响应内容**: `ApiResponse<CostingBomTreeConfig>`

#### 更新配置
- **功能**: 更新核价树 SQL 配置。
- **方法**: PUT
- **路径**: `/api/cpq/costing-bom-tree-config/{id}`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 配置 ID |

- **请求体**（`Map<String,String>`，**无固定 DTO**）: 同「新建配置」（`name` / `sqlTemplate`）
- **响应内容**: `ApiResponse<CostingBomTreeConfig>`

#### 设为生效
- **功能**: 将指定配置设为当前生效（互斥，其余置为非生效）。
- **方法**: POST
- **路径**: `/api/cpq/costing-bom-tree-config/{id}/activate`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 配置 ID |

- **响应内容**: `ApiResponse<Void>`

#### 删除配置
- **功能**: 删除指定核价树 SQL 配置。
- **方法**: DELETE
- **路径**: `/api/cpq/costing-bom-tree-config/{id}`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 配置 ID |

- **响应内容**: `ApiResponse<Void>`

---

### 2.6 GlobalSqlViewResource（跨组件全局 SQL 视图列表）

类级 `@Path`: `/api/cpq/sql-views/global`
类级鉴权：`@RoleAllowed({"SALES_MANAGER", "SYSTEM_ADMIN"})`
（注：类上仅 `@Produces(JSON)`，无 `@Consumes`——本类只有 GET 无请求体）

> 供前端 PathPicker 第 3 Tab 列出可跨引用的 GLOBAL scope 视图。

#### 全局视图列表
- **功能**: 列出所有 scope=GLOBAL 的 SQL 视图（`repository.listAllGlobal()`），并回填各视图所属组件的 code（内部按 componentId batch 查 `lookupComponentCode` 避免 N+1）。
- **方法**: GET
- **路径**: `/api/cpq/sql-views/global`
- **鉴权**: 需登录+角色[SALES_MANAGER, SYSTEM_ADMIN]
- **响应内容**: `ApiResponse<List<ComponentSqlViewDTO>>`（字段见 2.3 ComponentSqlViewDTO；每项 `componentCode` 已回填所属组件编码，经 `ComponentSqlViewDTO.from(row, code)` 双参构造）
## 三、模板管理

> 全局基准：基址 `http://localhost:8081`；鉴权=会话 Cookie（`@RoleAllowed` 端点需登录且角色匹配，请求头带 Cookie；无该注解不校验）；统一响应体 `ApiResponse<T>={code,message,data}`，个别端点直返实体/解析后 JSON，已在各端点标注。

---

### 3.1 TemplateResource（产品卡片模板 CRUD / 发布 / 版本 / 比对 / 管理员迁移）

类级 `@Path`: `/api/cpq/templates`
类级 `@RoleAllowed`: `SALES_REP`, `SALES_MANAGER`, `PRICING_MANAGER`, `SYSTEM_ADMIN`

#### 模板列表（分页 + 过滤）
- **功能**: 分页查询模板列表，支持按分类/客户/品类/状态/关键字/模板类型过滤
- **方法**: GET
- **路径**: `/api/cpq/templates`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |
| category | String | 否 | 分类名 |
| customerId | UUID | 否 | 客户 ID |
| categoryId | UUID | 否 | 产品品类 ID |
| status | String | 否 | 状态（DRAFT / PUBLISHED / ARCHIVED） |
| keyword | String | 否 | 关键字（模板名等） |
| templateKind | String | 否 | 模板类型（QUOTATION 报价 / COSTING 核价），不传返全部 |

- **响应内容**: `ApiResponse<List<TemplateDTO>>`，TemplateDTO 字段见下方【TemplateDTO 结构】

#### 模板详情
- **功能**: 按 ID 获取单个模板详情（含组件列表）
- **方法**: GET
- **路径**: `/api/cpq/templates/{id}`
- **鉴权**: 类级四角色
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **响应内容**: `ApiResponse<TemplateDTO>`

#### 客户报价模板匹配
- **功能**: 按客户+品类匹配报价模板（客户专属优先 → 通用兜底）
- **方法**: GET
- **路径**: `/api/cpq/templates/match-customer-quote`
- **鉴权**: 类级四角色
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | UUID | 否 | 客户 ID |
| categoryId | UUID | 否 | 产品品类 ID |

- **响应内容**: `ApiResponse<TemplateMatchResult>`

| 字段 | 类型 | 说明 |
|------|------|------|
| matchType | String 枚举 | CUSTOMER_SPECIFIC（仅客户专属）/ GENERAL_FALLBACK（仅通用）/ MIXED（两者皆有，已按客户专属优先排序）/ NONE（都没找到） |
| templates | List&lt;TemplateDTO&gt; | 匹配到的模板列表 |

#### 报价导入自动默认值
- **功能**: 为报价导入向导「选模板」步骤计算自动默认值（客户上次使用的报价模板 + 核价模板）
- **方法**: GET
- **路径**: `/api/cpq/templates/auto-defaults`
- **鉴权**: 类级四角色
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | UUID | 否 | 客户 ID |

- **响应内容**: `ApiResponse<QuoteImportAutoDefaults>`

| 字段 | 类型 | 说明 |
|------|------|------|
| categoryId | UUID | 品类 ID |
| categoryName | String | 品类名称 |
| customerTemplateId | UUID | 推荐报价模板 ID |
| customerTemplateSeriesId | UUID | 报价模板系列 ID |
| customerTemplateName | String | 报价模板名称 |
| customerTemplateVersion | String | 报价模板版本 |
| customerTemplateSource | String | 来源：LAST_USED / CUSTOMER_SPECIFIC_FALLBACK / GENERAL_FALLBACK / NONE |
| costingTemplateId | UUID | 推荐核价模板 ID |
| costingTemplateName | String | 核价模板名称 |
| costingTemplateVersion | String | 核价模板版本 |
| costingTemplateSource | String | 来源：CUSTOMER_SPECIFIC / GENERAL / NONE |

#### 新建模板
- **功能**: 创建新模板（默认 DRAFT 状态）
- **方法**: POST
- **路径**: `/api/cpq/templates`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **请求体**: `CreateTemplateRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | 是 | 模板名称 |
| category | String | 否 | 分类 |
| customerId | UUID | 否 | 客户 ID（COSTING 类型可留空=所有客户可用） |
| categoryId | UUID | 否 | 产品品类 ID |
| description | String | 否 | 描述 |
| usageNote | String | 否 | 使用说明 |
| productAttributes | String | 否 | 产品属性（JSON 字符串） |
| subtotalFormula | String | 否 | 小计公式（JSON 字符串） |
| templateKind | String | 否 | 模板类型 QUOTATION / COSTING，缺省 QUOTATION |

- **响应内容**: `ApiResponse<TemplateDTO>`

#### 更新模板
- **功能**: 更新模板（仅 DRAFT 可改）
- **方法**: PUT
- **路径**: `/api/cpq/templates/{id}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **请求体**: `CreateTemplateRequest`（同「新建模板」）
- **响应内容**: `ApiResponse<TemplateDTO>`

#### 删除模板
- **功能**: 删除模板
- **方法**: DELETE
- **路径**: `/api/cpq/templates/{id}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **响应内容**: `ApiResponse<Void>`

#### 发布模板
- **功能**: 发布模板（DRAFT → PUBLISHED），可选升主版本号
- **方法**: POST
- **路径**: `/api/cpq/templates/{id}/publish`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **请求体**: `PublishRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| majorVersion | Integer | 否 | 若设置则升主版本号 |

- **响应内容**: `ApiResponse<TemplateDTO>`

#### 归档模板
- **功能**: 归档模板
- **方法**: POST
- **路径**: `/api/cpq/templates/{id}/archive`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| force | boolean | 否 | 是否强制归档（有引用时），默认 false |

- **响应内容**: `ApiResponse<TemplateDTO>`

#### 基于模板新建草稿
- **功能**: 基于已有模板派生新 DRAFT 版本
- **方法**: POST
- **路径**: `/api/cpq/templates/{id}/new-draft`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 源模板 ID |

- **响应内容**: `ApiResponse<TemplateDTO>`

#### 模板版本历史
- **功能**: 按模板系列 ID 获取版本历史列表
- **方法**: GET
- **路径**: `/api/cpq/templates/series/{seriesId}/versions`
- **鉴权**: 类级四角色
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| seriesId | UUID | 模板系列 ID |

- **响应内容**: `ApiResponse<List<TemplateDTO>>`

#### 模板比对
- **功能**: 比对两个模板的差异（元数据 / 产品属性 / 组件页签 / 统计）
- **方法**: POST
- **路径**: `/api/cpq/templates/compare`
- **鉴权**: 类级四角色
- **请求体**: `CompareTemplatesRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| templateAId | UUID | 是 | 模板 A ID |
| templateBId | UUID | 是 | 模板 B ID |

- **响应内容**: `ApiResponse<TemplateComparisonResult>`

| 字段 | 类型 | 说明 |
|------|------|------|
| templateAId / templateBId | UUID | 两模板 ID |
| templateAName / templateBName | String | 两模板名称 |
| templateAVersion / templateBVersion | String | 两模板版本 |
| metadata | MetadataDiff | 元数据差异，含 name / version / category / description，每项为 FieldChange{valueA, valueB, changed} |
| productAttributes | AttributesDiff | 属性差异，含 added[] / removed[] / modified[]（AttributeChange{fieldName, valueA, valueB}） |
| components | ComponentsDiff | 组件差异，含 addedTabs[] / removedTabs[] / modifiedTabs[]（TabChange{tabName, componentId, fieldChanges[], addedFields[], removedFields[]}） |
| stats | Stats | 统计 {totalDiffs, added, removed, modified, similarityPercent} |

#### 【管理员】迁移到统一智能视图
- **功能**: 一次性数据迁移——将 PUBLISHED 模板每个 tc 的 `basic_data_path_composite` 上升覆盖 `basic_data_path` 并删 `_composite` 键，同步重建 snapshot
- **方法**: POST
- **路径**: `/api/cpq/templates/admin/migrate-to-unified-view`
- **鉴权**: SYSTEM_ADMIN
- **请求体**: `Map<String,Object>`（可选）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| templateIds | List&lt;String(UUID)&gt; | 否 | 目标模板 ID 列表；不传或空=处理全部 PUBLISHED 模板 |

- **响应内容**: `ApiResponse<Map<String,Object>>`，含 `{ totalTemplates, totalTcMigrated, totalFieldsMigrated, details[] }`

#### 【管理员】删除模板指定页签
- **功能**: 一次性数据修复——按 sortOrder 删除 PUBLISHED 模板的某些 Tab（绕过组件管理 UI）
- **方法**: POST
- **路径**: `/api/cpq/templates/admin/{templateId}/delete-tcs`
- **鉴权**: SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |

- **请求体**: `Map<String,Object>`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sortOrders | List&lt;Integer&gt; | 否 | 要删除的 tc 的 sortOrder 列表，缺省为空列表 |

- **响应内容**: `ApiResponse<Map<String,Object>>`，含 `{ deletedTcs, snapshotBefore, snapshotAfter }`

#### 【管理员】override 上升为组件字段
- **功能**: 一次性迁移——将 `template_component.fields_override` 上升为 `component.fields`（单一来源），并清空所有 tc override + 刷新 snapshot
- **方法**: POST
- **路径**: `/api/cpq/templates/admin/promote-override-to-component`
- **鉴权**: SYSTEM_ADMIN
- **请求体**: `Map<String,Object>`（可选）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| componentIds | List&lt;String(UUID)&gt; | 否 | 目标组件 ID 列表；不传或空=默认处理所有名称以"选配-"开头的 ACTIVE 组件 |

- **响应内容**: `ApiResponse<Map<String,Object>>`，含 `{ targetComponents, componentsUpdated, tcCleared, snapshotTouched, details[] }`

**【TemplateDTO 结构】**（多端点复用的模板响应体）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |
| templateSeriesId | UUID | 模板系列 ID |
| name | String | 模板名称 |
| version | String | 版本号 |
| category | String | 分类 |
| customerId | UUID | 客户 ID |
| customerName | String | 客户名（JOIN 回填） |
| categoryId | UUID | 产品品类 ID |
| categoryName | String | 品类名（JOIN 回填） |
| description | String | 描述 |
| usageNote | String | 使用说明 |
| productAttributes | List&lt;Map&gt; | 产品属性（解析后 JSON 数组） |
| subtotalFormula | List&lt;Map&gt; | 小计公式（解析后 JSON 数组） |
| componentsSnapshot | String | 组件快照（JSON 字符串） |
| excelViewConfig | String | Excel 视图配置（JSON 字符串） |
| status | String | 状态 DRAFT / PUBLISHED / ARCHIVED |
| templateKind | String | 模板类型 QUOTATION / COSTING |
| createdBy | UUID | 创建人 |
| publishedAt | OffsetDateTime | 发布时间 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |
| components | List&lt;TemplateComponentDTO&gt; | 组件页签列表（见 3.2） |

---

### 3.2 TemplateComponentResource（模板组件页签管理）

类级 `@Path`: `/api/cpq/templates/{templateId}/components`
类级 `@RoleAllowed`: `SALES_MANAGER`, `SYSTEM_ADMIN`

**【TemplateComponentDTO 结构】**（本小节各端点响应体）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板组件（tc）ID |
| componentId | UUID | 引用的组件 ID |
| tabName | String | 页签名称 |
| sortOrder | Integer | 排序序号 |
| presetRows | String | 预置行数据（JSON 字符串） |
| formulaAssignments | String | 公式指派（JSON 字符串） |
| dataDriverPathOverride | String | 模板级 driver_path 覆盖；null=沿用 component 默认 |
| fieldsOverride | String | 模板级 fields 覆盖（JSON 数组字符串）；null=沿用 component 默认 |

#### 列出模板组件
- **功能**: 列出模板下所有组件页签
- **方法**: GET
- **路径**: `/api/cpq/templates/{templateId}/components`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |

- **响应内容**: `ApiResponse<List<TemplateComponentDTO>>`

#### 添加组件到模板
- **功能**: 向模板追加一个组件页签
- **方法**: POST
- **路径**: `/api/cpq/templates/{templateId}/components`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |

- **请求体**: `Map<String,Object>`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| componentId | String(UUID) | 是 | 组件 ID，缺失抛 400 |
| tabName | String | 否 | 页签名称，不传则用组件默认 |

- **响应内容**: `ApiResponse<TemplateComponentDTO>`

#### 移除模板组件
- **功能**: 从模板移除一个组件页签
- **方法**: DELETE
- **路径**: `/api/cpq/templates/{templateId}/components/{tcId}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |
| tcId | UUID | 模板组件 ID |

- **响应内容**: `ApiResponse<Void>`

#### 更新预置行
- **功能**: 更新模板组件的预置行数据
- **方法**: PATCH
- **路径**: `/api/cpq/templates/{templateId}/components/{tcId}/preset-rows`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |
| tcId | UUID | 模板组件 ID |

- **请求体**: `Map<String,Object>`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| presetRows | List / 任意 | 否 | 预置行数据（数组会被序列化为 JSON；缺失按 "[]"） |

- **响应内容**: `ApiResponse<TemplateComponentDTO>`

#### 更新公式指派
- **功能**: 更新模板组件的公式指派配置
- **方法**: PATCH
- **路径**: `/api/cpq/templates/{templateId}/components/{tcId}/formula-assignments`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |
| tcId | UUID | 模板组件 ID |

- **请求体**: `Map<String,Object>`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| formulaAssignments | Map / 任意 | 否 | 公式指派（对象会被序列化为 JSON；缺失按 "{}"） |

- **响应内容**: `ApiResponse<TemplateComponentDTO>`

#### 组件排序
- **功能**: 按传入的 tc ID 顺序重排模板组件页签
- **方法**: PUT
- **路径**: `/api/cpq/templates/{templateId}/components/reorder`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |

- **请求体**: `Map<String,Object>`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| ids | List&lt;String(UUID)&gt; | 是 | 按新顺序排列的 tc ID 列表，缺失抛 400 |

- **响应内容**: `ApiResponse<List<TemplateComponentDTO>>`

#### 更新组件覆盖（fields / driverPath）
- **功能**: 编辑模板组件 override；body 内任一键缺省=不动，显式 null=清空 override，非空=设置。仅 DRAFT 可改
- **方法**: PATCH
- **路径**: `/api/cpq/templates/{templateId}/components/{tcId}/overrides`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |
| tcId | UUID | 模板组件 ID |

- **请求体**: `Map<String,Object>`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| fieldsOverride | String / List / Map / null | 否 | 字段覆盖；不含此键=不动，null=清空，非空（列表/对象会序列化为 JSON）=设置 |
| dataDriverPathOverride | String / null | 否 | driver 路径覆盖；不含此键=不动，null=清空，非空=设置 |

- **响应内容**: `ApiResponse<TemplateComponentDTO>`

---

### 3.3 TemplateExcelViewResource（模板 Excel 视图配置 / 试算 / 表头解析）

类级 `@Path`: `/api/cpq/templates/{id}/excel-view-config`
类级 `@RoleAllowed`: `SALES_REP`, `SALES_MANAGER`, `PRICING_MANAGER`, `SYSTEM_ADMIN`

#### 获取 Excel 视图配置
- **功能**: 返回模板 excel_view_config（解析后的 JSON 数组）
- **方法**: GET
- **路径**: `/api/cpq/templates/{id}/excel-view-config`
- **鉴权**: 类级四角色
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **响应内容**: `ApiResponse<Object>`（列定义 JSON 数组；解析失败降级返原始字符串）

#### 获取有效列
- **功能**: 返回后端解析后的有效列（excel_component_id → component.excel_columns + column_overrides），供前端 saveDraft 取列定义
- **方法**: GET
- **路径**: `/api/cpq/templates/{id}/excel-view-config/effective-columns`
- **鉴权**: 类级四角色
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **响应内容**: `ApiResponse<Object>`（有效列列表；模板不存在返空数组）

#### 保存 Excel 视图配置
- **功能**: 在 DRAFT 模板上保存 excel_view_config
- **方法**: PUT
- **路径**: `/api/cpq/templates/{id}/excel-view-config`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **请求体**: 原始 JSON 数组字符串（列定义）。列示例键：`col_key`、`label`、`source_type`（PRODUCT_ATTRIBUTE / COMPONENT_FIELD / EXCEL_FORMULA / FIXED_VALUE）、`field_key` / `formula` / `fixed_value`
- **响应内容**: `ApiResponse<String>`

#### 试算 TAB_JOIN_FORMULA 列
- **功能**: 给样本 lineItem + TAB_JOIN_FORMULA 列配置，返回单值试算结果
- **方法**: POST
- **路径**: `/api/cpq/templates/{id}/excel-view-config/dry-run-tab-formula`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **请求体**: `Map<String,Object>`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lineItemId | String(UUID) | 是 | 样本报价行 ID，缺失/格式非法抛 400 |
| column | Map | 是 | 列定义对象，缺失或非对象抛 400 |
| cardValuesJson | String | 否 | 可选卡片值 JSON |

- **响应内容**: `ApiResponse<Map<String,Object>>`，含 `{ value: BigDecimal|null, errors: [...] }`

#### 页签定义
- **功能**: 返回各组件的 alias/tabKey/rowKeyFields/detailFields/subtotalCols，供 TAB_JOIN_FORMULA 构建器初始化
- **方法**: GET
- **路径**: `/api/cpq/templates/{id}/excel-view-config/tab-defs`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **响应内容**: `ApiResponse<List<Map<String,Object>>>`

#### 样本卡片
- **功能**: 返回引用该模板的报价行（最多 50 条），供前端选样本试算
- **方法**: GET
- **路径**: `/api/cpq/templates/{id}/excel-view-config/sample-cards`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **响应内容**: `ApiResponse<List<Map<String,Object>>>`，每项含 `{quotationId, quotationNo, lineItemId, cardName}`

#### 解析 Excel 表头
- **功能**: 上传 Excel 文件，返回表头行列名，供导入 UI 做列映射
- **方法**: POST
- **路径**: `/api/cpq/templates/{id}/excel-view-config/parse-header`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **请求头**: `Content-Type: multipart/form-data`
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **请求体**（multipart form）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | 文件 | 是 | Excel 文件，缺失抛 400 |
| sheetIndex | int | 否 | 1-based sheet 序号，默认 1 |
| headerRowIndex | int | 否 | 1-based 表头行号，默认 2 |

- **响应内容**: `ApiResponse<List<String>>`（表头列名列表）

---

### 3.4 TemplateFormulaResource（模板公式 CRUD / 试算 / 校验 / 补全）

类级 `@Path`: `/api/cpq/templates/{templateId}/formulas`
类级 `@RoleAllowed`: `SALES_MANAGER`, `SYSTEM_ADMIN`, `PRICING_MANAGER`

**【TemplateFormulaDTO 结构】**（公式 CRUD 请求/响应体）

| 字段 | 类型 | 说明 |
|------|------|------|
| name | String | 公式名（同模板内唯一，可中文，被 [name] 引用） |
| expression | String | 表达式（上限 5000，支持算术+函数；Stage1 拒绝聚合 SUM_OVER） |
| dataType | String | 结果类型 DECIMAL(18,4) / STRING / INTEGER / BOOLEAN |
| dependsOn | List&lt;String&gt; | 依赖引用（保存时由 service 从 expression 自动扫描覆写） |
| description | String | 业务说明，可空 |

#### 列出模板公式
- **功能**: 列出模板下所有公式
- **方法**: GET
- **路径**: `/api/cpq/templates/{templateId}/formulas`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN / PRICING_MANAGER
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |

- **响应内容**: `ApiResponse<List<TemplateFormulaDTO>>`

#### 新增公式
- **功能**: 新增公式（仅 DRAFT）
- **方法**: POST
- **路径**: `/api/cpq/templates/{templateId}/formulas`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN / PRICING_MANAGER
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |

- **请求体**: `TemplateFormulaDTO`
- **响应内容**: `ApiResponse<TemplateFormulaDTO>`

#### 更新公式
- **功能**: 更新公式（仅 DRAFT）
- **方法**: PUT
- **路径**: `/api/cpq/templates/{templateId}/formulas/{name}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN / PRICING_MANAGER
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |
| name | String | 公式名 |

- **请求体**: `TemplateFormulaDTO`
- **响应内容**: `ApiResponse<TemplateFormulaDTO>`

#### 删除公式
- **功能**: 删除公式（仅 DRAFT）
- **方法**: DELETE
- **路径**: `/api/cpq/templates/{templateId}/formulas/{name}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN / PRICING_MANAGER
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |
| name | String | 公式名 |

- **响应内容**: `ApiResponse<Void>`

#### 试算公式
- **功能**: 给 partNo + customerId，返回该公式求值结果（可选 trace 依赖链）
- **方法**: POST
- **路径**: `/api/cpq/templates/{templateId}/formulas/{name}/evaluate`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN / PRICING_MANAGER
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |
| name | String | 公式名 |

- **请求体**: `Map<String,Object>`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| partNo | String | 否 | 料号 |
| customerId | String(UUID) | 否 | 客户 ID |
| trace | Boolean | 否 | true 时返回 `{value, trace:{依赖公式:结果}}` |

- **响应内容**: `ApiResponse<Object>`（求值结果或含 trace 的对象）

#### 校验公式
- **功能**: 保存前校验（表达式合法 + 依赖完整 + 无循环）
- **方法**: POST
- **路径**: `/api/cpq/templates/{templateId}/formulas/validate`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN / PRICING_MANAGER
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |

- **请求体**: `TemplateFormulaDTO`
- **响应内容**: `ApiResponse<ValidationResult>`

| 字段 | 类型 | 说明 |
|------|------|------|
| valid | boolean | 是否通过 |
| error | String | 旧版纯文本错误（deprecated，优先用 errors） |
| dependsOn | List&lt;String&gt; | 检测出的依赖 |
| errors | List&lt;FormulaErrorDTO&gt; | 结构化错误列表（含中文 message/code/line/column/suggestions），valid=true 时为 null |

#### 【调试】SUM_OVER 内部逻辑
- **功能**: 直接测试 SUM_OVER 内部逻辑，返回中间步骤
- **方法**: POST
- **路径**: `/api/cpq/templates/{templateId}/formulas/debug-sum-over`
- **鉴权**: SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |

- **请求体**: `Map<String,Object>`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| partNo | String | 否 | 料号 |
| expression | String | 否 | 待测 SUM_OVER 表达式 |

- **响应内容**: `ApiResponse<Map<String,Object>>`（中间步骤）

#### 公式自动补全数据
- **功能**: 返回当前模板的公式 / 组件字段 / 全局变量三类补全候选，供公式编辑器预加载
- **方法**: GET
- **路径**: `/api/cpq/templates/{templateId}/formulas/completions`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN / PRICING_MANAGER
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |

- **响应内容**: `ApiResponse<FormulaCompletionDTO>`

| 字段 | 类型 | 说明 |
|------|------|------|
| templateFormulas | List&lt;FormulaItem&gt; | 模板公式 {name, dataType, description} |
| components | List&lt;ComponentItem&gt; | 组件 {code, name, fields:[{name, label, dataType}]} |
| globalVariables | List&lt;GlobalVariableItem&gt; | 全局变量 {name, code, dataType, currentValue, description, unit, varType} |

---

### 3.5 TemplateGvBindingResource（模板全局变量绑定）

类级 `@Path`: `/api/cpq/templates/{tid}/global-variable-bindings`
类级 `@RoleAllowed`: `SALES_REP`, `SALES_MANAGER`, `PRICING_MANAGER`, `SYSTEM_ADMIN`

#### 获取绑定列表
- **功能**: 获取模板的全局变量绑定列表（按 display_order 升序，含 GVD 名称/类型/单位）
- **方法**: GET
- **路径**: `/api/cpq/templates/{tid}/global-variable-bindings`
- **鉴权**: 类级四角色
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| tid | UUID | 模板 ID |

- **响应内容**: `ApiResponse<List<TemplateGvBindingDTO>>`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 绑定 ID |
| templateId | UUID | 模板 ID |
| globalVariableCode | String | 全局变量业务编码（VARCHAR(64) 主键） |
| globalVariableName | String | 变量名（JOIN 回填） |
| varType | String | LOOKUP_TABLE / SCALAR（JOIN 回填） |
| unit | String | 单位（JOIN 回填） |
| isActive | boolean | 是否启用（JOIN 回填） |
| displayOrder | int | 显示顺序 |
| createdAt | OffsetDateTime | 创建时间 |

#### 全量替换绑定
- **功能**: 全量替换模板全局变量绑定（PUT 语义：先按 template_id DELETE 再 INSERT）。仅 DRAFT 模板可操作（否则 403）
- **方法**: PUT
- **路径**: `/api/cpq/templates/{tid}/global-variable-bindings`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| tid | UUID | 模板 ID |

- **请求体**: `UpdateBindingsRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| bindings | List&lt;BindingItem&gt; | 是 | 绑定列表（全量替换） |
| bindings[].globalVariableCode | String | 是 | 全局变量 code（非 gvId） |
| bindings[].displayOrder | int | 是 | 显示顺序 |

- **响应内容**: `ApiResponse<List<TemplateGvBindingDTO>>`

---

### 3.6 TemplateSqlViewResource（产品卡片模板 SQL 视图）

类级 `@Path`: `/api/cpq/templates/{templateId}/sql-views`
（类级无 `@RoleAllowed`，各端点单独声明）
隔离约束：非 DRAFT 模板禁止增删改（Service 层强制，抛 400）。

**【TemplateSqlViewDTO 结构】**（读端点响应体）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | SQL 视图 ID |
| templateId | UUID | 所属模板 ID |
| sqlViewName | String | BNF 引用名 |
| sqlTemplate | String | 含命名占位符的 SELECT SQL |
| declaredColumns | List&lt;Map&gt; | 列签名数组（反序列化后） |
| requiredVariables | List&lt;String&gt; | 必需占位符列表 |
| scope | String | 命名空间（固定 LOCAL） |
| status | String | 状态 ACTIVE / INACTIVE |
| description | String | 描述 |
| createdBy | UUID | 创建人 |
| createdAt / updatedAt | LocalDateTime | 时间戳 |

#### 列出模板 SQL 视图
- **功能**: 列出指定模板下所有 ACTIVE SQL 视图
- **方法**: GET
- **路径**: `/api/cpq/templates/{templateId}/sql-views`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN / SALES_MANAGER
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |

- **响应内容**: `ApiResponse<List<TemplateSqlViewDTO>>`

#### 获取单条 SQL 视图
- **功能**: 获取模板下单条 SQL 视图
- **方法**: GET
- **路径**: `/api/cpq/templates/{templateId}/sql-views/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN / SALES_MANAGER
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |
| id | UUID | SQL 视图 ID |

- **响应内容**: `ApiResponse<TemplateSqlViewDTO>`

#### 新建 SQL 视图
- **功能**: 新建 SQL 视图（同时 dry-run 校验，失败抛 400；模板非 DRAFT 抛 400）
- **方法**: POST
- **路径**: `/api/cpq/templates/{templateId}/sql-views`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |

- **请求体**: `CreateTemplateSqlViewRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sqlViewName | String | 是 | BNF 引用名，同模板内唯一（小写字母/下划线/数字） |
| sqlTemplate | String | 是 | 含命名占位符的 SELECT SQL |
| scope | String | 否 | 命名空间，仅允许 LOCAL，缺省 LOCAL |
| description | String | 否 | 描述 |

- **响应内容**: `ApiResponse<TemplateSqlViewDTO>`

#### 更新 SQL 视图
- **功能**: 更新 SQL 视图（改 SQL 时重跑 dry-run；模板非 DRAFT 抛 400）
- **方法**: PUT
- **路径**: `/api/cpq/templates/{templateId}/sql-views/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |
| id | UUID | SQL 视图 ID |

- **请求体**: `UpdateTemplateSqlViewRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sqlViewName | String | 否 | 新 BNF 引用名（改名时填） |
| sqlTemplate | String | 否 | 新 SQL 模板（改则触发 dry-run 重校验） |
| description | String | 否 | 描述 |

- **响应内容**: `ApiResponse<TemplateSqlViewDTO>`

#### 删除 SQL 视图
- **功能**: 软删除（status → INACTIVE；模板非 DRAFT 抛 400）
- **方法**: DELETE
- **路径**: `/api/cpq/templates/{templateId}/sql-views/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |
| id | UUID | SQL 视图 ID |

- **响应内容**: `ApiResponse<Void>`

#### Dry-run 校验 SQL 视图
- **功能**: 仅校验 SQL 不落库，返回列签名 + 占位符清单（"dry-run" 字面量路由优先于 /{id}）
- **方法**: POST
- **路径**: `/api/cpq/templates/{templateId}/sql-views/dry-run`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |

- **请求体**: `DryRunTemplateSqlViewRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sqlTemplate | String | 是 | 待校验的 SQL 模板（含命名占位符） |

- **响应内容**: `ApiResponse<DryRunSqlViewResponse>`

| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 是否通过 |
| declaredColumns | List&lt;ColumnMeta&gt; | 列签名 {name, dataType, nullable}（success=true 时） |
| requiredVariables | List&lt;String&gt; | 解析出的 :xxx 占位符（不含 :hfPartNos） |
| error | String | 错误信息（success=false 时） |

---

### 3.7 ProductTemplateBindingResource（产品-模板绑定）

类级 `@Path`: `/api/cpq/products/{productId}/template-bindings`
类级 `@RoleAllowed`: `SALES_REP`, `SALES_MANAGER`, `PRICING_MANAGER`, `SYSTEM_ADMIN`

**【ProductTemplateBindingDTO 结构】**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 绑定 ID |
| productId | UUID | 产品 ID |
| processIds | List&lt;String&gt; | 工序 ID 列表 |
| processIdsHash | String | 工序集合哈希 |
| templateId | UUID | 模板 ID |
| isDefault | Boolean | 是否默认绑定 |
| createdAt | OffsetDateTime | 创建时间 |

#### 列出产品绑定
- **功能**: 列出某产品的所有模板绑定
- **方法**: GET
- **路径**: `/api/cpq/products/{productId}/template-bindings`
- **鉴权**: 类级四角色
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| productId | UUID | 产品 ID |

- **响应内容**: `ApiResponse<List<ProductTemplateBindingDTO>>`

#### 创建绑定
- **功能**: 为产品创建模板绑定
- **方法**: POST
- **路径**: `/api/cpq/products/{productId}/template-bindings`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| productId | UUID | 产品 ID（覆盖请求体内的 productId） |

- **请求体**: `CreateBindingRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| productId | UUID | 否 | 产品 ID（由路径参数覆盖） |
| processIds | List&lt;String&gt; | 否 | 工序 ID 列表 |
| templateId | UUID | 是 | 模板 ID |
| isDefault | Boolean | 否 | 是否默认，默认 false |

- **响应内容**: `ApiResponse<ProductTemplateBindingDTO>`

#### 删除绑定
- **功能**: 删除产品模板绑定
- **方法**: DELETE
- **路径**: `/api/cpq/products/{productId}/template-bindings/{bindingId}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| productId | UUID | 产品 ID |
| bindingId | UUID | 绑定 ID |

- **响应内容**: `ApiResponse<Void>`

#### 设为默认绑定
- **功能**: 将指定绑定设为该产品的默认绑定
- **方法**: PUT
- **路径**: `/api/cpq/products/{productId}/template-bindings/{bindingId}/set-default`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| productId | UUID | 产品 ID |
| bindingId | UUID | 绑定 ID |

- **响应内容**: `ApiResponse<ProductTemplateBindingDTO>`

#### 匹配模板
- **功能**: 按产品 + 工序集合匹配模板绑定
- **方法**: GET
- **路径**: `/api/cpq/products/{productId}/template-bindings/match`
- **鉴权**: 类级四角色
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| productId | UUID | 产品 ID |

- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| processIds | String | 否 | 逗号分隔的工序 ID 列表，默认空（空则按空集匹配） |

- **响应内容**: `ApiResponse<List<ProductTemplateBindingDTO>>`

---

### 3.8 FormulaFunctionResource（公式函数清单）

类级 `@Path`: `/api/cpq/formulas/functions`
类级 `@RoleAllowed`: `SALES_REP`, `SALES_MANAGER`, `SYSTEM_ADMIN`, `PRICING_MANAGER`

#### 公式函数清单
- **功能**: 返回公式引擎支持的所有函数元数据（静态硬编码，无 DB 查询），供前端公式编辑器展示帮助文档与自动补全。覆盖聚合(SUM_OVER/COUNT_OVER/AVG_OVER/MIN_OVER/MAX_OVER)、条件(IF/COALESCE/NULLIF)、数学(ABS)
- **方法**: GET
- **路径**: `/api/cpq/formulas/functions`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN / PRICING_MANAGER
- **响应内容**: `ApiResponse<List<FormulaFunctionDTO>>`

| 字段 | 类型 | 说明 |
|------|------|------|
| name | String | 函数名，如 SUM_OVER |
| category | String | 分类 聚合 / 条件 / 算术 / 数学 |
| signature | String | 函数签名 |
| description | String | 功能描述（中文） |
| examples | List&lt;ExampleItem&gt; | 使用示例 {expression, explanation} |
| params | List&lt;ParamItem&gt; | 参数说明 {name, type, required, description} |

---

### 3.9 LegacyPathsResource（遗留 BNF 路径盘点）

类级 `@Path`: `/api/cpq/templates/legacy-paths`
（类级无 `@RoleAllowed`，端点单独声明）

#### 盘点遗留 BNF 路径
- **功能**: 扫描所有 DRAFT + PUBLISHED 模板的 excel_view_config，逐列调 BnfPathLinter，返回 WARN/ERROR 级别的路径负债清单（供运维盘点存量 PG 视图直引等问题）。"legacy-paths" 字面量路由优先于 /{templateId}
- **方法**: GET
- **路径**: `/api/cpq/templates/legacy-paths`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **响应内容**: `ApiResponse<List<Map<String,Object>>>`，每项 finding 含：

| 字段 | 类型 | 说明 |
|------|------|------|
| templateId | String | 模板 ID |
| templateName | String | 模板名称 |
| status | String | 模板状态 PUBLISHED / DRAFT |
| colKey | String | 列 key |
| variablePath | String | 变量路径 |
| lintLevel | String | WARN / ERROR |
| message | String | 提示信息 |
| suggestion | String | 修复建议 |
## 四、报价单

> 全局基准：基址 `http://localhost:8081`；鉴权 = 会话 Cookie（`@RoleAllowed` 端点需登录并具备相应角色，请求需带登录 Cookie；无注解端点不校验角色但仍受类级注解约束）。统一响应 `ApiResponse<T>` = `{ code, message, data }`；导出/HTML/Excel 类端点直返 `Response`（二进制/文本流），已在各端点标注。

---

### 4.1 QuotationResource（报价单主资源：CRUD、草稿、状态机、卡片值、Excel 视图、导出、发送）

- **类级 @Path**：`/api/cpq/quotations`
- **类级鉴权**：`@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`
- **产出/消费**：`Produces=application/json`、`Consumes=application/json`（个别导出端点单独覆盖）

> 说明：以下多个端点返回 `QuotationDTO` / `QuotationDTO.LineItemDTO`。为避免重复，`QuotationDTO` 与其嵌套结构的字段清单集中列在本小节末「附：QuotationDTO 字段全表」，各端点响应内容处只标注返回类型。

#### 4.1.1 报价单列表（分页 + 多条件过滤）
- **功能**：分页查询报价单列表，支持状态、销售、审批人、关键字过滤
- **方法**: GET
- **路径**: `/api/cpq/quotations`
- **鉴权**: 需登录（四角色任一）
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |
| status | String | 否 | 状态码过滤（DRAFT/SUBMITTED/... ） |
| salesRepId | UUID | 否 | 销售代表 ID 过滤 |
| assignedApproverId | UUID | 否 | 指派审批人 ID 过滤 |
| keyword | String | 否 | 关键字（报价单号/名称等模糊匹配） |

- **响应内容**: `ApiResponse<PageResult<QuotationDTO>>`

| 字段 | 类型 | 说明 |
|------|------|------|
| data.total | long | 总条数 |
| data.list | QuotationDTO[] | 当页报价单列表（列表投影，明细字段可能未填充） |

#### 4.1.2 报价单详情
- **功能**: 按 ID 获取报价单完整详情（含 lineItems、审批历史、DRAFT 漂移检测等）
- **方法**: GET
- **路径**: `/api/cpq/quotations/{id}`
- **鉴权**: 需登录
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 报价单 ID |

- **响应内容**: `ApiResponse<QuotationDTO>`（详情视图，填充 lineItems / approvalHistory / 4 份结构快照 / 漂移字段）

#### 4.1.3 创建报价单
- **功能**: 新建报价单（DRAFT），销售代表由当前会话推断
- **方法**: POST
- **路径**: `/api/cpq/quotations`
- **鉴权**: 需登录（销售归属取当前用户 ID，缺失则回退）
- **请求体**: `CreateQuotationRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | UUID | 是 | 客户 ID（@NotNull） |
| name | String | 是 | 报价单名称（@NotNull，≤500 字符） |
| contactId | UUID | 否 | 联系人 ID |
| contactName | String | 否 | 联系人姓名（≤200） |
| contactPhone | String | 否 | 联系电话（≤50） |
| contactEmail | String | 否 | 联系邮箱（≤200） |
| projectName | String | 否 | 项目名称（≤500） |
| opportunityId | String | 否 | 商机 ID（≤200） |
| quoteType | String | 否 | 报价类型（≤30） |
| priority | String | 否 | 优先级（≤20） |
| stage | String | 否 | 阶段（≤50） |
| expectedCloseDate | LocalDate | 否 | 预计成交日期 |
| customerTemplateId | UUID | 否 | 客户报价模板 ID（按 customerId+categoryId 匹配，留空后续 Step2 手选） |
| costingTemplateId | UUID | 否 | 核价模板 ID（template_kind='COSTING' 且 PUBLISHED），写入 costing_card_template_id |

- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.4 客户可选料号候选列表
- **功能**: Step2「批量从基础数据导入产品」候选料号（客户专属 mapping + 全局 mat_part）
- **方法**: GET
- **路径**: `/api/cpq/quotations/customer-part-candidates`
- **鉴权**: 需登录
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | UUID | 是 | 客户 ID（为空返回 400 "customerId 不能为空"） |
| importRecordId | UUID | 否 | 导入记录 ID（限定某次导入范围） |

- **响应内容**: `ApiResponse<List<CustomerPartCandidateDTO>>`

| 字段 | 类型 | 说明 |
|------|------|------|
| partNo | String | 宏丰料号（part_no） |
| partName | String | 料号名称 |
| unitWeight | BigDecimal | 单重 |
| weightUnit | String | 重量单位（KG/G/PCS 等） |
| customerProductNo | String | 客户产品编号（专属映射时） |
| customerPartName | String | 客户料号名称 |
| customerDrawingNo | String | 客户图号 |
| baseCurrency | String | 基础货币 |
| quoteCurrency | String | 报价货币 |
| customerSpecific | boolean | 是否客户专属（true=有 mapping） |
| currentVersion | Integer | 客户映射当前版本，透传到 line_item.part_version_locked |
| hfPartInfo | HfPartInfo | 生产料号详情（partNo/partName/specification/sizeInfo/statusCode），缺失为 null |

#### 4.1.5 保存草稿
- **功能**: 全量保存报价单草稿（表头 + 行项 + 组件数据），保存后按新行重建 snapshot_rows。含分段耗时埋点 `[draft-profile]`
- **方法**: PUT
- **路径**: `/api/cpq/quotations/{id}/draft`
- **鉴权**: 需登录
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 报价单 ID |

- **请求体**: `SaveDraftRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | 否 | 报价单名称 |
| contactId | UUID | 否 | 联系人 ID |
| contactName / contactPhone / contactEmail | String | 否 | 联系人信息 |
| projectName | String | 否 | 项目名称 |
| opportunityId | String | 否 | 商机 ID |
| quoteType / priority / stage | String | 否 | 报价类型/优先级/阶段 |
| expectedCloseDate | LocalDate | 否 | 预计成交日期 |
| paymentTerms | String | 否 | 付款条件 |
| deliveryCycle | Integer | 否 | 交货周期 |
| expiryDate | LocalDate | 否 | 报价有效期 |
| remarks | String | 否 | 备注 |
| finalDiscountRate | BigDecimal | 否 | 最终折扣率（人工覆盖） |
| discountAdjustmentReason | String | 否 | 折扣调整原因 |
| customerTemplateId | UUID | 否 | 报价模板绑定（Step1 选模板需透传，否则刷新丢失） |
| costingCardTemplateId | UUID | 否 | 核价模板绑定 |
| lineItems | LineItemDraft[] | 否 | 行项数组（见下） |

`SaveDraftRequest.LineItemDraft` 字段：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | UUID | 否 | 已存在行 ID（回传则就地 UPDATE 复用，避免换 UUID） |
| productId | UUID | 否 | 产品 ID |
| templateId | UUID | 否 | 模板 ID |
| productPartNo | String | 否 | 产品料号（V5 导入流程无 productId 时来自 mat_part） |
| productName | String | 否 | 产品名称 |
| customerPartNo | String | 否 | 客户料号 |
| customerProductNo | String | 否 | 客户产品编号（customerPartNo 为空时 fallback） |
| productAttributeValues | String | 否 | 产品属性值 |
| subtotal | BigDecimal | 否 | 行小计 |
| sortOrder | Integer | 否 | 排序序号 |
| processIds | UUID[] | 否 | 工序 ID 列表 |
| componentData | ComponentDataDraft[] | 否 | 组件数据（见下） |
| compositeType | String | 否 | 选配组合关系 SIMPLE/COMPOSITE/PART（重建须透传保留） |
| tempParentIndex | Integer | 否 | 父级在 lineItems 中索引（PART 子件用，替代已删旧父 UUID） |
| seedProcessesFromBase | Boolean | 否 | 从料号基础工序 seed 工序（导入行 true） |
| compositeProcesses | CompositeProcessDraft[] | 否 | 选配组合工艺步骤 |
| quoteExcelValues | String | 否 | 前端算好的报价 Excel 列值快照 JSON（后端原样落库不重算） |
| annualVolume | Integer | 否 | Step3 行级折扣：年用量 |
| discountSource | String | 否 | 折扣来源 |
| discountBaseAmount | BigDecimal | 否 | 折扣基准金额 |
| discountRateApplied | BigDecimal | 否 | 应用折扣率 |
| lineDiscountAmount | BigDecimal | 否 | 行折扣额 |
| lineUnitPrice | BigDecimal | 否 | 行单价 |
| lineFinalPrice | BigDecimal | 否 | 行最终价 |
| lineTotalAmount | BigDecimal | 否 | 行总额 |
| discountRuleCode | String | 否 | 折扣规则码 |

`CompositeProcessDraft`：`defCode`(String 工艺定义码)、`seqNo`(Integer 步骤序号)、`participatingParts`(String[] 参与子件料号)、`paramValues`(Map 参数值)。
`ComponentDataDraft`：`componentId`(UUID)、`tabName`(String 页签名)、`rowData`(String 行数据 JSON)、`subtotal`(BigDecimal)、`sortOrder`(Integer)。

- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.6 切换行项料号版本
- **功能**: 料号版本管理，切换某 line_item 的 part_version_locked（仅 DRAFT 可改）
- **方法**: PUT
- **路径**: `/api/cpq/quotations/{id}/line-items/{lineItemId}/part-version`
- **鉴权**: 需登录
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 报价单 ID |
| lineItemId | UUID | 行项 ID |

- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| version | int/String | 是 | 目标版本号（非整数返回 400） |

- **响应内容**: `ApiResponse<Map<String,Object>>`

| 字段 | 类型 | 说明 |
|------|------|------|
| quotationId | UUID | 报价单 ID |
| lineItemId | UUID | 行项 ID |
| partVersionLocked | int | 已锁定版本号 |
| excelViewSnapshot | String | 新版本 Excel 视图快照（前端立即渲染） |

#### 4.1.7 草稿态刷新报价卡片值快照
- **功能**: 遍历报价行重 expand + 按行键保编辑 + 重算，仅 DRAFT 执行；非 DRAFT 返回 refreshed=0。前端 Step2「刷新基础数据」按钮触发
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/refresh-card-snapshot`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **响应内容**: `ApiResponse<Map<String,Object>>` — `{ quotationId, refreshed(int 重刷行数) }`

#### 4.1.8 懒算整单 Excel 值
- **功能**: 懒算并落库整单 Excel 值（quoteExcelValues/costingExcelValues），首存留 NULL、开 Excel 视图/导出前补算（幂等）
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/ensure-excel-values`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **响应内容**: `ApiResponse<QuotationDTO>`（补算后最新 DTO，含 Excel 值）

#### 4.1.9 懒算整单卡片值
- **功能**: 懒算并落库整单卡片值（quote/costing card values），warm 与打开兜底复用；若单飞锁被占返回 warming 状态不阻塞
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/ensure-card-values`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **响应内容**: `ApiResponse<QuotationDTO>`；warm 在飞时返回轻量 DTO（仅 `cardValuesWarming=true`），否则返回最新完整 DTO

#### 4.1.10 编辑报价卡片单元格
- **功能**: 编辑回写报价卡片单元格（替代旧 autosave 写 row_data），写 editRows + 重算报价公式/Excel，核价不动；仅 DRAFT 可编辑
- **方法**: PUT
- **路径**: `/api/cpq/quotations/line-items/{lineItemId}/quote-card-edit`
- **鉴权**: 需登录
- **路径参数**: `lineItemId` UUID — 行项 ID
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| componentId | String/UUID | 是 | 组件 ID |
| rowKey | String | 是 | 行键 |
| fieldName | String | 是 | 字段名 |
| value | Object | 否 | 新值 |

- **响应内容**: `ApiResponse<Map<String,Object>>`（更新后的 quoteCardValues/quoteExcelValues，供前端就地刷新；非草稿态或数据缺失返回 400）

#### 4.1.11 计算折扣
- **功能**: 按原始金额计算客户级折扣/返利
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/calculate-discount`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| originalAmount | number | 是 | 原始金额（缺失/非数字返回 400） |

- **响应内容**: `ApiResponse<QuotationDTO>`（含折扣计算结果字段）

#### 4.1.12 提交报价单
- **功能**: DRAFT→SUBMITTED 并写入提交快照；提交前先确保 Excel 值已补算
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/submit`
- **鉴权**: `@RoleAllowed({"SALES_REP","SYSTEM_ADMIN"})` — 仅销售/管理员可提交
- **路径参数**: `id` UUID — 报价单 ID
- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.13 获取提交快照
- **功能**: 获取报价单提交时冻结的完整快照（JSON 反序列化后返回）
- **方法**: GET
- **路径**: `/api/cpq/quotations/{id}/snapshot`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **响应内容**: `ApiResponse<Object>`（快照 JSON 解析后的对象；无快照返回 data=null）

#### 4.1.14 字段级追溯
- **功能**: 追溯某字段路径的值来源（公式/输入/主数据等）
- **方法**: GET
- **路径**: `/api/cpq/quotations/{id}/field-trace`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| fieldPath | String | 是 | 字段路径，如 `mat_fee.xxx|yyy.unit_price` |

- **响应内容**: `ApiResponse<FieldTraceDTO>`

| 字段 | 类型 | 说明 |
|------|------|------|
| fieldPath | String | 被追溯字段路径（回显） |
| currentValue | Object | 该字段在快照中的值（可 null） |
| sourceType | String | 来源类型 FORMULA/MANUAL_INPUT/MASTER_DATA/CUSTOMER_DATA/ELEMENT_PRICE |
| referencedVersion | String | 引用版本号（如 "mat_fee v3"），不适用为 null |
| formula | String | 公式表达式字符串（computation JSON） |
| formulaInputs | Map | 公式输入变量值映射 |
| lastModifiedBy | String | 最后修改者（v1 从快照推断，可 null） |
| lastModifiedAt | String | 最后修改时间（v1 用 snapshotAt） |

#### 4.1.15 全表重算
- **功能**: 全表重算 DRAFT 报价单所有公式字段；非 DRAFT 返回 400
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/recalculate`
- **鉴权**: `@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`
- **路径参数**: `id` UUID — 报价单 ID
- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.16 审批通过（销售审批）
- **功能**: 报价单审批通过
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/approve`
- **鉴权**: 需登录（审批人取当前会话）
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**: `{ comment: String }`（可选，审批意见）
- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.17 审批驳回（销售审批）
- **功能**: 报价单审批驳回
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/reject`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**: `{ comment: String }`（可选，驳回意见）
- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.18 核价通过
- **功能**: 财务核价通过
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/costing-approve`
- **鉴权**: `@RoleAllowed({"PRICING_MANAGER","SYSTEM_ADMIN"})`
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**: `{ comment: String }`（可选）
- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.19 核价驳回
- **功能**: 财务核价驳回
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/costing-reject`
- **鉴权**: `@RoleAllowed({"PRICING_MANAGER","SYSTEM_ADMIN"})`
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**: `{ comment: String }`（可选，作为驳回原因）
- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.20 撤回
- **功能**: 撤回报价单（回退状态）
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/withdraw`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.21 开始编辑
- **功能**: 进入编辑态（如从已提交回到可编辑）
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/begin-edit`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.22 复制报价单
- **功能**: 复制报价单为新草稿，可指定新模板
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/copy`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 源报价单 ID
- **请求体**: `{ templateId: UUID }`（可选，指定新模板 ID）
- **响应内容**: `ApiResponse<QuotationDTO>`（新建的报价单）

#### 4.1.23 删除报价单
- **功能**: 删除报价单
- **方法**: DELETE
- **路径**: `/api/cpq/quotations/{id}`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **响应内容**: `ApiResponse<Void>`

#### 4.1.24 Admin 修复组件数据页签名
- **功能**: 一次性洗 quotation_line_component_data.tab_name 脏数据（saved-driven enrich 误写"选配-*"），修回模板权威值。默认 dry-run
- **方法**: POST
- **路径**: `/api/cpq/quotations/admin/heal-componentdata-tabnames`
- **鉴权**: `@RoleAllowed({"SYSTEM_ADMIN"})`
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| apply | boolean | 否 | 默认 false（仅扫描）；true 才真改库 |

- **响应内容**: `ApiResponse<Map<String,Object>>`（修复统计）

#### 4.1.25 导出 HTML
- **功能**: 导出报价单 HTML（内联展示）
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/export/html`
- **鉴权**: 需登录
- **产出**: `text/html`（直返 `Response`，Content-Disposition inline）
- **路径参数**: `id` UUID — 报价单 ID
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| showDiscount | boolean | 否 | 显示折扣，默认 true |
| showProcesses | boolean | 否 | 显示工序，默认 true |
| showTabDetails | boolean | 否 | 显示页签明细，默认 false |

- **响应内容**: HTML 字节流（filename="quotation.html"）

#### 4.1.26 导出 PDF
- **功能**: 导出用于浏览器打印为 PDF 的 HTML（务实方案，实际返回 HTML）
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/export/pdf`
- **鉴权**: 需登录
- **产出**: `text/html`（直返 `Response`）
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**（可选）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| showDiscount | boolean | 否 | 显示折扣，默认 false（body 非 true 即不显示） |
| showProcesses | boolean | 否 | 显示工序，默认 true（body 非 false 即显示） |
| showTabDetails | boolean | 否 | 显示页签明细，默认 false |

- **响应内容**: HTML 字节流（filename="quotation.html"）

#### 4.1.27 导出 Excel
- **功能**: 导出报价单 Excel（.xlsx）
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/export/excel`
- **鉴权**: 需登录
- **产出**: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`（直返 `Response`）
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**（可选）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| showDiscount | boolean | 否 | 显示折扣，默认 true（body 非 false 即显示） |
| includeRawData | boolean | 否 | 包含原始数据，默认 false |

- **响应内容**: Excel 字节流（filename=`{quotationNumber}.xlsx`）

#### 4.1.28 发送报价单邮件
- **功能**: 通过邮件发送报价单，可选附带 Excel
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/send`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| to | String | — | 收件人 |
| cc | String | — | 抄送 |
| subject | String | — | 主题 |
| body | String | — | 正文 |
| attachExcel | boolean | — | 是否附带 Excel，默认 false |

- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.29 获取 Excel 视图
- **功能**: 获取报价单 Excel 视图（v2）数据（列结构 + 行值）
- **方法**: GET
- **路径**: `/api/cpq/quotations/{id}/excel-view`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **查询参数**: `templateId` UUID（可选，求值上下文模板）
- **响应内容**: `ApiResponse<Map<String,Object>>`（Excel 视图结构 + 行数据）

#### 4.1.30 Excel 视图公式试算（dry-run）
- **功能**: 用临时列配置（不读模板/不落库）对某报价单逐行试算
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/excel-view/dry-run`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**: `ExcelDryRunRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| templateId | UUID | 否 | 求值上下文模板（供模板公式/SqlView） |
| columns | List<Map> | 否 | 临时列配置（含 CARD_FORMULA 的 formula/refs） |

- **响应内容**: `ApiResponse<Map<String,Object>>`（试算结果）

#### 4.1.31 更新 Excel 视图单元格
- **功能**: 编辑回写某行某列的 Excel 视图单元格值
- **方法**: PUT
- **路径**: `/api/cpq/quotations/{id}/excel-view`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| lineItemId | UUID | 是 | 行项 ID（缺失返回 400） |
| colKey | String | 是 | 列键（缺失返回 400） |
| value | Object | 否 | 新值 |

- **响应内容**: `ApiResponse<Void>`

#### 4.1.32 导出 Excel 视图
- **功能**: 导出 Excel 视图为 .xlsx
- **方法**: GET
- **路径**: `/api/cpq/quotations/{id}/export-excel-view`
- **鉴权**: 需登录
- **产出**: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`（直返 `Response`）
- **路径参数**: `id` UUID — 报价单 ID
- **响应内容**: Excel 字节流（filename=`{quotationNumber}-view.xlsx`）

#### 4.1.33 延长有效期
- **功能**: 延长报价单有效期
- **方法**: PUT
- **路径**: `/api/cpq/quotations/{id}/extend`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| newExpiryDate | String | 是* | 新有效期（ISO yyyy-MM-dd）；缺失时回退读 expiryDate |
| expiryDate | String | 否 | newExpiryDate 的别名（二者需至少一个，格式错误返回 400） |

- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.34 客户接受报价
- **功能**: 标记报价单被客户接受
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/accept`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.35 接受漂移并刷新版本
- **功能**: DRAFT 漂移检测：用户接受漂移后重算公式 + 更新 referenced_versions
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/refresh-versions`
- **鉴权**: 需登录（仅 SALES_REP 或 SYSTEM_ADMIN 有实际操作权，SALES_MANAGER 无权）
- **路径参数**: `id` UUID — 报价单 ID
- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.36 客户拒绝报价
- **功能**: 标记报价单被客户拒绝
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/reject-by-customer`
- **鉴权**: 需登录
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**: `{ comment: String }`（可选，拒绝原因）
- **响应内容**: `ApiResponse<QuotationDTO>`

#### 4.1.37 重新导入基础数据
- **功能**: 重新导入报价单基础数据（仅 DRAFT 可用），上传新 Excel 覆盖
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/reimport-basic-data`
- **鉴权**: `@RoleAllowed({"SALES_REP","SALES_MANAGER","SYSTEM_ADMIN"})`
- **消费**: `multipart/form-data`
- **路径参数**: `id` UUID — 报价单 ID
- **请求体**（表单）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | FileUpload | 是 | 新的 Excel 文件（.xlsx），缺失返回 400 |

- **响应内容**: `ApiResponse<ImportResultDTO>`（含 importRecordId、status、totalRows 等导入结果；失败抛 400）

#### 4.1.38 删除 driver 默认行（墓碑）
- **功能**: 将指定 driver 行追加到 deletedRowKeys 墓碑列表并立即重刷报价快照
- **方法**: POST
- **路径**: `/api/cpq/quotations/{qid}/line-items/{lid}/delete-driver-row`
- **鉴权**: 需登录
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| qid | UUID | 报价单 ID |
| lid | UUID | 行项 ID |

- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| componentId | UUID | 是 | 组件 ID（缺失返回 400） |
| effKey | String | 是 | 行有效键（缺失返回 400） |
| fp | String | 否 | 行指纹（默认空串） |

- **响应内容**: `ApiResponse<Void>`

#### 4.1.39 恢复所有 driver 默认行
- **功能**: 清空某组件的 deletedRowKeys 墓碑列表并立即重刷报价快照
- **方法**: POST
- **路径**: `/api/cpq/quotations/{qid}/line-items/{lid}/restore-driver-rows`
- **鉴权**: 需登录
- **路径参数**: `qid` UUID（报价单 ID）、`lid` UUID（行项 ID）
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| componentId | UUID | 是 | 组件 ID（缺失返回 400） |

- **响应内容**: `ApiResponse<Void>`

---

##### 附：QuotationDTO 字段全表

`QuotationDTO`（报价单 DTO）主要字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 报价单 ID |
| quotationNumber | String | 报价单编号 |
| customerId | UUID | 客户 ID |
| name | String | 名称 |
| contactId / contactName / contactPhone / contactEmail | UUID/String | 联系人信息 |
| projectName / opportunityId | String | 项目名/商机 ID |
| salesRepId | UUID | 销售代表 ID |
| quoteType / priority / stage | String | 报价类型/优先级/阶段 |
| expectedCloseDate | LocalDate | 预计成交日期 |
| status | String | 状态码 |
| totalAmount / originalAmount | BigDecimal | 总额/原始金额 |
| expiryDate | LocalDate | 有效期 |
| paymentTerms | String | 付款条件 |
| deliveryCycle | Integer | 交货周期 |
| systemDiscountRate / finalDiscountRate | BigDecimal | 系统折扣率/最终折扣率 |
| taxRate / taxAmount | BigDecimal | 税率/税额 |
| discountAdjustmentReason | String | 折扣调整原因 |
| isManuallyAdjusted | Boolean | 是否人工调整 |
| sourceQuotationId | UUID | 来源报价单 ID（复制） |
| assignedApproverId / assignedApproverName | UUID/String | 指派审批人 |
| remarks | String | 备注 |
| quoteCardStructure / quoteExcelStructure / costingCardStructure / costingExcelStructure | JsonNode | Phase2 报价单级 4 份结构快照 |
| quoteExcelColumns / costingExcelColumns | List<Map> | 带 display_format 的有效列（详情只读/比对用） |
| customerTemplateId | UUID | 客户报价模板 ID |
| costingCardTemplateId | UUID | 核价模板 ID |
| snapshotCustomerName/Level/Region/Industry/Address | String | 客户快照信息 |
| createdAt / updatedAt | OffsetDateTime | 创建/更新时间 |
| lineItems | LineItemDTO[] | 行项（详情视图填充） |
| approvalHistory | ApprovalDTO[] | 审批历史（详情视图填充） |
| referencedVersions | Map<String,Map<String,RefVersionEntry>> | 引用版本快照（仅 DRAFT） |
| hasDrift | boolean | 是否存在漂移（仅 DRAFT） |
| driftedRecords | DriftedRecordDTO[] | 漂移明细（hasDrift=true 时） |
| cardValuesWarming | boolean | 卡片值 warm 在飞标志，默认 false |

`QuotationDTO.LineItemDTO`（行项）主要字段：`id`、`productId`、`templateId`、`productPartNo`、`productName`、`customerPartNo`、`customerPartName`、`customerProductNo`、`customerDrawingNo`、`hfPartInfo`(生产料号详情)、`productAttributeValues`、`subtotal`、`systemDiscountRate`、`finalDiscountRate`、`discountAdjustmentReason`、`isManuallyAdjusted`、`sortOrder`、`processes`(ProcessDTO[])、`compositeProcesses`(List<Map> 组合工艺步骤)、`componentData`(ComponentDataDTO[])、`snapshot`(SnapshotDTO)、`partVersionLocked`、`productType`(SIMPLE/COMPOSITE)、`compositeType`(SIMPLE/COMPOSITE/PART)、`parentLineItemId`、`quoteCardValues`/`quoteExcelValues`/`costingCardValues`/`costingExcelValues`(4 份值 JSON 字符串)、以及 Step3 行级折扣字段 `annualVolume`/`discountSource`/`discountBaseAmount`/`discountRateApplied`/`lineDiscountAmount`/`lineUnitPrice`/`lineFinalPrice`/`lineTotalAmount`/`discountRuleCode`。

嵌套子结构：
- `ProcessDTO`：`id`、`processId`
- `ComponentDataDTO`：`id`、`componentId`、`tabName`、`rowData`、`deletedRowKeys`(墓碑数组 JSON，默认"[]")、`subtotal`、`sortOrder`
- `SnapshotDTO`：`id`、`productPartNo`、`productCategory`、`productSpecification`
- `ApprovalDTO`：`id`、`approverId`、`approverName`、`action`、`comment`、`actedAt`、`createdAt`
- `HfPartInfo`：`partNo`、`partName`、`specification`、`sizeInfo`、`statusCode`

---

### 4.2 QuotationAdminResource（报价单运维管理，SYSTEM_ADMIN 专用）

- **类级 @Path**：`/api/cpq/admin/quotations`
- **产出/消费**：`application/json`

#### 4.2.1 存量 DRAFT 草稿迁移（清 #ERROR 脏值）
- **功能**: 清掉草稿 quote_card_values 里的 `#ERROR[QUERY_ERROR]` 脏值（D1）。dryRun 默认只扫描；dryRun=false 逐单重烤（refreshDraftQuoteCards force=true），单单失败不中断
- **方法**: POST
- **路径**: `/api/cpq/admin/quotations/migrate-freeze-drafts`
- **鉴权**: `@RoleAllowed({"SYSTEM_ADMIN"})`
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| dryRun | boolean | 否 | 默认 true（安全扫描）；false 执行实际重烤 |

- **响应内容**: `ApiResponse<List<Map<String,Object>>>` — 每项含 `{ quotationId, quoteNo, before, errorLineCount(dryRun) / refreshedLines+after(非dryRun), status }`；status 取值 DRY_RUN / OK / STILL_ERROR / FAILED

---

### 4.3 CostingOrderResource（核价管理，PRICING_MANAGER/SYSTEM_ADMIN）

- **类级 @Path**：`/api/cpq/costing-orders`
- **类级鉴权**：`@RoleAllowed({"PRICING_MANAGER","SYSTEM_ADMIN"})`
- **产出/消费**：`application/json`
- **备注**：核价通过/驳回动作由 QuotationResource 提供（`/quotations/{id}/costing-approve`、`/quotations/{id}/costing-reject`）

#### 4.3.1 核价工作台列表
- **功能**: 核价管理列表（多状态过滤 + 关键字搜索 + 排序）
- **方法**: GET
- **路径**: `/api/cpq/costing-orders`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | List<String> | 否 | 状态过滤（可多值）PENDING/APPROVED/REJECTED/WITHDRAWN；不传返回全部 |
| keyword | String | 否 | 按报价单号模糊搜索（不区分大小写） |
| sort | String | 否 | 排序字段 "status"/"updatedAt"；默认按 entered_costing_at DESC |

- **响应内容**: `ApiResponse<List<CostingOrderListItemDTO>>`

| 字段 | 类型 | 说明 |
|------|------|------|
| costingOrderId | UUID | 核价单 ID |
| quotationId | UUID | 关联报价单 ID |
| costingOrderNumber | String | 核价单编号（如 HJ-20260629-0001） |
| quotationNumber | String | 报价单编号 |
| customerName | String | 快照客户名 |
| submittedByName | String | 提交人 |
| currency | String | 货币码（当前统一 CNY） |
| status | String | 英文码 PENDING/APPROVED/REJECTED/WITHDRAWN（前端映射中文） |
| rejectReason | String | 驳回原因（REJECTED 时非空） |
| createdAt | OffsetDateTime | entered_costing_at |
| updatedAt | OffsetDateTime | 更新时间 |

#### 4.3.2 核价单详情
- **功能**: 单条核价单详情（含冻结副本 frozenDto）
- **方法**: GET
- **路径**: `/api/cpq/costing-orders/{coid}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `coid` UUID — 核价单 ID
- **响应内容**: `ApiResponse<CostingOrderDetailDTO>`

| 字段 | 类型 | 说明 |
|------|------|------|
| costingOrderId | UUID | 核价单 ID |
| quotationId | UUID | 关联报价单 ID |
| costingOrderNumber | String | 核价单编号 |
| status | String | 英文码（前端映射中文） |
| rejectReason | String | 驳回原因（REJECTED 时非空） |
| totalAmount | BigDecimal | 核价总金额 |
| frozenDto | String | 冻结副本 JSON 原始字符串（含 costingCardStructure/gvDefs 等键，前端反序列化渲染） |
| createdAt | OffsetDateTime | 创建时间（entered_costing_at） |
| reviewedAt | OffsetDateTime | 审核时间（reviewed_at） |

---

### 4.4 QuotationRefDataResource（报价单引用数据，全局变量视图）

- **类级 @Path**：`/api/cpq/quotations/{qid}/ref-data`
- **类级鉴权**：`@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`
- **产出/消费**：`application/json`
- **背景**：V212 报价单引用数据（ADR-002 §4.1 端点 3、4）

#### 4.4.1 实时拉取引用数据（仅 DRAFT）
- **功能**: 按 quotation.customer_template_id 查全局变量绑定列表，逐个实时调 GlobalVariableDataLoader 组装返回。仅 DRAFT 可用
- **方法**: GET
- **路径**: `/api/cpq/quotations/{qid}/ref-data`
- **鉴权**: 需登录（四角色任一）
- **路径参数**: `qid` UUID — 报价单 ID
- **异常**: 报价单不存在返回 404 QUOTATION_NOT_FOUND；非 DRAFT 返回 400 QUOTATION_NOT_DRAFT（应改用 snapshot 端点）；无模板/无绑定返回空数组
- **响应内容**: `ApiResponse<List<BoundGvViewDTO>>`（按 displayOrder 升序）

| 字段 | 类型 | 说明 |
|------|------|------|
| code | String | 全局变量码 |
| name | String | 变量名 |
| varType | String | LOOKUP_TABLE / SCALAR |
| unit | String | 单位 |
| displayOrder | int | 显示顺序 |
| fetchedAt | OffsetDateTime | 实时拉取时刻 |
| columns | List<String> | 列顺序（key_columns + [value_column]） |
| rows | List<Map> | 整表行数据 |

#### 4.4.2 读取提交快照引用数据（非 DRAFT）
- **功能**: 直接读 quotation.bound_global_variables_snapshot JSONB 反序列化返回。任意非 DRAFT 状态可用
- **方法**: GET
- **路径**: `/api/cpq/quotations/{qid}/ref-data/snapshot`
- **鉴权**: 需登录
- **路径参数**: `qid` UUID — 报价单 ID
- **异常**: 报价单不存在返回 404 QUOTATION_NOT_FOUND；DRAFT 返回 400 QUOTATION_IS_DRAFT（应改用 /ref-data 端点）；快照为空返回空数组
- **响应内容**: `ApiResponse<List<BoundGvSnapshotItem>>`（结构与 BoundGvViewDTO 一致，仅 `fetchedAt` 改为 `snapshotAt` 快照时刻）

| 字段 | 类型 | 说明 |
|------|------|------|
| code | String | 全局变量码 |
| name | String | 变量名 |
| varType | String | LOOKUP_TABLE / SCALAR |
| unit | String | 单位 |
| displayOrder | int | 显示顺序 |
| snapshotAt | OffsetDateTime | 快照时刻（对应 JSONB snapshotAt 字段） |
| columns | List<String> | 列顺序（key_columns + [value_column]） |
| rows | List<Map> | 快照行数据 |
## 五、核价

> 基址 `http://localhost:8081`；鉴权=会话 Cookie。带 `@RoleAllowed` 的端点需登录且具备对应角色，请求头须携带会话 Cookie；无该注解者不校验。
> 统一响应体 `ApiResponse<T> = { code, message, data }`，个别端点直返二进制/实体，已在各处标注。

---

### 5.1 CostingBasicDataResource（核价基础数据：价格版本 + 元素/材料价 + 汇率）

类级 `@Path`: `/api/cpq/costing-basic`
类级鉴权: `@RoleAllowed({SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN})`（写操作方法另收窄为 `PRICING_MANAGER, SYSTEM_ADMIN`）。

版本主表用 `/versions`；明细按 kind 分三个子路径（elements / materials / rates）以减少前端误用。

#### 版本列表查询（中文）
- **功能**: 按种类与状态查询核价价格版本列表
- **方法**: GET
- **路径**: `/api/cpq/costing-basic/versions`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| kind | String | 否 | 版本种类 ELEMENT / MATERIAL / EXCHANGE |
| status | String | 否 | 版本状态过滤 |

- **响应内容**: `ApiResponse<List<CostingPriceVersionDTO>>`（字段见下方 CostingPriceVersionDTO）

#### 版本详情（中文）
- **功能**: 按 ID 获取单个价格版本
- **方法**: GET
- **路径**: `/api/cpq/costing-basic/versions/{id}`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 版本 ID |

- **响应内容**: `ApiResponse<CostingPriceVersionDTO>`

#### 新建版本（中文）
- **功能**: 创建新的价格版本（草稿）
- **方法**: POST
- **路径**: `/api/cpq/costing-basic/versions`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `CreateVersionRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| versionKind | String | 是 | 版本种类 ELEMENT / MATERIAL / EXCHANGE |
| versionNumber | String | 是 | 版本号（用户自定义，如 2000） |
| notes | String | 否 | 备注 |
| isDefault | Boolean | 否 | 是否同时设为默认（仅在 publish 后生效） |

- **响应内容**: `ApiResponse<CostingPriceVersionDTO>`

#### 更新版本（中文）
- **功能**: 修改现有价格版本的基本信息
- **方法**: PUT
- **路径**: `/api/cpq/costing-basic/versions/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，版本 ID）
- **请求体**: `CreateVersionRequest`（字段同「新建版本」；注意本端点未加 @Valid）
- **响应内容**: `ApiResponse<CostingPriceVersionDTO>`

#### 发布版本（中文）
- **功能**: 将草稿版本发布为正式版本
- **方法**: POST
- **路径**: `/api/cpq/costing-basic/versions/{id}/publish`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，版本 ID）
- **响应内容**: `ApiResponse<CostingPriceVersionDTO>`

#### 归档版本（中文）
- **功能**: 归档一个价格版本
- **方法**: POST
- **路径**: `/api/cpq/costing-basic/versions/{id}/archive`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，版本 ID）
- **响应内容**: `ApiResponse<CostingPriceVersionDTO>`

#### 设为默认版本（中文）
- **功能**: 将某版本设为该种类的默认版本
- **方法**: POST
- **路径**: `/api/cpq/costing-basic/versions/{id}/set-default`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，版本 ID）
- **响应内容**: `ApiResponse<CostingPriceVersionDTO>`

#### 派生新草稿（中文）
- **功能**: 基于现有版本复制出一个新草稿版本
- **方法**: POST
- **路径**: `/api/cpq/costing-basic/versions/{id}/new-draft`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，源版本 ID）
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| versionNumber | String | 否 | 新草稿版本号 |

- **响应内容**: `ApiResponse<CostingPriceVersionDTO>`

#### 删除版本（中文）
- **功能**: 删除一个价格版本
- **方法**: DELETE
- **路径**: `/api/cpq/costing-basic/versions/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，版本 ID）
- **响应内容**: `ApiResponse<Void>`

#### 元素价格列表（中文）
- **功能**: 查询指定版本下的元素价格明细
- **方法**: GET
- **路径**: `/api/cpq/costing-basic/versions/{versionId}/elements`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `versionId`（UUID，版本 ID）
- **响应内容**: `ApiResponse<List<ElementPriceDTO>>`

#### 新增元素价格（中文）
- **功能**: 在指定版本下新增一条元素价格
- **方法**: POST
- **路径**: `/api/cpq/costing-basic/versions/{versionId}/elements`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `versionId`（UUID，版本 ID）
- **请求体**: `ElementPriceDTO`（字段见下方）
- **响应内容**: `ApiResponse<ElementPriceDTO>`

#### 更新元素价格（中文）
- **功能**: 修改单条元素价格
- **方法**: PUT
- **路径**: `/api/cpq/costing-basic/elements/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，元素价格记录 ID）
- **请求体**: `ElementPriceDTO`
- **响应内容**: `ApiResponse<ElementPriceDTO>`

#### 删除元素价格（中文）
- **功能**: 删除单条元素价格
- **方法**: DELETE
- **路径**: `/api/cpq/costing-basic/elements/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，元素价格记录 ID）
- **响应内容**: `ApiResponse<Void>`

#### 材料价格列表（中文）
- **功能**: 查询指定版本下的材料价格明细
- **方法**: GET
- **路径**: `/api/cpq/costing-basic/versions/{versionId}/materials`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `versionId`（UUID，版本 ID）
- **响应内容**: `ApiResponse<List<MaterialPriceDTO>>`

#### 新增材料价格（中文）
- **功能**: 在指定版本下新增一条材料价格
- **方法**: POST
- **路径**: `/api/cpq/costing-basic/versions/{versionId}/materials`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `versionId`（UUID，版本 ID）
- **请求体**: `MaterialPriceDTO`（字段见下方）
- **响应内容**: `ApiResponse<MaterialPriceDTO>`

#### 更新材料价格（中文）
- **功能**: 修改单条材料价格
- **方法**: PUT
- **路径**: `/api/cpq/costing-basic/materials/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，材料价格记录 ID）
- **请求体**: `MaterialPriceDTO`
- **响应内容**: `ApiResponse<MaterialPriceDTO>`

#### 删除材料价格（中文）
- **功能**: 删除单条材料价格
- **方法**: DELETE
- **路径**: `/api/cpq/costing-basic/materials/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，材料价格记录 ID）
- **响应内容**: `ApiResponse<Void>`

#### 汇率列表（中文）
- **功能**: 查询指定版本下的汇率明细
- **方法**: GET
- **路径**: `/api/cpq/costing-basic/versions/{versionId}/rates`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `versionId`（UUID，版本 ID）
- **响应内容**: `ApiResponse<List<ExchangeRateDTO>>`

#### 新增汇率（中文）
- **功能**: 在指定版本下新增一条汇率
- **方法**: POST
- **路径**: `/api/cpq/costing-basic/versions/{versionId}/rates`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `versionId`（UUID，版本 ID）
- **请求体**: `ExchangeRateDTO`（字段见下方）
- **响应内容**: `ApiResponse<ExchangeRateDTO>`

#### 更新汇率（中文）
- **功能**: 修改单条汇率
- **方法**: PUT
- **路径**: `/api/cpq/costing-basic/rates/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，汇率记录 ID）
- **请求体**: `ExchangeRateDTO`
- **响应内容**: `ApiResponse<ExchangeRateDTO>`

#### 删除汇率（中文）
- **功能**: 删除单条汇率
- **方法**: DELETE
- **路径**: `/api/cpq/costing-basic/rates/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，汇率记录 ID）
- **响应内容**: `ApiResponse<Void>`

**CostingPriceVersionDTO 字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 版本 ID |
| versionKind | String | 版本种类 ELEMENT / MATERIAL / EXCHANGE |
| versionNumber | String | 版本号 |
| status | String | 状态 |
| notes | String | 备注 |
| isDefault | Boolean | 是否默认 |
| publishedAt | OffsetDateTime | 发布时间 |
| publishedBy | UUID | 发布人 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |
| createdBy | UUID | 创建人 |
| rowCount | Long | 该版本下明细行数（前端展示用） |

**ElementPriceDTO 字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 记录 ID |
| versionId | UUID | 所属版本 ID |
| elementCode | String | 元素代码 |
| costingPrice | BigDecimal | 核价价格 |
| marketRefPrice | BigDecimal | 市场参考价 |
| sourceUrl | String | 来源 URL |
| sourceName | String | 来源名称 |
| sourceRule | String | 来源规则 |
| currency | String | 币种 |
| unit | String | 单位 |
| discountRate | BigDecimal | 折扣率 |
| sortOrder | Integer | 排序号 |

**MaterialPriceDTO 字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 记录 ID |
| versionId | UUID | 所属版本 ID |
| materialNo | String | 材料号 |
| brandName | String | 品牌名称 |
| spec | String | 规格 |
| dimension | String | 尺寸 |
| costingPrice | BigDecimal | 核价价格 |
| marketRefPrice | BigDecimal | 市场参考价 |
| sourceUrl | String | 来源 URL |
| sourceName | String | 来源名称 |
| sourceRule | String | 来源规则 |
| currency | String | 币种 |
| unit | String | 单位 |
| discountRate | BigDecimal | 折扣率 |
| sortOrder | Integer | 排序号 |

**ExchangeRateDTO 字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 记录 ID |
| versionId | UUID | 所属版本 ID |
| fromCurrency | String | 源币种 |
| toCurrency | String | 目标币种 |
| costingRate | BigDecimal | 核价汇率 |
| marketRate | BigDecimal | 市场汇率 |
| rateRule | String | 汇率规则 |
| sourceUrl | String | 来源 URL |
| sortOrder | Integer | 排序号 |

---

### 5.2 CostingPartDataResource（料号级核价数据：8 类 list/save/delete）

类级 `@Path`: `/api/cpq/costing-part`
类级鉴权: `@RoleAllowed({SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN})`（save/delete 收窄为 `PRICING_MANAGER, SYSTEM_ADMIN`）。
资源粒度：8 类各一组 list / save / delete；list 按料号过滤。请求体与响应体均为对应实体本身（非 DTO）。

#### 1. 工序单价列表（中文）
- **功能**: 按料号与成本类型查询工序级单价
- **方法**: GET
- **路径**: `/api/cpq/costing-part/process-cost`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| hfPartNo | String | 否 | HF 料号 |
| costType | String | 否 | 成本类型（见 CostingPartProcessCost.VALID_TYPES） |

- **响应内容**: `ApiResponse<List<CostingPartProcessCost>>`

#### 1. 保存工序单价（中文）
- **功能**: 新增或更新一条工序级单价
- **方法**: POST
- **路径**: `/api/cpq/costing-part/process-cost`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `CostingPartProcessCost`（字段见下方）
- **响应内容**: `ApiResponse<CostingPartProcessCost>`

#### 1. 删除工序单价（中文）
- **功能**: 删除一条工序级单价
- **方法**: DELETE
- **路径**: `/api/cpq/costing-part/process-cost/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，记录 ID）
- **响应内容**: `ApiResponse<Void>`

#### 2. 模具工装列表（中文）
- **功能**: 按料号查询模具/工装成本
- **方法**: GET
- **路径**: `/api/cpq/costing-part/tooling`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**: `hfPartNo`（String，否，HF 料号）
- **响应内容**: `ApiResponse<List<CostingPartToolingCost>>`

#### 2. 保存模具工装（中文）
- **功能**: 新增或更新一条模具/工装成本（落库前按 I/J/K 自动算 unitPrice）
- **方法**: POST
- **路径**: `/api/cpq/costing-part/tooling`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `CostingPartToolingCost`（字段见下方）
- **响应内容**: `ApiResponse<CostingPartToolingCost>`

#### 2. 删除模具工装（中文）
- **功能**: 删除一条模具/工装成本
- **方法**: DELETE
- **路径**: `/api/cpq/costing-part/tooling/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，记录 ID）
- **响应内容**: `ApiResponse<Void>`

#### 3. 材料 BOM 列表（中文）
- **功能**: 按料号查询材料 BOM
- **方法**: GET
- **路径**: `/api/cpq/costing-part/material-bom`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**: `hfPartNo`（String，否，HF 料号）
- **响应内容**: `ApiResponse<List<CostingPartMaterialBom>>`

#### 3. 保存材料 BOM（中文）
- **功能**: 新增或更新一条材料 BOM
- **方法**: POST
- **路径**: `/api/cpq/costing-part/material-bom`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `CostingPartMaterialBom`（字段见下方）
- **响应内容**: `ApiResponse<CostingPartMaterialBom>`

#### 3. 删除材料 BOM（中文）
- **功能**: 删除一条材料 BOM
- **方法**: DELETE
- **路径**: `/api/cpq/costing-part/material-bom/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，记录 ID）
- **响应内容**: `ApiResponse<Void>`

#### 4. 元素 BOM 列表（中文）
- **功能**: 按投入材料号查询元素 BOM
- **方法**: GET
- **路径**: `/api/cpq/costing-part/element-bom`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**: `inputMaterialNo`（String，否，投入材料号）
- **响应内容**: `ApiResponse<List<CostingPartElementBom>>`

#### 4. 保存元素 BOM（中文）
- **功能**: 新增或更新一条元素 BOM
- **方法**: POST
- **路径**: `/api/cpq/costing-part/element-bom`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `CostingPartElementBom`（字段见下方）
- **响应内容**: `ApiResponse<CostingPartElementBom>`

#### 4. 删除元素 BOM（中文）
- **功能**: 删除一条元素 BOM
- **方法**: DELETE
- **路径**: `/api/cpq/costing-part/element-bom/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，记录 ID）
- **响应内容**: `ApiResponse<Void>`

#### 5. 质量检验列表（中文）
- **功能**: 按料号与阶段查询质量检验项
- **方法**: GET
- **路径**: `/api/cpq/costing-part/quality-check`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| hfPartNo | String | 否 | HF 料号 |
| stage | String | 否 | 阶段 INCOMING / SEMI_FINISHED |

- **响应内容**: `ApiResponse<List<CostingPartQualityCheck>>`

#### 5. 保存质量检验（中文）
- **功能**: 新增或更新一条质量检验项
- **方法**: POST
- **路径**: `/api/cpq/costing-part/quality-check`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `CostingPartQualityCheck`（字段见下方）
- **响应内容**: `ApiResponse<CostingPartQualityCheck>`

#### 5. 删除质量检验（中文）
- **功能**: 删除一条质量检验项
- **方法**: DELETE
- **路径**: `/api/cpq/costing-part/quality-check/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，记录 ID）
- **响应内容**: `ApiResponse<Void>`

#### 6. 电镀方案列表（中文）
- **功能**: 查询电镀方案；platingNo 为空时返回全部电镀方案
- **方法**: GET
- **路径**: `/api/cpq/costing-part/plating`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**: `platingNo`（String，否，电镀方案号；为空返回全部）
- **响应内容**: `ApiResponse<List<CostingPartPlating>>`

#### 6. 保存电镀方案（中文）
- **功能**: 新增或更新一条电镀方案
- **方法**: POST
- **路径**: `/api/cpq/costing-part/plating`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `CostingPartPlating`（字段见下方）
- **响应内容**: `ApiResponse<CostingPartPlating>`

#### 6. 删除电镀方案（中文）
- **功能**: 删除一条电镀方案
- **方法**: DELETE
- **路径**: `/api/cpq/costing-part/plating/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，记录 ID）
- **响应内容**: `ApiResponse<Void>`

#### 6.b 电镀费用列表（只读）（中文）
- **功能**: 按料号查询电镀费用（只读，无 save/delete）
- **方法**: GET
- **路径**: `/api/cpq/costing-part/plating-fee`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**: `hfPartNo`（String，否，HF 料号）
- **响应内容**: `ApiResponse<List<PlatingFee>>`（字段见下方）

#### 7. 设计成本列表（中文）
- **功能**: 按料号查询设计成本
- **方法**: GET
- **路径**: `/api/cpq/costing-part/design-cost`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**: `hfPartNo`（String，否，HF 料号）
- **响应内容**: `ApiResponse<List<CostingPartDesignCost>>`

#### 7. 保存设计成本（中文）
- **功能**: 新增或更新一条设计成本
- **方法**: POST
- **路径**: `/api/cpq/costing-part/design-cost`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `CostingPartDesignCost`（字段见下方）
- **响应内容**: `ApiResponse<CostingPartDesignCost>`

#### 7. 删除设计成本（中文）
- **功能**: 删除一条设计成本
- **方法**: DELETE
- **路径**: `/api/cpq/costing-part/design-cost/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，记录 ID）
- **响应内容**: `ApiResponse<Void>`

#### 8. 重量查询（中文）
- **功能**: 按料号查询单件重量（返回单条）
- **方法**: GET
- **路径**: `/api/cpq/costing-part/weight`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**: `hfPartNo`（String，否，HF 料号）
- **响应内容**: `ApiResponse<CostingPartWeight>`（字段见下方）

#### 8. 保存重量（中文）
- **功能**: 新增或更新单件重量
- **方法**: POST
- **路径**: `/api/cpq/costing-part/weight`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `CostingPartWeight`（字段见下方）
- **响应内容**: `ApiResponse<CostingPartWeight>`

#### 8. 删除重量（中文）
- **功能**: 删除一条重量记录
- **方法**: DELETE
- **路径**: `/api/cpq/costing-part/weight/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，记录 ID）
- **响应内容**: `ApiResponse<Void>`

**CostingPartProcessCost 字段**（表 `costing_part_process_cost`；VALID_TYPES = LABOR / DEPRECIATION / ENERGY_DEDICATED / ENERGY_SHARED / CONSUMABLE / MATERIAL_PROC / SEMI_FINISHED_PROC / POST_PROC）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| hfPartNo | String | HF 料号（非空） |
| partVersion | Integer | 料号版本，与 hfPartNo 组成业务唯一键，默认 2000 |
| processNo | String | 工序号（非空） |
| processName | String | 工序名称 |
| costType | String | 成本类型（非空，见 VALID_TYPES） |
| unitPrice | BigDecimal | 单价（非空，18,6） |
| currency | String | 币种，默认 CNY |
| unit | String | 单位，默认 KG |
| refCalcVersion | String | 参考计算版本 |
| isActive | Boolean | 是否启用，默认 true |
| notes | String | 备注 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

**CostingPartToolingCost 字段**（表 `costing_part_tooling_cost`；unitPrice = toolingUnitCost / processCount / cycleCount 应用层/生命周期回调自动计算）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| hfPartNo | String | HF 料号（非空） |
| partVersion | Integer | 料号版本，默认 2000 |
| processNo | String | 工序号（非空） |
| processName | String | 工序名称 |
| seqNo | Integer | 序号（非空） |
| toolingNo | String | 模具/工装号 |
| toolingUnitCost | BigDecimal | 模具单位成本（非空，18,4） |
| processCount | Integer | 工序次数 |
| cycleCount | Integer | 循环次数 |
| unitPrice | BigDecimal | 单价（18,6，自动算 I/J/K 后落库） |
| currency | String | 币种，默认 CNY |
| unit | String | 单位，默认 PCS |
| isActive | Boolean | 是否启用，默认 true |
| notes | String | 备注 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

**CostingPartMaterialBom 字段**（表 `costing_part_material_bom`）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| hfPartNo | String | HF 料号（非空） |
| partVersion | Integer | 料号版本，默认 2000 |
| seqNo | Integer | 序号（非空） |
| inputMaterialNo | String | 投入材料号 |
| processNo | String | 工序号 |
| processName | String | 工序名称 |
| inputQty | BigDecimal | 投入数量（18,6） |
| inputUnit | String | 投入单位 |
| outputQty | BigDecimal | 产出数量（18,6） |
| outputUnit | String | 产出单位 |
| outputLossRate | BigDecimal | 产出损耗率（8,4） |
| fixedLossQty | BigDecimal | 固定损耗量（18,6） |
| lossRate | BigDecimal | 损耗率（8,4） |
| isActive | Boolean | 是否启用，默认 true |
| notes | String | 备注 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

**CostingPartElementBom 字段**（表 `costing_part_element_bom`）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| inputMaterialNo | String | 投入材料号（非空，业务唯一键） |
| partVersion | Integer | 料号版本，与 inputMaterialNo 组成唯一键，默认 2000 |
| seqNo | Integer | 序号（非空） |
| elementCode | String | 元素代码（非空） |
| compositionPct | BigDecimal | 成分占比（非空，8,4） |
| lossRate | BigDecimal | 损耗率（8,4） |
| isActive | Boolean | 是否启用，默认 true |
| notes | String | 备注 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

**CostingPartQualityCheck 字段**（表 `costing_part_quality_check`；VALID_STAGES = INCOMING / SEMI_FINISHED）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| hfPartNo | String | HF 料号（非空） |
| partVersion | Integer | 料号版本，默认 2000 |
| stage | String | 阶段（非空，INCOMING / SEMI_FINISHED） |
| primarySeqNo | Integer | 主序号 |
| seqNo | Integer | 序号（非空） |
| requirementCode | String | 要求代码 |
| requirementDesc | String | 要求描述 |
| scrapRate | BigDecimal | 报废率（8,4） |
| isActive | Boolean | 是否启用，默认 true |
| notes | String | 备注 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

**CostingPartPlating 字段**（表 `costing_part_plating`）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| platingNo | String | 电镀方案号（非空，业务唯一键） |
| partVersion | Integer | 料号版本，与 platingNo 组成唯一键，默认 2000 |
| versionNumber | String | 版本号（非空） |
| seqNo | Integer | 序号（非空） |
| elementAttr | String | 元素属性 |
| platingAreaCm2 | BigDecimal | 电镀面积 cm²（18,6） |
| layerThicknessUm | BigDecimal | 镀层厚度 μm（18,6） |
| requirement | String | 要求 |
| isActive | Boolean | 是否启用，默认 true |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

**PlatingFee 字段**（表 `costing_part_plating_fee`；核价侧电镀费用，无 customer/version 维度）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| hfPartNo | String | HF 料号（非空） |
| partVersion | Integer | 料号版本，默认 2000 |
| platingPlanCode | String | 电镀方案代码 |
| planVersion | String | 方案版本 |
| platingProcessFee | BigDecimal | 电镀工序费（18,4） |
| platingMaterialFee | BigDecimal | 电镀材料费（18,4） |
| currency | String | 币种 |
| priceUnit | String | 价格单位 |
| defectRate | BigDecimal | 不良率（10,4） |
| isActive | Boolean | 是否启用，默认 true |
| notes | String | 备注 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

**CostingPartDesignCost 字段**（表 `costing_part_design_cost`）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| hfPartNo | String | HF 料号（非空） |
| partVersion | Integer | 料号版本，默认 2000 |
| designDrawingNo | String | 设计图号 |
| versionNumber | String | 版本号 |
| designProcFee | BigDecimal | 设计工序费（18,4） |
| designMaterialFee | BigDecimal | 设计材料费（18,4） |
| currency | String | 币种，默认 CNY |
| unit | String | 单位，默认 KG |
| lossRate | BigDecimal | 损耗率（8,4） |
| isActive | Boolean | 是否启用，默认 true |
| notes | String | 备注 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

**CostingPartWeight 字段**（表 `costing_part_weight`）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| hfPartNo | String | HF 料号（非空） |
| partVersion | Integer | 料号版本，默认 2000 |
| weightGPerPcs | BigDecimal | 单件重量（克/件，非空，18,6） |
| isActive | Boolean | 是否启用，默认 true |
| notes | String | 备注 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

---

### 5.3 CostingSheetResource（核价单：核价表 + 报价/核价比对视图）

类级 `@Path`: `/api/cpq/quotations`（挂在报价单资源路径下，按 quotationId 取核价数据）。类无类级鉴权注解，各端点单独声明。

#### 获取核价表（中文）
- **功能**: 按报价单 ID 获取对应核价表
- **方法**: GET
- **路径**: `/api/cpq/quotations/{id}/costing-sheet`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，报价单 ID）
- **响应内容**: `ApiResponse<CostingSheetDTO>`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 核价表 ID |
| quotationId | UUID | 关联报价单 ID |
| costingTemplateId | UUID | 核价模板 ID |
| costingTemplateName | String | 核价模板名称（回填自模板） |
| columns | List<Map<String,Object>> | 模板列定义（解析自模板 columns JSON） |
| rows | List<Map<String,Object>> | 核价数据行（解析自 sheet rows JSON） |
| totalCost | BigDecimal | 总成本 |
| status | String | 状态 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

#### 获取比对视图（中文）
- **功能**: 构建报价 vs 核价的比对模型（基础字段差异 + 标签分组差异 + 汇总）
- **方法**: GET
- **路径**: `/api/cpq/quotations/{id}/comparison`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，报价单 ID）
- **响应内容**: `ApiResponse<ComparisonDTO>`

ComparisonDTO 顶层字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| basicFieldDiffs | List<BasicFieldDiff> | Tab 1：基础数据字段差异 |
| tagGroups | List<TagGroup> | Tab 2：公式/业务标签分组 |
| summary | BigDecimalSummary | 汇总 |

BasicFieldDiff：`variableCode`(String 变量代码)、`variableLabel`(String 变量标签)、`costingValue`(Object 核价值)、`quotationValue`(Object 报价值)、`diffStatus`(String：SAME/MODIFIED/MISSING/NEW)。
TagGroup：`groupName`(String 分组名)、`tags`(List<TagDiff>)。
TagDiff：`tag`(String comparison_tag 代码)、`tagLabel`(String 标签名)、`costingValue`(Object)、`quotationValue`(Object)、`delta`(Object 差值)、`deltaPct`(String 差值百分比，如 "5.00%")。
BigDecimalSummary：`costingTotal`(Object 核价合计)、`quotationTotal`(Object 报价合计)、`profit`(Object 利润)、`profitRate`(String 利润率)、`modifiedFieldsCount`(Integer 变更字段数)。

#### 导出比对视图 Excel（中文）
- **功能**: 按前端已算好的双行对比模型导出比对 Excel（后端只写值+填色，不重算路径/公式）
- **方法**: POST
- **路径**: `/api/cpq/quotations/{id}/comparison/export`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，报价单 ID，仅用于文件名）
- **请求体**: `ComparisonExportRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| columns | List<Column> | 是 | 列定义列表 |
| rows | List<Row> | 是 | 数据行列表 |

Column：`tag`(String 标签)、`label`(String 列名)、`groupName`(String 分组名)。
Row：`partNo`(String 料号)、`presence`(String：BOTH/QUOTE_ONLY/COSTING_ONLY)、`cells`(Map<String,Cell>，key=tag)。
Cell：`quote`(Object 报价值)、`costing`(Object 核价值)、`highlighted`(boolean 是否高亮)。

- **响应内容**: 直返二进制 xlsx（`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，`Content-Disposition: attachment; filename="comparison-{id}.xlsx"`），非 ApiResponse 包装。

---

### 5.4 CostingTemplateResource（核价模板：CRUD + 版本流转 + 关联报价/核价模板）

类级 `@Path`: `/api/cpq/costing-templates`。类无类级鉴权注解，各端点单独声明。

#### 核价模板列表（中文）
- **功能**: 按状态与关联模板反查核价模板列表
- **方法**: GET
- **路径**: `/api/cpq/costing-templates`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 否 | 状态过滤 |
| linkedTemplateId | UUID | 否 | 按关联的 Excel 模板 ID 反查（V73 起；V74 起移除 categoryId） |

- **响应内容**: `ApiResponse<List<CostingTemplateDTO>>`

#### 核价模板详情（中文）
- **功能**: 按 ID 获取核价模板
- **方法**: GET
- **路径**: `/api/cpq/costing-templates/{id}`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，核价模板 ID）
- **响应内容**: `ApiResponse<CostingTemplateDTO>`

#### 新建核价模板（中文）
- **功能**: 创建核价模板
- **方法**: POST
- **路径**: `/api/cpq/costing-templates`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **请求体**: `CreateCostingTemplateRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | 是 | 模板名称 |
| isDefault | Boolean | 否 | 是否默认 |
| version | String | 否 | 版本 |
| description | String | 否 | 描述 |
| columns | Object | 否 | 列定义（任意 JSON） |
| referencedVariables | Object | 否 | 引用变量（任意 JSON） |
| seriesId | UUID | 否 | 升级版本时传入的系列 ID |
| linkedTemplateId | UUID | 否 | 关联的模板配置中的模板 ID（报价/核价模板，V73） |

- **响应内容**: `ApiResponse<CostingTemplateDTO>`

#### 更新核价模板（中文）
- **功能**: 修改核价模板
- **方法**: PUT
- **路径**: `/api/cpq/costing-templates/{id}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，核价模板 ID）
- **请求体**: `CreateCostingTemplateRequest`（字段同上；本端点未加 @Valid）
- **响应内容**: `ApiResponse<CostingTemplateDTO>`

#### 删除核价模板（中文）
- **功能**: 删除核价模板
- **方法**: DELETE
- **路径**: `/api/cpq/costing-templates/{id}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，核价模板 ID）
- **响应内容**: `ApiResponse<Void>`

#### 派生新草稿（中文）
- **功能**: 从已归档/已发布模板派生新草稿，便于在原模板基础上修改
- **方法**: POST
- **路径**: `/api/cpq/costing-templates/{id}/new-draft`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，源模板 ID）
- **响应内容**: `ApiResponse<CostingTemplateDTO>`

#### 设置关联模板（中文）
- **功能**: 单独设置/解除核价模板与报价或核价模板的关联（V73）
- **方法**: PUT
- **路径**: `/api/cpq/costing-templates/{id}/linked-template`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，核价模板 ID）
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| templateId | UUID | 否 | 目标模板 ID；缺省/空=解除关联，有值=校验存在后设置 |

- **响应内容**: `ApiResponse<CostingTemplateDTO>`

#### 发布核价模板（中文）
- **功能**: 发布核价模板
- **方法**: POST
- **路径**: `/api/cpq/costing-templates/{id}/publish`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，核价模板 ID）
- **响应内容**: `ApiResponse<CostingTemplateDTO>`

#### 归档核价模板（中文）
- **功能**: 归档核价模板
- **方法**: POST
- **路径**: `/api/cpq/costing-templates/{id}/archive`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，核价模板 ID）
- **响应内容**: `ApiResponse<CostingTemplateDTO>`

**CostingTemplateDTO 字段**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |
| seriesId | UUID | 系列 ID（版本族） |
| name | String | 模板名称 |
| linkedTemplateId | UUID | 关联的 template 表模板 ID（V73） |
| linkedTemplateName | String | 关联模板名称（回填） |
| linkedTemplateKind | String | 关联模板种类 QUOTATION / COSTING（回填） |
| linkedTemplateVersion | String | 关联模板版本（回填） |
| isDefault | Boolean | 是否默认 |
| version | String | 版本 |
| status | String | 状态 |
| description | String | 描述 |
| columns | String | 列定义（JSON 字符串） |
| referencedVariables | String | 引用变量（JSON 字符串） |
| createdBy | UUID | 创建人 |
| publishedAt | OffsetDateTime | 发布时间 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

---

### 5.5 CostingSummaryResource（核价汇总单：CRUD + 计算 + 发布 + 结果/覆盖值）

类级 `@Path`: `/api/cpq/costing-summary`
类级鉴权: `@RoleAllowed({SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN})`（写操作收窄为 `PRICING_MANAGER, SYSTEM_ADMIN`）。

#### 汇总单列表（中文）
- **功能**: 按料号与状态查询核价汇总单
- **方法**: GET
- **路径**: `/api/cpq/costing-summary`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| hfPartNo | String | 否 | HF 料号 |
| status | String | 否 | 状态 DRAFT/COMPUTED/PUBLISHED/ARCHIVED |

- **响应内容**: `ApiResponse<List<CostingSummary>>`（字段见下方）

#### 汇总单详情（中文）
- **功能**: 按 ID 获取核价汇总单
- **方法**: GET
- **路径**: `/api/cpq/costing-summary/{id}`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，汇总单 ID）
- **响应内容**: `ApiResponse<CostingSummary>`

#### 新建汇总单（中文）
- **功能**: 创建核价汇总单
- **方法**: POST
- **路径**: `/api/cpq/costing-summary`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `CostingSummary`（字段见下方）
- **响应内容**: `ApiResponse<CostingSummary>`

#### 删除汇总单（中文）
- **功能**: 删除核价汇总单
- **方法**: DELETE
- **路径**: `/api/cpq/costing-summary/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，汇总单 ID）
- **响应内容**: `ApiResponse<Void>`

#### 计算汇总（中文）
- **功能**: 触发汇总单计算并返回计算结果列表
- **方法**: POST
- **路径**: `/api/cpq/costing-summary/{id}/compute`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，汇总单 ID）
- **响应内容**: `ApiResponse<List<CostingSummaryResult>>`（字段见下方）

#### 发布汇总单（中文）
- **功能**: 发布核价汇总单
- **方法**: POST
- **路径**: `/api/cpq/costing-summary/{id}/publish`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，汇总单 ID）
- **响应内容**: `ApiResponse<CostingSummary>`

#### 归档汇总单（中文）
- **功能**: 归档核价汇总单
- **方法**: POST
- **路径**: `/api/cpq/costing-summary/{id}/archive`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，汇总单 ID）
- **响应内容**: `ApiResponse<CostingSummary>`

#### 汇总结果列表（中文）
- **功能**: 查询汇总单已算出的结果指标
- **方法**: GET
- **路径**: `/api/cpq/costing-summary/{id}/results`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，汇总单 ID）
- **响应内容**: `ApiResponse<List<CostingSummaryResult>>`

#### 覆盖值列表（中文）
- **功能**: 查询汇总单的人工覆盖值
- **方法**: GET
- **路径**: `/api/cpq/costing-summary/{id}/overrides`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，汇总单 ID）
- **响应内容**: `ApiResponse<List<CostingSummaryOverride>>`（字段见下方）

#### 保存覆盖值（中文）
- **功能**: 为汇总单新增/更新一条人工覆盖值（路径 id 会被写入请求体 summaryId）
- **方法**: POST
- **路径**: `/api/cpq/costing-summary/{id}/overrides`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，汇总单 ID，覆写请求体 summaryId）
- **请求体**: `CostingSummaryOverride`（字段见下方，summaryId 由路径注入无需传）
- **响应内容**: `ApiResponse<CostingSummaryOverride>`

#### 删除覆盖值（中文）
- **功能**: 删除一条人工覆盖值
- **方法**: DELETE
- **路径**: `/api/cpq/costing-summary/overrides/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `id`（UUID，覆盖值记录 ID）
- **响应内容**: `ApiResponse<Void>`

**CostingSummary 字段**（表 `costing_summary`；status：DRAFT/COMPUTED/PUBLISHED/ARCHIVED）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| summaryNo | String | 汇总单号（非空，唯一） |
| hfPartNo | String | HF 料号（非空） |
| elementVersionId | UUID | 元素价格版本 ID（非空） |
| materialVersionId | UUID | 材料价格版本 ID（非空） |
| exchangeVersionId | UUID | 汇率版本 ID（非空） |
| status | String | 状态，默认 DRAFT |
| quoteCurrency | String | 报价币种，默认 USD |
| notes | String | 备注 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |
| createdBy | UUID | 创建人 |
| computedAt | OffsetDateTime | 计算时间 |
| publishedAt | OffsetDateTime | 发布时间 |
| publishedBy | UUID | 发布人 |

**CostingSummaryResult 字段**（表 `costing_summary_result`）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| summaryId | UUID | 所属汇总单 ID（非空） |
| metricCode | String | 指标代码（非空） |
| metricLabel | String | 指标标签 |
| value | BigDecimal | 指标值（18,6） |
| currency | String | 币种，默认 USD |
| formulaUsed | String | 使用的公式 |
| sortOrder | Integer | 排序号，默认 0 |
| createdAt | OffsetDateTime | 创建时间 |

**CostingSummaryOverride 字段**（表 `costing_summary_override`）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| summaryId | UUID | 所属汇总单 ID（非空，保存时由路径注入） |
| targetKind | String | 目标种类（非空，ELEMENT / MATERIAL / EXCHANGE） |
| targetKey | String | 目标键（非空） |
| fieldName | String | 字段名（非空） |
| overrideValue | BigDecimal | 覆盖值（非空，18,6） |
| notes | String | 备注 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |
## 六、基础资料与主数据

> 基址 `http://localhost:8081`;鉴权 = 会话 Cookie（标注 @RoleAllowed 的端点需登录且具备对应角色，请求需带 Cookie）；除特别标注外，统一响应体为 `ApiResponse<T> = { code, message, data }`。

---

### 6.1 ComparisonTagResource（对比标签维护）

类级 `@Path`: `/api/cpq/comparison-tags`
`@Produces` / `@Consumes`: `application/json`

#### 对比标签列表查询
- **功能**: 按状态过滤查询对比标签列表
- **方法**: GET
- **路径**: `/api/cpq/comparison-tags`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 否 | 按状态过滤（如 ACTIVE / INACTIVE） |

- **响应内容**: `ApiResponse<List<ComparisonTagDTO>>`，`ComparisonTagDTO` 字段见下

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| code | String | 标签编码 |
| label | String | 标签显示名 |
| groupName | String | 分组名 |
| groupSortOrder | Integer | 分组排序号 |
| tagSortOrder | Integer | 组内标签排序号 |
| isBuiltin | Boolean | 是否内置标签 |
| status | String | 状态 |
| description | String | 描述 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

#### 对比标签详情
- **功能**: 按 ID 查询单个对比标签
- **方法**: GET
- **路径**: `/api/cpq/comparison-tags/{id}`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 标签主键 |

- **响应内容**: `ApiResponse<ComparisonTagDTO>`（字段同上）

#### 新建对比标签
- **功能**: 创建对比标签
- **方法**: POST
- **路径**: `/api/cpq/comparison-tags`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **请求体**: `CreateComparisonTagRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | String | 是 | 标签编码（@NotBlank） |
| label | String | 是 | 标签显示名（@NotBlank） |
| groupName | String | 是 | 分组名（@NotBlank） |
| groupSortOrder | Integer | 否 | 分组排序号 |
| tagSortOrder | Integer | 否 | 组内标签排序号 |
| status | String | 否 | 状态 |
| description | String | 否 | 描述 |

- **响应内容**: `ApiResponse<ComparisonTagDTO>`（字段同上）

#### 更新对比标签
- **功能**: 按 ID 更新对比标签
- **方法**: PUT
- **路径**: `/api/cpq/comparison-tags/{id}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 标签主键 |

- **请求体**: `CreateComparisonTagRequest`（字段同"新建对比标签"，更新时未加 @Valid 校验）
- **响应内容**: `ApiResponse<ComparisonTagDTO>`（字段同上）

#### 删除对比标签
- **功能**: 按 ID 删除对比标签
- **方法**: DELETE
- **路径**: `/api/cpq/comparison-tags/{id}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 标签主键 |

- **响应内容**: `ApiResponse<Void>`（data 为空）

---

### 6.2 ProductCategoryResource（产品分类维护）

类级 `@Path`: `/api/cpq/product-categories`
`@Produces` / `@Consumes`: `application/json`

#### 产品分类列表查询
- **功能**: 按状态过滤查询产品分类列表
- **方法**: GET
- **路径**: `/api/cpq/product-categories`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 否 | 按状态过滤 |

- **响应内容**: `ApiResponse<List<ProductCategoryDTO>>`，`ProductCategoryDTO` 字段见下

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| code | String | 分类编码 |
| name | String | 分类名称 |
| description | String | 描述 |
| parentId | UUID | 父分类 ID（可空，支持树形） |
| status | String | 状态 |
| sortOrder | Integer | 排序号 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

#### 产品分类详情
- **功能**: 按 ID 查询单个产品分类
- **方法**: GET
- **路径**: `/api/cpq/product-categories/{id}`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 分类主键 |

- **响应内容**: `ApiResponse<ProductCategoryDTO>`（字段同上）

#### 新建产品分类
- **功能**: 创建产品分类
- **方法**: POST
- **路径**: `/api/cpq/product-categories`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **请求体**: `CreateProductCategoryRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | String | 是 | 分类编码（@NotBlank） |
| name | String | 是 | 分类名称（@NotBlank） |
| description | String | 否 | 描述 |
| parentId | UUID | 否 | 父分类 ID |
| status | String | 否 | 状态 |
| sortOrder | Integer | 否 | 排序号 |

- **响应内容**: `ApiResponse<ProductCategoryDTO>`（字段同上）

#### 更新产品分类
- **功能**: 按 ID 更新产品分类
- **方法**: PUT
- **路径**: `/api/cpq/product-categories/{id}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 分类主键 |

- **请求体**: `CreateProductCategoryRequest`（字段同"新建产品分类"，更新时未加 @Valid 校验）
- **响应内容**: `ApiResponse<ProductCategoryDTO>`（字段同上）

#### 删除产品分类
- **功能**: 按 ID 删除产品分类
- **方法**: DELETE
- **路径**: `/api/cpq/product-categories/{id}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 分类主键 |

- **响应内容**: `ApiResponse<Void>`（data 为空）

---

### 6.3 BasicDataImportV6Resource（V6 基础数据 Excel 导入 + 建报价单）

类级 `@Path`: `/api/cpq/basic-data-import/v6`
`@Produces`: `application/json`

> 报价基础数据 19 Sheet（按 customerId 注入 customer_no）；核价基础数据 24 Sheet（customer_no 从 Excel 行读）。

#### 报价基础数据导入（异步）
- **功能**: 上传报价基础数据 Excel，同步建导入记录并读入内存，后台线程异步处理，立即返回 PROCESSING；前端用 GET `/{recordId}` 轮询
- **方法**: POST
- **路径**: `/api/cpq/basic-data-import/v6/quote`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **请求头**: `Content-Type: multipart/form-data`
- **请求体**（multipart form）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | UUID | 是 | 客户 ID；据此取客户 code 作为 V6 customer_no（客户须已配置 code，否则 400） |
| file | FileUpload | 是 | 上传的 Excel 文件 |

- **响应内容**: `ApiResponse<ImportResultDTO>`，返回时仅填部分字段（importRecordId、systemType="QUOTE"、status="PROCESSING"）

| 字段 | 类型 | 说明 |
|------|------|------|
| importRecordId | UUID | 导入记录 ID（用于轮询） |
| systemType | String | 系统类型 QUOTE / PRICING |
| status | String | 状态 SUCCESS / PARTIAL_SUCCESS / FAILED（本端点即时返回为 PROCESSING） |
| totalSuccessRows | int | 成功行数 |
| totalFailedRows | int | 失败行数 |
| sheetResults | List&lt;SheetResultDTO&gt; | 各 Sheet 导入结果 |

`SheetResultDTO` 字段:

| 字段 | 类型 | 说明 |
|------|------|------|
| sheetName | String | Sheet 名 |
| totalRows | int | 总行数 |
| successRows | int | 成功行数 |
| failedRows | int | 失败行数 |
| errors | List&lt;RowError&gt; | 行错误明细（最多前 50 条） |
| writtenCounts | Map&lt;String,Integer&gt; | 各目标表写入条数 |

- **错误码**: 400（customerId/file 为空、客户未配 code）、401（未登录）、404（客户不存在）、500（读文件失败）

#### 核价基础数据导入（同步）
- **功能**: 上传核价基础数据 Excel，同步解析落库并返回结果（customer_no 从 Excel 行内读取）
- **方法**: POST
- **路径**: `/api/cpq/basic-data-import/v6/pricing`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **请求头**: `Content-Type: multipart/form-data`
- **请求体**（multipart form）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | FileUpload | 是 | 上传的 Excel 文件 |

- **响应内容**: `ApiResponse<ImportResultDTO>`（字段同上；同步返回完整结果）
- **错误码**: 400（file 为空）、401（未登录）、500（导入失败）

#### 由导入记录创建报价单
- **功能**: V6 commit Step 2——导入完成后依据模板创建报价单（不填 LineItem，由编辑页 autoPopulate 自动生成）
- **方法**: POST
- **路径**: `/api/cpq/basic-data-import/v6/quote/create-quotation`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **请求头**: `Content-Type: application/json`
- **请求体**: `CreateQuotationFromImportRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| importRecordId | UUID | 是 | 导入记录 ID |
| customerId | UUID | 是 | 客户 ID |
| name | String | 是 | 报价单名称（最长 500） |
| categoryId | UUID | 否 | 产品分类 ID |
| customerTemplateId | UUID | 否 | 报价（对客）模板 ID |
| costingTemplateId | UUID | 否 | 核价模板 ID |

- **响应内容**: `ApiResponse<V6QuotationCommitService.CommitResult>`

| 字段 | 类型 | 说明 |
|------|------|------|
| quotationId | UUID | 新建的报价单 ID |
| importRecordId | UUID | 关联的导入记录 ID |
| hfPairsCount | int | 成品/半成品配对数量 |

- **错误码**: 400（必填字段为空）、401（未登录）、500（创建失败）

#### 查询导入结果
- **功能**: 按导入记录 ID 查询历史导入结果（用于异步轮询）
- **方法**: GET
- **路径**: `/api/cpq/basic-data-import/v6/{recordId}`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| recordId | UUID | 导入记录 ID |

- **响应内容**: `ApiResponse<Map<String,Object>>`，map 内容如下

| 字段 | 类型 | 说明 |
|------|------|------|
| importRecordId | UUID | 导入记录 ID |
| systemType | String | 系统类型 |
| status | String | 导入状态 |
| totalRows | Integer | 总行数 |
| successRows | Integer | 成功行数 |
| failedRows | Integer | 失败行数（取自 unmatchedRows） |
| originalFileName | String | 原始文件名 |
| createdAt | 时间 | 创建时间 |
| metadata | Object/JSON | 元数据（含各 Sheet 明细等） |

- **错误码**: 404（导入记录不存在）

---

### 6.4 MaterialBomQueryResource（V6 物料 BOM 只读查询）

类级 `@Path`: `/api/cpq/v6/material-bom-items`
`@Produces` / `@Consumes`: `application/json`

#### 物料 BOM 子表分页查询
- **功能**: 分页查询 material_bom_item 记录
- **方法**: GET
- **路径**: `/api/cpq/v6/material-bom-items`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerNo | String | 是 | 客户编号；缺失返回 400 MISSING_CUSTOMER_NO |
| materialNo | String | 否 | 物料编号 |
| systemType | String | 否 | QUOTE / PRICING / BOTH（大小写不敏感）；非法值返回 400 INVALID_SYSTEM_TYPE |
| page | int | 否 | 页码，从 0 开始（默认 0） |
| size | int | 否 | 每页条数（默认 20，最大 200） |

- **响应内容**: `ApiResponse<PageResult<MaterialBomItemDTO>>`；`PageResult` 包裹分页元信息（content / page / size / totalElements / totalPages）

`MaterialBomItemDTO` 字段（44 字段 1:1 透传 material_bom_item）:

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| createdAt | LocalDateTime | 创建时间 |
| updatedAt | LocalDateTime | 更新时间 |
| createdBy | UUID | 创建人 |
| updatedBy | UUID | 更新人 |
| systemType | String | 系统类型 QUOTE / PRICING / BOTH |
| customerNo | String | 客户编号 |
| materialNo | String | 料号 |
| characteristic | String | 特征码（可空） |
| seqNo | Integer | 项次 |
| componentNo | String | 组件号 |
| partNo | String | 零件号 |
| effectiveDatetime | LocalDateTime | 生效时间 |
| expireDatetime | LocalDateTime | 失效时间 |
| operationNo | String | 工序号 |
| operationSeq | String | 工序顺序 |
| itemSeq | Integer | 项目顺序 |
| issueUnit | String | 发料单位 |
| compositionQty | BigDecimal | 组成用量 |
| baseQty | BigDecimal | 基础用量 |
| componentUsageType | String | 组件用量类型 |
| featureMgmt | String | 特征管理 |
| upperLimitPct | BigDecimal | 上限百分比 |
| lowerLimitPct | BigDecimal | 下限百分比 |
| scrapBatch | BigDecimal | 批量损耗 |
| scrapRate | BigDecimal | 损耗率 |
| fixedScrap | BigDecimal | 固定损耗 |
| scrapRateType | String | 损耗率类型 |
| issueLocation | String | 发料仓 |
| issueStorage | String | 发料储位 |
| fasGroup | String | FAS 组 |
| plugPosition | String | 插件位置 |
| refRdCenter | String | 研发中心参照 |
| isOptional | Boolean | 是否可选 |
| woExpandOption | String | 工单展开选项 |
| isPurchaseReplace | Boolean | 是否采购替代 |
| componentLeadTime | BigDecimal | 组件前置期 |
| mainSubstitute | String | 主替代料 |
| attachedPart | String | 附属件 |
| ecnNo | String | ECN 变更单号 |
| useQtyFormula | Boolean | 是否用用量公式 |
| qtyFormula | String | 用量公式 |
| isBackflush | Boolean | 是否倒冲 |
| isCustomerSupply | Boolean | 是否客供 |
| defectRate | BigDecimal | 不良率 |
| calcType | String | 计算类型 |
| recoveryDiscount | BigDecimal | 回收折扣 |
| recoveryCurrency | String | 回收货币 |
| recoveryUnit | String | 回收单位 |

#### BOM 客户编号去重列表
- **功能**: 返回 material_bom_item 中所有不重复的 customer_no（缓存 5 分钟）
- **方法**: GET
- **路径**: `/api/cpq/v6/material-bom-items/customer-nos`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **响应内容**: `ApiResponse<List<String>>`（去重客户编号列表）

#### BOM 物料编号去重列表
- **功能**: 返回指定客户下所有不重复的 material_no，支持模糊搜索
- **方法**: GET
- **路径**: `/api/cpq/v6/material-bom-items/material-nos`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerNo | String | 是 | 客户编号；缺失返回 400 MISSING_CUSTOMER_NO |
| q | String | 否 | 模糊匹配 material_no（LIKE %q%） |
| limit | int | 否 | 最多返回条数（默认 500，最大 1000） |

- **响应内容**: `ApiResponse<List<String>>`（去重物料编号列表）

---

### 6.5 MaterialMasterResource（V6 料号主数据 CRUD）

类级 `@Path`: `/api/cpq/material-masters`
`@Produces` / `@Consumes`: `application/json`

> 服务于「产品管理 → 产品主数据」UI，与 V6 导入服务共表 material_master。

#### 料号主数据分页列表
- **功能**: 分页查询料号主数据，支持关键字搜索
- **方法**: GET
- **路径**: `/api/cpq/material-masters`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，从 0 开始（默认 0） |
| size | int | 否 | 每页条数（默认 20） |
| keyword | String | 否 | 关键字搜索 |

- **响应内容**: `ApiResponse<PageResult<MaterialMasterDTO>>`

`MaterialMasterDTO` 字段（1:1 映射 material_master）:

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| materialNo | String | 料号（业务唯一键） |
| materialName | String | 名称 |
| specification | String | 规格 |
| dimension | String | 尺寸 |
| oldMaterialNo | String | 旧料号 |
| materialType | String | 物料类型（1.银点类 / 2.非银点类 / 组成件 / 边角料） |
| usageProperty | String | 使用属性（1.正常 / 2.回收料） |
| unitWeight | BigDecimal | 单重 |
| standardUnit | String | 标准单位 |
| createdAt | LocalDateTime | 创建时间 |
| updatedAt | LocalDateTime | 更新时间 |
| createdBy | UUID | 创建人 |
| updatedBy | UUID | 更新人 |

#### 料号主数据详情
- **功能**: 按 ID 查询单个料号主数据
- **方法**: GET
- **路径**: `/api/cpq/material-masters/{id}`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 料号主数据主键 |

- **响应内容**: `ApiResponse<MaterialMasterDTO>`（字段同上）

#### 新建料号主数据
- **功能**: 创建料号主数据
- **方法**: POST
- **路径**: `/api/cpq/material-masters`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **请求体**: `CreateMaterialMasterRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| materialNo | String | 是 | 料号（@NotBlank，最长 20，业务唯一键） |
| materialName | String | 否 | 名称（最长 100） |
| specification | String | 否 | 规格（最长 100） |
| dimension | String | 否 | 尺寸（最长 100） |
| oldMaterialNo | String | 否 | 旧料号（最长 50） |
| materialType | String | 否 | 物料类型（最长 50） |
| usageProperty | String | 否 | 使用属性（最长 50） |
| unitWeight | BigDecimal | 否 | 单重 |
| standardUnit | String | 否 | 标准单位（最长 20） |

- **响应内容**: `ApiResponse<MaterialMasterDTO>`（字段同上）

#### 更新料号主数据
- **功能**: 按 ID 更新料号主数据（materialNo 业务唯一键，创建后不可改）
- **方法**: PUT
- **路径**: `/api/cpq/material-masters/{id}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 料号主数据主键 |

- **请求体**: `CreateMaterialMasterRequest`（字段同"新建"，更新时未加 @Valid 校验）
- **响应内容**: `ApiResponse<MaterialMasterDTO>`（字段同上）

#### 删除料号主数据
- **功能**: 按 ID 删除料号主数据
- **方法**: DELETE
- **路径**: `/api/cpq/material-masters/{id}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 料号主数据主键 |

- **响应内容**: `ApiResponse<Void>`（data 为空）

---

### 6.6 ProcessMasterResource（V6 工序主数据 CRUD）

类级 `@Path`: `/api/cpq/v6/process-master`
`@Produces` / `@Consumes`: `application/json`

#### 工序主数据分页列表
- **功能**: 分页查询工序主数据，支持 processNo / processName 模糊搜索
- **方法**: GET
- **路径**: `/api/cpq/v6/process-master`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，从 0 开始（默认 0） |
| size | int | 否 | 每页条数（默认 20，最大 200） |
| keyword | String | 否 | 关键字（可为空） |

- **响应内容**: `ApiResponse<PageResult<ProcessMasterDTO>>`

`ProcessMasterDTO` 字段（1:1 映射 process_master 11 个业务字段）:

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| processNo | String | 工序编号（业务唯一键） |
| processName | String | 工序名称 |
| processCategory | String | 工序分类（制造/组装/电镀/外协/包装/清洗） |
| isOutsource | Boolean | 是否外协 |
| standardCurrency | String | 标准货币 |
| standardUnit | String | 标准单位 |
| defaultDefectRate | BigDecimal | 默认不良率 |
| createdAt | LocalDateTime | 创建时间 |
| updatedAt | LocalDateTime | 更新时间 |
| createdBy | UUID | 创建人 |
| updatedBy | UUID | 更新人 |

#### 新建工序主数据
- **功能**: 创建工序主数据
- **方法**: POST
- **路径**: `/api/cpq/v6/process-master`
- **鉴权**: SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `ProcessMasterUpsertRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| processNo | String | 是（新建） | 工序编号，业务唯一键，新建必填且唯一 |
| processName | String | 是 | 工序名称 |
| processCategory | String | 否 | 工序分类 |
| isOutsource | Boolean | 否 | 是否外协（true=外协，false=自制） |
| standardCurrency | String | 否 | 标准货币 |
| standardUnit | String | 否 | 标准单位 |
| defaultDefectRate | BigDecimal | 否 | 默认不良率 |

- **响应内容**: `ApiResponse<ProcessMasterDTO>`（字段同上）

#### 编辑工序主数据
- **功能**: 更新工序主数据（processNo 业务键锁定，服务端忽略该字段改动）
- **方法**: PUT
- **路径**: `/api/cpq/v6/process-master/{id}`
- **鉴权**: SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 工序主数据主键 |

- **请求体**: `ProcessMasterUpsertRequest`（字段同"新建"，processNo 编辑时被忽略）
- **响应内容**: `ApiResponse<ProcessMasterDTO>`（字段同上）

#### 删除工序主数据
- **功能**: 硬删除工序主数据
- **方法**: DELETE
- **路径**: `/api/cpq/v6/process-master/{id}`
- **鉴权**: SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 工序主数据主键 |

- **响应内容**: `ApiResponse<UUID>`（返回被删除记录的 id）

---

### 6.7 MasterDataResource（UI-4 主数据维护页只读查询）

类级 `@Path`: `/api/cpq/master-data`
`@Produces`: `application/json`
类级 `@RoleAllowed`: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN（作用于全部端点）

> 全部为只读 GET，覆盖 13 张已注册物理表；不产生写操作。

#### 主数据总览
- **功能**: 返回全部 13 张已注册物理表的汇总（行数、最后更新时间等）
- **方法**: GET
- **路径**: `/api/cpq/master-data/overview`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | String(UUID) | 否 | 客户 UUID；提供时对 CUSTOMER 组表按 customer_id 过滤；格式非法返回 400 |

- **响应内容**: `ApiResponse<MasterDataOverviewDTO>`

`MasterDataOverviewDTO` 字段:

| 字段 | 类型 | 说明 |
|------|------|------|
| customerId | String | 传入的客户 UUID；null 表示全局视图 |
| tables | List&lt;TableSummaryDTO&gt; | 13 张表的汇总列表 |

`TableSummaryDTO` 字段:

| 字段 | 类型 | 说明 |
|------|------|------|
| tableName | String | 物理表名（snake_case） |
| displayName | String | 中文显示名 |
| group | String | 逻辑分组 GLOBAL / CUSTOMER / ELEMENT |
| customerScoped | boolean | 是否按 customer_id 过滤 |
| rowCount | long | 总行数；v1Disabled=true 时为 0 |
| lastUpdatedAt | String | MAX(updated_at)；v1Disabled=true 时为 null |
| v1Disabled | boolean | true=该表 Phase 1 未激活，未执行 DB 查询 |
| primaryKeyField | String | 主键字段名（如 mat_part 为 part_no，多数为 id） |

#### 单表分页数据查询
- **功能**: 分页查询单张物理表的数据行
- **方法**: GET
- **路径**: `/api/cpq/master-data/table/{tableName}`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| tableName | String | 必须为 13 张已注册表名之一 |

- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | String(UUID) | 否 | 客户 UUID，用于 CUSTOMER 组表过滤；格式非法返回 400 |
| page | int | 否 | 页码，从 0 开始（默认 0）；负数返回 400 |
| size | int | 否 | 每页条数，范围 [1,200]（默认 50）；越界返回 400 |
| search | String | 否 | 对该表 searchField 的子串过滤（ILIKE） |

- **响应内容**: `ApiResponse<PagedTableDataDTO>`

`PagedTableDataDTO` 字段:

| 字段 | 类型 | 说明 |
|------|------|------|
| tableName | String | 物理表名 |
| displayName | String | 显示名 |
| page | int | 当前页（0 起） |
| size | int | 请求页大小 |
| total | long | 匹配总行数（忽略分页） |
| columns | List&lt;ColumnMetadataDTO&gt; | 列元数据（结果集顺序） |
| rows | List&lt;Map&lt;String,Object&gt;&gt; | 数据行，key=物理列名 |
| v1Disabled | boolean | true=注册表 v1Enabled=false，rows 空且 total=0，HTTP 仍 200，调用方须检查此标志 |

`ColumnMetadataDTO` 字段:

| 字段 | 类型 | 说明 |
|------|------|------|
| columnName | String | 物理列名（snake_case） |
| label | String | 显示名（来自 basic_data_attribute.variable_label，缺则回退列名） |
| importanceLevel | String | 重要级别 CRITICAL / IMPORTANT / NORMAL（默认 NORMAL） |
| dataType | String | 数据类型提示 IDENTIFIER / VALUE；未知为 null |

#### 单行明细查询
- **功能**: 查询单张表中单行的完整明细
- **方法**: GET
- **路径**: `/api/cpq/master-data/table/{tableName}/row/{rowId}`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| tableName | String | 必须为 13 张已注册表名之一 |
| rowId | String | 主键值；多数表为 UUID 格式，mat_part 为 part_no 字符串 |

- **响应内容**: `ApiResponse<Map<String,Object>>`（单行 key=物理列名，value=序列化值）
## 七、客户与销售线索

> 全局基准：基址 `http://localhost:8081`；统一响应包 `ApiResponse<T> = { code, message, data }`。
> 鉴权：会话 Cookie。标注 `@RoleAllowed` 的端点须登录且具备对应角色，请求头需携带会话 Cookie；无该注解则不校验。
> 本片段内所有 Resource 均在类级声明 `@RoleAllowed`，故每个端点均需登录；方法级若另有 `@RoleAllowed`（如删除类动作）则以更严格的角色集合为准。

---

### 7.1 CustomerResource（客户管理）

类级 `@Path("/api/cpq/customers")`；类级鉴权 `@RoleAllowed({SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN})`。

#### 客户分页列表
- **功能**: 按等级/状态/关键字分页查询客户列表。
- **方法**: GET
- **路径**: `/api/cpq/customers`
- **鉴权**: 需登录，角色 `SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN`
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |
| level | String | 否 | 客户等级过滤 |
| status | String | 否 | 客户状态过滤 |
| keyword | String | 否 | 关键字（名称/编码等模糊匹配） |

- **响应内容**: `ApiResponse<PageResult<CustomerDTO>>`。`PageResult` 分页包（含 `content`/`total`/`page`/`size` 等，具体见通用分页 DTO），`content` 为 `CustomerDTO` 列表（字段见下方 CustomerDTO）。

#### 客户详情
- **功能**: 按 ID 查询单个客户详情。
- **方法**: GET
- **路径**: `/api/cpq/customers/{id}`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 客户 ID |

- **响应内容**: `ApiResponse<CustomerDTO>`（字段见 CustomerDTO）。

#### 新建客户
- **功能**: 创建客户，记录操作人（从会话取，取不到用兜底）。
- **方法**: POST
- **路径**: `/api/cpq/customers`
- **鉴权**: 需登录，角色同类级
- **请求体**: `CreateCustomerRequest`（`@Valid` 校验，字段见 CreateCustomerRequest）
- **响应内容**: `ApiResponse<CustomerDTO>`。

#### 更新客户
- **功能**: 按 ID 更新客户信息。
- **方法**: PUT
- **路径**: `/api/cpq/customers/{id}`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 客户 ID |

- **请求体**: `CreateCustomerRequest`（字段见下方）
- **响应内容**: `ApiResponse<CustomerDTO>`。

#### 删除客户
- **功能**: 按 ID 删除客户。
- **方法**: DELETE
- **路径**: `/api/cpq/customers/{id}`
- **鉴权**: 需登录，角色收窄为 `SALES_REP / SALES_MANAGER / SYSTEM_ADMIN`
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 客户 ID |

- **响应内容**: `ApiResponse<Void>`（成功无 data）。

#### 批量删除客户
- **功能**: 按 ID 列表批量删除客户（列表为空则不处理）。
- **方法**: POST
- **路径**: `/api/cpq/customers/batch-delete`
- **鉴权**: 需登录，角色收窄为 `SALES_REP / SALES_MANAGER / SYSTEM_ADMIN`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| ids | List\<UUID\> | 是 | 待删除客户 ID 列表 |

- **响应内容**: `ApiResponse<Void>`。

---

### 7.2 CustomerContactResource（客户联系人管理）

类级 `@Path("/api/cpq/customers/{customerId}/contacts")`；类级鉴权 `@RoleAllowed({SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN})`。所有端点含路径参数 `customerId`（UUID，所属客户 ID）。

#### 联系人列表
- **功能**: 查询指定客户下的全部联系人。
- **方法**: GET
- **路径**: `/api/cpq/customers/{customerId}/contacts`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| customerId | UUID | 客户 ID |

- **响应内容**: `ApiResponse<List<ContactDTO>>`（字段见 ContactDTO）。

#### 新建联系人
- **功能**: 在指定客户下创建联系人。
- **方法**: POST
- **路径**: `/api/cpq/customers/{customerId}/contacts`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| customerId | UUID | 客户 ID |

- **请求体**: `ContactDTO`（字段见下方）
- **响应内容**: `ApiResponse<ContactDTO>`。

#### 更新联系人
- **功能**: 更新指定联系人信息。
- **方法**: PUT
- **路径**: `/api/cpq/customers/{customerId}/contacts/{contactId}`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| customerId | UUID | 客户 ID |
| contactId | UUID | 联系人 ID |

- **请求体**: `ContactDTO`
- **响应内容**: `ApiResponse<ContactDTO>`。

#### 删除联系人
- **功能**: 删除指定联系人。
- **方法**: DELETE
- **路径**: `/api/cpq/customers/{customerId}/contacts/{contactId}`
- **鉴权**: 需登录，角色收窄为 `SALES_REP / SALES_MANAGER / SYSTEM_ADMIN`
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| customerId | UUID | 客户 ID |
| contactId | UUID | 联系人 ID |

- **响应内容**: `ApiResponse<Void>`。

#### 设为主联系人
- **功能**: 将指定联系人设为该客户的主联系人。
- **方法**: PUT
- **路径**: `/api/cpq/customers/{customerId}/contacts/{contactId}/set-primary`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| customerId | UUID | 客户 ID |
| contactId | UUID | 联系人 ID |

- **响应内容**: `ApiResponse<ContactDTO>`（更新后的联系人）。

---

### 7.3 CustomerLeadResource（销售线索管理）

类级 `@Path("/api/cpq/customer-leads")`；类级鉴权 `@RoleAllowed({SYSTEM_ADMIN, SALES_MANAGER, SALES_REP})`。

#### 线索分页列表
- **功能**: 按状态/电话分页查询销售线索。
- **方法**: GET
- **路径**: `/api/cpq/customer-leads`
- **鉴权**: 需登录，角色 `SYSTEM_ADMIN / SALES_MANAGER / SALES_REP`
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |
| status | String | 否 | 线索状态过滤 |
| phone | String | 否 | 电话过滤 |

- **响应内容**: `ApiResponse<PageResult<CustomerLead>>`，`content` 为 `CustomerLead` 实体列表（字段见 CustomerLead）。

#### 线索详情
- **功能**: 按 ID 查询单条线索。
- **方法**: GET
- **路径**: `/api/cpq/customer-leads/{id}`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 线索 ID |

- **响应内容**: `ApiResponse<CustomerLead>`。

#### 新建线索
- **功能**: 创建销售线索。
- **方法**: POST
- **路径**: `/api/cpq/customer-leads`
- **鉴权**: 需登录，角色同类级
- **请求体**: `CustomerLead` 实体（字段见下方）
- **响应内容**: `ApiResponse<CustomerLead>`。

#### 线索审核
- **功能**: 对线索执行审核动作（通过/驳回等），可绑定到已有客户并附审核备注。`action` 必填，缺失抛异常。审核人当前固定传 null（待 auth 集成后从会话取）。
- **方法**: POST
- **路径**: `/api/cpq/customer-leads/{id}/review`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 线索 ID |

- **请求体**（Map 形式）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| action | String | 是 | 审核动作（缺失则报错 "action required"） |
| bound_customer_id | String(UUID) | 否 | 审核通过时绑定的客户 ID，空白视为无 |
| review_note | String | 否 | 审核备注 |

- **响应内容**: `ApiResponse<Map<String, Object>>`（审核结果，具体键由 `CustomerLeadService.review` 返回）。

---

### 7.4 IndustryResource（行业管理）

类级 `@Path("/api/cpq/industries")`；类级鉴权 `@RoleAllowed({SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN})`。

#### 行业分页列表
- **功能**: 按状态/关键字分页查询行业。
- **方法**: GET
- **路径**: `/api/cpq/industries`
- **鉴权**: 需登录，角色同类级
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |
| status | String | 否 | 状态过滤 |
| keyword | String | 否 | 关键字模糊匹配 |

- **响应内容**: `ApiResponse<PageResult<IndustryDTO>>`（字段见 IndustryDTO）。

#### 启用行业列表
- **功能**: 查询全部启用状态的行业（用于下拉选择）。
- **方法**: GET
- **路径**: `/api/cpq/industries/active`
- **鉴权**: 需登录，角色同类级
- **响应内容**: `ApiResponse<List<IndustryDTO>>`。

#### 行业详情
- **功能**: 按 ID 查询单个行业。
- **方法**: GET
- **路径**: `/api/cpq/industries/{id}`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 行业 ID |

- **响应内容**: `ApiResponse<IndustryDTO>`。

#### 新建行业
- **功能**: 创建行业。
- **方法**: POST
- **路径**: `/api/cpq/industries`
- **鉴权**: 需登录，角色同类级
- **请求体**: `IndustryRequest`（`@Valid`，字段见下方）
- **响应内容**: `ApiResponse<IndustryDTO>`。

#### 更新行业
- **功能**: 按 ID 更新行业。
- **方法**: PUT
- **路径**: `/api/cpq/industries/{id}`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 行业 ID |

- **请求体**: `IndustryRequest`（`@Valid`）
- **响应内容**: `ApiResponse<IndustryDTO>`。

#### 删除行业
- **功能**: 按 ID 删除行业。
- **方法**: DELETE
- **路径**: `/api/cpq/industries/{id}`
- **鉴权**: 需登录，角色收窄为 `SALES_MANAGER / SYSTEM_ADMIN`
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 行业 ID |

- **响应内容**: `ApiResponse<Void>`。

#### 批量删除行业
- **功能**: 按 ID 列表批量删除行业（列表为空则不处理）。
- **方法**: POST
- **路径**: `/api/cpq/industries/batch-delete`
- **鉴权**: 需登录，角色收窄为 `SALES_MANAGER / SYSTEM_ADMIN`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| ids | List\<UUID\> | 是 | 待删除行业 ID 列表 |

- **响应内容**: `ApiResponse<Void>`。

---

### 附：本片段涉及 DTO / 实体字段

#### CustomerDTO（客户 DTO，响应体）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 客户 ID |
| name | String | 客户名称 |
| code | String | 客户编码 |
| level | String | 客户等级 |
| industry | String | 行业名称 |
| industryCode | String | 行业编码 |
| region | String | 区域 |
| address | String | 地址 |
| accumulatedAmount | BigDecimal | 累计成交金额 |
| creditLimit | BigDecimal | 授信额度 |
| paymentMethod | String | 付款方式 |
| remarks | String | 备注 |
| status | String | 状态 |
| version | Integer | 乐观锁版本号 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |
| contacts | List\<ContactDTO\> | 联系人列表（默认空数组） |
| quotationCount | Long | 报价单数量（统计字段） |
| avgDiscountRate | Double | 平均折扣率（统计字段） |

#### CreateCustomerRequest（新建/更新客户请求体）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | 是 | 客户名称（`@NotBlank`） |
| level | String | 否 | 客户等级 |
| industry | String | 否 | 行业名称 |
| industryCode | String | 否 | 行业编码 |
| region | String | 否 | 区域 |
| address | String | 否 | 地址 |
| creditLimit | BigDecimal | 否 | 授信额度 |
| paymentMethod | String | 否 | 付款方式 |
| remarks | String | 否 | 备注 |
| contacts | List\<ContactDTO\> | 否 | 随客户一并提交的联系人列表 |

#### ContactDTO（联系人 DTO，请求/响应体）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | UUID | 否 | 联系人 ID（新建时可空，响应回填） |
| customerId | UUID | 否 | 所属客户 ID |
| name | String | 否 | 姓名 |
| role | String | 否 | 职务/角色 |
| phone | String | 否 | 电话 |
| email | String | 否 | 邮箱 |
| wechat | String | 否 | 微信 |
| isPrimary | Boolean | 否 | 是否主联系人 |
| createdAt | OffsetDateTime | 否 | 创建时间（响应回填） |

#### CustomerLead（销售线索实体，请求/响应体）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| id | UUID | 否 | 线索 ID（自动生成，响应回填） |
| leadCode | String | 是 | 线索编号，唯一，`LEAD-yyyyMM-序号(4位)`，长度≤40 |
| sourceType | String | 是 | 来源类型：`CUSTOMER_SELF` / `SHARED_LINK` / `IMPORT_BATCH` |
| shareToken | String | 否 | 分享令牌，长度≤64 |
| contactName | String | 是 | 联系人姓名，长度≤128 |
| contactPhone | String | 是 | 联系人电话，长度≤40 |
| contactEmail | String | 否 | 联系人邮箱，长度≤128 |
| companyName | String | 否 | 公司名称，长度≤255 |
| note | String | 否 | 备注（TEXT） |
| status | String | 否 | 状态，默认 `PENDING_REVIEW`，状态机 `PENDING_REVIEW → CONVERTED / REJECTED`，长度≤20 |
| reviewedBy | UUID | 否 | 审核人 ID |
| reviewedAt | OffsetDateTime | 否 | 审核时间 |
| reviewAction | String | 否 | 审核动作：`BIND_EXISTING` / `CREATE_NEW` / `REJECT`，长度≤32 |
| boundCustomerId | UUID | 否 | 绑定的客户 ID |
| reviewNote | String | 否 | 审核备注（TEXT） |
| createdAt | OffsetDateTime | 否 | 创建时间（默认当前时间） |
| updatedAt | OffsetDateTime | 否 | 更新时间（更新时自动刷新） |

#### IndustryDTO（行业 DTO，响应体）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 行业 ID |
| code | String | 行业编码 |
| name | String | 行业名称 |
| status | String | 状态 |
| version | Integer | 乐观锁版本号 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

#### IndustryRequest（新建/更新行业请求体）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | String | 是 | 行业编码（`@NotBlank`，长度≤50） |
| name | String | 是 | 行业名称（`@NotBlank`，长度≤100） |
| status | String | 否 | 状态（长度≤20） |
## 八、配置中心与数据源

> 全局基准：基址 `http://localhost:8081`；鉴权=会话 Cookie（`@RoleAllowed` 端点需登录 + 对应角色，请求头带 Cookie；无注解则不校验）；统一响应体 `ApiResponse<T> = { code, message, data }`，部分端点返回内嵌实体/DTO，据实标注。

---

### 8.1 ConfigCenterResource（配置中心运维/统计工具端点）

类级 `@Path("/api/cpq/config-center")`，类级鉴权 `@RoleAllowed({SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN})`。

#### 配置中心健康统计快照
- **功能**: K3 —— 返回全局变量、结构（组件/模板）、数据源解析器三大板块的统计快照，供运维监控。异常时降级返回 `status=DEGRADED` + 错误信息。
- **方法**: GET
- **路径**: `/api/cpq/config-center/health`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **响应内容**: `ApiResponse<Map<String,Object>>`，data 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| global_variables.definitions_total | long | 全局变量定义总数 |
| global_variables.definitions_kv_table | long | KV_TABLE 类型定义数 |
| global_variables.definitions_costing_view | long | COSTING_VIEW 类型定义数 |
| global_variables.values_total | long | 全局变量明细值总数 |
| structure.components_total | long | 组件总数 |
| structure.templates_total | long | 模板总数 |
| structure.templates_published | long | 已发布模板数 |
| data_source_resolvers.registered_types | Set\<String\> | 已注册的数据源解析器类型集合 |
| data_source_resolvers.http_api_enabled | boolean | HTTP_API 解析器是否启用（有白名单主机） |
| data_source_resolvers.http_api_allowed_hosts_count | int | HTTP_API 允许主机数量 |
| status | String | `OK` 正常 / `DEGRADED` 降级 |
| error | String | 降级时的错误信息（正常时无此字段） |

#### 全量刷新模板快照
- **功能**: K4 —— 遍历所有 template.components_snapshot 引用到的 componentId 集合，逐个调用 `refreshSnapshotsByComponent`。适合 schema 大变更后批量修复。单个组件失败不影响其他，累计 errors。
- **方法**: POST
- **路径**: `/api/cpq/config-center/refresh-all-snapshots`
- **鉴权**: SYSTEM_ADMIN（方法级覆盖，仅系统管理员）
- **请求体**: 无
- **响应内容**: `ApiResponse<Map<String,Object>>`，data 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| components_processed | int | 处理的组件数量 |
| templates_touched_total | int | 累计刷新的模板数 |
| errors | int | 刷新失败的组件数 |
| per_component | Map\<String,Integer\> | componentId → 受影响模板数 映射 |

---

### 8.2 ConfigTemplateResource（配置模板主资源，LIST_FORMULA 数据源）

类级 `@Path("/api/cpq/config-templates")`（V203 / Phase B1）。

#### 列出配置模板
- **功能**: 按状态筛选列出配置模板（list 端点不返回嵌套 categories，避免 N+1）。
- **方法**: GET
- **路径**: `/api/cpq/config-templates`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 否 | 状态过滤（如 DRAFT / PUBLISHED / ARCHIVED），留空返回全部 |

- **响应内容**: `ApiResponse<List<ConfigTemplateDTO>>`（见文末 ConfigTemplateDTO）

#### 获取配置模板详情
- **功能**: 按 id 返回单个配置模板，含嵌套的大类（categories）及其明细项。
- **方法**: GET
- **路径**: `/api/cpq/config-templates/{id}`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 配置模板 id |

- **响应内容**: `ApiResponse<ConfigTemplateDTO>`（含 categories 列表）

#### 创建配置模板
- **功能**: 新建配置模板。
- **方法**: POST
- **路径**: `/api/cpq/config-templates`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `TemplateRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | String | 是 | 编码，非空，≤50 字符 |
| name | String | 是 | 名称，非空，≤200 字符 |
| description | String | 否 | 描述 |

- **响应内容**: `ApiResponse<ConfigTemplateDTO>`

#### 更新配置模板
- **功能**: 按 id 更新配置模板基本信息。
- **方法**: PUT
- **路径**: `/api/cpq/config-templates/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| id | UUID | 配置模板 id |`
- **请求体**: `TemplateRequest`（同上）
- **响应内容**: `ApiResponse<ConfigTemplateDTO>`

#### 删除配置模板
- **功能**: 按 id 删除配置模板。
- **方法**: DELETE
- **路径**: `/api/cpq/config-templates/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| id | UUID | 配置模板 id |`
- **响应内容**: `ApiResponse<Void>`

#### 发布配置模板
- **功能**: 将配置模板置为已发布状态。
- **方法**: POST
- **路径**: `/api/cpq/config-templates/{id}/publish`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| id | UUID | 配置模板 id |`
- **响应内容**: `ApiResponse<ConfigTemplateDTO>`

#### 归档配置模板
- **功能**: 将配置模板置为归档状态。
- **方法**: POST
- **路径**: `/api/cpq/config-templates/{id}/archive`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| id | UUID | 配置模板 id |`
- **响应内容**: `ApiResponse<ConfigTemplateDTO>`

#### 在模板下创建大类
- **功能**: 在指定配置模板下新建一个配置大类（category）。
- **方法**: POST
- **路径**: `/api/cpq/config-templates/{templateId}/categories`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| templateId | UUID | 所属配置模板 id |`
- **请求体**: `CategoryRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | String | 是 | 编码，非空，≤50 字符 |
| name | String | 是 | 名称，非空，≤200 字符 |
| sortOrder | Integer | 否 | 排序序号 |
| status | String | 否 | 状态 |

- **响应内容**: `ApiResponse<ConfigCategoryDTO>`

---

### 8.3 ConfigCategoryResource（配置大类资源）

类级 `@Path("/api/cpq/config-categories")`。

#### 更新配置大类
- **功能**: 按 id 更新配置大类。
- **方法**: PUT
- **路径**: `/api/cpq/config-categories/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| id | UUID | 配置大类 id |`
- **请求体**: `CategoryRequest`（code 必填≤50 / name 必填≤200 / sortOrder / status，同上）
- **响应内容**: `ApiResponse<ConfigCategoryDTO>`

#### 删除配置大类
- **功能**: 按 id 删除配置大类。
- **方法**: DELETE
- **路径**: `/api/cpq/config-categories/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| id | UUID | 配置大类 id |`
- **响应内容**: `ApiResponse<Void>`

#### 在大类下创建配置项
- **功能**: 在指定配置大类下新建一个配置项（item）。
- **方法**: POST
- **路径**: `/api/cpq/config-categories/{categoryId}/items`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| categoryId | UUID | 所属配置大类 id |`
- **请求体**: `ItemRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | String | 是 | 编码，非空，≤50 字符 |
| name | String | 是 | 名称，非空，≤200 字符 |
| defaultValue | String | 否 | 默认值，≤500 字符 |
| sortOrder | Integer | 否 | 排序序号 |
| status | String | 否 | 状态 |

- **响应内容**: `ApiResponse<ConfigItemDTO>`

---

### 8.4 ConfigItemResource（配置项资源）

类级 `@Path("/api/cpq/config-items")`。

#### 更新配置项
- **功能**: 按 id 更新配置项。
- **方法**: PUT
- **路径**: `/api/cpq/config-items/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| id | UUID | 配置项 id |`
- **请求体**: `ItemRequest`（code 必填≤50 / name 必填≤200 / defaultValue≤500 / sortOrder / status，同上）
- **响应内容**: `ApiResponse<ConfigItemDTO>`

#### 删除配置项
- **功能**: 按 id 删除配置项。
- **方法**: DELETE
- **路径**: `/api/cpq/config-items/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| id | UUID | 配置项 id |`
- **响应内容**: `ApiResponse<Void>`

---

### 8.5 DataSourceResolverResource（数据源解析统一端点，调试/预览用）

类级 `@Path("/api/cpq/data-sources")`（I2/I3），类级鉴权 `@RoleAllowed({SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN})`。用于调试 / 公式编辑器预览 / 字段配置预览。

#### 列出已注册解析器类型
- **功能**: 返回已注册的数据源解析器类型集合。
- **方法**: GET
- **路径**: `/api/cpq/data-sources/types`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **响应内容**: `ApiResponse<Set<String>>` —— 解析器类型字符串集合（如 DATABASE_QUERY / GLOBAL_VARIABLE / BNF_PATH / HTTP_API）

#### 解析数据源取值
- **功能**: 按 type + config + driverRow 调用注册表解析，返回解析后的标量值或 null。type 为空或注册表抛 IllegalArgumentException 时返回 400。
- **方法**: POST
- **路径**: `/api/cpq/data-sources/resolve`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `ResolveRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | 是 | 数据源解析器类型（为空返回 400 "type 必填"） |
| config | Map\<String,Object\> | 否 | 解析器配置（各类型自定义） |
| driverRow | Map\<String,Object\> | 否 | 驱动行上下文（供解析器取参） |

- **响应内容**: `ApiResponse<Object>` —— 解析结果标量值或 null

---

### 8.6 DataSourceResource（数据源 CRUD 与测试执行，管理端）

类级 `@Path("/api/cpq/datasources")`，类级鉴权 `@RoleAllowed({SYSTEM_ADMIN})`（默认仅系统管理员）。

#### 分页列出数据源
- **功能**: 分页 + 类型/关键字过滤列出数据源。
- **方法**: GET
- **路径**: `/api/cpq/datasources`
- **鉴权**: SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页大小，默认 20 |
| type | String | 否 | 类型过滤（SQL / API） |
| keyword | String | 否 | 关键字过滤 |

- **响应内容**: `ApiResponse<PageResult<DataSourceDTO>>`（见文末 DataSourceDTO）

#### 获取数据源详情
- **功能**: 按 id 返回单个数据源（含参数列表）。
- **方法**: GET
- **路径**: `/api/cpq/datasources/{id}`
- **鉴权**: SYSTEM_ADMIN
- **路径参数**: `| id | UUID | 数据源 id |`
- **响应内容**: `ApiResponse<DataSourceDTO>`

#### 创建数据源
- **功能**: 新建数据源（SQL 或 API 类型）。
- **方法**: POST
- **路径**: `/api/cpq/datasources`
- **鉴权**: SYSTEM_ADMIN
- **请求体**: `CreateDataSourceRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | String | 是 | 编码，非空，≤100，仅字母/数字/下划线/连字符 |
| name | String | 是 | 名称，非空，≤200 |
| type | String | 是 | 类型，必为 `SQL` 或 `API` |
| status | String | 否 | 状态，默认 `ACTIVE` |
| description | String | 否 | 描述 |
| sqlQuery | String | 否 | SQL 语句（SQL 类型用） |
| sqlResultColumn | String | 否 | SQL 结果取值列（SQL 类型用） |
| apiUrl | String | 否 | API 地址（API 类型用） |
| apiMethod | String | 否 | API 方法（API 类型用） |
| apiHeaders | String | 否 | API 请求头 JSON（API 类型用） |
| apiBodyTemplate | String | 否 | API 请求体模板（API 类型用） |
| apiResultPath | String | 否 | API 结果取值路径（API 类型用） |
| apiTimeoutSeconds | Integer | 否 | API 超时秒数 |
| params | List\<ParamRequest\> | 否 | 参数定义列表（见下） |

`ParamRequest` 子对象：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| paramCode | String | 是 | 参数编码，非空，≤100 |
| paramName | String | 是 | 参数名称，非空，≤200 |
| sourceType | String | 是 | 来源类型，必为 `USER_FIELD` 或 `SYSTEM_PARAM` |
| systemParamCode | String | 否 | 系统参数编码（SYSTEM_PARAM 时用） |
| isRequired | Boolean | 否 | 是否必填，默认 true |
| description | String | 否 | 描述 |

- **响应内容**: `ApiResponse<DataSourceDTO>`

#### 更新数据源
- **功能**: 按 id 更新数据源（请求体同创建，但未加 @Valid 校验）。
- **方法**: PUT
- **路径**: `/api/cpq/datasources/{id}`
- **鉴权**: SYSTEM_ADMIN
- **路径参数**: `| id | UUID | 数据源 id |`
- **请求体**: `CreateDataSourceRequest`（字段同上，此端点未强制 Bean 校验）
- **响应内容**: `ApiResponse<DataSourceDTO>`

#### 删除数据源
- **功能**: 按 id 删除数据源。
- **方法**: DELETE
- **路径**: `/api/cpq/datasources/{id}`
- **鉴权**: SYSTEM_ADMIN
- **路径参数**: `| id | UUID | 数据源 id |`
- **响应内容**: `ApiResponse<Void>`

#### 测试数据源
- **功能**: 用测试参数试跑数据源，返回原始响应/提取值/耗时。
- **方法**: POST
- **路径**: `/api/cpq/datasources/{id}/test`
- **鉴权**: SYSTEM_ADMIN
- **路径参数**: `| id | UUID | 数据源 id |`
- **请求体**: `DataSourceTestRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| testParams | Map\<String,String\> | 否 | 测试参数键值（可空） |

- **响应内容**: `ApiResponse<DataSourceTestResult>`（见文末 DataSourceTestResult）

#### 执行数据源
- **功能**: 用参数正式执行数据源取值（返回结构同 test）。此端点方法级放宽鉴权。
- **方法**: POST
- **路径**: `/api/cpq/datasources/{id}/execute`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN（方法级覆盖类级）
- **路径参数**: `| id | UUID | 数据源 id |`
- **请求体**: `DataSourceTestRequest`（同上）
- **响应内容**: `ApiResponse<DataSourceTestResult>`

---

### 8.7 CacheStatsResource（数据路径三层缓存监控）

类级 `@Path("/api/cpq/datapath/cache")`（X.3）。

#### 缓存统计
- **功能**: 返回 datapath 三层缓存（AST 解析 / SQL 编译 / schema 元数据）的命中率、命中/未命中数、加载数、淘汰数、估算大小。
- **方法**: GET
- **路径**: `/api/cpq/datapath/cache/stats`
- **鉴权**: SYSTEM_ADMIN（方法级）
- **响应内容**: `ApiResponse<Map<String,Map<String,Object>>>` —— 外层键为 `datapath-ast` / `datapath-sql` / `datapath-metadata`，每个内层值：

| 字段 | 类型 | 说明 |
|------|------|------|
| hitRate | double | 命中率，四位小数 |
| hitCount | long | 命中次数 |
| missCount | long | 未命中次数 |
| loadCount | long | 加载次数 |
| evictionCount | long | 淘汰次数 |
| estimatedSize | long | 估算缓存条目数 |

---

### 8.8 GlobalVariableResource（全局变量注册表与明细值，V104/V106）

类级 `@Path("/api/cpq/global-variables")`，类级鉴权 `@RoleAllowed({SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN})`。

#### 列出全部全局变量定义
- **功能**: 列出所有已注册全局变量（公式选择器拉取用）。
- **方法**: GET
- **路径**: `/api/cpq/global-variables`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **响应内容**: `ApiResponse<List<GlobalVariableDefinition>>`（见文末 GlobalVariableDefinition）

#### 新建全局变量定义
- **功能**: G1 —— 新建全局变量定义，形态强制 KV_TABLE + PUBLIC；核价类（COSTING_VIEW）仅 Flyway 初始化，不接受 UI 新建。
- **方法**: POST
- **路径**: `/api/cpq/global-variables`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN / SALES_MANAGER（方法级）
- **请求体**: `GlobalVariableDefinition`（见文末字段表）
- **响应内容**: `ApiResponse<GlobalVariableDefinition>`

#### 删除全局变量定义
- **功能**: G1 —— 删除全局变量定义（核价变量不可删），级联清 global_variable_value（FK CASCADE）。
- **方法**: DELETE
- **路径**: `/api/cpq/global-variables/{code}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN / SALES_MANAGER（方法级）
- **路径参数**: `| code | String | 全局变量编码 |`
- **响应内容**: `ApiResponse<String>` —— data 固定 `"ok"`

#### 获取单个全局变量定义
- **功能**: 按 code 返回单个变量定义，未注册返回 404。
- **方法**: GET
- **路径**: `/api/cpq/global-variables/{code}`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| code | String | 全局变量编码 |`
- **响应内容**: `ApiResponse<GlobalVariableDefinition>`（未注册 code=404，message="全局变量未注册: {code}"）

#### 列出候选 key
- **功能**: 返回该变量的候选 key 列表（UI 选 key 用），limit 上限 5000。
- **方法**: GET
- **路径**: `/api/cpq/global-variables/{code}/keys`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| code | String | 全局变量编码 |`
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| limit | int | 否 | 返回上限，默认 1000，硬上限 5000 |

- **响应内容**: `ApiResponse<List<Map<String,Object>>>` —— 每行为 key 列名 → 值 的映射

#### 直接取值
- **功能**: 按键取变量值；复合键时用列名作 query 参数传（如 `?from_currency=CNY&to_currency=USD`）。未注册 code 抛 404。
- **方法**: GET
- **路径**: `/api/cpq/global-variables/{code}/value`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: `| code | String | 全局变量编码 |`
- **查询参数**: 动态 —— 按变量定义的 `keyColumns` 各列名逐个传值（例 `from_currency`、`to_currency`）
- **响应内容**: `ApiResponse<BigDecimal>` —— 解析出的数值

#### Upsert 明细行
- **功能**: V106 —— 新增/更新一条明细行；值未变时静默 no-op。记录变更日志（含当前用户）。keyValues 或 value 缺失返回 400。
- **方法**: POST
- **路径**: `/api/cpq/global-variables/{code}/entries`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN / SALES_MANAGER（方法级）
- **请求头**: Cookie（会话用于解析当前用户 id）
- **路径参数**: `| code | String | 全局变量编码 |`
- **请求体**: `UpsertEntryRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyValues | Map\<String,Object\> | 是 | 键列值映射 |
| value | BigDecimal | 是 | 数值 |
| note | String | 否 | 变更备注（写入变更日志） |

- **响应内容**: `ApiResponse<Map<String,Object>>` —— `{ "value": <生效值 BigDecimal> }`

#### 删除明细行
- **功能**: V106 —— 删除一条明细行，幂等。记录变更日志。keyValues 缺失返回 400。
- **方法**: DELETE
- **路径**: `/api/cpq/global-variables/{code}/entries`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN / SALES_MANAGER（方法级）
- **请求头**: Cookie（会话用于解析当前用户 id）
- **路径参数**: `| code | String | 全局变量编码 |`
- **请求体**: `DeleteEntryRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyValues | Map\<String,Object\> | 是 | 键列值映射 |
| note | String | 否 | 变更备注 |

- **响应内容**: `ApiResponse<String>` —— data 固定 `"ok"`

#### 变更日志
- **功能**: V106 —— 返回明细变更日志；code 留空则返回全部。
- **方法**: GET
- **路径**: `/api/cpq/global-variables/change-log`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | String | 否 | 变量编码过滤，留空返回全部 |
| limit | int | 否 | 返回上限，默认 100 |

- **响应内容**: `ApiResponse<List<Map<String,Object>>>` —— 变更日志行列表

---

### 8.9 VariableLabelResource（变量标签，中文友好命名 SSOT，V149）

类级 `@Path("/api/cpq/variable-labels")`，类级鉴权 `@RoleAllowed({SALES_REP, SALES_MANAGER, PRICING_MANAGER, SYSTEM_ADMIN})`。读权限放给所有业务角色（公式/Excel 列编辑器用），写权限默认 SALES_MANAGER+。

#### 列出全部标签
- **功能**: 返回全部 ACTIVE 变量标签（前端选择器一次拉取）。
- **方法**: GET
- **路径**: `/api/cpq/variable-labels`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **响应内容**: `ApiResponse<List<VariableLabel>>`（见文末 VariableLabel）

#### 按分类分组
- **功能**: 按 category 分组返回标签（前端"业务域树"）。
- **方法**: GET
- **路径**: `/api/cpq/variable-labels/grouped`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **响应内容**: `ApiResponse<LinkedHashMap<String,List<VariableLabel>>>` —— category → 标签列表（有序）

#### 按路径精确查
- **功能**: 按 variablePath 精确查单个标签（回退判定用），未注册返回 404。
- **方法**: GET
- **路径**: `/api/cpq/variable-labels/by-path`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| path | String | 是 | 变量路径（如 `v_c_summary_agg.packaging_fee`） |

- **响应内容**: `ApiResponse<VariableLabel>`（未注册 code=404，message="未注册的变量路径: {path}"）

#### 渐进式起名（Upsert）
- **功能**: 用户在编辑器弹窗里给变量路径补中文名（quickName upsert）。请求体为空返回 400。
- **方法**: POST
- **路径**: `/api/cpq/variable-labels`
- **鉴权**: SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN（方法级）
- **请求体**: `QuickNameRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| variablePath | String | 是 | 变量路径（全局唯一） |
| displayName | String | 否 | 中文友好名 |
| category | String | 否 | 业务分类 |
| dataType | String | 否 | 数据类型（DECIMAL/INTEGER/PERCENT/STRING/DATE） |
| unit | String | 否 | 单位标签 |
| description | String | 否 | 描述 |

- **响应内容**: `ApiResponse<VariableLabel>`

#### 样本求值试算
- **功能**: V149 Phase 2 —— 用户在模板配置时点 ▶ 试算触发；仅注册过的 path 才能查（服务层白名单校验）。path 缺失返回 400，非法参数 400，其他异常 500。
- **方法**: POST
- **路径**: `/api/cpq/variable-labels/eval`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `EvalRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| path | String | 是 | 变量路径（为空返回 400 "path 必填"） |
| hfPartNo | String | 否 | 料号上下文（供求值定位行） |

- **响应内容**: `ApiResponse<Map<String,Object>>` —— `{ "value": <求值结果，null 时为空字符串> }`

---

### 附：本片段引用的 DTO / 实体字段

#### ConfigTemplateDTO（配置模板）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| code | String | 编码 |
| name | String | 名称 |
| description | String | 描述 |
| status | String | 状态 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |
| createdBy | UUID | 创建人 |
| publishedAt | OffsetDateTime | 发布时间 |
| categories | List\<ConfigCategoryDTO\> | 嵌套大类列表（仅 detail 端点返回，list 端点为空避免 N+1） |

#### ConfigCategoryDTO（配置大类）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| templateId | UUID | 所属模板 id |
| code | String | 编码 |
| name | String | 名称 |
| sortOrder | Integer | 排序序号 |
| status | String | 状态 |
| items | List\<ConfigItemDTO\> | 嵌套配置项列表 |

#### ConfigItemDTO（配置项）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| categoryId | UUID | 所属大类 id |
| code | String | 编码 |
| name | String | 名称 |
| defaultValue | String | 默认值 |
| sortOrder | Integer | 排序序号 |
| status | String | 状态 |

#### DataSourceDTO（数据源）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| code | String | 编码 |
| name | String | 名称 |
| type | String | 类型（SQL / API） |
| status | String | 状态 |
| description | String | 描述 |
| sqlQuery | String | SQL 语句 |
| sqlResultColumn | String | SQL 结果取值列 |
| apiUrl | String | API 地址 |
| apiMethod | String | API 方法 |
| apiHeaders | String | API 请求头（敏感值已脱敏为 `****`） |
| apiBodyTemplate | String | API 请求体模板 |
| apiResultPath | String | API 结果取值路径 |
| apiTimeoutSeconds | Integer | API 超时秒数 |
| createdBy | UUID | 创建人 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |
| params | List\<DataSourceParamDTO\> | 参数列表 |

`DataSourceParamDTO` 字段：id(UUID)、datasourceId(UUID)、paramOrder(Integer)、paramCode(String)、paramName(String)、sourceType(String, USER_FIELD/SYSTEM_PARAM)、systemParamCode(String)、isRequired(Boolean)、description(String)、createdAt(OffsetDateTime)。

#### DataSourceTestResult（数据源测试/执行结果）

| 字段 | 类型 | 说明 |
|------|------|------|
| rawResponse | String | 原始响应 |
| extractedValue | String | 提取的取值 |
| executionTimeMs | Long | 执行耗时（毫秒） |
| success | boolean | 是否成功 |
| errorMessage | String | 错误信息（失败时） |

#### GlobalVariableDefinition（全局变量定义，V104）

| 字段 | 类型 | 说明 |
|------|------|------|
| code | String | 编码 |
| name | String | 名称 |
| varType | String | LOOKUP_TABLE / SCALAR |
| valueSourceType | String | V188：KV_TABLE / COSTING_VIEW（分发到单表还是源视图） |
| visibility | String | V188：PUBLIC / COSTING_INTERNAL（UI 列表是否过滤） |
| sourceView | String | COSTING_VIEW 模式下的源视图；KV_TABLE 可空 |
| keyColumns | List\<String\> | 物理列名清单，单键长度=1，复合键>1 |
| valueColumn | String | 取值列 |
| labelTemplate | String | 标签模板 |
| unit | String | 单位 |
| description | String | 描述 |
| sortOrder | Integer | 排序序号 |
| isActive | Boolean | 是否启用 |
| updatedAt | OffsetDateTime | 更新时间（@JsonIgnore，不序列化） |

#### VariableLabel（变量标签，V149）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| variablePath | String | 视图列路径（例 `v_c_summary_agg.packaging_fee`），全局唯一 |
| displayName | String | 中文友好名 |
| category | String | 业务分类（成本汇总/费用比率/物料属性/单位标签/汇率） |
| dataType | String | DECIMAL / INTEGER / PERCENT / STRING / DATE |
| unit | String | 单位标签（例 ¥ / % / g / null） |
| description | String | 描述 |
| exampleValue | String | 示例值 |
| sourceType | String | VIEW_COLUMN（当前唯一）/ CONSTANT / DERIVED（预留） |
| status | String | ACTIVE / DEPRECATED / PENDING_REVIEW |
| createdAt | OffsetDateTime | 创建时间（@JsonIgnore） |
| updatedAt | OffsetDateTime | 更新时间（@JsonIgnore） |
## 九、Excel 导入

> **全局基准**：基址 `http://localhost:8081`；鉴权 = 会话 Cookie（标注 `@RoleAllowed` 的端点需登录且具备对应角色，请求头需带 Cookie；无注解端点不校验角色）。统一响应体 `ApiResponse<T> = { code, message, data }`；文件下载类端点直返二进制流（据实标注）。

---

### 9.1 ImportResource（Excel 导入执行 / 预览 / 记录查询）

类级 `@Path("/api/cpq/imports")`；默认 `@Produces/@Consumes = application/json`。

#### 执行导入（旧版三模板直落库）
- **功能**: 上传 Excel + 客户模板 + 映射模板，直接解析并落库生成导入记录（早期 v1/v2 直落库路径）。
- **方法**: POST
- **路径**: `/api/cpq/imports/execute`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: multipart/form-data`
- **路径参数**: 无
- **查询参数**: 无
- **请求体**（multipart 表单）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | 文件 | 是 | 上传的 Excel 文件 |
| customerId | String(UUID) | 是 | 客户 ID |
| excelTemplateId | String(UUID) | 是 | 客户 Excel 模板 ID |
| mappingTemplateId | String(UUID) | 是 | 导入映射模板 ID |

- **响应内容**: `ApiResponse<ImportRecordDTO>`（字段见 §9.1 附录 ImportRecordDTO）。异常统一抛 500 `Import execution failed: ...`。

#### 导入记录列表（分页）
- **功能**: 分页查询导入记录，支持按客户/状态/导入人/时间区间过滤。
- **方法**: GET
- **路径**: `/api/cpq/imports`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: 无特殊
- **路径参数**: 无
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |
| customerId | UUID | 否 | 客户 ID 过滤 |
| importStatus | String | 否 | 导入状态过滤 |
| importedBy | UUID | 否 | 导入人用户 ID 过滤 |
| startDate | String | 否 | 起始时间（ISO OffsetDateTime） |
| endDate | String | 否 | 结束时间（ISO OffsetDateTime） |

- **响应内容**: `ApiResponse<PageResult<ImportRecordDTO>>`。

#### 导入记录详情
- **功能**: 按 ID 查询单条导入记录。
- **方法**: GET
- **路径**: `/api/cpq/imports/{id}`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN`
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 导入记录 ID |

- **查询参数**: 无
- **响应内容**: `ApiResponse<ImportRecordDTO>`。

#### 导入预览（v3，仅解析不落库）
- **功能**: 解析 Excel 并返回预览数据（解析行 + 料号匹配结果），不生成报价单。响应内可能携带隐藏的 `__savedPath__` 提示供 confirm-import 复用。
- **方法**: POST
- **路径**: `/api/cpq/imports/import-excel`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: multipart/form-data`
- **路径参数**: 无
- **查询参数**: 无
- **请求体**（multipart 表单）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | 文件 | 是 | 上传的 Excel 文件；缺失抛 400 |
| templateId | String(UUID) | 是 | CPQ 模板 ID（其 excel_view_config 驱动解析）；空抛 400 |
| customerId | String(UUID) | 是 | 客户 ID；空抛 400 |

- **响应内容**: `ApiResponse<ImportPreviewDTO>`（字段见附录 ImportPreviewDTO）。异常抛 500 `Preview failed: ...`。

#### 确认导入（v3，由预览数据落库）
- **功能**: 基于预览已解析的数据创建报价单 + 导入记录，无需重新上传文件。
- **方法**: POST
- **路径**: `/api/cpq/imports/confirm-import`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: application/json`
- **路径参数**: 无
- **查询参数**: 无
- **请求体**: `ConfirmImportRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| templateId | UUID | 是 | 驱动导入的 CPQ 模板 ID（其 excel_view_config 生效） |
| customerId | UUID | 是 | 客户 ID |
| fileName | String | 否 | 原始上传文件名（写入导入记录） |
| savedFilePath | String | 否 | 上一次预览保存的服务器文件路径（避免重复上传） |
| rows | List<Map<String,Object>> | 否 | 预览已解析的行数据，每行为 col_key → value |
| matchResults | List<Map<String,Object>> | 否 | 预览的匹配结果，每项含 customerPartNo/matched/materialNo/materialName |

- **响应内容**: `ApiResponse<ImportRecordDTO>`。用户 ID 取当前会话（无会话回退默认值）。

#### 下载导入原始文件
- **功能**: 下载导入记录关联的服务器原始 Excel 文件。
- **方法**: GET
- **路径**: `/api/cpq/imports/{id}/download`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Produces = application/octet-stream`
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 导入记录 ID |

- **查询参数**: 无
- **响应内容**: 二进制文件流（`Response.ok(file)`），响应头 `Content-Disposition: attachment; filename="<原始文件名>"`。文件不存在抛 404。

**附录·ImportRecordDTO 字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 导入记录 ID |
| quotationId | UUID | 生成的报价单 ID |
| customerId | UUID | 客户 ID |
| customerName | String | 客户名称 |
| templateId | UUID | v3 模板 ID |
| templateName | String | 模板名称 |
| configSnapshot | String | 配置快照（JSON 文本） |
| excelTemplateId | UUID | 兼容 v1/v2：Excel 模板 ID |
| excelTemplateName | String | Excel 模板名称 |
| mappingTemplateId | UUID | 兼容 v1/v2：映射模板 ID |
| mappingTemplateName | String | 映射模板名称 |
| mappingSnapshot | String | 映射快照（JSON 文本） |
| originalFileName | String | 原始文件名 |
| originalFilePath | String | 服务器文件路径 |
| totalRows | Integer | 总行数 |
| successRows | Integer | 成功行数 |
| matchedRows | Integer | 匹配成功行数 |
| unmatchedRows | Integer | 未匹配行数 |
| importStatus | String | 导入状态 |
| errorDetail | String | 错误详情 |
| importedBy | UUID | 导入人 ID |
| importedByName | String | 导入人姓名 |
| createdAt | OffsetDateTime | 创建时间 |

**附录·ImportPreviewDTO 字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| rows | List<Map<String,Object>> | 解析行，每行 col_key → 单元格值 |
| matchResults | List<Map<String,Object>> | 每行匹配结果 { rowIndex, customerPartNo, matched, materialNo, materialName } |
| totalRows | int | 总行数 |
| matchedRows | int | 匹配成功行数 |
| unmatchedRows | int | 未匹配行数 |
| errors | List<Map<String,String>> | 行级解析错误 { row, error } |

---

### 9.2 BasicDataImportV5Resource（V5 基础资料导入，已废弃 · 保留兼容）

类级 `@Path("/api/cpq/import/basic-data/v5")`；`@Produces = application/json`。

> ⚠️ **已废弃**（`@Deprecated since=v6`，2026-05-12 起）：导入向导改为 staging-based 三步流程，新调用方应改用 §9.7 ImportSessionResource 的 upload/commit。本组端点保留作历史兼容，预计 6 个月后移除。

#### V5 预览（解析 + 校验，不写库）
- **功能**: 解析 Excel 并运行 BV-01~BV-32 校验，返回预览结果与差异/冲突，不写库。
- **方法**: POST
- **路径**: `/api/cpq/import/basic-data/v5/preview`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: multipart/form-data`
- **路径参数**: 无
- **查询参数**: 无
- **请求体**（multipart 表单）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | UUID | 是 | 客户 ID；空抛 400 |
| file | 文件 | 是 | Excel(.xlsx) 文件；空抛 400 |
| templateKind | String | 否 | 模板类别 `COSTING`/`QUOTATION`，决定 sheet 配置选择，默认 `QUOTATION` |

- **响应内容**: `ApiResponse<ImportResultDTO>`（字段见附录）。异常抛 400 `预览失败: ...`。

#### V5 确认写入（全有全无事务）
- **功能**: 全有全无事务写入 14 张物理表，支持 UI-1/UI-2 决策及料号版本决策。
- **方法**: POST
- **路径**: `/api/cpq/import/basic-data/v5/confirm`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: multipart/form-data`
- **路径参数**: 无
- **查询参数**: 无
- **请求体**（multipart 表单）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | UUID | 是 | 客户 ID；空抛 400 |
| file | 文件 | 是 | Excel(.xlsx) 文件；空抛 400 |
| resolutions | String(JSON) | 否 | UI-1/UI-2 决策数组，元素为 ResolutionDTO；null/空等价无决策；格式错误抛 400 |
| templateKind | String | 否 | 模板类别，默认 `QUOTATION` |
| partVersionDecisions | String(JSON) | 否 | 料号版本决策 Map，key=`cpn\|hf`，value=`BUMP`/`NO_CHANGE`/`SKIP`；格式错误抛 400 |

- **响应内容**: `ApiResponse<ImportResultDTO>`。异常抛 500 `导入失败: ...`。

**附录·ResolutionDTO 字段**（resolutions 数组元素）：

| 字段 | 类型 | 说明 |
|------|------|------|
| type | String | 决策类型 `BASIC_DIFF`(UI-1) / `CUSTOMER_CONFLICT`(UI-2) / `ORPHAN_ROW`(UI-3) |
| tableName | String | 物理表名（snake_case） |
| rowKey | String | 行业务键 |
| fieldName | String | 字段名（snake_case） |
| decision | String | 决策值：BASIC_DIFF/CUSTOMER_CONFLICT 取 `ACCEPT_NEW`/`KEEP_OLD`；ORPHAN_ROW 取 `DELETE_ORPHAN`/`KEEP_ORPHAN` |
| note | String | 变更说明（CRITICAL 字段选 ACCEPT_NEW 时必填） |
| oldValueAtPreview | String | 预览时旧值，confirm 时做乐观锁校验，被他人改动抛 409 |

**附录·ImportResultDTO 字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| importRecordId | UUID | 导入记录 ID |
| status | String | `PREVIEW_OK`/`PREVIEW_BLOCKED`/`SUCCESS`/`FAILED` |
| totalRows | int | 总行数 |
| processedRows | int | 已处理行数 |
| validation | ValidationResult | 校验结果对象 |
| errorSummary | String | 错误汇总 |
| basicDataDiffs | List<BasicDataDiffDTO> | 基础资料字段差异（全局表 mat_part/mat_bom/plating_plan） |
| customerDataConflicts | List<CustomerDataConflictDTO> | 客户资料冲突（客户级表 mat_process/mat_fee/plating_fee） |
| orphanRows | List<OrphanRowDTO> | 孤儿行（DB 有但本次 Excel 无的 is_current 行） |
| partVersionPreview | List<PartVersionPreviewDTO> | 料号版本预览（含 current_version + 建议 newVersion） |
| matPartCreated / matPartUpdated | int | mat_part 新建/更新计数 |
| matBomCreated / matBomUpdated | int | mat_bom 新建/更新计数 |
| matProcessCreated / matProcessVersioned | int | mat_process 新建/版本化计数 |
| matFeeCreated / matFeeVersioned | int | mat_fee 新建/版本化计数 |
| platingPlanCreated | int | plating_plan 新建计数 |
| platingFeeCreated / platingFeeVersioned | int | plating_fee 新建/版本化计数 |
| mappingCreated / mappingUpdated | int | 客户料号映射新建/更新计数 |
| costingPartRowsWritten | int | 核价料号级数据写入计数（8 张 costing_part_* 表汇总，V90） |
| changeLogRows | int | 变更日志行数 |

---

### 9.3 CustomerExcelTemplateResource（客户 Excel 模板管理）

类级 `@Path("/api/cpq/excel-templates")`；默认 `@Produces/@Consumes = application/json`；**类级 `@RoleAllowed({"SALES_MANAGER","SYSTEM_ADMIN"})`（全部端点适用）**。

#### 模板列表
- **功能**: 按客户查询其 Excel 模板列表。
- **方法**: GET
- **路径**: `/api/cpq/excel-templates`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | UUID | 否 | 客户 ID |

- **响应内容**: `ApiResponse<List<CustomerExcelTemplateDTO>>`。

#### 模板详情
- **功能**: 按 ID 查询单个 Excel 模板。
- **方法**: GET
- **路径**: `/api/cpq/excel-templates/{id}`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | Excel 模板 ID |

- **响应内容**: `ApiResponse<CustomerExcelTemplateDTO>`。

#### 新建模板
- **功能**: 创建客户 Excel 模板。
- **方法**: POST
- **路径**: `/api/cpq/excel-templates`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: application/json`
- **请求体**: `CreateCustomerExcelTemplateRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | 是 | 模板名称 |
| customerId | UUID | 是 | 客户 ID |
| description | String | 否 | 描述 |
| headerRowIndex | int | 否 | 表头行号，默认 1 |
| dataStartRowIndex | int | 否 | 数据起始行号，默认 2 |
| sheetIndex | int | 否 | Sheet 索引，默认 0 |
| partNoColumn | String | 否 | 料号所在列 |
| excelColumns | List<String> | 否 | Excel 列名列表 |

- **响应内容**: `ApiResponse<CustomerExcelTemplateDTO>`。用户 ID 取当前会话。

#### 更新模板
- **功能**: 按 ID 更新 Excel 模板。
- **方法**: PUT
- **路径**: `/api/cpq/excel-templates/{id}`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: application/json`
- **路径参数**: `id`(UUID) 模板 ID
- **请求体**: `CreateCustomerExcelTemplateRequest`（字段同新建）
- **响应内容**: `ApiResponse<CustomerExcelTemplateDTO>`。

#### 删除模板
- **功能**: 按 ID 删除 Excel 模板。
- **方法**: DELETE
- **路径**: `/api/cpq/excel-templates/{id}`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **路径参数**: `id`(UUID) 模板 ID
- **响应内容**: `ApiResponse<Void>`。

#### 解析 Excel 表头
- **功能**: 上传 Excel 并解析指定 Sheet 表头行返回列名列表（配置模板时使用）。
- **方法**: POST
- **路径**: `/api/cpq/excel-templates/parse-headers`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: multipart/form-data`
- **请求体**（multipart 表单）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | 文件 | 是 | 上传的 Excel 文件 |
| sheetIndex | int | 否 | Sheet 索引，默认 0 |
| headerRowIndex | int | 否 | 表头行号，默认 0 |

- **响应内容**: `ApiResponse<List<String>>`（表头列名列表）。异常抛 400 `Parse failed: ...`。

**附录·CustomerExcelTemplateDTO 字段**：id(UUID)、name、customerId(UUID)、description、headerRowIndex(int)、dataStartRowIndex(int)、sheetIndex(int)、partNoColumn、excelColumns(String，JSON 文本)、sampleFileName、createdBy(UUID)、createdAt、updatedAt。

---

### 9.4 CustomerMaterialMappingResource（客户料号 ↔ 内部物料映射）

类级 `@Path("/api/cpq/customers/{customerId}/material-mappings")`；默认 `@Produces/@Consumes = application/json`。所有端点均含路径参数 `customerId`(UUID)。

#### 映射列表（分页）
- **功能**: 按客户分页查询料号映射，支持关键词。
- **方法**: GET
- **路径**: `/api/cpq/customers/{customerId}/material-mappings`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN`
- **路径参数**: `customerId`(UUID) 客户 ID
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | String | 否 | 关键词过滤 |
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |

- **响应内容**: `ApiResponse<PageResult<CustomerMaterialMappingDTO>>`。

#### 新建映射
- **功能**: 为客户创建单条料号映射。
- **方法**: POST
- **路径**: `/api/cpq/customers/{customerId}/material-mappings`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: application/json`
- **路径参数**: `customerId`(UUID) 客户 ID
- **请求体**（JSON Map<String,String>）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerPartNo | String | 是 | 客户料号；空抛 400 |
| materialId | String(UUID) | 是 | 内部物料 ID；空或非法 UUID 抛 400 |

- **响应内容**: `ApiResponse<CustomerMaterialMappingDTO>`。请求体为空抛 400。

#### 删除映射
- **功能**: 删除单条料号映射。
- **方法**: DELETE
- **路径**: `/api/cpq/customers/{customerId}/material-mappings/{id}`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **路径参数**: `customerId`(UUID) 客户 ID；`id`(UUID) 映射 ID
- **响应内容**: `ApiResponse<Void>`。

#### 批量导入映射
- **功能**: 上传 Excel 批量导入客户料号映射。
- **方法**: POST
- **路径**: `/api/cpq/customers/{customerId}/material-mappings/import`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: multipart/form-data`
- **路径参数**: `customerId`(UUID) 客户 ID
- **请求体**（multipart 表单）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | 文件 | 是 | 上传的 Excel 文件 |

- **响应内容**: `ApiResponse<Map<String,Integer>>`，形如 `{ "imported": <导入条数> }`。异常抛 400 `Import failed: ...`。

#### 料号匹配
- **功能**: 按客户料号匹配内部物料。
- **方法**: GET
- **路径**: `/api/cpq/customers/{customerId}/material-mappings/match`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN`
- **路径参数**: `customerId`(UUID) 客户 ID
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| partNo | String | 否 | 客户料号 |

- **响应内容**: `ApiResponse<InternalMaterialDTO>`（匹配到的内部物料，未匹配为 null）。

**附录·CustomerMaterialMappingDTO 字段**：id(UUID)、customerId(UUID)、customerPartNo、materialId(UUID)、materialNo、materialName、createdAt。

---

### 9.5 ImportMappingTemplateResource（导入映射模板管理）

类级 `@Path("/api/cpq/import-mappings")`；默认 `@Produces/@Consumes = application/json`；**类级 `@RoleAllowed({"SALES_MANAGER","SYSTEM_ADMIN"})`（全部端点适用）**。

#### 映射模板列表
- **功能**: 按 Excel 模板查询导入映射模板列表。
- **方法**: GET
- **路径**: `/api/cpq/import-mappings`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| excelTemplateId | UUID | 否 | Excel 模板 ID |

- **响应内容**: `ApiResponse<List<ImportMappingTemplateDTO>>`。

#### 映射模板详情
- **功能**: 按 ID 查询单个导入映射模板。
- **方法**: GET
- **路径**: `/api/cpq/import-mappings/{id}`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **路径参数**: `id`(UUID) 映射模板 ID
- **响应内容**: `ApiResponse<ImportMappingTemplateDTO>`。

#### 新建映射模板
- **功能**: 创建导入映射模板。
- **方法**: POST
- **路径**: `/api/cpq/import-mappings`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: application/json`
- **请求体**: `CreateImportMappingTemplateRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | 是 | 映射模板名称 |
| excelTemplateId | UUID | 是 | 关联 Excel 模板 ID |
| templateId | UUID | 否 | 关联 CPQ 模板 ID |
| columnMappings | String(JSON) | 否 | 列映射配置（JSON 文本） |

- **响应内容**: `ApiResponse<ImportMappingTemplateDTO>`。用户 ID 取当前会话。

#### 更新映射模板
- **功能**: 按 ID 更新导入映射模板。
- **方法**: PUT
- **路径**: `/api/cpq/import-mappings/{id}`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: application/json`
- **路径参数**: `id`(UUID) 映射模板 ID
- **请求体**: `CreateImportMappingTemplateRequest`（字段同新建）
- **响应内容**: `ApiResponse<ImportMappingTemplateDTO>`。

#### 删除映射模板
- **功能**: 按 ID 删除导入映射模板。
- **方法**: DELETE
- **路径**: `/api/cpq/import-mappings/{id}`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **路径参数**: `id`(UUID) 映射模板 ID
- **响应内容**: `ApiResponse<Void>`。

**附录·ImportMappingTemplateDTO 字段**：id(UUID)、name、excelTemplateId(UUID)、templateId(UUID)、templateName、columnMappings(String，JSON 文本)、createdBy(UUID)、createdAt、updatedAt。

---

### 9.6 InternalMaterialResource（内部物料主数据管理）

类级 `@Path("/api/cpq/internal-materials")`；默认 `@Produces/@Consumes = application/json`。

#### 物料列表（分页）
- **功能**: 分页查询内部物料，支持关键词与状态过滤。
- **方法**: GET
- **路径**: `/api/cpq/internal-materials`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN`
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |
| keyword | String | 否 | 关键词过滤 |
| statusCode | String | 否 | 状态码过滤 |

- **响应内容**: `ApiResponse<PageResult<InternalMaterialDTO>>`。

#### 物料详情
- **功能**: 按 ID 查询单个内部物料。
- **方法**: GET
- **路径**: `/api/cpq/internal-materials/{id}`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `PRICING_MANAGER` / `SYSTEM_ADMIN`
- **路径参数**: `id`(UUID) 物料 ID
- **响应内容**: `ApiResponse<InternalMaterialDTO>`。

#### 新建物料
- **功能**: 创建内部物料。
- **方法**: POST
- **路径**: `/api/cpq/internal-materials`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: application/json`
- **请求体**: `CreateInternalMaterialRequest`（`@Valid` 校验）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| materialNo | String | 是 | 物料编号（`@NotBlank`） |
| name | String | 是 | 物料名称（`@NotBlank`） |
| specification | String | 否 | 规格 |
| size | String | 否 | 尺寸 |
| statusCode | String | 否 | 状态码，默认 `Y` |

- **响应内容**: `ApiResponse<InternalMaterialDTO>`。

#### 更新物料
- **功能**: 按 ID 更新内部物料。
- **方法**: PUT
- **路径**: `/api/cpq/internal-materials/{id}`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: application/json`
- **路径参数**: `id`(UUID) 物料 ID
- **请求体**: `CreateInternalMaterialRequest`（字段同新建，注意此处未加 `@Valid`）
- **响应内容**: `ApiResponse<InternalMaterialDTO>`。

#### 删除物料
- **功能**: 按 ID 删除内部物料。
- **方法**: DELETE
- **路径**: `/api/cpq/internal-materials/{id}`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **路径参数**: `id`(UUID) 物料 ID
- **响应内容**: `ApiResponse<Void>`。

#### 从 Excel 批量导入物料
- **功能**: 上传 Excel 批量导入内部物料。
- **方法**: POST
- **路径**: `/api/cpq/internal-materials/import`
- **鉴权**: `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: multipart/form-data`
- **请求体**（multipart 表单）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | 文件 | 是 | 上传的 Excel 文件 |

- **响应内容**: `ApiResponse<Map<String,Integer>>`，形如 `{ "imported": <导入条数> }`。异常抛 400 `Import failed: ...`。

**附录·InternalMaterialDTO 字段**：id(UUID)、materialNo、name、specification、size、statusCode、createdAt、updatedAt。

---

### 9.7 ImportSessionResource（V6 导入会话 · staging 三步流程）

类级 `@Path("/api/cpq/import-session")`；`@Produces = application/json`。V6 导入向导主线：上传建 session → 更新版本决策 → commit 合并 staging → mat_* 并建报价单。

#### Step 1 上传 Excel（建会话 + 检测差异）
- **功能**: 解析 Excel，创建 import_session，检测差异，返回 DiffPayload 供前端渲染 Step 2。
- **方法**: POST
- **路径**: `/api/cpq/import-session/upload`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: multipart/form-data`
- **路径参数**: 无
- **查询参数**: 无
- **请求体**（multipart 表单）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerId | UUID | 是 | 客户 ID；空抛 400 |
| file | 文件 | 是 | Excel(.xlsx) 文件；空抛 400，文件名缺失回退 `unknown.xlsx` |

- **响应内容**: `ApiResponse<UploadResultDTO>`。异常抛 400 `上传解析失败: ...`。

`UploadResultDTO`:

| 字段 | 类型 | 说明 |
|------|------|------|
| sessionId | UUID | 新建的 import_session ID，贯穿 Step 2/3 |
| diffPayload | DiffPayloadDTO | 差异检测结果 |

`DiffPayloadDTO`:

| 字段 | 类型 | 说明 |
|------|------|------|
| partVersionDecisions | List<PartVersionDecisionItem> | 料号版本变更决策列表（每个 customerProductNo+hfPartNo 一条，渲染 BUMP/NO_BUMP 开关） |
| customerConflicts | List<CustomerConflictItem> | 客户料号冲突列表 |
| orphanRows | List<OrphanItem> | 孤儿行列表 |
| validation | ValidationSummary | 校验汇总，含 `hasErrors`(boolean)、`errors`(List<String>)、`warnings`(List<String>)；hasErrors=true 时上传被阻塞不建 session |

#### Step 2 更新版本决策
- **功能**: 批量 upsert 用户的 BUMP/NO_BUMP 等决策（幂等，前端 debounce 调用）。
- **方法**: PUT
- **路径**: `/api/cpq/import-session/{id}/decisions`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: application/json`
- **路径参数**: `id`(UUID) session ID
- **请求体**: `DecisionUpdateRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| decisions | List<DecisionEntry> | 是 | 决策条目列表（可批量）；请求体为空抛 400 |

`DecisionEntry`:

| 字段 | 类型 | 说明 |
|------|------|------|
| decisionType | String | 决策类型 `PART_VERSION`/`CUSTOMER_CONFLICT`/`ORPHAN` |
| decisionKey | String | 决策业务键：PART_VERSION=`{customerProductNo}\|{hfPartNo}`；CUSTOMER_CONFLICT=`{conflictType}\|{primaryKey}`；ORPHAN=`{sheetCode}\|{rowIndex}` |
| decisionValueJson | String | 决策值原始 JSON，直接写入 decision_value JSONB 列（如 `{"action":"BUMP","currentVersion":2000,"suggestedVersion":2001}`） |

- **响应内容**: `ApiResponse<Void>`（code=200, data=null）。

#### Step 3 提交（合并 staging + 建报价单）
- **功能**: 按当前决策合并 staging → mat_*，创建报价单，返回新报价单 ID。
- **方法**: POST
- **路径**: `/api/cpq/import-session/{id}/commit`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`
- **请求头**: `Content-Type: application/json`
- **路径参数**: `id`(UUID) session ID
- **请求体**: `CommitRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | 是 | 报价单名称；空/空白抛 400 |
| categoryId | UUID | 否 | 报价单分类 ID |
| customerTemplateId | UUID | 否 | 客户报价模板 ID（template_kind=QUOTATION） |
| costingTemplateId | UUID | 否 | 核价模板 ID（template_kind=COSTING） |

- **响应内容**: `ApiResponse<CommitResult>`。用户 ID 取当前会话（回退默认值）。

`CommitResult`:

| 字段 | 类型 | 说明 |
|------|------|------|
| quotationId | UUID | 新建报价单 ID（前端跳转 `/quotations/{id}/edit`） |
| sessionId | UUID | 已 COMMITTED 的 session ID（审计用） |
| importRecordId | UUID | 本次 commit 创建的 import_record ID（跳转时附带 `?importRecordId=...` 精确过滤本次导入料号） |

#### 取消会话
- **功能**: 取消/放弃当前 session，staging 数据经 CASCADE DELETE 清除。
- **方法**: DELETE
- **路径**: `/api/cpq/import-session/{id}`
- **鉴权**: `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`
- **路径参数**: `id`(UUID) session ID
- **响应内容**: `ApiResponse<Void>`（code=200, data=null）。
## 十、3D 配置器与选配

> 基址：`http://localhost:8081`；鉴权=会话 Cookie（`@RoleAllowed` 端点需登录且具备指定角色，请求头须带登录 Cookie；无该注解的类/方法不校验角色，仅依赖全局 auth filter）。
> 统一响应包 `ApiResponse<T> = { code, message, data }`（`code=0` 表示成功）；`ConfiguratorShareResource` 中 `product_config_share` 等实体、`PartVersionResource` 直返手工构造 `Response`（`{code,message,data}`，`code=0` ok / `code=400` 错误），据实标注。

---

### 10.1 ConfiguratorTemplateResource（3D 选配模板 + 选项/取值/3D规则/业务引用 CRUD）

- 类级 `@Path`: `/api/cpq/configurator-templates`
- 类级鉴权: `@RoleAllowed({SYSTEM_ADMIN, PRICING_MANAGER, SALES_MANAGER, SALES_REP})`
- 说明：`product_config_template`（3D 产品选配模板），与 `config_template`（LIST_FORMULA 数据源）是两套独立系统。

#### 分页查询选配模板
- **功能**: 按状态/分类/关键字分页列出选配模板
- **方法**: GET
- **路径**: `/api/cpq/configurator-templates`
- **鉴权**: SYSTEM_ADMIN / PRICING_MANAGER / SALES_MANAGER / SALES_REP
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |
| status | String | 否 | 状态过滤（DRAFT/PUBLISHED/ARCHIVED） |
| category | String | 否 | 分类过滤 |
| keyword | String | 否 | 关键字（code/name 模糊） |

- **响应内容**: `ApiResponse<PageResult<ConfiguratorTemplate>>`（PageResult 含 content 列表 + 分页元信息；ConfiguratorTemplate 字段见下方「ConfiguratorTemplate 实体」）

#### 查询选配模板详情
- **功能**: 按 id 获取单个选配模板
- **方法**: GET
- **路径**: `/api/cpq/configurator-templates/{id}`
- **鉴权**: 同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |

- **响应内容**: `ApiResponse<ConfiguratorTemplate>`

#### 新建选配模板
- **功能**: 创建选配模板
- **方法**: POST
- **路径**: `/api/cpq/configurator-templates`
- **鉴权**: 同类级
- **请求体**: `ConfiguratorTemplate` 实体（见下方「ConfiguratorTemplate 实体」字段表）
- **响应内容**: `ApiResponse<ConfiguratorTemplate>`（已入库对象）

#### 更新选配模板
- **功能**: 局部更新选配模板（patch）
- **方法**: PUT
- **路径**: `/api/cpq/configurator-templates/{id}`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 模板 ID |`
- **请求体**: `Map<String,Object>` 任意可更新字段的键值对（局部更新）
- **响应内容**: `ApiResponse<ConfiguratorTemplate>`

#### 发布选配模板
- **功能**: 将模板状态置为发布
- **方法**: POST
- **路径**: `/api/cpq/configurator-templates/{id}/publish`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 模板 ID |`
- **响应内容**: `ApiResponse<ConfiguratorTemplate>`

#### 归档选配模板
- **功能**: 归档模板
- **方法**: POST
- **路径**: `/api/cpq/configurator-templates/{id}/archive`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 模板 ID |`
- **响应内容**: `ApiResponse<Void>`（data=null）

#### 查询模板选项列表
- **功能**: 列出模板下所有选项
- **方法**: GET
- **路径**: `/api/cpq/configurator-templates/{id}/options`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 模板 ID |`
- **响应内容**: `ApiResponse<List<ConfiguratorOption>>`（字段见「ConfiguratorOption 实体」）

#### 新增模板选项
- **功能**: 给模板添加一个选项
- **方法**: POST
- **路径**: `/api/cpq/configurator-templates/{id}/options`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 模板 ID |`
- **请求体**: `ConfiguratorOption` 实体
- **响应内容**: `ApiResponse<ConfiguratorOption>`

#### 查询选项取值列表
- **功能**: 列出指定选项下所有取值
- **方法**: GET
- **路径**: `/api/cpq/configurator-templates/options/{optionId}/values`
- **鉴权**: 同类级
- **路径参数**: `| optionId | UUID | 选项 ID |`
- **响应内容**: `ApiResponse<List<ConfiguratorOptionValue>>`（字段见「ConfiguratorOptionValue 实体」）

#### 新增选项取值
- **功能**: 给选项添加一个取值
- **方法**: POST
- **路径**: `/api/cpq/configurator-templates/options/{optionId}/values`
- **鉴权**: 同类级
- **路径参数**: `| optionId | UUID | 选项 ID |`
- **请求体**: `ConfiguratorOptionValue` 实体
- **响应内容**: `ApiResponse<ConfiguratorOptionValue>`

#### 更新选项
- **功能**: 局部更新选项
- **方法**: PUT
- **路径**: `/api/cpq/configurator-templates/options/{optionId}`
- **鉴权**: 同类级
- **路径参数**: `| optionId | UUID | 选项 ID |`
- **请求体**: `Map<String,Object>` 局部更新键值
- **响应内容**: `ApiResponse<ConfiguratorOption>`

#### 删除选项
- **功能**: 删除选项
- **方法**: DELETE
- **路径**: `/api/cpq/configurator-templates/options/{optionId}`
- **鉴权**: 同类级
- **路径参数**: `| optionId | UUID | 选项 ID |`
- **响应内容**: `ApiResponse<Void>`

#### 更新选项取值
- **功能**: 局部更新选项取值
- **方法**: PUT
- **路径**: `/api/cpq/configurator-templates/values/{valueId}`
- **鉴权**: 同类级
- **路径参数**: `| valueId | UUID | 取值 ID |`
- **请求体**: `Map<String,Object>` 局部更新键值
- **响应内容**: `ApiResponse<ConfiguratorOptionValue>`

#### 删除选项取值
- **功能**: 删除选项取值
- **方法**: DELETE
- **路径**: `/api/cpq/configurator-templates/values/{valueId}`
- **鉴权**: 同类级
- **路径参数**: `| valueId | UUID | 取值 ID |`
- **响应内容**: `ApiResponse<Void>`

#### 查询取值的 3D 规则列表
- **功能**: 列出选项取值绑定的 3D 渲染规则
- **方法**: GET
- **路径**: `/api/cpq/configurator-templates/values/{valueId}/3d-rules`
- **鉴权**: 同类级
- **路径参数**: `| valueId | UUID | 取值 ID |`
- **响应内容**: `ApiResponse<List<Configurator3DRule>>`（字段见「Configurator3DRule 实体」）

#### 新增 3D 规则
- **功能**: 给取值添加一条 3D 渲染规则（SHOW_MESH/HIDE_MESH/REPLACE_MATERIAL/SWAP_MESH/TRANSFORM_MESH）
- **方法**: POST
- **路径**: `/api/cpq/configurator-templates/values/{valueId}/3d-rules`
- **鉴权**: 同类级
- **路径参数**: `| valueId | UUID | 取值 ID |`
- **请求体**: `Configurator3DRule` 实体
- **响应内容**: `ApiResponse<Configurator3DRule>`

#### 更新 3D 规则
- **功能**: 局部更新 3D 规则
- **方法**: PUT
- **路径**: `/api/cpq/configurator-templates/3d-rules/{ruleId}`
- **鉴权**: 同类级
- **路径参数**: `| ruleId | UUID | 规则 ID |`
- **请求体**: `Map<String,Object>` 局部更新键值
- **响应内容**: `ApiResponse<Configurator3DRule>`

#### 删除 3D 规则
- **功能**: 删除 3D 规则
- **方法**: DELETE
- **路径**: `/api/cpq/configurator-templates/3d-rules/{ruleId}`
- **鉴权**: 同类级
- **路径参数**: `| ruleId | UUID | 规则 ID |`
- **响应内容**: `ApiResponse<Void>`

#### 查询取值的业务实体引用列表
- **功能**: 列出选项取值绑定的业务实体引用（§18A，替代 mat_feature_reference）
- **方法**: GET
- **路径**: `/api/cpq/configurator-templates/values/{valueId}/refs`
- **鉴权**: 同类级
- **路径参数**: `| valueId | UUID | 取值 ID |`
- **响应内容**: `ApiResponse<List<ConfiguratorValueReference>>`（字段见「ConfiguratorValueReference 实体」）

#### 新增业务实体引用
- **功能**: 给取值添加业务实体引用（MATERIAL/PROCESS/COMPONENT/COST_ITEM/GLOBAL_VAR）
- **方法**: POST
- **路径**: `/api/cpq/configurator-templates/values/{valueId}/refs`
- **鉴权**: 同类级
- **路径参数**: `| valueId | UUID | 取值 ID |`
- **请求体**: `ConfiguratorValueReference` 实体
- **响应内容**: `ApiResponse<ConfiguratorValueReference>`

#### 更新业务实体引用
- **功能**: 局部更新业务实体引用
- **方法**: PUT
- **路径**: `/api/cpq/configurator-templates/refs/{refId}`
- **鉴权**: 同类级
- **路径参数**: `| refId | UUID | 引用 ID |`
- **请求体**: `Map<String,Object>` 局部更新键值
- **响应内容**: `ApiResponse<ConfiguratorValueReference>`

#### 删除业务实体引用
- **功能**: 删除业务实体引用
- **方法**: DELETE
- **路径**: `/api/cpq/configurator-templates/refs/{refId}`
- **鉴权**: 同类级
- **路径参数**: `| refId | UUID | 引用 ID |`
- **响应内容**: `ApiResponse<Void>`

#### 从特征库导入特征
- **功能**: 将特征库字段批量导入为模板选项
- **方法**: POST
- **路径**: `/api/cpq/configurator-templates/{id}/import-features`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 模板 ID |`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| feature_field_ids | List&lt;Long&gt; | 是 | 特征库字段 ID 列表（非 list 抛 IllegalArgumentException） |

- **响应内容**: `ApiResponse<Map<String,Object>>`（导入结果统计，键值由 service.importFeatures 决定）

#### 设置模板基础 3D 模型
- **功能**: 为模板绑定基础 3D 模型
- **方法**: POST
- **路径**: `/api/cpq/configurator-templates/{id}/base-model`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 模板 ID |`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| model_id | String(UUID) | 是 | 3D 模型 ID（缺失抛 IllegalArgumentException） |

- **响应内容**: `ApiResponse<ConfiguratorTemplate>`（更新后的模板）

##### ConfiguratorTemplate 实体（表 product_config_template）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键（自动生成） |
| code | String(64) | 模板编码，唯一，非空 |
| name | String(128) | 模板名称，非空 |
| category | String(80) | 分类 |
| basePartNo | String(64) | 基础料号（列 base_part_no） |
| baseModelId | UUID | 基础 3D 模型 ID（列 base_model_id） |
| baseModelVersion | Integer | 基础模型版本（列 base_model_version） |
| baseModelSnapshotAt | OffsetDateTime | 模型快照时间（列 base_model_snapshot_at） |
| description | String(TEXT) | 描述 |
| showPrice | Boolean | 是否展示价格（列 show_price，默认 true） |
| metadata | Map(JSONB) | 扩展元数据 |
| status | String(16) | 状态，默认 DRAFT |
| version | Integer | 版本号，默认 1 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |
| createdBy | UUID | 创建人 |
| updatedBy | UUID | 更新人 |

##### ConfiguratorOption 实体（表 product_config_option）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| templateId | UUID | 所属模板 ID（列 template_id，非空） |
| code | String(64) | 选项编码（模板内唯一），非空 |
| label | String(128) | 选项显示名，非空 |
| optionType | String(32) | 选项类型：EXCLUSIVE/MULTI_SELECT/NUMERIC/TEXT/COLOR（列 option_type） |
| dataType | String(20) | 数据类型（列 data_type） |
| assignMode | String(20) | 赋值模式（列 assign_mode） |
| isRequired | Boolean | 是否必选（列 is_required，默认 true） |
| defaultValue | String(128) | 默认值（列 default_value） |
| minValue | String(40) | 最小值（列 min_value） |
| maxValue | String(40) | 最大值（列 max_value） |
| partnoPrefix | String(20) | 料号前缀（列 partno_prefix） |
| partnoSuffix | String(20) | 料号后缀（列 partno_suffix） |
| sortOrder | Integer | 排序（列 sort_order，默认 0） |
| description | String(TEXT) | 描述 |
| metadata | Map(JSONB) | 扩展元数据 |
| sourceFeatureFieldId | Long | 来源特征库字段 ID（列 source_feature_field_id） |
| sourceFeatureSnapshotAt | OffsetDateTime | 特征快照时间（列 source_feature_snapshot_at） |
| createdAt / updatedAt | OffsetDateTime | 创建/更新时间 |

##### ConfiguratorOptionValue 实体（表 product_config_option_value）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| optionId | UUID | 所属选项 ID（列 option_id，非空） |
| code | String(64) | 取值编码（选项内唯一），非空 |
| label | String(128) | 取值显示名，非空 |
| description | String(TEXT) | 描述 |
| priceDelta | BigDecimal(18,4) | 价格增量（列 price_delta，默认 0） |
| sortOrder | Integer | 排序（列 sort_order，默认 0） |
| partnoInclude | Boolean | 是否计入料号（列 partno_include，默认 true） |
| isActive | Boolean | 是否启用（列 is_active，默认 true） |
| featureType | String(40) | 特征类型（列 feature_type） |
| attributes | Map(JSONB) | 特征属性 |
| tags | String[] | 标签数组（TEXT[]） |
| geometryRef | Map(JSONB) | 几何引用（列 geometry_ref） |
| subModelPartNo | String(64) | 绑定的独立子模型料号（列 sub_model_part_no） |
| attachMode | String(20) | 附加模式（列 attach_mode） |
| attachPosition | Map(JSONB) | 附加位置（列 attach_position） |
| replaceBaseMesh | Boolean | 是否替换基础网格（列 replace_base_mesh，默认 false） |
| sourceFeatureValueId | Long | 来源特征库取值 ID（列 source_feature_value_id） |
| sourceFeatureSnapshotAt | OffsetDateTime | 特征快照时间（列 source_feature_snapshot_at） |
| localOnly | Boolean | 仅本地（列 local_only，默认 false） |
| createdAt / updatedAt | OffsetDateTime | 创建/更新时间 |

##### Configurator3DRule 实体（表 product_config_3d_rule）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| optionValueId | UUID | 所属取值 ID（列 option_value_id，非空） |
| action | String(32) | 动作：SHOW_MESH/HIDE_MESH/REPLACE_MATERIAL/SWAP_MESH/TRANSFORM_MESH，非空 |
| targetMesh | String(128) | 目标网格名（列 target_mesh） |
| params | Map(JSONB) | 动作参数 |
| sortOrder | Integer | 排序（列 sort_order，默认 0） |
| createdAt | OffsetDateTime | 创建时间 |

##### ConfiguratorValueReference 实体（表 product_config_value_reference）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| optionValueId | UUID | 所属取值 ID（列 option_value_id，非空） |
| refType | String(32) | 引用类型：MATERIAL/PROCESS/COMPONENT/COST_ITEM/GLOBAL_VAR（列 ref_type，非空） |
| refCode | String(80) | 引用编码（列 ref_code，非空） |
| qty | String(40) | 用量 |
| unit | String(20) | 单位 |
| note | String(TEXT) | 备注 |
| metadata | Map(JSONB) | 扩展元数据 |
| sortOrder | Integer | 排序（列 sort_order，默认 0） |
| isActive | Boolean | 是否启用（列 is_active，默认 true） |
| createdAt / updatedAt | OffsetDateTime | 创建/更新时间 |
| createdBy | UUID | 创建人 |

---

### 10.2 ConfiguratorInstanceResource（选配实例运行时 CRUD + 求值 + 报价单联动）

- 类级 `@Path`: `/api/cpq/configurator/instances`
- 类级鉴权: `@RoleAllowed({SYSTEM_ADMIN, PRICING_MANAGER, SALES_MANAGER, SALES_REP})`
- 说明：实例状态机 DRAFT → SUBMITTED → LINKED；30 天未操作 → EXPIRED。

#### 分页查询选配实例
- **功能**: 按状态/客户/模板分页列出选配实例
- **方法**: GET
- **路径**: `/api/cpq/configurator/instances`
- **鉴权**: 同类级
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |
| status | String | 否 | 状态过滤 |
| customerId | UUID | 否 | 客户 ID 过滤 |
| templateId | UUID | 否 | 模板 ID 过滤 |

- **响应内容**: `ApiResponse<PageResult<ConfiguratorInstance>>`（字段见「ConfiguratorInstance 实体」）

#### 查询选配实例详情
- **功能**: 按 id 获取选配实例
- **方法**: GET
- **路径**: `/api/cpq/configurator/instances/{id}`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 实例 ID |`
- **响应内容**: `ApiResponse<ConfiguratorInstance>`

#### 新建选配实例
- **功能**: 创建选配实例
- **方法**: POST
- **路径**: `/api/cpq/configurator/instances`
- **鉴权**: 同类级
- **请求体**: `ConfiguratorInstance` 实体
- **响应内容**: `ApiResponse<ConfiguratorInstance>`

#### 更新选配实例
- **功能**: 更新选配实例（传整实体作为 patch）
- **方法**: PUT
- **路径**: `/api/cpq/configurator/instances/{id}`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 实例 ID |`
- **请求体**: `ConfiguratorInstance` 实体（作为 patch）
- **响应内容**: `ApiResponse<ConfiguratorInstance>`

#### 删除选配实例
- **功能**: 删除选配实例
- **方法**: DELETE
- **路径**: `/api/cpq/configurator/instances/{id}`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 实例 ID |`
- **响应内容**: `ApiResponse<Void>`

#### 按模板求值（约束校验 + 价格）
- **功能**: 传入已选值，按模板运行约束校验与价格计算（不落库）
- **方法**: POST
- **路径**: `/api/cpq/configurator/instances/evaluate-by-template/{templateId}`
- **鉴权**: 同类级
- **路径参数**: `| templateId | UUID | 模板 ID |`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| selectedValues | Map&lt;String,Object&gt; | 否 | 选项 code → 已选值；非 Map 时按空 Map 处理 |

- **响应内容**: `ApiResponse<Map<String,Object>>`（求值结果：价格/约束冲突等，键值由 service.evaluate 决定）

#### 实例报价单联动
- **功能**: 将实例与报价单关联/生成（link 动作）
- **方法**: POST
- **路径**: `/api/cpq/configurator/instances/{id}/link-action`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 实例 ID |`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| action | String | 是 | 动作名（缺失抛 IllegalArgumentException） |
| quotation_id | String(UUID) | 否 | 报价单 ID（空白则不解析） |

- **响应内容**: `ApiResponse<Map<String,Object>>`（联动结果）

#### 解除实例报价单关联
- **功能**: 解除实例与报价单的关联
- **方法**: POST
- **路径**: `/api/cpq/configurator/instances/{id}/unlink`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 实例 ID |`
- **响应内容**: `ApiResponse<Map<String,Object>>`（解除结果）

##### ConfiguratorInstance 实体（表 product_config_instance）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| instanceCode | String(40) | 实例编号，唯一，格式 CI-yyyyMM-序号（列 instance_code） |
| templateId | UUID | 模板 ID（列 template_id，非空） |
| templateVersion | Integer | 模板版本（列 template_version） |
| name | String(128) | 实例名称 |
| customerId | UUID | 客户 ID（列 customer_id） |
| customerLeadId | UUID | 客户线索 ID（列 customer_lead_id） |
| userId | UUID | 用户 ID（列 user_id） |
| shareToken | String(64) | 分享 token（列 share_token） |
| selectedValues | Map(JSONB) | 已选值（列 selected_values，非空） |
| configFingerprint | String(64) | 配置指纹（列 config_fingerprint） |
| computedTotalPrice | BigDecimal(18,4) | 计算总价（列 computed_total_price） |
| basePrice | BigDecimal(18,4) | 基础价（列 base_price） |
| status | String(16) | 状态，默认 DRAFT（DRAFT/SUBMITTED/LINKED/EXPIRED） |
| linkedQuotationId | UUID | 关联报价单 ID（列 linked_quotation_id） |
| linkedAt | OffsetDateTime | 关联时间（列 linked_at） |
| linkedBy | UUID | 关联人（列 linked_by） |
| generatedPartNo | String(64) | 生成料号（列 generated_part_no） |
| generatedQuotationId | UUID | 生成的报价单 ID（列 generated_quotation_id） |
| generatedLineItemId | UUID | 生成的报价行 ID（列 generated_line_item_id） |
| expiresAt | OffsetDateTime | 过期时间（列 expires_at） |
| createdAt / updatedAt | OffsetDateTime | 创建/更新时间 |

---

### 10.3 ConfiguratorShareResource（选配实例分享链接管理）

- 类级 `@Path`: `/api/cpq/configurator/shares`
- 类级鉴权: `@RoleAllowed({SYSTEM_ADMIN, SALES_MANAGER, SALES_REP, PRICING_MANAGER})`

#### 分页查询分享链接
- **功能**: 按状态/分享类型/关键字分页列出分享链接
- **方法**: GET
- **路径**: `/api/cpq/configurator/shares`
- **鉴权**: 同类级
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |
| status | String | 否 | 状态过滤（ACTIVE/REVOKED…） |
| shareType | String | 否 | 分享类型（CUSTOMER_SELF/INTERNAL/PUBLIC_PRESET） |
| keyword | String | 否 | 关键字 |

- **响应内容**: `ApiResponse<PageResult<ConfiguratorShare>>`（字段见「ConfiguratorShare 实体」）

#### 分享统计
- **功能**: 返回分享链接按状态等维度的计数
- **方法**: GET
- **路径**: `/api/cpq/configurator/shares/stats`
- **鉴权**: 同类级
- **响应内容**: `ApiResponse<Map<String,Long>>`（维度名 → 计数）

#### 创建分享链接
- **功能**: 为某实例创建分享链接
- **方法**: POST
- **路径**: `/api/cpq/configurator/shares`
- **鉴权**: 同类级
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| instance_id | String(UUID) | 是 | 实例 ID |
| email | String | 否 | 分享给的邮箱 |
| share_type | String | 否 | 分享类型 |
| days | Number | 否 | 有效天数，默认 7 |
| can_modify | Boolean | 否 | 是否允许修改，默认 false |

- **响应内容**: `ApiResponse<ConfiguratorShare>`

#### 按 token 查询分享
- **功能**: 用分享 token 查分享记录（公网客户访问入口，由全局过滤器例外放行）
- **方法**: GET
- **路径**: `/api/cpq/configurator/shares/by-token/{token}`
- **鉴权**: 方法级 `@RoleAllowed({SYSTEM_ADMIN, SALES_MANAGER, SALES_REP, PRICING_MANAGER})`（注释标注为公网无认证访问，实际由全局过滤器例外处理）
- **路径参数**: `| token | String | 分享 token |`
- **响应内容**: `ApiResponse<ConfiguratorShare>`（找不到抛 404 NotFoundException）

#### 查询分享详情
- **功能**: 按 id 获取分享记录
- **方法**: GET
- **路径**: `/api/cpq/configurator/shares/{id}`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 分享 ID |`
- **响应内容**: `ApiResponse<ConfiguratorShare>`

#### 查询分享访问日志
- **功能**: 列出某分享链接的访问记录
- **方法**: GET
- **路径**: `/api/cpq/configurator/shares/{id}/access-log`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 分享 ID |`
- **响应内容**: `ApiResponse<List<ConfiguratorShareAccess>>`（字段见「ConfiguratorShareAccess 实体」）

#### 延长分享有效期
- **功能**: 延长分享链接过期时间
- **方法**: POST
- **路径**: `/api/cpq/configurator/shares/{id}/extend`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 分享 ID |`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| days | Number | 否 | 延长天数，默认 7 |

- **响应内容**: `ApiResponse<ConfiguratorShare>`

#### 撤销分享
- **功能**: 撤销分享链接
- **方法**: POST
- **路径**: `/api/cpq/configurator/shares/{id}/revoke`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 分享 ID |`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| reason | String | 否 | 撤销原因 |

- **响应内容**: `ApiResponse<ConfiguratorShare>`

##### ConfiguratorShare 实体（表 product_config_share）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| instanceId | UUID | 实例 ID（列 instance_id，非空） |
| shareType | String(32) | 分享类型：CUSTOMER_SELF/INTERNAL/PUBLIC_PRESET（列 share_type，非空） |
| shareToken | String(64) | 分享 token，唯一（列 share_token，非空） |
| sharedBy | UUID | 分享人（列 shared_by） |
| sharedToUserId | UUID | 分享给的用户 ID（列 shared_to_user_id） |
| sharedToEmail | String(128) | 分享给的邮箱（列 shared_to_email） |
| expiresAt | OffsetDateTime | 过期时间（列 expires_at） |
| accessCount | Integer | 访问次数（列 access_count，默认 0） |
| lastAccessedAt | OffsetDateTime | 最近访问时间（列 last_accessed_at） |
| canModify | Boolean | 是否允许修改（列 can_modify，默认 false） |
| status | String(16) | 状态，默认 ACTIVE |
| revokedAt | OffsetDateTime | 撤销时间（列 revoked_at） |
| revokedBy | UUID | 撤销人（列 revoked_by） |
| revokeReason | String(TEXT) | 撤销原因（列 revoke_reason） |
| createdAt | OffsetDateTime | 创建时间 |

##### ConfiguratorShareAccess 实体（表 product_config_share_access）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| shareId | UUID | 分享 ID（列 share_id，非空） |
| accessedAt | OffsetDateTime | 访问时间（列 accessed_at，非空） |
| ip | String(64) | 访问 IP |
| userAgent | String(TEXT) | 浏览器 UA（列 user_agent） |
| action | String(255) | 动作 |

---

### 10.4 ConfiguratorVersionResource（选配模板版本快照/对比/回滚）

- 类级 `@Path`: `/api/cpq/configurator-templates`
- 类级鉴权: `@RoleAllowed({SYSTEM_ADMIN, PRICING_MANAGER})`

#### 查询模板版本列表
- **功能**: 列出模板的所有版本快照
- **方法**: GET
- **路径**: `/api/cpq/configurator-templates/{templateId}/versions`
- **鉴权**: SYSTEM_ADMIN / PRICING_MANAGER
- **路径参数**: `| templateId | UUID | 模板 ID |`
- **响应内容**: `ApiResponse<List<ConfiguratorTemplateVersion>>`（字段见「ConfiguratorTemplateVersion 实体」）

#### 创建版本快照
- **功能**: 对当前模板生成一个版本快照
- **方法**: POST
- **路径**: `/api/cpq/configurator-templates/{templateId}/versions/snapshot`
- **鉴权**: 同类级
- **路径参数**: `| templateId | UUID | 模板 ID |`
- **请求体**:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| label | String | 否 | 版本标签 |
| changeSummary | String | 否 | 变更摘要 |

- **响应内容**: `ApiResponse<ConfiguratorTemplateVersion>`

#### 版本差异对比
- **功能**: 对比两个版本快照
- **方法**: GET
- **路径**: `/api/cpq/configurator-templates/versions/diff`
- **鉴权**: 同类级
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| v1 | UUID | 是 | 版本 1 ID |
| v2 | UUID | 是 | 版本 2 ID |

- **响应内容**: `ApiResponse<Map<String,Object>>`（差异结构，键值由 service.diffVersions 决定）

#### 版本回滚
- **功能**: 将模板回滚到指定历史版本
- **方法**: POST
- **路径**: `/api/cpq/configurator-templates/{templateId}/versions/{versionId}/rollback`
- **鉴权**: 同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| templateId | UUID | 模板 ID |
| versionId | UUID | 目标版本 ID |

- **响应内容**: `ApiResponse<Map<String,Object>>`（回滚结果）

##### ConfiguratorTemplateVersion 实体（表 product_config_template_version）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| templateId | UUID | 模板 ID（列 template_id，非空；与 version 联合唯一） |
| version | Integer | 版本号，非空 |
| label | String(64) | 版本标签 |
| status | String(16) | 状态，非空 |
| snapshot | Map(JSONB) | 模板快照全量，非空 |
| changeSummary | String(TEXT) | 变更摘要（列 change_summary） |
| createdBy | UUID | 创建人（列 created_by） |
| createdAt | OffsetDateTime | 创建时间 |
| publishedAt | OffsetDateTime | 发布时间（列 published_at） |
| archivedAt | OffsetDateTime | 归档时间（列 archived_at） |

---

### 10.5 ValveDemoSeedResource（阀门 demo 数据 seed 工具，仅 DEV 环境）

- 类级 `@Path`: `/api/cpq/admin/seed/valve-demo`
- 类级鉴权: `@RoleAllowed({SYSTEM_ADMIN})`
- 说明：仅 DEVELOPMENT 模式可用（非 dev 抛 403 FORBIDDEN）；配套 docs/sql-seed/valve-demo.sql。

#### 灌入阀门 demo 数据
- **功能**: 幂等灌入球阀选配全流程 demo 数据（模型/特征库/模板/选项/取值/约束/实例/线索/分享）
- **方法**: POST
- **路径**: `/api/cpq/admin/seed/valve-demo`
- **鉴权**: SYSTEM_ADMIN，且仅 DEV 模式
- **请求体**: 无
- **响应内容**: `ApiResponse<Map<String,Object>>`（各表入库计数统计）

| 字段 | 类型 | 说明 |
|------|------|------|
| mat_part_models | long | 模型行数 |
| feature_groups | long | 特征群组数 |
| feature_fields | long | 特征字段数 |
| feature_values | long | 特征取值数 |
| templates | long | 模板数 |
| options | long | 选项数 |
| option_values | long | 选项取值数 |
| constraints | long | 约束数 |
| instances | long | 实例数 |
| leads | long | 客户线索数 |
| shares | long | 分享链接数 |
| status | String | 状态提示文案 |

#### 回滚阀门 demo 数据
- **功能**: 幂等删除上述 demo 数据
- **方法**: DELETE
- **路径**: `/api/cpq/admin/seed/valve-demo`
- **鉴权**: SYSTEM_ADMIN，且仅 DEV 模式
- **响应内容**: `ApiResponse<Map<String,Object>>`（各表删除计数 deleted，含 shares/instances/leads/constraints/option_values/options/templates/feature_values/feature_fields/feature_groups/source_files/mat_part_models + status）

---

### 10.6 FeatureLibraryResource（特征库群组/字段/取值 CRUD，§18A）

- 类级 `@Path`: `/api/cpq/feature-library`
- 类级鉴权: `@RoleAllowed({SYSTEM_ADMIN, PRICING_MANAGER, SALES_MANAGER})`

#### 分页查询特征群组
- **功能**: 按状态/分类/关键字分页列出特征群组
- **方法**: GET
- **路径**: `/api/cpq/feature-library/groups`
- **鉴权**: SYSTEM_ADMIN / PRICING_MANAGER / SALES_MANAGER
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |
| status | String | 否 | 状态过滤 |
| category | String | 否 | 分类过滤 |
| keyword | String | 否 | 关键字 |

- **响应内容**: `ApiResponse<PageResult<FeatureGroup>>`（字段见「FeatureGroup 实体」）

#### 查询特征群组详情
- **功能**: 按 id 获取特征群组
- **方法**: GET
- **路径**: `/api/cpq/feature-library/groups/{id}`
- **鉴权**: 同类级
- **路径参数**: `| id | Long | 群组 ID |`
- **响应内容**: `ApiResponse<FeatureGroup>`

#### 新建特征群组
- **功能**: 创建特征群组
- **方法**: POST
- **路径**: `/api/cpq/feature-library/groups`
- **鉴权**: 同类级
- **请求体**: `FeatureGroup` 实体
- **响应内容**: `ApiResponse<FeatureGroup>`

#### 更新特征群组
- **功能**: 更新特征群组（传实体 patch）
- **方法**: PUT
- **路径**: `/api/cpq/feature-library/groups/{id}`
- **鉴权**: 同类级
- **路径参数**: `| id | Long | 群组 ID |`
- **请求体**: `FeatureGroup` 实体
- **响应内容**: `ApiResponse<FeatureGroup>`

#### 归档特征群组
- **功能**: 归档特征群组
- **方法**: POST
- **路径**: `/api/cpq/feature-library/groups/{id}/archive`
- **鉴权**: 同类级
- **路径参数**: `| id | Long | 群组 ID |`
- **响应内容**: `ApiResponse<Void>`

#### 查询群组字段列表
- **功能**: 列出群组下所有字段
- **方法**: GET
- **路径**: `/api/cpq/feature-library/groups/{groupId}/fields`
- **鉴权**: 同类级
- **路径参数**: `| groupId | Long | 群组 ID |`
- **响应内容**: `ApiResponse<List<FeatureField>>`（字段见「FeatureField 实体」）

#### 新增群组字段
- **功能**: 给群组添加字段
- **方法**: POST
- **路径**: `/api/cpq/feature-library/groups/{groupId}/fields`
- **鉴权**: 同类级
- **路径参数**: `| groupId | Long | 群组 ID |`
- **请求体**: `FeatureField` 实体
- **响应内容**: `ApiResponse<FeatureField>`

#### 更新字段
- **功能**: 更新特征字段（传实体 patch）
- **方法**: PUT
- **路径**: `/api/cpq/feature-library/fields/{fieldId}`
- **鉴权**: 同类级
- **路径参数**: `| fieldId | Long | 字段 ID |`
- **请求体**: `FeatureField` 实体
- **响应内容**: `ApiResponse<FeatureField>`

#### 删除字段
- **功能**: 删除特征字段
- **方法**: DELETE
- **路径**: `/api/cpq/feature-library/fields/{fieldId}`
- **鉴权**: 同类级
- **路径参数**: `| fieldId | Long | 字段 ID |`
- **响应内容**: `ApiResponse<Void>`

#### 查询字段取值列表
- **功能**: 列出字段下所有取值
- **方法**: GET
- **路径**: `/api/cpq/feature-library/fields/{fieldId}/values`
- **鉴权**: 同类级
- **路径参数**: `| fieldId | Long | 字段 ID |`
- **响应内容**: `ApiResponse<List<FeatureValue>>`（字段见「FeatureValue 实体」）

#### 新增字段取值
- **功能**: 给字段添加取值
- **方法**: POST
- **路径**: `/api/cpq/feature-library/fields/{fieldId}/values`
- **鉴权**: 同类级
- **路径参数**: `| fieldId | Long | 字段 ID |`
- **请求体**: `FeatureValue` 实体
- **响应内容**: `ApiResponse<FeatureValue>`

#### 更新取值
- **功能**: 更新特征取值（传实体 patch）
- **方法**: PUT
- **路径**: `/api/cpq/feature-library/values/{valueId}`
- **鉴权**: 同类级
- **路径参数**: `| valueId | Long | 取值 ID |`
- **请求体**: `FeatureValue` 实体
- **响应内容**: `ApiResponse<FeatureValue>`

#### 删除取值
- **功能**: 删除特征取值
- **方法**: DELETE
- **路径**: `/api/cpq/feature-library/values/{valueId}`
- **鉴权**: 同类级
- **路径参数**: `| valueId | Long | 取值 ID |`
- **响应内容**: `ApiResponse<Void>`

#### 群组被模板引用计数
- **功能**: 统计各特征群组被选配模板引用的次数
- **方法**: GET
- **路径**: `/api/cpq/feature-library/groups/template-refs`
- **鉴权**: 同类级
- **响应内容**: `ApiResponse<Map<Long,Integer>>`（群组 ID → 引用次数）

#### 计算模板刷新差异
- **功能**: 计算模板与特征库快照的差异（重新拉取差异）
- **方法**: GET
- **路径**: `/api/cpq/feature-library/refresh-diff/{templateId}`
- **鉴权**: 同类级
- **路径参数**: `| templateId | UUID | 模板 ID |`
- **响应内容**: `ApiResponse<List<Map<String,Object>>>`（差异条目列表）

##### FeatureGroup 实体（表 cpq_feature_group）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键（IDENTITY 自增） |
| code | String(40) | 群组编码，唯一，非空 |
| name | String(255) | 群组名称，非空 |
| description | String(TEXT) | 描述 |
| category | String(80) | 分类 |
| status | String(20) | 状态，默认 DRAFT |
| erpRefCode | String(40) | ERP 引用编码（列 erp_ref_code） |
| extraAttrs | Map(JSONB) | 扩展属性（列 extra_attrs） |
| createdBy | String(64) | 创建人（列 created_by） |
| createdAt | OffsetDateTime | 创建时间 |
| updatedBy | String(64) | 更新人（列 updated_by） |
| updatedAt | OffsetDateTime | 更新时间 |

##### FeatureField 实体（表 cpq_feature_field）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键（IDENTITY 自增） |
| groupId | Long | 所属群组 ID（列 group_id，非空；与 code 联合唯一） |
| code | String(40) | 字段编码，非空 |
| name | String(255) | 字段名称，非空 |
| sortOrder | Integer | 排序（列 sort_order，默认 0） |
| dataType | String(20) | 数据类型：STRING/NUMBER/DATE/BOOLEAN（列 data_type，非空） |
| assignMode | String(20) | 赋值模式：MANUAL/SELECT/COMPUTED（列 assign_mode，非空） |
| isRequired | Boolean | 是否必填（列 is_required，默认 false） |
| defaultValue | String(255) | 默认值（列 default_value） |
| minValue | String(40) | 最小值（列 min_value） |
| maxValue | String(40) | 最大值（列 max_value） |
| codeLength | Integer | 编码长度（列 code_length） |
| decimalPlaces | Integer | 小数位数（列 decimal_places） |
| dataSourceRef | String(80) | 数据源引用（列 data_source_ref） |
| partnoPrefix | String(20) | 料号前缀（列 partno_prefix） |
| partnoSuffix | String(20) | 料号后缀（列 partno_suffix） |
| extraAttrs | Map(JSONB) | 扩展属性（列 extra_attrs） |
| createdAt / updatedAt | OffsetDateTime | 创建/更新时间 |

##### FeatureValue 实体（表 cpq_feature_value）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 主键（IDENTITY 自增） |
| fieldId | Long | 所属字段 ID（列 field_id，非空；与 code 联合唯一） |
| code | String(40) | 取值编码，非空 |
| label | String(255) | 取值显示名，非空 |
| description | String(TEXT) | 描述 |
| sortOrder | Integer | 排序（列 sort_order，默认 0） |
| partnoInclude | Boolean | 是否计入料号（列 partno_include，默认 true） |
| isActive | Boolean | 是否启用（列 is_active，默认 true） |
| extraAttrs | Map(JSONB) | 扩展属性（列 extra_attrs） |
| createdAt / updatedAt | OffsetDateTime | 创建/更新时间 |

---

### 10.7 PartModelResource（3D 模型注册管理）

- 类级 `@Path`: `/api/cpq/part-models`
- 类级鉴权: `@RoleAllowed({SYSTEM_ADMIN, PRICING_MANAGER, SALES_MANAGER})`

#### 分页查询 3D 模型
- **功能**: 按料号/是否当前版本分页列出 3D 模型
- **方法**: GET
- **路径**: `/api/cpq/part-models`
- **鉴权**: SYSTEM_ADMIN / PRICING_MANAGER / SALES_MANAGER
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |
| partNo | String | 否 | 料号过滤 |
| isCurrent | Boolean | 否 | 是否当前版本过滤 |

- **响应内容**: `ApiResponse<PageResult<PartModel>>`（字段见「PartModel 实体」）

#### 查询 3D 模型详情
- **功能**: 按 id 获取模型
- **方法**: GET
- **路径**: `/api/cpq/part-models/{id}`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 模型 ID |`
- **响应内容**: `ApiResponse<PartModel>`

#### 注册 3D 模型
- **功能**: 登记一个 3D 模型（版本 + is_current）
- **方法**: POST
- **路径**: `/api/cpq/part-models`
- **鉴权**: 同类级
- **请求体**: `PartModel` 实体
- **响应内容**: `ApiResponse<PartModel>`

#### 设为当前版本
- **功能**: 将该模型设为其料号下的当前版本
- **方法**: POST
- **路径**: `/api/cpq/part-models/{id}/set-current`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 模型 ID |`
- **响应内容**: `ApiResponse<PartModel>`

#### 上传 3D 源文件（未实现）
- **功能**: 上传 UG NX .prt + .stp 并触发转换流水线（占位，尚未实现）
- **方法**: POST
- **路径**: `/api/cpq/part-models/upload`
- **鉴权**: 同类级
- **请求体**: `Map<String,Object>`（暂未定义）
- **响应内容**: `ApiResponse<Map<String,Object>>`，固定返回 `{status:"not_implemented", todo:"P3 CAD 转换"}`

##### PartModel 实体（表 mat_part_model）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| partNo | String(64) | 料号（列 part_no，非空；与 version 联合唯一） |
| version | Integer | 版本，非空，默认 1 |
| label | String(255) | 显示名 |
| isCurrent | Boolean | 是否当前版本（列 is_current，非空，默认 true） |
| glbUrl | String(TEXT) | GLB 模型 URL（列 glb_url，非空） |
| thumbnailUrl | String(TEXT) | 缩略图 URL（列 thumbnail_url） |
| meshCount | Integer | 网格数（列 mesh_count） |
| vertices | Integer | 顶点数 |
| sizeKb | Integer | 大小 KB（列 size_kb） |
| metadata | Map(JSONB) | 元数据 |
| uploadedBy | UUID | 上传人（列 uploaded_by） |
| uploadedAt | OffsetDateTime | 上传时间（列 uploaded_at，非空） |

---

### 10.8 PartVersionResource（料号版本管理 S2，直返手工 Response）

- 类级 `@Path`: `/api/cpq/part-version`
- 类级鉴权: 无 `@RoleAllowed`（依赖全局 auth filter；系统角色为 SALES_REP/SALES_MANAGER/PRICING_MANAGER/SYSTEM_ADMIN）
- 说明：所有端点直返手工构造的 `Response`，包体 `{code,message,data}`，`code=0` 成功 / `code=400` 参数错误。

#### 查询料号版本信息
- **功能**: 查 (cpn, hf) 当前激活版本 + 全部历史版本日志
- **方法**: GET
- **路径**: `/api/cpq/part-version/{cpn}/{hf}`
- **鉴权**: 登录（全局 filter）
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| cpn | String | 客户产品号 customerProductNo |
| hf | String | HF 料号 hfPartNo |

- **响应内容**: `data` = 对象：

| 字段 | 类型 | 说明 |
|------|------|------|
| customerProductNo | String | 客户产品号 |
| hfPartNo | String | HF 料号 |
| currentVersion | int | 当前激活版本（缺省 2000） |
| history | List&lt;PartVersionLogDTO&gt; | 历史版本日志（见下方 DTO） |

#### 计算版本存储指纹
- **功能**: 计算指定版本的存储指纹（调试/校验用）
- **方法**: GET
- **路径**: `/api/cpq/part-version/{cpn}/{hf}/fingerprint`
- **鉴权**: 登录
- **路径参数**: `| cpn | String | 客户产品号 |` `| hf | String | HF 料号 |`
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| version | Integer | 否 | 版本号（不传取当前激活版本，缺省 2000） |

- **响应内容**: `data` = `{ customerProductNo, hfPartNo, version:int, contentHash:String }`

#### 升版三路判定
- **功能**: 传入待校验新数据，返回 NO_CHANGE / REVERT_TO_HISTORICAL / NEW_VERSION 判定（S2 占位）
- **方法**: POST
- **路径**: `/api/cpq/part-version/propose`
- **鉴权**: 登录
- **请求体**: `VersionProposeRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerProductNo | String | 是 | 客户产品号（空返回 400） |
| hfPartNo | String | 是 | HF 料号（空返回 400） |
| rowsByTable | Map&lt;String, List&lt;Map&lt;String,Object&gt;&gt;&gt; | 否 | 待校验新数据，按表名分组的行集；S2 可传空 Map |

- **响应内容**: `data` = `VersionDecisionDTO`（见下方 DTO）

#### 应用升版决策
- **功能**: 应用升版，写日志 + bump current_version
- **方法**: POST
- **路径**: `/api/cpq/part-version/{cpn}/{hf}/apply`
- **鉴权**: 登录
- **路径参数**: `| cpn | String | 客户产品号 |` `| hf | String | HF 料号 |`
- **请求体**: `ApplyBumpRequest`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| contentHash | String | 否 | 新数据内容指纹 |
| sourceExcel | String | 否 | 来源 Excel 文件名 |
| diffByTable | Map&lt;String, DiffSummary&gt; | 否 | 各表 diff 计数（DiffSummary 见下） |

- **响应内容**: `data` = `{ customerProductNo, hfPartNo, newVersion:int }`；body 为空或非法参数返回 400

#### 切换激活版本
- **功能**: 切换激活版本到某历史版本（REVERT 路径）
- **方法**: PUT
- **路径**: `/api/cpq/part-version/{cpn}/{hf}/switch/{version}`
- **鉴权**: 登录
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| cpn | String | 客户产品号 |
| hf | String | HF 料号 |
| version | int | 目标版本号 |

- **响应内容**: `data` = `{ customerProductNo, hfPartNo, activeVersion:int }`；非法参数返回 400

#### 清空报价基础数据（临时 admin）
- **功能**: 清空报价基础数据 + 报价单（不清模板/组件/客户/产品/核价表），仅测试重置用
- **方法**: POST
- **路径**: `/api/cpq/part-version/admin/wipe-basic-data`
- **鉴权**: 登录
- **响应内容**: `data` = `{ deletedByTable: Map<String,Integer>, note:String }`

##### PartVersionLogDTO（mat_part_version_log 一行）

| 字段 | 类型 | 说明 |
|------|------|------|
| customerProductNo | String | 客户产品号 |
| hfPartNo | String | HF 料号 |
| version | int | 版本号 |
| contentHash | String | 内容指纹 |
| diffSummaryJson | String | diff 摘要 JSONB 原始字符串（前端按需解析） |
| sourceExcel | String | 来源 Excel |
| sourceImportId | UUID | 来源导入批次 ID |
| createdAt | OffsetDateTime | 创建时间 |
| createdBy | UUID | 创建人 |

##### VersionDecisionDTO（三路判定结果）

| 字段 | 类型 | 说明 |
|------|------|------|
| action | Enum | NO_CHANGE / REVERT_TO_HISTORICAL / NEW_VERSION |
| currentVersion | int | 当前激活版本 |
| proposedVersion | int | 建议新版本（NEW_VERSION 时 = current+1） |
| matchedHash | String | 命中的历史指纹（REVERT 时） |
| diffByTable | Map&lt;String, DiffSummary&gt; | 各表 diff 计数 |
| allHistoricalVersions | List&lt;Integer&gt; | 全部历史版本号 |

##### DiffSummary（单表 diff 计数）

| 字段 | 类型 | 说明 |
|------|------|------|
| added | int | 新增行数 |
| changed | int | 变更行数 |
| deleted | int | 删除行数 |

---

### 10.9 SelTemplateResource（选配模板 sel-templates + 生效模板解析）

- 类级 `@Path`: `/api/cpq/sel-templates`
- 类级鉴权: `@RoleAllowed({PRICING_MANAGER, SALES_MANAGER, SYSTEM_ADMIN})`

#### 查询选配模板列表
- **功能**: 列出所有选配模板
- **方法**: GET
- **路径**: `/api/cpq/sel-templates`
- **鉴权**: PRICING_MANAGER / SALES_MANAGER / SYSTEM_ADMIN
- **响应内容**: `ApiResponse<List<SelTemplateDTO>>`（字段见「SelTemplateDTO」）

#### 查询客户生效模板
- **功能**: 按客户号解析实际生效的选配模板（命中具体行业或回退 __DEFAULT__）
- **方法**: GET
- **路径**: `/api/cpq/sel-templates/effective`
- **鉴权**: 同类级
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| customerNo | String | 是 | 客户号（空抛 BusinessException） |

- **响应内容**: `ApiResponse<EffectiveTemplateDTO>`（字段见「EffectiveTemplateDTO」）

#### 查询选配模板详情
- **功能**: 按 id 获取选配模板
- **方法**: GET
- **路径**: `/api/cpq/sel-templates/{id}`
- **鉴权**: 同类级
- **路径参数**: `| id | UUID | 模板 ID |`
- **响应内容**: `ApiResponse<SelTemplateDTO>`

#### 新建/更新选配模板
- **功能**: upsert 选配模板（含参数项配置）
- **方法**: POST
- **路径**: `/api/cpq/sel-templates`
- **鉴权**: 同类级
- **请求体**: `SelTemplateUpsertRequest`（`@Valid`）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| industryCode | String | 是 | 行业码（NotBlank） |
| name | String | 是 | 模板名（NotBlank） |
| status | String | 否 | 状态 |
| items | List&lt;Item&gt; | 否 | 参数项列表 |
| items[].paramTypeCode | String | 否 | 参数类型编码 |
| items[].enabled | boolean | 否 | 是否启用 |
| items[].allowedValues | List&lt;String&gt; | 否 | 限定值列表（空=不限） |

- **响应内容**: `ApiResponse<SelTemplateDTO>`

#### 删除选配模板
- **功能**: 删除选配模板
- **方法**: DELETE
- **路径**: `/api/cpq/sel-templates/{id}`
- **鉴权**: 方法级 `@RoleAllowed({SALES_MANAGER, SYSTEM_ADMIN})`
- **路径参数**: `| id | UUID | 模板 ID |`
- **响应内容**: `ApiResponse<Void>`

##### SelTemplateDTO

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 模板 ID |
| industryCode | String | 行业码 |
| name | String | 模板名 |
| status | String | 状态 |
| version | Integer | 版本 |
| createdAt / updatedAt | OffsetDateTime | 创建/更新时间 |
| items | List&lt;Item&gt; | 参数项列表 |
| items[].paramTypeCode | String | 参数类型编码 |
| items[].enabled | boolean | 是否启用 |
| items[].sortOrder | Integer | 排序 |
| items[].allowedValues | List&lt;String&gt; | 限定值（空=不限） |

##### EffectiveTemplateDTO

| 字段 | 类型 | 说明 |
|------|------|------|
| customerNo | String | 客户号 |
| resolvedIndustryCode | String | 实际命中的模板行业码（具体行业 / __DEFAULT__） |
| templateId | UUID | 模板 ID（null=客户行业与默认模板都没配） |
| usedDefault | boolean | 是否回退到 __DEFAULT__ |
| hasTemplate | boolean | 是否有可用模板（false=templateId 为 null） |
| params | List&lt;Param&gt; | 仅含 enabled=true 的参数 |
| params[].paramTypeCode | String | 参数类型编码：MATERIAL/ELEMENT/PROCESS |
| params[].name | String | 参数名：材质/元素含量/工序 |
| params[].valueMode | String | 取值模式：single/multi/adjust |
| params[].effectiveValues | List&lt;Value&gt; | 限定后的可选值（adjust 类为空） |
| Value.key | String | 值 key（材质 code / 工序 code） |
| Value.label | String | 值展示名 |

---

### 10.10 SelParamTypeResource（选配参数类型 + 候选值）

- 类级 `@Path`: `/api/cpq/sel-param-types`
- 类级鉴权: `@RoleAllowed({PRICING_MANAGER, SALES_MANAGER, SYSTEM_ADMIN})`

#### 查询参数类型列表
- **功能**: 列出所有选配参数类型
- **方法**: GET
- **路径**: `/api/cpq/sel-param-types`
- **鉴权**: PRICING_MANAGER / SALES_MANAGER / SYSTEM_ADMIN
- **响应内容**: `ApiResponse<List<SelParamTypeDTO>>`

| 字段 | 类型 | 说明 |
|------|------|------|
| code | String | 参数类型编码 |
| name | String | 参数类型名称 |
| valueMode | String | 取值模式（single/multi/adjust） |
| dataSourceKey | String | 数据源键 |
| persistHandlerKey | String | 持久化处理器键 |
| sortOrder | Integer | 排序 |

#### 查询参数候选值
- **功能**: 按参数类型编码取候选值列表
- **方法**: GET
- **路径**: `/api/cpq/sel-param-types/{code}/candidates`
- **鉴权**: 同类级
- **路径参数**: `| code | String | 参数类型编码 |`
- **响应内容**: `ApiResponse<List<ParamCandidateDTO>>`

| 字段 | 类型 | 说明 |
|------|------|------|
| key | String | 存入 allowed_value_key（如材质 code / 工序 code） |
| label | String | 展示名（如「304 不锈钢」） |
## 十一、产品配置与物料配方

> 基址 `http://localhost:8081`；鉴权=会话 Cookie（`@RoleAllowed` 端点需登录且具备对应角色，请求头须带 `Cookie`）。
> 本片段部分端点直返实体/DTO/`Response`，非统一 `ApiResponse<T>` 包裹，已在各端点「响应内容」处据实标注。

---

### 11.1 ConfigureProductResource（报价单选配产品：指纹查询 + 一锅端落库）

类级 `@Path`: `/api/cpq/configure-product`
类级鉴权: `@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`
类级 `@Produces`/`@Consumes`: `application/json`

> 2026-05-18 hotfix：类级路径由 `/api/cpq/quotations` 改为独立父级 `/api/cpq/configure-product`，规避与 `QuotationResource` 的 `/{id}` 系列路由匹配冲突。

#### 实时查询选配指纹（P2→P3 之间）
- **功能**: 在选配抽屉 P2 锁定配置后、P3 之前，根据配置内容实时计算指纹，判断是否命中已有料号（命中则复用快照，免重复落库）。
- **方法**: POST
- **路径**: `/api/cpq/configure-product/lookup-fingerprint`
- **鉴权**: 需登录，角色 SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**（LookupFingerprintRequest）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| productType | String | 是 | 产品类型：`SIMPLE`（独立产品）/ `COMPOSITE`（组合产品）|
| recipeCode | String | SIMPLE 必填 | 材质配方编码 |
| elements | ElementOverride[] | SIMPLE 必填 | 元素配比覆盖列表（见下）|
| childHfPartNos | String[] | COMPOSITE 必填 | 组合产品的子件料号列表 |

  ElementOverride 结构：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| elementCode | String | 是 | 元素编码 |
| pct | BigDecimal | 是 | 元素占比（%）|

- **响应内容**（直返 LookupFingerprintResponse，非 ApiResponse 包裹）:

| 字段 | 类型 | 说明 |
|------|------|------|
| matched | boolean | 是否命中已有指纹（true=可复用）|
| hfPartNo | String | 命中的料号（未命中为 null）|
| snapshot | Snapshot | 命中料号的快照数据（未命中为 null）|

  Snapshot 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| unitWeightGrams | BigDecimal | 单件重量（克）|
| processes | Map<String,Object>[] | 工序列表，元素形如 `{processCode, seqNo, name?}` |
| compositeProcesses | Map<String,Object>[] | 组合工序列表（仅组合产品有）|

#### 确认加产品（P5 一锅端落库）
- **功能**: 报价单选配抽屉 P5 确认时，一次性将配置落库为报价行（SIMPLE=1 行；COMPOSITE=1 父 + N 子），并补建产品整份快照与报价单整份快照（快照为尽力而为，异常降级不影响加产品）。
- **方法**: POST
- **路径**: `/api/cpq/configure-product/quotations/{quotationId}`
- **鉴权**: 需登录，角色同类级；操作人 id 取自 SecurityIdentity（取不到降级为 null）
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| quotationId | UUID | 目标报价单 id |

- **请求体**（ConfigureProductRequest）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| productType | String | 是 | `SIMPLE` / `COMPOSITE` |
| parts | PartRequest[] | 是 | 配件列表：SIMPLE 时 size=1；COMPOSITE 时 size≥2 |
| compositeProcesses | CompositeProcessRequest[] | COMPOSITE 用 | 组合工序列表（仅 COMPOSITE）|
| tempId | String | 否 | 前端生成的 tempId（UUID 字符串）；作父/唯一 line item 的 id，避免二次映射；空则后端自生成 |

  PartRequest 结构：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | 否 | 配件名（如「配件 1」/「产品」）|
| partMode | String | 是 | `existing`（选已有料号）/ `custom`（自定义材质）|
| existingHfPartNo | String | existing 必填 | 已有料号 |
| recipeCode | String | custom 必填 | 材质配方编码 |
| elements | ElementOverride[] | custom 必填 | 元素配比覆盖 |
| processIds | UUID[] | 否 | 工序 id 顺序数组（命中复用时忽略）|
| unitWeightGrams | BigDecimal | 否 | 单件重量（克），仅未命中指纹时填 |
| quotationLineItemId | String | 否 | 前端 tempId（UUID 字符串），写 mat_process 时作行维度隔离同料号不同行的工序 |
| quantity | Integer | 否 | 配件组成用量（仅 COMPOSITE 子件用），写 material_bom_item.composition_qty，正整数，默认/兜底 1 |

  CompositeProcessRequest 结构：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| defCode | String | 是 | 组合工序定义编码（如 `RIVET` / `RESISTANCE_WELD`）|
| participatingPartIndexes | Integer[] | 是 | 参与的配件下标（引用 parts 数组，如 `[0,1]`）|
| params | Map<String,Object> | 是 | 工序参数（如 `{pressure:5.0, height:3.2}`）|

- **响应内容**（直返 ConfigureProductResponse）:

| 字段 | 类型 | 说明 |
|------|------|------|
| lineItems | Map<String,Object>[] | 落库的报价行：SIMPLE=1 行；COMPOSITE=1 父 + N 子（元素含 `id` 等）|
| fingerprintMatched | boolean | 是否至少 1 个料号为复用 |
| reusedHfPartNos | String[] | 被复用的料号列表 |

#### 从基础数据刷新快照
- **功能**: 基础数据更新后，用户主动将报价单各行快照从当前基础数据重新冻结（重跑 snapshot_rows），保留用户编辑层 row_data（UPSERT）；并递归重算核价卡片（存量核价单刷出整棵 BOM 树，仅 COSTING，不碰报价侧，尽力而为）。
- **方法**: POST
- **路径**: `/api/cpq/configure-product/quotations/{quotationId}/refresh-snapshot`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| quotationId | UUID | 目标报价单 id |

- **响应内容**（直返 ConfigureProductResponse，均为占位空值）:

| 字段 | 类型 | 说明 |
|------|------|------|
| lineItems | Map<String,Object>[] | 固定空列表 |
| fingerprintMatched | boolean | 固定 false（默认）|
| reusedHfPartNos | String[] | 固定空列表 |

---

### 11.2 CompositeProcessResource（组合工序定义管理）

类级 `@Path`: `/api/cpq/composite-processes`
类级鉴权: `@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`（写操作另收窄至 SYSTEM_ADMIN）
类级 `@Produces`: `application/json`

#### 列出组合工序定义
- **功能**: 返回所有 ACTIVE 组合工序定义。
- **方法**: GET
- **路径**: `/api/cpq/composite-processes`
- **鉴权**: 需登录，角色同类级
- **响应内容**（直返 `List<CompositeProcessDefDTO>`）: 见下 DTO 字段表

  CompositeProcessDefDTO 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| code | String | 工序编码 |
| name | String | 工序名称 |
| icon | String | 图标标识 |
| description | String | 描述 |
| paramSchema | String | 参数 schema（DB JSONB 原样透传的 JSON 字符串，前端解析为参数数组）|
| sortOrder | int | 排序号 |

#### 获取单个组合工序定义
- **功能**: 按 id 查单条组合工序定义。
- **方法**: GET
- **路径**: `/api/cpq/composite-processes/{id}`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组合工序定义 id |

- **响应内容**（直返 CompositeProcessDefDTO，字段同上）

#### 新建组合工序定义
- **功能**: 创建一条组合工序定义。
- **方法**: POST
- **路径**: `/api/cpq/composite-processes`
- **鉴权**: 仅 SYSTEM_ADMIN
- **请求头**: `Content-Type: application/json`
- **请求体**（CompositeProcessUpsertRequest）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | String | 是 | 工序编码 |
| name | String | 是 | 工序名称 |
| icon | String | 否 | 图标标识 |
| description | String | 否 | 描述 |
| paramSchema | ParamDef[] | 否 | 参数定义数组（Service 序列化为 JSON 存 paramSchema 列）|
| sortOrder | Integer | 否 | 排序号 |
| status | String | 否 | `ACTIVE` / `INACTIVE`（受 chk_composite_process_def_status 约束）|

  ParamDef 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 参数标识（如 `pressure` / `current`）|
| label | String | 参数中文标签（如「铆接压力」）|
| unit | String | 单位（如 `kN`，可为空串）|
| type | String | 类型：`number` / `text` |
| placeholder | String | 输入框占位符 |

- **响应内容**（直返新建后的 CompositeProcessDefDTO）

#### 更新组合工序定义
- **功能**: 按 id 更新组合工序定义。
- **方法**: PUT
- **路径**: `/api/cpq/composite-processes/{id}`
- **鉴权**: 仅 SYSTEM_ADMIN
- **请求头**: `Content-Type: application/json`
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组合工序定义 id |

- **请求体**（CompositeProcessUpsertRequest，字段同「新建」）
- **响应内容**（直返更新后的 CompositeProcessDefDTO）

#### 删除组合工序定义（软删）
- **功能**: 按 id 软删除组合工序定义。
- **方法**: DELETE
- **路径**: `/api/cpq/composite-processes/{id}`
- **鉴权**: 仅 SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 组合工序定义 id |

- **响应内容**: HTTP 204 No Content（无响应体）

---

### 11.3 ConfigureSearchResource（选配抽屉：料号统一搜索 + 材质取数）

类级 `@Path`: `/api/cpq/quotations/configure`
类级鉴权: `@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`
类级 `@Produces`: `application/json`

#### 料号统一搜索（选配 Step1）
- **功能**: 选配抽屉 Step1 搜索「已有配件」，跨客户模糊匹配 V6 物料主表（material_master），按料号/品名/规格/尺寸/材质等多字段 ILIKE 命中；材质经 material_recipe 字典优先展示，未绑定回退 material_type。不再做子件排除过滤（2026-05-31 用户决策彻底移除）。
- **方法**: GET
- **路径**: `/api/cpq/quotations/configure/search-parts`
- **鉴权**: 需登录，角色同类级
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| q | String | 是 | 关键字；空/空白直接返回空列表 |
| size | int | 否 | 返回条数，默认 50，实际取值夹在 [1,200] |

- **响应内容**（直返 `List<Map<String,Object>>`，每项字段如下）:

| 字段 | 类型 | 说明 |
|------|------|------|
| hfPartNo | String | 料号（material_no）|
| partName | String | 品名（material_name）|
| specification | String | 规格 |
| sizeInfo | String | 尺寸（V6 字段 dimension）|
| statusCode | String | 状态码，V6 无停产维度固定 `Y` |
| recipeId | UUID | 材质配方 id（material_recipe_id，可空）|
| recipeCode | String | 材质配方编码（material_recipe.code，可空）|
| recipeSymbol | String | 材质符号，未绑回退 material_type |
| recipeName | String | 材质名称，未绑回退 material_type |
| recipeSpec | String | 材质规格标签，未绑回退 specification |
| recipeType | String | 材质类型，未绑回退 material_type |

#### 已有料号材质取数（选配 Step2 锁定路径）
- **功能**: 用户在 Step1 选定已有料号后，Step2 渲染元素配比表取材质数据；返回字典派（recipeBound=true）或 BOM 派（recipeBound=false）的统一 DTO。
- **方法**: GET
- **路径**: `/api/cpq/quotations/configure/existing-part/{hfPartNo}/material`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| hfPartNo | String | 已有料号 |

- **响应内容**（直返 ExistingPartMaterialDTO）:

| 字段 | 类型 | 说明 |
|------|------|------|
| hfPartNo | String | 料号 |
| recipeBound | boolean | true=字典派（material_recipe）；false=BOM 派（mat_bom 派生，只读）|
| recipeCode | String | 材质编码（BOM 派为 null）|
| recipeSymbol | String | 材质符号（BOM 派为 null）|
| recipeName | String | 材质名称（BOM 派为 null）|
| recipeSpec | String | 材质规格（BOM 派为 null）|
| recipeType | String | `locked` / `editable` / `partial`，BOM 派固定 `locked` |
| elements | Element[] | 元素配比列表（见下）|

  Element 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| elementCode | String | 元素编码 |
| elementName | String | 元素名称 |
| pct | BigDecimal | 占比（%）|
| minPct | BigDecimal | 最小占比（字典派 editable/partial 有值；locked / BOM 派为 null）|
| maxPct | BigDecimal | 最大占比（同上）|
| isLocked | boolean | 是否只读锁定 |

---

### 11.4 MaterialRecipeResource（材质配方管理 + 料号绑定 + 智能推断）

类级 `@Path`: `/api/cpq/material-recipes`
类级鉴权: `@RoleAllowed({"SALES_REP","SALES_MANAGER","PRICING_MANAGER","SYSTEM_ADMIN"})`（写/绑定/推断另收窄至 SYSTEM_ADMIN）
类级 `@Produces`: `application/json`

#### 列出材质配方
- **功能**: 返回所有 ACTIVE 材质配方；`withCount=true` 时每条附 boundPartsCount（该材质下绑定的料号数）。
- **方法**: GET
- **路径**: `/api/cpq/material-recipes`
- **鉴权**: 需登录，角色同类级
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| withCount | boolean | 否 | 默认 false；true 时填充 boundPartsCount |

- **响应内容**（直返 `List<MaterialRecipeDTO>`）:

  MaterialRecipeDTO 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | UUID | 主键 |
| code | String | 材质编码 |
| symbol | String | 材质符号 |
| name | String | 材质名称 |
| specLabel | String | 规格标签 |
| recipeType | String | `locked` / `editable` / `partial` |
| status | String | `ACTIVE` / `INACTIVE` |
| sortOrder | Integer | 排序号 |
| elements | MaterialRecipeElementDTO[] | 元素列表（仅 detail 端点填充，list 端点为 null）|
| boundPartsCount | Long | 绑定料号数（仅 list 带 withCount=true 填充，否则 null）|

#### 获取材质配方详情
- **功能**: 按 id 查材质配方详情（含元素列表）。
- **方法**: GET
- **路径**: `/api/cpq/material-recipes/{id}`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 材质配方 id |

- **响应内容**（直返 MaterialRecipeDTO，elements 已填充）:

  MaterialRecipeElementDTO 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| elementCode | String | 元素编码 |
| elementName | String | 元素名称 |
| defaultPct | BigDecimal | 默认占比 |
| minPct | BigDecimal | 最小占比 |
| maxPct | BigDecimal | 最大占比 |
| isLocked | boolean | 是否锁定 |
| sortOrder | int | 排序号 |

#### 材质下料号分页列表
- **功能**: 分页查询某材质下绑定的料号（可关键字过滤）。
- **方法**: GET
- **路径**: `/api/cpq/material-recipes/{id}/parts`
- **鉴权**: 需登录，角色同类级
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 材质配方 id |

- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| keyword | String | 否 | 料号/品名等关键字过滤 |
| page | int | 否 | 页码，默认 0 |
| size | int | 否 | 每页条数，默认 20 |

- **响应内容**（直返 `PageResult<MaterialRecipePartDTO>`，分页壳含 items/total 等）:

  MaterialRecipePartDTO 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| partNo | String | 料号 |
| partName | String | 品名 |
| specification | String | 规格 |
| sizeInfo | String | 尺寸 |
| productType | String | `SIMPLE` / `COMPOSITE` |
| statusCode | String | `Y` / `N` |
| unitWeight | BigDecimal | 单件重量 |
| materialRecipeId | UUID | 当前绑定材质 id（可空）|
| materialRecipeCode | String | 绑定材质编码 |
| materialRecipeSymbol | String | 绑定材质符号 |
| createdAt | OffsetDateTime | 创建时间 |
| updatedAt | OffsetDateTime | 更新时间 |

#### 批量绑定料号到材质
- **功能**: 将 partNos 列出的料号绑定到本材质（UPDATE mat_part.material_recipe_id=id，允许从其他材质转移）。
- **方法**: POST
- **路径**: `/api/cpq/material-recipes/{id}/bind-parts`
- **鉴权**: 仅 SYSTEM_ADMIN
- **请求头**: `Content-Type: application/json`
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 目标材质配方 id |

- **请求体**（BindPartsRequest）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|
| partNos | String[] | 是 | 待绑定的料号列表 |

- **响应内容**（直返 `Map<String,Integer>`）:

| 字段 | 类型 | 说明 |
|------|------|------|
| updated | Integer | 实际更新的料号条数 |

#### 批量解绑料号
- **功能**: 将 partNos 列出的料号解绑（UPDATE mat_part.material_recipe_id=NULL）；id 仅占位统一 URL 风格，实际只看 partNos，不校验是否绑过该材质。
- **方法**: POST
- **路径**: `/api/cpq/material-recipes/{id}/unbind-parts`
- **鉴权**: 仅 SYSTEM_ADMIN
- **请求头**: `Content-Type: application/json`
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 占位（不校验）|

- **请求体**（BindPartsRequest，字段同上）
- **响应内容**（直返 `Map<String,Integer>`）:

| 字段 | 类型 | 说明 |
|------|------|------|
| updated | Integer | 实际解绑的料号条数 |

#### 搜索可绑定料号
- **功能**: 供「材质管理 → +绑定料号」子抽屉搜索 mat_part（可选仅未绑定料号）。
- **方法**: GET
- **路径**: `/api/cpq/material-recipes/search-parts`
- **鉴权**: 需登录，角色同类级
- **查询参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| q | String | 否 | 关键字 |
| onlyUnbound | boolean | 否 | 默认 false；true 仅返回未绑材质的料号 |
| size | int | 否 | 返回条数，默认 50 |

- **响应内容**（直返 `List<MaterialRecipePartDTO>`，字段见 11.4「材质下料号分页列表」）

#### 智能推断绑定建议
- **功能**: 扫描所有未绑材质的料号，基于 mat_bom.element_name 命中给出候选材质绑定建议（按置信度 EXACT_CODE > EXACT_SYMBOL > PREFIX_MATCH 排序）。
- **方法**: GET
- **路径**: `/api/cpq/material-recipes/suggest-bindings`
- **鉴权**: 仅 SYSTEM_ADMIN
- **响应内容**（直返 `List<BindingSuggestionDTO>`）:

  BindingSuggestionDTO 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| partNo | String | 料号 |
| partName | String | 品名 |
| specification | String | 规格 |
| sourceHints | String[] | 来源依据（mat_bom.element_name 命中字符串，去重）|
| candidates | Candidate[] | 候选材质列表（按 confidence 排序）|

  Candidate 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| recipeId | UUID | 候选材质 id |
| recipeCode | String | 候选材质编码 |
| recipeSymbol | String | 候选材质符号 |
| recipeName | String | 候选材质名称 |
| confidence | String | `EXACT_CODE` / `EXACT_SYMBOL` / `PREFIX_MATCH` |
| matchedOn | String | 命中依据（对应 mat_bom.element_name 原值）|

#### 批量确认绑定决策
- **功能**: 批量执行人工确认的 (partNo → recipeId) 绑定决策。
- **方法**: POST
- **路径**: `/api/cpq/material-recipes/confirm-bindings`
- **鉴权**: 仅 SYSTEM_ADMIN
- **请求头**: `Content-Type: application/json`
- **请求体**（ConfirmBindingsRequest）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| items | Item[] | 是 | 绑定决策列表 |

  Item 结构：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|
| partNo | String | 是 | 料号 |
| recipeId | UUID | 是 | 目标材质 id |

- **响应内容**（直返 `Map<String,Integer>`）:

| 字段 | 类型 | 说明 |
|------|------|------|
| updated | Integer | 实际绑定的料号条数 |

#### 新建材质配方
- **功能**: 创建一条材质配方（含元素列表）。
- **方法**: POST
- **路径**: `/api/cpq/material-recipes`
- **鉴权**: 仅 SYSTEM_ADMIN
- **请求头**: `Content-Type: application/json`
- **请求体**（MaterialRecipeUpsertRequest）:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| code | String | 是 | 材质编码 |
| symbol | String | 否 | 材质符号 |
| name | String | 否 | 材质名称 |
| specLabel | String | 否 | 规格标签 |
| recipeType | String | 否 | `locked` / `editable` / `partial` |
| sortOrder | Integer | 否 | 排序号 |
| status | String | 否 | `ACTIVE` / `INACTIVE` |
| elements | ElementUpsert[] | 否 | 元素列表 |

  ElementUpsert 结构：

| 字段 | 类型 | 说明 |
|------|------|------|
| elementCode | String | 元素编码 |
| elementName | String | 元素名称 |
| defaultPct | BigDecimal | 默认占比 |
| minPct | BigDecimal | 最小占比 |
| maxPct | BigDecimal | 最大占比 |
| isLocked | Boolean | 是否锁定 |
| sortOrder | Integer | 排序号 |

- **响应内容**（直返新建后的 MaterialRecipeDTO）

#### 更新材质配方
- **功能**: 按 id 更新材质配方（含元素列表）。
- **方法**: PUT
- **路径**: `/api/cpq/material-recipes/{id}`
- **鉴权**: 仅 SYSTEM_ADMIN
- **请求头**: `Content-Type: application/json`
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 材质配方 id |

- **请求体**（MaterialRecipeUpsertRequest，字段同「新建」）
- **响应内容**（直返更新后的 MaterialRecipeDTO）

#### 删除材质配方（软删）
- **功能**: 按 id 软删除材质配方。
- **方法**: DELETE
- **路径**: `/api/cpq/material-recipes/{id}`
- **鉴权**: 仅 SYSTEM_ADMIN
- **路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| id | UUID | 材质配方 id |

- **响应内容**: HTTP 204 No Content（无响应体）
## 十二、产品、定价与其他

> 全局基准：基址 `http://localhost:8081`；鉴权=会话 Cookie（`@RoleAllowed` 端点需登录且携带对应角色，请求头带 `Cookie`；无注解不校验）；统一响应体 `ApiResponse<T> = { code, message, data }`，个别端点直返实体 / `Response`（Excel 二进制、204 空响应等），已在各端点据实标注。

---

### 12.1 ProductResource（产品主数据管理）

类级 `@Path`：`/api/cpq/products`
类级鉴权：`@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})`

#### 产品列表（分页查询）
- **功能**: 分页查询产品，支持按类目、类目 ID、状态、关键字过滤
- **方法**: GET
- **路径**: `/api/cpq/products`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

  | 参数 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | page | int | 否 | 页码，0 基，默认 0 |
  | size | int | 否 | 每页条数，默认 20 |
  | category | String | 否 | 类目名称过滤 |
  | categoryId | UUID | 否 | 类目 ID 过滤 |
  | status | String | 否 | 状态过滤 |
  | keyword | String | 否 | 关键字（名称/料号等）模糊过滤 |

- **响应内容**: `ApiResponse<PageResult<ProductDTO>>`，`PageResult` 含分页元信息，`data` 内 `content` 为 `ProductDTO` 列表：

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | id | UUID | 产品 ID |
  | name | String | 产品名称 |
  | partNo | String | 料号 |
  | category | String | 类目名称 |
  | categoryId | UUID | 类目 ID |
  | categoryName | String | 类目显示名（按 categoryId 查出） |
  | specification | String | 规格 |
  | drawingNo | String | 图号 |
  | dimension | String | 尺寸 |
  | material | String | 材质 |
  | status | String | 状态 |
  | tags | String[] | 标签列表 |
  | externalId | String | 外部系统 ID |
  | lastSyncedAt | OffsetDateTime | 最后同步时间 |
  | createdAt | OffsetDateTime | 创建时间 |
  | updatedAt | OffsetDateTime | 更新时间 |

#### 新建产品
- **功能**: 创建产品
- **方法**: POST
- **路径**: `/api/cpq/products`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **请求体**: `CreateProductRequest`

  | 字段 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | name | String | 是 | 产品名称（@NotBlank） |
  | partNo | String | 是 | 料号（@NotBlank） |
  | category | String | 否 | 类目名称 |
  | categoryId | UUID | 否 | 类目 ID |
  | specification | String | 否 | 规格 |
  | drawingNo | String | 否 | 图号 |
  | dimension | String | 否 | 尺寸 |
  | material | String | 否 | 材质 |
  | status | String | 否 | 状态 |
  | tags | String[] | 否 | 标签列表 |

- **响应内容**: `ApiResponse<ProductDTO>`（字段同上）

#### 更新产品
- **功能**: 按 ID 更新产品
- **方法**: PUT
- **路径**: `/api/cpq/products/{id}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

  | 参数 | 类型 | 说明 |
  |------|------|------|
  | id | UUID | 产品 ID |

- **请求体**: `CreateProductRequest`（字段同「新建产品」，未标注 @Valid，服务端逻辑更新）
- **响应内容**: `ApiResponse<ProductDTO>`

#### 删除产品
- **功能**: 按 ID 删除产品
- **方法**: DELETE
- **路径**: `/api/cpq/products/{id}`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

  | 参数 | 类型 | 说明 |
  |------|------|------|
  | id | UUID | 产品 ID |

- **响应内容**: `ApiResponse<Void>`

#### 导入产品（Excel）
- **功能**: 上传 Excel 批量导入产品
- **方法**: POST
- **路径**: `/api/cpq/products/import`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **请求头**: `Content-Type: multipart/form-data`
- **请求体**: multipart 表单，字段 `file`（Excel 文件，FileUpload）
- **响应内容**: `ApiResponse<ImportResult>`

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | added | int | 新增条数 |
  | skipped | int | 跳过条数 |
  | failed | int | 失败条数 |
  | errors | String[] | 错误明细列表 |

---

### 12.2 ProcessResource（工序主数据管理）

类级 `@Path`：`/api/cpq/processes`
类级鉴权：`@RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})`

#### 工序列表
- **功能**: 列出全部工序，或按类目过滤
- **方法**: GET
- **路径**: `/api/cpq/processes`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **查询参数**:

  | 参数 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | category | String | 否 | 工序类目（如 MACHINING）；为空或空白则返回全部 |

- **响应内容**: `ApiResponse<List<ProcessDTO>>`

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | id | UUID | 工序 ID |
  | code | String | 工序编码 |
  | name | String | 工序名称 |
  | description | String | 描述 |
  | category | String | 类目枚举（SURFACE_TREATMENT / MACHINING / HEAT_TREATMENT / ASSEMBLY / INSPECTION / PACKAGING） |
  | isRequired | boolean | 是否必需 |
  | sortOrder | int | 排序号 |
  | status | String | 状态（ACTIVE / DISABLED） |
  | createdAt | OffsetDateTime | 创建时间 |

#### 工序详情
- **功能**: 按 ID 查询单个工序
- **方法**: GET
- **路径**: `/api/cpq/processes/{id}`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

  | 参数 | 类型 | 说明 |
  |------|------|------|
  | id | UUID | 工序 ID |

- **响应内容**: `ApiResponse<ProcessDTO>`（字段同上）

#### 新建工序
- **功能**: 创建工序
- **方法**: POST
- **路径**: `/api/cpq/processes`
- **鉴权**: SYSTEM_ADMIN
- **请求体**: `ProcessUpsertRequest`

  | 字段 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | code | String | 是 | 工序编码，最多 50 字符，全表唯一 |
  | name | String | 是 | 显示名称 |
  | category | String | 是 | 类目枚举（须匹配 DB CHECK：SURFACE_TREATMENT / MACHINING / HEAT_TREATMENT / ASSEMBLY / INSPECTION / PACKAGING） |
  | description | String | 否 | 描述 |
  | isRequired | Boolean | 否 | 是否默认必需，可空，默认 false |
  | sortOrder | Integer | 否 | 类目内排序，可空，默认 0 |
  | status | String | 否 | 状态枚举 ACTIVE / DISABLED，创建时可空（默认 ACTIVE） |

- **响应内容**: HTTP 201 Created，`ApiResponse<ProcessDTO>`

#### 更新工序
- **功能**: 按 ID 更新工序
- **方法**: PUT
- **路径**: `/api/cpq/processes/{id}`
- **鉴权**: SYSTEM_ADMIN
- **路径参数**:

  | 参数 | 类型 | 说明 |
  |------|------|------|
  | id | UUID | 工序 ID |

- **请求体**: `ProcessUpsertRequest`（字段同上，status 更新时显式传以保留当前值）
- **响应内容**: `ApiResponse<ProcessDTO>`

#### 删除工序（软删除）
- **功能**: 软删除，设置 status=DISABLED
- **方法**: DELETE
- **路径**: `/api/cpq/processes/{id}`
- **鉴权**: SYSTEM_ADMIN
- **路径参数**:

  | 参数 | 类型 | 说明 |
  |------|------|------|
  | id | UUID | 工序 ID |

- **响应内容**: HTTP 204 No Content（无响应体）

---

### 12.3 ProductProcessResource（产品-工序绑定管理）

类级 `@Path`：`/api/cpq/products/{productId}/processes`
类级鉴权：`@RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})`

> 说明：本资源是产品级工序绑定端点，与 ProcessResource（工序主数据）分离，避免 JAX-RS 路径拼接冲突。

#### 查询产品已绑工序
- **功能**: 列出某产品绑定的工序
- **方法**: GET
- **路径**: `/api/cpq/products/{productId}/processes`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

  | 参数 | 类型 | 说明 |
  |------|------|------|
  | productId | UUID | 产品 ID |

- **响应内容**: `ApiResponse<List<ProductProcessDTO>>`

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | id | UUID | 绑定关系 ID |
  | processId | UUID | 工序 ID |
  | code | String | 工序编码 |
  | name | String | 工序名称 |
  | description | String | 描述 |
  | category | String | 类目 |
  | isRequired | boolean | 该产品下是否必需 |
  | sortOrder | int | 排序号 |

#### 绑定工序（PUT）
- **功能**: 设置/覆盖产品的工序绑定列表
- **方法**: PUT
- **路径**: `/api/cpq/products/{productId}/processes`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

  | 参数 | 类型 | 说明 |
  |------|------|------|
  | productId | UUID | 产品 ID |

- **请求体**: `Map<String, Object>`，两种形态二选一：
  - `processes`：对象数组，每项含 `processId`（String/UUID）、`sortOrder`（int）、`isRequired`（boolean）
  - `processIds`：ID 字符串数组（兼容旧格式，自动转换为 sortOrder=0 / isRequired=false）
  - 两者都缺省时按空列表处理（清空绑定）
- **响应内容**: `ApiResponse<Void>`

#### 绑定工序（POST 兼容）
- **功能**: 与 PUT 等价的兼容入口
- **方法**: POST
- **路径**: `/api/cpq/products/{productId}/processes`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**: productId（UUID，产品 ID）
- **请求体**: 同「绑定工序（PUT）」
- **响应内容**: `ApiResponse<Void>`

#### 解绑全部工序
- **功能**: 清空某产品的所有工序绑定
- **方法**: DELETE
- **路径**: `/api/cpq/products/{productId}/processes`
- **鉴权**: SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**: productId（UUID，产品 ID）
- **响应内容**: `ApiResponse<Void>`

---

### 12.4 PricingStrategyResource（定价策略与规则管理）

类级 `@Path`：`/api/cpq/pricing-strategies`
类级鉴权：`@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})`

#### 定价策略列表（分页）
- **功能**: 分页查询定价策略，可按客户过滤
- **方法**: GET
- **路径**: `/api/cpq/pricing-strategies`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

  | 参数 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | page | int | 否 | 页码，默认 0 |
  | size | int | 否 | 每页条数，默认 20 |
  | customerId | UUID | 否 | 客户 ID 过滤 |

- **响应内容**: `ApiResponse<PageResult<PricingStrategyDTO>>`

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | id | UUID | 策略 ID |
  | customerId | UUID | 客户 ID |
  | name | String | 策略名称 |
  | type | String | 策略类型 |
  | baseDiscount | BigDecimal | 基础折扣 |
  | minOrderAmount | BigDecimal | 最小订单金额 |
  | effectiveDate | LocalDate | 生效日期 |
  | expirationDate | LocalDate | 失效日期 |
  | priority | Integer | 优先级 |
  | status | String | 状态 |
  | createdAt | OffsetDateTime | 创建时间 |
  | updatedAt | OffsetDateTime | 更新时间 |
  | rules | PricingRuleDTO[] | 关联规则列表（见下） |

  `PricingRuleDTO` 字段：

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | id | UUID | 规则 ID |
  | strategyId | UUID | 所属策略 ID |
  | ruleType | String | 规则类型 |
  | thresholdAmount | BigDecimal | 门槛金额 |
  | discountRate | BigDecimal | 折扣率 |
  | sortOrder | Integer | 排序号 |
  | createdAt | OffsetDateTime | 创建时间 |

#### 定价策略详情
- **功能**: 按 ID 查询定价策略
- **方法**: GET
- **路径**: `/api/cpq/pricing-strategies/{id}`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: id（UUID，策略 ID）
- **响应内容**: `ApiResponse<PricingStrategyDTO>`

#### 新建定价策略
- **功能**: 创建定价策略（可含规则）
- **方法**: POST
- **路径**: `/api/cpq/pricing-strategies`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `CreatePricingStrategyRequest`

  | 字段 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | customerId | UUID | 是 | 客户 ID（@NotNull） |
  | name | String | 是 | 策略名称（@NotBlank） |
  | type | String | 否 | 策略类型 |
  | baseDiscount | BigDecimal | 否 | 基础折扣 |
  | minOrderAmount | BigDecimal | 否 | 最小订单金额 |
  | effectiveDate | LocalDate | 否 | 生效日期 |
  | expirationDate | LocalDate | 否 | 失效日期 |
  | priority | Integer | 否 | 优先级 |
  | rules | RuleRequest[] | 否 | 规则数组 |

  `RuleRequest` 字段：`ruleType`(String)、`thresholdAmount`(BigDecimal)、`discountRate`(BigDecimal)、`sortOrder`(Integer)

- **响应内容**: `ApiResponse<PricingStrategyDTO>`

#### 更新定价策略
- **功能**: 按 ID 更新定价策略
- **方法**: PUT
- **路径**: `/api/cpq/pricing-strategies/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: id（UUID，策略 ID）
- **请求体**: `CreatePricingStrategyRequest`（字段同上，未标注 @Valid）
- **响应内容**: `ApiResponse<PricingStrategyDTO>`

#### 删除定价策略
- **功能**: 按 ID 删除定价策略
- **方法**: DELETE
- **路径**: `/api/cpq/pricing-strategies/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: id（UUID，策略 ID）
- **响应内容**: `ApiResponse<Void>`

#### 更新策略状态
- **功能**: 局部更新定价策略状态
- **方法**: PATCH
- **路径**: `/api/cpq/pricing-strategies/{id}`
- **鉴权**: PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**: id（UUID，策略 ID）
- **请求体**: `Map<String, String>`

  | 字段 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | status | String | 是 | 新状态；缺省或空白抛 400 |

- **响应内容**: `ApiResponse<PricingStrategyDTO>`

#### 导出定价策略（Excel）
- **功能**: 导出定价策略为 Excel
- **方法**: GET
- **路径**: `/api/cpq/pricing-strategies/export`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

  | 参数 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | customerId | UUID | 否 | 客户 ID 过滤 |

- **响应内容**: 直返 `Response`，二进制 Excel（`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，`Content-Disposition: attachment; filename=pricing-strategies.xlsx`）

#### 查询策略规则列表
- **功能**: 列出某策略下的规则（按 sortOrder 升序）
- **方法**: GET
- **路径**: `/api/cpq/pricing-strategies/{strategyId}/rules`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **路径参数**:

  | 参数 | 类型 | 说明 |
  |------|------|------|
  | strategyId | UUID | 策略 ID（无效则先校验触发 404） |

- **响应内容**: `ApiResponse<List<PricingRuleDTO>>`（字段见 12.4 定价策略列表内 PricingRuleDTO 表）

---

### 12.5 ElementPriceResource（元素参考价格中心）

类级 `@Path`：`/api/cpq/element-prices`
类级鉴权：`@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})`

#### 查询参考价格
- **功能**: 返回指定元素在 priceDate 当日或之前最近一条 MANUAL 参考价
- **方法**: GET
- **路径**: `/api/cpq/element-prices/reference`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

  | 参数 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | elementName | String | 否 | 元素名称（如 Ag） |
  | priceDate | String | 否 | 价格日期 yyyy-MM-dd，缺省取今天；格式错误抛 400 |

- **响应内容**: `ApiResponse<ElementReferenceDTO>`（无价格时 data=null，HTTP 仍 200）

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | elementName | String | 元素名称 |
  | price | BigDecimal | 价格 |
  | currency | String | 币种 |
  | unit | String | 单位 |
  | priceDate | LocalDate | 价格日期 |
  | enteredByName | String | 录入人姓名（user.full_name） |
  | note | String | 备注 |

#### 查询价格历史
- **功能**: 分页列出元素 MANUAL 价格历史（按日期降序）
- **方法**: GET
- **路径**: `/api/cpq/element-prices/history`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **查询参数**:

  | 参数 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | elementName | String | 否 | 元素名称 |
  | from | String | 否 | 起始日期 yyyy-MM-dd，缺省 today-30d |
  | to | String | 否 | 截止日期 yyyy-MM-dd，缺省 today |
  | page | int | 否 | 页码，默认 0 |
  | size | int | 否 | 每页条数，默认 20 |

- **响应内容**: `ApiResponse<List<ElementReferenceDTO>>`（字段同上）

#### 录入/更新参考价格
- **功能**: 幂等 upsert 当日 MANUAL 参考价（同元素同日覆盖）
- **方法**: POST
- **路径**: `/api/cpq/element-prices/manual`
- **鉴权**: SYSTEM_ADMIN
- **请求头**: 依赖会话 Cookie（服务端从会话取当前用户 ID）
- **请求体**: `UpsertManualPriceRequest`

  | 字段 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | elementName | String | 是 | 元素名称（@NotBlank） |
  | price | BigDecimal | 是 | 价格（@NotNull，必须 > 0，最小 0.000001） |
  | currency | String | 否 | 币种，默认 CNY |
  | unit | String | 否 | 单位 |
  | note | String | 否 | 备注 |

- **响应内容**: `ApiResponse<ElementReferenceDTO>`（字段同上）

#### 可用元素列表
- **功能**: 返回 mat_bom 中 bom_type=ELEMENT 的去重元素名（供报价填价下拉）
- **方法**: GET
- **路径**: `/api/cpq/element-prices/available-elements`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **响应内容**: `ApiResponse<List<String>>`（元素名称字符串列表）

---

### 12.6 ApprovalRuleResource（审批规则管理）

类级 `@Path`：`/api/cpq/approval-rules`
类级鉴权：`@RoleAllowed({"SYSTEM_ADMIN"})`（整类仅系统管理员）

#### 审批规则列表
- **功能**: 列出全部审批规则
- **方法**: GET
- **路径**: `/api/cpq/approval-rules`
- **鉴权**: SYSTEM_ADMIN
- **响应内容**: `ApiResponse<List<ApprovalRuleDTO>>`

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | id | UUID | 规则 ID |
  | ruleType | String | 规则类型 |
  | approverId | UUID | 审批人 ID |
  | matchField | String | 匹配字段 |
  | matchValueId | UUID | 匹配值 ID |
  | priority | Integer | 优先级 |
  | createdAt | OffsetDateTime | 创建时间 |

#### 新建审批规则
- **功能**: 创建审批规则
- **方法**: POST
- **路径**: `/api/cpq/approval-rules`
- **鉴权**: SYSTEM_ADMIN
- **请求体**: `CreateApprovalRuleRequest`

  | 字段 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | ruleType | String | 是 | 规则类型（@NotBlank） |
  | approverId | UUID | 是 | 审批人 ID（@NotNull） |
  | matchField | String | 否 | 匹配字段 |
  | matchValueId | UUID | 否 | 匹配值 ID |
  | priority | Integer | 否 | 优先级，默认 100 |

- **响应内容**: `ApiResponse<ApprovalRuleDTO>`

#### 更新审批规则
- **功能**: 按 ID 更新审批规则
- **方法**: PUT
- **路径**: `/api/cpq/approval-rules/{id}`
- **鉴权**: SYSTEM_ADMIN
- **路径参数**: id（UUID，规则 ID）
- **请求体**: `CreateApprovalRuleRequest`（@Valid，字段同上）
- **响应内容**: `ApiResponse<ApprovalRuleDTO>`

#### 删除审批规则
- **功能**: 按 ID 删除审批规则
- **方法**: DELETE
- **路径**: `/api/cpq/approval-rules/{id}`
- **鉴权**: SYSTEM_ADMIN
- **路径参数**: id（UUID，规则 ID）
- **响应内容**: `ApiResponse<Void>`

---

### 12.7 FormulaEvaluateResource（公式求值）

类级 `@Path`：`/api/cpq/formulas`
类级鉴权：`@RoleAllowed({"SALES_REP", "SALES_MANAGER", "PRICING_MANAGER", "SYSTEM_ADMIN"})`

> 用途：组件管理配置校验/试算公式、报价单运行时含 BNF 路径公式求值、Excel 视图渲染。含进程级缓存（key=expression:customerId:partNo:templateId，仅 bindings 与 driverRow 均空时命中，TTL 30s）。

#### 单条公式求值
- **功能**: 对单条表达式求值（支持 `{表[谓词].字段}` BNF 路径 + `[字段名]` + 运算/函数）
- **方法**: POST
- **路径**: `/api/cpq/formulas/evaluate`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `EvaluateRequest`

  | 字段 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | expression | String | 是 | 公式表达式；空/空白返回 PARSE_ERROR |
  | customerId | UUID | 否 | 客户 ID（自动注入 customer_id 谓词） |
  | partNo | String | 否 | 料号（自动注入 hf_part_no / part_no 谓词） |
  | bindings | Map<String,Object> | 否 | 行级变量（对应 `[字段名]` 引用） |
  | driverRow | Map<String,Object> | 否 | 行驱动行 K-V，作隐式 AND 谓词注入字段路径首段 |
  | templateId | UUID | 否 | 产品卡片模板 ID；非空时 `$view.col` 查 template_sql_view（V249 起） |
  | costingTemplateId | UUID | 否 | 向后兼容别名（已废弃）；templateId 为空时 fallback 取此值 |
  | quotationId | UUID | 否 | 报价单 ID（配合 templateId，用于状态冻结判断） |
  | quotationStatus | String | 否 | 报价单状态（DRAFT / SUBMITTED / APPROVED / PUBLISHED） |

- **响应内容**: `ApiResponse<EvaluateResponse>`

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | success | boolean | 是否成功 |
  | result | Object | 求值结果（Number/String/Boolean/null），success=true 时有效 |
  | error | String | 错误信息，success=false 时有效 |
  | errorType | String | 错误类型 PARSE_ERROR / EVAL_ERROR / CONTEXT_MISSING |

#### 批量公式求值
- **功能**: 批量求值，上限 5000 条，复用单条缓存；逐条独立 set/restore 上下文
- **方法**: POST
- **路径**: `/api/cpq/formulas/batch-evaluate`
- **鉴权**: SALES_REP / SALES_MANAGER / PRICING_MANAGER / SYSTEM_ADMIN
- **请求体**: `BatchEvaluateRequest`

  | 字段 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | tasks | EvaluateRequest[] | 否 | 待求值任务列表（每项同上单条请求体），上限 5000，超出抛 400；null 时返回空结果 |

- **响应内容**: `ApiResponse<BatchEvaluateResponse>`，`results` 为 `Result` 数组：

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | key | String | 任务 key，格式 expression:customerId:partNo:templateId（null 用 "_" 占位） |
  | status | String | "OK" 或 "ERROR" |
  | data | EvaluateResponse | status=OK 时的求值结果对象（字段同上） |
  | error | String | status=ERROR 时的错误信息 |

---

### 12.8 VersioningQueryResource（版本历史查询）

类级 `@Path`：`/api/cpq/versioning`
类级鉴权：`@RoleAllowed({"SALES_REP", "SALES_MANAGER", "SYSTEM_ADMIN"})`
> 全部为只读 GET 端点。tableName 取值：mat_process / mat_fee / plating_fee。

#### 版本历史列表（分页）
- **功能**: 按业务键过滤，分页返回全部版本历史
- **方法**: GET
- **路径**: `/api/cpq/versioning/history`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **查询参数**:

  | 参数 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | tableName | String | 否 | 表名 mat_process / mat_fee / plating_fee |
  | customerId | String | 是 | 客户 UUID（必填，空/非法抛 400） |
  | hfPartNo | String | 否 | HF 料号过滤 |
  | page | int | 否 | 页码，默认 0（负数抛 400） |
  | size | int | 否 | 每页条数，默认 50，范围 [1,200]（越界抛 400） |

- **响应内容**: `ApiResponse<PageResult<VersionHistoryItemDTO>>`

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | tableName | String | 表名 |
  | recordId | UUID | 记录主键 |
  | version | int | 版本号 |
  | isCurrent | boolean | 是否当前版本 |
  | businessKey | Map<String,Object> | 业务键字段（不含 customer_id / hf_part_no） |
  | customerId | UUID | 客户 ID |
  | hfPartNo | String | HF 料号 |
  | updatedAt | String | 更新时间 |
  | updatedBy | UUID | 更新人 ID |
  | updatedByName | String | 更新人姓名 |

#### 单行版本详情
- **功能**: 返回单条版本行的全字段明细
- **方法**: GET
- **路径**: `/api/cpq/versioning/row/{tableName}/{recordId}`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **路径参数**:

  | 参数 | 类型 | 说明 |
  |------|------|------|
  | tableName | String | 表名 mat_process / mat_fee / plating_fee |
  | recordId | String | 行 UUID 主键（空/非法抛 400） |

- **响应内容**: `ApiResponse<Map<String, Object>>`（该行全部列的 K-V）

#### 版本对比
- **功能**: 两个版本行的字段级对比
- **方法**: GET
- **路径**: `/api/cpq/versioning/compare`
- **鉴权**: SALES_REP / SALES_MANAGER / SYSTEM_ADMIN
- **查询参数**:

  | 参数 | 类型 | 必填 | 说明 |
  |------|------|------|------|
  | tableName | String | 否 | 表名 mat_process / mat_fee / plating_fee |
  | recordIdA | String | 是 | 行 A（较旧版本）UUID（空/非法抛 400） |
  | recordIdB | String | 是 | 行 B（较新版本）UUID（空/非法抛 400） |

- **响应内容**: `ApiResponse<VersionCompareDTO>`

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | versionA | int | 版本 A 号 |
  | versionB | int | 版本 B 号 |
  | fieldDiffs | FieldDiff[] | 逐字段差异列表 |

  `FieldDiff` 字段：

  | 字段 | 类型 | 说明 |
  |------|------|------|
  | fieldName | String | 字段名 |
  | valueA | String | A 值 |
  | valueB | String | B 值 |
  | sameValue | boolean | 两值是否相同 |
