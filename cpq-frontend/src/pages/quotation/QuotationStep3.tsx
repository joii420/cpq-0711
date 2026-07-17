/**
 * QuotationStep3 — 优惠策略 (Step 3)
 *
 * V2 重做要点：
 *   - 折扣来源动态提取（extractDiscountSources）：总金额 + 产品小计公式里的页签小计
 *   - 用户手填折扣率 → computeLineDiscount 按产品小计公式重算折后小计 / 折扣金额 / 行合计
 *   - 删除旧的后端阶梯引擎按钮和 callBackendCalculate
 *   - grandDiscount 直接 Σ lineDiscountAmount（computeLineDiscount 返回值已含 ×年用量）
 *   - 初始物化（2026-07-16）：进入本步即对所有可见行跑 recomputeRow——修「原小计(单价)初始显示 0，
 *     改年用量才刷新」；顺带给未设置的年用量落默认值 1。经 onSilentUpdate 程序化写入，不触发 autosave。
 */
import React, { useEffect, useMemo } from 'react';
import { Card, Table, InputNumber, Select, Typography, Tag, Alert, Space } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { LineItem } from './QuotationStep2';
import type { DriverExpansionMap } from './useDriverExpansions';
import { computeLineDiscount, extractDiscountSources, patchVisibleLineItem } from './lineDiscount';

const { Text } = Typography;

interface Props {
  quotationId?: string;
  lineItems: LineItem[];
  baseCurrency: string;
  driverExpansions?: DriverExpansionMap;
  customerId?: string;
  onUpdate: (updater: (prev: LineItem[]) => LineItem[]) => void;
  /** 程序化写 lineItems 的通道（不置 userEditedRef → 不触发 autosave，Wizard Plan A）；初始物化专用。缺省退回 onUpdate。 */
  onSilentUpdate?: (updater: (prev: LineItem[]) => LineItem[]) => void;
  /** 全局变量定义表（wizard 的 gvDefs）——computeLineDiscount 完整口径求值需与渲染层同源。 */
  globalVariableDefs?: Record<string, import('../../services/globalVariableService').GlobalVariableDefinition>;
}

const QuotationStep3: React.FC<Props> = ({
  quotationId: _quotationId,
  lineItems,
  baseCurrency,
  driverExpansions,
  customerId,
  onUpdate,
  onSilentUpdate,
  globalVariableDefs,
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
    const d = computeLineDiscount(li, driverExpansions, customerId, source, rate, qty, globalVariableDefs);
    return {
      lineUnitPrice: d.original,
      discountBaseAmount: d.discountBaseAmount,
      lineDiscountAmount: d.lineDiscountAmount,
      lineFinalPrice: d.lineFinalPrice,
      lineTotalAmount: d.lineTotalAmount,
    };
  };

  const patchRow = (index: number, patch: Partial<LineItem>) => {
    // patchVisibleLineItem 把可见下标 index 精确映射回 prev 全集真实行（PART 不计入可见序），
    // 只改命中的那一行——内部对每个非 PART 行都自增下标，杜绝「编辑首行 = 全改」。
    onUpdate(prev =>
      patchVisibleLineItem(prev, index, li => {
        const merged = { ...li, ...patch };
        return { ...merged, ...recomputeRow(merged as LineItem) };
      }),
    );
  };

  // 初始物化（修「原小计(单价)进入 Step3 显示 0，改年用量才刷新」）：
  // 2f021bb2 V2 重做丢了初始化——recomputeRow 原来只在 patchRow(用户编辑)时跑，进入本步时
  // lineUnitPrice 未算、渲染回退 li.subtotal（未保存前为 0）。此 effect 在挂载 / driverExpansions
  // 就绪 / 行集变化（enrich/编辑）时对所有可见行跑同一 recomputeRow，并给未设置（null/undefined）
  // 的年用量落默认值 1（显式填过的 0 保留不动）。
  // 纪律：走 onSilentUpdate（程序化 setLineItems，不置 userEditedRef）——初始化不是用户编辑，
  // 不得触发 autosave（Wizard Plan A）；无变化时逐行保留原引用、整体返回 prev，防 effect 自循环。
  useEffect(() => {
    const update = onSilentUpdate ?? onUpdate;
    update(prev => {
      let changed = false;
      const next = prev.map(li => {
        if (li.compositeType === 'PART') return li;
        try {
          const seeded = li.annualVolume == null ? { ...li, annualVolume: 1 } : li;
          const patch = recomputeRow(seeded);
          const dirty =
            seeded !== li ||
            (Object.keys(patch) as Array<keyof LineItem>).some(k => seeded[k] !== patch[k]);
          if (!dirty) return li;
          changed = true;
          return { ...seeded, ...patch };
        } catch {
          return li; // 单行计算异常不阻断其余行
        }
      });
      return changed ? next : prev;
    });
    // recomputeRow 闭包读 driverExpansions/customerId；lineItems 引用变化(enrich/编辑)也需重物化
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [driverExpansions, customerId, lineItems]);

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
    // 「规则」列暂时移除（2026-07-16 需求）：折扣规则引擎未接通，列里恒显示「未匹配」无信息量。
    // discountRuleCode 字段及 round-trip 链路保留，规则引擎接通后把列加回即可。
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
            scroll={{ x: 1190 }}
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
