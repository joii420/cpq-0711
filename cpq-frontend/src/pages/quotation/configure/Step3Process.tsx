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

  const filtered = allProcs.filter(p =>
    !q.trim() || p.name.includes(q) || (p.categoryName ?? '').includes(q),
  );

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
          placeholder="搜索工序…"
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
                description={<Tag>{p.categoryName ?? '—'}</Tag>}
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
                description={<Tag>{p.categoryName ?? '—'}</Tag>}
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
