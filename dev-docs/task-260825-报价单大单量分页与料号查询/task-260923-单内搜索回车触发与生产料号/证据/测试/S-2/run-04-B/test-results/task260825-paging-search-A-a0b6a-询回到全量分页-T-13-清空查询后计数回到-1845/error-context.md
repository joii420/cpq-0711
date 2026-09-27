# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: task260825-paging-search.spec.ts >> AC-13: 清空查询回到全量分页 >> T-13 清空查询后计数回到 1845
- Location: e2e/task260825-paging-search.spec.ts:342:3

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: locator('.ant-segmented').first()
Expected: visible
Timeout: 60000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 60000ms
  - waiting for locator('.ant-segmented').first()

```

# Test source

```ts
  80  | async function pgbarText(page: Page): Promise<string> {
  81  |   return (await page.locator('.qt-pgbar').first().innerText().catch(() => '')).replace(/\n/g, ' ');
  82  | }
  83  | 
  84  | /** 输入后按回车（task-260923：回车才触发搜索）。 */
  85  | async function submitSearch(page: Page, text: string) {
  86  |   const input = searchInput(page);
  87  |   await expect(input, '查询输入框应可见').toBeVisible({ timeout: 10000 });
  88  |   await input.fill(text);
  89  |   await input.press('Enter');
  90  |   await page.waitForTimeout(600);
  91  | }
  92  | 
  93  | /** 只读 SQL：样本单客户的产品分类名称（task-260923 D-3 选分类用；不硬编码）。 */
  94  | function queryCustomerCategoryName(quotationId: string): string {
  95  |   return execSync(
  96  |     `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -t -A -c "SELECT pc.name FROM quotation q JOIN customer c ON c.id=q.customer_id JOIN product_category pc ON pc.id=c.product_category_id WHERE q.id='${quotationId}';"`,
  97  |     { encoding: 'utf-8', shell: '/bin/bash' }
  98  |   ).trim();
  99  | }
  100 | 
  101 | /** D-3 证据：每条用例是否需要手动选产品分类，逐行追加到证据目录。 */
  102 | function recordCategoryDecision(entry: Record<string, unknown>) {
  103 |   const line = JSON.stringify({ at: new Date().toISOString(), test: test.info().title, ...entry });
  104 |   console.log(`[D-3] ${line}`);
  105 |   fs.appendFileSync(path.join(SHOT_DIR, 'D-3-category-select.jsonl'), line + '\n');
  106 | }
  107 | 
  108 | /** 表单项里第一个下拉当前显示的文字（取不到返回 ''）。label 用正则精确匹配，避免「报价模板 客户专属」误中「客户」。 */
  109 | async function selectShownText(page: Page, label: RegExp): Promise<string> {
  110 |   const item = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: label }) }).filter({ has: page.locator('.ant-select') }).first();
  111 |   return ((await item.locator('.ant-select').first().innerText({ timeout: 1000 }).catch(() => '')) || '').replace(/\s+/g, ' ').trim();
  112 | }
  113 | 
  114 | /** Step1 各关键字段当前显示值（写进 D-3 记录用）。 */
  115 | async function step1Fields(page: Page) {
  116 |   const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
  117 |   return {
  118 |     headerHasQuotationNo: await page.getByText(SEARCH_SAMPLE_QUOTATION_NO).first().isVisible().catch(() => false),
  119 |     customerShown: await selectShownText(page, /^\s*客户\s*$/),
  120 |     categoryShown: await selectShownText(page, /^\s*产品分类\s*$/),
  121 |     quoteTemplateShown: await selectShownText(page, /报价模板/),
  122 |     nextDisabled: await nextBtn.isDisabled().catch(() => null),
  123 |     nextTitle: await nextBtn.getAttribute('title').catch(() => null),
  124 |   };
  125 | }
  126 | 
  127 | /**
  128 |  * 打开编辑页并进入 Step2（本 spec 专用，不改共享夹具的 openEditStep2）。
  129 |  *
  130 |  * run-04 测试手法（主线 2026-09-24 裁决，不改 AC、不选模板 / 客户、不重开页面）：
  131 |  *   0. 先在同一个 page 里只读打开该单**详情页**，等分页栏 [data-testid="task260825-paging-bar"] 与产品卡片出现
  132 |  *      （主线单次观测：先开详情页后，编辑页「下一步」292ms 即可点；未证实，本轮即在验证它）；
  133 |  *   1. 再打开编辑页，轮询「下一步」可点并能点下去（≤120s；被加载遮罩挡住时继续轮询，不重开页面）；
  134 |  *   2. 可点即点，进 Step2 后照原用例执行；
  135 |  *   3. 120s 仍点不下去 ⇒ 记录后把该条判为「未执行（Step1 既有问题）」（test.skip），不重试。
  136 |  *   已删除 run-03 的「页头单号 + 客户框」判据（该页这两处恒不显示值，主线已确认）。
  137 |  *   每条用例的详情页耗时 / Step1 耗时 / 报价模板显示值写进 D-3-category-select.jsonl（文件名沿用）。
  138 |  */
  139 | async function openStep2WithCategoryIfNeeded(page: Page) {
  140 |   // 0) 详情页预热（只读守卫已在外层装好）
  141 |   const d0 = Date.now();
  142 |   await page.goto(`/quotations/${QID}`);
  143 |   const detailOk = await Promise.all([
  144 |     page.locator('[data-testid="task260825-paging-bar"]').first().waitFor({ state: 'visible', timeout: 120_000 }),
  145 |     page.locator('.qt-product-card').first().waitFor({ state: 'visible', timeout: 120_000 }),
  146 |   ]).then(() => true, () => false);
  147 |   const detailMs = Date.now() - d0;
  148 |   if (!detailOk) {
  149 |     recordCategoryDecision({ detailMs, detailOk, result: 'detail-not-ready-120s' });
  150 |     await shot(page, 'detail-not-ready');
  151 |     throw new Error(`详情页预热 120s 内未出现分页栏 + 产品卡片（${detailMs}ms）—— 按派工停下回报`);
  152 |   }
  153 | 
  154 |   // 1) 编辑页：轮询「下一步」可点并点下去
  155 |   const t0 = Date.now();
  156 |   await page.goto(`/quotations/${QID}/edit`);
  157 |   const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
  158 |   let firstEnabledMs: number | null = null;
  159 |   let clicked = false;
  160 |   let fieldsAtClick: Awaited<ReturnType<typeof step1Fields>> | null = null;
  161 |   while (Date.now() - t0 < 120_000) {
  162 |     const en = await nextBtn.isEnabled({ timeout: 500 }).catch(() => false);
  163 |     if (en) {
  164 |       if (firstEnabledMs === null) firstEnabledMs = Date.now() - t0;
  165 |       fieldsAtClick = await step1Fields(page); // 点之前取：点下去后页面已离开 Step1
  166 |       clicked = await nextBtn.click({ timeout: 3000 }).then(() => true, () => false);
  167 |       if (clicked) break;
  168 |     }
  169 |     await page.waitForTimeout(500);
  170 |   }
  171 |   const step1Ms = Date.now() - t0;
  172 |   if (!clicked) {
  173 |     const f = await step1Fields(page);
  174 |     recordCategoryDecision({ detailMs, step1Ms, firstEnabledMs, clicked, result: 'not-executed-step1-120s', ...f });
  175 |     await shot(page, 'step1-not-clickable');
  176 |     test.skip(true, `未执行（Step1 既有问题）：编辑页「下一步」120s 内未能点下去（首次可点=${firstEnabledMs}ms，报价模板显示「${f.quoteTemplateShown}」）`);
  177 |   }
  178 |   recordCategoryDecision({ detailMs, step1Ms, firstEnabledMs, clicked, result: 'clicked-next', quoteTemplateShown: fieldsAtClick?.quoteTemplateShown, categoryShown: fieldsAtClick?.categoryShown, nextTitle: fieldsAtClick?.nextTitle });
  179 |   await page.waitForLoadState('networkidle', { timeout: 45000 }).catch(() => {});
> 180 |   await expect(page.locator('.ant-segmented').first()).toBeVisible({ timeout: 60000 });
      |                                                        ^ Error: expect(locator).toBeVisible() failed
  181 | }
  182 | 
  183 | /** 登录 + 装只读守卫 + 打开编辑页 Step2。 */
  184 | async function openSampleReadOnly(page: Page) {
  185 |   const guard = await guardReadOnlyApi(page);
  186 |   await loginAdmin(page);
  187 |   await openStep2WithCategoryIfNeeded(page);
  188 |   await page.waitForTimeout(1200);
  189 |   return guard;
  190 | }
  191 | 
  192 | let backendUp = false;
  193 | let expectedOrder: ReturnType<typeof queryOrderedLineItems> = [];
  194 | let deepPartNo = '';
  195 | let customerCode = '';
  196 | let categoryName = '';
  197 | let fingerprintBefore = '';
  198 | 
  199 | test.beforeAll(async () => {
  200 |   backendUp = await isBackendUp();
  201 |   if (backendUp) {
  202 |     expect(querySampleQuotationIdByNo(SEARCH_SAMPLE_QUOTATION_NO), `样本单 ${SEARCH_SAMPLE_QUOTATION_NO} 应仍在库且 id 未变`).toBe(QID);
  203 |     expect(queryLineItemCount(QID), `样本单应仍为 ${TOTAL} 行`).toBe(TOTAL);
  204 |     expect(queryPartNoPatternCount(QID, SEARCH_SAMPLE_PART_NO_SQL_PATTERN), '料号抓取正则应覆盖全部行（否则集合比对会漏抓）').toBe(TOTAL);
  205 |     expectedOrder = queryOrderedLineItems(QID);
  206 |     expect(expectedOrder.length, '只读 SQL 取数应非空').toBe(TOTAL);
  207 |     // 第 1200 位（1-indexed）= 下标 1199
  208 |     deepPartNo = expectedOrder[1199].productPartNo;
  209 |     customerCode = queryQuotationCustomerCode(QID);
  210 |     categoryName = queryCustomerCategoryName(QID);
  211 |     console.log(`[fixtures] 样本=${SEARCH_SAMPLE_QUOTATION_NO} 第1200位销售料号=${deepPartNo}, 客户=${customerCode}, 客户产品分类=${categoryName}`);
  212 |     expect(categoryName, 'D-3：正泰客户的产品分类名称应可查到（Step1 选分类要用）').toBeTruthy();
  213 |     expect(deepPartNo, '第 1200 位料号不应为空').toBeTruthy();
  214 |     fingerprintBefore = queryQuotationWriteFingerprint(QID);
  215 |     expect(fingerprintBefore, '写入指纹应可取到').toBeTruthy();
  216 |     console.log(`[fixtures] 写入指纹(前)=${fingerprintBefore}`);
  217 |   }
  218 | });
  219 | 
  220 | test.afterAll(async () => {
  221 |   if (!backendUp) return;
  222 |   const after = queryQuotationWriteFingerprint(QID);
  223 |   console.log(`[fixtures] 写入指纹(后)=${after}`);
  224 |   expect(after, `只读纪律：${SEARCH_SAMPLE_QUOTATION_NO} 不得被本 spec 改写`).toBe(fingerprintBefore);
  225 | });
  226 | 
  227 | test.describe('AC-9: 查询命中深位料号（第 1200 位）', () => {
  228 |   test('T-09 搜索第 1200 位料号必须命中，命中数==1，且该卡片渲染在第 1 页', async ({ page }) => {
  229 |     test.skip(!backendUp, '后端未启动');
  230 |     const guard = await openSampleReadOnly(page);
  231 | 
  232 |     await submitSearch(page, deepPartNo);
  233 | 
  234 |     const countText = await pgbarText(page);
  235 |     console.log(`[T-09] 分页栏文案 = "${countText}"`);
  236 |     await shot(page, 'deep-hit');
  237 | 
  238 |     const cardCount = await countRenderedCards(page);
  239 |     console.log(`[T-09] 命中卡片数 = ${cardCount}`);
  240 |     expect(cardCount, 'AC-9: 搜索深位料号必须命中且只命中 1 条').toBe(1);
  241 | 
  242 |     const cardText = await page.locator('.qt-product-card').first().innerText();
  243 |     expect(cardText, `AC-9: 命中卡片应显示搜索的料号 ${deepPartNo}`).toContain(deepPartNo);
  244 | 
  245 |     // 计数显示为命中数而非 1845
  246 |     expect(countText, 'AC-9: 计数应反映命中数（匹配 1 条，不是全量 1845 的原样展示）').toContain('匹配 1 条');
  247 |     console.log(`[T-09] 只读守卫短路的写请求 = ${JSON.stringify(guard.intercepted)}`);
  248 |   });
  249 | });
  250 | 
  251 | test.describe('AC-10: 匹配字段 productPartNo（销售料号）', () => {
  252 |   test('T-10a 用销售料号（productPartNo）搜索命中', async ({ page }) => {
  253 |     test.skip(!backendUp, '后端未启动');
  254 |     await openSampleReadOnly(page);
  255 |     await submitSearch(page, deepPartNo);
  256 |     const cardCount = await countRenderedCards(page);
  257 |     expect(cardCount, `AC-10: productPartNo=${deepPartNo} 应命中`).toBeGreaterThan(0);
  258 |   });
  259 | 
  260 |   // T-10b（客户产品编号命中）已删除 —— task-260923 D-2 ①：AC-13 不再含此项，
  261 |   // 本样本单 0/1845 行有客户产品编号；客户产品编号可搜由 S-1 的 AC-4 在自造样本单 A 上覆盖。
  262 | });
  263 | 
  264 | test.describe('AC-11: 查询空态', () => {
  265 |   test('T-11 查询命中 0 行：空态文案逐字一致，不报错不白屏，不保留上一页内容', async ({ page }) => {
  266 |     test.skip(!backendUp, '后端未启动');
  267 |     // task-260923 D-2 ②：判据 = 页面脚本报错（pageerror）数 = 0；React/antd 开发期 console.error 警告不计（仅记录）
  268 |     const pageErrors: string[] = [];
  269 |     const consoleErrors: string[] = [];
  270 |     page.on('pageerror', (e) => { pageErrors.push(`${e.message}\n${e.stack || ''}`); });
  271 |     page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  272 | 
  273 |     await openSampleReadOnly(page);
  274 | 
  275 |     await submitSearch(page, 'XYZ999');
  276 |     await shot(page, 'empty-state');
  277 | 
  278 |     const cardCount = await countRenderedCards(page);
  279 |     console.log(`[T-11] 空态下卡片数 = ${cardCount}`);
  280 |     expect(cardCount, 'AC-11: 查询命中 0 时不应保留上一页任何卡片').toBe(0);
```