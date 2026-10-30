/**
 * task-261030 · S-UNIT · T-03 静态页生成器（AC-8 长值与窄屏的几何前置）
 *
 * 把 `CostingSubtotalSummary` 的 SSR 标记连同样式表写成独立静态页，交给
 * `e2e/task261030-costing-subtotal-geometry.spec.ts` 在 1280×720 / 390×844 下量几何。
 *
 * 外壳（api.md §3.1，主线 2026-10-30 补定）：组件只渲染 `.qt-subtotal-costing` 根节点；外壳由父级负责 ——
 *   编辑页 `div.qt-subtotal-bar-multi`，只读页 `div.qt-subtotal-bar.qt-subtotal-bar-multi`。两种真实容器各包一次。
 *
 * 只在设置了 `T261030_STATIC_OUT` 时运行（由 `e2e/task261030-costing-subtotal-unit.global-setup.ts`
 * 以新的 run id 调起）；平时 `vitest run src` 里显示为 skipped，不写任何文件。
 *
 * 样式来源：`quotation.css`（需求文档 ④ 点名的 `.qt-subtotal-*` 样式落点）+ 同目录
 * `CostingSubtotalSummary*.css`（若存在）+ `src/index.css`（若存在）。测试不读这些文件的内容做判断，
 * 只原样内联给浏览器；但会硬性检查「至少有一条规则的选择器提到 `.qt-subtotal-costing`」，
 * 否则量到的是未上样式的裸标记 —— 那是 harness 失效，不是实现结论。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  FIXTURE_C,
  FIXTURE_LONG,
  hasClass,
  parseHtml,
  queryAll,
  readCostingSummary,
  renderCostingSummary,
} from './__tests__/task261030-costing-subtotal.ssr';

const OUT_DIR = process.env.T261030_STATIC_OUT?.trim();
const RUN_ID = process.env.T261030_STATIC_RUN_ID?.trim() ?? '';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC_ROOT = path.resolve(HERE, '..', '..');

/** 两种真实外壳（api.md §3.1）。键名即静态页文件名后缀，geometry spec 按同名读取。 */
const SHELLS = {
  edit: 'qt-subtotal-bar-multi',
  readonly: 'qt-subtotal-bar qt-subtotal-bar-multi',
} as const;

function stylesheetSources(): string[] {
  const candidates = [
    path.join(SRC_ROOT, 'index.css'),
    path.join(HERE, 'quotation.css'),
    ...fs.readdirSync(HERE)
      .filter((name) => /^CostingSubtotalSummary.*\.css$/.test(name))
      .map((name) => path.join(HERE, name)),
  ];
  return candidates.filter((file) => fs.existsSync(file));
}

function buildPage(title: string, markup: string, css: string, shellClass: string): string {
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="t261030-run-id" content="${RUN_ID}">
<meta name="t261030-shell" content="${shellClass}">
<title>${title}</title>
<style>${css}</style>
<style>
  html, body { margin: 0; background: #fafbfc; }
  .t261030-page { padding: 16px; }
</style>
</head>
<body>
<div class="t261030-page">
  <div class="qt-product-card" data-t261030-card="1">
    <div class="${shellClass}" data-t261030-shell="1">${markup}</div>
  </div>
</div>
</body>
</html>
`;
}

describe.runIf(Boolean(OUT_DIR))('task261030-costing-subtotal / S-UNIT static pages for T-03', () => {
  it('T-03 / AC-8 writes long/default static pages inside both real shells', () => {
    expect(RUN_ID, '必须由 global-setup 提供 run id，用于证明静态页是本轮生成的').not.toBe('');
    const sources = stylesheetSources();
    console.info(`[T261030_COST_][static] stylesheets=${JSON.stringify(sources.map((file) => path.relative(SRC_ROOT, file)))}`);
    expect(sources.some((file) => file.endsWith('quotation.css')), 'quotation.css 必须存在').toBe(true);
    const css = sources.map((file) => `/* ${path.basename(file)} */\n${fs.readFileSync(file, 'utf8')}`).join('\n');
    expect(/\.qt-subtotal-costing\b/.test(css), 'harness 失效：内联的样式表里没有任何选择器提到 .qt-subtotal-costing').toBe(true);

    const fixtures = [
      { name: 'long', props: { ...FIXTURE_LONG } },
      { name: 'default', props: { ...FIXTURE_C } },
    ];
    fs.mkdirSync(OUT_DIR!, { recursive: true });
    for (const fixture of fixtures) {
      const markup = renderCostingSummary(fixture.props);
      const tree = parseHtml(markup);
      const top = tree.children.filter((child) => typeof child !== 'string');
      expect(top.length === 1 && typeof top[0] !== 'string' && hasClass(top[0], 'qt-subtotal-costing'),
        `${fixture.name}: 组件标记的唯一根节点必须是 .qt-subtotal-costing（api.md §3.1）`).toBe(true);
      expect(queryAll(tree, (el) => hasClass(el, 'qt-subtotal-bar') || hasClass(el, 'qt-subtotal-bar-multi')).length,
        `${fixture.name}: 组件不得自带外壳（外壳由父级负责）`).toBe(0);
      const view = readCostingSummary(markup);
      expect(view.columns.length, `${fixture.name}: 必须恰好 2 列`).toBe(2);
      for (const [shellName, shellClass] of Object.entries(SHELLS)) {
        const file = `${fixture.name}-${shellName}.html`;
        fs.writeFileSync(path.join(OUT_DIR!, file), buildPage(`T261030_COST_ ${file}`, markup, css, shellClass), 'utf8');
        console.info(`[T261030_COST_][static] wrote ${file} (${markup.length} chars of markup)`);
      }
    }
  });
});
