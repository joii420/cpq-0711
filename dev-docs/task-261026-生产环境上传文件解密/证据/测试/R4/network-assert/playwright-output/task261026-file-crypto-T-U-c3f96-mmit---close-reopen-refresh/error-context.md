# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: task261026-file-crypto.spec.ts >> T-U01 / AC-4: encrypted Bundle preview -> policy re-preview -> commit -> close/reopen/refresh
- Location: e2e/task261026-file-crypto.spec.ts:62:1

# Error details

```
Error: 第一次预览必须以 multipart file 发送

expect(received).toContain(expected) // indexOf

Expected substring: "multipart/form-data; boundary="
Received string:    "application/json"
```

# Page snapshot

```yaml
- generic [ref=e1]:
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
                      - textbox "🔍 搜索组件名 / 编码" [ref=e90]
                      - button "plus 新建" [ref=e92] [cursor=pointer]:
                        - img "plus" [ref=e94]:
                          - img [ref=e95]
                        - generic [ref=e98]: 新建
                      - button "folder-add 新建目录" [ref=e99] [cursor=pointer]:
                        - img "folder-add" [ref=e101]:
                          - img [ref=e102]
                        - generic [ref=e104]: 新建目录
                    - generic [ref=e106] [cursor=pointer]:
                      - checkbox "显示停用" [ref=e108]
                      - generic [ref=e109]: 显示停用
                    - generic [ref=e110]:
                      - button "停用 / 启用" [disabled] [ref=e111]:
                        - generic: 停用 / 启用
                      - button "删 除" [disabled] [ref=e112]:
                        - generic: 删 除
                    - generic [ref=e113]: 勾选卡片左侧框可批量操作
                    - button "保存全部草稿" [disabled] [ref=e115]:
                      - generic: 保存全部草稿
                  - generic [ref=e116]:
                    - generic [ref=e117] [cursor=pointer]:
                      - generic [ref=e118]: ▶
                      - generic [ref=e119]:
                        - generic [ref=e120]: 📁 取值配置器测试
                        - generic [ref=e121]:
                          - generic [ref=e122]: 页签7
                          - generic [ref=e123]: XLS1
                          - generic [ref=e124]: 小计1
                      - generic [ref=e125]:
                        - button "edit" [ref=e126]:
                          - img "edit" [ref=e128]:
                            - img [ref=e129]
                        - button "export" [ref=e131]:
                          - img "export" [ref=e133]:
                            - img [ref=e134]
                        - button "import" [ref=e136]:
                          - img "import" [ref=e138]:
                            - img [ref=e139]
                        - button "tool" [ref=e141]:
                          - img "tool" [ref=e143]:
                            - img [ref=e144]
                        - button "delete" [ref=e146]:
                          - img "delete" [ref=e148]:
                            - img [ref=e149]
                    - generic [ref=e151]:
                      - generic [ref=e153] [cursor=pointer]:
                        - generic [ref=e154]: ▶
                        - text: 页签组件
                        - generic [ref=e156]: ＋
                      - generic [ref=e158] [cursor=pointer]:
                        - generic [ref=e159]: ▶
                        - text: EXCEL 组件
                        - generic [ref=e161]: ＋
                      - generic [ref=e163] [cursor=pointer]:
                        - generic [ref=e164]: ▶
                        - text: 小计组件
                        - generic [ref=e166]: ＋
                  - generic [ref=e168] [cursor=pointer]:
                    - generic [ref=e169]: ▶
                    - generic [ref=e170]:
                      - generic [ref=e171]: 📁 核价组件
                      - generic [ref=e172]:
                        - generic [ref=e173]: 页签4
                        - generic [ref=e174]: 小计1
                    - generic [ref=e175]:
                      - button "edit" [ref=e176]:
                        - img "edit" [ref=e178]:
                          - img [ref=e179]
                      - button "export" [ref=e181]:
                        - img "export" [ref=e183]:
                          - img [ref=e184]
                      - button "import" [ref=e186]:
                        - img "import" [ref=e188]:
                          - img [ref=e189]
                      - button "tool" [ref=e191]:
                        - img "tool" [ref=e193]:
                          - img [ref=e194]
                      - button "delete" [ref=e196]:
                        - img "delete" [ref=e198]:
                          - img [ref=e199]
                  - generic [ref=e202] [cursor=pointer]:
                    - generic [ref=e203]: ▶
                    - generic [ref=e204]:
                      - generic [ref=e205]: 📁 取值配置器测试2
                      - generic [ref=e206]:
                        - generic [ref=e207]: 页签4
                        - generic [ref=e208]: 小计1
                    - generic [ref=e209]:
                      - button "edit" [ref=e210]:
                        - img "edit" [ref=e212]:
                          - img [ref=e213]
                      - button "export" [ref=e215]:
                        - img "export" [ref=e217]:
                          - img [ref=e218]
                      - button "import" [ref=e220]:
                        - img "import" [ref=e222]:
                          - img [ref=e223]
                      - button "tool" [ref=e225]:
                        - img "tool" [ref=e227]:
                          - img [ref=e228]
                      - button "delete" [ref=e230]:
                        - img "delete" [ref=e232]:
                          - img [ref=e233]
                  - generic [ref=e236] [cursor=pointer]:
                    - generic [ref=e237]: ▶
                    - generic [ref=e238]:
                      - generic [ref=e239]: 📁 正泰组件库-1
                      - generic [ref=e240]:
                        - generic [ref=e241]: 页签5
                        - generic [ref=e242]: 小计1
                    - generic [ref=e243]:
                      - button "edit" [ref=e244]:
                        - img "edit" [ref=e246]:
                          - img [ref=e247]
                      - button "export" [ref=e249]:
                        - img "export" [ref=e251]:
                          - img [ref=e252]
                      - button "import" [ref=e254]:
                        - img "import" [ref=e256]:
                          - img [ref=e257]
                      - button "tool" [ref=e259]:
                        - img "tool" [ref=e261]:
                          - img [ref=e262]
                      - button "delete" [ref=e264]:
                        - img "delete" [ref=e266]:
                          - img [ref=e267]
                  - generic [ref=e270] [cursor=pointer]:
                    - generic [ref=e271]: ▶
                    - generic [ref=e273]: 📁 T261026-CRYPTO-UI-DIR-1793082535746-172038
                    - generic [ref=e274]:
                      - button "edit" [ref=e275]:
                        - img "edit" [ref=e277]:
                          - img [ref=e278]
                      - button "export" [ref=e280]:
                        - img "export" [ref=e282]:
                          - img [ref=e283]
                      - button "import" [ref=e285]:
                        - img "import" [ref=e287]:
                          - img [ref=e288]
                      - button "tool" [ref=e290]:
                        - img "tool" [ref=e292]:
                          - img [ref=e293]
                      - button "delete" [ref=e295]:
                        - img "delete" [ref=e297]:
                          - img [ref=e298]
                  - generic [ref=e301] [cursor=pointer]:
                    - generic [ref=e302]: ▶
                    - generic [ref=e304]: 📁 RT-SA-260915-3c601abe-AC16-EMPTY-SRC
                    - generic [ref=e305]:
                      - button "edit" [ref=e306]:
                        - img "edit" [ref=e308]:
                          - img [ref=e309]
                      - button "export" [ref=e311]:
                        - img "export" [ref=e313]:
                          - img [ref=e314]
                      - button "import" [ref=e316]:
                        - img "import" [ref=e318]:
                          - img [ref=e319]
                      - button "tool" [ref=e321]:
                        - img "tool" [ref=e323]:
                          - img [ref=e324]
                      - button "delete" [ref=e326]:
                        - img "delete" [ref=e328]:
                          - img [ref=e329]
                - generic [ref=e332]:
                  - generic [ref=e333]: 🗂️
                  - generic [ref=e334]: 从左侧列表选择一个组件查看 / 编辑
                  - generic [ref=e335]: 组件 = 报价/核价中的一个页签或 Excel 表；定义列结构与公式
                  - button "查看配置帮助" [ref=e336] [cursor=pointer]:
                    - generic [ref=e337]: 查看配置帮助
  - dialog "导入组件到目录:T261026-CRYPTO-UI-DIR-1793082535746-172038" [ref=e339]:
    - generic [ref=e341]:
      - button "Close" [ref=e342] [cursor=pointer]:
        - img "close" [ref=e343]:
          - img [ref=e344]
      - generic [ref=e346]: 导入组件到目录:T261026-CRYPTO-UI-DIR-1793082535746-172038
    - generic [ref=e348]:
      - button "inbox 点击或拖拽导出的 bundle JSON 到此处 已载入:T261026-CRYPTO-UI-bundle.json" [ref=e352] [cursor=pointer]:
        - generic [ref=e353]:
          - paragraph [ref=e354]:
            - img "inbox" [ref=e355]:
              - img [ref=e356]
          - paragraph [ref=e358]: 点击或拖拽导出的 bundle JSON 到此处
          - paragraph [ref=e359]: 已载入:T261026-CRYPTO-UI-bundle.json
      - generic [ref=e361]:
        - generic [ref=e362]: "冲突策略:"
        - generic [ref=e364] [cursor=pointer]:
          - generic "跳过(同 code 已存在则跳过)" [ref=e365]:
            - text: 跳过(同 code 已存在则跳过)
            - combobox [ref=e366]
          - img "down" [ref=e368]:
            - img [ref=e369]
        - button "eye 预览" [active] [ref=e372] [cursor=pointer]:
          - img "eye" [ref=e374]:
            - img [ref=e375]
          - generic [ref=e377]: 预览
    - generic [ref=e379]:
      - button "关 闭" [ref=e381] [cursor=pointer]:
        - generic [ref=e382]: 关 闭
      - button "import 确认导入" [disabled] [ref=e384]:
        - generic:
          - img "import":
            - img
        - generic: 确认导入
  - generic [ref=e386]:
    - img "close-circle" [ref=e387]:
      - img [ref=e388]
    - generic [ref=e390]: bundle 为空或格式不正确(缺 components)
```

# Test source

```ts
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
  68  |   await expect(page.locator('input[placeholder*="搜索"]').first()).toBeVisible({ timeout: 60_000 });
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
> 134 |       '第一次预览必须以 multipart file 发送').toContain('multipart/form-data; boundary=');
      |                                     ^ Error: 第一次预览必须以 multipart file 发送
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
  169 |     expect(browserRequests[1].url).toContain('conflictPolicy=RENAME');
  170 |     expect(browserRequests[2].url).toContain('/import/commit');
  171 |     for (const request of browserRequests) {
  172 |       expect(request.contentType).toContain('multipart/form-data; boundary=');
  173 |       expect(request.url).not.toContain('/v1/inspect');
  174 |       expect(request.url).not.toContain('/v1/decrypt');
  175 |     }
  176 | 
  177 |     const capture = await fake('/__requests');
  178 |     const upstream = capture.requests as FakeCapture[];
  179 |     expect(upstream, 'Three browser actions must produce inspect -> decrypt each').toHaveLength(6);
  180 |     expect(upstream.map(item => item.path)).toEqual([
  181 |       '/v1/inspect', '/v1/decrypt', '/v1/inspect', '/v1/decrypt', '/v1/inspect', '/v1/decrypt',
  182 |     ]);
  183 |     const cipherHash = createHash('sha256').update(CIPHER).digest('hex');
  184 |     for (let index = 0; index < upstream.length; index += 2) {
  185 |       expect(upstream[index].size).toBe(CIPHER.length);
  186 |       expect(upstream[index + 1].size).toBe(CIPHER.length);
  187 |       expect(upstream[index].sha256).toBe(cipherHash);
  188 |       expect(upstream[index + 1].sha256).toBe(cipherHash);
  189 |       expect(upstream[index].encodedFileName).toBe('T261026-CRYPTO-UI-bundle.json');
  190 |       expect(upstream[index + 1].encodedFileName).toBe('T261026-CRYPTO-UI-bundle.json');
  191 |       expect(upstream[index].requestId).toBeTruthy();
  192 |       expect(upstream[index].requestId).toBe(upstream[index + 1].requestId);
  193 |       expect(upstream[index].authorizationMatched).toBeTruthy();
  194 |       expect(upstream[index + 1].authorizationMatched).toBeTruthy();
  195 |     }
  196 | 
  197 |     await page.goto('/dashboard');
  198 |     await page.goto('/components');
  199 |     await page.reload();
  200 |     const search = page.locator('input[placeholder*="搜索"]').first();
  201 |     await search.fill(COMPONENT_CODE);
  202 |     const reopenedDirectory = page.locator('.cmm-dir').filter({ hasText: DIR_NAME }).first();
  203 |     await expect(reopenedDirectory).toBeVisible();
  204 |     await reopenedDirectory.locator('.cmm-dir-head').click();
  205 |     await expect(reopenedDirectory.locator('.cmm-c-name').filter({ hasText: COMPONENT_CODE }).first(),
  206 |       'Commit result must survive drawer close, route switch and refresh').toBeVisible();
  207 | 
  208 |     await page.screenshot({ path: path.join(EVIDENCE, 'AC-4-final-component.png'), fullPage: true });
  209 |     fs.writeFileSync(path.join(EVIDENCE, 'AC-4-request-summary.json'), JSON.stringify({
  210 |       directoryId,
  211 |       componentCode: COMPONENT_CODE,
  212 |       browserRequests: browserRequests.map(request => ({
  213 |         url: request.url,
  214 |         contentType: request.contentType,
  215 |       })),
  216 |       upstream,
  217 |     }, null, 2));
  218 |   } finally {
  219 |     try {
  220 |       await deletePrivateFixture(page, directoryId);
  221 |     } finally {
  222 |       await context.tracing.stop({ path: path.join(EVIDENCE, 'AC-4-trace.zip') });
  223 |     }
  224 |   }
  225 | });
  226 | 
```