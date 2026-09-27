# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: quotation-flow.spec.ts >> LEGACY SIMPLE smoke · 报价单流程: 苏州西门子 + 报价模板0608 v1.10 + 10110002(渲染层无回归)
- Location: e2e/quotation-flow.spec.ts:144:1

# Error details

```
Test timeout of 30000ms exceeded.
```

```
Error: locator.click: Test timeout of 30000ms exceeded.
Call log:
  - waiting for locator('.ant-form-item').filter({ has: locator('label').filter({ hasText: '报价模板' }) }).first().locator('.ant-select').first()

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
      - generic [ref=e70]:
        - generic [ref=e72]:
          - generic [ref=e75]: 新建报价单
          - button "返回列表" [ref=e79] [cursor=pointer]:
            - generic [ref=e80]: 返回列表
        - generic [ref=e81]:
          - generic [ref=e82]:
            - generic [ref=e84]:
              - generic [ref=e86]: "1"
              - generic [ref=e89]: 选择客户
            - generic [ref=e92]:
              - generic [ref=e94]: "2"
              - generic [ref=e97]: 添加产品
            - generic [ref=e100]:
              - generic [ref=e102]: "3"
              - generic [ref=e105]: 优惠策略
            - generic [ref=e108]:
              - generic [ref=e110]: "4"
              - generic [ref=e113]: 交易条款
            - generic [ref=e116]:
              - generic [ref=e118]: "5"
              - generic [ref=e121]: 提交审批
          - generic [ref=e124]:
            - generic [ref=e125]:
              - generic [ref=e126]:
                - generic [ref=e127]:
                  - generic [ref=e130]: 报价单基本信息
                  - generic [ref=e131]:
                    - generic [ref=e133]:
                      - generic "客户" [ref=e135]: "* 客户"
                      - generic [ref=e139]:
                        - generic "苏州西门子 (8000137)" [ref=e140]:
                          - text: 苏州西门子 (8000137)
                          - combobox "* 客户" [ref=e141]
                        - img "down" [ref=e143]:
                          - img [ref=e144]
                    - generic [ref=e146]:
                      - generic [ref=e149]:
                        - generic "项目名称" [ref=e151]
                        - textbox "项目名称" [ref=e155]:
                          - /placeholder: 关联项目
                      - generic [ref=e158]:
                        - generic "商机编号" [ref=e160]
                        - textbox "商机编号" [ref=e164]:
                          - /placeholder: 商机ID
                    - generic [ref=e165]:
                      - generic [ref=e168]:
                        - generic "报价类型" [ref=e170]
                        - generic [ref=e174] [cursor=pointer]:
                          - generic "标准报价" [ref=e175]:
                            - text: 标准报价
                            - combobox "报价类型" [ref=e176]
                          - img "down" [ref=e178]:
                            - img [ref=e179]
                      - generic [ref=e183]:
                        - generic "优先级" [ref=e185]
                        - generic [ref=e189] [cursor=pointer]:
                          - generic "中" [ref=e190]:
                            - text: 中
                            - combobox "优先级" [ref=e191]
                          - img "down" [ref=e193]:
                            - img [ref=e194]
                      - generic [ref=e198]:
                        - generic "阶段" [ref=e200]
                        - generic [ref=e204] [cursor=pointer]:
                          - generic "初步接洽" [ref=e205]:
                            - text: 初步接洽
                            - combobox "阶段" [ref=e206]
                          - img "down" [ref=e208]:
                            - img [ref=e209]
                    - generic [ref=e212]:
                      - generic "预计成交日" [ref=e214]
                      - generic [ref=e219]:
                        - textbox "预计成交日" [ref=e220]:
                          - /placeholder: Select date
                        - generic:
                          - img "calendar":
                            - img
                - generic [ref=e221]:
                  - generic [ref=e224]: 联系人
                  - generic [ref=e225]:
                    - generic [ref=e227]:
                      - generic "选择联系人" [ref=e229]
                      - generic [ref=e233] [cursor=pointer]:
                        - generic [ref=e234]:
                          - generic: 选择联系人
                          - combobox "选择联系人" [ref=e235]
                        - img "down" [ref=e237]:
                          - img [ref=e238]
                    - generic [ref=e240]:
                      - generic [ref=e243]:
                        - generic "联系人姓名" [ref=e245]
                        - textbox "联系人姓名" [ref=e249]
                      - generic [ref=e252]:
                        - generic "电话" [ref=e254]
                        - textbox "电话" [ref=e258]
                      - generic [ref=e261]:
                        - generic "邮箱" [ref=e263]
                        - textbox "邮箱" [ref=e267]
              - generic [ref=e269]:
                - generic [ref=e272]: 客户概况
                - table [ref=e276]:
                  - rowgroup [ref=e277]:
                    - 'row "名称 : 苏州西门子" [ref=e278]':
                      - 'cell "名称 : 苏州西门子" [ref=e279]':
                        - generic [ref=e280]:
                          - generic [ref=e281]: "名称 :"
                          - generic [ref=e282]: 苏州西门子
                    - 'row "编码 : 8000137" [ref=e283]':
                      - 'cell "编码 : 8000137" [ref=e284]':
                        - generic [ref=e285]:
                          - generic [ref=e286]: "编码 :"
                          - generic [ref=e287]: "8000137"
                    - 'row "等级 : STANDARD" [ref=e288]':
                      - 'cell "等级 : STANDARD" [ref=e289]':
                        - generic [ref=e290]:
                          - generic [ref=e291]: "等级 :"
                          - generic [ref=e293]: STANDARD
                    - 'row "行业 : -" [ref=e294]':
                      - 'cell "行业 : -" [ref=e295]':
                        - generic [ref=e296]:
                          - generic [ref=e297]: "行业 :"
                          - generic [ref=e298]: "-"
                    - 'row "区域 : -" [ref=e299]':
                      - 'cell "区域 : -" [ref=e300]':
                        - generic [ref=e301]:
                          - generic [ref=e302]: "区域 :"
                          - generic [ref=e303]: "-"
                    - 'row "累计金额 : ¥0" [ref=e304]':
                      - 'cell "累计金额 : ¥0" [ref=e305]':
                        - generic [ref=e306]:
                          - generic [ref=e307]: "累计金额 :"
                          - generic [ref=e308]: ¥0
            - generic [ref=e309]:
              - generic [ref=e313]: 选择模板 决定产品卡片 / Excel 视图的结构,以及"选配添加"使用的组件集
              - generic [ref=e315]:
                - generic [ref=e317]:
                  - generic "客户" [ref=e319]
                  - textbox [disabled] [ref=e323]: 苏州西门子
                - generic [ref=e325]:
                  - generic "报价单名称" [ref=e327]: "* 报价单名称"
                  - generic [ref=e331]:
                    - textbox "请填写报价单名称" [ref=e332]: E2E-test-1790240845494
                    - generic [ref=e334]: 22 / 100
                - generic [ref=e337]:
                  - generic "产品分类" [ref=e339]:
                    - text: "* 产品分类"
                    - img "question-circle" [ref=e341]:
                      - img [ref=e342]
                  - generic [ref=e347]:
                    - generic: 默认分类
                    - generic [ref=e348]:
                      - generic "默认分类" [ref=e349]:
                        - text: 默认分类
                        - combobox [active] [ref=e350]
                      - img "down" [ref=e352]:
                        - img [ref=e353]
                      - img "close-circle" [ref=e356] [cursor=pointer]:
                        - img [ref=e357]
                - alert [ref=e359]:
                  - img "exclamation-circle" [ref=e361]:
                    - img [ref=e362]
                  - generic [ref=e364]:
                    - generic [ref=e365]: 未找到适用的报价模板
                    - generic [ref=e366]: 该客户在此产品分类下没有客户专属模板，系统中也没有通用模板。建议先去「模板配置」配置一个模板。
                - generic [ref=e368]:
                  - generic [ref=e370]:
                    - generic [ref=e371]:
                      - text: 核价模板
                      - generic [ref=e372]: 通用兜底
                    - img "question-circle" [ref=e374]:
                      - img [ref=e375]
                  - generic [ref=e381] [cursor=pointer]:
                    - generic "核价模板1 v1.2 (通用)" [ref=e382]:
                      - text: 核价模板1 v1.2 (通用)
                      - combobox [ref=e383]
                    - img "down" [ref=e385]:
                      - img [ref=e386]
                    - img "close-circle" [ref=e389]:
                      - img [ref=e390]
          - separator [ref=e392]
          - generic [ref=e393]:
            - button "上一步" [disabled] [ref=e394]:
              - generic: 上一步
            - button "下一步" [disabled] [ref=e395]:
              - generic: 下一步
```

# Test source

```ts
  108 |   const createRes = await fetch(`${BACKEND_URL}/api/cpq/quotations`, {
  109 |     method: 'POST',
  110 |     headers: {
  111 |       'Content-Type': 'application/json; charset=utf-8',
  112 |       Cookie: sessionCookie,
  113 |     },
  114 |     body: JSON.stringify(payload),
  115 |   });
  116 |   if (!createRes.ok) {
  117 |     const errText = await createRes.text();
  118 |     throw new Error(`创建报价单失败: ${createRes.status} ${errText}`);
  119 |   }
  120 |   const created = await createRes.json();
  121 |   const createdData = created.data ?? created;
  122 |   const id = createdData.id ?? createdData.quotationId ?? created.id ?? created.quotationId;
  123 |   if (!id) throw new Error(`创建报价单响应中无 id 字段: ${JSON.stringify(created)}`);
  124 |   console.log(`[createMinimalDraft] 创建测试报价单成功 id=${id}`);
  125 |   return String(id);
  126 | }
  127 | 
  128 | /**
  129 |  * 从当前页面 context 中提取 session cookie 字符串（用于直连 API）
  130 |  */
  131 | async function extractSessionCookie(page: Page): Promise<string> {
  132 |   const cookies = await page.context().cookies(BACKEND_URL);
  133 |   return cookies.map(c => `${c.name}=${c.value}`).join('; ');
  134 | }
  135 | 
  136 | let backendUp = false;
  137 | test.beforeAll(async () => { backendUp = await isBackendUp(); });
  138 | 
  139 | // ═══════════════════════════════════════════════════════════════════════
  140 | // 主流程用例（原有）：新建报价单 → 添加产品 → 逐 Tab 渲染验证
  141 | // 2026-06-18 Task F 更新：在此用例内额外监听 POST refresh-card-snapshot，
  142 | // 断言整个新建+添加产品流程中不应触发自动重刷（B1 删除后的回归保障）。
  143 | // ═══════════════════════════════════════════════════════════════════════
  144 | test('LEGACY SIMPLE smoke · 报价单流程: 苏州西门子 + 报价模板0608 v1.10 + 10110002(渲染层无回归)', async ({ page }) => {
  145 |   if (!backendUp) throw new Error('后端未启动；TC-075 SIMPLE 不允许跳过');
  146 | 
  147 |   // 控制台错误监控
  148 |   const consoleErrors: string[] = [];
  149 |   const lfDebug: string[] = [];
  150 |   page.on('console', (m) => {
  151 |     const text = m.text();
  152 |     if (m.type() === 'error') consoleErrors.push(text);
  153 |     if (text.includes('[LF-')) lfDebug.push(text);
  154 |   });
  155 |   page.on('pageerror', (e) => consoleErrors.push('PAGE-ERROR: ' + e.message));
  156 | 
  157 |   // PUT /quotations/{id}/draft 计数(验证自动保存不死循环)
  158 |   // 顺带捕获自动保存命中的报价单 id —— 末尾"懒算护栏"用例需要 reopen 这张
  159 |   // 已建好(含产品 + 卡片值快照)的报价单来验证打开阶段无 batch-expand 风暴。
  160 |   let draftPutCount = 0;
  161 |   let savedQuotationId: string | null = null;
  162 |   page.on('request', (req) => {
  163 |     if (req.method() === 'PUT') {
  164 |       const m = req.url().match(/\/quotations\/([^/]+)\/draft/);
  165 |       if (m) { draftPutCount += 1; savedQuotationId = m[1]; }
  166 |     }
  167 |   });
  168 | 
  169 |   // ── 1) 登录 (用项目 fixture) ──
  170 |   await loginAsAdmin(page);
  171 |   await shot(page, 'after-login');
  172 | 
  173 |   // ── 2) 进入新建报价单 ──
  174 |   await page.goto('/quotations/new');
  175 |   await page.waitForLoadState('networkidle');
  176 |   await shot(page, 'step1-init');
  177 | 
  178 |   // ── 3) 选客户: 苏州西门子 (label-based) ──
  179 |   await selectByLabel(page, '客户', '西门子');
  180 |   await shot(page, 'customer-selected');
  181 | 
  182 |   // ── 4) 等 QuotationCreateForm 子卡片渲染 + 滚动让它入视野 ──
  183 |   await page.locator('text=产品分类').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
  184 |   await page.locator('text=产品分类').first().scrollIntoViewIfNeeded().catch(() => {});
  185 |   await page.waitForTimeout(500);
  186 |   await shot(page, 'after-customer');
  187 | 
  188 |   // 报价单名称 (在 QuotationCreateForm 子卡片里, placeholder="请填写报价单名称")
  189 |   await page.locator('input[placeholder*="报价单名称"]').first().fill('E2E-test-' + Date.now());
  190 |   await page.waitForTimeout(200);
  191 |   await shot(page, 'name-filled');
  192 | 
  193 |   // 选产品分类
  194 |   await selectByLabel(page, '产品分类', '默认分类');
  195 |   await page.keyboard.press('Escape');  // 关闭下拉, 确保受控 onChange 提交(否则 step1Valid 可能不翻 true)
  196 |   await page.waitForTimeout(300);
  197 |   await shot(page, 'category-selected');
  198 | 
  199 |   // ── 6) 报价模板: 报价模板0608 **动态选最新版** ──
  200 |   // 该模板会被反复重发布(v1.0..v1.N 持续增长), 硬编码某版本会因版本漂移 + antd 虚拟滚动
  201 |   // (20+ 选项, 目标版本可能不在初始渲染窗口)而点不到超时。改为: 输入 "0608" 过滤后,
  202 |   // 滚动 dropdown 收集所有 "报价模板0608 vX.Y" 选项, 解析版本号取最大者点击 —— 与发布版本数解耦。
  203 |   await page.waitForTimeout(1200);  // 等模板加载
  204 |   {
  205 |     const templateItem = page.locator('.ant-form-item')
  206 |       .filter({ has: page.locator('label', { hasText: '报价模板' }) })
  207 |       .first();
> 208 |     await templateItem.locator('.ant-select').first().click();
      |                                                       ^ Error: locator.click: Test timeout of 30000ms exceeded.
  209 |     await page.waitForTimeout(300);
  210 | 
  211 |     // 输入 "0608" 缩小候选集
  212 |     await page.keyboard.type('0608', { delay: 60 });
  213 |     await page.waitForTimeout(900);
  214 | 
  215 |     // 下拉按版本 newest-first 排序: 最新版在初始渲染窗口顶部、可直接点击。
  216 |     // 不滚动(滚到底会把顶部的最新版滚出虚拟 DOM 导致点不到), 从初始渲染项解析版本取最大。
  217 |     const versions = new Map<string, [number, number]>();  // "v1.19" -> [1,19]
  218 |     const texts = await page.locator('.ant-select-item-option')
  219 |       .filter({ hasText: /报价模板0608\s+v\d+\.\d+/ }).allInnerTexts();
  220 |     for (const t of texts) {
  221 |       const m = t.match(/报价模板0608\s+v(\d+)\.(\d+)/);
  222 |       if (m) versions.set(`v${m[1]}.${m[2]}`, [parseInt(m[1], 10), parseInt(m[2], 10)]);
  223 |     }
  224 |     // 取最大版本 (先比 major 再比 minor)
  225 |     let bestKey = '';
  226 |     let best: [number, number] = [-1, -1];
  227 |     for (const [k, v] of versions) {
  228 |       if (v[0] > best[0] || (v[0] === best[0] && v[1] > best[1])) { best = v; bestKey = k; }
  229 |     }
  230 |     if (!bestKey) throw new Error('未在下拉中找到任何 报价模板0608 vX.Y 选项');
  231 |     console.log(`[template] 动态选最新版: 报价模板0608 ${bestKey} (候选 ${versions.size} 个)`);
  232 | 
  233 |     // 行尾锚精确匹配该版本(防 v1.1 撞 v1.10/v1.19)
  234 |     const re = new RegExp(`报价模板0608\\s+${bestKey.replace('.', '\\.')}(\\s|$|<)`);
  235 |     const opt = page.locator('.ant-select-item-option').filter({ hasText: re }).first();
  236 |     await opt.scrollIntoViewIfNeeded().catch(() => {});
  237 |     await opt.click();
  238 |     await page.waitForTimeout(400);
  239 |     await page.keyboard.press('Escape');  // 关闭下拉, 提交受控值(搜索态下 antd 偶尔不 commit → step1Valid 卡 false)
  240 |     await page.waitForTimeout(300);
  241 |   }
  242 |   await shot(page, 'template-selected');
  243 | 
  244 |   // ── 7) 下一步 → Step2 (等校验通过, 按钮 enabled 再点) ──
  245 |   const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
  246 |   await expect(nextBtn, 'Step1 校验应通过(产品分类+报价模板已选), 下一步按钮应可点').toBeEnabled({ timeout: 15000 });
  247 |   await nextBtn.click();
  248 |   await page.waitForLoadState('networkidle');
  249 |   await page.waitForTimeout(1500);
  250 |   await shot(page, 'step2-empty');
  251 |   await countLoading(page, 'step2-empty');
  252 | 
  253 |   // ── 8) 点 "+ 添加产品" → "选配添加" ──
  254 |   await page.getByRole('button', { name: /添加产品/ }).first().click();
  255 |   await page.waitForTimeout(500);
  256 |   await shot(page, 'add-product-dropdown');
  257 |   // dropdown 项 "选配添加"
  258 |   await page.locator('text=选配添加').first().click();
  259 |   await page.waitForTimeout(800);
  260 |   await shot(page, 'configure-drawer-p0');
  261 | 
  262 |   // ── 9) 选配添加抽屉（task-0712 F5 明细表重构，D11）：
  263 |   //     单屏明细表(左) + 3D 预览常驻(右)，无 P0~P5 逐步向导。
  264 |   //     若客户/行业无有效选配模板，抽屉显示「缺少选配模板」空态——用等待兜底两种态其一。
  265 |   const emptyTplHint = page.locator('.ant-drawer').getByText('缺少选配模板', { exact: false });
  266 |   const addRowBtn = page.locator('.ant-drawer button:has-text("新增材质料号")');
  267 |   await Promise.race([
  268 |     emptyTplHint.waitFor({ state: 'visible', timeout: 8000 }).catch(() => {}),
  269 |     addRowBtn.waitFor({ state: 'visible', timeout: 8000 }).catch(() => {}),
  270 |   ]);
  271 |   await shot(page, 'configure-drawer-open');
  272 | 
  273 |   if (await emptyTplHint.count() > 0) {
  274 |     console.log('[选配添加] ⚠️ 该客户/行业无有效选配模板(D6 兜底链未命中)，跳过选配子流程，改走"确认加入"前置校验(0 行应不可点)');
  275 |     const confirmDisabled = page.locator('.ant-drawer button:has-text("确认加入")');
  276 |     console.log(`[选配添加] 确认加入按钮存在(禁用态)=${await confirmDisabled.count() > 0}`);
  277 |   } else {
  278 |     // ── 10) 明细表「+ 新增材质料号」→ 内层子框三步：① 材质 → ② 元素含量 → ③ 工序 ──
  279 |     await addRowBtn.first().click();
  280 |     await page.waitForTimeout(500);
  281 |     await shot(page, 'sub-step1-material');
  282 | 
  283 |     // Step① 材质：网格卡片，选候选 00001(Ag，单元素、默认配比=100%，免微调、必过校验)。
  284 |     const materialCard = page.locator('.ant-drawer').getByText('00001', { exact: true }).first();
  285 |     await materialCard.click();
  286 |     await page.waitForTimeout(300);
  287 |     await shot(page, 'sub-step1-material-selected');
  288 |     await page.locator('.ant-drawer button:has-text("下一步")').last().click();
  289 |     await page.waitForTimeout(700);
  290 |     await shot(page, 'sub-step2-elements');
  291 | 
  292 |     // Step② 元素含量：00001 单元素默认配比恰=100%（不微调也能过 confirmAdd 校验），直接下一步。
  293 |     await page.locator('.ant-drawer button:has-text("下一步")').last().click();
  294 |     await page.waitForTimeout(500);
  295 |     await shot(page, 'sub-step3-process');
  296 | 
  297 |     // Step③ 工序：勾选第一个候选（非必选，选一个覆盖更真实的落库路径）。
  298 |     const firstProcessChip = page.locator('.ant-drawer label').filter({ has: page.locator('input[type="checkbox"]') }).first();
  299 |     if (await firstProcessChip.count() > 0) {
  300 |       await firstProcessChip.click();
  301 |       await page.waitForTimeout(200);
  302 |     }
  303 |     await shot(page, 'sub-step3-process-checked');
  304 |     await page.locator('.ant-drawer button:has-text("确认添加")').last().click();
  305 |     await page.waitForTimeout(600);
  306 |     await shot(page, 'sub-confirmed-back-to-table');
  307 | 
  308 |     // ── 11) 明细表：数量默认 1（Σqty==1 → SIMPLE，D11/D12 判定同口径），直接确认加入 ──
```