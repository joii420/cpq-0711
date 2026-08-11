import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { CardValues } from '../../services/quotationService';
import { parseSnapshotJsonLossless } from '../../utils/losslessJson';
import { buildSnapshotExpansions, type ComponentDataItem, type ComponentField } from './QuotationStep2';
import { buildUniqueRowKeys } from './useCardSnapshots';
import { ComponentCell } from './components/ComponentCell';
import {
  assembleReadonlySnapshotRow,
  buildReadonlySnapshotIndex,
  materializeReadonlySnapshotInputs,
  mergeReadonlySnapshotRow,
} from './readonlySnapshotRows';

const componentId = 'precision-component';
const inputWork = '1.234567890499';
const formulaWork = '2.469135780998';
const rawText = '001.230000000000';

const fields: ComponentField[] = [
  { name: 'row_key', field_type: 'INPUT_TEXT' },
  { name: 'hf_part_no', field_type: 'INPUT_TEXT' },
  { name: 'raw_text', field_type: 'INPUT_TEXT' },
  { name: 'precision_input', field_type: 'INPUT_NUMBER' },
  { name: 'precision_result', field_type: 'FORMULA' },
];

function snapshotJson(): string {
  return `{"tabs":[{"componentId":"${componentId}","tabName":"精度验证","baseRows":[{"driverRow":{"row_key":"RK-1","hf_part_no":"P-1","raw_text":"${rawText}","precision_input":${inputWork}},"basicDataValues":{}}],"editRows":[],"formulaResults":[{"rowKey":"RK-1","values":{"precision_result":${formulaWork}}}]}]}`;
}

function renderCell(
  field: ComponentField,
  row: Record<string, any>,
  formulaCache: Record<string, any>,
  componentFields: ComponentField[] = fields,
  basicDataValues?: Record<string, any>,
): string {
  const activeComponent = {
    componentId,
    componentCode: 'PRECISION',
    componentType: 'NORMAL',
    tabName: '精度验证',
    dataDriverPath: '$precision',
    fields: componentFields,
    formulas: [],
    rows: [{}],
    subtotal: '0',
  } as ComponentDataItem;
  return renderToStaticMarkup(
    <ComponentCell
      field={field}
      row={row}
      rowIndex={0}
      fieldKey={field.name}
      readonly={true}
      context={{
        basicDataValues,
        pathCacheState: {},
        formulaCache,
        partNo: 'P-1',
        activeComponent,
      }}
    />,
  );
}

describe.each(['QUOTE', 'COSTING'] as const)('%s readonly snapshot driver-row assembly', (side) => {
  it('keeps state lossless and maps row/formula values to readonly display', () => {
    const json = snapshotJson();
    const componentData = [{
      componentId,
      componentCode: 'PRECISION',
      componentType: 'NORMAL',
      tabName: '精度验证',
      dataDriverPath: '$precision',
      fields,
      formulas: [],
      rows: [{}],
      subtotal: '0',
    }] as ComponentDataItem[];
    const lineItem = {
      id: 'line-1',
      productPartNo: 'P-1',
      componentData,
      quoteCardValues: side === 'QUOTE' ? json : undefined,
      costingCardValues: side === 'COSTING' ? json : undefined,
    } as any;
    const parsed = parseSnapshotJsonLossless<CardValues>(json);
    const expansions = buildSnapshotExpansions([lineItem], side);
    expect(Object.keys(expansions)).toHaveLength(1);
    const expansion = Object.values(expansions)[0];
    const snapshotIndex = buildReadonlySnapshotIndex(parsed).get(componentId)!;
    const row = mergeReadonlySnapshotRow({}, expansion.rows[0].driverRow);
    const rowKey = buildUniqueRowKeys(fields, ['row_key'], parsed.tabs[0].baseRows, side === 'QUOTE')[0];
    const formulaCache = snapshotIndex.formula.get(rowKey)!;

    expect(row.row_key).toBe('RK-1');
    expect(row.hf_part_no).toBe('P-1');
    expect(row.raw_text).toBe(rawText);
    expect(row.precision_input).toBe(inputWork);
    expect(typeof row.precision_input).toBe('string');
    expect(formulaCache.precision_result).toBe(formulaWork);
    expect(typeof formulaCache.precision_result).toBe('string');

    expect(renderCell(fields[2], row, formulaCache)).toBe(`<span>${rawText}</span>`);
    expect(renderCell(fields[3], row, formulaCache)).toBe('<span>1.23456789</span>');
    expect(renderCell(fields[4], row, formulaCache)).toBe('<span class="qt-formula-cell-value">2.469135781</span>');
  });
});

it('preserves a persisted edit over the driver snapshot without mutating either source', () => {
  const driver = { row_key: 'RK-1', precision_input: inputWork };
  const persisted = { precision_input: '9.876543210499' };
  const merged = mergeReadonlySnapshotRow(persisted, driver);
  expect(merged).toEqual({ row_key: 'RK-1', precision_input: '9.876543210499' });
  expect(driver.precision_input).toBe(inputWork);
  expect(persisted.precision_input).toBe('9.876543210499');
});

it('uses snapshot driverRows when Stage H has no matching expansion key', () => {
  const stageHFields: ComponentField[] = [
    {
      name: '行号',
      field_type: 'INPUT_TEXT',
      default_source: { type: 'BASIC_DATA', path: '$task0810_precision_rows.row_key' },
    },
    {
      name: '精度输入',
      field_type: 'INPUT_NUMBER',
      default_source: { type: 'BASIC_DATA', path: '$task0810_precision_rows.precision_input' },
    },
  ];
  const activeSnap = {
    driverRows: [{
      row_key: '01',
      precision_input: inputWork,
    }],
    basicDataRows: [{
      '{$task0810_precision_rows.row_key}': '01',
      '{$task0810_precision_rows.precision_input}': inputWork,
    }],
  };
  const scaffoldRow = {};
  const { driverRow, rawRow, basicDataValues } = assembleReadonlySnapshotRow({
    persistedRow: scaffoldRow,
    rowIndex: 0,
    expIndex: -1,
    expandedRows: undefined,
    snapshotDriverRows: activeSnap.driverRows,
    snapshotBasicDataRows: activeSnap.basicDataRows,
  });
  const displayRow = materializeReadonlySnapshotInputs(stageHFields, rawRow, basicDataValues);

  expect(driverRow).toBe(activeSnap.driverRows[0]);
  expect(rawRow).not.toHaveProperty('行号');
  expect(rawRow).not.toHaveProperty('精度输入');
  expect(basicDataValues).toBe(activeSnap.basicDataRows[0]);
  expect(displayRow['行号']).toBe('01');
  expect(displayRow['精度输入']).toBe(inputWork);
  expect(typeof displayRow['精度输入']).toBe('string');
  expect(renderCell(stageHFields[0], displayRow, {}, stageHFields, basicDataValues)).toBe('<span>01</span>');
  expect(renderCell(stageHFields[1], displayRow, {}, stageHFields, basicDataValues)).toBe('<span>1.23456789</span>');

  const persisted = { '精度输入': '' };
  const persistedAssembly = assembleReadonlySnapshotRow({
    persistedRow: persisted,
    rowIndex: 0,
    expIndex: -1,
    snapshotDriverRows: activeSnap.driverRows,
    snapshotBasicDataRows: activeSnap.basicDataRows,
  });
  const persistedDisplayRow = materializeReadonlySnapshotInputs(
    stageHFields,
    persistedAssembly.rawRow,
    persistedAssembly.basicDataValues,
  );
  expect(persistedDisplayRow['精度输入']).toBe('');
});
