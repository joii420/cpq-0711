# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: quotation-flow.spec.ts >> TC-F1: 打开 DRAFT 报价单不自动发 refresh-card-snapshot
- Location: e2e/quotation-flow.spec.ts:463:1

# Error details

```
Error: 编辑态 Step1(客户/模板已锁定预填)下一步应可点

expect(locator).toBeEnabled() failed

Locator:  getByRole('button', { name: /下一步/ }).first()
Expected: enabled
Received: disabled
Timeout:  15000ms

Call log:
  - 编辑态 Step1(客户/模板已锁定预填)下一步应可点 with timeout 15000ms
  - waiting for getByRole('button', { name: /下一步/ }).first()
    19 × locator resolved to <button disabled type="button" title="请先填写产品分类和报价模板" class="ant-btn css-dev-only-do-not-override-ch9ese css-var-_r_0_ ant-btn-primary ant-btn-color-primary ant-btn-variant-solid">…</button>
       - unexpected value "disabled"

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
          - generic [ref=e74]:
            - generic [ref=e75]: 编辑报价单
            - generic [ref=e77]: QT-20260924-0938 - 草稿
          - generic [ref=e79]:
            - button "价格版本" [ref=e81] [cursor=pointer]:
              - generic [ref=e82]: 价格版本
            - button "save 保存草稿" [ref=e84] [cursor=pointer]:
              - img "save" [ref=e86]:
                - img [ref=e87]
              - generic [ref=e89]: 保存草稿
            - button "返回列表" [ref=e91] [cursor=pointer]:
              - generic [ref=e92]: 返回列表
        - generic [ref=e93]:
          - generic [ref=e94]:
            - generic [ref=e96]:
              - generic [ref=e98]: "1"
              - generic [ref=e101]: 选择客户
            - generic [ref=e104]:
              - generic [ref=e106]: "2"
              - generic [ref=e109]: 添加产品
            - generic [ref=e112]:
              - generic [ref=e114]: "3"
              - generic [ref=e117]: 优惠策略
            - generic [ref=e120]:
              - generic [ref=e122]: "4"
              - generic [ref=e125]: 交易条款
            - generic [ref=e128]:
              - generic [ref=e130]: "5"
              - generic [ref=e133]: 提交审批
          - generic [ref=e136]:
            - generic [ref=e137]:
              - generic [ref=e138]:
                - generic [ref=e139]:
                  - generic [ref=e142]: 报价单基本信息
                  - generic [ref=e143]:
                    - generic [ref=e145]:
                      - generic "客户" [ref=e147]: "* 客户"
                      - generic [ref=e151]:
                        - generic "T260911RA-客户-SA (T260911RA-SAmv1)" [ref=e152]:
                          - text: T260911RA-客户-SA (T260911RA-SAmv1)
                          - combobox "* 客户" [disabled] [ref=e153]
                        - img "down" [ref=e155]:
                          - img [ref=e156]
                    - generic [ref=e158]:
                      - generic [ref=e161]:
                        - generic "项目名称" [ref=e163]
                        - textbox "项目名称" [ref=e167]:
                          - /placeholder: 关联项目
                      - generic [ref=e170]:
                        - generic "商机编号" [ref=e172]
                        - textbox "商机编号" [ref=e176]:
                          - /placeholder: 商机ID
                    - generic [ref=e177]:
                      - generic [ref=e180]:
                        - generic "报价类型" [ref=e182]
                        - generic [ref=e186] [cursor=pointer]:
                          - generic "标准报价" [ref=e187]:
                            - text: 标准报价
                            - combobox "报价类型" [ref=e188]
                          - img "down" [ref=e190]:
                            - img [ref=e191]
                      - generic [ref=e195]:
                        - generic "优先级" [ref=e197]
                        - generic [ref=e201] [cursor=pointer]:
                          - generic "中" [ref=e202]:
                            - text: 中
                            - combobox "优先级" [ref=e203]
                          - img "down" [ref=e205]:
                            - img [ref=e206]
                      - generic [ref=e210]:
                        - generic "阶段" [ref=e212]
                        - generic [ref=e216] [cursor=pointer]:
                          - generic "初步接洽" [ref=e217]:
                            - text: 初步接洽
                            - combobox "阶段" [ref=e218]
                          - img "down" [ref=e220]:
                            - img [ref=e221]
                    - generic [ref=e224]:
                      - generic "预计成交日" [ref=e226]
                      - generic [ref=e231]:
                        - textbox "预计成交日" [ref=e232]:
                          - /placeholder: Select date
                        - generic:
                          - img "calendar":
                            - img
                - generic [ref=e233]:
                  - generic [ref=e236]: 联系人
                  - generic [ref=e237]:
                    - generic [ref=e239]:
                      - generic "选择联系人" [ref=e241]
                      - generic [ref=e245] [cursor=pointer]:
                        - generic [ref=e246]:
                          - generic: 选择联系人
                          - combobox "选择联系人" [ref=e247]
                        - img "down" [ref=e249]:
                          - img [ref=e250]
                    - generic [ref=e252]:
                      - generic [ref=e255]:
                        - generic "联系人姓名" [ref=e257]
                        - textbox "联系人姓名" [ref=e261]
                      - generic [ref=e264]:
                        - generic "电话" [ref=e266]
                        - textbox "电话" [ref=e270]
                      - generic [ref=e273]:
                        - generic "邮箱" [ref=e275]
                        - textbox "邮箱" [ref=e279]
              - generic [ref=e281]:
                - generic [ref=e284]: 客户概况
                - table [ref=e288]:
                  - rowgroup [ref=e289]:
                    - 'row "名称 : T260911RA-客户-SA" [ref=e290]':
                      - 'cell "名称 : T260911RA-客户-SA" [ref=e291]':
                        - generic [ref=e292]:
                          - generic [ref=e293]: "名称 :"
                          - generic [ref=e294]: T260911RA-客户-SA
                    - 'row "编码 : T260911RA-SAmv1" [ref=e295]':
                      - 'cell "编码 : T260911RA-SAmv1" [ref=e296]':
                        - generic [ref=e297]:
                          - generic [ref=e298]: "编码 :"
                          - generic [ref=e299]: T260911RA-SAmv1
                    - 'row "等级 : STANDARD" [ref=e300]':
                      - 'cell "等级 : STANDARD" [ref=e301]':
                        - generic [ref=e302]:
                          - generic [ref=e303]: "等级 :"
                          - generic [ref=e305]: STANDARD
                    - 'row "行业 : -" [ref=e306]':
                      - 'cell "行业 : -" [ref=e307]':
                        - generic [ref=e308]:
                          - generic [ref=e309]: "行业 :"
                          - generic [ref=e310]: "-"
                    - 'row "区域 : -" [ref=e311]':
                      - 'cell "区域 : -" [ref=e312]':
                        - generic [ref=e313]:
                          - generic [ref=e314]: "区域 :"
                          - generic [ref=e315]: "-"
                    - 'row "累计金额 : ¥0" [ref=e316]':
                      - 'cell "累计金额 : ¥0" [ref=e317]':
                        - generic [ref=e318]:
                          - generic [ref=e319]: "累计金额 :"
                          - generic [ref=e320]: ¥0
            - generic [ref=e321]:
              - generic [ref=e325]: 选择模板 决定产品卡片 / Excel 视图的结构,以及"选配添加"使用的组件集
              - generic [ref=e327]:
                - generic [ref=e329]:
                  - generic "客户" [ref=e331]
                  - textbox [disabled] [ref=e335]: T260911RA-客户-SA
                - generic [ref=e337]:
                  - generic "报价单名称" [ref=e339]: "* 报价单名称"
                  - generic [ref=e343]:
                    - textbox "请填写报价单名称" [ref=e344]: E2E-freeze-test-1790240902070
                    - generic [ref=e346]: 29 / 100
                - alert [ref=e347]:
                  - img "info-circle" [ref=e349]:
                    - img [ref=e350]
                  - generic [ref=e352]:
                    - generic [ref=e353]: 报价单已生成 — 客户、产品分类、报价模板、核价模板 已锁定,不可修改
                    - generic [ref=e354]: 如需更换,请新建报价单。联系人、报价单名称等仍可编辑。
                - generic [ref=e356]:
                  - generic "产品分类" [ref=e358]:
                    - text: "* 产品分类"
                    - img "question-circle" [ref=e360]:
                      - img [ref=e361]
                  - generic [ref=e364]:
                    - generic [ref=e367]:
                      - generic [ref=e368]:
                        - generic: 请选择产品分类
                        - combobox [disabled] [ref=e369]
                      - img "down" [ref=e371]:
                        - img [ref=e372]
                    - generic [ref=e376]: 该报价单未绑定报价模板，无法带出产品分类
          - separator [ref=e377]
          - generic [ref=e378]:
            - button "上一步" [disabled] [ref=e379]:
              - generic: 上一步
            - button "下一步" [disabled] [ref=e380]:
              - generic: 下一步
```

# Test source

```ts
  389 | 
  390 |   console.log(`\n=== LF-DEBUG / LF-RENDER 共 ${lfDebug.length} 条 ===`);
  391 |   lfDebug.forEach(e => console.log('  🟡 ' + e.slice(0, 350)));
  392 | 
  393 |   // ═══════════════════════════════════════════════════════════════════════
  394 |   // 懒算护栏(lazy-card-values 修复): 打开已保存报价单读卡片值快照, 不再风暴
  395 |   // ───────────────────────────────────────────────────────────────────────
  396 |   // 背景: 修复前, 打开一张已保存的报价单会对每个 Tab 现场 batch-expand +
  397 |   //       batch-evaluate 重算一遍("打开即风暴", 拖慢首屏 / autosave 风暴)。
  398 |   //       修复后, 打开走 loadQuotation → ensureCardValues 暖快照/兜底, 直接读
  399 |   //       已存的卡片值快照渲染 → 打开阶段对这两个端点的请求数应为 0。
  400 |   //       (ensureCardValues 的暖/兜底逻辑负责把快照填好, 使该读快照门闸生效。)
  401 |   //
  402 |   // 作用域: 监听器在 reopen 的 page.goto 之前注册, batchCalls 从空开始, 只统计
  403 |   //         "打开已保存报价单"这一段 —— 不含上面"新建 + 添加产品"阶段(那个阶段
  404 |   //         调 batch-expand / batch-evaluate 是合理的, 故必须新起一个干净监听器
  405 |   //         而非复用全程计数)。复用上面已建好(含产品 + 快照)的报价单 reopen,
  406 |   //         不另造流程。
  407 |   // ═══════════════════════════════════════════════════════════════════════
  408 |   if (savedQuotationId) {
  409 |     const batchCalls: string[] = [];
  410 |     const onBatchReq = (r: import('@playwright/test').Request) => {
  411 |       const u = r.url();
  412 |       if (u.includes('/components/batch-expand') || u.includes('/formulas/batch-evaluate')) {
  413 |         batchCalls.push(`${r.method()} ${u}`);
  414 |         console.log(`[lazy-card] ⚠️ 打开阶段检测到风暴请求: ${r.method()} ${u}`);
  415 |       }
  416 |     };
  417 |     page.on('request', onBatchReq);
  418 | 
  419 |     // 重新打开已保存的报价单(走 loadQuotation → ensureCardValues 读快照路径)
  420 |     // /edit 路由落地 Step1(currentStep 恒 useState(0) 起步)，点「下一步」进 Step2 才能看到产品卡片。
  421 |     await page.goto(`/quotations/${savedQuotationId}/edit`);
  422 |     await page.waitForLoadState('networkidle');
  423 |     await page.waitForTimeout(3000);  // 给"打开后若仍有现场重算"充足触发时间, 让风暴请求先飞出来
  424 |     const reopenNextBtn = page.getByRole('button', { name: /下一步/ }).first();
  425 |     if (await reopenNextBtn.isEnabled().catch(() => false)) {
  426 |       await reopenNextBtn.click();
  427 |       await page.waitForLoadState('networkidle');
  428 |       await page.waitForTimeout(500);
  429 |     }
  430 |     await shot(page, 'reopen-saved-quotation');
  431 | 
  432 |     // 等渲染落定: 产品卡片 Tab 出现 + 无"加载中"占位(镜像上面 8-Tab 落定判定)
  433 |     await page.locator('button.qt-tab-btn').first()
  434 |       .waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
  435 |     const reopenLoading = await countLoading(page, 'reopen-settled');
  436 |     console.log(`[lazy-card] reopen '加载中' count = ${reopenLoading} (期望 0)`);
  437 |     expect(reopenLoading, 'TC-075 SIMPLE: 重开后加载中必须为0').toBe(0);
  438 | 
  439 |     // 核心断言: 打开阶段对 batch-expand / batch-evaluate 的请求数 = 0(风暴已消失)
  440 |     await expect
  441 |       .poll(() => batchCalls.length, {
  442 |         timeout: 8000,
  443 |         message:
  444 |           `打开已保存报价单不应触发 batch-expand / batch-evaluate 风暴(懒算修复应读卡片值快照), ` +
  445 |           `但检测到 ${batchCalls.length} 次: ${batchCalls.join(', ')}`,
  446 |       })
  447 |       .toBe(0);
  448 | 
  449 |     page.off('request', onBatchReq);
  450 |     console.log('[lazy-card] ✅ 打开已保存报价单 batch-expand/batch-evaluate = 0 (懒算护栏通过)');
  451 |   } else {
  452 |     console.log('[lazy-card] ⚠️ 未捕获到已保存报价单 id(全程无 PUT /draft), 跳过 reopen 风暴断言');
  453 |   }
  454 | 
  455 |   await shot(page, 'final');
  456 |   console.log(`\n=== '加载中' final count: ${loadingFinal} (期望 0) ===`);
  457 |   expect(loadingFinal, 'TC-075 SIMPLE: 最终加载中必须为0').toBe(0);
  458 | });
  459 | 
  460 | // ═══════════════════════════════════════════════════════════════════════
  461 | // TC-F1: 打开 DRAFT 报价单不自动触发 POST refresh-card-snapshot（B1 冻结验证）
  462 | // ═══════════════════════════════════════════════════════════════════════
  463 | test('TC-F1: 打开 DRAFT 报价单不自动发 refresh-card-snapshot', async ({ page }) => {
  464 |   test.skip(!backendUp, '后端未启动');
  465 | 
  466 |   // ── 登录 ──
  467 |   await loginAsAdmin(page);
  468 | 
  469 |   // ── 通过 API 创建最小 DRAFT 报价单 ──
  470 |   const cookie = await extractSessionCookie(page);
  471 |   const quotationId = await createMinimalDraftQuotation(cookie);
  472 | 
  473 |   // ── 监听 POST refresh-card-snapshot 请求 ──
  474 |   const refreshRequests: string[] = [];
  475 |   page.on('request', (req) => {
  476 |     if (req.method() === 'POST' && /\/quotations\/[^/]+\/refresh-card-snapshot/.test(req.url())) {
  477 |       refreshRequests.push(req.url());
  478 |       console.log(`[TC-F1] ⚠️ 检测到自动 POST refresh-card-snapshot: ${req.url()}`);
  479 |     }
  480 |   });
  481 | 
  482 |   // ── 打开报价单（落地 Step1，向导不会因带 id 的 /edit 路由自动跳转 Step2——
  483 |   //     currentStep 恒以 useState(0) 起步，仅用户点「下一步」才前进），点「下一步」进 Step2 ──
  484 |   await page.goto(`/quotations/${quotationId}/edit`);
  485 |   await page.waitForLoadState('networkidle');
  486 |   // 额外等待 3s，确保任何"打开后自动刷新"逻辑（若还存在）有充足时间触发
  487 |   await page.waitForTimeout(3000);
  488 |   const step1NextBtn = page.getByRole('button', { name: /下一步/ }).first();
> 489 |   await expect(step1NextBtn, '编辑态 Step1(客户/模板已锁定预填)下一步应可点').toBeEnabled({ timeout: 15000 });
      |                                                             ^ Error: 编辑态 Step1(客户/模板已锁定预填)下一步应可点
  490 |   await step1NextBtn.click();
  491 |   await page.waitForLoadState('networkidle');
  492 |   await page.waitForTimeout(1000);
  493 | 
  494 |   // 记录页面截图
  495 |   await shot(page, 'tc-f1-draft-opened');
  496 | 
  497 |   // ── 核心断言：打开草稿不应触发 refresh-card-snapshot ──
  498 |   console.log(`\n[TC-F1] POST refresh-card-snapshot 请求数 = ${refreshRequests.length} (期望 0)`);
  499 |   expect(
  500 |     refreshRequests.length,
  501 |     `打开 DRAFT 报价单不应自动发 POST /quotations/${quotationId}/refresh-card-snapshot，` +
  502 |     `但检测到 ${refreshRequests.length} 次请求: ${refreshRequests.join(', ')}`
  503 |   ).toBe(0);
  504 | 
  505 |   // ── 附加断言：页面正常渲染 Step2（无 500/崩溃） ──
  506 |   // 验证没有出现"加载中"死锁（快照应直接渲染）
  507 |   const loadingCount = await countLoading(page, 'TC-F1');
  508 |   console.log(`[TC-F1] '加载中' count = ${loadingCount}`);
  509 |   // 注意：空报价单无产品，不期望 Tab 渲染，只期望页面可正常显示 Step2 工具栏
  510 |   const refreshBtn = page.locator('[data-testid="refresh-basic-data-btn"]');
  511 |   const btnVisible = await refreshBtn.isVisible().catch(() => false);
  512 |   console.log(`[TC-F1] 「刷新基础数据」按钮可见 = ${btnVisible} (期望 DRAFT 状态下可见)`);
  513 |   expect(btnVisible, '「刷新基础数据」按钮应在 DRAFT 状态下可见').toBe(true);
  514 | 
  515 |   await shot(page, 'tc-f1-final');
  516 |   console.log('\n[TC-F1] ✅ 通过: 打开 DRAFT 不触发自动 refresh-card-snapshot');
  517 | });
  518 | 
  519 | // ═══════════════════════════════════════════════════════════════════════
  520 | // TC-F2: 显式点击「刷新基础数据」→ 确认 Modal → 触发 POST refresh-card-snapshot
  521 | // ═══════════════════════════════════════════════════════════════════════
  522 | test('TC-F2: 显式刷新才触发 refresh-card-snapshot', async ({ page }) => {
  523 |   test.skip(!backendUp, '后端未启动');
  524 | 
  525 |   // ── 登录 ──
  526 |   await loginAsAdmin(page);
  527 | 
  528 |   // ── 通过 API 创建最小 DRAFT 报价单 ──
  529 |   const cookie = await extractSessionCookie(page);
  530 |   const quotationId = await createMinimalDraftQuotation(cookie);
  531 | 
  532 |   // ── 监听 POST refresh-card-snapshot 请求 ──
  533 |   const refreshRequests: string[] = [];
  534 |   // 同时记录响应，确认后端成功处理
  535 |   const refreshResponses: number[] = [];
  536 |   page.on('request', (req) => {
  537 |     if (req.method() === 'POST' && /\/quotations\/[^/]+\/refresh-card-snapshot/.test(req.url())) {
  538 |       refreshRequests.push(req.url());
  539 |       console.log(`[TC-F2] ✅ 检测到 POST refresh-card-snapshot: ${req.url()}`);
  540 |     }
  541 |   });
  542 |   page.on('response', (resp) => {
  543 |     if (/\/quotations\/[^/]+\/refresh-card-snapshot/.test(resp.url())) {
  544 |       refreshResponses.push(resp.status());
  545 |       console.log(`[TC-F2] refresh-card-snapshot 响应状态: ${resp.status()}`);
  546 |     }
  547 |   });
  548 | 
  549 |   // ── 打开报价单，点「下一步」进 Step2（/edit 路由落地 Step1，不自动跳转，见 TC-F1 同注）──
  550 |   await page.goto(`/quotations/${quotationId}/edit`);
  551 |   await page.waitForLoadState('networkidle');
  552 |   await page.waitForTimeout(1500);
  553 |   const tcf2NextBtn = page.getByRole('button', { name: /下一步/ }).first();
  554 |   await expect(tcf2NextBtn, '编辑态 Step1(客户/模板已锁定预填)下一步应可点').toBeEnabled({ timeout: 15000 });
  555 |   await tcf2NextBtn.click();
  556 |   await page.waitForLoadState('networkidle');
  557 |   await page.waitForTimeout(1000);
  558 |   await shot(page, 'tc-f2-draft-opened');
  559 | 
  560 |   // ── 确认打开时尚未触发 refresh（打开即冻结） ──
  561 |   expect(
  562 |     refreshRequests.length,
  563 |     '打开阶段不应有 refresh-card-snapshot 请求（B1 冻结保障）'
  564 |   ).toBe(0);
  565 | 
  566 |   // ── 点击「刷新基础数据」按钮 ──
  567 |   const refreshBtn = page.locator('[data-testid="refresh-basic-data-btn"]');
  568 |   await refreshBtn.waitFor({ state: 'visible', timeout: 10_000 });
  569 |   await refreshBtn.click();
  570 |   await page.waitForTimeout(500);
  571 |   await shot(page, 'tc-f2-modal-open');
  572 | 
  573 |   // ── Modal 确认框应弹出，标题「刷新基础数据」──
  574 |   const modalTitle = page.locator('.ant-modal-title');
  575 |   await modalTitle.waitFor({ state: 'visible', timeout: 8_000 });
  576 |   const titleText = await modalTitle.innerText();
  577 |   console.log(`[TC-F2] Modal 标题: "${titleText}"`);
  578 |   expect(titleText).toContain('刷新基础数据');
  579 | 
  580 |   // ── 点击 Modal 中的「刷新」确认按钮 ──
  581 |   // Ant Design Modal.confirm 的 okText 按钮在 .ant-modal-confirm-btns 内
  582 |   const okBtn = page.locator('.ant-modal-confirm-btns button').filter({ hasText: '刷新' }).first();
  583 |   await okBtn.waitFor({ state: 'visible', timeout: 8_000 });
  584 |   await okBtn.click();
  585 |   console.log('[TC-F2] 已点击「刷新」确认按钮');
  586 | 
  587 |   // ── 等待 POST 请求完成（最多 15s）──
  588 |   await page.waitForResponse(
  589 |     (resp) => /\/quotations\/[^/]+\/refresh-card-snapshot/.test(resp.url()),
```