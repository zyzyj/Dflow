// 版本比较的单测。纯函数，不依赖服务或网络。
//
// 这里锁住的是一个真实修过的 bug：本地 commit 未知时（Android 包内没有 .git），
// 旧代码把"未知"当成"不同"，于是每次启动都提示有新版本。
import { compareBuilds, upgradeGuidance, localBuildInfo } from '../../version-check.js';

let failed = 0;
const check = (label, actual, expected) => {
  const pass = JSON.stringify(actual) === JSON.stringify(expected);
  if (!pass) failed += 1;
  console.log(`  ${pass ? '✅' : '❌'} ${label}  got=${JSON.stringify(actual)} want=${JSON.stringify(expected)}`);
};

console.log('=== 版本比较 ===');
check('本地未知 -> 不报有更新', compareBuilds({ commit: '' }, { commit: 'abc' }),
  { state: 'local-unknown', changed: false });
check('commit 相同 -> 最新', compareBuilds({ commit: 'abc' }, { commit: 'abc' }),
  { state: 'current', changed: false });
check('commit 不同 -> 有更新', compareBuilds({ commit: 'abc' }, { commit: 'def' }),
  { state: 'behind', changed: true });
check('上游拿不到 -> 未知', compareBuilds({ commit: 'abc' }, null),
  { state: 'unknown', changed: false });
check('两边都未知 -> 未知', compareBuilds({}, {}),
  { state: 'unknown', changed: false });

console.log('=== 更新指引 ===');
const guide = upgradeGuidance({ commit: 'abc123', source: 'build-stamp' },
  { commit: 'def456', message: 'fix: x', date: '2026-01-01' });
const steps = guide.steps.join('\n');
check('包含 add upstream', steps.includes('git remote add upstream'), true);
check('包含 fetch', steps.includes('git fetch upstream'), true);
check('包含试合并分支', steps.includes('git switch -c'), true);
check('明确不自动执行', steps.includes('不要在手机上执行'), true);
check('提示 APK 需重建', guide.note.includes('重新构建'), true);
check('说明构建标记来源', guide.risks.some(r => r.includes('构建标记')), true);

console.log('=== 无 .git 时回退 ===');
const info = localBuildInfo('/nonexistent-dir-xyz', '/nonexistent-data-xyz');
check('不抛异常', typeof info === 'object', true);
check('来源标记 unknown', info.source, 'unknown');
check('commit 为空', info.commit, '');

console.log();
console.log(failed === 0 ? '✅ 全部通过' : `❌ ${failed} 项失败`);
process.exit(failed === 0 ? 0 : 1);
