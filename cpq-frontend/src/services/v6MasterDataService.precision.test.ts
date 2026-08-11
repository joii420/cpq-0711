import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from './api';
import { createProcess, updateProcess } from './v6MasterDataService';

vi.mock('./api', () => ({
  default: {
    post: vi.fn(),
    put: vi.fn(),
  },
}));

describe('v6 process decimal request contract', () => {
  beforeEach(() => {
    vi.mocked(api.post).mockReset();
    vi.mocked(api.put).mockReset();
    vi.mocked(api.post).mockResolvedValue({});
    vi.mocked(api.put).mockResolvedValue({});
  });

  it('preserves defaultDefectRate as an exact decimal string', async () => {
    const rate = '0.123456789012';
    await createProcess({ processNo: 'P-1', processName: 'Process', defaultDefectRate: rate });
    await updateProcess('id-1', { processNo: 'P-1', processName: 'Process', defaultDefectRate: rate });

    expect(vi.mocked(api.post).mock.calls[0][1]).toMatchObject({ defaultDefectRate: rate });
    expect(vi.mocked(api.put).mock.calls[0][1]).toMatchObject({ defaultDefectRate: rate });
    expect(typeof (vi.mocked(api.post).mock.calls[0][1] as { defaultDefectRate: unknown }).defaultDefectRate).toBe('string');
  });

  it('rejects a JS number before sending the request', async () => {
    await expect(createProcess({
      processNo: 'P-1',
      processName: 'Process',
      defaultDefectRate: 0.1,
    } as any)).rejects.toThrow(/defaultDefectRate must be a decimal string/);

    expect(api.post).not.toHaveBeenCalled();
  });
});
