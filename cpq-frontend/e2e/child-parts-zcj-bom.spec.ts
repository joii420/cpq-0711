/**
 * 选配-子配件清单 driver 与字段源统一到 $zcj_bom (V262, 2026-05-27)
 *
 * Bug: 报价单 QT-20260527-1651 子配件清单页签 数量/单位显示 "X (共 5 项)"，
 *      子料号显示投料号 9997 而非装配子件 8881~8885。
 * 根因 (AP-22+AP-37): data_driver_path=materials_mirror(投料 2 行) 与
 *      字段 path=$zcj_bom(装配子件 5 行) 维度不一致 → 数量/单位走全量查返数组。
 * 修复: driver 改 $zcj_bom，与字段同源，driver 返 5 行，每字段短路取单值。
 *
 * 本 spec 用 batch-expand API (前端 useDriverExpansions 真实调用形态:
 * 带 lineItemId + compositeType=SIMPLE) 验证 rowCount=5 + 数量/单位为单值非数组。
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const BACKEND = 'http://localhost:8081';
const CHILD_PARTS_COMP = 'c3ad18a5-2cd8-424e-b702-ea26f3a48cee';
const ROCKWELL = '3027d83b-d412-407d-ae43-5d513fed7b1e';
const LINE_ITEM = '6e899233-d189-4f64-8299-9a499c6d84d1';  // QT-1651 lineItem 3120012574
const PART_NO = '3120012574';

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('选配-子配件清单: driver=$zcj_bom 返 5 行装配子件,数量/单位单值非数组', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);

  // batch-expand 带 lineItemId + compositeType=SIMPLE = 前端 useDriverExpansions 真实调用
  const resp = await page.request.post(`${BACKEND}/api/cpq/components/batch-expand`, {
    data: { tasks: [{
      componentId: CHILD_PARTS_COMP,
      customerId: ROCKWELL,
      partNo: PART_NO,
      lineItemId: LINE_ITEM,
      compositeType: 'SIMPLE',
    }] },
  });
  expect.soft(resp.status(), 'batch-expand 必须 200').toBe(200);
  const result = (await resp.json()).data.results[0];

  // eslint-disable-next-line no-console
  console.log('[CHILD-PARTS] status=', result.status, 'driverPath=', result.data?.driverPath, 'rowCount=', result.data?.rowCount);
  expect.soft(result.status).toBe('OK');
  expect.soft(result.data?.driverPath, 'driver 必须是 $zcj_bom (装配子件视图)').toBe('$zcj_bom');
  expect.soft(result.data?.rowCount, 'zcj_bom 对 3120012574 返 5 行装配子件').toBe(5);

  const rows = result.data?.rows || [];
  const childKey = '{$zcj_bom.child_hf_part_no}';
  const qtyKey = '{$zcj_bom.composition_qty}';
  const unitKey = '{$zcj_bom.issue_unit}';

  // 子料号必须是装配子件 8881~8885,不是投料号 9997/9998
  const childNos = rows.map((r: any) => r.basicDataValues[childKey]).sort();
  // eslint-disable-next-line no-console
  console.log('[CHILD-PARTS] 子料号 =', childNos);
  expect.soft(childNos, '子料号应为装配子件 8881~8885').toEqual(['8881', '8882', '8883', '8884', '8885']);

  // 关键: 数量/单位必须是单值 (number/string),不是数组 → 不会渲染 "(共N项)"
  for (let i = 0; i < rows.length; i++) {
    const qty = rows[i].basicDataValues[qtyKey];
    const unit = rows[i].basicDataValues[unitKey];
    expect.soft(Array.isArray(qty), `row${i} 数量不应是数组(否则前端显"(共N项)")`).toBe(false);
    expect.soft(Array.isArray(unit), `row${i} 单位不应是数组`).toBe(false);
  }
  // 抽验具体值
  const r0 = rows.find((r: any) => r.basicDataValues[childKey] === '8881');
  expect.soft(Number(r0?.basicDataValues[qtyKey]), '8881 数量=1').toBe(1);
  expect.soft(r0?.basicDataValues[unitKey], '8881 单位=PCS').toBe('PCS');
  const r2 = rows.find((r: any) => r.basicDataValues[childKey] === '8883');
  expect.soft(r2?.basicDataValues[unitKey], '8883 单位=g').toBe('g');

  // eslint-disable-next-line no-console
  console.log('✅ 子配件清单 driver/字段同源 $zcj_bom, 5 行单值无 (共N项)');
});
