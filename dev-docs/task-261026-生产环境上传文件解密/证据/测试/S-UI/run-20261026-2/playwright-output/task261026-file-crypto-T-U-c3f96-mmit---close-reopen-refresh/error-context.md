# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: task261026-file-crypto.spec.ts >> T-U01 / AC-4: encrypted Bundle preview -> policy re-preview -> commit -> close/reopen/refresh
- Location: e2e/task261026-file-crypto.spec.ts:62:1

# Error details

```
Error: Commit result must survive drawer close, route switch and refresh

expect(locator).toBeVisible() failed

Locator:  getByText('T261026-CRYPTO-UI-COMP-1793081916978-162212').first()
Expected: visible
Received: hidden
Timeout:  20000ms

Call log:
  - Commit result must survive drawer close, route switch and refresh with timeout 20000ms
  - waiting for getByText('T261026-CRYPTO-UI-COMP-1793081916978-162212').first()
    21 × locator resolved to <div class="cmm-c-name">T261026-CRYPTO-UI-COMP-1793081916978-162212</div>
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
                      - textbox "🔍 搜索组件名 / 编码" [active] [ref=e90]: T261026-CRYPTO-UI-COMP-1793081916978-162212
                      - button "close-circle" [ref=e92] [cursor=pointer]:
                        - img "close-circle" [ref=e93]:
                          - img [ref=e94]
                    - button "plus 新建" [ref=e96] [cursor=pointer]:
                      - img "plus" [ref=e98]:
                        - img [ref=e99]
                      - generic [ref=e102]: 新建
                    - button "folder-add 新建目录" [ref=e103] [cursor=pointer]:
                      - img "folder-add" [ref=e105]:
                        - img [ref=e106]
                      - generic [ref=e108]: 新建目录
                  - generic [ref=e110] [cursor=pointer]:
                    - checkbox "显示停用" [ref=e112]
                    - generic [ref=e113]: 显示停用
                  - generic [ref=e114]:
                    - button "停用 / 启用" [disabled] [ref=e115]:
                      - generic: 停用 / 启用
                    - button "删 除" [disabled] [ref=e116]:
                      - generic: 删 除
                  - generic [ref=e117]: 勾选卡片左侧框可批量操作
                  - button "保存全部草稿" [disabled] [ref=e119]:
                    - generic: 保存全部草稿
                - generic [ref=e120]:
                  - generic [ref=e121] [cursor=pointer]:
                    - generic [ref=e122]: ▶
                    - generic [ref=e124]: 📁 取值配置器测试
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
                    - generic [ref=e152]:
                      - generic [ref=e153] [cursor=pointer]:
                        - generic [ref=e154]: ▶
                        - text: 页签组件
                        - generic [ref=e156]: ＋
                      - generic [ref=e157] [cursor=pointer]: ＋ 新建页签组件
                    - generic [ref=e158]:
                      - generic [ref=e159] [cursor=pointer]:
                        - generic [ref=e160]: ▶
                        - text: EXCEL 组件
                        - generic [ref=e162]: ＋
                      - generic [ref=e163] [cursor=pointer]: ＋ 新建 EXCEL 组件
                    - generic [ref=e164]:
                      - generic [ref=e165] [cursor=pointer]:
                        - generic [ref=e166]: ▶
                        - text: 小计组件
                        - generic [ref=e168]: ＋
                      - generic [ref=e169] [cursor=pointer]: ＋ 新建小计组件
                - generic [ref=e171] [cursor=pointer]:
                  - generic [ref=e172]: ▶
                  - generic [ref=e174]: 📁 核价组件
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
                  - generic [ref=e205]: 📁 取值配置器测试2
                  - generic [ref=e206]:
                    - button "edit" [ref=e207]:
                      - img "edit" [ref=e209]:
                        - img [ref=e210]
                    - button "export" [ref=e212]:
                      - img "export" [ref=e214]:
                        - img [ref=e215]
                    - button "import" [ref=e217]:
                      - img "import" [ref=e219]:
                        - img [ref=e220]
                    - button "tool" [ref=e222]:
                      - img "tool" [ref=e224]:
                        - img [ref=e225]
                    - button "delete" [ref=e227]:
                      - img "delete" [ref=e229]:
                        - img [ref=e230]
                - generic [ref=e233] [cursor=pointer]:
                  - generic [ref=e234]: ▶
                  - generic [ref=e236]: 📁 正泰组件库-1
                  - generic [ref=e237]:
                    - button "edit" [ref=e238]:
                      - img "edit" [ref=e240]:
                        - img [ref=e241]
                    - button "export" [ref=e243]:
                      - img "export" [ref=e245]:
                        - img [ref=e246]
                    - button "import" [ref=e248]:
                      - img "import" [ref=e250]:
                        - img [ref=e251]
                    - button "tool" [ref=e253]:
                      - img "tool" [ref=e255]:
                        - img [ref=e256]
                    - button "delete" [ref=e258]:
                      - img "delete" [ref=e260]:
                        - img [ref=e261]
                - generic [ref=e264] [cursor=pointer]:
                  - generic [ref=e265]: ▶
                  - generic [ref=e266]:
                    - generic [ref=e267]: 📁 T261026-CRYPTO-UI-DIR-1793081916978-162212
                    - generic [ref=e269]: 页签1
                  - generic [ref=e270]:
                    - button "edit" [ref=e271]:
                      - img "edit" [ref=e273]:
                        - img [ref=e274]
                    - button "export" [ref=e276]:
                      - img "export" [ref=e278]:
                        - img [ref=e279]
                    - button "import" [ref=e281]:
                      - img "import" [ref=e283]:
                        - img [ref=e284]
                    - button "tool" [ref=e286]:
                      - img "tool" [ref=e288]:
                        - img [ref=e289]
                    - button "delete" [ref=e291]:
                      - img "delete" [ref=e293]:
                        - img [ref=e294]
                - generic [ref=e297] [cursor=pointer]:
                  - generic [ref=e298]: ▶
                  - generic [ref=e300]: 📁 RT-SA-260915-3c601abe-AC16-EMPTY-SRC
                  - generic [ref=e301]:
                    - button "edit" [ref=e302]:
                      - img "edit" [ref=e304]:
                        - img [ref=e305]
                    - button "export" [ref=e307]:
                      - img "export" [ref=e309]:
                        - img [ref=e310]
                    - button "import" [ref=e312]:
                      - img "import" [ref=e314]:
                        - img [ref=e315]
                    - button "tool" [ref=e317]:
                      - img "tool" [ref=e319]:
                        - img [ref=e320]
                    - button "delete" [ref=e322]:
                      - img "delete" [ref=e324]:
                        - img [ref=e325]
              - generic [ref=e328]:
                - generic [ref=e329]: 🗂️
                - generic [ref=e330]: 从左侧列表选择一个组件查看 / 编辑
                - generic [ref=e331]: 组件 = 报价/核价中的一个页签或 Excel 表；定义列结构与公式
                - button "查看配置帮助" [ref=e332] [cursor=pointer]:
                  - generic [ref=e333]: 查看配置帮助
```

# Test source

```ts
  97  |   const browserRequests: Array<{ url: string; contentType: string }> = [];
  98  |   page.on('request', request => {
  99  |     if (request.method() !== 'POST' || !request.url().includes(`/component-directories/${directoryId}/import`)) return;
  100 |     browserRequests.push({
  101 |       url: request.url(),
  102 |       contentType: request.headers()['content-type'] || '',
  103 |     });
  104 |   });
  105 | 
  106 |   try {
  107 |     await page.reload();
  108 |     await expect(page.getByText(DIR_NAME, { exact: false }).first()).toBeVisible();
  109 |     const directory = page.locator('.cmm-dir').filter({ hasText: DIR_NAME }).first();
  110 |     const importButton = directory.locator('.cmm-dir-acts button')
  111 |       .filter({ has: page.locator('.anticon-import') }).first();
  112 |     await expect(importButton).toBeVisible();
  113 |     await importButton.click();
  114 | 
  115 |     const panel = page.locator('.ant-modal, .ant-drawer').filter({ hasText: /导入组件到目录/ }).last();
  116 |     await expect(panel).toBeVisible();
  117 |     await panel.locator('input[type=file]').first().setInputFiles({
  118 |       name: 'T261026-CRYPTO-UI-bundle.json',
  119 |       mimeType: 'application/json',
  120 |       buffer: CIPHER,
  121 |     });
  122 | 
  123 |     await choosePolicy(page, panel, /跳过/);
  124 |     const previewSkip = page.waitForResponse(response =>
  125 |       response.request().method() === 'POST'
  126 |       && response.url().includes(`/component-directories/${directoryId}/import`)
  127 |       && !response.url().includes('/commit'));
  128 |     await panel.getByRole('button', { name: /预\s*览/ }).click();
  129 |     const previewSkipResponse = await previewSkip;
  130 |     expect(previewSkipResponse.ok()).toBeTruthy();
  131 |     const previewSkipJson = await previewSkipResponse.json();
  132 |     expect(previewSkipJson?.data?.conflictPolicy,
  133 |       '第一次预览结果必须明确对应 SKIP 策略').toBe('SKIP');
  134 |     await expect(panel.getByText(COMPONENT_CODE, { exact: false }).first(),
  135 |       '第一次预览必须渲染解密后的非空组件').toBeVisible();
  136 | 
  137 |     await choosePolicy(page, panel, /重命名/);
  138 |     const previewRename = page.waitForResponse(response =>
  139 |       response.request().method() === 'POST'
  140 |       && response.url().includes(`/component-directories/${directoryId}/import`)
  141 |       && !response.url().includes('/commit'));
  142 |     await panel.getByRole('button', { name: /预\s*览/ }).click();
  143 |     const previewRenameResponse = await previewRename;
  144 |     expect(previewRenameResponse.ok()).toBeTruthy();
  145 |     const previewRenameJson = await previewRenameResponse.json();
  146 |     expect(previewRenameJson?.data?.conflictPolicy,
  147 |       '第二次预览结果必须明确对应 RENAME 策略').toBe('RENAME');
  148 |     await expect(panel.getByText(COMPONENT_CODE, { exact: false }).first(),
  149 |       '策略改变后的第二次预览必须仍来自同一原始文件').toBeVisible();
  150 | 
  151 |     const commit = page.waitForResponse(response =>
  152 |       response.request().method() === 'POST'
  153 |       && response.url().includes(`/component-directories/${directoryId}/import/commit`));
  154 |     await panel.getByRole('button', { name: /确认导入|提\s*交|导\s*入/ }).last().click();
  155 |     expect((await commit).ok()).toBeTruthy();
  156 | 
  157 |     const committedComponents = await componentsInDirectory(page, directoryId);
  158 |     expect(committedComponents.filter((item: any) => item.code === COMPONENT_CODE),
  159 |       '两次预览只能产生一次提交，唯一组件 code 必须恰好一条').toHaveLength(1);
  160 | 
  161 |     expect(browserRequests, 'preview + re-preview + commit request count').toHaveLength(3);
  162 |     expect(browserRequests[0].url).toContain('conflictPolicy=SKIP');
  163 |     expect(browserRequests[1].url).toContain('conflictPolicy=RENAME');
  164 |     expect(browserRequests[2].url).toContain('/import/commit');
  165 |     for (const request of browserRequests) {
  166 |       expect(request.contentType).toContain('multipart/form-data; boundary=');
  167 |       expect(request.url).not.toContain('/v1/inspect');
  168 |       expect(request.url).not.toContain('/v1/decrypt');
  169 |     }
  170 | 
  171 |     const capture = await fake('/__requests');
  172 |     const upstream = capture.requests as FakeCapture[];
  173 |     expect(upstream, 'Three browser actions must produce inspect -> decrypt each').toHaveLength(6);
  174 |     expect(upstream.map(item => item.path)).toEqual([
  175 |       '/v1/inspect', '/v1/decrypt', '/v1/inspect', '/v1/decrypt', '/v1/inspect', '/v1/decrypt',
  176 |     ]);
  177 |     const cipherHash = createHash('sha256').update(CIPHER).digest('hex');
  178 |     for (let index = 0; index < upstream.length; index += 2) {
  179 |       expect(upstream[index].size).toBe(CIPHER.length);
  180 |       expect(upstream[index + 1].size).toBe(CIPHER.length);
  181 |       expect(upstream[index].sha256).toBe(cipherHash);
  182 |       expect(upstream[index + 1].sha256).toBe(cipherHash);
  183 |       expect(upstream[index].encodedFileName).toBe('T261026-CRYPTO-UI-bundle.json');
  184 |       expect(upstream[index + 1].encodedFileName).toBe('T261026-CRYPTO-UI-bundle.json');
  185 |       expect(upstream[index].requestId).toBeTruthy();
  186 |       expect(upstream[index].requestId).toBe(upstream[index + 1].requestId);
  187 |       expect(upstream[index].authorizationMatched).toBeTruthy();
  188 |       expect(upstream[index + 1].authorizationMatched).toBeTruthy();
  189 |     }
  190 | 
  191 |     await page.goto('/dashboard');
  192 |     await page.goto('/components');
  193 |     await page.reload();
  194 |     const search = page.locator('input[placeholder*="搜索"]').first();
  195 |     await search.fill(COMPONENT_CODE);
  196 |     await expect(page.getByText(COMPONENT_CODE, { exact: false }).first(),
> 197 |       'Commit result must survive drawer close, route switch and refresh').toBeVisible();
      |                                                                            ^ Error: Commit result must survive drawer close, route switch and refresh
  198 | 
  199 |     await page.screenshot({ path: path.join(EVIDENCE, 'AC-4-final-component.png'), fullPage: true });
  200 |     fs.writeFileSync(path.join(EVIDENCE, 'AC-4-request-summary.json'), JSON.stringify({
  201 |       directoryId,
  202 |       componentCode: COMPONENT_CODE,
  203 |       browserRequests: browserRequests.map(request => ({
  204 |         url: request.url,
  205 |         contentType: request.contentType,
  206 |       })),
  207 |       upstream,
  208 |     }, null, 2));
  209 |   } finally {
  210 |     await deletePrivateFixture(page, directoryId);
  211 |   }
  212 | });
  213 | 
```