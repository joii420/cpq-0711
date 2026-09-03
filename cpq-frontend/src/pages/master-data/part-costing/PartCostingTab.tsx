// ─────────────────────────────────────────────────────────────────────────────
// PartCostingTab —— 料号核价列表（task-0728 · F2）
//   有核价数据的销售料号列表；服务端分页 + 服务端排序 + 服务端过滤；点行开抽屉。
//   本页主入口 = 点行进抽屉，无批量动作，属「列表操作规范」例外白名单
//   （纯查看 / Master-Detail 导航），用可点击行的裸 <Table>。
//
// task-260902 · F-1：行为骨架（防抖搜索 / 三态排序 / 分页 / 点行开抽屉 / 工具栏版式）
//   已抽到 `SheetPartListTab`，本文件退化为**薄封装**，只提供本页签自己的
//   列定义、文案、动作与抽屉 —— 列顺序、标题、渲染、空态文案、请求参数
//   与改造前**逐字一致**（AC-42：现有页签逐屏零变化）。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useState } from 'react';
import { Button, Tag, Typography } from 'antd';
import { ImportOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { listParts } from './api';
import type { PartSortBy } from './api';
import type { PartRow } from './types';
import PartCostingDrawer from './PartCostingDrawer';
import PricingBasicDataImportDrawer from '../PricingBasicDataImportDrawer';
import SheetPartListTab from './SheetPartListTab';
import type { ConfiguredFilter } from './SheetPartListTab';

const { Text } = Typography;

/**
 * 列 key → `api.md` A1 `sortBy` 白名单值。
 *
 * ⚠️ 「已配置」列只有 `key: 'configured'` 而**没有 dataIndex**，antd 回调里的
 *    `sorter.field` 会是 undefined，因此统一按 `sorter.columnKey` 查表，
 *    并把 `configured` 映射到契约里的 `configuredCount`。
 */
const SORT_KEY_MAP: Record<string, PartSortBy> = {
  materialName: 'materialName',
  materialNo: 'materialNo',
  specification: 'specification',
  dimension: 'dimension',
  configured: 'configuredCount',
  lastUpdatedAt: 'lastUpdatedAt',
};

/** 「配置状态」过滤：UI 三态 → A1 的 `configured`（boolean | undefined） */
const CONFIGURED_OPTIONS: { value: ConfiguredFilter; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'DONE', label: '已配齐' },
  { value: 'TODO', label: '未配齐' },
];

function fmtTime(iso?: string | null): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false });
  } catch {
    return iso;
  }
}

const PartCostingTab: React.FC = () => {
  // 导入核价数据抽屉（F1 从壳页顶部移入本页签，可见性沿用现状：不加角色判断）
  const [importOpen, setImportOpen] = useState(false);

  return (
    <SheetPartListTab<PartRow>
      rowKeyOf={(r) => r.materialNo}
      sortKeyMap={SORT_KEY_MAP}
      searchPlaceholder="按料号 / 品名搜索"
      configuredOptions={CONFIGURED_OPTIONS}
      emptyText="暂无有核价数据的料号"
      fetchParts={async (p) => {
        const r = await listParts({
          keyword: p.keyword,
          page: p.page,
          size: p.size,
          sortBy: p.sortBy as PartSortBy | undefined,
          sortOrder: p.sortOrder,
          configured: p.configured,
        });
        return { items: r.items ?? [], total: r.total ?? 0 };
      }}
      columns={({ orderOf, openDrawer }): ColumnsType<PartRow> => [
        {
          title: '品名',
          dataIndex: 'materialName',
          key: 'materialName',
          sorter: true,
          sortOrder: orderOf('materialName'),
          render: (v: string, row) => <a onClick={() => openDrawer(row)}>{v || '—'}</a>,
        },
        {
          title: '料号',
          dataIndex: 'materialNo',
          key: 'materialNo',
          width: 160,
          sorter: true,
          sortOrder: orderOf('materialNo'),
        },
        {
          title: '规格',
          dataIndex: 'specification',
          key: 'specification',
          width: 140,
          sorter: true,
          sortOrder: orderOf('specification'),
          render: (v) => v || '—',
        },
        {
          title: '尺寸',
          dataIndex: 'dimension',
          key: 'dimension',
          width: 140,
          sorter: true,
          sortOrder: orderOf('dimension'),
          render: (v) => v || '—',
        },
        {
          title: '已配置',
          key: 'configured', // 无 dataIndex —— 排序按 columnKey 映射为 configuredCount
          width: 120,
          sorter: true,
          sortOrder: orderOf('configured'),
          render: (_: unknown, row) => {
            const done = row.configuredCount >= row.totalSheets && row.totalSheets > 0;
            return (
              <Tag color={done ? 'green' : 'blue'}>
                {row.configuredCount}/{row.totalSheets}
              </Tag>
            );
          },
        },
        {
          title: '最近更新',
          dataIndex: 'lastUpdatedAt',
          key: 'lastUpdatedAt',
          width: 180,
          sorter: true,
          sortOrder: orderOf('lastUpdatedAt'),
          render: (v: string | null) => <Text type="secondary">{fmtTime(v)}</Text>,
        },
      ]}
      toolbarActions={() => (
        <Button type="primary" icon={<ImportOutlined />} onClick={() => setImportOpen(true)}>
          导入核价数据
        </Button>
      )}
    >
      {({ drawerOpen, activeRow, closeDrawer, refresh }) => (
        <>
          <PartCostingDrawer
            open={drawerOpen}
            materialNo={activeRow?.materialNo ?? null}
            onClose={closeDrawer}
          />

          {/* 导入完成后刷新列表（改造前壳页顶部按钮没有这一步，属顺手补齐的合理行为） */}
          <PricingBasicDataImportDrawer
            open={importOpen}
            onClose={() => { setImportOpen(false); refresh(); }}
          />
        </>
      )}
    </SheetPartListTab>
  );
};

export default PartCostingTab;
