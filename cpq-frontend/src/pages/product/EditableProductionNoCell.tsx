// ─────────────────────────────────────────────────────────────────────────────
// EditableProductionNoCell —— 销售产品列表「生产料号」单元格（子任务 · F-2）
//   服务 AC-6 / AC-10 / AC-12 / AC-13，视觉基准 `原型图/销售产品-可编辑生产料号.html`
//
// 🚫 **本组件只服务 `production_no` 这一列。** 销售产品其余 6 列（销售料号/品名/规格/尺寸/
//    旧料号/单重）保持只读，抽屉**维持全只读**（AC-7 反向断言）。
//    ⇒ 请勿把本组件泛化成「通用可编辑单元格」再铺到别处 —— 每多开放一列，
//      就多一个「下次导入会不会把它打回」的问题要单独走裁决（需求文档 AC-9 注）。
//
// 🔐 权限：**四个角色都能改**（用户 2026-09-03 裁决，AC-6b）⇒ 本文件**不读 authStore、
//    不写任何角色分支**。真正的防线在后端白名单（AC-11），前端不渲染输入框不算防线。
//
// 五种状态（对应原型的五行示例）：
//   ① 常态    纯文本（空值为灰 `—`），鼠标移到**行**上才浮出 ✎ 图标
//   ② 编辑中  Input：回车 / 失焦保存，Esc 取消
//   ③ 保存中  文本 + 「⟳ 保存中…」
//   ④ 保存失败 **回滚到原值** + 「✕ 保存失败，已还原」，🚫 不把失败的值留在界面上假装成功
//   ⑤ 空值    合法（用户原话「生产料号选填」），渲染灰 `—`，同样可编辑
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Input, message } from 'antd';
import type { InputRef } from 'antd';
import { EditOutlined, LoadingOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { updateDatasetPart } from './productHubApi';
import { DASH } from './productHubCells';
import type { ApiError } from '../../services/api';

/**
 * 列长上限，对应 `ds_quote_material.production_no varchar(128)`（AC-13）。
 *
 * 🚫 **刻意不给 Input 设 `maxLength`**：设了以后超长内容会被输入框**静默截断**，
 *    用户看不到任何提示、而截断后的值还会被真的写进库 —— 恰好违反 AC-13
 *    「给出可读提示」+「库中原值不被截断写入」两条。
 *    ⇒ 改为**保存前校验**：超长就停在编辑态、显示字符数提示、**一个请求都不发**。
 */
export const PRODUCTION_NO_MAX_LEN = 128;

/** 空值/占位灰度，与 `productHubCells` 的 MUTED 同色（原型 `.muted`） */
const MUTED: React.CSSProperties = { color: 'rgba(0, 0, 0, 0.25)' };

/** 状态文字（原型 `.st`）：保存中蓝、失败红 */
const ST_BASE: React.CSSProperties = { fontSize: 12, marginInlineStart: 6, whiteSpace: 'nowrap' };
const ST_SAVING: React.CSSProperties = { ...ST_BASE, color: '#1677ff' };
const ST_FAIL: React.CSSProperties = { ...ST_BASE, color: '#cf1322' };

/** ④「保存失败，已还原」提示的驻留时长（ms）。到点自动淡出，不需要用户手动关。 */
const FAIL_HINT_MS = 5000;

type CellState = 'view' | 'editing' | 'saving';

/** 归一化：去首尾空白；空串 ⇒ `null`（AC-12 要求落库 NULL 而不是空字符串） */
function normalize(raw: string): string | null {
  const t = raw.trim();
  return t === '' ? null : t;
}

/**
 * 把接口错误翻成用户读得懂的话（AC-13 / AC-15）。
 *
 * ⚠️ 401 的跳转由 `services/api.ts` 的全局拦截器负责（`window.location.href = '/login'`），
 *    本函数只保证**在跳走之前**用户看到的是「登录失效」而不是「Network error」。
 * ⚠️ 403 按用户 2026-09-03 的裁决（四角色全开）**当前不应出现**；留着是防后端将来收紧
 *    时前端只会甩一句 `Request failed`。
 */
function readableError(e: unknown): string {
  const err = e as ApiError | undefined;
  const status = err?.httpStatus;
  const msg = (err?.message ?? '').trim();
  if (status === 401) return '登录已失效，请重新登录后再试';
  if (status === 403) return '当前账号无权修改生产料号';
  if (status === 400) return msg || '生产料号不合法，请检查后重试';
  if (status === 404) return '该销售料号不存在或已被删除，请刷新列表';
  return msg && msg !== 'Network error' ? msg : '保存失败，请稍后重试';
}

export interface EditableProductionNoCellProps {
  /** 轴值 = 销售料号，作为 `PUT parts/{axisValue}` 的路径参数 */
  axisValue: string;
  /** 当前值。后端未补齐该字段时为 undefined ⇒ 与空值同样渲染 `—`，不得崩溃 */
  value?: string | null;
  /** 保存成功后回写列表本地状态，避免整表重取（也避免翻页/搜索态被刷掉） */
  onSaved: (next: string | null) => void;
}

const EditableProductionNoCell: React.FC<EditableProductionNoCellProps> = ({
  axisValue, value, onSaved,
}) => {
  const [state, setState] = useState<CellState>('view');
  const [draft, setDraft] = useState('');
  const [lenError, setLenError] = useState<string | null>(null);
  const [failHint, setFailHint] = useState(false);
  const inputRef = useRef<InputRef>(null);

  /** Esc 取消：`onKeyDown` 先于 `onBlur` 触发，用它抑制随后那次「失焦保存」 */
  const cancelledRef = useRef(false);
  /** 卸载后不再 setState（antd 6 + React 19 下会打 console 警告） */
  const aliveRef = useRef(true);
  const failTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 🚨 **必须在 effect 体里把 aliveRef 置回 true**，不能只在 cleanup 里置 false。
  //    `main.tsx` 开了 `React.StrictMode`，dev 下 effect 会「挂载 → 卸载 → 再挂载」跑两遍：
  //    只写 cleanup 的话首次挂载后 aliveRef 就永久为 false，此后每次保存都在 await 之后
  //    静默 early-return —— 症状是**单元格永远停在「保存中…」**，而后端其实已经写成功了。
  //    （2026-09-03 自检实测复现：mock 后端收到并落了 PUT，界面却卡在保存中、无成功提示。）
  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
      if (failTimer.current) clearTimeout(failTimer.current);
    };
  }, []);

  const current = value ?? null;

  const enterEdit = useCallback(() => {
    if (state !== 'view') return;
    cancelledRef.current = false;
    setFailHint(false);
    setLenError(null);
    setDraft(current ?? '');
    setState('editing');
    // antd 的 autoFocus 在 Table 重渲染下不稳，补一次显式聚焦并全选
    setTimeout(() => inputRef.current?.focus({ cursor: 'all' }), 0);
  }, [state, current]);

  const cancelEdit = useCallback(() => {
    cancelledRef.current = true;
    setLenError(null);
    setState('view');
  }, []);

  const commit = useCallback(async () => {
    if (cancelledRef.current) { cancelledRef.current = false; return; }
    const next = normalize(draft);

    // AC-13：超长**不发请求**，停在编辑态给出可读提示（库里原值因此绝不会被截断写入）
    if (next !== null && next.length > PRODUCTION_NO_MAX_LEN) {
      setLenError(`生产料号最长 ${PRODUCTION_NO_MAX_LEN} 字符，当前 ${next.length}`);
      // 停在编辑态：失焦触发时把焦点抢回来，否则提示一闪就没了
      setTimeout(() => inputRef.current?.focus(), 0);
      return;
    }

    // 值没变就直接退出，不打接口（避免每次点进点出都写一次 updated_at）
    if (next === current) { setLenError(null); setState('view'); return; }

    setLenError(null);
    setState('saving');
    try {
      // 🚫 只传 productionNo —— 不整行回传、不传 source（api.md §1 硬约束 1 / 2）
      await updateDatasetPart(axisValue, { productionNo: next });
      if (!aliveRef.current) return;
      setState('view');
      onSaved(next);
      message.success('保存成功');
    } catch (e) {
      if (!aliveRef.current) return;
      // ④ 失败：**回滚到原值**（state 回 view 后渲染读的就是 props 里的 current），
      //    🚫 不把失败的值留在界面上假装成功
      setState('view');
      setDraft(current ?? '');
      setFailHint(true);
      if (failTimer.current) clearTimeout(failTimer.current);
      failTimer.current = setTimeout(() => {
        if (aliveRef.current) setFailHint(false);
      }, FAIL_HINT_MS);
      message.error(readableError(e));
    }
  }, [draft, current, axisValue, onSaved]);

  // 🚨 整个单元格吞掉点击：`ProductSalesPartTab` 的 `onRow.onClick` 会开抽屉，
  //    不吞的话「双击进编辑」会先被解释成两次开抽屉，编辑根本进不去。
  //    代价：这一格单击不再开抽屉（同行其余 6 格照常开），已在回报里列为偏差。
  const swallow = (e: React.SyntheticEvent) => { e.stopPropagation(); };

  if (state === 'editing') {
    return (
      <div onClick={swallow} onDoubleClick={swallow}>
        <Input
          ref={inputRef}
          size="small"
          status={lenError ? 'error' : undefined}
          value={draft}
          onChange={(e) => {
            setDraft(e.target.value);
            if (lenError) setLenError(null);
          }}
          onPressEnter={() => { void commit(); }}
          onBlur={() => { void commit(); }}
          onKeyDown={(e) => { if (e.key === 'Escape') cancelEdit(); }}
          placeholder="生产料号（选填）"
          style={{ width: '100%' }}
        />
        {lenError && (
          <div style={{ color: '#cf1322', fontSize: 12, marginTop: 4 }}>{lenError}</div>
        )}
      </div>
    );
  }

  const text = current === null || current === '' ? null : String(current);

  return (
    <div
      onClick={swallow}
      onDoubleClick={(e) => { swallow(e); enterEdit(); }}
      // 🚨 必须 `width:100%` 铺满单元格：只读态内容可能只有一个 `—`，
      //    容器若按内容收缩，用户双击单元格空白处就落在容器外 —— 表现为「双击没反应」
      //    （实测：inline-flex 时 Playwright 双击单元格中心命中不到本容器）。
      style={{ display: 'flex', alignItems: 'center', gap: 6, width: '100%', minHeight: 22 }}
    >
      {text === null
        ? <span style={MUTED}>{DASH}</span>
        : <span title={text} style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{text}</span>}

      {state === 'saving' && <span style={ST_SAVING}><LoadingOutlined /> 保存中…</span>}

      {state === 'view' && failHint && (
        <span style={ST_FAIL}><CloseCircleOutlined /> 保存失败，已还原</span>
      )}

      {state === 'view' && !failHint && (
        // ✎ 常态透明，鼠标移到**行**上才浮出（原型 `tr:hover .pen{opacity:1}`）。
        // 用 class + global.css 而不是 React hover state：CSS 才能做到「悬停整行」，
        // 单元格自己管 hover 只会在移到这一格时才亮，与原型不符。
        <EditOutlined
          className="ph-edit-pen"
          title="编辑生产料号"
          onClick={(e) => { swallow(e); enterEdit(); }}
        />
      )}
    </div>
  );
};

export default EditableProductionNoCell;
