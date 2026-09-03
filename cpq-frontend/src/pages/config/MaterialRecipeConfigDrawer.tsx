/**
 * 含量配置抽屉（task-260901 · F-5，全新页面）——**二级 Drawer**，盖在材质编辑抽屉之上。
 * 对照 `原型图/3-含量配置抽屉.html` 状态 A / B / C。
 *
 * 🚨 **本页不选元素、不加元素、不删元素**（闸门 A 第四轮裁决）：
 * 元素行按该材质的 `composition` **预先填好且整列只读**，用户唯一能填的是含量列。
 * ⇒ 没有元素下拉、没有「添加元素」按钮、没有行删除按钮。
 * 「有哪些元素」在上一层（材质编辑抽屉的元素组成区）或新建材质时（配方卡片）定。
 *
 * 🚨 含量一律按字符串处理，禁止 `Number()` / `parseFloat`（`numeric(16,12)`，12 位小数）。
 * 显示与输入框回填都**去掉尾随 0**；提交时把框里的字符串**原样发给后端**，不补零、不做数值转换 ——
 * `75` 与 `75.000000000000` 在 `numeric(16,12)` 与 `BigDecimal.compareTo` 下是同一个值。
 *
 * → 服务 AC-10 / AC-14 / AC-30 / AC-35
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Drawer, Button, Space, Input, InputNumber, Table, Alert, Tag, Tooltip, message } from 'antd';
import {
  materialRecipeService,
  type CompositionItem,
  type MaterialRecipeConfig,
} from '../../services/materialRecipeService';
import { trimTrailingZeros, type DecimalString } from '../../utils/precision';
import { apiErrorCode, apiErrorMessage } from '../../utils/apiError';
import {
  configDuplicatedText,
  configsValueEqual,
  isPctLegal,
  isSumOk,
  pctIllegalText,
  sumDisplayPct,
  sumGapText,
  sumNotOneText,
  sumPct,
} from './recipeContentRules';

interface Props {
  open: boolean;
  recipeId: string;
  recipeCode: string;
  recipeSymbol: string;
  /** 矩阵列与本页元素行的权威来源（M-0） */
  composition: CompositionItem[];
  /** 该材质当前的 ACTIVE 配置，用于 M-4 重复判定与「下一个编号」提示 */
  activeConfigs: MaterialRecipeConfig[];
  /** null = 新建；非空 = 编辑该配置 */
  editingConfig: MaterialRecipeConfig | null;
  onClose: () => void;
  onSaved: () => void;
}

interface Row {
  elementNo: string;
  elementCode: string;
  elementName: string;
  /** 用户输入的原始字符串（去尾随零形态）。空串 = 未填 */
  pct: DecimalString;
}

/** M-1：`config_no = <材质编号>-<String.format("%02d", seq)>`，seq ≥ 100 时自然扩为三位 */
function formatSeq(seq: number): string {
  return seq < 10 ? `0${seq}` : String(seq);
}

const MaterialRecipeConfigDrawer: React.FC<Props> = ({
  open, recipeId, recipeCode, recipeSymbol, composition, activeConfigs, editingConfig, onClose, onSaved,
}) => {
  const [rows, setRows] = useState<Row[]>([]);
  const [remark, setRemark] = useState('');
  const [saving, setSaving] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  const isEditing = !!editingConfig;

  // 打开时按 composition 预填元素行（顺序 = sort_order），编辑态回填已有含量（去尾随零）
  useEffect(() => {
    if (!open) return;
    setServerError(null);
    setRemark(editingConfig?.remark ?? '');
    const byNo = new Map(
      (editingConfig?.elements ?? []).map((e) => [e.elementNo ?? e.elementCode, e.defaultPct]),
    );
    setRows(
      [...composition]
        .sort((a, b) => a.sortOrder - b.sortOrder)
        .map((c) => ({
          elementNo: c.elementNo,
          elementCode: c.elementCode,
          elementName: c.elementName,
          // AC-30：编辑输入框同样回填去零值（打开 00006-01 的编辑抽屉，Ag 那格显示 90 而不是 90.000000000000）
          pct: trimTrailingZeros(byNo.get(c.elementNo) ?? ''),
        })),
    );
  }, [open, editingConfig, composition]);

  const setPct = (elementNo: string, v: string) =>
    setRows((prev) => prev.map((r) => (r.elementNo === elementNo ? { ...r, pct: v } : r)));

  // ── 校验（三类，与原型状态 B 同屏） ──
  const illegalRows = useMemo(() => rows.filter((r) => !isPctLegal(r.pct)), [rows]);
  const sum = useMemo(() => sumPct(rows), [rows]);
  const sumOk = isSumOk(sum);

  /** M-4：与某条 ACTIVE 配置逐值相同 → 拦下（编辑态排除自身） */
  const duplicateOf = useMemo(() => {
    if (illegalRows.length > 0) return null;
    const mine = rows.map((r) => ({ elementNo: r.elementNo, pct: r.pct }));
    return (
      activeConfigs
        .filter((c) => c.id !== editingConfig?.id)
        .find((c) =>
          configsValueEqual(
            mine,
            c.elements.map((e) => ({ elementNo: e.elementNo ?? e.elementCode, pct: e.defaultPct })),
          ),
        ) ?? null
    );
  }, [rows, activeConfigs, editingConfig, illegalRows.length]);

  const blockReason = useMemo<string | null>(() => {
    if (composition.length === 0) return '该材质尚未定义元素组成，请先在材质抽屉的「元素组成」区补齐';
    const errCount = illegalRows.length + (sumOk ? 0 : 1);
    if (illegalRows.length > 0 || !sumOk) return `请先修正表单中的 ${errCount} 处错误`;
    if (duplicateOf) return `与已有配置 ${duplicateOf.configNo} 重复`;
    return null;
  }, [composition.length, illegalRows.length, sumOk, duplicateOf]);

  /** 下一个配置编号（提示用）——已停用的配置也占用 seq 水位，故只是估算，实际以保存结果为准（M-1 / M-2） */
  const nextConfigNoHint = useMemo(() => {
    const maxSeq = activeConfigs.reduce((m, c) => Math.max(m, c.seq ?? 0), 0);
    return `${recipeCode}-${formatSeq(maxSeq + 1)}`;
  }, [activeConfigs, recipeCode]);

  const handleSave = async () => {
    if (blockReason) return;
    setSaving(true);
    setServerError(null);
    try {
      const req = {
        remark: remark.trim() || null,
        // 🚨 原样发送用户输入的字符串（不补零、不过 JS number）
        elements: rows.map((r) => ({ elementNo: r.elementNo, defaultPct: r.pct })),
      };
      if (editingConfig) {
        await materialRecipeService.updateConfig(recipeId, editingConfig.id, req);
        message.success('含量配置已更新');
      } else {
        const created = await materialRecipeService.createConfig(recipeId, req);
        message.success(`含量配置 ${created?.configNo ?? ''} 已创建`);
      }
      onSaved();
    } catch (e: unknown) {
      // 🚨 按字符串错误码分支（`err.payload.code`）。这里前端已先拦过一轮，
      // 能走到后端报错的多半是并发改动（如别人刚改了该材质的元素组成）——
      // CONFIG_ELEMENT_SET_MISMATCH 就属于这种，提示要指向「重开抽屉」而不是「改含量」。
      const code = apiErrorCode(e);
      const msg = apiErrorMessage(e, '保存失败');
      setServerError(
        code === 'CONFIG_ELEMENT_SET_MISMATCH' || code === 'ELEMENT_NOT_FOUND'
          ? `${msg}（该材质的元素组成可能已被他人改动，请关闭本抽屉重新打开后再试）`
          : msg,
      );
    } finally {
      setSaving(false);
    }
  };

  const columns = [
    {
      title: '元素',
      key: 'element',
      width: 260,
      render: (_: unknown, r: Row) => (
        // 🔒 整列只读：本页不选元素、不加元素、不删元素。
        // ⚠️ 刻意**不用 <Input readOnly>** —— 那会在行里多出一个 input，
        //    「表体里的输入框」就不再唯一等于含量列了（AC-14①「用户唯一能填的是含量列」）。
        <div
          style={{
            border: '1px solid #d9d9d9', borderRadius: 6, padding: '4px 11px', height: 32,
            display: 'flex', alignItems: 'center', background: 'rgba(0,0,0,.04)',
            color: 'rgba(0,0,0,.65)', fontFamily: 'Consolas, monospace', fontSize: 13,
          }}
        >
          {r.elementNo} / {r.elementCode} / {r.elementName}
        </div>
      ),
    },
    {
      title: '含量（%）',
      key: 'pct',
      render: (_: unknown, r: Row) => {
        const bad = !isPctLegal(r.pct);
        return (
          <div>
            <InputNumber<string>
              stringMode
              // 🚫 不设 min/max：越界值要能被输入并当场标红（原型 3 状态 B），而不是被组件静默夹回
              status={bad ? 'error' : undefined}
              style={{ width: '100%' }}
              value={r.pct === '' ? null : r.pct}
              placeholder="填写含量，支持 12 位小数"
              onChange={(v) => setPct(r.elementNo, v ?? '')}
            />
            {bad && (
              <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>
                {pctIllegalText()}
              </div>
            )}
          </div>
        );
      },
    },
  ];

  const saveBtn = (
    <Button type="primary" loading={saving} disabled={!!blockReason} onClick={handleSave}>
      保存
    </Button>
  );

  return (
    <Drawer
      title={
        <span>
          {isEditing ? '编辑含量配置' : '新建含量配置'}
          <span style={{ color: 'rgba(0,0,0,.45)', fontSize: 13, fontWeight: 400, marginLeft: 8 }}>
            材质 {recipeCode} / {recipeSymbol}
          </span>
        </span>
      }
      open={open}
      onClose={onClose}
      width={720}
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
      {serverError && (
        <Alert type="error" showIcon style={{ marginBottom: 16 }} message={serverError} />
      )}

      <div style={{ marginBottom: 16 }}>
        <div style={{ fontSize: 13, marginBottom: 4 }}>配置编号</div>
        <Input
          readOnly
          value={isEditing ? editingConfig!.configNo : `保存后自动生成（下一个：${nextConfigNoHint}）`}
        />
        <div style={{ color: 'rgba(0,0,0,.45)', fontSize: 12, marginTop: 4 }}>
          格式 <code>&lt;材质编号&gt;-&lt;两位序号&gt;</code>。<b>序号不回收</b> —— 删过的编号不会被重新发出；
          已停用的配置同样占用序号，实际编号以保存结果为准。
        </div>
      </div>

      <div style={{ marginBottom: 16 }}>
        <div style={{ fontSize: 13, marginBottom: 4 }}>备注</div>
        <Input
          value={remark}
          maxLength={255}
          placeholder="选填，例如「客户 A 专用档次」"
          onChange={(e) => setRemark(e.target.value)}
        />
      </div>

      <div style={{ fontSize: 13, marginBottom: 4 }}>
        <span style={{ color: '#ff4d4f', marginRight: 4 }}>*</span>元素含量
      </div>
      <Table<Row>
        rowKey="elementNo"
        size="small"
        pagination={false}
        dataSource={rows}
        columns={columns as any}
        locale={{ emptyText: '该材质尚未定义元素组成' }}
      />

      <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 12 }}>
        <span>
          合计 <b style={{ fontFamily: 'Consolas, monospace' }}>{sumDisplayPct(sum)}%</b>
        </span>
        {sumOk ? <Tag color="green">符合</Tag> : <Tag color="red">{sumGapText(sum)}</Tag>}
      </div>

      {(illegalRows.length > 0 || !sumOk) && (
        <Alert
          type="error"
          showIcon
          style={{ marginTop: 12 }}
          message={`${illegalRows.length + (sumOk ? 0 : 1)} 处需要修正`}
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {illegalRows.map((r) => (
                <li key={r.elementNo}>{pctIllegalText(r.elementCode)}</li>
              ))}
              {!sumOk && <li>{sumNotOneText(sum)}</li>}
            </ul>
          }
        />
      )}

      {duplicateOf && (
        <Alert
          type="warning"
          showIcon
          style={{ marginTop: 12 }}
          message={configDuplicatedText(duplicateOf.configNo)}
          description={
            <span>
              判据：元素集合相同且每个元素含量逐值相等（<code>BigDecimal.compareTo == 0</code>），
              不套用合计的 0.02 容差。因为按值比较，<code>90</code> 与 <code>90.000000000000</code> 算同一个值。
            </span>
          }
        />
      )}

      <Alert
        type="info"
        showIcon
        style={{ marginTop: 12 }}
        message={
          <span>
            本页的元素行来自该材质的<b>元素组成</b>
            （{composition.map((c) => c.elementCode).join(' + ') || '—'}），<b>不可增删改</b>。
            它是材质的显式属性，也是配置矩阵能规整渲染的前提。
            若绕过前端直接打接口传了别的元素集合，后端返 400 <code>CONFIG_ELEMENT_SET_MISMATCH</code>。
          </span>
        }
      />
    </Drawer>
  );
};

export default MaterialRecipeConfigDrawer;
