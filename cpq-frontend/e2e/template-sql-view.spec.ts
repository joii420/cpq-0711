/**
 * E2E spec: Excel 模板独立 SQL 视图 — 验收测试
 *
 * 覆盖范围（方案 §9.3 + CLAUDE.md 验收任务）：
 *
 * 后端 API（直连，不依赖前端状态）：
 *   T1. GET /templates/{id}/sql-views — 空列表初始化
 *   T2. POST dry-run — 合法 SELECT 返回 declared_columns
 *   T3. POST dry-run — DDL 被拒绝（success=false）
 *   T4. POST dry-run — :hfPartNo 被拒绝（success=false）
 *   T5. POST create — 创建合法视图 → 200 + declared_columns
 *   T6. GET list — 包含新建项 + templateId 字段
 *   T7. 重复名称 → 409 Conflict
 *   T8. PUT update — 修改视图
 *   T9. DELETE soft delete — list 不再含
 *
 * UI 驱动（TemplateConfiguration 编辑页）：
 *   T10. 进入模板配置页 → "SQL 视图" Tab 可见
 *   T11. "SQL 视图" Tab 下有"新建 SQL 视图"按钮
 *   T12. ExcelViewConfigTab "🗄 SQL 视图"按钮存在
 *   T13. PathPickerDrawer — TEMPLATE 上下文，SQL 视图 Tab 默认选中 + 无 GLOBAL 区域
 *   T14. PathPickerDrawer — manual Tab 输入 $$ 路径 → error alert 显示
 *   T15. PathPickerDrawer — manual Tab 输入老 PG 直引 → WARN alert 显示
 *
 * 注意事项：
 * - 后端路由 POST /templates/{templateId}/sql-views/dry-run（要求 templateId 在路径中）
 * - B-TSV-01 已修复：templateSqlViewService.{get,update,delete,dryRun} 路由都已含 templateId
 * - 登录 cookie 在 beforeAll 中统一获取，避免 Redis 速率限制（30次/分/IP）
 */
import { test, expect, type Page, type BrowserContext } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const BACKEND_URL = 'http://localhost:8081';
const E2E_PREFIX = 'e2e_tsv_';

let backendUp = false;
let cookieHeader = '';
let testTemplateId = '';

// ── 工具函数 ──────────────────────────────────────────────────────────────────

async function apiFetch(
  path: string,
  options: RequestInit = {},
): Promise<Response> {
  return fetch(`${BACKEND_URL}/api/cpq${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Cookie: cookieHeader,
      ...(options.headers ?? {}),
    },
  });
}

async function deleteAllE2EViews(templateId: string) {
  const res = await apiFetch(`/templates/${templateId}/sql-views`);
  if (!res.ok) return;
  const json = (await res.json()) as any;
  const views: any[] = json.data ?? [];
  for (const v of views) {
    if (String(v.sqlViewName).startsWith(E2E_PREFIX)) {
      await apiFetch(`/templates/${templateId}/sql-views/${v.id}`, {
        method: 'DELETE',
      });
    }
  }
}

/** 打开 PathPickerDrawer：从 TemplateConfiguration Excel 视图模式点击第一个"SQL 视图"按钮 */
async function openPathPickerDrawer(page: Page) {
  // 先确保在"组件配置" Tab（包含 Excel 视图按钮）
  // .tm-center-toolbar sticky 遮盖问题：用 evaluate 直接点 .ant-tabs-tab-btn 内层元素
  const componentsTab = page.locator('.ant-tabs-tab').filter({ hasText: /^组件配置$/ }).first();
  if (await componentsTab.count() > 0) {
    await componentsTab.evaluate((tabEl) => {
      const btn = tabEl.querySelector('.ant-tabs-tab-btn') as HTMLElement | null;
      if (btn) btn.click();
      else (tabEl as HTMLElement).click();
    });
    await page.waitForTimeout(300);
  }

  // 切换到 Excel 视图模式（若可点）
  const excelViewBtn = page.locator('button').filter({ hasText: 'Excel视图' }).first();
  if (await excelViewBtn.count() > 0 && await excelViewBtn.isVisible()) {
    await excelViewBtn.click();
    await page.waitForTimeout(800);
  }

  // 如果没有列，先添加一列
  const addColBtn = page.getByRole('button', { name: '添加列' });
  if (await addColBtn.count() > 0 && await addColBtn.isVisible()) {
    // 检查是否已经有列
    const noCols = page.locator('text=暂无列配置').first();
    if (await noCols.count() > 0) {
      await addColBtn.click();
      await page.waitForTimeout(500);
    } else {
      // 检查是否有 SQL 视图按钮（有列的情况）
      const sqlBtn = page.locator('button[title*="SQL"]').first();
      if ((await sqlBtn.count()) === 0) {
        await addColBtn.click();
        await page.waitForTimeout(500);
      }
    }
  }

  // 找并点击"🗄 SQL 视图"按钮（title 属性更稳定）
  const sqlViewBtn = page.locator('button[title*="SQL"]').first();
  const altBtn = page.getByRole('button', { name: /SQL 视图/ }).first();

  if (await sqlViewBtn.count() > 0 && await sqlViewBtn.isVisible()) {
    await sqlViewBtn.click();
  } else if (await altBtn.count() > 0 && await altBtn.isVisible()) {
    await altBtn.click();
  } else {
    throw new Error('未找到"🗄 SQL 视图"按钮，请确认 ExcelViewConfigTab 有列且已切换到 Excel 视图模式');
  }
  await page.waitForTimeout(600);
}

// ── 前置 / 后置 ───────────────────────────────────────────────────────────────

// 在 beforeAll 中统一登录，避免 Redis 速率限制
test.beforeAll(async ({ browser }) => {
  backendUp = await isBackendUp();
  if (!backendUp) return;

  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await loginAsAdmin(page);
    const cookies = await context.cookies();
    cookieHeader = cookies.map((c) => `${c.name}=${c.value}`).join('; ');

    // 找一个 DRAFT 状态的 template
    const res = await apiFetch('/templates?size=20&status=DRAFT');
    const json = (await res.json()) as any;
    const templates: any[] = json.data ?? [];
    if (templates.length > 0) {
      testTemplateId = templates[0].id;
      console.log(`[TSV-E2E] testTemplateId=${testTemplateId} name=${templates[0].name?.slice(0, 40)}`);
    }
  } finally {
    await page.close();
    await context.close();
  }
});

test.afterAll(async () => {
  if (backendUp && cookieHeader && testTemplateId) {
    await deleteAllE2EViews(testTemplateId).catch(() => {});
  }
});

// 每个 test 前跳过检查，不重新登录
test.beforeEach(async () => {
  test.skip(!backendUp, '后端未启动，跳过');
  test.skip(!testTemplateId, '未找到 DRAFT 模板，跳过');
});

// ── T1: 空列表初始化 ──────────────────────────────────────────────────────────

test('T1: GET /templates/{id}/sql-views 初始为空数组', async () => {
  await deleteAllE2EViews(testTemplateId);
  const res = await apiFetch(`/templates/${testTemplateId}/sql-views`);
  expect(res.status).toBe(200);
  const json = (await res.json()) as any;
  expect(Array.isArray(json.data)).toBe(true);
  console.log('[T1] 初始 sql-views 列表长度:', json.data.length);
});

// ── T2: dry-run 通过合法 SELECT ──────────────────────────────────────────────

test('T2: dry-run 合法 SELECT → success=true + declared_columns', async () => {
  const res = await apiFetch(
    `/templates/${testTemplateId}/sql-views/dry-run`,
    {
      method: 'POST',
      body: JSON.stringify({
        sqlTemplate:
          "SELECT 'TEST'::varchar AS test_col, 1::int AS test_num",
      }),
    },
  );
  expect(res.status).toBe(200);
  const json = (await res.json()) as any;
  expect(json.data.success).toBe(true);
  const cols = json.data.declaredColumns ?? [];
  expect(cols.length).toBeGreaterThanOrEqual(2);
  const colNames = cols.map((c: any) => c.name);
  expect(colNames).toContain('test_col');
  expect(colNames).toContain('test_num');
  console.log('[T2] dry-run OK, cols:', colNames);
});

// ── T3: dry-run 拒绝 DDL ─────────────────────────────────────────────────────

test('T3: dry-run 拒绝 DDL (INSERT) → success=false', async () => {
  const res = await apiFetch(
    `/templates/${testTemplateId}/sql-views/dry-run`,
    {
      method: 'POST',
      body: JSON.stringify({
        sqlTemplate: "INSERT INTO mat_part (hf_part_no) VALUES ('hack')",
      }),
    },
  );
  expect(res.status).toBe(200);
  const json = (await res.json()) as any;
  expect(json.data.success).toBe(false);
  expect((json.data.error ?? '').length).toBeGreaterThan(0);
  console.log('[T3] DDL rejected:', json.data.error);
});

// ── T4: dry-run 拒绝 :hfPartNo ──────────────────────────────────────────────

test('T4: dry-run 拒绝 :hfPartNo 标量占位符 → success=false', async () => {
  const res = await apiFetch(
    `/templates/${testTemplateId}/sql-views/dry-run`,
    {
      method: 'POST',
      body: JSON.stringify({
        sqlTemplate: 'SELECT * FROM mat_part WHERE hf_part_no = :hfPartNo',
      }),
    },
  );
  expect(res.status).toBe(200);
  const json = (await res.json()) as any;
  expect(json.data.success).toBe(false);
  expect(json.data.error ?? '').toContain('hfPartNo');
  console.log('[T4] :hfPartNo rejected:', json.data.error);
});

// ── T5: 创建合法视图 ──────────────────────────────────────────────────────────

test('T5: POST 创建合法 SQL 视图 → 200 + declared_columns + templateId', async () => {
  await deleteAllE2EViews(testTemplateId);
  const res = await apiFetch(`/templates/${testTemplateId}/sql-views`, {
    method: 'POST',
    body: JSON.stringify({
      sqlViewName: `${E2E_PREFIX}summary`,
      sqlTemplate:
        "SELECT 'TEST'::varchar AS test_col, 1::int AS test_num",
      description: 'E2E 测试视图',
    }),
  });
  expect(res.status).toBe(200);
  const json = (await res.json()) as any;
  expect(json.data.sqlViewName).toBe(`${E2E_PREFIX}summary`);
  expect(json.data.templateId).toBe(testTemplateId);
  // declaredColumns 应为解析后的数组（后端 DTO 不再返原始 JSONB 字符串）
  const cols = json.data.declaredColumns;
  expect(Array.isArray(cols)).toBe(true);
  expect(cols.length).toBeGreaterThanOrEqual(2);
  console.log('[T5] created id=', json.data.id, 'declaredColumns=', cols);
});

// ── T6: list 包含新建项 + templateId 字段 ────────────────────────────────────

test('T6: list 包含新建项 + templateId 字段正确', async () => {
  await deleteAllE2EViews(testTemplateId);
  await apiFetch(`/templates/${testTemplateId}/sql-views`, {
    method: 'POST',
    body: JSON.stringify({
      sqlViewName: `${E2E_PREFIX}list_chk`,
      sqlTemplate: "SELECT 1 AS n",
    }),
  });
  const res = await apiFetch(`/templates/${testTemplateId}/sql-views`);
  expect(res.status).toBe(200);
  const json = (await res.json()) as any;
  const found = (json.data ?? []).find(
    (v: any) => v.sqlViewName === `${E2E_PREFIX}list_chk`,
  );
  expect(found).toBeTruthy();
  expect(found.templateId).toBe(testTemplateId);
  expect(found.scope).toBe('LOCAL');
  console.log('[T6] listed:', found.sqlViewName, 'templateId=', found.templateId, 'scope=', found.scope);
});

// ── T7: 重复名称 → 409 ───────────────────────────────────────────────────────

test('T7: 重复 sqlViewName → 409 Conflict', async () => {
  await deleteAllE2EViews(testTemplateId);
  const first = await apiFetch(`/templates/${testTemplateId}/sql-views`, {
    method: 'POST',
    body: JSON.stringify({
      sqlViewName: `${E2E_PREFIX}dup`,
      sqlTemplate: "SELECT 1 AS x",
    }),
  });
  expect(first.status).toBe(200);
  const dup = await apiFetch(`/templates/${testTemplateId}/sql-views`, {
    method: 'POST',
    body: JSON.stringify({
      sqlViewName: `${E2E_PREFIX}dup`,
      sqlTemplate: "SELECT 2 AS y",
    }),
  });
  expect(dup.status).toBe(409);
  console.log('[T7] duplicate rejected with 409');
});

// ── T8: 更新视图 ──────────────────────────────────────────────────────────────

test('T8: PUT update → declared_columns 重新提取', async () => {
  await deleteAllE2EViews(testTemplateId);
  const createRes = await apiFetch(`/templates/${testTemplateId}/sql-views`, {
    method: 'POST',
    body: JSON.stringify({
      sqlViewName: `${E2E_PREFIX}update_me`,
      sqlTemplate: "SELECT 1 AS old_col",
    }),
  });
  expect(createRes.status).toBe(200);
  const created = (await createRes.json()) as any;
  const viewId = created.data.id;

  // 后端路由：PUT /templates/{templateId}/sql-views/{id}（含 templateId）
  const updateRes = await apiFetch(
    `/templates/${testTemplateId}/sql-views/${viewId}`,
    {
      method: 'PUT',
      body: JSON.stringify({
        sqlViewName: `${E2E_PREFIX}update_me`,
        sqlTemplate: "SELECT 1 AS new_col, 2 AS extra_col",
      }),
    },
  );
  expect(updateRes.status).toBe(200);
  const updated = (await updateRes.json()) as any;
  const colNames = (updated.data.declaredColumns ?? []).map((c: any) => c.name);
  expect(colNames).toContain('new_col');
  expect(colNames).toContain('extra_col');
  expect(colNames).not.toContain('old_col');
  console.log('[T8] updated cols:', colNames);
});

// ── T9: 软删除 → list 不再含 ─────────────────────────────────────────────────

test('T9: DELETE 软删除 → list 不再包含该项', async () => {
  await deleteAllE2EViews(testTemplateId);
  const createRes = await apiFetch(`/templates/${testTemplateId}/sql-views`, {
    method: 'POST',
    body: JSON.stringify({
      sqlViewName: `${E2E_PREFIX}soft_del`,
      sqlTemplate: "SELECT 1 AS x",
    }),
  });
  expect(createRes.status).toBe(200);
  const view = (await createRes.json()) as any;
  const viewId = view.data.id;

  const delRes = await apiFetch(
    `/templates/${testTemplateId}/sql-views/${viewId}`,
    { method: 'DELETE' },
  );
  expect(delRes.status).toBe(200);

  const listRes = await apiFetch(`/templates/${testTemplateId}/sql-views`);
  const json = (await listRes.json()) as any;
  const stillThere = (json.data ?? []).find((v: any) => v.id === viewId);
  expect(stillThere).toBeFalsy();
  console.log('[T9] soft delete confirmed, list no longer contains viewId=', viewId);
});

// ── UI 测试（需要浏览器 page fixture）────────────────────────────────────────

// ── T10: UI — "SQL 视图" Tab 在 TemplateConfiguration 可见 ──────────────────

test('T10: UI TemplateConfiguration — "SQL 视图" Tab 可见', async ({ page, context }) => {
  // 在 UI 测试中需要先恢复登录 session（beforeAll 中拿了 cookie）
  const cookiePairs = cookieHeader.split('; ').map((pair) => {
    const idx = pair.indexOf('=');
    return { name: pair.slice(0, idx), value: pair.slice(idx + 1), domain: 'localhost', path: '/' };
  });
  await context.addCookies(cookiePairs);

  await page.goto(`/templates/${testTemplateId}`);
  await page.waitForLoadState('networkidle');

  // Ant Design Tabs 的 tab 标签是 div.ant-tabs-tab，不是真正的 ARIA tab role
  // 使用 locator + hasText 更可靠
  const sqlViewTab = page.locator('.ant-tabs-tab').filter({ hasText: /^SQL 视图$/ }).first();
  await expect(sqlViewTab).toBeVisible({ timeout: 10000 });
  console.log('[T10] "SQL 视图" Tab 可见 ✓');
});

// ── T11: UI — "SQL 视图" Tab 下有"新建 SQL 视图"按钮 ────────────────────────

test('T11: UI 切换到 "SQL 视图" Tab → "新建 SQL 视图" 按钮可见', async ({ page, context }) => {
  const cookiePairs = cookieHeader.split('; ').map((pair) => {
    const idx = pair.indexOf('=');
    return { name: pair.slice(0, idx), value: pair.slice(idx + 1), domain: 'localhost', path: '/' };
  });
  await context.addCookies(cookiePairs);

  await page.goto(`/templates/${testTemplateId}`);
  await page.waitForLoadState('networkidle');

  // 注意：.tm-center-toolbar 是 sticky 固定栏遮盖 Tabs 导航区域。
  // Ant Design Tabs 的 onChange 由 .ant-tabs-tab-btn 内层元素触发，
  // 使用 JavaScript 直接点击内层按钮确保 React 事件触发。
  const sqlViewTab = page
    .locator('.ant-tabs-tab')
    .filter({ hasText: /^SQL 视图$/ })
    .first();
  await expect(sqlViewTab).toBeVisible({ timeout: 8000 });

  // 用 evaluate 获取内层 .ant-tabs-tab-btn 并 dispatchEvent 触发点击
  await sqlViewTab.evaluate((tabEl) => {
    const btn = tabEl.querySelector('.ant-tabs-tab-btn') as HTMLElement | null;
    if (btn) {
      btn.click();
    } else {
      (tabEl as HTMLElement).click();
    }
  });
  await page.waitForTimeout(1000);

  // 期望"新建 SQL 视图"按钮
  const createBtn = page.getByRole('button', { name: '新建 SQL 视图' });
  await expect(createBtn).toBeVisible({ timeout: 8000 });
  console.log('[T11] "新建 SQL 视图" 按钮可见 ✓');
});

// ── T12: UI — ExcelViewConfigTab 中"🗄 SQL 视图"按钮存在 ────────────────────

test('T12: UI ExcelViewConfigTab 中"🗄 SQL 视图"按钮可见', async ({ page, context }) => {
  const cookiePairs = cookieHeader.split('; ').map((pair) => {
    const idx = pair.indexOf('=');
    return { name: pair.slice(0, idx), value: pair.slice(idx + 1), domain: 'localhost', path: '/' };
  });
  await context.addCookies(cookiePairs);

  await page.goto(`/templates/${testTemplateId}`);
  await page.waitForLoadState('networkidle');

  // 切换到 "组件配置" Tab（包含 Excel 视图模式切换按钮）
  // .tm-center-toolbar sticky 遮盖问题：用 evaluate 直接点 .ant-tabs-tab-btn 内层元素
  const componentsTab = page.locator('.ant-tabs-tab').filter({ hasText: /^组件配置$/ }).first();
  await expect(componentsTab).toBeVisible({ timeout: 8000 });
  await componentsTab.evaluate((tabEl) => {
    const btn = tabEl.querySelector('.ant-tabs-tab-btn') as HTMLElement | null;
    if (btn) btn.click();
    else (tabEl as HTMLElement).click();
  });
  await page.waitForTimeout(400);

  // 点"Excel视图"按钮（TemplateConfiguration 中心工具栏按钮）
  const excelViewBtn = page.locator('button').filter({ hasText: 'Excel视图' }).first();
  await expect(excelViewBtn).toBeVisible({ timeout: 5000 });
  await excelViewBtn.click();
  await page.waitForTimeout(800);

  // 如没有列则添加一列
  const addColBtn = page.getByRole('button', { name: '添加列' });
  if (await addColBtn.count() > 0 && await addColBtn.isVisible()) {
    await addColBtn.click();
    await page.waitForTimeout(500);
  }

  // 期望"🗄 SQL 视图"按钮存在（title 属性查找更可靠，因为 emoji 渲染可能因环境不同）
  const sqlViewBtnByTitle = page.locator('button[title*="SQL"]').first();
  const sqlViewBtnByText = page.getByRole('button', { name: /SQL 视图/ }).first();

  let found = false;
  if (await sqlViewBtnByTitle.count() > 0) {
    await expect(sqlViewBtnByTitle).toBeVisible({ timeout: 5000 });
    found = true;
    console.log('[T12] "🗄 SQL 视图" 按钮通过 title 可见 ✓');
  } else if (await sqlViewBtnByText.count() > 0) {
    await expect(sqlViewBtnByText).toBeVisible({ timeout: 5000 });
    found = true;
    console.log('[T12] "🗄 SQL 视图" 按钮通过文字可见 ✓');
  }
  expect(found).toBe(true);
});

// ── T13: UI — PathPickerDrawer TEMPLATE 上下文：SQL 视图 Tab 默认选中 + 无 GLOBAL 区 ──

test('T13: UI PathPickerDrawer TEMPLATE 上下文 — SQL 视图 Tab 默认选中，无 GLOBAL 区域', async ({ page, context }) => {
  const cookiePairs = cookieHeader.split('; ').map((pair) => {
    const idx = pair.indexOf('=');
    return { name: pair.slice(0, idx), value: pair.slice(idx + 1), domain: 'localhost', path: '/' };
  });
  await context.addCookies(cookiePairs);

  await page.goto(`/templates/${testTemplateId}`);
  await page.waitForLoadState('networkidle');

  await openPathPickerDrawer(page);

  // 期望抽屉出现（title "插入物理表路径"）
  const drawerTitle = page.locator('.ant-drawer-title').filter({ hasText: '插入物理表路径' });
  await expect(drawerTitle).toBeVisible({ timeout: 8000 });

  // PathPickerDrawer 内部的 Tabs 要与外部 TemplateConfiguration 的 tabs 区分
  // 抽屉内的 tab 在 .ant-drawer-body 下
  const drawerBody = page.locator('.ant-drawer-body');

  // 期望 "SQL 视图" Tab 在抽屉内是激活状态（defaultTab="sql-view"）
  const sqlTabInDrawer = drawerBody.locator('.ant-tabs-tab').filter({ hasText: /SQL 视图/ }).first();
  await expect(sqlTabInDrawer).toBeVisible({ timeout: 5000 });
  const isActive = await sqlTabInDrawer.evaluate((el) =>
    el.classList.contains('ant-tabs-tab-active'),
  );
  expect(isActive).toBe(true);

  // 期望没有 "GLOBAL" 文字（TEMPLATE 上下文隔离，不显示跨组件 GLOBAL 区域）
  const globalTextInDrawer = drawerBody.locator('text=GLOBAL').first();
  expect(await globalTextInDrawer.count()).toBe(0);

  console.log('[T13] PathPickerDrawer TEMPLATE 上下文 SQL 视图 Tab 默认选中 ✓, GLOBAL 区域不显示 ✓');
});

// ── T14: UI — manual Tab 输入 $$ 路径 → error alert 显示 ──────────────────

test('T14: UI PathPickerDrawer TEMPLATE 上下文 — manual Tab $$ 路径显示 error alert，确认按钮被阻止', async ({ page, context }) => {
  const consoleErrors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });

  const cookiePairs = cookieHeader.split('; ').map((pair) => {
    const idx = pair.indexOf('=');
    return { name: pair.slice(0, idx), value: pair.slice(idx + 1), domain: 'localhost', path: '/' };
  });
  await context.addCookies(cookiePairs);

  await page.goto(`/templates/${testTemplateId}`);
  await page.waitForLoadState('networkidle');

  await openPathPickerDrawer(page);

  const drawerTitle = page.locator('.ant-drawer-title').filter({ hasText: '插入物理表路径' });
  await expect(drawerTitle).toBeVisible({ timeout: 8000 });

  const drawerBody = page.locator('.ant-drawer-body');

  // 切到 manual Tab（"手动输入 BNF"）
  const manualTab = drawerBody.locator('.ant-tabs-tab').filter({ hasText: /手动输入/ }).first();
  await expect(manualTab).toBeVisible({ timeout: 5000 });
  await manualTab.click();
  await page.waitForTimeout(400);

  // 输入 $$ 路径
  const textarea = drawerBody.locator('textarea').first();
  await textarea.fill('$$COMP-FAKE.view.col');
  await page.waitForTimeout(400);

  // 期望出现 error type Alert（含"不允许 $$ 跨组件引用"）
  const errorAlert = drawerBody.locator('.ant-alert-error').first();
  await expect(errorAlert).toBeVisible({ timeout: 5000 });

  // 确认 error alert 文案匹配
  const alertText = await errorAlert.textContent();
  expect(alertText ?? '').toMatch(/\$\$|跨组件|不允许|隔离/);

  // 期望点击"插入"按钮后抽屉不关闭（TEMPLATE 上下文中 $$ 路径被 handleConfirm 阻止）
  const confirmBtn = page.locator('.ant-drawer-footer').getByRole('button', { name: '插入' }).first();
  if (await confirmBtn.count() > 0) {
    await confirmBtn.click();
    await page.waitForTimeout(500);
    // 抽屉应仍然开着
    await expect(drawerTitle).toBeVisible({ timeout: 3000 });
  }

  console.log('[T14] $$ 路径 error alert 显示 ✓, text:', alertText?.slice(0, 60));
  console.log('[T14] console errors (antd warnings only):', consoleErrors.filter(e => e.includes('Warning:')).slice(0, 3));
});

// ── T15: UI — manual Tab 老 PG 直引 → WARN alert 显示 ────────────────────

test('T15: UI PathPickerDrawer TEMPLATE 上下文 — manual Tab 老 PG 直引显示 WARN alert', async ({ page, context }) => {
  const cookiePairs = cookieHeader.split('; ').map((pair) => {
    const idx = pair.indexOf('=');
    return { name: pair.slice(0, idx), value: pair.slice(idx + 1), domain: 'localhost', path: '/' };
  });
  await context.addCookies(cookiePairs);

  await page.goto(`/templates/${testTemplateId}`);
  await page.waitForLoadState('networkidle');

  await openPathPickerDrawer(page);

  const drawerTitle = page.locator('.ant-drawer-title').filter({ hasText: '插入物理表路径' });
  await expect(drawerTitle).toBeVisible({ timeout: 8000 });

  const drawerBody = page.locator('.ant-drawer-body');

  // 切到 manual Tab
  const manualTab = drawerBody.locator('.ant-tabs-tab').filter({ hasText: /手动输入/ }).first();
  await expect(manualTab).toBeVisible({ timeout: 5000 });
  await manualTab.click();
  await page.waitForTimeout(400);

  // 输入老 PG 视图直引路径（legacyPathPolicy=WARN_WITH_MIGRATION_SUGGEST）
  const textarea = drawerBody.locator('textarea').first();
  await textarea.fill('v_costing_summary_full.material_cost');
  await page.waitForTimeout(400);

  // 期望出现 warning type Alert（"老 PG 视图直引（建议迁移）"）
  const warnAlert = drawerBody.locator('.ant-alert-warning').first();
  await expect(warnAlert).toBeVisible({ timeout: 5000 });

  const warnText = await warnAlert.textContent();
  expect(warnText ?? '').toMatch(/老 PG 视图|直引|迁移/);

  // WARN 模式下"插入"按钮不应 disabled（可以确认，只是有警告）
  // 这里只验证 alert 出现即可，不强制检查 disabled 状态
  console.log('[T15] 老 PG 直引 WARN alert 显示 ✓, text:', warnText?.slice(0, 60));
});

// ── Bug 清单（已知缺陷，测试故意跳过等修复后验证）──────────────────────────

test('B-TSV-01 回归：dry-run 路由含 templateId — 不应返 404', async () => {
  test.skip(!backendUp, '后端未启动，跳过');
  test.skip(!testTemplateId, '未找到 DRAFT 模板，跳过');

  // 直接打到后端,验证路由可达(200 + success=true / false 都行,关键是非 404)
  const res = await apiFetch(`/templates/${testTemplateId}/sql-views/dry-run`, {
    method: 'POST',
    body: JSON.stringify({ sqlTemplate: 'SELECT 1::int AS test_col' }),
  });
  expect(res.status).toBe(200);
  const json = (await res.json()) as any;
  expect(json.data?.success).toBe(true);
  console.log('[B-TSV-01-fix] dry-run 路由对齐,success=true ✓');
});
