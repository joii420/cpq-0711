# 报价审批流程完善 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补全报价审批流程：审批规则配置可用化、审批操作页面、审批进度展示、状态标签优化、撤回功能。

**Architecture:** 后端方案 A（纯 Java），不引入 Camunda。后端已有 approve/reject/submit 端点和审批路由服务，本次主要是前端接通 + 后端小量增强（withdraw 端点、assignedApproverName 字段、Flyway 迁移）。

**Tech Stack:** Java 17 + Quarkus, React 18 + Ant Design 5 + Zustand, PostgreSQL 16, Flyway

**Spec:** `docs/superpowers/specs/2026-04-16-approval-workflow-design.md`

---

### Task 1: Flyway 迁移 — quotation_approval 表 action 约束新增 WITHDRAWN

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V17__add_withdrawn_approval_action.sql`

- [ ] **Step 1: 创建迁移文件**

```sql
-- V17__add_withdrawn_approval_action.sql
-- Add WITHDRAWN to quotation_approval action CHECK constraint for withdraw feature
ALTER TABLE quotation_approval DROP CONSTRAINT chk_qa_action;
ALTER TABLE quotation_approval ADD CONSTRAINT chk_qa_action CHECK (action IN ('APPROVED','REJECTED','WITHDRAWN'));
```

- [ ] **Step 2: 验证迁移文件语法**

Run: `cd D:/a-joii/project/CPQ-superpowers/dev/cpq-backend && ./mvnw quarkus:dev -Dquarkus.http.port=8081 &`
Expected: Flyway 执行 V17 迁移成功，无报错

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V17__add_withdrawn_approval_action.sql
git commit -m "feat(approval): add WITHDRAWN to quotation_approval action constraint (V17)"
```

---

### Task 2: 后端 — withdraw 端点 + assignedApproverName

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/dto/QuotationDTO.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java`

- [ ] **Step 1: QuotationDTO 新增 assignedApproverName 字段**

在 `QuotationDTO.java` 的 `assignedApproverId` 字段之后添加：

```java
public String assignedApproverName;
```

- [ ] **Step 2: QuotationService — getById 补充 assignedApproverName**

在 `QuotationService.java` 的 `getById` 方法中，`QuotationDTO.from(q)` 之后、返回之前，查询审批人姓名：

```java
if (q.assignedApproverId != null) {
    User approver = User.findById(q.assignedApproverId);
    if (approver != null) {
        dto.assignedApproverName = approver.fullName;
    }
}
```

- [ ] **Step 3: QuotationService — 新增 withdraw 方法**

在 `QuotationService.java` 中 `reject` 方法之后添加：

```java
@Transactional
public QuotationDTO withdraw(UUID id, UUID currentUserId) {
    Quotation q = Quotation.findById(id);
    if (q == null) {
        throw new BusinessException(404, "Quotation not found: " + id);
    }
    if (!"SUBMITTED".equals(q.status)) {
        throw new BusinessException(400, "Only SUBMITTED quotations can be withdrawn");
    }
    if (!q.salesRepId.equals(currentUserId)) {
        throw new BusinessException(403, "Only the creator can withdraw this quotation");
    }

    q.status = "DRAFT";
    q.assignedApproverId = null;

    QuotationApproval record = new QuotationApproval();
    record.quotationId = id;
    record.approverId = currentUserId;
    record.action = "WITHDRAWN";
    record.comment = "销售代表撤回";
    record.actedAt = OffsetDateTime.now();
    record.persist();

    LOG.infof("Withdrawn quotation id=%s number=%s by user=%s", id, q.quotationNumber, currentUserId);
    QuotationDTO dto = QuotationDTO.from(q);
    dto.lineItems = loadLineItems(id);
    return dto;
}
```

- [ ] **Step 4: QuotationResource — 新增 withdraw 端点**

在 `QuotationResource.java` 中 `reject` 端点之后添加：

```java
@POST
@Path("/{id}/withdraw")
public ApiResponse<QuotationDTO> withdraw(@PathParam("id") UUID id, @Context HttpServerRequest request) {
    UUID currentUserId = sessionHelper.getCurrentUserId(request);
    return ApiResponse.success(quotationService.withdraw(id, currentUserId));
}
```

- [ ] **Step 5: 验证端点**

使用 curl 测试 withdraw 端点（需要一个 SUBMITTED 状态的报价单）：
```bash
curl -X POST http://localhost:8081/api/cpq/quotations/{id}/withdraw -H "Cookie: CPQ_SESSION=..." -v
```
Expected: 200 OK, status 变为 DRAFT

- [ ] **Step 6: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/dto/QuotationDTO.java cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java
git commit -m "feat(approval): add withdraw endpoint and assignedApproverName field"
```

---

### Task 3: 后端 — approve/reject 增加操作人权限校验

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java`

当前 approve/reject 没有校验操作人身份，任何人都能调。需要增加权限校验。

- [ ] **Step 1: QuotationService.approve 增加 currentUserId 参数和校验**

修改 `approve` 方法签名和校验逻辑：

```java
@Transactional
public QuotationDTO approve(UUID id, String comment, UUID currentUserId) {
    Quotation q = Quotation.findById(id);
    if (q == null) {
        throw new BusinessException(404, "Quotation not found: " + id);
    }
    if (!"SUBMITTED".equals(q.status)) {
        throw new BusinessException(400, "Only SUBMITTED quotations can be approved");
    }
    // Permission check: assigned approver or SYSTEM_ADMIN
    User currentUser = User.findById(currentUserId);
    boolean isAdmin = currentUser != null && "SYSTEM_ADMIN".equals(currentUser.role);
    boolean isAssignedApprover = currentUserId.equals(q.assignedApproverId);
    if (!isAdmin && !isAssignedApprover) {
        throw new BusinessException(403, "You are not authorized to approve this quotation");
    }

    q.status = "APPROVED";

    QuotationApproval approval = new QuotationApproval();
    approval.quotationId = id;
    approval.approverId = currentUserId;
    approval.action = "APPROVED";
    approval.comment = comment;
    approval.actedAt = OffsetDateTime.now();
    approval.persist();

    LOG.infof("Approved quotation id=%s number=%s by=%s", id, q.quotationNumber, currentUserId);
    QuotationDTO dto = QuotationDTO.from(q);
    dto.lineItems = loadLineItems(id);
    return dto;
}
```

- [ ] **Step 2: QuotationService.reject 同样增加校验**

修改 `reject` 方法签名，增加相同的权限校验逻辑（isAdmin || isAssignedApprover），approverId 改为 currentUserId。

```java
@Transactional
public QuotationDTO reject(UUID id, String comment, UUID currentUserId) {
    Quotation q = Quotation.findById(id);
    if (q == null) {
        throw new BusinessException(404, "Quotation not found: " + id);
    }
    if (!"SUBMITTED".equals(q.status)) {
        throw new BusinessException(400, "Only SUBMITTED quotations can be rejected");
    }
    User currentUser = User.findById(currentUserId);
    boolean isAdmin = currentUser != null && "SYSTEM_ADMIN".equals(currentUser.role);
    boolean isAssignedApprover = currentUserId.equals(q.assignedApproverId);
    if (!isAdmin && !isAssignedApprover) {
        throw new BusinessException(403, "You are not authorized to reject this quotation");
    }

    q.status = "DRAFT";

    QuotationApproval approval = new QuotationApproval();
    approval.quotationId = id;
    approval.approverId = currentUserId;
    approval.action = "REJECTED";
    approval.comment = comment;
    approval.actedAt = OffsetDateTime.now();
    approval.persist();

    LOG.infof("Rejected quotation id=%s number=%s reason=%s by=%s", id, q.quotationNumber, comment, currentUserId);
    QuotationDTO dto = QuotationDTO.from(q);
    dto.lineItems = loadLineItems(id);
    return dto;
}
```

- [ ] **Step 3: QuotationResource — approve/reject 端点传入 currentUserId**

修改 approve 端点：
```java
@POST
@Path("/{id}/approve")
public ApiResponse<QuotationDTO> approve(@PathParam("id") UUID id, Map<String, String> body, @Context HttpServerRequest request) {
    UUID currentUserId = sessionHelper.getCurrentUserId(request);
    String comment = body != null ? body.get("comment") : null;
    return ApiResponse.success(quotationService.approve(id, comment, currentUserId));
}
```

修改 reject 端点：
```java
@POST
@Path("/{id}/reject")
public ApiResponse<QuotationDTO> reject(@PathParam("id") UUID id, Map<String, String> body, @Context HttpServerRequest request) {
    UUID currentUserId = sessionHelper.getCurrentUserId(request);
    String comment = body != null ? body.get("comment") : null;
    return ApiResponse.success(quotationService.reject(id, comment, currentUserId));
}
```

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java cpq-backend/src/main/java/com/cpq/quotation/resource/QuotationResource.java
git commit -m "feat(approval): add permission checks to approve/reject endpoints"
```

---

### Task 4: 前端 — quotationService 新增 withdraw 方法

**Files:**
- Modify: `cpq-frontend/src/services/quotationService.ts`

- [ ] **Step 1: 添加 withdraw 方法**

在 `quotationService.ts` 的 `reject` 方法之后添加：

```typescript
withdraw: (id: string) => api.post(`/quotations/${id}/withdraw`) as Promise<any>,
```

- [ ] **Step 2: Commit**

```bash
git add cpq-frontend/src/services/quotationService.ts
git commit -m "feat(approval): add withdraw method to quotationService"
```

---

### Task 5: 前端 — 状态标签优化

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationList.tsx`
- Modify: `cpq-frontend/src/pages/quotation/QuotationDetail.tsx`

- [ ] **Step 1: QuotationList.tsx — 修改 statusMap 和 statusTabs**

`statusMap` 修改两处：
```typescript
SUBMITTED: { label: '审批中', color: 'processing' },
```
```typescript
REJECTED: { label: '已退回', color: 'error' },
```

`statusTabs` 修改两处：
```typescript
{ key: 'SUBMITTED', label: '审批中' },
```
```typescript
{ key: 'REJECTED', label: '已退回' },
```

- [ ] **Step 2: QuotationDetail.tsx — 修改 statusConfig 和 approvalActionMap**

`statusConfig` 修改两处：
```typescript
SUBMITTED: { label: '审批中', color: 'processing' },
```
```typescript
REJECTED: { label: '已退回', color: 'error' },
```

`approvalActionMap` 修改一处并新增 WITHDRAWN：
```typescript
REJECTED: { label: '退回', color: 'error' },
WITHDRAWN: { label: '撤回', color: 'default' },
```

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationList.tsx cpq-frontend/src/pages/quotation/QuotationDetail.tsx
git commit -m "feat(approval): rename status labels - SUBMITTED='审批中', REJECTED='已退回'"
```

---

### Task 6: 前端 — UserManagement 补充区域/部门选择

**Files:**
- Modify: `cpq-frontend/src/pages/system/UserManagement.tsx`

- [ ] **Step 1: 导入 regionService 和 departmentService，加载字典数据**

在文件顶部添加导入：
```typescript
import { regionService } from '../../services/regionService';
import { departmentService } from '../../services/departmentService';
```

在组件内部 `const [params, ...]` 之后添加状态：
```typescript
const [regions, setRegions] = useState<any[]>([]);
const [departments, setDepartments] = useState<any[]>([]);
```

在 `useEffect(() => { fetchData(); }, [params]);` 之后添加新的 useEffect：
```typescript
useEffect(() => {
  regionService.list({ page: 0, size: 1000 }).then(res => {
    setRegions((res.data?.content || []).filter((r: any) => r.status === 'ACTIVE'));
  });
  departmentService.list({ page: 0, size: 1000 }).then(res => {
    setDepartments((res.data?.content || []).filter((d: any) => d.status === 'ACTIVE'));
  });
}, []);
```

- [ ] **Step 2: 列表表格增加区域/部门列**

在 columns 数组中状态列之前插入两列：
```typescript
{
  title: '区域', dataIndex: 'regionId', key: 'region',
  render: (v: string) => regions.find(r => r.id === v)?.name || '-',
},
{
  title: '部门', dataIndex: 'departmentId', key: 'department',
  render: (v: string) => departments.find(d => d.id === v)?.name || '-',
},
```

- [ ] **Step 3: 表单增加区域/部门下拉框**

在 Form 中 `role` 选择框之后、`保存` 按钮之前添加：
```tsx
<Form.Item name="regionId" label="所属区域">
  <Select placeholder="请选择区域" allowClear>
    {regions.map(r => <Select.Option key={r.id} value={r.id}>{r.name}</Select.Option>)}
  </Select>
</Form.Item>
<Form.Item name="departmentId" label="所属部门">
  <Select placeholder="请选择部门" allowClear>
    {departments.map(d => <Select.Option key={d.id} value={d.id}>{d.name}</Select.Option>)}
  </Select>
</Form.Item>
```

- [ ] **Step 4: 浏览器验证**

打开 http://localhost:5174，进入系统管理 → 用户管理：
- 新增/编辑用户时能看到区域和部门下拉框
- 列表中能看到区域和部门列
- 选择后保存成功

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/system/UserManagement.tsx
git commit -m "feat(user): add region/department select to UserManagement"
```

---

### Task 7: 前端 — 审批规则配置页重做

**Files:**
- Modify: `cpq-frontend/src/pages/system/ApprovalRuleManagement.tsx`

- [ ] **Step 1: 完整重写 ApprovalRuleManagement.tsx**

```tsx
import React, { useEffect, useState } from 'react';
import {
  Table, Button, Modal, Form, Input, Select, Space, Popconfirm, message, Card, Tag, Alert,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { approvalRuleService } from '../../services/approvalRuleService';
import { userService } from '../../services/userService';
import { regionService } from '../../services/regionService';
import { departmentService } from '../../services/departmentService';

const ruleTypeOptions = [
  { label: '固定审批人', value: 'FIXED' },
  { label: '动态规则', value: 'DYNAMIC' },
];

const matchFieldOptions = [
  { label: '区域', value: 'REGION' },
  { label: '部门', value: 'DEPARTMENT' },
];

const ApprovalRuleManagement: React.FC = () => {
  const [data, setData] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingRule, setEditingRule] = useState<any>(null);
  const [form] = Form.useForm();
  const [ruleType, setRuleType] = useState<string>('');
  const [matchField, setMatchField] = useState<string>('');

  // Lookup data
  const [managers, setManagers] = useState<any[]>([]);
  const [regions, setRegions] = useState<any[]>([]);
  const [departments, setDepartments] = useState<any[]>([]);
  const [fallbackAdmin, setFallbackAdmin] = useState<string>('');

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await approvalRuleService.list();
      setData(res.data || []);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  const fetchLookups = async () => {
    try {
      const [mgrRes, regRes, deptRes, adminRes] = await Promise.all([
        userService.list({ role: 'SALES_MANAGER', status: 'ACTIVE', size: 1000 }),
        regionService.list({ page: 0, size: 1000 }),
        departmentService.list({ page: 0, size: 1000 }),
        userService.list({ role: 'SYSTEM_ADMIN', status: 'ACTIVE', size: 1 }),
      ]);
      setManagers(mgrRes.data?.content || []);
      setRegions((regRes.data?.content || []).filter((r: any) => r.status === 'ACTIVE'));
      setDepartments((deptRes.data?.content || []).filter((d: any) => d.status === 'ACTIVE'));
      const admins = adminRes.data?.content || [];
      setFallbackAdmin(admins.length > 0 ? admins[0].fullName : '无');
    } catch (e: any) {
      message.error('加载配置数据失败: ' + e.message);
    }
  };

  useEffect(() => { fetchData(); fetchLookups(); }, []);

  const handleOpen = (rule?: any) => {
    setEditingRule(rule || null);
    if (rule) {
      form.setFieldsValue(rule);
      setRuleType(rule.ruleType || '');
      setMatchField(rule.matchField || '');
    } else {
      form.resetFields();
      form.setFieldsValue({ priority: 100 });
      setRuleType('');
      setMatchField('');
    }
    setModalOpen(true);
  };

  const handleSave = async (values: any) => {
    // Clean fields based on rule type
    const payload = { ...values };
    if (payload.ruleType === 'FIXED') {
      payload.matchField = null;
      payload.matchValueId = null;
    }
    try {
      if (editingRule) {
        await approvalRuleService.update(editingRule.id, payload);
        message.success('更新成功');
      } else {
        await approvalRuleService.create(payload);
        message.success('创建成功');
      }
      setModalOpen(false);
      form.resetFields();
      setEditingRule(null);
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await approvalRuleService.delete(id);
      message.success('删除成功');
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  // Lookup helpers
  const getManagerName = (id: string) => managers.find(m => m.id === id)?.fullName || id?.substring(0, 8) + '...';
  const getMatchValueName = (field: string, id: string) => {
    if (field === 'REGION') return regions.find(r => r.id === id)?.name || id;
    if (field === 'DEPARTMENT') return departments.find(d => d.id === id)?.name || id;
    return id || '-';
  };

  const matchValueOptions = matchField === 'REGION'
    ? regions.map(r => ({ label: r.name, value: r.id }))
    : matchField === 'DEPARTMENT'
      ? departments.map(d => ({ label: d.name, value: d.id }))
      : [];

  const columns = [
    {
      title: '优先级', dataIndex: 'priority', key: 'priority', width: 80,
      sorter: (a: any, b: any) => a.priority - b.priority,
      defaultSortOrder: 'ascend' as const,
    },
    {
      title: '规则类型', dataIndex: 'ruleType', key: 'ruleType', width: 120,
      render: (v: string) => {
        const opt = ruleTypeOptions.find(o => o.value === v);
        return <Tag color={v === 'FIXED' ? 'blue' : 'green'}>{opt?.label || v}</Tag>;
      },
    },
    {
      title: '审批人', dataIndex: 'approverId', key: 'approver',
      render: (v: string) => getManagerName(v),
    },
    {
      title: '匹配条件', key: 'matchCondition',
      render: (_: any, record: any) => {
        if (record.ruleType === 'FIXED') return <Tag>所有报价单</Tag>;
        const fieldLabel = matchFieldOptions.find(o => o.value === record.matchField)?.label || record.matchField;
        const valueName = getMatchValueName(record.matchField, record.matchValueId);
        return <span>{fieldLabel} = {valueName}</span>;
      },
    },
    {
      title: '操作', key: 'actions', width: 140,
      render: (_: any, record: any) => (
        <Space size="small">
          <Button size="small" icon={<EditOutlined />} onClick={() => handleOpen(record)}>编辑</Button>
          <Popconfirm title="确认删除？" onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card
      title="审批规则管理"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => handleOpen()}>新增规则</Button>
      }
    >
      <Alert
        message={`兜底审批人：${fallbackAdmin}（无规则匹配时自动路由给系统管理员）`}
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
      />
      <Table rowKey="id" columns={columns} dataSource={data} loading={loading} pagination={false} />

      <Modal
        title={editingRule ? '编辑审批规则' : '新增审批规则'}
        open={modalOpen}
        onCancel={() => { setModalOpen(false); form.resetFields(); setEditingRule(null); }}
        onOk={() => form.submit()}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="ruleType" label="规则类型" rules={[{ required: true, message: '请选择规则类型' }]}>
            <Select options={ruleTypeOptions} placeholder="请选择规则类型" onChange={(v: string) => { setRuleType(v); setMatchField(''); form.setFieldsValue({ matchField: undefined, matchValueId: undefined }); }} />
          </Form.Item>
          <Form.Item name="approverId" label="审批人" rules={[{ required: true, message: '请选择审批人' }]}>
            <Select placeholder="请选择销售经理" showSearch optionFilterProp="children">
              {managers.map(m => <Select.Option key={m.id} value={m.id}>{m.fullName}（{m.username}）</Select.Option>)}
            </Select>
          </Form.Item>
          {ruleType === 'DYNAMIC' && (
            <>
              <Form.Item name="matchField" label="匹配维度" rules={[{ required: true, message: '请选择匹配维度' }]}>
                <Select options={matchFieldOptions} placeholder="请选择匹配维度" onChange={(v: string) => { setMatchField(v); form.setFieldsValue({ matchValueId: undefined }); }} />
              </Form.Item>
              <Form.Item name="matchValueId" label="匹配值" rules={[{ required: true, message: '请选择匹配值' }]}>
                <Select options={matchValueOptions} placeholder="请选择匹配值" showSearch optionFilterProp="label" />
              </Form.Item>
            </>
          )}
          <Form.Item name="priority" label="优先级（数值越小越优先）" rules={[{ required: true, message: '请输入优先级' }]}>
            <Input type="number" placeholder="100" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
};

export default ApprovalRuleManagement;
```

- [ ] **Step 2: 浏览器验证**

打开 http://localhost:5174，进入系统管理 → 审批规则管理：
- 顶部显示兜底审批人名称
- 新增规则时可选 FIXED/DYNAMIC
- 审批人是下拉选择销售经理列表
- DYNAMIC 规则能选区域或部门 + 匹配值
- 列表展示人名而非 UUID

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/system/ApprovalRuleManagement.tsx
git commit -m "feat(approval): rewrite ApprovalRuleManagement with FIXED/DYNAMIC types and dropdown selects"
```

---

### Task 8: 前端 — QuotationList 审批操作 + 撤回 + 待我审批扩展

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationList.tsx`

- [ ] **Step 1: "待我审批" Tab 扩展给 SYSTEM_ADMIN**

修改第 235-237 行，将：
```typescript
...(user?.role === 'SALES_MANAGER'
  ? [{ key: PENDING_APPROVAL_TAB, label: '待我审批' }]
  : []),
```
改为：
```typescript
...(['SALES_MANAGER', 'SYSTEM_ADMIN'].includes(user?.role || '')
  ? [{ key: PENDING_APPROVAL_TAB, label: '待我审批' }]
  : []),
```

- [ ] **Step 2: 修改 loadData 中 "待我审批" 筛选逻辑**

修改 `loadData` 函数中 `assignedApproverId` 的传值逻辑。将：
```typescript
assignedApproverId: isPendingApprovalTab && user ? user.id : undefined,
```
改为（SYSTEM_ADMIN 看所有 SUBMITTED，不传 assignedApproverId）：
```typescript
assignedApproverId: isPendingApprovalTab && user && user.role !== 'SYSTEM_ADMIN' ? user.id : undefined,
```

- [ ] **Step 3: 添加审批弹窗状态和处理函数**

在组件内部 `const [loading, setLoading]` 之后添加：
```typescript
const [approveModalOpen, setApproveModalOpen] = useState(false);
const [rejectModalOpen, setRejectModalOpen] = useState(false);
const [currentActionId, setCurrentActionId] = useState<string>('');
const [rejectComment, setRejectComment] = useState('');
const [approveComment, setApproveComment] = useState('');
const [actionLoading, setActionLoading] = useState(false);

const handleApprove = async () => {
  setActionLoading(true);
  try {
    await quotationService.approve(currentActionId, approveComment || undefined);
    message.success('审批通过');
    setApproveModalOpen(false);
    setApproveComment('');
    loadData();
  } catch (e: any) {
    message.error(e.message);
  } finally {
    setActionLoading(false);
  }
};

const handleReject = async () => {
  if (!rejectComment.trim()) {
    message.warning('请填写退回原因');
    return;
  }
  setActionLoading(true);
  try {
    await quotationService.reject(currentActionId, rejectComment);
    message.success('已退回');
    setRejectModalOpen(false);
    setRejectComment('');
    loadData();
  } catch (e: any) {
    message.error(e.message);
  } finally {
    setActionLoading(false);
  }
};

const handleWithdraw = async (qId: string) => {
  try {
    await quotationService.withdraw(qId);
    message.success('已撤回');
    loadData();
  } catch (e: any) {
    message.error(e.message);
  }
};
```

- [ ] **Step 4: renderRowActions 添加审批和撤回按钮**

在 `renderRowActions` 函数中，`// Submit — only for DRAFT` 块之前，添加审批按钮逻辑：

```typescript
// Approve/Reject — for SUBMITTED quotations in pending approval tab
if (record.status === 'SUBMITTED' && statusFilter === PENDING_APPROVAL_TAB) {
  const canApprove = user?.role === 'SYSTEM_ADMIN' || record.assignedApproverId === user?.id;
  if (canApprove) {
    actions.push(
      <Button key="approve" size="small" type="primary" style={{ backgroundColor: '#52c41a', borderColor: '#52c41a' }}
        onClick={() => { setCurrentActionId(record.id); setApproveModalOpen(true); }}>
        通过
      </Button>
    );
    actions.push(
      <Button key="reject-approval" size="small" danger
        onClick={() => { setCurrentActionId(record.id); setRejectModalOpen(true); }}>
        退回
      </Button>
    );
  }
}

// Withdraw — SUBMITTED + current user is creator
if (record.status === 'SUBMITTED' && record.salesRepId === user?.id) {
  actions.push(
    <Popconfirm key="withdraw" title="撤回后报价单将回到草稿状态，需重新提交审批。是否继续？" onConfirm={() => handleWithdraw(record.id)}>
      <Button size="small">撤回</Button>
    </Popconfirm>
  );
}
```

- [ ] **Step 5: 在 return JSX 末尾 Table 之后添加审批弹窗**

在 `</Table>` 之后、`</Card>` 之前添加：
```tsx
{/* Approve Modal */}
<Modal title="审批通过" open={approveModalOpen} onCancel={() => { setApproveModalOpen(false); setApproveComment(''); }}
  onOk={handleApprove} confirmLoading={actionLoading} okText="确认通过" okButtonProps={{ style: { backgroundColor: '#52c41a', borderColor: '#52c41a' } }}>
  <Input.TextArea rows={3} placeholder="审批意见（可选）" value={approveComment} onChange={e => setApproveComment(e.target.value)} />
</Modal>

{/* Reject Modal */}
<Modal title="退回报价单" open={rejectModalOpen} onCancel={() => { setRejectModalOpen(false); setRejectComment(''); }}
  onOk={handleReject} confirmLoading={actionLoading} okText="确认退回" okButtonProps={{ danger: true }}>
  <Input.TextArea rows={3} placeholder="请填写退回原因（必填）" value={rejectComment} onChange={e => setRejectComment(e.target.value)} />
</Modal>
```

- [ ] **Step 6: 添加 salesRepId 列到 columns（用于撤回权限判断）**

在 columns 数组的 `总金额` 列之后添加销售代表列（方便展示谁创建的）：
```typescript
{
  title: '销售代表', dataIndex: 'salesRepName', key: 'salesRep',
  render: (v: string, record: any) => v || record.salesRepId?.substring(0, 8) + '...',
},
```

- [ ] **Step 7: 浏览器验证**

1. 用销售经理登录 → 待我审批 Tab 可见 → SUBMITTED 行有"通过""退回"按钮
2. 用系统管理员登录 → 待我审批 Tab 可见 → 显示所有 SUBMITTED
3. 用销售代表登录 → SUBMITTED 行有"撤回"按钮
4. 执行通过/退回/撤回操作验证

- [ ] **Step 8: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationList.tsx
git commit -m "feat(approval): add approve/reject/withdraw actions to QuotationList"
```

---

### Task 9: 前端 — QuotationDetail 审批操作 + 进度卡片 + 撤回

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationDetail.tsx`

- [ ] **Step 1: 导入 authStore**

在文件顶部添加：
```typescript
import { useAuthStore } from '../../stores/authStore';
```

在组件内部 `const { id } = useParams` 之后添加：
```typescript
const user = useAuthStore((s) => s.user);
```

- [ ] **Step 2: 添加审批弹窗状态和处理函数**

在 `const [actionLoading, setActionLoading]` 之后添加：
```typescript
const [approveModalOpen, setApproveModalOpen] = useState(false);
const [approvalRejectModalOpen, setApprovalRejectModalOpen] = useState(false);
const [approveComment, setApproveComment] = useState('');
const [approvalRejectComment, setApprovalRejectComment] = useState('');

const handleApprove = async () => {
  setActionLoading(true);
  try {
    await quotationService.approve(id!, approveComment || undefined);
    message.success('审批通过');
    setApproveModalOpen(false);
    setApproveComment('');
    loadQuotation();
  } catch (e: any) {
    message.error(e.message);
  } finally {
    setActionLoading(false);
  }
};

const handleApprovalReject = async () => {
  if (!approvalRejectComment.trim()) {
    message.warning('请填写退回原因');
    return;
  }
  setActionLoading(true);
  try {
    await quotationService.reject(id!, approvalRejectComment);
    message.success('已退回');
    setApprovalRejectModalOpen(false);
    setApprovalRejectComment('');
    loadQuotation();
  } catch (e: any) {
    message.error(e.message);
  } finally {
    setActionLoading(false);
  }
};

const handleWithdraw = async () => {
  setActionLoading(true);
  try {
    await quotationService.withdraw(id!);
    message.success('已撤回');
    loadQuotation();
  } catch (e: any) {
    message.error(e.message);
  } finally {
    setActionLoading(false);
  }
};
```

- [ ] **Step 3: 顶部操作栏添加审批按钮和撤回按钮**

在 Action Bar 的 `<Space wrap>` 中，`{/* Status-specific actions */}` 之后、`{status === 'DRAFT' && (` 之前，添加：

```tsx
{/* Approval actions — SUBMITTED */}
{status === 'SUBMITTED' && (() => {
  const isAssigned = user?.id === quotation.assignedApproverId;
  const isAdmin = user?.role === 'SYSTEM_ADMIN';
  const isCreator = user?.id === quotation.salesRepId;
  return (
    <>
      {(isAssigned || isAdmin) && (
        <>
          <Button type="primary" style={{ backgroundColor: '#52c41a', borderColor: '#52c41a' }}
            icon={<CheckCircleOutlined />} onClick={() => setApproveModalOpen(true)}>
            通过
          </Button>
          <Button danger icon={<CloseCircleOutlined />}
            onClick={() => setApprovalRejectModalOpen(true)}>
            退回
          </Button>
        </>
      )}
      {user?.role === 'SALES_MANAGER' && !isAssigned && !isAdmin && (
        <>
          <Button disabled icon={<CheckCircleOutlined />} title="该报价单不在您的审批范围内">通过</Button>
          <Button disabled icon={<CloseCircleOutlined />} title="该报价单不在您的审批范围内">退回</Button>
        </>
      )}
      {isCreator && (
        <Popconfirm title="撤回后报价单将回到草稿状态，需重新提交审批。是否继续？" onConfirm={handleWithdraw}>
          <Button loading={actionLoading}>撤回</Button>
        </Popconfirm>
      )}
    </>
  );
})()}
```

- [ ] **Step 4: 审批进度卡片**

在 `{/* Header Info */}` 的 Card 之前（Action Bar Card 之后），添加审批进度卡片：

```tsx
{/* Approval Progress */}
{(status === 'SUBMITTED' || status === 'APPROVED' || (status === 'DRAFT' && quotation.approvalHistory?.length > 0)) && (
  <Card size="small" style={{ marginBottom: 16 }}>
    {status === 'SUBMITTED' && (
      <Space>
        <Tag color="processing">审批中</Tag>
        <span>审批人：<strong>{quotation.assignedApproverName || '未指定'}</strong></span>
        <span style={{ color: '#999' }}>
          已等待 {(() => {
            const submitted = quotation.approvalHistory?.find((a: any) => a.action === 'WITHDRAWN' || true);
            const updatedAt = quotation.updatedAt ? new Date(quotation.updatedAt) : null;
            if (!updatedAt) return '-';
            const hours = Math.floor((Date.now() - updatedAt.getTime()) / 3600000);
            return hours < 24 ? `${hours} 小时` : `${Math.floor(hours / 24)} 天`;
          })()}
        </span>
      </Space>
    )}
    {status === 'APPROVED' && (() => {
      const approveRecord = [...(quotation.approvalHistory || [])].reverse().find((a: any) => a.action === 'APPROVED');
      return (
        <Space>
          <Tag color="success">已通过</Tag>
          <span>审批人：<strong>{quotation.assignedApproverName || '未指定'}</strong></span>
          {approveRecord?.actedAt && <span>{new Date(approveRecord.actedAt).toLocaleString('zh-CN')}</span>}
          {approveRecord?.comment && <span style={{ color: '#666' }}>意见：{approveRecord.comment}</span>}
        </Space>
      );
    })()}
    {status === 'DRAFT' && quotation.approvalHistory?.length > 0 && (() => {
      const lastReject = [...(quotation.approvalHistory || [])].reverse().find((a: any) => a.action === 'REJECTED');
      if (!lastReject) return null;
      return (
        <Space>
          <Tag color="error">上次审批被退回</Tag>
          <span style={{ color: '#c00' }}>原因：{lastReject.comment || '未填写'}</span>
        </Space>
      );
    })()}
  </Card>
)}
```

- [ ] **Step 5: 审批历史表格增加 WITHDRAWN 显示 + 显示审批人姓名**

修改 `approvalColumns` 中操作人列，将 UUID 截断改为有意义的显示（先用截断 UUID，后续可优化）：
```typescript
{ title: '操作人', dataIndex: 'approverName', key: 'approver', render: (v: string, record: any) => v || (record.approverId ? record.approverId.substring(0, 8) + '...' : '-') },
```

- [ ] **Step 6: 在 return JSX 末尾添加审批弹窗**

在最后一个 `</Modal>` 之后、`</div>` 之前添加：
```tsx
{/* Approve Modal */}
<Modal title="审批通过" open={approveModalOpen} onCancel={() => { setApproveModalOpen(false); setApproveComment(''); }}
  onOk={handleApprove} confirmLoading={actionLoading} okText="确认通过"
  okButtonProps={{ style: { backgroundColor: '#52c41a', borderColor: '#52c41a' } }}>
  <Input.TextArea rows={3} placeholder="审批意见（可选）" value={approveComment} onChange={e => setApproveComment(e.target.value)} />
</Modal>

{/* Approval Reject Modal */}
<Modal title="退回报价单" open={approvalRejectModalOpen} onCancel={() => { setApprovalRejectModalOpen(false); setApprovalRejectComment(''); }}
  onOk={handleApprovalReject} confirmLoading={actionLoading} okText="确认退回" okButtonProps={{ danger: true }}>
  <Input.TextArea rows={3} placeholder="请填写退回原因（必填）" value={approvalRejectComment} onChange={e => setApprovalRejectComment(e.target.value)} />
</Modal>
```

- [ ] **Step 7: 浏览器验证**

1. 打开一个 SUBMITTED 状态报价单详情页
2. 审批进度卡片显示"审批中 — 审批人：XXX，已等待 X 小时"
3. 审批人登录 → 看到通过/退回按钮，点击操作成功
4. 创建人登录 → 看到撤回按钮，点击撤回成功
5. 退回后重新打开 → 显示"上次审批被退回"

- [ ] **Step 8: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationDetail.tsx
git commit -m "feat(approval): add approve/reject/withdraw buttons and progress card to QuotationDetail"
```

---

### Task 10: 后端 — ApprovalDTO 补充 approverName

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/dto/QuotationDTO.java`
- Modify: `cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`

- [ ] **Step 1: ApprovalDTO 添加 approverName 字段**

在 `QuotationDTO.ApprovalDTO` 类中添加：
```java
public String approverName;
```

- [ ] **Step 2: QuotationService 在加载审批历史时查询审批人姓名**

找到 `getById` 方法中加载 approvalHistory 的代码，在 `ApprovalDTO.from(a)` 之后补充：
```java
User approverUser = User.findById(a.approverId);
if (approverUser != null) {
    approvalDto.approverName = approverUser.fullName;
}
```

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/dto/QuotationDTO.java cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java
git commit -m "feat(approval): add approverName to ApprovalDTO for display"
```

---

### Task 11: 文档更新 — PRD + RECORD

**Files:**
- Modify: `docs/PRD.md`
- Modify: `docs/RECORD.md`

- [ ] **Step 1: PRD.md — 状态机新增撤回**

在 PRD.md 状态机部分（搜索 `DRAFT → SUBMITTED`），在 `SUBMITTED → DRAFT（审批人退回）` 之后添加：
```
SUBMITTED → DRAFT（销售代表撤回）
```

在变更记录表格末尾添加：
```
| v2.0-patch3 | 2026-04-16 | 撤回功能 | 新增销售代表撤回已提交报价单功能，SUBMITTED→DRAFT；QuotationApproval.action新增WITHDRAWN |
| v2.0-patch3 | 2026-04-16 | 审批规则配置修正 | 规则类型从GLOBAL/REGION/CUSTOMER/AMOUNT修正为FIXED/DYNAMIC，与后端对齐 |
```

- [ ] **Step 2: RECORD.md — 追加开发记录**

在 RECORD.md 最新日期段落后追加：
```markdown
### 报价审批流程完善
- [2026-04-16] 审批/操作页面 - QuotationList 待我审批 Tab 扩展给 SYSTEM_ADMIN + 快捷通过/退回按钮 | `QuotationList.tsx`
- [2026-04-16] 审批/操作页面 - QuotationDetail 顶部添加通过/退回/撤回按钮 + 审批进度卡片 | `QuotationDetail.tsx`
- [2026-04-16] 审批/撤回功能 - 新增 withdraw 端点，SUBMITTED→DRAFT | `QuotationResource.java` + `QuotationService.java` + V17 迁移
- [2026-04-16] 审批/权限校验 - approve/reject 增加操作人身份校验（assigned_approver 或 SYSTEM_ADMIN）| `QuotationService.java`
- [2026-04-16] 审批/进度展示 - QuotationDTO 新增 assignedApproverName，ApprovalDTO 新增 approverName | `QuotationDTO.java`
- [2026-04-16] 审批/状态标签 - SUBMITTED→"审批中"，REJECTED→"已退回" | `QuotationList.tsx` + `QuotationDetail.tsx`
- [2026-04-16] 系统管理/用户 - UserManagement 新增区域/部门下拉选择框 | `UserManagement.tsx`
- [2026-04-16] 系统管理/审批规则 - ApprovalRuleManagement 重做：FIXED/DYNAMIC + 审批人/匹配值下拉选择 | `ApprovalRuleManagement.tsx`

**关键决策**：
- 审批引擎继续使用纯 Java（方案A），不引入 Camunda，后续迁移代价小（仅改 QuotationService 三个方法）
- SYSTEM_ADMIN 可查看和审批所有报价单，不受 assigned_approver_id 限制
```

- [ ] **Step 3: Commit**

```bash
git add docs/PRD.md docs/RECORD.md
git commit -m "docs: update PRD with withdraw feature and RECORD with approval workflow changes"
```
