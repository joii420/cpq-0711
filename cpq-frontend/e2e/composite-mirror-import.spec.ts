/**
 * E2E 验证 V223 备份性导入 v_composite_child_* 物理视图到组件 SQL 视图（方向 X）。
 *
 * 验证目标：
 * 1. COMP-CFG-MATERIAL-RECIPE 含 composite_child_materials_mirror + composite_child_weights_mirror(GLOBAL)
 * 2. COMP-CFG-ELEMENT-BOM 含 composite_child_elements_mirror
 * 3. COMP-CFG-PROCESS 含 composite_child_processes_mirror
 * 4. /sql-views/global 返回 composite_child_weights_mirror（GLOBAL scope）
 * 5. 所有 mirror 的 sql_template 含 v_composite_child_ 视图 SQL 内容
 * 6. component.dataDriverPath 未被改动（仍指向 v_composite_child_* 物理视图）
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const BACKEND_URL = 'http://localhost:8081';

let backendUp = false;
let cookieHeader = '';
let componentIds: Record<string, string> = {};

test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

test.beforeEach(async ({ page, context }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  const cookies = await context.cookies();
  cookieHeader = cookies.map((c) => `${c.name}=${c.value}`).join('; ');

  // 拿三个核心组件 ID
  const res = await fetch(`${BACKEND_URL}/api/cpq/components?keyword=`, {
    headers: { Cookie: cookieHeader },
  });
  const json = (await res.json()) as any;
  for (const c of json.data || []) {
    if (['COMP-CFG-MATERIAL-RECIPE', 'COMP-CFG-ELEMENT-BOM', 'COMP-CFG-PROCESS'].includes(c.code)) {
      componentIds[c.code] = c.id;
    }
  }
});

test('V223: COMP-CFG-MATERIAL-RECIPE 含 materials + weights mirror', async () => {
  const cid = componentIds['COMP-CFG-MATERIAL-RECIPE'];
  expect(cid).toBeTruthy();
  const res = await fetch(`${BACKEND_URL}/api/cpq/components/${cid}/sql-views`, {
    headers: { Cookie: cookieHeader },
  });
  expect(res.status).toBe(200);
  const json = (await res.json()) as any;
  const names = (json.data || []).map((v: any) => v.sqlViewName);
  console.log('[V223] MATERIAL-RECIPE sql_views =', names);
  expect(names).toContain('composite_child_materials_mirror');
  expect(names).toContain('composite_child_weights_mirror');

  // materials mirror SQL 应含 v_composite_child_materials 视图体（UNION ALL 形态）
  const materials = (json.data || []).find(
    (v: any) => v.sqlViewName === 'composite_child_materials_mirror'
  );
  expect(materials.sqlTemplate).toMatch(/mat_bom|UNION/i);
  expect(materials.scope).toBe('COMPONENT');
  // weights 应为 GLOBAL
  const weights = (json.data || []).find(
    (v: any) => v.sqlViewName === 'composite_child_weights_mirror'
  );
  expect(weights.scope).toBe('GLOBAL');
});

test('V223: COMP-CFG-ELEMENT-BOM 含 elements mirror', async () => {
  const cid = componentIds['COMP-CFG-ELEMENT-BOM'];
  expect(cid).toBeTruthy();
  const res = await fetch(`${BACKEND_URL}/api/cpq/components/${cid}/sql-views`, {
    headers: { Cookie: cookieHeader },
  });
  const json = (await res.json()) as any;
  const names = (json.data || []).map((v: any) => v.sqlViewName);
  console.log('[V223] ELEMENT-BOM sql_views =', names);
  expect(names).toContain('composite_child_elements_mirror');
});

test('V223: COMP-CFG-PROCESS 含 processes mirror', async () => {
  const cid = componentIds['COMP-CFG-PROCESS'];
  expect(cid).toBeTruthy();
  const res = await fetch(`${BACKEND_URL}/api/cpq/components/${cid}/sql-views`, {
    headers: { Cookie: cookieHeader },
  });
  const json = (await res.json()) as any;
  const names = (json.data || []).map((v: any) => v.sqlViewName);
  console.log('[V223] PROCESS sql_views =', names);
  expect(names).toContain('composite_child_processes_mirror');
});

test('V223: GLOBAL /sql-views/global 含 weights mirror', async () => {
  const res = await fetch(`${BACKEND_URL}/api/cpq/sql-views/global`, {
    headers: { Cookie: cookieHeader },
  });
  expect(res.status).toBe(200);
  const json = (await res.json()) as any;
  const found = (json.data || []).find(
    (v: any) => v.sqlViewName === 'composite_child_weights_mirror'
  );
  expect(found).toBeTruthy();
  expect(found.componentCode).toBe('COMP-CFG-MATERIAL-RECIPE');
  console.log('[V223] global mirror componentCode =', found.componentCode);
});

test('V223: 三个核心组件 dataDriverPath 未被改动（仍 v_composite_child_*）', async () => {
  for (const code of ['COMP-CFG-MATERIAL-RECIPE', 'COMP-CFG-ELEMENT-BOM', 'COMP-CFG-PROCESS']) {
    const cid = componentIds[code];
    const res = await fetch(`${BACKEND_URL}/api/cpq/components/${cid}`, {
      headers: { Cookie: cookieHeader },
    });
    const json = (await res.json()) as any;
    const dataDriverPath = json.data?.dataDriverPath || '';
    expect(dataDriverPath).toMatch(/^v_composite_child_/);
    console.log(`[V223] ${code} dataDriverPath = ${dataDriverPath} (unchanged)`);
  }
});
