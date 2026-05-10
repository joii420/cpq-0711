import React, { useState } from 'react';
import { Card, Form, Input, Button, message, Result } from 'antd';
import { useSearchParams, Link } from 'react-router-dom';
import { authService } from '../services/authService';

const ResetPassword: React.FC = () => {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token');
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  if (!token) return <Result status="error" title="无效链接" subTitle="缺少重置令牌" extra={<Link to="/login"><Button>返回登录</Button></Link>} />;

  const onFinish = async (values: { newPassword: string; confirmPassword: string }) => {
    if (values.newPassword !== values.confirmPassword) { message.error('两次密码不一致'); return; }
    setLoading(true);
    try { await authService.resetPassword(token, values.newPassword); setDone(true); }
    catch (err: any) { message.error(err.message || '重置失败'); }
    finally { setLoading(false); }
  };

  if (done) return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
      <Card style={{ width: 400 }}><Result status="success" title="密码重置成功" /><Link to="/login"><Button type="primary" block>去登录</Button></Link></Card>
    </div>
  );

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
      <Card style={{ width: 400 }} title="重置密码">
        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item name="newPassword" label="新密码" rules={[{ required: true }, { min: 8 }, { pattern: /^(?=.*[a-zA-Z])(?=.*\d)/, message: '须含字母和数字' }]}><Input.Password /></Form.Item>
          <Form.Item name="confirmPassword" label="确认密码" rules={[{ required: true }]}><Input.Password /></Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>确认重置</Button>
        </Form>
      </Card>
    </div>
  );
};
export default ResetPassword;
