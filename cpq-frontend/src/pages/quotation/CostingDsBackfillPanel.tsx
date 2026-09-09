/**
 * CostingDsBackfillPanel —— 核价通过确认抽屉里的「基础数据升版比对」区（task-260907 第二段 F-1~F-5）
 *
 * 视觉基准：dev-docs/task-260907-报价导入建单切ds新表/task-260907-record层与核价回填/原型图/
 *   01-核价通过确认抽屉-默认态.html / 02-…跨版与无法对齐.html / 03-…零变更与空态.html / 04-…极值与错误态.html
 * 契约：同目录 api.md §1（GET /quotations/{id}/costing-approve/preview → dsBackfill）
 *
 * 🚨 三条硬要求（原型图 index.html 文末 + AC-5④ / AC-14③ / AC-20③）：
 *   1. 「本次不动」（untouchedRows）列必须渲染，不许省、不许折叠 —— AP-60 守卫。
 *      真实事故：预览显示「0 变更」，执行删了 3 行。财务要能看见「这一组有 7 行本次不动」。
 *   2. result === 'UNCHANGED' 的组仍要列出（本次覆盖 = 0），不许过滤 ——
 *      否则分不清「这张表没变」和「这张表根本没被算进去」。
 *   3. 「对不上的行」不许折叠进「更多」：红色告警条 + 独立明细表 + 主按钮变红 + 页脚提示条数。
 *   4. 🆕 `result === 'BLOCKED'`（2026-09-07 契约由两值扩到四值）：该组**本次跳过回填、一个字节不写**，
 *      但**核价通过照常进行** —— 🚫 不阻断、不禁用确认按钮、主按钮不变红。
 *      ⇒ 它不是错误态，是「这组我没敢写，你来看一眼」：视觉上**比 UNCHANGED 显眼、比报错克制**，
 *      本组件统一用橙档（Tag color=orange / 行底 #fff7e6 / warning 告警条）承载，
 *      并在**独立的**「本次跳过的组」明细表里给出 grainKey 与两侧行数（🚫 不并进「对不上的行」，理由见 BlockedSection）。
 *
 * ⚠️ 本文件里有**三套互不相干的 reason 值域**（行级 unanchored / 组件级 nonParticipating / 组级 blocked），
 *    🚫 任何情况下都不许合并成一张映射表 —— 它们回答的是三个不同的问题，后果也各不相同。
 *
 * ⚠️ 列集与原型的偏差：AC-5② 规定表头固定九列（销售料号 / 版本迁移 / 判定 / 整组行数 / 本次覆盖 /
 *    本次不动 / 对不上 / 本次覆盖的列 / 原样保留的列），原型 01 少画「对不上」、02/04 少画「原样保留的列」。
 *    按 CLAUDE.md「原型与 AC 冲突以 AC 原文为准」→ 本组件恒渲染九列。
 */
import React from 'react';
import { Alert, Empty, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type {
  DsBackfillCollidingRow,
  DsBackfillGroup,
  DsBackfillNoRecordSnapshot,
  DsBackfillNonParticipating,
  DsBackfillPreview,
  DsBackfillRecordStale,
  DsBackfillTable,
  DsBackfillUnanchoredRow,
} from '../../services/costingOrderService';

const { Text } = Typography;

const MONO = '"SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace';
const MUTED = 'rgba(0,0,0,.45)';

/** 抽屉外层（标题 / 页脚文案 / 主按钮形态）需要的派生状态，与本面板同源，避免两处各算一遍。 */
export interface DsBackfillFlags {
  /** 后端下发了 dsBackfill 段（未下发 = 后端尚未发布，前端只渲染老回填，不报错） */
  present: boolean;
  /** applicable === true，走新回填 */
  applicable: boolean;
  upgradedGroups: number;
  unchangedGroups: number;
  /** 🔴 >0 → 红色告警条 + 明细表 + 主按钮变红 */
  unanchoredRows: number;
  /** 全部判定 UNCHANGED（AC-14③：顶部绿色提示条 + 页脚文案改变） */
  allUnchanged: boolean;
  /**
   * 🆕 判定为 BLOCKED 的组数（本次跳过回填，一个字节不写，须事后人工处理）。
   * 🚫 它**不阻断确认** —— 抽屉的禁用逻辑与红色主按钮一律不看这个数。
   */
  blockedGroups: number;
  /**
   * 🆕 D-35：`_record` 快照可能过期 ⇒ **这份预览本身可能不可信**。
   * 取 `recordStale.stale` 与 `summary.recordStale` 的**并**，🚫 不许只信其中一个。
   */
  recordStale: boolean;
  /**
   * 🆕 整单没有比对快照 ⇒ **确认后一个字节都不会写**。
   * ⚠️ 它**必然**伴随 `tables=[]`，🚫 任何消费方都不许把它挂在 `hasTables` 上。
   */
  noRecordSnapshot: boolean;
  /** 🆕 D-33：不参与升版的组件数（summary 未下发时按数组长度兜底） */
  nonParticipatingComponents: number;
  /** 🆕 applicable=true 但一张表都没有 —— 只有 nonParticipating 要说的场景 */
  hasTables: boolean;
}

/**
 * 🆕 BLOCKED 组计数。summary 未下发 `blockedGroups` 时按实际渲染出来的组兜底 ——
 * 口径同 `nonParticipatingComponents`：🚫 不许因 summary 缺字段就当 0，那正是「静默」本身。
 */
const countBlockedGroups = (ds?: DsBackfillPreview | null): number =>
  (ds?.tables ?? []).reduce(
    (n, t) => n + (t.groups ?? []).filter((g) => g.result === 'BLOCKED').length,
    0,
  );

export const deriveDsFlags = (ds?: DsBackfillPreview | null): DsBackfillFlags => {
  const s = ds?.summary;
  const upgraded = s?.upgradedGroups ?? 0;
  const unchanged = s?.unchangedGroups ?? 0;
  return {
    present: !!ds,
    applicable: ds?.applicable === true,
    upgradedGroups: upgraded,
    unchangedGroups: unchanged,
    unanchoredRows: s?.unanchoredRows ?? 0,
    allUnchanged: ds?.applicable === true && upgraded === 0 && unchanged > 0,
    blockedGroups: s?.blockedGroups ?? countBlockedGroups(ds),
    recordStale: isRecordStale(ds),
    noRecordSnapshot: isNoRecordSnapshot(ds),
    nonParticipatingComponents: s?.nonParticipatingComponents ?? (ds?.nonParticipating?.length ?? 0),
    hasTables: (ds?.tables?.length ?? 0) > 0,
  };
};

/**
 * 「对不上的行」原因常量（**行级**）→ 财务读得懂的中文（api.md §1 全集，2026-09-07 补齐）。
 * 🚫 与下面的 NON_PARTICIPATING_REASON_TEXT（**组件级**）是两套独立枚举，不许混成一个值域 ——
 *    前者答「这一行为什么锚不上」，后者答「这个组件为什么不参与」。
 * 未知常量原样透出（带原码），🚫 不吞、🚫 不空白。
 */
const UNANCHORED_REASON_TEXT: Record<string, string> = {
  // origin_id 与 base_row_fingerprint 都空 —— 只活在 row_data 的行 / 用户手工新增的行
  NO_ANCHOR: '这是报价单上新增的行，基础数据里没有对应记录，确认后按新增写入',
  // 有过锚，但跨版后指纹对不上
  CROSS_VERSION_FINGERPRINT_MISS: '本单拍快照后，该行内容已被其他报价单改动，无法在当前版本中定位',
  /**
   * 🆕 2026-09-07 主线定稿。api.md :146 说行级实测有三个值，而同文件上方的「全集」表只列了两个 ——
   * 缺的就是这一条，它此前会以「未知原因（SAME_VERSION_ORIGIN_MISS）」摆给财务看。
   * 📌 措辞要点：「版本号未变」是它区别于 CROSS_VERSION_FINGERPRINT_MISS 的**唯一可见特征**
   *    （财务在同一张明细表里会同时看到两种，得能分辨）；「可能被…」是推测故留「可能」，🚫 不写成断言。
   */
  SAME_VERSION_ORIGIN_MISS:
    '快照记录的那一行已不在基础数据中（版本号未变，可能被直接删除或替换），无法定位',
};

/**
 * 未知行级常量原样透出（带原码），🚫 不吞、🚫 不空白。
 * 兜底形态与组件级 nonParticipatingReasonText 保持一致 —— 两处都写「未知原因（原码）」，
 * 否则同一个抽屉里两种兜底长得不一样，财务会以为是两类不同的问题。
 */
const unanchoredReasonText = (reason?: string | null): string => {
  if (!reason) return '未说明原因';
  return UNANCHORED_REASON_TEXT[reason] ?? `未知原因（${reason}）`;
};

/**
 * 🆕 D-33「不参与升版」原因常量（组件级，与上面的行级 reason 是两套，别混用）。
 * 📌 文案 2026-09-07 由主线定稿（原型 01 的「为什么不参与」列即此措辞）——
 *    🚫 不对财务暴露 builder_config / data_driver_path 这类内部词，只回答「会不会写回 / 为什么 / 找谁」。
 * 🚫 后端可能再加常量 ⇒ 未知值走 nonParticipatingReasonText 的兜底分支，绝不渲染空白。
 */
const NON_PARTICIPATING_REASON_TEXT: Record<string, string> = {
  NO_BUILDER_CONFIG: '该页签的取数视图是手工编写的，系统无法自动判断它对应哪张基础数据表 —— 需由配置人员改用取数配置器重建',
  NO_DRIVER_PATH: '该页签没有配置数据来源，本次不写回',
  UNSUPPORTED_DRIVER_PATH: '该页签的数据来源形态本期不支持写回',
  BUILDER_CONFIG_CORRUPT: '该页签的取数配置读取失败，本次不写回 —— 请联系配置人员检查',
  NOT_QUOTE_DIALECT: '该页签取的不是报价基础数据，不参与本次写回',
  NO_TAB_TYPE: '该页签未设置页签类型，无法判断对应哪张基础数据表',
  TAB_VIEW_NOT_FOUND: '找不到该页签对应的取数视图配置',
  NOT_VERSIONED_SHEET: '该页签对应的是基础资料（无版本），按规则不做升版写回',
};

/** 未知常量原样透出（带上原码），🚫 不许空白、🚫 不许吞。 */
const nonParticipatingReasonText = (reason?: string | null): string => {
  if (!reason) return '未说明原因';
  return NON_PARTICIPATING_REASON_TEXT[reason] ?? `未知原因（${reason}）`;
};

/**
 * 🆕 BLOCKED「本次跳过回填」的原因常量（**组级**）。
 * 🚫 这是本文件里的**第三套**独立值域，与上面两套（行级 unanchored / 组件级 nonParticipating）
 *    互不相干，🚫 不许合并成一张映射表 —— 三者分别回答「这一行为什么锚不上」「这个组件为什么不参与」
 *    「这一组为什么整组跳过」，后果也不同（前者会写、中者不写、后者不写但需人工处理）。
 * 📌 目前后端只有 GRAIN_KEY_COLLISION 一个值，仍按枚举处理：未知码走兜底，🚫 不吞不空白。
 */
const BLOCKED_REASON_TEXT: Record<string, string> = {
  GRAIN_KEY_COLLISION:
    '这一组里有多行的粒度键取值相同，系统无法确定报价单的数据该写到哪一行，因此整组跳过',
};

/** 未知常量原样透出（带上原码），兜底形态与另外两套保持一致。 */
const blockedReasonText = (reason?: string | null): string => {
  if (!reason) return '未说明原因';
  return BLOCKED_REASON_TEXT[reason] ?? `未知原因（${reason}）`;
};

/**
 * 🆕 D-35「`_record` 快照可能过期」的原因常量（**单据级**，api.md §1 硬约束 4）。
 * 🚫 这是本文件里的**第四套**独立值域。四套分别回答：
 *    行级 = 这一行为什么锚不上 · 组件级 = 这个组件为什么不参与 ·
 *    组级 = 这一组为什么整组跳过 · **单据级 = 这份预览为什么可能不可信**。
 * 🚫 后端 `recordStale.detail` 是异常原文（`IllegalStateException: …`），
 *    **api.md 明写不得当用户文案渲染** —— 财务看的只能是这里的映射。
 */
const RECORD_STALE_REASON_TEXT: Record<string, string> = {
  WRITE_FAILED: '这张报价单最后一次保存时，用于比对的数据快照没有写成功',
};

/** 未知常量原样透出（带上原码），兜底形态与另外三套保持一致。 */
const recordStaleReasonText = (reason?: string | null): string => {
  if (!reason) return '未说明原因';
  return RECORD_STALE_REASON_TEXT[reason] ?? `未知原因（${reason}）`;
};

/**
 * 🚨 D-35 判据：`summary.recordStale`（布尔便捷位）与顶层 `recordStale.stale` 取**并**。
 * 🚫 不许只信其中一个 —— 两个出口来自同一件事，任一被裁掉/漏发都会让告警整块消失，
 *    而这条告警**漏报的代价是财务照着过期数据确认回填**（D-35 后果链）。
 */
const isRecordStale = (ds?: DsBackfillPreview | null): boolean =>
  ds?.recordStale?.stale === true || ds?.summary?.recordStale === true;

/**
 * 🆕 D-35 告警条。**红档（error），且置于面板最顶端** —— 它限定的是**下面所有内容的可信度**，
 * 放在汇总条之后就变成了「众多提示中的一条」。
 * 分级理由：BLOCKED 是「这组我没敢写」（warning），本条是「**我给你看的东西可能是错的**」（error）。
 * 🚫 不许折叠、🚫 不许静默、🚫 不许渲染 detail 原文。
 */
const RecordStaleAlert: React.FC<{ stale?: DsBackfillRecordStale | null }> = ({ stale }) => (
  <Alert
    type="error"
    showIcon
    style={{ marginBottom: 16 }}
    data-testid="ds-backfill-record-stale-alert"
    title={<b>此刻预览的内容可能不是报价单的最新数据。</b>}
    description={
      <>
        {recordStaleReasonText(stale?.reason)}，
        因此<b>下面列出的比对结果可能是基于过期的报价单数据算出来的</b>。
        <br />
        <b>请先不要确认</b> —— 让销售重新打开这张报价单并保存一次，再回到这里重新预览。
        {stale?.detectedAt && (
          <div style={{ fontSize: 12, color: MUTED, marginTop: 4 }}>检测时间：{stale.detectedAt}</div>
        )}
      </>
    }
  />
);

/**
 * 🆕「从来没拍过比对快照」的原因常量（**单据级**）。
 * 🚫 本文件里的**第五套**独立值域。五套分别回答：
 *    行级 = 这一行为什么锚不上 · 组件级 = 这个组件为什么不参与 · 组级 = 这一组为什么整组跳过 ·
 *    单据级(recordStale) = 这份预览为什么可能不可信 · **单据级(本条) = 为什么整单一个字节都不会写**。
 * 🚫 与 recordStale **尤其**不许合并 —— 一个是「写过但失败」，一个是「从来没写过」，
 *    财务要做的动作虽然相同（让销售保存一次），但她得知道现在到底是哪一种。
 */
const NO_RECORD_SNAPSHOT_REASON_TEXT: Record<string, string> = {
  NEVER_WRITTEN: '这张报价单从建单到现在，没有在报价页面保存过 —— 常见于导入建出来的单',
};

/** 未知常量原样透出（带上原码），兜底形态与另外四套保持一致。 */
const noRecordSnapshotReasonText = (reason?: string | null): string => {
  if (!reason) return '未说明原因';
  return NO_RECORD_SNAPSHOT_REASON_TEXT[reason] ?? `未知原因（${reason}）`;
};

/**
 * 🚨 判据：顶层 `noRecordSnapshot` 对象与 `summary.noRecordSnapshot` 布尔位取**并**
 * （与 recordStale 同型，上一轮已证伪过「只信一个」会让告警在另一形态下整块消失）。
 *
 * ⚠️ **唯一的例外**：顶层对象在、且 `participatingComponents` 显式为 `0` ⇒ **不报**。
 *    那是「本单本来就没有要写回基础数据的组件」，属正常态，不是本缺口；
 *    报了等于把一句吓人的话说给一张完全正常的单。
 * 🔑 但「字段缺失」🚫 **不**当 0 处理 —— summary-only 形态下根本没有这个计数，
 *    把 undefined 当 0 会让告警在那条路径上静默消失，正是取并要防的事。
 */
const isNoRecordSnapshot = (ds?: DsBackfillPreview | null): boolean => {
  const obj = ds?.noRecordSnapshot;
  const signalled = !!obj || ds?.summary?.noRecordSnapshot === true;
  if (!signalled) return false;
  if (obj && typeof obj.participatingComponents === 'number' && obj.participatingComponents === 0) {
    return false;
  }
  return true;
};

/**
 * 🆕「整单没有比对快照」告警条。**红档（error）**，与 recordStale 同级。
 *
 * 📌 定档理由（2026-09-07 由前端判、主线裁）：档位表达的是「该不该停下来处理」，不是「结论有多不确定」。
 *    本条的影响面是**整单零写入**，比 BLOCKED（某一组跳过）大一个量级 ——
 *    把它放到 BLOCKED 的橙档之下，等于让「整单都不写」看起来比「有一组不写」更轻，是倒的。
 *    ⚠️ 与 recordStale 的混淆风险靠**首句差异**消除，不靠降档：
 *       recordStale →「此刻预览的内容**可能**不是最新数据」（不确定 / 数据旧）
 *       本条       →「这张报价单**没有**可供比对的数据快照」（确定 / 什么都不写）
 * 🚫 不许折叠、🚫 不许静默、🚫 不许出现内部术语（快照表名 / 保存方法名）。
 */
const NoRecordSnapshotAlert: React.FC<{ info?: DsBackfillNoRecordSnapshot | null }> = ({ info }) => {
  const n = info?.participatingComponents;
  return (
    <Alert
      type="error"
      showIcon
      style={{ marginBottom: 16 }}
      data-testid="ds-backfill-no-record-snapshot-alert"
      title={<b>这张报价单没有可供比对的数据快照，确认后不会写入任何基础数据。</b>}
      description={
        <>
          {noRecordSnapshotReasonText(info?.reason)}。
          {typeof n === 'number' && n > 0 && (
            <>
              本单有 <b>{n}</b> 个页签本该把数据写回基础数据，现在<b>一个都写不了</b>。
            </>
          )}
          <br />
          <b>请先不要确认</b> —— 让销售打开这张报价单<b>保存一次</b>，再回到这里重新预览。
          <br />
          <span style={{ color: MUTED, fontSize: 13 }}>
            确认本身不会损坏任何数据（它一个字节都不写），但也<b>不会</b>把这张单的数据写回基础数据。
          </span>
        </>
      }
    />
  );
};

const num = (v: number | null | undefined): number => (typeof v === 'number' ? v : 0);

/**
 * 🚨 「回填后行数 < 整组行数」= 按 D-34 不该出现的状态（回填只增不删，无墓碑通道）。
 * 判据抽成函数：判定标签 / 行底色 / 汇总条 / 顶部告警条四处必须同源，
 * 🚫 不许各处各写一遍 `result < base` —— 那正是「同一件事多套实现」的起点。
 */
const isShrinking = (g: DsBackfillGroup): boolean =>
  // 🚨 BLOCKED 组豁免：整组跳过、一行不写 ⇒ 后端可能把 resultRowCount 留空/给 0，
  //    此时 num() 会得到 0，`0 < baseRowCount` 恒真 ⇒ 会误报「该组行数会减少 —— 按设计不应发生」，
  //    并连带点亮顶部红条与汇总条。那是**假警报**，且长得和真缺陷一模一样。
  //    ⇒ 判据前置一道 result 闸，🚫 不许只靠「后端一定会返 baseRowCount」这个假设。
  g.result !== 'BLOCKED' && num(g.resultRowCount) < num(g.baseRowCount);

/** 本次跳过回填（整组不写，但核价通过照常进行）。 */
const isBlocked = (g: DsBackfillGroup): boolean => g.result === 'BLOCKED';

/** 汇总条一项（原型 .summary .item）。 */
const SummaryItem: React.FC<{ label: string; value: number; warn?: boolean }> = ({ label, value, warn }) => (
  <div>
    <div style={{ fontSize: 12, color: MUTED }}>{label}</div>
    <div style={{ fontSize: 22, fontWeight: 600, lineHeight: 1.2, marginTop: 2, color: warn ? '#d4380d' : undefined }}>
      {value}
    </div>
  </div>
);

/**
 * 版本迁移单元格 —— 🔑 D-25 的核心可见物（AC-10①）。
 * crossVersion=true 时必须是三段「快照 v1 → 库中 v2 → v3」，中间段金色，
 * 让财务一眼看见「库里已经被别人改过」。
 */
const VersionCell: React.FC<{ g: DsBackfillGroup }> = ({ g }) => {
  const arrow = <span style={{ color: 'rgba(0,0,0,.25)' }}>→</span>;
  const wrap: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, whiteSpace: 'nowrap' };
  /**
   * 🆕 BLOCKED：整组跳过 ⇒ **不升版**。
   * 🚫 这里绝不能落到下面的 crossVersion / 默认分支去显示「→ v3」——
   *    那会向财务承诺一个本次根本不会发生的版本迁移（同 AP-60 判据四的「说了做不到」形态）。
   *    跨版信息仍保留（她需要知道库里已经变过），但终点明写「不升版」。
   */
  if (isBlocked(g)) {
    return (
      <span style={wrap}>
        {g.crossVersion ? (
          <>
            <Tag color="gold" style={{ marginInlineEnd: 0 }}>快照 v{g.baseVersionNo ?? '?'}</Tag>
            {arrow}
            <Tag color="blue" style={{ marginInlineEnd: 0 }}>库中 v{g.currentVersionNo ?? '?'}</Tag>
          </>
        ) : (
          <span>v{g.currentVersionNo ?? '?'}</span>
        )}
        {arrow}
        <span style={{ color: '#d46b08', fontWeight: 600 }}>不升版</span>
      </span>
    );
  }
  if (g.crossVersion) {
    return (
      <span style={wrap}>
        <Tag color="gold" style={{ marginInlineEnd: 0 }}>快照 v{g.baseVersionNo ?? '?'}</Tag>
        {arrow}
        <Tag color="blue" style={{ marginInlineEnd: 0 }}>库中 v{g.currentVersionNo ?? '?'}</Tag>
        {arrow}
        <span style={{ fontWeight: 600, color: '#0958d9' }}>v{g.targetVersionNo ?? '?'}</span>
      </span>
    );
  }
  if (g.result === 'UNCHANGED') {
    return (
      <span style={wrap}>
        v{g.currentVersionNo ?? '?'} {arrow} <span style={{ color: MUTED }}>v{g.currentVersionNo ?? '?'}</span>
      </span>
    );
  }
  // CREATED = 该料号在基础数据里还没有 ⇒ 起点无版本，原型 01 画的是「— → v1」
  if (g.result === 'CREATED') {
    return (
      <span style={wrap}>
        <span style={{ color: MUTED }}>—</span> {arrow}{' '}
        <span style={{ fontWeight: 600, color: '#0958d9' }}>v{g.targetVersionNo ?? 1}</span>
      </span>
    );
  }
  return (
    <span style={wrap}>
      v{g.currentVersionNo ?? '?'} {arrow} <span style={{ fontWeight: 600, color: '#0958d9' }}>v{g.targetVersionNo ?? '?'}</span>
    </span>
  );
};

/**
 * 判定标签。原型 01/02：新建（绿）/ 升版（蓝）/ 跨版升版（金）/ 无变更（灰）/ 异常（红）。
 * 🆕 本次跳过（橙）—— 视觉分级刻意落在**灰与红之间**：
 *    比「无变更」显眼（它需要财务事后处理，不是「什么事都没有」），
 *    比「异常」克制（它不阻断确认，也不是缺陷信号）。
 */
const resultTag = (g: DsBackfillGroup): React.ReactNode => {
  // 变小优先于一切正常判定 —— 这一行此刻的要紧事不是「升了几版」，而是「它不该变小」
  if (isShrinking(g)) return <Tag color="red">异常</Tag>;
  if (isBlocked(g)) return <Tag color="orange">本次跳过</Tag>;
  if (g.result === 'UNCHANGED') return <Tag>无变更</Tag>;
  if (g.result === 'CREATED') return <Tag color="green">新建</Tag>;
  return g.crossVersion ? <Tag color="gold">跨版升版</Tag> : <Tag color="blue">升版</Tag>;
};

const columnTags = (cols?: string[]): React.ReactNode => {
  if (!cols || cols.length === 0) return <Text type="secondary">—</Text>;
  return (
    <span>
      {cols.map((c) => (
        <Tag key={c} style={{ marginInlineEnd: 4, marginBottom: 2 }}>{c}</Tag>
      ))}
    </span>
  );
};

/**
 * 表头（AC-5② 2026-09-07 修订版：列名与顺序与「该屏对应的原型」逐字一致，🚫 不写死列数）。
 * 四条列级不变量：
 *   a. 「本次不动」列恒在（AP-60 行维度守卫）；
 *   b. 🚨「本次覆盖的列」与「原样保留的列」成对出现 —— 出现前者而缺后者即不合格（AP-60 列维度守卫）；
 *   c. `unanchoredRows > 0` 的屏必须有「对不上」列 ⇒ 本函数按 showUnanchored 插入该列，
 *      使「无对不上」屏 = 原型 01 的 8 列、「有对不上」屏 = 原型 02 的 9 列，两屏各自逐字一致；
 *   d. 全 UNCHANGED 屏「可省」b 的两列 —— 本实现选择不省（"可省" 非 "必省"），
 *      保留后仍能看到「整组不写」这句结论。
 */
const buildGroupColumns = (showUnanchored: boolean, showColumnScope: boolean): ColumnsType<DsBackfillGroup> => [
  {
    title: '销售料号',
    dataIndex: 'axisValue',
    key: 'axisValue',
    render: (v: string) => <span style={{ fontFamily: MONO, fontSize: 13 }}>{v}</span>,
  },
  { title: '版本迁移', key: 'version', render: (_, g) => <VersionCell g={g} /> },
  { title: '判定', key: 'result', render: (_, g) => resultTag(g) },
  { title: '整组行数', key: 'baseRowCount', align: 'right', render: (_, g) => num(g.baseRowCount) },
  /**
   * 🚨 AC-5② 不变量 e：「回填后行数」必须有，且与「整组行数」不同时标色。
   * 由来（2026-09-07 实测倒逼）：出现过 baseRowCount 2 → resultRowCount 4，而同一行显示
   * 「本次覆盖 0 / 本次不动 2」—— 财务据此会读成「什么都没变」，实际这组确认后从 2 行变 4 行。
   * 与 AP-60 / repair-0727 是**镜像**形态：那次静默删行，这次静默翻倍。
   * ⇒ 预览必须描述**结果状态**，只描述增量必然漏掉这一整类后果。
   */
  {
    title: '回填后行数',
    key: 'resultRowCount',
    align: 'right',
    onCell: () => ({ 'data-testid': 'ds-backfill-result-rows' } as React.TdHTMLAttributes<HTMLElement>),
    render: (_, g) => {
      // ⚠️ resultRowCount 由**后端**算并返回，前端只渲染。
      // 🚫 不许在这里自己推（base + unanchored 之类）—— 那会变成「同一件事两套实现」，
      //    后端口径一改前端就静默说谎（本任务刚用 D-31 消灭过一次这种形态）。
      const base = num(g.baseRowCount);
      const result = num(g.resultRowCount);
      /**
       * 🆕 BLOCKED：整组跳过 ⇒ 回填后这一组**保持原样**。
       * 🚫 这里刻意**不**把 baseRowCount 顶上来当结果值 —— 那就是「前端自己推 resultRowCount」，
       *    正是上面那条禁令要防的事。后端给了数就渲染那个数，没给就渲染「—」，
       *    再用一行小注把「本次不写」这个结论说死；两种情况都不套色（不是绿、不是红）。
       */
      if (isBlocked(g)) {
        return (
          <span style={{ color: MUTED }}>
            {typeof g.resultRowCount === 'number' ? result : '—'}
            <div style={{ fontSize: 12, whiteSpace: 'nowrap', color: '#d46b08' }}>
              本次跳过，该组保持原样
            </div>
          </span>
        );
      }
      if (result > base) {
        // base === 0 → 整组新建（绿）；base > 0 → 组会变大（红，多为「对不上的行按新增写入」）
        const color = base === 0 ? '#389e0d' : '#d4380d';
        return <span style={{ color, fontWeight: 600 }}>{result}</span>;
      }
      if (result < base) {
        // 🚨 D-34 明定「回填永不删行」（无墓碑通道）⇒ 组根本不该变小。
        //    一旦显示变小，那本身就是缺陷 —— 也正是 AP-60 的**原始**形态（repair-0727：预览 0 变更、执行删 3 行）。
        //    ⇒ 让不该出现的状态**最大声**，而不是安静地显示成两个普通数字。
        return (
          <span style={{ color: '#cf1322', fontWeight: 600 }}>
            {result}
            <div style={{ fontWeight: 400, fontSize: 12, whiteSpace: 'nowrap' }}>
              该组行数会减少 —— 按设计不应发生
            </div>
          </span>
        );
      }
      return <span>{result}</span>;
    },
  },
  {
    title: '本次覆盖', key: 'patchedRows', align: 'right',
    onCell: () => ({ 'data-testid': 'ds-backfill-patched-rows' } as React.TdHTMLAttributes<HTMLElement>),
    render: (_, g) => num(g.patchedRows),
  },
  // 🚨 AP-60 守卫列：不许省、不许折叠。testid 供验收用例定位「值也出得来」，不影响展示。
  {
    title: '本次不动', key: 'untouchedRows', align: 'right',
    onCell: () => ({ 'data-testid': 'ds-backfill-untouched-rows' } as React.TdHTMLAttributes<HTMLElement>),
    render: (_, g) => num(g.untouchedRows),
  },
  ...(showUnanchored
    ? ([{
        title: '对不上',
        key: 'unanchored',
        align: 'right',
        render: (_, g) => {
          const n = g.unanchoredRows?.length ?? 0;
          return n > 0 ? <span style={{ color: '#cf1322', fontWeight: 600 }}>{n}</span> : <span>0</span>;
        },
      }] as ColumnsType<DsBackfillGroup>)
    : []),
  // ── 不变量 b：这两列必须**成对**出现或成对省略，🚫 不许只留前者 ──
  ...(showColumnScope
    ? ([
        {
          title: '本次覆盖的列',
          key: 'patchedCols',
          render: (_, g) =>
            // BLOCKED 与 UNCHANGED 一样「一列都不覆盖」，但两者的**原因与后续动作**不同，
            // 差别由右边那一列与判定标签承担，这里统一显示「—」。
            g.result === 'UNCHANGED' || isBlocked(g)
              ? <Text type="secondary">—</Text>
              : columnTags(g.columnScope?.patched),
        },
        {
          title: '原样保留的列',
          key: 'preservedCols',
          render: (_, g) => {
            if (isBlocked(g)) {
              return (
                <Text type="secondary">整组跳过，本次一行不写 —— 需事后人工处理</Text>
              );
            }
            if (g.result === 'UNCHANGED') return <Text type="secondary">整组不写，连更新时间都不会动</Text>;
            // CREATED：基底为空，没有「原样保留」可言（原型 01 的原文）
            if (g.result === 'CREATED') return <Text type="secondary">该料号在基础数据里还没有，本次整组新建</Text>;
            return <Text type="secondary">{(g.columnScope?.preserved ?? []).join('、') || '—'}</Text>;
          },
        },
      ] as ColumnsType<DsBackfillGroup>)
    : []),
];

const SheetHead: React.FC<{ name: string; tableName?: string; danger?: boolean; note?: string }> = ({
  name, tableName, danger, note,
}) => (
  <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, margin: '4px 0 10px' }}>
    <span style={{ fontSize: 15, fontWeight: 600, color: danger ? '#cf1322' : undefined }}>{name}</span>
    {tableName && <span style={{ fontFamily: MONO, fontSize: 12, color: MUTED }}>{tableName}</span>}
    {note && <span style={{ fontSize: 12, color: MUTED }}>{note}</span>}
  </div>
);

/** 表格容器：横向超宽时在表格自己的容器内滚动，页面主体不出现横向滚动条（F-2）。 */
const tableWrapStyle: React.CSSProperties = {
  border: '1px solid #f0f0f0',
  borderRadius: 8,
  overflow: 'hidden',
  marginBottom: 20,
};

/** 同一张表里完全可能出现两条同 axisValue 的组（不同客户），故行 key 注入序号保证唯一。 */
interface KeyedGroup extends DsBackfillGroup {
  __key: string;
}

const SheetSection: React.FC<{ t: DsBackfillTable; showUnanchored: boolean; showColumnScope: boolean }> = ({
  t, showUnanchored, showColumnScope,
}) => (
  <>
    <SheetHead name={t.sheetName || t.sheetKey} tableName={t.tableName} />
    <div style={tableWrapStyle}>
      <Table<KeyedGroup>
        size="small"
        rowKey="__key"
        columns={buildGroupColumns(showUnanchored, showColumnScope) as ColumnsType<KeyedGroup>}
        dataSource={(t.groups ?? []).map((g, i) => ({ ...g, __key: `${t.tableName}::${g.axisValue}::${i}` }))}
        pagination={false}
        scroll={{ x: 'max-content' }}
        onRow={(g) => ({
          'data-testid': 'ds-backfill-group-row',
          // 判定枚举原样挂在 data 属性上：展示层给财务看中文标签（无变更 / 升版 / 跨版升版），
          // 验收用例按 [data-result="UNCHANGED"] 定位，两边不互相迁就。
          'data-result': g.result,
          style: {
            // 三档底色，优先级从高到低：
            //   红 #fff2f0 = 不该出现的状态（组会变小）
            //   橙 #fff7e6 = 本次跳过（需事后人工处理，比黄档更要紧）
            //   黄 #fffbe6 = 跨版 / 有对不上的行（正常但需留意）
            background: isShrinking(g)
              ? '#fff2f0'
              : isBlocked(g)
                ? '#fff7e6'
                : (g.unanchoredRows?.length ?? 0) > 0 || g.crossVersion
                  ? '#fffbe6'
                  : undefined,
          },
        } as React.HTMLAttributes<HTMLElement>)}
      />
    </div>
  </>
);

interface FlatUnanchored extends DsBackfillUnanchoredRow {
  __key: string;
  __sheetName: string;
  __axisValue: string;
}

/** 「对不上的行」独立明细表（AC-20③ / F-4）：🚫 不许折叠、🚫 不许只在 tooltip 里提。 */
const UnanchoredSection: React.FC<{ rows: FlatUnanchored[] }> = ({ rows }) => {
  // displayValues 是后端下发的动态 map（契约里键不固定），列按首次出现顺序展开 ——
  // 原型 02 的「项次 / 投入料号 / 组成数量」即由此自然得到。
  const dynamicKeys: string[] = [];
  rows.forEach((r) => {
    Object.keys(r.displayValues ?? {}).forEach((k) => {
      if (!dynamicKeys.includes(k)) dynamicKeys.push(k);
    });
  });

  const columns: ColumnsType<FlatUnanchored> = [
    { title: '页签', dataIndex: '__sheetName', key: '__sheetName' },
    {
      title: '销售料号',
      dataIndex: '__axisValue',
      key: '__axisValue',
      render: (v: string) => <span style={{ fontFamily: MONO, fontSize: 13 }}>{v}</span>,
    },
    ...dynamicKeys.map((k) => ({
      title: k,
      key: `dv-${k}`,
      render: (_: unknown, r: FlatUnanchored) => {
        const v = r.displayValues?.[k];
        return v === null || v === undefined || v === '' ? <Text type="secondary">—</Text> : <span>{String(v)}</span>;
      },
    })),
    {
      title: '原因',
      key: 'reason',
      render: (_: unknown, r: FlatUnanchored) => unanchoredReasonText(r.reason),
    },
  ];

  return (
    <div data-testid="ds-backfill-unanchored-panel">
      <SheetHead name={`对不上的行（${rows.length}）`} danger note="确认后按新增写入，库里原行保留" />
      <div style={tableWrapStyle}>
        <Table<FlatUnanchored>
          size="small"
          rowKey="__key"
          columns={columns}
          dataSource={rows}
          pagination={false}
          scroll={{ x: 'max-content' }}
          onRow={() => ({ style: { background: '#fff2f0' } })}
        />
      </div>
    </div>
  );
};

interface FlatColliding extends DsBackfillCollidingRow {
  __key: string;
  __sheetName: string;
  __axisValue: string;
  __reason?: string | null;
}

/**
 * 🆕「本次跳过的组」冲突明细（BLOCKED / GRAIN_KEY_COLLISION）。
 *
 * 🚦 **为什么另起一区、不并进上面的「对不上的行」**（本次的设计决定，理由记在这里以免下次被"顺手合并"）：
 *   1. **层级不同**：`unanchoredRows` 是**行**级（这一行认不出）；`collidingRows` 是**组**级
 *      （整组因粒度键歧义而跳过）。合并后「N 行」这个计数会同时指两种东西。
 *   2. **后果相反**：对不上的行**确认后会按新增写入**（组会变大）；BLOCKED 组**一个字节都不写**。
 *      同一张表里放两种相反后果的行，是 AP-52「语义错配」的标准起手式。
 *   3. **列不同**：前者的动态列是 `displayValues`（行的业务身份，如 项次/投入料号）；
 *      后者是 `grainKey`（粒度键取值）+ 两侧行数。硬塞一张表要么列错位、要么两个概念被读成一个。
 *   4. **视觉分级不同**：前者红（error），后者橙（warning）—— 合并就必须二选一，等于抹掉分级。
 */
const BlockedSection: React.FC<{ rows: FlatColliding[]; groupCount: number }> = ({ rows, groupCount }) => {
  // grainKey 是后端下发的动态 map（不同页签的粒度列不同：报价侧 MATERIAL_BOM 只有
  // input_material_no，ELEMENT_BOM 是 {material_part_no, element_code}）⇒ 列按首次出现顺序取并集。
  const grainKeys: string[] = [];
  rows.forEach((r) => {
    Object.keys(r.grainKey ?? {}).forEach((k) => {
      if (!grainKeys.includes(k)) grainKeys.push(k);
    });
  });

  const columns: ColumnsType<FlatColliding> = [
    { title: '页签', dataIndex: '__sheetName', key: '__sheetName' },
    {
      title: '销售料号',
      dataIndex: '__axisValue',
      key: '__axisValue',
      render: (v: string) => <span style={{ fontFamily: MONO, fontSize: 13 }}>{v}</span>,
    },
    ...grainKeys.map((k) => ({
      title: k,
      key: `gk-${k}`,
      render: (_: unknown, r: FlatColliding) => {
        const v = r.grainKey?.[k];
        return v === null || v === undefined || v === ''
          ? <Text type="secondary">—</Text>
          : <span style={{ fontFamily: MONO, fontSize: 13 }}>{String(v)}</span>;
      },
    })),
    {
      title: '基础数据里的行数',
      key: 'baseRowCount',
      align: 'right',
      onCell: () => ({ 'data-testid': 'ds-backfill-colliding-base' } as React.TdHTMLAttributes<HTMLElement>),
      render: (_: unknown, r: FlatColliding) => num(r.baseRowCount),
    },
    {
      title: '报价单里的行数',
      key: 'recordRowCount',
      align: 'right',
      onCell: () => ({ 'data-testid': 'ds-backfill-colliding-record' } as React.TdHTMLAttributes<HTMLElement>),
      render: (_: unknown, r: FlatColliding) => num(r.recordRowCount),
    },
    {
      title: '原因',
      key: 'reason',
      render: (_: unknown, r: FlatColliding) => blockedReasonText(r.__reason),
    },
  ];

  return (
    <div data-testid="ds-backfill-blocked-panel">
      <SheetHead
        name={`本次跳过的组（${groupCount}）`}
        note="这些组本次一个字节都不写，核价通过照常进行，需事后人工处理"
      />
      <div style={tableWrapStyle}>
        <Table<FlatColliding>
          size="small"
          rowKey="__key"
          columns={columns}
          dataSource={rows}
          pagination={false}
          scroll={{ x: 'max-content' }}
          onRow={() => ({ style: { background: '#fff7e6' } })}
        />
      </div>
    </div>
  );
};

interface ExtendRow {
  key: string;
  sheetName: string;
  fields: string[];
}

/** 「仅记录、不写回基础数据的字段」（AC-3① / F-3）。 */
const ExtendColumnOnlySection: React.FC<{ rows: ExtendRow[] }> = ({ rows }) => (
  <>
    <SheetHead name="仅记录、不写回基础数据的字段" />
    <div style={tableWrapStyle}>
      <Table<ExtendRow>
        size="small"
        rowKey="key"
        pagination={false}
        scroll={{ x: 'max-content' }}
        dataSource={rows}
        columns={[
          { title: '页签', dataIndex: 'sheetName', key: 'sheetName' },
          { title: '字段', key: 'fields', render: (_, r) => columnTags(r.fields) },
          {
            title: '为什么不写回',
            key: 'why',
            render: () => <Text type="secondary">公式列 / 自定义列，基础数据里没有对应字段</Text>,
          },
        ]}
      />
    </div>
  </>
);

/**
 * 🚨 D-33 / api.md §1 硬约束 4：不参与基础数据升版的组件必须显式告知，🚫 不许静默。
 * 实测现网 156/228 个组件视图是手写 SQL（无 builder_config）⇒ 这块常态非空，
 * 且存在「100% 不参与」的单（后端实测两张真实单都是 5/5）——
 * 那种单 `tables` 是空数组，本告警是抽屉里唯一有信息量的东西，🚫 更不能被空态顶掉。
 * ⚠️ 原型四屏都没画这块（原型缺口，已在回报里指明），此处按 D-33 原文「本单有 N 个组件不参与基础数据升版」实现。
 */
interface NonParticipatingRow extends DsBackfillNonParticipating {
  __key: string;
}

const NonParticipatingSection: React.FC<{ items: DsBackfillNonParticipating[]; count: number }> = ({ items, count }) => (
  <div data-testid="ds-backfill-non-participating">
    <Alert
      type="warning"
      showIcon
      style={{ marginBottom: 16 }}
      title={<b>本单有 {count} 个组件不参与基础数据升版。</b>}
      description={
        <>
          它们的数据只留在报价单里，<b>不会写回基础数据</b>。逐条原因见下表。
        </>
      }
    />
    <div style={tableWrapStyle}>
      <Table<NonParticipatingRow>
        size="small"
        rowKey="__key"
        pagination={false}
        scroll={{ x: 'max-content' }}
        dataSource={items.map((it, i) => ({ ...it, __key: `${it.componentId ?? 'np'}-${i}` }))}
        columns={[
          {
            title: '组件',
            key: 'name',
            render: (_, r) => r.componentName || r.componentId || '（未命名组件）',
          },
          {
            title: '为什么不参与',
            key: 'reason',
            render: (_, r) => <Text type="secondary">{nonParticipatingReasonText(r.reason)}</Text>,
          },
        ]}
      />
    </div>
  </div>
);

const CostingDsBackfillPanel: React.FC<{ ds: DsBackfillPreview }> = ({ ds }) => {
  const flags = deriveDsFlags(ds);

  // ── 老单：applicable=false → 空态，🚫 不渲染比对表格区（AC-15 / F-5）──
  if (!flags.applicable) {
    return (
      <div style={{ padding: '40px 0' }}>
        {/*
          🚨 D-35 防御位：api.md 说「recordStale 非空时 applicable 恒 true」，
          但那是**后端的承诺**，不是前端的保证。万一后端违约（或将来改了口径），
          把告警挂在 applicable 上就等于让它恰好在最该出现时整块消失 ——
          与 D-33「100% 不参与时告警被空态顶掉」是同一个坑，本任务已经踩过一次。
          ⇒ 这里先渲染告警，再渲染空态。代价是一段几乎不会执行的代码，收益是不会静默。
        */}
        {/*
          🆕 同一条防御位：主线已把 api.md 的 applicable 裁为「三者全空才 false」，
          但那是**后端的承诺**，不是前端的保证。本条恰恰只在「一行快照都没有」时出现 ——
          若它被 applicable 或空态顶掉，就是在唯一该说话的场景里闭嘴。
          （本任务已在 D-33 上踩过一次、在 recordStale 上防过一次，这是第三次。）
        */}
        {flags.noRecordSnapshot && <NoRecordSnapshotAlert info={ds.noRecordSnapshot} />}
        {flags.recordStale && <RecordStaleAlert stale={ds.recordStale} />}
        <Empty
          image={<div style={{ fontSize: 40, lineHeight: 1 }}>📄</div>}
          styles={{ image: { height: 44 } }}
          description={
            <div style={{ color: MUTED }}>
              本单不涉及新基础数据表的升版。
              <div style={{ fontSize: 13, marginTop: 6 }}>
                该报价单建于数据源切换之前，按原有流程核价通过即可。
              </div>
            </div>
          }
        />
      </div>
    );
  }

  const tables = ds.tables ?? [];
  const summary = ds.summary;
  const nonParticipating = ds.nonParticipating ?? [];
  const nonParticipatingCount = summary?.nonParticipatingComponents ?? nonParticipating.length;

  // 「判定恒 UNCHANGED 的屏」按**实际渲染出来的组**判，不看 summary ——
  // summary 与 tables 万一不一致时，列集必须跟着眼睛看到的那份走（AC-5② 不变量 d）。
  const allGroups = tables.flatMap((t) => t.groups ?? []);
  const allUnchangedScreen = allGroups.length > 0 && allGroups.every((g) => g.result === 'UNCHANGED');
  // 🚨 D-34：回填只增不删 ⇒ 一个组都不该变小。>0 就是缺陷信号，三处联动（汇总条 / 顶部红条 / 行内）
  const shrinkingGroups = allGroups.filter(isShrinking).length;
  /**
   * 🆕 BLOCKED 组：**按实际渲染出来的组数**，不看 summary。
   * 理由同 allUnchangedScreen：summary 与 tables 万一不一致时，
   * 汇总条上的数字必须跟眼睛在下面表格里能数出来的那份走，否则财务会去找那个数不出来的组。
   * （summary 的 blockedGroups 仍在 deriveDsFlags 里用于抽屉页脚，两处口径差异见 flags 注释。）
   */
  const blockedGroupCount = allGroups.filter(isBlocked).length;

  const unanchoredFlat: FlatUnanchored[] = [];
  const collidingFlat: FlatColliding[] = [];
  const crossVersionNotes: string[] = [];
  tables.forEach((t) => {
    (t.groups ?? []).forEach((g, gi) => {
      (g.unanchoredRows ?? []).forEach((r, ri) => {
        unanchoredFlat.push({
          ...r,
          __key: `${t.tableName}::${g.axisValue}::${gi}::${ri}`,
          __sheetName: t.sheetName || t.sheetKey,
          __axisValue: g.axisValue,
        });
      });
      (g.collidingRows ?? []).forEach((c, ci) => {
        collidingFlat.push({
          ...c,
          __key: `${t.tableName}::${g.axisValue}::${gi}::c${ci}`,
          __sheetName: t.sheetName || t.sheetKey,
          __axisValue: g.axisValue,
          // 原因挂在**组**上（blockedReason），明细表逐行显示 —— 同一组的每条冲突原因相同，
          // 但财务是按行看的，逐行给比让她回头去组表里对更省事。
          __reason: g.blockedReason,
        });
      });
      if (g.crossVersion && num(g.untouchedRows) > 0) {
        crossVersionNotes.push(
          `料号 ${g.axisValue} 的${t.sheetName || t.sheetKey} 有 ${num(g.untouchedRows)} 行本次不动` +
            ' —— 其中包含其他报价单改过而本单没有表征的列，它们会保持库中当前的值。',
        );
      }
    });
  });

  const crossVersionGroups = tables.flatMap((t) =>
    (t.groups ?? []).filter((g) => g.crossVersion).map((g) => `${g.axisValue}（${t.sheetName || t.sheetKey}）`),
  );

  // extendColumnOnly 契约只给 sheetKey，页签中文名按 tables[] 反查（🚫 不改契约，见 api.md §1）
  const sheetNameOf = (sheetKey: string): string =>
    tables.find((t) => t.sheetKey === sheetKey)?.sheetName ?? sheetKey;
  const extendRows: ExtendRow[] = (ds.extendColumnOnly ?? [])
    .filter((e) => (e.fields ?? []).length > 0)
    .map((e, i) => ({
      key: `${e.sheetKey}-${i}`,
      sheetName: e.sheetName ?? sheetNameOf(e.sheetKey),
      fields: e.fields ?? [],
    }));

  return (
    <>
      {/*
        ── 🆕 D-35：`_record` 快照可能过期 ⇒ **整份预览的可信度**存疑（api.md §1 硬约束 4）。
           置于**最顶端**是刻意的：它限定的是下面所有内容，排在汇总条之后就退化成「众多提示之一」。
           🚫 不许折叠、🚫 不许静默、🚫 不许把 detail 的异常原文给财务看。 ── */}
      {/*
        ── 🆕 排在 recordStale **之前**：两条都是红档、都要求「让销售保存一次」，但
           本条是**确定**且**总量**的结论（整单零写入），recordStale 是**可能**的（数据也许旧）。
           确定的结论排在可能的结论前面。
           ⚠️ 两者**可以同时出现**（写快照时抛异常 ⇒ 既「写失败过」又「一行都没有」），
              此时两条红条并列 —— 那是对的，🚫 不许择一显示。 ── */}
      {flags.noRecordSnapshot && <NoRecordSnapshotAlert info={ds.noRecordSnapshot} />}
      {flags.recordStale && <RecordStaleAlert stale={ds.recordStale} />}

      {/* ── 汇总条五项（AC-5③）：最后一项 >0 时标红 ── */}
      <div
        // 验收用例按此定位汇总条。**必须有**：汇总条上的标签（「将升版」「无变更」）与抽屉里
        // 别处的文案会撞 —— 页脚有「…个料号组将升版…」、判定列有 <Tag>无变更</Tag> ⇒
        // 不限定范围的 getByText 会命中多个元素、以 strict mode violation 的面目失败，
        // 而那种失败长得像「汇总条没渲染」。（2026-09-07 接通入口后实测撞到）
        data-testid="ds-backfill-summary"
        style={{
          display: 'flex',
          gap: 32,
          padding: '16px 20px',
          background: '#fafafa',
          borderRadius: 8,
          marginBottom: 16,
          flexWrap: 'wrap',
        }}
      >
        <SummaryItem label="涉及表" value={num(summary?.tables)} />
        <SummaryItem label="涉及料号组" value={num(summary?.axes)} />
        <SummaryItem label="将升版" value={num(summary?.upgradedGroups)} />
        <SummaryItem label="无变更" value={num(summary?.unchangedGroups)} />
        <SummaryItem label="无法对齐的行" value={flags.unanchoredRows} warn={flags.unanchoredRows > 0} />
        {/*
          后两项**只在 >0 时出现**（AC-5③「🚫 不锁项数」）—— 四份原型逐一实测印证该规则：
          01 有「不参与的组件=2」无「行数会减少的组」；02 反过来；03/04 两项都没有（两者都是 0）。
        */}
        {/* 🆕 BLOCKED 组计数。同样只在 >0 时出现 —— 恒显示会在绝大多数单子上留下一行
            僵尸「本次跳过的组 0」，那是噪声不是信息（AC-5③「🚫 不锁项数」的同一条理由）。 */}
        {blockedGroupCount > 0 && (
          <div data-testid="ds-backfill-blocked-summary">
            <SummaryItem label="本次跳过的组" value={blockedGroupCount} warn />
          </div>
        )}
        {nonParticipatingCount > 0 && (
          <SummaryItem label="不参与的组件" value={nonParticipatingCount} warn />
        )}
        {shrinkingGroups > 0 && <SummaryItem label="行数会减少的组" value={shrinkingGroups} warn />}
      </div>

      {/* ── 全部无变更（AC-14③）── */}
      {allUnchangedScreen && (
        <Alert
          type="success"
          showIcon
          style={{ marginBottom: 16 }}
          title={<b>本单不会改动任何基础数据。</b>}
          description={
            <>
              报价单里的数据与基础数据当前版本完全一致，确认后这 {summary?.unchangedGroups ?? allGroups.length} 个料号组
              <b>一行都不会写</b>，版本号与更新时间都保持不变。
            </>
          }
        />
      )}

      {/* ── 默认说明（AP-60：说的是「会变成什么样」，不是「哪些值变了」）。
             tables 为空（只有 nonParticipating 要说）时不出 —— 下面根本没有「下表」。 ── */}
      {tables.length > 0 && !allUnchangedScreen && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          title={
            <>
              下表列的是<b>确认后基础数据会变成什么样</b>，不是「哪些值变了」。
              「<b>本次不动</b>」那一列是本页签没有表征的行 —— 它们会<b>原样保留</b>，不会被删除、不会被置空。
              <br />
              「<b>回填后行数</b>」与「整组行数」不同时会标色：
              <span style={{ color: '#389e0d', fontWeight: 600 }}>绿色</span>=整组新建，
              <span style={{ color: '#d4380d', fontWeight: 600 }}>红色</span>=有对不上的行按新增写入、组会变大。
            </>
          }
        />
      )}

      {/* ── 跨版警示（AC-10①）── */}
      {crossVersionGroups.length > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title={<b>基础数据在本单提交后被改动过。</b>}
          description={
            <>
              以下料号组在本单拍快照时的版本已经落后于库里当前版本：{crossVersionGroups.join('、')}。
              <br />
              本次确认会以<b>库里当前版本为底</b>再叠加本单的改动，<b>不会把别人改的内容退回去</b>。
            </>
          }
        />
      )}

      {/*
        🆕 BLOCKED 告警条。**warning 不是 error**，这是刻意的：
          - 比「无变更」显眼 —— 它需要财务事后动手，不是「什么都没发生」；
          - 比报错克制 —— 它**不阻断核价通过**，主按钮不变红、不禁用（🚫 别照着「对不上的行」那条抄）。
        文案回答三件事：写不写（不写）／通过还能不能继续（能）／接下来谁做什么（事后人工处理）。
      */}
      {blockedGroupCount > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          data-testid="ds-backfill-blocked-alert"
          title={<b>有 {blockedGroupCount} 个料号组本次跳过回填。</b>}
          description={
            <>
              这些组里存在<b>粒度键取值相同的多行</b>，系统无法确定报价单的数据该写到哪一行，
              因此本次<b>一个字节都不写</b>，它们在基础数据里<b>保持原样</b>。
              <br />
              <b>核价通过照常进行</b> —— 这些组不阻断本次确认；但它们需要<b>事后人工核对处理</b>
              {collidingFlat.length > 0 ? '，逐组明细见下方「本次跳过的组」。' : '。'}
            </>
          }
        />
      )}

      {/* ── 对不上的行：红色告警条（AC-20③，🚫 不许折叠）── */}
      {flags.unanchoredRows > 0 && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          title={<b>有 {flags.unanchoredRows} 行对不上。</b>}
          description={
            <>
              这些行在本单拍快照之后被别人改过内容，系统无法确定它们现在对应库里的哪一行。
              <b>确认后这些行将作为新增行写入，库里原有的行不会被删除。</b>
              <br />
              如果这不是你要的结果，请点「取消」，让销售重新打开报价单刷新基础数据后再提交。
            </>
          }
        />
      )}

      {/*
        🚨 「行数会减少」的顶部告警（AC-5②e 第②处）。
        🚫 只做单元格内小注不合格 —— 13 张表的抽屉里滚过去就没了，那不叫「最大声」。
        ⚠️ 与「有 N 行对不上」的关键差别：那条是**可确认的正常分支**，这条**不是** ——
           所以文案里带处置指令（请先不要确认，联系技术人员核查），不是单纯描述现象。
      */}
      {shrinkingGroups > 0 && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          data-testid="ds-backfill-shrink-alert"
          title={<b>有 {shrinkingGroups} 个料号组的行数会减少 —— 按设计不应发生。</b>}
          description={
            <>
              回填只增不删（页签没表征的行一律原样保留），出现「回填后行数 &lt; 整组行数」说明有异常。
              <b>请先不要确认，联系技术人员核查。</b>
            </>
          }
        />
      )}

      {/* ── 逐表逐组比对（UNCHANGED 组也在，🚫 不过滤）── */}
      {tables.length === 0 ? (
        /*
         * 🚫 这里不能是空态：applicable=true 且 tables=[] 是「100% 组件不参与」的常态场景，
         *    渲染空态会把上面那条 D-33 告警的语境顶掉。只有连告警都没有时才补这句。
         *
         * 🚨 2026-09-07 追加 noRecordSnapshot：**这句话与那条新告警是直接矛盾的**。
         *    告警说「本单有 3 个页签本该写回，现在一个都写不了」，这句说「本单没有需要写回的页签」——
         *    同一屏两句话互相打架，而财务只会记住后者（它在下面、更像结论）。
         *    ⇒ 本条为真时一律不渲染这句：原因已由告警说清，空白比一句错话好。
         */
        nonParticipatingCount > 0 || flags.noRecordSnapshot ? null : (
          <Empty description="本单没有需要写回基础数据的页签" />
        )
      ) : (
        tables.map((t) => (
          <React.Fragment key={`${t.sheetKey}-${t.tableName}`}>
            <SheetSection
              t={t}
              showUnanchored={flags.unanchoredRows > 0}
              showColumnScope={!allUnchangedScreen}
            />
            {/* 元素价格说明：原型 01 在物料与元素BOM 表后给出；原型 03（全无变更屏）不出 —— 整组不写时它无意义。 */}
            {!allUnchangedScreen && (t.tableName === 'ds_quote_element_bom' || t.sheetKey === 'ELEMENT_BOM') && (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 20 }}
                title={<b>元素价格不写回基础数据。</b>}
                description={
                  <>
                    报价单里用到的元素实时价（<span style={{ fontFamily: MONO, fontSize: 13 }}>element_price</span>）
                    只记录在报价单自己的快照里，供后续价格调整对照用；基础数据的元素价格由「客户价格调整策略」维护，
                    本次确认不会碰它。
                  </>
                }
              />
            )}
          </React.Fragment>
        ))
      )}

      {/* ── 对不上的行明细表 ── */}
      {unanchoredFlat.length > 0 && <UnanchoredSection rows={unanchoredFlat} />}

      {/* ── 🆕「本次跳过的组」明细表，与上面那张**并列**不合并（理由见 BlockedSection 头注）。
             空态守卫：没有 BLOCKED 组时整块不渲染，🚫 不留「0 组」的僵尸区块。
             ⚠️ 前件用 collidingFlat.length 而非 blockedGroupCount —— 后端给了 BLOCKED 组却没给
             collidingRows 时渲染一张空表，等于用一个空壳冒充证据（顶部告警条仍在，信息不丢）。 ── */}
      {collidingFlat.length > 0 && (
        <BlockedSection rows={collidingFlat} groupCount={blockedGroupCount} />
      )}

      {/* ── 跨版「本次不动」补充说明 ── */}
      {crossVersionNotes.length > 0 && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 20 }}
          title={
            <>
              {crossVersionNotes.map((n) => (
                <div key={n}>{n}</div>
              ))}
            </>
          }
        />
      )}

      {/* ── 🆕 D-33：不参与升版的组件，🚫 不许静默（api.md §1 硬约束 4）。
             位置按原型 01：比对表之后、「仅记录不写回」之前。
             ⚠️ tables 为空（100% 不参与）时它自然落到最上面，仍是抽屉里唯一有信息量的块。 ── */}
      {nonParticipatingCount > 0 && (
        <NonParticipatingSection items={nonParticipating} count={nonParticipatingCount} />
      )}

      {/* ── 仅记录、不写回的字段 ── */}
      {extendRows.length > 0 && <ExtendColumnOnlySection rows={extendRows} />}

      {allUnchangedScreen && (
        <Text type="secondary" style={{ fontSize: 13 }}>
          🔑 判定为「无变更」的组<b>仍然列在这里</b>，不会被过滤掉 —— 否则分不清「这张表没变」和「这张表根本没被算进去」。
        </Text>
      )}
    </>
  );
};

export default CostingDsBackfillPanel;
