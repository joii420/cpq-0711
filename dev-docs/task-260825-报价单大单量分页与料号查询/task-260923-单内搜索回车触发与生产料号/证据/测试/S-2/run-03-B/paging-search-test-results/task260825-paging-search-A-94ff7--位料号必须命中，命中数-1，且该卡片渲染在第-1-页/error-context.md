# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: task260825-paging-search.spec.ts >> AC-9: 查询命中深位料号（第 1200 位） >> T-09 搜索第 1200 位料号必须命中，命中数==1，且该卡片渲染在第 1 页
- Location: e2e/task260825-paging-search.spec.ts:270:3

# Error details

```
Error: 加载判据 120s 内未满足（页头单号可见=false，客户显示「搜索并选择客户」，期望含「正泰」）—— 按派工停下回报
```

# Test source

```ts
  65  | let shotIdx = 0;
  66  | async function shot(page: Page, name: string) {
  67  |   // 文件名带用例标题：worker 重启会把 shotIdx 清零，只用序号会互相覆盖（run-02-A 实证只剩 1 张）
  68  |   const tag = test.info().title.split(' ')[0].replace(/[^\w-]/g, '_');
  69  |   const file = path.join(SHOT_DIR, `search-${tag}-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  70  |   await page.screenshot({ path: file, fullPage: false }).catch(() => {});
  71  |   return file;
  72  | }
  73  | 
  74  | /** 报价单编辑页料号查询输入框：分页栏（.qt-pgbar）里的 antd 输入框；不依赖提示文字。上下两条分页栏取第一条。 */
  75  | function searchInput(page: Page) {
  76  |   return page.locator('.qt-pgbar input.ant-input').first();
  77  | }
  78  | 
  79  | /** 分页栏计数文字（第一条分页栏的全部文字，含「共 N 条」/「匹配 N 条 / 共 M 条」）。 */
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
  94  | function queryCustomerName(quotationId: string): string {
  95  |   return execSync(
  96  |     `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -t -A -c "SELECT c.name FROM quotation q JOIN customer c ON c.id=q.customer_id WHERE q.id='${quotationId}';"`,
  97  |     { encoding: 'utf-8', shell: '/bin/bash' }
  98  |   ).trim();
  99  | }
  100 | 
  101 | function queryCustomerCategoryName(quotationId: string): string {
  102 |   return execSync(
  103 |     `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -t -A -c "SELECT pc.name FROM quotation q JOIN customer c ON c.id=q.customer_id JOIN product_category pc ON pc.id=c.product_category_id WHERE q.id='${quotationId}';"`,
  104 |     { encoding: 'utf-8', shell: '/bin/bash' }
  105 |   ).trim();
  106 | }
  107 | 
  108 | /** D-3 证据：每条用例是否需要手动选产品分类，逐行追加到证据目录。 */
  109 | function recordCategoryDecision(entry: Record<string, unknown>) {
  110 |   const line = JSON.stringify({ at: new Date().toISOString(), test: test.info().title, ...entry });
  111 |   console.log(`[D-3] ${line}`);
  112 |   fs.appendFileSync(path.join(SHOT_DIR, 'D-3-category-select.jsonl'), line + '\n');
  113 | }
  114 | 
  115 | /** 表单项里第一个下拉当前显示的文字（取不到返回 ''）。label 用正则精确匹配，避免「报价模板 客户专属」误中「客户」。 */
  116 | async function selectShownText(page: Page, label: RegExp): Promise<string> {
  117 |   const item = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: label }) }).filter({ has: page.locator('.ant-select') }).first();
  118 |   return ((await item.locator('.ant-select').first().innerText({ timeout: 1000 }).catch(() => '')) || '').replace(/\s+/g, ' ').trim();
  119 | }
  120 | const isEmptySelectText = (t: string) => !t || t.includes('请选择') || t.includes('搜索并选择');
  121 | 
  122 | /** Step1 各关键字段当前显示值（写进 D-3 记录用）。 */
  123 | async function step1Fields(page: Page) {
  124 |   const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
  125 |   return {
  126 |     headerHasQuotationNo: await page.getByText(SEARCH_SAMPLE_QUOTATION_NO).first().isVisible().catch(() => false),
  127 |     customerShown: await selectShownText(page, /^\s*客户\s*$/),
  128 |     categoryShown: await selectShownText(page, /^\s*产品分类\s*$/),
  129 |     quoteTemplateShown: await selectShownText(page, /报价模板/),
  130 |     nextDisabled: await nextBtn.isDisabled().catch(() => null),
  131 |     nextTitle: await nextBtn.getAttribute('title').catch(() => null),
  132 |   };
  133 | }
  134 | 
  135 | /**
  136 |  * 打开编辑页并进入 Step2（本 spec 专用，不改共享夹具的 openEditStep2）。
  137 |  *
  138 |  * 主线 2026-09-24 裁决的测试手法（不改 AC、不扩 D-3）：
  139 |  *   1. 先等页面加载完成：页头出现单号 QT-20260908-0628 **且**「客户」下拉显示样本客户名（SQL 现查），
  140 |  *      最长 120s，记录实际耗时。run-02-A 的「下一步禁用」判定发生在加载完成之前（页头单号仍为「-」）。
  141 |  *   2. 加载完成后看「下一步」：可点 ⇒ 直接点；
  142 |  *      仍禁用且「报价模板」为空 ⇒ 停下回报（🚫 不选模板、不选客户）；
  143 |  *      仍禁用且「产品分类」为空 ⇒ 按 D-3 选上客户的产品分类（仅限产品分类）；
  144 |  *      其它原因仍禁用 / 120s 内加载不完 ⇒ 停下回报。
  145 |  *   🚫 任何分支都不重开页面、不重试。写请求全部被只读守卫短路，afterAll 指纹核验。
  146 |  *   每条用例的等待耗时与判定写进 D-3-category-select.jsonl。
  147 |  */
  148 | async function openStep2WithCategoryIfNeeded(page: Page) {
  149 |   const t0 = Date.now();
  150 |   await page.goto(`/quotations/${QID}/edit`);
  151 | 
  152 |   // ① 等加载完成（两个条件同时满足）
  153 |   let loaded = false;
  154 |   while (Date.now() - t0 < 120_000) {
  155 |     const hdr = await page.getByText(SEARCH_SAMPLE_QUOTATION_NO).first().isVisible().catch(() => false);
  156 |     const cust = hdr ? await selectShownText(page, /^\s*客户\s*$/) : '';
  157 |     if (hdr && cust.includes(customerName)) { loaded = true; break; }
  158 |     await page.waitForTimeout(500);
  159 |   }
  160 |   const loadMs = Date.now() - t0;
  161 |   if (!loaded) {
  162 |     const f = await step1Fields(page);
  163 |     recordCategoryDecision({ loadMs, loaded, result: 'load-timeout-120s', customerExpected: customerName, ...f });
  164 |     await shot(page, 'load-timeout');
> 165 |     throw new Error(`加载判据 120s 内未满足（页头单号可见=${f.headerHasQuotationNo}，客户显示「${f.customerShown}」，期望含「${customerName}」）—— 按派工停下回报`);
      |           ^ Error: 加载判据 120s 内未满足（页头单号可见=false，客户显示「搜索并选择客户」，期望含「正泰」）—— 按派工停下回报
  166 |   }
  167 | 
  168 |   // ② 加载完成后判「下一步」
  169 |   const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
  170 |   if (!(await nextBtn.isVisible().catch(() => false))) {
  171 |     recordCategoryDecision({ loadMs, loaded, needSelect: false, result: 'no-step1-next-button' });
  172 |   } else {
  173 |     const enabled = await expect(nextBtn).toBeEnabled({ timeout: 3000 }).then(() => true, () => false);
  174 |     if (enabled) {
  175 |       recordCategoryDecision({ loadMs, loaded, needSelect: false, result: 'enabled-without-select', ...(await step1Fields(page)) });
  176 |     } else {
  177 |       const f = await step1Fields(page);
  178 |       if (isEmptySelectText(f.quoteTemplateShown)) {
  179 |         recordCategoryDecision({ loadMs, loaded, needSelect: false, result: 'template-empty-after-load', ...f });
  180 |         await shot(page, 'template-empty-after-load');
  181 |         throw new Error(`加载完成（${loadMs}ms）后「下一步」仍禁用且「报价模板」为空（显示「${f.quoteTemplateShown}」）—— 按派工停下回报，不选模板`);
  182 |       }
  183 |       if (!isEmptySelectText(f.categoryShown)) {
  184 |         recordCategoryDecision({ loadMs, loaded, needSelect: false, result: 'disabled-other-reason', ...f });
  185 |         await shot(page, 'disabled-other-reason');
  186 |         throw new Error(`加载完成（${loadMs}ms）后「下一步」仍禁用，但产品分类「${f.categoryShown}」与报价模板「${f.quoteTemplateShown}」都有值（title=${f.nextTitle}）—— 按派工停下回报`);
  187 |       }
  188 |       // D-3：产品分类为空 → 选上客户的产品分类（仅限产品分类）
  189 |       const item = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: /^\s*产品分类\s*$/ }) }).first();
  190 |       const sel = item.locator('.ant-select').first();
  191 |       const selDisabled = ((await sel.getAttribute('class').catch(() => '')) || '').includes('ant-select-disabled');
  192 |       if (selDisabled) {
  193 |         recordCategoryDecision({ loadMs, loaded, needSelect: true, result: 'category-select-unavailable', ...f });
  194 |         await shot(page, 'D3-category-unavailable');
  195 |         throw new Error(`D-3：产品分类为空但下拉禁用（title=${f.nextTitle}）—— 按派工停下回报`);
  196 |       }
  197 |       await sel.click();
  198 |       await page.waitForTimeout(300);
  199 |       await page.keyboard.type(categoryName, { delay: 60 });
  200 |       await page.waitForTimeout(900);
  201 |       const opt = page.locator('.ant-select-item-option').filter({ hasText: categoryName }).first();
  202 |       if (!(await opt.isVisible({ timeout: 5000 }).catch(() => false))) {
  203 |         recordCategoryDecision({ loadMs, loaded, needSelect: true, result: 'option-not-found', category: categoryName, ...f });
  204 |         await shot(page, 'D3-option-not-found');
  205 |         throw new Error(`D-3：「产品分类」下拉里找不到选项「${categoryName}」—— 按派工停下回报`);
  206 |       }
  207 |       await opt.click();
  208 |       await page.waitForTimeout(600);
  209 |       const enabledAfter = await expect(nextBtn).toBeEnabled({ timeout: 20000 }).then(() => true, () => false);
  210 |       recordCategoryDecision({ loadMs, loaded, needSelect: true, category: categoryName, result: enabledAfter ? 'selected-then-enabled' : 'selected-still-disabled', ...(await step1Fields(page)) });
  211 |       if (!enabledAfter) {
  212 |         await shot(page, 'D3-still-disabled');
  213 |         throw new Error(`D-3：已选「${categoryName}」后「下一步」仍禁用 —— 按派工停下回报`);
  214 |       }
  215 |     }
  216 |     await nextBtn.click();
  217 |     await page.waitForLoadState('networkidle', { timeout: 45000 }).catch(() => {});
  218 |   }
  219 |   await expect(page.locator('.ant-segmented').first()).toBeVisible({ timeout: 60000 });
  220 | }
  221 | 
  222 | /** 登录 + 装只读守卫 + 打开编辑页 Step2。 */
  223 | async function openSampleReadOnly(page: Page) {
  224 |   const guard = await guardReadOnlyApi(page);
  225 |   await loginAdmin(page);
  226 |   await openStep2WithCategoryIfNeeded(page);
  227 |   await page.waitForTimeout(1200);
  228 |   return guard;
  229 | }
  230 | 
  231 | let backendUp = false;
  232 | let expectedOrder: ReturnType<typeof queryOrderedLineItems> = [];
  233 | let deepPartNo = '';
  234 | let customerCode = '';
  235 | let categoryName = '';
  236 | let customerName = '';
  237 | let fingerprintBefore = '';
  238 | 
  239 | test.beforeAll(async () => {
  240 |   backendUp = await isBackendUp();
  241 |   if (backendUp) {
  242 |     expect(querySampleQuotationIdByNo(SEARCH_SAMPLE_QUOTATION_NO), `样本单 ${SEARCH_SAMPLE_QUOTATION_NO} 应仍在库且 id 未变`).toBe(QID);
  243 |     expect(queryLineItemCount(QID), `样本单应仍为 ${TOTAL} 行`).toBe(TOTAL);
  244 |     expect(queryPartNoPatternCount(QID, SEARCH_SAMPLE_PART_NO_SQL_PATTERN), '料号抓取正则应覆盖全部行（否则集合比对会漏抓）').toBe(TOTAL);
  245 |     expectedOrder = queryOrderedLineItems(QID);
  246 |     expect(expectedOrder.length, '只读 SQL 取数应非空').toBe(TOTAL);
  247 |     // 第 1200 位（1-indexed）= 下标 1199
  248 |     deepPartNo = expectedOrder[1199].productPartNo;
  249 |     customerCode = queryQuotationCustomerCode(QID);
  250 |     categoryName = queryCustomerCategoryName(QID);
  251 |     customerName = queryCustomerName(QID);
  252 |     expect(customerName, '样本单客户名称应可查到（加载完成判据要用）').toBeTruthy();
  253 |     console.log(`[fixtures] 样本=${SEARCH_SAMPLE_QUOTATION_NO} 第1200位销售料号=${deepPartNo}, 客户=${customerCode}, 客户产品分类=${categoryName}`);
  254 |     expect(categoryName, 'D-3：正泰客户的产品分类名称应可查到（Step1 选分类要用）').toBeTruthy();
  255 |     expect(deepPartNo, '第 1200 位料号不应为空').toBeTruthy();
  256 |     fingerprintBefore = queryQuotationWriteFingerprint(QID);
  257 |     expect(fingerprintBefore, '写入指纹应可取到').toBeTruthy();
  258 |     console.log(`[fixtures] 写入指纹(前)=${fingerprintBefore}`);
  259 |   }
  260 | });
  261 | 
  262 | test.afterAll(async () => {
  263 |   if (!backendUp) return;
  264 |   const after = queryQuotationWriteFingerprint(QID);
  265 |   console.log(`[fixtures] 写入指纹(后)=${after}`);
```