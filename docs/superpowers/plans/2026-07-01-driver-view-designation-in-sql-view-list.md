# 驱动视图指定改由 SQL 视图列表工具栏完成 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除组件详情页「数据驱动路径」自由输入框，改为在 SQL 视图列表用「工具栏动作 + 只读驱动标签列」指定唯一驱动视图。

**Architecture:** `component.data_driver_path` 仍是唯一真源（值形态 `$视图名`），不加新列、存量零迁移。新增一个即时端点写它；组件保存链路停止写它以保证单一写者；SQL 视图列表通过 props 读当前驱动、通过端点切换驱动；新建首个视图自动设为驱动、删除当前驱动视图自动清空。

**Tech Stack:** Quarkus 3 + Hibernate Panache（后端）、React + Ant Design + `SelectableTable`（前端）、@QuarkusTest（后端测试）、Playwright E2E。

参考 spec：`docs/superpowers/specs/2026-07-01-driver-view-designation-in-sql-view-list-design.md`

---

## 文件结构（改动清单）

**后端**
- 修改 `cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java` — 新增 `setDriverView(UUID, String)`（唯一驱动写入 + snapshot 刷新）。
- 修改 `cpq-backend/src/main/java/com/cpq/component/resource/ComponentResource.java` — 新增 `PUT /{id}/driver-view` 端点。
- 新建 `cpq-backend/src/main/java/com/cpq/component/dto/SetDriverViewRequest.java` — 端点入参 DTO。
- 修改 `cpq-backend/src/main/java/com/cpq/component/service/ComponentSqlViewService.java` — create 首个默认驱动、delete 清空当前驱动；注入 `ComponentService`。
- 新建 `cpq-backend/src/test/java/com/cpq/component/service/DriverViewDesignationTest.java` — 驱动行为测试。

**前端**
- 修改 `cpq-frontend/src/services/componentSqlViewService.ts` — 新增 `setDriver` 客户端方法。
- 修改 `cpq-frontend/src/pages/component/SqlViewListPanel.tsx` — 去「状态」列、加「驱动」列 + 工具栏动作 + 删除驱动提示 + 新 props。
- 修改 `cpq-frontend/src/pages/component/ComponentManagement.tsx` — 移除输入框/选择路径/driver PathPicker；给面板传 props；保存链路停止写 `dataDriverPath`；草稿恢复不再覆盖 driver。

---

## Task 1: 后端 — `setDriverView` 服务方法 + 端点

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/component/dto/SetDriverViewRequest.java`
- Modify: `cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java`（在 `update` 方法之后新增方法；类已 `@Inject sqlViewRepository`、`templateService`、`em`，且有 private `normalizeDriverPath`）
- Modify: `cpq-backend/src/main/java/com/cpq/component/resource/ComponentResource.java:78`（`update` 端点之后）
- Test: `cpq-backend/src/test/java/com/cpq/component/service/DriverViewDesignationTest.java`

- [ ] **Step 1: 写请求 DTO**

Create `SetDriverViewRequest.java`:

```java
package com.cpq.component.dto;

/** PUT /components/{id}/driver-view 入参：sqlViewName=null/空 表示清空驱动。 */
public class SetDriverViewRequest {
    /** 本组件 SQL 视图名（不含 $ 前缀）；null 或空串表示取消驱动。 */
    public String sqlViewName;
}
```

- [ ] **Step 2: 先写失败测试（setDriverView 主行为）**

Create `DriverViewDesignationTest.java`（用 `@TestTransaction` 自动回滚，DB 隔离）：

```java
package com.cpq.component.service;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class DriverViewDesignationTest {

    @Inject
    ComponentService componentService;

    /** 建一个 NORMAL 组件 + 一张 ACTIVE 视图，返回 componentId。 */
    private Component newComponentWithView(String viewName) {
        Component c = new Component();
        c.code = "TEST-DRV-" + UUID.randomUUID().toString().substring(0, 8);
        c.name = "driver-test";
        c.componentType = "NORMAL";
        c.fields = "[]";
        c.formulas = "[]";
        c.persist();

        ComponentSqlView v = new ComponentSqlView();
        v.componentId = c.id;
        v.sqlViewName = viewName;
        v.sqlTemplate = "SELECT 1 AS x";
        v.declaredColumns = "[]";
        v.scope = "COMPONENT";
        v.status = "ACTIVE";
        v.persist();
        return c;
    }

    @Test
    @TestTransaction
    void setDriverView_setsDollarPrefixedPath() {
        Component c = newComponentWithView("cz_view");
        componentService.setDriverView(c.id, "cz_view");
        Component reloaded = Component.findById(c.id);
        assertEquals("$cz_view", reloaded.dataDriverPath);
    }

    @Test
    @TestTransaction
    void setDriverView_nullClearsDriver() {
        Component c = newComponentWithView("cz_view");
        componentService.setDriverView(c.id, "cz_view");
        componentService.setDriverView(c.id, null);
        Component reloaded = Component.findById(c.id);
        assertTrue(reloaded.dataDriverPath == null || reloaded.dataDriverPath.isBlank());
    }

    @Test
    @TestTransaction
    void setDriverView_unknownViewRejected() {
        Component c = newComponentWithView("cz_view");
        assertThrows(RuntimeException.class,
                () -> componentService.setDriverView(c.id, "no_such_view"));
    }
}
```

- [ ] **Step 3: 运行测试，确认因方法不存在而失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=DriverViewDesignationTest -q`
Expected: 编译失败 / FAIL —— `setDriverView` 未定义。

- [ ] **Step 4: 实现 `setDriverView`**

在 `ComponentService.java` 的 `update(...)` 方法结束（`}`）之后插入：

```java
    /**
     * 设置/清空组件的驱动视图。data_driver_path 唯一真源，值形态 $视图名。
     *
     * @param sqlViewName 本组件 ACTIVE SQL 视图名（不含 $）；null/空=清空驱动。
     */
    @Transactional
    public ComponentDTO setDriverView(UUID componentId, String sqlViewName) {
        Component component = Component.findById(componentId);
        if (component == null) {
            throw new BusinessException(404, "Component not found: " + componentId);
        }
        if (sqlViewName == null || sqlViewName.isBlank()) {
            component.dataDriverPath = null;
        } else {
            String name = sqlViewName.trim();
            boolean exists = sqlViewRepository
                    .findByComponentAndName(componentId, name)
                    .isPresent();
            if (!exists) {
                throw new BusinessException(400,
                        "SQL 视图不存在或未启用：" + name);
            }
            component.dataDriverPath = normalizeDriverPath("$" + name);
        }
        LOG.infof("[driver-view] componentId=%s set dataDriverPath='%s'",
                componentId, component.dataDriverPath);

        // 配置中心原则：driver 变更后同步所有引用该组件的模板 snapshot
        try {
            templateService.refreshSnapshotsByComponent(componentId);
        } catch (Exception e) {
            LOG.warnf("[driver-view] snapshot refresh failed componentId=%s: %s",
                    componentId, e.getMessage());
        }
        return ComponentDTO.from(component);
    }
```

> 注：`sqlViewRepository.findByComponentAndName` 只返回 ACTIVE 记录（见 `ComponentSqlViewService.create` 用法），满足「只有启用视图可设为驱动」。`normalizeDriverPath`、`BusinessException`、`ComponentDTO.from` 均已在本类可见。

- [ ] **Step 5: 运行测试，确认通过**

Run: `cd cpq-backend && ./mvnw test -Dtest=DriverViewDesignationTest -q`
Expected: PASS（3 个测试全绿）。

- [ ] **Step 6: 加端点**

在 `ComponentResource.java` 的 `update`（第 78 行 `}`）之后插入：

```java
    @PUT
    @Path("/{id}/driver-view")
    public ApiResponse<ComponentDTO> setDriverView(
            @PathParam("id") UUID id,
            com.cpq.component.dto.SetDriverViewRequest req) {
        return ApiResponse.success(
                componentService.setDriverView(id, req == null ? null : req.sqlViewName));
    }
```

- [ ] **Step 7: 重启 + curl 自检端点**

Run: `touch cpq-backend/src/main/java/com/cpq/component/resource/ComponentResource.java` 等 5-7 秒；然后
`curl -s -o /dev/null -w "%{http_code}\n" -X PUT http://localhost:8081/api/cpq/components/00000000-0000-0000-0000-000000000000/driver-view -H "Content-Type: application/json" -d '{"sqlViewName":null}'`
Expected: `404`（组件不存在 → 走 BusinessException(404)，非 500）。

- [ ] **Step 8: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/component/dto/SetDriverViewRequest.java \
        cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java \
        cpq-backend/src/main/java/com/cpq/component/resource/ComponentResource.java \
        cpq-backend/src/test/java/com/cpq/component/service/DriverViewDesignationTest.java
git commit -m "feat(component): setDriverView 端点 + 服务方法(驱动唯一真源 data_driver_path)"
```

---

## Task 2: 后端 — 首个视图默认驱动 + 删除清空驱动

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/component/service/ComponentSqlViewService.java`（`create` 两处 return 前；`delete` 软删之后；类需 `@Inject ComponentService`）
- Test: `cpq-backend/src/test/java/com/cpq/component/service/DriverViewDesignationTest.java`（追加用例）

- [ ] **Step 1: 追加失败测试**

在 `DriverViewDesignationTest.java` 追加（需 `@Inject ComponentSqlViewService sqlViewService;`，并 import `com.cpq.component.dto.CreateComponentSqlViewRequest`）：

```java
    @Inject
    ComponentSqlViewService sqlViewService;

    private CreateComponentSqlViewRequest viewReq(String name) {
        CreateComponentSqlViewRequest r = new CreateComponentSqlViewRequest();
        r.sqlViewName = name;
        r.sqlTemplate = "SELECT 1 AS x";
        r.scope = "COMPONENT";
        return r;
    }

    @Test
    @TestTransaction
    void createFirstView_becomesDriverWhenNoneSet() {
        Component c = new Component();
        c.code = "TEST-DRV-" + UUID.randomUUID().toString().substring(0, 8);
        c.name = "driver-test"; c.componentType = "NORMAL";
        c.fields = "[]"; c.formulas = "[]"; c.persist();

        sqlViewService.create(c.id, viewReq("first_view"), null);
        assertEquals("$first_view", ((Component) Component.findById(c.id)).dataDriverPath);
    }

    @Test
    @TestTransaction
    void createSecondView_doesNotStealDriver() {
        Component c = new Component();
        c.code = "TEST-DRV-" + UUID.randomUUID().toString().substring(0, 8);
        c.name = "driver-test"; c.componentType = "NORMAL";
        c.fields = "[]"; c.formulas = "[]"; c.persist();

        sqlViewService.create(c.id, viewReq("first_view"), null);
        sqlViewService.create(c.id, viewReq("second_view"), null);
        assertEquals("$first_view", ((Component) Component.findById(c.id)).dataDriverPath);
    }

    @Test
    @TestTransaction
    void deleteDriverView_clearsDriver() {
        Component c = new Component();
        c.code = "TEST-DRV-" + UUID.randomUUID().toString().substring(0, 8);
        c.name = "driver-test"; c.componentType = "NORMAL";
        c.fields = "[]"; c.formulas = "[]"; c.persist();

        var dto = sqlViewService.create(c.id, viewReq("first_view"), null);
        assertEquals("$first_view", ((Component) Component.findById(c.id)).dataDriverPath);
        sqlViewService.delete(c.id, dto.id);
        String p = ((Component) Component.findById(c.id)).dataDriverPath;
        assertTrue(p == null || p.isBlank());
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=DriverViewDesignationTest -q`
Expected: 新增 3 个用例 FAIL（default-first / clear-on-delete 未实现）。

- [ ] **Step 3: 注入 ComponentService**

在 `ComponentSqlViewService.java` 现有 `@Inject` 段（约第 50-57 行区域）追加：

```java
    @Inject
    ComponentService componentService;
```

- [ ] **Step 4: create 里加「首个默认驱动」**

在 `create(...)` 方法里，**两处 `return ComponentSqlViewDTO.from(...)`**（revive 分支约第 165 行、新建分支约第 184 行）之前，各插入同一段（复用 helper 减少重复）。先在类中新增私有 helper：

```java
    /** 若组件当前无驱动，则把刚建/复活的视图设为默认驱动。 */
    private void defaultDriverIfNone(UUID componentId, String sqlViewName) {
        Component c = Component.findById(componentId);
        if (c != null && (c.dataDriverPath == null || c.dataDriverPath.isBlank())) {
            componentService.setDriverView(componentId, sqlViewName);
        }
    }
```

然后在 revive 分支 `return` 前：

```java
            defaultDriverIfNone(componentId, reuse.sqlViewName);
            return ComponentSqlViewDTO.from(reuse, lookupComponentCode(componentId));
```

在新建分支 `return` 前：

```java
        defaultDriverIfNone(componentId, entity.sqlViewName);
        return ComponentSqlViewDTO.from(entity, lookupComponentCode(componentId));
```

- [ ] **Step 5: delete 里加「删除当前驱动则清空」**

在 `delete(...)` 软删除（`entity.status = "INACTIVE";` 约第 256 行）之后插入：

```java
        // 若被删视图正是当前驱动 → 清空组件驱动（回到无驱动=产品级单行）
        Component owner = Component.findById(componentId);
        if (owner != null && ("$" + entity.sqlViewName).equals(owner.dataDriverPath)) {
            componentService.setDriverView(componentId, null);
        }
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd cpq-backend && ./mvnw test -Dtest=DriverViewDesignationTest -q`
Expected: PASS（6 个用例全绿）。

- [ ] **Step 7: 重启自检（无循环注入）**

Run: `touch cpq-backend/src/main/java/com/cpq/component/service/ComponentSqlViewService.java` 等 5-7 秒；
`curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health`
Expected: `200`（应用正常启动，说明 `ComponentSqlViewService→ComponentService` 无循环注入问题）。

- [ ] **Step 8: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/component/service/ComponentSqlViewService.java \
        cpq-backend/src/test/java/com/cpq/component/service/DriverViewDesignationTest.java
git commit -m "feat(component): 首个SQL视图默认驱动 + 删除当前驱动视图自动清空"
```

---

## Task 3: 前端 — `componentSqlViewService.setDriver` 客户端方法

**Files:**
- Modify: `cpq-frontend/src/services/componentSqlViewService.ts:71`（对象末尾 `listGlobal` 之后）

- [ ] **Step 1: 加方法**

在 `componentSqlViewService` 对象里、`listGlobal` 之后加：

```typescript
  /** 设置/清空组件驱动视图。sqlViewName=null 表示取消驱动。返回更新后的组件。 */
  setDriver: (
    componentId: string,
    sqlViewName: string | null,
  ): Promise<{ data: { id: string; dataDriverPath?: string } }> =>
    api.put(`/components/${componentId}/driver-view`, { sqlViewName }) as Promise<any>,
```

- [ ] **Step 2: 类型检查**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误。

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/services/componentSqlViewService.ts
git commit -m "feat(frontend): componentSqlViewService.setDriver 客户端方法"
```

---

## Task 4: 前端 — SQL 视图列表：去状态列 + 驱动列 + 工具栏动作

**Files:**
- Modify: `cpq-frontend/src/pages/component/SqlViewListPanel.tsx`

- [ ] **Step 1: 扩展 Props（父传当前驱动 + 变更回调）**

把 `interface Props`（第 22-24 行）改为：

```typescript
interface Props {
  componentId: string;
  /** 当前驱动路径（= component.dataDriverPath，形态 $视图名）；空=无驱动。 */
  currentDriverPath?: string;
  /** 驱动变更后回调，参数为新的 dataDriverPath（可空）。 */
  onDriverChange?: (newDriverPath: string) => void;
}
```

并把组件签名（第 26 行）改为：

```typescript
const SqlViewListPanel: React.FC<Props> = ({ componentId, currentDriverPath, onDriverChange }) => {
```

- [ ] **Step 2: 加「判断某视图是否驱动」helper + 设/取消驱动处理**

在 `handleDryRun` 之后（约第 128 行 `};` 后）加：

```typescript
  const isDriverView = (v: ComponentSqlView) =>
    !!currentDriverPath && currentDriverPath === `$${v.sqlViewName}`;

  const handleSetDriver = async (rows: ComponentSqlView[]) => {
    const v = rows[0];
    try {
      const res = await componentSqlViewService.setDriver(componentId, v.sqlViewName);
      onDriverChange?.(res.data?.dataDriverPath ?? `$${v.sqlViewName}`);
      message.success(`已设为驱动：${v.sqlViewName}`);
      loadViews();
    } catch (e: any) {
      message.error('设置驱动失败: ' + (e?.message ?? ''));
    }
  };

  const handleClearDriver = async (rows: ComponentSqlView[]) => {
    const v = rows[0];
    try {
      await componentSqlViewService.setDriver(componentId, null);
      onDriverChange?.('');
      message.success(`已取消驱动：${v.sqlViewName}`);
      loadViews();
    } catch (e: any) {
      message.error('取消驱动失败: ' + (e?.message ?? ''));
    }
  };
```

- [ ] **Step 3: 替换列定义（去「状态」列，加「驱动」列）**

把 `columns` 里的「状态」列对象（第 163-174 行）**整段删除**，替换为「驱动」列：

```typescript
    {
      title: '驱动',
      key: 'driver',
      width: 90,
      render: (_: unknown, record: ComponentSqlView) =>
        isDriverView(record) ? (
          <Tag color="processing">驱动</Tag>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
```

- [ ] **Step 4: 加工具栏动作「设为驱动 / 取消驱动」**

在 `actions={[...]}` 数组里、`delete` 动作之前插入两项：

```typescript
          {
            key: 'set-driver',
            label: '设为驱动',
            enabledWhen: (rows) =>
              rows.length !== 1
                ? (rows.length === 0 ? false : '只能单选设置驱动')
                : isDriverView(rows[0])
                ? '该视图已是驱动'
                : true,
            onClick: handleSetDriver,
          },
          {
            key: 'clear-driver',
            label: '取消驱动',
            enabledWhen: (rows) =>
              rows.length === 1 && isDriverView(rows[0])
                ? true
                : rows.length === 0
                ? false
                : '仅当前驱动视图可取消',
            onClick: handleClearDriver,
          },
```

- [ ] **Step 5: 删除确认文案：驱动视图追加提示**

把 `delete` 动作的 `confirmDescription`（第 221-222 行）改为动态提示——因静态字符串无法读选中行，改用 `confirmTitle` 保持，`confirmDescription` 追加通用一句：

```typescript
            confirmDescription:
              '删除后字段配置中引用此视图的 BNF path 将失效；若删除的是当前驱动视图，本组件将变为无驱动（产品级单行）。如有引用将列出受影响字段供确认。',
```

- [ ] **Step 6: 类型检查 + Vite transform 自检**

Run:
```
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/SqlViewListPanel.tsx
```
Expected: tsc 0 错误；curl `200`。

- [ ] **Step 7: Commit**

```bash
git add cpq-frontend/src/pages/component/SqlViewListPanel.tsx
git commit -m "feat(frontend): SQL视图列表去状态列/加驱动列+工具栏设为/取消驱动"
```

---

## Task 5: 前端 — ComponentManagement：移除输入框、接线、停止写 driver

**Files:**
- Modify: `cpq-frontend/src/pages/component/ComponentManagement.tsx`（配置行 1413-1437；SqlViewListPanel 用法 1497；save 1232 与 1010；草稿恢复 1187）

- [ ] **Step 1: 移除「数据驱动路径」输入框 + 选择路径按钮 + driver PathPicker**

把 1413-1437 的 config-row 块替换为（**仅保留核价 BOM 递归开关**，删除输入框、"选择路径"按钮、`PathPickerDrawer`）：

```tsx
      {/* 配置行：核价 BOM 递归（驱动视图改由「SQL 视图」Tab 工具栏指定） */}
      <div className="cmm-cfg-row">
        <span className="cmm-lbl">核价 BOM 递归展开：</span>
        <Tooltip title="勾选=核价时按 material_bom_item 闭包递归展开子料号；不勾=按根料号普通取数。仅核价侧生效。">
          <Switch size="small" checked={bomRecursiveExpand} onChange={setBomRecursiveExpand} />
        </Tooltip>
      </div>
```

> 注：删除后 `driverPickerOpen` state 与其 `PathPickerDrawer` 用法一并移除；若 `driverPickerOpen`/`setDriverPickerOpen` 声明处（`useState`）不再被引用，删除其声明避免 TS 未使用告警。`dataDriverPath`/`setDataDriverPath` state **保留**（下面仍用）。

- [ ] **Step 2: 给 SqlViewListPanel 传当前驱动 + 变更回调**

把第 1497 行 `<SqlViewListPanel componentId={selectedComponent.id} />` 改为：

```tsx
            children: selectedComponent ? (
              <SqlViewListPanel
                componentId={selectedComponent.id}
                currentDriverPath={dataDriverPath}
                onDriverChange={(newPath) => {
                  setDataDriverPath(newPath);
                  // driver 变了→行键候选依赖它，刷新
                  void refreshRowKeyCandidates(selectedComponent.id, newPath, fields);
                }}
              />
            ) : null,
```

- [ ] **Step 3: 保存链路停止写 dataDriverPath（单一写者）**

`handleSave`：删除第 1232 行 `payload.dataDriverPath = dataDriverPath ?? '';`（保留 rowKeyFields / bomRecursiveExpand 两行）。
`doSaveAll`：删除第 1010 行 `payload.dataDriverPath = s.dataDriverPath ?? '';`。

> 后端 `update` 对 `dataDriverPath` 是 `!= null` 才写，前端停发即不覆盖，驱动只由 `setDriver` 端点写。

- [ ] **Step 4: 草稿恢复不再覆盖 driver（服务端权威）**

删除第 1187 行 `setDataDriverPath(draft.snapshot.dataDriverPath ?? '');`（driver 现由服务端即时保存，恢复本地草稿时不应回退到旧值；仍保留其余 draft.snapshot.* 恢复）。

- [ ] **Step 5: 类型检查 + Vite transform 自检**

Run:
```
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/ComponentManagement.tsx
```
Expected: tsc 0 错误（含无 `Input`/`PathPickerDrawer` 未使用告警——若 `Input` 仅此处用到需一并清理 import）；curl `200`。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/component/ComponentManagement.tsx
git commit -m "feat(frontend): 移除数据驱动路径输入框,驱动改由SQL视图Tab指定+停止双写driver"
```

---

## Task 6: 端到端验证（协议级改动必跑）

**Files:** 无（仅验证）

- [ ] **Step 1: 存量驱动显示正确**

选一个已有 driver 的组件（如 `COMP-0023`，driver=`$zh_view`），在组件管理进详情 → 「SQL 视图」Tab，确认 `zh_view` 行显示「驱动」标签、无「状态」列、详情页无「数据驱动路径」输入框。
（可用 curl 侧证 API：`curl -s http://localhost:8081/api/cpq/components/<COMP-0023-id> | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['dataDriverPath'])"` → `$zh_view`。）

- [ ] **Step 2: 切换/取消/首个默认/删除 手动走查**

在一个测试组件上：新建首张视图→自动「驱动」；新建第二张→驱动不变；选第二张点「设为驱动」→驱动转移、第一张变 `—`；点「取消驱动」→无驱动；删除当前驱动视图→驱动清空且确认框有提示。

- [ ] **Step 3: E2E `quotation-flow.spec.ts`**

Run（PowerShell）:
```powershell
cd cpq-frontend
Remove-Item e2e\screenshots\qf-*.png -ErrorAction SilentlyContinue
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `1 passed`；日志 `'加载中' final count = 0`；8 个 Tab `'加载中'=0`（证明驱动展开链路不受影响）。

- [ ] **Step 4: 自检声明**

汇报一行「已自检」，例：
> TS 0 错误 ✅；SqlViewListPanel.tsx + ComponentManagement.tsx → Vite 200 ✅；后端 /driver-view → 404(不存在组件,非500) ✅；DriverViewDesignationTest 6 passed ✅；存量 COMP-0023 dataDriverPath=$zh_view 不变 ✅；E2E 1 passed + 加载中=0 ✅

---

## Self-Review（对照 spec）

- **§3.1 移除输入框/选择路径/driver PathPicker、保留 BOM 开关** → Task 5 Step 1 ✅
- **§3.1 dataDriverPath 保留只读用途、不再随保存写库、面板回调同步** → Task 5 Step 2/3 ✅
- **§3.2 去状态列 + 只读驱动列** → Task 4 Step 3 ✅
- **§3.2 工具栏设为/取消驱动 + enabledWhen** → Task 4 Step 4 ✅
- **§3.2 面板经 props 读当前驱动** → Task 4 Step 1 + Task 5 Step 2 ✅
- **§4 不加新列、data_driver_path 唯一真源、即时端点、单选天然成立** → Task 1 ✅
- **§4 下游零改动 / status 列保留 DB** → 未动下游、未动 status 存储 ✅
- **§4 写者唯一性（停止组件保存写 driver）** → Task 5 Step 3 ✅
- **§5.1 首个视图默认驱动（仅无驱动时）** → Task 2 Step 4 ✅
- **§5.2 删除当前驱动视图自动清空 + 提示** → Task 2 Step 5 + Task 4 Step 5 ✅
- **§6 E2E + curl + tsc 自检** → Task 6 ✅
- **§7 验收标准** → Task 6 Step 1/2/3 覆盖 ✅

类型一致性核对：`setDriverView`(后端) / `setDriver`(前端 service) / `handleSetDriver`+`handleClearDriver`+`isDriverView`+`defaultDriverIfNone`(helper) 命名在各任务间一致；`SetDriverViewRequest.sqlViewName` 与前端 `{ sqlViewName }` 一致；`onDriverChange` 参数为 `string`（空串=清空）与 Task 5 传参一致。
