import React, { useEffect, useState } from 'react';
import { Drawer, Tabs, Button, message } from 'antd';
import { elementPriceStrategyService } from '../../services/elementPriceStrategyService';
import type { PriceSourceDTO } from '../../types/element-price-strategy';
import PriceDetailTab from './PriceDetailTab';
import PriceMatrixTab from './PriceMatrixTab';

/**
 * 元素价格表抽屉（1200，两个 Tab：明细 / 矩阵） —— task-0722 · F5
 */
interface Props {
  open: boolean;
  onClose: () => void;
}

const ElementPriceTableDrawer: React.FC<Props> = ({ open, onClose }) => {
  const [sources, setSources] = useState<PriceSourceDTO[]>([]);
  const [activeKey, setActiveKey] = useState('detail');

  useEffect(() => {
    if (!open) return;
    setActiveKey('detail');
    // 查看用途，不限启用态：历史数据可能来自已停用的源
    elementPriceStrategyService.listSources()
      .then(setSources)
      .catch((e: any) => message.error(e?.message ?? '价格源加载失败'));
  }, [open]);

  return (
    <Drawer
      title="元素价格表"
      open={open}
      onClose={onClose}
      width={1200}
      placement="right"
      destroyOnClose
      footer={<div style={{ textAlign: 'right' }}><Button onClick={onClose}>关闭</Button></div>}
    >
      <Tabs
        activeKey={activeKey}
        onChange={setActiveKey}
        items={[
          { key: 'detail', label: '明细', children: <PriceDetailTab active={activeKey === 'detail'} sources={sources} /> },
          { key: 'matrix', label: '矩阵', children: <PriceMatrixTab sources={sources} /> },
        ]}
      />
    </Drawer>
  );
};

export default ElementPriceTableDrawer;
