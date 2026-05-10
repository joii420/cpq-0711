import React, { useEffect, useState } from 'react';
import { Typography, Card, Spin, Alert } from 'antd';
import { CheckCircleOutlined } from '@ant-design/icons';
import api from '../services/api';

interface HealthData {
  code: number;
  data: {
    status: string;
    service: string;
  };
}

const Dashboard: React.FC = () => {
  const [health, setHealth] = useState<HealthData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.get('/health')
      .then((res: any) => {
        setHealth(res);
        setLoading(false);
      })
      .catch((err: Error) => {
        setError(err.message);
        setLoading(false);
      });
  }, []);

  return (
    <div>
      <Typography.Title level={4}>工作台</Typography.Title>
      <Card title="System Health" style={{ maxWidth: 400 }}>
        {loading && <Spin />}
        {error && <Alert type="error" message={error} />}
        {health && (
          <div>
            <p><CheckCircleOutlined style={{ color: '#48bb78', marginRight: 8 }} />Status: {health.data.status}</p>
            <p>Service: {health.data.service}</p>
          </div>
        )}
      </Card>
    </div>
  );
};

export default Dashboard;
