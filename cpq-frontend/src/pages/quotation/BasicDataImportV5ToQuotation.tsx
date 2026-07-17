/**
 * BasicDataImportV5ToQuotation — 薄壳包装层
 *
 * V6 重构后职责大幅简化：
 *  1. 拉取客户列表
 *  2. 渲染 BasicDataImportV5Wizard（三步流程，内含创建报价单 Step 3）
 *
 * 原 CreateQuotationDrawer / handleWizardSuccess / 第二阶段逻辑
 * 已全部移入 BasicDataImportV5Wizard Step 3，本组件不再负责。
 *
 * 调用方：QuotationList.tsx 第 404 行
 *   <BasicDataImportV5ToQuotation open={basicImportOpen} onClose={...} />
 */
import React, { useEffect, useState } from 'react';
import { Drawer, Spin, message } from 'antd';
import BasicDataImportV5Wizard from './BasicDataImportV5Wizard';
import { customerService } from '../../services/customerService';

interface Customer {
  id: string;
  name: string;
  productCategoryId?: string;
}

interface Props {
  open: boolean;
  onClose: () => void;
}

const BasicDataImportV5ToQuotation: React.FC<Props> = ({ open, onClose }) => {
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [customersLoading, setCustomersLoading] = useState(false);

  // 每次打开时拉取客户列表
  useEffect(() => {
    if (!open) return;
    setCustomersLoading(true);
    customerService
      .list({ page: 0, size: 200 })
      .then((r: any) => {
        const list: any[] = r.data?.content ?? r.data ?? [];
        setCustomers(list.map((c: any) => ({ id: c.id, name: c.name, productCategoryId: c.productCategoryId })));
      })
      .catch(() => {
        message.error('加载客户列表失败');
      })
      .finally(() => setCustomersLoading(false));
  }, [open]);

  // 客户列表加载中时显示占位 Drawer
  if (customersLoading && open) {
    return (
      <Drawer
        title="从基础数据导入"
        placement="right"
        width={960}
        open={open}
        onClose={onClose}
        maskClosable={false}
        keyboard={false}
      >
        <div style={{ textAlign: 'center', padding: '80px 0' }}>
          <Spin size="large" tip="加载客户列表…" />
        </div>
      </Drawer>
    );
  }

  return (
    <BasicDataImportV5Wizard
      open={open}
      customers={customers}
      onClose={onClose}
      title="从基础数据导入"
    />
  );
};

export default BasicDataImportV5ToQuotation;
