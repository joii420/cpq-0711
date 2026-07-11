import React, { useState } from 'react';
import { Tabs } from 'antd';
import ComponentManagement from './ComponentManagement';
import DataSourceList from '../datasource/DataSourceList';
import GlobalVariablePage from '../global-variable/GlobalVariablePage';
import CostingBomTreeConfigTab from './CostingBomTreeConfigTab';

const ComponentManagementHub: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('component');

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <h2 style={{ margin: 0 }}>组件管理</h2>
      </div>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        destroyInactiveTabPane
        items={[
          { key: 'component', label: '组件', children: <ComponentManagement /> },
          { key: 'datasource', label: '数据源', children: <DataSourceList /> },
          { key: 'global-variable', label: '全局变量', children: <GlobalVariablePage /> },
          { key: 'costing-bom-tree', label: '核价树配置', children: <CostingBomTreeConfigTab /> },
        ]}
      />
    </div>
  );
};

export default ComponentManagementHub;
