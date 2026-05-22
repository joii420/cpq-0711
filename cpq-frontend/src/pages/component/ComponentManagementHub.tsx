import React, { useState } from 'react';
import { Tabs, Button, Drawer, Space } from 'antd';
import { FunctionOutlined } from '@ant-design/icons';
import ComponentManagement from './ComponentManagement';
import DataSourceList from '../datasource/DataSourceList';
import GlobalVariablePage from '../global-variable/GlobalVariablePage';

const ComponentManagementHub: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('component');
  const [globalVarOpen, setGlobalVarOpen] = useState(false);

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h2 style={{ margin: 0 }}>组件管理</h2>
        <Space>
          <Button icon={<FunctionOutlined />} onClick={() => setGlobalVarOpen(true)}>
            全局变量配置
          </Button>
        </Space>
      </div>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        destroyInactiveTabPane
        items={[
          { key: 'component', label: '组件', children: <ComponentManagement /> },
          { key: 'datasource', label: '数据源', children: <DataSourceList /> },
        ]}
      />
      <Drawer
        title="全局变量配置"
        placement="right"
        width={1200}
        open={globalVarOpen}
        onClose={() => setGlobalVarOpen(false)}
        destroyOnClose
      >
        <GlobalVariablePage />
      </Drawer>
    </div>
  );
};

export default ComponentManagementHub;
