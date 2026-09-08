import { expect, Page, APIRequestContext } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

// 🚩 本仓是 Vite ESM 项目，没有 __dirname（AP-43 同族：ESM 下用 CJS 全局会 ReferenceError）
const HERE = path.dirname(fileURLToPath(import.meta.url));

export const BACKEND = process.env.PW_BACKEND_URL || 'http://localhost:8099';
/** 报价模板 · ds 原生 v1.1。🚨 必须显式传 —— auto-defaults 对正泰返回的是「正泰模板1」(LAST_USED)。 */
export const TEMPLATE_ID = '875a5c9f-3579-4c9d-b600-1791ff25afa7';
export const P = 'T260907T-';
export const FG1 = `${P}FG01`;
export const FIXTURE = 'T260907-主文件-CUST0004.xlsx';

export function fixturePath(name = FIXTURE) {
  let cur = HERE;
  for (let i = 0; i < 6; i++) {
    const p = path.join(cur, 'dev-docs', 'task-260907-报价导入建单切ds新表', '测试数据', name);
    if (fs.existsSync(p)) return p;
    cur = path.dirname(cur);
  }
  throw new Error('找不到测试夹具 ' + name);
}

/**
 * 等后端就绪，最多 120s。
 *
 * 🚨 **不是「重跑一下就好」的补丁，是结构性消除一个失败模式。**
 * 实测：本 worktree 的 Quarkus 跑在 dev mode，而后端代理正在**同一个 worktree** 里编译；
 * 它每写一次 `target/classes`，dev mode 就 `Restarting quarkus due to changes in ...`，
 * 期间连接被拒约 10~15s。日志实证：一次跑测期间 8099 **重启了 3 次**。
 * 症状是 `ECONNREFUSED 127.0.0.1:8099`，而测试前后手工 curl 都是通的 ——
 * 典型的「随机挂、每次挂的还不一样」（testing.md §4：先查测试基础设施，再查业务代码）。
 */
export async function waitBackendReady(api: APIRequestContext, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    try {
      // 业务端点返 401 = 应用在跑且鉴权正常（🚫 /q/health 返 404，它不是健康探针）
      const r = await api.get('/api/cpq/components');
      if (r.status() === 401 || r.status() === 200) return;
      last = `HTTP ${r.status()}`;
    } catch (e) {
      last = String(e).slice(0, 120);
    }
    await new Promise((res) => setTimeout(res, 2000));
  }
  throw new Error(`后端 ${BACKEND} 在 ${timeoutMs}ms 内未就绪（最后一次：${last}）。`
    + ' ⚠️ dev mode 会因同 worktree 的并发编译而重启，这是基础设施问题不是产品缺陷。');
}

export async function login(api: APIRequestContext) {
  const r = await api.post('/api/cpq/auth/login', {
    data: { username: 'admin', password: 'Admin@2026' },
  });
  expect(r.status(), `登录失败：${await r.text()}`).toBe(200);
  const s = /CPQ_SESSION=([^;]+)/.exec(r.headers()['set-cookie'] || '')?.[1] || '';
  expect(s, '没拿到 CPQ_SESSION').toBeTruthy();
  return s;
}

export async function chintId(api: APIRequestContext, session: string) {
  // 🚩 page 是 0-based：page=1 拿到的是空的第二页（实测 totalElements=48 全在第 0 页）
  const r = await api.get('/api/cpq/customers?page=0&size=500', {
    headers: { Cookie: `CPQ_SESSION=${session}` },
  });
  const list: any[] = (await r.json()).data?.content ?? [];
  expect(list.length, '客户列表为空（响应形状变了？）').toBeGreaterThan(0);
  const c = list.find((x) => x.code === 'CUST-0004');
  expect(c, '库里没有 CUST-0004（正泰），夹具锚点漂了').toBeTruthy();
  return c.id as string;
}

/** 导入夹具并等到终态 SUCCESS，返回 importRecordId。 */
export async function importFixture(api: APIRequestContext, session: string, customerId: string) {
  const imp = await api.post('/api/cpq/dataset/quote/quotation-import', {
    headers: { Cookie: `CPQ_SESSION=${session}` },
    multipart: {
      customerId,
      file: {
        name: FIXTURE,
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer: fs.readFileSync(fixturePath()),
      },
    },
  });
  expect(imp.status(), `导入失败：${await imp.text()}`).toBe(200);
  const recId = (await imp.json()).data.importRecordId;
  let status = '';
  for (let i = 0; i < 240 && status !== 'SUCCESS' && status !== 'FAILED'; i++) {
    const r = await api.get(`/api/cpq/dataset/quote/quotation-import/${recId}`, {
      headers: { Cookie: `CPQ_SESSION=${session}` },
    });
    status = (await r.json()).data.status;
    if (status === 'PROCESSING') await new Promise((res) => setTimeout(res, 500));
  }
  expect(status, '夹具导入未成功 ⇒ 后面全是空验证').toBe('SUCCESS');
  return recId;
}

/** 建单（显式传模板）+ 物化 + 绑对模板自检。 */
export async function createQuotation(
  api: APIRequestContext, session: string, customerId: string, recId: string, name: string,
) {
  const create = await api.post('/api/cpq/dataset/quote/create-quotation', {
    headers: { Cookie: `CPQ_SESSION=${session}` },
    data: { importRecordId: recId, customerId, name, customerTemplateId: TEMPLATE_ID },
  });
  expect(create.status(), `建单失败：${await create.text()}`).toBe(200);
  const body = (await create.json()).data;
  await api.post(`/api/cpq/quotations/${body.quotationId}/ensure-card-values`, {
    headers: { Cookie: `CPQ_SESSION=${session}`, 'Content-Type': 'application/json' },
    data: {},
  });
  // 🚨 绑错模板的话后面所有页签断言都是无意义的红，先自检
  const q = await api.get(`/api/cpq/quotations/${body.quotationId}`, {
    headers: { Cookie: `CPQ_SESSION=${session}` },
  });
  const qb = (await q.json()).data;
  expect(qb?.customerTemplateId,
    `建出的单没绑 ds 原生模板（拿到 ${qb?.customerTemplateId}）—— 必须显式传 templateId`,
  ).toBe(TEMPLATE_ID);
  return body;
}

/**
 * 打开编辑页并走到 Step 2「添加产品」。
 * 🚩 编辑页落在 5 步向导的 Step 1「选择客户」，页签在 Step 2；
 *    且 **步骤条不可点**（点 .ant-steps-item 无效），必须点「下一步」。
 *    不做这一步，13 个页签会全部「不存在」—— 症状与渲染缺陷无法区分。
 */
export async function gotoStep2(page: Page, quotationId: string) {
  await page.goto(`/quotations/${quotationId}/edit`);
  await page.waitForLoadState('networkidle');
  // antd 会给两字按钮插空格，用正则规避
  const next = page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first();
  await expect(next, '找不到「下一步」按钮').toHaveCount(1);
  await next.click();
  await page.waitForTimeout(6000);
}

/** 取 FG01 的产品卡片。🚩 页签是 button.qt-tab-btn，不是 .ant-tabs-tab。 */
export async function fg01Card(page: Page) {
  const cards = page.locator('div.qt-product-card');
  const n = await cards.count();
  expect(n, '产品卡片数为 0 —— Step 2 没到位或建单没建出明细行').toBeGreaterThan(0);
  for (let i = 0; i < n; i++) {
    if (((await cards.nth(i).innerText().catch(() => '')) || '').includes(FG1)) return cards.nth(i);
  }
  throw new Error(`没找到 ${FG1} 的产品卡片（共 ${n} 张）`);
}

export async function clickTab(card: any, page: Page, tab: string) {
  const btn = card.locator('button.qt-tab-btn', { hasText: tab }).first();
  await expect(btn, `页签「${tab}」不存在`).toHaveCount(1);
  await btn.click();
  await page.waitForTimeout(900);
}
