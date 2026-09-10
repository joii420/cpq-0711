import { test, expect } from '@playwright/test';
import * as FT from './t260909ft.helpers';
import { openComponentByCode, switchTab } from './task260908-s2.helpers';

/**
 * AC-15② 的**安全取证**：证明「什么都不改直接保存」会往后端发什么，
 * 但 **abort 掉请求** —— 不真的写库，COMP-2299 一个字节不动。
 */
test('AC-15② 安全取证: 拦截 PUT /builder 并 abort，只看 payload', async ({ page }) => {
  test.setTimeout(180_000);
  let payload: any = null;
  let hits = 0;
  await page.route('**/api/cpq/components/*/builder', async (route) => {
    if (route.request().method() === 'PUT') {
      hits++;
      payload = route.request().postDataJSON();
      await route.abort('failed');            // 🚦 不放行，绝不写库
    } else {
      await route.continue();
    }
  });
  await FT.uiLogin(page);
  await openComponentByCode(page, 'COMP-2299');
  await switchTab(page, '取数配置');
  await page.waitForTimeout(5000);
  await FT.clickBuilderSave(page).catch((e) => console.log('[save click] ' + String(e).slice(0, 200)));
  await page.waitForTimeout(3000);
  console.log('[ABORT] PUT 命中次数 = ' + hits);
  expect(hits, '没捕获到 PUT /builder ⇒ 本取证无效（量具问题），不得据此下结论').toBeGreaterThan(0);
  const cols = (payload?.columns ?? []).map((c: any) => `${c.fieldName}=${c.fieldType}`);
  console.log('[ABORT] 前端将要提交的 columns[].fieldType =\n' + JSON.stringify(cols, null, 1));
  FT.writeEvidence('AC-15-2-拦截取证-前端将提交的payload.md',
    ['# AC-15② 安全取证（拦截 PUT /builder 并 abort，未写库）', '',
     `- 捕获 PUT 次数 = ${hits}`, '',
     '## 前端「什么都不改直接保存」将提交的 fieldType', '',
     ...cols.map((c: string) => `- ${c}`), '',
     '## 库里真实值（对照）', '',
     ...FT.dbFieldsOf(FT.sqlScalar(`SELECT id::text FROM component WHERE code='COMP-2299'`))
        .map((f: any) => `- ${f.field_name}=${f.field_type}`),
    ].join('\n') + '\n');
});
