import React, { useEffect, useState } from 'react';
import { Input, List, Tag, Button, Empty } from 'antd';
import { SearchOutlined, PlusOutlined, CloseOutlined } from '@ant-design/icons';
import api from '../../../services/api';
import type { PartState } from '../ConfigureProductDrawer';

interface Process {
  id: string;
  code: string;
  name: string;
  categoryName?: string;
}

interface Props {
  part: PartState;
  onUpdate: (patch: Partial<PartState>) => void;
}

const Step3Process: React.FC<Props> = ({ part, onUpdate }) => {
  const [allProcs, setAllProcs] = useState<Process[]>([]);
  const [q, setQ] = useState('');

  useEffect(() => {
    // 通用工序字典 — 假定有 /processes 端点
    api.get('/processes', { params: { status: 'ACTIVE', size: 200 } })
      .then((res: any) => {
        // 处理可能的两种返回结构: 数组 / { data: 数组 } / { content: 数组 }
        const list = Array.isArray(res) ? res
          : (res?.data ?? res?.content ?? []);
        setAllProcs(Array.isArray(list) ? list : []);
      })
      .catch(() => setAllProcs([]));
  }, []);

  /**
   * 2026-05-19: 复用料号场景下用现有工序作为预填起点.
   *
   * 触发条件: allProcs 加载完 + reusedFromExisting 有 snapshot.processes + 当前 processIds 为空.
   * 把 snapshot 里的 processCode 映射成 processId 写回 part.processIds — 用户可在 UI 直接改/删/加,
   * 提交时后端 resolvePart `existing+processIds` 分支会用这些 processIds 覆盖当前客户的 mat_process.
   *
   * 仅当 processIds 真为空时预填 — 否则用户已经手动改过,不要被预填覆盖.
   */
  useEffect(() => {
    if (allProcs.length === 0) return;
    if (part.processIds.length > 0) return;
    const snapshotProcs = part.reusedFromExisting?.snapshot?.processes;
    if (!snapshotProcs || snapshotProcs.length === 0) return;
    const mapped = snapshotProcs
      .map(sp => allProcs.find(p => p.code === sp.processCode)?.id)
      .filter((x): x is string => !!x);
    if (mapped.length > 0) {
      onUpdate({ processIds: mapped });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allProcs, part.reusedFromExisting]);

  const filtered = allProcs.filter(p => {
    const kw = q.trim().toLowerCase();
    if (!kw) return true;
    return p.name.toLowerCase().includes(kw)
      || p.code.toLowerCase().includes(kw)
      || (p.categoryName ?? '').toLowerCase().includes(kw);
  });

  const isAdded = (id: string) => part.processIds.includes(id);

  const toggle = (id: string) => {
    const next = isAdded(id)
      ? part.processIds.filter(x => x !== id)
      : [...part.processIds, id];
    onUpdate({ processIds: next });
  };

  const addedDetailed = part.processIds
    .map(id => allProcs.find(p => p.id === id))
    .filter(Boolean) as Process[];

  return (
    <div style={{ display: 'flex', gap: 16, height: 480 }}>
      <div style={{ width: 280, borderRight: '0.5px solid #eee', paddingRight: 12, display: 'flex', flexDirection: 'column' }}>
        <Input
          prefix={<SearchOutlined />}
          placeholder="搜索工序（编码 / 名称）…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          style={{ marginBottom: 8 }}
        />
        <List
          style={{ flex: 1, overflow: 'auto' }}
          dataSource={filtered}
          locale={{ emptyText: <Empty description="无匹配工序" /> }}
          renderItem={(p) => (
            <List.Item
              style={{
                background: isAdded(p.id) ? '#f0effe' : undefined,
                padding: 8,
                borderRadius: 6,
                marginBottom: 2,
              }}
            >
              <List.Item.Meta
                title={p.name}
                description={
                  <span>
                    <Tag color="blue" style={{ marginRight: 4 }}>{p.code}</Tag>
                    <Tag>{p.categoryName ?? '—'}</Tag>
                  </span>
                }
              />
              <Button
                size="small"
                type={isAdded(p.id) ? 'default' : 'primary'}
                icon={<PlusOutlined />}
                onClick={() => toggle(p.id)}
              >
                {isAdded(p.id) ? '已添加' : '添加'}
              </Button>
            </List.Item>
          )}
        />
      </div>

      <div style={{ flex: 1, overflow: 'auto' }}>
        <h3>已选工序 ({addedDetailed.length})</h3>
        <div style={{ color: '#aaa', fontSize: 12, marginBottom: 12 }}>按添加顺序</div>
        <List
          dataSource={addedDetailed}
          locale={{ emptyText: <Empty description="从左侧添加工序" /> }}
          renderItem={(p, i) => (
            <List.Item
              style={{
                background: '#f8f7ff',
                padding: 8,
                borderRadius: 6,
                marginBottom: 4,
              }}
            >
              <List.Item.Meta
                avatar={<Tag color="purple">{i + 1}</Tag>}
                title={p.name}
                description={
                  <span>
                    <Tag color="blue" style={{ marginRight: 4 }}>{p.code}</Tag>
                    <Tag>{p.categoryName ?? '—'}</Tag>
                  </span>
                }
              />
              <Button type="text" icon={<CloseOutlined />} onClick={() => toggle(p.id)} />
            </List.Item>
          )}
        />
      </div>
    </div>
  );
};

export default Step3Process;
