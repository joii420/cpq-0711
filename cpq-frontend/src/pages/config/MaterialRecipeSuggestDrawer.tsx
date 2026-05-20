import React, { useEffect, useMemo, useState } from 'react';
import {
  Drawer, Button, Space, Table, Tag, Empty, Alert, message, Tooltip, Select, Statistic, Row, Col,
} from 'antd';
import { ThunderboltOutlined, CheckCircleOutlined, MinusCircleOutlined } from '@ant-design/icons';
import {
  materialRecipeService,
  type BindingSuggestion,
  type SuggestionCandidate,
  type MaterialRecipeLite,
} from '../../services/materialRecipeService';

interface Props {
  open: boolean;
  onClose: () => void;
  /** 确认绑定后刷新外层列表 + 当前 Drawer 关闭 */
  onConfirmed: () => void;
}

/** 单行决策: undefined = 待定 / 'IGNORE' = 忽略 / recipeId = 选定绑定 */
type RowDecision = string | 'IGNORE' | undefined;

const confidenceTag: Record<string, { label: string; color: string }> = {
  EXACT_CODE:   { label: '精确(代码)', color: 'green' },
  EXACT_SYMBOL: { label: '精确(化学式)', color: 'cyan' },
  PREFIX_MATCH: { label: '前缀',         color: 'orange' },
};

const MaterialRecipeSuggestDrawer: React.FC<Props> = ({ open, onClose, onConfirmed }) => {
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [suggestions, setSuggestions] = useState<BindingSuggestion[]>([]);
  const [allRecipes, setAllRecipes] = useState<MaterialRecipeLite[]>([]);
  const [decisions, setDecisions] = useState<Record<string, RowDecision>>({});

  useEffect(() => {
    if (!open) {
      setSuggestions([]);
      setDecisions({});
      return;
    }
    setLoading(true);
    Promise.all([
      materialRecipeService.suggestBindings(),
      materialRecipeService.list(),
    ])
      .then(([sugs, recipes]) => {
        setSuggestions(sugs);
        setAllRecipes(recipes);
        // 默认决策:候选非空时,选置信度最高的那个;否则未定
        const initial: Record<string, RowDecision> = {};
        for (const s of sugs) {
          if (s.candidates.length > 0) {
            initial[s.partNo] = s.candidates[0].recipeId;
          }
        }
        setDecisions(initial);
      })
      .catch((e: any) => message.error(e?.response?.data?.message ?? e?.message ?? '加载失败'))
      .finally(() => setLoading(false));
  }, [open]);

  const stats = useMemo(() => {
    const total = suggestions.length;
    let withCandidates = 0;
    let confirmed = 0;
    let ignored = 0;
    for (const s of suggestions) {
      if (s.candidates.length > 0) withCandidates++;
      const d = decisions[s.partNo];
      if (d === 'IGNORE') ignored++;
      else if (d) confirmed++;
    }
    return { total, withCandidates, confirmed, ignored, pending: total - confirmed - ignored };
  }, [suggestions, decisions]);

  const setDecision = (partNo: string, d: RowDecision) => {
    setDecisions(prev => ({ ...prev, [partNo]: d }));
  };

  // 批量"全部接受推荐":对每行 candidates[0] 设为决策
  const acceptAllTop = () => {
    const next: Record<string, RowDecision> = { ...decisions };
    for (const s of suggestions) {
      if (s.candidates.length > 0 && next[s.partNo] !== 'IGNORE') {
        next[s.partNo] = s.candidates[0].recipeId;
      }
    }
    setDecisions(next);
  };

  const ignoreAllWithoutCandidates = () => {
    const next: Record<string, RowDecision> = { ...decisions };
    for (const s of suggestions) {
      if (s.candidates.length === 0) {
        next[s.partNo] = 'IGNORE';
      }
    }
    setDecisions(next);
  };

  const handleConfirm = async () => {
    const items: Array<{ partNo: string; recipeId: string }> = [];
    for (const [partNo, d] of Object.entries(decisions)) {
      if (d && d !== 'IGNORE') {
        items.push({ partNo, recipeId: d });
      }
    }
    if (items.length === 0) {
      message.warning('没有待确认的绑定项');
      return;
    }
    setSubmitting(true);
    try {
      const res = await materialRecipeService.confirmBindings(items);
      message.success(`已确认绑定 ${res.updated} 个料号`);
      onConfirmed();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? e?.message ?? '绑定失败');
    } finally {
      setSubmitting(false);
    }
  };

  const recipeOptions = useMemo(() =>
    allRecipes.map(r => ({ value: r.id, label: `${r.code} ${r.name}` })),
    [allRecipes],
  );

  const columns = [
    {
      title: '料号',
      dataIndex: 'partNo',
      width: 140,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    { title: '品名', dataIndex: 'partName', width: 140 },
    {
      title: '依据(BOM 元素名)',
      dataIndex: 'sourceHints',
      width: 200,
      render: (hints: string[]) => (
        hints.length === 0
          ? <span style={{ color: '#bbb' }}>无</span>
          : <Space size={4} wrap>{hints.map(h => <Tag key={h}>{h}</Tag>)}</Space>
      ),
    },
    {
      title: '候选材质',
      key: 'candidates',
      render: (_: unknown, s: BindingSuggestion) => {
        if (s.candidates.length === 0) {
          return <span style={{ color: '#bbb' }}>无候选,请手动选择</span>;
        }
        return (
          <Space direction="vertical" size={2} style={{ width: '100%' }}>
            {s.candidates.map(c => (
              <span key={c.recipeId} style={{ fontSize: 12 }}>
                <Tag color={confidenceTag[c.confidence]?.color}>{confidenceTag[c.confidence]?.label ?? c.confidence}</Tag>
                <b>{c.recipeCode}</b> <span style={{ color: '#888' }}>{c.recipeName}</span>
                <span style={{ color: '#bbb', marginLeft: 6 }}>← {c.matchedOn}</span>
              </span>
            ))}
          </Space>
        );
      },
    },
    {
      title: '决策',
      key: 'decision',
      width: 280,
      render: (_: unknown, s: BindingSuggestion) => {
        const d = decisions[s.partNo];
        return (
          <Space>
            <Select
              size="small"
              style={{ width: 180 }}
              placeholder="选择材质"
              value={d === 'IGNORE' ? undefined : d}
              onChange={(v) => setDecision(s.partNo, v as string | undefined)}
              options={recipeOptions}
              showSearch
              optionFilterProp="label"
              allowClear
            />
            <Tooltip title="忽略此行 — 本次不绑定">
              <Button
                size="small"
                type={d === 'IGNORE' ? 'primary' : 'default'}
                danger={d === 'IGNORE'}
                icon={<MinusCircleOutlined />}
                onClick={() => setDecision(s.partNo, d === 'IGNORE' ? undefined : 'IGNORE')}
              />
            </Tooltip>
          </Space>
        );
      },
    },
  ];

  return (
    <Drawer
      title={<><ThunderboltOutlined /> 未绑料号 - 智能建议绑定</>}
      open={open}
      onClose={onClose}
      width={1200}
      placement="right"
      maskClosable={false}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Space>
            <Button onClick={onClose}>取消</Button>
            <Button
              type="primary"
              icon={<CheckCircleOutlined />}
              loading={submitting}
              disabled={stats.confirmed === 0}
              onClick={handleConfirm}
            >
              批量确认绑定 ({stats.confirmed})
            </Button>
          </Space>
        </div>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="算法说明"
        description={
          <>
            扫描 <code>mat_part.material_recipe_id IS NULL</code> 的料号 → 对照该料号 <code>mat_bom (bom_type='ELEMENT')</code>
            的 element_name 反查 <code>material_recipe.code / symbol</code>:
            <b style={{ color: '#52c41a' }}> 精确(代码)</b> &gt;
            <b style={{ color: '#13c2c2' }}> 精确(化学式)</b> &gt;
            <b style={{ color: '#fa8c16' }}> 前缀</b>。
            纯元素(Cu/Ag/Ni)和数字串(25.85)会被过滤。
            <br />
            <b>注</b>:确认前请人工 review 每行候选;无候选的料号可手动选材质,或点 <MinusCircleOutlined /> 忽略。
          </>
        }
      />

      <Row gutter={12} style={{ marginBottom: 12 }}>
        <Col span={5}><Statistic title="未绑料号" value={stats.total} /></Col>
        <Col span={5}><Statistic title="有候选" value={stats.withCandidates} valueStyle={{ color: '#52c41a' }} /></Col>
        <Col span={5}><Statistic title="已选定" value={stats.confirmed} valueStyle={{ color: '#1677ff' }} /></Col>
        <Col span={5}><Statistic title="忽略" value={stats.ignored} valueStyle={{ color: '#999' }} /></Col>
        <Col span={4}><Statistic title="待定" value={stats.pending} valueStyle={{ color: '#fa8c16' }} /></Col>
      </Row>

      <Space style={{ marginBottom: 12 }}>
        <Button size="small" onClick={acceptAllTop}>接受所有"置信度最高"推荐</Button>
        <Button size="small" onClick={ignoreAllWithoutCandidates}>忽略无候选的行</Button>
      </Space>

      <Table
        rowKey="partNo"
        columns={columns as any}
        dataSource={suggestions}
        loading={loading}
        pagination={{ pageSize: 20, showSizeChanger: true }}
        scroll={{ y: 480 }}
        size="small"
        locale={{ emptyText: <Empty description="无未绑料号 — 所有料号都已绑定材质" /> }}
      />
    </Drawer>
  );
};

export default MaterialRecipeSuggestDrawer;
