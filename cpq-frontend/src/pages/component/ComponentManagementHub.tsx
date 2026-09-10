import React, { useState } from 'react';
import { Tabs } from 'antd';
import ComponentManagement from './ComponentManagement';
import DataSourceList from '../datasource/DataSourceList';
import GlobalVariablePage from '../global-variable/GlobalVariablePage';
import CostingBomTreeConfigTab from './CostingBomTreeConfigTab';
import { useAuthStore } from '../../stores/authStore';

type Role = 'SALES_REP' | 'SALES_MANAGER' | 'PRICING_MANAGER' | 'SYSTEM_ADMIN';

const ComponentManagementHub: React.FC = () => {
  const [activeTab, setActiveTab] = useState<string>('component');

  // task-260909 F-4（AC-17）：「核价树配置」仅 SYSTEM_ADMIN 可见。
  // 角色取法与 src/layouts/MainLayout.tsx:157 同款（`(user?.role || 'SALES_REP') as Role`），
  // 🚫 不另造一套角色判定。
  // ⚠️ 前端隐藏不构成权限 —— 后端 CostingBomTreeConfigResource 类级角色同步收紧为
  //    {SYSTEM_ADMIN}（api.md §2 / backtask.md B-8），两处都改才算这条做完。
  const { user } = useAuthStore();
  const userRole = (user?.role || 'SALES_REP') as Role;
  const isSystemAdmin = userRole === 'SYSTEM_ADMIN';

  // 非管理员：该 tab **整项不进 items 数组**（不是渲染出来再隐藏、也不是渲染成禁用态）。
  const items = [
    { key: 'component', label: '组件', children: <ComponentManagement /> },
    { key: 'datasource', label: '数据源', children: <DataSourceList /> },
    { key: 'global-variable', label: '全局变量', children: <GlobalVariablePage /> },
    ...(isSystemAdmin
      ? [{ key: 'costing-bom-tree', label: '核价树配置', children: <CostingBomTreeConfigTab /> }]
      : []),
  ];

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <h2 style={{ margin: 0 }}>组件管理</h2>
      </div>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        destroyInactiveTabPane
        items={items}
      />
    </div>
  );
};

export default ComponentManagementHub;
