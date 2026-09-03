// ─────────────────────────────────────────────────────────────────────────────
// DatasetSheetDrawer —— 数据集料号抽屉（task-260902 · F-4 / F-5 / F-8）
//
// 视觉基准：原型「核价数据-抽屉 / -历史只读 / -空态 / -极值 / 保存冲突」5 份。
// 左侧 tab（tabPosition=left）+ 顶部版本下拉 + EditableSheetTable + 新增行/保存。
// tab 数由 `GET /dataset/{ds}/sheets` 决定，**前端不写死**（F-3：9 或 17 都走同一段代码）。
//
// 复用（闸门 A0 · D-13 抽公共件）：
//   · 表体直接用现有 `part-costing/EditableSheetTable`（+ 三个可选 prop）
//   · 行身份走现有 `__rid`（AP-54 教训：过滤后下标当原下标会让受控输入错位/假死）
//   · HTTP 层走 `createSheetApi(basePath)` 工厂
//
// 🚫 前端不回传 `role=NAME` 的列与 `row_fingerprint`（api.md §7）。
// 🚫 `frontend.md §1.2`：禁用态**可见但禁用 + hover 说明原因**，不隐藏按钮。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Drawer, Tabs, Select, Button, Space, Tag, Spin, Empty, Alert, Modal, Typography, message,
} from 'antd';
import { SaveOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useAuthStore } from '../../../stores/authStore';
import EditableSheetTable, { withRowIds, newBlankRow } from '../part-costing/EditableSheetTable';
import type { ColumnDef, SheetRow } from '../part-costing/types';
import { createDatasetApi } from './api';
import type { DatasetApi } from './api';
import {
  DATASETS, DATASET_EDIT_ROLES, NO_PERMISSION_TIP, HISTORY_READONLY_TIP,
  NO_ROWS_TIP, EMPTY_ROWS_TEXT, KEEP_ONE_ROW_TIP,
} from './datasetConfig';
import type {
  DatasetKey, DatasetPartRow, DatasetSheetMeta, DatasetOverviewSheet,
  DatasetPartOverview, DatasetVersionInfo, ValidationError,
} from './types';
import { toColumnDefs } from './types';
import ValidationErrorTable from './ValidationErrorTable';
import './DatasetSheetDrawer.css';

const { Text } = Typography;

/** sheet 元数据按 basePath 缓存（静态，抽屉多次打开只取一次）。
 *  ⚠️ 必须按 dataset 分桶 —— 用单个模块级变量会让 cost-basic / cost-detail 互相串号。 */
const SHEETS_CACHE: Partial<Record<DatasetKey, DatasetSheetMeta[]>> = {};

function fmtSource(s?: string | null): string {
  if (s === 'MANUAL') return '手工';
  if (s === 'IMPORT') return '导入';
  return s ?? '—';
}

/** 版本下拉/归档标签的时间格式：2026-09-03 10:12 */
function fmtTime(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 只回传可写列（SUBDIM / VALUE）；AXIS 由服务端注入，NAME 与 row_fingerprint 不回传 */
function writableColumns(cols: ColumnDef[]): ColumnDef[] {
  return cols.filter((c) => c.role === 'SUBDIM' || c.role === 'VALUE');
}

function toPayloadRow(row: SheetRow, writable: ColumnDef[]): SheetRow {
  const obj: SheetRow = {};
  writable.forEach((c) => { obj[c.name] = row[c.name] ?? null; });
  return obj;
}

/** 两行在可写列上是否一致（冲突态标脏行用） */
function sameWritable(a: SheetRow | undefined, b: SheetRow, writable: ColumnDef[]): boolean {
  if (!a) return false;
  return writable.every((c) => {
    const x = a[c.name] ?? null;
    const y = b[c.name] ?? null;
    return String(x ?? '') === String(y ?? '');
  });
}

// ── 单个 sheet 面板 ──────────────────────────────────────────────────────────
interface PanelProps {
  api: DatasetApi;
  axisValue: string;
  sheet: DatasetSheetMeta;
  overviewSheet?: DatasetOverviewSheet;
  canEdit: boolean;
  onSaved: () => void;
}

const DatasetSheetPanel: React.FC<PanelProps> = ({
  api, axisValue, sheet, overviewSheet, canEdit, onSaved,
}) => {
  const columns = useMemo(() => toColumnDefs(sheet.columns), [sheet.columns]);
  const writable = useMemo(() => writableColumns(columns), [columns]);

  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [versions, setVersions] = useState<DatasetVersionInfo[]>([]);
  /** 该轴值该 sheet 的最新版本号；null = 从未有过数据（空态） */
  const [latestVersion, setLatestVersion] = useState<number | null>(overviewSheet?.versionNo ?? null);
  /** 当前展示的版本号（= 保存时的 baseVersion） */
  const [shownVersion, setShownVersion] = useState<number | null>(overviewSheet?.versionNo ?? null);
  const [readOnly, setReadOnly] = useState(false);
  const [source, setSource] = useState<string | null>(overviewSheet?.source ?? null);
  const [rows, setRows] = useState<SheetRow[]>([]);
  const [saveErrors, setSaveErrors] = useState<ValidationError[] | null>(null);
  /** 409 冲突：库中已到的版本号；非 null 时顶部出冲突条 + 脏行标红 */
  const [conflictVersion, setConflictVersion] = useState<number | null>(null);

  /** 本次加载的服务端基线（按 __rid 索引），用于冲突态标出「哪几行是你改的」 */
  const baselineRef = useRef<Map<string, SheetRow>>(new Map());

  const hasEverData = latestVersion !== null;
  const isHistory = readOnly || (hasEverData && shownVersion !== null && shownVersion !== latestVersion);

  const loadRows = useCallback(async (version?: number) => {
    setLoading(true);
    try {
      const r = await api.getRows(axisValue, sheet.sheetKey, version);
      const withIds = withRowIds((r.rows ?? []) as SheetRow[]);
      setRows(withIds);
      const base = new Map<string, SheetRow>();
      withIds.forEach((row) => base.set(String(row.__rid), { ...row }));
      baselineRef.current = base;
      setShownVersion(r.versionNo ?? null);
      setReadOnly(!!r.readOnly);
      setSource(r.source ?? null);
      if (r.isLatest && r.versionNo !== null && r.versionNo !== undefined) setLatestVersion(r.versionNo);
      setConflictVersion(null);
      setSaveErrors(null);
    } catch (e: any) {
      message.error(e?.message ?? '读取数据失败');
    } finally {
      setLoading(false);
    }
  }, [api, axisValue, sheet.sheetKey]);

  const loadVersions = useCallback(async () => {
    try {
      const r = await api.getVersions(axisValue, sheet.sheetKey);
      const list = r.versions ?? [];
      setVersions(list);
      const latest = list.find((v) => v.isLatest);
      if (latest) setLatestVersion(latest.versionNo);
    } catch {
      setVersions([]);
    }
  }, [api, axisValue, sheet.sheetKey]);

  useEffect(() => {
    if (overviewSheet?.versionNo !== null && overviewSheet?.versionNo !== undefined) {
      void loadVersions();
      void loadRows();
    } else {
      // AC-32 空态：该轴值该 sheet 从未有过数据 —— 不预置空行，让空态文案先出来
      setVersions([]);
      setLatestVersion(null);
      setShownVersion(null);
      setReadOnly(false);
      setRows([]);
      baselineRef.current = new Map();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleAddRow = () => setRows((rs) => [...rs, newBlankRow(columns)]);

  const doRefresh = useCallback(async () => {
    await loadVersions();
    await loadRows();
  }, [loadVersions, loadRows]);

  /** F-8：刷新会丢弃本地未保存改动 ⇒ 必须二次确认 */
  const confirmRefresh = () => {
    Modal.confirm({
      title: '刷新会丢弃本地未保存的改动',
      content: '确认后将重新拉取版本列表与行数据，当前表格里未保存的修改会丢失。',
      okText: '确认刷新',
      cancelText: '取消',
      onOk: () => doRefresh(),
    });
  };

  const handleSave = async () => {
    const payloadRows = rows.map((r) => toPayloadRow(r, writable));
    if (payloadRows.length === 0) { message.warning(NO_ROWS_TIP); return; }

    setSaving(true);
    setSaveErrors(null);
    try {
      const res = await api.saveRows(axisValue, sheet.sheetKey, {
        baseVersion: hasEverData ? shownVersion : null,
        rows: payloadRows,
      });
      setConflictVersion(null);
      if (res.result === 'UNCHANGED') {
        message.info('数据无变化，未升版');                       // AC-28
      } else if (res.result === 'CREATED') {
        message.success(`已创建 v${res.versionNo}`);
        await doRefresh();
        onSaved();
      } else {
        message.success(`已升版至 v${res.versionNo}`);            // AC-27
        await doRefresh();
        onSaved();
      }
    } catch (e: any) {
      const status = e?.httpStatus;
      const payload = e?.payload as any;
      if (status === 409) {
        // AC-41：明确冲突提示 + 本地改动**保留不清空**
        const cur = Number(payload?.currentVersion);
        setConflictVersion(Number.isFinite(cur) ? cur : null);
      } else if (status === 400 && Array.isArray(payload?.errors) && payload.errors.length > 0) {
        setSaveErrors(payload.errors as ValidationError[]);       // F-9 复用同一张错误表
      } else if (status === 422) {
        // api.md §7：整组清空护栏。正常路径下用户点不到（删除按钮在只剩一行时已禁用），
        // 这里兜底把后端原文亮出来，不吞成泛化的「保存失败」。
        message.error(e?.message ?? KEEP_ONE_ROW_TIP);
      } else {
        message.error(e?.message ?? '保存失败');
      }
    } finally {
      setSaving(false);
    }
  };

  const versionOptions = useMemo(
    () => versions.map((v) => ({
      value: v.versionNo,
      label: `v${v.versionNo}${v.isLatest ? '（当前）' : ''}${
        v.updatedAt || v.archivedAt ? ` · ${fmtTime(v.updatedAt ?? v.archivedAt)}` : ''
      }`,
    })),
    [versions],
  );

  const shownVersionInfo = versions.find((v) => v.versionNo === shownVersion);

  const editable = canEdit && !isHistory;
  const saveDisabledReason = !canEdit
    ? NO_PERMISSION_TIP
    : isHistory
      ? HISTORY_READONLY_TIP
      : rows.length === 0
        ? NO_ROWS_TIP
        : undefined;
  const addRowDisabledReason = !canEdit ? NO_PERMISSION_TIP : isHistory ? HISTORY_READONLY_TIP : undefined;

  const dirtyRow = (row: SheetRow): string | undefined =>
    (conflictVersion !== null && !sameWritable(baselineRef.current.get(String(row.__rid)), row, writable)
      ? 'ds-row-dirty'
      : undefined);

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {/* AC-41 冲突条：本地改动保留，提供「刷新到 v{n}」（会二次确认） */}
      {conflictVersion !== null && (
        <Alert
          type="error"
          showIcon
          message={<b>数据已被他人更新至 v{conflictVersion}，请刷新后重试</b>}
          description={
            <div>
              <Text type="secondary">
                你正在编辑的是 v{shownVersion}。本次提交<b>未写入</b>，库中数据保持 v{conflictVersion} 不变。
              </Text>
              <div style={{ marginTop: 8 }}>
                <Button size="small" type="primary" icon={<ReloadOutlined />} onClick={confirmRefresh}>
                  刷新到 v{conflictVersion}
                </Button>
                <Text type="secondary" style={{ marginLeft: 8 }}>
                  刷新会丢弃本地未保存的改动，会再次确认
                </Text>
              </div>
            </div>
          }
        />
      )}

      {/* 顶部工具行：左＝版本 + 状态，右＝新增行 + 保存 */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap' }}>
        <Space wrap>
          <Text type="secondary">版本</Text>
          <Select<number>
            style={{ width: 190 }}
            value={hasEverData ? shownVersion ?? undefined : undefined}
            options={versionOptions}
            onChange={(v) => { void loadRows(v); }}
            loading={loading}
            disabled={!hasEverData}
            placeholder={hasEverData ? '选择版本' : '暂无版本'}
          />
          {!hasEverData && <Tag>尚未创建</Tag>}
          {/* 冲突态下这个版本已经不是「最新」了，🚫 不能与「已过期」同时挂着自相矛盾
              （原型「核价数据-保存冲突」只出「已过期」一个标签）。 */}
          {hasEverData && !isHistory && conflictVersion === null && <Tag color="green">最新版本 · 可编辑</Tag>}
          {hasEverData && isHistory && <Tag color="gold">历史版本 · 只读</Tag>}
          {conflictVersion !== null && <Tag color="red">已过期</Tag>}
          {hasEverData && !isHistory && source && <Tag>来源：{fmtSource(source)}</Tag>}
          {hasEverData && isHistory && shownVersionInfo?.archivedAt && (
            <Tag>
              归档于 {fmtTime(shownVersionInfo.archivedAt)}
              {shownVersionInfo.archivedBy ? ` · ${shownVersionInfo.archivedBy}` : ''}
            </Tag>
          )}
        </Space>

        {/* 🚫 禁用但可见 + hover 说明原因（frontend.md §1.2 / AC-29 / AC-31） */}
        <Space>
          <Button
            icon={<PlusOutlined />}
            disabled={!!addRowDisabledReason}
            title={addRowDisabledReason}
            onClick={handleAddRow}
          >
            新增行
          </Button>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={saving}
            disabled={!!saveDisabledReason}
            title={saveDisabledReason}
            onClick={handleSave}
          >
            保存
          </Button>
        </Space>
      </div>

      {hasEverData && !isHistory && (
        <Alert
          type="info"
          showIcon
          message={
            <span>
              列头带 <b>🔗</b> 的是<b>比对项</b>，参与行指纹。改动它们会触发<b>整组升版</b>；
              改「项次」这类非比对项<b>不会</b>升版（改动也不会被写入）。
            </span>
          }
        />
      )}

      {hasEverData && isHistory && (
        <Alert
          type="warning"
          showIcon
          message={
            <span>
              正在查看 <b>v{shownVersion}</b> 的归档数据。切回 <b>v{latestVersion}（当前）</b> 才能编辑。
            </span>
          }
        />
      )}

      {saveErrors && (
        <>
          <Alert
            type="error"
            showIcon
            message={<b>保存校验未通过，共 {saveErrors.length} 处问题，本次未写入任何数据</b>}
          />
          <ValidationErrorTable errors={saveErrors} />
        </>
      )}

      <Spin spinning={loading}>
        {rows.length === 0 ? (
          // AC-32 空态：🚫 不是红色遮罩、不是白屏、不是「加载中…」永久占位（AP-31 / AP-38 族）
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={EMPTY_ROWS_TEXT} />
        ) : (
          <EditableSheetTable
            columns={columns}
            rows={rows}
            editable={editable}
            onChange={setRows}
            showComparedBadge
            lookupFn={api.lookup}
            rowClassName={dirtyRow}
            deleteDisabledTip={KEEP_ONE_ROW_TIP}
          />
        )}
      </Spin>

      <Text type="secondary">
        白底列（如「组成料号名称」）为<b>主数据带出的只读列</b>，不建数据库字段、不参与保存。
      </Text>
    </Space>
  );
};

// ── 抽屉主体 ─────────────────────────────────────────────────────────────────
interface Props {
  open: boolean;
  dataset: DatasetKey;
  part: DatasetPartRow | null;
  onClose: () => void;
}

const DatasetSheetDrawer: React.FC<Props> = ({ open, dataset, part, onClose }) => {
  const cfg = DATASETS[dataset];
  const api = useMemo(() => createDatasetApi(dataset), [dataset]);
  const user = useAuthStore((s) => s.user);
  const canEdit = !!user && DATASET_EDIT_ROLES.includes(user.role);

  const axisValue = part?.axisValue ?? null;

  const [sheets, setSheets] = useState<DatasetSheetMeta[]>(SHEETS_CACHE[dataset] ?? []);
  const [overview, setOverview] = useState<DatasetPartOverview | null>(null);
  /**
   * 概览「是否已问过后端」（成功或失败都算），而不是「是否拿到了数据」。
   *
   * 🚨 为什么必须有这个标志：tab 面板的初始态（有数据 → 读行 / 无数据 → 空态）
   *    在 **mount 那一刻**由 `overviewSheet` 决定，且面板的 mount effect 依赖数组是 `[]`、只跑一次。
   *    若 Tabs 在 overview 到达之前就渲染，首个面板会带着 `overviewSheet=undefined` 挂载 →
   *    被判成「从未有过数据」→ 明明有数据却显示空态，且之后不会自愈（AC-32 反向误判）。
   *    ⇒ overview 问完之前不渲染 Tabs。
   * 🚫 用「失败也置 true」而不是「成功才置 true」：接口挂了要落到 Empty/报错，
   *    绝不能停在永久转圈（AP-31 / AP-38 族）。
   */
  const [overviewReady, setOverviewReady] = useState(false);
  const [loading, setLoading] = useState(false);
  const [activeKey, setActiveKey] = useState<string>('');

  const loadOverview = useCallback(async (axis: string) => {
    try {
      const ov = await api.getOverview(axis);
      setOverview(ov);
    } catch (e: any) {
      message.error(e?.message ?? '读取料号概览失败');
    } finally {
      setOverviewReady(true);
    }
  }, [api]);

  useEffect(() => {
    if (!open || !axisValue) return;
    let cancelled = false;
    (async () => {
      setLoading(true);
      setOverviewReady(false);
      setOverview(null);
      try {
        let metaSheets = SHEETS_CACHE[dataset];
        if (!metaSheets) {
          const r = await api.getSheets();
          metaSheets = (r.sheets ?? []).slice().sort((a, b) => a.sortOrder - b.sortOrder);
          SHEETS_CACHE[dataset] = metaSheets;
        }
        if (cancelled) return;
        setSheets(metaSheets);
        if (metaSheets.length > 0) setActiveKey(metaSheets[0].sheetKey);
        await loadOverview(axisValue);
      } catch (e: any) {
        if (!cancelled) message.error(e?.message ?? '加载失败');
      } finally {
        // 🚫 AP-31：任何失败路径都必须解除等待态。
        //    getSheets 抛错时 loadOverview 根本没被调用，若只在它的 finally 里置位，
        //    这里就会永久停在转圈 —— 那正是「加载中…永久占位」族的典型成因。
        if (!cancelled) { setLoading(false); setOverviewReady(true); }
      }
    })();
    return () => { cancelled = true; };
  }, [open, axisValue, dataset, api, loadOverview]);

  const overviewMap = useMemo(() => {
    const m = new Map<string, DatasetOverviewSheet>();
    (overview?.sheets ?? []).forEach((s) => m.set(s.sheetKey, s));
    return m;
  }, [overview]);

  const tabItems = useMemo(() => sheets.map((sheet) => {
    const ov = overviewMap.get(sheet.sheetKey);
    const hasData = ov?.versionNo !== null && ov?.versionNo !== undefined;
    const badge = hasData
      ? <Tag color="blue" style={{ marginLeft: 4 }}>v{ov!.versionNo} · {ov!.rowCount ?? 0}</Tag>
      : <Tag style={{ marginLeft: 4 }}>—</Tag>;
    return {
      key: sheet.sheetKey,
      label: <span>{sheet.sheetName}{badge}</span>,
      children: axisValue ? (
        <DatasetSheetPanel
          api={api}
          axisValue={axisValue}
          sheet={sheet}
          overviewSheet={ov}
          canEdit={canEdit}
          onSaved={() => { if (axisValue) void loadOverview(axisValue); }}
        />
      ) : null,
    };
  }), [sheets, overviewMap, axisValue, canEdit, api, loadOverview]);

  const subtitle = [part?.materialName, part?.specification, part?.dimension]
    .filter((v) => v !== null && v !== undefined && v !== '')
    .join(' ｜ ');

  const title = (
    <span>
      {cfg.label} · <strong>{axisValue ?? ''}</strong>
      {subtitle && <Text type="secondary" style={{ marginLeft: 12 }}>{subtitle}</Text>}
    </span>
  );

  return (
    <Drawer
      title={title}
      width={1180}
      placement="right"
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      {(loading && sheets.length === 0) || !overviewReady ? (
        <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
      ) : sheets.length === 0 ? (
        <Empty description="无 sheet 元数据" />
      ) : (
        <Tabs
          tabPosition="left"
          activeKey={activeKey}
          onChange={setActiveKey}
          destroyInactiveTabPane
          items={tabItems}
          style={{ minHeight: 400 }}
        />
      )}
    </Drawer>
  );
};

export default DatasetSheetDrawer;
