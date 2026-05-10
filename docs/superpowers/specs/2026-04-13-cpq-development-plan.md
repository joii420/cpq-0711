# CPQ 报价系统 - 完整开发计划

> **文档版本**: v1.0
> **编写日期**: 2026-04-13
> **PRD 版本**: v2.0 (2026-04-11)
> **开发模式**: 单人开发 + Claude Code AI 辅助
> **方法论**: 全栈垂直切片 — 每个模块完成「后端 API → 数据库迁移 → 前端页面 → 集成测试」的完整闭环

---

## 一、总体概览

### 1.1 技术栈

| 层级 | 选型 | 版本 |
|------|------|------|
| 前端框架 | React + TypeScript | React 18 |
| 前端构建 | Vite | 最新 |
| UI 组件库 | Ant Design | 5.x |
| 状态管理 | Zustand | 最新 |
| 后端框架 | Java + Quarkus | Java 17 + Quarkus 3.23.3 |
| 数据库 | PostgreSQL | 16 |
| 规则引擎 | Drools | 7.74.x |
| 公式引擎 | Apache Commons JEXL | 最新 |
| PDF 导出 | Quarkus Qute | 内置 |
| Excel 导入导出 | Apache POI | 最新 |
| 邮件 | Quarkus Mailer | 内置 |
| 定时任务 | Quarkus Scheduler | 内置 |

### 1.2 里程碑规划

| 阶段 | 名称 | 预估周数 | 周次 | 核心交付 |
|------|------|---------|------|----------|
| **M0** | 项目启动 | 1周 | 第1周 | Quarkus + React 项目骨架、DB 设计、CI、开发规范 |
| **M1** | 账号安全基础 | 2周 | 第2-3周 | User/Region/Department CRUD、登录认证、角色权限、改密/重置 |
| **M2** | 主数据管理 | 3周 | 第4-6周 | 客户管理（含联系人）、产品管理（含Excel导入）、工序种子数据 |
| **M2b** | 数据源管理 | 2周 | 第6-7周 | DataSource CRUD、参数配置、SQL/API执行、测试面板、权限隔离 |
| **M3** | 配置中心 | 5周 | 第8-12周 | 组件管理、模板配置（拖拽）、工序选型、产品-模板关联、版本比对 |
| **M4a** | 计算引擎 | 2周 | 第12-13周 | Drools 折扣+审批引擎、JEXL 公式引擎、前端 JS 等价实现、测试套件 |
| **M4b** | 定价与报价 | 6周 | 第14-19周 | 定价策略、报价生成器5步、审批流程、报价单管理、站内通知、复制功能 |
| **M5** | 报价输出 | 2周 | 第19-20周 | 详情页、PDF/Excel导出、邮件发送、延期、打印 |
| **M6** | 集成测试与优化 | 2周 | 第21-22周 | E2E测试、性能测试、安全检查、Bug修复 |
| **M7** | UAT与上线 | 1-2周 | 第23-24周 | 用户验收、部署、数据初始化 |
| | **合计** | **~28-30周** | | |

### 1.3 模块依赖链

```
M0 → M1(账号) → M2(主数据) → M2b(数据源) → M3(配置中心) → M4a(引擎) → M4b(报价业务) → M5(输出) → M6 → M7
                                    ↑                              ↑
                               M3组件的DATA_SOURCE              M4b折扣/审批
                               字段依赖数据源模块               依赖引擎模块
```

---

## 二、M0 项目启动（第1周）

**目标**：搭建可运行的全栈开发骨架，后续所有模块在此基础上增量开发。

### 任务清单

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 0.1 | Quarkus 项目初始化 | 后端 | `mvn quarkus:dev` 启动成功，health endpoint 返回 200 |
| 0.2 | 引入核心依赖 | 后端 | RESTEasy Reactive、Hibernate ORM Panache、Flyway、Quarkus Security、Quarkus Mailer、Quarkus Scheduler、Jackson JSONB 全部在 pom.xml 中声明且编译通过 |
| 0.3 | PostgreSQL + Flyway 初始迁移 | 后端 | V1__init.sql 创建基础表（User、Region、Department、OperationLog、PasswordResetToken、Notification），`flyway migrate` 成功 |
| 0.4 | 分层架构搭建 | 后端 | 建立 `resource`→`service`→`repository` 三层结构，按业务模块分包（customer、product、quotation、template、component、pricing、approval、notification、datasource、system）；统一异常处理（ExceptionMapper）；统一 API 响应包装 |
| 0.5 | API 规范建立 | 后端 | 路径前缀 `/api/cpq/`，RESTful 命名，分页参数统一（page/size/sort），错误响应格式统一（code/message/details） |
| 0.6 | React 项目初始化 | 前端 | Vite + React 18 + TypeScript，`npm run dev` 启动成功，代理到 Quarkus 后端 |
| 0.7 | 前端基础设施 | 前端 | 路由（React Router v6）、状态管理（Zustand）、HTTP 客户端（axios + 拦截器）、UI 组件库（Ant Design 5.x）、布局组件（侧边栏+主内容区） |
| 0.8 | 开发环境 Docker Compose | DevOps | `docker-compose up` 一键启动 PostgreSQL 16（应用本地 dev 模式跑） |
| 0.9 | 前后端联调验证 | 全栈 | 前端请求 `/api/cpq/health` 拿到后端响应并展示，cookie 携带正常 |

### 技术决策

- **UI 组件库 Ant Design 5.x**：Table/Form/Drawer/Steps/Tabs/Tree 齐全，中文开箱即用
- **状态管理 Zustand**：轻量无 boilerplate，单人项目快速迭代
- **纯 SPA**：无 SEO 需求，Vite 开发体验最佳
- **暂不引入 Drools/JEXL**：M0 只搭骨架，引擎 M4a 单独引入

### 验收检查点

- [ ] `mvn quarkus:dev` 启动无报错，health 返回 200
- [ ] `npm run dev` 启动无报错，页面加载正常
- [ ] 前端调用后端 API 成功返回，代理配置正常
- [ ] Flyway 迁移执行成功，基础表已创建
- [ ] Docker Compose 启动 PostgreSQL 正常

---

## 三、M1 账号安全基础（第2-3周）

**目标**：完成用户体系和认证授权，所有后续模块的 API 都依赖此处的权限中间件。

### 任务清单

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 1.1 | Flyway 迁移：User/Region/Department 表 | 后端 | DDL 执行成功，含索引和约束；Region/Department 预置种子数据（华南/华东/华北/华中 + 销售一部/二部/三部） |
| 1.2 | 区域/部门字典 CRUD API | 后端 | `GET/POST/PUT/PATCH` /api/cpq/regions、/api/cpq/departments；停用保护（关联 ACTIVE 用户时阻止）；单元测试覆盖 |
| 1.3 | 区域/部门管理前端 | 前端 | 系统管理 → 区域管理 / 部门管理页面；Table + 新增/编辑 Modal + 停用确认弹窗 |
| 1.4 | 用户管理 CRUD API | 后端 | `GET/POST/PUT/PATCH` /api/cpq/users；创建用户时自动生成初始密码、写入 is_first_login=true 和 initial_password_expires_at；角色单选（四角色枚举）；region_id/department_id 引用字典表；最后管理员保护 |
| 1.5 | 用户管理前端 | 前端 | 系统管理 → 用户管理页面；Table（角色/状态筛选、搜索）+ 新增/编辑 Drawer（角色下拉、区域/部门下拉）+ 停用确认 + 重置密码按钮 |
| 1.6 | 登录认证 API | 后端 | `POST /api/cpq/auth/login`（Session + HttpOnly Cookie）；`POST /api/cpq/auth/logout`（销毁 Session）；`GET /api/cpq/auth/me`（当前用户信息）；登录失败 5 次锁定 30 分钟；Session 有效期 8 小时 |
| 1.7 | 角色权限中间件 | 后端 | 自定义 `@RolesAllowed` 拦截器或 Quarkus Security 注解；按 PRD 1.4 权限矩阵校验；未登录返回 401，无权限返回 403；角色变更即时生效（请求级校验） |
| 1.8 | 首次登录强制改密 | 后端 | 登录响应含 `force_change_password` 标志；`POST /api/cpq/auth/change-password`（验证旧密码 + 复杂度校验：≥8位，含字母和数字）；成功后 is_first_login=false；初始密码超 24 小时拒绝登录 |
| 1.9 | 忘记密码/重置 | 后端 | `POST /api/cpq/auth/forgot-password`（发送重置邮件，token SHA-256 哈希存储）；`POST /api/cpq/auth/reset-password`（token 验证 + 一次性失效）；同一用户新申请时旧 token 同事务内失效 |
| 1.10 | 登录页前端 | 前端 | 登录表单（用户名/邮箱 + 密码）；登录失败提示（含锁定提示）；"忘记密码"链接 → 邮箱输入 → 发送成功提示 |
| 1.11 | 改密页 + 重置密码页前端 | 前端 | 首次登录强制跳转改密页（不可跳过）；右上角用户菜单 → 修改密码入口；重置密码页（URL 含 token） |
| 1.12 | 操作日志基础设施 | 后端 | OperationLog 写入工具类/AOP 切面；`GET /api/cpq/operation-logs`（筛选：时间范围/操作人/操作类型）；本阶段记录：用户创建/角色变更/密码重置 |
| 1.13 | 登录限流 | 后端 | 同一 IP 每分钟最多 20 次登录请求，超出返回 429 |
| 1.14 | 全局路由守卫 + 布局 | 前端 | 未登录重定向到登录页；已登录展示侧边栏布局（菜单按角色动态渲染）；Session 过期自动跳回登录页 |

### 执行顺序

```
1.1(DDL) → 1.2(字典API) → 1.3(字典前端)
                ↓
         1.4(用户API) → 1.5(用户前端)
                ↓
    1.6(登录API) + 1.7(权限中间件) → 1.8(改密) + 1.9(重置) + 1.13(限流)
                ↓
    1.10(登录页) + 1.11(改密页) → 1.14(路由守卫+布局)
                ↓
         1.12(操作日志)
```

### 验收检查点

- [ ] 以系统管理员登录，创建四种角色的用户
- [ ] 各角色登录后看到不同的菜单（按权限矩阵）
- [ ] 新用户首次登录被强制改密
- [ ] 忘记密码流程走通（邮件 → 重置链接 → 新密码）
- [ ] 错误登录 5 次后账号锁定 30 分钟
- [ ] 停用用户无法登录
- [ ] 区域/部门字典可维护，停用保护生效

---

## 四、M2 主数据管理（第4-6周）

**目标**：完成客户、产品、工序三大主数据模块，为后续配置中心和报价流程提供数据基础。

### 任务清单

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 2.1 | Flyway 迁移：Customer/CustomerContact 表 | 后端 | DDL 含客户编码自动生成逻辑（触发器或 Service 层）；CustomerContact 的 is_primary 约束（至少一个主要联系人） |
| 2.2 | 客户管理 CRUD API | 后端 | `GET/POST/PUT/DELETE` /api/cpq/customers；模糊搜索（名称/编码/联系人）；等级筛选；分页；软删除（ACTIVE→INACTIVE）；删除保护（检查关联 DRAFT/SUBMITTED/APPROVED 报价单）；乐观锁（version 字段）；客户编码自动生成 |
| 2.3 | 联系人管理 API | 后端 | `/api/cpq/customers/{id}/contacts` CRUD；主要联系人切换；不可删除最后一个主要联系人；手机号格式校验（11位） |
| 2.4 | 客户管理前端 | 前端 | 列表页：分类标签筛选（全部/活跃/VIP/潜在/不活跃）、搜索、分页、批量删除、等级徽章卡片；详情/编辑 Drawer：基本信息 + 联系人管理（列表/添加/编辑/删除/主要联系人标记）+ 商务信息 + 统计面板（只读） |
| 2.5 | Flyway 迁移：Product 表 | 后端 | DDL 含 SKU 唯一约束、tags JSONB、external_id/last_synced_at 预留字段（默认 NULL） |
| 2.6 | 产品管理 CRUD API | 后端 | `GET/POST/PUT/DELETE` /api/cpq/products；品类筛选；SKU/名称搜索；状态筛选；分页；软删除（ACTIVE→INACTIVE）；删除保护（检查关联进行中报价单） |
| 2.7 | 产品 Excel 导入 API | 后端 | `POST /api/cpq/products/import`（multipart）；Apache POI 解析；最大 5000 条/次；返回导入结果报告（新增/失败/跳过条数）；SKU 重复时跳过并记录 |
| 2.8 | 产品管理前端 | 前端 | 列表页：品类筛选、搜索、状态筛选、分页；新增/编辑 Drawer：名称/SKU/品类/规格/状态/工艺标签（多选 Tag）/备注；Excel 导入按钮 + 上传弹窗 + 结果报告展示 |
| 2.9 | Flyway 迁移：Process/ProductProcess 表 | 后端 | DDL + 种子数据脚本（6 大类工序，每类 3-8 个工序，共约 30 条预置数据，编码格式 MRO-XX-XXXX） |
| 2.10 | 工序管理 API | 后端 | `GET /api/cpq/processes`（按分类返回，支持分类筛选）；`GET/POST/DELETE /api/cpq/products/{id}/processes`（产品工序绑定）；工序本身不可增删改（技术预置） |
| 2.11 | 工序选型配置前端（模块四） | 前端 | 产品工序选型页面：左侧工序分类侧边栏（6 大类）；中间分类网格卡片（工序名称/编码/描述/必选标记/复选框）；右侧已选工序面板（分类统计/移除/保存/重置）；必选工序禁用取消 |
| 2.12 | 主数据操作日志集成 | 后端 | 客户/产品的创建/编辑/删除操作写入 OperationLog |

### 执行顺序

```
2.1(Customer DDL) → 2.2(客户API) + 2.3(联系人API) → 2.4(客户前端)
       ↓
2.5(Product DDL) → 2.6(产品API) + 2.7(Excel导入) → 2.8(产品前端)
       ↓
2.9(Process DDL+种子) → 2.10(工序API) → 2.11(工序选型前端)
       ↓
     2.12(操作日志集成)
```

### 验收检查点

- [ ] 创建客户，配置多个联系人并标记主要联系人
- [ ] 按等级/关键词搜索筛选客户列表，批量删除生效
- [ ] 客户统计面板显示（累计金额此时为 0）
- [ ] 创建产品，Excel 批量导入产品（≤5000条），导入结果报告正确
- [ ] 产品列表品类筛选、状态筛选正常
- [ ] 进入工序选型页面，为产品勾选可用工序，必选工序不可取消
- [ ] 产品删除时若有进行中报价单则阻止（逻辑存在即可）

---

## 五、M2b 数据源管理（第6-7周）

**目标**：完成数据源配置基础设施，为 M3 组件管理中的 DATA_SOURCE 字段类型提供支撑。

### 任务清单

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 2b.1 | Flyway 迁移：DataSource/DataSourceParam 表 | 后端 | DDL 含 code 唯一约束、type 枚举、param_order 排序；(datasource_id, param_code) 唯一约束 |
| 2b.2 | 只读数据库连接配置 | 后端 | `quarkus.datasource.datasource-readonly.*` 独立连接池；专用只读用户仅 SELECT 权限；仅授权业务数据表，禁止访问系统表 |
| 2b.3 | 数据源 CRUD API | 后端 | `GET/POST/PUT/DELETE` /api/cpq/datasources；按类型筛选（SQL/API）；关键词搜索（编号/名称）；分页；删除保护（检查 Component.fields 中 datasource_binding 引用）；code/type 创建后不可修改 |
| 2b.4 | 数据源参数管理 API | 后端 | 嵌套在数据源 CRUD 中（创建/更新数据源时同步管理参数列表）；系统参数枚举校验（SYS_CUSTOMER_ID/SYS_CUSTOMER_LEVEL/SYS_CUSTOMER_REGION/SYS_QUOTE_DATE/SYS_SALES_REP_ID）；param_order 自动维护 |
| 2b.5 | API 认证信息 AES 加密 | 后端 | api_headers 中敏感值 AES-256 加密存储；加密密钥通过 `quarkus.cpq.encryption-key` 配置注入；读取时解密；API 响应中脱敏（****） |
| 2b.6 | SQL 数据源执行服务 | 后端 | 使用 datasource-readonly 连接执行；PreparedStatement 参数化查询；仅允许 SELECT（正则校验，禁止分号）；10 秒超时强制中断；取第一行 sql_result_column 列值返回 |
| 2b.7 | API 数据源执行服务 | 后端 | HTTP 客户端执行；URL/请求体中 `{param_code}` 占位符替换；请求头解密后携带；JSONPath 提取返回值；超时由 api_timeout_seconds 控制；仅允许 HTTPS |
| 2b.8 | 数据源测试接口 | 后端 | `POST /api/cpq/datasources/{id}/test`（接收测试参数值 Map）；返回：原始返回数据、提取到的目标值、执行耗时 |
| 2b.9 | 数据源管理前端 - 列表页 | 前端 | Table：编号/名称/类型/参数数量/状态/创建时间；类型筛选、搜索；每行操作：编辑/测试/删除 |
| 2b.10 | 数据源管理前端 - 编辑页 | 前端 | 三区域表单：① 基本信息（编号/名称/类型/状态/描述）② 查询配置（SQL：SQL 编辑器+返回栏位；API：URL/方法/请求头/请求体/返回路径/超时）③ 参数配置表格（拖拽排序/编码/名称/来源类型/系统参数下拉/必填/说明） |
| 2b.11 | 数据源管理前端 - 测试面板 | 前端 | 编辑页内嵌测试区域：参数列表+测试值输入框；执行测试按钮；结果展示：原始返回（折叠）+提取值（高亮）+耗时 |
| 2b.12 | 数据源操作日志集成 | 后端 | 新增/编辑/删除操作写入 OperationLog，target_type=DataSource |

### 执行顺序

```
2b.1(DDL) → 2b.2(只读连接) → 2b.3(CRUD API) + 2b.4(参数API) + 2b.5(AES加密)
                                        ↓
                              2b.6(SQL执行) + 2b.7(API执行) → 2b.8(测试接口)
                                        ↓
                     2b.9(列表前端) → 2b.10(编辑前端) → 2b.11(测试面板)
                                        ↓
                                   2b.12(操作日志)
```

### 验收检查点

- [ ] 创建 SQL 类型数据源，配置用户字段引用和系统参数两种参数
- [ ] 创建 API 类型数据源，请求头 Authorization 值加密存储、界面脱敏
- [ ] SQL 数据源测试：输入测试参数值，返回正确查询结果
- [ ] API 数据源测试：调用外部接口，JSONPath 提取目标值正确
- [ ] SQL 安全：`DELETE` 语句或含分号语句被拒绝
- [ ] 只读连接：通过数据源 SQL 查 User 表被权限拒绝
- [ ] 删除数据源时若被组件引用则阻止（逻辑存在即可）

---

## 六、M3 配置中心（第8-12周）

**目标**：完成组件管理、模板配置、产品-模板关联、版本比对。前端复杂度最高的阶段（拖拽交互密集）。

### 子阶段 3A：组件管理（第8-9周）

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 3.1 | Flyway 迁移：ComponentDirectory/Component 表 | 后端 | DDL 含 JSONB 字段（fields/formulas）；code 唯一约束；directory 自引用外键 |
| 3.2 | 组件目录树 API | 后端 | `GET/POST/PUT/DELETE` /api/cpq/component-directories；支持嵌套目录；sort_order 排序；删除时检查子目录或组件 |
| 3.3 | 组件 CRUD API | 后端 | `GET/POST/PUT/DELETE` /api/cpq/components；fields JSONB 校验（字段名唯一、field_type 枚举、每组件最多一个 is_subtotal=true）；column_count 自动计算；formulas 与 FORMULA 字段 name 一致性校验；删除前检查 TemplateComponent 引用 |
| 3.4 | 公式循环引用检测 | 后端 | 保存组件时对 expression 中的跨组件引用执行 DFS 有向图遍历，检测环路，有则返回 400 |
| 3.5 | DATA_SOURCE 字段校验 | 后端 | field_type=DATA_SOURCE 时校验 datasource_binding 完整性：datasource_id 存在且 ACTIVE；param_bindings 参数在数据源中存在；bound_field_name 在本组件中存在且非 FORMULA |
| 3.6 | 组件管理前端 - 目录树 | 前端 | 左侧 Tree 组件：新建目录/组件、展开/折叠、编辑/删除右键菜单、面包屑导航 |
| 3.7 | 组件管理前端 - 字段配置 | 前端 | 中部表格：每行一个字段（拖拽排序、名称、类型下拉、内容/来源、金额 checkbox、小计 checkbox、说明）；添加/删除行；表头预览实时同步；列数徽章 |
| 3.8 | 组件管理前端 - DATA_SOURCE 配置弹窗 | 前端 | 两步 Modal：步骤一选数据源（搜索+参数预览）→ 步骤二绑定用户字段参数（下拉选本组件非 FORMULA 字段）；全部绑定后启用确认 |
| 3.9 | 组件管理前端 - 公式构建器 | 前端 | 运算符工具栏（可拖拽）；公式表格（名称+拖拽式表达式+结果类型）；右侧面板：本组件字段（蓝色芯片）+其他组件小计（橙色芯片）；芯片可移除 |

### 子阶段 3B：模板配置（第10-11周）

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 3.10 | Flyway 迁移：Template/TemplateComponent 表 | 后端 | DDL 含 template_series_id、version、JSONB 字段、status 枚举 |
| 3.11 | 模板 CRUD API | 后端 | `GET/POST/PUT/DELETE` /api/cpq/templates；按 category/status 筛选；template_series_id 自动/继承 |
| 3.12 | 模板发布 API | 后端 | `POST /api/cpq/templates/{id}/publish`；发布前校验（subtotal_formula 非空+至少一个组件）；写入 components_snapshot；状态→PUBLISHED；版本号自动递增 |
| 3.13 | 模板归档 API | 后端 | `POST /api/cpq/templates/{id}/archive`；归档检查（产品仅绑定该版本→警告）；进行中报价单使用→禁止；support force=true |
| 3.14 | 创建新草稿 API | 后端 | `POST /api/cpq/templates/{id}/new-draft`；复制新 DRAFT 记录；继承 template_series_id |
| 3.15 | TemplateComponent 管理 API | 后端 | `GET/POST/DELETE` /api/cpq/templates/{id}/components；仅 DRAFT 可操作 |
| 3.16 | 模板配置前端 - 拖拽画布 | 前端 | 左侧组件面板（dnd-kit 拖拽）；中间画布：产品属性区（3列网格动态字段）+页签拖放区→标签页渲染组件表格；标签页可删除；添加行 |
| 3.17 | 模板配置前端 - 小计公式 | 前端 | 底部渐变色条；拖拽公式构建器（component_subtotal + product_attribute）；复用公式交互组件 |
| 3.18 | 模板配置前端 - 右侧面板 | 前端 | 基本信息+发布/归档按钮+版本历史列表+"创建新草稿"按钮 |
| 3.19 | 模板预览模式 | 前端 | 详细视图/简易视图切换；展开/收缩页签 |
| 3.20 | 模板自动保存 | 前端 | 每 30 秒自动保存 DRAFT；保存指示器 |

### 子阶段 3C：产品-模板关联 + 版本比对（第11-12周）

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 3.21 | Flyway 迁移：ProductTemplateBinding 表 | 后端 | DDL 含 process_ids JSONB、process_ids_hash、部分唯一索引 `WHERE is_default = true` |
| 3.22 | 产品-模板关联 API | 后端 | `GET/POST/PUT/DELETE` /api/cpq/products/{id}/template-bindings；process_ids_hash 后端自动计算；默认模板切换；仅绑定 PUBLISHED 模板 |
| 3.23 | 产品选型与模板关联前端 | 前端 | 左侧产品库侧边栏；右侧三标签页：工序选择/工序组合与模板绑定（三步弹窗）/模板版本查看 |
| 3.24 | 模板版本比对 API | 后端 | `POST /api/cpq/templates/compare`；从 components_snapshot 读取；返回差异结构+统计 |
| 3.25 | 模板版本比对前端 | 前端 | 版本选择下拉；概览/详情切换；差异统计面板；并排对比区域 |

### 执行顺序

```
子阶段 3A:
3.1 → 3.2 → 3.3 + 3.4 + 3.5 → 3.6 → 3.7 → 3.8 → 3.9

子阶段 3B:
3.10 → 3.11 + 3.12 + 3.13 + 3.14 + 3.15 → 3.16 → 3.17 → 3.18 → 3.19 → 3.20

子阶段 3C:
3.21 → 3.22 → 3.23
3.24 → 3.25
```

### 验收检查点

- [ ] 创建组件目录和组件（投料组件：材料编码/固定值 + 材料单价/数据源 + 使用数量/输入框 + 小计/公式）
- [ ] DATA_SOURCE 字段配置弹窗两步流程走通
- [ ] 公式构建器拖拽流畅，跨组件引用正常，循环引用被阻止
- [ ] 创建模板，拖入组件形成标签页，配置产品属性和小计公式
- [ ] 发布模板：components_snapshot 正确，版本号 v1.0→v1.1 递增
- [ ] 创建新草稿、归档检查提示正确
- [ ] 产品+工序组合绑定模板，设置默认模板
- [ ] 版本比对差异高亮展示，统计面板正确

---

## 七、M4a 计算引擎技术 Sprint（第12-13周）

**目标**：集成三大计算引擎，通过完整测试套件验证。技术风险最高的阶段，全部验收通过后方可进入 M4b。

### 任务清单

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 4a.1 | Drools 7.74.x 依赖引入与环境配置 | 后端 | drools-core + drools-compiler + kie-api 三件套；启动参数 `--add-opens`；`mvn quarkus:dev` 无报错 |
| 4a.2 | KieBase 管理服务 | 后端 | KieServices→KieFileSystem→KieBuilder 动态编译链路；KieBase 缓存（ConcurrentHashMap）；缓存失效（updated_at 比对）；冷启动 < 500ms |
| 4a.3 | 折扣计算引擎 | 后端 | `DiscountCalculationService`（@Blocking）；动态 DRL；salience=-priority；多策略优先级匹配；无策略兜底=100；输入：客户ID+总价，输出：折扣率+规则名 |
| 4a.4 | 审批路由引擎 | 后端 | `ApprovalRoutingService`（@Blocking）；动态 DRL；FIXED salience=-priority+1000；DYNAMIC 按 REGION/DEPARTMENT 匹配；兜底→最早 ACTIVE 系统管理员 |
| 4a.5 | Drools 降级开关 | 后端 | `cpq.engine.drools.enabled` feature flag；false 时切换纯 Java 实现，接口不变 |
| 4a.6 | JEXL 公式引擎（后端） | 后端 | `FormulaCalculationService`（@Blocking）；组件行级+产品小计两级计算；跨组件默认值 0；容差 ±0.01 |
| 4a.7 | 前端 JS 公式引擎 | 前端 | `formulaEngine.ts`；与 JEXL 等价；decimal.js 精度处理；两级计算 |
| 4a.8 | 前后端公式一致性校验接口 | 后端 | `POST /api/cpq/formulas/validate`；容差内覆盖存储；超出返回 400+差异明细 |
| 4a.9 | 折扣引擎单元测试 | 测试 | 9 场景全覆盖：无策略/基础折扣/单条批量/多条批量取最优/低于门槛/多策略排序/全过期/缓存命中/缓存失效 |
| 4a.10 | 审批路由单元测试 | 测试 | 7 场景全覆盖：FIXED/DYNAMIC REGION/DYNAMIC DEPARTMENT/同priority FIXED优先/多规则排序/无匹配兜底/已提交不受规则变更影响 |
| 4a.11 | 公式引擎单元测试 | 测试 | 8 场景全覆盖：四则运算/括号/跨组件引用/目标未填默认0/循环引用/产品属性引用/容差边界/容差超出 |
| 4a.12 | 前后端公式一致性测试 | 测试 | 随机 1000 组输入，JS 与 JEXL 差异全部 ≤ ±0.01 |
| 4a.13 | @Blocking 线程模型验证 | 测试 | REST 接口触发三引擎，日志无 IO 线程阻塞警告 |
| 4a.14 | 性能基准测试 | 测试 | KieBase 冷启动 < 500ms；单次执行 < 50ms；100 并发步骤三 p95 < 200ms |
| 4a.15 | 降级开关验证 | 测试 | flag=false 时折扣+审批走纯 Java，结果与 Drools 等价 |

### 执行顺序

```
4a.1(依赖) → 4a.2(KieBase管理)
      ↓
4a.3(折扣) + 4a.4(审批) → 4a.5(降级)
      ↓
4a.6(JEXL后端) → 4a.8(一致性接口)
      ↓
4a.7(JS前端)
      ↓
4a.9 ~ 4a.15（测试套件，全部通过方可进入 M4b）
```

### 验收标准（必须全部通过）

| 标准 | 对应任务 | 验证方式 |
|------|----------|----------|
| 三引擎单元测试 100% 通过 | 4a.9/4a.10/4a.11 | `mvn test` 全绿 |
| 折扣计算集成测试 | 4a.9 ⑧⑨ | 缓存命中/失效日志 |
| 审批路由集成测试 | 4a.10 ⑦ | assigned_approver_id 正确 |
| 前后端一致性 1000 组 | 4a.12 | 自动化全通过 |
| @Blocking 验证 | 4a.13 | 无 IO 线程警告 |
| 性能基准 | 4a.14 | 冷启动<500ms，单次<50ms，p95<200ms |
| 降级开关 | 4a.15 | flag=false 结果等价 |

---

## 八、M4b 定价与报价业务（第14-19周）

**目标**：完成定价策略、报价生成器5步、审批流程、报价单管理、站内通知。功能量最大的里程碑。

### 子阶段 4b-A：定价策略管理（第14周）

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 4b.1 | Flyway 迁移：PricingStrategy/PricingRule 表 | 后端 | DDL 含 priority、status 枚举、折扣率 0-100 约束 |
| 4b.2 | 定价策略 CRUD API | 后端 | `GET/POST/PUT/DELETE` /api/cpq/pricing-strategies；按客户筛选；含 PricingRule 嵌套 CRUD；策略变更清除 KieBase 缓存 |
| 4b.3 | 定价策略导入导出 API | 后端 | Excel 导出/导入（≤1000条）；导入结果报告 |
| 4b.4 | 定价策略前端 | 前端 | 左侧客户列表+右侧策略配置区：策略卡片+批量折扣规则表格+多策略提示+导入导出 |
| 4b.5 | 定价策略操作日志 | 后端 | 创建/编辑/删除/导入写入 OperationLog |

### 子阶段 4b-B：报价生成器（第15-17周）

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 4b.6 | Flyway 迁移：Quotation 系列表 | 后端 | Quotation、QuotationLineItem、QuotationLineProcess、QuotationLineComponentData、QuotationLineItemSnapshot、QuotationApproval 六表 DDL；SEQUENCE |
| 4b.7 | 报价单 CRUD API | 后端 | `GET/POST/PUT/DELETE` /api/cpq/quotations；编号自动生成（QT-YYYYMMDD-XXXX）；权限过滤 |
| 4b.8 | 草稿保存 API | 后端 | `PUT /api/cpq/quotations/{id}/draft`；最后写入胜出；联系人快照 DRAFT 阶段实时更新 |
| 4b.9 | 步骤一前端：客户选择 | 前端 | 客户搜索下拉+新建入口+画像卡片+联系人下拉+元数据表单+步骤进度条 |
| 4b.10 | 步骤二前端：三步弹窗 | 前端 | 产品选择→工序选择→模板选择（精确匹配 ProductTemplateBinding）；异常处理三种提示 |
| 4b.11 | 步骤二前端：产品卡片渲染 | 前端 | 产品 Tabs；product_attributes 动态渲染；页签组件表格（INPUT/FIXED_VALUE/FORMULA/DATA_SOURCE）；小计实时计算 |
| 4b.12 | 步骤二前端：DATA_SOURCE 交互 | 前端 | 300ms 防抖→必填检查→查询→填值；5分钟缓存；失败红色提示；未查询红框 |
| 4b.13 | 数据源查询 API | 后端 | `POST /api/cpq/datasources/{id}/execute`；系统参数自动注入 |
| 4b.14 | 步骤二前端：模板切换+行操作 | 前端 | 切换确认弹窗；超20行提示；展开/折叠 |
| 4b.15 | 草稿自动保存前端 | 前端 | 10秒自动保存+localStorage降级；保存指示器 |
| 4b.16 | 步骤三前端：价格策略应用 | 前端 | 读前端状态总价→调折扣API→展示明细→手动调整+原因；返回步骤二后清空手动折扣 |
| 4b.17 | 折扣计算 API | 后端 | `POST /api/cpq/quotations/{id}/calculate-discount`；调 Drools；返回折扣率+规则详情 |
| 4b.18 | 步骤四前端：商务条款 | 前端 | 付款方式+交货周期 |
| 4b.19 | 步骤五前端：提交审批 | 前端 | 完整预览+提交前校验（必填/至少一产品/小计>0/DS已查询）+提交按钮 |
| 4b.20 | 提交审批 API（原子事务） | 后端 | `POST /api/cpq/quotations/{id}/submit`；单一事务：客户快照→产品快照→JEXL校验→审批路由→状态变更；通知异步 |

### 子阶段 4b-C：审批流程 + 报价单管理（第17-18周）

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 4b.21 | Flyway 迁移：ApprovalRule 表 | 后端 | DDL 含 rule_type/match_field/priority/match_value_id |
| 4b.22 | 审批规则配置 API | 后端 | `GET/POST/PUT/DELETE` /api/cpq/approval-rules；FIXED/DYNAMIC；规则变更清除 KieBase 缓存 |
| 4b.23 | 审批操作 API | 后端 | `POST .../approve` + `POST .../reject`（comment 必填）；权限：assigned_approver_id 或系统管理员 |
| 4b.24 | 审批规则配置前端 | 前端 | 规则列表+新增Modal（FIXED/DYNAMIC联动）+优先级排序+兜底审批人提示 |
| 4b.25 | 报价单管理列表前端 | 前端 | 统一列表（角色动态标签/搜索/筛选/排序/分页/行操作按状态+权限） |
| 4b.26 | 销售经理审批待办区前端 | 前端 | "待我审批"角标+快捷通过/退回+退回弹窗 |
| 4b.27 | 操作日志前端 | 前端 | 日志 Table+筛选+导出 Excel |

### 子阶段 4b-D：站内通知 + 复制 + 定时任务（第18-19周）

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 4b.28 | 站内通知 API | 后端 | 通知列表/未读数量(<100ms)/标记已读/全部已读；(recipient_id, is_read) 索引 |
| 4b.29 | 通知触发服务 | 后端 | 写入 Notification（同步）+异步邮件；6种场景全覆盖 |
| 4b.30 | 站内通知前端 | 前端 | 铃铛+红色角标（30秒轮询）+下拉面板+标记已读+跳转+完整列表页 |
| 4b.31 | 审批通知集成 | 后端 | submit/approve/reject 事务提交后触发通知 |
| 4b.32 | 报价单复制 API | 后端 | `POST .../copy`；全量复制+DATA_SOURCE 清空+重置编号/日期/状态/审批 |
| 4b.33 | 报价单复制前端 | 前端 | 复制按钮+跳转新草稿+"来源"标签+归档模板标记 |
| 4b.34 | 定时任务实现 | 后端 | 5个 @Scheduled+@Blocking 任务：过期标记/策略过期/催办/令牌清理/通知清理；结构化日志+幂等 |
| 4b.35 | M1 通知补全 | 后端 | 回补密码重置和角色变更的通知触发 |

### 执行顺序

```
4b-A: 4b.1 → 4b.2+4b.3 → 4b.4 → 4b.5

4b-B: 4b.6 → 4b.7+4b.8+4b.13+4b.17
      → 4b.9 → 4b.10 → 4b.11+4b.12+4b.14 → 4b.15 → 4b.16 → 4b.18 → 4b.19 → 4b.20

4b-C: 4b.21 → 4b.22+4b.23 → 4b.24+4b.25+4b.26+4b.27

4b-D: 4b.28 → 4b.29 → 4b.30 → 4b.31
      4b.32 → 4b.33
      4b.34 + 4b.35
```

### 验收检查点

- [ ] 定价经理配置定价策略+批量折扣规则，导入导出 Excel
- [ ] 销售代表完整报价流程：步骤一→二→三→四→五提交
- [ ] 三步弹窗选产品→工序→模板，产品卡片渲染正确
- [ ] DATA_SOURCE 字段自动查询填值，缓存生效
- [ ] 步骤三自动折扣（Drools），手动调整可用
- [ ] 提交原子事务（快照+路由+状态变更）正确
- [ ] 审批人收到通知，通过/退回操作生效
- [ ] 报价单管理列表按角色展示正确
- [ ] 复制报价单功能正常（DATA_SOURCE 清空，归档模板标记）
- [ ] 草稿自动保存+恢复正常
- [ ] 定时任务执行正确

---

## 九、M5 报价输出（第19-20周）

**目标**：完成报价单详情页、PDF/Excel 导出、邮件发送、延期、打印、客户反馈标记。

### 任务清单

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 5.1 | 报价单详情页 API | 后端 | `GET /api/cpq/quotations/{id}/detail`；SUBMITTED+返回快照，DRAFT 返回实时；含审批历史+完整结构 |
| 5.2 | 报价单详情页前端 | 前端 | 四区域：顶部操作栏（状态+角色动态按钮）+审批历史+正文+操作日志 |
| 5.3 | 报价单头部渲染 | 前端 | 编号/日期/客户/联系人/项目/到期/付款/交货；SUBMITTED+用快照字段 |
| 5.4 | 产品明细区域渲染 | 前端 | 多产品卡片；属性值动态渲染；页签表格（从 components_snapshot）；FORMULA 实时计算 |
| 5.5 | PDF 导出 API | 后端 | `POST .../export/pdf`（show_discount/show_processes/show_tab_details）；Qute 渲染；中文字体；< 3s |
| 5.6 | PDF Qute 模板 | 后端 | 公司抬头/报价单号/客户/产品明细/汇总/商务条款/签章；条件渲染折扣/工序/详情 |
| 5.7 | Excel 导出 API | 后端 | `POST .../export/excel`（show_discount/include_raw_data_sheet）；POI；< 2s |
| 5.8 | 导出前端 | 前端 | PDF 选项 Modal（3 Checkbox）+ Excel 选项 Modal（2 Checkbox） |
| 5.9 | 打印功能 | 前端 | `window.print()` + @media print CSS |
| 5.10 | 邮件发送 API | 后端 | `POST .../send`；仅 APPROVED；自动 PDF 附件；状态→SENT；记录日志 |
| 5.11 | 邮件发送前端 | 前端 | 邮件编辑 Modal（收件人/抄送/主题/正文/附件选项） |
| 5.12 | 延期 API | 后端 | `PUT .../extend`；仅 SENT/APPROVED；不触发重新审批 |
| 5.13 | 延期前端 | 前端 | DatePicker Modal |
| 5.14 | 客户反馈标记 API | 后端 | `POST .../accept`（原子累加 accumulated_amount）+ `POST .../reject`；仅 SENT+创建人；不可逆 |
| 5.15 | 客户反馈标记前端 | 前端 | SENT 时显示两按钮；二次确认；操作后消失 |

### 执行顺序

```
5.1 → 5.2+5.3+5.4
  ↓
5.5+5.6+5.7 → 5.8 → 5.9
  ↓
5.10 → 5.11
  ↓
5.12 → 5.13
  ↓
5.14 → 5.15
```

### 验收检查点

- [ ] 详情页渲染正确，操作栏按状态+角色动态切换
- [ ] SUBMITTED+状态展示快照，DRAFT 展示实时数据
- [ ] PDF 导出：选项组合正确；中文正常；<3s
- [ ] Excel 导出：两 Sheet 正确；原始数据可选
- [ ] 打印样式干净
- [ ] 邮件发送：仅 APPROVED 触发；PDF 附件；发送后→SENT
- [ ] 延期：SENT/APPROVED 可延期；APPROVED 延期不重新审批
- [ ] ACCEPTED 时客户累计金额原子累加；操作不可逆
- [ ] 完整生命周期：DRAFT→SUBMITTED→APPROVED→SENT→ACCEPTED/REJECTED/EXPIRED

---

## 十、M6 集成测试与优化（第21-22周）

**目标**：全系统端到端验证、性能调优、安全加固、体验打磨。

### 任务清单

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 6.1 | E2E 测试用例设计 | 测试 | 覆盖 PRD 全部用户场景 |
| 6.2 | 核心业务流程 E2E | 测试 | 5 条黄金路径：①完整生命周期 ②退回重提 ③复制+编辑 ④模板→报价 ⑤策略→折扣 |
| 6.3 | 权限矩阵全覆盖 | 测试 | 4角色×15模块访问控制；越权返回 403 |
| 6.4 | 状态机完整性 | 测试 | 7状态所有合法/非法转换；非法返回 400 |
| 6.5 | 并发场景 | 测试 | 双 ACCEPTED 原子累加；多标签页保存；100并发列表 p95<500ms |
| 6.6 | API 性能测试 | 测试 | k6 100并发：列表<500ms，折扣<200ms，提交<1s，PDF<3s |
| 6.7 | 数据库性能优化 | 后端 | 慢查询索引优化；JSONB GIN 索引；keyset pagination |
| 6.8 | 安全测试 OWASP Top 10 | 测试 | SQL注入/XSS/CSRF/越权/敏感数据/限流全验证 |
| 6.9 | 前端性能优化 | 前端 | 首屏<2s；代码分割；虚拟滚动；按需引入 |
| 6.10 | 前端体验打磨 | 前端 | loading一致；校验统一；空状态；响应式1280~1920px |
| 6.11 | 定时任务验证 | 测试 | 5个任务手动触发执行正确 |
| 6.12 | 数据一致性检查 | 测试 | 快照一致/折扣率统一/公式容差 |
| 6.13 | Bug 修复与回归 | 全栈 | P0/P1 全部修复+回归 |

### 验收标准

| 指标 | 目标 |
|------|------|
| E2E 黄金路径 | 5 条全部通过 |
| 权限矩阵 | 4角色×15模块全覆盖 |
| API p95 | < 500ms（100并发） |
| 折扣计算 p95 | < 200ms（100并发） |
| 首屏加载 | < 2s |
| OWASP Top 10 | 无高危 |
| 定时任务 | 5个全部正确 |

---

## 十一、M7 UAT与上线（第23-24周）

**目标**：用户验收、生产部署、数据初始化。

### 任务清单

| # | 任务 | 类型 | 验收标准 |
|---|------|------|----------|
| 7.1 | UAT 环境搭建 | DevOps | 独立 UAT 环境+测试数据初始化脚本 |
| 7.2 | UAT 测试执行 | 验收 | 业务方按角色操作；P0 当天修复，P1 三天内 |
| 7.3 | 生产环境准备 | DevOps | PostgreSQL+Quarkus 单实例+HTTPS+SMTP+JVM 参数+只读用户 |
| 7.4 | Flyway 生产迁移 | 后端 | 全部迁移脚本执行成功 |
| 7.5 | 生产数据初始化 | 后端 | 管理员账号+字典数据+工序种子+初始审批规则 |
| 7.6 | AES 加密密钥配置 | DevOps | 生产密钥独立配置，非代码仓库 |
| 7.7 | 监控与日志 | DevOps | Health 监控+JSON 日志+关键告警 |
| 7.8 | 上线部署与冒烟测试 | DevOps | 完整流程冒烟：登录→客户→报价→审批→PDF→邮件 |
| 7.9 | 应急预案 | DevOps | 回滚方案+每日备份+Drools 降级开关确认 |

### 验收检查点

- [ ] UAT P0 清零，P1 全部修复
- [ ] 生产冒烟完整流程走通
- [ ] 定时任务正常调度
- [ ] 邮件发送正常
- [ ] 监控告警测试通过

---

## 十二、风险与对策

| 风险 | 影响 | 概率 | 对策 |
|------|------|------|------|
| 拖拽交互复杂度超预期 | M3 延期 | 中 | dnd-kit 库成熟度高；公式构建器可简化为非拖拽方案降级 |
| Drools 7.74.x 与 Quarkus 兼容 | M4a 延期 | 低 | M0 已做 POC 验证；降级为纯 Java Service |
| PDF 中文排版问题 | M5 延期 | 中 | M0 阶段提前 POC 验证字体嵌入 |
| 报价生成器5步向导状态管理 | M4b 延期 | 中 | Zustand 分 store 管理；草稿持久化兜底 |
| 单人开发疲劳 | 整体延期 | 高 | AI 辅助降低编码负担；每里程碑完成后 1 天休整 |
| 需求变更 | 整体延期 | 高 | PRD v2.0 已高度完善；变更走 PRD 变更日志+影响评估 |

---

## 十三、开发规范

### 13.1 Git 规范

- 主分支：`main`
- 开发分支：`dev`
- 功能分支：`feature/M{n}-{module}-{description}`（如 `feature/M1-auth-login`）
- 每个任务完成后提交，commit message 格式：`feat(module): description` / `fix(module): description`

### 13.2 API 规范

- 路径前缀：`/api/cpq/`
- RESTful 动词：GET/POST/PUT/DELETE
- 分页：`?page=0&size=20&sort=createdAt,desc`
- 响应格式：`{ "code": 200, "message": "success", "data": {...} }`
- 错误格式：`{ "code": 400, "message": "...", "details": [...] }`

### 13.3 数据库规范

- 表名：snake_case 单数（如 `quotation`，非 `quotations`）
- 主键：UUID，字段名 `id`
- 时间字段：`created_at`、`updated_at`（Timestamp with time zone）
- 软删除：status 字段枚举（ACTIVE/INACTIVE），不做物理删除
- Flyway 迁移文件：`V{n}__{description}.sql`

### 13.4 前端规范

- 文件命名：PascalCase 组件文件，camelCase 工具文件
- 目录结构：`src/pages/{module}/`、`src/components/`、`src/services/`、`src/stores/`
- API 调用统一走 `src/services/{module}Service.ts`
- 状态管理按模块分 store：`src/stores/{module}Store.ts`
