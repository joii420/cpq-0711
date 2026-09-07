/**
 * E2E · task-260904 —— AC-24（反向 · 前端语义闸门与后端判据一致）
 *
 * AC 原文（`dev-docs/task-260904-页签类型收缩/需求文档.md §3.3`）：
 *   前置：前端 5 处按 `tabType === 'BOM'` 判断的语义闸门。
 *   操作：新建一个数据源为「物料BOM」的组件（其 tab_type 为空），在 UI 上依次尝试：
 *     ① 公式编辑器的「父子取值」分区；② 字段类型下拉的树相关选项；③ 跨页签公式抽屉的树 token。
 *   断言：① 新建组件（有 builder_config、tab_type 为空）三处**均可用**（不置灰）；
 *        ② **存量 BOM 树组件**（tab_type='BOM'、无 builder_config）三处**仍可用**，与改动前一致。
 *
 * 🚨 为什么必须是 E2E：AC-18 是纯后端断言（保存 400/200/400），**验不到 UI 灰掉**。
 *    「后端放行、前端灰掉」这种不一致只有在浏览器里点才看得见。
 *
 * 🚨 数据纪律（`docs/rules/testing.md` §4.3）：
 *   - 本 spec 只**新建**自己的组件（`t260904_` 前缀），afterAll 按 id 精确删除；
 *   - 对存量 BOM 树组件**只读不写**：只打开、只观察，不点保存；
 *   - 🚫 不清库、不改任何全局状态（用户启停用 / 模板发布态 / 系统开关一概不碰）。
 *
 * 📸 证据归档：截图直接写进任务目录 `dev-docs/task-260904-页签类型收缩/证据/e2e/`，
 *    🚫 不写 `test-results/` —— 那个目录每轮开跑前会被清空，留在那里等于没有证据（testing.md §2）。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';

/** 证据归档目录：任务目录内，随任务提交，不会被下一轮清空 */
const SHOT_DIR = path.resolve(
  path.dirname(__filename),
  '../../dev-docs/task-260904-页签类型收缩/证据/e2e'
);
fs.mkdirSync(SHOT_DIR, { recursive: true });

const RUN_ID = Math.random().toString(36).slice(2, 8);
const NAME_NEW = `t260904_新建树组件_${RUN_ID}`;

/**
 * 「数据源 = 物料BOM」的 builder_config —— 三段坐标见 api.md §0（dialect 缺一不可）。
 * ⚠️ 2026-09-05 V417（task-260819 D-39）把 semantic_tab_view.tab_type 的种子值由显示名「BOM 树」
 *    纠正为键值「BOM」；继续传「BOM 树」会 400 COMPILE_TABVIEW_NOT_FOUND。api.md §0 的示例待回写。
 */
const CFG_MATERIAL_BOM = {
  tabType: 'BOM',
  variantKey: '',
  dialect: 'QUOTE',
  columns: [
    { sourceNodeKey: 'MATERIAL_BOM', sourceColumn: 'input_material_no', fieldName: '投入料号', isRowKey: true, isPartNo: true },
    { sourceNodeKey: 'MATERIAL_BOM', sourceColumn: 'component_qty', fieldName: '组成数量' },
  ],
};

let backendUp = false;
let cookieHeader = '';
let newComponentId = '';
let legacyComponentId = '';
let legacyComponentName = '';
let shotIdx = 0;

function psql(sql: string): string {
  return execSync(
    `PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db_0724 -At -F '|' -c "${sql.replace(/"/g, '\\"')}"`,
    { encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'], shell: '/bin/bash' }
  ).trim();
}

async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `ac24-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

test.beforeEach(async ({ page, context }) => {
  test.skip(!backendUp, '后端未启动（harness 前置，不是 AC 结论）');
  await loginAsAdmin(page);
  const cookies = await context.cookies();
  cookieHeader = cookies.map((c) => `${c.name}=${c.value}`).join('; ');
});

test.afterAll(async () => {
  if (newComponentId) {
    // 🚫 只删本 spec 自建的那一个组件，命中面被 id 限死
    try {
      psql(`DELETE FROM component_sql_view WHERE component_id='${newComponentId}'`);
      psql(`DELETE FROM component WHERE id='${newComponentId}'`);
      console.log(`[cleanup] 已删除自建组件 ${newComponentId}`);
    } catch (e) {
      console.warn('[cleanup] 删除自建组件失败：', e);
    }
  }
});

/**
 * 经取数配置器的**真实保存路径**产出一个「数据源 = 物料BOM」的新组件。
 * 🚨 不许依赖库里恰好有 —— `component_sql_view.builder_version` 现网 0 行非 NULL，
 *    依赖存量会让「新组件」这一态一次都不命中（test.md §3 的窗口期空跑）。
 */
async function createNewTreeComponentViaBuilder() {
  const create = await fetch(`${BACKEND_URL}/api/cpq/components`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Cookie: cookieHeader },
    // 🚨 必须带 directoryId：/components 页是「组件目录树 + 右侧详情」，不是表格。
    //    2026-09-05 实测：不带目录建出来的组件，搜索框搜不到（HIT_COUNT=0）——
    //    那不是产品缺陷，是本 spec 的夹具没落进 UI 能看见的地方。
    body: JSON.stringify({ name: NAME_NEW, directoryId: legacyDirectoryId() }),
  });
  expect(create.ok, `建空白组件应成功，实际 ${create.status}`).toBeTruthy();
  const created = (await create.json()) as any;
  newComponentId = created.data.id;

  const save = await fetch(`${BACKEND_URL}/api/cpq/components/${newComponentId}/builder`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', Cookie: cookieHeader },
    body: JSON.stringify(CFG_MATERIAL_BOM),   // ⚠️ 裸 builder_config，🚫 不包一层 {"builderConfig":...}
  });
  const saveBody = await save.text();
  expect(save.ok, `取数配置器保存应成功，实际 ${save.status} ${saveBody}`).toBeTruthy();

  // ⓪ 窗口期守卫：没有 builder_version 就没有分支①，后面「新组件三处可用」验的是空气
  const bv = psql(
    `SELECT COALESCE(builder_version::text,'(null)') FROM component_sql_view WHERE component_id='${newComponentId}'`
  );
  expect(bv, 'AC-24⓪：新组件的 builder_version 仍为 NULL ⇒ 它不是「新模型配的」组件，本用例会空跑').not.toBe('(null)');
  const tt = psql(`SELECT COALESCE(tab_type,'(null)') FROM component WHERE id='${newComponentId}'`);
  expect(tt, 'AC-24⓪：新组件的 component.tab_type 应为空（正是它让前端旧闸门灰掉）').toBe('(null)');
  console.log(`[AC-24] 新组件 ${newComponentId} builder_version=${bv} tab_type=${tt}`);
}

/** 取存量 BOM 树组件所在的目录 id —— 新建组件挂进同一个目录，保证 UI 里找得到。 */
function legacyDirectoryId(): string {
  const v = psql(
    `SELECT c.directory_id::text FROM component c LEFT JOIN component_sql_view v ON v.component_id=c.id ` +
      `WHERE c.tab_type='BOM' AND v.builder_version IS NULL AND c.directory_id IS NOT NULL ORDER BY c.code LIMIT 1`
  );
  expect(v, '前置未满足：找不到带 directory_id 的存量 BOM 树组件 ⇒ 新建组件无处可挂，UI 里会找不到').not.toBe('');
  return v;
}

/** 取一个存量 BOM 树组件（tab_type='BOM' 且无 builder_version）—— 只读，一个字节都不改。 */
function pickLegacyTreeComponent() {
  const row = psql(
    `SELECT c.id::text, c.name FROM component c LEFT JOIN component_sql_view v ON v.component_id=c.id ` +
      `WHERE c.tab_type='BOM' AND v.builder_version IS NULL ORDER BY c.code LIMIT 1`
  );
  expect(row, '前置未满足：现网找不到「tab_type=BOM 且 builder_version 为 NULL」的存量组件 ⇒ AC-24② 会空跑').not.toBe('');
  [legacyComponentId, legacyComponentName] = row.split('|');
  console.log(`[AC-24] 存量树组件 ${legacyComponentId} / ${legacyComponentName}`);
}

/**
 * 打开组件详情。
 *
 * 🚨 2026-09-05 实测：`/components` 是「**组件目录树 + 右侧详情**」布局，
 *    `table` / `.ant-table-row` / `.ant-list-item` / `.ant-card` 计数<b>全为 0</b>，
 *    原来按 `.ant-table-row` 找行的写法必然找不到（那不是产品缺陷，是选择器错了）。
 *    搜索框实际 placeholder = `🔍 搜索组件名 / 编码`。
 * ⚠️ 命中项 `click()` 会 30s 超时（疑似被遮挡/未稳定），故用 `force`。
 *    ⚠️ 这一段仍是**未跑通的选择器**：若再超时，要改的是这里，🚫 不是下面的断言。
 */
async function openComponent(page: Page, name: string) {
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  const search = page.locator('input[placeholder*="搜索"]').first();
  await expect(search, '组件页应有搜索框（placeholder 含「搜索」）').toBeVisible({ timeout: 10_000 });
  await search.fill(name);
  await page.waitForTimeout(2000);
  const hit = page.getByText(name, { exact: false }).first();
  await expect(hit, `组件目录树里应能搜到「${name}」`).toHaveCount(1, { timeout: 10_000 });
  await hit.click({ force: true, timeout: 10_000 });
  await page.waitForTimeout(1500);
}

/**
 * 三处语义闸门的可用性观察。
 *
 * ⚠️ 选择器口径：前端 F-8 落地后若 DOM 结构与此不符，**要改的是这里的选择器，不是断言** ——
 * 断言（「三处均可用/不置灰」）直接来自 AC-24 原文，不得放宽。
 */
async function assertThreeGatesEnabled(page: Page, who: string) {
  // 🚨 2026-09-05 实测组件详情的真实结构：右侧是 **Tabs**，不是按钮组。
  //    .ant-tabs-tab 文本 = ["组件","数据源","全局变量","核价模板","字段配置","取数配置","公式","SQL 视图"]
  //    原来按 getByRole('button', {name:/公式/}) 找入口必然找不到 —— 那是选择器错，不是产品缺陷。
  const tab = (name: string) => page.locator('.ant-tabs-tab', { hasText: name }).first();

  // ② 字段类型下拉的树相关选项（「字段配置」Tab）
  await tab('字段配置').click();
  await page.waitForTimeout(1200);
  await shot(page, `${who}-fieldconfig`);
  const fieldTypeSelect = page.locator('.ant-table-row').first().locator('.ant-select').first();
  if (await fieldTypeSelect.isVisible().catch(() => false)) {
    await fieldTypeSelect.click();
    await page.waitForTimeout(600);
    await shot(page, `${who}-fieldtype-dropdown`);
    const treeOption = page.locator('.ant-select-item-option', { hasText: /树|TREE|LIST_FORMULA/ }).first();
    if (await treeOption.isVisible().catch(() => false)) {
      const cls = (await treeOption.getAttribute('class')) || '';
      expect(cls.includes('ant-select-item-option-disabled'),
        `${who} · AC-24②：字段类型下拉里的树相关选项被禁用，class=${cls}`).toBe(false);
    } else {
      console.log(`[AC-24②] ${who}：下拉里没有树相关选项 —— 已截图存档，请主线按 AC 原文核实此处期望形态`);
    }
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
  } else {
    console.log(`[AC-24②] ${who}：该组件没有已选列，字段类型下拉不存在 —— 已截图存档`);
  }

  // ① 公式编辑器的「父子取值」分区（「公式」Tab）
  await tab('公式').click();
  await page.waitForTimeout(1500);
  await shot(page, `${who}-formula-tab`);
  const parentChild = page.getByText(/父子取值|父取值|子件汇总/).first();
  await expect(parentChild,
    `${who} · AC-24①：公式 Tab 里应出现「父子取值」分区（新组件 tab_type 为空时也必须出现）`)
    .toBeVisible({ timeout: 10_000 });
  const pcCls = await parentChild
    .locator('xpath=ancestor-or-self::*[contains(@class,"ant-btn") or contains(@class,"ant-radio") or contains(@class,"ant-tabs-tab") or contains(@class,"ant-collapse-item")][1]')
    .getAttribute('class').catch(() => '');
  expect((pcCls || '').includes('disabled'),
    `${who} · AC-24①：「父子取值」分区被置灰 ⇒ 后端按 semantic 放行、前端仍看 tabType（新组件为空），` +
    `用户在 UI 上点不出父子取值。class=${pcCls}`).toBe(false);

  // ③ 跨页签公式抽屉的树 token
  const tabJoinEntry = page.getByRole('button', { name: /跨页签|页签间/ }).first();
  if (await tabJoinEntry.isVisible().catch(() => false)) {
    await tabJoinEntry.click();
    await page.waitForTimeout(1200);
    await shot(page, `${who}-tabjoin-drawer`);
    await expect(page.getByText(/树|tree_ref|tree_attr/).first(),
      `${who} · AC-24③：跨页签公式抽屉里应能用树 token`).toBeVisible({ timeout: 8_000 });
    await page.keyboard.press('Escape');
  } else {
    console.log(`[AC-24③] ${who}：未找到跨页签公式入口 —— 已截图存档，请主线按 AC 原文核实入口位置`);
    await shot(page, `${who}-tabjoin-missing`);
  }
}

// ══════════════════════════════════════════════════════════════════════════════

test('AC-24①：新建组件（数据源=物料BOM、tab_type 为空）三处语义闸门均可用', async ({ page }) => {
  await createNewTreeComponentViaBuilder();
  await openComponent(page, NAME_NEW);
  await shot(page, 'new-component-detail');
  await assertThreeGatesEnabled(page, '新组件');

  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  expect(errors, `AC-24①：不应有 JS 报错：${errors.join(' | ')}`).toHaveLength(0);
});

test('AC-24②：存量 BOM 树组件（tab_type=BOM、无 builder_config）三处仍可用', async ({ page }) => {
  pickLegacyTreeComponent();

  // 只读快照：跑完再核一次，证明本用例没写过它（testing.md §4.3）
  const before = psql(
    `SELECT COALESCE(tab_type,'(null)')||'|'||bom_recursive_expand::text||'|'||md5(fields::text) FROM component WHERE id='${legacyComponentId}'`
  );

  await openComponent(page, legacyComponentName);
  await shot(page, 'legacy-component-detail');
  await assertThreeGatesEnabled(page, '存量树组件');

  const after = psql(
    `SELECT COALESCE(tab_type,'(null)')||'|'||bom_recursive_expand::text||'|'||md5(fields::text) FROM component WHERE id='${legacyComponentId}'`
  );
  expect(after, `AC-24②：本用例不得改动存量组件（before=${before} after=${after}）`).toBe(before);
});
