// ─────────────────────────────────────────────────────────────────────────────
// PartCostingDrawer —— 料号核价抽屉（F3/F5）
//   右侧 Drawer，固定 16 个 tab（tabPosition=left）；每 tab = 一个版本组。
//   tab 内：版本切换（历史只读）+ EditableSheetTable + 保存/新增行。
//   保存走后端指纹比对：UNCHANGED→info；UPGRADED/CREATED→success 并刷新徽标/版本/行。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Drawer, Tabs, Select, Button, Space, Tag, Spin, Empty, message, Modal, Typography, Alert,
} from 'antd';
import { SaveOutlined, ReloadOutlined } from '@ant-design/icons';
import { useAuthStore } from '../../../stores/authStore';
import EditableSheetTable, { withRowIds, newBlankRow } from './EditableSheetTable';
import {
  getSheets, getOverview, getRows, getVersions, saveRows,
} from './api';
import type {
  SheetMeta, PartOverview, OverviewSheet, VersionInfo, SheetRow, SheetRowsResult,
} from './types';

const { Text } = Typography;

// 编辑权角色（C10）
const EDIT_ROLES = ['PRICING_MANAGER', 'SYSTEM_ADMIN'];

// Sheet 元数据全局缓存（静态，抽屉多次打开只取一次）
let SHEETS_CACHE: SheetMeta[] | null = null;

function fmtSource(s?: string): string {
  if (s === 'MANUAL') return '手工';
  if (s === 'IMPORT') return '导入';
  return s ?? '—';
}

function fmtTime(iso?: string): string {
  if (!iso) return '';
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false });
  } catch {
    return iso;
  }
}

// ── 单个版本组面板（tab 内容，切到才挂载）─────────────────────────────────────
interface SheetTabPanelProps {
  materialNo: string;
  sheet: SheetMeta;
  overviewSheet?: OverviewSheet;
  canEdit: boolean;
  onSaved: () => void; // 保存成功后通知抽屉刷新概览徽标
}

const SheetTabPanel: React.FC<SheetTabPanelProps> = ({
  materialNo, sheet, overviewSheet, canEdit, onSaved,
}) => {
  const hasData = !!overviewSheet?.hasData;
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [versions, setVersions] = useState<VersionInfo[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<string | null>(null);
  const [currentVersionNo, setCurrentVersionNo] = useState<string | null>(overviewSheet?.currentVersion ?? null);
  const [rowsResult, setRowsResult] = useState<SheetRowsResult | null>(null);
  const [editRows, setEditRows] = useState<SheetRow[]>([]);
  const [masterInfo, setMasterInfo] = useState<Record<string, unknown> | null>(null);

  // 当前选中版是否为当前版（决定可编辑）
  const isCurrentSel = hasData
    ? (rowsResult ? rowsResult.isCurrent : false)
    : true; // 空 tab 从零新建 = 当前版语义
  const editable = canEdit && isCurrentSel;

  const loadVersion = useCallback(async (version?: string) => {
    setLoading(true);
    try {
      const r = await getRows(materialNo, sheet.sheetKey, version);
      setRowsResult(r);
      setEditRows(withRowIds(r.rows ?? []));
      setMasterInfo(r.masterInfo ?? null);
      setSelectedVersion(r.version);
    } catch (e: any) {
      message.error(e?.message ?? '读取数据失败');
    } finally {
      setLoading(false);
    }
  }, [materialNo, sheet.sheetKey]);

  const loadVersionList = useCallback(async () => {
    try {
      const r = await getVersions(materialNo, sheet.sheetKey);
      setVersions(r.versions ?? []);
      const cur = (r.versions ?? []).find((v) => v.isCurrent);
      if (cur) setCurrentVersionNo(cur.version);
    } catch (e: any) {
      // 版本列表失败不致命，记录即可
      setVersions([]);
    }
  }, [materialNo, sheet.sheetKey]);

  // 首次挂载加载
  useEffect(() => {
    if (hasData) {
      loadVersionList();
      loadVersion(); // 当前版
    } else {
      // 空 tab：从零新建，种一行空行便于录入
      setVersions([]);
      setCurrentVersionNo(null);
      setSelectedVersion(null);
      setRowsResult(null);
      setEditRows(canEdit ? [newBlankRow(sheet.columns)] : []);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleVersionChange = (v: string) => {
    loadVersion(v);
  };

  const reloadAfterSave = async (newVersion: string) => {
    setCurrentVersionNo(newVersion);
    await loadVersionList();
    await loadVersion(); // 回到新当前版
    onSaved();
  };

  const handleSave = async () => {
    // 收集 SUBDIM + VALUE 列（AXIS 由服务端注入；NAME 不回传）
    const writable = sheet.columns.filter((c) => c.role === 'SUBDIM' || c.role === 'VALUE');
    const payloadRows: SheetRow[] = editRows.map((row) => {
      const obj: SheetRow = {};
      writable.forEach((c) => {
        obj[c.name] = row[c.name] ?? null;
      });
      return obj;
    });

    if (payloadRows.length === 0) {
      message.warning('至少保留一行');
      return;
    }

    setSaving(true);
    try {
      const res = await saveRows(materialNo, sheet.sheetKey, {
        expectedCurrentVersion: hasData ? currentVersionNo : null,
        rows: payloadRows,
      });
      if (res.result === 'UNCHANGED') {
        message.info('内容未变化，未产生新版本');
      } else {
        message.success(`已保存，版本 ${res.version}`);
        await reloadAfterSave(res.version);
      }
    } catch (e: any) {
      const status = e?.httpStatus;
      if (status === 409) {
        Modal.confirm({
          title: '版本冲突',
          content: '该数据已被他人升级，请刷新后重试。',
          okText: '刷新',
          cancelText: '取消',
          onOk: async () => {
            await loadVersionList();
            await loadVersion();
          },
        });
      } else if (status === 422) {
        message.error('整组不可清空，至少保留一行');
      } else if (status === 400) {
        message.error(e?.message ?? '校验失败，请检查列填写');
      } else {
        message.error(e?.message ?? '保存失败');
      }
    } finally {
      setSaving(false);
    }
  };

  const versionOptions = useMemo(
    () => versions.map((v) => ({
      value: v.version,
      label: `${v.version}${v.isCurrent ? '（当前）' : ''} · ${fmtSource(v.source)} · ${v.operator} · ${fmtTime(v.operatedAt)}`,
    })),
    [versions],
  );

  return (
    <div>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {/* 顶部：版本切换 + 只读提示 */}
        <Space wrap>
          {hasData ? (
            <>
              <Text type="secondary">版本：</Text>
              <Select
                style={{ minWidth: 420 }}
                value={selectedVersion ?? undefined}
                options={versionOptions}
                onChange={handleVersionChange}
                loading={loading}
                placeholder="选择版本"
              />
            </>
          ) : (
            <Tag color="default">未配置 · 可从零新建（保存后生成 2000 版）</Tag>
          )}
          {hasData && rowsResult && !rowsResult.isCurrent && (
            <Tag color="orange">历史版本 · 只读</Tag>
          )}
          {!canEdit && <Tag color="default">无编辑权 · 只读</Tag>}
        </Space>

        {/* 主从 BOM 主表信息（P06/P07） */}
        {masterInfo && Object.keys(masterInfo).length > 0 && (
          <Alert
            type="info"
            showIcon
            message={
              <Space wrap>
                {Object.entries(masterInfo).map(([k, val]) => (
                  <Tag key={k}>{k}: {String(val ?? '—')}</Tag>
                ))}
              </Space>
            }
          />
        )}

        {/* 表体 */}
        <Spin spinning={loading}>
          {editRows.length === 0 && !editable ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />
          ) : (
            <EditableSheetTable
              columns={sheet.columns}
              rows={editRows}
              editable={editable}
              onChange={setEditRows}
            />
          )}
        </Spin>

        {/* 底部：保存 */}
        {editable && (
          <Space>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={saving}
              onClick={handleSave}
            >
              保存
            </Button>
          </Space>
        )}
      </Space>
    </div>
  );
};

// ── 抽屉主体 ─────────────────────────────────────────────────────────────────
interface Props {
  open: boolean;
  materialNo: string | null;
  onClose: () => void;
}

const PartCostingDrawer: React.FC<Props> = ({ open, materialNo, onClose }) => {
  const user = useAuthStore((s) => s.user);
  const canEdit = !!user && EDIT_ROLES.includes(user.role);

  const [sheets, setSheets] = useState<SheetMeta[]>(SHEETS_CACHE ?? []);
  const [overview, setOverview] = useState<PartOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const [activeKey, setActiveKey] = useState<string>('');

  const loadOverview = useCallback(async (mno: string) => {
    try {
      const ov = await getOverview(mno);
      setOverview(ov);
    } catch (e: any) {
      message.error(e?.message ?? '读取料号概览失败');
    }
  }, []);

  useEffect(() => {
    if (!open || !materialNo) return;
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        // 元数据（缓存一次）
        let metaSheets = SHEETS_CACHE;
        if (!metaSheets) {
          const r = await getSheets();
          metaSheets = (r.sheets ?? []).slice().sort((a, b) => a.order - b.order);
          SHEETS_CACHE = metaSheets;
        }
        if (cancelled) return;
        setSheets(metaSheets);
        if (metaSheets.length > 0) setActiveKey(metaSheets[0].sheetKey);
        await loadOverview(materialNo);
      } catch (e: any) {
        if (!cancelled) message.error(e?.message ?? '加载失败');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [open, materialNo, loadOverview]);

  const overviewMap = useMemo(() => {
    const m = new Map<string, OverviewSheet>();
    (overview?.sheets ?? []).forEach((s) => m.set(s.sheetKey, s));
    return m;
  }, [overview]);

  const tabItems = useMemo(() => sheets.map((sheet) => {
    const ov = overviewMap.get(sheet.sheetKey);
    const badge = ov?.hasData
      ? <Tag color="blue" style={{ marginLeft: 4 }}>{ov.currentVersion}</Tag>
      : <Tag style={{ marginLeft: 4 }}>未配置</Tag>;
    return {
      key: sheet.sheetKey,
      label: <span>{sheet.tabName}{badge}</span>,
      children: materialNo ? (
        <SheetTabPanel
          materialNo={materialNo}
          sheet={sheet}
          overviewSheet={ov}
          canEdit={canEdit}
          onSaved={() => materialNo && loadOverview(materialNo)}
        />
      ) : null,
    };
  }), [sheets, overviewMap, materialNo, canEdit, loadOverview]);

  const title = overview ? (
    <Space size="large" wrap>
      <span>料号核价 · <strong>{overview.materialNo}</strong></span>
      <Text type="secondary">{overview.materialName}</Text>
      {overview.specification && <Text type="secondary">规格：{overview.specification}</Text>}
      {overview.dimension && <Text type="secondary">尺寸：{overview.dimension}</Text>}
    </Space>
  ) : `料号核价 · ${materialNo ?? ''}`;

  return (
    <Drawer
      title={title}
      width={1200}
      placement="right"
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={
        <Button
          icon={<ReloadOutlined />}
          onClick={() => materialNo && loadOverview(materialNo)}
        >
          刷新概览
        </Button>
      }
    >
      {loading && sheets.length === 0 ? (
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

export default PartCostingDrawer;
