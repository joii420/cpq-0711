import React, { useEffect, useState } from 'react';
import { Input, List, Tag, InputNumber, Alert, Spin, Empty } from 'antd';
import { SearchOutlined, LockOutlined } from '@ant-design/icons';
import {
  materialRecipeService,
  type MaterialRecipeLite,
  type MaterialRecipeDetail,
  type ExistingPartMaterial,
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

// 把 ExistingPartMaterial 适配成 Step2Material 渲染层用的 detail 形态
function toDetail(m: ExistingPartMaterial): MaterialRecipeDetail | null {
  if (!m.elements.length && !m.recipeBound) return null;
  return {
    id: m.hfPartNo,
    code: m.recipeCode ?? '__BOM__',
    symbol: m.recipeSymbol ?? '导入 BOM',
    name: m.recipeName ?? '已导入材质配比',
    specLabel: m.recipeSpec ?? undefined,
    recipeType: m.recipeType ?? 'locked',
    elements: m.elements.map((e, i) => ({
      elementCode: e.elementCode,
      elementName: e.elementName,
      defaultPct: e.pct,
      minPct: e.minPct ?? undefined,
      maxPct: e.maxPct ?? undefined,
      isLocked: e.isLocked,
      sortOrder: i,
    })),
  };
}

const Step2Material: React.FC<Props> = ({ part, onUpdate }) => {
  const [recipes, setRecipes] = useState<MaterialRecipeLite[]>([]);
  const [detail, setDetail] = useState<MaterialRecipeDetail | null>(null);
  const [existingMat, setExistingMat] = useState<ExistingPartMaterial | null>(null);
  const [q, setQ] = useState('');
  const [loading, setLoading] = useState(false);

  // 自定义路径才需要拉字典列表;锁定路径完全靠 existing-part 端点
  useEffect(() => {
    if (part.matLocked) { setRecipes([]); return; }
    materialRecipeService.list().then(setRecipes).catch(() => setRecipes([]));
  }, [part.matLocked]);

  // 锁定路径:直接调 existing-part endpoint,拿到统一形态后本地构造 detail
  useEffect(() => {
    if (!part.matLocked || !part.selectedHfPartNo) { setExistingMat(null); return; }
    setLoading(true);
    materialRecipeService.loadForExisting(part.selectedHfPartNo)
      .then((m) => {
        setExistingMat(m);
        const d = toDetail(m);
        setDetail(d);
        // 把字典/BOM 派的元素含量同步回 part.elementOverrides,后续 Step5 / 提交需要
        const ov: { [k: string]: number } = {};
        m.elements.forEach(e => { ov[e.elementCode] = Number(e.pct); });
        onUpdate({
          elementOverrides: ov,
          selectedRecipeCode: m.recipeCode ?? null,
          selectedRecipeSymbol: m.recipeSymbol ?? null,
        });
      })
      .catch(() => { setExistingMat(null); setDetail(null); })
      .finally(() => setLoading(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [part.matLocked, part.selectedHfPartNo]);

  // 自定义路径:从字典列表挑选后加载详情
  useEffect(() => {
    if (part.matLocked) return;
    if (!part.selectedRecipeCode) { setDetail(null); return; }
    const r = recipes.find(x => x.code === part.selectedRecipeCode);
    if (r) loadDetailFromDict(r.id);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [part.selectedRecipeCode, recipes, part.matLocked]);

  const loadDetailFromDict = async (id: string) => {
    setLoading(true);
    try {
      const d = await materialRecipeService.detail(id);
      setDetail(d);
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
      elementOverrides: {},
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

  // 锁定时左栏只展示当前料号绑定的"材质卡片"(字典派或 BOM 派);非锁定走字典过滤
  const lockedLeftItem: MaterialRecipeLite | null = part.matLocked && existingMat
    ? {
        id: existingMat.hfPartNo,
        code: existingMat.recipeCode ?? '__BOM__',
        symbol: existingMat.recipeSymbol ?? '导入 BOM',
        name: existingMat.recipeName ?? '已导入材质配比',
        specLabel: existingMat.recipeSpec ?? undefined,
        recipeType: existingMat.recipeType ?? 'locked',
      }
    : null;
  const displayRecipes = part.matLocked
    ? (lockedLeftItem ? [lockedLeftItem] : [])
    : filtered;

  const sumPct = Object.values(part.elementOverrides).reduce((a, b) => a + (Number(b) || 0), 0);
  const sumOk = Math.abs(sumPct - 100) < 0.01;

  const recipeBound = existingMat?.recipeBound ?? true;

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
          locale={{ emptyText: <Empty description={part.matLocked ? '该料号无材质数据' : '无匹配材质'} /> }}
          renderItem={(r) => {
            const isSel = part.matLocked
              ? true
              : r.code === part.selectedRecipeCode;
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
            <Empty description={part.matLocked ? '该料号无材质数据,建议选择"无匹配料号"走自定义路径' : '从左侧选择材质'} />
          ) : (
            <>
              <h3>{detail.symbol} {detail.name}</h3>
              <div style={{ color: '#888', marginBottom: 12 }}>配比 {detail.specLabel ?? '—'}</div>

              {part.matLocked && (
                <Alert
                  icon={<LockOutlined />}
                  type="warning"
                  showIcon
                  message={recipeBound
                    ? '料号已绑定该材质,元素含量锁定'
                    : '该料号未绑定材质字典,展示其导入 BOM 的元素配比,只读不可改'}
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
                            {canEdit && e.minPct !== undefined && e.maxPct !== undefined && (
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
                          min={e.minPct !== undefined ? Number(e.minPct) : 0}
                          max={e.maxPct !== undefined ? Number(e.maxPct) : 100}
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
