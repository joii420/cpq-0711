// task-260920 · 主线亲验：把三页原型渲染成整页截图，供与真实数据截图逐屏对照（只读本地 HTML，不连任何服务）
import { test } from '@playwright/test';
const P = '/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute/dev-docs/task-260729-客户价格调整策略和价格版本/task-260920-审核列表秒开与按需试算/原型图';
const OUT = '/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute/dev-docs/task-260729-客户价格调整策略和价格版本/task-260920-审核列表秒开与按需试算/证据/亲验/原型比对';
for (const f of ['审核列表-未计算态', '审核抽屉-触发计算', '批量通过-试算与确认']) {
  test('原型 ' + f, async ({ page }) => {
    await page.goto('file://' + P + '/' + f + '.html');
    await page.waitForTimeout(500);
    await page.screenshot({ path: OUT + '/原型-' + f + '.png', fullPage: true });
  });
}
