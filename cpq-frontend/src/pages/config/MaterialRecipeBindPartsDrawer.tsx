import React, { useEffect, useState } from 'react';
import { Drawer, Input, Switch, Space, Button, Table, Tag, message, Alert } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import {
  materialRecipeService,
  type MaterialRecipePart,
} from '../../services/materialRecipeService';

interface Props {
  open: boolean;
  recipeId: string;
  recipeCode: string;
  recipeName: string;
  onClose: () => void;
  onConfirmed: () => void;
}

/**
 * 「材质管理 → +绑定料号」子 Drawer.
 *
 * <p>搜 mat_part(可勾选"仅未绑")→ 多选 → 确认 → POST /material-recipes/{id}/bind-parts
 * <p>已绑到其他材质的料号会在选中时显示"转移自 XXX"警示标签
 */
const MaterialRecipeBindPartsDrawer: React.FC<Props> = ({
  open, recipeId, recipeCode, recipeName, onClose, onConfirmed,
}) => {
  const [q, setQ] = useState('');
  const [onlyUnbound, setOnlyUnbound] = useState(true);
  const [loading, setLoading] = useState(false);
  const [results, setResults] = useState<MaterialRecipePart[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) {
      setQ('');
      setOnlyUnbound(true);
      setResults([]);
      setSelectedKeys([]);
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    if (!q.trim()) { setResults([]); return; }
    setLoading(true);
    const t = setTimeout(() => {
      materialRecipeService.searchParts(q, { onlyUnbound, size: 100 })
        .then(setResults)
        .catch(() => setResults([]))
        .finally(() => setLoading(false));
    }, 300);
    return () => clearTimeout(t);
  }, [q, onlyUnbound, open]);

  const handleConfirm = async () => {
    if (selectedKeys.length === 0) {
      message.warning('请至少选择一个料号');
      return;
    }
    // 检查是否有料号绑定到其他材质 → 二次确认
    const transferFrom = results.filter(
      r => selectedKeys.includes(r.partNo) && r.materialRecipeId && r.materialRecipeId !== recipeId,
    );
    if (transferFrom.length > 0) {
      const ok = window.confirm(
        `有 ${transferFrom.length} 个料号已绑定到其他材质,确认转移到 "${recipeCode} ${recipeName}"?\n\n` +
        transferFrom.slice(0, 5).map(r => `• ${r.partNo} (当前: ${r.materialRecipeCode})`).join('\n') +
        (transferFrom.length > 5 ? `\n…还有 ${transferFrom.length - 5} 个` : ''),
      );
      if (!ok) return;
    }

    setSubmitting(true);
    try {
      const res = await materialRecipeService.bindParts(recipeId, selectedKeys);
      message.success(`已绑定 ${res.updated} 个料号到 ${recipeCode}`);
      onConfirmed();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? e?.message ?? '绑定失败');
    } finally {
      setSubmitting(false);
    }
  };

  const columns = [
    { title: '料号', dataIndex: 'partNo', key: 'partNo', width: 160,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '品名', dataIndex: 'partName', key: 'partName' },
    { title: '规格', dataIndex: 'specification', key: 'specification', width: 160 },
    {
      title: '当前材质',
      key: 'currentRecipe',
      width: 160,
      render: (_: unknown, r: MaterialRecipePart) => {
        if (!r.materialRecipeId) return <Tag color="default">未绑</Tag>;
        if (r.materialRecipeId === recipeId) return <Tag color="green">已绑本材质</Tag>;
        return <Tag color="orange">转移自 {r.materialRecipeCode}</Tag>;
      },
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

  return (
    <Drawer
      title={<>绑定料号到 <b>{recipeCode}</b> {recipeName}</>}
      open={open}
      onClose={onClose}
      width={900}
      placement="right"
      maskClosable={false}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button type="primary" loading={submitting} onClick={handleConfirm}>
              绑定 {selectedKeys.length > 0 ? `(${selectedKeys.length})` : ''}
            </Button>
          </Space>
        </div>
      }
    >
      <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <Input
          prefix={<SearchOutlined />}
          placeholder="搜索料号 / 品名 / 规格 / 尺寸…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          allowClear
          size="large"
        />
        <Space>
          <Switch checked={onlyUnbound} onChange={setOnlyUnbound} />
          <span style={{ color: '#666' }}>仅显示未绑材质的料号</span>
        </Space>
        {!q.trim() && (
          <Alert
            type="info"
            showIcon
            message="输入关键字开始搜索"
            description="搜索结果上限 100 条;关闭'仅未绑'可搜到已绑其他材质的料号(选中后会转移到本材质)。"
          />
        )}
        <Table
          rowKey="partNo"
          columns={columns}
          dataSource={results}
          loading={loading}
          pagination={false}
          scroll={{ y: 360 }}
          size="small"
          rowSelection={{
            selectedRowKeys: selectedKeys,
            onChange: (keys) => setSelectedKeys(keys as string[]),
          }}
        />
        <div style={{ color: '#888', fontSize: 12 }}>
          匹配 <b style={{ color: '#5c6bc0' }}>{results.length}</b> 条,已选 <b style={{ color: '#5c6bc0' }}>{selectedKeys.length}</b> 条
        </div>
      </Space>
    </Drawer>
  );
};

export default MaterialRecipeBindPartsDrawer;
