# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: task260825-paging-search.spec.ts >> AC-12: 查询状态下翻页正确（2026-08-28 裁决：默认页大小 100→10） >> T-12 命中数跨页时，第 2 页是命中集合第 11-20 条（按新默认页大小 10 算）
- Location: e2e/task260825-paging-search.spec.ts:277:3

# Error details

```
Error: D-3：「下一步」禁用（title=请先填写产品分类和报价模板），但「产品分类」下拉不可用（visible=true, disabled=true）—— 按派工停下回报
```

# Test source

```ts
  34  | import { isBackendUp } from './fixtures/auth';
  35  | import {
  36  |   SEARCH_SAMPLE_QUOTATION_ID,
  37  |   SEARCH_SAMPLE_QUOTATION_NO,
  38  |   SEARCH_SAMPLE_PART_NO_REGEX,
  39  |   SEARCH_SAMPLE_PART_NO_SQL_PATTERN,
  40  |   querySampleQuotationIdByNo,
  41  |   queryPartNoPatternCount,
  42  |   queryQuotationWriteFingerprint,
  43  |   guardReadOnlyApi,
  44  |   loginAdmin,
  45  |   switchViewType,
  46  |   countRenderedCards,
  47  |   extractVisiblePartNoSet,
  48  |   queryOrderedLineItems,
  49  |   queryLineItemCount,
  50  |   queryQuotationCustomerCode,
  51  | } from './fixtures/task260825-paging';
  52  | 
  53  | const QID = SEARCH_SAMPLE_QUOTATION_ID;
  54  | const TOTAL = 1845;
  55  | 
  56  | // 大单打开约 15s（登录 + 下一步 + 首屏渲染），默认 30s 用例超时偏紧；只放宽等待余量，不改判据。
  57  | // ⚠️ run-01-A 实证：文件顶层 test.setTimeout() 不生效（仍报 30000ms 超时），改用 describe.configure。
  58  | test.describe.configure({ timeout: 120_000 });
  59  | 
  60  | const __filename = fileURLToPath(import.meta.url);
  61  | const __dirnameLocal = path.dirname(__filename);
  62  | // 证据目录可由环境变量改写（testing.md §5.7⑤：复跑不得覆盖上一轮证据）
  63  | const SHOT_DIR = process.env.T260923_S2_EVIDENCE_DIR || path.join(__dirnameLocal, 'screenshots', 'task260825');
  64  | fs.mkdirSync(SHOT_DIR, { recursive: true });
  65  | let shotIdx = 0;
  66  | async function shot(page: Page, name: string) {
  67  |   const file = path.join(SHOT_DIR, `search-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  68  |   await page.screenshot({ path: file, fullPage: false }).catch(() => {});
  69  |   return file;
  70  | }
  71  | 
  72  | /** 报价单编辑页料号查询输入框：分页栏（.qt-pgbar）里的 antd 输入框；不依赖提示文字。上下两条分页栏取第一条。 */
  73  | function searchInput(page: Page) {
  74  |   return page.locator('.qt-pgbar input.ant-input').first();
  75  | }
  76  | 
  77  | /** 分页栏计数文字（第一条分页栏的全部文字，含「共 N 条」/「匹配 N 条 / 共 M 条」）。 */
  78  | async function pgbarText(page: Page): Promise<string> {
  79  |   return (await page.locator('.qt-pgbar').first().innerText().catch(() => '')).replace(/\n/g, ' ');
  80  | }
  81  | 
  82  | /** 输入后按回车（task-260923：回车才触发搜索）。 */
  83  | async function submitSearch(page: Page, text: string) {
  84  |   const input = searchInput(page);
  85  |   await expect(input, '查询输入框应可见').toBeVisible({ timeout: 10000 });
  86  |   await input.fill(text);
  87  |   await input.press('Enter');
  88  |   await page.waitForTimeout(600);
  89  | }
  90  | 
  91  | /** 只读 SQL：样本单客户的产品分类名称（task-260923 D-3 选分类用；不硬编码）。 */
  92  | function queryCustomerCategoryName(quotationId: string): string {
  93  |   return execSync(
  94  |     `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -t -A -c "SELECT pc.name FROM quotation q JOIN customer c ON c.id=q.customer_id JOIN product_category pc ON pc.id=c.product_category_id WHERE q.id='${quotationId}';"`,
  95  |     { encoding: 'utf-8', shell: '/bin/bash' }
  96  |   ).trim();
  97  | }
  98  | 
  99  | /** D-3 证据：每条用例是否需要手动选产品分类，逐行追加到证据目录。 */
  100 | function recordCategoryDecision(entry: Record<string, unknown>) {
  101 |   const line = JSON.stringify({ at: new Date().toISOString(), test: test.info().title, ...entry });
  102 |   console.log(`[D-3] ${line}`);
  103 |   fs.appendFileSync(path.join(SHOT_DIR, 'D-3-category-select.jsonl'), line + '\n');
  104 | }
  105 | 
  106 | /**
  107 |  * 打开编辑页并进入 Step2（本 spec 专用，不改共享夹具的 openEditStep2）。
  108 |  * task-260923 D-3：样本单产品分类为空，Step1「下一步」时有被禁用（title「请先填写产品分类和报价模板」）。
  109 |  * 禁用时按用户操作：在「产品分类」下拉选上正泰客户的分类（SQL 现查）再点「下一步」。
  110 |  * 选分类不落库：写请求全部被只读守卫短路，afterAll 指纹核验。
  111 |  * 选不了 / 选了仍禁用 → 直接判失败并写明（🚫 不做自动重开页面）。
  112 |  */
  113 | async function openStep2WithCategoryIfNeeded(page: Page) {
  114 |   await page.goto(`/quotations/${QID}/edit`);
  115 |   await page.waitForLoadState('networkidle', { timeout: 45000 }).catch(() => {
  116 |     console.warn('[open] networkidle 45s 内未达到（大单已知现象），改用 DOM 信号兜底');
  117 |   });
  118 |   await page.waitForTimeout(1200);
  119 |   const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
  120 |   let needSelect = false;
  121 |   if (await nextBtn.isVisible().catch(() => false)) {
  122 |     // 给自然就绪留 5s；仍禁用才按 D-3 选分类
  123 |     const enabledNaturally = await expect(nextBtn).toBeEnabled({ timeout: 5000 }).then(() => true, () => false);
  124 |     if (!enabledNaturally) {
  125 |       needSelect = true;
  126 |       const title = await nextBtn.getAttribute('title').catch(() => null);
  127 |       const item = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: '产品分类' }) }).first();
  128 |       const sel = item.locator('.ant-select').first();
  129 |       const selVisible = await sel.isVisible().catch(() => false);
  130 |       const selDisabled = selVisible ? ((await sel.getAttribute('class')) || '').includes('ant-select-disabled') : true;
  131 |       if (!selVisible || selDisabled) {
  132 |         recordCategoryDecision({ needSelect, nextTitle: title, category: categoryName, result: 'category-select-unavailable', selVisible, selDisabled });
  133 |         await shot(page, 'D3-category-unavailable');
> 134 |         throw new Error(`D-3：「下一步」禁用（title=${title}），但「产品分类」下拉不可用（visible=${selVisible}, disabled=${selDisabled}）—— 按派工停下回报`);
      |               ^ Error: D-3：「下一步」禁用（title=请先填写产品分类和报价模板），但「产品分类」下拉不可用（visible=true, disabled=true）—— 按派工停下回报
  135 |       }
  136 |       await sel.click();
  137 |       await page.waitForTimeout(300);
  138 |       await page.keyboard.type(categoryName, { delay: 60 });
  139 |       await page.waitForTimeout(900);
  140 |       const opt = page.locator('.ant-select-item-option').filter({ hasText: categoryName }).first();
  141 |       const optOk = await opt.isVisible({ timeout: 5000 }).catch(() => false);
  142 |       if (!optOk) {
  143 |         recordCategoryDecision({ needSelect, nextTitle: title, category: categoryName, result: 'option-not-found' });
  144 |         await shot(page, 'D3-option-not-found');
  145 |         throw new Error(`D-3：「产品分类」下拉里找不到选项「${categoryName}」—— 按派工停下回报`);
  146 |       }
  147 |       await opt.click();
  148 |       await page.waitForTimeout(600);
  149 |       const enabledAfter = await expect(nextBtn).toBeEnabled({ timeout: 20000 }).then(() => true, () => false);
  150 |       recordCategoryDecision({ needSelect, nextTitle: title, category: categoryName, result: enabledAfter ? 'selected-then-enabled' : 'selected-still-disabled' });
  151 |       if (!enabledAfter) {
  152 |         await shot(page, 'D3-still-disabled');
  153 |         throw new Error(`D-3：已选「${categoryName}」后「下一步」仍禁用（title=${await nextBtn.getAttribute('title').catch(() => null)}）—— 按派工停下回报`);
  154 |       }
  155 |     } else {
  156 |       recordCategoryDecision({ needSelect, category: categoryName, result: 'enabled-without-select' });
  157 |     }
  158 |     await nextBtn.click();
  159 |     await page.waitForLoadState('networkidle', { timeout: 45000 }).catch(() => {});
  160 |   } else {
  161 |     recordCategoryDecision({ needSelect, result: 'no-step1-next-button' });
  162 |   }
  163 |   await expect(page.locator('.ant-segmented').first()).toBeVisible({ timeout: 60000 });
  164 | }
  165 | 
  166 | /** 登录 + 装只读守卫 + 打开编辑页 Step2。 */
  167 | async function openSampleReadOnly(page: Page) {
  168 |   const guard = await guardReadOnlyApi(page);
  169 |   await loginAdmin(page);
  170 |   await openStep2WithCategoryIfNeeded(page);
  171 |   await page.waitForTimeout(1200);
  172 |   return guard;
  173 | }
  174 | 
  175 | let backendUp = false;
  176 | let expectedOrder: ReturnType<typeof queryOrderedLineItems> = [];
  177 | let deepPartNo = '';
  178 | let customerCode = '';
  179 | let categoryName = '';
  180 | let fingerprintBefore = '';
  181 | 
  182 | test.beforeAll(async () => {
  183 |   backendUp = await isBackendUp();
  184 |   if (backendUp) {
  185 |     expect(querySampleQuotationIdByNo(SEARCH_SAMPLE_QUOTATION_NO), `样本单 ${SEARCH_SAMPLE_QUOTATION_NO} 应仍在库且 id 未变`).toBe(QID);
  186 |     expect(queryLineItemCount(QID), `样本单应仍为 ${TOTAL} 行`).toBe(TOTAL);
  187 |     expect(queryPartNoPatternCount(QID, SEARCH_SAMPLE_PART_NO_SQL_PATTERN), '料号抓取正则应覆盖全部行（否则集合比对会漏抓）').toBe(TOTAL);
  188 |     expectedOrder = queryOrderedLineItems(QID);
  189 |     expect(expectedOrder.length, '只读 SQL 取数应非空').toBe(TOTAL);
  190 |     // 第 1200 位（1-indexed）= 下标 1199
  191 |     deepPartNo = expectedOrder[1199].productPartNo;
  192 |     customerCode = queryQuotationCustomerCode(QID);
  193 |     categoryName = queryCustomerCategoryName(QID);
  194 |     console.log(`[fixtures] 样本=${SEARCH_SAMPLE_QUOTATION_NO} 第1200位销售料号=${deepPartNo}, 客户=${customerCode}, 客户产品分类=${categoryName}`);
  195 |     expect(categoryName, 'D-3：正泰客户的产品分类名称应可查到（Step1 选分类要用）').toBeTruthy();
  196 |     expect(deepPartNo, '第 1200 位料号不应为空').toBeTruthy();
  197 |     fingerprintBefore = queryQuotationWriteFingerprint(QID);
  198 |     expect(fingerprintBefore, '写入指纹应可取到').toBeTruthy();
  199 |     console.log(`[fixtures] 写入指纹(前)=${fingerprintBefore}`);
  200 |   }
  201 | });
  202 | 
  203 | test.afterAll(async () => {
  204 |   if (!backendUp) return;
  205 |   const after = queryQuotationWriteFingerprint(QID);
  206 |   console.log(`[fixtures] 写入指纹(后)=${after}`);
  207 |   expect(after, `只读纪律：${SEARCH_SAMPLE_QUOTATION_NO} 不得被本 spec 改写`).toBe(fingerprintBefore);
  208 | });
  209 | 
  210 | test.describe('AC-9: 查询命中深位料号（第 1200 位）', () => {
  211 |   test('T-09 搜索第 1200 位料号必须命中，命中数==1，且该卡片渲染在第 1 页', async ({ page }) => {
  212 |     test.skip(!backendUp, '后端未启动');
  213 |     const guard = await openSampleReadOnly(page);
  214 | 
  215 |     await submitSearch(page, deepPartNo);
  216 | 
  217 |     const countText = await pgbarText(page);
  218 |     console.log(`[T-09] 分页栏文案 = "${countText}"`);
  219 |     await shot(page, 'deep-hit');
  220 | 
  221 |     const cardCount = await countRenderedCards(page);
  222 |     console.log(`[T-09] 命中卡片数 = ${cardCount}`);
  223 |     expect(cardCount, 'AC-9: 搜索深位料号必须命中且只命中 1 条').toBe(1);
  224 | 
  225 |     const cardText = await page.locator('.qt-product-card').first().innerText();
  226 |     expect(cardText, `AC-9: 命中卡片应显示搜索的料号 ${deepPartNo}`).toContain(deepPartNo);
  227 | 
  228 |     // 计数显示为命中数而非 1845
  229 |     expect(countText, 'AC-9: 计数应反映命中数（匹配 1 条，不是全量 1845 的原样展示）').toContain('匹配 1 条');
  230 |     console.log(`[T-09] 只读守卫短路的写请求 = ${JSON.stringify(guard.intercepted)}`);
  231 |   });
  232 | });
  233 | 
  234 | test.describe('AC-10: 匹配字段 productPartNo（销售料号）', () => {
```