// Vision-model reverse prompting, entirely on-device.
//
// DFlow's reverse-prompting was designed around an external MCP agent: the app
// queues a card, an agent reads the image and writes the prompt back. That works
// on a desktop where an agent is running, but leaves the phone unable to reverse
// anything on its own. This module closes that gap by calling a vision model
// over an OpenAI-compatible API, so the pipeline runs with no companion machine.
//
// The prompt text is not invented here. It reuses the same reverse/expansion
// presets the agent flow uses (read from the preset library), so a prompt
// produced by the API and one produced by an agent follow the same instructions.

import fs from 'node:fs';
import path from 'node:path';

const MIME_BY_EXT = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
};

/**
 * Normalises a user-supplied base URL.
 *
 * The rule is simple on purpose: **what the user typed is what gets used.**
 * An earlier version appended `/v1` whenever the path did not already end in a
 * version segment, which silently rewrote perfectly valid input — relays are
 * commonly mounted at `/openai/v1`, `/api`, or something else entirely, and a
 * base URL without any path is legitimate for a reverse proxy that maps it
 * itself. The appended segment turned those into 404s that looked like a bad key.
 *
 * Two normalisations are still applied, because neither can change which
 * endpoint is addressed:
 *   - a missing scheme defaults to https (http for loopback/private addresses,
 *     which is what local model servers speak)
 *   - a trailing slash is dropped, and a pasted `/chat/completions` is stripped
 */
export function normalizeBaseUrl(input) {
  let value = String(input || '').trim();
  if (!value) return '';
  if (!/^https?:\/\//i.test(value)) {
    // Loopback and private-LAN endpoints are almost always plain HTTP — local
    // model servers (Ollama, LM Studio, vLLM) do not serve TLS. Defaulting those
    // to https produces a handshake failure that reads like "fetch failed",
    // which is impossible to diagnose from the UI.
    const host = value.split('/')[0].split(':')[0];
    const isLocal = host === 'localhost' || host === '127.0.0.1' || host === '::1' ||
      /^10\./.test(host) || /^192\.168\./.test(host) || /^172\.(1[6-9]|2\d|3[01])\./.test(host);
    value = `${isLocal ? 'http' : 'https'}://${value}`;
  }
  value = value.replace(/\/+$/, '');
  // The user may paste the full endpoint instead of the base; dropping the known
  // suffix is unambiguous because the client appends it again.
  value = value.replace(/\/chat\/completions$/i, '');
  return value;
}

/**
 * Whether a base URL looks like it is missing its API prefix.
 *
 * Reported to the user as a hint rather than applied silently. Guessing wrong
 * here is worse than asking: a wrong guess costs a confusing 404, while the hint
 * costs one glance.
 */
export function baseUrlHint(input) {
  const value = normalizeBaseUrl(input);
  if (!value) return '';
  let path = '';
  try {
    path = new URL(value).pathname.replace(/\/+$/, '');
  } catch {
    return '';
  }
  if (!path || path === '/') {
    return '地址没有路径。若服务端需要 /v1（多数 OpenAI 兼容服务需要），请一并填写，例如 ' +
      `${value}/v1。本应用不会替你补。`;
  }
  if (!/\/v\d+$/.test(path)) {
    // Something like /openai or /api — plausible, but worth confirming.
    return `当前路径为 ${path}。确认服务端确实在这个路径下监听；若需要 /v1，请自行补全。`;
  }
  return '';
}

/**
 * Builds the endpoint for one API call.
 *
 * Joins rather than appends so a base URL that already ends in `/v1` is not
 * turned into `/v1/v1`.
 */
export function endpointFor(baseUrl, suffix) {
  const base = normalizeBaseUrl(baseUrl);
  if (!base) return '';
  const clean = suffix.startsWith('/') ? suffix : `/${suffix}`;
  return base + clean;
}

/**
 * Turns a transport-level failure into something actionable.
 *
 * These errors carry no status code, and the raw text is "fetch failed" — which
 * says nothing about whether the host is wrong, the network is blocked, or a
 * proxy is in the way. The three causes have different fixes, so they are named
 * separately.
 */
function describeNetworkFailure(error, url, proxy, timeoutMs) {
  const codes = [error?.code, error?.cause?.code, error?.name, error?.cause?.name,
                 ...(Array.isArray(error?.cause?.errors) ? error.cause.errors.map(e => e?.code) : [])]
                .filter(Boolean).map(String);
  const has = (...want) => want.some(w => codes.includes(w));
  const via = proxy ? `\n当前代理：${proxy}（模型请求也走这个代理）` : '';
  const hint = baseUrlHint(url.replace(/\/models$/, ''));

  if (has('ETIMEDOUT', 'UND_ERR_CONNECT_TIMEOUT', 'TimeoutError', 'AbortError')) {
    return `连接超时（${timeoutMs / 1000} 秒）\n请求地址：${url}${via}\n`
      + (proxy
        ? '模型服务可能无法经当前代理访问。可在设置里清空代理后重试，或换一个能直达该服务的代理。'
        : '服务端可能无法直连该地址。若该服务需要代理，请在设置里配置。');
  }
  if (has('ENOTFOUND', 'EAI_AGAIN')) {
    return `无法解析域名\n请求地址：${url}${via}\n请确认地址拼写正确。`;
  }
  if (has('ECONNREFUSED')) {
    return `连接被拒绝\n请求地址：${url}${via}\n`
      + (proxy ? '这通常是代理未在监听，或代理不允许该目标。' : '该地址没有服务在监听，请确认端口。');
  }
  if (has('CERT', 'UNABLE_TO_VERIFY', 'ERR_TLS', 'SSL')) {
    return `TLS 证书校验失败\n请求地址：${url}${via}\n`
      + '若是自建服务使用了自签名证书，请改用 http 地址（仅限内网）。';
  }
  return `无法连接\n请求地址：${url}${via}\n`
    + `错误：${error?.message || error}${codes.length ? `（${codes.join('/')}）` : ''}`
    + (hint ? `\n${hint}` : '');
}

/**
 * fetch wrapper that converts transport failures into actionable messages.
 *
 * Used by both the model-list and the reverse call so a connection problem is
 * explained the same way in both places, instead of surfacing as "fetch failed".
 */
async function fetchWithDiagnosis(url, options, { proxy = '', timeoutMs = 20000 } = {}) {
  try {
    return await fetch(url, options);
  } catch (error) {
    // An aborted request (user pressed cancel) is not a network fault.
    if (error?.name === 'AbortError' && !options?.signal?.aborted) {
      throw error;
    }
    throw new Error(describeNetworkFailure(error, url, proxy, timeoutMs));
  }
}

export { describeNetworkFailure };

/** Whether a base URL and model are both set, i.e. the built-in reverse can run. */
export function isConfigured(account) {
  return Boolean(account?.aiBaseUrl && account?.aiModel);
}

/**
 * Lists models from an OpenAI-compatible endpoint.
 *
 * The reply shape varies (OpenAI, vLLM, Jan, assorted relays), so the id is
 * picked out defensively rather than assuming `data[].id`.
 */
export async function listModels({ baseUrl, apiKey, timeoutMs = 20000, proxy = '' }) {
  const base = normalizeBaseUrl(baseUrl);
  if (!base) throw new Error('请先填写 API 地址');

  const url = `${base}/models`;
  let response;
  try {
    response = await fetch(url, {
      headers: {
        ...(apiKey ? { Authorization: `Bearer ${apiKey}` } : {}),
        Accept: 'application/json',
      },
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (error) {
    // A connection-level failure has no HTTP status, so it must be explained
    // here. The proxy is called out explicitly because it is applied globally to
    // every outbound request: a proxy configured for an image board will also
    // carry (or block) model traffic, and nothing else in the UI says so.
    throw new Error(describeNetworkFailure(error, url, proxy, timeoutMs));
  }

  const text = await response.text();
  if (!response.ok) {
    const hint = baseUrlHint(baseUrl);
    throw new Error(
      `拉取模型列表失败（HTTP ${response.status}）：${describeHttpError(response.status, text)}`
      + `\n请求地址：${url}`
      + (hint ? `\n${hint}` : ''),
    );
  }
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    throw new Error('模型列表不是有效的 JSON，请确认地址指向 OpenAI 兼容接口');
  }

  const rows = Array.isArray(payload) ? payload
    : Array.isArray(payload?.data) ? payload.data
    : Array.isArray(payload?.models) ? payload.models
    : [];

  const models = rows
    .map(row => (typeof row === 'string' ? row : row?.id || row?.name || row?.model))
    .filter(id => typeof id === 'string' && id.trim())
    .map(id => id.trim());

  return [...new Set(models)].sort((a, b) => a.localeCompare(b));
}

function describeHttpError(status, body) {
  if (status === 401 || status === 403) return '密钥被拒绝，请检查 API Key';
  if (status === 404) return '地址不对，请确认是否包含 /v1';
  if (status === 429) return '请求过于频繁或额度不足';
  const trimmed = String(body || '').trim().slice(0, 200);
  return trimmed || '服务端未返回说明';
}

function readImageAsDataUrl(file) {
  const ext = path.extname(file).toLowerCase();
  const mime = MIME_BY_EXT[ext];
  if (!mime) throw new Error(`不支持的图片格式：${ext || '(无扩展名)'}`);
  const stat = fs.statSync(file);
  // Guard the request size: base64 inflates by ~33%, and most endpoints cap the
  // body well below this. Failing with a clear reason beats a truncated upload.
  const limit = 24 * 1024 * 1024;
  if (stat.size > limit) {
    throw new Error(`原图过大（${(stat.size / 1048576).toFixed(1)} MB），请改用较小的图或降低画质`);
  }
  const bytes = fs.readFileSync(file);
  return `data:${mime};base64,${bytes.toString('base64')}`;
}

/**
 * Builds the instruction sent to the model.
 *
 * Two stages, mirroring the agent workflow: observe the image faithfully, then
 * organise the result according to the expansion preset. Keeping them separate
 * matters — merging them invites the model to invent details that fit the
 * expansion style instead of describing what is actually there.
 */
export function buildInstruction({ reversePreset, expansionPreset, customInstruction }) {
  const parts = [];
  parts.push(
    '你是一名图像提示词工程师。请严格依据图片可见内容生成提示词，不要虚构图中不存在的身份、品牌、物体或生图参数。',
  );
  if (reversePreset) {
    parts.push(`【反推要求】\n${reversePreset}`);
  }
  if (expansionPreset) {
    parts.push(`【扩写要求】\n${expansionPreset}`);
  }
  if (customInstruction) {
    parts.push(`【本次额外要求】\n${customInstruction}`);
  }
  parts.push(
    '输出要求：只输出最终提示词正文本身，不要解释、不要标题、不要 Markdown 代码块。' +
    '提示词使用英文标签风格，逗号分隔，按重要程度排列。',
  );
  return parts.join('\n\n');
}

/**
 * Sends one image and returns the generated prompt.
 *
 * `onDelta` is called with the accumulated text as it streams, so a long
 * generation shows progress instead of appearing frozen.
 */
export async function reverseImage({
  baseUrl,
  apiKey,
  model,
  imagePath,
  imageBytes,
  imageMimeType,
  instruction,
  maxTokens = 2048,
  temperature = 0.7,
  timeoutMs = 180000,
  proxy = '',
  onDelta,
  signal,
}) {
  if (!model) throw new Error('请先选择模型');
  const base = normalizeBaseUrl(baseUrl);
  if (!base) throw new Error('请先填写 API 地址');

  // Two input shapes: a cached file on disk (the library flow) or raw bytes
  // (an image handed over by an MCP caller). Both end up as a data URL.
  let dataUrl;
  if (imageBytes) {
    if (!Buffer.isBuffer(imageBytes) || imageBytes.length < 24) {
      throw new Error('传入的图片数据无效');
    }
    // Guard the request size: base64 inflates by ~33%, and most endpoints cap the
    // body well below this. Failing with a clear reason beats a truncated upload.
    const limit = 24 * 1024 * 1024;
    if (imageBytes.length > limit) {
      throw new Error(`图片过大（${(imageBytes.length / 1048576).toFixed(1)} MB），请改用较小的图或降低画质`);
    }
    dataUrl = `data:${imageMimeType || 'image/png'};base64,${imageBytes.toString('base64')}`;
  } else {
    if (!imagePath) throw new Error('这张图还没有可用的本地缓存');
    dataUrl = readImageAsDataUrl(imagePath);
  }

  const response = await fetchWithDiagnosis(`${base}/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(apiKey ? { Authorization: `Bearer ${apiKey}` } : {}),
    },
    body: JSON.stringify({
      model,
      stream: true,
      temperature,
      max_tokens: maxTokens,
      messages: [
        {
          role: 'user',
          content: [
            { type: 'text', text: instruction },
            { type: 'image_url', image_url: { url: dataUrl } },
          ],
        },
      ],
    }),
    signal: signal || AbortSignal.timeout(timeoutMs),
  }, { proxy, timeoutMs });

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(
      `模型调用失败（HTTP ${response.status}）：${describeHttpError(response.status, text)}`
      + `\n请求地址：${base}/chat/completions`
      + (proxy ? `\n当前代理：${proxy}` : ''),
    );
  }

  // Some relays ignore `stream: true` and answer with a single JSON body, so the
  // content type decides how to read it rather than trusting the request.
  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    const payload = await response.json();
    const text = payload?.choices?.[0]?.message?.content;
    if (typeof text !== 'string' || !text.trim()) {
      throw new Error('模型没有返回内容，请确认所选模型支持图片输入');
    }
    return text.trim();
  }

  return readStream(response, onDelta);
}

/** Parses an SSE stream, tolerating keep-alive lines and `[DONE]`. */
async function readStream(response, onDelta) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let full = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let newline;
    while ((newline = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (!data || data === '[DONE]') continue;
      try {
        const chunk = JSON.parse(data);
        const delta = chunk?.choices?.[0]?.delta?.content
          // Some providers send the whole message instead of a delta.
          ?? chunk?.choices?.[0]?.message?.content
          ?? '';
        if (delta) {
          full += delta;
          onDelta?.(full);
        }
      } catch {
        // A malformed chunk should not abort an otherwise good generation.
      }
    }
  }

  if (!full.trim()) throw new Error('模型没有返回内容，请确认所选模型支持图片输入');
  return full.trim();
}

/** Strips fences and stray labels models add despite being told not to. */
export function cleanPrompt(text) {
  let value = String(text || '').trim();
  value = value.replace(/^```[a-z]*\s*/i, '').replace(/```\s*$/, '');
  value = value.replace(/^(提示词|prompt)\s*[:：]\s*/i, '');
  return value.trim();
}
