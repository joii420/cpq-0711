/**
 * 材质**新建**抽屉 · 配方卡片（task-260901 · F-13）——对照 `原型图/6-新建材质抽屉.html` 状态 A / B / C / D / E。
 *
 * 🚫 **与「编辑材质」是两种不同形态，不要合并实现**：新建时元素组成还不存在，
 *    它是**从配方卡片推导**出来的（闸门 A 第四轮裁决）。
 *
 * M-0a：保存时校验**各卡片的元素种类集合相同**，一致则该集合即为材质的元素组成
 *       （`sort_order` 取自配方 1 内的元素顺序）。
 *       这与**导入**的 M-5b 是同一条规则的两个入口 —— 判据、错误文案、
 *       「不一致就整体拒绝」的处置必须一致，故共用 `recipeContentRules.ts`。
 *
 * 提交走 `POST /material-recipes` **一次调用**（建材质 + 组成 + 全部配置，一个事务）。
 * 🚫 不要拆成「先建材质再逐条建配置」—— 中途失败会留下一个没有配置的半成品材质并白占编号。
 *
 * X-6（输入便利，不改变校验语义）：新增第 2 张及以后的卡片时，**预填第 1 张的元素种类、含量留空**；
 * 预填项仍可删改，保存时照样按元素种类集合逐张比对。
 *
 * → 服务 AC-33 / AC-34 / AC-35 / AC-16 / AC-24
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  Drawer, Form, Input, Select, InputNumber, Switch, Button, Space, Table,
  Alert, Tag, Tooltip, message,
} from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import {
  materialRecipeService,
  type MaterialRecipeCreateRequest,
} from '../../services/materialRecipeService';
import { elementService, type ElementItem } from '../../services/elementService';
import { genUUID } from '../../utils/uuid';
import { apiErrorCode, apiErrorMessage } from '../../utils/apiError';
import {
  buildElementOptions, filterElementOption, sortElementOption,
  ELEMENT_NOT_FOUND_TEXT, type ElementOption,
} from './elementOptions';
import {
  compositionInconsistentText,
  configDuplicatedInRequestText,
  configsValueEqual,
  elementNoSet,
  formatElementSet,
  isPctLegal,
  isSumOk,
  pctIllegalText,
  setsEqual,
  sumDisplayPct,
  sumGapText,
  sumPct,
} from './recipeContentRules';

const MAX_SYMBOL_LEN = 32;

interface CardRow {
  key: string;
  elementNo: string | null;
  elementCode: string;
  elementName: string;
  /** 用户输入的原始字符串（去尾随零形态）。空串 = 未填 */
  pct: string;
}

interface RecipeCard {
  id: string;
  rows: CardRow[];
}

interface Props {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}

const emptyRow = (): CardRow => ({
  key: genUUID(), elementNo: null, elementCode: '', elementName: '', pct: '',
});
const emptyCard = (): RecipeCard => ({ id: genUUID(), rows: [emptyRow()] });

const MaterialRecipeCreateDrawer: React.FC<Props> = ({ open, onClose, onCreated }) => {
  const [form] = Form.useForm();
  const [symbolValue, setSymbolValue] = useState('');
  const [allowCustomContent, setAllowCustomContent] = useState(false);
  const [cards, setCards] = useState<RecipeCard[]>([emptyCard()]);
  const [saving, setSaving] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const [serverSymbolError, setServerSymbolError] = useState<string | null>(null);

  const [elementDict, setElementDict] = useState<ElementItem[]>([]);
  const [dictLoading, setDictLoading] = useState(false);
  const [dictError, setDictError] = useState(false);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    form.setFieldsValue({ recipeType: 'locked', sortOrder: 100 });
    setSymbolValue('');
    setAllowCustomContent(false);
    setCards([emptyCard()]);
    setServerError(null);
  }, [open, form]);

  useEffect(() => {
    if (!open) return;
    setDictLoading(true);
    setDictError(false);
    elementService.list()
      .then(setElementDict)
      .catch(() => {
        setDictError(true);
        message.error('元素字典加载失败，请刷新重试');
      })
      .finally(() => setDictLoading(false));
  }, [open]);

  // ── 卡片编辑 ──
  const patchCard = (cardId: string, fn: (c: RecipeCard) => RecipeCard) =>
    setCards((prev) => prev.map((c) => (c.id === cardId ? fn(c) : c)));

  const addCard = () => {
    setCards((prev) => {
      // X-6：预填第 1 张卡片的元素种类，含量留空
      const seed = prev[0];
      const rows: CardRow[] = seed && seed.rows.some((r) => r.elementNo)
        ? seed.rows
            .filter((r) => r.elementNo)
            .map((r) => ({ key: genUUID(), elementNo: r.elementNo, elementCode: r.elementCode, elementName: r.elementName, pct: '' }))
        : [emptyRow()];
      return [...prev, { id: genUUID(), rows }];
    });
  };

  const removeCard = (cardId: string) => setCards((prev) => prev.filter((c) => c.id !== cardId));

  const addRow = (cardId: string) => patchCard(cardId, (c) => ({ ...c, rows: [...c.rows, emptyRow()] }));
  const removeRow = (cardId: string, key: string) =>
    patchCard(cardId, (c) => ({ ...c, rows: c.rows.filter((r) => r.key !== key) }));

  const setRowElement = (cardId: string, key: string, no: string) => {
    const hit = elementDict.find((e) => e.elementNo === no);
    if (!hit) return;
    patchCard(cardId, (c) => ({
      ...c,
      rows: c.rows.map((r) =>
        r.key === key
          ? { ...r, elementNo: hit.elementNo, elementCode: hit.elementCode, elementName: hit.elementName }
          : r),
    }));
  };

  const setRowPct = (cardId: string, key: string, pct: string) =>
    patchCard(cardId, (c) => ({
      ...c,
      rows: c.rows.map((r) => (r.key === key ? { ...r, pct } : r)),
    }));

  // ── 校验 ──
  const symbolError = useMemo(() => {
    const v = symbolValue.trim();
    if (!v) return '请填写材质名 / 化学式';
    if (v.length > MAX_SYMBOL_LEN) return `材质名最多 ${MAX_SYMBOL_LEN} 字符，当前 ${v.length} 字符`;
    return null;
  }, [symbolValue]);

  /** 单卡内错误（不含跨卡比对） */
  const cardErrors = useMemo(() => cards.map((c) => {
    const filled = c.rows.filter((r) => r.elementNo);
    if (filled.length === 0) return '该配方至少要有 1 个元素';
    const nos = filled.map((r) => r.elementNo!);
    if (new Set(nos).size !== nos.length) return '同一配方内元素重复，请删除多余的行';
    const bad = filled.find((r) => !isPctLegal(r.pct));
    if (bad) return pctIllegalText(bad.elementCode);
    if (!isSumOk(sumPct(filled))) {
      return `该配方合计为 ${sumDisplayPct(sumPct(filled))}%，${sumGapText(sumPct(filled))}`;
    }
    return null;
  }), [cards]);

  /** M-0a 跨卡片元素种类集合一致性（与导入侧 M-5b 同一判据） */
  const crossCardError = useMemo(() => {
    const usable = cards
      .map((c, i) => ({ idx: i + 1, rows: c.rows.filter((r) => r.elementNo) }))
      .filter((c) => c.rows.length > 0);
    if (usable.length < 2) return null;
    const base = usable[0];
    const baseSet = elementNoSet(base.rows as any);
    for (let k = 1; k < usable.length; k++) {
      const cur = usable[k];
      if (!setsEqual(baseSet, elementNoSet(cur.rows as any))) {
        return compositionInconsistentText(
          base.idx, cur.idx,
          formatElementSet(base.rows as any),
          formatElementSet(cur.rows as any),
        );
      }
    }
    // CONFIG_DUPLICATED_IN_REQUEST：两组配方内容逐值相同
    for (let a = 0; a < usable.length; a++) {
      for (let b = a + 1; b < usable.length; b++) {
        const ra = usable[a].rows.map((r) => ({ elementNo: r.elementNo!, pct: r.pct }));
        const rb = usable[b].rows.map((r) => ({ elementNo: r.elementNo!, pct: r.pct }));
        if (configsValueEqual(ra, rb)) {
          return configDuplicatedInRequestText(usable[a].idx, usable[b].idx);
        }
      }
    }
    return null;
  }, [cards]);

  const blockReason = useMemo<string | null>(() => {
    if (symbolError && cards.every((c) => c.rows.every((r) => !r.elementNo))) {
      return '请填写材质名，并至少完成 1 张配方';
    }
    if (symbolError) return symbolError;
    if (cards.length === 0) return '至少要有 1 张配方';
    const firstCardErr = cardErrors.findIndex((e) => !!e);
    if (firstCardErr >= 0) return `配方${firstCardErr + 1}：${cardErrors[firstCardErr]}`;
    if (crossCardError) return crossCardError;
    return null;
  }, [symbolError, cards, cardErrors, crossCardError]);

  /** 元素组成预告：取配方 1 的元素与顺序 */
  const derivedComposition = useMemo(
    () => (cards[0]?.rows ?? []).filter((r) => r.elementNo).map((r) => r.elementCode),
    [cards],
  );

  const handleSubmit = async () => {
    if (blockReason) return;
    let values: any;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setSaving(true);
    setServerError(null);
    try {
      const req: MaterialRecipeCreateRequest = {
        symbol: symbolValue.trim(),
        name: values.name?.trim() || null,
        specLabel: null,
        recipeType: values.recipeType ?? 'locked',
        allowCustomContent,
        sortOrder: values.sortOrder ?? 100,
        status: 'ACTIVE',
        // 🚫 请求体不含 composition —— 服务端从 configs 推导（各组元素种类须相同，取第 1 组的元素与顺序）
        configs: cards.map((c) => ({
          remark: null,
          elements: c.rows
            .filter((r) => r.elementNo)
            // 🚨 原样发送用户输入的字符串（不补零、不过 JS number）
            .map((r) => ({ elementNo: r.elementNo!, defaultPct: r.pct })),
        })),
      };
      const created = await materialRecipeService.create(req);
      message.success(`材质 ${created?.code ?? ''} 已创建`);
      onCreated();
    } catch (e: unknown) {
      // 🚨 按字符串错误码分支（`err.payload.code`），不按文案匹配
      const code = apiErrorCode(e);
      const msg = apiErrorMessage(e, '保存失败');
      setServerError(null);
      setServerSymbolError(null);
      if (code === 'RECIPE_SYMBOL_DUPLICATED' || code === 'RECIPE_SYMBOL_TOO_LONG') {
        setServerSymbolError(msg);
      } else {
        // COMPOSITION_INCONSISTENT_ACROSS_CONFIGS / CONFIG_DUPLICATED_IN_REQUEST /
        // COMPOSITION_EMPTY / COMPOSITION_ELEMENT_DUPLICATED 等都指向配方区，走顶部聚合告警
        setServerError(msg);
      }
    } finally {
      setSaving(false);
    }
  };

  const renderCard = (card: RecipeCard, idx: number) => {
    const filled = card.rows.filter((r) => r.elementNo);
    const sum = sumPct(filled);
    const ok = filled.length > 0 && isSumOk(sum) && !filled.some((r) => !isPctLegal(r.pct));
    const err = cardErrors[idx];
    const selectedNos = new Set(filled.map((r) => r.elementNo!));

    const columns = [
      {
        title: '元素',
        key: 'element',
        width: 260,
        render: (_: unknown, r: CardRow) => {
          const options: ElementOption[] = buildElementOptions(elementDict, selectedNos, r.elementNo);
          return (
            <Select<string>
              showSearch
              style={{ width: '100%' }}
              value={r.elementNo ?? undefined}
              placeholder="请选择元素"
              loading={dictLoading}
              options={options}
              filterOption={filterElementOption as any}
              filterSort={sortElementOption as any}
              notFoundContent={dictError ? '元素字典加载失败' : ELEMENT_NOT_FOUND_TEXT}
              onChange={(no) => setRowElement(card.id, r.key, no)}
            />
          );
        },
      },
      {
        title: '元素名称',
        key: 'elementName',
        width: 140,
        render: (_: unknown, r: CardRow) => (
          <span style={{ color: 'rgba(0,0,0,.65)' }}>{r.elementName || '—'}</span>
        ),
      },
      {
        title: '含量(%)',
        key: 'pct',
        render: (_: unknown, r: CardRow) => {
          const bad = !!r.elementNo && !isPctLegal(r.pct);
          return (
            <div>
              <InputNumber<string>
                stringMode
                // 🚫 不设 min/max：越界值要能被输入并当场标红（原型 6 状态 D）
                status={bad ? 'error' : undefined}
                style={{ width: 180 }}
                value={r.pct === '' ? null : r.pct}
                placeholder="支持 12 位小数"
                onChange={(v) => setRowPct(card.id, r.key, v ?? '')}
              />
              {bad && (
                <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>{pctIllegalText()}</div>
              )}
            </div>
          );
        },
      },
      {
        title: '',
        key: 'op',
        width: 60,
        render: (_: unknown, r: CardRow) => (
          <Button
            type="link"
            size="small"
            danger
            disabled={card.rows.length === 1}
            onClick={() => removeRow(card.id, r.key)}
          >
            删除
          </Button>
        ),
      },
    ];

    return (
      <div
        key={card.id}
        data-testid={`recipe-card-${idx + 1}`}
        style={{
          border: `1px solid ${err ? '#ffccc7' : '#f0f0f0'}`,
          borderRadius: 8,
          marginBottom: 12,
          overflow: 'hidden',
          background: '#fff',
        }}
      >
        <div
          style={{
            display: 'flex', alignItems: 'center', gap: 8, padding: '10px 14px',
            background: err ? '#fff2f0' : '#fafafa', borderBottom: '1px solid #f0f0f0',
          }}
        >
          <b>配方{idx + 1}</b>
          <div style={{ flex: 1 }} />
          <Button size="small" icon={<PlusOutlined />} onClick={() => addRow(card.id)}>添加元素</Button>
          <Tooltip title={cards.length === 1 ? '至少要保留 1 张配方' : undefined}>
            <span>
              <Button
                type="link"
                size="small"
                danger
                disabled={cards.length === 1}
                icon={<DeleteOutlined />}
                onClick={() => removeCard(card.id)}
              >
                删除本配方
              </Button>
            </span>
          </Tooltip>
        </div>
        <div style={{ padding: '4px 14px' }}>
          <Table<CardRow>
            rowKey="key"
            size="small"
            pagination={false}
            dataSource={card.rows}
            columns={columns as any}
          />
        </div>
        <div
          style={{
            display: 'flex', alignItems: 'center', gap: 10, padding: '8px 14px',
            borderTop: '1px solid #f0f0f0', background: '#fafafa',
          }}
        >
          <span>合计 <b style={{ fontFamily: 'Consolas, monospace' }}>{sumDisplayPct(sum)}%</b></span>
          {ok ? <Tag color="green">符合</Tag> : <Tag color="red">{sumGapText(sum)}</Tag>}
          {err && (
            <>
              <div style={{ flex: 1 }} />
              <span style={{ color: '#ff4d4f', fontSize: 12 }}>{err}</span>
            </>
          )}
        </div>
      </div>
    );
  };

  const saveBtn = (
    <Button type="primary" loading={saving} disabled={!!blockReason} onClick={handleSubmit}>
      保存
    </Button>
  );

  return (
    <Drawer
      title="新建材质"
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
            {blockReason ? <Tooltip title={blockReason}><span>{saveBtn}</span></Tooltip> : saveBtn}
          </Space>
        </div>
      }
    >
      {serverError && <Alert type="error" showIcon style={{ marginBottom: 16 }} message={serverError} />}

      {crossCardError && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message={crossCardError}
          description="同一组元素才是同一个材质 —— 元素种类不同的两组配比，属于两个材质，请分开新建。"
        />
      )}

      <Form form={form} layout="vertical">
        <Space size="large" wrap align="start">
          <Form.Item label="材质编号">
            <Input style={{ width: 260 }} disabled value="保存后自动生成（5 位补零自增）" />
          </Form.Item>
          {/* ⚠️ 受控组件，故不挂 name */}
          <Form.Item
            label="材质名 / 化学式"
            required
            validateStatus={(symbolError || serverSymbolError) ? 'error' : undefined}
            help={symbolError ?? serverSymbolError ?? '最多 32 字符。材质名即材质身份，导入时按它匹配材质。'}
          >
            <Input
              id="symbol"
              style={{ width: 320 }}
              value={symbolValue}
              placeholder="请输入材质名"
              onChange={(e) => { setSymbolValue(e.target.value); setServerSymbolError(null); setServerError(null); }}
            />
          </Form.Item>
          <Form.Item name="name" label="名称">
            <Input placeholder="留空则与材质名相同" style={{ width: 220 }} />
          </Form.Item>
          <Form.Item name="recipeType" label="类型" rules={[{ required: true }]}>
            {/* 🚫 固定枚举，不开 showSearch（AC-35 反向断言） */}
            <Select
              style={{ width: 140 }}
              options={[
                { value: 'locked', label: '标准锁定' },
                { value: 'editable', label: '含量可调' },
                { value: 'partial', label: '部分可调' },
              ]}
            />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber min={0} style={{ width: 100 }} />
          </Form.Item>
        </Space>
      </Form>

      <div style={{ marginBottom: 20 }}>
        <div style={{ fontSize: 14, marginBottom: 8 }}><b>支持自定义含量</b></div>
        <Switch checked={allowCustomContent} onChange={setAllowCustomContent} />
        <span style={{ marginLeft: 10, fontSize: 13, color: 'rgba(0,0,0,.65)' }}>
          {allowCustomContent ? '开' : '关（默认）'}
        </span>
        <div style={{ color: 'rgba(0,0,0,.45)', fontSize: 12, marginTop: 4 }}>
          打开后，选配可让用户自行输入各元素含量（仍强制合计 = 100%，且只能改含量不能改元素）。
        </div>
      </div>

      <div style={{ fontSize: 14, marginBottom: 8 }}>
        <span style={{ color: '#ff4d4f', marginRight: 4 }}>*</span><b>含量配方</b>
      </div>
      <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={addCard}>添加配方</Button>
        <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
          已有 {cards.length} 张配方
          {derivedComposition.length > 0 && <>，元素组成将取自 <b>配方1</b>：{derivedComposition.join('、')}</>}
        </span>
      </div>

      {cards.map(renderCard)}

      <div
        style={{
          borderLeft: '3px solid #1677ff', background: '#f0f5ff', padding: '10px 14px',
          borderRadius: '0 6px 6px 0', fontSize: 12, color: 'rgba(0,0,0,.65)', lineHeight: 1.8,
        }}
      >
        保存后一次性生成：材质编号 + 元素组成（取自配方1 的元素及其顺序）+ 每张配方一条含量配置。
        <b>整个请求要么全成要么全不成</b> —— 任一张配方不合法则材质、组成、配置都不落库，编号也不消耗。
        <br />
        <b>至少要有 1 张配方、每张至少 1 个元素</b> —— 没有配方就推导不出元素组成，材质也就无从建起。
      </div>
    </Drawer>
  );
};

export default MaterialRecipeCreateDrawer;
