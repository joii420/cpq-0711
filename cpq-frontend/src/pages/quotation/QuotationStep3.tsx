/**
 * QuotationStep3 — 优惠策略 (Step 3)
 *
 * V2 重做要点：
 *   - 折扣来源动态提取（extractDiscountSources）：总金额 + 产品小计公式里的页签小计
 *   - 用户手填折扣率 → computeLineDiscount 按产品小计公式重算折后小计 / 折扣金额 / 行合计
 *   - 删除旧的后端阶梯引擎按钮和 callBackendCalculate
 *   - grandDiscount 直接 Σ lineDiscountAmount（computeLineDiscount 返回值已含 ×年用量）
 */
import React, { useMemo } from 'react';
import { Card, Table, InputNumber, Select, Typography, Tag, Alert, Space } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { LineItem } from './QuotationStep2';
import type { DriverExpansionMap } from './useDriverExpansions';
import { computeLineDiscount, extractDiscountSources } from './lineDiscount';

const { Text } = Typography;

interface Props {
  quotationId?: string;
  lineItems: LineItem[];
  baseCurrency: string;
  driverExpansions?: DriverExpansionMap;
  customerId?: string;
  onUpdate: (updater: (prev: LineItem[]) => LineItem[]) => void;
}

const QuotationStep3: React.FC<Props> = ({
  quotationId: _quotationId,
  lineItems,
  baseCurrency,
  driverExpansions,
  customerId,
  onUpdate,
}) => {
  // 过滤掉 PART 子件（与 Step2 一致，只展示父级）
  const visibleItems = useMemo(
    () => lineItems.filter(li => li.compositeType !== 'PART'),
    [lineItems],
  );

  // 单行重算：折扣来源 / 折扣率 / 年用量任一变化时调用
  const recomputeRow = (li: LineItem): Partial<LineItem> => {
    const source = li.discountSource ?? 'SUBTOTAL';
    const rate = li.discountRateApplied ?? 0;
    const qty = li.annualVolume ?? 0;
    const d = computeLineDiscount(li, driverExpansions, customerId, source, rate, qty);
    return {
      lineUnitPrice: d.original,
      discountBaseAmount: d.discountBaseAmount,
      lineDiscountAmount: d.lineDiscountAmount,
      lineFinalPrice: d.lineFinalPrice,
      lineTotalAmount: d.lineTotalAmount,
    };
  };

  const patchRow = (index: number, patch: Partial<LineItem>) => {
    onUpdate(prev => {
      // 找到 visibleItems[index] 在 prev 数组中真实索引（考虑 PART filter）
      let visibleIdx = 0;
      return prev.map(li => {
        if (li.compositeType === 'PART') return li;
        if (visibleIdx === index) {
          const merged = { ...li, ...patch };
          return { ...merged, ...recomputeRow(merged as LineItem) };
        }
        visibleIdx += 1;
        return li;
      });
    });
  };

  const columns: ColumnsType<LineItem> = [
    {
      title: '产品',
      key: 'product',
      width: 240,
      render: (_v, li) => (
        <div>
          <div><Text strong>{li.productName || li.productPartNo || '—'}</Text></div>
          <div>
            <Text type="secondary" style={{ fontSize: 11 }}>{li.productPartNo}</Text>
            {li.compositeType === 'COMPOSITE' && (
              <Tag color="purple" style={{ marginLeft: 4, fontSize: 10 }}>组合</Tag>
            )}
          </div>
        </div>
      ),
    },
    {
      title: '原小计 (单价)',
      key: 'unitPrice',
      width: 130,
      align: 'right',
      render: (_v, li) => formatCurrency(li.lineUnitPrice ?? li.subtotal ?? 0, baseCurrency),
    },
    {
      title: '年用量',
      key: 'annualVolume',
      width: 130,
      render: (_v, li, idx) => (
        <InputNumber
          value={li.annualVolume}
          min={0}
          style={{ width: '100%' }}
          onChange={v => patchRow(idx, { annualVolume: v ?? 0 })}
          placeholder="0"
        />
      ),
    },
    {
      title: '折扣来源',
      key: 'discountSource',
      width: 200,
      render: (_v, li, idx) => (
        <Select
          value={li.discountSource ?? 'SUBTOTAL'}
          onChange={v => patchRow(idx, { discountSource: v })}
          options={extractDiscountSources(li)}
          style={{ width: '100%' }}
          size="small"
        />
      ),
    },
    {
      title: '折扣率(%)',
      key: 'discountRate',
      width: 110,
      render: (_v, li, idx) => (
        <InputNumber
          value={li.discountRateApplied}
          min={0}
          max={100}
          step={0.5}
          style={{ width: '100%' }}
          onChange={v => patchRow(idx, { discountRateApplied: v ?? 0 })}
          placeholder="0"
        />
      ),
    },
    {
      title: '折扣金额',
      key: 'discountAmount',
      width: 110,
      align: 'right',
      render: (_v, li) => (
        <Text type="warning">{formatCurrency(li.lineDiscountAmount ?? 0, baseCurrency)}</Text>
      ),
    },
    {
      title: '折后单价',
      key: 'finalPrice',
      width: 130,
      align: 'right',
      render: (_v, li) => (
        <Text strong>{formatCurrency(li.lineFinalPrice ?? li.lineUnitPrice ?? li.subtotal ?? 0, baseCurrency)}</Text>
      ),
    },
    {
      title: '行合计',
      key: 'lineTotal',
      width: 140,
      align: 'right',
      render: (_v, li) => (
        <Text strong style={{ color: '#0958d9' }}>{formatCurrency(li.lineTotalAmount ?? 0, baseCurrency)}</Text>
      ),
    },
    {
      title: '规则',
      key: 'rule',
      width: 110,
      render: (_v, li) => (
        li.discountRuleCode
          ? <Tag color="blue" style={{ fontSize: 10 }}>{li.discountRuleCode}</Tag>
          : <Text type="secondary" style={{ fontSize: 11 }}>未匹配</Text>
      ),
    },
  ];

  const grandOriginal = visibleItems.reduce(
    (sum, li) => sum + (li.lineUnitPrice ?? li.subtotal ?? 0) * (li.annualVolume ?? 0),
    0,
  );
  // computeLineDiscount 返回的 lineDiscountAmount 已含 ×年用量，直接求和
  const grandDiscount = visibleItems.reduce(
    (sum, li) => sum + (li.lineDiscountAmount ?? 0),
    0,
  );
  const grandTotal = visibleItems.reduce(
    (sum, li) => sum + (li.lineTotalAmount ?? 0),
    0,
  );

  return (
    <Card title="优惠策略 (Step 3) — 行级折扣" size="small">
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="行级折扣"
        description={
          <div style={{ fontSize: 13 }}>
            每行选折扣来源 + 填折扣率 → 自动按产品小计公式重算折后小计 / 折扣金额 / 行合计。
            折扣来源默认「总金额」，也可选某页签小计单独打折。
          </div>
        }
      />

      <Space style={{ marginBottom: 12 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          ({visibleItems.length} 行, 币种 {baseCurrency})
        </Text>
      </Space>

      {visibleItems.length === 0 ? (
        <Alert type="warning" message="请先在 Step2 添加产品" showIcon />
      ) : (
        <>
          <Table<LineItem>
            rowKey={(li, i) => li.id ?? `row-${i}`}
            dataSource={visibleItems}
            columns={columns}
            pagination={false}
            size="small"
            bordered
            scroll={{ x: 1280 }}
          />

          <div
            style={{
              marginTop: 12, padding: '12px 16px', background: '#f0f5ff',
              borderRadius: 6, display: 'flex', gap: 32, justifyContent: 'flex-end',
            }}
          >
            <div>
              <Text type="secondary">原始合计</Text>
              <div style={{ fontSize: 16, fontWeight: 600 }}>{formatCurrency(grandOriginal, baseCurrency)}</div>
            </div>
            <div>
              <Text type="secondary">总折扣</Text>
              <div style={{ fontSize: 16, fontWeight: 600, color: '#cf1322' }}>
                -{formatCurrency(grandDiscount, baseCurrency)}
              </div>
            </div>
            <div>
              <Text type="secondary">合计应收</Text>
              <div style={{ fontSize: 18, fontWeight: 700, color: '#0958d9' }}>
                {formatCurrency(grandTotal, baseCurrency)}
              </div>
            </div>
          </div>
        </>
      )}
    </Card>
  );
};

function formatCurrency(value: number, currency: string): string {
  const symbol = currency === 'CNY' ? '¥' : currency === 'USD' ? '$' : `${currency} `;
  return `${symbol}${(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export default QuotationStep3;
