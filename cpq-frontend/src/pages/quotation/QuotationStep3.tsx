/**
 * QuotationStep3 — 优惠策略 (Step 3) 最小可用版
 *
 * 后端能力 (已就位):
 *   - quotation_line_item 已有 9 个 Step3 列 (annual_volume / discount_source /
 *     discount_base_amount / discount_rate_applied / line_discount_amount /
 *     line_unit_price / line_final_price / line_total_amount / discount_rule_code)
 *   - pricing_strategy + pricing_rule (BULK_DISCOUNT 阶梯) 表已存
 *   - POST /api/cpq/quotations/{id}/calculate-discount 端点已实现 (整单级)
 *
 * 本版作用域 (最小可用, V1):
 *   - 行级表格展示 lineItems: 产品 / 原小计 / 年用量(可编辑) / 折扣率(%) / 折扣金额 / 折后单价 / 行合计
 *   - 用户编辑年用量 / 折扣率 → onUpdate(prev) → setLineItems 更新, 触发 saveDraft
 *   - 整单合计自动汇总
 *   - 「调用后端阶梯引擎」按钮触发 calculateDiscount + 同步系统折扣率到所有行
 *   - 折扣来源选择, 当前仅 SUBTOTAL (V2 扩 MATERIAL_COST / PROCESS_FEE 等 metric)
 *
 * V2 follow-up (业务驱动):
 *   - 行级独立调用阶梯引擎 (按行 annualVolume 单独命中规则)
 *   - 折扣来源细化到 metric_code (扣减只针对材料 / 工序等部分成本)
 *   - 跨产品集合阶梯 (按客户全年累计)
 */
import React, { useMemo } from 'react';
import { Card, Table, InputNumber, Select, Button, Space, Typography, Tag, Alert, message } from 'antd';
import { CalculatorOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import api from '../../services/api';
import type { LineItem } from './QuotationStep2';

const { Text } = Typography;

interface Props {
  quotationId?: string;
  lineItems: LineItem[];
  baseCurrency: string;
  onUpdate: (updater: (prev: LineItem[]) => LineItem[]) => void;
}

const DISCOUNT_SOURCE_OPTIONS = [
  { label: '整单合计 (SUBTOTAL)', value: 'SUBTOTAL' },
  { label: '材料成本 (MATERIAL_COST)', value: 'MATERIAL_COST' },
  { label: '加工费 (PROCESS_FEE)', value: 'PROCESS_FEE' },
  { label: '组合工艺费 (COMPOSITE_FEE)', value: 'COMPOSITE_FEE' },
];

const QuotationStep3: React.FC<Props> = ({ quotationId, lineItems, baseCurrency, onUpdate }) => {
  // 过滤掉 PART 子件 (与 Step2 一致, 只展示父级)
  const visibleItems = useMemo(
    () => lineItems.filter(li => li.compositeType !== 'PART'),
    [lineItems],
  );

  // 单行计算: 折扣率/年用量 改变时联动算 lineDiscountAmount/lineFinalPrice/lineTotalAmount
  const recomputeRow = (li: LineItem): Partial<LineItem> => {
    const unitPrice = li.lineUnitPrice ?? li.subtotal ?? 0;
    const rate = li.discountRateApplied ?? 0;  // 折扣率 % (0~100)
    const annualVol = li.annualVolume ?? 0;
    const discountAmount = (unitPrice * rate) / 100;
    const finalPrice = unitPrice - discountAmount;
    const total = finalPrice * annualVol;
    return {
      lineDiscountAmount: round4(discountAmount),
      lineFinalPrice: round4(finalPrice),
      lineTotalAmount: round4(total),
    };
  };

  const patchRow = (index: number, patch: Partial<LineItem>) => {
    onUpdate(prev => {
      // 找到 visibleItems[index] 在 prev 数组中真实索引 (考虑 PART filter)
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

  // 调后端阶梯引擎按整单总额计算
  const callBackendCalculate = async () => {
    if (!quotationId) {
      message.warning('报价单尚未保存, 请先在 Step1/2 完成基础信息');
      return;
    }
    const originalAmount = visibleItems.reduce(
      (sum, li) => sum + (li.lineUnitPrice ?? li.subtotal ?? 0) * (li.annualVolume ?? 1),
      0,
    );
    if (originalAmount <= 0) {
      message.warning('原始合计 = 0, 请先填年用量');
      return;
    }
    try {
      const resp: any = await api.post(`/quotations/${quotationId}/calculate-discount`, { originalAmount });
      const data = resp?.data ?? resp;
      const systemRate = Number(data?.systemDiscountRate ?? data?.finalDiscountRate ?? 0);
      // 把整单 systemRate 同步到所有行 (V1 行级与整单同率, V2 改成按行命中规则)
      onUpdate(prev => prev.map(li => {
        if (li.compositeType === 'PART') return li;
        const merged = { ...li, discountRateApplied: systemRate, discountRuleCode: data?.discountRuleCode ?? 'BULK_DISCOUNT_V1' };
        return { ...merged, ...recomputeRow(merged as LineItem) };
      }));
      message.success(`阶梯引擎已计算: 系统折扣率 ${systemRate}% (整单原价 ¥${originalAmount.toLocaleString()})`);
    } catch (e: any) {
      message.error(e?.response?.data?.message || e?.message || '后端计算失败');
    }
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
            {li.compositeType === 'COMPOSITE' && <Tag color="purple" style={{ marginLeft: 4, fontSize: 10 }}>组合</Tag>}
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
      width: 180,
      render: (_v, li, idx) => (
        <Select
          value={li.discountSource ?? 'SUBTOTAL'}
          onChange={v => patchRow(idx, { discountSource: v })}
          options={DISCOUNT_SOURCE_OPTIONS}
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
  const grandDiscount = visibleItems.reduce(
    (sum, li) => sum + (li.lineDiscountAmount ?? 0) * (li.annualVolume ?? 0),
    0,
  );
  const grandTotal = visibleItems.reduce(
    (sum, li) => sum + (li.lineTotalAmount ?? 0),
    0,
  );

  return (
    <Card title="优惠策略 (Step 3) — 行级折扣 V1" size="small">
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="行级阶梯折扣"
        description={
          <div style={{ fontSize: 13 }}>
            每行填入年用量与折扣率 → 自动算折扣金额 + 折后单价 + 行合计.
            点「调用后端阶梯引擎」按客户折扣策略按整单总额匹配阶梯规则 (pricing_strategy + pricing_rule).
            V1 行级与整单同率; V2 follow-up 行级独立阶梯匹配.
          </div>
        }
      />

      <Space style={{ marginBottom: 12 }}>
        <Button
          type="primary"
          icon={<CalculatorOutlined />}
          onClick={callBackendCalculate}
          disabled={!quotationId || visibleItems.length === 0}
        >
          调用后端阶梯引擎计算
        </Button>
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

function round4(n: number): number {
  return Math.round(n * 10000) / 10000;
}

function formatCurrency(value: number, currency: string): string {
  const symbol = currency === 'CNY' ? '¥' : currency === 'USD' ? '$' : `${currency} `;
  return `${symbol}${(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export default QuotationStep3;
