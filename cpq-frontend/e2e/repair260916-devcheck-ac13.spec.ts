/**
 * repair-260916 · AC-13④ (mainline, after merge): on the shared dev stack, COMP-0004's builder preview
 * (CUST-0004 / S3120011203) shows 00144 → H85. Read-only: every non-GET API call except compute endpoints is aborted.
 */
import { test, expect } from '@playwright/test';
import * as path from 'path';
import * as H from './repair260916-global.helpers';

test('AC-13④ 开发库 COMP-0004 预览 00144 → H85', async ({ page }) => {
  await H.uiLogin(page);
  const blocked = await H.installWriteGuard(page);
  const c4 = H.COMPS.find((c) => c.code === 'COMP-0004')!;
  const { get } = await H.openBuilderTab(page, c4);
  console.log(`[AC-13] isStale=${get.isStale} builderVersion=${get.builderVersion}`);
  expect(get.isStale).toBe(false);
  const p = await H.runPreview(page, H.CUSTOMER_CODE, 'S3120011203', 'AC-13 dev COMP-0004');
  const byPart = H.namesByPart(p.rows, '_来料固定加工费_投入料号');
  console.log(`[AC-13] rows=${p.rows.length} names=${JSON.stringify(byPart)} diagnostics=${JSON.stringify(p.diagnostics)}`);
  expect(p.rows.length).toBeGreaterThan(0);
  expect(byPart['00144']).toEqual(['H85']);
  expect(byPart['S3110520422']).toEqual(['料号2']);
  const out = path.join(H.EVID, 'AC-13');
  await page.locator('.svb-preview').first().screenshot({ path: path.join(out, 'AC-13-开发库-COMP-0004-预览-00144为H85.png') });
  await page.screenshot({ path: path.join(out, 'AC-13-开发库-COMP-0004-取数配置整页.png'), fullPage: true });
  console.log(`[AC-13] write guard blocked=${JSON.stringify(blocked)}`);
  expect(blocked).toEqual([]);
});
