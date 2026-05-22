import React, { useState } from 'react';
import { Tabs, Button, Drawer, Space, Empty } from 'antd';
import { DatabaseOutlined } from '@ant-design/icons';
import ProcessManagement from '../config/ProcessManagement';
import PartVersionPage from '../partversion/PartVersionPage';
import MaterialRecipeManagement from '../config/MaterialRecipeManagement';
import ConfigTemplateManagement from '../configtemplate/ConfigTemplateManagement';
import BasicDataConfig from '../basicdata/BasicDataConfig';

const MasterDataHubPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('process');
  const [basicOpen, setBasicOpen] = useState(false);

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h2 style={{ margin: 0 }}>主数据维护</h2>
        <Space>
          <Button icon={<DatabaseOutlined />} onClick={() => setBasicOpen(true)}>
            基础数据配置
          </Button>
        </Space>
      </div>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        destroyInactiveTabPane
        items={[
          { key: 'process', label: '工序', children: <ProcessManagement /> },
          { key: 'part', label: '料号', children: <PartVersionPage /> },
          {
            key: 'bom',
            label: 'BOM',
            children: <Empty description="BOM 模块开发中" style={{ padding: '60px 0' }} />,
          },
          { key: 'material', label: '材质', children: <MaterialRecipeManagement /> },
          { key: 'dataTemplate', label: '数据模板', children: <ConfigTemplateManagement /> },
        ]}
      />
      <Drawer
        title="基础数据配置"
        placement="right"
        width={1200}
        open={basicOpen}
        onClose={() => setBasicOpen(false)}
        destroyOnClose
      >
        <BasicDataConfig />
      </Drawer>
    </div>
  );
};

export default MasterDataHubPage;
