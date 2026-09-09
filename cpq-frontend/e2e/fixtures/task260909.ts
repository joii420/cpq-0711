/**
 * task-260909「已有产品抽屉数据源收敛」测试夹具
 *
 * 🚫 本文件与两个 spec 的断言全部从 `需求文档.md §③` 的 AC 原文派生，
 *    **不曾读过** `cpq-backend/src/main/java/com/cpq/existingproduct/`、
 *    `cpq-frontend/src/pages/quotation/AddProductModal.tsx`、
 *    `cpq-frontend/src/types/existingProduct.ts`。
 *
 * 三条贯穿全套的纪律（`test.md` §0 / §3）：
 *  1. 🚫 **零硬编码计数**。共享库 `cpq_db_0724` 会漂移（正泰 2662 个料号里 2645 个是
 *     别的会话的性能测试造数），任何绝对数字都会变成"红得像业务回归"的假红。
 *     一律用 `sqlScalar()` 当场取不变量。
 *  2. 🚫 **不许写库存量**。`ds_quote_customer_part` 全程只读。本套唯一的写入面是
 *     自建的报价单，编号一律带 `T260909-` 前缀，`finally` 里按前缀回收。
 *  3. **断言前先断言非空**。空列表让"每行都满足 X"空跑通过，是四类假绿之首。
 */

import { execSync } from 'child_process';
import { createRequire } from 'module';
const require = createRequire(import.meta.url);
import * as nodePath from 'path';
import { fileURLToPath } from 'url';
const __dirname = nodePath.dirname(fileURLToPath(import.meta.url));
import { expect, Locator, Page, APIRequestContext, request } from '@playwright/test';

// ──────────────────────────────────────────────────────────────────────────
// 0. 环境坐标
// ──────────────────────────────────────────────────────────────────────────

/** 被测前端 = worktree 临时 vite。⚠️ 它的 `/api` 由 Vite proxy 转发，**目标未必是 PW_BACKEND_URL**。 */
export const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5174';

/** 被测后端 = worktree 临时实例（已含 B-1~B-5 改动）。 */
export const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';

/**
 * A/B 对照的「改动前」一侧 = 主仓 master 后端（未改动）。
 * 🚨 T1 会硬断言两者 URL 不同 —— 相同就意味着两个请求打到了同一个实例，
 *    那条 A/B 用例整个是空验证，必须硬失败而不是记录"无差异"。
 */
export const MASTER_BACKEND_URL = process.env.PW_MASTER_BACKEND_URL || 'http://localhost:8081';

export const DB = {
  host: '10.177.152.12',
  port: '5432',
  user: 'postgres',
  db: 'cpq_db_0724',
  password: 'joii5231',
};

/** 造数前缀 —— 跨会话防串扰的唯一依据（`test.md §3` 造数纪律）。 */
export const TEST_PREFIX = 'T260909-';

/** 本套用到的三个客户（AC-1 / AC-2 / AC-12）。code 写死是身份不是计数，允许。 */
export const CUSTOMERS = {
  /** 正泰 —— 核心症状客户，约 2662 个料号 */
  CHINT: 'CUST-0004',
  /** 罗克韦尔 —— 定点字段验证与选配标签验证都在这里 */
  ROCKWELL: 'CUST-0001',
  /** 苏州西门子 —— dqcp 实测 0 行，AC-12 空态前置（无需造数） */
  SIEMENS: '8000137',
} as const;

/** AC-3 规定的 7 列，顺序敏感。 */
export const EXPECTED_COLUMNS = [
  '来源', '客户产品编号', '客户图号', '客户物料名', '销售料号', '品名', '规格',
] as const;

// ──────────────────────────────────────────────────────────────────────────
// 1. SQL 不变量取值（只读）
// ──────────────────────────────────────────────────────────────────────────

/**
 * 跑一条只读 SQL，返回单值。
 *
 * 🚨 **只允许 SELECT**。这里做一次硬拦截，是为了让"清库/改存量"这类红线操作
 *    在夹具层就跑不出去 —— `CLAUDE.md §3.2`「测试也算」。
 */
export function sqlRaw(sql: string): string {
  const trimmed = sql.trim().replace(/;$/, '');
  if (!/^(SELECT|WITH|EXPLAIN)\b/i.test(trimmed)) {
    throw new Error(
      `[task260909] 夹具只允许只读 SQL，拒绝执行：${trimmed.slice(0, 120)}\n` +
      `（DELETE/UPDATE/TRUNCATE/DROP 属 CLAUDE.md §3.2 红线，测试无批准权）`,
    );
  }
  const out = execSync(
    `PGPASSWORD=${DB.password} psql -h ${DB.host} -p ${DB.port} -U ${DB.user} -d ${DB.db} ` +
    `-t -A -P pager=off -c "${trimmed.replace(/"/g, '\\"')}"`,
    { encoding: 'utf-8', shell: '/bin/bash' },
  );
  return out.trim();
}

/**
 * 跑一条**只读** git 命令（仅允许 diff / rev-parse / ls-files），返回 stdout。
 * 🚫 白名单同 sqlRaw：历史销毁类命令（reset/push -f/clean 等）属 §3.2 红线，夹具层直接拒。
 */
export function gitRaw(args: string): string {
  if (!/^(diff|rev-parse|ls-files)\b/.test(args.trim())) {
    throw new Error(`[task260909] 夹具只允许只读 git 命令，拒绝执行：git ${args}`);
  }
  const path = require('path');
  const repoRoot = path.resolve(__dirname, '..', '..', '..');
  return execSync(`git -C "${repoRoot}" ${args}`, { encoding: 'utf-8', shell: '/bin/bash' });
}

/** 该路径在本分支相对 master 的 diff 行数。 */
export function gitDiffLines(file: string): number {
  const out = gitRaw(`diff master...HEAD -- "${file}"`);
  return out.trim() === '' ? 0 : out.trim().split('\n').length;
}

/** 3D 模型管理的全部源码文件（AC-8 的「无回归」主断言就打在它们上）。 */
export const MODEL_CONFIG_FILES = [
  'cpq-backend/src/main/java/com/cpq/modelconfig/dto/ModelConfigDTO.java',
  'cpq-backend/src/main/java/com/cpq/modelconfig/entity/ModelConfigFile.java',
  'cpq-backend/src/main/java/com/cpq/modelconfig/entity/ModelConfig.java',
  'cpq-backend/src/main/java/com/cpq/modelconfig/resource/ModelConfigResource.java',
  'cpq-backend/src/main/java/com/cpq/modelconfig/service/ModelConfigService.java',
  'cpq-frontend/src/pages/config/ModelConfigManagement.tsx',
  'cpq-frontend/src/services/modelConfigService.ts',
  'cpq-frontend/src/types/modelConfig.ts',
] as const;

/** 本次**确实改过**的文件，用作 git diff 判据的阳性对照。 */
export const CHANGED_FILE_CONTROL = 'cpq-frontend/src/pages/quotation/AddProductModal.tsx';

/** 取一个整数不变量。用于所有"当场对账"的断言。 */
export function sqlScalar(sql: string): number {
  const raw = sqlRaw(sql);
  const n = Number(raw);
  if (!Number.isFinite(n)) {
    throw new Error(`[task260909] SQL 未返回数字：${JSON.stringify(raw)}\nSQL: ${sql}`);
  }
  return n;
}

/** 某客户在 dqcp 里的**料号级**总数 —— 接口经 DISTINCT ON 去重后应恰好等于它。 */
export function distinctMaterialCount(customerNo: string): number {
  return sqlScalar(
    `SELECT count(DISTINCT material_no) FROM ds_quote_customer_part WHERE customer_no='${customerNo}'`,
  );
}

/** 解析客户 UUID（不写死 id，防库重建后 id 漂移）。 */
export function customerIdOf(code: string): string {
  const id = sqlRaw(`SELECT id FROM customer WHERE code='${code}'`);
  expect(id, `customer 表里找不到 code=${code} 的客户`).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
  );
  return id;
}

// ──────────────────────────────────────────────────────────────────────────
// 2. 接口访问
// ──────────────────────────────────────────────────────────────────────────

/** 建一个已登录的 APIRequestContext，baseURL 指向给定后端。 */
export async function apiContext(baseURL: string, username = 'admin', password = 'Admin@2026') {
  const ctx = await request.newContext({ baseURL, ignoreHTTPSErrors: true });
  const login = await ctx.post('/api/cpq/auth/login', { data: { username, password } });
  expect(login.ok(), `登录 ${baseURL} 失败：${login.status()} ${await login.text()}`).toBe(true);
  return ctx;
}

/**
 * 🚨 实例正身断言：确认某个后端 URL 上跑的**确实是**你以为的那份代码。
 *
 * 📌 2026-09-09 事故（本函数因此存在）：主线给的 `PW_BACKEND_URL=8098` 实际被
 *    **另一个 worktree（repair-260908-tab-dup-rows）** 的 Quarkus 占用（03:46 起），
 *    主线自己起的实例根本没绑上端口（日志有 `Port 8098 seems to be in use`）。
 *    探活只看到 401 就放行了 —— 而 **401 只证明「有个 Quarkus 在跑、鉴权正常」，
 *    证明不了「它跑的是我的代码」**。结果 A/B 两侧都是旧代码，
 *    `beforeTotal=1, afterTotal=1`，看起来像"修复没生效"的产品缺陷。
 *
 * 🔑 判据用**字段集**而不是端口号：字段集是代码形态的直接证据，端口只是地址。
 *    「两个端口不同」完全可以「两个都不是你要测的实例」。
 *
 * @param variant 'changed' = 本次改动后（含 customerDrawingNo，无 has3d/thumbnailUrl）
 *                'unchanged' = 改动前的 master（反之）
 */
export async function assertBackendVariant(
  ctx: APIRequestContext, url: string, quotationId: string, variant: 'changed' | 'unchanged',
) {
  const pg = await fetchExistingProducts(ctx, quotationId, { page: 0, size: 1 });
  expect(pg.content.length,
    `实例正身无法判定：${url} 对该报价单返回 0 行，取不到字段集。请换一个有数据的客户的报价单。`,
  ).toBeGreaterThan(0);
  const keys = Object.keys(pg.content[0]).sort();
  const has = (k: string) => keys.includes(k);
  const diag =
    `\n  URL = ${url}\n  期望形态 = ${variant}\n  实际字段集 = ${JSON.stringify(keys)}\n` +
    `  🚨 环境指向错误 —— 本轮结论**全部无效**，不要按产品缺陷解读，先修环境。`;

  if (variant === 'changed') {
    expect(has('customerDrawingNo'), `${url} 不是「改动后」实例：响应缺 customerDrawingNo。${diag}`).toBe(true);
    expect(has('has3d'), `${url} 不是「改动后」实例：响应仍含 has3d（本次应删）。${diag}`).toBe(false);
    expect(has('thumbnailUrl'), `${url} 不是「改动后」实例：响应仍含 thumbnailUrl（本次应删）。${diag}`).toBe(false);
  } else {
    expect(has('has3d'), `${url} 不是「改动前」实例：响应缺 has3d，看起来已是改动后的代码。${diag}`).toBe(true);
    expect(has('customerDrawingNo'), `${url} 不是「改动前」实例：响应已含 customerDrawingNo。${diag}`).toBe(false);
  }
  console.log(`[task260909] 实例正身 ✅ ${url} = ${variant}；字段集 = ${JSON.stringify(keys)}`);
  return keys;
}

export interface ExistingProductRow {
  materialNo: string;
  customerProductNo?: string | null;
  customerProductNos?: string[] | null;
  customerDrawingNo?: string | null;
  customerMaterialName?: string | null;
  productName?: string | null;
  spec?: string | null;
  source?: string | null;
  configProductType?: string | null;
  [k: string]: unknown;
}

export interface ExistingProductPage {
  content: ExistingProductRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * 打 `GET /quotations/{id}/existing-products`。
 * 🚫 断言字段名是 `content` / `totalElements`（api.md §1.2），不是 items / total。
 */
export async function fetchExistingProducts(
  ctx: APIRequestContext,
  quotationId: string,
  query: Record<string, string | number> = {},
): Promise<ExistingProductPage> {
  const qs = new URLSearchParams(
    Object.entries({ page: 0, size: 20, ...query }).map(([k, v]) => [k, String(v)]),
  ).toString();
  const res = await ctx.get(`/api/cpq/quotations/${quotationId}/existing-products?${qs}`);
  expect(res.status(), `existing-products 返回非 200：${await res.text()}`).toBe(200);
  const body = await res.json();
  const data = body?.data ?? body;
  expect(data, `响应体缺 data：${JSON.stringify(body).slice(0, 300)}`).toBeTruthy();
  expect(Array.isArray(data.content), `响应 data.content 不是数组（字段名应为 content 而非 items）`)
    .toBe(true);
  expect(typeof data.totalElements, `响应缺 totalElements（不是 total）`).toBe('number');
  return data as ExistingProductPage;
}

// ──────────────────────────────────────────────────────────────────────────
// 3. 造数与回收（唯一的写入面）
// ──────────────────────────────────────────────────────────────────────────

const createdQuotationIds: string[] = [];

/** 客户的产品分类 id；没有则返回空串。 */
export function categoryIdOf(customerCode: string): string {
  return sqlRaw(`SELECT COALESCE(product_category_id::text,'') FROM customer WHERE code='${customerCode}'`);
}

/**
 * 取该客户「分类轴」下可用的报价模板 id；没有则返回空串。
 *
 * 匹配口径 = 客户 × 客户的产品分类 × QUOTATION × PUBLISHED
 * （三模板统一产品分类轴，task-260712）。🚫 不硬编码模板 id —— 共享库里模板会增删。
 */
export function quoteTemplateIdOf(customerCode: string): string {
  return sqlRaw(
    `SELECT t.id::text FROM template t JOIN customer c ON c.id = t.customer_id ` +
    `WHERE c.code='${customerCode}' AND t.category_id = c.product_category_id ` +
    `AND t.template_kind='QUOTATION' AND t.status='PUBLISHED' ` +
    `ORDER BY t.is_default DESC, t.updated_at DESC LIMIT 1`,
  );
}

/**
 * 🚫 从候选里排除的客户前缀：`T2609R*` 是 task-260907R 系列（2026-09-09 当天所建），
 *    相关活动可能仍在进行，不碰别人的在途夹具（主线 2026-09-09 指示）。
 */
const EMPTY_CUSTOMER_EXCLUDE_PREFIX = 'T2609R';

/**
 * 挑一个「dqcp 零行 **且** 有可用报价模板」的客户 —— AC-12 的原始场景前置。
 *
 * 🚨 为什么不能用苏州西门子：实测它**一个模板都没有**（任何分类、任何状态），
 *    而 Step1 的「下一步」在缺产品分类或报价模板时是禁用的 ⇒ 进不去 Step2、开不了抽屉。
 *    ⚠️ 注意 AC-2 仍然用它 —— 那是**纯接口用例不进 Step2**，两者不矛盾。
 *
 * 🚫 运行时解析而不写死 code：这批是别的会话的 E2E 夹具，随时可能被清理。
 * 排序取 `created_at` 最早的，偏向更稳定的老夹具。
 *
 * 📌 本函数会打印完整候选清单与被排除数 —— 让报告能看出它**实际选了谁、排掉了谁**，
 *    而不是只看到一个 code（`testing.md §4.4`：判据要能被检查，不能只给结论）。
 */
export function pickEmptyDataCustomerWithTemplate(): string {
  const base =
    `FROM customer c ` +
    `JOIN template t ON t.customer_id=c.id AND t.category_id=c.product_category_id ` +
    `  AND t.template_kind='QUOTATION' AND t.status='PUBLISHED' ` +
    `WHERE NOT EXISTS (SELECT 1 FROM ds_quote_customer_part d WHERE d.customer_no=c.code) `;

  // 排除前后各数一次，把「这条排除规则今天到底排掉了几个」显式记录下来
  const total = sqlScalar(`SELECT count(DISTINCT c.code) ${base}`);
  const kept = sqlScalar(
    `SELECT count(DISTINCT c.code) ${base} AND c.code NOT LIKE '${EMPTY_CUSTOMER_EXCLUDE_PREFIX}%'`,
  );
  const candidates = sqlRaw(
    `SELECT string_agg(DISTINCT c.code, ',') ${base} AND c.code NOT LIKE '${EMPTY_CUSTOMER_EXCLUDE_PREFIX}%'`,
  );
  console.log(
    `[task260909] AC-12 候选客户：符合条件 ${total} 个，排除 ${EMPTY_CUSTOMER_EXCLUDE_PREFIX}* 后剩 ${kept} 个` +
    `（本次排除 ${total - kept} 个）；清单 = ${candidates || '<空>'}`,
  );

  const picked = sqlRaw(
    `SELECT c.code ${base} AND c.code NOT LIKE '${EMPTY_CUSTOMER_EXCLUDE_PREFIX}%' ` +
    `GROUP BY c.code, c.created_at ORDER BY c.created_at, c.code LIMIT 1`,
  );
  // 兜底断言：无论排除条件是否命中，选出来的都不许是被排除前缀
  expect(picked.startsWith(EMPTY_CUSTOMER_EXCLUDE_PREFIX),
    `选中的客户 ${picked} 命中了排除前缀 ${EMPTY_CUSTOMER_EXCLUDE_PREFIX} —— 排除条件没生效`,
  ).toBe(false);
  return picked;
}

/**
 * 建一张 DRAFT 报价单，name 带 `T260909-` 前缀。
 *
 * @param needStep2 该单是否要走 UI 进 Step2。
 *   true（默认）⇒ **必须**带产品分类 + 报价模板，否则 Step1 的「下一步」是禁用的
 *   （tooltip「请先填写产品分类和报价模板」），所有 UI 用例会倒在"抽屉打不开"，
 *   而那个现象**长得和产品缺陷一模一样**，实际是造数不完整。
 *   取不到就**硬失败并说明原因**，🚫 不静默建一张进不去的单。
 *   false ⇒ 只走接口的用例（如 AC-2 的苏州西门子），无模板也能建、也够用。
 */
export async function createTestQuotation(
  ctx: APIRequestContext, customerCode: string, tag: string, needStep2 = true,
): Promise<{ id: string; quotationNumber: string }> {
  const customerId = customerIdOf(customerCode);
  const categoryId = categoryIdOf(customerCode);
  const templateId = quoteTemplateIdOf(customerCode);

  if (needStep2) {
    expect(categoryId,
      `客户 ${customerCode} 没有产品分类（customer.product_category_id 为空）—— ` +
      `Step1「下一步」会被禁用，造出来的单进不去 Step2。请换客户或补分类。`,
    ).not.toBe('');
    expect(templateId,
      `客户 ${customerCode} 名下没有可用的报价模板` +
      `（分类=${categoryId} / QUOTATION / PUBLISHED 命中 0 条）—— ` +
      `Step1「下一步」会被禁用，无法造出可进入 Step2 的单。\n` +
      `⚠️ 这是数据前提缺失，不是产品缺陷，别按缺陷报。`,
    ).not.toBe('');
  }

  const res = await ctx.post('/api/cpq/quotations', {
    data: {
      customerId,
      name: `${TEST_PREFIX}${tag}-${Date.now()}`,
      quoteType: 'STANDARD',
      // 🚨 请求体字段名是 `categoryId`，**不是** `productCategoryId`。
      //    `product_category_id` 是 `customer` 表的**列名**（客户身上挂的分类），
      //    建单 DTO（CreateQuotationRequest）用的是 `categoryId` ——
      //    **同一个概念在两层用了不同的名字**。按 DB 列名推请求体字段名会推错，
      //    而错法正是"未知字段被静默忽略"：单照样建、照样 200，列却是空的。
      //    下面的「建完回查落库」就是为了让这种错在造数阶段被抓住。
      ...(categoryId ? { categoryId } : {}),
      ...(templateId ? { customerTemplateId: templateId } : {}),
    },
  });
  expect(res.ok(), `建报价单失败（客户 ${customerCode}）：${res.status()} ${await res.text()}`).toBe(true);
  const d = (await res.json()).data;
  createdQuotationIds.push(d.id);

  // 🚨 建完必须回查落库，不能只看 200。
  //    请求体字段名若与后端契约不符，多数框架是**静默忽略**未知字段 —— 单照样建出来、
  //    照样返 200，只是两个字段是空的，然后 UI 用例倒在"抽屉打不开"。
  //    那个现象和产品缺陷无法区分，所以在造数这一步就断死（`testing.md §5.5` 只验了契约一侧）。
  if (needStep2) {
    const landed = sqlRaw(
      `SELECT COALESCE(product_category_id::text,'') || '|' || COALESCE(customer_template_id::text,'') ` +
      `FROM quotation WHERE id='${d.id}'`,
    );
    const [gotCat, gotTpl] = landed.split('|');
    expect(gotCat,
      `造数自检失败：报价单 ${d.quotationNumber} 的 product_category_id 没落库（期望 ${categoryId}）—— ` +
      `请求体字段名可能与后端契约不符而被静默忽略。这不是产品缺陷，是夹具要修。`,
    ).toBe(categoryId);
    expect(gotTpl,
      `造数自检失败：报价单 ${d.quotationNumber} 的 customer_template_id 没落库（期望 ${templateId}）—— ` +
      `同上，字段名被静默忽略。修夹具，🚫 别按缺陷报。`,
    ).toBe(templateId);
  }

  console.log(
    `[task260909] 建单 ${d.quotationNumber} (${d.id}) 客户=${customerCode} tag=${tag} ` +
    `分类=${categoryId || '<无>'} 模板=${templateId || '<无>'} needStep2=${needStep2}`,
  );
  return { id: d.id, quotationNumber: d.quotationNumber };
}

/**
 * 回收本次自建的报价单。
 *
 * 🚨 按 `CLAUDE.md §3.2` 三步前置执行（用户已就"限定前缀的删除"批准，但前置一步不省）：
 *  ① 先量化影响面 —— 打印将删除的条数与清单
 *  ② 逐条核对确实是本进程自建的（id 来自 createTestQuotation 的登记）
 *  ③ 走应用自己的 DELETE 端点，而不是裸 SQL —— 级联与业务校验由后端负责
 *
 * 🚫 不删任何未登记的行；🚫 不碰 `ds_quote_customer_part` 一行。
 */
export async function cleanupTestQuotations(ctx: APIRequestContext) {
  if (createdQuotationIds.length === 0) {
    console.log('[task260909] 回收：本次未自建任何报价单，无需清理');
    return;
  }
  console.log(`[task260909] 回收前量化影响面：将删除 ${createdQuotationIds.length} 张自建报价单`);
  for (const id of createdQuotationIds) {
    // ② 删之前再确认一次它确实带我们的前缀 —— 防 id 被串改后误删他人单据
    const name = sqlRaw(`SELECT name FROM quotation WHERE id='${id}'`);
    if (!name) { console.log(`[task260909] 回收：${id} 已不存在，跳过`); continue; }
    if (!name.startsWith(TEST_PREFIX)) {
      console.warn(`[task260909] 🚨 回收中止：${id} 的 name=${JSON.stringify(name)} 不带 ${TEST_PREFIX} 前缀，不删，报主线`);
      continue;
    }
    const res = await ctx.delete(`/api/cpq/quotations/${id}`);
    console.log(`[task260909] 回收 ${id} (${name}) → ${res.status()}`);
  }
  createdQuotationIds.length = 0;
}

// ──────────────────────────────────────────────────────────────────────────
// 4. 抽屉 UI 操作
// ──────────────────────────────────────────────────────────────────────────

/** 打开编辑页并走到 Step 2。步骤条不可点，必须点「下一步」（t260907-helpers 已验证）。 */
export async function gotoStep2(page: Page, quotationId: string) {
  await page.goto(`/quotations/${quotationId}/edit`);
  await page.waitForLoadState('networkidle');
  // antd 给两字/三字按钮插空格，用正则规避（`cpq-playwright-selector-pitfalls`）
  const next = page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first();
  await expect(next, '编辑页找不到「下一步」按钮 —— 这是选择器/页面未加载问题，不是产品 bug').toBeVisible({ timeout: 60_000 });
  await next.click();
  await page.waitForTimeout(4000);
}

/**
 * 打开「添加产品 ▾ → 从已有产品添加」抽屉。
 *
 * 🚨 `clickSearch` 默认 true：**抽屉打开时列表默认是空的，必须点「查询」才加载**
 *    （`fixtures/task260901-draft.ts:441` 已实证）。
 *    AC-12 依赖这一点 —— 不点就断言空态，"没查过的空"和"查了确实没有的空"无法区分，
 *    那是一条恒真的假绿（需求文档 AC-12 已把这步写进前置）。
 */
export async function openExistingProductDrawer(page: Page, clickSearch = true): Promise<Locator> {
  const addBtn = page.getByRole('button', { name: /添加产品/ }).first();
  await expect(addBtn, '「添加产品」按钮应可见').toBeVisible({ timeout: 60_000 });
  await addBtn.click();
  await page.waitForTimeout(800);

  const items = page.locator('.ant-dropdown-menu-item:visible');
  const texts = await items.allInnerTexts();
  const entry = items.filter({ hasText: /已有产品/ }).first();
  await expect(entry, `未找到「从已有产品添加」菜单项；实际下拉项 = ${JSON.stringify(texts)}`)
    .toBeVisible({ timeout: 15_000 });
  await entry.click();

  const drawer = page.locator('.ant-drawer').last();
  await expect(drawer, '「从已有产品添加」抽屉应打开').toBeVisible({ timeout: 60_000 });

  if (clickSearch) await clickDrawerSearch(page, drawer);
  return drawer;
}

/** 点抽屉里的「查询」。antd 两字按钮会渲染成「查 询」，用正则匹配。 */
export async function clickDrawerSearch(page: Page, drawer: Locator) {
  const btn = drawer.getByRole('button', { name: /^查\s*询$/ }).first();
  await expect(btn, '抽屉应有「查询」按钮（找不到 = 选择器问题，先排除再谈产品 bug）')
    .toBeVisible({ timeout: 30_000 });
  await btn.click();
  await page.waitForTimeout(1500);
}

/** 读抽屉表格的可见表头文本（去掉勾选列的空表头）。 */
export async function readDrawerHeaders(drawer: Locator): Promise<string[]> {
  const ths = drawer.locator('.ant-table-thead th');
  const raw = await ths.allInnerTexts();
  return raw.map((t) => t.replace(/\s+/g, '')).filter((t) => t.length > 0);
}

/** 抽屉表格的一行，按表头文本取值。 */
export type DrawerRow = Record<string, string>;

/**
 * 读抽屉表格的数据行，返回 `{ 表头: 单元格文本 }`。
 * 用表头映射而不是下标写死 —— 列顺序变了断言会指出真正的问题（AC-3），
 * 而不是把值错位地读进别的列（AP-54 族）。
 */
export async function readDrawerRows(drawer: Locator): Promise<DrawerRow[]> {
  const headers = await readDrawerHeaders(drawer);
  const rows = drawer.locator('.ant-table-row');
  const n = await rows.count();
  const out: DrawerRow[] = [];
  for (let i = 0; i < n; i++) {
    const cells = await rows.nth(i).locator('td').allInnerTexts();
    // 勾选列在最前且表头为空，用"从右对齐"消掉它：取末尾 headers.length 个单元格。
    // 🚨 该对齐只在「多出来的无名列都在最左」时成立。若表格尾部还有一个无表头的列
    //    （如「操作」），右对齐会整体错位一格，把值读进相邻列 —— 那是 AP-54 族的静默错读，
    //    比报错更难发现。所以这里硬校验多出来的列数：0（无勾选列）或 1（只有勾选列）。
    const extra = cells.length - headers.length;
    expect(extra,
      `行 ${i + 1} 的单元格数(${cells.length}) 与可见表头数(${headers.length}) 之差为 ${extra}，` +
      `超出预期的 0/1（0=无勾选列，1=仅最左勾选列）。表格结构与夹具假设不符，` +
      `继续按右对齐读会静默错列 —— 先修夹具，🚫 不要当成产品缺陷。`,
    ).toBeLessThanOrEqual(1);
    expect(extra, `行 ${i + 1} 的单元格数少于表头数，无法对齐`).toBeGreaterThanOrEqual(0);
    const tail = cells.slice(extra);
    const row: DrawerRow = {};
    headers.forEach((h, idx) => { row[h] = (tail[idx] ?? '').replace(/\n/g, ' ').trim(); });
    out.push(row);
  }
  return out;
}

/**
 * 往抽屉的某个过滤框填值并回车。
 *
 * 🚨 找不到过滤框时**打印全部 placeholder 再硬失败** —— 让"选择器没对上"
 *    和"产品没这个过滤框"在报告里可区分。四个 Playwright 已知坑都表现为 timeout，
 *    不给诊断信息就会被误报成产品 bug（`test.md §4.4`）。
 */
export async function fillDrawerFilter(page: Page, drawer: Locator, columnLabel: string, value: string) {
  const inputs = drawer.locator('input:visible');
  const n = await inputs.count();
  const placeholders: string[] = [];
  let target: Locator | null = null;
  for (let i = 0; i < n; i++) {
    const ph = (await inputs.nth(i).getAttribute('placeholder')) ?? '';
    placeholders.push(ph);
    if (!target && ph.replace(/\s+/g, '').includes(columnLabel)) target = inputs.nth(i);
  }
  if (!target) {
    throw new Error(
      `[task260909] 抽屉里找不到「${columnLabel}」过滤框。\n` +
      `实际可见 input 的 placeholder = ${JSON.stringify(placeholders)}\n` +
      `⚠️ 这是选择器/实现差异，先排除它再判定产品 bug。`,
    );
  }
  await target.fill('');
  await target.fill(value);
  await target.press('Enter');
  await page.waitForTimeout(1500);
}

/** 清空某个过滤框并回车。 */
export async function clearDrawerFilter(page: Page, drawer: Locator, columnLabel: string) {
  await fillDrawerFilter(page, drawer, columnLabel, '');
}

/** 读分页器当前页码；无分页器时返回 null。 */
export async function readPagerCurrent(drawer: Locator): Promise<number | null> {
  const active = drawer.locator('.ant-pagination-item-active');
  if (await active.count() === 0) return null;
  return Number((await active.first().innerText()).trim());
}

/** 读分页器 total（antd 的 showTotal 文案形如「共 2662 条」）。 */
export async function readPagerTotal(drawer: Locator): Promise<number | null> {
  const text = await drawer.innerText();
  const m = text.match(/共\s*([\d,]+)\s*条/);
  return m ? Number(m[1].replace(/,/g, '')) : null;
}

/** 翻到第 N 页。 */
export async function gotoPage(page: Page, drawer: Locator, n: number) {
  const item = drawer.locator(`.ant-pagination-item-${n}`).first();
  await expect(item, `分页器没有第 ${n} 页`).toBeVisible({ timeout: 20_000 });
  await item.click();
  await page.waitForTimeout(1500);
}

/** 勾选第 i 行（0-based）。antd 真正可点的是 .ant-checkbox-input，裸 input 被样式盖住。 */
export async function checkRow(page: Page, drawer: Locator, i: number) {
  const row = drawer.locator('.ant-table-row').nth(i);
  await expect(row, `抽屉没有第 ${i + 1} 行`).toBeVisible({ timeout: 20_000 });
  const cb = row.locator('.ant-checkbox-input').first();
  if (await cb.count() > 0) {
    await cb.check({ force: true }).catch(async () => {
      await row.locator('.ant-checkbox').first().click({ force: true });
    });
  } else {
    await row.click();
  }
  await page.waitForTimeout(400);
}

/** 点「加入报价单」并等抽屉关闭。 */
export async function confirmAdd(page: Page, drawer: Locator) {
  const btn = drawer.getByRole('button', { name: /加入报价单/ }).last();
  await expect(btn, '「加入报价单」按钮应可点（未启用通常是没勾选成功）').toBeEnabled({ timeout: 30_000 });
  await btn.click();
  await expect(drawer, '加入后抽屉应关闭').toBeHidden({ timeout: 60_000 });
  await page.waitForTimeout(3000);
}

// ──────────────────────────────────────────────────────────────────────────
// 5. 证据归档
// ──────────────────────────────────────────────────────────────────────────

/** 证据目录相对**仓库根**的路径（🚫 不留 test-results/，那目录下一轮开跑会被清空）。 */
export const EVIDENCE_REL = 'dev-docs/task-260909-已有产品抽屉数据源收敛/证据';

/**
 * 解析证据目录的绝对路径，并**自检落点**。
 *
 * 🚨 这个自检是有来历的：上一版写成 `resolve(here, '..', '../dev-docs/...')`，
 *    `here` 是 `cpq-frontend/e2e/fixtures/`，两层 `..` 只到 `cpq-frontend/`，
 *    于是截图被静默写进 `cpq-frontend/dev-docs/`，而去仓库根找的人**什么都看不到**。
 *    **它不报错** —— 这正是最需要守卫的那一类错误：写错目录不该只靠肉眼发现。
 *
 * 判据用「仓库根同时含 cpq-frontend 与 cpq-backend」这个结构特征，
 * 比数 `..` 的层数稳（层数会随文件移动静默失效，结构特征不会）。
 */
export async function evidenceDir(): Promise<string> {
  const fs = await import('fs');
  const path = await import('path');
  const { fileURLToPath } = await import('url');
  const here = path.dirname(fileURLToPath(import.meta.url));      // …/cpq-frontend/e2e/fixtures
  const repoRoot = path.resolve(here, '..', '..', '..');           // …/<worktree 根>

  // ① 结构自检：这确实是仓库根吗
  for (const marker of ['cpq-frontend', 'cpq-backend', 'dev-docs']) {
    expect(fs.existsSync(path.join(repoRoot, marker)),
      `证据目录落点自检失败：推导出的仓库根 ${repoRoot} 下没有 ${marker}/ —— ` +
      `路径层数算错了，截图会被静默写到错地方（不报错，最难发现）`,
    ).toBe(true);
  }
  const dir = path.join(repoRoot, EVIDENCE_REL);

  // ② 反向自检：绝对不能落在 cpq-frontend 里面（那正是上一版的 bug 形态）
  expect(dir.includes(`${path.sep}cpq-frontend${path.sep}`),
    `证据目录落点自检失败：${dir} 落在 cpq-frontend/ 内部，应落在仓库根的 dev-docs/ 下`,
  ).toBe(false);

  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

/** 截图并归档到任务目录，落点每次都自检。 */
export async function shot(page: Page, name: string) {
  const path = await import('path');
  const dir = await evidenceDir();
  const file = path.join(dir, `${name}.png`);
  await page.screenshot({ path: file, fullPage: false });
  console.log(`[task260909] 证据截图 → ${file}`);
  return file;
}
