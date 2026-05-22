import React, { useEffect, useState } from 'react';
import { Card, Descriptions, Skeleton, Typography, Empty } from 'antd';
import dayjs from 'dayjs';
import {
  boundGlobalVariableService,
  type BoundGvViewDTO,
} from '../../../services/boundGlobalVariableService';

const { Text } = Typography;

interface Props {
  quotationId: string;
  status: string;
  onError?: (err: Error) => void;
}

type LoadState = 'idle' | 'loading' | 'success' | 'error';

// -------------------------------------------------------------------------
// 工具：按 varType 分发渲染
// -------------------------------------------------------------------------

/** SCALAR 类型：单值展示 (column=3 + value 拼接 unit) */
const ScalarCard: React.FC<{ item: BoundGvViewDTO; snapshotAt?: string }> = ({ item, snapshotAt }) => {
  const formatScalar = (v: unknown): string => {
    if (v === null || v === undefined || v === '') return '—';
    const base = String(v);
    return item.unit ? `${base} ${item.unit}` : base;
  };
  return (
    <Card title={item.name} size="small" style={{ marginBottom: 16 }}>
      <Descriptions column={3} bordered size="small">
        {item.rows.length === 0 ? (
          <Descriptions.Item label="取值" span={3}>
            <Text type="secondary">暂无数据</Text>
          </Descriptions.Item>
        ) : (
          (item.columns || []).map((col) => (
            <Descriptions.Item key={col} label={col}>
              {formatScalar(item.rows[0]?.[col])}
            </Descriptions.Item>
          ))
        )}
      </Descriptions>
      {snapshotAt && (
        <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
          快照时间: {dayjs(snapshotAt).format('YYYY-MM-DD HH:mm')}
        </Text>
      )}
    </Card>
  );
};

/**
 * LOOKUP_TABLE 类型：Descriptions form 展示 (PRD §3.7.3.3 v3.5 修订, v3.5.1 微调)。
 * column=3 (一行 3 个 K:V 对); 每行一个 Descriptions.Item:
 *   - label = key 列值 (灰底, 如 'Ag'/'Cu'/'Sn')
 *   - value = value_column 列值 拼接 unit (白底, 如 '400 CNY'/'100 CNY')
 * 列头名称 (如 'key' / 'value_number') 不显示, 与"报价单信息→基本信息"卡片样式一致.
 * 大表保护: max-height + 滚动条 (避免上千行 GV 撑爆页面).
 */
const LookupTableCard: React.FC<{ item: BoundGvViewDTO; snapshotAt?: string }> = ({ item, snapshotAt }) => {
  // columns 第一位是 key 列名 (如 'key'/'element_code'); 最后一位是 value 列名 (如 'value_number'/'costing_price')
  // 详见 GlobalVariableDataLoader.buildColumns: key_columns + [value_column]
  const keyCol = item.columns?.[0];
  const valueCol = item.columns?.[item.columns.length - 1];

  const formatKey = (v: unknown): string => {
    if (v === null || v === undefined || v === '') return '—';
    return String(v);
  };

  const formatValueWithUnit = (v: unknown): string => {
    if (v === null || v === undefined || v === '') return '—';
    const base = String(v);
    return item.unit ? `${base} ${item.unit}` : base;
  };

  return (
    <Card title={item.name} size="small" style={{ marginBottom: 16 }}>
      {item.rows.length === 0 ? (
        <Empty description="暂无数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <div style={{ maxHeight: 600, overflow: 'auto' }}>
          <Descriptions column={3} bordered size="small">
            {item.rows.map((row, idx) => (
              <Descriptions.Item
                key={`${item.code}-${idx}`}
                label={keyCol ? formatKey(row[keyCol]) : `行${idx + 1}`}
              >
                {valueCol ? formatValueWithUnit(row[valueCol]) : '—'}
              </Descriptions.Item>
            ))}
          </Descriptions>
        </div>
      )}
      {snapshotAt && (
        <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
          快照时间: {dayjs(snapshotAt).format('YYYY-MM-DD HH:mm')}
        </Text>
      )}
    </Card>
  );
};

// -------------------------------------------------------------------------
// 主组件
// -------------------------------------------------------------------------

const BoundGlobalVariablesTab: React.FC<Props> = ({ quotationId, status, onError }) => {
  const [loadState, setLoadState] = useState<LoadState>('idle');
  const [items, setItems] = useState<BoundGvViewDTO[]>([]);

  const isDraft = status === 'DRAFT';

  useEffect(() => {
    if (!quotationId) return;

    let cancelled = false;
    setLoadState('loading');

    const fetch = isDraft
      ? boundGlobalVariableService.getQuotationRefData(quotationId)
      : boundGlobalVariableService.getQuotationRefDataSnapshot(quotationId);

    fetch
      .then((data) => {
        if (cancelled) return;
        // 按 displayOrder 排序后展示
        const sorted = [...(data || [])].sort((a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0));
        setItems(sorted);
        setLoadState('success');
      })
      .catch((err: Error) => {
        if (cancelled) return;
        setLoadState('error');
        onError?.(err);
      });

    return () => {
      cancelled = true;
    };
  }, [quotationId, isDraft, onError]);

  if (loadState === 'idle' || loadState === 'loading') {
    return (
      <div style={{ padding: '24px 0' }}>
        <Skeleton active paragraph={{ rows: 6 }} />
      </div>
    );
  }

  if (loadState === 'error') {
    return (
      <Empty
        description="加载引用数据失败，请刷新后重试"
        style={{ padding: 48 }}
      />
    );
  }

  if (items.length === 0) {
    return (
      <Empty
        description="该报价单未绑定任何全局变量"
        style={{ padding: 48 }}
      />
    );
  }

  return (
    <div style={{ padding: '8px 0' }}>
      {items.map((item) => {
        // 非 DRAFT 时 fetchedAt 字段对应快照时间
        const snapshotAt = !isDraft ? item.fetchedAt : undefined;

        if (item.varType === 'SCALAR') {
          return (
            <ScalarCard key={item.code} item={item} snapshotAt={snapshotAt} />
          );
        }
        // LOOKUP_TABLE（默认分支）
        return (
          <LookupTableCard key={item.code} item={item} snapshotAt={snapshotAt} />
        );
      })}
    </div>
  );
};

export default BoundGlobalVariablesTab;
