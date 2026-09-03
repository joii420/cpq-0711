// ─────────────────────────────────────────────────────────────────────────────
// DatasetPartListTab —— 「基础核价」/「详细核价」页签（task-260902 · F-2 / F-3）
//
// 视觉基准：原型「核价数据-列表」。两个页签**同一个组件**，唯一差异是 `dataset` prop
// （F-3：tab 数 9 / 17 由 `GET /dataset/{ds}/sheets` 决定，前端不写死）。
//
// 列表是裸 <Table> + 可点击行（Master-Detail 导航，属 `docs/列表操作规范.md` 例外白名单），
// 行为骨架复用 `part-costing/SheetPartListTab`（F-1 抽出的公共件），
// 工具栏版式由它内部套 TOOLBAR_ROW_STYLE。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useMemo, useState } from 'react';
import { Button, Tag, Tooltip, Typography } from 'antd';
import { ImportOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useAuthStore } from '../../../stores/authStore';
import SheetPartListTab from '../part-costing/SheetPartListTab';
import type { ConfiguredFilter } from '../part-costing/SheetPartListTab';
import { createDatasetApi } from './api';
import { DATASETS, DATASET_EDIT_ROLES, NO_PERMISSION_TIP } from './datasetConfig';
import type { DatasetKey, DatasetPartRow } from './types';
import DatasetSheetDrawer from './DatasetSheetDrawer';
import DatasetImportDrawer from './DatasetImportDrawer';

const { Text } = Typography;

/** 列 columnKey → `api.md §3` sortBy 白名单值（旧料号不可排序，与原型一致） */
const SORT_KEY_MAP: Record<string, string> = {
  axisValue: 'axisValue',
  materialName: 'materialName',
  specification: 'specification',
  dimension: 'dimension',
  configured: 'configuredCount',
  lastUpdatedAt: 'lastUpdatedAt',
};

const CONFIGURED_OPTIONS: { value: ConfiguredFilter; label: string }[] = [
  { value: 'ALL', label: '配置状态：全部' },
  { value: 'DONE', label: '已配齐' },
  { value: 'TODO', label: '未配齐' },
];

function fmtTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * 「已配置 N/M」徽标配色 —— 原型「核价数据-列表」四行给了四种色：
 *   9/9 绿 · 6/9 蓝 · 2/9 金 · 0/9 灰
 * 能同时命中这四行的唯一规则：0 → 灰；配齐 → 绿；≥50% → 蓝；否则金。
 * （原型只给了取值样例、没给规则，此处为推断，已在回报中列出待确认。）
 */
function configuredTagColor(done: number, total: number): string | undefined {
  if (!total || done <= 0) return undefined;      // 灰（antd 默认色）
  if (done >= total) return 'green';
  return done * 2 >= total ? 'blue' : 'gold';
}

interface Props {
  dataset: DatasetKey;
}

const DatasetPartListTab: React.FC<Props> = ({ dataset }) => {
  const cfg = DATASETS[dataset];
  const api = useMemo(() => createDatasetApi(dataset), [dataset]);
  const user = useAuthStore((s) => s.user);
  const canImport = !!user && DATASET_EDIT_ROLES.includes(user.role);

  const [importOpen, setImportOpen] = useState(false);

  return (
    <SheetPartListTab<DatasetPartRow>
      rowKeyOf={(r) => r.axisValue}
      sortKeyMap={SORT_KEY_MAP}
      searchPlaceholder={`搜索${cfg.axisLabel} / 品名`}
      configuredOptions={CONFIGURED_OPTIONS}
      emptyText="暂无数据"
      // 7 列合计 1170px：窄视口横向滚动，🚫 不把列挤到换行（原型「列不换行、不撑破布局」）
      tableScroll={{ x: 1170 }}
      fetchParts={async (p) => {
        const r = await api.listParts({
          keyword: p.keyword,
          page: Math.max(0, p.page - 1),   // 契约 §3：page 为 0-based
          size: p.size,
          sortBy: p.sortBy,
          sortDir: p.sortOrder,
          configured: p.configured,
        });
        return { items: r.items ?? [], total: r.total ?? 0 };
      }}
      columns={({ orderOf }): ColumnsType<DatasetPartRow> => [
        {
          title: cfg.axisLabel,
          dataIndex: 'axisValue',
          key: 'axisValue',
          width: 160,
          sorter: true,
          sortOrder: orderOf('axisValue'),
          render: (v: string) => <b>{v}</b>,
        },
        {
          title: '品名',
          dataIndex: 'materialName',
          key: 'materialName',
          width: 240,   // 原型极值行品名 16 字（触点组件-银镍复合型双面焊接式），须一行放得下
          sorter: true,
          sortOrder: orderOf('materialName'),
          render: (v) => v || '—',
        },
        {
          title: '规格',
          dataIndex: 'specification',
          key: 'specification',
          width: 180,   // 原型极值行规格「AgNi11#-Ⅰ / 线材」含斜杠，不折行
          sorter: true,
          sortOrder: orderOf('specification'),
          render: (v) => v || <Text type="secondary">—</Text>,
        },
        {
          title: '尺寸',
          dataIndex: 'dimension',
          key: 'dimension',
          width: 150,
          sorter: true,
          sortOrder: orderOf('dimension'),
          render: (v) => v || <Text type="secondary">—</Text>,
        },
        {
          title: '旧料号',
          dataIndex: 'oldMaterialNo',
          key: 'oldMaterialNo',
          width: 150,
          render: (v) => v || <Text type="secondary">—</Text>,
        },
        {
          title: '已配置',
          key: 'configured', // 无 dataIndex —— 排序按 columnKey 映射为 configuredCount
          width: 110,
          sorter: true,
          sortOrder: orderOf('configured'),
          render: (_: unknown, row) => (
            <Tag color={configuredTagColor(row.configuredCount, row.totalSheetCount)}>
              {row.configuredCount} / {row.totalSheetCount}
            </Tag>
          ),
        },
        {
          title: '最后更新',
          dataIndex: 'lastUpdatedAt',
          key: 'lastUpdatedAt',
          width: 180,
          sorter: true,
          sortOrder: orderOf('lastUpdatedAt'),
          render: (v: string | null) => <Text type="secondary">{fmtTime(v)}</Text>,
        },
      ]}
      toolbarActions={() => (
        // AC-31：无权限时**可见但禁用** + hover 说明原因，不隐藏
        <Tooltip title={canImport ? undefined : NO_PERMISSION_TIP}>
          <Button
            type="primary"
            icon={<ImportOutlined />}
            disabled={!canImport}
            onClick={() => setImportOpen(true)}
          >
            {cfg.importActionLabel}
          </Button>
        </Tooltip>
      )}
    >
      {({ drawerOpen, activeRow, closeDrawer, refresh }) => (
        <>
          <DatasetSheetDrawer
            open={drawerOpen}
            dataset={dataset}
            part={activeRow}
            onClose={closeDrawer}
          />

          {/* AC-33：导入成功 → 抽屉自动关闭 + 列表自动刷新（无需手工点刷新） */}
          <DatasetImportDrawer
            open={importOpen}
            dataset={dataset}
            onClose={() => setImportOpen(false)}
            onSuccess={() => { setImportOpen(false); refresh(); }}
          />
        </>
      )}
    </SheetPartListTab>
  );
};

export default DatasetPartListTab;
