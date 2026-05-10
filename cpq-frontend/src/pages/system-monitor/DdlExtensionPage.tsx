import React, { useState } from 'react';
import { Button, Typography, Space, Alert } from 'antd';
import { PlusOutlined, LockOutlined } from '@ant-design/icons';
import DdlHistoryList from './DdlHistoryList';
import DdlExtensionWizardDrawer from './DdlExtensionWizardDrawer';
import { useAuthStore } from '../../stores/authStore';

const { Title, Text } = Typography;

const DdlExtensionPage: React.FC = () => {
  const { user } = useAuthStore();
  const isAdmin = user?.role === 'SYSTEM_ADMIN';

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [refreshTrigger, setRefreshTrigger] = useState(0);

  const handleSuccess = () => {
    setRefreshTrigger((prev) => prev + 1);
  };

  return (
    <div style={{ padding: 24 }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          marginBottom: 20,
        }}
      >
        <div>
          <Title level={4} style={{ margin: 0 }}>
            DDL 扩列管理
          </Title>
          <Text type="secondary" style={{ fontSize: 13 }}>
            在业务白名单表中安全地扩展自定义列，操作将记录为 Flyway Migration 内容
          </Text>
        </div>
        <Space>
          {isAdmin ? (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setDrawerOpen(true)}
            >
              新建扩列
            </Button>
          ) : (
            <Button
              type="primary"
              icon={<LockOutlined />}
              disabled
              title="仅系统管理员可执行扩列操作"
            >
              新建扩列
            </Button>
          )}
        </Space>
      </div>

      {!isAdmin && (
        <Alert
          type="warning"
          showIcon
          message="权限提示"
          description="DDL 扩列操作仅系统管理员（SYSTEM_ADMIN）可执行。如需扩列，请联系系统管理员。"
          style={{ marginBottom: 20 }}
        />
      )}

      <div
        style={{
          background: '#fff',
          borderRadius: 8,
          padding: '20px 24px',
          boxShadow: '0 1px 4px rgba(0,0,0,0.06)',
        }}
      >
        <DdlHistoryList refreshTrigger={refreshTrigger} />
      </div>

      {isAdmin && (
        <DdlExtensionWizardDrawer
          open={drawerOpen}
          onClose={() => setDrawerOpen(false)}
          onSuccess={handleSuccess}
        />
      )}
    </div>
  );
};

export default DdlExtensionPage;
