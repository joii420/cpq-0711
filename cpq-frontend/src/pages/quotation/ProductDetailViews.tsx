/**
 * ProductDetailViews —— 两级只读视图切换容器（反 AP-50 单源）
 *
 * 供 QuotationDetail（报价单详情）和 CostingReviewPage（核价工作台）共用同一份
 * 两级视图渲染逻辑，避免双源维护。
 *
 * Props:
 *   quotation — quotationService.getById 返回的完整报价单对象
 *
 * 内含状态：mainTab / viewType / gvDefs / enrichedLineItems / usePathFormulaCache
 * 不向外暴露这些状态，调用方只需传入 quotation。
 */
import React, { useEffect, useState } from 'react';
import { Card, Col, Row, Segmented, Space } from 'antd';
import { Typography } from 'antd';
import { globalVariableService } from '../../services/globalVariableService';
import type { GlobalVariableDefinition } from '../../services/globalVariableService';
import ReadonlyProductCard from './ReadonlyProductCard';
import ReadonlyExcelView from './ReadonlyExcelView';
import ReadonlyComparison from './ReadonlyComparison';
import { usePathFormulaCache } from './usePathFormulaCache';
import { enrichComponentData } from './enrichComponentData';
import type { LineItem } from './QuotationStep2';

const { Text } = Typography;

interface Props {
  quotation: any;
  locateTarget?: { lineItemId?: string; productPartNo?: string; componentId?: string; seq: number } | null;
}

const ProductDetailViews: React.FC<Props> = ({ quotation, locateTarget }) => {
  // ----------------------------------------------------------------
  // 两级视图切换 state
  // ----------------------------------------------------------------
  const [mainTab, setMainTab] = useState<'quote' | 'costing' | 'comparison'>('quote');
  const [viewType, setViewType] = useState<'card' | 'excel'>('card');

  // ----------------------------------------------------------------
  // B-GV-2：动态 key 全局变量定义字典，供 ReadonlyProductCard FORMULA 字段求值
  // ----------------------------------------------------------------
  const [gvDefs, setGvDefs] = useState<Record<string, GlobalVariableDefinition>>({});
  useEffect(() => {
    globalVariableService
      .list()
      .then((res: any) => {
        const arr: GlobalVariableDefinition[] = Array.isArray(res)
          ? res
          : Array.isArray(res?.data)
            ? res.data
            : [];
        const map: Record<string, GlobalVariableDefinition> = {};
        for (const d of arr) {
          if (d?.code) map[d.code] = d;
        }
        setGvDefs(map);
      })
      .catch(() => setGvDefs({}));
  }, []);

  // ----------------------------------------------------------------
  // 任务2：enriched lineItems —— 预热 _globalPathCache。
  // 详情/核价工作台打开时 cache 是空的，需先 enrich 再让 hook 扫 path token。
  // ----------------------------------------------------------------
  const [enrichedLineItems, setEnrichedLineItems] = useState<LineItem[]>([]);
  useEffect(() => {
    if (!quotation?.lineItems?.length) {
      setEnrichedLineItems([]);
      return;
    }
    let cancelled = false;
    Promise.all(
      (quotation.lineItems as any[]).map(async (li: any) => {
        if (!li.templateId) return li as LineItem;
        const enrichedComps = await enrichComponentData(li.templateId, li.componentData || []);
        return { ...li, componentData: enrichedComps } as LineItem;
      }),
    )
      .then((result) => {
        if (!cancelled) setEnrichedLineItems(result);
      })
      .catch(() => {
        if (!cancelled) setEnrichedLineItems([]);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [quotation?.id, quotation?.lineItems?.length]);

  // 触发 path 公式缓存预热
  usePathFormulaCache(enrichedLineItems, quotation?.customerId, gvDefs);

  // ----------------------------------------------------------------
  // Plan 1b 详情页定位：cardRefs + locateResolved state
  // ----------------------------------------------------------------
  const cardRefs = React.useRef<Record<string, HTMLDivElement | null>>({});
  const [locateResolved, setLocateResolved] = useState<{ cardId?: string; componentId?: string; seq: number } | null>(null);

  // visible 上移，保证 locateTarget effect 能引用
  const visible = (quotation?.lineItems || []).filter(
    (li: any) => li.compositeType !== 'PART',
  );

  useEffect(() => {
    if (!locateTarget) return;
    setMainTab('quote');     // 后端只校验报价卡，定位恒落报价卡片视图
    setViewType('card');
    const all = (quotation?.lineItems || []) as any[];
    const hit = all.find((li: any) => li.id === locateTarget.lineItemId);
    let cardId = hit?.id;
    if (hit?.compositeType === 'PART') cardId = hit.parentLineItemId;   // PART→父卡
    if (!cardId && hit?.compositeType !== 'PART' && locateTarget.productPartNo) {
      cardId = visible.find((li: any) => li.productPartNo === locateTarget.productPartNo)?.id;  // 兜底(PART 不走)
    }
    setLocateResolved({ cardId, componentId: locateTarget.componentId, seq: locateTarget.seq });
    if (cardId && cardRefs.current[cardId]) {
      cardRefs.current[cardId]!.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locateTarget?.seq]);

  // ----------------------------------------------------------------
  // 渲染
  // ----------------------------------------------------------------

  return (
    <Card title="产品明细" style={{ marginBottom: 16 }}>
      {/* 两级 Segmented 控件 */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          gap: 12,
          marginBottom: 12,
        }}
      >
        <Segmented
          size="small"
          options={[
            { label: '报价单', value: 'quote' },
            { label: '核价单', value: 'costing' },
            { label: '比对视图', value: 'comparison' },
          ]}
          value={mainTab}
          onChange={(v) => setMainTab(v as 'quote' | 'costing' | 'comparison')}
        />
        {mainTab !== 'comparison' && (
          <Segmented
            size="small"
            options={[
              { label: '产品卡片', value: 'card' },
              { label: 'Excel 视图', value: 'excel' },
            ]}
            value={viewType}
            onChange={(v) => setViewType(v as 'card' | 'excel')}
          />
        )}
      </div>

      {/* 三渲染器 */}
      {visible.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 32, color: '#999' }}>暂无产品</div>
      ) : mainTab === 'comparison' ? (
        <ReadonlyComparison
          quotationId={quotation.id}
          lineItems={visible}
          quoteColumns={quotation.quoteExcelColumns}
          costingColumns={quotation.costingExcelColumns}
        />
      ) : viewType === 'excel' ? (
        <ReadonlyExcelView
          lineItems={visible}
          side={mainTab === 'costing' ? 'COSTING' : 'QUOTE'}
          columns={
            mainTab === 'costing' ? quotation.costingExcelColumns : quotation.quoteExcelColumns
          }
        />
      ) : (
        <div className="qt-products-list">
          {visible.map((li: any, idx: number) => {
            const isLocateTarget = locateResolved?.cardId != null && locateResolved.cardId === li.id;
            return (
              <div key={li.id || idx} ref={el => { if (li.id) cardRefs.current[li.id] = el; }}>
                <ReadonlyProductCard
                  lineItem={li}
                  index={idx}
                  quotationId={quotation.id}
                  quotationStatus={quotation.status}
                  customerId={quotation.customerId}
                  globalVariableDefs={gvDefs}
                  side={mainTab === 'costing' ? 'COSTING' : 'QUOTE'}
                  quoteCardStructure={quotation.quoteCardStructure ?? null}
                  costingCardStructure={quotation.costingCardStructure ?? null}
                  locateComponentId={isLocateTarget ? locateResolved!.componentId : undefined}
                  locateSeq={isLocateTarget ? locateResolved!.seq : undefined}
                />
              </div>
            );
          })}
        </div>
      )}

      {/* 报价汇总行（仅 quote × card 视图下显示） */}
      {mainTab === 'quote' && viewType === 'card' && (
        <Row justify="end" style={{ marginTop: 16 }}>
          <Col>
            <Space direction="vertical" align="end">
              <Text>
                原价合计：
                <Text strong>
                  ¥{Number(quotation.originalAmount || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}
                </Text>
              </Text>
              <Text>
                折扣率：<Text strong>{quotation.finalDiscountRate}%</Text>
              </Text>
              <Text style={{ fontSize: 16 }}>
                报价总金额：
                <Text strong style={{ fontSize: 18, color: '#c00' }}>
                  ¥{Number(quotation.totalAmount || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}
                </Text>
              </Text>
            </Space>
          </Col>
        </Row>
      )}
    </Card>
  );
};

export default ProductDetailViews;
