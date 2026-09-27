# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: task260923-search-s1.spec.ts >> T-12 · AC-12 120 字符超长输入：空态引用全文、无 JS 报错、框宽 320、无横向滚动
- Location: e2e/task260923-search-s1.spec.ts:373:1

# Error details

```
Test timeout of 240000ms exceeded.
```

```
Error: locator.evaluate: Test timeout of 240000ms exceeded.
Call log:
  - waiting for locator('[data-testid="task260825-paging-bar"]').first().locator('.ant-input-affix-wrapper').first()

```

# Test source

```ts
  289 |   rec.s1 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  290 |   await H.shot(page, 'T-9-①');
  291 |   expect(rec.s1.cnt, 'AC-9①：计数').toBe(H.txtMatch(4));
  292 |   expect(H.sorted(rec.s1.nos), 'AC-9①：卡片').toEqual(H.SET_30002);
  293 |   // ② 核价单
  294 |   await seg('核价单').click(); await page.waitForTimeout(4000);
  295 |   rec.s2 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  296 |   await H.shot(page, 'T-9-②核价单');
  297 |   expect(rec.s2.cnt, 'AC-9②：计数').toBe(H.txtMatch(4));
  298 |   expect(H.sorted(rec.s2.nos), 'AC-9②：卡片销售料号集合').toEqual(H.SET_30002);
  299 |   // ③（AC 变更 D-1，2026-09-24）切回「📝 报价单」，再切「📑 Excel 视图」⇒ 报价单侧 Excel 视图
  300 |   await seg('报价单').click(); await page.waitForTimeout(3000);
  301 |   await seg('Excel 视图').click(); await page.waitForTimeout(6000);
  302 |   const rows = await page.evaluate(() => Array.from(document.querySelectorAll('.ant-table-tbody tr.ant-table-row'))
  303 |     .filter((r) => (r as HTMLElement).offsetParent !== null).map((r) => (r as HTMLElement).innerText.replace(/\s+/g, ' ')));
  304 |   const selected = await page.locator('.ant-segmented-item-selected').allInnerTexts();
  305 |   rec.s3 = { selected, rows, salesNos: rows.map((r) => (r.match(/\bS\d{4}\b/) || [''])[0]), cnt: await H.countText(page) };
  306 |   await H.shot(page, 'T-9-③Excel视图');
  307 |   H.writeEvidence('T-9-①②③.json', JSON.stringify(rec, null, 1));
  308 |   expect(selected, 'AC-9③ 前置：此刻应处于「报价单 + Excel 视图」').toEqual(expect.arrayContaining(['📝 报价单', '📑 Excel 视图']));
  309 |   expect(rows.length, `AC-9③：报价单侧 Excel 视图表格恰 4 行（此刻选中=${JSON.stringify(selected)}）`).toBe(4);
  310 |   expect(H.sorted(rec.s3.salesNos), 'AC-9③：销售料号集合').toEqual(H.SET_30002);
  311 |   // ④ 切回「📋 产品卡片」
  312 |   await seg('产品卡片').click(); await page.waitForTimeout(3000);
  313 |   rec.s4 = { nos: await H.visibleSalesNos(page), cnt: await H.countText(page) };
  314 |   await H.shot(page, 'T-9-④切回');
  315 |   expect(H.sorted(rec.s4.nos), 'AC-9④：仍 4 张卡片 {S0004~S0007}').toEqual(H.SET_30002);
  316 |   // ⑤ 改成 30003 不回车
  317 |   const inp = H.searchInput(page);
  318 |   await inp.fill('30003'); await page.waitForTimeout(2000);
  319 |   rec.s5 = { nos: await H.visibleSalesNos(page), cnt: await H.countText(page) };
  320 |   expect(H.sorted(rec.s5.nos), 'AC-9⑤：仍 {S0004~S0007}').toEqual(H.SET_30002);
  321 |   expect(rec.s5.cnt, 'AC-9⑤：仍「匹配 4 条」').toBe(H.txtMatch(4));
  322 |   // ⑥ 回车
  323 |   await inp.press('Enter'); await page.waitForTimeout(800);
  324 |   rec.s6 = { nos: await H.visibleSalesNos(page), cnt: await H.countText(page) };
  325 |   await H.shot(page, 'T-9-⑥');
  326 |   expect(H.sorted(rec.s6.nos), 'AC-9⑥：变为 {S0008~S0011}').toEqual(H.SET_30003);
  327 |   expect(rec.s6.cnt, 'AC-9⑥：匹配 4 条').toBe(H.txtMatch(4));
  328 |   // ⑦ 刷新（刷新后编辑页回到 Step1，按用户路径点「下一步」回到卡片）
  329 |   await page.reload();
  330 |   await H.gotoStep2(page);
  331 |   const vals = await H.allSearchInputs(page).evaluateAll((els) => els.map((e) => (e as HTMLInputElement).value));
  332 |   rec.s7 = { vals, cnt: await H.countText(page) };
  333 |   await H.shot(page, 'T-9-⑦刷新后');
  334 |   H.writeEvidence('T-9.json', JSON.stringify(rec, null, 1));
  335 |   expect(vals.length).toBeGreaterThan(0);
  336 |   expect(vals.every((v) => v === ''), `AC-9⑦：刷新后搜索框为空（实际 ${JSON.stringify(vals)}）`).toBe(true);
  337 |   expect(rec.s7.cnt, 'AC-9⑦：刷新后共 14 条').toBe(H.TXT_ALL);
  338 | });
  339 | 
  340 | test('T-10 · AC-10 详情页：不回车不变 / 回车生产料号命中 / ✕ 恢复 / 空态引用提交词', async ({ page }) => {
  341 |   test.setTimeout(240_000);
  342 |   await login(page);
  343 |   await H.openDetail(page, A.id);
  344 |   const inp = H.searchInput(page);
  345 |   const rec: any = {};
  346 |   // ①
  347 |   await inp.click(); await inp.pressSequentially('300034', { delay: 80 }); await page.waitForTimeout(2000);
  348 |   expect(await inp.inputValue()).toBe('300034');
  349 |   rec.s1 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  350 |   await H.shot(page, 'T-10-①未回车');
  351 |   expect(rec.s1.cnt, 'AC-10①：共 14 条').toBe(H.TXT_ALL);
  352 |   expect(H.sorted(rec.s1.nos), 'AC-10①：第 1 页 10 张').toEqual(H.sorted(H.FIRST_PAGE));
  353 |   // ②
  354 |   await inp.press('Enter'); await page.waitForTimeout(800);
  355 |   rec.s2 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  356 |   await H.shot(page, 'T-10-②回车');
  357 |   expect(rec.s2.cnt, 'AC-10②：计数').toBe(H.txtMatch(1));
  358 |   expect(rec.s2.nos, 'AC-10②：卡片 = S0011').toEqual(['S0011']);
  359 |   // ③
  360 |   await H.bar(page).locator('.ant-input-clear-icon').first().click();
  361 |   rec.t3 = await H.within(1000, () => H.countText(page), H.TXT_ALL, 'AC-10③ 点 ✕ 后');
  362 |   // ④
  363 |   await submit(page, 'XYZ-T260923');
  364 |   const sub = H.emptySubtitle('XYZ-T260923');
  365 |   await expect(page.getByText(sub, { exact: true }).first(), 'AC-10④：空态副文案逐字').toBeVisible();
  366 |   await inp.click(); await inp.press('End'); await inp.pressSequentially('-9', { delay: 80 }); await page.waitForTimeout(1500);
  367 |   expect(await inp.inputValue()).toBe('XYZ-T260923-9');
  368 |   await expect(page.getByText(sub, { exact: true }).first(), 'AC-10④：追加 -9 不回车后副文案不变').toBeVisible();
  369 |   await H.shot(page, 'T-10-④空态追加');
  370 |   H.writeEvidence('T-10.json', JSON.stringify(rec, null, 1));
  371 | });
  372 | 
  373 | test('T-12 · AC-12 120 字符超长输入：空态引用全文、无 JS 报错、框宽 320、无横向滚动', async ({ page }) => {
  374 |   test.setTimeout(240_000);
  375 |   const errs = H.collectPageErrors(page);
  376 |   await login(page);
  377 |   await H.openEdit(page, A.id);
  378 |   const long = 'T260923-' + 'Z'.repeat(112);
  379 |   expect(long.length, '量具：构造的字符串应为 120 字符').toBe(120);
  380 |   await submit(page, long);
  381 |   await page.waitForTimeout(1000);
  382 |   await expect(page.getByText(H.EMPTY_TITLE, { exact: true }).first(), 'AC-12：空态标题').toBeVisible();
  383 |   const quoted = await page.evaluate(() => {
  384 |     const el = Array.from(document.querySelectorAll('body *')).find((e) =>
  385 |       e.children.length === 0 && /^「[\s\S]*」在本报价单的/.test((e as HTMLElement).innerText || ''));
  386 |     const t = (el as HTMLElement | undefined)?.innerText || '';
  387 |     return t.slice(1, t.indexOf('」'));
  388 |   });
> 389 |   const width = await H.bar(page).locator('.ant-input-affix-wrapper').first().evaluate((e) => e.getBoundingClientRect().width);
      |                                                                               ^ Error: locator.evaluate: Test timeout of 240000ms exceeded.
  390 |   const scroll = await page.evaluate(() => [document.documentElement.scrollWidth, document.documentElement.clientWidth]);
  391 |   const rec = { quoted, quotedLen: quoted.length, width, scroll, pageErrors: errs };
  392 |   await H.shot(page, 'T-12-超长输入');
  393 |   H.writeEvidence('T-12.json', JSON.stringify(rec, null, 1));
  394 |   expect(quoted, 'AC-12：副文案引号内为这 120 个字符').toBe(long);
  395 |   expect(errs, 'AC-12：控制台 pageerror 数 = 0').toEqual([]);
  396 |   expect(Math.round(width), 'AC-12：分页栏搜索框宽度仍为 320px').toBe(320);
  397 |   expect(scroll[0], `AC-12：无横向滚动条（scrollWidth=${scroll[0]} clientWidth=${scroll[1]}）`).toBe(scroll[1]);
  398 | });
  399 | 
```