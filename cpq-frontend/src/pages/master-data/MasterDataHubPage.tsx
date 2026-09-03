import React, { useState } from 'react';
import { Tabs } from 'antd';
import V6ProcessCrudTab from './V6ProcessCrudTab';
import MaterialRecipeManagement from '../config/MaterialRecipeManagement';
import ElementManagement from '../config/ElementManagement';
import PartCostingTab from './part-costing/PartCostingTab';
import DatasetPartListTab from './dataset/DatasetPartListTab';
import PlatingSchemeTab from './dataset/PlatingSchemeTab';

/**
 * 主数据维护壳页（task-0728 · F1）
 *
 * - 页签 7 项，顺序：料号核价 → 材质 → 元素 → 工序 → 基础核价 → 详细核价 → 电镀方案，
 *   默认停在「料号核价」。
 *   （task-260902 · F-2/F-3 · AC-24 加两个；F-10 · AC-48 再加「电镀方案」并置于最后。
 *    新增的三个一律挂在现有 4 个**之后**，现有 4 个零改动。）
 * - 原「BOM」「数据模板」两个页签已摘除入口；
 *   ⚠️ 对应组件文件 `V6BomQueryTab.tsx` / `../configtemplate/ConfigTemplateManagement.tsx` **保留不删**，日后可挂回。
 * - 原顶部「导入核价数据」按钮 + PricingBasicDataImportDrawer 已移入「料号核价」页签内部（F2），
 *   壳页顶部只留标题。
 */
const MasterDataHubPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('part-costing');

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <h2 style={{ margin: 0 }}>主数据维护</h2>
      </div>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        destroyInactiveTabPane
        items={[
          { key: 'part-costing', label: '料号核价', children: <PartCostingTab /> },
          { key: 'material', label: '材质', children: <MaterialRecipeManagement /> },
          { key: 'element', label: '元素', children: <ElementManagement /> },
          { key: 'process', label: '工序', children: <V6ProcessCrudTab /> },
          { key: 'cost-basic', label: '基础核价', children: <DatasetPartListTab dataset="cost-basic" /> },
          { key: 'cost-detail', label: '详细核价', children: <DatasetPartListTab dataset="cost-detail" /> },
          { key: 'plating-scheme', label: '电镀方案', children: <PlatingSchemeTab /> },
        ]}
      />
    </div>
  );
};

export default MasterDataHubPage;
