import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';

const port = Number(process.env.FAKE_CRYPTO_PORT || 0);
if (!Number.isInteger(port) || port <= 0) {
  throw new Error('FAKE_CRYPTO_PORT must be an explicitly reserved non-zero port');
}

const expectedToken = process.env.FAKE_CRYPTO_TOKEN || 'T261026-SECRET-MUST-NOT-LEAK';
const instanceId = `T261026-CRYPTO-FAKE-${randomUUID()}`;
const requests = [];
let plan = {
  inspectStatus: 200,
  inspectBody: Buffer.from('{"encrypted":true}'),
  decryptStatus: 200,
  decryptBody: Buffer.from('{}'),
};

function json(res, status, value) {
  const body = Buffer.from(JSON.stringify(value));
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': body.length });
  res.end(body);
}

async function read(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return Buffer.concat(chunks);
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url || '/', `http://127.0.0.1:${port}`);
  if (req.method === 'GET' && url.pathname === '/__identity') {
    return json(res, 200, { instanceId });
  }
  if (req.method === 'GET' && url.pathname === '/__requests') {
    return json(res, 200, { instanceId, requests });
  }
  if (req.method === 'POST' && url.pathname === '/__reset') {
    requests.length = 0;
    return json(res, 200, { instanceId, reset: true });
  }
  if (req.method === 'POST' && url.pathname === '/__plan') {
    const body = JSON.parse((await read(req)).toString('utf8'));
    requests.length = 0;
    plan = {
      inspectStatus: Number(body.inspectStatus ?? 200),
      inspectBody: Buffer.from(body.inspectBody ?? '{"encrypted":true}'),
      decryptStatus: Number(body.decryptStatus ?? 200),
      decryptBody: Buffer.from(body.decryptBodyBase64 || '', 'base64'),
    };
    return json(res, 200, { instanceId, planned: true });
  }

  if (req.method !== 'POST' || !['/v1/inspect', '/v1/decrypt'].includes(url.pathname)) {
    return json(res, 404, { code: 'NOT_FOUND' });
  }

  const body = await read(req);
  const requestId = req.headers['x-request-id'] || '';
  requests.push({
    method: req.method,
    path: url.pathname,
    size: body.length,
    sha256: createHash('sha256').update(body).digest('hex'),
    contentType: req.headers['content-type'] || '',
    encodedFileName: req.headers['x-file-name'] || '',
    requestId,
    authorizationMatched: req.headers.authorization === `Bearer ${expectedToken}`,
  });

  const status = url.pathname === '/v1/inspect' ? plan.inspectStatus : plan.decryptStatus;
  const responseBody = url.pathname === '/v1/inspect' ? plan.inspectBody : plan.decryptBody;
  res.writeHead(status, {
    'content-type': url.pathname === '/v1/inspect' || status >= 400
      ? 'application/json'
      : 'application/octet-stream',
    'content-length': responseBody.length,
    'x-request-id': requestId,
  });
  res.end(responseBody);
});

server.listen(port, '127.0.0.1', () => {
  // Only identity/address are logged. Token and file bodies are intentionally absent.
  process.stdout.write(JSON.stringify({ instanceId, address: `http://127.0.0.1:${port}` }) + '\n');
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
