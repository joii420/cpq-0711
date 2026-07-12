import React, { useState } from 'react';
import { Tabs, Button, Space } from 'antd';
import { ImportOutlined } from '@ant-design/icons';
import V6ProcessCrudTab from './V6ProcessCrudTab';
import V6BomQueryTab from './V6BomQueryTab';
import PartVersionPage from '../partversion/PartVersionPage';
import MaterialRecipeManagement from '../config/MaterialRecipeManagement';
import ElementManagement from '../config/ElementManagement';
import ConfigTemplateManagement from '../configtemplate/ConfigTemplateManagement';
import PricingBasicDataImportDrawer from './PricingBasicDataImportDrawer';
import PartCostingTab from './part-costing/PartCostingTab';

const MasterDataHubPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('process');
  const [pricingImportOpen, setPricingImportOpen] = useState(false);

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h2 style={{ margin: 0 }}>主数据维护</h2>
        <Space>
          <Button type="primary" icon={<ImportOutlined />} onClick={() => setPricingImportOpen(true)}>
            导入核价数据
          </Button>
        </Space>
      </div>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        destroyInactiveTabPane
        items={[
          { key: 'process', label: '工序', children: <V6ProcessCrudTab /> },
          { key: 'part', label: '料号', children: <PartVersionPage /> },
          { key: 'bom', label: 'BOM', children: <V6BomQueryTab /> },
          { key: 'material', label: '材质', children: <MaterialRecipeManagement /> },
          { key: 'element', label: '元素', children: <ElementManagement /> },
          { key: 'dataTemplate', label: '数据模板', children: <ConfigTemplateManagement /> },
          { key: 'part-costing', label: '料号核价', children: <PartCostingTab /> },
        ]}
      />
      <PricingBasicDataImportDrawer
        open={pricingImportOpen}
        onClose={() => setPricingImportOpen(false)}
      />
    </div>
  );
};

export default MasterDataHubPage;
