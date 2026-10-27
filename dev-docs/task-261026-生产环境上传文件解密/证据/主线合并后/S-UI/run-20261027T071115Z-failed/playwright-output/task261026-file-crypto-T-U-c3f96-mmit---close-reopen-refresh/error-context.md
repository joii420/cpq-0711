# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: task261026-file-crypto.spec.ts >> T-U01 / AC-4: encrypted Bundle preview -> policy re-preview -> commit -> close/reopen/refresh
- Location: e2e/task261026-file-crypto.spec.ts:62:1

# Error details

```
TimeoutError: locator.click: Timeout 20000ms exceeded.
Call log:
  - waiting for locator('.cmm-dir').filter({ hasText: 'T261026-CRYPTO-UI-DIR-1793085041480-208360' }).first().locator('.cmm-sec').filter({ hasText: '页签组件' }).first().getByText('页签组件', { exact: true }).first()

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
          - tabpanel "组件" [active] [ref=e84]:
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
                - generic [ref=e269]:
                  - generic [ref=e270] [cursor=pointer]:
                    - generic [ref=e271]: ▶
                    - generic [ref=e272]:
                      - generic [ref=e273]: 📁 T261026-CRYPTO-UI-DIR-1793085041480-208360
                      - generic [ref=e275]: 页签1
                    - generic [ref=e276]:
                      - button "edit" [ref=e277]:
                        - img "edit" [ref=e279]:
                          - img [ref=e280]
                      - button "export" [ref=e282]:
                        - img "export" [ref=e284]:
                          - img [ref=e285]
                      - button "import" [ref=e287]:
                        - img "import" [ref=e289]:
                          - img [ref=e290]
                      - button "tool" [ref=e292]:
                        - img "tool" [ref=e294]:
                          - img [ref=e295]
                      - button "delete" [ref=e297]:
                        - img "delete" [ref=e299]:
                          - img [ref=e300]
                  - generic [ref=e302]:
                    - generic [ref=e304] [cursor=pointer]:
                      - generic [ref=e305]: ▶
                      - text: 页签组件
                      - generic [ref=e307]: ＋
                    - generic [ref=e309] [cursor=pointer]:
                      - generic [ref=e310]: ▶
                      - text: EXCEL 组件
                      - generic [ref=e312]: ＋
                    - generic [ref=e314] [cursor=pointer]:
                      - generic [ref=e315]: ▶
                      - text: 小计组件
                      - generic [ref=e317]: ＋
                - generic [ref=e319] [cursor=pointer]:
                  - generic [ref=e320]: ▶
                  - generic [ref=e322]: 📁 RT-SA-260915-3c601abe-AC16-EMPTY-SRC
                  - generic [ref=e323]:
                    - button "edit" [ref=e324]:
                      - img "edit" [ref=e326]:
                        - img [ref=e327]
                    - button "export" [ref=e329]:
                      - img "export" [ref=e331]:
                        - img [ref=e332]
                    - button "import" [ref=e334]:
                      - img "import" [ref=e336]:
                        - img [ref=e337]
                    - button "tool" [ref=e339]:
                      - img "tool" [ref=e341]:
                        - img [ref=e342]
                    - button "delete" [ref=e344]:
                      - img "delete" [ref=e346]:
                        - img [ref=e347]
              - generic [ref=e350]:
                - generic [ref=e351]: 🗂️
                - generic [ref=e352]: 从左侧列表选择一个组件查看 / 编辑
                - generic [ref=e353]: 组件 = 报价/核价中的一个页签或 Excel 表；定义列结构与公式
                - button "查看配置帮助" [ref=e354] [cursor=pointer]:
                  - generic [ref=e355]: 查看配置帮助
```

# Test source

```ts
  110 |     await page.reload();
  111 |     await expect(page.getByText(DIR_NAME, { exact: false }).first()).toBeVisible();
  112 |     const directory = page.locator('.cmm-dir').filter({ hasText: DIR_NAME }).first();
  113 |     const importButton = directory.locator('.cmm-dir-acts button')
  114 |       .filter({ has: page.locator('.anticon-import') }).first();
  115 |     await expect(importButton).toBeVisible();
  116 |     await importButton.click();
  117 | 
  118 |     const panel = page.locator('.ant-modal, .ant-drawer').filter({ hasText: /导入组件到目录/ }).last();
  119 |     await expect(panel).toBeVisible();
  120 |     await panel.locator('input[type=file]').first().setInputFiles({
  121 |       name: 'T261026-CRYPTO-UI-bundle.json',
  122 |       mimeType: 'application/json',
  123 |       buffer: CIPHER,
  124 |     });
  125 | 
  126 |     await choosePolicy(page, panel, /跳过/);
  127 |     const previewSkip = page.waitForResponse(response =>
  128 |       response.request().method() === 'POST'
  129 |       && response.url().includes(`/component-directories/${directoryId}/import`)
  130 |       && !response.url().includes('/commit'));
  131 |     await panel.getByRole('button', { name: /预\s*览/ }).click();
  132 |     await expect.poll(() => browserRequests.length,
  133 |       { message: '第一次预览必须发出浏览器请求' }).toBe(1);
  134 |     expect(browserRequests[0].contentType,
  135 |       '第一次预览必须以 multipart file 发送').toContain('multipart/form-data; boundary=');
  136 |     const previewSkipResponse = await previewSkip;
  137 |     expect(previewSkipResponse.ok()).toBeTruthy();
  138 |     const previewSkipJson = await previewSkipResponse.json();
  139 |     expect(previewSkipJson?.data?.conflictPolicy,
  140 |       '第一次预览结果必须明确对应 SKIP 策略').toBe('SKIP');
  141 |     await expect(panel.getByText(COMPONENT_CODE, { exact: false }).first(),
  142 |       '第一次预览必须渲染解密后的非空组件').toBeVisible();
  143 | 
  144 |     await choosePolicy(page, panel, /重命名/);
  145 |     const previewRename = page.waitForResponse(response =>
  146 |       response.request().method() === 'POST'
  147 |       && response.url().includes(`/component-directories/${directoryId}/import`)
  148 |       && !response.url().includes('/commit'));
  149 |     await panel.getByRole('button', { name: /预\s*览/ }).click();
  150 |     const previewRenameResponse = await previewRename;
  151 |     expect(previewRenameResponse.ok()).toBeTruthy();
  152 |     const previewRenameJson = await previewRenameResponse.json();
  153 |     expect(previewRenameJson?.data?.conflictPolicy,
  154 |       '第二次预览结果必须明确对应 RENAME 策略').toBe('RENAME');
  155 |     await expect(panel.getByText(COMPONENT_CODE, { exact: false }).first(),
  156 |       '策略改变后的第二次预览必须仍来自同一原始文件').toBeVisible();
  157 | 
  158 |     const commit = page.waitForResponse(response =>
  159 |       response.request().method() === 'POST'
  160 |       && response.url().includes(`/component-directories/${directoryId}/import/commit`));
  161 |     await panel.getByRole('button', { name: /确认导入|提\s*交|导\s*入/ }).last().click();
  162 |     expect((await commit).ok()).toBeTruthy();
  163 | 
  164 |     const committedComponents = await componentsInDirectory(page, directoryId);
  165 |     expect(committedComponents.filter((item: any) => item.code === COMPONENT_CODE),
  166 |       '两次预览只能产生一次提交，唯一组件 code 必须恰好一条').toHaveLength(1);
  167 | 
  168 |     expect(browserRequests, 'preview + re-preview + commit request count').toHaveLength(3);
  169 |     expect(browserRequests[0].url).toContain('conflictPolicy=SKIP');
  170 |     expect(browserRequests[1].url).toContain('conflictPolicy=RENAME');
  171 |     expect(browserRequests[2].url).toContain('/import/commit');
  172 |     for (const request of browserRequests) {
  173 |       expect(request.contentType).toContain('multipart/form-data; boundary=');
  174 |       expect(request.url).not.toContain('/v1/inspect');
  175 |       expect(request.url).not.toContain('/v1/decrypt');
  176 |     }
  177 | 
  178 |     const capture = await fake('/__requests');
  179 |     const upstream = capture.requests as FakeCapture[];
  180 |     expect(upstream, 'Three browser actions must produce inspect -> decrypt each').toHaveLength(6);
  181 |     expect(upstream.map(item => item.path)).toEqual([
  182 |       '/v1/inspect', '/v1/decrypt', '/v1/inspect', '/v1/decrypt', '/v1/inspect', '/v1/decrypt',
  183 |     ]);
  184 |     const cipherHash = createHash('sha256').update(CIPHER).digest('hex');
  185 |     for (let index = 0; index < upstream.length; index += 2) {
  186 |       expect(upstream[index].size).toBe(CIPHER.length);
  187 |       expect(upstream[index + 1].size).toBe(CIPHER.length);
  188 |       expect(upstream[index].sha256).toBe(cipherHash);
  189 |       expect(upstream[index + 1].sha256).toBe(cipherHash);
  190 |       expect(upstream[index].encodedFileName).toBe('T261026-CRYPTO-UI-bundle.json');
  191 |       expect(upstream[index + 1].encodedFileName).toBe('T261026-CRYPTO-UI-bundle.json');
  192 |       expect(upstream[index].requestId).toBeTruthy();
  193 |       expect(upstream[index].requestId).toBe(upstream[index + 1].requestId);
  194 |       expect(upstream[index].authorizationMatched).toBeTruthy();
  195 |       expect(upstream[index + 1].authorizationMatched).toBeTruthy();
  196 |     }
  197 | 
  198 |     await page.goto('/dashboard');
  199 |     await page.goto('/components');
  200 |     await page.reload();
  201 |     const reopenedDirectory = page.locator('.cmm-dir').filter({ hasText: DIR_NAME }).first();
  202 |     await expect(reopenedDirectory).toBeVisible();
  203 |     if (!await reopenedDirectory.evaluate(element => element.classList.contains('open'))) {
  204 |       await reopenedDirectory.locator('.cmm-dir-head').click();
  205 |     }
  206 |     const normalSection = reopenedDirectory.locator('.cmm-sec').filter({ hasText: '页签组件' }).first();
  207 |     await expect(normalSection, '私有目录内应有“页签组件”分组').toBeVisible();
  208 |     const reopenedComponent = normalSection.locator('.cmm-c-name').filter({ hasText: COMPONENT_CODE }).first();
  209 |     if (!await reopenedComponent.isVisible()) {
> 210 |       await normalSection.getByText('页签组件', { exact: true }).first().click();
      |                                                                      ^ TimeoutError: locator.click: Timeout 20000ms exceeded.
  211 |     }
  212 |     await expect(reopenedComponent,
  213 |       'Commit result must survive drawer close, route switch and refresh').toBeVisible();
  214 | 
  215 |     await page.screenshot({ path: path.join(EVIDENCE, 'AC-4-final-component.png'), fullPage: true });
  216 |     fs.writeFileSync(path.join(EVIDENCE, 'AC-4-request-summary.json'), JSON.stringify({
  217 |       directoryId,
  218 |       componentCode: COMPONENT_CODE,
  219 |       browserRequests: browserRequests.map(request => ({
  220 |         url: request.url,
  221 |         contentType: request.contentType,
  222 |       })),
  223 |       upstream,
  224 |     }, null, 2));
  225 |   } finally {
  226 |     try {
  227 |       await deletePrivateFixture(page, directoryId);
  228 |     } finally {
  229 |       await context.tracing.stop({ path: path.join(EVIDENCE, 'AC-4-trace.zip') });
  230 |     }
  231 |   }
  232 | });
  233 | 
```