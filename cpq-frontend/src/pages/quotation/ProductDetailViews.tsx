/**
 * ProductDetailViews —— 两级只读视图切换容器（反 AP-50 单源）
 *
 * 供 QuotationDetail（报价单详情）和 CostingReviewPage（核价工作台）共用同一份
 * 两级视图渲染逻辑，避免双源维护。
 *
 * Props:
 *   quotation — quotationService.getById 返回的完整报价单对象，
 *               或 frozenDto 解析后的快照对象（frozen=true 时）
 *   frozen   — 若为 true，gvDefs 从 quotation.gvDefs 读取（不发 /global-variables 请求），
 *              enrichComponentData 也跳过（不发 /templates 请求）
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
import type { VersionSwitchResult } from '../../services/costingOrderService';

const { Text } = Typography;

interface Props {
  quotation: any;
  locateTarget?: { lineItemId?: string; productPartNo?: string; componentId?: string; seq: number } | null;
  /** 冻结模式：gvDefs 取 quotation.gvDefs，enrich 跳过，QUOTE 分支由 ReadonlyProductCard 内部离线组装 */
  frozen?: boolean;
  /** task-0713：核价单 ID。仅核价管理（CostingReviewPage）传入，供核价侧版本切换下拉调用接口；
   *  报价单详情（QuotationDetail）不传，核价侧版本下拉不出现，行为保持现状不变。 */
  coid?: string;
  /** task-0713（F4）：= status==='PENDING' && role∈{财务,管理员}，决定核价侧版本下拉是否可交互 */
  editable?: boolean;
  /** task-0713（F3）：版本切换成功后的增量回调，由上层（CostingReviewPage）合并到本地状态，
   *  本组件自身不持有 quotation 状态，只透传。 */
  onVersionSwitched?: (result: VersionSwitchResult) => void;
}

const ProductDetailViews: React.FC<Props> = ({ quotation, locateTarget, frozen, coid, editable, onVersionSwitched }) => {
  // ----------------------------------------------------------------
  // 两级视图切换 state
  // ----------------------------------------------------------------
  const [mainTab, setMainTab] = useState<'quote' | 'costing' | 'comparison'>('quote');
  const [viewType, setViewType] = useState<'card' | 'excel'>('card');

  // ----------------------------------------------------------------
  // B-GV-2：动态 key 全局变量定义字典，供 ReadonlyProductCard FORMULA 字段求值
  // frozen 模式：从 quotation.gvDefs 构建 map，不发 /global-variables 请求
  // live 模式：原有 globalVariableService.list() 拉取
  // ----------------------------------------------------------------
  const [gvDefs, setGvDefs] = useState<Record<string, GlobalVariableDefinition>>({});
  useEffect(() => {
    if (frozen && Array.isArray(quotation?.gvDefs)) {
      // frozen 模式：从冻结快照 gvDefs 数组构建 code→def map
      const map: Record<string, GlobalVariableDefinition> = {};
      for (const d of quotation.gvDefs as GlobalVariableDefinition[]) {
        if (d?.code) map[d.code] = d;
      }
      setGvDefs(map);
      return;
    }
    // live 模式：原有逻辑
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
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [frozen, quotation?.id]);

  // ----------------------------------------------------------------
  // 任务2：enriched lineItems —— 预热 _globalPathCache。
  // frozen 模式：直接用原始 lineItems（QUOTE 离线组装在 ReadonlyProductCard 内部完成）
  // live 模式：enrich 后让 hook 扫 path token
  // ----------------------------------------------------------------
  const [enrichedLineItems, setEnrichedLineItems] = useState<LineItem[]>([]);
  useEffect(() => {
    if (!quotation?.lineItems?.length) {
      setEnrichedLineItems([]);
      return;
    }
    // frozen 模式：跳过 enrichComponentData（不发 /templates 请求），直接用原始 lineItems
    if (frozen) {
      setEnrichedLineItems(quotation.lineItems as LineItem[]);
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
  }, [frozen, quotation?.id, quotation?.lineItems?.length]);

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
                  frozen={frozen}
                  coid={mainTab === 'costing' ? coid : undefined}
                  editable={mainTab === 'costing' ? editable : undefined}
                  onVersionSwitched={onVersionSwitched}
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

      {/* task-0713（F5/3a）：核价侧单据总价 —— 读 costingTotalAmount（Σ核价成本 subtotal，
          不含 Step3 折扣），与上方报价总金额（含折扣）是两条口径。切换版本后由
          onVersionSwitched 增量更新 quotation.costingTotalAmount 即时反映，不整单重查。
          精度守 cpq-decimal-display-policy：对外总额 2 位。 */}
      {mainTab === 'costing' && viewType === 'card' && (
        <Row justify="end" style={{ marginTop: 16 }}>
          <Col>
            <Space direction="vertical" align="end">
              <Text style={{ fontSize: 16 }}>
                核价单据总价：
                <Text strong style={{ fontSize: 18, color: '#c00' }}>
                  ¥{Number(quotation.costingTotalAmount || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
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
