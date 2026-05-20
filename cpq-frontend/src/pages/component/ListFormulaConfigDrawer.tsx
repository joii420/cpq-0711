/**
 * ListFormulaConfigDrawer (Phase B)
 *
 * 配置 LIST_FORMULA 字段:
 *   - 左栏: 选「配置模板」 (config_template, 仅 PUBLISHED) → 选「大类」(category)
 *   - 右栏: 大类下的所有明细项, 每项可配多分支 IF-ELSE-IF 链 (condition + formula) + 兜底 default_formula
 *
 * 条件 / 公式 token:
 *   - [字段名] — 本行其他字段
 *   - {GV_CODE} — 全局变量 (Phase C 接入)
 *   - 数字 / 字符串 / 运算符
 *
 * 同组件多 LIST_FORMULA 字段必须绑同 (template, category) — 调用方控制.
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  Drawer, Input, Select, Empty, Spin, Alert, Button, Space, Typography, Tag, Tooltip, Card, Form,
} from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import type { ListFormulaConfig, ListFormulaItemRule, ListFormulaBranch } from './types';
import { configTemplateService, type ConfigTemplate, type ConfigCategory } from '../../services/configTemplateService';

const { Text } = Typography;

interface Props {
  open: boolean;
  value?: ListFormulaConfig;
  fieldName?: string;
  otherFieldNames?: string[];
  onClose: () => void;
  onConfirm: (next: ListFormulaConfig) => void;
}

const ListFormulaConfigDrawer: React.FC<Props> = ({
  open, value, fieldName, otherFieldNames = [], onClose, onConfirm,
}) => {
  const [templates, setTemplates] = useState<ConfigTemplate[]>([]);
  const [loadingTemplates, setLoadingTemplates] = useState(false);
  const [tplError, setTplError] = useState<string | null>(null);

  const [selectedTplId, setSelectedTplId] = useState<string | null>(null);
  const [selectedTplDetail, setSelectedTplDetail] = useState<ConfigTemplate | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);

  const [selectedCategoryCode, setSelectedCategoryCode] = useState<string | null>(null);
  const [perItemRules, setPerItemRules] = useState<Record<string, ListFormulaItemRule>>({});

  // 打开时初始化
  useEffect(() => {
    if (!open) return;
    setSelectedTplId(value?.config_template_id || null);
    setSelectedCategoryCode(value?.category_code || null);
    setPerItemRules({ ...(value?.per_item_rules || {}) });
    setTplError(null);

    setLoadingTemplates(true);
    configTemplateService.list('PUBLISHED')
      .then(r => setTemplates(r.data || []))
      .catch(e => setTplError(e?.message || '加载模板列表失败'))
      .finally(() => setLoadingTemplates(false));
  }, [open]);

  // 选定模板 → 拉详情
  useEffect(() => {
    if (!open || !selectedTplId) {
      setSelectedTplDetail(null);
      return;
    }
    setLoadingDetail(true);
    configTemplateService.getById(selectedTplId)
      .then(r => setSelectedTplDetail(r.data))
      .catch(e => setTplError(e?.message || '加载模板详情失败'))
      .finally(() => setLoadingDetail(false));
  }, [open, selectedTplId]);

  const selectedCategory: ConfigCategory | undefined = useMemo(
    () => selectedTplDetail?.categories.find(c => c.code === selectedCategoryCode),
    [selectedTplDetail, selectedCategoryCode],
  );

  const updateItemRule = (itemCode: string, patch: Partial<ListFormulaItemRule>) => {
    setPerItemRules(prev => {
      const cur = prev[itemCode] || { branches: [], default_formula: '' };
      return { ...prev, [itemCode]: { ...cur, ...patch } };
    });
  };

  const addBranch = (itemCode: string) => {
    const cur = perItemRules[itemCode] || { branches: [], default_formula: '' };
    updateItemRule(itemCode, { branches: [...cur.branches, { condition: '', formula: '' }] });
  };
  const updateBranch = (itemCode: string, idx: number, patch: Partial<ListFormulaBranch>) => {
    const cur = perItemRules[itemCode];
    if (!cur) return;
    const next = cur.branches.map((b, i) => i === idx ? { ...b, ...patch } : b);
    updateItemRule(itemCode, { branches: next });
  };
  const removeBranch = (itemCode: string, idx: number) => {
    const cur = perItemRules[itemCode];
    if (!cur) return;
    updateItemRule(itemCode, { branches: cur.branches.filter((_, i) => i !== idx) });
  };

  const handleConfirm = () => {
    if (!selectedTplId || !selectedTplDetail || !selectedCategoryCode || !selectedCategory) return;
    onConfirm({
      config_template_id: selectedTplId,
      config_template_code: selectedTplDetail.code,
      config_template_name: selectedTplDetail.name,
      category_code: selectedCategoryCode,
      category_name: selectedCategory.name,
      per_item_rules: perItemRules,
    });
  };
  const canConfirm = !!selectedTplId && !!selectedCategoryCode && !!selectedCategory;

  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={`配置 LIST_FORMULA 字段: ${fieldName || '未命名'}`}
      placement="right"
      width={1200}
      footer={
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" onClick={handleConfirm} disabled={!canConfirm}>应用</Button>
        </div>
      }
    >
      {/* 顶部: 选模板 + 选大类 */}
      <Form layout="inline" style={{ marginBottom: 12 }}>
        <Form.Item label="配置模板" required>
          <Select
            style={{ width: 320 }}
            value={selectedTplId || undefined}
            placeholder={loadingTemplates ? '加载中...' : '选模板 (仅 PUBLISHED)'}
            loading={loadingTemplates}
            onChange={(v) => { setSelectedTplId(v); setSelectedCategoryCode(null); setPerItemRules({}); }}
            options={templates.map(t => ({
              label: <span><code>{t.code}</code> {t.name}</span>,
              value: t.id,
            }))}
            showSearch
            optionFilterProp="children"
          />
        </Form.Item>
        <Form.Item label="大类" required>
          <Select
            style={{ width: 260 }}
            value={selectedCategoryCode || undefined}
            placeholder={selectedTplDetail ? '选大类' : '请先选模板'}
            disabled={!selectedTplDetail || loadingDetail}
            onChange={(v) => { setSelectedCategoryCode(v); }}
            options={(selectedTplDetail?.categories || []).map(c => ({
              label: `${c.name} (${c.items.length} 项)`,
              value: c.code,
            }))}
          />
        </Form.Item>
        {selectedCategory && (
          <Form.Item>
            <Space>
              <Tag color="blue">{selectedCategory.items.length} 个明细项</Tag>
              <Tag color="green">已配 {Object.keys(perItemRules).length}</Tag>
            </Space>
          </Form.Item>
        )}
      </Form>

      {tplError && <Alert type="error" showIcon message={tplError} style={{ marginBottom: 12 }} />}

      {/* token 提示 */}
      <Alert
        style={{ marginBottom: 12 }}
        type="info"
        showIcon
        message={
          <span style={{ fontSize: 12 }}>
            <b>条件</b> 例: <code>[厚度] {'>'} 5 AND [材质] = 'AgCu85'</code> ·{' '}
            <b>公式</b> 例: <code>[基础工时] * 1.2</code> / <code>0.8</code> / <code>{'{PROCESS_DEFAULT_PRICE}'}</code>
            {otherFieldNames.length > 0 && <> · 本组件可引用字段: {otherFieldNames.map(n => <code key={n} style={{ marginRight: 4 }}>[{n}]</code>)}</>}
          </span>
        }
      />

      {/* 主体: 明细项卡片列表 */}
      {loadingDetail ? (
        <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
      ) : !selectedCategory ? (
        <Empty description="请在顶部选模板 + 大类" style={{ marginTop: 60 }} />
      ) : selectedCategory.items.length === 0 ? (
        <Empty description="该大类下还没有明细项, 请先去「配置模板管理」加明细项" style={{ marginTop: 40 }} />
      ) : (
        <div style={{ maxHeight: 'calc(100vh - 320px)', overflow: 'auto' }}>
          {selectedCategory.items.map(item => {
            const rule = perItemRules[item.code] || { branches: [], default_formula: '' };
            return (
              <Card
                key={item.code}
                size="small"
                style={{ marginBottom: 8 }}
                title={
                  <Space size={6}>
                    <Tag color="purple">{item.code}</Tag>
                    <span>{item.name}</span>
                    {item.defaultValue && (
                      <Tooltip title="此明细项自身的默认值 (config_item.default_value), 全分支不命中 + 无 default_formula 时兜底">
                        <Tag color="default" style={{ fontSize: 10 }}>item default: {item.defaultValue}</Tag>
                      </Tooltip>
                    )}
                  </Space>
                }
                extra={
                  <Button size="small" type="link" icon={<PlusOutlined />}
                    onClick={() => addBranch(item.code)}>
                    + 分支
                  </Button>
                }
              >
                {rule.branches.length === 0 && (
                  <Text type="secondary" style={{ fontSize: 12 }}>暂无条件分支, 仅走默认公式 / item default value</Text>
                )}
                {rule.branches.map((b, idx) => (
                  <div key={idx} style={{
                    display: 'grid',
                    gridTemplateColumns: '60px 1fr 1fr 32px',
                    gap: 8,
                    alignItems: 'center',
                    marginBottom: 4,
                  }}>
                    <Text style={{ fontSize: 12, color: '#666' }}>{idx === 0 ? 'IF' : 'ELSE IF'}</Text>
                    <Input
                      size="small"
                      placeholder="条件 (空=总是 true) 如 [厚度] > 5"
                      value={b.condition}
                      onChange={(e) => updateBranch(item.code, idx, { condition: e.target.value })}
                      style={{ fontFamily: 'Consolas, Monaco, monospace' }}
                    />
                    <Input
                      size="small"
                      placeholder="公式或字面值 如 [基础工时] * 1.2 或 0.8"
                      value={b.formula}
                      onChange={(e) => updateBranch(item.code, idx, { formula: e.target.value })}
                      style={{ fontFamily: 'Consolas, Monaco, monospace' }}
                    />
                    <Button
                      size="small"
                      type="text"
                      danger
                      icon={<DeleteOutlined />}
                      onClick={() => removeBranch(item.code, idx)}
                    />
                  </div>
                ))}
                {/* 默认分支 (始终在末尾) */}
                <div style={{
                  display: 'grid',
                  gridTemplateColumns: '60px 1fr 1fr 32px',
                  gap: 8,
                  alignItems: 'center',
                  marginTop: rule.branches.length > 0 ? 4 : 0,
                }}>
                  <Text style={{ fontSize: 12, color: '#666' }}>ELSE</Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>(默认)</Text>
                  <Input
                    size="small"
                    placeholder={`默认公式, 空 → 走 item default${item.defaultValue ? ` (${item.defaultValue})` : ' (空)'}`}
                    value={rule.default_formula || ''}
                    onChange={(e) => updateItemRule(item.code, { default_formula: e.target.value })}
                    style={{ fontFamily: 'Consolas, Monaco, monospace' }}
                  />
                  <div />
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </Drawer>
  );
};

export default ListFormulaConfigDrawer;
