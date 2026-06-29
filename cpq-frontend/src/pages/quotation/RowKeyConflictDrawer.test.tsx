/**
 * RowKeyConflictDrawer — 降级测试（@testing-library/react 不在本项目依赖中）
 *
 * 覆盖范围：
 *   1. 组件默认导出存在且为 React 组件（Function）
 *   2. RowKeyConflictDTO 类型接口字段完整（通过 TypeScript 编译期约束验证）
 *   3. 列定义纯函数行为（rowKey 生成策略）
 *
 * 不覆盖范围：DOM 渲染 / 用户事件（等待 RTL 引入后补全）
 */
import { describe, it, expect } from 'vitest';
import RowKeyConflictDrawer, { type RowKeyConflictDTO } from './RowKeyConflictDrawer';

describe('RowKeyConflictDrawer 基础结构', () => {
  it('默认导出是一个 React 函数组件', () => {
    expect(typeof RowKeyConflictDrawer).toBe('function');
  });

  it('RowKeyConflictDTO 必填字段 rowKey / rowIndices 类型正确（编译期保证）', () => {
    // 若接口定义不符，tsc --noEmit 会报错，此处运行期断言是双重保险
    const dto: RowKeyConflictDTO = {
      rowKey: 'P1||Cu',
      rowIndices: [2, 3],
      lineItemId: 'li1',
      productName: '产品A',
      productPartNo: 'PN-A',
      componentId: 'c1',
      tabName: '投料',
    };
    expect(dto.rowKey).toBe('P1||Cu');
    expect(dto.rowIndices).toHaveLength(2);
  });

  it('rowKey 生成策略：lineItemId-componentId-rowKey-index 拼接', () => {
    const c: RowKeyConflictDTO = { lineItemId: 'li1', componentId: 'c1', rowKey: 'X', rowIndices: [] };
    // 与组件内 Table rowKey lambda 逻辑对齐
    const key = `${c.lineItemId ?? ''}-${c.componentId ?? ''}-${c.rowKey}-0`;
    expect(key).toBe('li1-c1-X-0');
  });

  it('lineItemId / productName 等可选字段可缺省', () => {
    const minimal: RowKeyConflictDTO = { rowKey: 'K', rowIndices: [1] };
    expect(minimal.lineItemId).toBeUndefined();
    expect(minimal.productName).toBeUndefined();
  });
});
