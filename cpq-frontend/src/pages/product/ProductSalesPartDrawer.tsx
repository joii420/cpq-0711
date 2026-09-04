// ─────────────────────────────────────────────────────────────────────────────
// ProductSalesPartDrawer —— 销售产品抽屉（task-260903 · F-4，本任务核心）
//
// 右侧 Drawer；左侧竖排 tab（数量与顺序**由 `GET /dataset/quote/sheets` 决定，
// 🚫 前端不写死 13**）；每 tab 内 = 版本下拉 + **平铺表格（不是树）**。
//
// 🔒 全只读（AC-8 / AC-9）：
//   · **不渲染** 保存 / 新增行 / 删除 按钮 —— 是不渲染，不是禁用。
//     这是 `frontend.md §1.2`「禁止 if(...) return null 隐藏按钮」的**合理例外**：
//     那条针对「本可用但当前不可用」的动作，需让用户知道能力存在；
//     本页是**整页无编辑能力**，渲染一排永久禁用的保存按钮反而误导。
//     **此例外已在闸门 A 呈报时点名。**
//   · 所有角色（含 PRICING_MANAGER / SYSTEM_ADMIN）一律只读，故本文件**不读 authStore**。
//   · 🚨 无论 rows 响应里 `readOnly` 为何值一律只读（api.md 硬约束 7）。
//   · 🚫 不调 `PUT rows` —— 调它就产生了第二条升版路径。
//
// 🚧 过渡（2026-09-03 主线情报更正）：原计划复用的 `<SheetPartDrawer>` 公共件不会存在了 ——
//    task-260902 实测新旧抽屉 UI 逐项不同，改为零触碰 legacy + 新建
//    `pages/master-data/dataset/DatasetSheetDrawer.tsx`（该目录当前尚未合入 master，无从参照）。
//    ⇒ 维持本地平行实现；日后若要收敛，本文件整体替换即可，列表页无需改动。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Drawer, Tabs, Select, Space, Tag, Spin, Empty, Typography, message } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import ReadonlySheetTable from './ReadonlySheetTable';
import { quoteSheetApi } from './productHubApi';
import type {
  SheetMeta, PartOverview, OverviewSheet, VersionInfo, SheetRow,
} from './productHubTypes';

const { Text } = Typography;

/** 全角空格分隔符，对应原型「来源：导入 <全角空格>.<全角空格> 共 9 行」的视觉间距。
 *  用 \u3000 转义而不直接写该字符：eslint no-irregular-whitespace 禁止源码里出现裸全角空格。 */
const SEP = '\u3000\u00b7\u3000';

/** sheet 元数据是静态的（列结构由后端 Registry 决定），抽屉多次打开只取一次 */
let SHEETS_CACHE: SheetMeta[] | null = null;

function fmtSource(s?: string | null): string {
  if (s === 'MANUAL') return '手工';
  if (s === 'IMPORT') return '导入';
  return s ?? '—';
}

function fmtDate(iso?: string | null): string {
  if (!iso) return '';
  try {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString('zh-CN');
  } catch {
    return iso;
  }
}

// ── 单个 sheet 面板（切到才挂载：Tabs destroyOnHidden）─────────────────────────
interface SheetPanelProps {
  axisValue: string;
  sheet: SheetMeta;
  overviewSheet?: OverviewSheet;
}

const SheetPanel: React.FC<SheetPanelProps> = ({ axisValue, sheet, overviewSheet }) => {
  // versionNo === null ⇒ 该 sheet 该轴值**从未有过数据**（api.md 硬约束 6）。
  // 此时**一次请求都不发**，直接空态 —— 这是 AP-31「加载中永久占位族」的正面防线：
  // 不发请求就不可能停在「加载中…」。
  const hasData = overviewSheet ? overviewSheet.versionNo !== null : false;

  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<SheetRow[]>([]);
  const [source, setSource] = useState<string | null>(null);
  const [versions, setVersions] = useState<VersionInfo[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);

  const loadRows = useCallback(async (version?: number) => {
    setLoading(true);
    try {
      const r = await quoteSheetApi.getRows(axisValue, sheet.sheetKey, version);
      setRows(r.rows ?? []);
      setSource(r.source ?? null);
      // 服务端回的 versionNo 才是权威（省略 version 参数时它是「当前版本」）
      setSelectedVersion(r.versionNo ?? version ?? null);
    } catch (e) {
      message.error((e as Error)?.message ?? '读取数据失败');
      setRows([]);
    } finally {
      // finally 保证任何分支都退出 loading，不留「加载中…」死态
      setLoading(false);
    }
  }, [axisValue, sheet.sheetKey]);

  const loadVersions = useCallback(async () => {
    try {
      const r = await quoteSheetApi.getVersions(axisValue, sheet.sheetKey);
      setVersions(r.versions ?? []);
    } catch {
      // 版本列表失败不致命：表格数据已单独取，下拉降级为空即可
      setVersions([]);
    }
  }, [axisValue, sheet.sheetKey]);

  useEffect(() => {
    if (!hasData) {
      setRows([]);
      setVersions([]);
      setSelectedVersion(null);
      return;
    }
    void loadVersions();
    void loadRows();
  }, [hasData, loadVersions, loadRows]);

  const versionOptions = useMemo(
    () => versions.map((v) => {
      const when = fmtDate(v.updatedAt ?? v.archivedAt);
      const parts = [
        `v${v.versionNo}${v.isLatest ? '（当前）' : ''}`,
        fmtSource(v.source),
        when,
      ].filter((s) => s !== '' && s !== '—');
      return { value: v.versionNo, label: parts.join(' · ') };
    }),
    [versions],
  );

  // 空 tab（从未有过数据）：Empty 空态，**版本下拉不渲染**（原型「销售产品-抽屉-空tab」）
  if (!hasData) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />;
  }

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <div
        style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          gap: 12, flexWrap: 'wrap',
        }}
      >
        <Space wrap size={10}>
          <Text type="secondary">版本</Text>
          <Select<number>
            style={{ minWidth: 260 }}
            value={selectedVersion ?? undefined}
            options={versionOptions}
            onChange={(v) => { void loadRows(v); }}
            loading={loading}
            placeholder="选择版本"
          />
          <Tag color="success" icon={<LockOutlined />}>只读</Tag>
        </Space>
        <Text type="secondary" style={{ fontSize: 12 }}>
          来源：{fmtSource(source)}{SEP}共 {rows.length} 行
        </Text>
      </div>

      <Spin spinning={loading}>
        <ReadonlySheetTable columns={sheet.columns} rows={rows} />
      </Spin>
    </Space>
  );
};

// ── 抽屉主体 ─────────────────────────────────────────────────────────────────
interface Props {
  open: boolean;
  /** 轴值 = 销售料号 */
  axisValue: string | null;
  /** 列表行上已有的品名，用于 overview 返回前先把副标题填上（避免标题跳动） */
  fallbackMaterialName?: string | null;
  onClose: () => void;
}

const ProductSalesPartDrawer: React.FC<Props> = ({
  open, axisValue, fallbackMaterialName, onClose,
}) => {
  const [sheets, setSheets] = useState<SheetMeta[]>(SHEETS_CACHE ?? []);
  const [overview, setOverview] = useState<PartOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const [activeKey, setActiveKey] = useState<string>('');

  useEffect(() => {
    if (!open || !axisValue) return;
    let cancelled = false;
    (async () => {
      setLoading(true);
      // 每次打开都清掉上一个料号的概览，避免徽标短暂串号
      setOverview(null);
      try {
        let metaSheets = SHEETS_CACHE;
        if (!metaSheets) {
          const r = await quoteSheetApi.getSheets();
          metaSheets = (r.sheets ?? []).slice().sort((a, b) => a.sortOrder - b.sortOrder);
          SHEETS_CACHE = metaSheets;
        }
        if (cancelled) return;
        setSheets(metaSheets);
        // AC-11 步骤⑦：每次打开抽屉都重置到**第一个 tab**，不保留上次停留位置
        setActiveKey(metaSheets.length > 0 ? metaSheets[0].sheetKey : '');
        const ov = await quoteSheetApi.getOverview(axisValue);
        if (!cancelled) setOverview(ov);
      } catch (e) {
        if (!cancelled) message.error((e as Error)?.message ?? '加载失败');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [open, axisValue]);

  const overviewMap = useMemo(() => {
    const m = new Map<string, OverviewSheet>();
    (overview?.sheets ?? []).forEach((s) => m.set(s.sheetKey, s));
    return m;
  }, [overview]);

  const tabItems = useMemo(() => sheets.map((sheet) => {
    const ov = overviewMap.get(sheet.sheetKey);
    const count = ov?.rowCount ?? 0;
    return {
      key: sheet.sheetKey,
      label: (
        <Space size={8}>
          <span>{sheet.sheetName}</span>
          {/* 徽标 = 该 sheet 该料号的行数；0 行（含 versionNo=null 从未有数据）打灰色 0 */}
          <Tag color={count > 0 ? 'blue' : 'default'} style={{ marginInlineEnd: 0 }}>
            {count}
          </Tag>
        </Space>
      ),
      children: axisValue ? (
        <SheetPanel axisValue={axisValue} sheet={sheet} overviewSheet={ov} />
      ) : null,
    };
  }), [sheets, overviewMap, axisValue]);

  const materialName = overview?.materialName ?? fallbackMaterialName ?? null;

  const title = (
    <div>
      {/* AC-5：标题必须原样含轴值（如 S-3120014539） */}
      <div style={{ fontSize: 16, fontWeight: 600 }}>销售产品 · {axisValue ?? ''}</div>
      <div style={{ fontSize: 12, color: 'rgba(0, 0, 0, 0.45)', marginTop: 2 }}>
        {materialName ? `${materialName}${SEP}` : ''}以销售料号为轴的报价数据
      </div>
    </div>
  );

  return (
    <Drawer
      title={title}
      // antd 6：width 已弃用（弃用告警走 console.error，会违反 AC-11「console 无 error」），
      // 改用等价的 size；数值语义不变，仍是 1200px（原型抽屉宽度）
      size={1200}
      placement="right"
      open={open}
      onClose={onClose}
      destroyOnHidden
      extra={<Tag color="success" icon={<LockOutlined />}>只读</Tag>}
    >
      {loading && sheets.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
      ) : sheets.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />
      ) : (
        <Tabs
          // antd 6：tabPosition 已弃用，改用 tabPlacement；"start" = LTR 下的左侧竖排
          tabPlacement="start"
          activeKey={activeKey}
          onChange={setActiveKey}
          destroyOnHidden
          items={tabItems}
          style={{ minHeight: 400 }}
        />
      )}
    </Drawer>
  );
};

export default ProductSalesPartDrawer;
