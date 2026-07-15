/**
 * CompositeProcessSection — 选配添加·组合工艺条件区块（task-0712 F5，D12/D13/D14）。
 *
 * 1:1 对齐原型 `.combo-section` / `.combo-hint`：明细表数量合计 Σqty ≥ 2 时可用（否则整块灰置
 * 提示"数量合计≥2时需选择组合工艺"，不隐藏——D14 通则同样适用非工具栏场景）；候选来自
 * `compositeProcessService.list()`（B6 已改读 process_master WHERE process_category='ASSEMBLY'，
 * `code` = process_no，五处标识锚点之一）；顶部过滤框 + 多选 chip。
 */
import React, { useEffect, useState } from 'react';
import { Input, Empty, Tag } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import {
  compositeProcessService,
  type CompositeProcessCandidateDTO,
} from '../../../services/compositeProcessService';
import type { CompositeSelectionState } from '../../../types/configure';

interface Props {
  sumQty: number;
  selections: CompositeSelectionState[];
  onChange: (next: CompositeSelectionState[]) => void;
}

const CompositeProcessSection: React.FC<Props> = ({ sumQty, selections, onChange }) => {
  const [defs, setDefs] = useState<CompositeProcessCandidateDTO[]>([]);
  const [q, setQ] = useState('');

  useEffect(() => {
    compositeProcessService.list().then(setDefs).catch(() => setDefs([]));
  }, []);

  if (sumQty < 2) {
    return (
      <div
        style={{
          marginTop: 16,
          padding: '12px 14px',
          background: '#fafafa',
          border: '1px dashed #e4e7ed',
          borderRadius: 8,
          color: '#c0c4cc',
          fontSize: 12.5,
          textAlign: 'center',
        }}
      >
        数量合计 ≥ 2 时需选择组合工艺
      </div>
    );
  }

  const isSel = (code: string) => selections.some((s) => s.defCode === code);
  const toggle = (def: CompositeProcessCandidateDTO) => {
    if (isSel(def.code)) onChange(selections.filter((s) => s.defCode !== def.code));
    else onChange([...selections, { defCode: def.code, name: def.name }]);
  };

  const kw = q.trim().toLowerCase();
  const filtered = defs.filter(
    (d) => !kw || d.name.toLowerCase().includes(kw) || d.code.toLowerCase().includes(kw),
  );

  return (
    <div
      style={{
        marginTop: 16,
        border: '1px solid #91d5ff',
        borderRadius: 8,
        padding: '12px 14px',
        background: '#f5faff',
      }}
    >
      <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 10, display: 'flex', alignItems: 'center', gap: 8 }}>
        🔗 组合工艺 <Tag color="blue">数量合计 ≥ 2</Tag>
      </div>
      <Input
        prefix={<SearchOutlined />}
        placeholder="搜索组合工艺名称，如「焊接」…"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        style={{ marginBottom: 12 }}
        allowClear
      />
      {filtered.length === 0 ? (
        <Empty description="未找到匹配的组合工艺" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap' }}>
          {filtered.map((d) => (
            <label
              key={d.code}
              style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', fontSize: 13, color: '#303133' }}
            >
              <input type="checkbox" checked={isSel(d.code)} onChange={() => toggle(d)} />
              <span>{d.name}</span>
              <span style={{ color: '#909399', fontSize: 11 }}>{d.code}</span>
            </label>
          ))}
        </div>
      )}
    </div>
  );
};

export default CompositeProcessSection;
