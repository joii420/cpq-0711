# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: task261026-file-crypto.spec.ts >> T-U01 / AC-4: encrypted Bundle preview -> policy re-preview -> commit -> close/reopen/refresh
- Location: e2e/task261026-file-crypto.spec.ts:62:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator:  locator('input[placeholder*="搜索"]').first()
Expected: visible
Received: hidden
Timeout:  60000ms

Call log:
  - Expect "toBeVisible" with timeout 60000ms
  - waiting for locator('input[placeholder*="搜索"]').first()
    61 × locator resolved to <input value="" type="text" placeholder="🔍 搜索组件名 / 编码" class="ant-input ant-input-sm css-dev-only-do-not-override-ch9ese"/>
       - unexpected value "hidden"

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
        - heading "组件管理" [level=2] [ref=e69]
        - generic [ref=e70]:
          - tablist [ref=e71]:
            - generic [ref=e73]:
              - tab "组件" [selected] [ref=e75] [cursor=pointer]
              - tab "数据源" [ref=e77] [cursor=pointer]
              - tab "全局变量" [ref=e79] [cursor=pointer]
              - tab "核价树配置" [ref=e81] [cursor=pointer]
          - tabpanel "组件" [ref=e84]:
            - generic [ref=e85]:
              - generic [ref=e86]:
                - generic [ref=e87]:
                  - generic [ref=e88]:
                    - generic [ref=e89]:
                      - textbox "🔍 搜索组件名 / 编码"
                    - button "search 查询" [ref=e91] [cursor=pointer]:
                      - img "search" [ref=e93]:
                        - img [ref=e94]
                      - generic [ref=e96]: 查询
                    - button "plus 新建" [ref=e97] [cursor=pointer]:
                      - img "plus" [ref=e99]:
                        - img [ref=e100]
                      - generic [ref=e103]: 新建
                    - button "folder-add 新建目录" [ref=e104] [cursor=pointer]:
                      - img "folder-add" [ref=e106]:
                        - img [ref=e107]
                      - generic [ref=e109]: 新建目录
                  - generic [ref=e111] [cursor=pointer]:
                    - checkbox "显示停用" [ref=e113]
                    - generic [ref=e114]: 显示停用
                  - generic [ref=e115]:
                    - button "停用 / 启用" [disabled] [ref=e116]:
                      - generic: 停用 / 启用
                    - button "删 除" [disabled] [ref=e117]:
                      - generic: 删 除
                  - generic [ref=e118]: 勾选卡片左侧框可批量操作
                  - button "保存全部草稿" [disabled] [ref=e120]:
                    - generic: 保存全部草稿
                - generic [ref=e121]:
                  - generic [ref=e122] [cursor=pointer]:
                    - generic [ref=e123]: ▶
                    - generic [ref=e124]:
                      - generic [ref=e125]: 📁 取值配置器测试
                      - generic [ref=e126]:
                        - generic [ref=e127]: 页签7
                        - generic [ref=e128]: XLS1
                        - generic [ref=e129]: 小计1
                    - generic [ref=e130]:
                      - button "edit" [ref=e131]:
                        - img "edit" [ref=e133]:
                          - img [ref=e134]
                      - button "export" [ref=e136]:
                        - img "export" [ref=e138]:
                          - img [ref=e139]
                      - button "import" [ref=e141]:
                        - img "import" [ref=e143]:
                          - img [ref=e144]
                      - button "tool" [ref=e146]:
                        - img "tool" [ref=e148]:
                          - img [ref=e149]
                      - button "delete" [ref=e151]:
                        - img "delete" [ref=e153]:
                          - img [ref=e154]
                  - generic [ref=e156]:
                    - generic [ref=e158] [cursor=pointer]:
                      - generic [ref=e159]: ▶
                      - text: 页签组件
                      - generic [ref=e161]: ＋
                    - generic [ref=e163] [cursor=pointer]:
                      - generic [ref=e164]: ▶
                      - text: EXCEL 组件
                      - generic [ref=e166]: ＋
                    - generic [ref=e168] [cursor=pointer]:
                      - generic [ref=e169]: ▶
                      - text: 小计组件
                      - generic [ref=e171]: ＋
                - generic [ref=e173] [cursor=pointer]:
                  - generic [ref=e174]: ▶
                  - generic [ref=e175]:
                    - generic [ref=e176]: 📁 核价组件
                    - generic [ref=e177]:
                      - generic [ref=e178]: 页签4
                      - generic [ref=e179]: 小计1
                  - generic [ref=e180]:
                    - button "edit" [ref=e181]:
                      - img "edit" [ref=e183]:
                        - img [ref=e184]
                    - button "export" [ref=e186]:
                      - img "export" [ref=e188]:
                        - img [ref=e189]
                    - button "import" [ref=e191]:
                      - img "import" [ref=e193]:
                        - img [ref=e194]
                    - button "tool" [ref=e196]:
                      - img "tool" [ref=e198]:
                        - img [ref=e199]
                    - button "delete" [ref=e201]:
                      - img "delete" [ref=e203]:
                        - img [ref=e204]
                - generic [ref=e207] [cursor=pointer]:
                  - generic [ref=e208]: ▶
                  - generic [ref=e209]:
                    - generic [ref=e210]: 📁 取值配置器测试2
                    - generic [ref=e211]:
                      - generic [ref=e212]: 页签4
                      - generic [ref=e213]: 小计1
                  - generic [ref=e214]:
                    - button "edit" [ref=e215]:
                      - img "edit" [ref=e217]:
                        - img [ref=e218]
                    - button "export" [ref=e220]:
                      - img "export" [ref=e222]:
                        - img [ref=e223]
                    - button "import" [ref=e225]:
                      - img "import" [ref=e227]:
                        - img [ref=e228]
                    - button "tool" [ref=e230]:
                      - img "tool" [ref=e232]:
                        - img [ref=e233]
                    - button "delete" [ref=e235]:
                      - img "delete" [ref=e237]:
                        - img [ref=e238]
                - generic [ref=e241] [cursor=pointer]:
                  - generic [ref=e242]: ▶
                  - generic [ref=e243]:
                    - generic [ref=e244]: 📁 正泰组件库-1
                    - generic [ref=e245]:
                      - generic [ref=e246]: 页签5
                      - generic [ref=e247]: 小计1
                  - generic [ref=e248]:
                    - button "edit" [ref=e249]:
                      - img "edit" [ref=e251]:
                        - img [ref=e252]
                    - button "export" [ref=e254]:
                      - img "export" [ref=e256]:
                        - img [ref=e257]
                    - button "import" [ref=e259]:
                      - img "import" [ref=e261]:
                        - img [ref=e262]
                    - button "tool" [ref=e264]:
                      - img "tool" [ref=e266]:
                        - img [ref=e267]
                    - button "delete" [ref=e269]:
                      - img "delete" [ref=e271]:
                        - img [ref=e272]
                - generic [ref=e275] [cursor=pointer]:
                  - generic [ref=e276]: ▶
                  - generic [ref=e278]: 📁 RT-SA-260915-3c601abe-AC16-EMPTY-SRC
                  - generic [ref=e279]:
                    - button "edit" [ref=e280]:
                      - img "edit" [ref=e282]:
                        - img [ref=e283]
                    - button "export" [ref=e285]:
                      - img "export" [ref=e287]:
                        - img [ref=e288]
                    - button "import" [ref=e290]:
                      - img "import" [ref=e292]:
                        - img [ref=e293]
                    - button "tool" [ref=e295]:
                      - img "tool" [ref=e297]:
                        - img [ref=e298]
                    - button "delete" [ref=e300]:
                      - img "delete" [ref=e302]:
                        - img [ref=e303]
              - generic [ref=e306]:
                - generic [ref=e307]: 🗂️
                - generic [ref=e308]: 从左侧列表选择一个组件查看 / 编辑
                - generic [ref=e309]: 组件 = 报价/核价中的一个页签或 Excel 表；定义列结构与公式
                - button "查看配置帮助" [ref=e310] [cursor=pointer]:
                  - generic [ref=e311]: 查看配置帮助
```

# Test source

```ts
  1   | import { test, expect, type Page } from '@playwright/test';
  2   | import { createHash } from 'node:crypto';
  3   | import fs from 'node:fs';
  4   | import path from 'node:path';
  5   | import { loginAsAdmin } from './fixtures/auth';
  6   | 
  7   | const FAKE = process.env.FILE_CRYPTO_FAKE_URL!;
  8   | const EVIDENCE = path.resolve(process.env.PW_EVIDENCE_DIR || 'test-results/task261026-file-crypto-evidence');
  9   | const RUN = `${Date.now()}-${process.pid}`;
  10  | const DIR_NAME = `T261026-CRYPTO-UI-DIR-${RUN}`;
  11  | const COMPONENT_CODE = `T261026-CRYPTO-UI-COMP-${RUN}`;
  12  | const CIPHER = Buffer.from(`T261026-CRYPTO-UI-CIPHER-${RUN}`);
  13  | 
  14  | type FakeCapture = {
  15  |   method: string;
  16  |   path: string;
  17  |   size: number;
  18  |   sha256: string;
  19  |   contentType: string;
  20  |   encodedFileName: string;
  21  |   requestId: string;
  22  |   authorizationMatched: boolean;
  23  | };
  24  | 
  25  | async function fake(pathname: string, init?: RequestInit) {
  26  |   const response = await fetch(`${FAKE}${pathname}`, init);
  27  |   expect(response.ok, `fake control ${pathname} -> ${response.status}`).toBeTruthy();
  28  |   return response.json() as Promise<any>;
  29  | }
  30  | 
  31  | async function choosePolicy(page: Page, panel: ReturnType<Page['locator']>, label: RegExp) {
  32  |   const labelled = panel.locator('.ant-form-item').filter({ hasText: /冲突.*策略|冲突处理/ })
  33  |     .locator('.ant-select').first();
  34  |   const select = (await labelled.count()) > 0 ? labelled : panel.locator('.ant-select').first();
  35  |   await expect(select, '组件导入弹层应有冲突策略选择器').toBeVisible();
  36  |   await select.click();
  37  |   const option = page.locator('.ant-select-item-option').filter({ hasText: label }).last();
  38  |   await expect(option, `冲突策略应包含 ${label}`).toBeVisible();
  39  |   await option.click();
  40  | }
  41  | 
  42  | async function deletePrivateFixture(page: Page, directoryId: string) {
  43  |   const list = await page.request.get(`/api/cpq/components?directoryId=${directoryId}`);
  44  |   if (list.ok()) {
  45  |     const json = await list.json();
  46  |     const candidates = Array.isArray(json?.data) ? json.data : (json?.data?.content || []);
  47  |     for (const component of candidates.filter((item: any) => item.directoryId === directoryId)) {
  48  |       await page.request.delete(`/api/cpq/components/${component.id}`);
  49  |     }
  50  |   }
  51  |   await page.request.delete(`/api/cpq/component-directories/${directoryId}`);
  52  | }
  53  | 
  54  | async function componentsInDirectory(page: Page, directoryId: string) {
  55  |   const response = await page.request.get(`/api/cpq/components?directoryId=${directoryId}`);
  56  |   expect(response.ok(), `component list HTTP ${response.status()}`).toBeTruthy();
  57  |   const json = await response.json();
  58  |   const candidates = Array.isArray(json?.data) ? json.data : (json?.data?.content || []);
  59  |   return candidates.filter((item: any) => item.directoryId === directoryId);
  60  | }
  61  | 
  62  | test('T-U01 / AC-4: encrypted Bundle preview -> policy re-preview -> commit -> close/reopen/refresh', async ({ page, context }) => {
  63  |   fs.mkdirSync(EVIDENCE, { recursive: true });
  64  |   await loginAsAdmin(page);
  65  | 
  66  |   // Pre-warm the newly started Vite stack before the timed sequence assertions.
  67  |   await page.goto('/components');
> 68  |   await expect(page.locator('input[placeholder*="搜索"]').first()).toBeVisible({ timeout: 60_000 });
      |                                                                  ^ Error: expect(locator).toBeVisible() failed
  69  | 
  70  |   const createDirectory = await page.request.post('/api/cpq/component-directories', {
  71  |     data: { name: DIR_NAME, parentId: null },
  72  |   });
  73  |   expect(createDirectory.ok(), await createDirectory.text()).toBeTruthy();
  74  |   const directoryId = (await createDirectory.json()).data.id as string;
  75  | 
  76  |   await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  77  | 
  78  |   const bundle = Buffer.from(JSON.stringify({
  79  |     bundleVersion: '1.0',
  80  |     components: [{
  81  |       code: COMPONENT_CODE,
  82  |       name: COMPONENT_CODE,
  83  |       componentType: 'NORMAL',
  84  |       fields: [],
  85  |       formulas: [],
  86  |     }],
  87  |   }));
  88  |   await fake('/__plan', {
  89  |     method: 'POST',
  90  |     headers: { 'content-type': 'application/json' },
  91  |     body: JSON.stringify({
  92  |       inspectStatus: 200,
  93  |       inspectBody: '{"encrypted":true}',
  94  |       decryptStatus: 200,
  95  |       decryptBodyBase64: bundle.toString('base64'),
  96  |     }),
  97  |   });
  98  | 
  99  |   const browserRequests: Array<{ url: string; contentType: string }> = [];
  100 |   page.on('request', request => {
  101 |     if (request.method() !== 'POST' || !request.url().includes(`/component-directories/${directoryId}/import`)) return;
  102 |     browserRequests.push({
  103 |       url: request.url(),
  104 |       contentType: request.headers()['content-type'] || '',
  105 |     });
  106 |   });
  107 | 
  108 |   try {
  109 |     await page.reload();
  110 |     await expect(page.getByText(DIR_NAME, { exact: false }).first()).toBeVisible();
  111 |     const directory = page.locator('.cmm-dir').filter({ hasText: DIR_NAME }).first();
  112 |     const importButton = directory.locator('.cmm-dir-acts button')
  113 |       .filter({ has: page.locator('.anticon-import') }).first();
  114 |     await expect(importButton).toBeVisible();
  115 |     await importButton.click();
  116 | 
  117 |     const panel = page.locator('.ant-modal, .ant-drawer').filter({ hasText: /导入组件到目录/ }).last();
  118 |     await expect(panel).toBeVisible();
  119 |     await panel.locator('input[type=file]').first().setInputFiles({
  120 |       name: 'T261026-CRYPTO-UI-bundle.json',
  121 |       mimeType: 'application/json',
  122 |       buffer: CIPHER,
  123 |     });
  124 | 
  125 |     await choosePolicy(page, panel, /跳过/);
  126 |     const previewSkip = page.waitForResponse(response =>
  127 |       response.request().method() === 'POST'
  128 |       && response.url().includes(`/component-directories/${directoryId}/import`)
  129 |       && !response.url().includes('/commit'));
  130 |     await panel.getByRole('button', { name: /预\s*览/ }).click();
  131 |     await expect.poll(() => browserRequests.length,
  132 |       { message: '第一次预览必须发出浏览器请求' }).toBe(1);
  133 |     expect(browserRequests[0].contentType,
  134 |       '第一次预览必须以 multipart file 发送').toContain('multipart/form-data; boundary=');
  135 |     const previewSkipResponse = await previewSkip;
  136 |     expect(previewSkipResponse.ok()).toBeTruthy();
  137 |     const previewSkipJson = await previewSkipResponse.json();
  138 |     expect(previewSkipJson?.data?.conflictPolicy,
  139 |       '第一次预览结果必须明确对应 SKIP 策略').toBe('SKIP');
  140 |     await expect(panel.getByText(COMPONENT_CODE, { exact: false }).first(),
  141 |       '第一次预览必须渲染解密后的非空组件').toBeVisible();
  142 | 
  143 |     await choosePolicy(page, panel, /重命名/);
  144 |     const previewRename = page.waitForResponse(response =>
  145 |       response.request().method() === 'POST'
  146 |       && response.url().includes(`/component-directories/${directoryId}/import`)
  147 |       && !response.url().includes('/commit'));
  148 |     await panel.getByRole('button', { name: /预\s*览/ }).click();
  149 |     const previewRenameResponse = await previewRename;
  150 |     expect(previewRenameResponse.ok()).toBeTruthy();
  151 |     const previewRenameJson = await previewRenameResponse.json();
  152 |     expect(previewRenameJson?.data?.conflictPolicy,
  153 |       '第二次预览结果必须明确对应 RENAME 策略').toBe('RENAME');
  154 |     await expect(panel.getByText(COMPONENT_CODE, { exact: false }).first(),
  155 |       '策略改变后的第二次预览必须仍来自同一原始文件').toBeVisible();
  156 | 
  157 |     const commit = page.waitForResponse(response =>
  158 |       response.request().method() === 'POST'
  159 |       && response.url().includes(`/component-directories/${directoryId}/import/commit`));
  160 |     await panel.getByRole('button', { name: /确认导入|提\s*交|导\s*入/ }).last().click();
  161 |     expect((await commit).ok()).toBeTruthy();
  162 | 
  163 |     const committedComponents = await componentsInDirectory(page, directoryId);
  164 |     expect(committedComponents.filter((item: any) => item.code === COMPONENT_CODE),
  165 |       '两次预览只能产生一次提交，唯一组件 code 必须恰好一条').toHaveLength(1);
  166 | 
  167 |     expect(browserRequests, 'preview + re-preview + commit request count').toHaveLength(3);
  168 |     expect(browserRequests[0].url).toContain('conflictPolicy=SKIP');
```