import React, { useState } from 'react';
import { Tabs } from 'antd';
import V6ProcessCrudTab from './V6ProcessCrudTab';
import MaterialRecipeManagement from '../config/MaterialRecipeManagement';
import ElementManagement from '../config/ElementManagement';
import DatasetPartListTab from './dataset/DatasetPartListTab';
import PlatingSchemeTab from './dataset/PlatingSchemeTab';

/**
 * 主数据维护壳页（task-0728 · F1）
 *
 * - 页签 6 项，顺序：材质 → 元素 → 工序 → 基础核价 → 详细核价 → 电镀方案，
 *   默认停在「材质」。
 *   （task-260902 · F-2/F-3 · AC-24 加两个；F-10 · AC-48 再加「电镀方案」并置于最后。
 *    task-260907 · F-1 · AC-1/AC-2：摘除原首位页签并把默认落点改到「材质」，
 *    其余 6 个的 key / label / children / 顺序一个字节未动。）
 * - 原「BOM」「数据模板」两个页签已摘除入口；
 *   ⚠️ 对应组件文件 `V6BomQueryTab.tsx` / `../configtemplate/ConfigTemplateManagement.tsx` **保留不删**，日后可挂回。
 * - 壳页顶部只留标题，不挂任何导入入口。
 */
const MasterDataHubPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('material');

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
