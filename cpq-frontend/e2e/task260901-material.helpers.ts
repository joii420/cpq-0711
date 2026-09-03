/**
 * task-260901「材质管理模块定义规则更新」E2E 公共件。
 *
 * 三件事，都是纪律不是便利：
 *  1. 🚨 **端口/库避让** —— 默认拒绝跑在 5174/8081（主线亲验与用户验收用的环境）。
 *  2. 🚨 **证据归档** —— 截图直接写进任务目录 `证据/`，不写 `test-results/`
 *     （后者下一轮开跑会被清空 ⇒ 留在那里 = 没有证据，testing.md §2）。
 *  3. 🚨 **全局状态还原** —— 材质库是共享库里的公共基础数据。`AC测%` 系列 + 元素 `Xx`
 *     + 挂在真实材质 00006 上的测试配置，一律在 afterAll 里物理清除，
 *     且 `00006-01` / `00006` 的元素组成受硬保护。
 */
import { expect, Page } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __f = fileURLToPath(import.meta.url);

/** 证据归档目录（任务目录下，随任务提交）。 */
export const EVIDENCE_DIR = path.resolve(
  path.dirname(__f),
  '../../dev-docs/task-260901-材质管理模块定义规则更新/证据'
);

export const BASE_URL = process.env.PW_BASE_URL || '';
export const BACKEND_URL = process.env.PW_BACKEND_URL || '';

/** 目标库。清理 SQL 打在这里。 */
export const DB_NAME = process.env.PW_DB || 'cpq_db_0724';
const DB_HOST = process.env.PW_DB_HOST || '10.177.152.12';
const DB_USER = process.env.PW_DB_USER || 'postgres';
const DB_PASS = process.env.PW_DB_PASS || 'joii5231';

export const AC_PREFIX = 'AC测';
export const REAL_RECIPE_CODE = '00006';
export const PROTECTED_CONFIG_NO = '00006-01';

/**
 * 🚨 隔离守卫。默认**拒绝**跑在共享 dev 环境上。
 * 需要在 dev 环境跑时由主线显式 `PW_ALLOW_DEV_ENV=1` —— 那是主线的裁决，不是本用例自作主张。
 */
export function assertIsolatedEnv() {
  if (!BASE_URL || !BACKEND_URL) {
    throw new Error(
      '[task260901] 必须显式给 PW_BASE_URL / PW_BACKEND_URL（临时前端/后端端口）。\n' +
      '本套用例会往材质库写数据，跑在共享 5174/8081 会污染主线亲验环境。'
    );
  }
  const onShared = /:5174(\/|$)/.test(BASE_URL) || /:8081(\/|$)/.test(BACKEND_URL);
  if (onShared && process.env.PW_ALLOW_DEV_ENV !== '1') {
    throw new Error(
      `[task260901] 🚫 拒绝在共享 dev 环境执行：BASE=${BASE_URL} BACKEND=${BACKEND_URL}\n` +
      '8081/5174 保留给主线亲验。请起临时端口（如 5175 → 8082），或由主线显式设 PW_ALLOW_DEV_ENV=1。'
    );
  }
  console.log(`[task260901] env: base=${BASE_URL} backend=${BACKEND_URL} db=${DB_NAME}` +
    (onShared ? '  ⚠️ 已由 PW_ALLOW_DEV_ENV=1 显式放行' : ''));
}

/** 只读 SQL，返回制表分隔的行数组。 */
export function sql(q: string): string[] {
  const out = execFileSync('psql',
    ['-h', DB_HOST, '-U', DB_USER, '-d', DB_NAME, '-tAF', '\t', '-c', q],
    { env: { ...process.env, PGPASSWORD: DB_PASS }, encoding: 'utf-8' });
  return out.split('\n').map(s => s.trim()).filter(Boolean);
}

export function sqlOne(q: string): string | null {
  const r = sql(q);
  return r.length ? r[0] : null;
}

/**
 * 全局状态还原。三条谓词都是收敛的：
 *  - `symbol LIKE 'AC测%'`（本套用例专用前缀）
 *  - `element_code = 'Xx'`
 *  - `config_no <> '00006-01'` 且限定 recipe=00006
 * 🚫 不清库、不 TRUNCATE、不 DROP。
 */
export function restoreGlobalState() {
  sql(`
    DELETE FROM material_recipe_element WHERE config_id IN (
      SELECT c.id FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id
      WHERE r.symbol LIKE '${AC_PREFIX}%');
    DELETE FROM material_recipe_config WHERE recipe_id IN (
      SELECT id FROM material_recipe WHERE symbol LIKE '${AC_PREFIX}%');
    DELETE FROM material_recipe_composition WHERE recipe_id IN (
      SELECT id FROM material_recipe WHERE symbol LIKE '${AC_PREFIX}%');
    DELETE FROM material_recipe WHERE symbol LIKE '${AC_PREFIX}%';

    DELETE FROM material_recipe_element WHERE config_id IN (
      SELECT c.id FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id
      WHERE r.code='${REAL_RECIPE_CODE}' AND c.config_no <> '${PROTECTED_CONFIG_NO}');
    DELETE FROM material_recipe_config
      WHERE config_no <> '${PROTECTED_CONFIG_NO}'
        AND recipe_id IN (SELECT id FROM material_recipe WHERE code='${REAL_RECIPE_CODE}');

    DELETE FROM element WHERE element_code='Xx';
    UPDATE material_recipe SET allow_custom_content=false WHERE code='${REAL_RECIPE_CODE}';
  `);
}

/**
 * 还原自检。残留即失败。
 * 🚫 不许用「跑完手工清一下」代替它 —— 用例中途失败就不会清，
 *    下一轮变成恒定失败，而且长得像业务回归。
 */
export function assertNoResidue() {
  const ac = sqlOne(`SELECT count(*) FROM material_recipe WHERE symbol LIKE '${AC_PREFIX}%'`);
  expect(ac, `还原自检：仍有 ${ac} 条 ${AC_PREFIX}% 材质残留`).toBe('0');
  const xx = sqlOne(`SELECT count(*) FROM element WHERE element_code='Xx'`);
  expect(xx, `还原自检：仍有 ${xx} 行测试元素 Xx 残留`).toBe('0');

  // 🚨 真实数据不变式
  const comp = sql(`SELECT c.element_code FROM material_recipe_composition c
    JOIN material_recipe r ON r.id=c.recipe_id
    WHERE r.code='${REAL_RECIPE_CODE}' ORDER BY c.sort_order`);
  expect(comp, `🚨 真实材质 ${REAL_RECIPE_CODE} 的元素组成被改动了`).toEqual(['Ag', 'Ni']);
  const keep = sqlOne(`SELECT count(*) FROM material_recipe_config WHERE config_no='${PROTECTED_CONFIG_NO}'`);
  expect(keep, `🚨 存量真实配置 ${PROTECTED_CONFIG_NO} 被删掉了`).toBe('1');
  // 🚨 AC-22（2026-09-02 更正）已改用 00006-02 作删除对象；00006-01 全路径只读。
  //    这里**刻意不做复位兜底** —— 有兜底，护栏就哑了；状态不对必须硬失败。
  const keepStatus = sqlOne(`SELECT status FROM material_recipe_config WHERE config_no='${PROTECTED_CONFIG_NO}'`);
  expect(keepStatus, `🚨 存量真实配置 ${PROTECTED_CONFIG_NO} 被留在 ${keepStatus} 状态`).toBe('ACTIVE');

  // 🚨 不许留下孤儿销售指纹：它会让**下一轮**选配复用一个已不存在的料号 ⇒ 零落库，
  //    且症状伪装成「写入点没跑」。这是本套用例最隐蔽的一种残留。
  const orphan = sqlOne(`SELECT count(*) FROM sel_part_signature s
    WHERE NOT EXISTS (SELECT 1 FROM material_master m WHERE m.material_no = s.quote_part_no)`);
  expect(orphan, `🚨 残留 ${orphan} 行孤儿销售指纹（指向已不存在的料号）`).toBe('0');
}

let shotIdx = 0;
/** 截图 → 证据/（不是 test-results/）。 */
export async function shot(page: Page, name: string, opts: { fullPage?: boolean } = {}) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const file = path.join(EVIDENCE_DIR, `E2E-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: opts.fullPage ?? false });
  console.log(`📸 证据 → ${file}`);
  return file;
}

/** 文本证据（SQL 输出、接口原文）→ 证据/。 */
export function evidence(name: string, content: string) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const file = path.join(EVIDENCE_DIR, `E2E-${name}.txt`);
  fs.writeFileSync(file, content, 'utf-8');
  console.log(`🧾 证据 → ${file}`);
  return file;
}

/**
 * API 登录。
 * 🚨 **带退避重试**：登录限流是 30 次/分/IP，整套 spec 反复重跑很容易打满。
 * 打满后表现为 `API 登录应成功` 失败，**看起来像鉴权坏了**，实际是测试基础设施问题
 * （2026-09-02 实跑踩到）。⇒ 429 就等一会儿再试，并把状态码打出来，不掩盖真失败。
 */
export async function login(page: Page) {
  let last = 0, body = '';
  for (let i = 0; i < 4; i++) {
    const res = await page.request.post('/api/cpq/auth/login', {
      data: { username: 'admin', password: 'Admin@2026' },
    });
    if (res.ok()) return;
    last = res.status();
    body = await res.text().catch(() => '');
    console.warn(`[login] 第 ${i + 1} 次失败 status=${last} body=${body.slice(0, 200)}；` +
      (last === 429 ? '疑似登录限流(30/min/IP)，退避后重试' : '非限流失败'));
    await page.waitForTimeout(20_000);
  }
  expect(false, `API 登录连续 4 次失败，最后 status=${last} body=${body}。` +
    `429 = 登录限流（测试基础设施问题，不是产品缺陷）；其它码才是真失败`).toBeTruthy();
}

/** 进「主数据维护 → 材质」。 */
export async function gotoMaterialTab(page: Page) {
  await page.goto('/master-data-hub');
  if (/change-password|\/login/.test(page.url())) await page.goto('/master-data-hub');
  await page.getByRole('tab', { name: '材质' }).click();
  // ⚠️ 不要等「材质管理」这四个字：页签名即标题，页面上没有这段文案
  //    （前端 2026-09-02 实测，grep 全 src/ 零命中）。等表格行才是可靠信号。
  await page.waitForSelector('.ant-table-row', { timeout: 20_000 });
}

/**
 * 🚨 读**禁用**按钮的 tooltip 必须用 `page.mouse.move`。
 * `locator.hover({force:true})` 在禁用元素上取不到 tooltip —— 前端 2026-09-02 一度据此
 * 准备把共享组件 `SelectableTable.tsx` 报成缺陷，换成 mouse.move 后三处 tooltip 全部正常。
 * ⇒ 用 hover 会假报「tooltip 不存在」，把测试问题误判成产品缺陷。
 */
export async function tooltipOf(page: Page, locator: any): Promise<string> {
  const box = await locator.boundingBox();
  expect(box, 'tooltip 目标须可见并有布局盒（拿不到 = 定位错了，不是产品缺陷）').not.toBeNull();
  await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
  await page.waitForTimeout(700);
  // 🚨 antd v6 的 tooltip 文本在 `.ant-tooltip-container`；`.ant-tooltip-inner` **在 v6 里不存在**
  //    （2026-09-02 探 DOM 实测）。取错 class ⇒ 恒 not found ⇒ 会被误报成「tooltip 没做」。
  const tip = page.locator('.ant-tooltip-container, .ant-tooltip-inner').last();
  await expect(tip, 'hover 后应出现 tooltip').toBeVisible({ timeout: 8_000 });
  const text = (await tip.innerText()).trim();
  console.log('[tooltip]', text);
  return text;
}

/**
 * 🚨 AC-19 专用还原（2026-09-02 新增登记）。
 * 自定义含量提交会**真建料号**：`material_master` + `material_bom_item` + `element_bom_item` 各落行。
 * 这三张表**不在** test.md §1 最初登记的三张表里，还原面因此扩大，登记在此。
 *
 * 识别判据：dev 库基线实测 `material_master` 1890 行中
 * `material_recipe_id` 与 `config_fingerprint` **双非空 0 行**（258 条材质全 locked，custom 路径走不通）。
 * ⇒ 双非空 = 必然是本次测试造出来的。
 * 🚫 仍不按判据批删：先查出**具体 id 列表**，超过 MAX_SAFE 条就**中止并报告**，不删。
 */
const MAX_SAFE_CUSTOM_PARTS = 20;
export function restoreCustomConfiguredParts(): string {
  // ⚠️ 判据只用 `material_recipe_id IS NOT NULL`（dev 库基线实测 0 行）。
  //    🚫 不能再 AND `config_fingerprint IS NOT NULL` —— 实测选配落库的 fingerprint 是 NULL
  //    （与选配 Plan 3b R1 一致），双非空谓词会**漏掉本次造的行**，清不干净。
  const ids = sql(`SELECT id FROM material_master WHERE material_recipe_id IS NOT NULL`);
  const partNos = sql(`SELECT material_no FROM material_master WHERE material_recipe_id IS NOT NULL`);
  if (ids.length === 0) return '无自定义料号需清理';
  if (ids.length > MAX_SAFE_CUSTOM_PARTS) {
    throw new Error(`🚨 停下报告：待清理的自定义料号 ${ids.length} 条，超过安全阈值 ` +
      `${MAX_SAFE_CUSTOM_PARTS}。这超出「测试自建」的合理规模，可能命中了真实数据 —— ` +
      `不自行删除，请主线裁决。id 前 5 条：${ids.slice(0, 5).join(',')}`);
  }
  // ⚠️ element_bom_item / material_bom_item **没有** material_master_id 列，按 `material_no`（料号）关联
  const inIds = ids.map(i => `'${i}'`).join(',');
  const inNos = partNos.map(i => `'${i}'`).join(',');
  sql(`
    DELETE FROM element_bom_item  WHERE material_no IN (${inNos});
    DELETE FROM material_bom_item WHERE material_no IN (${inNos});
    -- 🚨 必须一并删销售侧复用指纹：只删 material_master 会留下**孤儿指纹**，
    --    下次选配判定「已存在」直接复用那个已被删的料号 ⇒ 一行都不落库，
    --    看起来像「写入点没跑」的产品缺陷（2026-09-02 实跑踩到并实证：孤儿指纹恰 1 行）。
    DELETE FROM sel_part_signature WHERE quote_part_no IN (${inNos});
    DELETE FROM material_master   WHERE id IN (${inIds});
  `);
  return `已清理自定义料号 ${ids.length} 条（含销售指纹）：${partNos.join(',')}`;
}

/**
 * 🚨 按**标签**取开关，不按下标。
 * 材质编辑抽屉里有两个 `.ant-switch`：「状态/启用」排在前，「支持自定义含量」在后。
 * `.ant-switch` + `.first()` 抓到的是「状态」——2026-09-02 实跑踩到，
 * 表现为点击超时/断言值不对，看起来像「开关坏了」，实际是选错了对象。
 */
export async function switchByLabel(page: Page, scope: any, label: string | RegExp) {
  const hitSwitch = (t: string) => typeof label === 'string' ? t.includes(label) : label.test(t);
  const switches = scope.locator('.ant-switch');
  const n = await switches.count();
  expect(n, `作用域内应有开关（0 个 = 定位错了）`).toBeGreaterThan(0);
  for (let i = 0; i < n; i++) {
    const ctx: string = await switches.nth(i).evaluate((el: Element) =>
      (el.closest('.ant-form-item, .ant-row, section, div')?.textContent || ''));
    if (hitSwitch(ctx)) {
      console.log(`[switchByLabel] 「${label}」= 第 ${i} 个开关（共 ${n} 个）`);
      return switches.nth(i);
    }
  }
  throw new Error(`未找到标签为「${label}」的开关（共 ${n} 个开关）`);
}

/**
 * 🚨 按**邻近标签文本**找下拉框，不按下标。
 * 选配抽屉里有多个 `.ant-select`（材质、含量配置…），`.nth(k)` 的写法一旦顺序变就静默选错，
 * 表现为「候选为空」——看起来像下拉没数据的产品缺陷（2026-09-02 实跑踩到）。
 */
export async function selectByNearbyLabel(scope: any, label: string | RegExp) {
  const hit = (t: string) => typeof label === 'string' ? t.includes(label) : label.test(t);
  const sels = scope.locator('.ant-select');
  const n = await sels.count();
  expect(n, `作用域内应有下拉框（0 个 = 定位错了）`).toBeGreaterThan(0);
  const seen: string[] = [];
  for (let i = 0; i < n; i++) {
    const ctx: string = await sels.nth(i).evaluate((el: Element) => {
      let node: Element | null = el;
      for (let d = 0; d < 5 && node; d++) {
        node = node.parentElement;
        const t = (node?.textContent || '').trim();
        if (t.length > 2) return t;
      }
      return '';
    });
    seen.push(ctx.slice(0, 40));
    if (hit(ctx)) {
      console.log(`[selectByNearbyLabel] 「${label}」= 第 ${i} 个下拉（共 ${n} 个）`);
      return sels.nth(i);
    }
  }
  throw new Error(`未找到邻近文本含「${label}」的下拉（共 ${n} 个）：${JSON.stringify(seen)}`);
}

/**
 * 🚨 按**标签文本**找元素组成 chip 上的关闭图标，不猜 chip 的 class。
 * 2026-09-02 实跑：chip 既不是 `.ant-tag` 也不带 `chip` 类名，猜 class 一律 not found，
 * 而 not found 看起来像「删不掉」的产品缺陷。改从 `.anticon-close` 反向按祖先文本认领。
 */
export async function chipCloseByLabel(scope: any, label: string | RegExp) {
  const hitChip = (t: string) => typeof label === 'string' ? t.includes(label) : label.test(t);
  const icons = scope.locator('.anticon-close');
  const n = await icons.count();
  expect(n, `作用域内应有可删除的 chip（0 个 = 定位错了或本就不可删）`).toBeGreaterThan(0);
  for (let i = 0; i < n; i++) {
    const ctx: string = await icons.nth(i).evaluate((el: Element) => {
      let node: Element | null = el;
      for (let d = 0; d < 4 && node; d++) { node = node.parentElement; if (node && (node.textContent || '').trim()) break; }
      return (node?.textContent || '').trim();
    });
    if (hitChip(ctx)) {
      console.log(`[chipCloseByLabel] 「${label}」= 第 ${i} 个 close 图标（共 ${n} 个），祖先文本=${ctx}`);
      return icons.nth(i);
    }
  }
  throw new Error(`未找到文本含「${label}」的 chip（共 ${n} 个 close 图标）`);
}

/**
 * ⚠️ AntD Select 走虚拟滚动：没渲染的选项在 DOM 里不存在。
 * 选配材质下拉实测 258 项 —— **必须先输入过滤再点选**，靠滚动找会随机挂。
 */
export async function pickFromSelect(page: Page, select: any, filter: string, optionText: string) {
  await select.click();
  await page.keyboard.type(filter);
  const opt = page.locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: optionText }).first();
  await expect(opt, `下拉应筛出「${optionText}」（过滤词 ${filter}）`).toBeVisible({ timeout: 10_000 });
  await opt.click();
}
