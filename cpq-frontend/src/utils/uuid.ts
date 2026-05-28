/**
 * 生成 RFC 4122 v4 UUID（前端 tempId / quotationLineItemId 用）。
 *
 * 为什么不直接用 `crypto.randomUUID()`：
 * 该方法是「安全上下文 (Secure Context)」专属 API，只在 HTTPS / localhost / 127.0.0.1 下挂载。
 * 当通过局域网 IP + 纯 HTTP 访问（vite `host: true` 监听 0.0.0.0，同事用主机 IP 打开）时，
 * 全局 `crypto` 对象仍然存在，但 `crypto.randomUUID` 为 `undefined`——直接调用会抛
 * `TypeError: crypto.randomUUID is not a function`，导致报价单「自动展开」失败。
 * 注意：`typeof crypto !== 'undefined'` 这种守卫无效，因为它判断的是 crypto 对象是否存在，
 * 而故障模式是「crypto 在、crypto.randomUUID 不在」。
 * 见 docs/RECORD.md 2026-05-27 条目 / docs/反模式.md。
 *
 * 取值优先级：
 *   1. crypto.randomUUID()      — 安全上下文，最佳
 *   2. crypto.getRandomValues() — 非安全上下文 (HTTP/IP) 也可用，手拼 v4
 *   3. Math.random()            — 最终兜底（无 crypto 的极端环境）
 */
export function genUUID(): string {
  const c: Crypto | undefined =
    typeof globalThis !== 'undefined' ? (globalThis.crypto as Crypto | undefined) : undefined;

  // 1. 安全上下文优先
  if (c && typeof c.randomUUID === 'function') {
    return c.randomUUID();
  }

  // 2. getRandomValues 兜底（非安全上下文 HTTP/IP 访问仍可用）
  if (c && typeof c.getRandomValues === 'function') {
    const bytes = new Uint8Array(16);
    c.getRandomValues(bytes);
    return formatUuidV4(bytes);
  }

  // 3. 最终兜底：Math.random（无 crypto 的极端环境）
  const bytes = new Uint8Array(16);
  for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256);
  return formatUuidV4(bytes);
}

// 0x00~0xff -> "00".."ff" 查表，避免逐字节 padStart
const HEX: string[] = [];
for (let i = 0; i < 256; i++) HEX.push((i + 0x100).toString(16).slice(1));

function formatUuidV4(bytes: Uint8Array): string {
  bytes[6] = (bytes[6] & 0x0f) | 0x40; // version = 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80; // variant = 10xx
  const b = bytes;
  return (
    HEX[b[0]] + HEX[b[1]] + HEX[b[2]] + HEX[b[3]] + '-' +
    HEX[b[4]] + HEX[b[5]] + '-' +
    HEX[b[6]] + HEX[b[7]] + '-' +
    HEX[b[8]] + HEX[b[9]] + '-' +
    HEX[b[10]] + HEX[b[11]] + HEX[b[12]] + HEX[b[13]] + HEX[b[14]] + HEX[b[15]]
  );
}
