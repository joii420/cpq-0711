import React, { useState } from 'react';
import { Card, Tabs } from 'antd';
import RegularProcessTab from './RegularProcessTab';
import CompositeProcessTab from './CompositeProcessTab';

const ProcessManagement: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'regular' | 'composite'>('regular');

  return (
    <Card title="工序管理" bodyStyle={{ padding: '0 16px 16px' }}>
      <Tabs
        activeKey={activeTab}
        onChange={(k) => setActiveTab(k as 'regular' | 'composite')}
        items={[
          { key: 'regular',   label: '普通工序',   children: <RegularProcessTab /> },
          { key: 'composite', label: '组合工序',   children: <CompositeProcessTab /> },
        ]}
      />
    </Card>
  );
};

export default ProcessManagement;
