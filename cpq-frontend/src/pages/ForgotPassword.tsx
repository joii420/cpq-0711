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
    try { await authService.forgotPassword(values.email); } catch {}
    finally { setLoading(false); setSent(true); }
  };

  if (sent) return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
      <Card style={{ width: 400 }}>
        <Result status="success" title="重置邮件已发送" subTitle="请检查您的邮箱，点击链接重置密码（1小时内有效）" />
        <Link to="/login"><Button type="primary" block>返回登录</Button></Link>
      </Card>
    </div>
  );

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
