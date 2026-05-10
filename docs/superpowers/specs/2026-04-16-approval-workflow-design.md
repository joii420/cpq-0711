# 报价审批流程完善 — 设计文档

> **日期**: 2026-04-16
> **范围**: 审批规则配置页重做、用户管理补区域/部门、审批操作页面、审批进度展示、状态标签优化、撤回功能

---

## 背景

报价生成器提交审批后，系统缺少审批操作界面和进度展示。后端 approve/reject 接口已就绪，前端 service 方法已定义但未接入 UI。本次补全审批流程的前端交互和少量后端增强。

## 现状

- **后端已实现**: submit、approve、reject 端点；JavaApprovalRoutingService 审批路由；QuotationApproval 实体和表
- **前端已实现**: quotationService.approve()/reject() 方法定义（未调用）；QuotationList "待我审批" Tab（仅 SALES_MANAGER 可见，无操作按钮）；QuotationDetail 审批历史表格（无审批按钮）
- **缺失**: 审批操作按钮、审批进度展示、SYSTEM_ADMIN 审批入口、撤回功能

---

## 设计

### 0. 审批基础设施修复

#### 0.1 用户管理补充区域/部门选择

**问题**：User 实体已有 regionId/departmentId 字段，但 UserManagement.tsx 没有区域/部门选择框，导致用户无法设置所属区域/部门，DYNAMIC 审批规则永远匹配不上。

**修复**：
- UserManagement.tsx 新增/编辑用户表单中增加"所属区域"和"所属部门"下拉选择框
- 下拉数据源：调用 Region/Department 字典接口，仅展示 ACTIVE 状态
- 非必填（PRD 中未标记为必填）

#### 0.2 审批规则配置页重做

**问题**：前端规则类型（GLOBAL/REGION/CUSTOMER/AMOUNT）与后端（FIXED/DYNAMIC）不匹配；审批人和匹配值需手动输入 UUID。

**重做内容**：
- 规则类型改为 `FIXED`（固定审批人）/ `DYNAMIC`（动态规则），与后端对齐
- FIXED 类型表单：审批人（下拉选择销售经理列表）+ 优先级
- DYNAMIC 类型表单：匹配字段（REGION/DEPARTMENT 下拉）+ 匹配值（从区域/部门字典下拉选择）+ 审批人（下拉选择销售经理列表）+ 优先级
- 列表展示审批人姓名、匹配字段中文名、匹配值名称（不是 UUID）
- 顶部提示"当前兜底审批人：{最早系统管理员姓名}"

**后端辅助接口**：
- 需要一个获取销售经理列表的接口（`GET /api/cpq/users?role=SALES_MANAGER&status=ACTIVE`），现有 UserResource 如已支持按 role 过滤则直接复用

### 1. 状态标签优化

前端 statusMap 调整：

| 英文状态 | 原标签 | 改为 | 理由 |
|----------|--------|------|------|
| SUBMITTED | 已提交 | **审批中** | 明确处于内部审批流程 |
| REJECTED | 已驳回 | **已退回** | 与 PRD "退回"用语对齐 |

涉及文件：`QuotationList.tsx`、`QuotationDetail.tsx` 中的 statusMap。

### 2. 审批人操作页面

#### 2.1 QuotationList 列表快捷审批

**"待我审批" Tab 可见性**：

| 角色 | 可见 | 筛选逻辑 |
|------|------|----------|
| SALES_MANAGER | 是 | `assigned_approver_id = 当前用户 AND status = SUBMITTED` |
| SYSTEM_ADMIN | 是 | `status = SUBMITTED`（全部） |
| SALES_REP | 否 | — |
| PRICING_MANAGER | 否 | — |

**操作按钮**（仅"待我审批" Tab 内的 SUBMITTED 行显示）：
- "通过"按钮（绿色）→ 确认弹窗 → 调用 `quotationService.approve(id)`
- "退回"按钮（红色）→ 弹窗填写退回原因（必填）→ 调用 `quotationService.reject(id, comment)`
- 操作成功后刷新列表

#### 2.2 QuotationDetail 详情页审批

顶部操作栏，SUBMITTED 状态下动态按钮规则：

| 条件 | 按钮展示 |
|------|----------|
| 当前用户 == assigned_approver_id | 显示"通过"+"退回" |
| 当前用户 role == SYSTEM_ADMIN | 显示"通过"+"退回"（不受 assigned_approver_id 限制） |
| 当前用户 role == SALES_MANAGER 但不是该单审批人 | 按钮置灰 + tooltip "该报价单不在您的审批范围内" |
| 其他角色 | 不显示审批按钮 |

审批操作交互：
- "通过" → 确认弹窗（可选填审批意见）→ 调用 approve → 刷新页面
- "退回" → 弹窗填写退回原因（必填）→ 调用 reject → 刷新页面

### 3. 审批进度展示

QuotationDetail 页面审批历史区上方新增审批状态卡片：

| 报价单状态 | 卡片内容 |
|------------|----------|
| SUBMITTED | "审批中 — 审批人：{full_name}，已等待 {时长}" |
| APPROVED | "已通过 — 审批人：{full_name}，{通过时间}" |
| DRAFT（有退回记录时） | "上次审批被退回 — 原因：{comment}" |

**后端变更**：`GET /quotations/{id}` 响应新增 `assignedApproverName` 字段（关联查询 User.full_name）。现有 `assignedApproverId` 保持不变。

### 4. 撤回功能

#### 4.1 后端

新增端点：`POST /api/cpq/quotations/{id}/withdraw`

校验规则：
- 报价单状态必须为 SUBMITTED
- 操作人必须是 sales_rep_id（创建人本人）

操作：
- 状态变更：SUBMITTED → DRAFT
- 清空 assigned_approver_id
- 写入 QuotationApproval 记录（action=WITHDRAWN）
- 记录操作日志

数据库变更：
- Flyway 迁移：修改 quotation_approval 表的 action CHECK 约束，新增 WITHDRAWN 枚举值

#### 4.2 前端

- `quotationService` 新增 `withdraw(id)` 方法
- QuotationDetail：SUBMITTED + 当前用户是创建人 → 显示"撤回"按钮
- QuotationList：SUBMITTED 行 + 当前用户是创建人 → 显示"撤回"操作
- 确认弹窗："撤回后报价单将回到草稿状态，需重新提交审批。是否继续？"

#### 4.3 PRD 更新

状态机新增：`SUBMITTED → DRAFT（销售代表撤回）`

---

## 权限总览

| 角色 | 列表数据范围 | "待我审批" Tab | 审批操作 | 撤回操作 |
|------|-------------|---------------|---------|---------|
| SALES_REP | 仅本人创建 | 不可见 | 无 | 仅本人创建的 SUBMITTED 报价单 |
| SALES_MANAGER | 所有报价单 | 可见（assigned_approver_id = 自己） | 仅路由到自己的 | 无 |
| PRICING_MANAGER | 所有报价单（只读） | 不可见 | 无 | 无 |
| SYSTEM_ADMIN | 所有报价单 | 可见（全部 SUBMITTED） | 任意报价单 | 无 |

---

## 涉及文件清单

### 后端
- `QuotationResource.java` — 新增 withdraw 端点
- `QuotationService.java` — 新增 withdraw 逻辑；getById 补充 assignedApproverName
- `QuotationApproval.java` — action 枚举新增 WITHDRAWN
- 新增 Flyway 迁移 — 修改 quotation_approval action CHECK 约束

### 前端
- `UserManagement.tsx` — 新增区域/部门下拉选择框
- `ApprovalRuleManagement.tsx` — 重做：规则类型对齐 FIXED/DYNAMIC，审批人/匹配值改为下拉选择
- `QuotationList.tsx` — statusMap 修改；"待我审批" Tab 权限扩展；审批/撤回操作按钮
- `QuotationDetail.tsx` — statusMap 修改；审批操作按钮；审批进度卡片；撤回按钮
- `quotationService.ts` — 新增 withdraw 方法

### 文档
- `docs/PRD.md` — 状态机新增撤回，变更日志
- `docs/RECORD.md` — 记录本次开发内容
