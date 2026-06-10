import React, { useEffect, useState } from 'react';
import { Select, message } from 'antd';
import { tabJoinFormulaService, type SampleCard } from '../../../services/tabJoinFormulaService';

interface Props {
  componentId: string;
  value?: string;
  onChange: (lineItemId: string, label: string) => void;
}

const SampleCardPicker: React.FC<Props> = ({ componentId, value, onChange }) => {
  const [options, setOptions] = useState<{ value: string; label: string }[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!componentId) return;
    setLoading(true);
    tabJoinFormulaService
      .sampleCardsByComponent(componentId)
      .then((res: any) => {
        const cards: SampleCard[] = Array.isArray(res?.data) ? res.data : [];
        setOptions(
          cards.map((c) => ({
            value: c.lineItemId,
            label: `${c.quotationNo} / ${c.cardName}`,
          })),
        );
      })
      .catch(() => {
        message.warning('样本卡片加载失败，请刷新后重试');
        setOptions([]);
      })
      .finally(() => setLoading(false));
  }, [componentId]);

  return (
    <Select
      style={{ width: 280 }}
      placeholder="选样本卡片试算"
      loading={loading}
      value={value}
      options={options}
      allowClear
      onChange={(val) => {
        if (!val) {
          onChange('', '');
          return;
        }
        const opt = options.find((o) => o.value === val);
        onChange(val, opt?.label ?? val);
      }}
    />
  );
};

export default SampleCardPicker;
