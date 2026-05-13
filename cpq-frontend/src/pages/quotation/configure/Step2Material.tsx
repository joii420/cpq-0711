import React, { useEffect, useState } from 'react';
import { Input, List, Tag, InputNumber, Alert, Spin, Empty } from 'antd';
import { SearchOutlined, LockOutlined } from '@ant-design/icons';
import {
  materialRecipeService,
  type MaterialRecipeLite,
  type MaterialRecipeDetail,
} from '../../../services/materialRecipeService';
import type { PartState } from '../ConfigureProductDrawer';

interface Props {
  part: PartState;
  onUpdate: (patch: Partial<PartState>) => void;
}

const typeBadge: Record<string, { label: string; color: string }> = {
  locked: { label: '标准锁定', color: 'red' },
  editable: { label: '含量可调', color: 'green' },
  partial: { label: '部分可调', color: 'orange' },
};

const Step2Material: React.FC<Props> = ({ part, onUpdate }) => {
  const [recipes, setRecipes] = useState<MaterialRecipeLite[]>([]);
  const [detail, setDetail] = useState<MaterialRecipeDetail | null>(null);
  const [q, setQ] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    materialRecipeService.list().then(setRecipes).catch(() => setRecipes([]));
  }, []);

  useEffect(() => {
    if (!part.selectedRecipeCode) {
      setDetail(null);
      return;
    }
    // 找到 recipe id 后加载 detail
    const r = recipes.find(x => x.code === part.selectedRecipeCode);
    if (r) loadDetail(r.id);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [part.selectedRecipeCode, recipes]);

  const loadDetail = async (id: string) => {
    setLoading(true);
    try {
      const d = await materialRecipeService.detail(id);
      setDetail(d);
      // 初始化 elementOverrides 为默认值(如未填)
      if (!Object.keys(part.elementOverrides).length) {
        const ov: { [k: string]: number } = {};
        d.elements.forEach(e => { ov[e.elementCode] = Number(e.defaultPct); });
        onUpdate({ elementOverrides: ov });
      }
    } finally { setLoading(false); }
  };

  const selectRecipe = (r: MaterialRecipeLite) => {
    if (part.matLocked) return;
    onUpdate({
      selectedRecipeCode: r.code,
      selectedRecipeSymbol: r.symbol,
      elementOverrides: {},  // reset, useEffect 会重填
    });
  };

  const setElem = (code: string, pct: number) => {
    onUpdate({ elementOverrides: { ...part.elementOverrides, [code]: pct } });
  };

  const filtered = recipes.filter(r =>
    !q.trim()
      || r.symbol.toLowerCase().includes(q.toLowerCase())
      || r.name.includes(q)
      || (r.specLabel ?? '').includes(q),
  );

  const displayRecipes = part.matLocked
    ? recipes.filter(r => r.code === part.selectedRecipeCode)
    : filtered;

  const sumPct = Object.values(part.elementOverrides).reduce((a, b) => a + (Number(b) || 0), 0);
  const sumOk = Math.abs(sumPct - 100) < 0.01;

  return (
    <div style={{ display: 'flex', gap: 16, height: 480 }}>
      <div style={{ width: 280, borderRight: '0.5px solid #eee', paddingRight: 12, display: 'flex', flexDirection: 'column' }}>
        {!part.matLocked && (
          <Input
            prefix={<SearchOutlined />}
            placeholder="化学式 / 名称…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ marginBottom: 8 }}
          />
        )}
        <List
          style={{ flex: 1, overflow: 'auto' }}
          dataSource={displayRecipes}
          locale={{ emptyText: <Empty description="无匹配材质" /> }}
          renderItem={(r) => {
            const isSel = r.code === part.selectedRecipeCode;
            const t = typeBadge[r.recipeType] || typeBadge.editable;
            return (
              <List.Item
                onClick={() => selectRecipe(r)}
                style={{
                  cursor: part.matLocked ? 'not-allowed' : 'pointer',
                  background: isSel ? '#f0effe' : undefined,
                  padding: 8,
                  borderRadius: 6,
                  marginBottom: 2,
                }}
              >
                <List.Item.Meta
                  title={<><b>{r.symbol}</b> {r.name}</>}
                  description={r.specLabel}
                />
                <Tag color={t.color}>{t.label}</Tag>
              </List.Item>
            );
          }}
        />
      </div>

      <div style={{ flex: 1, overflow: 'auto' }}>
        <Spin spinning={loading}>
          {!detail ? (
            <Empty description="从左侧选择材质" />
          ) : (
            <>
              <h3>{detail.symbol} {detail.name}</h3>
              <div style={{ color: '#888', marginBottom: 12 }}>配比 {detail.specLabel ?? '—'}</div>

              {part.matLocked && (
                <Alert
                  icon={<LockOutlined />}
                  type="warning"
                  showIcon
                  message="料号已绑定该材质,元素含量锁定"
                  style={{ marginBottom: 12 }}
                />
              )}

              <List
                dataSource={detail.elements}
                renderItem={(e) => {
                  const canEdit = !part.matLocked && !e.isLocked;
                  const v = part.elementOverrides[e.elementCode] ?? Number(e.defaultPct);
                  return (
                    <List.Item>
                      <List.Item.Meta
                        avatar={<Tag color="purple">{e.elementCode}</Tag>}
                        title={
                          <>
                            {e.elementName}
                            {canEdit && (
                              <span style={{ color: '#aaa', fontSize: 11, marginLeft: 8 }}>
                                ({Number(e.minPct)}–{Number(e.maxPct)}%)
                              </span>
                            )}
                          </>
                        }
                      />
                      {canEdit ? (
                        <InputNumber
                          value={v}
                          min={Number(e.minPct)}
                          max={Number(e.maxPct)}
                          step={0.1}
                          onChange={(n) => setElem(e.elementCode, Number(n ?? 0))}
                          addonAfter="%"
                        />
                      ) : (
                        <span style={{ color: '#999' }}>{v}% <LockOutlined /></span>
                      )}
                    </List.Item>
                  );
                }}
              />

              {!part.matLocked && (
                <Alert
                  style={{ marginTop: 12 }}
                  type={sumOk ? 'success' : 'warning'}
                  showIcon
                  message={
                    sumOk
                      ? `含量之和 ${sumPct.toFixed(1)}%,配比正确`
                      : `含量之和 ${sumPct.toFixed(1)}%,建议调整至 100`
                  }
                />
              )}
            </>
          )}
        </Spin>
      </div>
    </div>
  );
};

export default Step2Material;
