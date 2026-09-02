import React from 'react';
import { Modal } from 'antd';

/**
 * task-260901 F-2d（AC-12）：保存草稿撞版本冲突时的阻断式提示。
 *
 * 1:1 还原 `dev-docs/task-260901-保存草稿增量协议与并发保护/原型图/冲突提示.html` 状态 1：
 *   · 标题「保存失败」
 *   · 正文「这张报价单已被他人修改，你页面上的数据已过期。」
 *     + 次级说明（原型 `.dim`：45% 灰、13px、上间距 8px）「刷新后请重新编辑。当前未保存的修改将丢失。」
 *   · **只有一个「刷新页面」按钮**（primary）
 *
 * 🚫 不得加「强制覆盖」「忽略」「稍后再说」，也不给右上角关闭 ×（closable / maskClosable 全关）。
 *   理由（原型图注解原文）：给「强制覆盖」等于把 `QuotationLineItem` 类注释里那个 4/4 复现的
 *   静默数据丢失重新放回来 —— 用户会覆盖掉对方改的年用量、折扣，且没有任何提示。
 *
 * 用 antd `Modal.error` 而非 Drawer：本弹层是「阻断式单按钮告知」（一句话 + 一个动作，
 * 无表单、无多步、无内容浏览），命中 `frontend.md` §1.1 的例外条款「简单二次确认」。
 * 判据（原型图注解）：将来若演化成「列出冲突行让用户逐行选」，就变成内容浏览 + 决策界面，
 * 届时必须改用 Drawer。
 */

let dialogOpen = false;

export function showStaleVersionDialog(onRefresh: () => void = () => window.location.reload()): void {
  // 连点保存 / 并发两条请求同时 409 时只弹一次，避免叠罗汉。
  if (dialogOpen) return;
  dialogOpen = true;
  Modal.error({
    title: '保存失败',
    content: (
      <div data-testid="stale-version-dialog">
        这张报价单已被他人修改，你页面上的数据已过期。
        <span style={{ color: 'rgba(0,0,0,.45)', fontSize: 13, display: 'block', marginTop: 8 }}>
          刷新后请重新编辑。当前未保存的修改将丢失。
        </span>
      </div>
    ),
    okText: '刷新页面',
    closable: false,
    maskClosable: false,
    keyboard: false,
    onOk: () => {
      dialogOpen = false;
      onRefresh();
    },
  });
}

/** 仅供测试复位。 */
export function resetStaleVersionDialog(): void {
  dialogOpen = false;
}
