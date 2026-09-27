# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: quotation-flow.spec.ts >> TC-075 SIMPLE · Stage H 确定性精度单保存/刷新/重开后所有 Tab 稳定
- Location: e2e/quotation-flow.spec.ts:624:1

# Error details

```
Error: 必须显式设置 PW_PRECISION_SEED_QUOTATION_NO，指向 Stage H 已加载的确定性 SIMPLE 精度种子；禁止回退到随机或普通业务单据。
```

# Page snapshot

```yaml
- generic [ref=e3]:
  - complementary [ref=e4]:
    - generic [ref=e5]:
      - generic [ref=e7]: CPQ 报价系统
      - menu [ref=e8]:
        - menuitem "dashboard 工作台" [ref=e9] [cursor=pointer]:
          - img "dashboard" [ref=e10]:
            - img [ref=e11]
          - generic [ref=e13]: 工作台
        - menuitem "team 客户管理" [ref=e14] [cursor=pointer]:
          - img "team" [ref=e15]:
            - img [ref=e16]
          - generic [ref=e18]: 客户管理
        - menuitem "shopping 产品管理" [ref=e19] [cursor=pointer]:
          - img "shopping" [ref=e20]:
            - img [ref=e21]
          - generic [ref=e23]: 产品管理
        - menuitem "file-text 报价中心" [ref=e24] [cursor=pointer]:
          - img "file-text" [ref=e25]:
            - img [ref=e26]
          - generic [ref=e28]: 报价中心
        - menuitem "percentage 定价管理" [ref=e29] [cursor=pointer]:
          - img "percentage" [ref=e30]:
            - img [ref=e31]
          - generic [ref=e33]: 定价管理
        - menuitem "appstore 配置中心" [ref=e34] [cursor=pointer]:
          - img "appstore" [ref=e35]:
            - img [ref=e36]
          - generic [ref=e38]: 配置中心
        - menuitem "database 主数据维护" [ref=e39] [cursor=pointer]:
          - img "database" [ref=e40]:
            - img [ref=e41]
          - generic [ref=e43]: 主数据维护
        - menuitem "setting 系统管理" [ref=e44] [cursor=pointer]:
          - img "setting" [ref=e45]:
            - img [ref=e46]
          - generic [ref=e48]: 系统管理
  - generic [ref=e49]:
    - banner [ref=e50]:
      - button "moon" [ref=e51] [cursor=pointer]:
        - img "moon" [ref=e53]:
          - img [ref=e54]
      - generic [ref=e56]:
        - img "bell" [ref=e57] [cursor=pointer]:
          - img [ref=e58]
        - superscript [ref=e60] [cursor=pointer]: 99+
      - generic [ref=e61] [cursor=pointer]:
        - img "user" [ref=e63]:
          - img [ref=e64]
        - text: 系统管理员
    - main [ref=e66]:
      - generic [ref=e67]:
        - heading "工作台" [level=4] [ref=e68]
        - generic [ref=e69]:
          - generic [ref=e72]: System Health
          - generic [ref=e74]:
            - paragraph [ref=e75]:
              - img "check-circle" [ref=e76]:
                - img [ref=e77]
              - text: "Status: UP"
            - paragraph [ref=e80]: "Service: CPQ Quotation System"
```

# Test source

```ts
  1   | import Decimal from 'decimal.js';
  2   | import { expect, type Locator, type Page } from '@playwright/test';
  3   | import type { DecimalString } from '../../src/utils/precision';
  4   | 
  5   | export const PRECISION_PARTS = {
  6   |   simple: 'TASK0810-SIMPLE',
  7   |   composite: 'TASK0810-COMPOSITE',
  8   |   partOne: 'TASK0810-PART-01',
  9   |   partTwo: 'TASK0810-PART-02',
  10  | } as const;
  11  | 
  12  | export const PRECISION_TABS = ['精度验证', '稳定页签'] as const;
  13  | export const PRECISION_ROW_KEYS = ['01', '02'] as const;
  14  | export const PRECISION_SENTINELS = [
  15  |   '1.234567891234',
  16  |   '98765431.123456789012',
  17  | ] as const satisfies readonly DecimalString[];
  18  | export const STABILITY_SENTINELS = ['stable-01', 'stable-02'] as const;
  19  | 
  20  | export interface PrecisionFixtureContract {
  21  |   seedQuotationNo: string;
  22  |   tabName: string;
  23  |   inputField: string;
  24  |   resultField: string;
  25  |   positiveRowIndex: number;
  26  |   negativeRowIndex: number;
  27  | }
  28  | 
  29  | export const PRECISION_FIXTURE: PrecisionFixtureContract = {
  30  |   seedQuotationNo: process.env.PW_PRECISION_SEED_QUOTATION_NO || '',
  31  |   tabName: process.env.PW_PRECISION_TAB_NAME || '精度验证',
  32  |   inputField: process.env.PW_PRECISION_INPUT_FIELD || '精度输入',
  33  |   resultField: process.env.PW_PRECISION_RESULT_FIELD || '精度结果',
  34  |   positiveRowIndex: 0,
  35  |   negativeRowIndex: 1,
  36  | };
  37  | 
  38  | export const PRECISION_CASES = {
  39  |   positiveWork: '1.234567890499',
  40  |   positiveDisplay: '1.23456789',
  41  |   negativeWork: '-1.234567890500',
  42  |   negativeDisplay: '-1.234567891',
  43  |   largeWork: '98765431.123456789012',
  44  |   largeDisplay: '98765431.123456789',
  45  |   // TC-076（阻断组）：FR-12 已由 repair-0812 作废改写（问题说明.md §5/§7）——对账不再按 12 位
  46  |   // 工作值比，而是先把两侧归一到 FORMULA_RESULT_SCALE(9) 再比。这对哨兵的差异精确落在归一后的
  47  |   // 第 9 位（frontendFraction 第 9 位 '9' vs backendFraction 第 9 位 '8'），归一后仍不同，
  48  |   // 必须继续阻断提交——对应 repair-0812 test.md TC-09 / 单测 TC-02b 的"最小真差异边界"。
  49  |   reconcileFrontend: '7.123456789111',
  50  |   reconcileBackend: '7.123456788499',
  51  |   reconcileDisplay: '7.123456789',
  52  |   recoveredWork: '7.123456789112',
  53  |   // TC-076b（放行组）：差异只落在第 10~12 位（前 9 位完全相同），归一后两侧相等 —— 这正是
  54  |   // 被作废的旧 FR-12 曾要求阻断、repair-0812 裁决改为必须放行的那对值。
  55  |   reconcileSubScaleFrontend: '7.123456789111',
  56  |   reconcileSubScaleBackend: '7.123456789499',
  57  |   reconcileSubScaleDisplay: '7.123456789',
  58  | } as const satisfies Record<string, DecimalString>;
  59  | 
  60  | const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';
  61  | 
  62  | function unwrap(body: any): any {
  63  |   return body?.data ?? body;
  64  | }
  65  | 
  66  | async function jsonOrThrow(response: { ok(): boolean; status(): number; text(): Promise<string>; json(): Promise<any> }, label: string) {
  67  |   if (!response.ok()) throw new Error(`${label}失败: HTTP ${response.status()} ${await response.text()}`);
  68  |   const body = await response.json();
  69  |   if (typeof body?.code === 'number') {
  70  |     expect(body.code, `${label} API code 必须与 HTTP status 一致`).toBe(response.status());
  71  |   }
  72  |   return unwrap(body);
  73  | }
  74  | 
  75  | /**
  76  |  * 复制仓库约定的确定性 SIMPLE 种子单据。种子必须满足：
  77  |  * - 目标页签同时存在于报价/核价卡片，至少两行；
  78  |  * - inputField 为可编辑 INPUT_NUMBER；resultField 为恒等公式(input * 1)；
  79  |  * - 报价与核价的 resultField 使用相同公式，便于三视图逐值对拍。
  80  |  */
  81  | export async function copyPrecisionSeed(page: Page): Promise<string> {
  82  |   if (!PRECISION_FIXTURE.seedQuotationNo) {
> 83  |     throw new Error(
      |           ^ Error: 必须显式设置 PW_PRECISION_SEED_QUOTATION_NO，指向 Stage H 已加载的确定性 SIMPLE 精度种子；禁止回退到随机或普通业务单据。
  84  |       '必须显式设置 PW_PRECISION_SEED_QUOTATION_NO，指向 Stage H 已加载的确定性 SIMPLE 精度种子；' +
  85  |       '禁止回退到随机或普通业务单据。',
  86  |     );
  87  |   }
  88  |   const seedId = await findQuotationIdByNo(page, PRECISION_FIXTURE.seedQuotationNo);
  89  |   const copyResponse = await page.request.post(`${BACKEND_URL}/api/cpq/quotations/${seedId}/copy`, { data: {} });
  90  |   const copied = await jsonOrThrow(copyResponse, '复制精度种子报价单');
  91  |   const copiedId = copied?.id ?? copied?.quotationId;
  92  |   if (!copiedId) throw new Error(`复制响应缺少 quotation id: ${JSON.stringify(copied)}`);
  93  |   return String(copiedId);
  94  | }
  95  | 
  96  | export async function findQuotationIdByNo(page: Page, quotationNo: string): Promise<string> {
  97  |   const pageSize = 100;
  98  |   const exactMatches: any[] = [];
  99  |   let pageIndex = 0;
  100 |   let scanned = 0;
  101 |   let total = Number.POSITIVE_INFINITY;
  102 | 
  103 |   while (scanned < total) {
  104 |     const query = new URLSearchParams({
  105 |       keyword: quotationNo,
  106 |       page: String(pageIndex),
  107 |       size: String(pageSize),
  108 |     });
  109 |     const listResponse = await page.request.get(`${BACKEND_URL}/api/cpq/quotations?${query}`);
  110 |     const list = await jsonOrThrow(listResponse, `查询精度种子报价单第 ${pageIndex + 1} 页`);
  111 |     const items: any[] = Array.isArray(list) ? list : (list?.content ?? []);
  112 |     exactMatches.push(...items.filter((item) =>
  113 |       item?.quotationNumber === quotationNo || item?.quotationNo === quotationNo,
  114 |     ));
  115 |     scanned += items.length;
  116 |     const responseTotal = Number(list?.totalElements ?? list?.total);
  117 |     if (!Array.isArray(list) && Number.isFinite(responseTotal)) total = responseTotal;
  118 |     if (items.length === 0 || items.length < pageSize) break;
  119 |     pageIndex += 1;
  120 |     if (pageIndex > 10_000) throw new Error(`查询 quotationNo=${quotationNo} 分页超过安全上限`);
  121 |   }
  122 | 
  123 |   if (exactMatches.length !== 1 || !exactMatches[0]?.id) {
  124 |     throw new Error(
  125 |       `确定性 E2E 种子 quotationNo=${quotationNo} 必须唯一，实际精确匹配 ${exactMatches.length} 条。` +
  126 |       '请加载仓库 Stage H seed；不得使用第一页随机业务单据替代。',
  127 |     );
  128 |   }
  129 |   return String(exactMatches[0].id);
  130 | }
  131 | 
  132 | export async function assertPageLoadingSettled(page: Page, label: string): Promise<void> {
  133 |   const visibleLoading = page.locator(
  134 |     '.ant-btn-loading:visible, .ant-spin-spinning:visible, .anticon-loading:visible, [aria-label="loading"]:visible',
  135 |   );
  136 |   await expect(visibleLoading, `${label} 不得残留可见 loading`).toHaveCount(0, { timeout: 30_000 });
  137 |   await expect(page.getByText('加载中', { exact: false }), `${label} 不得残留“加载中”文案`)
  138 |     .toHaveCount(0, { timeout: 30_000 });
  139 | }
  140 | 
  141 | async function settleQuotationStep2(page: Page): Promise<void> {
  142 |   await page.waitForLoadState('networkidle');
  143 |   const cards = page.locator('.qt-product-card');
  144 |   if (!(await cards.first().isVisible().catch(() => false))) {
  145 |     const next = page.getByRole('button', { name: /下一步/ }).first();
  146 |     await expect(next, '编辑页 Step1 应可进入 Step2').toBeEnabled({ timeout: 20_000 });
  147 |     await next.click();
  148 |   }
  149 |   await expect(cards.first(), 'Step2 应渲染产品卡片').toBeVisible({ timeout: 30_000 });
  150 |   await assertPageLoadingSettled(page, 'Step2 页面');
  151 | }
  152 | 
  153 | export async function openQuotationStep2(page: Page, quotationId: string): Promise<void> {
  154 |   await page.goto(`/quotations/${quotationId}/edit`);
  155 |   await settleQuotationStep2(page);
  156 | }
  157 | 
  158 | export async function reloadQuotationStep2(page: Page): Promise<void> {
  159 |   await page.reload();
  160 |   await settleQuotationStep2(page);
  161 | }
  162 | 
  163 | export async function openDetail(page: Page, quotationId: string): Promise<void> {
  164 |   await page.goto(`/quotations/${quotationId}`);
  165 |   await page.waitForLoadState('networkidle');
  166 |   await expect(page.locator('.qt-product-card').first(), '详情页应渲染产品卡片').toBeVisible({ timeout: 30_000 });
  167 |   await expect(page.getByText('加载中', { exact: false })).toHaveCount(0, { timeout: 30_000 });
  168 | }
  169 | 
  170 | export async function productCardByPartNo(page: Page, partNo: string): Promise<Locator> {
  171 |   const cards = page.locator('.qt-product-card').filter({
  172 |     has: page.locator('.qt-sku-badge').filter({ hasText: new RegExp(`料号:\\s*${escapeRegExp(partNo)}(?:\\s|$)`) }),
  173 |   });
  174 |   await expect(cards, `料号 ${partNo} 必须唯一对应一个可见产品卡`).toHaveCount(1);
  175 |   const card = cards.nth(0);
  176 |   await expect(card).toBeVisible();
  177 |   return card;
  178 | }
  179 | 
  180 | export async function assertVisibleProductCards(page: Page, expectedPartNos: readonly string[]): Promise<void> {
  181 |   const cards = page.locator('.qt-product-card');
  182 |   await expect(cards, `可见产品卡数量必须为 ${expectedPartNos.length}`).toHaveCount(expectedPartNos.length);
  183 |   for (const partNo of expectedPartNos) await productCardByPartNo(page, partNo);
```