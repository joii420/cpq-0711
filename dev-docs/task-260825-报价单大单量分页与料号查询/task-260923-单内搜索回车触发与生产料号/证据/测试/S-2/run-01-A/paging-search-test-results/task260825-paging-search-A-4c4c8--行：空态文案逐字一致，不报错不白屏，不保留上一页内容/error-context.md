# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: task260825-paging-search.spec.ts >> AC-11: 查询空态 >> T-11 查询命中 0 行：空态文案逐字一致，不报错不白屏，不保留上一页内容
- Location: e2e/task260825-paging-search.spec.ts:177:3

# Error details

```
Error: expect(locator).toBeEnabled() failed

Locator:  getByRole('button', { name: /下一步/ }).first()
Expected: enabled
Received: disabled
Timeout:  20000ms

Call log:
  - Expect "toBeEnabled" with timeout 20000ms
  - waiting for getByRole('button', { name: /下一步/ }).first()
    24 × locator resolved to <button disabled type="button" title="请先填写产品分类和报价模板" class="ant-btn css-dev-only-do-not-override-ch9ese css-var-_r_0_ ant-btn-primary ant-btn-color-primary ant-btn-variant-solid">…</button>
       - unexpected value "disabled"

```

# Test source

```ts
  61  |  * 该单「写入指纹」（只读）：报价单 updated_at + user_data_version + 全部行的 id/row_version/card_snapshot_at/quote_values_at 摘要。
  62  |  * 只读片在跑前、跑后各取一次，二者必须相等 —— 证明测试没有保存/改写这张共享草稿。
  63  |  */
  64  | export function queryQuotationWriteFingerprint(quotationId: string): string {
  65  |   return psql(
  66  |     `SELECT q.updated_at || '#' || coalesce(q.user_data_version::text,'') || '#' || md5(string_agg(li.id::text || coalesce(li.row_version::text,'') || coalesce(li.card_snapshot_at::text,'') || coalesce(li.quote_values_at::text,''), ',' ORDER BY li.sort_order)) FROM quotation q JOIN quotation_line_item li ON li.quotation_id=q.id WHERE q.id='${quotationId}' GROUP BY q.updated_at, q.user_data_version;`
  67  |   );
  68  | }
  69  | 
  70  | /**
  71  |  * 只读守卫：拦截除 GET 与登录外的一切 /api 请求，以 mock 成功响应短路，不放行到真实后端。
  72  |  * 打开编辑页会自发 POST ensure-card-values / formulas/batch-evaluate，以及（已知 S-8）PUT /draft ——
  73  |  * 不拦就等于「保存」了共享草稿。用 fulfill 而不是 abort：abort 会在控制台留 net::ERR_FAILED，
  74  |  * 干扰「控制台无 error」类断言。返回被短路请求的记录。
  75  |  */
  76  | export async function guardReadOnlyApi(page: Page): Promise<{ intercepted: string[] }> {
  77  |   const rec = { intercepted: [] as string[] };
  78  |   await page.route('**/api/**', async (route) => {
  79  |     const req = route.request();
  80  |     const pathname = new URL(req.url()).pathname;
  81  |     if (req.method() === 'GET' || pathname.endsWith('/auth/login')) {
  82  |       await route.continue();
  83  |       return;
  84  |     }
  85  |     rec.intercepted.push(`${req.method()} ${pathname}`);
  86  |     await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'intercepted-by-readonly-test', data: {} }) });
  87  |   });
  88  |   return rec;
  89  | }
  90  | 
  91  | function psql(sql: string): string {
  92  |   const cmd = `PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -t -A -F'|' -c "${sql.replace(/"/g, '\\"')}"`;
  93  |   return execSync(cmd, { encoding: 'utf-8', shell: '/bin/bash' }).trim();
  94  | }
  95  | 
  96  | export interface LineItemRow {
  97  |   id: string;
  98  |   sortOrder: number;
  99  |   productPartNo: string;
  100 |   customerPartNo: string;
  101 | }
  102 | 
  103 | /** 按 sort_order 升序取出该单全部行的 (id, sort_order, productPartNo, customerPartNo)。只读，不写库。 */
  104 | export function queryOrderedLineItems(quotationId: string): LineItemRow[] {
  105 |   const out = psql(
  106 |     `SELECT id, sort_order, product_part_no_snapshot, coalesce(customer_part_no,'') FROM quotation_line_item WHERE quotation_id='${quotationId}' ORDER BY sort_order;`
  107 |   );
  108 |   if (!out) return [];
  109 |   return out.split('\n').filter(Boolean).map((line) => {
  110 |     const [id, so, ppn, cpn] = line.split('|');
  111 |     return { id, sortOrder: Number(so), productPartNo: ppn, customerPartNo: cpn };
  112 |   });
  113 | }
  114 | 
  115 | /** 查 material_customer_map 取某料号在某客户下的 customerProductNo（AC-10 用的字段，与 customer_part_no 列是两回事）。 */
  116 | export function queryCustomerProductNo(materialNo: string, customerCode: string): string | null {
  117 |   const out = psql(
  118 |     `SELECT customer_product_no FROM material_customer_map WHERE material_no='${materialNo}' AND customer_no='${customerCode}' LIMIT 1;`
  119 |   );
  120 |   return out || null;
  121 | }
  122 | 
  123 | export function queryQuotationCustomerCode(quotationId: string): string {
  124 |   const out = psql(
  125 |     `SELECT c.code FROM quotation q JOIN customer c ON c.id=q.customer_id WHERE q.id='${quotationId}';`
  126 |   );
  127 |   return out;
  128 | }
  129 | 
  130 | /** 该单总行数（只读校验用，避免样本本身漂移导致后续断言全部失真）。 */
  131 | export function queryLineItemCount(quotationId: string): number {
  132 |   const out = psql(`SELECT count(*) FROM quotation_line_item WHERE quotation_id='${quotationId}';`);
  133 |   return Number(out);
  134 | }
  135 | 
  136 | /** 登录（复用项目既有 storageState 机制）。 */
  137 | export async function loginAdmin(page: Page) {
  138 |   const { loginAsAdmin } = await import('./auth');
  139 |   await loginAsAdmin(page);
  140 | }
  141 | 
  142 | /**
  143 |  * 大单（1845 行）渲染耗时 30s+ 且期间持续有网络活动（自发 saveDraft / batch-evaluate 等），
  144 |  * `waitForLoadState('networkidle')` 在这种页面上经常在默认 30s 内等不到"网络安静"，
  145 |  * 属于测试基础设施的等待策略问题（不是业务 bug，见 testing.md §4 "先查测试基础设施"）。
  146 |  * 这里放宽超时且失败不中断，随后一律再用一个具体 DOM 信号（Segmented/卡片可见）兜底确认。
  147 |  */
  148 | async function waitNetworkIdleTolerant(page: Page, timeoutMs = 45000) {
  149 |   await page.waitForLoadState('networkidle', { timeout: timeoutMs }).catch(() => {
  150 |     console.warn(`[task260825] networkidle 在 ${timeoutMs}ms 内未达到（大单页面持续有后台请求属已知现象），改用 DOM 信号兜底确认`);
  151 |   });
  152 | }
  153 | 
  154 | /** 打开报价单编辑页并进入 Step2（若已在 Step1，点"下一步"）。 */
  155 | export async function openEditStep2(page: Page, quotationId: string) {
  156 |   await page.goto(`/quotations/${quotationId}/edit`);
  157 |   await waitNetworkIdleTolerant(page);
  158 |   await page.waitForTimeout(1200);
  159 |   const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
  160 |   if (await nextBtn.isVisible().catch(() => false)) {
> 161 |     await expect(nextBtn).toBeEnabled({ timeout: 20000 });
      |                           ^ Error: expect(locator).toBeEnabled() failed
  162 |     await nextBtn.click();
  163 |     await waitNetworkIdleTolerant(page);
  164 |   }
  165 |   // Step2 标志性元素：mainTab Segmented + 分页栏或卡片（大单渲染慢，超时放宽到 60s）
  166 |   await expect(page.locator('.ant-segmented').first()).toBeVisible({ timeout: 60000 });
  167 | }
  168 | 
  169 | /** 打开报价单详情页（只读）。 */
  170 | export async function openDetail(page: Page, quotationId: string) {
  171 |   await page.goto(`/quotations/${quotationId}`);
  172 |   await waitNetworkIdleTolerant(page);
  173 |   await page.waitForTimeout(1200);
  174 | }
  175 | 
  176 | /** 切上层 Segmented（报价单 / 核价单 / 比对视图）。 */
  177 | export async function switchMainTab(page: Page, label: '报价单' | '核价单' | '比对视图') {
  178 |   const seg = page.locator('.ant-segmented-item', { hasText: label }).first();
  179 |   await expect(seg, `mainTab "${label}" 应可见`).toBeVisible({ timeout: 10000 });
  180 |   await seg.click();
  181 |   await page.waitForTimeout(500);
  182 | }
  183 | 
  184 | /** 切下层 Segmented（产品卡片 / Excel 视图）。 */
  185 | export async function switchViewType(page: Page, label: '产品卡片' | 'Excel 视图') {
  186 |   const seg = page.locator('.ant-segmented-item', { hasText: label }).first();
  187 |   await expect(seg, `viewType "${label}" 应可见`).toBeVisible({ timeout: 10000 });
  188 |   await seg.click();
  189 |   await page.waitForTimeout(800);
  190 | }
  191 | 
  192 | /** 从当前 DOM 纯文本里按 PART_NO_REGEX 抓取所有出现的料号（去重排序），用于跨视图集合比对。 */
  193 | export async function extractVisiblePartNoSet(page: Page, scopeSelector = 'body', partNoRegex: RegExp = PART_NO_REGEX): Promise<Set<string>> {
  194 |   const text = await page.locator(scopeSelector).first().innerText();
  195 |   const matches = text.match(partNoRegex) || [];
  196 |   return new Set(matches);
  197 | }
  198 | 
  199 | /**
  200 |  * 按料号精确定位唯一产品卡（复用既有 e2e 基础设施 fixtures/precision.ts:productCardByPartNo 的
  201 |  * 同一约定：`.qt-product-card` 内 `.qt-sku-badge` 文本形如 "料号: <partNo>"）。
  202 |  * 比"整卡 innerText 里 includes(partNo)"更精确 —— 避免同页面其它文字巧合命中子串。
  203 |  */
  204 | export async function cardByPartNo(page: Page, partNo: string) {
  205 |   const cards = page.locator('.qt-product-card').filter({
  206 |     has: page.locator('.qt-sku-badge').filter({ hasText: new RegExp(`料号:\\s*${partNo.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?:\\s|$)`) }),
  207 |   });
  208 |   await expect(cards, `料号 ${partNo} 应唯一对应一张可见产品卡`).toHaveCount(1, { timeout: 10000 });
  209 |   return cards.first();
  210 | }
  211 | 
  212 | /** 当前渲染的产品卡片数量（.qt-product-card）。 */
  213 | export async function countRenderedCards(page: Page): Promise<number> {
  214 |   return page.locator('.qt-product-card').count();
  215 | }
  216 | 
  217 | /**
  218 |  * 显式切页大小（2026-08-28 用户裁决：默认页大小 100→10，可选档位 [10,30,50,100,200,500]）。
  219 |  * 一些用例（如三视图切片/配对、AP-54 写回下标专项）为了测到"深页/大页"的边界，需要显式切到
  220 |  * 100 条/页而不是依赖默认值 —— 默认值已从 100 改成 10，不再天然满足这些用例原有的 SQL 期望值
  221 |  * （如"第 3 页 = 全局下标 200~299"）。调用方无需自己重算这些偏移量，只要先调本函数切到 100。
  222 |  */
  223 | export async function switchPageSize(page: Page, size: 10 | 30 | 50 | 100 | 200 | 500) {
  224 |   const sizeChanger = page.locator('.ant-pagination-options-size-changer').first();
  225 |   await expect(sizeChanger, `页大小切换器应可见（切到 ${size} 前）`).toBeVisible({ timeout: 10000 });
  226 |   await sizeChanger.click();
  227 |   await page.waitForTimeout(300);
  228 |   const opt = page.locator('.ant-select-item-option', { hasText: `${size} 条/页` }).first();
  229 |   await expect(opt, `下拉应有 "${size} 条/页" 选项`).toBeVisible({ timeout: 5000 });
  230 |   await opt.click();
  231 |   await page.waitForTimeout(1000);
  232 | }
  233 | 
  234 | /** 阻断除 GET 外的一切 /api 请求（性能测量 / 只读浏览类用例的红线要求）。返回被拦截请求数的引用计数器。 */
  235 | export async function blockNonGetApi(page: Page): Promise<{ blocked: number; requests: string[] }> {
  236 |   const counter = { blocked: 0, requests: [] as string[] };
  237 |   await page.route('**/api/**', async (route) => {
  238 |     const req = route.request();
  239 |     if (req.method() !== 'GET') {
  240 |       counter.blocked++;
  241 |       counter.requests.push(`${req.method()} ${req.url()}`);
  242 |       await route.abort();
  243 |       return;
  244 |     }
  245 |     await route.continue();
  246 |   });
  247 |   return counter;
  248 | }
  249 | 
  250 | /** 统计 /api 网络请求数（用于 AC-3/AC-17 翻页零请求断言），不拦截，只计数。 */
  251 | export function countApiRequests(page: Page): { count: number; urls: string[] } {
  252 |   const rec = { count: 0, urls: [] as string[] };
  253 |   page.on('request', (req) => {
  254 |     if (req.url().includes('/api/')) {
  255 |       rec.count++;
  256 |       rec.urls.push(`${req.method()} ${req.url()}`);
  257 |     }
  258 |   });
  259 |   return rec;
  260 | }
  261 | 
```