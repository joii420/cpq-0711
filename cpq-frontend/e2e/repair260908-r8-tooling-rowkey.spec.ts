/**
 * E2E · repair-260908「材料名位置 + 核价侧行键」· 认领 **AC-R8**（序列）
 *
 * AC-R8 原文（`问题说明.md §⑥`）：
 *   迁移后 UI 里**新建**组件 → 数据集切「明细核价」→ 数据源选「模具工装成本」→
 *   **自动带出的行键列恰好是 `生产料号` 与 `工序编号` 两列**，不含「模具台账/工装编号」。
 *
 * 🚫 本文件不读实现源码（`cpq-backend/src/main/java/**`、`cpq-frontend/src/**`）。
 *    断言派生自 AC 原文；选择器与页面操作复用既有测试代码 `task260908-s2.helpers.ts`。
 *
 * 分片隔离（主线分配）
 *    · 造数前缀 `T260908R-`（组件 + 专属目录），afterAll 按 id 精确回收
 *    · 零全局计数断言 · 不改任何全局状态
 *
 * `test.md §3.3`：必须验「**恰好** 2 列」而不是「包含这 2 列」——
 *    「包含生产料号和工序编号」在改动前（3 列）也成立，那种写法**恒绿**。
 *    ⇒ 用**集合相等** + 显式断言不含「模具台账/工装编号」。
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
import {
  BACKEND_URL, DB_DESC, assertIsolatedEnv, psql, setCookieHeader, api,
  openComponentByCode, switchTab, selectDataset,
  readSelectedColumns, serverRowKeys, fieldTree,
} from './task260908-s2.helpers';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
/** 证据必须落任务目录 —— `test-results/` 每轮开跑前会被清空，留在那里的截图不算证据。 */
const EVIDENCE_DIR = path.resolve(
  HERE, '..', '..', 'dev-docs', 'task-260908-取数配置器优化',
  'repair-260908-核价侧行键与材料名位置', '证据',
);

/** 本片造数前缀 —— 主线分配，不许改成「更自然」的命名。 */
const PFX = 'T260908R-';

/** AC-R8 的目标集合（用户原话 §①-2.6 / §③ E-6）。 */
const WANT = ['生产料号', '工序编号'];
/** 改动前多出来的那一列 —— 必须**不在**里面（判据落在会变的维度上）。 */
const MUST_NOT = '模具台账/工装编号';

const SOURCE_SEL = '[data-role="builder-source"]';

const created: { id: string; code: string; name: string }[] = [];
let dirId = '';

test.beforeAll(() => {
  assertIsolatedEnv();
  console.log(`[R8] 造数前缀=${PFX} 后端=${BACKEND_URL} 库=${DB_DESC}`);
});

test.beforeEach(async ({ page, context }) => {
  await loginAsAdmin(page);
  setCookieHeader((await context.cookies()).map((c) => `${c.name}=${c.value}`).join('; '));
});

test.afterAll(async () => {
  // 命中面被自己的 id 限死；再按前缀兜一次底（防中途崩溃漏登记）。
  for (const c of created) {
    try {
      const n = psql(`SELECT count(*) FROM component_sql_view WHERE component_id='${c.id}'`);
      psql(`DELETE FROM component_sql_view WHERE component_id='${c.id}'`);
      psql(`DELETE FROM component WHERE id='${c.id}'`);
      console.log(`[R8][cleanup] 删组件 ${c.code}（component_sql_view ${n} 行）`);
    } catch (e) { console.warn(`[R8][cleanup] 删 ${c.id} 失败：`, e); }
  }
  try {
    const left = psql(`SELECT count(*) FROM component WHERE name LIKE '${PFX}%'`);
    if (left !== '0') {
      psql(`DELETE FROM component_sql_view WHERE component_id IN (SELECT id FROM component WHERE name LIKE '${PFX}%')`);
      psql(`DELETE FROM component WHERE name LIKE '${PFX}%'`);
      console.log(`[R8][cleanup] 前缀兜底清掉 ${left} 个 ${PFX}* 组件`);
    }
  } catch (e) { console.warn('[R8][cleanup] 前缀兜底失败：', e); }
  if (dirId) {
    await api(`/api/cpq/component-directories/${dirId}`, { method: 'DELETE' })
      .catch((e) => console.warn('[R8][cleanup] 删目录失败：', e));
  }
});

async function newComponent(suffix: string) {
  if (!dirId) {
    const r = await api('/api/cpq/component-directories', {
      method: 'POST', body: JSON.stringify({ name: `${PFX}DIR`, parentId: null }),
    });
    const b = await r.text();
    expect(r.ok, `建本片专属目录失败：${r.status} ${b}`).toBeTruthy();
    dirId = (JSON.parse(b) as any).data.id;
  }
  const name = `${PFX}${suffix}-${Date.now()}`;
  const r = await api('/api/cpq/components', {
    method: 'POST',
    body: JSON.stringify({ name, directoryId: dirId, componentType: 'NORMAL' }),
  });
  const b = await r.text();
  expect(r.ok, `建组件 ${name} 失败：${r.status} ${b}`).toBeTruthy();
  const d = (JSON.parse(b) as any).data;
  expect(d.componentType,
    `夹具 componentType=${d.componentType} 不是 NORMAL ⇒ 它没有「取数配置」Tab，`
    + '后面会以 timeout 收场且长得像产品缺陷。判【未验证】。').toBe('NORMAL');
  created.push({ id: d.id, code: d.code, name: d.name });
  console.log(`[R8] 造出组件 ${d.code} ${d.name} (${d.id})`);
  return d as { id: string; code: string; name: string };
}

/**
 * 关掉「切换数据集会清空已选输出列」确认框。
 *
 * 实测（2026-09-08）：**两个弹层的主按钮文案不一样** —— 切数据集是「**确认切换**」，
 * 切数据源是「**继续切换**」。共享 helper 的
 * `confirmIfAsked` 只匹配 `/^确\s*定$|^确\s*认$/` ⇒ 匹配不上、弹层留在页面上，
 * 之后 `.ant-modal-wrap` 拦掉所有 pointer 事件，失败点落在**下一步**的
 * `selectSource` 上，长得像「数据源下拉坏了」的产品缺陷。
 * 这是 harness 缺口，不是产品缺陷 —— 本 spec 自己兜住，不去改共享 helper。
 */
async function confirmDatasetSwitch(page: any) {
  const scope = page.locator('.ant-modal-wrap, .ant-modal-confirm').filter({ visible: true });
  for (let i = 0; i < 12; i++) {
    if (await scope.count() === 0) return;
    const btn = scope.locator('button')
      .filter({ hasText: /继\s*续\s*切\s*换|确\s*认\s*切\s*换|^确\s*定$|^确\s*认$/ }).first();
    if (await btn.isVisible().catch(() => false)) {
      console.log(`[R8] 确认弹层：点「${(await btn.innerText()).replace(/\s+/g, '')}」`);
      await btn.click();
      await page.waitForTimeout(900);
      continue;
    }
    // 🚨 这里**绝不能按 Escape**：Escape 等于点「取消」，切换会被悄悄撤销，
    //    而页面看上去一切正常 —— 上一轮就是这么让「数据源没切过去」伪装成断言失败的。
    await page.waitForTimeout(700);
  }
  expect(await page.locator('.ant-modal-wrap').filter({ visible: true }).count(),
    '确认弹层关不掉 ⇒ 后续点击全被 .ant-modal-wrap 拦住，本条判【未验证】（harness 问题）').toBe(0);
}

/** 读数据源下拉当前显示的文案（前置守卫用）。 */
async function currentSource(page: any): Promise<string> {
  const t = await page.locator(SOURCE_SEL).first().innerText();
  return t.replace(/\s+/g, '');
}

/**
 * 选数据源 —— 自带**虚拟滚动**兜底。
 *
 * 实测（2026-09-08，明细核价）：数据源下拉有 20+ 项，antd 用 rc-virtual-list 只渲染可视区，
 * 「模具工装成本」排在下面、DOM 里根本不存在 ⇒ 共享 helper 的 `selectSource` 报
 * 「数据源下拉里找不到 X」。那句错**长得像「数据源没配上」的产品缺陷**，其实是量具够不着。
 * ⇒ 先试搜索框过滤，再退化为滚动 holder。找不到才算真缺陷。
 */
async function selectSourceScrolling(page: any, label: string) {
  const sel = page.locator('[data-role="builder-source"]').first();
  await expect(sel, '找不到「数据源」下拉 ⇒ 入口问题，本条判【未验证】').toBeVisible({ timeout: 15_000 });
  await sel.click();
  await page.waitForTimeout(500);
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').first();
  const opt = dropdown.locator('.ant-select-item-option').filter({ hasText: new RegExp(`^${label}$`) }).first();

  // ① 搜索框过滤（若该 Select 支持 showSearch）
  const search = sel.locator('input.ant-select-selection-search-input').first();
  if (await search.count()) {
    await search.fill(label).catch(() => {});
    await page.waitForTimeout(700);
  }
  // ② 退化：滚动虚拟列表
  if (!(await opt.isVisible().catch(() => false))) {
    const holder = dropdown.locator('.rc-virtual-list-holder').first();
    for (let i = 0; i < 25; i++) {
      if (await opt.isVisible().catch(() => false)) break;
      if (await holder.count()) {
        await holder.evaluate((el: any) => { el.scrollTop += 200; }).catch(() => {});
      } else {
        await dropdown.hover().catch(() => {});
        await page.mouse.wheel(0, 200);
      }
      await page.waitForTimeout(200);
    }
  }
  const visibleOpts = await dropdown.locator('.ant-select-item-option').allInnerTexts().catch(() => []);
  await expect(opt,
    `数据源下拉里找不到「${label}」（已试搜索 + 滚动 25 次）。`
    + `当前可见选项=${JSON.stringify(visibleOpts)}`).toBeVisible({ timeout: 10_000 });
  await opt.click();
  await page.waitForTimeout(2500);
}

async function shot(page: any, name: string) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const f = path.join(EVIDENCE_DIR, `${name}.png`);
  await page.screenshot({ path: f, fullPage: true }).catch(() => {});
  console.log(`[shot] ${name} -> ${f}`);
}

// T0 · 量具自检 —— 先证明「恰好 2 列」这个判据会动
test('T0 量具自检: 集合相等判据对「多一列」必须硬失败（改动前那一态）', async () => {
  const setEq = (a: string[], b: string[]) =>
    a.length === b.length && [...a].sort().join('') === [...b].sort().join('');
  // 改动前实测的三列（问题说明 §③「当前（实测）」）
  const BEFORE = ['生产料号', '工序编号', MUST_NOT];
  expect(setEq(BEFORE, WANT),
    '量具坏了：三列（含模具台账/工装编号）竟被判为等于目标两列 ⇒ 后面的绿全是假绿').toBe(false);
  expect(setEq(['生产料号', '工序编号'], WANT), '量具坏了：目标两列自身应判相等').toBe(true);
  expect(setEq(['生产料号'], WANT), '量具坏了：少一列也必须判不等').toBe(false);
  // 顺序无关（UI 顺序不是 AC 的断言点）
  expect(setEq(['工序编号', '生产料号'], WANT), '量具：集合相等应与顺序无关').toBe(true);
});

// AC-R8
test('AC-R8: 新建组件 -> 明细核价 -> 模具工装成本 -> 自动带出的行键列恰好 [生产料号, 工序编号]',
  async ({ page }) => {
    test.setTimeout(220_000);

    // 服务端事实（现算，不写死）：该源的 ROW_KEY 列集合
    const srv = await serverRowKeys(page, '费用类', 'TOOLING', 'COST_DETAIL');
    const srvNames = srv.map((f) => f.displayName);
    console.log(`[AC-R8] 服务端 ROW_KEY 列 = ${JSON.stringify(srvNames)}`);
    expect(new Set(srvNames), `服务端侧：模具工装成本的 ROW_KEY 列应恰好为 ${JSON.stringify(WANT)}，`
      + `实际=${JSON.stringify(srvNames)}`).toEqual(new Set(WANT));
    expect(srvNames, `服务端侧：ROW_KEY 里仍残留「${MUST_NOT}」`).not.toContain(MUST_NOT);

    // UI 序列：新建 -> 取数配置 -> 明细核价 -> 模具工装成本
    const fx = await newComponent('R8');
    await openComponentByCode(page, fx.code);
    await switchTab(page, '取数配置');
    await expect(page.locator('.svb-recipe-bar').first(),
      '取数配置面板不可见 ⇒ 后面的断言全是空跑，本条判【未验证】').toBeVisible({ timeout: 20_000 });
    await selectDataset(page, '明细核价');
    await confirmDatasetSwitch(page);
    await selectSourceScrolling(page, '模具工装成本');
    await confirmDatasetSwitch(page);
    await shot(page, 'AC-R8-tooling-rowkey');

    // 🚨 前置守卫：数据源**真的**切过去了才有资格读结论。
    //    上一轮就栽在这：源没切成功（仍是「物料」），断言照样跑，
    //    失败信息长得像「工序编号没带出来」的产品缺陷 —— 其实一个字都没测到模具工装成本。
    const cur = await currentSource(page);
    expect(cur,
      `数据源仍停在「${cur}」，没切到「模具工装成本」⇒ 后面读到的已选列是**另一个源**的，`
      + '结论无效。本条判【未验证】（harness 问题），🚫 不得记成产品缺陷。').toContain('模具工装成本');

    // 量具口径：已选列行上显示的是**视图列名**（如 production_no），不是显示名。
    // ⇒ 期望集合用服务端 field-tree 现算的 viewColumn，🚫 不写死。
    const wantCols = srv.map((f) => f.viewColumn);
    const all = await fieldTree(page, '费用类', 'TOOLING', 'COST_DETAIL');
    const forbidden = all.find((f) => f.displayName === MUST_NOT);
    expect(forbidden,
      `field-tree 里找不到「${MUST_NOT}」这一列 ⇒ 反向断言会恒真通过，本条判【未验证】`).toBeTruthy();
    console.log(`[AC-R8] 期望行键视图列=${JSON.stringify(wantCols)} 禁止列=${forbidden!.viewColumn}`);

    // 量具：已选输出列（取不到会硬失败，不静默返回空数组）
    const rows = await readSelectedColumns(page, 'AC-R8 选完数据源');
    const keyRows = rows.filter((r) => r.hasRowKeyBadge);
    expect(keyRows.length,
      '带「行键」徽标的行为 0 ⇒ 要么行键没自动带出（缺陷），要么徽标量具坏了。'
      + `已选列原文=${JSON.stringify(rows.map((r) => r.text))}`).toBeGreaterThan(0);

    const got = keyRows.map((r) => wantCols.concat(forbidden!.viewColumn)
      .find((c) => r.leaves.includes(c)) ?? `<未识别:${r.text}>`);
    console.log(`[AC-R8] UI 自动带出的行键列 = ${JSON.stringify(got)}`);

    // 集合相等，不是「包含」
    expect(new Set(got),
      `AC-R8：自动带出的行键列应**恰好**是 ${JSON.stringify(WANT)}（视图列 ${JSON.stringify(wantCols)}），`
      + `实际=${JSON.stringify(got)}。\n已选列原文=${JSON.stringify(rows.map((r) => r.text))}`)
      .toEqual(new Set(wantCols));

    // 显式反向：整份已选列里都不该出现「模具台账/工装编号」
    const anyForbidden = rows.filter((r) => r.leaves.includes(forbidden!.viewColumn));
    expect(anyForbidden.map((r) => r.text),
      `AC-R8 反向：已选输出列里出现了「${MUST_NOT}」(${forbidden!.viewColumn})`).toEqual([]);
  });
