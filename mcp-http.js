// MCP over Streamable HTTP.
//
// Why this exists: the stdio MCP server is launched *by* its client, so it can
// only ever run on the machine that has the repository. On a phone that is
// useless — the phone is where the reverse queue and the cached images live, and
// nothing else can reach it. Exposing the same tools over HTTP lets a remote
// agent (on the LAN, or the machine the user is actually working on) drive the
// queue and read prompts.
//
// The tools are not redefined here; mcp-tools.js holds them so both transports
// stay identical. What differs is only how requests arrive and how callers
// authenticate.

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import crypto from 'node:crypto';
import { registerTools } from './mcp-tools.js';

/**
 * Compares two secrets without leaking their length or content through timing.
 *
 * A plain `===` on a token returns as soon as a byte differs, which is enough to
 * recover a token byte by byte over many requests.
 */
function timingSafeEqual(a, b) {
  const left = Buffer.from(String(a || ''), 'utf8');
  const right = Buffer.from(String(b || ''), 'utf8');
  if (left.length !== right.length) return false;
  return crypto.timingSafeEqual(left, right);
}

/** Reads a token from either the custom header or a Bearer Authorization. */
export function extractToken(req) {
  const direct = req.get?.('X-DFlow-Token') || req.headers?.['x-dflow-token'];
  if (direct) return String(direct).trim();
  const auth = req.get?.('authorization') || req.headers?.authorization || '';
  const match = /^Bearer\s+(.+)$/i.exec(String(auth).trim());
  return match ? match[1].trim() : '';
}

/**
 * Builds the auth middleware.
 *
 * `getConfig` is read per request rather than captured once, so turning the
 * endpoint on, rotating the token or disabling it takes effect immediately
 * instead of after a restart.
 */
export function createAuthMiddleware(getConfig) {
  return (req, res, next) => {
    const config = getConfig();
    if (!config.enabled) {
      return res.status(404).json({
        jsonrpc: '2.0',
        error: { code: -32601, message: '对外接口未启用。请在 DFlow 设置里打开。' },
        id: null,
      });
    }
    if (!config.requireToken) return next();
    const provided = extractToken(req);
    if (!config.token) {
      return res.status(503).json({
        jsonrpc: '2.0',
        error: { code: -32603, message: '接口要求令牌但尚未生成，请在设置里重新生成。' },
        id: null,
      });
    }
    if (!timingSafeEqual(provided, config.token)) {
      res.set('WWW-Authenticate', 'Bearer realm="dflow"');
      return res.status(401).json({
        jsonrpc: '2.0',
        error: { code: -32001, message: '令牌无效。请在请求头带上 X-DFlow-Token 或 Authorization: Bearer <令牌>。' },
        id: null,
      });
    }
    return next();
  };
}

/**
 * Builds the request handler for POST /mcp.
 *
 * Stateless on purpose: a fresh server and transport per request. Tool calls
 * here are all request/response (list the queue, read a prompt, reverse an
 * image), so there is no session state worth keeping — and session maps are
 * exactly the kind of long-lived state that leaks or goes stale on a phone that
 * gets suspended and resumed.
 */
export function createMcpHandler({ json, raw, version, getConfig }) {
  let inFlight = 0;
  // Bounds concurrent vision calls. The built-in model is a metered API and the
  // phone may be running its own batch; letting a remote caller pile on would
  // make both slower and hit rate limits.
  const MAX_CONCURRENT = 4;

  return async function handleMcpRequest(req, res) {
    if (inFlight >= MAX_CONCURRENT) {
      return res.status(429).json({
        jsonrpc: '2.0',
        error: { code: -32000, message: `DFlow 正在处理 ${inFlight} 个请求，请稍后重试` },
        id: req.body?.id ?? null,
      });
    }

    const config = getConfig();
    const server = new McpServer({ name: 'dflow-http', version });
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: undefined,   // stateless
      // A plain JSON reply is easier for simple clients than an SSE stream, and
      // nothing here needs server-initiated messages.
      enableJsonResponse: true,
      // Images come through this endpoint as base64, so the default 4 MiB cap
      // would reject legitimate calls.
      maxRequestBodySize: 64 * 1024 * 1024,
    });

    registerTools(server, {
      json,
      raw,
      // HTTP has no per-process session to register, and the remote caller is
      // not the local agent-management surface, so no beforeTool hook is needed.
      reverse: config.reverse,
    });

    inFlight += 1;
    try {
      await server.connect(transport);
      // req.body is already parsed by the mounted JSON middleware; passing it
      // through avoids the transport re-reading the stream.
      await transport.handleRequest(req, res, req.body);
    } catch (error) {
      if (!res.headersSent) {
        res.status(500).json({
          jsonrpc: '2.0',
          error: { code: -32603, message: error.message || '内部错误' },
          id: req.body?.id ?? null,
        });
      }
    } finally {
      inFlight -= 1;
      // The transport owns the response stream; closing only releases callbacks.
      await transport.close().catch(() => {});
      await server.close().catch(() => {});
    }
  };
}

/**
 * Explains how to connect, so a user can copy a working configuration instead of
 * guessing at headers.
 */
export function connectionInfo({ addresses, port, requireToken, token, enabled }) {
  const hosts = addresses?.length ? addresses : ['127.0.0.1'];
  return {
    enabled,
    requireToken,
    // Echoed only when a token is required; otherwise there is nothing to copy.
    token: requireToken ? token : '',
    endpoints: hosts.map(host => ({
      host,
      url: `http://${host}:${port}/mcp`,
      scope: host === '127.0.0.1' ? 'shutdown' : 'lan',
    })),
    headerName: 'X-DFlow-Token',
    clientConfig: {
      mcpServers: {
        'dflow-http': {
          type: 'http',
          url: `http://${hosts[0]}:${port}/mcp`,
          ...(requireToken ? { headers: { 'X-DFlow-Token': token || '<令牌>' } } : {}),
        },
      },
    },
    note: requireToken
      ? '调用时需带上 X-DFlow-Token 请求头（或 Authorization: Bearer <令牌>）。'
      : '当前未启用令牌：同一网络内的任何设备都能读取你的收藏图片与提示词。',
  };
}
