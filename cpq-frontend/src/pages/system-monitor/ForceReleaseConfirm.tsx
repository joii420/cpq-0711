import React from 'react';
import { Button, Popconfirm } from 'antd';
import { UnlockOutlined } from '@ant-design/icons';

interface Props {
  onConfirm: () => void | Promise<void>;
  description: string;
  loading?: boolean;
}

const ForceReleaseConfirm: React.FC<Props> = ({ onConfirm, description, loading }) => {
  return (
    <Popconfirm
      title="强制释放锁"
      description={description}
      okText="确认释放"
      cancelText="取消"
      okButtonProps={{ danger: true }}
      onConfirm={onConfirm}
    >
      <Button
        type="link"
        size="small"
        danger
        icon={<UnlockOutlined />}
        loading={loading}
      >
        强制释放
      </Button>
    </Popconfirm>
  );
};

export default ForceReleaseConfirm;
