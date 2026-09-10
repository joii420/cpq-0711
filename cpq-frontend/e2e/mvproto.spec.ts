import { test } from '@playwright/test';
const SHOT='/tmp/claude-1000/-home-joii-project-cpq/dd3dc6c5-18d8-4be3-b01e-677976918873/scratchpad/verify';
const P='file:///home/joii/project/cpq/.claude/worktrees/task-260909-field-type/dev-docs/task-260909-取数配置器字段类型选择/原型图/';
const pages=['01-列配置-基础核价默认.html','02-选择器展开态.html','03-列配置-报价方言对照.html'];
test('截原型图', async ({ page }) => {
  test.setTimeout(120000);
  for (let i=0;i<pages.length;i++){
    await page.goto(P+encodeURIComponent(pages[i]));
    await page.waitForTimeout(1200);
    await page.screenshot({ path: `${SHOT}/proto-${i+1}.png`, fullPage: true });
    console.log('已截', pages[i]);
  }
});
