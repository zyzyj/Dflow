// Local MCP server over stdio.
//
// This is the desktop transport: the MCP client launches this process and talks
// JSON-RPC over its stdin/stdout, so it only works on the machine running DFlow.
// The tools themselves live in mcp-tools.js and are shared with the HTTP
// transport (mcp-http.js), which is what makes them reachable from a phone.

import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { registerTools } from './mcp-tools.js';

const port = Number(process.env.DFLOW_PORT || 4173);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw Error('DFLOW_PORT 无效');
const base = `http://127.0.0.1:${port}`;
const { version: appVersion } = JSON.parse(await fs.readFile(new URL('./package.json', import.meta.url), 'utf8'));

const server = new McpServer({ name: 'dflow-local', version: appVersion });

let sessionId = null;
const clientId = crypto.randomUUID();
let registering = null;
let revoked = false;
let initialized = false;

/**
 * Calls the local DFlow HTTP API.
 *
 * Registration is per-process here: stdio is a single client, so one session id
 * is enough, and the retry below exists because DFlow may have restarted and
 * forgotten the session between two tool calls.
 */
async function api(route, options = {}, retried = false) {
  const headers = new Headers(options.headers || {});
  if (sessionId) headers.set('X-DFlow-MCP-Session', sessionId);
  let response;
  try {
    response = await fetch(base + route, { ...options, headers, signal: AbortSignal.timeout(30000) });
  } catch (error) {
    throw Error(`无法连接本机 DFlow (${base})：请先启动 npm start。${error.message}`);
  }
  if (response.status === 401 && route === '/api/reverse/direct/claim' && !retried) {
    await registerSession(true);
    return api(route, options, true);
  }
  if (!response.ok) {
    const body = await response.text();
    let message = body.slice(0, 400);
    try { message = JSON.parse(body).error || message; } catch { /* HTML error */ }
    throw Error(`DFlow ${response.status}: ${message}`);
  }
  return response;
}

async function json(route, method = 'GET', body) {
  const response = await api(route, {
    method,
    ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  });
  return response.json();
}

const raw = (route, options = {}) => api(route, options);

async function registerSession(force = false) {
  if (revoked) throw Error('MCP 会话已被用户断开，请在客户端重新连接');
  if (registering) return registering;
  if (sessionId && !force) return;
  registering = (async () => {
    const client = server.server.getClientVersion();
    const response = await fetch(base + '/api/mcp/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId,
        name: process.env.DFLOW_AGENT_NAME || client?.name || '未知 Agent',
        version: client?.version || '',
      }),
      signal: AbortSignal.timeout(5000),
    });
    const result = await response.json();
    if (response.status === 410 && result.reason === 'revoked') revoked = true;
    if (!response.ok) throw Error(result.error || `登记失败 HTTP ${response.status}`);
    sessionId = result.id;
  })().finally(() => { registering = null; });
  return registering;
}

registerTools(server, {
  json,
  raw,
  beforeTool: () => registerSession(true),
  // stdio runs on the same machine, so the built-in model may be used directly.
  reverse: async ({ bytes, mimeType, reversePreset, expansionPreset, customInstruction }) => {
    const { reverseImage, buildInstruction, cleanPrompt } = await import('./ai-reverse.js');
    const state = await json('/api/state');
    const account = state.account || {};
    const text = await reverseImage({
      baseUrl: account.aiBaseUrl,
      apiKey: account.aiApiKey,
      model: account.aiModel,
      temperature: account.aiTemperature,
      maxTokens: account.aiMaxTokens,
      imageBytes: bytes,
      imageMimeType: mimeType,
      instruction: buildInstruction({ reversePreset, expansionPreset, customInstruction }),
    });
    return { prompt: cleanPrompt(text) };
  },
});

server.server.oninitialized = () => {
  initialized = true;
  registerSession().catch(error => console.error('DFlow MCP 连接状态登记失败:', error.message));
};

// Register before connect: the SDK emits `initialized` during connect, so
// assigning this callback afterwards loses it.
await server.connect(new StdioServerTransport());

// Sequential heartbeats never overlap registration and never resurrect a revoked
// session. Tools also register, so idle/late initialization cannot hide a client.
async function heartbeat() {
  if (initialized && !revoked) {
    try {
      if (!sessionId) await registerSession();
      else {
        const response = await fetch(base + `/api/mcp/sessions/${encodeURIComponent(sessionId)}/heartbeat`, {
          method: 'POST', signal: AbortSignal.timeout(5000),
        });
        if (response.status === 410) {
          const result = await response.json();
          if (result.reason === 'revoked') revoked = true;
          else { sessionId = null; await registerSession(); }
        } else if (!response.ok) sessionId = null;
      }
    } catch { sessionId = null; }
  }
  setTimeout(heartbeat, 5000).unref();
}
setTimeout(heartbeat, 5000).unref();
