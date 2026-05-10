# CPQ 报价系统 - TDD 测试规格文档

> **版本**: v1.0
> **生成日期**: 2026-04-29
> **配套文档**: `docs/UI-FLOW.md`（页面/按钮/操作流程）、`docs/API.md`（接口契约）、`docs/PRD.md`（需求规格）
> **面向读者**: 测试工程师 / 后端 / 前端 / SRE
> **测试方法学**: BDD（Given-When-Then）+ TDD（先红后绿，单元 + 集成 + E2E）

---

## 0. 文档总览

### 0.1 测试金字塔

```
            ┌──────────────┐
            │   E2E (UI)    │  Playwright / Cypress (剧情链路)
            │     ~ 5%      │
            ├──────────────┤
            │  集成 (API)   │  REST Assured / Quarkus QuarkusTest
            │    ~ 30%      │
            ├──────────────┤
            │   单元测试    │  JUnit 5 + Mockito（后端） / Vitest + RTL（前端）
            │    ~ 65%      │
            └──────────────┘
```

### 0.2 用例编号规则

`<模块>-<场景>-<编号>` 三段式：

| 前缀 | 模块 |
|---|---|
| AUTH | 认证 |
| CUST | 客户管理 |
| PROD | 产品管理 |
| MAT | 生产料号 |
| CAT | 产品分类 |
| QUOT | 报价单核心 |
| QIMP | 报价导入（V5/V3）|
| QAPP | 报价审批 / 撤回 |
| QOUT | 报价输出（PDF/Excel/邮件） |
| COST | 核价 / 比对 |
| PRC | 定价策略 |
| TPL | 客户报价模板 |
| CTPL | 核价模板 |
| BIND | 产品 - 模板绑定 |
| COMP | 组件管理 |
| BDC | 基础数据配置 |
| TAG | 业务标签字典 |
| DS | 数据源 |
| MD | 主数据维护 / 历史版本 |
| CL | 变更日志 |
| EP | 元素价格中心 |
| CFG | 系统配置中心 |
| LOCK | 锁监控 |
| DDL | DDL 扩列 |
| USR | 用户 / 区域 / 部门 |
| AR | 审批规则 |
| NOTI | 通知 |
| OPL | 操作日志 |
| PERF | 性能 |
| SEC | 安全 / 权限 |

例：`QUOT-SUBMIT-01`、`QIMP-V5-DIFF-03`、`SEC-RBAC-12`。

### 0.3 通用 Fixtures（前置数据）

每个测试套件应使用以下 Fixtures（独立 schema 或事务回滚）：

| Fixture | 内容 |
|---|---|
| `fix_users` | 4 类角色账号：`alice` (SALES_REP) / `bob_mgr` (SALES_MANAGER) / `carol_pm` (PRICING_MANAGER) / `admin` (SYSTEM_ADMIN) |
| `fix_customers` | C1（VIP）+ 联系人 / C2（标准）/ C3（已停用）|
| `fix_products` | 产品分类 P_CAT_AG（银点类）+ 产品 P1（绑定核价模板 + 通用客户报价模板） |
| `fix_basic_data` | basic_data_config 16 sheet 全量 seed (V58_5) + 字段重要性默认配置 |
| `fix_costing_template` | "银点类核价模板 v1" PUBLISHED + isDefault=true |
| `fix_customer_template` | "C1 银点类报价模板 v1" PUBLISHED；通用兜底"银点类通用报价模板 v1" |
| `fix_excel_files` | `valid_5parts.xlsx` / `oversize_2001rows.xlsx` / `bad_format.xls` / `missing_required.xlsx` / `conflict_diff.xlsx` |
| `fix_element_prices` | Ag/Au/Cu 三种元素当日参考价 |
| `fix_pricing_strategy` | C1 客户的 VIP 阶梯折扣策略（满 10 万 9.5 折，满 50 万 9 折）|

### 0.4 通用断言模板

```
✅ HTTP 状态码符合预期
✅ 响应包装：{ "code": <expected>, "message": <regex>, "data": <schema> }
✅ DB 写入：表 X 行数变化 +N，字段值正确
✅ 审计：operation_log / change_log 写入 1 条对应记录
✅ 通知：必要时检查 notifications 表插入
✅ 权限：非授权角色返 403
✅ 性能：响应时间 < 上限（性能用例必填）
✅ 幂等：重复调用结果一致或幂等键校验
✅ 事务：失败路径全部回滚（ROLLBACK 后 DB 状态不变）
```

### 0.5 测试环境约定

| 项 | 值 |
|---|---|
| 后端启动 | `mvn quarkus:dev`（数据库走 testcontainers PostgreSQL 16） |
| 前端启动 | `npm run dev`（指向 `http://localhost:8080/api/cpq`） |
| Mock 开关 | `VITE_USE_MOCK_*` 各模块独立开关（默认关）|
| 时间冻结 | `Clock.fixed(2026-04-29T10:00:00Z)` 用于版本号 / 到期日 / 累计金额日期断言 |
| 文件清理 | 测试完毕清理 `data/imports/<customerId>/2026-04/` 临时文件 |

---

## 1. 认证 / 用户（AUTH）

### AUTH-LOGIN-01：用户名密码正确登录成功
**Given** 用户 `alice` (SALES_REP, 状态 ACTIVE, 非首次登录) 已存在  
**When** `POST /api/cpq/auth/login { username:"alice", password:"<correct>" }`  
**Then**
- HTTP 200，code=200
- 响应 data 含 `user.id / user.role / user.fullName`，**不含密码字段**
- 响应 Header 含 `Set-Cookie: JSESSIONID=<...>; HttpOnly; Secure; SameSite=Strict`
- DB `operation_log` 新增一条 `action=LOGIN`
- 后续请求携带该 Cookie 可访问 `GET /auth/me`

### AUTH-LOGIN-02：错误密码返回 401
**Given** 用户 `alice` 存在  
**When** `POST /auth/login` 提交错误密码  
**Then**
- HTTP 401，code=401，message="用户名或密码错误"
- **不应**透露具体是用户不存在还是密码错误（防爆破）
- 连续失败 5 次后账号锁定 15 分钟，第 6 次返回 423

### AUTH-LOGIN-03：首次登录强制改密
**Given** 用户 `new_user`（`forceChangePassword=true`）  
**When** 登录成功  
**Then** 响应 `data.forceChangePassword=true`，前端强制跳 `/change-password`，访问其它路由统一重定向回此页

### AUTH-CHGPWD-04：修改密码新旧密码相同 → 400
**Given** 已登录用户  
**When** `POST /auth/change-password { oldPassword=X, newPassword=X }`  
**Then** HTTP 400，message="新密码不能与旧密码相同"

### AUTH-CHGPWD-05：修改密码弱密码 → 400
**When** newPassword="123" 或仅字母  
**Then** HTTP 400，message="密码至少 8 位且包含字母与数字"

### AUTH-FORGOT-06：忘记密码邮件发送
**When** `POST /auth/forgot-password { email }`  
**Then**
- 邮件存在 → 200，发送重置链接（含 1 次性 token，TTL 30 分钟）
- 邮件不存在 → 仍返 200（防枚举）

### AUTH-RESET-07：重置密码 token 失效
**Given** token 已过期或已用  
**When** `POST /auth/reset-password { token, newPassword }`  
**Then** HTTP 400，message="重置链接已失效，请重新申请"

### AUTH-LOGOUT-08：登出
**When** `POST /auth/logout`  
**Then**
- HTTP 200
- Cookie 被清除（`Set-Cookie: JSESSIONID=; Max-Age=0`）
- 后续请求 `GET /auth/me` 返 401

### AUTH-ME-09：获取当前用户
**When** `GET /auth/me`（已登录）  
**Then** data 含 `id, username, fullName, role, regionId, departmentId, forceChangePassword`

---

## 2. 客户管理（CUST）

### CUST-LIST-01：分页 + 关键词搜索
**Given** DB 有 25 个客户（10 VIP, 15 标准）  
**When** `GET /customers?page=0&size=10&keyword=华&level=VIP`  
**Then**
- data.totalElements=匹配数，content.length<=10
- 所有返回的 customer.name 或 code 含"华"，level=VIP
- 响应时间 < 500ms

### CUST-CREATE-02：创建客户成功
**Given** 销售代表已登录  
**When** `POST /customers { name:"华南科技", level:"VIP", contacts:[{name:"张三", phone:"13800000000", isPrimary:true}] }`  
**Then**
- HTTP 200，data.id 非空，data.code 自动生成（如 `CUST-2026-0123`）
- DB customer + customer_contact 各 1 条
- 至少 1 个 isPrimary=true 联系人

### CUST-CREATE-03：联系人手机号格式校验
**When** 联系人 phone="abc" 或 "1234"  
**Then** HTTP 400，message 含"手机号格式不正确"

### CUST-CREATE-04：缺少主要联系人 → 400
**When** contacts 全部 isPrimary=false  
**Then** HTTP 400，message="每个客户至少保留一个主要联系人"

### CUST-DELETE-05：客户有进行中报价单禁止删除
**Given** 客户 C1 存在 1 张 DRAFT 报价单  
**When** `DELETE /customers/{C1.id}`  
**Then** HTTP 400，message 形如"该客户有进行中的报价单（1张），无法删除..."  
**And** DB customer 状态保持 ACTIVE 不变

### CUST-DELETE-06：所有报价单已 ACCEPTED/REJECTED 可删除
**Given** 客户 C2 报价单全为终态  
**When** `DELETE /customers/{C2.id}`  
**Then** HTTP 200，DB customer.status=INACTIVE（软删），原报价单仍可查询

### CUST-ACCUM-07：ACCEPTED 触发累计金额原子更新（并发安全）
**Given** 客户 C1.accumulated_amount=100,000  
**When** 同时（多线程）将 2 张 SENT 报价单（各 50,000）标记为 ACCEPTED  
**Then**
- 最终 accumulated_amount=200,000（不丢失更新）
- SQL 路径：`UPDATE customer SET accumulated_amount = accumulated_amount + :delta WHERE id=:id`（白盒检查日志）
- 与 ACCEPTED 状态切换在同一事务，事务失败两侧回滚

### CUST-MAPPING-08：客户料号映射重复导入冲突
**Given** C1 已存在 (custMatNo="X100", internalMatNo="HF001") 映射  
**When** 再次 `POST /customers/{C1.id}/material-mappings/import` 含同一行  
**Then** 200，但 result.skipped=1, errors 记录"已存在"；DB 不重复插入

### CUST-CONTACTS-09：删除最后一个主要联系人
**Given** C1 仅 1 个 isPrimary 联系人  
**When** 调用删除联系人 API  
**Then** HTTP 400，message="不能删除最后一位主要联系人"

### CUST-RBAC-10：定价经理只读
**Given** carol_pm 登录  
**When** `POST /customers` / `PUT /customers/{id}` / `DELETE`  
**Then** HTTP 403

### CUST-UI-11（E2E）：客户管理列表抽屉操作
**Given** 已登录销售代表  
**Steps**：
1. 进入 `/customers`
2. 点击「+ 新增客户」→ 抽屉打开（宽 720）
3. 填写客户名称 / 等级 / 一个联系人（必填项）
4. 点击「保存」  
**Expect**：抽屉关闭，列表第一行展示新客户，message.success("创建成功")

---

## 3. 产品管理（PROD / MAT / CAT）

### PROD-CREATE-01：创建产品分类引用
**When** `POST /products { name, partNo:"P1001", categoryId:<existing>, status:"ACTIVE" }`  
**Then** HTTP 200，DB product 1 条，categoryName 由后端反查填充

### PROD-CREATE-02：partNo 重复
**Given** P1001 已存在  
**When** 再次创建相同 partNo  
**Then** HTTP 400，message="产品料号 P1001 已存在"

### PROD-CREATE-03：categoryId 不存在
**When** categoryId=不存在的 UUID  
**Then** HTTP 400，message 含"产品分类不存在"

### PROD-IMPORT-04：Excel 批量导入成功
**Given** 文件含 100 行有效数据  
**When** `POST /products/import` (multipart)  
**Then**
- HTTP 200，data.successCount=100, failedCount=0
- DB product 新增 100 条
- 响应时间 < 5s（5000 行上限测试见 PERF-PROD-IMPORT）

### PROD-IMPORT-05：缺失必填列
**When** Excel 缺"产品名称"列  
**Then** HTTP 400，message 形如"导入失败：缺失必填列【产品名称】"

### PROD-DELETE-06：被进行中报价单引用 → 阻止
**Given** 产品 P1 在 DRAFT 报价单 line_item 中被引用  
**When** `DELETE /products/{P1.id}`  
**Then** HTTP 400，message="存在 N 张进行中报价单引用此产品..."

### PROD-PROCESS-07：保存工序绑定
**When** `PUT /products/{id}/processes [{processId, sortOrder, isRequired}]`  
**Then** DB product_process_binding 全量替换；操作日志写入

### MAT-IMPORT-08：生产料号 5000 行批量导入
**When** Excel 5000 行  
**Then** 成功，响应时间 < 30s（v1 性能 SLA），无 OOM

### MAT-DELETE-09：被映射引用禁止删除
**Given** 内部料号 HF001 被客户映射引用  
**When** `DELETE /internal-materials/{id}`  
**Then** HTTP 400，message="该料号被 N 个客户料号映射引用..."

### CAT-CYCLE-10：分类循环引用
**Given** 分类 A 是 B 的父级  
**When** `PUT /product-categories/{B.id} { parentId:<A> }` 后再 `PUT /product-categories/{A.id} { parentId:<B> }`  
**Then** 第二次 HTTP 400，message="检测到循环引用..."

### CAT-DELETE-11：有子分类禁止删
**When** 删除有子级的分类 → 400

---

## 4. 基础数据配置（BDC）

### BDC-SHEET-CRUD-01：Sheet 增删改查
**When** 创建 Sheet `{ sheetName:"测试Sheet", targetTable:"mat_part", sheetIndex:5 }`  
**Then** HTTP 200，DB basic_data_config 1 条，targetTable 持久化

### BDC-SHEET-DEL-02：有子 Sheet 禁止删除
**Given** 父 Sheet S1 → 子 Sheet S2 (parentConfigId=S1.id)  
**When** `DELETE /basic-data-config/sheets/{S1.id}`  
**Then** HTTP 400，message="存在子 Sheet 配置，请先删除子级"

### BDC-ATTR-03：variableCode 唯一约束
**Given** Sheet S1 已有 attribute variableCode=`UNIT_WEIGHT`  
**When** 再为 S1 添加同 variableCode 的 attribute  
**Then** HTTP 400，message 含"变量编码已存在"  
**Note**：V57 修复：复合唯一索引（不再是全表）

### BDC-ATTR-04：列字母解析正确
**When** columnLetter="AA"  
**Then** 后端 columnIndex=26（A=0,Z=25,AA=26）

### BDC-DERIVED-05：computation 类型必填校验
**When** 创建 derived attribute 但 computation.type 缺失  
**Then** HTTP 400，message="计算定义不完整：缺少 type"

### BDC-DERIVED-06：EXPRESSION 公式语法错误
**When** computation={ type:"EXPRESSION", formula:"[A] + ]" }  
**Then** HTTP 400，message 含"公式解析失败"

### BDC-PARSE-EXCEL-07：Excel 解析返回结构
**When** `POST /basic-data-config/parse-excel` 上传 valid_5parts.xlsx  
**Then** data.sheets 含数组，每个 sheet 有 sheetName / sheetIndex / headerRowIndex / columns[{columnLetter, columnIndex, columnTitle}]

### BDC-IMPORT-METADATA-08（v5 元数据化）：未在 basic_data_config 注册的 Sheet 跳过 + WARN 日志
**Given** Excel 含 16 标准 Sheet + 1 个未注册的 Sheet  
**When** V5 导入  
**Then**
- 16 Sheet 全部成功解析
- 1 个未注册 Sheet 跳过且打 WARN 日志
- 不阻断其余 Sheet

---

## 5. 业务标签字典（TAG）

### TAG-LIST-01：内置 11 个标签存在
**When** `GET /comparison-tags`  
**Then** 返回 ≥ 11 条，含 `MATERIAL_COST_AG / MATERIAL_COST_CU / MATERIAL_COST_TOTAL / PROCESSING_COST / LABOR_COST / SETUP_COST / OVERHEAD_COST / PACKAGING_COST / CUSTOM_COST / UNIT_TOTAL_COST / TOTAL`

### TAG-BUILTIN-DEL-02：内置标签禁止删除
**When** `DELETE /comparison-tags/{MATERIAL_COST_AG.id}`  
**Then** HTTP 400，message="内置标签不可删除，仅可禁用"

### TAG-BUILTIN-CODE-03：内置标签 code 不可改
**When** `PUT /comparison-tags/{builtin.id} { code:"NEW_CODE" }`  
**Then** HTTP 400，message="内置标签 code 不可修改"

### TAG-CUSTOM-04：自定义标签全功能 CRUD
**When** 新增/更新/删除自定义 code="CUSTOM_X"  
**Then** 全部成功

---

## 6. 核价模板（CTPL）

### CTPL-LIST-01：按分类筛选
**When** `GET /costing-templates?categoryId=<P_CAT_AG>&status=PUBLISHED`  
**Then** 仅返该分类 PUBLISHED 模板

### CTPL-DEFAULT-02：同分类仅一个 isDefault
**Given** 分类 P_CAT_AG 已有 default 模板  
**When** 创建另一个 isDefault=true 的模板  
**Then** HTTP 400，message="该分类已存在默认核价模板"  
**Note**：依赖部分唯一索引

### CTPL-EDIT-DRAFT-03：仅 DRAFT 可编辑 columns
**Given** 模板 T1 状态 PUBLISHED  
**When** `PUT /costing-templates/{T1.id} { columns: [...] }`  
**Then** HTTP 400，message="已发布模板不可编辑"

### CTPL-PUBLISH-04：发布递增版本号
**Given** T1 v1 PUBLISHED, 创建新草稿 T1' v2 DRAFT  
**When** `POST /costing-templates/{T1'.id}/publish`  
**Then**
- T1' 状态 PUBLISHED, version=2
- 同分类原 default 模板 isDefault 不变（如果 T1' 不标 default）
- 若 T1' 标 default → T1 isDefault 变 false

### CTPL-DELETE-05：仅 DRAFT 可删除
**When** 删除 PUBLISHED → 400

### CTPL-COLUMN-FORMULA-06：列公式语法校验
**When** column.formula="[C]*[D]+[A]" 含未引用列 → 400

---

## 7. 客户报价模板（TPL）

### TPL-MATCH-01：客户专属优先于通用
**Given** 客户 C1 + 分类 P_CAT_AG 同时有：
- 客户专属模板 T_C1（PUBLISHED）
- 通用模板 T_PUB（PUBLISHED）

**When** V5 导入选 C1 + P_CAT_AG  
**Then** matchedCustomerTemplateOptions 优先返 T_C1（仅 1 条 → 自动选中）

### TPL-MATCH-02：仅有通用模板时回退
**Given** 客户 C2 无专属模板  
**When** 导入选 C2 + P_CAT_AG  
**Then** 自动回退选 T_PUB

### TPL-MATCH-03：无任何模板 → 阻止
**Given** 分类 P_CAT_NEW 既无客户专属也无通用模板  
**When** V5 导入分类 P_CAT_NEW  
**Then** HTTP 400，message="未匹配到客户报价模板，请先在配置中心维护"

### TPL-MATCH-04：多版本时由用户选择
**Given** 同 (customer×category) 有 2 个 PUBLISHED 模板  
**When** 预览  
**Then** matchedCustomerTemplateOptions 含 2 个 UUID，前端弹 Select 让用户选

### TPL-PUBLISH-05：唯一索引校验
**Given** (C1, P_CAT_AG) 已有 PUBLISHED  
**When** 发布另一 (C1, P_CAT_AG) DRAFT  
**Then** HTTP 400，message 含"已存在已发布版本，请先归档..."

### TPL-ARCHIVE-06：有 in-progress 报价单时禁止归档（除非 force）
**Given** 模板 T1 被 1 个 DRAFT 报价单引用  
**When** `POST /templates/{T1.id}/archive` (无 force)  
**Then** HTTP 400  
**When** `POST /templates/{T1.id}/archive?force=true`  
**Then** 200, T1.status=ARCHIVED

### TPL-NEW-DRAFT-07：基于已发布版本创建新草稿
**Given** T1 v1 PUBLISHED  
**When** `POST /templates/{T1.id}/new-draft`  
**Then** 新模板 T1' v2 DRAFT，columns / components 完整继承

### TPL-EXCEL-VIEW-08：Excel 视图配置保存
**When** `PUT /templates/{id}/excel-view-config` 含 sheets[]、columns[]  
**Then** DB excelViewConfig JSONB 持久化，importEntrySheet 引用的 sheet_name 必须在 sheets 列表

### TPL-PARSE-HEADER-09：解析客户 Excel 表头
**When** `POST /templates/{id}/excel-view-config/parse-header` 上传客户 Excel  
**Then** data 返回 sheets[].columns[]，columnLetter / columnTitle 抽取正确

---

## 8. 报价单核心（QUOT）

### QUOT-CREATE-01：手动创建草稿
**When** `POST /quotations { customerId:C1, name:"Q-Test" }`  
**Then**
- HTTP 200，data.quotationNumber 形如 `QT-20260429-NNNN`（自动生成）
- status=DRAFT, totalAmount=0
- 创建人 = 当前用户

### QUOT-DRAFT-SAVE-02：保存草稿增量更新 line items
**Given** 报价单 Q1 (DRAFT, 2 个 line items)  
**When** `PUT /quotations/{Q1.id}/draft { lineItems: [3 个] }`  
**Then** DB line_items 全量替换为 3 条，旧 2 条软删除

### QUOT-DRAFT-AUTO-03：前端 10s 自动保存
**Given** 报价单向导打开  
**When** 用户编辑某字段后停留 ≥10s  
**Then** 自动触发 `PUT /draft`，右上角显示"已自动保存于 HH:mm:ss"  
**Edge**：网络失败 → 显示"保存失败，将在 5s 后重试"

### QUOT-CALC-DISC-04：自动计算折扣应用客户策略
**Given** C1 有 VIP 阶梯策略（满 10 万 9.5 折）；Q1 originalAmount=120000  
**When** `POST /quotations/{Q1.id}/calculate-discount`  
**Then**
- systemDiscountRate=0.05（即 95%）
- totalAmount=114000
- 数据库未提交（仅响应），前端展示后用户可调

### QUOT-SUBMIT-05：DRAFT → SUBMITTED
**Given** Q1 DRAFT，无 ERROR 字段，无漂移  
**When** `POST /quotations/{Q1.id}/submit`  
**Then**
- status=SUBMITTED, submittedAt 写入, assignedApproverId 由审批规则解析
- DB 快照冻结：snapshot_customer_*, costing_sheet 状态 LIVE → SNAPSHOT
- 通知审批人（notifications 表 1 条）
- DRAFT 期间所有 customer-level 引用版本被记录在 ImportRecord

### QUOT-SUBMIT-06：DRAFT 含 ERROR 字段不可提交
**Given** Q1 line item.cells 含一个 cell.error="Division by zero"  
**When** 提交  
**Then** HTTP 400，message="存在 N 个公式错误单元格，无法提交"

### QUOT-SUBMIT-07：DRAFT 漂移横幅未消除不可提交
**Given** customer-level 数据版本已变更，前端显示漂移横幅  
**When** 用户未点"使用最新版本"直接提交  
**Then** HTTP 400，message 含"检测到数据漂移，请先确认引用版本"

### QUOT-APPROVE-08：通过 - 仅指派审批人
**Given** Q1 SUBMITTED, assignedApprover=bob_mgr  
**When** alice (SALES_REP) 调 `POST /approve`  
**Then** HTTP 403  
**When** bob_mgr 调 `POST /approve { note }`  
**Then** 200, status=APPROVED, approval_history 1 条

### QUOT-APPROVE-09：SYSTEM_ADMIN 兜底审批
**When** admin 调用任意报价单 `/approve`  
**Then** 200（SYSTEM_ADMIN 全权）

### QUOT-REJECT-10：退回必填 reason
**When** `POST /reject {}` 缺 reason  
**Then** 400, message="退回原因必填"  
**When** `POST /reject { reason:"价格过高" }`  
**Then** 200, status=DRAFT, approval_history 写入；销售代表收到通知

### QUOT-WITHDRAW-11：销售代表 SUBMITTED 撤回
**Given** Q1 SUBMITTED, alice 是创建人  
**When** alice `POST /withdraw`  
**Then** 200, status=DRAFT, snapshot 解除冻结  
**When** carol_pm 尝试同操作  
**Then** 403

### QUOT-COPY-12：复制报价单
**When** `POST /quotations/{Q1.id}/copy`  
**Then**
- 新建 Q2，状态 DRAFT
- quotationNumber 全新（QT-20260429-NNNN+1）
- expiryDate 重置为 today+30，所有审批记录清空
- line items 完整复制

### QUOT-DELETE-13：DRAFT 删除（仅创建人 / ADMIN）
**Given** Q1 DRAFT, 创建人 alice  
**When** bob_mgr 删除 → 403（除非他是 ADMIN）  
**When** alice 删除 → 200, 软删除  
**Edge**：SUBMITTED+ 状态删除一律 400

### QUOT-LIST-14：本人报价单 / 待我审批
**When** alice `GET /quotations`  
**Then** 仅返 alice 创建的报价单（含所有状态）  
**When** bob_mgr `GET /quotations?assignedToMe=true&status=SUBMITTED`  
**Then** 仅返 assignedApproverId=bob_mgr 的 SUBMITTED 报价单

---

## 9. 报价单导入（QIMP）— V5 主流程

### QIMP-V5-PREVIEW-01：合法 Excel 预览成功
**Given** alice 登录, valid_5parts.xlsx (5 产品)  
**When** `POST /quotations/import-basic-data` 上传 + customerId=C1  
**Then**
- HTTP 200
- data.importBatchId 非空（UUID）
- data.totalProducts=5
- data.costingRows.length=5, productPreview.length=5
- data.matchedCostingTemplateOptions.length=1（自动选中）
- data.tempFilePath 存在于 `data/imports/<C1.id>/2026-04/<batch>-data.xlsx`
- 校验产品分类一致性（5 产品同属 P_CAT_AG）
- 响应时间 < 3s

### QIMP-V5-PREVIEW-02：超 2000 行 Excel 拒绝
**Given** oversize_2001rows.xlsx  
**When** 上传  
**Then** HTTP 400, message="单次导入行数超出上限 2000，请拆分后重试"

### QIMP-V5-PREVIEW-03：错误格式 (.xls / .csv) 拒绝
**When** 上传 bad_format.xls  
**Then** HTTP 400, message="仅支持 .xlsx 格式"

### QIMP-V5-PREVIEW-04：缺失必填列
**Given** missing_required.xlsx 缺 HF_PART_NO 列  
**When** 上传  
**Then** HTTP 400, errors 数组含"Sheet '宏丰料号' 缺少必填列【HF_PART_NO】"

### QIMP-V5-PREVIEW-05：产品分类不一致警告
**Given** Excel 含 P_CAT_AG + P_CAT_AU 混合产品  
**When** 上传  
**Then** HTTP 200, warnings 含"检测到 2 种产品分类，请确认是否分批导入"

### QIMP-V5-PREVIEW-06：未配置任何模板时阻止
**Given** P_CAT_NEW 无核价模板  
**When** Excel 含 P_CAT_NEW 产品  
**Then** HTTP 400, message="分类 [新分类] 未配置核价模板"

### QIMP-V5-DIFF-07（UI-2）：基础资料整体差异 - CRITICAL 备注必填
**Given** 预览检测到基础资料差异（CRITICAL 字段 unit_weight 旧 0.4 → 新 0.5）  
**When** 前端 UI-2 抽屉用户未填写 CRITICAL 字段备注就点击"全部采纳"  
**Then** "确认基础资料"按钮置灰，提示"CRITICAL 字段需填写变更原因"

### QIMP-V5-CONFLICT-08（UI-1）：客户资料字段级冲突决策
**Given** 预览检测到 customer-level 冲突（unit_price 旧 380 → 新 400）  
**When** 前端 UI-1 字段级选 "采纳新值" 提交  
**Then** confirm-basic-data-import 请求体包含 resolutions[]  
**And** 后端 VersionedWriter 对 mat_fee 表 INSERT 新版本，is_current=true，旧版本 is_current=false  
**And** basic_data_change_log 字段级记录写入 1 条（field_name=unit_price, old_value=380, new_value=400, change_source=V5_IMPORT）

### QIMP-V5-CONFLICT-09：KEEP_OLD 决策不写新版本
**When** resolution = KEEP_OLD  
**Then** mat_fee 不变，change_log 也不写入

### QIMP-V5-CONCURRENT-10：预览 → 确认期间被另一线程修改
**Given** alice 完成预览（拿到 importBatchId）  
**When** bob_mgr 同时修改了同一料号的 mat_fee（is_current=true 的版本号变化）  
**And** alice 提交确认  
**Then** HTTP 409, message="数据已被其他用户修改，请重新预览"

### QIMP-V5-LOCK-11：DDL 锁活跃时新导入被拒
**Given** 管理员正在执行 DDL 扩列（DDL 全局锁活跃）  
**When** 任何用户尝试 V5 import  
**Then** HTTP 423, message="系统正在执行 DDL 操作，请稍后重试"

### QIMP-V5-LOCK-12：同料号产品锁互斥
**Given** alice 正在导入料号 HF001（持锁中）  
**When** bob_mgr 同时尝试导入含 HF001 的 Excel  
**Then** HTTP 423, message="料号 HF001 正在被其他用户导入，请稍后重试"

### QIMP-V5-CONFIRM-13：成功生成报价单与 ImportRecord
**When** `POST /quotations/confirm-basic-data-import`（合法请求）  
**Then**
- HTTP 200, data.quotationId / quotationNumber
- DB:
  - quotation 1 条 (DRAFT)
  - quotation_line_item N 条
  - costing_sheet 1 条 (LIVE)
  - import_record 1 条（含 costingTemplateSnapshot + customerTemplateSnapshot 完整 JSON）
  - product_data_pool 1 条（按 importBatchId）
  - basic_data_change_log K 条字段级记录
- 临时文件 `tempFilePath` 移动至持久路径

### QIMP-V5-CONFIRM-14：事务原子性
**Given** 模拟 line_items 写入成功后 costing_sheet 写失败  
**Then** **全部回滚**，DB 无任何残留写入，importBatchId 释放

### QIMP-V5-REIMPORT-15：DRAFT 重新导入合并
**Given** Q1 DRAFT 已有 5 行 line items（人工修改过 2 个字段）  
**When** `POST /quotations/{Q1.id}/reimport-basic-data` 上传新 Excel  
**Then**
- 系统对比修改字段 vs 新数据 → 弹出"保留 / 覆盖"决策
- 用户决策后 line items 重算
- import_record 新增 1 条（关联 Q1）
- 不影响审批历史

### QIMP-V5-REIMPORT-16：APPROVED 报价单不可重导
**Given** Q1 APPROVED  
**When** reimport-basic-data  
**Then** HTTP 400, message="已批准报价单不可重新导入，请先撤回"

### QIMP-V3-EXCEL-17（向后兼容）：客户 Excel 导入
**When** `POST /imports/import-excel` + templateId  
**Then** 按模板 excelViewConfig 解析，预览返回成功

### QIMP-RECORD-18：导入历史下载原始文件
**When** `GET /imports/records/{id}/download`  
**Then** Content-Disposition: attachment; filename="<originalFileName>"，文件字节一致

### QIMP-RETENTION-19：12 个月后原始 Excel 清理
**Given** 导入记录 R1 createdAt < now - 12 个月  
**When** 定时任务执行  
**Then** R1.originalFilePath 文件删除, DB 字段置 NULL；ImportRecord 本身永久保留

---

## 10. 核价表 + 比对视图（COST）

### COST-SHEET-01：核价表生成正确
**Given** Q1 已确认导入  
**When** `GET /quotations/{Q1.id}/costing-sheet`  
**Then**
- columns 与 costing_template_snapshot 一致
- rows 数量 = line_items 数
- totalCost = SUM(每行 UNIT_TOTAL_COST 列)
- 状态 LIVE（DRAFT 阶段）

### COST-SHEET-02：SUBMITTED 后核价表冻结
**When** Q1 提交后查询  
**Then** status=SNAPSHOT, rows / cells 不再随基础数据变更而变化

### COST-COMPARE-03：比对视图基础字段差异
**Given** Q1 line item 修改了 AG_PRICE: 400 → 420  
**When** `GET /quotations/{Q1.id}/comparison`  
**Then**
- basicFieldDiffs 含 { variableCode:"AG_PRICE", costingValue:400, quotationValue:420, diffStatus:"MODIFIED" }
- summary.modifiedFieldsCount=1

### COST-COMPARE-04：业务标签分组差异
**Given** 模板列绑定 comparison_tag=MATERIAL_COST_AG  
**When** 查询比对视图  
**Then**
- tagGroups[].tags[] 含 MATERIAL_COST_AG 行 with delta + deltaPct
- summary.profitRate 计算公式正确：(quotationTotal-costingTotal)/quotationTotal

### COST-COMPARE-05：毛利率低于阈值警告
**Given** business.gross_margin_warning_min=0.15  
**And** Q1 profitRate=0.08  
**When** 查询比对视图  
**Then** summary 含 warningLevel="LOW_MARGIN"

### COST-COMPARE-06：毛利率低于阻止阈值不可提交
**Given** business.gross_margin_block_min=0.05, profitRate=0.03  
**When** 用户点提交  
**Then** HTTP 400, message="毛利率低于 5%，禁止提交，请调整定价"

---

## 11. 报价审批撤回（QAPP）

### QAPP-WD-REQ-01：APPROVED → 请求撤回
**Given** Q1 APPROVED, alice 是销售代表  
**When** alice `POST /quotations/{Q1.id}/withdraw-request { reason:"客户调整需求" }`  
**Then**
- 创建 quotation_withdraw_request (PENDING)
- bob_mgr（原审批人）收到通知

### QAPP-WD-REQ-02：reason 必填
**When** reason 缺失或空字符串  
**Then** 400

### QAPP-WD-REQ-03：非 APPROVED 状态不可撤回
**Given** Q1 SUBMITTED  
**When** withdraw-request  
**Then** 400, message="仅已批准报价单可发起撤回请求"

### QAPP-WD-REQ-04：同一报价单仅一个 PENDING
**Given** Q1 已有 PENDING 撤回  
**When** 再次 withdraw-request  
**Then** 400, message="已存在待处理的撤回请求"  
**Note**：依赖部分唯一索引

### QAPP-WD-APPROVE-05：原审批人同意撤回
**When** bob_mgr `POST /withdraw/approve`  
**Then**
- request 状态 APPROVED
- Q1 状态 APPROVED → DRAFT
- quotation_approval 记录 action=WITHDRAWN
- alice 收到通知

### QAPP-WD-REJECT-06：原审批人拒绝撤回
**When** bob_mgr `POST /withdraw/reject`  
**Then** request 状态 REJECTED, Q1 仍 APPROVED

### QAPP-WD-FALLBACK-07：原审批人离职 → ADMIN 兜底
**Given** bob_mgr 状态变 INACTIVE  
**When** admin 调 `POST /withdraw/approve`  
**Then** 200（兜底路由生效）

### QAPP-WD-RBAC-08：第三方角色禁止
**When** carol_pm 调 withdraw/approve → 403

---

## 12. 报价输出（QOUT）

### QOUT-PDF-01：导出 HTML 可打印
**When** `POST /quotations/{Q1.id}/export/pdf`  
**Then**
- Content-Type: text/html
- HTML 含报价单号 / 客户 / 产品明细 / 总价 / 二维码（可选）
- 浏览器打印可生成 PDF

### QOUT-EXCEL-02：导出 Excel 含公式
**When** `POST /quotations/{Q1.id}/export/excel`  
**Then**
- Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
- 文件名 形如 `QT-20260429-0001.xlsx`
- 多 Sheet 按 customer_template_snapshot 渲染
- EXCEL_FORMULA 列保留 Excel 公式（{row} 替换为实际行号 2..N）
- FIXED_VALUE 列写死值

### QOUT-EXCEL-03：性能 - 50 产品 < 3s
**Given** Q1 含 50 line items  
**When** 导出 Excel  
**Then** 响应时间 < 3s

### QOUT-SEND-04：邮件发送成功
**When** `POST /quotations/{Q1.id}/send { to:"a@b.com", subject, body }`（Q1 已 APPROVED）  
**Then**
- HTTP 200, status SENT
- 邮件队列 1 条
- 附件含 Excel（如 attachExcel=true）
- expiryDate 不变

### QOUT-SEND-05：非 APPROVED 状态禁止发送
**Given** Q1 DRAFT  
**When** /send  
**Then** 400, message="仅已批准报价单可发送给客户"

### QOUT-SEND-06：to 字段格式校验
**When** to="invalid"  
**Then** 400

### QOUT-EXTEND-07：延期 expiryDate
**When** `PUT /extend { newExpiryDate:"2026-06-30" }`  
**Then** Q1.expiryDate 更新

### QOUT-EXTEND-08：延期日期不能早于当前
**When** newExpiryDate=昨天  
**Then** 400, message="延期日期不能早于今日"

### QOUT-ACCEPT-09：标记客户接受 → 累计金额累加
**Given** Q1 SENT, totalAmount=50000, C1.accumulated_amount=100000  
**When** alice `POST /accept`  
**Then**
- Q1 ACCEPTED
- C1.accumulated_amount=150000（同事务原子加）
- 操作日志写入

### QOUT-REJECT-CUSTOMER-10：客户拒绝
**When** `POST /reject-by-customer { reason }`  
**Then** Q1 REJECTED, customer.accumulated_amount **不变**

### QOUT-EXPIRE-11：定时任务过期
**Given** Q1 SENT, expiryDate=昨天  
**When** Quarkus Scheduler 执行  
**Then** Q1 EXPIRED, 通知销售代表

### QOUT-EXCEL-VIEW-12：双向同步
**When** `PUT /excel-view` 修改 cell 值  
**Then** 对应 line_item 的 component_field 同步更新

---

## 13. 定价策略（PRC）

### PRC-CRUD-01：创建策略 + 规则
**When** `POST /pricing-strategies + POST /rules`  
**Then** DB 1 条策略 + N 条规则

### PRC-RULE-RANGE-02：阶梯规则匹配金额
**Given** 规则：满 100000 → 9.5 折  
**When** Q1.originalAmount=120000，调 calculate-discount  
**Then** systemDiscountRate=0.05, totalAmount=114000

### PRC-RULE-EXPIRED-03：过期策略不参与计算
**Given** 策略 endDate < today  
**When** 计算折扣  
**Then** 该策略被忽略（status='EXPIRED'）

### PRC-RULE-CUSTOMER-LEVEL-04：等级匹配
**Given** 规则限 VIP 客户  
**When** 标准客户调用  
**Then** 不应用此规则

### PRC-RULE-DELETE-05：仅 DISABLED 可删
**When** 删除 ACTIVE 策略 → 400

### PRC-RBAC-06：仅 PRICING_MANAGER / ADMIN 可写
**When** SALES_REP `POST /pricing-strategies` → 403

---

## 14. 组件 / 数据源（COMP / DS）

### COMP-CREATE-01：自动编码
**When** `POST /components { name }`  
**Then** code 自动生成 `COMP-XXXX`（4 位数字递增）

### COMP-CYCLE-02：DFS 检测循环引用
**Given** 组件 A.formula 引用 B 的字段，B.formula 引用 A 的字段  
**When** `PUT /components/{B.id}` 保存  
**Then** 400, message="检测到循环引用：A → B → A"

### COMP-RENAME-03：字段重命名同步公式
**Given** 组件 X 字段 oldKey="price" → newKey="unit_price"，被 X.formula 和 X 内其它字段引用  
**When** 更新字段  
**Then** 所有引用自动同步重写为 [unit_price]

### DS-AES-04：API headers 加密存储
**When** `POST /datasources { type:API, headers:{"Authorization":"Bearer xxx"} }`  
**Then** DB 中 headers 字段为 AES-256-GCM 密文，不可明文读出

### DS-TEST-05：SQL 数据源测试
**When** `POST /datasources/{id}/test { params }`  
**Then** 返回 SQL 执行结果前 100 行

### DS-PARAM-06：必填参数缺失
**When** params 缺必填 → 400

---

## 15. 主数据维护（MD）

### MD-OVERVIEW-01：数据总览展示
**When** `GET /master-data` 客户=null  
**Then** 全局基础资料 + 元素表卡片返回

### MD-HISTORY-02：版本列表筛选
**When** `GET /versioning/history?customerId&tableName&hfPartNo`  
**Then** 返回多版本，按 version DESC

### MD-DETAIL-03：非 GET 拒绝
**When** `POST /versioning/row/...`  
**Then** HTTP 405

### MD-COMPARE-04：双列对比 diff
**When** `GET /versioning/compare?versionA=1&versionB=3`  
**Then**
- 返回字段级差异列表
- 字段名 / 旧值 / 新值 / 状态（SAME / MODIFIED / ADDED / REMOVED）

### MD-FIELD-IMP-05：仅 SYSTEM_ADMIN 编辑
**When** SALES_MANAGER `PUT /master-data/field-importance` → 403

### MD-FIELD-IMP-06：CRITICAL 字段在冲突 UI 排顶
**Given** 字段 unit_weight=CRITICAL  
**When** UI-1 渲染冲突列表  
**Then** unit_weight 行排在最顶部 + 醒目高亮（白盒可断言数据排序）

---

## 16. 变更日志（CL）

### CL-LIST-01：默认 7 天 + 客户筛选
**When** `GET /change-log/search?customerId=C1`  
**Then** 仅返 7 天内 + customer_id=C1 的记录

### CL-LIST-02：字段名筛选
**When** fieldName=unit_price  
**Then** 仅返该字段历史

### CL-EXPORT-03：导出超 10000 行 → 422
**Given** 时间范围匹配 10001 条  
**When** `GET /change-log/export?format=EXCEL`  
**Then** HTTP 422, message="导出行数超出上限 10000，请缩小筛选范围"

### CL-EXPORT-04：合法导出 Excel
**When** 匹配 5000 行  
**Then** 200, Content-Type: application/vnd...sheet, 文件可下载

### CL-EXPORT-05：CSV 格式
**When** format=CSV  
**Then** Content-Type: text/csv; charset=utf-8

### CL-RBAC-06：SALES_REP 不可访问
**When** alice `GET /change-log/search` → 403

### CL-RETENTION-07：5 年保留期清理
**Given** retention.change_log_years=5  
**Given** 记录 createdAt < now - 5 年  
**When** 定时任务执行  
**Then** 旧记录被删除

---

## 17. 元素价格中心（EP）

### EP-MANUAL-01：录入参考价 UPSERT
**Given** 同元素同日 (Ag, 2026-04-29) 已有 MANUAL 行 (4500)  
**When** admin `POST /element-prices/manual { elementName:Ag, price:5500, currency:RMB, unit:"克" }`  
**Then**
- DB element_daily_price 行 price=5500（覆盖，非新增）
- 唯一索引 `uq_element_daily(elementName, COALESCE(source_id::TEXT,''), price_date)` 触发 ON CONFLICT DO UPDATE

### EP-MANUAL-02：非 admin 拒绝
**When** alice / bob_mgr / carol_pm 调用 → 403

### EP-REFERENCE-03：取最新参考价
**When** `GET /element-prices/reference?elementName=Ag`  
**Then** 返回最新 MANUAL 行（按 priceDate DESC 取 1）

### EP-REFERENCE-04：不存在元素
**When** elementName=NotExist  
**Then** 200, data=null（前端展示"参考价：暂无"）

### EP-HISTORY-05：分页历史
**When** `GET /element-prices/history?elementName=Ag&from&to&page=0&size=20`  
**Then** 分页返回，按 priceDate DESC

### EP-ELEMENTS-06：动态元素清单
**When** `GET /element-prices/elements`  
**Then** 从 mat_bom (bom_type=ELEMENT) 聚合 distinct element_name

### EP-V1-FORMULA-07：v1 ELEMENT_PRICE 函数禁用
**Given** 报价单公式含 ELEMENT_PRICE("Ag", customerId)  
**When** 重算  
**Then** 单元格 cell.error="ELEMENT_PRICE 函数在 v1 不可用，请改用手填"

### EP-V1-NO-AUTO-FILL-08：v1 不自动填元素单价
**Given** 报价单含元素 BOM 行 (Ag)  
**When** 创建报价单  
**Then** row.element_actual_unit_price 为 null，前端显示空白输入框 + ElementPriceHint 参考价提示

### EP-V1-MANUAL-FILL-09：销售手填后保存
**Given** 销售在元素 BOM 行 Ag 行填入 5400  
**When** 保存草稿  
**Then** row_data 含 { element_name:"Ag", element_actual_unit_price:5400, element_actual_currency:"RMB", element_actual_unit:"克" }

---

## 18. 系统配置（CFG）

### CFG-LIST-01：按分类筛选
**When** `GET /system-config?category=business`  
**Then** 仅返 business 分类配置项

### CFG-EDIT-VALIDATION-02：仅 ADMIN 改 validation
**When** SALES_MANAGER `PUT /system-config/{validation.loss_rate_max}` → 403

### CFG-EDIT-BUSINESS-03：MGR 可改 business
**When** bob_mgr `PUT /system-config/{business.gross_margin_warning_min}` → 200

### CFG-DATATYPE-04：JSON 类型校验
**When** dataType=JSON, value="{not valid json"  
**Then** 400, message="JSON 格式错误"

### CFG-NUMBER-05：数值范围校验
**Given** validation.loss_rate_max 数据类型 NUMBER  
**When** value="abc"  
**Then** 400

### CFG-RESTORE-06：恢复默认值
**When** `POST /system-config/{key}/restore-default`  
**Then** value 重置为 default_value，operation_log 写入

### CFG-AUDIT-07：所有修改记录日志
**When** 任意配置修改  
**Then** operation_log 含 oldValue + newValue + 修改人 + 时间

---

## 19. 锁监控（LOCK）

### LOCK-LIST-01：列表展示活跃锁
**When** `GET /locks/active`（管理员）  
**Then** 返回所有 ACTIVE 状态产品导入锁，含持锁人 / 客户 / 料号 / TTL

### LOCK-RELEASE-02：强制释放写入操作日志
**When** admin 强制释放 → operation_log 1 条，记录 acted_by + targetLockId

### LOCK-DDL-STATUS-03：DDL 锁状态
**When** `GET /locks/ddl-status`  
**Then** 返回 active / lockHolder / expireAt

### LOCK-MUTEX-04：DDL 锁活跃 → 阻止新导入（已在 QIMP-V5-LOCK-11 覆盖）

### LOCK-AUTO-RELEASE-05：超时自动释放
**Given** import.lock_timeout_seconds=300  
**Given** 锁创建已 301 秒，无心跳  
**When** 定时任务  
**Then** 锁状态 ACTIVE → EXPIRED, 释放

### LOCK-HEARTBEAT-06：心跳保活
**Given** import.lock_heartbeat_interval_seconds=30  
**When** 客户端每 30s 调心跳 API  
**Then** 锁 expireAt 顺延

---

## 20. DDL 扩列（DDL）

### DDL-WIZARD-01：完整 4 步流程
**Steps**：
1. 选表 mat_part
2. 字段：name="custom_field_1", type=VARCHAR(100)
3. 重要性 NORMAL, 不影响计算
4. 预览 SQL 显示 `ALTER TABLE mat_part ADD COLUMN custom_field_1 VARCHAR(100)` → 「执行」  
**Then**
- DDL 锁申请成功
- 执行 ALTER TABLE
- 锁释放
- ddl_history 1 条
- 「复制 Migration SQL」按钮可用

### DDL-VALIDATE-02：字段名格式
**When** name="Custom-Field"（含大写或减号）  
**Then** 400, message="字段名仅允许小写字母、数字、下划线"

### DDL-NOT-NULL-03：禁止 NOT NULL
**When** 配置 NOT NULL  
**Then** 400, message="新增列不可设置 NOT NULL，请通过默认值或后置回填"

### DDL-WHITELIST-04：表白名单
**When** 选择非白名单表  
**Then** 400, message="该表不在可扩展白名单内"

### DDL-DUPLICATE-05：同名列禁止
**Given** mat_part 已有 custom_field_1  
**When** 再次添加同名  
**Then** 400, message="列 custom_field_1 已存在"

### DDL-MUTEX-06：执行期间新导入被拒（已在 QIMP-V5-LOCK-11 覆盖）

### DDL-RBAC-07：仅 SYSTEM_ADMIN
**When** SALES_MANAGER 访问 → 403

### DDL-FIELD-IMPORTANCE-08：扩列同时写入字段重要性
**Given** Step 3 选 IMPORTANT + 影响计算=是  
**Then** field_importance 表新增 1 条对应记录

---

## 21. 系统管理（USR / AR / NOTI / OPL）

### USR-CREATE-01：新建用户初始密码
**When** `POST /users { username, role, ... }`  
**Then**
- DB user 1 条，passwordExpireAt = now + 90 天, forceChangePassword=true
- 响应含一次性初始密码（前端 Modal 显示，点确定关闭）

### USR-PATCH-02：仅状态切换
**When** `PATCH /users/{id} { status:"INACTIVE" }`  
**Then** 200, status 更新；其他字段忽略

### USR-RESET-PWD-03：重置密码
**When** admin `POST /users/{id}/reset-password`  
**Then** 返回新初始密码 + forceChangePassword=true

### USR-RBAC-04：非 ADMIN 不可创建用户
**When** bob_mgr `POST /users` → 403

### AR-CREATE-05：FIXED 规则
**When** `POST /approval-rules { type:FIXED, approverIds:[bob_mgr.id] }`  
**Then** 报价单提交时 assignedApprover=bob_mgr

### AR-DYNAMIC-06：动态匹配
**Given** 规则匹配 region=华南 → 华南审批人  
**When** 客户在华南的报价单提交  
**Then** assignedApprover 解析为对应人

### AR-PRIORITY-07：优先级越小越先匹配
**When** 规则 R1(priority=1) 和 R2(priority=10) 都匹配  
**Then** 选 R1

### NOTI-LIST-08：仅本人通知
**When** alice `GET /notifications`  
**Then** 仅返 recipientId=alice 的记录

### NOTI-MARK-ALL-09：标记全部已读
**When** `POST /notifications/mark-all-read`  
**Then** 用户所有未读 notifications.isRead=true, unreadCount=0

### NOTI-UNREAD-COUNT-10：未读数 30s 轮询
**Given** 前端每 30s `GET /notifications/unread-count`  
**Then** 顶部 Badge 实时更新

### OPL-LIST-11：操作日志筛选
**When** `GET /operation-logs?module=QUOTATION&action=APPROVE`  
**Then** 返回审批操作

### OPL-RBAC-12：SALES_REP 不可见
**When** alice 访问菜单 → 不显示「操作日志」入口；直接 GET → 403

---

## 22. 性能（PERF）

| 用例编号 | 场景 | SLA |
|---|---|---|
| PERF-IMPORT-01 | V5 导入预览（5 产品 × 16 sheet） | < 3s |
| PERF-IMPORT-02 | V5 导入确认（事务写入） | < 5s |
| PERF-MATCH-03 | 料号匹配查询 | < 200ms |
| PERF-MAT-IMPORT-04 | 生产料号 5000 行 Excel 导入 | < 30s |
| PERF-EXCEL-RENDER-05 | Excel 视图渲染（50 产品） | < 1s |
| PERF-EXCEL-EXPORT-06 | Excel 导出（50 产品） | < 3s |
| PERF-COSTING-07 | 核价表计算（10 产品 × 50 列） | < 500ms |
| PERF-SYNC-08 | 双向同步延迟 | < 200ms |
| PERF-FORMULA-EVAL-09 | 单次公式求值 | < 10ms |
| PERF-FULL-RECALC-10 | 全表重算 | < 500ms |
| PERF-LIST-11 | 客户列表（1000 条内）| < 500ms |
| PERF-SEARCH-12 | 客户搜索 | < 300ms |
| PERF-CACHE-HIT-13 | datapath 缓存命中率（稳态） | > 0.85 |

---

## 23. 安全 / 权限（SEC）

### SEC-RBAC-01：菜单按角色过滤
**Given** alice (SALES_REP) 登录  
**Then** 左侧菜单**不显示**：配置中心 / 主数据维护 / 变更日志 / 数据源 / 系统管理（系统管理>通知列表除外）

### SEC-RBAC-02：URL 直接访问被禁
**Given** alice 浏览器输入 `/system/users`  
**Then** AuthGuard 重定向 / 提示无权限

### SEC-RBAC-03：API 后端二次校验
**Given** 攻击者直接构造 `POST /users` 请求（绕过前端）  
**Then** 后端 @RolesAllowed 拦截 → 403

### SEC-CSRF-04：CSRF 防护
**Then** 所有写操作（POST/PUT/DELETE）需 CSRF token 或 SameSite Cookie 阻挡跨站请求

### SEC-XSS-05：输入逃逸
**When** 客户名 "<script>alert(1)</script>"  
**Then** 列表页显示为转义文本，**不执行 JS**

### SEC-SQLI-06：SQL 注入
**When** 关键词搜索 `' OR 1=1 --`  
**Then** PreparedStatement 转义；不返回非匹配数据

### SEC-FILE-UPLOAD-07：文件类型白名单
**When** 上传 .exe / .sh 至 Excel 接口  
**Then** 400 拒绝

### SEC-FILE-PATH-08：路径穿越
**When** originalFileName="../../etc/passwd"  
**Then** 后端规范化路径，存储路径仍在 `data/imports/` 内

### SEC-AES-09：API 数据源 headers 加密
**When** DB 直查 datasource.headers  
**Then** 字段为 base64 密文，不可解读

### SEC-PASSWORD-10：密码哈希
**Then** users.password_hash 使用 BCrypt（cost=12+），永不以明文存储

### SEC-RATE-LIMIT-11：登录爆破防护
**When** 同一 IP 1 分钟内失败 ≥ 10 次  
**Then** 该 IP 临时封禁 30 分钟

### SEC-AUDIT-12：所有写操作记录日志
**Then** operation_log 含完整 (用户/时间/IP/UserAgent/Action/Target/旧值/新值)

### SEC-SESSION-13：会话过期
**Given** Session 空闲 > 30 分钟  
**Then** 自动过期，所有 API 返 401

### SEC-CONCURRENT-14：单用户多会话
**Then** 系统**允许**同一用户多设备登录（不强制单点）；除非 PRD 明确单点

---

## 24. UI E2E 关键链路（Playwright）

> 单元 / 集成测试覆盖逻辑，UI E2E 仅验证"剧情完整可走通"。

### E2E-FULL-QUOTE-01：完整报价闭环
**Steps**：
1. alice 登录 → 验证跳 `/dashboard`
2. 进入报价单管理 → 「从基础数据导入」
3. 选客户 C1，上传 valid_5parts.xlsx → 等预览成功
4. UI-2 弹出 → 全部"采纳新值"+ CRITICAL 字段填备注 → 下一步
5. UI-1 弹出 → 全部"采纳新值"→ 「确认导入」
6. CreateQuotationDrawer → 填名称、确认创建
7. 进入向导 Step 2，切换到比对视图，验证毛利率 > 5%
8. Step 3 「自动计算折扣」→ Step 4 → Step 5 「提交审批」
9. 切到 bob_mgr 账号 → 工作台「待我审批」
10. 进入详情 → 「通过」抽屉填意见 → 确认
11. 切回 alice → 详情页 → 「发送给客户」邮件抽屉 → 发送
12. 切到管理员模拟客户接受（或 alice 「接受」按钮）→ 状态 ACCEPTED
**Expect**：每步无报错，最终 customer.accumulated_amount 增加 totalAmount

### E2E-WITHDRAW-02：撤回流程
**Steps**：
1. APPROVED 报价单 alice → 「请求撤回」抽屉填理由
2. bob_mgr → 同意撤回
3. 返回报价单 → 状态 DRAFT，可继续编辑

### E2E-DDL-EXTEND-03：DDL 扩列流程
**Steps**：
1. admin 进入 DDL 扩列 → 4 步向导
2. 执行期间另一窗口尝试 V5 导入 → 应被 423 拒绝
3. 扩列完成后再导入 → 200 正常

### E2E-DRIFT-04：漂移检测
**Steps**：
1. alice 创建 DRAFT Q1 引用 mat_fee v1
2. bob_mgr 触发 V5 导入更新同料号 mat_fee v2
3. alice 进入 Q1 → 顶部黄色横幅"检测到数据漂移"
4. 点击「使用最新版本」→ 横幅消失
5. 提交按钮启用 → 提交成功

### E2E-LOCK-FORCE-RELEASE-05：锁强制释放
**Steps**：
1. alice 启动导入并人为不发送心跳（mock 客户端）
2. 锁状态变 ACTIVE 卡死
3. admin 进入锁监控 → 强制释放
4. operation_log 写入释放记录
5. alice 再次导入 → 不再阻塞

---

## 25. 回归测试清单（每次发版必跑）

| 编号 | 用例 | 频次 |
|---|---|---|
| REG-01 | AUTH-LOGIN-01 + LOGOUT-08 | 每次 |
| REG-02 | E2E-FULL-QUOTE-01 | 每次 |
| REG-03 | E2E-WITHDRAW-02 | 每次 |
| REG-04 | QIMP-V5-CONFIRM-13 + 14 | 每次 |
| REG-05 | CUST-DELETE-05 + ACCUM-07 | 每次 |
| REG-06 | TPL-MATCH-01 ~ 04 | 每次 |
| REG-07 | EP-V1-MANUAL-FILL-09 | 每次 |
| REG-08 | LOCK-MUTEX-04 + DDL-MUTEX-06 | 每次 |
| REG-09 | SEC-RBAC-01 ~ 03 | 每次 |
| REG-10 | PERF-IMPORT-01 ~ 02 | 每次 |
| REG-11 | CL-EXPORT-03（10000 行 422） | 每次 |
| REG-12 | QUOT-SUBMIT-06 + 07（含 ERROR / 漂移阻断提交）| 每次 |

---

## 26. 自动化测试组织建议

### 26.1 后端

```
cpq-backend/src/test/java/com/cpq/
  auth/        # AUTH-*
  customer/    # CUST-*
  product/     # PROD-*, MAT-*, CAT-*
  basicdata/   # BDC-*
  template/    # TPL-*, CTPL-*, BIND-*
  comparisontag/ # TAG-*
  quotation/   # QUOT-*, QAPP-*, QOUT-*
  importexcel/ # QIMP-*
  costing/     # COST-*
  pricing/     # PRC-*
  versioning/  # MD-*
  changelog/   # CL-*
  elementprice/ # EP-*
  systemconfig/ # CFG-*
  lock/        # LOCK-*
  ddl/         # DDL-*
  user/        # USR-*, AR-*, NOTI-*, OPL-*
  perf/        # PERF-*
  security/    # SEC-*
```

测试基础类：

- `BaseQuarkusTest`：启动 Quarkus + testcontainers PostgreSQL，注入 EntityManager
- `BaseRestTest`：基于 RestAssured，提供 `loginAs(role)` 帮助方法
- `Fixtures`：加载 `fix_*` 数据集

### 26.2 前端

```
cpq-frontend/src/__tests__/
  pages/      # 各页面组件单测（Vitest + React Testing Library）
  flows/      # 关键流程组合测试
  e2e/        # Playwright E2E（独立 npm workspace）
```

约定：每个页面测试至少覆盖：

1. 渲染（角色 / 状态 / 空数据）
2. 主按钮点击触发正确 API
3. 表单字段必填校验
4. 抽屉打开 / 关闭

### 26.3 CI 集成

| 阶段 | 命令 | 失败阻断 |
|---|---|---|
| Lint + 类型 | `npm run lint && tsc --noEmit && mvn checkstyle` | 是 |
| 单元测试 | `mvn test` + `npm run test:unit` | 是 |
| 集成测试 | `mvn verify -P integration` | 是 |
| E2E（仅 main / release） | `npm run test:e2e` | 是 |
| 性能 | `mvn verify -P perf`（每周一次或发布前） | 否（仅告警）|

---

## 27. 缺陷记录模板

```
[BUG-编号] <模块> - <一句话描述>

环境：Quarkus 3.34 + Postgres 16 + Frontend 18.x（commit <sha>）
角色：<触发用户角色>
前置条件：<准备步骤>
重现步骤：
  1. ...
  2. ...
实际结果：<现象>
预期结果：<参照 PRD/UI-FLOW 对应章节>
影响范围：<阻塞 / 主要 / 次要 / 优化>
关联用例：<TDD 用例编号，如 QUOT-SUBMIT-06>
```

---

## 28. 变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-04-29 | v1.0 | 初版：覆盖 28 章 / 350+ 用例 |

---

**文档结束** | 与 `docs/UI-FLOW.md`、`docs/API.md`、`docs/PRD.md` 配套。变更需同步更新 PRD 与本文档版本号。
