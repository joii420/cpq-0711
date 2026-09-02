/**
 * 材质**编辑**抽屉（task-260901 · F-4 / F-3）——对照 `原型图/2-材质编辑抽屉.html` 状态 A / A2 / B / C。
 *
 * 🚫 **本抽屉不再承担「新建材质」** —— 新建是另一种形态（配方卡片），见 `MaterialRecipeCreateDrawer.tsx`
 *    与 `原型图/6-新建材质抽屉.html`。原因：新建时元素组成还不存在，它是**从配方卡片推导**出来的。
 *
 * 三条闸门 A 裁决落在本文件：
 *   ① 抽屉宽度 760 → **1200**（元素种类多时窄抽屉放不下）
 *   ② 含量配置改**矩阵**渲染（行=配置、列=元素），含量**去掉小数点后多余的 0**
 *   ③ **元素组成是材质的显式属性**（D10）—— 独立一区，**不是从配置推出来的**；
 *      矩阵的列与列序完全取自它 ⇒ **0 配置时表头照常渲染**，只有表体走空态
 *
 * M-0b：元素组成的可改性 = 与「材质编号只读」同款的两段式 ——
 *   无任何 ACTIVE 配置时可自由增删改；**一旦有 ACTIVE 配置就整区只读**（增删按钮禁用可见 + hover 给原因）。
 *
 * → 服务 AC-13 / AC-16 / AC-17 / AC-24 / AC-28 / AC-31 / AC-35
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Drawer, Form, Input, Select, InputNumber, Switch, Button, Space, Table,
  Alert, Tooltip, Tag, Spin, message,
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, LockOutlined, HolderOutlined, CloseOutlined,
} from '@ant-design/icons';
import {
  DndContext, PointerSensor, useSensor, useSensors, type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext, useSortable, horizontalListSortingStrategy, arrayMove,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import dayjs from 'dayjs';
import {
  materialRecipeService,
  type CompositionItem,
  type MaterialRecipeConfig,
  type MaterialRecipeDetail,
  type MaterialRecipeUpdateRequest,
} from '../../services/materialRecipeService';
import { elementService, type ElementItem } from '../../services/elementService';
import { formatPctText } from '../../utils/precision';
import { apiErrorCode, apiErrorMessage } from '../../utils/apiError';
import { buildElementOptions, filterElementOption, ELEMENT_NOT_FOUND_TEXT, type ElementOption } from './elementOptions';
import MaterialRecipeConfigDrawer from './MaterialRecipeConfigDrawer';
import MaterialRecipeConfigDeleteDrawer from './MaterialRecipeConfigDeleteDrawer';

const MAX_SYMBOL_LEN = 32;

/** 时间格式化 YYYY-MM-DD HH:mm；空值回退 '—' */
const fmtTime = (v?: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—');

interface Props {
  open: boolean;
  /** 被编辑材质的 id；null 时不渲染内容 */
  recipeId: string | null;
  onClose: () => void;
  onSaved: () => void;
}

// ── 元素组成 chip（可拖动排序 = 矩阵列序） ─────────────────────────────
const SortableChip: React.FC<{
  item: CompositionItem;
  editable: boolean;
  onRemove: (elementNo: string) => void;
}> = ({ item, editable, onRemove }) => {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id: item.elementNo, disabled: !editable });
  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
    border: '1px solid #d9d9d9',
    background: editable ? '#fff' : 'rgba(0,0,0,.04)',
    color: editable ? 'rgba(0,0,0,.88)' : 'rgba(0,0,0,.65)',
    borderRadius: 6,
    padding: '3px 10px',
    marginRight: 8,
    marginBottom: 8,
    fontSize: 13,
  };
  return (
    <span ref={setNodeRef} style={style} {...attributes}>
      {editable && (
        <span style={{ cursor: 'grab', color: '#bfbfbf' }} {...listeners} title="拖动调整列序">
          <HolderOutlined />
        </span>
      )}
      <span style={{ fontFamily: 'Consolas, monospace', color: 'rgba(0,0,0,.45)' }}>{item.elementNo}</span>
      <b>{item.elementCode}</b>
      <span style={{ color: 'rgba(0,0,0,.65)' }}>/ {item.elementName}</span>
      {editable && (
        <CloseOutlined
          style={{ fontSize: 11, color: 'rgba(0,0,0,.45)', cursor: 'pointer' }}
          onClick={() => onRemove(item.elementNo)}
        />
      )}
    </span>
  );
};

const MaterialRecipeEditDrawer: React.FC<Props> = ({ open, recipeId, onClose, onSaved }) => {
  const [form] = Form.useForm();
  const [detail, setDetail] = useState<MaterialRecipeDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  // 后端错误按**字符串错误码**归位到对应字段（原型 2 状态 C），不按文案匹配
  const [serverSymbolError, setServerSymbolError] = useState<string | null>(null);
  const [serverCompositionError, setServerCompositionError] = useState<string | null>(null);

  const [composition, setComposition] = useState<CompositionItem[]>([]);
  const [configs, setConfigs] = useState<MaterialRecipeConfig[]>([]);
  const [allowCustomContent, setAllowCustomContent] = useState(false);
  const [symbolValue, setSymbolValue] = useState('');

  const [addingElement, setAddingElement] = useState(false);
  const [elementDict, setElementDict] = useState<ElementItem[]>([]);
  const [dictLoading, setDictLoading] = useState(false);
  const [dictError, setDictError] = useState(false);

  const [selectedConfigIds, setSelectedConfigIds] = useState<React.Key[]>([]);
  const [configDrawerOpen, setConfigDrawerOpen] = useState(false);
  const [editingConfig, setEditingConfig] = useState<MaterialRecipeConfig | null>(null);
  const [deleteDrawerOpen, setDeleteDrawerOpen] = useState(false);

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 4 } }));

  /** M-0b：有 ACTIVE 配置 ⇒ 元素组成整区只读。以服务端 `compositionEditable` 为准 */
  const compositionEditable = detail?.compositionEditable ?? false;
  const activeConfigCount = configs.length;
  /**
   * 只读原因文案。
   * ⚠️ 正常情况下「只读」必然伴随 ACTIVE 配置数 > 0；但服务端才是权威，
   * 出现「只读且 0 条配置」时不能照着模板拼出「该材质已有 0 条含量配置」这种自相矛盾的话。
   */
  const compositionLockReason = activeConfigCount > 0
    ? `该材质已有 ${activeConfigCount} 条含量配置，元素组成不可修改。换元素组成请新建材质，或先删除全部含量配置`
    : '该材质的元素组成当前不可修改。换元素组成请新建材质，或先删除全部含量配置';

  const clearServerErrors = () => {
    setServerError(null);
    setServerSymbolError(null);
    setServerCompositionError(null);
  };

  const loadDetail = useCallback(async (id: string) => {
    setLoading(true);
    setServerError(null);
    setServerSymbolError(null);
    setServerCompositionError(null);
    try {
      const d = await materialRecipeService.detail(id);
      setDetail(d);
      setComposition([...d.composition].sort((a, b) => a.sortOrder - b.sortOrder));
      setConfigs(d.configs ?? []);
      setAllowCustomContent(!!d.allowCustomContent);
      setSymbolValue(d.symbol ?? '');
      form.setFieldsValue({
        code: d.code,
        symbol: d.symbol,
        name: d.name,
        recipeType: d.recipeType,
        sortOrder: d.sortOrder,
        status: d.status ?? 'ACTIVE',
      });
    } catch (e: any) {
      message.error(e?.message ?? '加载详情失败');
    } finally {
      setLoading(false);
    }
  }, [form]);

  useEffect(() => {
    if (!open || !recipeId) return;
    setSelectedConfigIds([]);
    loadDetail(recipeId);
  }, [open, recipeId, loadDetail]);

  // 元素字典（task-260812：抽屉打开时一次性全量拉取，前端本地过滤）
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

  // ── 元素组成编辑 ──
  const selectedNos = useMemo(() => new Set(composition.map((c) => c.elementNo)), [composition]);
  const addOptions: ElementOption[] = useMemo(
    () => buildElementOptions(elementDict, selectedNos),
    [elementDict, selectedNos],
  );

  const handleAddElement = (no: string) => {
    const hit = elementDict.find((e) => e.elementNo === no);
    if (!hit) return;
    clearServerErrors();
    setComposition((prev) => [
      ...prev,
      {
        elementNo: hit.elementNo,
        elementCode: hit.elementCode,
        elementName: hit.elementName,
        sortOrder: prev.length + 1,
      },
    ]);
    setAddingElement(false);
  };

  const handleRemoveElement = (no: string) => (
    clearServerErrors(),
    setComposition((prev) => prev.filter((c) => c.elementNo !== no).map((c, i) => ({ ...c, sortOrder: i + 1 })))
  );

  const handleDragEnd = (e: DragEndEvent) => {
    const { active, over } = e;
    if (!over || active.id === over.id) return;
    setComposition((prev) => {
      const from = prev.findIndex((c) => c.elementNo === active.id);
      const to = prev.findIndex((c) => c.elementNo === over.id);
      if (from < 0 || to < 0) return prev;
      return arrayMove(prev, from, to).map((c, i) => ({ ...c, sortOrder: i + 1 }));
    });
  };

  // ── 表单校验（前端即时；后端错误码另经 serverError 回显） ──
  const symbolError = useMemo(() => {
    const v = symbolValue.trim();
    if (!v) return '请填写材质名 / 化学式';
    if (v.length > MAX_SYMBOL_LEN) return `材质名最多 ${MAX_SYMBOL_LEN} 字符，当前 ${v.length} 字符`;
    return null;
  }, [symbolValue]);

  const compositionError = composition.length === 0 ? '材质必须至少有一个元素' : null;

  const blockReason = useMemo(() => {
    const errs = [symbolError, compositionError].filter(Boolean) as string[];
    if (errs.length === 0) return null;
    return errs.length === 1 ? errs[0] : `请先修正表单中的 ${errs.length} 处错误`;
  }, [symbolError, compositionError]);

  const handleSubmit = async () => {
    if (blockReason || !detail) return;
    let values: any;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setSaving(true);
    setServerError(null);
    try {
      const req: MaterialRecipeUpdateRequest = {
        symbol: symbolValue.trim(),
        name: values.name?.trim() || null,
        specLabel: detail.specLabel ?? null,
        recipeType: values.recipeType,
        allowCustomContent,
        composition: composition.map((c, i) => ({ elementNo: c.elementNo, sortOrder: i + 1 })),
        sortOrder: values.sortOrder ?? 100,
        status: values.status ?? 'ACTIVE',
      };
      await materialRecipeService.update(detail.id, req);
      message.success('材质已更新');
      onSaved();
    } catch (e: unknown) {
      // 🚨 按**字符串错误码**分支（`err.payload.code`），不按文案匹配 —— 文案是给人看的，改文案不该改行为
      const code = apiErrorCode(e);
      const msg = apiErrorMessage(e, '保存失败');
      clearServerErrors();
      if (code === 'RECIPE_SYMBOL_DUPLICATED' || code === 'RECIPE_SYMBOL_TOO_LONG') {
        setServerSymbolError(msg);
      } else if (
        code === 'COMPOSITION_LOCKED'
        || code === 'COMPOSITION_EMPTY'
        || code === 'COMPOSITION_ELEMENT_DUPLICATED'
      ) {
        setServerCompositionError(msg);
      } else {
        // 含 CUSTOM_CONTENT_NEEDS_CONFIG 与一切未知码：顶部整体告警，文案仍用后端原文
        setServerError(msg);
      }
    } finally {
      setSaving(false);
    }
  };

  // ── 含量配置矩阵 ──
  const selectedConfigs = useMemo(
    () => configs.filter((c) => selectedConfigIds.includes(c.id)),
    [configs, selectedConfigIds],
  );

  /** 配置区工具栏动作的 enabledWhen：禁用但可见 + hover 给原因（frontend.md §1.2） */
  const configActionReason = (n: number): string | null => {
    if (n === 0) return '请先勾选一条配置';
    if (n > 1) return `只能选择一条配置（当前选中 ${n} 条）`;
    return null;
  };
  const cfgReason = configActionReason(selectedConfigs.length);

  const matrixColumns = useMemo(() => {
    // AC-13④ / AC-17②（2026-09-02 修订后）：表头为
    // `配置编号 | <元素…> | 合计 | 创建时间 | 备注`。
    // 判据是「**元素列及其顺序**恰为该材质的元素组成」，不是禁止其他列 ——
    // 原「表头恰为四列」的写法是 AC 写过头了，已按原型 2 状态 A 把这两列补回。
    const cols: any[] = [
      {
        title: '配置编号',
        dataIndex: 'configNo',
        key: 'configNo',
        width: 130,
        fixed: 'left' as const,
        // 行内只保留「主入口」链接（frontend.md §1.2）：点编号进配置编辑；
        // 变更类动作（新建/删除）仍在上方工具栏。
        render: (v: string, r: MaterialRecipeConfig) => (
          <a onClick={(e) => { e.stopPropagation(); setEditingConfig(r); setConfigDrawerOpen(true); }}>{v}</a>
        ),
      },
    ];
    composition.forEach((c) => {
      cols.push({
        title: c.elementCode,
        key: `el-${c.elementNo}`,
        width: 120,
        align: 'right' as const,
        render: (_: unknown, r: MaterialRecipeConfig) => {
          const hit = r.elements.find((e) => (e.elementNo ?? e.elementCode) === c.elementNo);
          // AC-30：库里存 90.000000000000，这里显示 90%
          return hit ? `${formatPctText(hit.defaultPct)}%` : '—';
        },
      });
    });
    cols.push({
      title: '合计',
      key: 'total',
      width: 100,
      align: 'right' as const,
      render: (_: unknown, r: MaterialRecipeConfig) => (
        <b>{formatPctText(r.totalPct)}%</b>
      ),
    });
    cols.push({
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 150,
      render: (v?: string) => <span style={{ color: 'rgba(0,0,0,.45)' }}>{fmtTime(v)}</span>,
    });
    cols.push({
      title: '备注',
      dataIndex: 'remark',
      key: 'remark',
      width: 180,
      render: (v?: string | null) => <span style={{ color: 'rgba(0,0,0,.45)' }}>{v || '—'}</span>,
    });
    return cols;
  }, [composition]);

  const reloadAfterConfigChange = async () => {
    setConfigDrawerOpen(false);
    setDeleteDrawerOpen(false);
    setEditingConfig(null);
    setSelectedConfigIds([]);
    if (recipeId) await loadDetail(recipeId);
    // 列表页的「含量配置」列 / 「支持自定义含量」列要跟着变
    onSaved();
  };

  const toolbarBtn = (
    key: string, label: string, icon: React.ReactNode, danger: boolean,
    disabled: boolean, reason: string | null, onClick: () => void,
  ) => {
    const btn = (
      <Button key={key} size="small" icon={icon} danger={danger} disabled={disabled} onClick={onClick}>
        {label}
      </Button>
    );
    // 🚫 禁用但可见 —— 不许 `if (...) return null` 隐藏
    return reason ? <Tooltip key={key} title={reason}>{<span>{btn}</span>}</Tooltip> : btn;
  };

  const saveBtn = (
    <Button type="primary" loading={saving} disabled={!!blockReason} onClick={handleSubmit}>
      保存
    </Button>
  );

  return (
    <>
      <Drawer
        title={
          detail
            ? <span>编辑材质 <span style={{ color: 'rgba(0,0,0,.45)', fontSize: 13, fontWeight: 400 }}>{detail.code} / {detail.symbol}</span></span>
            : '编辑材质'
        }
        open={open}
        onClose={onClose}
        // ① 闸门 A 裁决：760 → 1200
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
        <Spin spinning={loading}>
          {serverError && (
            <Alert type="error" showIcon style={{ marginBottom: 16 }} message={serverError} />
          )}

          <Form form={form} layout="vertical">
            <Space size="large" wrap align="start">
              <Form.Item name="code" label="材质编号">
                <Input style={{ width: 180 }} disabled />
              </Form.Item>
              {/* ⚠️ 受控组件，故**不挂 name** —— 挂了 name 会被 Form 注入 value/onChange 覆盖掉本地 state */}
              <Form.Item
                label="材质名 / 化学式"
                required
                validateStatus={(symbolError || serverSymbolError) ? 'error' : undefined}
                help={symbolError ?? serverSymbolError
                  ?? '最多 32 字符。材质名即材质身份 —— 导入时按它匹配材质，改名等于改身份，请谨慎。'}
              >
                <Input
                  id="symbol"
                  style={{ width: 320 }}
                  value={symbolValue}
                  onChange={(e) => { setSymbolValue(e.target.value); setServerSymbolError(null); setServerError(null); }}
                />
              </Form.Item>
              <Form.Item name="name" label="名称">
                <Input placeholder="留空默认=材质名" style={{ width: 220 }} />
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
              <Form.Item name="status" label="状态">
                {/* 🚫 固定枚举，不开 showSearch */}
                <Select
                  style={{ width: 110 }}
                  options={[
                    { value: 'ACTIVE', label: '启用' },
                    { value: 'INACTIVE', label: '停用' },
                  ]}
                />
              </Form.Item>
            </Space>
          </Form>

          {/* ③ 元素组成区 —— 材质的显式属性（D10），不是从配置推出来的 */}
          <div style={{ marginTop: 4, marginBottom: 20 }}>
            <div style={{ fontSize: 14, marginBottom: 8 }}>
              <span style={{ color: '#ff4d4f', marginRight: 4 }}>*</span>
              <b>元素组成</b>
            </div>
            <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
              <SortableContext
                items={composition.map((c) => c.elementNo)}
                strategy={horizontalListSortingStrategy}
              >
                <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center' }}>
                  {composition.map((c) => (
                    <SortableChip
                      key={c.elementNo}
                      item={c}
                      editable={compositionEditable}
                      onRemove={handleRemoveElement}
                    />
                  ))}
                  {compositionEditable && (
                    addingElement ? (
                      <Select<string>
                        showSearch
                        autoFocus
                        defaultOpen
                        style={{ width: 300, marginBottom: 8 }}
                        placeholder="搜索元素编号 / 符号 / 中文名"
                        loading={dictLoading}
                        options={addOptions}
                        filterOption={filterElementOption as any}
                        notFoundContent={dictError ? '元素字典加载失败' : ELEMENT_NOT_FOUND_TEXT}
                        onChange={handleAddElement}
                        onBlur={() => setAddingElement(false)}
                      />
                    ) : (
                      <Button
                        size="small"
                        icon={<PlusOutlined />}
                        style={{ marginBottom: 8 }}
                        onClick={() => setAddingElement(true)}
                      >
                        添加元素
                      </Button>
                    )
                  )}
                  {!compositionEditable && (
                    // 禁用但可见：增删按钮置灰 + hover 给原因（AC-31）
                    <Tooltip title={compositionLockReason}>
                      <span style={{ marginBottom: 8 }}>
                        <Button size="small" icon={<PlusOutlined />} disabled>添加元素</Button>
                      </span>
                    </Tooltip>
                  )}
                </div>
              </SortableContext>
            </DndContext>

            {!compositionEditable && (
              <div
                style={{
                  marginTop: 4, background: '#fffbe6', border: '1px solid #ffe58f',
                  borderRadius: 6, padding: '8px 12px', fontSize: 12, color: '#874d00',
                }}
              >
                <LockOutlined style={{ marginRight: 6 }} />
                {compositionLockReason}。
              </div>
            )}
            {(compositionError || serverCompositionError) && (
              <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>
                {compositionError ?? serverCompositionError}
              </div>
            )}
            <div style={{ color: 'rgba(0,0,0,.45)', fontSize: 12, marginTop: 4 }}>
              元素组成决定下方矩阵的<b>列与列序</b>（拖动 chip 可调整顺序）。它是材质的属性，<b>与有没有配置无关</b>。
            </div>
          </div>

          {/* 支持自定义含量开关（M-5） */}
          <div style={{ marginBottom: 20 }}>
            <div style={{ fontSize: 14, marginBottom: 8 }}><b>支持自定义含量</b></div>
            <Tooltip title={activeConfigCount === 0 ? '该材质尚未配置任何含量，开关不可用' : undefined}>
              <span>
                <Switch
                  checked={allowCustomContent}
                  disabled={activeConfigCount === 0}
                  onChange={setAllowCustomContent}
                />
              </span>
            </Tooltip>
            <span style={{ marginLeft: 10, fontSize: 13, color: 'rgba(0,0,0,.65)' }}>
              {activeConfigCount === 0
                ? '该材质尚未配置任何含量，开关不可用'
                : allowCustomContent
                  ? '开 —— 选配时可自行输入各元素含量'
                  : '关 —— 选配时只能从下方配置中选择'}
            </span>
            <div style={{ color: 'rgba(0,0,0,.45)', fontSize: 12, marginTop: 4 }}>
              打开后，选配可让用户自行输入各元素含量（仍强制合计 = 100%，且<b>只能改含量不能改元素</b>）。
              新建材质与导入自动创建的材质一律默认关。
            </div>
          </div>

          {/* ② 含量配置矩阵（行=配置、列=元素） */}
          <div style={{ fontSize: 14, marginBottom: 8 }}><b>含量配置</b></div>
          <div
            style={{
              display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10,
              padding: '8px 12px', borderRadius: 6, flexWrap: 'wrap',
              background: selectedConfigs.length > 0 ? '#e6f4ff' : '#fafafa',
              border: `1px solid ${selectedConfigs.length > 0 ? '#91caff' : '#f0f0f0'}`,
            }}
          >
            <Button
              size="small"
              type="primary"
              icon={<PlusOutlined />}
              disabled={composition.length === 0}
              onClick={() => { setEditingConfig(null); setConfigDrawerOpen(true); }}
            >
              新建配置
            </Button>
            {toolbarBtn('cfg-edit', '编辑', <EditOutlined />, false, !!cfgReason, cfgReason,
              () => { setEditingConfig(selectedConfigs[0]); setConfigDrawerOpen(true); })}
            {toolbarBtn('cfg-del', '删除', <DeleteOutlined />, true, !!cfgReason, cfgReason,
              () => setDeleteDrawerOpen(true))}
            <div style={{ flex: 1, minWidth: 4 }} />
            <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>共 {activeConfigCount} 组</span>
          </div>

          {/* ④ 0 配置时表头照常渲染（元素列来自元素组成，与配置无关），只有表体走空态 */}
          <Table<MaterialRecipeConfig>
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={configs}
            columns={matrixColumns}
            // 矩阵自身横向滚动，页面 body 不出现横向滚动条（AC-13 ⑤）
            scroll={{ x: 'max-content' }}
            rowSelection={{
              selectedRowKeys: selectedConfigIds,
              onChange: setSelectedConfigIds,
            }}
            locale={{
              emptyText: (
                <div style={{ padding: '28px 0', color: 'rgba(0,0,0,.45)' }}>
                  <div style={{ fontSize: 14, marginBottom: 6 }}>该材质尚未配置含量</div>
                  <div style={{ fontSize: 12 }}>
                    未配置含量的材质在选配时不可选。点上方「新建配置」补一组，或通过导入材质库批量补齐。
                  </div>
                </div>
              ),
            }}
          />
          <div style={{ color: 'rgba(0,0,0,.45)', fontSize: 12, marginTop: 6 }}>
            含量<b>去掉小数点后多余的 0</b>：库里存的是 <code>90.000000000000</code>，这里显示 <code>90%</code>。
            {activeConfigCount === 0 && (
              <> 表头不因没数据而隐藏 —— 元素列来自元素组成，与有没有配置无关。</>
            )}
          </div>

          {detail && !detail.compositionEditable && composition.length > 0 && (
            <div style={{ marginTop: 12 }}>
              <Tag color="blue">元素组成只读</Tag>
              <span style={{ fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
                前端置灰只是体验，<b>后端同样会拦</b> —— 直接打接口绕过前端会返 409 <code>COMPOSITION_LOCKED</code>。
              </span>
            </div>
          )}
        </Spin>
      </Drawer>

      {/* 二级抽屉：新建 / 编辑含量配置（F-5） */}
      {detail && (
        <MaterialRecipeConfigDrawer
          open={configDrawerOpen}
          recipeId={detail.id}
          recipeCode={detail.code}
          recipeSymbol={detail.symbol}
          composition={composition}
          activeConfigs={configs}
          editingConfig={editingConfig}
          onClose={() => { setConfigDrawerOpen(false); setEditingConfig(null); }}
          onSaved={reloadAfterConfigChange}
        />
      )}

      {/* 二级抽屉：删除配置二次确认（F-3） */}
      {detail && (
        <MaterialRecipeConfigDeleteDrawer
          open={deleteDrawerOpen}
          recipeId={detail.id}
          recipeCode={detail.code}
          recipeSymbol={detail.symbol}
          composition={composition}
          targets={selectedConfigs}
          onClose={() => setDeleteDrawerOpen(false)}
          onDeleted={reloadAfterConfigChange}
        />
      )}
    </>
  );
};

export default MaterialRecipeEditDrawer;
