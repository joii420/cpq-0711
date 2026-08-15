/**
 * repair-0814 回归：行键（row_key_fields）= **字段名**口径契约。
 *
 * 背景：组件管理「字段配置 → 行键」列的复选框原先按 driver 视图真实列名(resolvedColumn)读写，
 * 而 row_key_fields 存的是字段名(rule-0724 §2.4 C3) → 已配好的行键一律显示未勾选 → 用户去点 →
 * 追加一份英文列名(material_no/parent_no…)且删不掉。本 spec 锁死修复后的契约。
 *
 * 四段顺序执行（同一临时组件）：
 *   S1 阴性  — 打开不改任何东西直接保存 → 行键逐字不变
 *   S2 勾选态 — 已配好的行键字段必须显示为已勾选（旧实现恒未勾选，正是诱导用户去点的根源）
 *   S3 写入/取消 — 勾选写入的是字段名本身、且取消能真正生效
 *   S4 存量清除 — 库里残留的 driver 列名项，打开保存后被自然清除，字段名项一个不少
 *
 * 全程使用独立临时目录 + 临时组件，不触碰任何真实数据；结束清理。
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';
import * as fs from 'fs';
import type { Page } from '@playwright/test';

const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';
const BUNDLE = '/home/joii/project/cpq/客户组件模板/核价组件/核价组件模板-简易.json';
const DIR_NAME = 'ZZ-回归-repair0814';

let cookieHeader = '';
let dirId = '';
let comp: { id: string; name: string; code: string };

async function api(path: string, init: RequestInit = {}) {
  return fetch(`${BACKEND_URL}${path}`, {
    ...init,
    headers: { Cookie: cookieHeader, 'Content-Type': 'application/json', ...(init.headers || {}) },
  });
}
async function getComp() {
  return ((await (await api(`/api/cpq/components/${comp.id}`)).json()) as any).data;
}

async function openComponent(page: Page) {
  await page.goto('/components-raw');
  await page.waitForLoadState('networkidle');
  await page.locator('input[placeholder*="搜索"]').first().fill(comp.code);
  await page.waitForTimeout(1500);
  const dirNode = page.locator(`text=${DIR_NAME}`).first();
  if (await dirNode.count()) { await dirNode.click({ timeout: 10_000 }); await page.waitForTimeout(1000); }
  const node = page.locator(`.cmm-c-code:has-text("${comp.code}")`).first();
  await node.waitFor({ state: 'attached', timeout: 20_000 });
  await node.evaluate((el) => {
    const card = (el as HTMLElement).closest('[class*="cmm-c"]') as HTMLElement | null;
    (card ?? (el as HTMLElement)).click();
  });
  await page.waitForTimeout(2500); // getById + row-key-candidates(400ms debounce)
}

/** 读「行键」列每行 {字段名, 勾选, 禁用}；字段名列是 <Input>，须取 value 而非 innerText */
async function readRowKeyColumn(page: Page) {
  return page.evaluate(() => {
    const ths = Array.from(document.querySelectorAll('.ant-table-thead th'));
    const colOf = (t: string) => ths.findIndex((th) => (th.textContent || '').trim() === t);
    const rkIdx = colOf('行键');
    const nameIdx = colOf('字段名');
    if (rkIdx < 0 || nameIdx < 0) return [] as Array<{ field: string; checked: boolean; disabled: boolean }>;
    return Array.from(document.querySelectorAll('.ant-table-tbody tr')).map((r) => {
      const tds = r.querySelectorAll('td');
      const box = tds[rkIdx]?.querySelector('input[type=checkbox]') as HTMLInputElement | undefined;
      const nameInput = tds[nameIdx]?.querySelector('input') as HTMLInputElement | undefined;
      return { field: (nameInput?.value || '').trim(), checked: !!box?.checked, disabled: !!box?.disabled };
    });
  });
}

async function toggleRowKey(page: Page, field: string) {
  const ok = await page.evaluate((f) => {
    const ths = Array.from(document.querySelectorAll('.ant-table-thead th'));
    const colOf = (t: string) => ths.findIndex((th) => (th.textContent || '').trim() === t);
    const rkIdx = colOf('行键');
    const nameIdx = colOf('字段名');
    if (rkIdx < 0 || nameIdx < 0) return false;
    for (const r of Array.from(document.querySelectorAll('.ant-table-tbody tr'))) {
      const tds = r.querySelectorAll('td');
      const nameInput = tds[nameIdx]?.querySelector('input') as HTMLInputElement | undefined;
      if ((nameInput?.value || '').trim() !== f) continue;
      const box = tds[rkIdx]?.querySelector('input[type=checkbox]') as HTMLInputElement | undefined;
      if (!box || box.disabled) return false;
      box.click();
      return true;
    }
    return false;
  }, field);
  expect(ok, `未能点到字段「${field}」的行键复选框`).toBeTruthy();
  await page.waitForTimeout(600);
}

async function save(page: Page) {
  await page.getByRole('button', { name: /^保\s*存$/ }).last().click({ timeout: 15_000 });
  await page.waitForTimeout(2500);
}

test.describe('repair-0814 · 行键=字段名口径契约', () => {
  test.beforeAll(async () => { expect(await isBackendUp()).toBeTruthy(); });

  test('行键写入/显示/取消/存量清除 四段契约', async ({ page, context }) => {
    test.setTimeout(300_000);
    await loginAsAdmin(page);
    cookieHeader = (await context.cookies()).map((c) => `${c.name}=${c.value}`).join('; ');

    // 建临时目录 + 导入（bundle 的 rowKeyFields 是纯字段名）
    dirId = ((await (await api('/api/cpq/component-directories', {
      method: 'POST', body: JSON.stringify({ name: DIR_NAME, parentId: null }),
    })).json()) as any).data.id;
    const bundleText = fs.readFileSync(BUNDLE, 'utf-8');
    await api(`/api/cpq/component-directories/${dirId}/import`, { method: 'POST', body: bundleText });
    await api(`/api/cpq/component-directories/${dirId}/import/commit?conflictPolicy=RENAME`,
      { method: 'POST', body: bundleText });
    const listed = (await (await api(`/api/cpq/components?directoryId=${dirId}`)).json()) as any;
    const all = (listed.data || []).filter((c: any) => c.directoryId === dirId);
    const t = all.find((c: any) => c.name === '物料BOM') || all[0];
    comp = { id: t.id, name: t.name, code: t.code };
    const baseline: string[] = (await getComp()).rowKeyFields;
    console.log(`[RK] 临时组件 ${comp.code} ${comp.name}；导入后 rowKeyFields=${JSON.stringify(baseline)}`);

    const puts: any[] = [];
    page.on('request', (r) => {
      if (r.method() === 'PUT' && r.url().includes(`/api/cpq/components/${comp.id}`)) {
        try { puts.push(JSON.parse(r.postData() || '{}')); } catch { /* noop */ }
      }
    });

    // ── S1 阴性：不改任何东西直接保存 ──
    await openComponent(page);
    await save(page);
    console.log(`[RK][S1] PUT payload rowKeyFields = ${JSON.stringify(puts[0]?.rowKeyFields)}`);
    expect(puts[0]?.rowKeyFields, 'S1 PUT 请求体应与加载值逐字一致').toEqual(baseline);
    expect((await getComp()).rowKeyFields, 'S1 落库不应变化').toEqual(baseline);

    // ── S2 勾选态：已配好的行键字段必须显示已勾选 ──
    const rows = await readRowKeyColumn(page);
    console.log(`[RK][S2] 勾选态 = ${JSON.stringify(rows.filter((r) => r.checked).map((r) => r.field))}`);
    for (const f of baseline) {
      const row = rows.find((r) => r.field === f);
      expect(row, `字段「${f}」未出现在字段表`).toBeTruthy();
      expect(row!.checked, `字段「${f}」在行键里却显示未勾选`).toBeTruthy();
    }
    // 反向：不在行键里的字段必须显示未勾选
    for (const r of rows.filter((x) => x.field && !baseline.includes(x.field))) {
      expect(r.checked, `字段「${r.field}」不在行键里却显示已勾选`).toBeFalsy();
    }

    // ── S3 勾选写字段名 + 取消生效 ──
    const target = rows.find((r) => r.field && !r.checked && !r.disabled);
    expect(target, '没有可勾选的候选字段').toBeTruthy();
    const field = target!.field;
    await toggleRowKey(page, field);
    await save(page);
    let data = await getComp();
    const names: string[] = (data.fields || []).map((f: any) => f.name);
    console.log(`[RK][S3] 勾选「${field}」后 = ${JSON.stringify(data.rowKeyFields)}`);
    expect(data.rowKeyFields, 'S3 应写入字段名本身').toContain(field);
    expect(data.rowKeyFields.filter((k: string) => !names.includes(k)),
      'S3 不得混入任何非字段名项（如 driver 列名）').toEqual([]);

    await openComponent(page);
    await toggleRowKey(page, field);
    await save(page);
    data = await getComp();
    console.log(`[RK][S3] 取消「${field}」后 = ${JSON.stringify(data.rowKeyFields)}`);
    expect(data.rowKeyFields, 'S3 取消勾选应真正生效').not.toContain(field);
    expect(data.rowKeyFields.slice().sort(), 'S3 取消后应回到基线').toEqual(baseline.slice().sort());

    // ── S4 存量 driver 列名残留 → 打开保存后自然清除 ──
    const polluted = [...baseline, 'parent_no', 'material_no'];
    const put = await api(`/api/cpq/components/${comp.id}`, {
      method: 'PUT',
      body: JSON.stringify({
        name: data.name, fields: data.fields, formulas: data.formulas,
        rowKeyFields: polluted, tabType: data.tabType,
        partNoField: data.partNoField, partNameField: data.partNameField,
      }),
    });
    expect(put.ok, 'S4 写入混合值应被后端放行（软告警不阻断）').toBeTruthy();
    expect((await getComp()).rowKeyFields).toEqual(polluted);

    await openComponent(page);
    await save(page);
    const after = (await getComp()).rowKeyFields as string[];
    console.log(`[RK][S4] 污染 ${JSON.stringify(polluted)} → 保存后 ${JSON.stringify(after)}`);
    expect(after, 'S4 应清除 parent_no').not.toContain('parent_no');
    expect(after, 'S4 应清除 material_no').not.toContain('material_no');
    expect(after.slice().sort(), 'S4 字段名项应一个不少').toEqual(baseline.slice().sort());
  });

  test.afterAll(async () => {
    if (!cookieHeader || !dirId) return;
    const listed = (await (await api(`/api/cpq/components?directoryId=${dirId}`)).json()) as any;
    for (const c of (listed.data || []).filter((x: any) => x.directoryId === dirId)) {
      await api(`/api/cpq/components/${c.id}`, { method: 'DELETE' });
    }
    await api(`/api/cpq/component-directories/${dirId}`, { method: 'DELETE' });
    console.log('[RK] 已清理临时目录与组件');
  });
});
