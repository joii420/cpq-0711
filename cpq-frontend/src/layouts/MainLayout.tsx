import React, { useEffect, useRef, useState } from 'react';
import { Layout, Menu, Avatar, Dropdown, Badge, Popover, List, Button, Typography } from 'antd';
import {
  UserOutlined,
  TeamOutlined,
  ShoppingOutlined,
  FileTextOutlined,
  SettingOutlined,
  BellOutlined,
  LogoutOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  AppstoreOutlined,
  PercentageOutlined,
  CheckOutlined,
  SunOutlined,
  MoonOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../stores/authStore';
import { useThemeStore } from '../stores/themeStore';
import { notificationService } from '../services/notificationService';

const { Sider, Header, Content } = Layout;
const { Text } = Typography;

// PRD 1.4 权限矩阵 — 每个菜单项标注可见角色
// SALES_REP=销售代表, SALES_MANAGER=销售经理, PRICING_MANAGER=定价经理, SYSTEM_ADMIN=系统管理员
type Role = 'SALES_REP' | 'SALES_MANAGER' | 'PRICING_MANAGER' | 'SYSTEM_ADMIN';
const ALL_ROLES: Role[] = ['SALES_REP', 'SALES_MANAGER', 'PRICING_MANAGER', 'SYSTEM_ADMIN'];

interface MenuItem {
  key: string;
  icon?: React.ReactNode;
  label: string;
  roles: Role[];
  children?: MenuItem[];
}

// 2026-05-21 菜单聚合压缩: 详见菜单调整方案 PR
// - 产品管理 = 壳页 /products-hub (Tab1 产品主数据 = InternalMaterialManagement / Tab2 客户对应主数据 = ProductManagement)
// - 报价中心 = 报价单管理 + 核价管理 (核价单从配置中心搬到这里)
// - 定价管理 = 定价策略 + 审批流 (审批规则从系统管理搬到这里, 路由不变)
// - 配置中心 = 组件管理 (内嵌数据源 Tab + 全局变量按钮) + 模板管理
// - 主数据维护 = 壳页 /master-data-hub (工序/料号/材质/数据模板 4 Tab + 基础数据配置按钮 + 产品分类按钮)
// - 已移除菜单 (路由全保留以保证书签/直链/E2E 不挂):
//   料号级核价数据 / 产品模板绑定 / 模板版本对比 / 基础数据配置 / 业务标签字典 /
//   材质管理 / 工序管理 / 配置模板 / 数据总览 / 主表查看 / 历史版本 / 料号版本管理 /
//   字段重要性 / 变更日志 / 元素价格中心 / 系统配置中心 / 锁监控 / DDL扩列管理 /
//   导入历史 / 数据源管理 (并入组件管理) / 全局变量配置 (并入组件管理) /
//   产品分类管理 (并入产品管理按钮)
const allMenuItems: MenuItem[] = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '工作台', roles: ALL_ROLES },
  { key: '/customers', icon: <TeamOutlined />, label: '客户管理', roles: ['SALES_REP', 'SALES_MANAGER', 'PRICING_MANAGER', 'SYSTEM_ADMIN'] },
  { key: '/products-hub', icon: <ShoppingOutlined />, label: '产品管理', roles: ['SALES_REP', 'SALES_MANAGER', 'PRICING_MANAGER', 'SYSTEM_ADMIN'] },
  {
    key: '/quotation-center',
    icon: <FileTextOutlined />,
    label: '报价中心',
    roles: ALL_ROLES,
    children: [
      { key: '/quotations', label: '报价单管理', roles: ALL_ROLES },
      { key: '/costing-summary', label: '核价管理', roles: ['PRICING_MANAGER', 'SALES_MANAGER', 'SYSTEM_ADMIN'] },
    ],
  },
  {
    key: '/pricing-mgmt',
    icon: <PercentageOutlined />,
    label: '定价管理',
    roles: ['SALES_REP', 'SALES_MANAGER', 'PRICING_MANAGER', 'SYSTEM_ADMIN'],
    children: [
      { key: '/pricing', label: '定价策略', roles: ['SALES_REP', 'SALES_MANAGER', 'PRICING_MANAGER', 'SYSTEM_ADMIN'] },
      { key: '/system/approval-rules', label: '审批流', roles: ['SYSTEM_ADMIN'] },
    ],
  },
  {
    key: '/config',
    icon: <AppstoreOutlined />,
    label: '配置中心',
    roles: ['SALES_MANAGER', 'SYSTEM_ADMIN'],
    children: [
      { key: '/components', label: '组件管理', roles: ['SALES_MANAGER', 'SYSTEM_ADMIN'] },
      { key: '/templates', label: '模板管理', roles: ['SALES_REP', 'SALES_MANAGER', 'PRICING_MANAGER', 'SYSTEM_ADMIN'] },
    ],
  },
  { key: '/master-data-hub', icon: <DatabaseOutlined />, label: '主数据维护', roles: ['SALES_MANAGER', 'SYSTEM_ADMIN'] },
  {
    key: '/configurator-hub',
    icon: <ShoppingOutlined />,
    label: '🛒 3D 选配',
    roles: ['SALES_REP', 'SALES_MANAGER', 'PRICING_MANAGER', 'SYSTEM_ADMIN'],
    children: [
      // 销售路径
      { key: '/configurator/instances', label: '📋 选配实例列表', roles: ['SALES_REP', 'SALES_MANAGER', 'PRICING_MANAGER', 'SYSTEM_ADMIN'] },
      { key: '/configurator/start', label: '🎯 开始选配', roles: ['SALES_REP', 'SALES_MANAGER', 'PRICING_MANAGER', 'SYSTEM_ADMIN'] },
      { key: '/configurator/shares', label: '🔗 我分享的链接', roles: ['SALES_REP', 'SALES_MANAGER', 'PRICING_MANAGER', 'SYSTEM_ADMIN'] },
      { key: '/system/customer-leads', label: '📩 客户线索', roles: ['SALES_REP', 'SALES_MANAGER', 'SYSTEM_ADMIN'] },
      // 管理资源
      { key: '/system/configurator-templates', label: '🛒 选配模板管理', roles: ['SYSTEM_ADMIN', 'PRICING_MANAGER'] },
      { key: '/system/feature-library', label: '📚 特征库管理', roles: ['SYSTEM_ADMIN', 'PRICING_MANAGER'] },
      { key: '/system/part-models', label: '📦 3D 源文件管理', roles: ['SYSTEM_ADMIN', 'PRICING_MANAGER'] },
    ],
  },
  {
    key: '/system',
    icon: <SettingOutlined />,
    label: '系统管理',
    roles: ['SYSTEM_ADMIN'],
    children: [
      { key: '/system/users', label: '用户管理', roles: ['SYSTEM_ADMIN'] },
      { key: '/system/regions', label: '区域管理', roles: ['SYSTEM_ADMIN'] },
      { key: '/system/departments', label: '部门管理', roles: ['SYSTEM_ADMIN'] },
      { key: '/system/notifications', label: '通知列表', roles: ALL_ROLES },
      { key: '/system/operation-logs', label: '操作日志', roles: ['SALES_MANAGER', 'SYSTEM_ADMIN'] },
    ],
  },
];

const MainLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuthStore();
  const { mode: themeMode, toggle: toggleTheme } = useThemeStore();
  const userRole = (user?.role || 'SALES_REP') as Role;

  // Filter menu items by current user role
  const filterMenuByRole = (items: MenuItem[]): any[] => {
    return items
      .filter((item) => item.roles.includes(userRole))
      .map((item) => {
        const { roles: _roles, ...rest } = item;
        if (item.children) {
          const filteredChildren = filterMenuByRole(item.children);
          if (filteredChildren.length === 0) return null;
          return { ...rest, children: filteredChildren };
        }
        return rest;
      })
      .filter(Boolean);
  };

  const menuItems = filterMenuByRole(allMenuItems);
  const [unreadCount, setUnreadCount] = useState(0);
  const [notifications, setNotifications] = useState<any[]>([]);
  const [bellOpen, setBellOpen] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchUnreadCount = async () => {
    try {
      const res = await notificationService.getUnreadCount();
      setUnreadCount(res.data?.count ?? 0);
    } catch {
      // silently fail — user may not be logged in yet
    }
  };

  const fetchNotifications = async () => {
    try {
      const res = await notificationService.list({ page: 0, size: 5 });
      setNotifications(res.data || []);
    } catch {
      // silently fail
    }
  };

  useEffect(() => {
    fetchUnreadCount();
    pollRef.current = setInterval(fetchUnreadCount, 30000);
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, []);

  const handleBellOpen = (open: boolean) => {
    setBellOpen(open);
    if (open) {
      fetchNotifications();
    }
  };

  const handleMarkAllRead = async () => {
    try {
      await notificationService.markAllRead();
      setUnreadCount(0);
      fetchNotifications();
    } catch {
      // silently fail
    }
  };

  const notificationContent = (
    <div style={{ width: 340 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <Text strong>通知</Text>
        <Button size="small" type="link" icon={<CheckOutlined />} onClick={handleMarkAllRead}>
          全部已读
        </Button>
      </div>
      <List
        size="small"
        dataSource={notifications}
        locale={{ emptyText: '暂无通知' }}
        renderItem={(item: any) => (
          <List.Item style={{ padding: '6px 0' }}>
            <div style={{ width: '100%' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <Text strong={!item.isRead} style={{ fontSize: 13 }}>{item.title}</Text>
                {!item.isRead && (
                  <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#1677ff', display: 'inline-block', marginTop: 4, flexShrink: 0 }} />
                )}
              </div>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {item.createdAt ? new Date(item.createdAt).toLocaleString() : ''}
              </Text>
            </div>
          </List.Item>
        )}
      />
      <div style={{ textAlign: 'center', marginTop: 8 }}>
        <Button type="link" size="small" onClick={() => { setBellOpen(false); navigate('/system/notifications'); }}>
          查看全部通知
        </Button>
      </div>
    </div>
  );

  const userMenu = {
    items: [
      { key: 'profile', label: '修改密码', icon: <UserOutlined /> },
      { type: 'divider' as const },
      { key: 'logout', label: '退出登录', icon: <LogoutOutlined />, danger: true },
    ],
    onClick: ({ key }: { key: string }) => {
      if (key === 'logout') {
        logout();
        navigate('/login');
      } else if (key === 'profile') {
        navigate('/change-password');
      }
    },
  };

  return (
    <Layout style={{ height: '100vh', overflow: 'hidden' }}>
      <Sider
        width={220}
        theme="dark"
        style={{
          position: 'fixed',
          left: 0,
          top: 0,
          bottom: 0,
          overflow: 'auto',
          zIndex: 100,
        }}
      >
        <div style={{ height: 64, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <span style={{ color: '#fff', fontSize: 18, fontWeight: 600 }}>CPQ 报价系统</span>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout style={{ marginLeft: 220 }}>
        <Header
          style={{
            background: themeMode === 'dark' ? '#1f1f1f' : '#fff',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            gap: 16,
            position: 'sticky',
            top: 0,
            zIndex: 99,
            boxShadow: themeMode === 'dark' ? '0 1px 4px rgba(0,0,0,0.3)' : '0 1px 4px rgba(0,0,0,0.08)',
          }}
        >
          <Button
            type="text"
            icon={themeMode === 'dark' ? <SunOutlined /> : <MoonOutlined />}
            onClick={toggleTheme}
            title={themeMode === 'dark' ? '切换到浅色模式' : '切换到深色模式'}
            style={{ fontSize: 18 }}
          />
          <Popover
            content={notificationContent}
            trigger="click"
            open={bellOpen}
            onOpenChange={handleBellOpen}
            placement="bottomRight"
          >
            <Badge count={unreadCount} size="small" style={{ cursor: 'pointer' }}>
              <BellOutlined style={{ fontSize: 18, cursor: 'pointer' }} />
            </Badge>
          </Popover>
          <Dropdown menu={userMenu} placement="bottomRight">
            <span style={{ cursor: 'pointer' }}>
              <Avatar size="small" icon={<UserOutlined />} style={{ marginRight: 8 }} />
              {user?.fullName || 'User'}
            </span>
          </Dropdown>
        </Header>
        <Content style={{ margin: 24, padding: 24, background: themeMode === 'dark' ? '#1f1f1f' : '#fff', borderRadius: 8, minHeight: 280, overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
};

export default MainLayout;
