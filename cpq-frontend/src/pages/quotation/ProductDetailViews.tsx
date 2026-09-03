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
import { Button, Card, Col, Row, Segmented, Space } from 'antd';
import { Typography } from 'antd';
import { globalVariableService } from '../../services/globalVariableService';
import type { GlobalVariableDefinition } from '../../services/globalVariableService';
import ReadonlyProductCard from './ReadonlyProductCard';
import ReadonlyExcelView from './ReadonlyExcelView';
import ComparisonBoard from './ComparisonBoard';
import { formatNumber } from '../../utils/formatNumber';
import { usePathFormulaCache } from './usePathFormulaCache';
import { enrichComponentData } from './enrichComponentData';
import type { LineItem } from './QuotationStep2';
import type { VersionSwitchResult } from '../../services/costingOrderService';
import { usePagedSearch } from './usePagedSearch';
import PagingBar from './PagingBar';

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
  /** repair-0806：一级视图初始值（深链预选）。不传时保持既有默认 'quote'，
   *  仅作 useState 初值，切换后仍由用户交互接管，不受上层重渲染回拉。 */
  initialMainTab?: 'quote' | 'costing' | 'comparison';
}

const ProductDetailViews: React.FC<Props> = ({ quotation, locateTarget, frozen, coid, editable, onVersionSwitched, initialMainTab }) => {
  // ----------------------------------------------------------------
  // task-0717：比对视图（ComparisonBoard）桶 / 只读判定。
  // 本组件被两个调用方共用：
  //   - QuotationDetail（报价单详情，销售只读）：不传 coid → bucket=SALES，恒 readonly（详情页不可配置）。
  //   - CostingReviewPage（核价单页面，财务）：传 coid → bucket=FINANCE，readonly = !editable
  //     （editable = PENDING + 财务/管理员，即"核价单页面（财务，可配置）"；非 editable 时即
  //     "核价单详情（财务只读）"——两种场景由同一 editable 语义自然覆盖，无需拆出独立入口）。
  // 两处 comparison 数据口径都读冻结快照（frozen=true，见 api.md §2 优雅降级为 live）。
  // ----------------------------------------------------------------
  const comparisonBucket: 'SALES' | 'FINANCE' = coid ? 'FINANCE' : 'SALES';
  const comparisonReadonly = coid ? !editable : true;

  // ----------------------------------------------------------------
  // 两级视图切换 state
  // ----------------------------------------------------------------
  const [mainTab, setMainTab] = useState<'quote' | 'costing' | 'comparison'>(initialMainTab ?? 'quote');
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

  // task-260825（F-3）：详情页前端分页 + 料号查询，独立于编辑页 QuotationStep2 的分页状态（各页面各自独立）。
  const paging = usePagedSearch<any>({
    items: visible,
    getSearchFields: (li) => [li.productPartNo, li.customerProductNo, li.customerPartName],
  });
  const {
    page: pgPage, setPage: pgSetPage, pageSize: pgPageSize, setPageSize: pgSetPageSize,
    searchInput: pgSearchInput, setSearchInput: pgSetSearchInput,
    total: pgTotal, matchedTotal: pgMatchedTotal, isSearching: pgIsSearching,
    pagedItems: pagedVisible, showPager, pageSizeOptions: pgPageSizeOptions,
  } = paging;
  const handlePagerChange = (p: number, ps: number) => {
    pgSetPage(p);
    if (ps !== pgPageSize) pgSetPageSize(ps);
  };
  const renderPagingBar = () => (
    <PagingBar
      total={pgTotal}
      matchedTotal={pgMatchedTotal}
      isSearching={pgIsSearching}
      page={pgPage}
      pageSize={pgPageSize}
      pageSizeOptions={pgPageSizeOptions}
      onPageChange={handlePagerChange}
      searchValue={pgSearchInput}
      onSearchChange={pgSetSearchInput}
    />
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
    if (cardId) {
      const pos = visible.findIndex((li: any) => li.id === cardId);
      if (pos >= 0) paging.locateToPosition(pos);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locateTarget?.seq]);

  // 页码切好、目标卡片在新页重新挂载后再滚动（双 rAF 确保切页渲染已提交）
  useEffect(() => {
    if (!locateResolved?.cardId) return;
    const id = locateResolved.cardId;
    let raf2 = 0;
    const raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => {
        cardRefs.current[id]?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
    });
    return () => { cancelAnimationFrame(raf1); cancelAnimationFrame(raf2); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locateResolved?.cardId, pgPage]);

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
        <ComparisonBoard
          quotationId={quotation.id}
          bucket={comparisonBucket}
          readonly={comparisonReadonly}
          frozen
        />
      ) : pgIsSearching && pgMatchedTotal === 0 ? (
        <div className="qt-empty-state" style={{ padding: '56px 20px', textAlign: 'center' }}>
          <div style={{ fontSize: 44, lineHeight: 1, opacity: .25 }}>🔍</div>
          <div style={{ marginTop: 14, color: 'rgba(0,0,0,.88)', fontSize: 15 }}>未找到匹配的料号</div>
          <div style={{ marginTop: 6, color: 'rgba(0,0,0,.45)', fontSize: 13 }}>
            「{pgSearchInput}」在本报价单的 {pgTotal} 个料号中无匹配。请换一个料号片段，或清空查询查看全部。
          </div>
          <div style={{ marginTop: 14 }}>
            <Button onClick={paging.clearSearch}>清空查询</Button>
          </div>
        </div>
      ) : viewType === 'excel' ? (
        <div>
          {showPager && renderPagingBar()}
          {/* task-260825（F-3/AC-7）：ReadonlyExcelView 纯客户端计算（useExcelSnapshotRows 无网络副作用），
              直接喂当前页窗口即可，不需要 LinkedExcelView 那套 renderLineItems 兜底切片。 */}
          <ReadonlyExcelView
            lineItems={pagedVisible}
            side={mainTab === 'costing' ? 'COSTING' : 'QUOTE'}
            columns={
              mainTab === 'costing' ? quotation.costingExcelColumns : quotation.quoteExcelColumns
            }
          />
          {showPager && renderPagingBar()}
        </div>
      ) : (
        <div className="qt-products-list">
          {showPager && renderPagingBar()}
          {paging.pagedPositions.map((pos) => {
            const li = visible[pos];
            const isLocateTarget = locateResolved?.cardId != null && locateResolved.cardId === li.id;
            return (
              <div key={li.id || pos} ref={el => { if (li.id) cardRefs.current[li.id] = el; }}>
                <ReadonlyProductCard
                  lineItem={li}
                  index={pos}
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
          {showPager && renderPagingBar()}
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
                  {/* task-0801：不再固定 2 位 toLocaleString，改走 formatNumber（6 位去尾零兜底） */}
                  ¥{formatNumber(quotation.originalAmount ?? '0', { isComputed: true }) ?? '0'}
                </Text>
              </Text>
              <Text>
                折扣率：<Text strong>{quotation.finalDiscountRate}%</Text>
              </Text>
              <Text style={{ fontSize: 16 }}>
                报价总金额：
                <Text strong style={{ fontSize: 18, color: '#c00' }}>
                  ¥{formatNumber(quotation.totalAmount ?? '0', { isComputed: true }) ?? '0'}
                </Text>
              </Text>
            </Space>
          </Col>
        </Row>
      )}

      {/* task-0713（F5/3a）：核价侧单据总价 —— 读 costingTotalAmount（Σ核价成本 subtotal，
          不含 Step3 折扣），与上方报价总金额（含折扣）是两条口径。切换版本后由
          onVersionSwitched 增量更新 quotation.costingTotalAmount 即时反映，不整单重查。
          精度口径（2026-08-01 task-0801 起）：对外总额统一 DISPLAY_SCALE=6 去尾零，
          原固定 2 位口径（cpq-decimal-display-policy 记忆）已作废，见 formatNumber.ts 头注。
          coid 门控：只有核价管理场景（CostingReviewPage 传入 coid）才显示本行；
          报价管理详情（QuotationDetail 不传 coid）没有 costing_order 语境，
          costingTotalAmount 无意义（会显示误导性的 ¥0），故不渲染。 */}
      {!!coid && mainTab === 'costing' && viewType === 'card' && (
        <Row justify="end" style={{ marginTop: 16 }}>
          <Col>
            <Space direction="vertical" align="end">
              <Text style={{ fontSize: 16 }}>
                核价单据总价：
                <Text strong style={{ fontSize: 18, color: '#c00' }}>
                  ¥{formatNumber(quotation.costingTotalAmount ?? '0', { isComputed: true }) ?? '0'}
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
