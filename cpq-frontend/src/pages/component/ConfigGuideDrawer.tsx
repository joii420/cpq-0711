/**
 * ConfigGuideDrawer
 *
 * 组件管理页内嵌的"配置指引"抽屉。点 toolbar 上"配置帮助"按钮打开,
 * 给系统管理员/销售经理在配组件时提供操作步骤、字段类型说明、
 * 公式语法、典型示例和"基础数据 → 组件值"映射机制。
 */
import React from 'react';
import { Drawer, Typography, Alert, Tag, Divider, Collapse } from 'antd';

const { Title, Paragraph, Text } = Typography;
const { Panel } = Collapse;

interface Props {
  open: boolean;
  onClose: () => void;
}

const codeStyle: React.CSSProperties = {
  background: '#f6f8fa',
  border: '1px solid #e1e4e8',
  borderRadius: 4,
  padding: '10px 12px',
  fontFamily: 'Consolas, Monaco, monospace',
  fontSize: 12,
  whiteSpace: 'pre',
  overflowX: 'auto',
  margin: '6px 0 12px',
};

const ConfigGuideDrawer: React.FC<Props> = ({ open, onClose }) => {
  return (
    <Drawer
      title="组件配置帮助"
      placement="right"
      width={720}
      open={open}
      onClose={onClose}
    >
      <Alert
        type="info"
        showIcon
        message="组件 = 报价单产品卡片中的一个标签页"
        description="组件定义一个表格的列结构(fields)和计算公式(formulas)。配置后被模板引用,在报价单里渲染成可填的表格。"
        style={{ marginBottom: 16 }}
      />

      <Title level={5}>1. 配置流程总览</Title>
      <Paragraph>
        <Text strong>新建组件 → 选类型 → 加字段 → 写公式 → 保存激活</Text>。组件状态变 ACTIVE 后,
        可在「模板配置」中拖入使用。
      </Paragraph>

      <Collapse defaultActiveKey={['type', 'field']} bordered={false} style={{ marginBottom: 16 }}>
        <Panel header={<b>1.1 组件类型(componentType)</b>} key="type">
          <Paragraph>
            <Tag color="blue">NORMAL</Tag>普通页签组件 — 投料/费用/工艺等。报价单里渲染成表格,
            可有多行,支持每行独立公式。
          </Paragraph>
          <Paragraph>
            <Tag color="purple">SUBTOTAL</Tag>小计汇总组件(可选,不配走默认求和) — 每个模板最多 1 个,放在产品卡片底部,
            汇总各 NORMAL 组件的小计。无字段表格,只配公式。
          </Paragraph>
        </Panel>

        <Panel header={<b>1.2 字段类型(field_type) — 5 种,各自取值方式不同</b>} key="field">
          <Paragraph>
            <Tag color="cyan">FIXED_VALUE</Tag>
            <Text> 默认值/常量。新增行时预填 </Text><Text code>content</Text>
            <Text>,用户编辑时<Text strong>可逐行修改</Text>。语义是"预设默认值",不是"只读"。</Text>
          </Paragraph>
          <Paragraph>
            <Tag color="green">INPUT / INPUT_NUMBER / INPUT_TEXT</Tag>
            <Text> 报价员手填。INPUT_NUMBER 仅接受数字(可参与公式)。</Text>
          </Paragraph>
          <Paragraph>
            <Tag color="geekblue">BASIC_DATA</Tag>
            <Text> 基础数据(V5)— 直接绑 BNF 路径引用 Excel 导入的物理表(mat_part / mat_bom / mat_fee 等)。配置时点</Text>
            <Text code>配置物理表路径</Text>
            <Text>按钮,在抽屉内输入路径并校验语法。报价单运行时按当前料号 + 客户上下文自动求值,值可被公式引用。</Text>
          </Paragraph>
          <Paragraph>
            <Tag color="orange">DATA_SOURCE</Tag>
            <Text> 调用数据源(SQL/HTTP API)查询返回值。</Text>
            <Text strong>失焦自动触发</Text>
            <Text>(300ms 防抖),提交报价单时把当前值快照固化。</Text>
          </Paragraph>
          <Paragraph>
            <Tag color="magenta">FORMULA</Tag>
            <Text> 公式实时计算,</Text><Text strong>不存值</Text>
            <Text>。展示时按 expression 实时算。每个 FORMULA 字段必须在 formulas 数组里有同名条目。</Text>
          </Paragraph>
        </Panel>

        <Panel header={<b>1.3 字段属性</b>} key="attrs">
          <Paragraph>
            <Text code>is_amount</Text> — 是否金额字段(影响报价表合计列汇总)<br/>
            <Text code>is_subtotal</Text> — 是否参与产品小计(SUBTOTAL 组件及配了小计列的普通组件均可用)<br/>
            <Text code>sort_order</Text> — 显示顺序(从 1 开始)
          </Paragraph>
        </Panel>
      </Collapse>

      <Divider />

      <Title level={5}>2. DATA_SOURCE 字段两步配置</Title>
      <Paragraph>
        点字段右侧 <Text code>配置数据源</Text> 按钮,弹出绑定向导:
      </Paragraph>
      <Paragraph>
        <Text strong>第一步</Text>:从字典选数据源(预置在「数据源管理」中)<br/>
        <Text strong>第二步</Text>:把数据源参数(如 <Text code>part_no</Text>)
        绑到本组件其他<Text strong>非 FORMULA</Text>字段(下拉选 FIXED_VALUE / INPUT / 其他 DATA_SOURCE)
      </Paragraph>
      <Alert
        type="warning"
        showIcon
        message="不能绑到 FORMULA 字段,避免循环依赖"
        style={{ marginBottom: 12 }}
      />

      <Title level={5}>3. FORMULA 公式语法</Title>
      <Paragraph>公式编辑器从底部"可用字段/函数"区域拖拽 token 组成 expression 数组:</Paragraph>
      <Paragraph>
        <Tag>field</Tag>本组件其他字段(用 <Text code>[字段名]</Text> 占位):
        <div style={codeStyle}>{`[使用数量] × [单价]`}</div>
      </Paragraph>
      <Paragraph>
        <Tag>operator</Tag>+ - × ÷ ( )
      </Paragraph>
      <Paragraph>
        <Tag>number</Tag>常量数字(如 1.05、100)
      </Paragraph>
      <Paragraph>
        <Tag>function</Tag>22 个内置函数:
        <Text code>ROUND / CEIL / FLOOR / SUM / AVG / MAX / MIN / IF / IFERROR / LOOKUP / EXISTS / EXCHANGE / TAX_INCLUDED / TAX_EXCLUDED / IN / CONTAINS</Text> 等
        <div style={codeStyle}>{`ROUND([使用数量] × [单价] × 1.05, 2)
IF([产出料号类型] = "1.银点类", [元素含量] × 1, 0)`}</div>
      </Paragraph>

      <Divider />

      <Title level={5}>4. 关键:基础数据 → 组件值的映射</Title>
      <Alert
        type="success"
        showIcon
        message="v5 取消「衍生字段」,推荐用 BASIC_DATA 字段类型直接绑 BNF 路径"
        style={{ marginBottom: 12 }}
      />

      <Paragraph>
        <Text strong>推荐:BASIC_DATA 字段(原生 UI 支持)</Text>:
      </Paragraph>
      <Paragraph>
        在字段配置里把字段类型设为「基础数据」,内容/配置列点
        <Text code>配置物理表路径</Text>按钮,弹出抽屉:
      </Paragraph>
      <Paragraph>
        ① 文本框输入路径 → ② 失焦自动校验语法 → ③ 点"插入"完成
        → ④ 字段卡片显示 <Text code>{'{path}'}</Text>,点击可重新修改
      </Paragraph>
      <div style={codeStyle}>{`BASIC_DATA 字段示例:

[单重]   field_type=BASIC_DATA
         basic_data_path = mat_part.unit_weight
         → 报价单运行时自动取该料号的 unit_weight

[Ag含量] field_type=BASIC_DATA
         basic_data_path = mat_bom[bom_type='ELEMENT', element_name='Ag'].composition_pct
         → 取当前料号 Ag 元素的含量(%)

[组装报废率] field_type=BASIC_DATA
         basic_data_path = mat_fee[fee_type='ASSEMBLY_PROCESS'].reject_rate
         → 取当前 (客户, 料号) 的组装报废率`}</div>

      <Paragraph>
        <Text strong>BNF 路径语法</Text>:
      </Paragraph>
      <div style={codeStyle}>{`表名.字段                              — 简单引用
表名[字段='值'].字段                    — 等值谓词
表名[字段 IN ('v1','v2')].字段          — IN 谓词
表名[字段 LIKE '%xx%'].字段             — LIKE 谓词
表名[a='x' AND b='y'].字段              — AND 多条件
A[k='v'].B[k='v'].C.field               — 嵌套(最多 3 层)
元素BOM[元素='Ag'].组成含量             — 中文 sheet 名也支持`}</div>

      <Paragraph>
        <Text strong>替代:DATA_SOURCE 数据源</Text>(适合复杂自定义查询,如调用第三方 API):
      </Paragraph>
      <div style={codeStyle}>{`数据源「mat_part_unit_weight」
  SQL: SELECT unit_weight FROM mat_part WHERE part_no = :part_no
  参数: part_no

组件 DATA_SOURCE 字段
  绑数据源: mat_part_unit_weight
  参数绑定: part_no ← 本组件的「料号」字段`}</div>

      <Paragraph>
        <Text strong>BASIC_DATA vs DATA_SOURCE 选择</Text>:V5 物理表(单重/BOM/费用等导入数据)
        <Text strong>优先用 BASIC_DATA</Text>(无需预建 SQL,语法直观);
        非物理表数据(第三方 API、自定义 SQL 聚合)用 DATA_SOURCE。
      </Paragraph>

      <Title level={5}>5. 上下文自动注入(无需写)</Title>
      <Paragraph>
        报价单运行时会自动给 BNF 路径注入隐含谓词,公式里不用写:
      </Paragraph>
      <Paragraph>
        <Text code>customer_id</Text> ← 报价单关联的客户(查 mat_process / mat_fee / plating_fee)<br/>
        <Text code>hf_part_no</Text> ← 当前产品行的料号(LineItem.product_part_no)
      </Paragraph>
      <div style={codeStyle}>{`你写:
  {mat_part.unit_weight}

系统执行:
  SELECT unit_weight FROM mat_part WHERE part_no = :partNo
  // :partNo = 当前行料号,自动注入`}</div>

      <Divider />

      <Title level={5}>6. 端到端示例:银点料号"投料金额"组件</Title>
      <div style={codeStyle}>{`配置阶段
─────────────────────────────────────────
组件「投料金额」(NORMAL)
  fields:
    [料号]      INPUT(由报价单自动填)
    [元素]      FIXED_VALUE,默认 "Ag"
    [单重]      BASIC_DATA → mat_part.unit_weight
    [元素含量]  BASIC_DATA → mat_bom[bom_type='ELEMENT',element_name='Ag'].composition_pct
    [使用数量]  INPUT_NUMBER
    [小计]      FORMULA: [单重] × [元素含量] / 100 × [使用数量]
                          (is_amount=true)

  formulas:
    {name:"小计", expression:[
      {type:'field', value:'单重'},
      {type:'operator', value:'×'},
      {type:'field', value:'元素含量'},
      {type:'operator', value:'/'},
      {type:'number', value:'100'},
      {type:'operator', value:'×'},
      {type:'field', value:'使用数量'},
    ]}

报价阶段
─────────────────────────────────────────
1. 销售导入基础数据 → mat_part 写入(料号 3120012574 单重 0.5)
2. 创建报价单 → 自动匹配模板
3. 添加产品 3120012574 → 投料组件实例化:
     [料号] = "3120012574"  (自动)
     [元素] = "Ag"           (默认)
     [单重] = 0.5            (BASIC_DATA → mat_part.unit_weight 自动求值)
     [元素含量] = 0.75       (BASIC_DATA → mat_bom 自动求值)
     [使用数量] 销售填 → 100
     [小计] 实时算 → 0.375    (0.5 × 0.75 / 100 × 100)
4. 提交审批 → row_data 快照固化所有 BASIC_DATA / DATA_SOURCE 当前值`}</div>

      <Divider />

      <Title level={5}>7. 常见坑</Title>
      <Paragraph>
        ⚠️ <Text strong>FORMULA 字段必须在 formulas 数组里有同名条目</Text> — 字段名必须 100% 一致。<br/>
        ⚠️ <Text strong>DATA_SOURCE 不能绑 FORMULA 字段</Text>(循环引用,前端会过滤)。<br/>
        ⚠️ <Text strong>BNF 路径里中文 sheet 名要用真实 sheet 中文名</Text>(如「元素BOM」),
        英文物理表名是另一种写法(<Text code>mat_bom</Text>),两者等价但不能混用。<br/>
        SUBTOTAL(小计汇总)组件可选:每个模板最多 1 个;不配则产品小计默认 = 各页签总计之和;若配置则其 formulas 决定产品小计。<br/>
        ⚠️ <Text strong>修改字段名时</Text>系统会自动同步 formulas 中的引用 token,但 BNF 路径里硬写的字段名不会同步,需手动改。
      </Paragraph>
    </Drawer>
  );
};

export default ConfigGuideDrawer;
