/**
 * task-260908「取数配置器优化」· 分片 S1（语义图与编译）共享 helpers
 *
 * 🚦 本片的写入面 = **只读**：打开取数配置 Tab → 切数据集/数据源 → 双击加列 → 读「生成的 SQL」/ 点预览。
 *    🚫 全程不点「保存」，不写 component_sql_view、不写 component。
 *    ⇒ 每条用例都用 {@link guardReadOnly} 在 finally 里断言被触碰组件的
 *      builder_config / sql_template **逐字节未变**。变了就硬失败并要求停下来报主线
 *      —— 因为那会连带把 S-全局 的 AC-9（存量 sql_template 零变化）打红，
 *      而那种红**长得像「迁移改了存量视图」这种严重回归**。
 *
 * 🚫 本文件不读实现代码（`cpq-backend/src/main/java/**`、`cpq-frontend/src/**`）。
 *    断言一律按 `需求文档.md §③` 的 AC 原文 + `api.md` 的契约写。
 *    选择器来自既有 e2e（`task260907-builder-gaps.spec.ts` / `task260819v9-dataset-selector.spec.ts`），
 *    那是允许参考的既有测试代码。
 */
import { expect, Page } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

// 🚩 Vite ESM 项目没有 __dirname（AP-43 同族）
const HERE = path.dirname(fileURLToPath(import.meta.url));

/** 证据归档目录。🚨 `test-results/` 每轮开跑前会被清空 ⇒ 当证据的产物必须落到任务目录里（testing.md §2）。 */
export const EVIDENCE_DIR = path.join(
  HERE, '..', '..', 'dev-docs', 'task-260908-取数配置器优化', '证据', 'e2e'
);

/** AC 原文里点名的组件锚点。坐标（tabType/variantKey/dialect）才是判据，id 只是快捷方式（需求文档 §③ 注）。 */
export const ANCHORS = {
  /** AC-1 / AC-7 —— 「T260907-来料其他费用」费用类 / INCOMING_OTHER_FEE / QUOTE */
  incomingOtherFee: {
    id: '04375490-927f-4802-bcc7-4156df4fc27d',
    tabType: '费用类', variantKey: 'INCOMING_OTHER_FEE', dialect: 'QUOTE',
    sourceLabel: '来料其他费用',
  },
  /** AC-2 / AC-3 / AC-23 —— 「T260907-物料BOM」 BOM / QUOTE */
  materialBom: {
    id: 'f6d51727-bd1e-4cc3-a839-94208cc12f41',
    tabType: 'BOM', variantKey: '', dialect: 'QUOTE',
    sourceLabel: '物料BOM',
  },
  /** AC-5 —— 「T260907-物料」 主件 / QUOTE */
  quoteMaterial: {
    id: 'c6a71e5e-217a-49ab-8cce-a9cee88469a4',
    tabType: '主件', variantKey: '', dialect: 'QUOTE',
    sourceLabel: '物料',
  },
  /** AC-4 —— 「材质元素」 材质元素 / QUOTE（物料与元素BOM） */
  elementBom: {
    id: 'dc3297cc-e9a3-4184-8bc8-bb61db2a07d2',
    tabType: '材质元素', variantKey: '', dialect: 'QUOTE',
    sourceLabel: '物料与元素BOM',
  },
  /** AC-5b —— 「产品」 主件 / COST_BASIC */
  costBasicMaterial: {
    id: '786b487c-fe71-473e-9b84-f13e4a4c5b48',
    tabType: '主件', variantKey: '', dialect: 'COST_BASIC',
    sourceLabel: '物料',
  },
} as const;

/** AC 原文里的字段显示名（用户原话直接给定，见 §⑤，故可写死）。 */
export const F_MATERIAL_NAME = '材料名';
export const F_PRODUCTION_NO = '生产料号';

// ═══════════════════════════════════════════════════════════════════
// DB（只读）
// ═══════════════════════════════════════════════════════════════════

const PG_HOST = process.env.PW_DB_HOST || '10.177.152.12';
const PG_DB = process.env.PW_DB_NAME || 'cpq_db_0724';

/**
 * 只读 psql。
 * 🚨 本片**只允许 SELECT**。出现 INSERT/UPDATE/DELETE/DROP/TRUNCATE 一律在这里挡掉 ——
 *    CLAUDE.md §3.2 红线，子代理没有批准权（testing.md §4.5）。
 */
export function psqlRO(sql: string): string {
  if (!/^\s*(select|with)\b/i.test(sql)) {
    throw new Error(`[S1 只读片] 拒绝执行非 SELECT 语句：${sql.slice(0, 120)}`);
  }
  return execSync(
    `PGPASSWORD=joii5231 psql -h ${PG_HOST} -p 5432 -U postgres -d ${PG_DB} -At -F '|' -c "${sql.replace(/"/g, '\\"')}"`,
    { encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'], shell: '/bin/bash' }
  ).trim();
}

export function scalarInt(sql: string, why: string): number {
  const raw = psqlRO(sql);
  const n = Number(raw);
  expect(Number.isFinite(n), `${why}：基准查询没返回数字（拿到 ${JSON.stringify(raw)}）⇒ 量具坏了，本条判【未验证】`)
    .toBe(true);
  return n;
}

export type Anchor = { id: string; tabType: string; variantKey: string; dialect: string; sourceLabel: string };

/**
 * 解析锚点组件 → `{id, name}`。
 * 先按 id 找；找不到就**按坐标 (tabType, variantKey, dialect) 兜底**（需求文档 §③ 明确「坐标才是判据」）。
 * 两条都空 ⇒ 硬失败并说清这是**夹具缺失**不是产品缺陷。
 */
export function resolveAnchor(a: Anchor): { id: string; name: string; code: string; byId: boolean } {
  // 🔧 2026-09-08 执行期 harness 修复（夹具可达性）：组件管理页是「目录树 + 右侧详情」，
  //    `directory_id IS NULL` 的组件**不出现在树里** ⇒ UI 点不开。实测 AC-5 点名的
  //    c6a71e5e「T260907-物料」(COMP-2179) 正是 directory_id IS NULL ⇒ 必然搜不到、报成「入口问题」。
  //    ⇒ 按 id 命中时追加可达性条件；不可达则退到坐标兜底（AC 原文：「坐标才是判据」）。
  const byId = psqlRO(
    `select c.id::text, c.name, c.code from component c join component_sql_view v on v.component_id=c.id ` +
    `where c.id='${a.id}' and v.builder_config is not null and c.directory_id is not null`
  );
  if (byId) {
    const [id, name, code] = byId.split('|');
    return { id, name, code, byId: true };
  }
  const byCoord = psqlRO(
    `select c.id::text, c.name, c.code from component c join component_sql_view v on v.component_id=c.id ` +
    `where v.builder_config->>'dialect'='${a.dialect}' and v.builder_config->>'tabType'='${a.tabType}' ` +
    `and coalesce(v.builder_config->>'variantKey','')='${a.variantKey}' and c.directory_id is not null ` +
    `order by c.name limit 1`
  );
  expect(byCoord,
    `夹具缺失：既没有组件 ${a.id}，坐标 (${a.tabType}/${a.variantKey}/${a.dialect}) 下也一个组件都没有。\n` +
    `  ⇒ 本条判【未验证】，不是产品缺陷。请主线补一个该坐标的组件。`
  ).not.toBe('');
  const [id, name, code] = byCoord.split('|');
  console.log(`[anchor] id ${a.id} 在库里不可达（不存在或未挂目录），按坐标兜底到 ${id} / ${name} / ${code}`);
  return { id, name, code, byId: false };
}

/** 组件的 builder 行指纹（builder_config + sql_template）。只读。 */
export function builderFingerprint(componentId: string): string {
  return psqlRO(
    `select md5(coalesce(builder_config::text,'<null>')) || ' / ' || md5(coalesce(sql_template,'<null>')) ` +
    `|| ' / len=' || coalesce(length(sql_template),0)::text ` +
    `from component_sql_view where component_id='${componentId}'`
  );
}

/**
 * S1 只读守卫。用法：
 * ```ts
 * const g = readOnlyGuard(id);
 * try { ...操作... } finally { g.assertUntouched(); }
 * ```
 * 🚨 这不是形式主义：一旦配置器有 autosave，S1 就会**悄悄改掉存量组件的 sql_template**，
 *    然后 S-全局 的 AC-9 会红成「迁移改了存量视图」。守卫的作用是让它**在 S1 就红、并且红在正确的地方**。
 */
export function readOnlyGuard(componentId: string, label: string) {
  const before = builderFingerprint(componentId);
  expect(before, `前置：读不到组件 ${componentId}(${label}) 的 component_sql_view 行 ⇒ 守卫失效，本条判【未验证】`)
    .not.toBe('');
  return {
    before,
    assertUntouched() {
      const after = builderFingerprint(componentId);
      expect(after,
        `🚨 S1 声明为**只读片**，但操作组件 ${componentId}(${label}) 之后它的 builder 行变了：\n` +
        `   before = ${before}\n   after  = ${after}\n` +
        `   ⇒ 说明配置器存在**非用户触发的写入**（autosave / 打开即保存）。\n` +
        `   这会连带把 S-全局 的 AC-9 打红。**停下来报主线**，🚫 不要改成「跑完还原」绕过去。`
      ).toBe(before);
    },
  };
}

// ═══════════════════════════════════════════════════════════════════
// 证据归档
// ═══════════════════════════════════════════════════════════════════

export function archive(fileName: string, content: string) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const p = path.join(EVIDENCE_DIR, fileName);
  fs.writeFileSync(p, content, 'utf-8');
  console.log(`📄 证据归档 → ${p}`);
  return p;
}

export async function shot(page: Page, fileName: string) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const p = path.join(EVIDENCE_DIR, fileName);
  await page.screenshot({ path: p, fullPage: true }).catch(() => {});
  console.log(`📸 证据归档 → ${p}`);
  return p;
}

// ═══════════════════════════════════════════════════════════════════
// UI 动作（选择器全部沿用既有 e2e 的实证结论）
// ═══════════════════════════════════════════════════════════════════

/**
 * 切 Tab。坑（task260907-builder-gaps 实证）：
 *  ① 弹层打开时 `.ant-modal-wrap` 拦所有 pointer 事件 ⇒ 点击 15s 超时，长得像「Tab 不见了」⇒ 先 Escape
 *  ② 用 role=tab 比 getByText 稳（避免命中正文同名文字）
 */
export async function switchTab(page: Page, name: string) {
  for (let i = 0; i < 4; i++) {
    if (await page.locator('.ant-modal-wrap').filter({ visible: true }).count() === 0) break;
    await page.keyboard.press('Escape');
    await page.waitForTimeout(400);
  }
  const tab = page.getByRole('tab', { name, exact: true }).first();
  await expect(tab, `找不到 Tab「${name}」⇒ 入口问题，本条判【未验证】，不是产品缺陷`)
    .toBeVisible({ timeout: 15_000 });
  await tab.click();
  await page.waitForTimeout(2000);
}

/**
 * 按名字打开既有组件（组件管理页是「目录树 + 右侧详情」，🚫 不是表格 —— `.ant-table-row` 恒 0）。
 * 搜索框 placeholder 实测 = `🔍 搜索组件名 / 编码`。
 */
export async function openComponentByName(page: Page, name: string, code?: string) {
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  const search = page.locator('input[placeholder*="搜索"]').first();
  await expect(search, '组件管理页应有搜索框（placeholder 含「搜索」）⇒ 取不到判【未验证】')
    .toBeVisible({ timeout: 15_000 });
  // 🔧 2026-09-08 执行期 harness 修复：按 **code** 搜（唯一），🚫 不按 name ——
  //    「产品」「材质元素」「BOM」这类名字全库重名，按名字搜可能打开**另一个方言的同名组件**，
  //    那种错误不报错、只会让断言在错的 SQL 上跑（S3 用例已实证并采用 code 口径）。
  const key = code || name;
  await search.fill(key);
  await page.waitForTimeout(2500);
  // 🔧 2026-09-08 执行期 harness 修复：搜到的卡片可能落在**折叠的目录**里 ——
  //    实测 AC-5b 的 COMP-2271 在「核价组件」目录下，报错是 `Received: hidden`（元素在、但不可见）。
  //    ⚠️ hidden 与 not found 是两种完全不同的成因，前者是「没展开」后者才是「不存在」。
  for (let i = 0; i < 20; i++) {
    const closed = page.locator('.cmm-dir:not(.open) .cmm-dir-head');
    if (await closed.count() === 0) break;
    await closed.first().click({ timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(250);
  }
  await page.waitForTimeout(600);
  const hit = page.getByText(key, { exact: false }).first();
  await expect(hit, `搜不到组件「${name}」(${key}) ⇒ 入口/夹具问题（未挂目录或已被清理），本条判【未验证】`)
    .toBeVisible({ timeout: 15_000 });
  await hit.click({ force: true, timeout: 15_000 });
  await page.waitForTimeout(2000);
  // 🔧 2026-09-08 执行期 harness 修复：`.cm-center-title` 只存在于 styles.css，
  //    组件详情的标题实测在 `.cmm-detail-head` 里的 `span.cmm-t`（probe 实证）。
  //    ⚠️ 这是**量具修正**，断言语义（打开的确实是这个组件）逐字不变。
  //    同族坏选择器：cross-tab-builder.spec.ts:82 / cross-tab-ref.spec.ts:104（既有失败基线，非本次引入）。
  await expect(page.locator('.cmm-detail-head'), `打开的不是「${name}」`)
    .toContainText(name, { timeout: 10_000 });
}

/** 打开某锚点组件的「取数配置」Tab，并确认取数面板渲染出来了（否则后面全是空跑）。 */
export async function openBuilderOf(page: Page, a: Anchor) {
  const { id, name, code } = resolveAnchor(a);
  await openComponentByName(page, name, code);
  await switchTab(page, '取数配置');
  await page.waitForTimeout(2500);
  const panel = page.locator('.svb-recipe-bar').first();
  await expect(panel, '取数配置面板（.svb-recipe-bar）没渲染出来 ⇒ 后面的断言全是空跑，本条判【未验证】')
    .toBeVisible({ timeout: 15_000 });
  return { id, name };
}

/** 展开左侧全部字段分组。🚨 折叠是 CSS `display:none`，不展开则 `toBeVisible()` 报 hidden，长得像「字段没渲染」。 */
export async function expandAllGroups(page: Page): Promise<number> {
  for (let i = 0; i < 200; i++) {   // 🔧 上限 30 对核价方言不够（分组数更多）
    const collapsed = page.locator('.svb-grp.collapsed');
    if (await collapsed.count() === 0) break;
    await collapsed.first().locator('.svb-grp-h').click({ timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(180);
  }
  return page.locator('.svb-grp.collapsed').count();
}

/**
 * 执行一个会触发重新编译的动作，并**等到 `/builder/compile` 真的回来且 200**。
 *
 * 🚨 这是本片最重要的一处量具校准（test.md §4.2）：
 *    compile 400 时前端会**继续显示上一次的 SQL**。若只 `waitForTimeout` 然后读面板，
 *    「SQL 里有 LEFT JOIN」会以**读到旧结果**的方式通过 —— 恒绿且完全看不出来。
 */
export async function withCompile(page: Page, action: () => Promise<void>, why: string): Promise<any | null> {
  const pending = page
    .waitForResponse((r) => /\/builder\/compile(\?|$)/.test(r.url()), { timeout: 25_000 })
    .catch(() => null);
  await action();
  const resp = await pending;
  let parsed: any = null;
  if (resp) {
    const text = await resp.text().catch(() => '');
    expect(resp.ok(),
      `${why}：/builder/compile 返回 ${resp.status()} ⇒ 面板上显示的是**上一次的 SQL**，` +
      `此时任何 SQL 断言都是在读旧结果（假绿/假红都可能）。响应=${text.slice(0, 400)}`
    ).toBe(true);
    try { parsed = JSON.parse(text); } catch { parsed = null; }
  } else {
    console.log(`[量具] ${why}：未捕获到 /builder/compile 响应（可能是本地缓存编译）。`
      + '若后续 SQL 断言异常，先怀疑这里。');
  }
  await page.waitForTimeout(900);
  return parsed;
}

/**
 * 从 compile 响应里取「每一列的视图列名」。
 * 列名属性由后端决定，做多路兜底；一个都读不到就硬失败并 dump 键名（🚫 不返回空数组）。
 */
export function compileColumnNames(compileBody: any, why: string): { fieldName: string; viewColumn: string }[] {
  // 🔧 2026-09-08 执行期 harness 修复：compile 响应的列清单键名实测是 `declaredColumns`
  //    （顶层键 = ["sql","declaredColumns","requiredVariables","grain","rewriterCompatible","warnings"]），
  //    原来只试 columns/cols/fields ⇒ 读不到、AC-5b 恒判「未验证」。
  const cols = deepFind(compileBody, ['declaredColumns', 'columns', 'cols', 'fields']);
  expect(Array.isArray(cols) && cols.length > 0,
    `${why}：compile 响应里读不到 columns 数组 ⇒ 量具未校准，本条判【未验证】。` +
    `顶层键=${compileBody ? JSON.stringify(Object.keys(compileBody)) : '(非 JSON)'}`
  ).toBe(true);
  return (cols as any[]).map((c) => {
    // 🔧 2026-09-08 执行期 harness 修复：`declaredColumns` 实测是**字符串数组**
    //    （元素形如 "hf_part_no"），不是对象数组 ⇒ 原来只按对象取键，读不到就硬失败。
    if (typeof c === 'string') return { fieldName: '', viewColumn: c };
    const viewColumn = c?.viewColumn ?? c?.columnName ?? c?.column ?? c?.alias ?? c?.name ?? c?.key;
    const fieldName = c?.fieldName ?? c?.label ?? c?.title ?? c?.displayName ?? c?.name;
    expect(typeof viewColumn === 'string' && viewColumn.length > 0,
      `${why}：compile 响应的列对象里读不到视图列名（试过 viewColumn/columnName/column/alias/name/key）。` +
      `列对象=${JSON.stringify(c).slice(0, 300)} ⇒ 量具未校准，本条判【未验证】。`
    ).toBe(true);
    return { fieldName: String(fieldName ?? ''), viewColumn: String(viewColumn) };
  });
}

/** 选择数据源（`[data-role="builder-source"]`）。 */
export async function selectSource(page: Page, sourceLabel: string) {
  const sel = page.locator('[data-role="builder-source"]').first();
  await expect(sel, '应存在「数据源」下拉（data-role="builder-source"）⇒ 取不到判【未验证】')
    .toBeVisible({ timeout: 10_000 });
  await withCompile(page, async () => {
    await sel.click();
    await page.waitForTimeout(400);
    const option = page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
      .filter({ hasText: new RegExp(`^${sourceLabel}$`) })
      .first();
    await expect(option, `数据源下拉里找不到「${sourceLabel}」`).toBeVisible({ timeout: 10_000 });
    await option.click();
  }, `选数据源「${sourceLabel}」`);
  await page.waitForTimeout(1200);
}

/** 选择数据集（报价 / 基础核价 / 明细核价）；切数据集会弹「清空已选输出列」确认，有就确认。 */
export async function selectDataset(page: Page, label: string) {
  const bar = page.locator('.svb-recipe-bar').first();
  let target = bar.getByText(label, { exact: true }).first();
  if (await target.count() === 0) target = page.getByText(label, { exact: true }).first();
  await expect(target, `取数面板里找不到数据集「${label}」⇒ 入口问题，本条判【未验证】`)
    .toBeVisible({ timeout: 10_000 });
  await target.click();
  const confirm = page.getByRole('button', { name: /确\s*定|确\s*认/ }).first();
  if (await confirm.isVisible().catch(() => false)) await confirm.click();
  await page.waitForTimeout(2000);

  // 阳性对照：面板自己打印的「表前缀 ds_xxx_」是**数据集独有**的信号
  // （中文显示名三套大量重名，用它判「切过去了」证不出东西 —— task260819v9 实证）。
  const body = await page.locator('body').innerText();
  const m = body.match(/表前缀\s*(ds_[a-z_]+_)/);
  expect(m,
    `切到数据集「${label}」后读不到「表前缀 ds_xxx_」标记 —— 该标记是判定「数据集真的切过去了」的唯一精确信号。\n` +
    `  读不到 ⇒ 判据需重新校准，本条判【未验证】，不是产品缺陷。`
  ).not.toBeNull();
  console.log(`[数据集] ${label} → 表前缀 ${m![1]}`);
  return m![1];
}

/**
 * 把左侧某字段加入「已选输出列」（既有 spec 统一用双击，真实拖拽受虚拟滚动影响）。
 * 加完等 compile 回来。
 */
export async function addField(page: Page, fieldLabel: string): Promise<any | null> {
  const before = await selectedColumnCount(page);
  const compiled = await withCompile(page, async () => {
    // 🔧 2026-09-08 执行期 harness 修复：`材料名` 在**每个数据源分组里各有一份**（实测同页 14 个），
    //    `.first()` 常落在一个**折叠分组**里 ⇒ 报 hidden。取第一个**可见**的即可
    //    （AC-7 已单独断言它内联在锚点分组内，这里只负责把列加进去）。
    const f = page.locator('.svb-grp').getByText(fieldLabel, { exact: true })
      .locator('visible=true').first();
    await expect(f,
      `字段面板里找不到可见的「${fieldLabel}」（若报 hidden ⇒ 分组没展开，不是字段缺失）`
    ).toBeVisible({ timeout: 10_000 });
    await f.dblclick();
  }, `加列「${fieldLabel}」`);
  const after = await selectedColumnCount(page);
  expect(after,
    `加「${fieldLabel}」后「已选输出列」数量没变（${before} → ${after}）⇒ 这一列**根本没加进去**，` +
    `后面所有关于它的 SQL 断言都会是空跑。`
  ).toBeGreaterThan(before);
  return compiled;
}

export async function selectedColumnCount(page: Page): Promise<number> {
  return page.locator('[data-role="selected-column"], .selected-column-row').count();
}

export async function selectedColumnTexts(page: Page): Promise<string[]> {
  const loc = page.locator('[data-role="selected-column"], .selected-column-row');
  return (await loc.allInnerTexts()).map((s) => s.replace(/\s+/g, ' ').trim());
}

/**
 * 「生成的 SQL」面板文本。
 * 🚨 🚫 不要用 `pre/code` 的 `.first()` —— 2026-09-07 实证它取到的是只含 `"ds_quote_"` 的碎片元素，
 *    于是「应含 LEFT JOIN」以**取错元素**的方式变红，长得像产品缺陷。
 */
export async function sqlPanelText(page: Page): Promise<string> {
  const panel = page.locator('[data-role="sql-panel"]');
  let txt = '';
  if (await panel.count() > 0) txt = (await panel.allInnerTexts()).join('\n');
  else txt = (await page.locator('pre, code').allInnerTexts().catch(() => [] as string[])).join('\n');
  expect(txt.trim().length,
    '「生成的 SQL」面板读到空串 ⇒ 量具坏了（选择器或面板未渲染），此时 `toContain` 会以「不含」形式假红、' +
    '`not.toContain` 会以「不含」形式**假绿**。本条判【未验证】。'
  ).toBeGreaterThan(0);
  return txt;
}

// ═══════════════════════════════════════════════════════════════════
// SQL 文本工具（🚨 全部按「标识符边界」匹配，🚫 不用裸 substring）
// ═══════════════════════════════════════════════════════════════════

/**
 * 统计某张表在 SQL 里被引用的次数。
 *
 * 🚨 **这里有一个必踩的坑**：`ds_quote_material` 是 `ds_quote_material_bom` /
 *    `ds_quote_material_bom_record` 的**前缀**。裸 `split('ds_quote_material').length-1`
 *    在物料BOM 的 SQL 上会数出一大堆，于是 AC-5 的「只出现 1 次」永远红、
 *    AC-3 的「出现 2 次」永远绿 —— 两个方向同时错。
 *    ⇒ 必须用标识符边界（前后都不能是 `[A-Za-z0-9_]`）。
 */
export function tableRefCount(sql: string, table: string): number {
  const re = new RegExp(`(?<![A-Za-z0-9_])${table}(?![A-Za-z0-9_])`, 'g');
  return (sql.match(re) || []).length;
}

export type JoinInfo = { alias: string; on: string; raw: string };

/** 抽出 `LEFT JOIN <table> [AS] <alias> ON <条件>` 的全部实例（同样按标识符边界匹配表名）。 */
export function leftJoinsOf(sql: string, table: string): JoinInfo[] {
  const re = new RegExp(
    `LEFT\\s+(?:OUTER\\s+)?JOIN\\s+(?:public\\.)?${table}(?![A-Za-z0-9_])\\s+(?:AS\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s+ON\\s+` +
    `([\\s\\S]*?)(?=\\bLEFT\\s+JOIN\\b|\\bINNER\\s+JOIN\\b|\\bRIGHT\\s+JOIN\\b|\\bJOIN\\b|\\bWHERE\\b|\\bGROUP\\s+BY\\b|\\bORDER\\s+BY\\b|\\bUNION\\b|$)`,
    'gi'
  );
  const out: JoinInfo[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(sql)) !== null) {
    out.push({ alias: m[1], on: m[2].replace(/\s+/g, ' ').trim(), raw: m[0] });
  }
  return out;
}

/** ON 子句里的条件个数（按顶层 AND 粗切；只用于「1 个条件 vs 2 个条件」这种量级判断）。 */
export function onConditionCount(on: string): number {
  return on.split(/\bAND\b/i).map((s) => s.trim()).filter(Boolean).length;
}

// ═══════════════════════════════════════════════════════════════════
// 预览
// ═══════════════════════════════════════════════════════════════════

export type PreviewResult = {
  status: number;
  body: any;
  rowCount: number | null;
  elapsedMs: number | null;
  rows: any[];
  diagnostics: any[];
};

function deepFind(obj: any, keys: string[]): any {
  if (obj == null || typeof obj !== 'object') return undefined;
  for (const k of keys) if (obj[k] !== undefined) return obj[k];
  for (const v of Object.values(obj)) {
    const r = deepFind(v, keys);
    if (r !== undefined) return r;
  }
  return undefined;
}

/**
 * 点「重新执行」跑真实预览，并**从网络响应**取结果。
 *
 * 🚨 test.md §4.2 明确要求：断言预览行数之前，先确认预览**真的执行了**
 *    （返回里有 `rowCount` 与 `elapsedMs`），而不是请求 400 之后前端显示了上一次的结果。
 *    ⇒ 本函数：① 必须抓到 `/builder/preview` 响应；② 必须 2xx；③ 必须能读出 rowCount。
 *      三者任一不满足就硬失败，🚫 不退化成读 DOM 表格行数。
 */
/**
 * 预览条的「客户」下拉：没选客户时点「重新执行」不会发请求（实测）。
 * 优先选 `罗克韦尔`（CUST-0001，2026-09-08 实测 ds_quote_material_bom 85 行 / ds_quote_element_bom 107 行，
 * 是「有数据的客户」），取不到就选第一项；下拉本身不存在（核价方言）时直接返回。
 */
export async function ensurePreviewCustomer(page: Page, why: string, prefer = '罗克韦尔'): Promise<string | null> {
  const ph = page.getByText('选择预览客户', { exact: false }).first();
  if (await ph.count() === 0 || !(await ph.isVisible().catch(() => false))) {
    return null; // 已选过 / 该方言无此下拉
  }
  // 🔧 2026-09-08 执行期 harness 修复。三个坑，任何一个都表现为「预览发不出请求 / 预览 0 行」：
  //   ① 普通 click 被 antd selector 覆盖层吞掉 ⇒ 必须 force
  //   ② 该 Select **没有 showSearch**（实测 `input.ant-select-selection-search-input` count=0）⇒ 打不了字
  //   ③ 选项走 **rc-virtual-list 虚拟滚动**，一屏只渲染 10 项（全库 66 个客户）
  //      ⇒ 目标客户不在首屏 DOM 里，`filter({hasText})` 必然 0 命中，
  //        然后退回「第一项」= T260902-客户-…（无 BOM 数据）⇒ 预览 rowCount=0，
  //        而 0 行会让「材料名非空 > 0」这类阳性对照**空跑**。必须滚动找。
  await ph.click({ force: true, timeout: 10_000 }).catch(() => {});
  await page.waitForTimeout(1500);
  const ddSel = '.ant-select-dropdown:not(.ant-select-dropdown-hidden)';
  const optSel = `${ddSel} .ant-select-item-option`;
  for (let i = 0; i < 12 && await page.locator(optSel).count() === 0; i++) await page.waitForTimeout(500);
  if (await page.locator(optSel).count() === 0) {
    console.log(`[量具] ${why}：客户下拉打开了但一个选项都没有 ⇒ 预览发不出请求，本条判【未验证】。`);
    await page.keyboard.press('Escape');
    return null;
  }
  const holder = page.locator(`${ddSel} .rc-virtual-list-holder`).first();
  let hit = page.locator(optSel).filter({ hasText: prefer }).first();
  for (let i = 0; i < 40 && await hit.count() === 0; i++) {
    await holder.evaluate((el) => { el.scrollTop += el.clientHeight * 0.8; }).catch(() => {});
    await page.waitForTimeout(250);
    hit = page.locator(optSel).filter({ hasText: prefer }).first();
  }
  const target = (await hit.count()) > 0 ? hit : page.locator(optSel).first();
  const found = (await hit.count()) > 0;
  const label = ((await target.innerText().catch(() => '')) || '').trim();
  await target.click({ force: true, timeout: 10_000 });
  await page.waitForTimeout(1200);
  if (!found) {
    console.log(`[量具] ${why}：滚了 40 屏也没找到「${prefer}」，退回第一项「${label}」。`
      + '⚠️ 该客户可能没有数据 ⇒ 预览 0 行会让阳性对照空跑，报告里要写明。');
  } else {
    console.log(`[预览] ${why}：已选预览客户「${label}」`);
  }
  return label;
}

export async function runPreview(page: Page, why: string): Promise<PreviewResult> {
  const pending = page
    .waitForResponse((r) => /\/builder\/preview(\?|$)/.test(r.url()), { timeout: 60_000 })
    .catch(() => null);

  const btn = page.getByText('重新执行', { exact: false }).first();
  await expect(btn, `${why}：找不到「重新执行」按钮（真实预览应默认展开）⇒ 入口问题，本条判【未验证】`)
    .toBeVisible({ timeout: 15_000 });
  // 🔧 2026-09-08 执行期 harness 修复：预览条上的「客户」下拉不选，点「重新执行」**不发请求**
  //    （实测：一个 /builder/preview 都抓不到）。AC-2 原文本来就写了「客户选实测有数据的客户」，
  //    是用例漏了这一步。⚠️ 核价方言下该下拉可能不存在 ⇒ best-effort，不存在就跳过。
  await ensurePreviewCustomer(page, why);
  await btn.click();

  const resp = await pending;
  expect(resp,
    `${why}：点了「重新执行」但**一个 /builder/preview 请求都没抓到**。\n` +
    `  ⇒ 此时页面上显示的很可能是上一次的结果，任何行数断言都是在读旧结果。本条判【未验证】。`
  ).not.toBeNull();

  const status = resp!.status();
  const text = await resp!.text().catch(() => '');
  let body: any = null;
  try { body = JSON.parse(text); } catch { /* 保留 text */ }
  expect(status >= 200 && status < 300,
    `${why}：/builder/preview 返回 ${status} ⇒ 预览没执行成功，页面上是旧结果。响应=${text.slice(0, 600)}`
  ).toBe(true);

  const rc = deepFind(body, ['rowCount', 'totalRows', 'total']);
  const el = deepFind(body, ['elapsedMs', 'elapsed', 'costMs', 'durationMs']);
  const rows = deepFind(body, ['rows', 'data', 'records']) ?? [];
  const diags = deepFind(body, ['diagnostics', 'diagnosis', 'warnings']) ?? [];

  expect(typeof rc === 'number',
    `${why}：预览响应里读不到 rowCount（试过 rowCount/totalRows/total）。\n` +
    `  🚨 读不到就**不许**拿 DOM 行数替代 —— 那正是 test.md §4.2 点名的假绿形态。\n` +
    `  响应顶层键=${body ? JSON.stringify(Object.keys(body)) : '(非 JSON)'}\n` +
    `  响应片段=${text.slice(0, 800)}`
  ).toBe(true);

  if (typeof el !== 'number') {
    console.log(`[量具] ${why}：响应里没读到 elapsedMs（试过 elapsedMs/elapsed/costMs/durationMs）。`
      + 'rowCount 已拿到，行数断言仍成立；此项仅作记录。');
  }

  console.log(`[预览] ${why} → HTTP ${status} rowCount=${rc} elapsedMs=${el ?? '(缺)'} `
    + `返回行数=${Array.isArray(rows) ? rows.length : '(非数组)'}`);

  return {
    status, body,
    rowCount: typeof rc === 'number' ? rc : null,
    elapsedMs: typeof el === 'number' ? el : null,
    rows: Array.isArray(rows) ? rows : [],
    diagnostics: Array.isArray(diags) ? diags : [],
  };
}

/**
 * 从预览结果里取某一列的全部取值。
 * 列键名（视图列名 vs 字段显示名）由后端决定，这里做**多路兜底**；
 * 全都取不到就硬失败并把可用键打出来 —— 🚫 不返回空数组（空数组会让「非空 N 行」恒 0，
 * 于是「材料名取不到值时显示空」这类断言以**空跑**的方式通过）。
 */
export function previewColumn(pr: PreviewResult, fieldName: string, why: string): any[] {
  expect(pr.rows.length,
    `${why}：预览返回 0 行数据（rowCount=${pr.rowCount}）⇒ 逐行断言会空跑。本条判【未验证】。`
  ).toBeGreaterThan(0);

  const first = pr.rows[0];
  const keys = Array.isArray(first) ? [] : Object.keys(first ?? {});

  // ① 直接按显示名
  if (keys.includes(fieldName)) return pr.rows.map((r) => r[fieldName]);

  // ② 通过 columns 元数据把显示名映射到物理键
  const cols = deepFind(pr.body, ['columns', 'cols', 'fields', 'declaredColumns']);
  if (Array.isArray(cols)) {
    const hit = cols.find((c: any) =>
      c?.fieldName === fieldName || c?.name === fieldName || c?.label === fieldName ||
      c?.title === fieldName || c?.displayName === fieldName);
    if (hit) {
      const key = hit.key ?? hit.dataIndex ?? hit.viewColumn ?? hit.column ?? hit.sourceColumn ?? hit.name;
      if (key && keys.includes(key)) return pr.rows.map((r) => r[key]);
      if (Array.isArray(first)) {
        const idx = cols.indexOf(hit);
        return pr.rows.map((r) => r[idx]);
      }
    }
  }

  throw new Error(
    `${why}：预览结果里定位不到列「${fieldName}」。\n` +
    `  行对象可用键 = ${JSON.stringify(keys)}\n` +
    `  columns 元数据 = ${JSON.stringify(cols)?.slice(0, 600)}\n` +
    `  ⇒ 这是**量具未校准**（列键名规则与预期不符），本条判【未验证】，不是产品缺陷。`
  );
}

export function isBlank(v: any): boolean {
  return v === null || v === undefined || v === '' || v === '—' || v === '-';
}
