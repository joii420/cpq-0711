import React, { useCallback, useEffect, useState } from 'react';
import { Drawer, Table, Tag, Tooltip, Button, Alert, Spin, message, Collapse } from 'antd';
import dayjs from 'dayjs';
import { priceRevisionService } from '../../services/priceRevisionService';
import { buildMaterialVersionLabel } from './materialVersionLabel';
import type {
  PriceRevisionDTO, MaterialVersionRowDTO,
  RevisionPreviewResponse, RevisionPreviewLineItemDTO,
} from '../../types/price-adjust';
import { formatNumber } from '../../utils/formatNumber';
import type { DecimalString } from '../../utils/precision';

export interface QuotationPriceRevisionsDrawerProps {
  open: boolean;
  quotationId: string | null;
  onClose: () => void;
}

function fmtMoney(v: DecimalString | null | undefined): string {
  return formatNumber(v, { isComputed: true, decimals: 2 }) ?? '—';
}

/**
 * 屏 7 · 报价单价格版本抽屉（1000px，fronttask §6 / api.md §4）。
 * 入口：报价单详情页 + 编辑页顶部「价格版本」按钮；全角色可见，销售只读（api.md §0.1）。
 *
 * 两张表 + 切版只读预览：
 * ① 整单版本轨迹表（revisions[]）—— 按 api.md §4.1 给定字段忠实渲染，不发明"涨跌"列：
 *    PriceRevisionDTO 本身不带逐料号价格增量字段，fronttask §6.1 提到的"涨跌对比按料号级对齐"
 *    在当前契约下找不到落点（见文件末尾注释），本组件按"不逐行对齐、不因行数不同报错"的
 *    防御性原则处理"已升版料号"集合展示，未虚构涨跌百分比。
 * ② 料号级价格版本表（materialVersions[]）—— §11.1.1"单内混合价"的可查证据。
 *    🔒 state=NOT_UPDATED 必须显式标"尚未更新"，绝不直接展示 currentVersionNo
 *    （那是指针已推进但单未更新成功时的误导性显示，本组件对该 state 分支永不渲染版本号）。
 * ③ 切版只读预览：所有输入框/保存/提交/导出全部不提供（本组件从未渲染任何写操作按钮）；
 *    🔒 双侧都从 preview 接口的快照渲染，禁止读取其他数据源（本组件除 getPriceRevisionPreview
 *    外不发起任何其他请求，结构上杜绝"核价侧读当前值"的错误）。
 */
const QuotationPriceRevisionsDrawer: React.FC<QuotationPriceRevisionsDrawerProps> = ({ open, quotationId, onClose }) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revisions, setRevisions] = useState<PriceRevisionDTO[]>([]);
  const [materialVersions, setMaterialVersions] = useState<MaterialVersionRowDTO[]>([]);

  const [previewRevision, setPreviewRevision] = useState<PriceRevisionDTO | null>(null);
  const [previewData, setPreviewData] = useState<RevisionPreviewResponse | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);

  const load = useCallback(async () => {
    if (!quotationId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await priceRevisionService.getPriceRevisions(quotationId);
      setRevisions(res.revisions || []);
      setMaterialVersions(res.materialVersions || []);
    } catch (e: any) {
      setError(e?.message || '加载价格版本失败');
    } finally {
      setLoading(false);
    }
  }, [quotationId]);

  useEffect(() => {
    if (open && quotationId) { load(); setPreviewRevision(null); setPreviewData(null); }
  }, [open, quotationId, load]);

  const openPreview = async (r: PriceRevisionDTO) => {
    if (!quotationId) return;
    setPreviewRevision(r);
    setPreviewLoading(true);
    setPreviewData(null);
    try {
      const data = await priceRevisionService.getPriceRevisionPreview(quotationId, r.revisionId);
      setPreviewData(data);
    } catch (e: any) {
      message.error(e?.message || '加载切版预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

  // 🔒 退出预览恢复：清空预览态即可，不涉及任何落库/撤销操作（裁决14：预览本就不落库）
  const exitPreview = () => { setPreviewRevision(null); setPreviewData(null); };

  const revisionColumns = [
    { title: 'R 版本号', dataIndex: 'revisionNo', render: (v: string, r: PriceRevisionDTO) => <span><b style={{ fontFamily: 'monospace' }}>{v}</b>{r.isInitial && <Tag style={{ marginLeft: 6 }}>初版</Tag>}</span> },
    { title: '依据 V 版本号', dataIndex: 'basedVersionNo', render: (v: string | null) => v ? <span style={{ fontFamily: 'monospace' }}>{v}</span> : '—' },
    {
      title: '已升版料号', dataIndex: 'upgradedMaterialNos',
      render: (v: string[]) => v && v.length
        ? <Tooltip title={v.join('、')}><span>{v.length} 个</span></Tooltip>
        : '—',
    },
    { title: '首次生效', dataIndex: 'firstEffectiveAt', render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—' },
    { title: '最后更新', dataIndex: 'lastUpdatedAt', render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—' },
    { title: '报价总额', dataIndex: 'quoteTotalAmount', align: 'right' as const, render: (v: DecimalString) => fmtMoney(v) },
    {
      title: '操作', render: (_: unknown, r: PriceRevisionDTO) => <a onClick={() => openPreview(r)}>切版预览</a>,
    },
  ];

  const materialColumns = [
    { title: '料号', dataIndex: 'materialNo', render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '料号名称', dataIndex: 'materialName' },
    {
      // 🔒 渲染文案由纯函数 buildMaterialVersionLabel 产出（materialVersionLabel.test.ts 锁住
      // NOT_UPDATED 分支绝不吐出 currentVersionNo 这条规则），本处只负责挑选展示形态
      title: '所处版本', render: (_: unknown, r: MaterialVersionRowDTO) => {
        const label = buildMaterialVersionLabel(r);
        if (r.state === 'UPGRADED') return <span style={{ fontFamily: 'monospace' }}>{label.text}</span>;
        if (r.state === 'REJECTED') return <span><span style={{ fontFamily: 'monospace' }}>{r.currentVersionNo}</span> <Tag>未升版</Tag></span>;
        if (r.state === 'NOT_UPDATED') {
          return (
            <Tooltip title="料号指针已推进到新版本，但该报价单尚未成功应用新价（更新任务处理中或失败），请到「更新任务」页查看">
              <Tag color="orange">{label.text}</Tag>
            </Tooltip>
          );
        }
        return <Tag>{label.text}</Tag>;
      },
    },
  ];

  return (
    <Drawer
      title="价格版本"
      placement="right"
      width={1000}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      {loading && <div style={{ textAlign: 'center', padding: 48 }}><Spin tip="加载中…" /></div>}
      {error && <Alert type="error" showIcon message={error} />}

      {!loading && !error && !previewRevision && (
        <>
          <h4 style={{ marginBottom: 10 }}>整单版本轨迹</h4>
          <Table<PriceRevisionDTO>
            size="small"
            rowKey="revisionId"
            dataSource={revisions}
            columns={revisionColumns}
            pagination={false}
            style={{ marginBottom: 28 }}
          />

          <h4 style={{ marginBottom: 4 }}>料号级价格版本</h4>
          <div style={{ fontSize: 12.5, color: 'rgba(0,0,0,.45)', marginBottom: 10 }}>
            单内每个料号各处在哪一版——「单内混合价」的可查证据（§11.1.1）。
          </div>
          <Table<MaterialVersionRowDTO>
            size="small"
            rowKey="materialNo"
            dataSource={materialVersions}
            columns={materialColumns}
            pagination={false}
          />
        </>
      )}

      {previewRevision && (
        <div>
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message={`只读预览 · ${previewRevision.revisionNo}`}
            description="预览态下所有输入框、保存、提交、导出均已禁用（本视图不提供任何写操作入口）。双侧数据均来自该版本快照，与当前实际值无关。"
            action={<Button size="small" onClick={exitPreview}>退出预览</Button>}
          />
          {previewLoading && <div style={{ textAlign: 'center', padding: 48 }}><Spin tip="加载预览中…" /></div>}
          {previewData && (
            <>
              <div style={{ marginBottom: 12, fontSize: 13 }}>
                报价总额（该版本快照）：<b>{fmtMoney(previewData.quoteTotalAmount)}</b>
              </div>
              <Collapse
                items={previewData.lineItems.map((li: RevisionPreviewLineItemDTO) => ({
                  key: li.lineItemId,
                  label: <span style={{ fontFamily: 'monospace' }}>{li.materialNo}</span>,
                  children: (
                    <div style={{ display: 'flex', gap: 24 }}>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontWeight: 600, marginBottom: 6 }}>报价侧（快照）</div>
                        <pre style={{ fontSize: 11.5, background: '#fafafa', padding: 10, borderRadius: 4, maxHeight: 240, overflow: 'auto' }}>
                          {JSON.stringify(li.quoteCardValues, null, 2)}
                        </pre>
                      </div>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontWeight: 600, marginBottom: 6 }}>核价侧（快照，🔒 非当前值）</div>
                        <pre style={{ fontSize: 11.5, background: '#fafafa', padding: 10, borderRadius: 4, maxHeight: 240, overflow: 'auto' }}>
                          {JSON.stringify(li.costingCardValues, null, 2)}
                        </pre>
                      </div>
                    </div>
                  ),
                }))}
              />
            </>
          )}
        </div>
      )}
    </Drawer>
  );
};

export default QuotationPriceRevisionsDrawer;

/**
 * ⚠️ 未落地点记录（诚实边界，供技术总监复核）：
 * fronttask §6.1 "🔒 涨跌对比按料号级对齐（§11.7.0 边界2）：两版都有→算涨跌；只在新版有→标
 * 「本期新增」；只在旧版有→标「已移除」" —— api.md §4.1 的 PriceRevisionDTO 只给
 * upgradedMaterialNos（纯料号清单）与 quoteTotalAmount（整单聚合），不含逐料号价格，
 * MaterialVersionRowDTO 也只有 state/currentVersionNo，同样不含价格。两个 DTO 均无法支撑
 * "算涨跌 / 本期新增 / 已移除"的三态判定。本组件老实按现有字段渲染，未凭空拼出涨跌列。
 * 若该规则要落地，需要 api.md 补一个"两版料号级价格对齐"的数据源（例如在 revisions[] 或
 * 单独端点里给出 { materialNo, prevAmount, currAmount } 数组）。
 */
