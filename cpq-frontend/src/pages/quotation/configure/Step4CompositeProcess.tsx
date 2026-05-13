import React, { useEffect, useState } from 'react';
import { Card, Button, Tag, Input, InputNumber, Empty } from 'antd';
import {
  compositeProcessService,
  type CompositeProcessDef,
  type CompositeProcessParamDef,
} from '../../../services/compositeProcessService';
import type { PartState, CompositeProcessAdded } from '../ConfigureProductDrawer';

interface Props {
  parts: PartState[];
  addedCProcs: CompositeProcessAdded[];
  onChangeAdded: (next: CompositeProcessAdded[]) => void;
}

const Step4CompositeProcess: React.FC<Props> = ({ parts, addedCProcs, onChangeAdded }) => {
  const [defs, setDefs] = useState<CompositeProcessDef[]>([]);

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

  const togglePart = (defCode: string, partIdx: number) => {
    onChangeAdded(
      addedCProcs.map(a => {
        if (a.defCode !== defCode) return a;
        const has = a.participatingPartIndexes.includes(partIdx);
        if (has && a.participatingPartIndexes.length <= 2) return a; // 至少 2 个
        const next = has
          ? a.participatingPartIndexes.filter(x => x !== partIdx)
          : [...a.participatingPartIndexes, partIdx];
        return { ...a, participatingPartIndexes: next };
      }),
    );
  };

  const setParam = (defCode: string, key: string, val: any) => {
    onChangeAdded(
      addedCProcs.map(a =>
        a.defCode === defCode ? { ...a, params: { ...a.params, [key]: val } } : a,
      ),
    );
  };

  const getParamSchema = (def: CompositeProcessDef): CompositeProcessParamDef[] =>
    compositeProcessService.parseParamSchema(def.paramSchema);

  return (
    <div style={{ display: 'flex', gap: 16, height: 480 }}>
      {/* 左:工艺库 */}
      <div style={{ width: 280, borderRight: '0.5px solid #eee', paddingRight: 12, overflow: 'auto' }}>
        <h4 style={{ marginTop: 0 }}>组合工艺库</h4>
        {defs.length === 0
          ? <Empty description="无可用工艺" />
          : defs.map(def => (
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
                <div style={{ color: '#888', fontSize: 11, marginTop: 4 }}>{def.description}</div>
                {isAdded(def.code) && <Tag color="green" style={{ marginTop: 6 }}>已添加</Tag>}
              </Card>
            ))}
      </div>

      {/* 右:已选组合工艺 */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        <h3 style={{ marginTop: 0 }}>已选组合工艺 ({addedCProcs.length})</h3>
        <div style={{ color: '#aaa', fontSize: 12, marginBottom: 12 }}>每项工艺需指定参与配件(至少 2 个)</div>
        {addedCProcs.length === 0 ? (
          <Empty description="从左侧选择组合工艺" />
        ) : (
          addedCProcs.map(a => {
            const def = defs.find(d => d.code === a.defCode);
            if (!def) return null;
            const schema = getParamSchema(def);
            return (
              <Card
                key={a.defCode}
                size="small"
                style={{ marginBottom: 12, background: '#f9f8ff' }}
                title={<><span style={{ fontSize: 16 }}>{def.icon}</span> {def.name}</>}
                extra={
                  <Button type="text" danger onClick={() => remove(a.defCode)}>
                    移除
                  </Button>
                }
              >
                <div style={{ marginBottom: 12 }}>
                  <div style={{ fontSize: 11, color: '#888' }}>参与配件(点击切换,至少 2 个)</div>
                  <div style={{ marginTop: 6 }}>
                    {parts.map((p, pi) => {
                      const sel = a.participatingPartIndexes.includes(pi);
                      return (
                        <Tag.CheckableTag
                          key={pi}
                          checked={sel}
                          onChange={() => togglePart(a.defCode, pi)}
                          style={{ marginRight: 6, marginBottom: 4 }}
                        >
                          {p.name}
                        </Tag.CheckableTag>
                      );
                    })}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  {schema.map(pm => (
                    <div key={pm.id} style={{ flex: 1, minWidth: 140 }}>
                      <div style={{ fontSize: 11, color: '#888', marginBottom: 4 }}>
                        {pm.label}{pm.unit ? ` (${pm.unit})` : ''}
                      </div>
                      {pm.type === 'number' ? (
                        <InputNumber
                          style={{ width: '100%' }}
                          placeholder={pm.placeholder}
                          value={a.params[pm.id]}
                          onChange={(v) => setParam(a.defCode, pm.id, v)}
                        />
                      ) : (
                        <Input
                          placeholder={pm.placeholder}
                          value={a.params[pm.id] ?? ''}
                          onChange={(e) => setParam(a.defCode, pm.id, e.target.value)}
                        />
                      )}
                    </div>
                  ))}
                </div>
              </Card>
            );
          })
        )}
      </div>
    </div>
  );
};

export default Step4CompositeProcess;
