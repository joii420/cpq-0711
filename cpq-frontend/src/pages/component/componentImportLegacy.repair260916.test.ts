/**
 * repair-260916 F-10（D-7 / AC-16⑤）：导入抽屉「旧格式」提示的判定。
 * 旧格式 = bundleVersion 缺失或低于 1.1；1.1、1.2 及更高都不提示。
 */
import { describe, it, expect } from 'vitest';
import { isLegacyBundleVersion } from './ComponentImportDrawer';

describe('isLegacyBundleVersion', () => {
  it.each([
    [undefined, true],
    ['1.0', true],
    ['1.1', false],
    ['1.2', false],
  ])('bundleVersion=%s → 旧格式=%s（任务要求的四种输入）', (v, expected) => {
    expect(isLegacyBundleVersion(v as string | undefined)).toBe(expected);
  });

  it.each([
    [null, true],
    ['', true],
    ['  ', true],
    ['abc', true],   // 无法解析 → 按 1.0（与后端 versionLowerThan 一致）
    ['1', true],     // 无次版本号 → 1.0
    ['0.9', true],
    [' 1.1 ', false],
    ['1.10', false],
    ['2.0', false],
  ])('边界 bundleVersion=%j → 旧格式=%s', (v, expected) => {
    expect(isLegacyBundleVersion(v as string | null)).toBe(expected);
  });
});
