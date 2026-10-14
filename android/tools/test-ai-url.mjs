import { normalizeBaseUrl, baseUrlHint, listModels } from '../../ai-reverse.js';

console.log('=== normalizeBaseUrl：不再自动加 /v1 ===');
const cases = [
  ['api.openai.com', 'https://api.openai.com'],
  ['https://api.openai.com/v1', 'https://api.openai.com/v1'],
  ['https://relay.example.com/openai/v1', 'https://relay.example.com/openai/v1'],
  ['https://relay.example.com/api', 'https://relay.example.com/api'],
  ['127.0.0.1:11434', 'http://127.0.0.1:11434'],
  ['127.0.0.1:11434/v1', 'http://127.0.0.1:11434/v1'],
  ['https://api.deepseek.com/v1/chat/completions', 'https://api.deepseek.com/v1'],
  ['192.168.1.9:8000/v1/', 'http://192.168.1.9:8000/v1'],
];
let bad = 0;
for (const [input, want] of cases) {
  const got = normalizeBaseUrl(input);
  const pass = got === want;
  if (!pass) bad++;
  console.log(`  ${pass ? '✅' : '❌'} ${input.padEnd(46)} -> ${got}`);
}

console.log();
console.log('=== baseUrlHint：只在可疑时提示 ===');
const hintCases = [
  ['api.openai.com', true],
  ['https://api.openai.com/v1', false],
  ['https://relay.example.com/api', true],
  ['127.0.0.1:11434/v1', false],
];
for (const [input, shouldHint] of hintCases) {
  const hint = baseUrlHint(input);
  const pass = Boolean(hint) === shouldHint;
  if (!pass) bad++;
  console.log(`  ${pass ? '✅' : '❌'} ${input.padEnd(36)} -> ${hint ? hint.slice(0, 48) + '…' : '(无)'}`);
}

console.log();
console.log('=== 网络失败的诊断信息 ===');
try {
  await listModels({ baseUrl: 'http://127.0.0.1:59999/v1', timeoutMs: 3000 });
  console.log('  ❌ 竟然成功了？');
  bad++;
} catch (error) {
  const msg = String(error.message);
  const hasUrl = msg.includes('127.0.0.1:59999');
  const actionable = msg.includes('代理') || msg.includes('监听') || msg.includes('超时');
  console.log(`  ${hasUrl ? '✅' : '❌'} 含实际请求地址`);
  console.log(`  ${actionable ? '✅' : '❌'} 含可操作提示`);
  console.log('  实际信息：');
  for (const line of msg.split('\n')) console.log('    ' + line);
  if (!hasUrl || !actionable) bad++;
}

console.log();
console.log('=== 超时的诊断信息 ===');
try {
  // 一个会丢弃 SYN 的地址，触发超时而非拒绝
  await listModels({ baseUrl: 'http://10.255.255.1/v1', timeoutMs: 2000 });
  console.log('  ❌ 竟然成功了？');
  bad++;
} catch (error) {
  const msg = String(error.message);
  const timeoutish = msg.includes('超时') || msg.includes('无法连接');
  console.log(`  ${timeoutish ? '✅' : '❌'} 说明是超时类问题`);
  for (const line of msg.split('\n')) console.log('    ' + line);
  if (!timeoutish) bad++;
}

console.log();
console.log(bad === 0 ? '✅ 全部通过' : `❌ ${bad} 项失败`);
process.exit(bad === 0 ? 0 : 1);
