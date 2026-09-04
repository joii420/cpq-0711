// ─────────────────────────────────────────────────────────────────────────────
// ProductHubPage —— 产品管理壳页（task-260903 · F-1，重写）
//
// 两个页签 [客户产品][销售产品]，默认选中**客户产品**（AC-1）。
// 路由 `/products-hub` 不变（书签 / 直链 / E2E 不挂）。
//
// 摘除说明（需求文档 ④ R-5，用户在闸门 A 已确认接受 R-1）：
//   旧的「产品主数据」(InternalMaterialManagement) / 「客户对应主数据」(ProductManagement)
//   两个页签**只摘 UI 入口**，两个组件文件 + 后端端点 + 表**全部保留不删** ——
//   `product` 被 quotation_line_item / product_template_binding / product_process 三张表外键引用，
//   `material_master` 仍被 V6 导入链路写入。删表属 CLAUDE.md §3.2 红线，须单独立项。
//
// 🚫 **不许顺手删「产品分类管理」** —— `product_category` 被 `customer.product_category_id` 引用，
//    报价 / 核价 / 选配三套模板按客户产品分类匹配（task-0712），删则断链。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useState } from 'react';
import { Tabs, Button, Drawer, Space } from 'antd';
import { AppstoreOutlined } from '@ant-design/icons';
import ProductCategoryManagement from '../basicdata/ProductCategoryManagement';
import ProductCustomerPartTab from './ProductCustomerPartTab';
import ProductSalesPartTab from './ProductSalesPartTab';

const ProductHubPage: React.FC = () => {
  // AC-1：默认选中「客户产品」
  const [activeTab, setActiveTab] = useState<string>('customer');
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
        // 销毁非活动页签：两个列表各自服务端分页，同时挂载会重复发请求
        // （antd 6 已把 destroyInactiveTabPane 标为 deprecated，用等价的 destroyOnHidden，
        //   否则 dev 下 antd 会打一条 console.error 级弃用告警，违反 AC-11「console 无 error」）
        destroyOnHidden
        items={[
          { key: 'customer', label: '客户产品', children: <ProductCustomerPartTab /> },
          { key: 'sales', label: '销售产品', children: <ProductSalesPartTab /> },
        ]}
      />
      <Drawer
        title="产品分类管理"
        placement="right"
        // antd 6：width 已弃用，改用等价的 size（数值语义不变，仍是 960px）
        size={960}
        open={categoryOpen}
        onClose={() => setCategoryOpen(false)}
        destroyOnHidden
      >
        <ProductCategoryManagement />
      </Drawer>
    </div>
  );
};

export default ProductHubPage;
