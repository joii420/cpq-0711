/**
 * 三层模型 UI 状态 → `ConfigureProductRequest` 的映射（task-260902 · api.md §1.1/§1.2）。
 *
 * ⚠️ 与 task-0712 的 `configureRequest.ts` 是**两套**，不是替换：那一套喂的是旧的
 *    「一行 = 一个材质料号」模型（`SelDetailRow`），它和它的单测都**保留不动**。
 *
 * 三条提交纪律（改这个文件前先确认没破坏）：
 *  1. 🚫 **`processNos` 按列表顺序原样发送，不排序、不去重。**
 *     - 不排序：顺序影响 `unit_price.seq_no` 与报价单显示顺序（排序是后端算指纹时的事，AC-19）。
 *     - 🚨 **不去重**：重复次数**仍是指纹维度**（`["Z100","Z101","Z100"].sort()` → `Z100,Z100,Z101`
 *       ≠ `Z100,Z101`）。顺手加个 `distinct()` 会把「焊两次」和「焊一次」静默算成同一个料号，
 *       没有任何报错外观，只表现为两个不同工艺的产品共用了价格（AC-20）。
 *  2. 🚫 **占比与含量按用户输入的原始字符串原样发送**，不补零、不做数值转换
 *     （`'70'` 与 `'70.000000000000'` 在 `numeric(24,12)` 与 `BigDecimal.compareTo` 下同值）。
 *  3. 🚨 **`configNo` 与 `elements` 每个材质恰好给一个**，两个都给或都不给 → 400
 *     `MATERIAL_SOURCE_AMBIGUOUS`（api.md §1.2，作用域随 `materials[]` 下沉到每个 material）。
 */
import type {
  CompositeProcessItem,
  CompositeProcessRequest,
  ConfigurePart,
  PartMaterialRequest,
  PartRequest,
  ProductType,
} from '../../../types/configure';

/** 配件展示名：三条路径各取各的名字来源。 */
export function partDisplayName(part: ConfigurePart): string {
  if (part.partType === 'OUTSOURCED') return part.outsourcedName || part.outsourcedPartNo || '外购件';
  if (part.partMode === 'existing') return part.existingPartName || part.existingHfPartNo || '已有零件';
  return part.name;
}

function buildMaterials(part: ConfigurePart): PartMaterialRequest[] | undefined {
  // 只有「零件 + 新建」这条路径自己配材质；已有零件沿用它自己的，外购件没有材质构成。
  if (part.partType !== 'PART' || part.partMode !== 'new') return undefined;
  if (part.materials.length === 0) return undefined;
  return part.materials.map((m) => (
    m.contentMode === 'custom'
      ? {
          recipeCode: m.recipeCode,
          ratio: m.ratio,
          // 互斥：走自定义就**不带** configNo
          configNo: null,
          elements: m.elements.map((e) => ({ elementCode: e.elementCode, pct: e.pct })),
        }
      : {
          recipeCode: m.recipeCode,
          ratio: m.ratio,
          configNo: m.configNo,
          // 互斥：走标准配置就**不带** elements
          elements: null,
        }
  ));
}

export function buildConfigureParts(
  parts: ConfigurePart[],
  composites: CompositeProcessItem[],
): { productType: ProductType; parts: PartRequest[]; compositeProcesses?: CompositeProcessRequest[] } {
  // 与后端同口径（api.md §3.3）：一个配件 → SIMPLE；两个及以上 → COMPOSITE。
  // 后端仍会兜底裁决，提交后一律按响应里的 productType 消费 lineItems。
  const productType: ProductType = parts.length >= 2 ? 'COMPOSITE' : 'SIMPLE';

  const partRequests: PartRequest[] = parts.map((part) => ({
    name: partDisplayName(part),
    partType: part.partType,
    // 🔄 值域改名：前端只发 'new'（后端把老的 'custom' 也当 'new'）
    partMode: part.partType === 'OUTSOURCED' ? 'new' : (part.partMode === 'existing' ? 'existing' : 'new'),
    spec: part.partType === 'PART' && part.partMode === 'new' ? (part.spec || undefined) : undefined,
    dimension: part.partType === 'PART' && part.partMode === 'new' ? (part.dimension || undefined) : undefined,
    unitWeightGrams: part.partType === 'PART' && part.partMode === 'new'
      ? (part.unitWeightGrams || undefined)
      : undefined,
    materials: buildMaterials(part),
    existingHfPartNo: part.partMode === 'existing' ? part.existingHfPartNo : undefined,
    outsourcedPartNo: part.partType === 'OUTSOURCED' ? part.outsourcedPartNo : undefined,
    // 🚫 原样顺序，不 sort、不 distinct（见文件头纪律 1）
    processNos: part.processes.length > 0 ? part.processes.map((p) => p.processNo) : undefined,
    quantity: '1',
  }));

  const allIndexes = parts.map((_, i) => i);
  const compositeProcesses: CompositeProcessRequest[] = composites.map((c) => ({
    defCode: c.defCode,
    // 「参与配件」与「参数」维持 task-0712 的既有语义（全部配件 / 空参数），本次不改契约
    participatingPartIndexes: allIndexes,
    params: {},
  }));

  return {
    productType,
    parts: partRequests,
    compositeProcesses: productType === 'COMPOSITE' ? compositeProcesses : undefined,
  };
}
