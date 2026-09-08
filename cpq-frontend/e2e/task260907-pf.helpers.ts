/**
 * task-260907「产品管理客户过滤」E2E 公共件。
 *
 * 🚫 本套用例不读实现代码（`pages/product/**`、`pages/master-data/**`、`dataset/**`），
 * 断言一律指回 `需求文档.md §③` 的 AC 原文，选择器指回 `原型图/`。
 *
 * 复用而不重造：登录 / 打开产品管理页 / 切页签 / 抽屉开关 / 只读 SQL 等基础设施，
 * 直接从同目录 `product-hub.helpers.ts`（task-260903「产品管理页重做」）import 复用 ——
 * 那些是**通用**能力，与本任务共享同一个页面。本文件只额外提供两样东西：
 *   1. 本任务专属的**夹具写库**（product-hub.helpers 的 `sql()` 按约定只读，本任务需要写夹具，
 *      不去污染那份契约，改在本文件另开一个**显式标注为写**的入口，且约束只对 `T260907M-` 前缀生效）；
 *   2. 本任务专属的**证据归档目录**（不能写去 task-260903 的 `证据/`，那是它的交付物）。
 *
 * 🚨 共享库红线（`CLAUDE.md` §3.2 + test.md 夹具纪律）：
 *   - 前缀固定 `T260907M-`（并发线 `T260907B-`/`T260907Q-`/`T260907T-` 与本套互不干扰）；
 *   - 清理一律按**精确值**（`material_no + customer_no` 复合等值匹配），🚫 不出现 `LIKE 'T260907%'`；
 *   - 不含 TRUNCATE / DROP / 无 WHERE 的 DELETE，哪怕写在 `beforeAll`/`afterAll` 里。
 */
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { expect, type Page } from '@playwright/test';

const __f = fileURLToPath(import.meta.url);

export const EVIDENCE_DIR = path.resolve(
  path.dirname(__f),
  '../../dev-docs/task-260907-产品管理客户过滤/证据/e2e'
);

const DB_HOST = process.env.PW_DB_HOST || '10.177.152.12';
const DB_USER = process.env.PW_DB_USER || 'postgres';
const DB_PASS = process.env.PW_DB_PASS || 'joii5231';
export const DB_NAME = process.env.PW_DB || 'cpq_db_0724';

// ─────────────────────────── AC 常量（test.md「客户取值」，禁止就地改数） ───────────────────────────

export const FX = 'T260907M-';
export const CUST_A = 'CUST-0001'; // 罗克韦尔
export const CUST_B = 'CUST-0004'; // 正泰
export const CUST_UNREG_1 = 'C1';
export const CUST_UNREG_2 = 'Q13CUST0617';
/** 明确不存在于候选集合、也不应出现在任何业务表里的客户号（AC-12 用）。 */
export const CUST_ABSENT = 'T260907M-NOPE';

// ─────────────────────────── 只读 SQL ───────────────────────────

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

export function tableExists(t: string): boolean {
  return sqlOne(`SELECT to_regclass('public.${t}') IS NOT NULL`) === 't';
}

export function columnExists(table: string, column: string): boolean {
  return sqlOne(`SELECT count(*) FROM information_schema.columns WHERE table_schema='public' `
    + `AND table_name='${table}' AND column_name='${column}'`) !== '0';
}

/** 外部依赖门：28 张表的 customer_no DDL 是否已落共享库（探针同后端套件）。 */
export function materialHasCustomerNo(): boolean {
  return columnExists('ds_quote_material', 'customer_no');
}

export function materialUniqueIndexIsComposite(): boolean {
  return sqlOne(`SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid `
    + `WHERE c.relname='uq_ds_quote_material' AND i.indnkeyatts > 1`) !== '0';
}

// ─────────────────────────── 唯一的写库入口（本任务专属夹具） ───────────────────────────

/**
 * 🚨 本文件<b>唯一</b>写库入口。只对 `T260907M-` 前缀的值生效——不是靠约定，是<b>参数类型上</b>
 * 强制要求 `materialNo`/`customerNo` 以 `T260907M-` 开头才允许调用（customerNo 例外：
 * 允许传入真实客户号 `CUST-0001`/`CUST-0004`，因为「客户」不是本套夹具自造的对象，
 * 「料号」才是——一律要求 materialNo 以 FX 开头）。
 */
export function insertMaterialRow(materialNo: string, customerNo: string, productionNo: string | null): void {
  if (!materialNo.startsWith(FX)) {
    throw new Error(`🚨 拒绝写入：materialNo=${materialNo} 不以 ${FX} 开头 —— 本文件的写库入口只允许操作本套夹具`);
  }
  const pnLiteral = productionNo === null ? 'NULL' : `'${productionNo.replace(/'/g, "''")}'`;
  execFileSync('psql',
    ['-h', DB_HOST, '-U', DB_USER, '-d', DB_NAME, '-c',
      `INSERT INTO ds_quote_material (material_no, material_name, customer_no, production_no, source, created_at) `
      + `VALUES ('${materialNo}', 'T260907M E2E 测试夹具', '${customerNo}', ${pnLiteral}, 'IMPORT', now())`],
    { env: { ...process.env, PGPASSWORD: DB_PASS }, encoding: 'utf-8' });
}

/** 精确清理：`DELETE FROM ds_quote_material WHERE material_no=? AND customer_no=?`（复合等值，非 LIKE）。 */
export function cleanupMaterialRow(materialNo: string, customerNo: string): void {
  if (!materialNo.startsWith(FX)) {
    throw new Error(`🚨 拒绝删除：materialNo=${materialNo} 不以 ${FX} 开头 —— 精确清理只对本套夹具生效`);
  }
  execFileSync('psql',
    ['-h', DB_HOST, '-U', DB_USER, '-d', DB_NAME, '-c',
      `DELETE FROM ds_quote_material WHERE material_no = '${materialNo}' AND customer_no = '${customerNo}'`],
    { env: { ...process.env, PGPASSWORD: DB_PASS }, encoding: 'utf-8' });
}

export function readProductionNo(materialNo: string, customerNo: string): string | null {
  const marker = sqlOne(`SELECT CASE WHEN production_no IS NULL THEN '<NULL>' ELSE '<VAL>' || production_no END `
    + `FROM ds_quote_material WHERE material_no = '${materialNo}' AND customer_no = '${customerNo}'`);
  if (marker === null) return null;
  if (marker === '<NULL>') return null;
  return marker.slice('<VAL>'.length);
}

// ─────────────────────────── 证据归档（本任务专属目录） ───────────────────────────

let shotIdx = 0;
export async function shot(page: Page, name: string, opts: { fullPage?: boolean } = {}) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const file = path.join(EVIDENCE_DIR, `E2E-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: opts.fullPage ?? false });
  console.log(`📸 证据 → ${file}`);
  return file;
}

export function evidence(name: string, content: string) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const file = path.join(EVIDENCE_DIR, `E2E-${name}.txt`);
  fs.writeFileSync(file, content, 'utf-8');
  console.log(`🧾 证据 → ${file}`);
  return file;
}

// ─────────────────────────── 壳页全局客户选择器（F-1，选择器按原型图约定，非实现） ───────────────────────────

/**
 * 定位壳页客户选择器。
 * 🚨 <b>不猜实现的 class/data-testid</b>——用「与文案『客户』同一带 addon 的 antd Select」这一语义特征，
 * 与 `原型图/01-壳页-客户产品-默认态.html`「带『客户』addon」的描述对齐。
 * 若定位失败，报错里带上页面当前 DOM 摘要，避免把「测试选择器没跟上实现」误判成「功能没做」。
 */
export async function customerSelector(page: Page) {
  // antd `Select` 加 addonBefore 时结构大致是 `.ant-input-group-addon` + 紧邻的 `.ant-select`；
  // 退而求其次按可访问角色 combobox 且离「客户」文案最近的一个来定位。
  const byAddon = page.locator('.ant-input-group-addon', { hasText: '客户' })
    .locator('xpath=following-sibling::*[1]//*[@role="combobox"]');
  if (await byAddon.count()) {
    return byAddon.first();
  }
  const byRole = page.getByRole('combobox').filter({ hasText: /所有客户|CUST-|客户/ });
  return byRole.first();
}

/** 读选择器当前显示文案。 */
export async function customerSelectorText(page: Page): Promise<string> {
  const sel = await customerSelector(page);
  return (await sel.innerText().catch(() => sel.textContent())) ?? '';
}

/**
 * 选中某个客户。`labelOrCode` 既可传客户编号也可传客户名称（AC-1③ 要求两者都能搜）。
 * 🚨 下拉可能虚拟滚动 ⇒ 先输入关键字过滤，再点击第一个可见选项（本项目成文踩坑，见 test.md §7）。
 */
export async function selectCustomer(page: Page, labelOrCode: string) {
  const sel = await customerSelector(page);
  await sel.click();
  await page.waitForTimeout(300);
  // antd Select 带 showSearch 时点击后会聚焦一个可输入的搜索框（可能是同一个元素）
  await page.keyboard.type(labelOrCode, { delay: 20 });
  await page.waitForTimeout(500);
  const option = page.locator('.ant-select-item-option', { hasText: labelOrCode }).first();
  await expect(option, `AC-1③：下拉里搜不到「${labelOrCode}」——若这是未建档客户号，检查候选是否被前端"customerName 为空"误过滤`)
    .toBeVisible({ timeout: 8_000 });
  await option.click();
  await page.waitForTimeout(800);
}

// 🔄 2026-09-07 D-7：客户改为必选，UI 上取消「所有客户」选项 —— `selectAllCustomers` 已随之废弃并移除。
// 需要"当前客户全量"语义的用例改用 `selectCustomer(page, <某个具体客户号>)`。
