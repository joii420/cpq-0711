import React, { useState } from 'react';
import { Tabs, Button, Drawer, Space } from 'antd';
import { AppstoreOutlined } from '@ant-design/icons';
import InternalMaterialManagement from '../material/InternalMaterialManagement';
import ProductManagement from './ProductManagement';
import ProductCategoryManagement from '../basicdata/ProductCategoryManagement';

const ProductHubPage: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('master');
  const [categoryOpen, setCategoryOpen] = useState(false);

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h2 style={{ margin: 0 }}>产品管理</h2>
        <Space>
          <Button icon={<AppstoreOutlined />} onClick={() => setCategoryOpen(true)}>
            产品分类管理
          </Button>
        </Space>
      </div>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        destroyInactiveTabPane
        items={[
          { key: 'master', label: '产品主数据', children: <InternalMaterialManagement /> },
          { key: 'customer', label: '客户对应主数据', children: <ProductManagement /> },
        ]}
      />
      <Drawer
        title="产品分类管理"
        placement="right"
        width={960}
        open={categoryOpen}
        onClose={() => setCategoryOpen(false)}
        destroyOnClose
      >
        <ProductCategoryManagement />
      </Drawer>
    </div>
  );
};

export default ProductHubPage;
