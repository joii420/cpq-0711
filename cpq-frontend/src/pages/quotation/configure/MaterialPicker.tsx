/**
 * MaterialPicker — 材质选择器（task-260902 · F-6，服务 AC-6 / AC-17 / AC-18）。
 *
 * 1:1 对齐 `原型图/3-新建零件与多材质.html` 状态 A2 / A2-b / A2-c：
 * 浅蓝面板 + 搜索框 + 「N / M 条」计数 + 六列表格（编号/名称/首个配置的含量/含量配置组数/自定义/选择）。
 *
 * 🚨 **不可选的材质仍然列出、灰显、写明原因，🚫 不从列表里过滤掉**（`frontend.md §1.2`：
 *    禁止 `if(…) return null` 隐藏能力 —— 过滤掉用户会以为材质丢了）。两种不可选：
 *      ① `configCount === 0` → 「该材质尚未配置含量」（后端提交也会 409 `RECIPE_HAS_NO_CONFIG`）
 *      ② 本零件已添加过     → 「该材质已添加」（AC-17，防错而非报错）
 *
 * ⚠️ **不可搜索等于不可用**：实测材质库 258 条 ACTIVE，走虚拟滚动靠滚动找会随机挂
 *    （对齐 `task-260901` AC-35 踩坑记录）⇒ 必须能按**材质编号或材质名**过滤，大小写不敏感。
 *
 * 🚧 **契约缺口（已报主线）**：原型的「首个配置的含量」列要 `Ag 90% / Ni 10%` 这样的**含量值**，
 *    但 `api.md §3` 明确 `GET /material-recipes` 列表里 `composition` / `configs` **均为 null**，
 *    只有 `elementCodes: ["Ag","Ni"]`（元素符号，无百分比）。258 条逐条调详情不可行。
 *    ⇒ 本组件的处置：**先用 `elementCodes` 立刻渲染出元素符号**（`Ag / Ni`，不是空白也不是「加载中…」），
 *      再对**当前页可见的行**按需拉 `detail()` 补上百分比，结果进内存缓存。
 *      这样任何时刻都有可读内容，AP-31 的「加载中永久占位」不可能出现。
 */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Input, Table, Tag } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  materialRecipeService,
  type MaterialRecipeDetail,
  type MaterialRecipeLite,
} from '../../../services/materialRecipeService';
import { formatPctText } from '../../../utils/precision';
import { EmptyBlock, Ellipsis, Mono, NoteBlock, ReasonedButton, pickerPanelStyle } from './configureUi';

interface Props {
  /** 全量 ACTIVE 材质（调用方已按 status 过滤）。 */
  materials: MaterialRecipeLite[];
  loading?: boolean;
  loadError?: string | null;
  /** 本零件已添加的材质编号（AC-17 灰显判据）。 */
  addedCodes: ReadonlySet<string>;
  onSelect: (lite: MaterialRecipeLite) => void;
  onClose: () => void;
}

const PAGE_SIZE = 10;

/** `Ag 90% / Ni 10%` —— 含量一律去尾随零（走 `formatPctText` 的字符串正则，🚫 不过 `Number`）。 */
function firstConfigContentText(detail: MaterialRecipeDetail | undefined, lite: MaterialRecipeLite): string {
  const cfg = detail?.configs?.[0];
  if (cfg && cfg.elements.length > 0) {
    const order = (detail?.composition ?? []).map((c) => c.elementNo);
    const byNo = new Map(cfg.elements.map((e) => [e.elementNo ?? e.elementCode, e]));
    const keys = order.length > 0 ? order : cfg.elements.map((e) => e.elementNo ?? e.elementCode);
    const parts = keys
      .map((k) => byNo.get(k))
      .filter((e): e is NonNullable<typeof e> => !!e)
      .map((e) => `${e.elementCode} ${formatPctText(e.defaultPct)}%`);
    if (parts.length > 0) return parts.join(' / ');
  }
  // 回落：列表接口能给的只有元素符号，先把它显示出来 —— 有内容永远好过「加载中…」
  const codes = lite.elementCodes ?? [];
  return codes.length > 0 ? codes.join(' / ') : '—';
}

const MaterialPicker: React.FC<Props> = ({ materials, loading, loadError, addedCodes, onSelect, onClose }) => {
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [detailCache, setDetailCache] = useState<Record<string, MaterialRecipeDetail>>({});
  const inflight = useRef<Set<string>>(new Set());

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return materials;
    // 编号与名称都参与过滤；大小写不敏感（AC-18 ④「agni」与「AgNi」结果集必须相同）；
    // 中文名可搜由 `name` 参与匹配保证（AC-18 ⑤「镀铜」命中 DCO3镀铜 / 铁镀铜）。
    return materials.filter((m) => {
      const hay = `${m.code ?? ''} ${m.symbol ?? ''} ${m.name ?? ''}`.toLowerCase();
      return hay.includes(kw);
    });
  }, [materials, keyword]);

  useEffect(() => { setPage(1); }, [keyword]);

  const visible = useMemo(
    () => filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE),
    [filtered, page],
  );

  // 只为**当前页可见的行**补拉详情（含量百分比）。258 条逐条拉不可行，见文件头「契约缺口」。
  useEffect(() => {
    const todo = visible.filter((m) => m.id && !detailCache[m.id] && !inflight.current.has(m.id));
    if (todo.length === 0) return;
    let cancelled = false;
    todo.forEach((m) => inflight.current.add(m.id));
    const timer = window.setTimeout(() => {
      Promise.all(todo.map((m) => materialRecipeService.detail(m.id).then(
        (d) => [m.id, d] as const,
        () => null,
      ))).then((results) => {
        todo.forEach((m) => inflight.current.delete(m.id));
        if (cancelled) return;
        const patch: Record<string, MaterialRecipeDetail> = {};
        results.forEach((r) => { if (r) patch[r[0]] = r[1]; });
        if (Object.keys(patch).length > 0) setDetailCache((prev) => ({ ...prev, ...patch }));
      });
    }, 250); // 打字时不要每个键都打一轮详情
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, [visible, detailCache]);

  /** 不可选的原因（null = 可选）。🚫 返回原因 ≠ 从列表里剔除。 */
  const disabledReason = (m: MaterialRecipeLite): string | null => {
    if ((m.configCount ?? 0) === 0) return '该材质尚未配置含量';
    if (addedCodes.has(m.code)) return '该材质已添加';
    return null;
  };

  const columns: ColumnsType<MaterialRecipeLite> = [
    { title: '材质编号', dataIndex: 'code', key: 'code', width: 110, render: (v: string) => <Mono muted>{v}</Mono> },
    {
      title: '材质名',
      key: 'name',
      width: 200,
      render: (_v, m) => <Ellipsis text={m.symbol || m.name} />,
    },
    {
      title: '首个配置的含量',
      key: 'content',
      render: (_v, m) => (
        <span style={{ fontSize: 12, color: '#909399' }}>
          <Ellipsis text={firstConfigContentText(detailCache[m.id], m)} />
        </span>
      ),
    },
    {
      title: '含量配置',
      key: 'configCount',
      width: 96,
      align: 'right',
      render: (_v, m) => {
        const n = m.configCount ?? 0;
        // 0 组直接用红 tag 暴露出来 —— 这一列的存在意义就是让用户不点进去也知道它不可用
        return n === 0 ? <Tag color="red">0 组</Tag> : <span>{n} 组</span>;
      },
    },
    {
      title: '自定义',
      key: 'allowCustom',
      width: 110,
      render: (_v, m) => (m.allowCustomContent ? <Tag color="gold">支持自定义</Tag> : <Tag>仅标准</Tag>),
    },
    {
      title: '',
      key: 'pick',
      width: 90,
      align: 'right',
      render: (_v, m) => (
        <ReasonedButton size="small" type="primary" reason={disabledReason(m)} onClick={() => onSelect(m)}>
          选择
        </ReasonedButton>
      ),
    },
  ];

  return (
    <div style={pickerPanelStyle}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12, flexWrap: 'wrap' }}>
        <b>选择材质</b>
        <Input
          prefix={<SearchOutlined />}
          allowClear
          placeholder="输入材质编号或材质名过滤，如 00006 / AgNi / AgZnO12/Cu"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          style={{ maxWidth: 320 }}
        />
        <span style={{ fontSize: 12, color: '#909399' }}>{filtered.length} / {materials.length} 条</span>
        <div style={{ flex: 1 }} />
        <Button size="small" onClick={onClose}>取消</Button>
      </div>

      <div style={{ background: '#fff', borderRadius: 6 }}>
        {loadError ? (
          <EmptyBlock icon="⚠" title="材质库加载失败" hint={loadError} />
        ) : loading ? (
          <Table<MaterialRecipeLite> rowKey="id" size="small" loading pagination={false} dataSource={[]} columns={columns} />
        ) : materials.length === 0 ? (
          /* 🚨 空是空，不是「加载中…」（AP-31） */
          <EmptyBlock
            icon="🧪"
            title="材质库里还没有启用的材质"
            hint={<>材质需要先在<b>主数据维护 → 材质</b>里录入并配置含量</>}
            actions={<Button size="small" onClick={() => window.open('/config/material-recipes', '_blank')}>→ 打开材质管理</Button>}
          />
        ) : filtered.length === 0 ? (
          <EmptyBlock
            icon="🔍"
            title={`没有匹配「${keyword.trim()}」的材质`}
            hint="试试材质编号（00006）或材质名（AgNi / AgZnO12/Cu）"
          />
        ) : (
          <Table<MaterialRecipeLite>
            rowKey="id"
            size="small"
            dataSource={filtered}
            columns={columns}
            pagination={{
              current: page,
              pageSize: PAGE_SIZE,
              total: filtered.length,
              size: 'small',
              showSizeChanger: false,
              onChange: setPage,
            }}
            onRow={(m) => ({ style: disabledReason(m) ? { opacity: 0.45 } : undefined })}
          />
        )}
      </div>

      <NoteBlock style={{ background: '#fff' }}>
        🚫 <b>不可选的材质仍然列出、灰显并写明原因，不从列表里过滤掉</b> —— 过滤掉用户会以为材质丢了。
        <br />
        两种不可选：<b>含量配置 0 组</b>（后端提交也会 409 <code>RECIPE_HAS_NO_CONFIG</code>）·
        <b>本零件已添加过</b>（同一个零件里同一材质不能重复）。
      </NoteBlock>
    </div>
  );
};

export default MaterialPicker;
