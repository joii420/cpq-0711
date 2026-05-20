import React, { useEffect, useState } from 'react';
import { Drawer, List, Button, Tag, Typography, Empty, Space, Input, message, Card } from 'antd';
import { ArrowRightOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  globalVariableService,
  compileGlobalVariableToPath,
  type GlobalVariableDefinition,
} from '../services/globalVariableService';

interface KeyOption {
  key_values: Record<string, any>;
  value: number | string | null;
  label: string;
}

interface GlobalVariablePickerDrawerProps {
  open: boolean;
  onClose: () => void;
  /** 选中后的回调; 接收编译产物 BNF path 字符串 + 元数据供调用方落形态选择 */
  onPick: (result: {
    bnfPath: string;             // 静态: v_costing_element_price[element_code='Cu'].costing_price
                                 // 动态: v_costing_element_price.costing_price (无谓词, 依赖 ImplicitJoinRewriter)
    code: string;                // ELEM_PRICE
    keyValues: Record<string, any>;  // 静态时含具体值; 动态时为空
    isDynamic: boolean;          // true = 按 driver 行字段动态查表 (推荐)
    label: string;               // 「元素核价(动态)」或「元素核价[Cu]」
    unit?: string;
    def: GlobalVariableDefinition;
  }) => void;
  title?: string;
  /**
   * 是否允许动态 key 模式 (默认 true).
   * Excel 模板列的场景没有 driver 行概念, 应该传 false 强制走静态.
   */
  allowDynamic?: boolean;
}

/**
 * V104+V106: 通用「全局变量 + key」选择器抽屉.
 * 二层 UI: 选变量 → 列出可选 key 列表 → 用户挑一行 → onPick 触发.
 */
const GlobalVariablePickerDrawer: React.FC<GlobalVariablePickerDrawerProps> = ({
  open, onClose, onPick, title = '插入全局变量', allowDynamic = true,
}) => {
  const [defs, setDefs] = useState<GlobalVariableDefinition[]>([]);
  const [defsLoading, setDefsLoading] = useState(false);
  const [selected, setSelected] = useState<GlobalVariableDefinition | null>(null);
  const [keys, setKeys] = useState<KeyOption[]>([]);
  const [keysLoading, setKeysLoading] = useState(false);
  const [filter, setFilter] = useState('');

  useEffect(() => {
    if (!open) { setSelected(null); setKeys([]); setFilter(''); return; }
    setDefsLoading(true);
    globalVariableService.list()
      .then((res: any) => {
        const list: GlobalVariableDefinition[] = res?.data?.data || res?.data || [];
        setDefs(Array.isArray(list) ? list : []);
      })
      .catch(() => setDefs([]))
      .finally(() => setDefsLoading(false));
  }, [open]);

  const loadKeys = async (def: GlobalVariableDefinition) => {
    setSelected(def);
    setKeysLoading(true);
    try {
      const res: any = await globalVariableService.listKeys(def.code, 1000);
      const list: KeyOption[] = res?.data?.data || res?.data || [];
      setKeys(Array.isArray(list) ? list : []);
    } catch {
      message.error('加载候选 key 失败');
      setKeys([]);
    } finally {
      setKeysLoading(false);
    }
  };

  const pickStatic = (opt: KeyOption) => {
    if (!selected) return;
    try {
      const bnfPath = compileGlobalVariableToPath(selected, opt.key_values);
      onPick({
        bnfPath,
        code: selected.code,
        keyValues: opt.key_values,
        isDynamic: false,
        label: `${selected.name}[${opt.label}]`,
        unit: selected.unit,
        def: selected,
      });
    } catch (e: any) {
      message.error(e?.message || '编译路径失败');
    }
  };

  /**
   * V110: 动态 key 模式 — path 不带谓词, 由 ImplicitJoinRewriter 按 driver 行同名列自动注入.
   * 例: ELEM_PRICE 的 key_columns = ['element_code'], 视图含 element_name 别名列;
   *     当 driver 行有 element_name='Cu' 时, 路径 v_costing_element_price.costing_price 自动收窄到 Cu 那行.
   */
  const pickDynamic = () => {
    if (!selected) return;
    const bnfPath = `${selected.sourceView}.${selected.valueColumn}`;
    onPick({
      bnfPath,
      code: selected.code,
      keyValues: {},
      isDynamic: true,
      label: `${selected.name}（动态查表）`,
      unit: selected.unit,
      def: selected,
    });
  };

  const filteredKeys = filter
    ? keys.filter((k) => k.label.toLowerCase().includes(filter.toLowerCase()))
    : keys;

  return (
    <Drawer
      title={title}
      open={open}
      onClose={onClose}
      width={760}
      destroyOnClose
    >
      <div style={{ display: 'flex', gap: 12, height: '100%' }}>
        {/* 左侧: 变量列表 */}
        <Card
          title="选择变量"
          size="small"
          style={{ flex: '0 0 240px', overflow: 'auto' }}
          extra={
            <Button size="small" icon={<ReloadOutlined />}
              loading={defsLoading}
              onClick={() => { setDefsLoading(true); globalVariableService.list().then((r: any) => {
                setDefs(r?.data?.data || r?.data || []);
              }).finally(() => setDefsLoading(false)); }}
            />
          }
        >
          {defs.length === 0 ? (
            <Empty description="暂无全局变量" />
          ) : (
            <List
              size="small"
              dataSource={defs}
              renderItem={(d) => (
                <List.Item
                  onClick={() => loadKeys(d)}
                  style={{
                    cursor: 'pointer',
                    padding: '8px 12px',
                    background: selected?.code === d.code ? '#e6f4ff' : undefined,
                    borderRadius: 4,
                  }}
                >
                  <div style={{ width: '100%' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                      <span style={{ fontSize: 14, color: '#d46b08' }}>🌐</span>
                      <Typography.Text strong>{d.name}</Typography.Text>
                      {/* V190: 核价 3 张表加 tag, 让公式编辑者一眼区分 */}
                      {(d as any).valueSourceType === 'COSTING_VIEW' && (
                        <Tag color="purple" style={{ marginLeft: 4, fontSize: 10 }}>核价</Tag>
                      )}
                    </div>
                    <div style={{ fontSize: 11, color: '#8c8c8c', marginTop: 2 }}>
                      {d.code}{d.unit ? ` · ${d.unit}` : ''}
                    </div>
                    <div style={{ fontSize: 10, color: '#bfbfbf' }}>
                      key: {d.keyColumns.join(' + ')}
                    </div>
                  </div>
                </List.Item>
              )}
            />
          )}
        </Card>

        {/* 右侧: key 列表 + 直接选 */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
          {!selected ? (
            <Empty description="先在左侧选择一个变量" />
          ) : (
            <>
              <div style={{ marginBottom: 8 }}>
                <Space size="small">
                  <Typography.Text strong>{selected.name}</Typography.Text>
                  <Tag>{selected.code}</Tag>
                  {selected.unit && <Tag color="blue">{selected.unit}</Tag>}
                </Space>
                {selected.description && (
                  <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 4 }}>
                    {selected.description}
                  </div>
                )}
              </div>

              {/* V110: 动态 key 模式 — 推荐方案 (单键变量时显示) */}
              {allowDynamic && selected.keyColumns.length === 1 && (
                <Card
                  size="small"
                  style={{
                    marginBottom: 12,
                    borderColor: '#ffd591',
                    background: '#fffbe6',
                  }}
                  bodyStyle={{ padding: 12 }}
                >
                  <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
                    <div style={{ flex: 1 }}>
                      <div style={{ fontSize: 13, fontWeight: 600, color: '#d46b08', marginBottom: 4 }}>
                        ⚡ 动态查表（推荐）
                      </div>
                      <div style={{ fontSize: 12, color: '#666', lineHeight: 1.6 }}>
                        系统根据当前行的 <Typography.Text code>{selected.keyColumns[0]}</Typography.Text> 字段
                        (或同名别名列, 如 <code>element_name</code>) 自动查表.
                        <br />
                        多行 driver 场景 (如电镀方案、元素 BOM) 每行各自取自己那行元素的单价。
                      </div>
                    </div>
                    <Button type="primary" onClick={pickDynamic}>
                      使用动态查表
                    </Button>
                  </div>
                </Card>
              )}

              {/* 静态绑定 — 选具体一行 */}
              <div style={{ marginBottom: 6, fontSize: 12, color: '#8c8c8c' }}>
                {allowDynamic && selected.keyColumns.length === 1
                  ? '或绑定到具体值（适用于"始终用这个值"的场景）:'
                  : '选择具体 key:'}
              </div>
              <Input
                allowClear
                placeholder="搜索 key (如 Cu / 1610010128 / CNY)"
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
                style={{ marginBottom: 8 }}
              />
              <Card size="small" style={{ flex: 1, overflow: 'auto' }} loading={keysLoading}>
                {filteredKeys.length === 0 ? (
                  <Empty description="无候选 key" />
                ) : (
                  <List
                    size="small"
                    dataSource={filteredKeys}
                    renderItem={(opt) => (
                      <List.Item
                        actions={[
                          <Button
                            key="pick"
                            type="default"
                            size="small"
                            icon={<ArrowRightOutlined />}
                            onClick={() => pickStatic(opt)}
                          >
                            绑定此值
                          </Button>,
                        ]}
                      >
                        <div>
                          <div>
                            <Typography.Text code>{opt.label}</Typography.Text>
                          </div>
                          <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 2 }}>
                            当前值: <Typography.Text strong>
                              {opt.value !== null && opt.value !== undefined ? String(opt.value) : '—'}
                            </Typography.Text>
                          </div>
                        </div>
                      </List.Item>
                    )}
                  />
                )}
              </Card>
              <div style={{ marginTop: 8, fontSize: 11, color: '#8c8c8c' }}>
                💡 动态查表生成 <code>{selected.sourceView}.{selected.valueColumn}</code>;
                绑定具体值生成 <code>{selected.sourceView}[{selected.keyColumns[0]}='X'].{selected.valueColumn}</code>。
              </div>
            </>
          )}
        </div>
      </div>
    </Drawer>
  );
};

export default GlobalVariablePickerDrawer;
