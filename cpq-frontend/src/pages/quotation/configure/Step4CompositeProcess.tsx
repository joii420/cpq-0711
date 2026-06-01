import React, { useEffect, useState } from 'react';
import { Card, Button, Tag, Input, Empty } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import {
  compositeProcessService,
  type CompositeProcessDef,
} from '../../../services/compositeProcessService';
import type { PartState, CompositeProcessAdded } from '../ConfigureProductDrawer';

interface Props {
  parts: PartState[];
  addedCProcs: CompositeProcessAdded[];
  onChangeAdded: (next: CompositeProcessAdded[]) => void;
}

/**
 * 组合工艺选择（需求 #2 简化版）：
 * 用户只挑选「使用哪些组合工艺」，不再勾选参与配件、不再填参数。
 * - participatingPartIndexes 预置为全部配件（提交时 Drawer 仍统一覆盖为全配件）。
 * - params 恒为空对象 {}。
 * 需求 #3：组合工艺库卡片显示编码，并支持按编码/名称模糊搜索。
 */
const Step4CompositeProcess: React.FC<Props> = ({ parts, addedCProcs, onChangeAdded }) => {
  const [defs, setDefs] = useState<CompositeProcessDef[]>([]);
  const [q, setQ] = useState('');

  useEffect(() => {
    compositeProcessService.list()
      .then(setDefs)
      .catch(() => setDefs([]));
  }, []);

  const isAdded = (code: string) => addedCProcs.some(a => a.defCode === code);

  const add = (def: CompositeProcessDef) => {
    if (isAdded(def.code)) return;
    const all = parts.map((_, i) => i);
    onChangeAdded([
      ...addedCProcs,
      { defCode: def.code, participatingPartIndexes: all, params: {} },
    ]);
  };

  const remove = (code: string) =>
    onChangeAdded(addedCProcs.filter(a => a.defCode !== code));

  const filtered = defs.filter(d => {
    const kw = q.trim().toLowerCase();
    if (!kw) return true;
    return d.name.toLowerCase().includes(kw) || d.code.toLowerCase().includes(kw);
  });

  return (
    <div style={{ display: 'flex', gap: 16, height: 480 }}>
      {/* 左:工艺库 */}
      <div style={{ width: 300, borderRight: '0.5px solid #eee', paddingRight: 12, display: 'flex', flexDirection: 'column' }}>
        <h4 style={{ marginTop: 0 }}>组合工艺库</h4>
        <Input
          prefix={<SearchOutlined />}
          placeholder="搜索组合工艺（编码 / 名称）…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          style={{ marginBottom: 8 }}
        />
        <div style={{ flex: 1, overflow: 'auto' }}>
          {filtered.length === 0
            ? <Empty description="无匹配工艺" />
            : filtered.map(def => (
                <Card
                  key={def.id}
                  size="small"
                  style={{
                    marginBottom: 8,
                    cursor: isAdded(def.code) ? 'not-allowed' : 'pointer',
                    background: isAdded(def.code) ? '#f0effe' : undefined,
                  }}
                  onClick={() => add(def)}
                >
                  <div>
                    <span style={{ fontSize: 18 }}>{def.icon}</span> <b>{def.name}</b>
                  </div>
                  <div style={{ marginTop: 4 }}>
                    <Tag color="blue">{def.code}</Tag>
                  </div>
                  <div style={{ color: '#888', fontSize: 11, marginTop: 4 }}>{def.description}</div>
                  {isAdded(def.code) && <Tag color="green" style={{ marginTop: 6 }}>已添加</Tag>}
                </Card>
              ))}
        </div>
      </div>

      {/* 右:已选组合工艺（仅名称 + 移除） */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        <h3 style={{ marginTop: 0 }}>已选组合工艺 ({addedCProcs.length})</h3>
        <div style={{ color: '#aaa', fontSize: 12, marginBottom: 12 }}>默认全部配件参与</div>
        {addedCProcs.length === 0 ? (
          <Empty description="从左侧选择组合工艺" />
        ) : (
          addedCProcs.map(a => {
            const def = defs.find(d => d.code === a.defCode);
            return (
              <Card
                key={a.defCode}
                size="small"
                style={{ marginBottom: 12, background: '#f9f8ff' }}
                title={
                  <>
                    <span style={{ fontSize: 16 }}>{def?.icon}</span> {def?.name ?? a.defCode}{' '}
                    {def && <Tag color="blue">{def.code}</Tag>}
                  </>
                }
                extra={
                  <Button type="text" danger onClick={() => remove(a.defCode)}>
                    移除
                  </Button>
                }
              />
            );
          })
        )}
      </div>
    </div>
  );
};

export default Step4CompositeProcess;
