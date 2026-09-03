/**
 * task-260902 · 组件层 · 三个页面工具栏的「导出」按钮三态（T-01 / T-02 / T-09 组件部分 / T-23）。
 *
 * 断言派生自 `需求文档.md §③` 的 AC 原文 + `原型图/1-材质页签-工具栏.html` /
 * `2-工序页签-工具栏.html` / `3-用户列表.html` 的**定稿文案**。🚫 不读实现代码。
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 测试手段说明（重要，先读这段再改本文件）
 * ─────────────────────────────────────────────────────────────────────────────
 * 本项目**未安装** `@testing-library/react` / `jsdom`（`package.json` 实测），且 `node_modules`
 * 跨多个并发 worktree 共享，不能为本任务临时新增依赖（会影响其它并发会话）。
 * ⇒ 沿用仓库既有先例 `src/pages/component/__tests__/treeRefUI.test.tsx` 的做法：
 *    用 `react-dom/server` 的 `renderToStaticMarkup` 渲染**真实组件树**取 HTML 字符串再断言。
 *    这是「真实渲染输出」而不是手抄结构体。
 *
 * 🚨 这套手段的**两条固有边界**（已报主线，不是本文件可以自行绕过的）：
 *   1. **SSR 不跑 `useEffect`** ⇒ 列表数据恒为空。所以：
 *      - 「有数据时导出按钮可点击」（AC-1 后半句）**在本层验不了**，由 E2E T-22 覆盖；
 *      - 「0 条时禁用」（AC-23）恰好就是 SSR 的初始态，能验。
 *   2. **AntD `Tooltip` 的浮层走 Portal，SSR 不输出** ⇒ tooltip 文案在本层**验不了**。
 *      2026-09-02 用户裁决（test.md 矩阵 AC-23 行）：**AC-23 拆两层** ——
 *      本文件只做 `T-23a`（断 `disabled` + 「导入」按钮不被禁用），
 *      **tooltip 文案由 `T-23b` 在 E2E 里用真实浏览器读**（`e2e/task260902-export-import.spec.ts`）。
 *      🚫 **不改实现去迁就测试**（曾提过让实现加 `data-*` 桥接属性，已被否决）。
 *
 * 🚨 **2026-09-03 实证：本层验不了「按角色显隐」这一整个维度，相关用例已全部改判 E2E。**
 *    机制：`renderToStaticMarkup` 走 React 的 **SSR 路径**，zustand 在该路径下取的不是
 *    `getState()`（我 `setRole()` 写进去的值），所以页面里
 *    `useAuthStore((s) => s.user?.role === 'SYSTEM_ADMIN')` **恒为 false**。
 *    后果分两面，第二面才是危险的：
 *      · 「管理员应看到导出按钮」→ 恒红（看起来像权限判错的产品缺陷）；
 *      · 「非管理员不该看到导出按钮」→ **恒绿，但是空验证** —— 它不是因为角色判断正确才绿，
 *        而是因为**任何角色下按钮都不渲染**。这是 testing.md §3 的典型假绿，
 *        比恒红危险得多（恒红会被查，恒绿不会）。
 *    ⇒ 已把 T-01 / T-02 / T-09 / T-23a(材质·工序) 全部移到
 *      `e2e/task260902-export-import.spec.ts` 的 **T-01/02/09-UI** 用真实浏览器验证。
 *    ⇒ 本文件只保留**不含角色门**、SSR 能如实验证的用户页用例。
 *    🚫 不保留「反正它绿」的空验证用例。
 *
 * 另：**AC-27 已由组件层改判 E2E**（`T-27`）—— 本项目无 jsdom/RTL，点不了、也观察不了请求，
 * 写一个「渲染出来了就算过」的替身用例会恒绿且看起来像已覆盖（假绿）。
 */
import { describe, it, expect, beforeEach } from 'vitest';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';

import MaterialRecipeManagement from '../config/MaterialRecipeManagement';
import V6ProcessCrudTab from '../master-data/V6ProcessCrudTab';
import UserManagement from '../system/UserManagement';

// ─────────────────────────────────────────────
// 原型图定稿文案（逐字，🚫 不许为了让用例变绿而改这里）
// ─────────────────────────────────────────────

/** 原型图 1 状态 A：材质页签右组 5 个动作，顺序即读取类在前、写入类在后。 */
const MATERIAL_TOOLBAR = ['刷新', '导出材质库', '导入材质库', '下载导入模板', '新建材质'] as const;
/** 原型图 2 状态 A：工序页签右组 4 个动作。 */
const PROCESS_TOOLBAR = ['刷新', '导出工序', '导入工序', '新增工序'] as const;
/** 原型图 3 状态 A：用户列表右组 3 个动作。 */
const USER_TOOLBAR = ['导出用户', '导入用户', '新增用户'] as const;

/** 原型图 1/2/3 共用同一句，🚫「三个页面各写各的」是缺陷。 */
const EMPTY_EXPORT_TOOLTIP = '当前筛选结果为 0 条，无可导出数据';

type Role = 'SYSTEM_ADMIN' | 'SALES_MANAGER' | 'PRICING_MANAGER' | 'SALES_REP';

function setRole(role: Role) {
  const next = {
    user: { id: 't260902-uid', username: 't260902', fullName: 'T260902 测试', role },
    isAuthenticated: true,
    loading: false,
    forceChangePassword: false,
  };
  useAuthStore.setState(next);
  // 🚨 关键（2026-09-03 实测定位）：zustand v5 的 `useStore` 在 **SSR 路径**上取的是
  //    `api.getInitialState()` 作为 server snapshot，**不是** `api.getState()` ——
  //    也就是说 `setState()` 在 `renderToStaticMarkup` 下**完全不生效**，
  //    页面里 `useAuthStore((s) => s.user?.role === 'SYSTEM_ADMIN')` 恒为 false。
  //    症状：管理员态下「导出材质库/导出工序」查不到 —— 看起来像**权限判错的产品缺陷**，
  //    实际是本测试手段的坑（对照：用户页不做角色判断，所以一直是绿的，更容易误导）。
  //    ⇒ 这里把 server snapshot 也指到当前 state。这是**测试脚手架**的修正，
  //      🚫 没有改动任何断言，也没有碰实现。
  (useAuthStore as unknown as { getInitialState: () => unknown }).getInitialState = () =>
    useAuthStore.getState();
}

/**
 * 渲染一个页面组件，返回 HTML。
 * 渲染抛错时给出**可执行的**诊断，而不是一句 stack —— 渲染失败与断言失败是两类问题，
 * 混在一起会被误判成「功能没做」。
 */
function render(name: string, el: React.ReactElement): string {
  try {
    return renderToStaticMarkup(<MemoryRouter>{el}</MemoryRouter>);
  } catch (e) {
    throw new Error(
      `[task260902] ${name} 在 SSR 下渲染失败：${(e as Error)?.message}\n` +
      '这不是产品缺陷，是本层测试手段的边界。常见原因与处置：\n' +
      '  · 组件依赖 window/document（AntD 某些组件、Handsontable）⇒ 该断言改由 E2E 覆盖；\n' +
      '  · 组件需要必填 props（如 V6ProcessCrudTab 挂在 MasterDataHubPage 下）⇒ 补 props；\n' +
      '  · 组件在模块顶层发请求 ⇒ 报主线，那本身是个问题。\n' +
      '🚫 不要为了让它过就删断言。'
    );
  }
}

/** 从 SSR 输出里摘出某个按钮的整段 `<button ...>文字</button>`（判 disabled 用）。 */
function buttonTag(html: string, text: string): string | undefined {
  const esc = text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  // AntD 的 Button 会把文字包一层 <span>，两种形态都收
  const re = new RegExp(`<button[^>]*>(?:(?!</button>).)*?${esc}(?:(?!</button>).)*?</button>`, 's');
  return html.match(re)?.[0];
}

/** 该文字是否出现在渲染输出里（AntD Button 文字可能被 <span> 包裹，故只查文字本身）。 */
function hasText(html: string, text: string): boolean {
  return html.includes(text);
}

/** 各按钮文字在 HTML 里的出现位置（判顺序用）。-1 表示没渲染。 */
function order(html: string, texts: readonly string[]): number[] {
  return texts.map((t) => html.indexOf(t));
}

describe('task-260902 · 导出按钮三态（组件层）', () => {
  beforeEach(() => setRole('SYSTEM_ADMIN'));

  // ═══════════════ T-01 → AC-1：材质页签，管理员态 5 个动作 ═══════════════

  it('AC-12 / 原型图 3：用户管理页两个新按钮常显（整页已限 SYSTEM_ADMIN，不再做角色判断）', () => {
    setRole('SYSTEM_ADMIN');
    const html = render('UserManagement', <UserManagement />);
    for (const t of USER_TOOLBAR) {
      expect(hasText(html, t), `原型图 3 状态 A：用户列表工具栏缺少「${t}」`).toBe(true);
    }
    const pos = order(html, USER_TOOLBAR);
    expect(pos, `原型图 3：右组顺序必须是 ${USER_TOOLBAR.join(' → ')}`).toEqual([...pos].sort((a, b) => a - b));
  });

  // ═══════════════ T-23 → AC-23：0 条时禁用（三页同规则）═══════════════
  //
  // ⚠️ SSR 不跑 useEffect ⇒ 列表恒为空 ⇒ 这里渲染出来的就是 AC-23 的「筛选结果 0 条」态。
  //    正因如此，本层**验不了**「有数据时按钮可点击」的对照面 —— 那条由 E2E T-22 覆盖
  //    （T-22 在真实数据下点导出，点得动就证明了不是「恒定禁用」）。

  // 只留用户页：材质/工序的导出按钮带角色门，SSR 下恒不渲染（见文件头「本层能力边界」），
  // 它们的禁用态由 E2E `T-23b` 在真实浏览器里断言。
  const emptyStateCases = [
    { name: '用户', el: () => <UserManagement />, exportBtn: '导出用户', importBtn: '导入用户' },
  ] as const;

  it.each(emptyStateCases)(
    'T-23a / AC-23：$name 页在结果 0 条时「$exportBtn」禁用，而「$importBtn」不被禁用',
    ({ name, el, exportBtn, importBtn }) => {
      setRole('SYSTEM_ADMIN');
      const html = render(`${name}(空态)`, el());

      const exp = buttonTag(html, exportBtn);
      expect(exp, `AC-23：渲染输出里找不到「${exportBtn}」按钮`).toBeDefined();
      expect(/\bdisabled\b/.test(exp!),
        `AC-23：结果为 0 条时「${exportBtn}」必须是禁用态（管理员有这个能力，只是此刻没东西可导），` +
        `实际渲染 = ${exp}`
      ).toBe(true);

      const imp = buttonTag(html, importBtn);
      expect(imp, `AC-23：渲染输出里找不到「${importBtn}」按钮`).toBeDefined();
      expect(/\bdisabled\b/.test(imp!),
        `AC-23：🚨「${importBtn}」在空态下**不**该被禁用 —— 一张空表恰恰最需要导入` +
        `（原型图 3 状态 B 明确写了这条），实际渲染 = ${imp}`
      ).toBe(false);
    }
  );

  // 🚚 tooltip 文案（AC-23 的后半句）已移到 E2E `T-23b` —— AntD Tooltip 走 Portal，
  //    SSR 输出里没有它。这里**刻意不留一个「查不到就跳过」的替身用例**：
  //    那种用例恒绿，且在报告里看起来像「已覆盖」。
  //    E2E 侧的三个坑（antd v6 类名是 .ant-tooltip-container 不是 .ant-tooltip-inner；
  //    禁用元素必须用 mouse.move 不能 hover；关闭 tooltip 只隐藏不移除 ⇒ 只取「当前可见」的
  //    并留 hover 前对照组）都写在 e2e/task260902-export-import.spec.ts 的 T-23b 里。

  // ═══════════════ T-27 → AC-27：已改判 E2E ═══════════════
  //
  // AC-27 的可观测断言是「点击后按钮进入 loading，期间再次点击不发第二个请求」—— 那是**交互**断言，
  // 需要能点击、能观察网络。本项目没有 jsdom/RTL，`renderToStaticMarkup` 只出一次静态 HTML。
  // ⇒ 2026-09-02 主线裁决：AC-27 落 E2E（见 e2e/task260902-export-import.spec.ts 的 T-27，
  //   用 `page.on('request')` 计数）。本文件**不留替身用例**，也不留 `it.todo` ——
  //   todo 在报告里仍占一行「待办」，容易被读成「测试侧欠账」，实际是已换层覆盖。
});
