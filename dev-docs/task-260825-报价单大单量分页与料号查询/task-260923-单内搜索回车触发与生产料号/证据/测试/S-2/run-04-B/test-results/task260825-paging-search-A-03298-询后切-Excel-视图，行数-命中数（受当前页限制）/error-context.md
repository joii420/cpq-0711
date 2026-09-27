# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: task260825-paging-search.spec.ts >> AC-14: 查询与分页作用于三视图 >> T-14 查询后切 Excel 视图，行数 == 命中数（受当前页限制）
- Location: e2e/task260825-paging-search.spec.ts:363:3

# Error details

```
Error: AC-14: 查询作用于 Excel 视图，行数应等于命中数 1（不是 100 也不是 1845）

expect(received).toBe(expected) // Object.is equality

Expected: 1
Received: 0
```

# Test source

```ts
  276 |     await shot(page, 'empty-state');
  277 | 
  278 |     const cardCount = await countRenderedCards(page);
  279 |     console.log(`[T-11] 空态下卡片数 = ${cardCount}`);
  280 |     expect(cardCount, 'AC-11: 查询命中 0 时不应保留上一页任何卡片').toBe(0);
  281 | 
  282 |     const bodyText = await page.locator('body').innerText();
  283 |     expect(bodyText, 'AC-11: 空态标题应逐字一致').toContain('未找到匹配的料号');
  284 |     expect(bodyText, 'AC-11: 空态副文案应逐字一致（含具体查询词与总数 1845）').toContain('「XYZ999」在本报价单的 1845 个料号中无匹配。请换一个料号片段，或清空查询查看全部。');
  285 |     expect(bodyText, 'AC-11: 应有"清空查询"按钮文案').toContain('清空查询');
  286 | 
  287 |     console.log(`[T-11] console.error（仅记录，不计入判据）${consoleErrors.length} 条 = ${JSON.stringify(consoleErrors)}`);
  288 |     console.log(`[T-11] pageerror ${pageErrors.length} 条 = ${JSON.stringify(pageErrors)}`);
  289 |     expect(pageErrors, 'AC-13（D-2 ②）: 页面脚本报错（pageerror）数应为 0').toEqual([]);
  290 |   });
  291 | });
  292 | 
  293 | test.describe('AC-12: 查询状态下翻页正确（2026-08-28 裁决：默认页大小 100→10）', () => {
  294 |   test('T-12 命中数跨页时，第 2 页是命中集合第 11-20 条（按新默认页大小 10 算）', async ({ page }) => {
  295 |     test.skip(!backendUp, '后端未启动');
  296 |     // 用真实数据构造一个命中数可控的查询词：找一个使命中数 >10（跨页，按新默认页大小 10）的前缀
  297 |     // 在销售料号上按不同前缀长度统计命中分布，选一个命中数在 [11,1844] 区间的前缀
  298 |     let candidatePrefix = '';
  299 |     let candidateCount = 0;
  300 |     for (let len = 10; len <= 12; len++) {
  301 |       const counts = new Map<string, number>();
  302 |       for (const r of expectedOrder) {
  303 |         const p = r.productPartNo.slice(0, len);
  304 |         counts.set(p, (counts.get(p) || 0) + 1);
  305 |       }
  306 |       for (const [p, c] of counts) {
  307 |         if (c > 10 && c < 1845) { candidatePrefix = p; candidateCount = c; break; }
  308 |       }
  309 |       if (candidatePrefix) break;
  310 |     }
  311 |     test.skip(!candidatePrefix, '未能从现网数据构造出命中数跨页（>10 且 <1845）的查询前缀，需要主线协助确认是否要专门造数');
  312 |     console.log(`[T-12] 选用前缀="${candidatePrefix}" 期望命中数=${candidateCount}`);
  313 | 
  314 |     await openSampleReadOnly(page);
  315 |     await submitSearch(page, candidatePrefix);
  316 | 
  317 |     console.log(`[T-12] 分页栏="${await pgbarText(page)}"`);
  318 | 
  319 |     const matchedInOrder = expectedOrder.filter((r) => r.productPartNo.startsWith(candidatePrefix)).map((r) => r.productPartNo);
  320 |     const expectedPage2 = matchedInOrder.slice(10, 20); // 新默认页大小 10：第 2 页 = 第 11~20 条
  321 |     expect(expectedPage2.length, 'T-12 期望集合应为 10 条（防空跑）').toBe(10);
  322 | 
  323 |     const jumper = page.locator('.ant-pagination-options-quick-jumper input').first();
  324 |     if (await jumper.count() > 0) {
  325 |       await jumper.fill('2');
  326 |       await jumper.press('Enter');
  327 |     } else {
  328 |       await page.locator('.ant-pagination-item-2').first().click();
  329 |     }
  330 |     await page.waitForTimeout(800);
  331 |     await shot(page, 'query-page2');
  332 | 
  333 |     const setPage2 = await extractVisiblePartNoSet(page, '.qt-products-list, body', SEARCH_SAMPLE_PART_NO_REGEX);
  334 |     console.log(`[T-12] 查询态第2页 页面抓到的料号=${JSON.stringify([...setPage2])}`);
  335 |     const diff = expectedPage2.filter((x) => !setPage2.has(x));
  336 |     console.log(`[T-12] 查询态第2页 与期望差异(应为空)=${JSON.stringify(diff.slice(0, 10))}`);
  337 |     expect(diff, 'AC-12: 查询状态下第 2 页应恰好是命中集合第 11-20 条（新默认页大小 10）').toEqual([]);
  338 |   });
  339 | });
  340 | 
  341 | test.describe('AC-13: 清空查询回到全量分页', () => {
  342 |   test('T-13 清空查询后计数回到 1845', async ({ page }) => {
  343 |     test.skip(!backendUp, '后端未启动');
  344 |     await openSampleReadOnly(page);
  345 |     await submitSearch(page, deepPartNo);
  346 |     expect(await countRenderedCards(page), '查询后应先命中').toBe(1);
  347 | 
  348 |     // 清空：不按回车（task-260923 D-2：框内去首尾空格后为空即恢复全部）
  349 |     const input = searchInput(page);
  350 |     await input.fill('');
  351 |     await page.waitForTimeout(600);
  352 |     await shot(page, 'cleared');
  353 |     const countText = await pgbarText(page);
  354 |     console.log(`[T-13] 清空后分页栏="${countText}"`);
  355 |     expect(countText, 'AC-13: 清空查询后计数应回到「共 1845 条」').toContain('共 1845 条');
  356 |     const cardCount = await countRenderedCards(page);
  357 |     expect(cardCount, 'AC-13: 清空查询后应恢复分页渲染（<=10，新默认页大小）').toBeLessThanOrEqual(10);
  358 |     expect(cardCount, 'AC-13: 清空查询后应有卡片渲染').toBeGreaterThan(0);
  359 |   });
  360 | });
  361 | 
  362 | test.describe('AC-14: 查询与分页作用于三视图', () => {
  363 |   test('T-14 查询后切 Excel 视图，行数 == 命中数（受当前页限制）', async ({ page }) => {
  364 |     test.skip(!backendUp, '后端未启动');
  365 |     await openSampleReadOnly(page);
  366 |     await submitSearch(page, deepPartNo);
  367 |     expect(await countRenderedCards(page), '查询后卡片视图应先命中 1 条').toBe(1);
  368 | 
  369 |     await switchViewType(page, 'Excel 视图');
  370 |     await page.waitForTimeout(1500);
  371 |     await shot(page, 'query-excel-view');
  372 | 
  373 |     const rows = page.locator('.ant-table-tbody tr.ant-table-row');
  374 |     const rowCount = await rows.count();
  375 |     console.log(`[T-14] 查询态 Excel 视图行数 = ${rowCount}`);
> 376 |     expect(rowCount, 'AC-14: 查询作用于 Excel 视图，行数应等于命中数 1（不是 100 也不是 1845）').toBe(1);
      |                                                                           ^ Error: AC-14: 查询作用于 Excel 视图，行数应等于命中数 1（不是 100 也不是 1845）
  377 |     const rowText = await rows.first().innerText();
  378 |     expect(rowText, 'AC-14: Excel 视图命中行应含搜索的料号').toContain(deepPartNo);
  379 |   });
  380 | });
  381 | 
```