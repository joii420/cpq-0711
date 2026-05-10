import React, { useState } from 'react';
import { Card, Form, Input, Button, Typography, Alert, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { authService } from '../services/authService';
import { useAuthStore } from '../stores/authStore';

const ChangePassword: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const forceChangePassword = useAuthStore((s) => s.forceChangePassword);

  const onFinish = async (values: { currentPassword: string; newPassword: string; confirmPassword: string }) => {
    if (values.newPassword !== values.confirmPassword) {
      message.error('两次输入的密码不一致');
      return;
    }
    setLoading(true);
    try {
      await authService.changePassword({ currentPassword: values.currentPassword, newPassword: values.newPassword });
      message.success('密码修改成功');
      useAuthStore.setState({ forceChangePassword: false });
      navigate('/dashboard');
    } catch (err: any) {
      message.error(err.message || '修改失败');
    } finally { setLoading(false); }
  };

  return (
    <div style={{ maxWidth: 500, margin: '0 auto' }}>
      {forceChangePassword && <Alert message="首次登录，请修改初始密码后继续使用系统" type="warning" showIcon style={{ marginBottom: 16 }} />}
      <Card title="修改密码">
        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item name="currentPassword" label="当前密码" rules={[{ required: true }]}><Input.Password /></Form.Item>
          <Form.Item name="newPassword" label="新密码" rules={[{ required: true }, { min: 8, message: '至少8位' }, { pattern: /^(?=.*[a-zA-Z])(?=.*\d)/, message: '必须包含字母和数字' }]}><Input.Password /></Form.Item>
          <Form.Item name="confirmPassword" label="确认新密码" rules={[{ required: true }]}><Input.Password /></Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>确认修改</Button>
        </Form>
      </Card>
    </div>
  );
};
export default ChangePassword;
