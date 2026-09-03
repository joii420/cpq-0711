/**
 * 删除含量配置的二次确认（task-260901 · F-3）——**二级 Drawer**，对照 `原型图/3-含量配置抽屉.html` 状态 D。
 *
 * `frontend.md §1.2`：危险动作走弹层并**逐条列出所选项**，不用零散的行内确认。
 * 弹层内必须写明四条后果（编号不回收 / 已有报价单不受影响 / 重导不复活 / 实为停用）。
 * → 服务 AC-15
 */
import React, { useState } from 'react';
import { Drawer, Button, Space, Alert, Table, message } from 'antd';
import {
  materialRecipeService,
  type CompositionItem,
  type MaterialRecipeConfig,
} from '../../services/materialRecipeService';
import { formatPctText } from '../../utils/precision';

interface Props {
  open: boolean;
  recipeId: string;
  recipeCode: string;
  recipeSymbol: string;
  composition: CompositionItem[];
  /** 待删除的配置（工具栏「删除」要求恰好勾选 1 条，这里仍按数组渲染以保持「逐条列出」语义） */
  targets: MaterialRecipeConfig[];
  onClose: () => void;
  onDeleted: () => void;
}

const MaterialRecipeConfigDeleteDrawer: React.FC<Props> = ({
  open, recipeId, recipeCode, recipeSymbol, composition, targets, onClose, onDeleted,
}) => {
  const [deleting, setDeleting] = useState(false);

  const orderedComposition = [...composition].sort((a, b) => a.sortOrder - b.sortOrder);

  const columns = [
    { title: '配置编号', dataIndex: 'configNo', key: 'configNo', width: 120 },
    ...orderedComposition.map((c) => ({
      title: c.elementCode,
      key: `el-${c.elementNo}`,
      align: 'right' as const,
      render: (_: unknown, r: MaterialRecipeConfig) => {
        const hit = r.elements.find((e) => (e.elementNo ?? e.elementCode) === c.elementNo);
        return hit ? `${formatPctText(hit.defaultPct)}%` : '—';
      },
    })),
    {
      title: '合计',
      key: 'total',
      align: 'right' as const,
      render: (_: unknown, r: MaterialRecipeConfig) => `${formatPctText(r.totalPct)}%`,
    },
  ];

  const handleDelete = async () => {
    setDeleting(true);
    try {
      for (const t of targets) {
        await materialRecipeService.deleteConfig(recipeId, t.id);
      }
      message.success(`已删除 ${targets.length} 条含量配置`);
      onDeleted();
    } catch (e: any) {
      message.error(e?.message ?? '删除失败');
    } finally {
      setDeleting(false);
    }
  };

  return (
    <Drawer
      title="删除含量配置"
      open={open}
      onClose={onClose}
      width={560}
      placement="right"
      maskClosable={false}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button danger type="primary" loading={deleting} onClick={handleDelete}>
              确认删除
            </Button>
          </Space>
        </div>
      }
    >
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
        message={
          <span>
            将删除材质 <b>{recipeCode} / {recipeSymbol}</b> 下的以下 {targets.length} 条配置：
          </span>
        }
      />
      <Table<MaterialRecipeConfig>
        rowKey="id"
        size="small"
        pagination={false}
        dataSource={targets}
        columns={columns as any}
        scroll={{ x: 'max-content' }}
      />
      <div
        style={{
          marginTop: 14,
          borderLeft: '3px solid #1677ff',
          background: '#f0f5ff',
          padding: '10px 14px',
          borderRadius: '0 6px 6px 0',
          fontSize: 12,
          color: 'rgba(0,0,0,.65)',
          lineHeight: 1.9,
        }}
      >
        <b style={{ color: 'rgba(0,0,0,.88)' }}>删除后会发生什么：</b>
        <br />• 该配置从配置矩阵与选配下拉中消失（<b>实为停用</b>，物理行保留）
        <br />• <b>编号不会被回收</b> —— 下次新建拿到的是下一个序号，而不是刚删掉的那个
        <br />• <b>已引用它的报价单不受影响</b> —— 选配时含量已写入报价单自身的元素 BOM
        <br />• 重新导入相同含量的 Excel <b>不会复活</b>这条，会新建一条编号不同、内容相同的配置
      </div>
    </Drawer>
  );
};

export default MaterialRecipeConfigDeleteDrawer;
