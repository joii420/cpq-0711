import React, { useCallback, useEffect, useState } from 'react';
import { Input, Tag, Button, message, Space, Empty, Pagination } from 'antd';
import { PlusOutlined, DisconnectOutlined, SearchOutlined } from '@ant-design/icons';
import SelectableTable, { runBatch } from '../../components/SelectableTable';
import type { ToolbarAction } from '../../components/SelectableTable';
import {
  materialRecipeService,
  type MaterialRecipePart,
} from '../../services/materialRecipeService';
import MaterialRecipeBindPartsDrawer from './MaterialRecipeBindPartsDrawer';

interface Props {
  recipeId: string;
  recipeCode: string;
  recipeName: string;
  /** 父抽屉刷新外层列表的回调(绑定/解绑后联动刷新 boundPartsCount)*/
  onCountChanged?: () => void;
}

/**
 * 「材质管理 → 关联料号」Tab 组件.
 *
 * - 上方:搜索框 + "+绑定料号"按钮
 * - 中部:本材质下绑定的 mat_part 表(分页)
 * - 工具栏:"解绑选中"(把料号 material_recipe_id 置 NULL)
 *
 * "转移到其他材质"暂不在此 Tab 实现,改通过 "+绑定料号" Drawer 在目标材质处选已绑料号,语义等价.
 */
const MaterialRecipePartsTab: React.FC<Props> = ({ recipeId, recipeCode, recipeName, onCountChanged }) => {
  const [list, setList] = useState<MaterialRecipePart[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [bindDrawerOpen, setBindDrawerOpen] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const res = await materialRecipeService.listParts(recipeId, { keyword: keyword || undefined, page, size });
      setList(res.content ?? []);
      setTotal(res.totalElements ?? 0);
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  }, [recipeId, keyword, page, size]);

  useEffect(() => { refresh(); }, [refresh]);

  // keyword 变化时,重置到第 0 页(防止"页码越界 + 空列表"的卡死)
  useEffect(() => { setPage(0); }, [keyword]);

  const columns = [
    {
      title: '料号',
      dataIndex: 'partNo',
      key: 'partNo',
      width: 160,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    { title: '品名', dataIndex: 'partName', key: 'partName' },
    { title: '规格', dataIndex: 'specification', key: 'specification', width: 160 },
    { title: '尺寸', dataIndex: 'sizeInfo', key: 'sizeInfo', width: 120 },
    {
      title: '类型',
      dataIndex: 'productType',
      key: 'productType',
      width: 90,
      render: (v: string | null | undefined) =>
        v === 'COMPOSITE' ? <Tag color="purple">组合</Tag> :
        v === 'SIMPLE' ? <Tag color="blue">单一</Tag> :
        <Tag>—</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'statusCode',
      key: 'statusCode',
      width: 80,
      render: (s: string | null | undefined) => (
        <Tag color={s === 'Y' ? 'green' : 'red'}>{s === 'Y' ? '在产' : '停产'}</Tag>
      ),
    },
  ];

  const actions: ToolbarAction<MaterialRecipePart>[] = [
    {
      key: 'unbind',
      label: '解绑',
      icon: <DisconnectOutlined />,
      danger: true,
      enabledWhen: (rows) => rows.length === 0 ? '至少选 1 个料号' : true,
      needsConfirm: true,
      confirmTitle: '确认解除 {N} 个料号的材质绑定?',
      confirmDescription: '解绑后这些料号的 material_recipe_id 置为 NULL,选配 Step2 会走 BOM 派(只读)。',
      onClick: async (rows) => {
        const partNos = rows.map(r => r.partNo);
        try {
          const res = await materialRecipeService.unbindParts(recipeId, partNos);
          message.success(`已解绑 ${res.updated} 个料号`);
          refresh();
          onCountChanged?.();
        } catch (e: any) {
          message.error(e?.response?.data?.message ?? e?.message ?? '解绑失败');
        }
      },
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }}>
        <Input
          prefix={<SearchOutlined />}
          placeholder="搜索料号/品名/规格"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          allowClear
          style={{ width: 320 }}
        />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setBindDrawerOpen(true)}>
          绑定料号
        </Button>
      </Space>

      <SelectableTable<MaterialRecipePart>
        rowKey="partNo"
        columns={columns}
        dataSource={list}
        loading={loading}
        pagination={false}
        actions={actions}
        rowLabel={(r) => `${r.partNo} ${r.partName ?? ''}`}
        locale={{
          emptyText: keyword
            ? <Empty description={`未匹配到含 "${keyword}" 的料号`} />
            : <Empty description="该材质下暂无关联料号" />,
        }}
      />

      <div style={{ marginTop: 12, textAlign: 'right' }}>
        <Pagination
          current={page + 1}
          pageSize={size}
          total={total}
          showSizeChanger
          showTotal={(t) => `共 ${t} 条`}
          onChange={(p, s) => { setPage(p - 1); setSize(s); }}
        />
      </div>

      <MaterialRecipeBindPartsDrawer
        open={bindDrawerOpen}
        recipeId={recipeId}
        recipeCode={recipeCode}
        recipeName={recipeName}
        onClose={() => setBindDrawerOpen(false)}
        onConfirmed={() => { setBindDrawerOpen(false); refresh(); onCountChanged?.(); }}
      />
    </div>
  );
};

export default MaterialRecipePartsTab;
