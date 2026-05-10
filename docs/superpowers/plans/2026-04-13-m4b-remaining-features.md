# M4b Remaining Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Approval Rule CRUD API + frontend, enhance Quotation List frontend, add Notification API + bell component, add Scheduled Tasks service, and add Operation Log frontend.

**Architecture:** Backend follows Quarkus/Panache patterns (entity → service → resource → DTO). Frontend uses React/Ant Design with axios services; all pages follow established Card+Table+Modal patterns. Notification bell polls `/api/cpq/notifications/unread-count` every 30 seconds and lives in MainLayout header. Scheduled tasks use Quarkus `@Scheduled` + `@Blocking`. No new DB migrations needed — all tables already exist.

**Tech Stack:** Java 17 + Quarkus 3.34.3 (REST Jackson, Hibernate Panache, @Scheduled), React + TypeScript + Ant Design 5, axios, react-router-dom.

---

## File Map

### Backend — New Files
- `cpq-backend/src/main/java/com/cpq/approval/dto/ApprovalRuleDTO.java` — DTO + `from()` mapper
- `cpq-backend/src/main/java/com/cpq/approval/dto/CreateApprovalRuleRequest.java` — request body DTO
- `cpq-backend/src/main/java/com/cpq/approval/service/ApprovalRuleService.java` — CRUD service
- `cpq-backend/src/main/java/com/cpq/approval/resource/ApprovalRuleResource.java` — REST resource at `/api/cpq/approval-rules`
- `cpq-backend/src/main/java/com/cpq/notification/entity/Notification.java` — Panache entity for `notification` table
- `cpq-backend/src/main/java/com/cpq/notification/dto/NotificationDTO.java` — DTO + `from()` mapper
- `cpq-backend/src/main/java/com/cpq/notification/service/NotificationService.java` — CRUD + create + sendNotification
- `cpq-backend/src/main/java/com/cpq/notification/resource/NotificationResource.java` — REST resource at `/api/cpq/notifications`
- `cpq-backend/src/main/java/com/cpq/system/service/ScheduledTaskService.java` — 4 cron jobs

### Backend — New Test Files
- `cpq-backend/src/test/java/com/cpq/approval/resource/ApprovalRuleResourceTest.java`
- `cpq-backend/src/test/java/com/cpq/notification/resource/NotificationResourceTest.java`

### Frontend — New Files
- `cpq-frontend/src/pages/system/ApprovalRuleManagement.tsx` — CRUD page
- `cpq-frontend/src/pages/notification/NotificationList.tsx` — full notification list
- `cpq-frontend/src/pages/system/OperationLogList.tsx` — read-only log table
- `cpq-frontend/src/services/approvalRuleService.ts` — API calls
- `cpq-frontend/src/services/notificationService.ts` — API calls

### Frontend — Modified Files
- `cpq-frontend/src/pages/quotation/QuotationList.tsx` — enhance with status tabs, more columns, role-aware actions
- `cpq-frontend/src/layouts/MainLayout.tsx` — add notification bell with polling, update sidebar
- `cpq-frontend/src/router/index.tsx` — add 3 new routes

---

## Task 1: Approval Rule DTOs and Service

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/approval/dto/ApprovalRuleDTO.java`
- Create: `cpq-backend/src/main/java/com/cpq/approval/dto/CreateApprovalRuleRequest.java`
- Create: `cpq-backend/src/main/java/com/cpq/approval/service/ApprovalRuleService.java`

- [ ] **Step 1: Write the failing test for ApprovalRuleService list()**

Create `cpq-backend/src/test/java/com/cpq/approval/resource/ApprovalRuleResourceTest.java`:

```java
package com.cpq.approval.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApprovalRuleResourceTest {

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanupTestData() {
        em.createNativeQuery("DELETE FROM approval_rule WHERE priority > 900").executeUpdate();
    }

    @Test
    @Order(1)
    void listRules_returnsOk() {
        RestAssured.given()
            .when().get("/api/cpq/approval-rules")
            .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data", notNullValue());
    }

    @Test
    @Order(2)
    void createRule_fixedType_succeeds() {
        // need a valid approver id - get first user
        String userId = RestAssured.given()
            .when().get("/api/cpq/users")
            .then().statusCode(200)
            .extract().path("data.content[0].id");

        String body = String.format("""
            {
              "ruleType": "FIXED",
              "approverId": "%s",
              "priority": 999
            }
            """, userId);

        RestAssured.given()
            .contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/approval-rules")
            .then()
            .statusCode(200)
            .body("data.ruleType", equalTo("FIXED"))
            .body("data.priority", equalTo(999));
    }

    @Test
    @Order(3)
    void createAndDeleteRule_succeeds() {
        String userId = RestAssured.given()
            .when().get("/api/cpq/users")
            .then().statusCode(200)
            .extract().path("data.content[0].id");

        String body = String.format("""
            {"ruleType": "FIXED", "approverId": "%s", "priority": 998}
            """, userId);

        String id = RestAssured.given()
            .contentType(ContentType.JSON).body(body)
            .when().post("/api/cpq/approval-rules")
            .then().statusCode(200)
            .extract().path("data.id");

        RestAssured.given()
            .when().delete("/api/cpq/approval-rules/" + id)
            .then().statusCode(200);
    }

    @Test
    @Order(4)
    void updateRule_changesPriority() {
        String userId = RestAssured.given()
            .when().get("/api/cpq/users")
            .then().statusCode(200)
            .extract().path("data.content[0].id");

        String createBody = String.format("""
            {"ruleType": "FIXED", "approverId": "%s", "priority": 997}
            """, userId);

        String id = RestAssured.given()
            .contentType(ContentType.JSON).body(createBody)
            .when().post("/api/cpq/approval-rules")
            .then().statusCode(200)
            .extract().path("data.id");

        String updateBody = String.format("""
            {"ruleType": "FIXED", "approverId": "%s", "priority": 950}
            """, userId);

        RestAssured.given()
            .contentType(ContentType.JSON).body(updateBody)
            .when().put("/api/cpq/approval-rules/" + id)
            .then()
            .statusCode(200)
            .body("data.priority", equalTo(950));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -pl . -Dtest=ApprovalRuleResourceTest -q 2>&1 | tail -20
```

Expected: FAIL — `ApprovalRuleResource` does not exist yet.

- [ ] **Step 3: Create ApprovalRuleDTO**

Create `cpq-backend/src/main/java/com/cpq/approval/dto/ApprovalRuleDTO.java`:

```java
package com.cpq.approval.dto;

import com.cpq.approval.entity.ApprovalRule;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ApprovalRuleDTO {

    public UUID id;
    public String ruleType;
    public UUID approverId;
    public String approverName;
    public String matchField;
    public UUID matchValueId;
    public Integer priority;
    public OffsetDateTime createdAt;

    public static ApprovalRuleDTO from(ApprovalRule rule) {
        ApprovalRuleDTO dto = new ApprovalRuleDTO();
        dto.id = rule.id;
        dto.ruleType = rule.ruleType;
        dto.approverId = rule.approverId;
        dto.matchField = rule.matchField;
        dto.matchValueId = rule.matchValueId;
        dto.priority = rule.priority;
        dto.createdAt = rule.createdAt;
        return dto;
    }
}
```

- [ ] **Step 4: Create CreateApprovalRuleRequest**

Create `cpq-backend/src/main/java/com/cpq/approval/dto/CreateApprovalRuleRequest.java`:

```java
package com.cpq.approval.dto;

import java.util.UUID;

public class CreateApprovalRuleRequest {
    public String ruleType;   // FIXED or DYNAMIC
    public UUID approverId;
    public String matchField; // e.g., "region", "department"
    public UUID matchValueId;
    public Integer priority;
}
```

- [ ] **Step 5: Create ApprovalRuleService**

Create `cpq-backend/src/main/java/com/cpq/approval/service/ApprovalRuleService.java`:

```java
package com.cpq.approval.service;

import com.cpq.approval.dto.ApprovalRuleDTO;
import com.cpq.approval.dto.CreateApprovalRuleRequest;
import com.cpq.approval.entity.ApprovalRule;
import com.cpq.common.exception.BusinessException;
import com.cpq.system.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ApprovalRuleService {

    private static final Logger LOG = Logger.getLogger(ApprovalRuleService.class);

    public List<ApprovalRuleDTO> list() {
        List<ApprovalRule> rules = ApprovalRule.find("ORDER BY priority ASC").list();
        List<ApprovalRuleDTO> dtos = rules.stream().map(r -> {
            ApprovalRuleDTO dto = ApprovalRuleDTO.from(r);
            if (r.approverId != null) {
                User approver = User.findById(r.approverId);
                if (approver != null) {
                    dto.approverName = approver.fullName;
                }
            }
            return dto;
        }).collect(Collectors.toList());
        LOG.debugf("list approval rules count=%d", dtos.size());
        return dtos;
    }

    @Transactional
    public ApprovalRuleDTO create(CreateApprovalRuleRequest request) {
        if (request.ruleType == null || (!request.ruleType.equals("FIXED") && !request.ruleType.equals("DYNAMIC"))) {
            throw new BusinessException(400, "ruleType must be FIXED or DYNAMIC");
        }
        if (request.approverId == null) {
            throw new BusinessException(400, "approverId is required");
        }
        ApprovalRule rule = new ApprovalRule();
        rule.ruleType = request.ruleType;
        rule.approverId = request.approverId;
        rule.matchField = request.matchField;
        rule.matchValueId = request.matchValueId;
        rule.priority = request.priority != null ? request.priority : 100;
        rule.persist();
        LOG.infof("Created approval rule id=%s type=%s priority=%d", rule.id, rule.ruleType, rule.priority);
        ApprovalRuleDTO dto = ApprovalRuleDTO.from(rule);
        User approver = User.findById(rule.approverId);
        if (approver != null) dto.approverName = approver.fullName;
        return dto;
    }

    @Transactional
    public ApprovalRuleDTO update(UUID id, CreateApprovalRuleRequest request) {
        ApprovalRule rule = ApprovalRule.findById(id);
        if (rule == null) {
            throw new BusinessException(404, "ApprovalRule not found: " + id);
        }
        if (request.ruleType != null) rule.ruleType = request.ruleType;
        if (request.approverId != null) rule.approverId = request.approverId;
        if (request.matchField != null) rule.matchField = request.matchField;
        if (request.matchValueId != null) rule.matchValueId = request.matchValueId;
        if (request.priority != null) rule.priority = request.priority;
        LOG.infof("Updated approval rule id=%s", id);
        ApprovalRuleDTO dto = ApprovalRuleDTO.from(rule);
        User approver = User.findById(rule.approverId);
        if (approver != null) dto.approverName = approver.fullName;
        return dto;
    }

    @Transactional
    public void delete(UUID id) {
        ApprovalRule rule = ApprovalRule.findById(id);
        if (rule == null) {
            throw new BusinessException(404, "ApprovalRule not found: " + id);
        }
        rule.delete();
        LOG.infof("Deleted approval rule id=%s", id);
    }
}
```

- [ ] **Step 6: Create ApprovalRuleResource**

Create `cpq-backend/src/main/java/com/cpq/approval/resource/ApprovalRuleResource.java`:

```java
package com.cpq.approval.resource;

import com.cpq.approval.dto.ApprovalRuleDTO;
import com.cpq.approval.dto.CreateApprovalRuleRequest;
import com.cpq.approval.service.ApprovalRuleService;
import com.cpq.common.dto.ApiResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/cpq/approval-rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApprovalRuleResource {

    @Inject
    ApprovalRuleService approvalRuleService;

    @GET
    public ApiResponse<List<ApprovalRuleDTO>> list() {
        return ApiResponse.success(approvalRuleService.list());
    }

    @POST
    public ApiResponse<ApprovalRuleDTO> create(CreateApprovalRuleRequest request) {
        return ApiResponse.success(approvalRuleService.create(request));
    }

    @PUT
    @Path("/{id}")
    public ApiResponse<ApprovalRuleDTO> update(@PathParam("id") UUID id, CreateApprovalRuleRequest request) {
        return ApiResponse.success(approvalRuleService.update(id, request));
    }

    @DELETE
    @Path("/{id}")
    public ApiResponse<Void> delete(@PathParam("id") UUID id) {
        approvalRuleService.delete(id);
        return ApiResponse.success();
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -pl . -Dtest=ApprovalRuleResourceTest -q 2>&1 | tail -20
```

Expected: All 4 tests PASS.

- [ ] **Step 8: Commit**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/main/java/com/cpq/approval/dto/ cpq-backend/src/main/java/com/cpq/approval/service/ cpq-backend/src/main/java/com/cpq/approval/resource/ cpq-backend/src/test/java/com/cpq/approval/
git commit -m "feat(approval): add approval rule CRUD service and resource"
```

---

## Task 2: Approval Rule Frontend

**Files:**
- Create: `cpq-frontend/src/services/approvalRuleService.ts`
- Create: `cpq-frontend/src/pages/system/ApprovalRuleManagement.tsx`
- Modify: `cpq-frontend/src/layouts/MainLayout.tsx` (sidebar entry)
- Modify: `cpq-frontend/src/router/index.tsx` (route)

- [ ] **Step 1: Create approvalRuleService**

Create `cpq-frontend/src/services/approvalRuleService.ts`:

```typescript
import api from './api';

export const approvalRuleService = {
  list: () => api.get('/approval-rules') as Promise<any>,
  create: (data: any) => api.post('/approval-rules', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/approval-rules/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/approval-rules/${id}`) as Promise<any>,
};
```

- [ ] **Step 2: Create ApprovalRuleManagement page**

Create `cpq-frontend/src/pages/system/ApprovalRuleManagement.tsx`:

```tsx
import React, { useEffect, useState } from 'react';
import {
  Table, Button, Modal, Form, Select, InputNumber, Tag, Space, Card, Popconfirm, message, Alert,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { approvalRuleService } from '../../services/approvalRuleService';
import { userService } from '../../services/userService';

const ruleTypeOptions = [
  { label: '固定审批人', value: 'FIXED' },
  { label: '动态匹配', value: 'DYNAMIC' },
];

const matchFieldOptions = [
  { label: '区域', value: 'region' },
  { label: '部门', value: 'department' },
];

const ApprovalRuleManagement: React.FC = () => {
  const [rules, setRules] = useState<any[]>([]);
  const [users, setUsers] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingRule, setEditingRule] = useState<any>(null);
  const [form] = Form.useForm();
  const [ruleType, setRuleType] = useState<string>('FIXED');

  const loadData = async () => {
    setLoading(true);
    try {
      const [rulesRes, usersRes] = await Promise.all([
        approvalRuleService.list(),
        userService.list({ page: 0, size: 200 }),
      ]);
      setRules(rulesRes.data || []);
      setUsers(usersRes.data?.content || []);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadData(); }, []);

  const fallbackApprover = users.find((u: any) => {
    const sorted = [...rules].sort((a, b) => (b.priority || 0) - (a.priority || 0));
    return sorted.length > 0 && u.id === sorted[0]?.approverId;
  });

  const openCreate = () => {
    setEditingRule(null);
    form.resetFields();
    form.setFieldsValue({ ruleType: 'FIXED', priority: 100 });
    setRuleType('FIXED');
    setModalOpen(true);
  };

  const openEdit = (rule: any) => {
    setEditingRule(rule);
    form.setFieldsValue({
      ruleType: rule.ruleType,
      approverId: rule.approverId,
      matchField: rule.matchField,
      matchValueId: rule.matchValueId,
      priority: rule.priority,
    });
    setRuleType(rule.ruleType);
    setModalOpen(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      if (editingRule) {
        await approvalRuleService.update(editingRule.id, values);
        message.success('更新成功');
      } else {
        await approvalRuleService.create(values);
        message.success('创建成功');
      }
      setModalOpen(false);
      loadData();
    } catch (e: any) {
      if (e.message) message.error(e.message);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await approvalRuleService.delete(id);
      message.success('删除成功');
      loadData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const userOptions = users.map((u: any) => ({ label: u.fullName, value: u.id }));

  const columns = [
    {
      title: '规则类型',
      dataIndex: 'ruleType',
      key: 'ruleType',
      width: 120,
      render: (v: string) => (
        <Tag color={v === 'FIXED' ? 'blue' : 'purple'}>
          {v === 'FIXED' ? '固定审批人' : '动态匹配'}
        </Tag>
      ),
    },
    { title: '审批人', dataIndex: 'approverName', key: 'approverName' },
    {
      title: '匹配字段',
      dataIndex: 'matchField',
      key: 'matchField',
      render: (v: string) => v ? matchFieldOptions.find(o => o.value === v)?.label || v : '-',
    },
    {
      title: '匹配值ID',
      dataIndex: 'matchValueId',
      key: 'matchValueId',
      render: (v: string) => v || '-',
    },
    { title: '优先级', dataIndex: 'priority', key: 'priority', width: 90, sorter: (a: any, b: any) => a.priority - b.priority },
    {
      title: '操作',
      key: 'actions',
      width: 160,
      render: (_: any, record: any) => (
        <Space size="small">
          <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>
          <Popconfirm title="确认删除？" onConfirm={() => handleDelete(record.id)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const defaultFallback = users.find((u: any) => u.role === 'SALES_MANAGER') || users[0];

  return (
    <Card
      title="审批规则管理"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建规则
        </Button>
      }
    >
      {defaultFallback && (
        <Alert
          style={{ marginBottom: 16 }}
          type="info"
          message={`兜底审批人：${defaultFallback.fullName}（无规则匹配时使用）`}
          showIcon
        />
      )}
      <Table
        rowKey="id"
        columns={columns}
        dataSource={rules}
        loading={loading}
        pagination={false}
      />
      <Modal
        title={editingRule ? '编辑审批规则' : '新建审批规则'}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="ruleType" label="规则类型" rules={[{ required: true }]}>
            <Select options={ruleTypeOptions} onChange={v => setRuleType(v)} />
          </Form.Item>
          <Form.Item name="approverId" label="审批人" rules={[{ required: true, message: '请选择审批人' }]}>
            <Select options={userOptions} showSearch optionFilterProp="label" placeholder="选择审批人" />
          </Form.Item>
          {ruleType === 'DYNAMIC' && (
            <>
              <Form.Item name="matchField" label="匹配字段" rules={[{ required: true, message: '请选择匹配字段' }]}>
                <Select options={matchFieldOptions} placeholder="选择匹配字段" />
              </Form.Item>
              <Form.Item name="matchValueId" label="匹配值ID" rules={[{ required: true, message: '请输入匹配值ID' }]}>
                <Select
                  options={userOptions}
                  showSearch
                  optionFilterProp="label"
                  placeholder="选择匹配值（区域/部门ID）"
                />
              </Form.Item>
            </>
          )}
          <Form.Item name="priority" label="优先级" rules={[{ required: true }]}>
            <InputNumber min={1} max={9999} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
};

export default ApprovalRuleManagement;
```

- [ ] **Step 3: Add route for ApprovalRuleManagement**

Edit `cpq-frontend/src/router/index.tsx`. Add import after existing system imports:

```typescript
import ApprovalRuleManagement from '../pages/system/ApprovalRuleManagement';
```

Add route inside the children array after `{ path: 'system/departments', ... }`:

```typescript
{ path: 'system/approval-rules', element: <ApprovalRuleManagement /> },
```

- [ ] **Step 4: Add sidebar entry in MainLayout**

Edit `cpq-frontend/src/layouts/MainLayout.tsx`. In the `menuItems` array, find the `'/system'` entry and add to its `children` array:

```typescript
{ key: '/system/approval-rules', label: '审批规则' },
```

The system entry should look like:
```typescript
{
  key: '/system',
  icon: <SettingOutlined />,
  label: '系统管理',
  children: [
    { key: '/system/users', label: '用户管理' },
    { key: '/system/regions', label: '区域管理' },
    { key: '/system/departments', label: '部门管理' },
    { key: '/system/approval-rules', label: '审批规则' },
    { key: '/system/operation-logs', label: '操作日志' },
  ],
},
```

(The operation-logs entry will be needed by Task 6 — add it now.)

- [ ] **Step 5: Commit**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev
git add cpq-frontend/src/services/approvalRuleService.ts cpq-frontend/src/pages/system/ApprovalRuleManagement.tsx cpq-frontend/src/router/index.tsx cpq-frontend/src/layouts/MainLayout.tsx
git commit -m "feat(approval): add approval rule management frontend"
```

---

## Task 3: Notification Entity, DTO, Service

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/notification/entity/Notification.java`
- Create: `cpq-backend/src/main/java/com/cpq/notification/dto/NotificationDTO.java`
- Create: `cpq-backend/src/main/java/com/cpq/notification/service/NotificationService.java`

- [ ] **Step 1: Write the failing test for NotificationResource**

Create `cpq-backend/src/test/java/com/cpq/notification/resource/NotificationResourceTest.java`:

```java
package com.cpq.notification.resource;

import com.cpq.system.entity.User;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationResourceTest {

    @Inject
    EntityManager em;

    private static UUID testRecipientId;

    @BeforeAll
    static void setupRecipient() {
        // Will be set in first test using the DB
    }

    @BeforeEach
    @Transactional
    void ensureTestUser() {
        if (testRecipientId == null) {
            User u = User.find("status = 'ACTIVE' ORDER BY createdAt ASC").firstResult();
            if (u != null) testRecipientId = u.id;
        }
        if (testRecipientId != null) {
            em.createNativeQuery("DELETE FROM notification WHERE recipient_id = ?1 AND title LIKE 'Test Notif%'")
              .setParameter(1, testRecipientId)
              .executeUpdate();
        }
    }

    @Test
    @Order(1)
    void listNotifications_returnsPagedResult() {
        if (testRecipientId == null) return;
        RestAssured.given()
            .queryParam("recipientId", testRecipientId.toString())
            .when().get("/api/cpq/notifications")
            .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .body("data.content", notNullValue());
    }

    @Test
    @Order(2)
    void getUnreadCount_returnsNumber() {
        if (testRecipientId == null) return;
        RestAssured.given()
            .queryParam("recipientId", testRecipientId.toString())
            .when().get("/api/cpq/notifications/unread-count")
            .then()
            .statusCode(200)
            .body("data", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(3)
    void createAndMarkRead_succeeds() {
        if (testRecipientId == null) return;

        // Create notification directly via service
        String createBody = String.format("""
            {
              "recipientId": "%s",
              "type": "SYSTEM",
              "title": "Test Notif Create",
              "content": "test content"
            }
            """, testRecipientId);

        String notifId = RestAssured.given()
            .contentType(ContentType.JSON).body(createBody)
            .when().post("/api/cpq/notifications/internal")
            .then()
            .statusCode(200)
            .extract().path("data.id");

        // Mark read
        RestAssured.given()
            .when().put("/api/cpq/notifications/" + notifId + "/read")
            .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void markAllRead_succeeds() {
        if (testRecipientId == null) return;
        RestAssured.given()
            .queryParam("recipientId", testRecipientId.toString())
            .when().put("/api/cpq/notifications/read-all")
            .then()
            .statusCode(200);
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -pl . -Dtest=NotificationResourceTest -q 2>&1 | tail -20
```

Expected: FAIL — `NotificationResource` does not exist.

- [ ] **Step 3: Create Notification entity**

Create `cpq-backend/src/main/java/com/cpq/notification/entity/Notification.java`:

```java
package com.cpq.notification.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification")
public class Notification extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "recipient_id", nullable = false)
    public UUID recipientId;

    @Column(nullable = false, length = 50)
    public String type;

    @Column(nullable = false, length = 500)
    public String title;

    @Column(columnDefinition = "TEXT")
    public String content;

    @Column(length = 500)
    public String link;

    @Column(name = "related_type", length = 50)
    public String relatedType;

    @Column(name = "related_id")
    public UUID relatedId;

    @Column(name = "is_read", nullable = false)
    public Boolean isRead = false;

    @Column(name = "read_at")
    public OffsetDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
```

- [ ] **Step 4: Create NotificationDTO**

Create `cpq-backend/src/main/java/com/cpq/notification/dto/NotificationDTO.java`:

```java
package com.cpq.notification.dto;

import com.cpq.notification.entity.Notification;

import java.time.OffsetDateTime;
import java.util.UUID;

public class NotificationDTO {

    public UUID id;
    public UUID recipientId;
    public String type;
    public String title;
    public String content;
    public String link;
    public String relatedType;
    public UUID relatedId;
    public Boolean isRead;
    public OffsetDateTime readAt;
    public OffsetDateTime createdAt;

    public static NotificationDTO from(Notification n) {
        NotificationDTO dto = new NotificationDTO();
        dto.id = n.id;
        dto.recipientId = n.recipientId;
        dto.type = n.type;
        dto.title = n.title;
        dto.content = n.content;
        dto.link = n.link;
        dto.relatedType = n.relatedType;
        dto.relatedId = n.relatedId;
        dto.isRead = n.isRead;
        dto.readAt = n.readAt;
        dto.createdAt = n.createdAt;
        return dto;
    }
}
```

- [ ] **Step 5: Create NotificationService**

Create `cpq-backend/src/main/java/com/cpq/notification/service/NotificationService.java`:

```java
package com.cpq.notification.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.notification.dto.NotificationDTO;
import com.cpq.notification.entity.Notification;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class NotificationService {

    private static final Logger LOG = Logger.getLogger(NotificationService.class);

    @Inject
    Mailer mailer;

    public PageResult<NotificationDTO> listByRecipient(UUID recipientId, int page, int size) {
        long total = Notification.count("recipientId = ?1", recipientId);
        List<NotificationDTO> content = Notification
                .find("recipientId = ?1 ORDER BY createdAt DESC", recipientId)
                .page(page, size)
                .<Notification>list()
                .stream()
                .map(NotificationDTO::from)
                .collect(Collectors.toList());
        return new PageResult<>(content, page, size, total);
    }

    public long getUnreadCount(UUID recipientId) {
        return Notification.count("recipientId = ?1 AND isRead = false", recipientId);
    }

    @Transactional
    public void markRead(UUID notificationId) {
        Notification n = Notification.findById(notificationId);
        if (n == null) {
            throw new BusinessException(404, "Notification not found: " + notificationId);
        }
        if (!Boolean.TRUE.equals(n.isRead)) {
            n.isRead = true;
            n.readAt = OffsetDateTime.now();
        }
    }

    @Transactional
    public void markAllRead(UUID recipientId) {
        Notification.update("isRead = true, readAt = ?1 WHERE recipientId = ?2 AND isRead = false",
                OffsetDateTime.now(), recipientId);
    }

    @Transactional
    public NotificationDTO create(UUID recipientId, String type, String title, String content,
                                   String link, String relatedType, UUID relatedId) {
        Notification n = new Notification();
        n.recipientId = recipientId;
        n.type = type;
        n.title = title;
        n.content = content;
        n.link = link;
        n.relatedType = relatedType;
        n.relatedId = relatedId;
        n.persist();
        LOG.infof("Created notification id=%s type=%s recipient=%s", n.id, n.type, n.recipientId);
        return NotificationDTO.from(n);
    }

    public NotificationDTO sendNotification(UUID recipientId, String type, String title, String content,
                                             String link, String relatedType, UUID relatedId) {
        NotificationDTO dto = create(recipientId, type, title, content, link, relatedType, relatedId);
        try {
            com.cpq.system.entity.User user = com.cpq.system.entity.User.findById(recipientId);
            if (user != null && user.email != null) {
                mailer.send(Mail.withText(user.email, title, content != null ? content : title));
                LOG.infof("Sent email notification to %s", user.email);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to send email notification to recipient=%s: %s", recipientId, e.getMessage());
        }
        return dto;
    }
}
```

- [ ] **Step 6: Create NotificationResource**

Create `cpq-backend/src/main/java/com/cpq/notification/resource/NotificationResource.java`:

```java
package com.cpq.notification.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.notification.dto.NotificationDTO;
import com.cpq.notification.service.NotificationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.UUID;

@Path("/api/cpq/notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NotificationResource {

    @Inject
    NotificationService notificationService;

    @GET
    public ApiResponse<PageResult<NotificationDTO>> list(
            @QueryParam("recipientId") UUID recipientId,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("10") int size) {
        if (recipientId == null) {
            throw new WebApplicationException("recipientId is required", 400);
        }
        return ApiResponse.success(notificationService.listByRecipient(recipientId, page, size));
    }

    @GET
    @Path("/unread-count")
    public ApiResponse<Long> unreadCount(@QueryParam("recipientId") UUID recipientId) {
        if (recipientId == null) {
            throw new WebApplicationException("recipientId is required", 400);
        }
        return ApiResponse.success(notificationService.getUnreadCount(recipientId));
    }

    @PUT
    @Path("/{id}/read")
    public ApiResponse<Void> markRead(@PathParam("id") UUID id) {
        notificationService.markRead(id);
        return ApiResponse.success();
    }

    @PUT
    @Path("/read-all")
    public ApiResponse<Void> markAllRead(@QueryParam("recipientId") UUID recipientId) {
        if (recipientId == null) {
            throw new WebApplicationException("recipientId is required", 400);
        }
        notificationService.markAllRead(recipientId);
        return ApiResponse.success();
    }

    /**
     * Internal endpoint for creating notifications (used by tests and internal services).
     */
    @POST
    @Path("/internal")
    public ApiResponse<NotificationDTO> createInternal(Map<String, Object> body) {
        UUID recipientId = UUID.fromString(body.get("recipientId").toString());
        String type = body.get("type").toString();
        String title = body.get("title").toString();
        String content = body.containsKey("content") ? body.get("content").toString() : null;
        String link = body.containsKey("link") ? body.get("link").toString() : null;
        String relatedType = body.containsKey("relatedType") ? body.get("relatedType").toString() : null;
        UUID relatedId = body.containsKey("relatedId") ? UUID.fromString(body.get("relatedId").toString()) : null;
        return ApiResponse.success(notificationService.create(recipientId, type, title, content, link, relatedType, relatedId));
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -pl . -Dtest=NotificationResourceTest -q 2>&1 | tail -20
```

Expected: All 4 tests PASS.

- [ ] **Step 8: Commit**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/main/java/com/cpq/notification/ cpq-backend/src/test/java/com/cpq/notification/
git commit -m "feat(notification): add notification entity, service, and REST resource"
```

---

## Task 4: Notification Bell in MainLayout + NotificationList Page

**Files:**
- Modify: `cpq-frontend/src/layouts/MainLayout.tsx`
- Create: `cpq-frontend/src/services/notificationService.ts`
- Create: `cpq-frontend/src/pages/notification/NotificationList.tsx`
- Modify: `cpq-frontend/src/router/index.tsx`

- [ ] **Step 1: Create notificationService**

Create `cpq-frontend/src/services/notificationService.ts`:

```typescript
import api from './api';

export const notificationService = {
  list: (recipientId: string, page = 0, size = 10) =>
    api.get('/notifications', { params: { recipientId, page, size } }) as Promise<any>,
  getUnreadCount: (recipientId: string) =>
    api.get('/notifications/unread-count', { params: { recipientId } }) as Promise<any>,
  markRead: (id: string) =>
    api.put(`/notifications/${id}/read`) as Promise<any>,
  markAllRead: (recipientId: string) =>
    api.put('/notifications/read-all', null, { params: { recipientId } }) as Promise<any>,
};
```

- [ ] **Step 2: Update MainLayout with notification bell**

Replace the content of `cpq-frontend/src/layouts/MainLayout.tsx` with:

```tsx
import React, { useEffect, useRef, useState } from 'react';
import { Layout, Menu, Avatar, Dropdown, Badge, List, Popover, Button, Typography } from 'antd';
import {
  UserOutlined,
  TeamOutlined,
  ShoppingOutlined,
  FileTextOutlined,
  SettingOutlined,
  BellOutlined,
  LogoutOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  AppstoreOutlined,
  PercentageOutlined,
  CheckOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../stores/authStore';
import { notificationService } from '../services/notificationService';

const { Sider, Header, Content } = Layout;
const { Text } = Typography;

const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '工作台' },
  { key: '/customers', icon: <TeamOutlined />, label: '客户管理' },
  { key: '/products', icon: <ShoppingOutlined />, label: '产品管理' },
  { key: '/quotations', icon: <FileTextOutlined />, label: '报价中心' },
  {
    key: '/pricing-mgmt',
    icon: <PercentageOutlined />,
    label: '定价管理',
    children: [
      { key: '/pricing', label: '定价策略' },
    ],
  },
  { key: '/datasources', icon: <DatabaseOutlined />, label: '数据源管理' },
  {
    key: '/config',
    icon: <AppstoreOutlined />,
    label: '配置中心',
    children: [
      { key: '/components', label: '组件管理' },
      { key: '/templates', label: '模板配置' },
      { key: '/template-bindings', label: '产品模板绑定' },
      { key: '/template-comparison', label: '模板版本对比' },
    ],
  },
  {
    key: '/system',
    icon: <SettingOutlined />,
    label: '系统管理',
    children: [
      { key: '/system/users', label: '用户管理' },
      { key: '/system/regions', label: '区域管理' },
      { key: '/system/departments', label: '部门管理' },
      { key: '/system/approval-rules', label: '审批规则' },
      { key: '/system/operation-logs', label: '操作日志' },
    ],
  },
];

const MainLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuthStore();
  const [unreadCount, setUnreadCount] = useState(0);
  const [notifications, setNotifications] = useState<any[]>([]);
  const [bellOpen, setBellOpen] = useState(false);
  const pollInterval = useRef<ReturnType<typeof setInterval> | null>(null);

  const loadUnreadCount = async () => {
    if (!user?.id) return;
    try {
      const res = await notificationService.getUnreadCount(user.id);
      setUnreadCount(res.data || 0);
    } catch {
      // silent fail — polling
    }
  };

  const loadNotifications = async () => {
    if (!user?.id) return;
    try {
      const res = await notificationService.list(user.id, 0, 10);
      setNotifications(res.data?.content || []);
    } catch {
      // silent fail
    }
  };

  useEffect(() => {
    loadUnreadCount();
    pollInterval.current = setInterval(loadUnreadCount, 30000);
    return () => {
      if (pollInterval.current) clearInterval(pollInterval.current);
    };
  }, [user?.id]);

  const handleBellOpen = (open: boolean) => {
    setBellOpen(open);
    if (open) loadNotifications();
  };

  const handleNotifClick = async (notif: any) => {
    if (!notif.isRead) {
      await notificationService.markRead(notif.id);
      loadUnreadCount();
    }
    setBellOpen(false);
    if (notif.link) navigate(notif.link);
  };

  const handleMarkAllRead = async () => {
    if (!user?.id) return;
    await notificationService.markAllRead(user.id);
    setUnreadCount(0);
    loadNotifications();
  };

  const notificationPanel = (
    <div style={{ width: 360 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <Text strong>通知</Text>
        <Button size="small" icon={<CheckOutlined />} onClick={handleMarkAllRead}>
          全部标为已读
        </Button>
      </div>
      <List
        size="small"
        dataSource={notifications}
        renderItem={(item: any) => (
          <List.Item
            style={{
              cursor: 'pointer',
              background: item.isRead ? 'transparent' : '#f0f5ff',
              padding: '8px 12px',
              borderRadius: 4,
            }}
            onClick={() => handleNotifClick(item)}
          >
            <List.Item.Meta
              title={<Text style={{ fontWeight: item.isRead ? 'normal' : 600 }}>{item.title}</Text>}
              description={
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {item.content || ''}
                </Text>
              }
            />
          </List.Item>
        )}
        locale={{ emptyText: '暂无通知' }}
      />
      <div style={{ textAlign: 'center', marginTop: 8 }}>
        <Button type="link" size="small" onClick={() => { setBellOpen(false); navigate('/notifications'); }}>
          查看全部
        </Button>
      </div>
    </div>
  );

  const userMenu = {
    items: [
      { key: 'profile', label: '修改密码', icon: <UserOutlined /> },
      { type: 'divider' as const },
      { key: 'logout', label: '退出登录', icon: <LogoutOutlined />, danger: true },
    ],
    onClick: ({ key }: { key: string }) => {
      if (key === 'logout') {
        logout();
        navigate('/login');
      } else if (key === 'profile') {
        navigate('/change-password');
      }
    },
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={220} theme="dark">
        <div style={{ height: 64, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <span style={{ color: '#fff', fontSize: 18, fontWeight: 600 }}>CPQ 报价系统</span>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header style={{ background: '#fff', padding: '0 24px', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 16 }}>
          <Popover
            content={notificationPanel}
            trigger="click"
            open={bellOpen}
            onOpenChange={handleBellOpen}
            placement="bottomRight"
          >
            <Badge count={unreadCount} size="small" overflowCount={99}>
              <BellOutlined style={{ fontSize: 18, cursor: 'pointer' }} />
            </Badge>
          </Popover>
          <Dropdown menu={userMenu} placement="bottomRight">
            <span style={{ cursor: 'pointer' }}>
              <Avatar size="small" icon={<UserOutlined />} style={{ marginRight: 8 }} />
              {user?.fullName || 'User'}
            </span>
          </Dropdown>
        </Header>
        <Content style={{ margin: 24, padding: 24, background: '#fff', borderRadius: 8, minHeight: 280 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
```

- [ ] **Step 3: Create NotificationList page**

Create `cpq-frontend/src/pages/notification/NotificationList.tsx`:

```tsx
import React, { useEffect, useState } from 'react';
import { Table, Card, Button, Tag, Space, message } from 'antd';
import { CheckOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { notificationService } from '../../services/notificationService';
import { useAuthStore } from '../../stores/authStore';

const typeColors: Record<string, string> = {
  APPROVAL_SUBMITTED: 'blue',
  APPROVAL_APPROVED: 'green',
  APPROVAL_REJECTED: 'red',
  APPROVAL_REMINDER: 'orange',
  PASSWORD_RESET: 'purple',
  ROLE_CHANGED: 'cyan',
  SYSTEM: 'default',
};

const typeLabels: Record<string, string> = {
  APPROVAL_SUBMITTED: '待审批',
  APPROVAL_APPROVED: '已审批',
  APPROVAL_REJECTED: '已驳回',
  APPROVAL_REMINDER: '审批提醒',
  PASSWORD_RESET: '密码重置',
  ROLE_CHANGED: '角色变更',
  SYSTEM: '系统通知',
};

const NotificationList: React.FC = () => {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [loading, setLoading] = useState(false);

  const loadData = async () => {
    if (!user?.id) return;
    setLoading(true);
    try {
      const res = await notificationService.list(user.id, page, size);
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadData(); }, [page, user?.id]);

  const handleMarkRead = async (id: string) => {
    try {
      await notificationService.markRead(id);
      loadData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleMarkAllRead = async () => {
    if (!user?.id) return;
    try {
      await notificationService.markAllRead(user.id);
      message.success('全部已读');
      loadData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const columns = [
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 120,
      render: (v: string) => <Tag color={typeColors[v] || 'default'}>{typeLabels[v] || v}</Tag>,
    },
    {
      title: '标题',
      dataIndex: 'title',
      key: 'title',
      render: (v: string, record: any) => (
        <span
          style={{ fontWeight: record.isRead ? 'normal' : 600, cursor: record.link ? 'pointer' : 'default' }}
          onClick={() => record.link && navigate(record.link)}
        >
          {v}
        </span>
      ),
    },
    { title: '内容', dataIndex: 'content', key: 'content', ellipsis: true },
    {
      title: '状态',
      dataIndex: 'isRead',
      key: 'isRead',
      width: 90,
      render: (v: boolean) => v ? <Tag color="default">已读</Tag> : <Tag color="blue">未读</Tag>,
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '-',
    },
    {
      title: '操作',
      key: 'actions',
      width: 100,
      render: (_: any, record: any) =>
        !record.isRead ? (
          <Button size="small" icon={<CheckOutlined />} onClick={() => handleMarkRead(record.id)}>
            标为已读
          </Button>
        ) : null,
    },
  ];

  return (
    <Card
      title="通知中心"
      extra={
        <Button icon={<CheckOutlined />} onClick={handleMarkAllRead}>
          全部标为已读
        </Button>
      }
    >
      <Table
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          onChange: (p) => setPage(p - 1),
          showTotal: (t) => `共 ${t} 条`,
        }}
      />
    </Card>
  );
};

export default NotificationList;
```

- [ ] **Step 4: Add notification route in router**

Edit `cpq-frontend/src/router/index.tsx`. Add import:

```typescript
import NotificationList from '../pages/notification/NotificationList';
```

Add route in the children array (after quotation routes):

```typescript
{ path: 'notifications', element: <NotificationList /> },
```

- [ ] **Step 5: Commit**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev
git add cpq-frontend/src/services/notificationService.ts cpq-frontend/src/pages/notification/ cpq-frontend/src/layouts/MainLayout.tsx cpq-frontend/src/router/index.tsx
git commit -m "feat(notification): add notification bell and list page frontend"
```

---

## Task 5: Enhanced Quotation List Frontend

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationList.tsx`

- [ ] **Step 1: Replace QuotationList with enhanced version**

Replace the entire content of `cpq-frontend/src/pages/quotation/QuotationList.tsx`:

```tsx
import React, { useEffect, useState } from 'react';
import {
  Table, Button, Input, Tabs, Tag, Card, Popconfirm, message, Space, Badge,
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, CopyOutlined,
  SendOutlined, EyeOutlined, CheckOutlined, CloseOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { quotationService } from '../../services/quotationService';
import { useAuthStore } from '../../stores/authStore';

const { Search } = Input;

const statusMap: Record<string, { label: string; color: string }> = {
  DRAFT:     { label: '草稿',   color: 'default' },
  SUBMITTED: { label: '待审批', color: 'processing' },
  APPROVED:  { label: '已审批', color: 'success' },
  SENT:      { label: '已发送', color: 'cyan' },
  ACCEPTED:  { label: '已成交', color: 'green' },
  REJECTED:  { label: '已拒绝', color: 'error' },
  EXPIRED:   { label: '已过期', color: 'warning' },
};

const statusTabs = [
  { key: '', label: '全部' },
  { key: 'DRAFT', label: '草稿' },
  { key: 'SUBMITTED', label: '待审批' },
  { key: 'APPROVED', label: '已审批' },
  { key: 'SENT', label: '已发送' },
  { key: 'ACCEPTED', label: '已成交' },
  { key: 'REJECTED', label: '已拒绝' },
  { key: 'EXPIRED', label: '已过期' },
];

const QuotationList: React.FC = () => {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [loading, setLoading] = useState(false);

  const isSalesManager = user?.role === 'SALES_MANAGER' || user?.role === 'ADMIN';

  const loadData = async () => {
    setLoading(true);
    try {
      const res = await quotationService.list({
        page,
        size,
        status: statusFilter || undefined,
        keyword: keyword || undefined,
      });
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadData(); }, [page, statusFilter, keyword]);

  const handleDelete = async (id: string) => {
    try {
      await quotationService.delete(id);
      message.success('删除成功');
      loadData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleCopy = async (id: string) => {
    try {
      const res = await quotationService.copy(id);
      message.success('复制成功');
      navigate(`/quotations/${res.data.id}/edit`);
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleApprove = async (id: string) => {
    try {
      await quotationService.approve(id, '');
      message.success('审批通过');
      loadData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleReject = async (id: string) => {
    try {
      await quotationService.reject(id, '审批驳回');
      message.success('已驳回');
      loadData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const renderActions = (record: any) => {
    const { status, id } = record;
    const actions: React.ReactNode[] = [];

    if (status === 'DRAFT') {
      actions.push(
        <Button key="edit" size="small" icon={<EditOutlined />} onClick={() => navigate(`/quotations/${id}/edit`)}>编辑</Button>,
        <Button key="copy" size="small" icon={<CopyOutlined />} onClick={() => handleCopy(id)}>复制</Button>,
        <Popconfirm key="del" title="确认删除？" onConfirm={() => handleDelete(id)}>
          <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
        </Popconfirm>,
      );
    } else if (status === 'SUBMITTED') {
      actions.push(
        <Button key="view" size="small" icon={<EyeOutlined />} onClick={() => navigate(`/quotations/${id}/edit`)}>查看</Button>,
      );
      if (isSalesManager) {
        actions.push(
          <Popconfirm key="approve" title="确认审批通过？" onConfirm={() => handleApprove(id)}>
            <Button size="small" type="primary" icon={<CheckOutlined />}>审批</Button>
          </Popconfirm>,
          <Popconfirm key="reject" title="确认驳回？" onConfirm={() => handleReject(id)}>
            <Button size="small" danger icon={<CloseOutlined />}>驳回</Button>
          </Popconfirm>,
        );
      }
    } else if (status === 'APPROVED') {
      actions.push(
        <Button key="send" size="small" icon={<SendOutlined />} disabled>发送</Button>,
        <Button key="copy" size="small" icon={<CopyOutlined />} onClick={() => handleCopy(id)}>复制</Button>,
      );
    } else if (status === 'SENT' || status === 'REJECTED' || status === 'EXPIRED') {
      actions.push(
        <Button key="view" size="small" icon={<EyeOutlined />} onClick={() => navigate(`/quotations/${id}/edit`)}>查看</Button>,
        <Button key="copy" size="small" icon={<CopyOutlined />} onClick={() => handleCopy(id)}>复制</Button>,
      );
    } else {
      actions.push(
        <Button key="view" size="small" icon={<EyeOutlined />} onClick={() => navigate(`/quotations/${id}/edit`)}>查看</Button>,
      );
    }

    return <Space size="small">{actions}</Space>;
  };

  const pendingCount = data.filter(d => d.status === 'SUBMITTED').length;

  const columns = [
    { title: '报价单号', dataIndex: 'quotationNumber', key: 'quotationNumber', width: 180 },
    { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true },
    { title: '客户', dataIndex: 'snapshotCustomerName', key: 'customer', width: 150 },
    {
      title: '原价',
      dataIndex: 'originalAmount',
      key: 'originalAmount',
      width: 130,
      render: (v: number) => v != null ? `¥${Number(v).toLocaleString()}` : '-',
    },
    {
      title: '成交价',
      dataIndex: 'totalAmount',
      key: 'totalAmount',
      width: 130,
      render: (v: number) => v != null ? `¥${Number(v).toLocaleString()}` : '-',
    },
    {
      title: '折扣率',
      dataIndex: 'finalDiscountRate',
      key: 'finalDiscountRate',
      width: 90,
      render: (v: number) => v != null ? `${v}%` : '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (s: string) => {
        const m = statusMap[s] || { label: s, color: 'default' };
        return <Tag color={m.color}>{m.label}</Tag>;
      },
    },
    { title: '到期日', dataIndex: 'expiryDate', key: 'expiryDate', width: 120 },
    {
      title: '操作',
      key: 'actions',
      width: 240,
      render: (_: any, record: any) => renderActions(record),
    },
  ];

  const tabItems = statusTabs.map(tab => ({
    key: tab.key,
    label: tab.key === 'SUBMITTED' && isSalesManager && pendingCount > 0
      ? <Badge count={pendingCount} offset={[8, 0]}>{tab.label}</Badge>
      : tab.label,
  }));

  return (
    <Card
      title="报价单管理"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/quotations/new')}>
          新建报价单
        </Button>
      }
    >
      <div style={{ marginBottom: 16 }}>
        <Search
          placeholder="搜索报价单号/名称/客户"
          onSearch={v => { setKeyword(v); setPage(0); }}
          allowClear
          style={{ width: 300 }}
        />
      </div>
      <Tabs
        activeKey={statusFilter}
        onChange={key => { setStatusFilter(key); setPage(0); }}
        items={tabItems}
        style={{ marginBottom: 16 }}
      />
      <Table
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          onChange: (p) => setPage(p - 1),
          showTotal: (t) => `共 ${t} 条`,
        }}
      />
    </Card>
  );
};

export default QuotationList;
```

- [ ] **Step 2: Commit**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev
git add cpq-frontend/src/pages/quotation/QuotationList.tsx
git commit -m "feat(quotation): enhance quotation list with tabs, discount columns, and role-aware actions"
```

---

## Task 6: Scheduled Tasks Service

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/system/service/ScheduledTaskService.java`

- [ ] **Step 1: Create ScheduledTaskService**

Create `cpq-backend/src/main/java/com/cpq/system/service/ScheduledTaskService.java`:

```java
package com.cpq.system.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ScheduledTaskService {

    private static final Logger LOG = Logger.getLogger(ScheduledTaskService.class);

    @Inject
    EntityManager em;

    @Scheduled(cron = "0 30 0 * * ?")
    @Transactional
    void markExpiredQuotations() {
        int updated = em.createNativeQuery(
            "UPDATE quotation SET status = 'EXPIRED' " +
            "WHERE status IN ('SENT','APPROVED') AND expiry_date < CURRENT_DATE"
        ).executeUpdate();
        LOG.infof("[Scheduler] markExpiredQuotations: updated=%d", updated);
    }

    @Scheduled(cron = "0 0 1 * * ?")
    @Transactional
    void markExpiredStrategies() {
        int updated = em.createNativeQuery(
            "UPDATE pricing_strategy SET status = 'EXPIRED' " +
            "WHERE status = 'ACTIVE' AND expiration_date IS NOT NULL AND expiration_date < CURRENT_DATE"
        ).executeUpdate();
        LOG.infof("[Scheduler] markExpiredStrategies: updated=%d", updated);
    }

    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    void cleanExpiredTokens() {
        int deleted = em.createNativeQuery(
            "DELETE FROM password_reset_token WHERE expires_at < NOW()"
        ).executeUpdate();
        LOG.infof("[Scheduler] cleanExpiredTokens: deleted=%d", deleted);
    }

    @Scheduled(cron = "0 0 4 ? * MON")
    @Transactional
    void cleanOldNotifications() {
        int deleted = em.createNativeQuery(
            "DELETE FROM notification WHERE created_at < NOW() - interval '6 months'"
        ).executeUpdate();
        LOG.infof("[Scheduler] cleanOldNotifications: deleted=%d", deleted);
    }
}
```

- [ ] **Step 2: Verify compilation (no test needed for scheduler — cron jobs can't be unit tested without time-manipulation)**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw compile -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Run full backend test suite to ensure no regressions**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test -q 2>&1 | tail -30
```

Expected: All tests pass (previously passing tests still pass + new ApprovalRule and Notification tests pass).

- [ ] **Step 4: Commit**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev
git add cpq-backend/src/main/java/com/cpq/system/service/ScheduledTaskService.java
git commit -m "feat(system): add scheduled tasks for expiry and cleanup"
```

---

## Task 7: Operation Log Frontend

**Files:**
- Create: `cpq-frontend/src/pages/system/OperationLogList.tsx`
- Create: `cpq-frontend/src/services/operationLogService.ts`
- Modify: `cpq-frontend/src/router/index.tsx`

> Note: The sidebar entry for '操作日志' was already added in Task 2 Step 4.

- [ ] **Step 1: Create operationLogService**

Create `cpq-frontend/src/services/operationLogService.ts`:

```typescript
import api from './api';

export const operationLogService = {
  list: (params: {
    page: number;
    size: number;
    operatorId?: string;
    operationType?: string;
    startTime?: string;
    endTime?: string;
  }) => api.get('/operation-logs', { params }) as Promise<any>,
};
```

- [ ] **Step 2: Add operation log backend resource**

Check if `OperationLogResource` exists:

```bash
ls /d/a-joii/project/CPQ-superpowers/dev/cpq-backend/src/main/java/com/cpq/system/resource/
```

If `OperationLogResource.java` does not exist, create it:

Create `cpq-backend/src/main/java/com/cpq/system/resource/OperationLogResource.java`:

```java
package com.cpq.system.resource;

import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import com.cpq.system.entity.OperationLog;
import com.cpq.system.entity.User;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Path("/api/cpq/operation-logs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OperationLogResource {

    private static final Logger LOG = Logger.getLogger(OperationLogResource.class);

    @GET
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("operatorId") UUID operatorId,
            @QueryParam("operationType") String operationType,
            @QueryParam("startTime") String startTime,
            @QueryParam("endTime") String endTime) {

        StringBuilder where = new StringBuilder("1=1");
        Map<String, Object> params = new LinkedHashMap<>();

        if (operatorId != null) {
            where.append(" AND operatorId = :operatorId");
            params.put("operatorId", operatorId);
        }
        if (operationType != null && !operationType.isBlank()) {
            where.append(" AND operationType = :operationType");
            params.put("operationType", operationType);
        }
        if (startTime != null && !startTime.isBlank()) {
            try {
                where.append(" AND createdAt >= :startTime");
                params.put("startTime", OffsetDateTime.parse(startTime));
            } catch (DateTimeParseException e) {
                LOG.warnf("Invalid startTime format: %s", startTime);
            }
        }
        if (endTime != null && !endTime.isBlank()) {
            try {
                where.append(" AND createdAt <= :endTime");
                params.put("endTime", OffsetDateTime.parse(endTime));
            } catch (DateTimeParseException e) {
                LOG.warnf("Invalid endTime format: %s", endTime);
            }
        }

        String query = where + " ORDER BY createdAt DESC";
        long total = OperationLog.count(where.toString(), params);
        List<Map<String, Object>> content = OperationLog.find(query, params)
                .page(page, size)
                .<OperationLog>list()
                .stream()
                .map(log -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", log.id);
                    m.put("operatorId", log.operatorId);
                    User op = User.findById(log.operatorId);
                    m.put("operatorName", op != null ? op.fullName : log.operatorId.toString());
                    m.put("operationType", log.operationType);
                    m.put("targetType", log.targetType);
                    m.put("targetId", log.targetId);
                    m.put("summary", log.summary);
                    m.put("createdAt", log.createdAt);
                    return m;
                })
                .collect(Collectors.toList());

        return ApiResponse.success(new PageResult<>(content, page, size, total));
    }
}
```

- [ ] **Step 3: Create OperationLogList frontend page**

Create `cpq-frontend/src/pages/system/OperationLogList.tsx`:

```tsx
import React, { useEffect, useState } from 'react';
import { Table, Card, Select, Space, DatePicker, Button } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { operationLogService } from '../../services/operationLogService';
import { userService } from '../../services/userService';
import dayjs, { Dayjs } from 'dayjs';

const { RangePicker } = DatePicker;

const operationTypeOptions = [
  { label: '创建', value: 'CREATE' },
  { label: '更新', value: 'UPDATE' },
  { label: '删除', value: 'DELETE' },
  { label: '审批', value: 'APPROVE' },
  { label: '驳回', value: 'REJECT' },
  { label: '提交', value: 'SUBMIT' },
  { label: '登录', value: 'LOGIN' },
  { label: '密码重置', value: 'PASSWORD_RESET' },
];

const OperationLogList: React.FC = () => {
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [loading, setLoading] = useState(false);
  const [users, setUsers] = useState<any[]>([]);
  const [operatorId, setOperatorId] = useState<string | undefined>();
  const [operationType, setOperationType] = useState<string | undefined>();
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);

  const loadUsers = async () => {
    try {
      const res = await userService.list({ page: 0, size: 200 });
      setUsers(res.data?.content || []);
    } catch {
      // silent
    }
  };

  const loadData = async () => {
    setLoading(true);
    try {
      const params: any = { page, size };
      if (operatorId) params.operatorId = operatorId;
      if (operationType) params.operationType = operationType;
      if (dateRange?.[0]) params.startTime = dateRange[0].toISOString();
      if (dateRange?.[1]) params.endTime = dateRange[1].toISOString();
      const res = await operationLogService.list(params);
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);
    } catch {
      // silent
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadUsers(); }, []);
  useEffect(() => { loadData(); }, [page, operatorId, operationType, dateRange]);

  const columns = [
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '-',
    },
    { title: '操作人', dataIndex: 'operatorName', key: 'operatorName', width: 150 },
    { title: '操作类型', dataIndex: 'operationType', key: 'operationType', width: 120 },
    { title: '目标类型', dataIndex: 'targetType', key: 'targetType', width: 120 },
    { title: '摘要', dataIndex: 'summary', key: 'summary', ellipsis: true },
  ];

  const userOptions = users.map((u: any) => ({ label: u.fullName, value: u.id }));

  return (
    <Card title="操作日志">
      <Space style={{ marginBottom: 16 }} wrap>
        <RangePicker
          showTime
          onChange={v => { setDateRange(v as any); setPage(0); }}
          placeholder={['开始时间', '结束时间']}
        />
        <Select
          placeholder="操作人"
          allowClear
          style={{ width: 160 }}
          options={userOptions}
          showSearch
          optionFilterProp="label"
          onChange={v => { setOperatorId(v); setPage(0); }}
        />
        <Select
          placeholder="操作类型"
          allowClear
          style={{ width: 140 }}
          options={operationTypeOptions}
          onChange={v => { setOperationType(v); setPage(0); }}
        />
        <Button icon={<SearchOutlined />} onClick={() => loadData()}>查询</Button>
      </Space>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          onChange: (p) => setPage(p - 1),
          showTotal: (t) => `共 ${t} 条`,
        }}
      />
    </Card>
  );
};

export default OperationLogList;
```

- [ ] **Step 4: Add route for OperationLogList**

Edit `cpq-frontend/src/router/index.tsx`. Add import:

```typescript
import OperationLogList from '../pages/system/OperationLogList';
```

Add route in children array:

```typescript
{ path: 'system/operation-logs', element: <OperationLogList /> },
```

- [ ] **Step 5: Commit**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev
git add cpq-frontend/src/services/operationLogService.ts cpq-frontend/src/pages/system/OperationLogList.tsx cpq-backend/src/main/java/com/cpq/system/resource/OperationLogResource.java cpq-frontend/src/router/index.tsx
git commit -m "feat(system): add operation log list page and backend resource"
```

---

## Task 8: Final Grouping Commits and Verification

- [ ] **Step 1: Run full backend test suite**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev/cpq-backend
./mvnw test 2>&1 | tail -40
```

Expected: All tests pass. Note total test count and any failures.

- [ ] **Step 2: Build frontend to check for TypeScript errors**

```bash
cd /d/a-joii/project/CPQ-superpowers/dev/cpq-frontend
npm run build 2>&1 | tail -30
```

Expected: No TypeScript errors. Build succeeds.

- [ ] **Step 3: Kill any running Java processes and do final status check**

```bash
taskkill /f /im java.exe 2>/dev/null || true
cd /d/a-joii/project/CPQ-superpowers/dev
git log --oneline -10
```

Expected: Shows the 4+ commits from this plan.

---

## Self-Review

**Spec coverage check:**

| Requirement | Task |
|---|---|
| ApprovalRule list/create/update/delete service | Task 1 |
| ApprovalRule REST resource at `/api/cpq/approval-rules` | Task 1 |
| ApprovalRuleDTO + CreateApprovalRuleRequest | Task 1 |
| ApprovalRuleManagement frontend (table, modal, banner) | Task 2 |
| Sidebar + route for approval-rules | Task 2 |
| Notification entity for existing `notification` table | Task 3 |
| NotificationService (listByRecipient, getUnreadCount, markRead, markAllRead, create, sendNotification) | Task 3 |
| NotificationResource at `/api/cpq/notifications` | Task 3 |
| Notification bell in MainLayout (poll 30s, dropdown, mark-read, nav) | Task 4 |
| NotificationList full page with pagination | Task 4 |
| QuotationList status filter tabs | Task 5 |
| QuotationList role-aware actions (DRAFT/SUBMITTED/APPROVED/etc.) | Task 5 |
| QuotationList pending approval badge for sales managers | Task 5 |
| QuotationList extra columns (originalAmount, finalDiscountRate) | Task 5 |
| Quotation copy endpoint already implemented | N/A — confirmed in QuotationService |
| ScheduledTaskService with 4 cron jobs | Task 6 |
| OperationLogList frontend (filters, read-only table) | Task 7 |
| OperationLogResource backend | Task 7 |
| Sidebar + route for operation-logs | Task 2 Step 4 + Task 7 Step 4 |

**Placeholder scan:** No TBD/TODO/placeholder language present.

**Type consistency:**
- `ApprovalRuleDTO.from(ApprovalRule)` defined in Task 1 Step 3, used in Task 1 Step 5 and Step 6 ✓
- `NotificationDTO.from(Notification)` defined in Task 3 Step 4, used in Task 3 Step 5 ✓
- `notificationService.list/getUnreadCount/markRead/markAllRead` defined in Task 4 Step 1, used in Task 4 Step 2 and Step 3 ✓
- `operationLogService.list` defined in Task 7 Step 1, used in Task 7 Step 3 ✓
- `approvalRuleService.list/create/update/delete` defined in Task 2 Step 1, used in Task 2 Step 2 ✓
- `userService.list` already exists in `cpq-frontend/src/services/userService.ts` ✓
- `useAuthStore` with `user.id` and `user.role` used in Tasks 4, 5, 7 — consistent with existing usage in QuotationList ✓
