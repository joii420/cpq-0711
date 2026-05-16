/**
 * QuotationStep3 — 本地 stub
 *
 * 远端 master 包含 V162 迁移 (annual_volume / discount_source 等 9 列) + 行级折扣 UI,
 * 但本地 checkout 缺这个 .tsx 文件（未推上来）。
 * 此 stub 让 Wizard 编译通过；真实 step3 UI 等对应分支合并后替换。
 */
import React from 'react';
import { Alert, Card } from 'antd';
import type { LineItem } from './QuotationStep2';

interface Props {
  lineItems: LineItem[];
  baseCurrency: string;
  onUpdate: (updater: (prev: LineItem[]) => LineItem[]) => void;
}

const QuotationStep3: React.FC<Props> = ({ lineItems, baseCurrency }) => (
  <Card title="优惠策略 (Step 3)" size="small">
    <Alert
      type="info"
      showIcon
      message="此步骤功能尚未在本地 checkout 中实现"
      description={
        <div>
          <div>当前 Step3 UI（v3.1 行级表格 + 年用量阶梯折扣）的前端代码未推送到 master 分支。</div>
          <div>已加载 lineItems: {lineItems.length} 条，币种: {baseCurrency}</div>
          <div>占位 stub —— 待对应分支合并后替换为真实组件。</div>
        </div>
      }
    />
  </Card>
);

export default QuotationStep3;
