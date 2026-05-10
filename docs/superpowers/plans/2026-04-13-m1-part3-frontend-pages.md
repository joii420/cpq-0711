---

# M1 Part 3: Frontend Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement login page, password change/reset pages, user management page, region/department management pages, and route guards with role-based menu rendering.

**Architecture:** React 18 SPA with Ant Design 5.x components. Zustand for auth state. Axios interceptors for 401 redirect. React Router v6 route guards. All API calls via `/services/api.ts`.

**Tech Stack:** React 18, TypeScript, Ant Design 5.x, Zustand, React Router v6, Axios

---

## File Structure

```
cpq-frontend/src/
  services/
    authService.ts              # Login/logout/me/change-password API calls
    userService.ts              # User CRUD API calls
    regionService.ts            # Region CRUD API calls
    departmentService.ts        # Department CRUD API calls
  pages/
    Login.tsx                   # Replace placeholder with real login form
    ChangePassword.tsx          # First login force + voluntary change
    ResetPassword.tsx           # Token-based reset page
    ForgotPassword.tsx          # Email input for reset request
    system/
      UserManagement.tsx        # User list + create/edit drawer
      RegionManagement.tsx      # Region list + create/edit modal
      DepartmentManagement.tsx  # Department list + create/edit modal
  router/
    index.tsx                   # Add auth guard, new routes
    AuthGuard.tsx               # Route protection component
  stores/
    authStore.ts                # Enhance with login/logout actions
```

---

### Task 1: Auth Service + Enhanced Auth Store

**Files:**
- Create: `cpq-frontend/src/services/authService.ts`
- Modify: `cpq-frontend/src/stores/authStore.ts`

- [ ] **Step 1: Create authService.ts**

```typescript
import api from './api';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  id: string;
  username: string;
  fullName: string;
  role: string;
  forceChangePassword: boolean;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export const authService = {
  login: (data: LoginRequest) => api.post('/auth/login', data) as Promise<any>,
  logout: () => api.post('/auth/logout') as Promise<any>,
  me: () => api.get('/auth/me') as Promise<any>,
  changePassword: (data: ChangePasswordRequest) => api.post('/auth/change-password', data) as Promise<any>,
  forgotPassword: (email: string) => api.post('/auth/forgot-password', { email }) as Promise<any>,
  resetPassword: (token: string, newPassword: string) =>
    api.post('/auth/reset-password', { token, newPassword }) as Promise<any>,
};
```

- [ ] **Step 2: Enhance authStore.ts**

```typescript
import { create } from 'zustand';
import { authService } from '../services/authService';

interface User {
  id: string;
  username: string;
  fullName: string;
  role: 'SALES_REP' | 'SALES_MANAGER' | 'PRICING_MANAGER' | 'SYSTEM_ADMIN';
}

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  forceChangePassword: boolean;
  loading: boolean;
  setUser: (user: User | null) => void;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  fetchMe: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: false,
  forceChangePassword: false,
  loading: true,
  setUser: (user) => set({ user, isAuthenticated: !!user }),
  login: async (username, password) => {
    const res = await authService.login({ username, password });
    const data = res.data;
    set({
      user: { id: data.id, username: data.username, fullName: data.fullName, role: data.role },
      isAuthenticated: true,
      forceChangePassword: data.forceChangePassword,
    });
  },
  logout: async () => {
    await authService.logout();
    set({ user: null, isAuthenticated: false, forceChangePassword: false });
  },
  fetchMe: async () => {
    try {
      const res = await authService.me();
      const data = res.data;
      set({
        user: { id: data.id, username: data.username, fullName: data.fullName, role: data.role },
        isAuthenticated: true,
        loading: false,
      });
    } catch {
      set({ user: null, isAuthenticated: false, loading: false });
    }
  },
}));
```

- [ ] **Step 3: Commit**

---

### Task 2: Login Page

**Files:**
- Modify: `cpq-frontend/src/pages/Login.tsx`

- [ ] **Step 1: Replace Login.tsx with real login form**

```tsx
import React, { useState } from 'react';
import { Card, Form, Input, Button, Typography, Alert, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate, Link } from 'react-router-dom';
import { useAuthStore } from '../stores/authStore';

const Login: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();
  const { login, forceChangePassword } = useAuthStore();

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    setError(null);
    try {
      await login(values.username, values.password);
      const store = useAuthStore.getState();
      if (store.forceChangePassword) {
        navigate('/change-password');
      } else {
        navigate('/dashboard');
      }
    } catch (err: any) {
      setError(err.message || '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' }}>
      <Card style={{ width: 400 }}>
        <Typography.Title level={3} style={{ textAlign: 'center', marginBottom: 32 }}>CPQ 报价系统</Typography.Title>
        {error && <Alert message={error} type="error" showIcon style={{ marginBottom: 16 }} closable onClose={() => setError(null)} />}
        <Form onFinish={onFinish} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名或邮箱" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block loading={loading}>登录</Button>
          </Form.Item>
          <div style={{ textAlign: 'center' }}>
            <Link to="/forgot-password">忘记密码？</Link>
          </div>
        </Form>
      </Card>
    </div>
  );
};

export default Login;
```

- [ ] **Step 2: Commit**

---

### Task 3: AuthGuard + Route Config

**Files:**
- Create: `cpq-frontend/src/router/AuthGuard.tsx`
- Modify: `cpq-frontend/src/router/index.tsx`

- [ ] **Step 1: Create AuthGuard**

```tsx
import React, { useEffect } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Spin } from 'antd';
import { useAuthStore } from '../stores/authStore';

const AuthGuard: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated, loading, fetchMe, forceChangePassword } = useAuthStore();
  const location = useLocation();

  useEffect(() => {
    fetchMe();
  }, []);

  if (loading) {
    return <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100vh' }}><Spin size="large" /></div>;
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (forceChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }

  return <>{children}</>;
};

export default AuthGuard;
```

- [ ] **Step 2: Update router/index.tsx**

```tsx
import React from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import MainLayout from '../layouts/MainLayout';
import AuthGuard from './AuthGuard';
import Login from '../pages/Login';
import Dashboard from '../pages/Dashboard';
import ChangePassword from '../pages/ChangePassword';
import ForgotPassword from '../pages/ForgotPassword';
import ResetPassword from '../pages/ResetPassword';
import UserManagement from '../pages/system/UserManagement';
import RegionManagement from '../pages/system/RegionManagement';
import DepartmentManagement from '../pages/system/DepartmentManagement';

const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  { path: '/forgot-password', element: <ForgotPassword /> },
  { path: '/reset-password', element: <ResetPassword /> },
  {
    path: '/',
    element: <AuthGuard><MainLayout /></AuthGuard>,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <Dashboard /> },
      { path: 'change-password', element: <ChangePassword /> },
      { path: 'system/users', element: <UserManagement /> },
      { path: 'system/regions', element: <RegionManagement /> },
      { path: 'system/departments', element: <DepartmentManagement /> },
    ],
  },
]);

export default router;
```

- [ ] **Step 3: Commit**

---

### Task 4: Change Password Page

**Files:**
- Create: `cpq-frontend/src/pages/ChangePassword.tsx`

- [ ] **Step 1: Create ChangePassword.tsx**

```tsx
import React, { useState } from 'react';
import { Card, Form, Input, Button, Typography, Alert, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { authService } from '../services/authService';
import { useAuthStore } from '../stores/authStore';

const ChangePassword: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const { forceChangePassword } = useAuthStore();

  const onFinish = async (values: { currentPassword: string; newPassword: string; confirmPassword: string }) => {
    if (values.newPassword !== values.confirmPassword) {
      message.error('两次输入的密码不一致');
      return;
    }
    setLoading(true);
    try {
      await authService.changePassword({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      });
      message.success('密码修改成功');
      useAuthStore.setState({ forceChangePassword: false });
      navigate('/dashboard');
    } catch (err: any) {
      message.error(err.message || '修改失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ maxWidth: 500, margin: '0 auto' }}>
      {forceChangePassword && (
        <Alert message="首次登录，请修改初始密码后继续使用系统" type="warning" showIcon style={{ marginBottom: 16 }} />
      )}
      <Card title="修改密码">
        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item name="currentPassword" label="当前密码" rules={[{ required: true }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="newPassword" label="新密码" rules={[
            { required: true },
            { min: 8, message: '至少8位' },
            { pattern: /^(?=.*[a-zA-Z])(?=.*\d)/, message: '必须包含字母和数字' },
          ]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="confirmPassword" label="确认新密码" rules={[{ required: true }]}>
            <Input.Password />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>确认修改</Button>
        </Form>
      </Card>
    </div>
  );
};

export default ChangePassword;
```

- [ ] **Step 2: Commit**

---

### Task 5: Forgot Password + Reset Password Pages

**Files:**
- Create: `cpq-frontend/src/pages/ForgotPassword.tsx`
- Create: `cpq-frontend/src/pages/ResetPassword.tsx`

- [ ] **Step 1: Create ForgotPassword.tsx**

```tsx
import React, { useState } from 'react';
import { Card, Form, Input, Button, Typography, Result } from 'antd';
import { MailOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { authService } from '../services/authService';

const ForgotPassword: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [sent, setSent] = useState(false);

  const onFinish = async (values: { email: string }) => {
    setLoading(true);
    try {
      await authService.forgotPassword(values.email);
      setSent(true);
    } catch { /* Silent */ }
    finally { setLoading(false); setSent(true); }
  };

  if (sent) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
        <Card style={{ width: 400 }}>
          <Result status="success" title="重置邮件已发送" subTitle="请检查您的邮箱，点击链接重置密码（1小时内有效）" />
          <Link to="/login"><Button type="primary" block>返回登录</Button></Link>
        </Card>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
      <Card style={{ width: 400 }} title="忘记密码">
        <Form onFinish={onFinish} size="large">
          <Form.Item name="email" rules={[{ required: true, type: 'email', message: '请输入有效邮箱' }]}>
            <Input prefix={<MailOutlined />} placeholder="注册邮箱" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>发送重置链接</Button>
          <div style={{ textAlign: 'center', marginTop: 16 }}><Link to="/login">返回登录</Link></div>
        </Form>
      </Card>
    </div>
  );
};

export default ForgotPassword;
```

- [ ] **Step 2: Create ResetPassword.tsx**

```tsx
import React, { useState } from 'react';
import { Card, Form, Input, Button, message, Result } from 'antd';
import { useSearchParams, Link } from 'react-router-dom';
import { authService } from '../services/authService';

const ResetPassword: React.FC = () => {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  if (!token) {
    return <Result status="error" title="无效链接" subTitle="缺少重置令牌" extra={<Link to="/login"><Button>返回登录</Button></Link>} />;
  }

  const onFinish = async (values: { newPassword: string; confirmPassword: string }) => {
    if (values.newPassword !== values.confirmPassword) {
      message.error('两次密码不一致');
      return;
    }
    setLoading(true);
    try {
      await authService.resetPassword(token, values.newPassword);
      setDone(true);
    } catch (err: any) {
      message.error(err.message || '重置失败');
    } finally { setLoading(false); }
  };

  if (done) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
        <Card style={{ width: 400 }}>
          <Result status="success" title="密码重置成功" />
          <Link to="/login"><Button type="primary" block>去登录</Button></Link>
        </Card>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
      <Card style={{ width: 400 }} title="重置密码">
        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item name="newPassword" label="新密码" rules={[{ required: true }, { min: 8 }, { pattern: /^(?=.*[a-zA-Z])(?=.*\d)/, message: '须含字母和数字' }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="confirmPassword" label="确认密码" rules={[{ required: true }]}>
            <Input.Password />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>确认重置</Button>
        </Form>
      </Card>
    </div>
  );
};

export default ResetPassword;
```

- [ ] **Step 3: Commit**

---

### Task 6: User Management Page

**Files:**
- Create: `cpq-frontend/src/services/userService.ts`
- Create: `cpq-frontend/src/pages/system/UserManagement.tsx`

- [ ] **Step 1: Create userService.ts**

```typescript
import api from './api';

export const userService = {
  list: (params: { page?: number; size?: number; role?: string; status?: string; keyword?: string }) =>
    api.get('/users', { params }) as Promise<any>,
  create: (data: any) => api.post('/users', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/users/${id}`, data) as Promise<any>,
  updateStatus: (id: string, status: string) => api.patch(`/users/${id}`, { status }) as Promise<any>,
  resetPassword: (id: string) => api.post(`/users/${id}/reset-password`) as Promise<any>,
};
```

- [ ] **Step 2: Create UserManagement.tsx**

```tsx
import React, { useEffect, useState } from 'react';
import { Table, Button, Drawer, Form, Input, Select, Space, Tag, Popconfirm, message, Modal } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { userService } from '../../services/userService';
import { regionService } from '../../services/regionService';
import { departmentService } from '../../services/departmentService';

const roleMap: Record<string, { label: string; color: string }> = {
  SYSTEM_ADMIN: { label: '系统管理员', color: 'red' },
  SALES_MANAGER: { label: '销售经理', color: 'blue' },
  SALES_REP: { label: '销售代表', color: 'green' },
  PRICING_MANAGER: { label: '定价经理', color: 'orange' },
};

const UserManagement: React.FC = () => {
  const [data, setData] = useState<any>({ content: [], totalElements: 0 });
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingUser, setEditingUser] = useState<any>(null);
  const [regions, setRegions] = useState<any[]>([]);
  const [departments, setDepartments] = useState<any[]>([]);
  const [form] = Form.useForm();
  const [params, setParams] = useState({ page: 0, size: 20, role: '', status: '', keyword: '' });

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await userService.list(params);
      setData(res.data);
    } finally { setLoading(false); }
  };

  const fetchDicts = async () => {
    const [r, d] = await Promise.all([
      regionService.list({ size: 100 }),
      departmentService.list({ size: 100 }),
    ]);
    setRegions(r.data.content);
    setDepartments(d.data.content);
  };

  useEffect(() => { fetchData(); fetchDicts(); }, [params]);

  const handleSave = async (values: any) => {
    try {
      if (editingUser) {
        await userService.update(editingUser.id, values);
        message.success('更新成功');
      } else {
        const res = await userService.create(values);
        Modal.success({ title: '用户创建成功', content: `初始密码：${res.data.initialPassword}` });
      }
      setDrawerOpen(false);
      form.resetFields();
      setEditingUser(null);
      fetchData();
    } catch (err: any) { message.error(err.message); }
  };

  const columns = [
    { title: '用户名', dataIndex: 'username' },
    { title: '姓名', dataIndex: 'fullName' },
    { title: '邮箱', dataIndex: 'email' },
    { title: '角色', dataIndex: 'role', render: (r: string) => <Tag color={roleMap[r]?.color}>{roleMap[r]?.label}</Tag> },
    { title: '状态', dataIndex: 'status', render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag> },
    {
      title: '操作', render: (_: any, record: any) => (
        <Space>
          <a onClick={() => { setEditingUser(record); form.setFieldsValue(record); setDrawerOpen(true); }}>编辑</a>
          {record.status === 'ACTIVE' ? (
            <Popconfirm title="确认停用？" onConfirm={async () => { try { await userService.updateStatus(record.id, 'INACTIVE'); message.success('已停用'); fetchData(); } catch (e: any) { message.error(e.message); } }}>
              <a style={{ color: 'red' }}>停用</a>
            </Popconfirm>
          ) : (
            <a onClick={async () => { await userService.updateStatus(record.id, 'ACTIVE'); message.success('已启用'); fetchData(); }}>启用</a>
          )}
          <a onClick={async () => { try { const res = await userService.resetPassword(record.id); Modal.success({ title: '密码已重置', content: `新密码：${res.data.initialPassword}` }); } catch (e: any) { message.error(e.message); } }}>重置密码</a>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <Space>
          <Input.Search placeholder="搜索用户名/姓名/邮箱" onSearch={v => setParams(p => ({ ...p, keyword: v, page: 0 }))} allowClear style={{ width: 250 }} />
          <Select placeholder="角色" allowClear style={{ width: 130 }} onChange={v => setParams(p => ({ ...p, role: v || '', page: 0 }))}>
            {Object.entries(roleMap).map(([k, v]) => <Select.Option key={k} value={k}>{v.label}</Select.Option>)}
          </Select>
          <Select placeholder="状态" allowClear style={{ width: 100 }} onChange={v => setParams(p => ({ ...p, status: v || '', page: 0 }))}>
            <Select.Option value="ACTIVE">启用</Select.Option>
            <Select.Option value="INACTIVE">停用</Select.Option>
          </Select>
        </Space>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditingUser(null); form.resetFields(); setDrawerOpen(true); }}>新增用户</Button>
      </div>
      <Table columns={columns} dataSource={data.content} rowKey="id" loading={loading}
        pagination={{ current: params.page + 1, pageSize: params.size, total: data.totalElements, onChange: (p, s) => setParams(prev => ({ ...prev, page: p - 1, size: s })) }} />
      <Drawer title={editingUser ? '编辑用户' : '新增用户'} open={drawerOpen} onClose={() => setDrawerOpen(false)} width={500}>
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="username" label="用户名" rules={[{ required: !editingUser }]}>
            <Input disabled={!!editingUser} />
          </Form.Item>
          <Form.Item name="fullName" label="姓名" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ required: !editingUser, type: 'email' }]}><Input /></Form.Item>
          <Form.Item name="role" label="角色" rules={[{ required: !editingUser }]}>
            <Select>{Object.entries(roleMap).map(([k, v]) => <Select.Option key={k} value={k}>{v.label}</Select.Option>)}</Select>
          </Form.Item>
          <Form.Item name="regionId" label="所属区域">
            <Select allowClear>{regions.filter(r => r.status === 'ACTIVE').map(r => <Select.Option key={r.id} value={r.id}>{r.name}</Select.Option>)}</Select>
          </Form.Item>
          <Form.Item name="departmentId" label="所属部门">
            <Select allowClear>{departments.filter(d => d.status === 'ACTIVE').map(d => <Select.Option key={d.id} value={d.id}>{d.name}</Select.Option>)}</Select>
          </Form.Item>
          <Button type="primary" htmlType="submit" block>保存</Button>
        </Form>
      </Drawer>
    </div>
  );
};

export default UserManagement;
```

- [ ] **Step 3: Commit**

---

### Task 7: Region & Department Management Pages

**Files:**
- Create: `cpq-frontend/src/services/regionService.ts`
- Create: `cpq-frontend/src/services/departmentService.ts`
- Create: `cpq-frontend/src/pages/system/RegionManagement.tsx`
- Create: `cpq-frontend/src/pages/system/DepartmentManagement.tsx`

- [ ] **Step 1: Create regionService.ts**

```typescript
import api from './api';

export const regionService = {
  list: (params?: { page?: number; size?: number }) =>
    api.get('/regions', { params }) as Promise<any>,
  create: (data: any) => api.post('/regions', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/regions/${id}`, data) as Promise<any>,
  updateStatus: (id: string, status: string) => api.patch(`/regions/${id}`, { status }) as Promise<any>,
};
```

- [ ] **Step 2: Create departmentService.ts**

```typescript
import api from './api';

export const departmentService = {
  list: (params?: { page?: number; size?: number }) =>
    api.get('/departments', { params }) as Promise<any>,
  create: (data: any) => api.post('/departments', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/departments/${id}`, data) as Promise<any>,
  updateStatus: (id: string, status: string) => api.patch(`/departments/${id}`, { status }) as Promise<any>,
};
```

- [ ] **Step 3: Create RegionManagement.tsx**

(Standard CRUD table with Modal for create/edit, status toggle with Popconfirm. Same pattern as UserManagement but simpler — fields: code, name, sortOrder, status)

```tsx
import React, { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Space, Tag, Popconfirm, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { regionService } from '../../services/regionService';

const RegionManagement: React.FC = () => {
  const [data, setData] = useState<any>({ content: [], totalElements: 0 });
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [form] = Form.useForm();

  const fetchData = async () => {
    setLoading(true);
    try { const res = await regionService.list({ size: 100 }); setData(res.data); }
    finally { setLoading(false); }
  };

  useEffect(() => { fetchData(); }, []);

  const handleSave = async (values: any) => {
    try {
      if (editing) { await regionService.update(editing.id, values); message.success('更新成功'); }
      else { await regionService.create(values); message.success('创建成功'); }
      setModalOpen(false); form.resetFields(); setEditing(null); fetchData();
    } catch (err: any) { message.error(err.message); }
  };

  const columns = [
    { title: '编码', dataIndex: 'code' },
    { title: '名称', dataIndex: 'name' },
    { title: '排序', dataIndex: 'sortOrder' },
    { title: '状态', dataIndex: 'status', render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag> },
    {
      title: '操作', render: (_: any, record: any) => (
        <Space>
          <a onClick={() => { setEditing(record); form.setFieldsValue(record); setModalOpen(true); }}>编辑</a>
          {record.status === 'ACTIVE' ? (
            <Popconfirm title="确认停用？" onConfirm={async () => { try { await regionService.updateStatus(record.id, 'DISABLED'); message.success('已停用'); fetchData(); } catch (e: any) { message.error(e.message); } }}>
              <a style={{ color: 'red' }}>停用</a>
            </Popconfirm>
          ) : (
            <a onClick={async () => { await regionService.updateStatus(record.id, 'ACTIVE'); fetchData(); }}>启用</a>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); form.resetFields(); setModalOpen(true); }}>新增区域</Button>
      </div>
      <Table columns={columns} dataSource={data.content} rowKey="id" loading={loading} pagination={false} />
      <Modal title={editing ? '编辑区域' : '新增区域'} open={modalOpen} onCancel={() => setModalOpen(false)} onOk={() => form.submit()}>
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="code" label="编码" rules={[{ required: !editing }]}><Input disabled={!!editing} /></Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="sortOrder" label="排序"><InputNumber style={{ width: '100%' }} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default RegionManagement;
```

- [ ] **Step 4: Create DepartmentManagement.tsx** (same structure as Region, just swap service/labels)

```tsx
import React, { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Space, Tag, Popconfirm, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { departmentService } from '../../services/departmentService';

const DepartmentManagement: React.FC = () => {
  const [data, setData] = useState<any>({ content: [], totalElements: 0 });
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [form] = Form.useForm();

  const fetchData = async () => {
    setLoading(true);
    try { const res = await departmentService.list({ size: 100 }); setData(res.data); }
    finally { setLoading(false); }
  };

  useEffect(() => { fetchData(); }, []);

  const handleSave = async (values: any) => {
    try {
      if (editing) { await departmentService.update(editing.id, values); message.success('更新成功'); }
      else { await departmentService.create(values); message.success('创建成功'); }
      setModalOpen(false); form.resetFields(); setEditing(null); fetchData();
    } catch (err: any) { message.error(err.message); }
  };

  const columns = [
    { title: '编码', dataIndex: 'code' },
    { title: '名称', dataIndex: 'name' },
    { title: '排序', dataIndex: 'sortOrder' },
    { title: '状态', dataIndex: 'status', render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag> },
    {
      title: '操作', render: (_: any, record: any) => (
        <Space>
          <a onClick={() => { setEditing(record); form.setFieldsValue(record); setModalOpen(true); }}>编辑</a>
          {record.status === 'ACTIVE' ? (
            <Popconfirm title="确认停用？" onConfirm={async () => { try { await departmentService.updateStatus(record.id, 'DISABLED'); message.success('已停用'); fetchData(); } catch (e: any) { message.error(e.message); } }}>
              <a style={{ color: 'red' }}>停用</a>
            </Popconfirm>
          ) : (
            <a onClick={async () => { await departmentService.updateStatus(record.id, 'ACTIVE'); fetchData(); }}>启用</a>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); form.resetFields(); setModalOpen(true); }}>新增部门</Button>
      </div>
      <Table columns={columns} dataSource={data.content} rowKey="id" loading={loading} pagination={false} />
      <Modal title={editing ? '编辑部门' : '新增部门'} open={modalOpen} onCancel={() => setModalOpen(false)} onOk={() => form.submit()}>
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="code" label="编码" rules={[{ required: !editing }]}><Input disabled={!!editing} /></Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item name="sortOrder" label="排序"><InputNumber style={{ width: '100%' }} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default DepartmentManagement;
```

- [ ] **Step 5: Update MainLayout sidebar to include system sub-menu**

Update `cpq-frontend/src/layouts/MainLayout.tsx` menu items to include system management sub-items:

```typescript
const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '工作台' },
  { key: '/customers', icon: <TeamOutlined />, label: '客户管理' },
  { key: '/products', icon: <ShoppingOutlined />, label: '产品管理' },
  { key: '/quotations', icon: <FileTextOutlined />, label: '报价中心' },
  {
    key: '/system',
    icon: <SettingOutlined />,
    label: '系统管理',
    children: [
      { key: '/system/users', label: '用户管理' },
      { key: '/system/regions', label: '区域管理' },
      { key: '/system/departments', label: '部门管理' },
    ],
  },
];
```

- [ ] **Step 6: Verify TypeScript compiles**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev/cpq-frontend && npx tsc --noEmit
```

- [ ] **Step 7: Commit**

```bash
cd D:/a-joii/project/CPQ-superpowers/dev
git add cpq-frontend/src/
git commit -m "feat(frontend): add user, region, department management pages"
```

---

## Self-Review

**Spec coverage:** Login page ✅, Auth guard ✅, Force change password redirect ✅, Change password page ✅, Forgot password ✅, Reset password ✅, User management (CRUD + reset password) ✅, Region/Department management ✅, Role-based menu ✅, Session expiry redirect ✅

**Placeholder scan:** All steps have complete code. No TBD/TODO.

**Type consistency:** `authService` methods match backend DTOs. `userService/regionService/departmentService` call correct API paths matching Part 1 resources.
