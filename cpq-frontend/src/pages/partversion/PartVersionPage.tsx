import React, { useState } from 'react';
import { Card, Input, Button, Space, Typography, Alert } from 'antd';
import { SearchOutlined, InfoCircleOutlined } from '@ant-design/icons';
import PartVersionDrawer from '../../components/PartVersionDrawer';

const { Title, Paragraph } = Typography;

const PartVersionPage: React.FC = () => {
  const [customerProductNo, setCustomerProductNo] = useState('');
  const [hfPartNo, setHfPartNo] = useState('');
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [pickedCpn, setPickedCpn] = useState('');
  const [pickedHf, setPickedHf] = useState('');

  const openDrawer = () => {
    if (!customerProductNo.trim() || !hfPartNo.trim()) return;
    setPickedCpn(customerProductNo.trim());
    setPickedHf(hfPartNo.trim());
    setDrawerOpen(true);
  };

  return (
    <div style={{ padding: 24 }}>
      <Title level={3}>料号版本管理</Title>
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleOutlined />}
        message="S2 阶段独立功能页面"
        description="按 (客户产品编号, 宏丰料号) 查询版本号、查看历史、升版或切换激活版本。业务功能不变 — 现有导入流程未被触碰, 此页面仅提供独立的版本管理能力。"
        style={{ marginBottom: 16 }}
      />

      <Card title="查询料号版本">
        <Paragraph type="secondary">输入版本主键 (客户产品编号 + 宏丰料号), 打开版本管理抽屉.</Paragraph>
        <Space>
          <Input
            placeholder="客户产品编号 (customer_product_no)"
            value={customerProductNo}
            onChange={e => setCustomerProductNo(e.target.value)}
            style={{ width: 280 }}
            onPressEnter={openDrawer}
          />
          <Input
            placeholder="宏丰料号 (hf_part_no)"
            value={hfPartNo}
            onChange={e => setHfPartNo(e.target.value)}
            style={{ width: 280 }}
            onPressEnter={openDrawer}
          />
          <Button
            type="primary"
            icon={<SearchOutlined />}
            disabled={!customerProductNo.trim() || !hfPartNo.trim()}
            onClick={openDrawer}
          >
            查看版本
          </Button>
        </Space>
      </Card>

      <PartVersionDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        customerProductNo={pickedCpn}
        hfPartNo={pickedHf}
      />
    </div>
  );
};

export default PartVersionPage;
