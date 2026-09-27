# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: task260923-search-s1.spec.ts >> T-7 · AC-7 空态：文案引用最近一次回车提交的文字；追加不回车不变；清空查询恢复
- Location: e2e/task260923-search-s1.spec.ts:221:1

# Error details

```
Error: AC-7：计数

expect(received).toBe(expected) // Object.is equality

Expected: "未匹配到料号（共 14 条）"
Received: ""
```

# Test source

```ts
  131 |   expect(nos.length, `AC-3：第 1 页恰好 4 张（实际 ${JSON.stringify(nos)}）`).toBe(4);
  132 |   expect(H.sorted(nos), 'AC-3：销售料号集合').toEqual(H.SET_30002);
  133 |   for (const s of nos) expect(hl[s], `AC-3：${s} 卡片头部黄底高亮片段数应为 0`).toEqual([]);
  134 |   expect(src.S0004.production, '源值非空').toBeTruthy();
  135 |   expect(pop.partNo, `AC-3：S0004 浮层「料号：」应 = 源 production_no ${src.S0004.production}\n原文=${pop.raw}`).toBe(src.S0004.production);
  136 |   // 量具阳性对照：销售料号命中必须能被 yellowFragments 抓到，否则上面「高亮 = 0」不可信
  137 |   await submit(page, 'S0004');
  138 |   const ctrl = await H.yellowFragments((await H.cardBySales(page, 'S0004')).locator('.qt-card-header'));
  139 |   H.writeEvidence('T-3-量具对照.json', JSON.stringify({ ctrl }));
  140 |   expect(ctrl.length, '量具阳性对照：搜 S0004 后 S0004 卡片应抓到 ≥1 个黄底片段；抓不到 ⇒ AC-3 高亮断言【未验证】').toBeGreaterThan(0);
  141 | });
  142 | 
  143 | test('T-4 · AC-4 原有三字段照常可搜（销售料号 / 客户产品编号大小写不敏感 / 客户料号名称）', async ({ page }) => {
  144 |   test.setTimeout(240_000);
  145 |   await login(page);
  146 |   await H.openEdit(page, A.id);
  147 |   const rec: any = {};
  148 |   // ①
  149 |   await submit(page, 'S0011');
  150 |   rec.q1 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  151 |   const c1 = await H.cardBySales(page, 'S0011');
  152 |   rec.q1.hl = await H.yellowFragments(c1.locator('.qt-part-badge'));
  153 |   await H.shot(page, 'T-4-①S0011');
  154 |   // ②
  155 |   await submit(page, 'zt-c012');
  156 |   rec.q2 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  157 |   const c2 = await H.cardBySales(page, 'S0012');
  158 |   rec.q2.hl = await H.yellowFragments(c2.locator('.qt-sku-badge'));
  159 |   await H.shot(page, 'T-4-②zt-c012');
  160 |   // ③
  161 |   await submit(page, '正泰端子');
  162 |   rec.q3 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  163 |   await H.shot(page, 'T-4-③正泰端子');
  164 |   H.writeEvidence('T-4.json', JSON.stringify(rec, null, 1));
  165 |   expect(rec.q1.cnt, 'AC-4①：计数').toBe(H.txtMatch(1));
  166 |   expect(rec.q1.nos, 'AC-4①：卡片').toEqual(['S0011']);
  167 |   expect(rec.q1.hl, 'AC-4①：销售料号徽标里 S0011 黄底高亮').toEqual(['S0011']);
  168 |   expect(rec.q2.cnt, 'AC-4②：计数「匹配 1 条」').toMatch(/^匹配 1 条/);
  169 |   expect(rec.q2.nos, 'AC-4②：卡片').toEqual(['S0012']);
  170 |   expect(rec.q2.hl, 'AC-4②：客户产品编号徽标里 ZT-C012 黄底高亮（大小写不敏感）').toEqual([src.S0012.custProductNo]);
  171 |   expect(src.S0012.custProductNo, '源值').toBe('ZT-C012');
  172 |   expect(rec.q3.cnt, 'AC-4③：计数「匹配 1 条」').toMatch(/^匹配 1 条/);
  173 |   expect(rec.q3.nos, 'AC-4③：卡片（客户料号名称命中）').toEqual(['S0012']);
  174 | });
  175 | 
  176 | test('T-5 · AC-5 点 ✕ 不回车，1 秒内恢复全部', async ({ page }) => {
  177 |   test.setTimeout(240_000);
  178 |   await login(page);
  179 |   await H.openEdit(page, A.id);
  180 |   await submit(page, '30002');
  181 |   expect(await H.countText(page), 'AC-5 前置：匹配 4 条').toBe(H.txtMatch(4));
  182 |   const clr = H.bar(page).locator('.ant-input-clear-icon').first();
  183 |   await expect(clr, '搜索框里的 ✕ 应可见').toBeVisible();
  184 |   await clr.click();
  185 |   const t = await H.within(1000, async () => [await H.searchInput(page).inputValue(), await H.countText(page),
  186 |     H.sorted(await H.visibleSalesNos(page))], ['', H.TXT_ALL, H.sorted(H.FIRST_PAGE)], 'AC-5 点 ✕ 后');
  187 |   await H.shot(page, 'T-5-点叉后');
  188 |   H.writeEvidence('T-5.json', JSON.stringify({ restoredMs: t }));
  189 | });
  190 | 
  191 | test('T-6 · AC-6 退格删光 / 纯空格：各自 1 秒内恢复；中间再回车仍匹配 4 条', async ({ page }) => {
  192 |   test.setTimeout(240_000);
  193 |   await login(page);
  194 |   await H.openEdit(page, A.id);
  195 |   const inp = H.searchInput(page);
  196 |   await submit(page, '30002');
  197 |   expect(await H.countText(page), 'AC-6 前置：匹配 4 条').toBe(H.txtMatch(4));
  198 |   const probe = async () => [await H.countText(page), H.sorted(await H.visibleSalesNos(page))];
  199 |   const ALL = [H.TXT_ALL, H.sorted(H.FIRST_PAGE)];
  200 |   const rec: any = {};
  201 |   // ① 退格删光
  202 |   await inp.click(); await inp.press('End');
  203 |   const mid: string[] = [];
  204 |   for (let i = 0; i < 5; i++) { await inp.press('Backspace'); if (i < 4) mid.push(await H.countText(page)); }
  205 |   expect(await inp.inputValue(), 'AC-6①：框内已删光').toBe('');
  206 |   rec.midCounts = mid; // 删到一半的计数（不属 AC-6 断言，仅记录）
  207 |   rec.t1 = await H.within(1000, probe, ALL, 'AC-6① 退格删光后');
  208 |   await H.shot(page, 'T-6-①删光');
  209 |   // ② 再输入回车
  210 |   await inp.pressSequentially('30002', { delay: 50 }); await inp.press('Enter'); await page.waitForTimeout(800);
  211 |   rec.c2 = await H.countText(page);
  212 |   expect(rec.c2, 'AC-6②：匹配 4 条').toBe(H.txtMatch(4));
  213 |   // ③ 全选改为 3 个空格
  214 |   await inp.click(); await inp.press('Control+A'); await page.keyboard.type('   ');
  215 |   expect(await inp.inputValue(), 'AC-6③：框内应为 3 个空格（证明操作生效）').toBe('   ');
  216 |   rec.t3 = await H.within(1000, probe, ALL, 'AC-6③ 改为 3 个空格后');
  217 |   await H.shot(page, 'T-6-③三个空格');
  218 |   H.writeEvidence('T-6.json', JSON.stringify(rec, null, 1));
  219 | });
  220 | 
  221 | test('T-7 · AC-7 空态：文案引用最近一次回车提交的文字；追加不回车不变；清空查询恢复', async ({ page }) => {
  222 |   test.setTimeout(240_000);
  223 |   await login(page);
  224 |   await H.openEdit(page, A.id);
  225 |   await submit(page, 'XYZ-T260923');
  226 |   const sub = H.emptySubtitle('XYZ-T260923');
  227 |   const rec: any = { cnt: await H.countText(page), cards: await H.visibleSalesNos(page), pag: await H.paginationVisible(page) };
  228 |   await H.shot(page, 'T-7-空态');
  229 |   await expect(page.getByText(H.EMPTY_TITLE, { exact: true }).first(), 'AC-7：标题').toBeVisible();
  230 |   await expect(page.getByText(sub, { exact: true }).first(), 'AC-7：副文案逐字').toBeVisible();
> 231 |   expect(rec.cnt, 'AC-7：计数').toBe(H.TXT_NONE);
      |                              ^ Error: AC-7：计数
  232 |   expect(rec.pag, 'AC-7：分页器不显示').toBe(false);
  233 |   expect(rec.cards, 'AC-7：空态不应残留卡片').toEqual([]);
  234 |   // 追加 -9 不回车
  235 |   const inp = H.searchInput(page);
  236 |   await inp.click(); await inp.press('End'); await inp.pressSequentially('-9', { delay: 80 });
  237 |   await page.waitForTimeout(1500);
  238 |   expect(await inp.inputValue(), '框内应为 XYZ-T260923-9').toBe('XYZ-T260923-9');
  239 |   await expect(page.getByText(sub, { exact: true }).first(), 'AC-7：追加 -9 后副文案仍逐字引用「XYZ-T260923」').toBeVisible();
  240 |   await H.shot(page, 'T-7-追加未回车');
  241 |   // 清空查询
  242 |   await page.getByRole('button', { name: /清\s*空\s*查\s*询/ }).first().click();
  243 |   await page.waitForTimeout(800);
  244 |   const vals = await H.allSearchInputs(page).evaluateAll((els) => els.map((e) => (e as HTMLInputElement).value));
  245 |   rec.afterClear = { vals, cnt: await H.countText(page) };
  246 |   H.writeEvidence('T-7.json', JSON.stringify(rec, null, 1));
  247 |   expect(vals.length, '搜索框数应 ≥1').toBeGreaterThan(0);
  248 |   expect(vals.every((v) => v === ''), `AC-7：点「清空查询」后搜索框为空（实际 ${JSON.stringify(vals)}）`).toBe(true);
  249 |   expect(rec.afterClear.cnt, 'AC-7：点「清空查询」后计数').toBe(H.TXT_ALL);
  250 |   await H.shot(page, 'T-7-清空查询后');
  251 | });
  252 | 
  253 | test('T-8 · AC-8 输入法组字中回车不触发，普通回车触发', async ({ page }) => {
  254 |   test.setTimeout(240_000);
  255 |   await login(page);
  256 |   await H.openEdit(page, A.id);
  257 |   const inp = H.searchInput(page);
  258 |   await inp.click(); await inp.pressSequentially('30003', { delay: 60 });
  259 |   await inp.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true, bubbles: true, cancelable: true });
  260 |   await page.waitForTimeout(1500);
  261 |   const rec: any = { afterComposing: { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) } };
  262 |   await H.shot(page, 'T-8-组字中回车后');
  263 |   await inp.press('Enter'); await page.waitForTimeout(800);
  264 |   rec.afterEnter = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  265 |   await H.shot(page, 'T-8-普通回车后');
  266 |   // 量具对照：同样用 dispatchEvent 合成、但 isComposing=false 的回车必须能触发，
  267 |   // 否则「组字中回车不触发」可能只是合成事件整体没被处理（空验证）
  268 |   await inp.fill(''); await page.waitForTimeout(800);
  269 |   await inp.pressSequentially('30003', { delay: 30 });
  270 |   await inp.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: false, bubbles: true, cancelable: true });
  271 |   await page.waitForTimeout(1500);
  272 |   rec.gauge = { cnt: await H.countText(page) };
  273 |   H.writeEvidence('T-8.json', JSON.stringify(rec, null, 1));
  274 |   expect(rec.gauge.cnt, '量具对照：合成的非组字回车应触发搜索；不触发 ⇒ 组字断言【未验证】').toBe(H.txtMatch(4));
  275 |   expect(rec.afterComposing.cnt, 'AC-8：组字中回车后计数不变').toBe(H.TXT_ALL);
  276 |   expect(H.sorted(rec.afterComposing.nos), 'AC-8：组字中回车后仍为第 1 页 10 张').toEqual(H.sorted(H.FIRST_PAGE));
  277 |   expect(rec.afterEnter.cnt, 'AC-8：普通回车后计数').toBe(H.txtMatch(4));
  278 |   expect(H.sorted(rec.afterEnter.nos), 'AC-8：普通回车后卡片').toEqual(H.SET_30003);
  279 | });
  280 | 
  281 | test('T-9 · AC-9 序列：卡片 → 核价单 → 报价单 Excel 视图 → 切回卡片 → 改词不回车 → 回车 → 刷新', async ({ page }) => {
  282 |   test.setTimeout(300_000);
  283 |   await login(page);
  284 |   await H.openEdit(page, A.id);
  285 |   const seg = (label: string) => page.locator('.ant-segmented-item', { hasText: label }).first();
  286 |   const rec: any = {};
  287 |   // ①
  288 |   await submit(page, '30002');
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
```