/**
 * AddProductModal — 报价单 Step2「添加产品 ▾ → 从已有产品添加」抽屉（task-0712 F4）。
 *
 * 1:1 复刻 dev-docs/task-0712-选配模板和报价单选配功能/prototypes/原型-报价单-从已有产品添加.html：
 * 抽屉(960) → 顶部 4 过滤(客户产品编号/销售料号/品名/规格 + 查询/重置) → 左列表(多选+全选) +
 * 右 3D 预览(单击行切换，无 3D 占位"该料号未配置 3D 模型") → 底部"已选 N 项" + 取消/加入报价单。
 *
 * 语义变更（D8，取代旧"三步向导：选产品→选工序→选模板"）：直接加成品销售料号（可多选批量），
 * 套报价单已绑客户报价模板渲染，不再走材质/工序配置。数据源 material_customer_map，按本报价单
 * 客户过滤（服务端从 quotation 派生 customer_no，前端不传客户，见 api.md §2.1）。
 *
 * 落库：不新建端点（backtask B4 已核对），复用 BulkImportPartsDrawer.buildLineItemFromTemplate
 * 把 ExistingProductDTO 映射成 LineItem，父组件(QuotationWizard)负责去重追加 + 既有 saveDraft 落库。
 */
import React, { useEffect, useState } from 'react';
import { Drawer, Table, Input, Button, Empty, Tooltip, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { LineItem } from './QuotationStep2';
import { buildLineItemFromTemplate } from './BulkImportPartsDrawer';
import { quotationService } from '../../services/quotationService';
import { templateService } from '../../services/templateService';
import { modelConfigService } from '../../services/modelConfigService';
import type { ExistingProductDTO, ExistingProductQueryParams } from '../../types/existingProduct';
import type { ModelConfigDTO } from '../../types/modelConfig';

export interface AddProductModalProps {
  open: boolean;
  /** 查候选/3D 都要（api.md §2.1 服务端从 quotation 派生客户）。Step2 打开此抽屉时报价单已创建，恒非空。 */
  quotationId: string | undefined;
  /** 已绑定的客户报价模板 id；用于「加入报价单」时展开 LineItem。 */
  customerTemplateId: string | undefined;
  onCancel: () => void;
  onConfirm: (lineItems: LineItem[]) => void;
}

const EMPTY_FILTERS: ExistingProductQueryParams = {
  customerProductNo: '',
  salesPartNo: '',
  productName: '',
  spec: '',
};

const PAGE_SIZE = 20;

const AddProductModal: React.FC<AddProductModalProps> = ({
  open,
  quotationId,
  customerTemplateId,
  onCancel,
  onConfirm,
}) => {
  const [filters, setFilters] = useState<ExistingProductQueryParams>(EMPTY_FILTERS);
  const [appliedFilters, setAppliedFilters] = useState<ExistingProductQueryParams>({});
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);

  const [list, setList] = useState<ExistingProductDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const [activeRow, setActiveRow] = useState<ExistingProductDTO | null>(null);

  const [preview, setPreview] = useState<ModelConfigDTO | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [zoomHint, setZoomHint] = useState(false);

  const [confirming, setConfirming] = useState(false);

  // 每次打开重置为初始态（对齐原型 openDrawer()：过滤条 + 选中态 + 预览目标全部重置）。
  useEffect(() => {
    if (!open) return;
    setFilters(EMPTY_FILTERS);
    setAppliedFilters({});
    setPage(0);
    setSelectedRowKeys([]);
    setActiveRow(null);
    setZoomHint(false);
  }, [open]);

  // 拉列表：打开 / 过滤条件变化 / 翻页 时查询。
  useEffect(() => {
    if (!open || !quotationId) return;
    let cancelled = false;
    setLoading(true);
    quotationService
      .listExistingProducts(quotationId, { ...appliedFilters, page, size: PAGE_SIZE })
      .then((res) => {
        if (cancelled) return;
        const content = res.content || [];
        setList(content);
        setTotal(res.totalElements || 0);
        // 若当前预览目标不在新结果集中（切换过滤/翻页），回退到结果首行；对齐原型 applyFilter()。
        setActiveRow((prev) => {
          if (prev && content.some((p) => p.materialNo === prev.materialNo)) return prev;
          return content[0] ?? null;
        });
      })
      .catch((e: any) => {
        if (cancelled) return;
        message.error(e?.message || '加载已有产品列表失败');
        setList([]);
        setTotal(0);
        setActiveRow(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, quotationId, appliedFilters, page]);

  // 右侧 3D 预览：随 activeRow(销售料号) 切换，实时查 model-configs/current（D3/D15）。
  // 防抖/取消：连续切行时用 AbortController 丢弃过期响应，避免预览图闪回旧值。
  useEffect(() => {
    setZoomHint(false);
    if (!activeRow) {
      setPreview(null);
      setPreviewLoading(false);
      return;
    }
    const controller = new AbortController();
    setPreviewLoading(true);
    modelConfigService
      .current({ subjectType: 'SALES_PART', subjectKey: activeRow.materialNo }, controller.signal)
      .then((data) => {
        if (controller.signal.aborted) return;
        setPreview(data);
      })
      .catch((e: any) => {
        if (controller.signal.aborted || e?.code === 'ERR_CANCELED' || e?.name === 'CanceledError') return;
        setPreview(null);
      })
      .finally(() => {
        if (!controller.signal.aborted) setPreviewLoading(false);
      });
    return () => controller.abort();
  }, [activeRow]);

  const handleQuery = () => {
    setPage(0);
    setAppliedFilters({ ...filters });
  };

  const handleReset = () => {
    setFilters(EMPTY_FILTERS);
    setPage(0);
    setAppliedFilters({});
  };

  const handleFilterKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleQuery();
  };

  const handleConfirm = async () => {
    if (selectedRowKeys.length === 0) return;
    if (!customerTemplateId) {
      message.error('当前报价单未绑定客户报价模板，无法加入产品');
      return;
    }
    setConfirming(true);
    try {
      const tplRes = await templateService.getById(customerTemplateId);
      const tmpl = tplRes.data;
      const selected = list.filter((p) => selectedRowKeys.includes(p.materialNo));
      const lineItems = selected.map((p) =>
        buildLineItemFromTemplate(tmpl, {
          partNo: p.materialNo,
          partName: p.productName || p.customerMaterialName || p.materialNo,
          customerProductNo: p.customerProductNo || undefined,
          customerPartName: p.customerMaterialName || undefined,
          customerSpecific: false,
        }),
      );
      onConfirm(lineItems);
      message.success(`已加入 ${lineItems.length} 个产品`);
    } catch (e: any) {
      message.error(e?.message || '加入报价单失败');
    } finally {
      setConfirming(false);
    }
  };

  const columns: ColumnsType<ExistingProductDTO> = [
    {
      title: '客户产品编号',
      dataIndex: 'customerProductNo',
      key: 'customerProductNo',
      render: (v: string | null) => v || '—',
    },
    { title: '销售料号', dataIndex: 'materialNo', key: 'materialNo' },
    { title: '品名', dataIndex: 'productName', key: 'productName', render: (v: string | null) => v || '—' },
    { title: '规格', dataIndex: 'spec', key: 'spec', render: (v: string | null) => v || '—' },
    {
      title: '客户物料名',
      dataIndex: 'customerMaterialName',
      key: 'customerMaterialName',
      render: (v: string | null) => v || '—',
    },
  ];

  const renderPreviewBox = () => {
    if (!activeRow) {
      return (
        <div
          style={{
            aspectRatio: '1/1',
            background: '#fafafa',
            color: '#c0c4cc',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 8,
            fontSize: 13,
            textAlign: 'center',
            padding: '0 16px',
          }}
        >
          <div style={{ fontSize: 30 }}>🧊</div>
          <div>请选择左侧产品行以预览 3D</div>
        </div>
      );
    }
    if (previewLoading) {
      return (
        <div
          style={{
            aspectRatio: '1/1',
            background: '#fafafa',
            color: '#c0c4cc',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 8,
            fontSize: 13,
          }}
        >
          <div style={{ fontSize: 30 }}>🧊</div>
          <div>加载中…</div>
        </div>
      );
    }
    if (preview) {
      return (
        <div
          style={{
            aspectRatio: '1/1',
            background: preview.thumbnailUrl
              ? `url(${preview.thumbnailUrl}) center/cover no-repeat`
              : 'linear-gradient(135deg,#e6f0ff,#dfe7f5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#7a8aa8',
            fontSize: 34,
            position: 'relative',
          }}
        >
          {!preview.thumbnailUrl && '🧊'}
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              setZoomHint(true);
              window.setTimeout(() => setZoomHint(false), 2000);
            }}
            style={{
              position: 'absolute',
              top: 8,
              right: 8,
              padding: '3px 9px',
              fontSize: 12,
              background: '#fff',
              border: '1px solid #dcdfe6',
              borderRadius: 4,
              cursor: 'pointer',
              color: '#606266',
            }}
          >
            ⤢ 交互查看
          </button>
          <div
            style={{
              position: 'absolute',
              left: 10,
              right: 10,
              bottom: 10,
              background: 'rgba(0,0,0,.72)',
              color: '#fff',
              fontSize: 12,
              padding: '7px 10px',
              borderRadius: 4,
              opacity: zoomHint ? 1 : 0,
              pointerEvents: 'none',
              transition: 'opacity .2s',
              textAlign: 'center',
            }}
          >
            （可旋转 3D 模型，增强项）
          </div>
        </div>
      );
    }
    return (
      <div
        style={{
          aspectRatio: '1/1',
          background: '#fafafa',
          color: '#c0c4cc',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 8,
          fontSize: 13,
          textAlign: 'center',
          padding: '0 16px',
        }}
      >
        <div style={{ fontSize: 30 }}>🚫</div>
        <div>该料号未配置 3D 模型</div>
      </div>
    );
  };

  const renderPreviewCap = () => {
    if (!activeRow) return null;
    if (preview) {
      return (
        <>
          销售料号: {activeRow.materialNo}
          <br />
          模型: {preview.label || '—'}
        </>
      );
    }
    return <>销售料号: {activeRow.materialNo}</>;
  };

  return (
    <Drawer
      title="添加产品 — 从已有产品"
      placement="right"
      width={960}
      open={open}
      onClose={onCancel}
      destroyOnClose
      footer={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ marginRight: 'auto', color: '#606266' }}>已选 {selectedRowKeys.length} 项</div>
          <Button onClick={onCancel} disabled={confirming}>
            取消
          </Button>
          {selectedRowKeys.length === 0 ? (
            <Tooltip title="请至少选择一项产品">
              <Button type="primary" disabled>
                加入报价单
              </Button>
            </Tooltip>
          ) : (
            <Button type="primary" loading={confirming} onClick={handleConfirm}>
              加入报价单
            </Button>
          )}
        </div>
      }
    >
      {/* 过滤条 */}
      <div
        style={{
          display: 'flex',
          gap: 12,
          alignItems: 'flex-end',
          flexWrap: 'wrap',
          marginBottom: 16,
          paddingBottom: 16,
          borderBottom: '1px solid #f0f0f0',
        }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label style={{ fontSize: 12, color: '#909399' }}>客户产品编号</label>
          <Input
            style={{ width: 150 }}
            placeholder="如 CP-SIE-2201"
            value={filters.customerProductNo}
            onChange={(e) => setFilters((f) => ({ ...f, customerProductNo: e.target.value }))}
            onKeyDown={handleFilterKeyDown}
          />
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label style={{ fontSize: 12, color: '#909399' }}>销售料号</label>
          <Input
            style={{ width: 150 }}
            placeholder="如 SP-10110001"
            value={filters.salesPartNo}
            onChange={(e) => setFilters((f) => ({ ...f, salesPartNo: e.target.value }))}
            onKeyDown={handleFilterKeyDown}
          />
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label style={{ fontSize: 12, color: '#909399' }}>品名</label>
          <Input
            style={{ width: 150 }}
            placeholder="如 传动轴"
            value={filters.productName}
            onChange={(e) => setFilters((f) => ({ ...f, productName: e.target.value }))}
            onKeyDown={handleFilterKeyDown}
          />
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label style={{ fontSize: 12, color: '#909399' }}>规格</label>
          <Input
            style={{ width: 150 }}
            placeholder="如 Φ20×150mm"
            value={filters.spec}
            onChange={(e) => setFilters((f) => ({ ...f, spec: e.target.value }))}
            onKeyDown={handleFilterKeyDown}
          />
        </div>
        <Button type="primary" onClick={handleQuery}>
          查询
        </Button>
        <Button onClick={handleReset}>重置</Button>
      </div>

      {/* 左列表 + 右 3D 预览 */}
      <div style={{ display: 'flex', gap: 18, alignItems: 'flex-start' }}>
        <div style={{ flex: '0 0 62%', maxWidth: '62%', minWidth: 0 }}>
          <div style={{ border: '1px solid #f0f0f0', borderRadius: 4, overflow: 'hidden' }}>
            <Table<ExistingProductDTO>
              rowKey="materialNo"
              size="small"
              loading={loading}
              columns={columns}
              dataSource={list}
              pagination={
                total > PAGE_SIZE
                  ? {
                      current: page + 1,
                      pageSize: PAGE_SIZE,
                      total,
                      size: 'small',
                      showSizeChanger: false,
                      onChange: (p) => setPage(p - 1),
                    }
                  : false
              }
              rowSelection={{
                selectedRowKeys,
                onChange: (keys) => setSelectedRowKeys(keys as string[]),
                preserveSelectedRowKeys: true,
              }}
              onRow={(record) => ({
                onClick: () => setActiveRow(record),
                style: {
                  cursor: 'pointer',
                  background: activeRow?.materialNo === record.materialNo ? '#e6f7ff' : undefined,
                },
              })}
              locale={{
                emptyText: (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description="未查到匹配的产品，请调整过滤条件后重试"
                    style={{ padding: '36px 0' }}
                  />
                ),
              }}
            />
          </div>
        </div>

        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ border: '1px solid #e4e7ed', borderRadius: 8, overflow: 'hidden' }}>
            {renderPreviewBox()}
            <div
              style={{
                padding: '8px 10px',
                fontSize: 12.5,
                color: '#606266',
                borderTop: '1px solid #f0f0f0',
                lineHeight: 1.6,
              }}
            >
              {renderPreviewCap()}
            </div>
          </div>
          <div style={{ fontSize: 12, color: '#909399', marginTop: 8, lineHeight: 1.6 }}>
            单击左侧产品行可切换预览；「⤢ 交互查看」为增强项占位。
          </div>
        </div>
      </div>
    </Drawer>
  );
};

export default AddProductModal;
