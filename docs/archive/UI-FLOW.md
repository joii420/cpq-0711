# CPQ 报价系统 - 页面布局/按钮/操作流程文档

> **版本**: v1.0
> **生成日期**: 2026-04-29
> **基础路径**: 前端 SPA (`/` 开头) / 后端 API (`/api/cpq` 开头)
> **面向读者**: QA / 测试工程师 / 前端开发 / 产品经理
> **配套文档**: `docs/PRD.md`、`docs/API.md`、`docs/操作说明.md`、`docs/TDD.md`

---

## 文档目的

本文档以"页面 / 按钮 / 操作流程"为粒度，覆盖系统现存全部前端模块。每个页面统一描述：

1. **路由 & 入口**：URL、菜单位置、跳转触发
2. **角色可见性**：哪些角色能访问 / 看到哪些按钮
3. **页面结构**：顶部工具栏、筛选区、主表格 / 主表单、Tab、底栏
4. **按钮清单**：按钮文字、触发行为、调用的 API、置灰 / 隐藏条件
5. **抽屉 / 弹窗**：标题、宽度、字段、按钮
6. **操作流程**：用户从打开到完成的完整步骤链路
7. **状态机约束**：依赖的状态变更与前置条件

通用 UI 规则（覆盖所有模块）：

- 弹出式交互一律使用 **Drawer**（右滑），仅保留 `message` / `notification` / `Popconfirm` 即时反馈，不再使用居中 Modal（PRD 规范）
- 抽屉默认 `placement="right"`，宽度按内容复杂度选 480 / 600 / 720 / 960 / 1200
- 列表页面统一走 **SelectableTable + 工具栏动作** 模式：行内不放动作按钮，所有变更/危险动作上提到顶部工具栏；选择驱动启用；危险动作走 Modal 列出所选项二次确认。**详细规范见 [`docs/列表操作规范.md`](./列表操作规范.md)，新增列表页面必须按此实现。**
  - 旧规则"破坏性操作用 Popconfirm 二次确认"已被替换：批量场景下 Popconfirm 看不到具体目标，改为 SelectableTable 内置 Modal 列出所选项；`Popconfirm` 仅保留给行内单条、无副作用、单一字段（如"标记为主要联系人"）等轻量场景
- 所有列表页统一支持：分页（10/20/50）、关键词搜索、状态筛选、刷新
- 顶部固定 Header 含：主题切换、通知铃铛（30s 轮询未读数）、用户头像下拉（修改密码 / 退出登录）

---

## 目录

- [一、全局布局与菜单](#一全局布局与菜单)
- [二、认证模块](#二认证模块)
- [三、工作台](#三工作台)
- [四、客户管理](#四客户管理)
- [五、产品管理](#五产品管理)
  - [5.1 产品列表](#51-产品列表)
  - [5.2 工序配置](#52-工序配置)
  - [5.3 生产料号管理](#53-生产料号管理)
  - [5.4 产品分类管理](#54-产品分类管理)
- [六、报价中心](#六报价中心)
  - [6.1 报价单管理（列表）](#61-报价单管理列表)
  - [6.2 报价单向导（新建 / 编辑）](#62-报价单向导新建--编辑)
  - [6.3 报价单详情](#63-报价单详情)
  - [6.4 V5 基础数据导入向导](#64-v5-基础数据导入向导)
  - [6.5 客户 Excel 导入弹窗](#65-客户-excel-导入弹窗)
  - [6.6 批量从基础数据添加产品](#66-批量从基础数据添加产品)
  - [6.7 添加产品三步向导](#67-添加产品三步向导)
  - [6.8 四视图切换](#68-四视图切换)
  - [6.9 撤回审批](#69-撤回审批)
  - [6.10 导入历史](#610-导入历史)
- [七、定价管理](#七定价管理)
- [八、配置中心](#八配置中心)
  - [8.1 组件管理](#81-组件管理)
  - [8.2 模板配置](#82-模板配置)
  - [8.3 核价模板](#83-核价模板)
  - [8.4 产品模板绑定](#84-产品模板绑定)
  - [8.5 模板版本对比](#85-模板版本对比)
  - [8.6 基础数据配置](#86-基础数据配置)
  - [8.7 业务标签字典](#87-业务标签字典)
- [九、主数据维护](#九主数据维护)
  - [9.1 数据总览](#91-数据总览)
  - [9.2 历史版本](#92-历史版本)
  - [9.3 字段重要性](#93-字段重要性)
- [十、变更日志中心](#十变更日志中心)
- [十一、数据源管理](#十一数据源管理)
- [十二、系统管理](#十二系统管理)
  - [12.1 用户 / 区域 / 部门](#121-用户--区域--部门)
  - [12.2 审批规则](#122-审批规则)
  - [12.3 通知列表](#123-通知列表)
  - [12.4 操作日志](#124-操作日志)
  - [12.5 元素价格中心](#125-元素价格中心)
  - [12.6 系统配置中心](#126-系统配置中心)
  - [12.7 锁监控](#127-锁监控)
  - [12.8 DDL 扩列管理](#128-ddl-扩列管理)
- [十三、按钮可用性矩阵（速查）](#十三按钮可用性矩阵速查)
- [十四、跨页关键操作流程图](#十四跨页关键操作流程图)

---

## 一、全局布局与菜单

### 1.1 主框架（MainLayout）

| 区域 | 内容 |
|------|------|
| 左侧 Sider（220px，深色） | "CPQ 报价系统"标题 + 一级菜单 + 子菜单（按角色过滤） |
| 顶部 Header（64px，sticky） | 主题切换 ☀/🌙 ＜→＞ 通知铃铛（Badge 未读数）→ 用户名 + 头像 |
| 主内容 Content | 当前路由 `<Outlet />`，圆角 8px，背景 #fff |

**用户菜单（Avatar 下拉）**：
- 修改密码 → `/change-password`
- 退出登录 → 调 `POST /api/cpq/auth/logout` → 跳转 `/login`

**通知 Popover**（点击铃铛打开）：
- 加载最近 5 条通知（`GET /notifications?page=0&size=5`）
- 顶部"全部已读"按钮 → `POST /notifications/mark-all-read`
- 底部"查看全部通知"→ 跳转 `/system/notifications`

### 1.2 菜单结构（按角色过滤）

```
工作台                                          ← ALL
客户管理                                        ← ALL
产品管理 ▽
  ├─ 产品列表                                   ← ALL
  ├─ 生产料号管理                               ← ALL
  └─ 产品分类管理                               ← ALL
报价中心 ▽
  ├─ 报价单管理                                 ← ALL
  └─ 导入历史                                   ← ALL
定价管理 ▽
  └─ 定价策略                                   ← ALL
数据源管理                                      ← SYSTEM_ADMIN
配置中心 ▽                                       ← SALES_MANAGER / SYSTEM_ADMIN
  ├─ 组件管理
  ├─ 模板配置                                   ← ALL（其余子项仅 MGR/ADMIN）
  ├─ 核价模板
  ├─ 产品模板绑定
  ├─ 模板版本对比
  ├─ 基础数据配置
  └─ 业务标签字典
主数据维护 ▽                                     ← SALES_MANAGER / SYSTEM_ADMIN
  ├─ 数据总览
  ├─ 历史版本
  └─ 字段重要性                                 ← SYSTEM_ADMIN
变更日志                                        ← SALES_MANAGER / SYSTEM_ADMIN
系统管理 ▽                                       ← SYSTEM_ADMIN
  ├─ 用户管理
  ├─ 区域管理
  ├─ 部门管理
  ├─ 审批规则
  ├─ 通知列表                                   ← ALL（仅本菜单项）
  ├─ 操作日志                                   ← MGR / ADMIN
  ├─ 元素价格中心
  ├─ 系统配置中心
  ├─ 锁监控
  └─ DDL 扩列管理
```

> 角色变量：`SALES_REP` 销售代表 / `SALES_MANAGER` 销售经理 / `PRICING_MANAGER` 定价经理 / `SYSTEM_ADMIN` 系统管理员。

---

## 二、认证模块

### 2.1 登录页 `/login`

| 元素 | 说明 |
|------|------|
| 用户名 Input | 必填 |
| 密码 Input.Password | 必填，最小 8 位 |
| 「记住我」Checkbox | 持久化 Session |
| 「登录」Button (Primary) | `POST /auth/login`，成功设置 HttpOnly Cookie，跳转 `/dashboard`；若 `forceChangePassword=true` 跳转 `/change-password` |
| 「忘记密码？」Link | 跳转 `/forgot-password` |

**操作流程**：
1. 输入用户名 + 密码 → 点击登录
2. 401 → 提示"用户名或密码错误"
3. 200 + `forceChangePassword=true` → 强制跳到修改密码页
4. 200 + 正常 → 进入工作台

### 2.2 忘记密码 `/forgot-password`

字段：邮箱（必填，格式校验）  
按钮：「发送重置链接」→ `POST /auth/forgot-password` → 显示成功提示（"已发送，请查收邮箱"）  
链接：「返回登录」→ `/login`

### 2.3 重置密码 `/reset-password?token=xxx`

字段：新密码、确认密码（≥8 位、含字母+数字）  
按钮：「确认重置」→ `POST /auth/reset-password { token, newPassword }` → 成功跳 `/login`  
异常：URL 无 token 或 token 失效 → 显示错误页

### 2.4 修改密码 `/change-password`

字段：当前密码、新密码、确认密码  
按钮：「确认修改」→ `POST /auth/change-password` → 成功跳 `/dashboard`  
约束：首次登录强制进入此页且不可跳过；新密码不可与旧密码相同

---

## 三、工作台 `/dashboard`

| 模块 | 内容 | API |
|------|------|-----|
| 系统健康卡片 | 数据库 / Redis / 文件存储状态 | `GET /health` |
| 个人代办 | 待我审批的报价单列表 | `GET /quotations?assignedToMe=true&status=SUBMITTED` |
| 通知摘要 | 最近 5 条未读通知 | `GET /notifications?page=0&size=5` |

> v1 版本工作台为简化页，重点是健康检查 + 跳转入口。

---

## 四、客户管理 `/customers`

### 4.1 列表页结构

**顶部工具栏**：
- `Input.Search`：关键词搜索（客户名称 / 编码 / 联系人）
- `Select`：状态（全部 / 启用 / 停用）
- `Select`：等级（钻石 / VIP / 黄金 / 白银 / 标准）
- 「批量停用」Button danger（多选时显示，仅 SALES_MANAGER+）
- 「新增客户」Button primary + Plus 图标（SALES_REP+）

**主表格列**：
| 列 | 说明 |
|---|---|
| 客户名称 | 链接，点击打开详情抽屉 |
| 客户编码 | 自动生成 |
| 等级 | Tag，颜色随等级 |
| 行业 | 文本 |
| 区域 | 文本 |
| 累计金额 | 只读，从 `accumulated_amount` |
| 状态 | Tag（ACTIVE / INACTIVE） |
| 操作 | 「编辑」｜「停用」(Popconfirm) |

调用：`GET /customers?page&size&keyword&level&status`

### 4.2 客户详情/编辑 抽屉（宽 720）

**抽屉标题**：`新增客户` / `编辑客户：<name>`

**Tab 1 - 基本信息**：
| 字段 | 类型 | 必填 |
|---|---|---|
| 客户名称 | Input | 是 |
| 客户编码 | Input（编辑禁用，新增自动生成） | 否 |
| 等级 | Select（钻石/VIP/黄金/白银/标准） | 否 |
| 所属行业 | Select 可输入 | 否 |
| 所属区域 | TreeSelect 引用区域树 | 否 |
| 地址 | Input | 否 |
| 付款方式 | Select | 否 |
| 信用额度（万元） | InputNumber | 否 |
| 备注 | TextArea | 否 |

**Tab 2 - 联系人管理**：
- 表格：姓名｜职责｜手机号｜邮箱｜微信｜主要联系人 Tag｜操作（编辑/设主要/删除）
- 「+ 添加联系人」Button → 嵌套抽屉（宽 480）
  - 字段：姓名*、职责（采购/技术/财务）、手机号*（11 位校验）、邮箱、微信、是否主要联系人 Checkbox
- 约束：每客户至少 1 个主要联系人，不可全部删除

**Tab 3 - 统计面板（只读）**：历史订单数 / 累计金额 / 平均折扣率 / 信用额度

**底部按钮**：「保存」「取消」  
调用：新增 `POST /customers`，更新 `PUT /customers/{id}`，停用 `DELETE /customers/{id}`

### 4.3 客户料号映射 Tab（嵌入客户详情）

**工具栏**：
- 关键词搜索（客户料号 / 内部料号）
- 「Excel 导入」Button → 嵌套抽屉
- 「+ 添加映射」Button primary

**表格列**：客户料号 / 内部料号 / 物料名称 / 规格 / 操作（删除）

**添加映射 抽屉（宽 600）**：
- 客户料号*（Input）
- 内部物料*（Select 下拉搜索，显示"料号 - 名称"）
- 「保存」「取消」  
调用：`POST /customers/{id}/material-mappings`、`POST /customers/{id}/material-mappings/import`

### 4.4 客户删除保护

调用 `DELETE /customers/{id}` 时若客户存在 DRAFT/SUBMITTED/APPROVED 报价单，后端返回 400 错误：
> "该客户有进行中的报价单（X 张），无法删除。请先处理相关报价单后再操作。"

前端弹 `notification.error` 提示，不执行删除。

---

## 五、产品管理

### 5.1 产品列表 `/products`

**工具栏**：
- 「Excel 导入」Button + Upload 图标 → 抽屉（宽 720，Dragger）
- 「+ 新增产品」Button primary

**分类标签 Tabs**：全部 / 分类 1 / 分类 2 ...（动态渲染产品分类树）

**搜索区**：
- `Input.Search`（产品名称/料号）
- `Select`：状态（启用/停用）

**表格列**：产品名称 ｜ 料号 ｜ 分类 Tag ｜ 规格 ｜ 标签 Tags ｜ 状态 Tag ｜ 操作

**操作列按钮**：
- 「编辑」→ 抽屉
- 「配置工序」→ 跳转 `/products/{id}/processes`
- 「模板绑定」→ 跳转 `/template-bindings?productId={id}`
- 「删除」(Popconfirm) → `DELETE /products/{id}`

**新增 / 编辑抽屉（宽 720）**：
| 字段 | 必填 |
|---|---|
| 产品名称 | 是 |
| 料号（partNo） | 是；编辑时禁用 |
| 分类（categoryId） | 是 |
| 规格 | 否 |
| 图号 | 否 |
| 尺寸 | 否 |
| 材质 | 否 |
| 状态 | 默认 ACTIVE |
| 标签（多选） | 否 |
- 「保存」「取消」

**导入抽屉（Dragger）**：
- 列顺序：名称 / 料号 / 分类 / 规格 / 标签（逗号分隔）
- 导入完成显示：新增 X / 跳过 X / 失败 X + 错误明细
- 按钮：「开始导入」「取消」「关闭」

### 5.2 工序配置 `/products/{productId}/processes`

**三栏布局**：

| 区域 | 内容 |
|---|---|
| 左侧（120px） | 分类 Tabs：表面处理｜机加｜热处理｜装配｜检测｜包装（每项 Badge 数量） |
| 中间（flex 1） | 工序卡片网格，每张卡片：Checkbox + 工序名 + 工序代码 + 描述 |
| 右侧（280px） | "已选工序"列表，可拖拽排序，每行：拖拽 icon + 工序名 + 分类 Tag + 必选 Switch + 删除按钮 |

**底部按钮**（右侧栏）：
- 「保存」（脏标记激活）→ `PUT /products/{productId}/processes`，提交 `[{processId, sortOrder, isRequired}]`
- 「重置」→ 恢复上次保存状态

### 5.3 生产料号管理 `/materials`

**工具栏**：「Excel 导入」「+ 新增料号」

**搜索 / 筛选**：关键词（料号/名称）、状态（Y 可生产 / N 停产）

**表格列**：料号｜名称｜规格｜尺寸｜状态 Tag｜操作（编辑/删除）

**新增 / 编辑抽屉（宽 480）**：
| 字段 | 必填 |
|---|---|
| 料号 | 是；编辑禁用 |
| 名称 | 是 |
| 规格 | 否 |
| 尺寸 | 否 |
| 状态码 | 默认 Y |

调用：`POST /internal-materials`、`PUT /internal-materials/{id}`、`POST /internal-materials/import`、`DELETE /internal-materials/{id}`（被引用时禁止）

### 5.4 产品分类管理 `/product-categories`

**工具栏**：「+ 新增分类」

**表格**（树形展开 defaultExpandAllRows）：编码｜名称｜描述｜排序｜状态 Tag｜操作（编辑/删除）

**新增 / 编辑抽屉（宽 600）**：
| 字段 | 说明 |
|---|---|
| 编码 | 必填，编辑禁用 |
| 名称 | 必填 |
| 描述 | 否 |
| 父级分类 | TreeSelect（不含自身及后代，防循环引用） |
| 排序 | InputNumber |
| 状态 | 默认 ACTIVE |

约束：删除时若有子分类或被产品引用 → 后端返回 400。

---

## 六、报价中心

### 6.1 报价单管理（列表）`/quotations`

**顶部工具栏（按钮）**：
| 按钮 | 行为 |
|---|---|
| 导入历史 | 跳转 `/import-history` |
| 从客户 Excel 导入 | 打开 `ImportExcelModal`（5 步弹窗） |
| 从基础数据导入 | 打开 `BasicDataImportV5ToQuotation` 抽屉 |
| 手动创建 | 跳转 `/quotations/new` |

**Tabs（按状态过滤）**：
全部 / 草稿(DRAFT) / 审批中(SUBMITTED) / 已批准(APPROVED) / 已发送(SENT) / 已接受(ACCEPTED) / 已退回(REJECTED) / 已过期(EXPIRED) / **待我审批**（仅 SALES_MANAGER / SYSTEM_ADMIN，`assignedToMe=true&status=SUBMITTED`）

**搜索 / 筛选**：关键词（报价单号 / 名称）、客户 Select

**表格列**：报价单号 ｜ 名称 ｜ 客户 ｜ 状态 Tag ｜ 总金额 ｜ 到期日 ｜ 创建人 ｜ 创建时间 ｜ 操作

**操作列按钮（按状态显示）**：

| 状态 | 按钮 | 调用 / 跳转 |
|---|---|---|
| DRAFT | 编辑 / 复制 / 删除(Popconfirm) / 提交审批(Popconfirm) | 编辑→`/quotations/{id}/edit`; 复制→`POST /quotations/{id}/copy`; 删除→`DELETE`; 提交→`POST /submit` |
| SUBMITTED（指派审批人） | 通过(绿) / 退回(红) | 通过→Drawer + `POST /approve`; 退回→Drawer + `POST /reject` |
| SUBMITTED（销售代表本人） | 撤回 | `POST /withdraw` |
| APPROVED | 发送给客户 / 延期 | 发送→Drawer + `POST /send`; 延期→Drawer + `PUT /extend` |
| SENT | 接受(Popconfirm) / 拒绝 / 延期 | 接受→`POST /accept`; 拒绝→Drawer + `POST /reject-by-customer`; 延期→Drawer |
| 其他 | 编辑 / 复制 | - |

**「审批通过」抽屉（宽 480）**：审批意见 TextArea（可选）→ 「确认通过」按钮 → `POST /quotations/{id}/approve { note }`

**「退回报价」抽屉（宽 480）**：退回原因 TextArea（必填）→ 「确认退回」按钮 → `POST /quotations/{id}/reject { reason }`

### 6.2 报价单向导（新建 / 编辑）`/quotations/new` 或 `/quotations/{id}/edit`

5 步 Steps 组件 + 顶部「保存草稿」「返回列表」按钮。

#### Step 1 - 基本信息
| 字段 | 类型 | 必填 |
|---|---|---|
| 客户 | Select 下拉搜索 | 是 |
| 报价单名称 | Input | 是 |
| 项目名称 | Input | 否 |
| 商机编号 | Input | 否 |
| 报价类型 | Select（新报/复报/调价） | 否 |
| 优先级 | Select（高/中/低） | 否 |
| 阶段 | Select（询价/谈判/成交） | 否 |
| 预计成交日 | DatePicker | 否 |
| 联系人 | Select（联动客户）→ 自动填充 姓名/电话/邮箱 | 否 |
| 客户概况卡片（右侧） | Descriptions：等级/累计金额/信用额度 | 只读 |

按钮：「下一步」（必填校验通过启用）

#### Step 2 - 产品配置
- 视图切换 `Segmented`：**产品卡片视图** / Excel 视图 / 核价表视图 / 比对视图（详见 6.8）
- 「批量从基础数据导入」Button（产品已绑定模板时启用，打开 `BulkImportPartsDrawer`）
- 「+ 添加产品」Button → `AddProductModal` 三步向导
- 漂移检测 Alert（顶部黄色横幅）+ 「使用最新版本」Button

每个产品卡片支持：编辑 BASIC_DATA 字段（INPUT 字段可改，FORMULA 实时重算）/ 删除产品 / 折叠展开 / 工序展示

按钮：「上一步」「下一步」

#### Step 3 - 优惠策略（定价）
- 显示：原始总金额 / 系统折扣率 / 最终总金额
- 「自动计算折扣」Button → `POST /quotations/{id}/calculate-discount` 应用客户定价策略
- 手动折扣率 InputNumber（0-100，整数百分比）
- 折扣调整原因 TextArea（手动改折扣率时必填）

#### Step 4 - 交易条款
| 字段 | 类型 | 默认 |
|---|---|---|
| 付款条件 | TextArea | 客户默认 |
| 交货周期（天） | InputNumber | - |
| 报价有效期 | DatePicker | 今日 +30 天 |
| 备注 | TextArea | - |

#### Step 5 - 提交审批（总览）
- 卡片：报价单号 / 状态 Tag / 名称 / 客户 / 项目 / 联系人 / 报价类型 / 优先级
- 产品明细表：产品名称 / 料号 / 小计 / 合计
- 定价汇总：原始 / 折扣率 / 最终
- 交易条款汇总
- 「提交审批」Button primary（仅 DRAFT，Popconfirm）→ `POST /submit`

**自动保存**：进入向导后每 10s 静默 `PUT /quotations/{id}/draft`，并在右上角显示"已自动保存于 HH:mm:ss"

### 6.3 报价单详情 `/quotations/{id}`

**顶部工具栏（按钮按状态显示）**：

| 状态 | 按钮 |
|---|---|
| DRAFT | 编辑 / 提交审批(Popconfirm) / 删除 |
| SUBMITTED（审批人/ADMIN） | 通过(绿) / 退回(红) |
| SUBMITTED（销售代表） | 撤回 |
| APPROVED | 发送报价 / 延期 / 请求撤回(销售代表) |
| SENT | 接受报价(Popconfirm) / 拒绝报价 / 延期 |
| 通用 | 复制 / 导出 PDF / 导出 Excel / 打印 |

**Tabs**：

1. **报价单信息**：基本信息 Descriptions / 产品明细表 / 审批历史 Collapse
2. **数据来源**（仅 SUBMITTED+，懒加载）：基础资料快照 / 客户资料引用版本 / 元素单价快照 / 审计信息 / 「对比最新版本」Button

**抽屉清单**：

| 抽屉 | 宽度 | 字段 / 内容 | 提交动作 |
|---|---|---|---|
| 导出 PDF | 480 | 显示折扣 / 显示工序 / 显示组件明细 Switch | 新窗口预览 + 调用浏览器打印 |
| 导出 Excel | 480 | 显示折扣 / 包含原始数据 Switch | `POST /export/excel` 下载 .xlsx |
| 发送邮件 | 600 | 收件人*（邮箱格式） / 抄送 / 主题* / 邮件正文 / 附加 Excel Switch | `POST /send` → 状态 SENT |
| 延期 | 480 | 新有效期*（DatePicker，过去日期禁用） | `PUT /extend` |
| 拒绝报价（客户拒绝） | 480 | 拒绝原因 TextArea | `POST /reject-by-customer` |
| 审批通过 | 480 | 审批意见 TextArea（可选） | `POST /approve` |
| 退回报价 | 480 | 退回原因 TextArea*（必填） | `POST /reject` |
| 请求撤回（APPROVED） | 480 | 撤回原因*（必填） | `POST /withdraw-request` |
| 撤回审批操作（PENDING） | 480 | 备注 | `POST /withdraw/approve` 或 `/withdraw/reject` |

### 6.4 V5 基础数据导入向导

**入口**：报价单列表 / 报价单详情「重新导入基础数据」按钮 / 报价单向导 Step 2「批量从基础数据导入」

**主抽屉（宽 720）**：步骤指示器 `上传 → 差异 → 冲突 → 写入 → 完成`

#### Step UPLOAD
- 客户 Select（必选）
- 拖拽上传 Excel（.xlsx）
- 「开始预览」Button（条件禁用）→ `POST /quotations/import-basic-data`
- 进度条 + 解析进度

#### Step 差异（UI-2 子抽屉，宽 960）— 基础资料整体差异确认
- 顶部 Alert：差异总览（CRITICAL X / IMPORTANT X / NORMAL X）
- 「全部采纳新值」Button
- 按物理表分组 Collapse：
  - 每个差异项展示：字段标签 / 旧值 / 新值 / 决策（保留旧 / 采纳新）/ 备注
  - **CRITICAL 字段备注必填**
- 「下一步（确认基础资料）」Button

#### Step 冲突（UI-1 子抽屉，宽 1200）— 客户资料字段级冲突决策
- 顶部 Alert：冲突总览
- 「全部采纳新值」Button
- 按"料号 × 数据表"分组 Collapse
- 每字段：决策（采用导入值 / 保留现有值）+ 备注
- 草稿自动保存（500ms 防抖）
- 「确认导入」Button → `POST /quotations/confirm-basic-data-import`

#### Step DONE
- 成功结果卡 + 创建报价单提示
- 「关闭」「再次导入」「创建报价单」按钮

#### CreateQuotationDrawer（宽 480，DONE 后叠加）
- 客户名称（禁用）
- 报价单名称*（Input）
- 产品分类*（Select）
- 模板匹配 Alert：无模板（红） / 通用模板（黄） / 客户专属（绿）
- 客户报价模板 Select（多版本）
- 「稍后创建」「确认创建」按钮 → 跳转 `/quotations/{id}/edit?autoPopulate=1&importRecordId={id}`

#### Step ERROR
- 错误结果卡 + 错误明细
- 「重新开始」「关闭」按钮

### 6.5 客户 Excel 导入弹窗（v3 兼容流程）

**入口**：报价单列表「从客户 Excel 导入」  
**5 步 Modal**（保留 Modal 实现，仅作过渡兼容；新流程统一走 V5 抽屉）

| Step | 内容 | 按钮 |
|---|---|---|
| 0 | 选客户 Select 下拉搜索 | 下一步 |
| 1 | 选 CPQ 模板（已过滤）| 查看示例文件 / 下一步 |
| 2 | 拖拽上传 Excel | 预览 |
| 3 | 预览结果表格 + 统计 | 下一步 |
| 4 | 确认导入 | 确认导入 → 跳转编辑页 |

调用：`POST /imports/import-excel`、`POST /imports/confirm-import`

### 6.6 批量从基础数据添加产品（BulkImportPartsDrawer）

**宽度 1100**  
**入口**：报价单向导 Step 2「批量从基础数据导入」

- 搜索栏（料号 / 名称 / 客户产品编号）
- 表格：料号（专属/已添加 Tag）｜ 名称 ｜ 单重 ｜ 客户产品编号 ｜ 客户图号
- 多选 Checkbox（已添加行禁用）
- 底栏：已选 X / 总数 Y
- 按钮：「取消」「添加 N 个产品」（禁用条件：未勾选）

### 6.7 添加产品三步向导（AddProductModal）

> 历史遗留为 Modal，按 PRD 规范应改为 Drawer；测试时按现状测试

**Step 0 - 选择产品**：分类侧栏 + 关键词搜索 + 产品卡片网格（单选高亮）  
**Step 1 - 选择工序**：分组多选 Checkbox（无工序时可跳过）  
**Step 2 - 选择模板**：模板卡片网格（渐变背景，单选）  
**底部**：「上一步」「下一步 / 确认添加」「取消」

### 6.8 四视图切换（QuotationStep2 内）

| 视图 | 描述 | 数据来源 |
|---|---|---|
| 产品卡片视图 | 默认，可编辑 INPUT 字段、查看 FORMULA 实时重算 | `GET /quotations/{id}` 的 lineItems |
| Excel 视图 | 客户原生 Excel 视图，可编辑 INPUT 字段，FORMULA 灰色只读，FIXED_VALUE 浅蓝只读 | `GET /quotations/{id}/excel-view` ｜ `PUT /quotations/{id}/excel-view`（双向同步） |
| 核价表视图 | 内部成本基线（只读），显示模板名 Tag + LIVE/SNAPSHOT Tag + 总成本统计 | `GET /quotations/{id}/costing-sheet` |
| 比对视图 | 核价 vs 客户报价对比，两个 Tab：基础字段 + 业务标签分组（含毛利汇总） | `GET /quotations/{id}/comparison` |

**ElementPriceHint 组件**（嵌入元素 BOM 行单价输入框旁）：
- 触发条件：`row.element_name` 有值 + 字段为单价字段
- 调 `GET /element-prices/reference?elementName={Ag}` → 显示 Tooltip + Tag「参考 5500 RMB/克」
- 加载失败：显示灰 Tag「参考价：暂无」（不影响填价）

### 6.9 撤回审批（WithdrawSection）

嵌入 QuotationDetail 页面：

| 状态 / 角色 | 显示 |
|---|---|
| APPROVED + 销售代表 + 无 PENDING | 「请求撤回审批」Button → 抽屉填写原因 → `POST /withdraw-request` |
| PENDING + 原审批人 / SYSTEM_ADMIN | 「同意撤回」（Popconfirm，确认后状态回 DRAFT）/「拒绝撤回」(Popconfirm) |
| 已有撤回历史 | 折叠列表显示历史撤回记录 |

调用：`GET /withdraw-requests`、`/withdraw-requests/pending`、`POST /withdraw-request`、`POST /withdraw/approve`、`POST /withdraw/reject`

### 6.10 导入历史 `/import-history`

**筛选**：客户 Select / 状态（SUCCESS / PARTIAL / FAILED / PROCESSING） / 时间 RangePicker  
**工具栏**：「V5 增强导入」Button（CloudUpload 图标）

**表格列**：导入号 ｜ 客户 ｜ 类型 ｜ 状态 Tag ｜ 总记录数 ｜ 成功/失败数 ｜ 导入时间 ｜ 操作  
**操作**：「查看详情」（抽屉）/「下载原始 Excel」→ `GET /imports/records/{id}/download`

---

## 七、定价管理 `/pricing`

**两栏布局**：

### 7.1 左侧（Sider）—— 客户列表
- 等级 Tabs：ALL / DIAMOND / VIP / GOLD / SILVER / STANDARD
- `Input.Search` 搜索客户
- 客户列表（分页 20）
- 选中后右侧加载该客户的定价策略

### 7.2 右侧（Content）—— 策略管理
- 选中客户名称
- 「+ 新增策略」Button primary
- 策略列表（按等级分 Tabs，每 Tab 显示 Badge 活跃数）：
  - 表格：名称｜有效期｜状态 Tag（生效中/已过期/已禁用）｜操作
  - 操作：编辑 / 删除（仅 DISABLED）/ 启用（仅 DISABLED）

### 7.3 策略编辑抽屉（宽 720，2 步）

**Step 0 基础信息**：
| 字段 | 必填 |
|---|---|
| 名称 | 是 |
| 开始日期 | 是 |
| 结束日期 | 是 |
| 客户等级 | 是 |
| 状态 | 是（默认 ACTIVE） |

**Step 1 折扣规则**：
- 表格：规则类型｜匹配条件（金额范围 / 数量范围 / 客户等级）｜折扣率
- 「+ 添加规则」Button
- 「保存」「上一步」「取消」

调用：`POST /pricing-strategies`、`PUT /pricing-strategies/{id}`、`POST /pricing-strategies/{id}/rules`

---

## 八、配置中心

### 8.1 组件管理 `/components`

**三栏布局**：

| 区域 | 内容 |
|---|---|
| 左 ComponentTree | 搜索框（300ms 防抖）+ 树结构 + 右键菜单（新建目录/新建组件/重命名/删除） |
| 中 Editor | 工具栏（组件名 + Code + 类型 Tag + 列数 Badge + 「配置帮助」+ 「保存」loading） + 数据驱动路径配置（BNF 路径 Input + 「选择路径」） + FieldConfigTable + FormulaBuilder |
| 右 FieldPanel | 当前字段 / 其他组件小计 / 报价单字段（点击插入到活跃公式） |

**字段配置表（FieldConfigTable）**：列名 / 字段 Key / 字段类型（FIXED_VALUE / INPUT / FORMULA / DATA_SOURCE） / 数据源绑定状态 / 操作（编辑/删除/上下移）

**FormulaBuilder**：函数面板（22 个函数）+ 公式输入框 + 实时校验  
**PathPickerDrawer（宽 720）**：BNF 路径选择器（Sheet → 字段 → 过滤条件）

**DataSource 绑定 流程（3 步弹窗，宽 600）**：
1. select：搜索 + 列表 + 「选择」按钮
2. bind：参数表（参数名 → 下拉选组件字段）
3. config：当前绑定显示 + 「切换」「保存」

调用：`GET /components`、`PUT /components/{id}`（DFS 检测循环引用）、`POST /components`、`DELETE /components/{id}`、`GET /component-directories`、`POST /component-directories`

### 8.2 模板配置

#### 8.2.1 模板列表 `/templates`
**工具栏**：「+ 新建模板」+ 关键词搜索 + 产品分类 + 适用范围 Select（全部 / 通用 / 客户专属，专属时联动客户 Select）+ 状态 Select

**表格列**：模板名称（链接）｜ 产品分类 ｜ 适用范围 Tag ｜ 版本 ｜ 状态 Tag（DRAFT / PUBLISHED / ARCHIVED）｜ 描述 ｜ 创建时间 ｜ 操作（编辑 / 删除-仅 Draft / 新建草稿）

**新建模板抽屉（宽 600）**：名称*｜产品分类｜适用客户（留空=通用）｜描述

#### 8.2.2 模板配置页 `/templates/{id}`
**布局**：左 ComponentPalette（组件库）+ 中模板编辑（多 Tab） + 右 ViewToggle（detail/simple/excel）

**工具栏**：自动保存指示（每 30s `PATCH /templates/{id}`）/ 手动「保存」/「发布」（仅 Draft → PUBLISHED）/「归档」（仅 Published）

**Tab 操作**：每个 Tab 对应一个 TemplateComponent，Tab header 显示组件名 + 删除 icon（仅 Draft）；点击「+」打开 ComponentPalette 添加组件

**Tab 内容区**：
- ProductAttributesGrid：可拖入产品属性（PRODUCT_ATTRIBUTE 来源）
- TabComponentArea：拖拽放置字段、配置 preset_rows、formula_assignments
- SubtotalDropBar / SubtotalFormulaBar：拖入小计字段 + 公式

**Excel 视图配置 Tab（ExcelViewConfigTab）**：
- 客户选择 Select
- 导入设置：表头行号 / 数据起始行号 / Sheet 索引 / 部件号列 / 样本文件名
- 「导入示例」「导出示例」按钮
- 列配置表：列 Key ｜ 列标题 ｜ 数据来源（PRODUCT_ATTRIBUTE / COMPONENT_FIELD / EXCEL_FORMULA / FIXED_VALUE）｜ 操作（编辑 / 上移 / 下移 / 删除）
- 「+ 新增列」「保存」按钮

调用：`GET /templates`、`PUT /templates/{id}`、`POST /templates/{id}/publish`、`POST /templates/{id}/archive?force=true`、`POST /templates/{id}/new-draft`、`PUT /templates/{id}/excel-view-config`、`POST /templates/{id}/excel-view-config/parse-header`

### 8.3 核价模板

#### 8.3.1 列表 `/costing-templates`
**工具栏**：分类 Select / 状态 Select / 「+ 新建模板」  
**表格列**：名称 ｜ 产品分类 ｜ 默认 Tag ｜ 版本 ｜ 状态 Tag ｜ 操作（配置/查看 / 发布 / 归档 / 删除）

**新建模板抽屉**：名称*｜产品分类*｜是否默认（同分类仅一个 Default）

#### 8.3.2 配置页 `/costing-templates/{id}`
**列配置表**（DRAFT 可编辑，PUBLISHED/ARCHIVED 只读）：
- 列 Key ｜ 列标题 ｜ 数据来源（VARIABLE / FORMULA / COMPARISON_TAG）｜ 内容（VARIABLE→变量路径 Input；FORMULA→公式 + 业务标签 Select；COMPARISON_TAG→标签 Select）｜ 排序 ｜ 操作（删除）
- 「+ 新增列」「保存」

调用：`POST /costing-templates`、`PUT /costing-templates/{id}`、`POST /costing-templates/{id}/publish`、`POST /costing-templates/{id}/archive`、`DELETE`

### 8.4 产品模板绑定 `/template-bindings`

**两栏**：
- 左：产品搜索 + 产品列表 → 选中后右侧加载
- 右：已配置工序展示 + 绑定列表表格（工序组合 / 模板 / 是否默认 / 操作：编辑 / 删除 / 设为默认）+ 「新建绑定」

**绑定 Drawer（3 步，宽 720）**：
- Step 0：流程选择（Checkbox 列表，按分类分组）
- Step 1：模板选择（已发布模板列表）
- Step 2：确认（显示选中流程 + 模板）
- 按钮：「上一步」「下一步」「完成」

调用：`GET /products/{id}/template-bindings`、`POST`、`DELETE`

### 8.5 模板版本对比 `/template-comparison`

**工具栏**：模板 A Select / 模板 B Select / 「⇄ 交换」/「对比」Button primary  
**对比结果**：
- 统计卡片：总差异数 / 新增 / 移除 / 变更
- 差异详情表（可展开，按字段维度展示）

### 8.6 基础数据配置 `/basic-data-config`

**两栏**：
- 左 Sider（280px）：「Sheet 配置」标题 + 「Excel 导入」「+ 新增」按钮 + Sheet 树（targetTable Tag，defaultExpandAll）
- 右 Content：选中 Sheet 后展示

**右侧顶部**：Sheet 名称｜「编辑 Sheet」｜「删除 Sheet」  
**说明行**：描述 ｜ 关联列 ｜ 目标表 Tag

**Tabs**：

#### Tab 1 属性配置
- 「+ 新增属性」按钮
- 表格：列 ｜ 表头 ｜ 变量编码（UNIQUE）｜ 变量标签 ｜ 类型 Tag（IDENTIFIER / VALUE）｜ BNF 路径（可复制）｜ 导入必填 Switch（disabled，仅展示）｜ 状态 ｜ 操作（编辑 / 禁用）

#### Tab 2 衍生字段
- 「+ 新增衍生字段」
- 表格：变量编码 ｜ 变量标签 ｜ 计算类型 Tag（LOOKUP / EXPRESSION / AGGREGATE）｜ 类型 ｜ 状态 ｜ 操作

**Sheet 编辑抽屉（宽 720）**：Sheet 名称*｜父级 Sheet｜关联列（逗号分隔）｜表头行号｜数据起始行号｜描述｜排序｜目标物理表 Select｜行级鉴别器 JSON

**属性编辑抽屉（宽 480）**：Excel 列字母*（A-Z, AA…）｜表头原文*｜变量编码*（编辑禁用）｜变量标签*｜字段类型*｜导入必填 Switch｜排序

**衍生字段编辑抽屉（宽 720）**：变量编码*｜变量标签*｜字段类型*｜计算类型*（LOOKUP / EXPRESSION / AGGREGATE）｜computation JSON*｜排序

**Excel 导入抽屉（宽 720）**：Upload.Dragger（.xlsx/.xls）→ 解析后展示 Sheet 列表（SheetName / 列数 / 「导入」按钮 / 已存在 Tag）→ 「关闭」

调用：`GET/POST/PUT/DELETE /basic-data-config/sheets`、`/attributes`、`/derived`、`POST /basic-data-config/parse-excel`

### 8.7 业务标签字典 `/comparison-tags`

**工具栏**：「+ 新增标签」  
**表格列**：分组 ｜ 编码 ｜ 标签 ｜ 类型 Tag（内置 / 自定义）｜ 状态 ｜ 操作（编辑 / 删除-仅自定义）

**新增 / 编辑抽屉（宽 600）**：
| 字段 | 说明 |
|---|---|
| 编码 | 必填，内置标签禁用 |
| 标签名称 | 必填 |
| 分组 | Select+Tags 模式（材料成本 / 加工费 / 其他费用 / 汇总）|
| 分组排序 / 组内排序 | InputNumber |
| 说明 | TextArea |
| 状态 | 默认 ACTIVE |

约束：内置 11 个标签不可改 code，不可删除（仅可禁用）。

---

## 九、主数据维护

### 9.1 数据总览 `/master-data`

**筛选**：客户 Select（支持空 = 全局基础资料）

**卡片组**（按 Group 分类：全局 / 客户级 / 元素）：每张卡片显示物理表名称 / 行数 / 最近更新时间 / 数据完整度  
**卡片点击** → 打开 TableDataDrawer（宽 1200）展示完整表格 + 「查看历史版本」按钮跳转 `/master-data/history?tableName=`

**底部**：最近 10 条导入记录列表

### 9.2 历史版本 `/master-data/history`

**筛选**：客户 Select / 表名 Select / HF 产品号 Search / 时间范围

**表格列**：产品号 ｜ 表名 ｜ 版本号 ｜ 修改人 ｜ 修改时间 ｜ 变更字段数 ｜ Checkbox（多选用于对比）｜ 操作

**操作按钮**：「查看详情」（EyeOutlined）→ RowDetailDrawer（宽 1200，表格视图 / JSON 视图切换） / 「对比版本」（DiffOutlined，需选 2 行启用） → VersionCompareDrawer / 「导出」（DownloadOutlined）

**VersionCompareDrawer（宽 1200）**：
- 标题：`版本对比 v{A} vs v{B}`
- Tabs：变更字段 / 所有字段
- 表格：字段 ｜ 旧值（橙高亮）｜ 新值（绿高亮）｜ 状态

调用：`GET /versioning/history`、`GET /versioning/row/{tableName}/{recordId}`、`GET /versioning/compare?tableName&hfPartNo&customerId&versionA&versionB`

### 9.3 字段重要性 `/master-data/field-importance`（仅 SYSTEM_ADMIN）

**筛选**：Sheet 表 Select  
**表格列**：字段名 ｜ 标签 ｜ 重要性 Tag（CRITICAL ⭐⭐ / IMPORTANT ⭐ / NORMAL）｜ 影响计算 Switch ｜ 操作（编辑）

**编辑字段重要性抽屉（宽 480）**：
- 字段名（禁用）
- 重要性级别 Select（CRITICAL / IMPORTANT / NORMAL）
- 影响计算 Switch
- 「保存」「取消」（修改需二次确认）

---

## 十、变更日志中心 `/change-log`

**筛选**：
- 客户 Select / 表名 Select / HF 产品号 / 字段名 / 重要性 MultiSelect / 变更来源 MultiSelect（V5_IMPORT / MANUAL_EDIT / DDL）/ 时间范围（默认近 7 天）

**工具栏**：「导出」下拉（Excel / CSV，超 10000 行返 422 错误）/ 「刷新」

**视图切换**：时序视图（默认）/ 按导入分组 / 按记录分组

**表格列**：
| 产品号 | 表名 | 字段 | 变更值（A→B 内联） | 变更来源 Tag | 重要性 Tag | 影响计算 ✓/✗ | 变更时间 | 操作人 | 操作 |

**字段名 Popover**：鼠标悬停"变更字段数"显示字段列表  
**操作**：「查看详情」（InfoCircleOutlined）→ 抽屉详情

调用：`GET /change-log/search`、`GET /change-log/export?format=EXCEL|CSV`

---

## 十一、数据源管理 `/datasources`（仅 SYSTEM_ADMIN）

### 11.1 列表
**工具栏**：类型 Select（SQL / API）/ 关键词搜索 / 「+ 新增」  
**表格列**：编码（code）｜ 名称 ｜ 类型 Tag ｜ 状态 Tag ｜ 创建时间 ｜ 操作（编辑 / 测试 PlayCircleOutlined / 删除 Popconfirm）

### 11.2 编辑 `/datasources/new` / `/datasources/{id}/edit`
**Form**：编码*｜名称*｜类型*（SQL / API）｜状态｜描述

**SQL 类型**：sqlQuery TextArea（多行 SQL）｜ sqlResultColumn

**API 类型**：URL ｜ 方法 Select（GET / POST）｜ 超时秒数 ｜ 响应路径（JSONPath）｜ Headers Input.TextArea（AES-256-GCM 加密存储）

**参数表**：参数编码 ｜ 名称 ｜ 来源 Select（系统参数 / 输入）｜ 是否必填 Switch  
**系统参数**：CURRENT_USER_ID / CURRENT_ORG_ID / CURRENT_DATE / CURRENT_YEAR

**按钮**：「保存」「测试」（PlayCircleOutlined）→ `POST /datasources/{id}/test`，弹抽屉显示结果 / 「返回列表」

---

## 十二、系统管理（仅 SYSTEM_ADMIN，部分子项 MGR 可见）

### 12.1 用户 / 区域 / 部门

#### 用户管理 `/system/users`
- 工具栏：「+ 新增用户」
- 表格列：用户名 ｜ 姓名 ｜ 邮箱 ｜ 角色 Tag ｜ 区域 ｜ 部门 ｜ 状态 ｜ 操作
- 操作：「编辑」（抽屉，宽 720）｜「停用 / 启用」(Popconfirm，调 `PATCH /users/{id}` 切 status)｜「重置密码」(Popconfirm) → 弹抽屉显示一次性初始密码
- 抽屉字段：用户名*（编辑禁用）｜姓名*｜邮箱*（格式校验）｜角色 Select*｜区域 TreeSelect｜部门 TreeSelect｜状态

#### 区域管理 `/system/regions`
- 「+ 新增」  
- 表格：编码｜名称｜排序｜状态｜操作
- 抽屉：编码*（编辑禁用）｜名称*｜排序

#### 部门管理 `/system/departments`
- 「+ 新增」
- 表格（树形）：名称｜编码｜排序｜状态｜操作（编辑 / 停用 / 删除）
- 抽屉：编码*（新增必填）｜名称*｜父部门 TreeSelect｜排序

### 12.2 审批规则 `/system/approval-rules`
- 「+ 新增规则」
- 表格：名称 ｜ 规则类型（FIXED 固定审批人 / DYNAMIC 动态匹配）｜ 优先级 ｜ 匹配字段 ｜ 状态 ｜ 操作（编辑 / 启用-禁用 / 删除）
- 抽屉：名称*｜规则类型*｜优先级｜匹配字段（区域 / 部门 / 客户等级）｜审批人 Select（FIXED 时显示）

### 12.3 通知列表 `/system/notifications`
- 「全部已读」按钮
- 表格：类型 Tag ｜ 标题 ｜ 内容 ｜ 状态（已读 / 未读，未读项加粗 + 蓝点）｜ 时间 ｜ 操作（标记已读，仅未读项）
- 分页：20 / 页

### 12.4 操作日志 `/system/operation-logs`（MGR / ADMIN）
- 筛选：操作类型 Select（CREATE / UPDATE / DELETE / APPROVE / REJECT / LOGIN / LOGOUT）｜ 目标类型（USER / CUSTOMER / PRODUCT / QUOTATION / TEMPLATE / COMPONENT）｜ 时间 RangePicker
- 表格：操作人 ID ｜ 操作类型 ｜ 目标类型 ｜ 目标 ID ｜ 详情 ｜ 时间

### 12.5 元素价格中心 `/element-price-center`
- 工具栏：「+ 录入参考价」（PlusOutlined，仅 SYSTEM_ADMIN）｜「刷新」
- 筛选：元素 Select（动态聚合 mat_bom 中 bom_type=ELEMENT 的元素）｜ 时间 RangePicker ｜ 货币
- 表格：元素名 ｜ 价格 ｜ 货币 ｜ 单位 ｜ 生效日期 ｜ 录入时间 ｜ 录入人 ｜ 备注 ｜ 操作

**ManualPriceEntryDrawer（宽 720）**：
- 元素 Select*（选择后自动填默认货币 / 单位）
- 价格*（InputNumber）
- 货币（默认 RMB）｜ 单位（默认克）
- 备注 TextArea
- 「保存」（同元素同日 UPSERT 覆盖）「取消」

调用：`POST /element-prices/manual`、`GET /element-prices/reference`、`GET /element-prices/history`、`GET /element-prices/elements`

### 12.6 系统配置中心 `/system-config`
- 工具栏：「+ 新增」｜「刷新」｜「批量重置」
- 筛选：分类 Select（validation / import / retention / element_price / business）
- 表格：配置 Key ｜ 分类 ｜ 描述 ｜ 值 ｜ 数据类型 Tag（STRING / NUMBER / BOOLEAN / JSON）｜ 操作（编辑 / 删除 / 恢复默认值）

**EditConfigDrawer（宽 600）**：
- 配置 Key（编辑禁用）
- 分类 Select
- 描述 TextArea
- 值（按数据类型动态渲染：String→Input / Number→InputNumber / Boolean→Switch / JSON→Monaco JSON 编辑器）
- 「保存」「取消」

约束：仅 SYSTEM_ADMIN 编辑全部；SALES_MANAGER 仅可改 `business.*` 分类（毛利率阈值）

### 12.7 锁监控 `/system-monitor/locks`

**Tabs**：

#### Tab 1 产品导入锁
- 表格：持锁人 ｜ 客户 ｜ 料号（或"客户级锁"）｜ 开始时间 ｜ 剩余 TTL（秒）｜ 操作（强制释放 Popconfirm）
- 30s 自动刷新

#### Tab 2 DDL 全局锁
- 显示状态：是否活跃｜持锁人｜到期时间
- 「Release Lock」Button（Popconfirm）

### 12.8 DDL 扩列管理 `/system-monitor/ddl-extension`
- 工具栏：「+ 新建扩列」（仅 SYSTEM_ADMIN）
- 描述：仅可对白名单表（mat_part / mat_bom / 等）扩列，新列不可 NOT NULL
- 表格（DdlHistoryList）：表名 ｜ 列名 ｜ 数据类型 ｜ 创建人 ｜ 创建时间 ｜ SQL ｜ 操作（复制 SQL）

**DdlExtensionWizardDrawer（宽 800，4 步 Steps）**：

| Step | 内容 | 按钮 |
|---|---|---|
| 1 选择目标表 | 可扩展表列表（白名单） | 下一步 |
| 2 字段信息 | 字段名（小写+数字+下划线）｜ 数据类型 Select（VARCHAR/DECIMAL/INT/BOOLEAN/DATE/TIMESTAMP/JSONB）｜ 长度/精度（依类型）｜ 注释 | 上一步 / 下一步 |
| 3 重要性配置 | 重要性 Select（默认 NORMAL）｜ 影响计算 Switch（默认否） | 上一步 / 下一步 |
| 4 预览 & 确认 | 显示生成的 ALTER TABLE SQL | 上一步 / 「复制 Migration SQL」 / 「执行」 |

约束：执行期间所有新导入请求被拒（DDL 锁互斥）；不允许添加同名列。

---

## 十三、按钮可用性矩阵（速查）

| 按钮 / 操作 | 启用条件 |
|---|---|
| 报价单「编辑」 | DRAFT / SUBMITTED（销售本人撤回后）/ REJECTED |
| 报价单「提交审批」 | DRAFT + 无 ERROR 单元格 + 无漂移横幅 |
| 报价单「通过 / 退回」 | SUBMITTED + 当前用户=指派审批人 或 SYSTEM_ADMIN |
| 报价单「撤回」 | SUBMITTED + 当前用户=销售代表本人 |
| 报价单「请求撤回」 | APPROVED + 销售代表 + 无 PENDING 撤回 |
| 报价单「同意 / 拒绝撤回」 | PENDING 撤回 + 原审批人 或 SYSTEM_ADMIN |
| 报价单「发送给客户」 | APPROVED |
| 报价单「接受 / 拒绝」 | SENT + 销售代表本人 |
| 报价单「延期」 | APPROVED / SENT |
| 报价单「删除草稿」 | DRAFT + 创建人本人 或 SYSTEM_ADMIN |
| 报价单「重新导入基础数据」 | DRAFT |
| 模板配置 / 核价模板「编辑 / 删除」 | DRAFT |
| 模板「发布」 | DRAFT → PUBLISHED（同 customer×category 仅 1 个 PUBLISHED） |
| 模板「归档」 | PUBLISHED + 无 in-progress 报价单（除非 force=true） |
| 客户「停用」 | 无 DRAFT / SUBMITTED / APPROVED 报价单 |
| 产品「删除」 | 无 in-progress 报价单引用 |
| 元素价格「录入参考价」 | SYSTEM_ADMIN 角色 |
| 系统配置「编辑（非 business）」 | SYSTEM_ADMIN |
| 系统配置「编辑（business）」 | SYSTEM_ADMIN 或 SALES_MANAGER |
| DDL 扩列「执行」 | SYSTEM_ADMIN + DDL 锁可获取 |
| 锁监控「强制释放」 | SYSTEM_ADMIN |
| 字段重要性「编辑」 | SYSTEM_ADMIN |
| 变更日志「导出」 | SALES_MANAGER / SYSTEM_ADMIN + 导出行数 ≤ 10000 |
| 业务标签「删除」 | 自定义标签（非内置）|

---

## 十四、跨页关键操作流程图

### 14.1 销售完整报价流程

```
[报价单管理列表] 「从基础数据导入」
        │
        ▼
[V5 主抽屉 Step UPLOAD]
  选客户 → 上传 Excel → 「开始预览」
  POST /quotations/import-basic-data
        │
        ▼
[Step 差异 (UI-2 子抽屉 960)]
  CRITICAL 备注必填 → 「下一步」
        │
        ▼
[Step 冲突 (UI-1 子抽屉 1200)]
  字段级决策 → 草稿自动保存（500ms 防抖）→ 「确认导入」
  POST /quotations/confirm-basic-data-import
        │
        ▼
[Step DONE → CreateQuotationDrawer 480]
  填名称 / 选分类 / 选模板 → 「确认创建」
  跳转 /quotations/{id}/edit?autoPopulate=1
        │
        ▼
[QuotationWizard Step 2 产品配置]
  四视图切换：产品卡片 / Excel / 核价表 / 比对
  漂移横幅 → 「使用最新版本」
  ElementPriceHint 提示参考价 → 销售手填元素单价
        │
        ▼
[Step 3 优惠] → [Step 4 交易] → [Step 5 提交]
  「提交审批」（无 ERROR + 无漂移）
  POST /quotations/{id}/submit
        │
        ▼
[销售经理工作台「待我审批」]
  详情页「通过 / 退回」抽屉
  POST /approve  或  POST /reject { reason }
        │
        ▼
[APPROVED] → 「发送给客户」邮件抽屉 → SENT
        │
        ▼
[客户接受] → SENT → 「接受报价」→ ACCEPTED
  原子 SQL 累加 customer.accumulated_amount
```

### 14.2 撤回审批流程

```
[报价单详情 APPROVED]
  销售代表 → 「请求撤回」抽屉填原因
  POST /withdraw-request { reason }
        │
        ▼
[原审批人收到通知 → 报价单详情 PENDING]
  「同意撤回」→ POST /withdraw/approve → 状态回 DRAFT
  「拒绝撤回」→ POST /withdraw/reject  → 保持 APPROVED
```

### 14.3 主数据漂移检测流程

```
[报价单 DRAFT 顶部漂移黄色横幅]
  ↑ 触发条件：引用的 customer-level 数据版本已被新导入覆盖
        │
        ▼
点击横幅 → 「查看变更详情」抽屉
  跳转 [变更日志中心] 已预填 customer + hf_part_no 过滤
        │
        ▼
返回报价单 → 「使用最新版本」更新引用
  漂移横幅消失 → 提交按钮启用
```

### 14.4 DDL 扩列流程（管理员）

```
[DDL 扩列管理] 「+ 新建扩列」
        │
Step 1 选表 → Step 2 列定义 → Step 3 重要性 → Step 4 预览 SQL
        │
「执行」→ 申请 DDL 全局锁
        │
        ▼ （锁活跃期间，所有新导入请求 423 拒绝）
ALTER TABLE <table> ADD COLUMN <col> <type>
        │
        ▼
释放锁 → 「复制 Migration SQL」补到代码库
```

---

**文档结束** | 与 `docs/操作说明.md`（用户面向）互补，本文档面向 QA / 测试工程师。变更需同步更新 `docs/PRD.md` 与本文档版本号。
