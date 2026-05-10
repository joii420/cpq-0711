# CPQ 系统 PRD 合规修复计划

> **基于**: docs/TEST.md 测试报告 (2026-04-15)
> **当前合规率**: 64.6% (62 PASS / 22 PARTIAL / 12 FAIL)
> **目标合规率**: 95%+

---

## 修复分批策略

### 第一批：P0 安全漏洞（必须立即修复）

| 编号 | 问题 | 修复内容 | 预估工时 |
|------|------|---------|---------|
| **P0-1** | 全局 RBAC 未生效 | 按 PRD 1.4 权限矩阵为所有 Resource 添加 `@RoleAllowed` 注解。具体映射：客户管理(SALES_REP,SALES_MANAGER,PRICING_MANAGER,SYSTEM_ADMIN)、产品管理(SALES_MANAGER,SYSTEM_ADMIN查写/其他只读)、组件管理(SALES_MANAGER,SYSTEM_ADMIN)、模板(SALES_MANAGER,SYSTEM_ADMIN)、定价策略(PRICING_MANAGER,SYSTEM_ADMIN)、报价生成器(全角色按 salesRepId 过滤)、数据源管理(SYSTEM_ADMIN)、系统管理(SYSTEM_ADMIN) | 1h |
| **P0-2** | SQL 注入漏洞 | `UserService.list()`、`CustomerService.list()`、`ProductService.list()`、`ComponentService.list()` 中所有字符串拼接改为 Panache 参数化查询 `WHERE role = :role` + `params.put("role", role)` | 1h |
| **P0-3** | API 认证信息明文存储 | 实现 `EncryptionService`（AES-256），DataSource 保存时加密 api_headers 敏感值，读取时解密，API 响应脱敏为 `****` | 1.5h |

### 第二批：P1 核心业务缺失

| 编号 | 问题 | 修复内容 | 预估工时 |
|------|------|---------|---------|
| **P1-1** | 审批待办列表缺失 | QuotationList 前端新增"待我审批"标签页，后端 GET /quotations 增加 `assignedApproverId` 查询参数，前端按当前用户角色+ID 过滤 | 1h |
| **P1-2** | 催办通知缺邮件 | NotificationService 注入 Mailer，`create()` 方法异步发送邮件（try-catch 不阻塞站内通知写入）| 0.5h |
| **P1-3** | 状态机不完整 | ① 审批驳回确认状态应为 DRAFT（PRD 明确说"退回给原销售代表修改"→DRAFT 是正确的，TEST.md 误判）② accept/rejectByCustomer 增加 `salesRepId == currentUserId` 校验 ③ EXPIRED 状态前端显示处理 | 1h |
| **P1-4** | 累计金额未更新 | 检查 QuotationService.accept()，确认原子 SQL `UPDATE customer SET accumulated_amount = accumulated_amount + :delta` 存在且在同一事务中。（上轮已修复，需验证） | 0.5h |
| **P1-5** | SQL 查询未用只读用户 | application.properties 配置 `quarkus.datasource.datasource-readonly.*`，SqlExecutionService 注入 `@Named("datasource-readonly")` DataSource，Docker 初始化脚本创建 readonly 用户 | 1h |
| **P1-6** | 产品属性结构化 JSONB | PRD v1.9 说的是**模板级别**的 product_attributes 动态化（已实现），不是产品实体本身。验证当前实现是否满足需求，若确认模板层面已够用则标记为 PASS | 0.5h |

### 第三批：P2 功能不完整

| 编号 | 问题 | 修复内容 | 预估工时 |
|------|------|---------|---------|
| **P2-1** | 客户分类标签维度 | 保持当前等级标签（与原型一致），额外增加"活跃/不活跃"状态筛选 Select | 0.5h |
| **P2-2** | 客户搜索缺联系人匹配 | 后端 CustomerService.list() 增加 LEFT JOIN customer_contact 搜索 | 0.5h |
| **P2-3** | 统计面板缺指标 | 前端增加"历史订单数"（按 customerId 查报价单 count）和"平均折扣率"（avg final_discount_rate） | 0.5h |
| **P2-4** | 模板发布方式 | PRD 明确说"草稿(DRAFT)也是一条独立记录，发布后该草稿记录状态变为 PUBLISHED，不再新建"。当前实现是正确的，TEST.md 误读了 PRD。标记为 PASS | 0h |
| **P2-5** | 模板归档检查粒度 | 区分"仅绑定该模板的产品→警告可强制"和"进行中报价单使用→阻止" | 0.5h |
| **P2-6** | DATA_SOURCE 两步绑定 | ComponentManagement 的 DataSourceModal 改为两步：步骤一从数据源列表选择，步骤二绑定参数 | 1h |
| **P2-7** | 步骤三折扣自动刷新 | QuotationWizard 进入 step 3 时自动调 calculateDiscount，移除手动按钮 | 0.5h |
| **P2-8** | 步骤四缺字段 | 增加 expiryDate DatePicker（默认 +30天，可调整）和备注 TextArea | 0.5h |
| **P2-9** | 草稿 localStorage 降级 | 自动保存失败时写 localStorage，页面加载时优先从后端恢复，后端不可用时读 localStorage | 0.5h |
| **P2-10** | 编号 4 位限制 | SEQUENCE 不重置是 PRD 允许的（"全局自增4位补零，不按日期重置"）。标记为 PASS | 0h |
| **P2-11** | 多角色视图 | 根据当前用户 role 动态切换列表数据范围：SALES_REP 仅看本人，SALES_MANAGER/SYSTEM_ADMIN 看全部 | 1h |
| **P2-12** | PDF 返回 HTML | 引入 openhtmltopdf 库，Qute 渲染 HTML 后转为真实 PDF 二进制流返回 | 1.5h |

### 第四批：P3 轻微增强

| 编号 | 问题 | 修复内容 | 预估工时 |
|------|------|---------|---------|
| **P3-1** | 定时任务时区 | Quarkus cron 不支持时区参数，通过 JVM 启动参数 `-Duser.timezone=Asia/Shanghai` 解决 | 0.1h |
| **P3-3** | Cookie 缺 Secure | SessionHelper Set-Cookie 添加 `Secure` 标志（仅生产 HTTPS 环境） | 0.1h |
| **P3-4** | 限流覆盖不足 | RateLimitFilter 扩展到 forgot-password 和 reset-password 端点 | 0.3h |
| **P3-5** | 折扣率缺 CHECK 约束 | 新增 Flyway 迁移添加 `CHECK (discount_rate >= 0 AND discount_rate <= 100)` | 0.2h |
| **P3-7** | 大版本升级无 UI | TemplateConfigPanel 发布弹窗增加"大版本号"可选输入 | 0.3h |
| **P3-9** | accept/reject 创建人校验 | accept/rejectByCustomer 方法增加 salesRepId 校验（与 P1-3 合并） | 已含 |
| **P3-11** | 审批通知缺邮件 | 与 P1-2 合并 | 已含 |

### 不修复项（合理降级）

| 编号 | 原因 |
|------|------|
| P3-2 | 产品同步是 V2 功能，V1 不需要占位任务 |
| P3-6 | Drools 仅为性能优化，Java 实现功能等价，feature flag 已预留 |
| P3-8 | match_value_id 多态引用是合理的设计，无法加 FK |
| P3-12 | external_id/last_synced_at 是 V2 预留字段，V1 不暴露是正确的 |
| P3-13 | 复制报价单的 DATA_SOURCE 清空需验证但非阻塞 |
| P2-10 | PRD 明确说"全局自增，不按日期重置"，当前实现正确 |
| P2-4 | PRD 明确说"草稿发布后状态变为 PUBLISHED，不再新建"，当前实现正确 |

---

## 工时估算

| 批次 | 问题数 | 预估总工时 |
|------|--------|-----------|
| 第一批 P0 | 3 | 3.5h |
| 第二批 P1 | 6 | 4.5h |
| 第三批 P2 | 10（实际需修8个） | 6.5h |
| 第四批 P3 | 5 | 1h |
| **合计** | **24** | **~15.5h** |

---

## 执行顺序建议

```
P0-2 SQL注入 → P0-1 RBAC权限 → P0-3 AES加密
    ↓
P1-1 审批待办 → P1-2 通知邮件 → P1-3 状态机+创建人校验 → P1-5 只读连接
    ↓
P2-6 DS两步绑定 → P2-7 折扣自动刷新 → P2-8 步骤四字段 → P2-11 角色视图 → P2-12 真实PDF
    ↓
P2-1/2/3/5/9 小修补
    ↓
P3 批量修复
```

先修安全漏洞，再补核心业务，最后打磨细节。
