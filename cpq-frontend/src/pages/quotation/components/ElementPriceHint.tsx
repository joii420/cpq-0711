import React, { useEffect, useState } from 'react';
import { Tooltip, Tag, Spin } from 'antd';
import { InfoCircleOutlined } from '@ant-design/icons';
import { elementPriceService } from '../../../services/elementPriceService';
import type { ElementReferenceDTO } from '../../../types/element-price';

interface ElementPriceHintProps {
  elementName: string;
  priceDate?: string; // YYYY-MM-DD，默认今日
}

/**
 * 在报价单元素行单价旁显示参考价提示。
 * 渲染为一个 Tooltip + 小 Tag 图标，悬停后显示详细信息。
 */
const ElementPriceHint: React.FC<ElementPriceHintProps> = ({ elementName, priceDate }) => {
  const [ref, setRef] = useState<ElementReferenceDTO | null>(null);
  const [loading, setLoading] = useState(false);
  const [fetched, setFetched] = useState(false);

  useEffect(() => {
    if (!elementName) return;
    let cancelled = false;
    setLoading(true);
    const today = priceDate ?? new Date().toISOString().slice(0, 10);
    elementPriceService
      .getReference(elementName, today)
      .then((data) => {
        if (!cancelled) {
          setRef(data);
          setFetched(true);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setRef(null);
          setFetched(true);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [elementName, priceDate]);

  if (loading) {
    return (
      <Spin size="small" style={{ marginLeft: 4 }} />
    );
  }

  if (!fetched) return null;

  const tooltipContent = ref ? (
    <div style={{ maxWidth: 220 }}>
      <div>
        参考价：<strong>{ref.price.toLocaleString()} {ref.currency}/{ref.unit}</strong>
      </div>
      <div style={{ color: 'rgba(255,255,255,0.75)', fontSize: 12, marginTop: 2 }}>
        {ref.enteredByName}，{ref.priceDate}
      </div>
      {ref.note && (
        <div style={{ color: 'rgba(255,255,255,0.65)', fontSize: 12, marginTop: 2 }}>
          {ref.note}
        </div>
      )}
    </div>
  ) : (
    <span>参考价：暂无</span>
  );

  return (
    <Tooltip title={tooltipContent} placement="topLeft">
      <Tag
        icon={<InfoCircleOutlined />}
        color={ref ? 'blue' : 'default'}
        style={{
          marginLeft: 4,
          cursor: 'help',
          fontSize: 11,
          padding: '0 4px',
          lineHeight: '18px',
        }}
      >
        {ref
          ? `参考 ${ref.price.toLocaleString()} ${ref.currency}/${ref.unit}`
          : '参考价：暂无'}
      </Tag>
    </Tooltip>
  );
};

export default ElementPriceHint;
