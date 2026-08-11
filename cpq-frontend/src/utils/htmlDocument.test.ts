import { afterEach, describe, expect, it, vi } from 'vitest';
import { openHtmlDocument } from './htmlDocument';

describe('openHtmlDocument', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('opens server HTML through a Blob URL and prints only after load', () => {
    const print = vi.fn();
    const opened = { onload: null as null | (() => void), opener: {}, print } as unknown as Window;
    const open = vi.fn(() => opened);
    const createObjectURL = vi.fn(() => 'blob:quotation-html');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('window', { open });
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });

    expect(openHtmlDocument('<html>1.234567891</html>', true)).toBe(opened);
    expect(open).toHaveBeenCalledWith('blob:quotation-html', '_blank');
    expect(opened.opener).toBeNull();
    expect(print).not.toHaveBeenCalled();

    opened.onload?.(new Event('load'));
    expect(print).toHaveBeenCalledOnce();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:quotation-html');
  });
});
