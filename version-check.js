// Version tracking against the upstream repository.
//
// DFlow is used as a fork with local changes (an Android build, its own UI, the
// built-in vision reverse). "Is there a newer upstream?" is therefore a question
// to answer and explain, not an action to perform: pulling upstream into a fork
// this far ahead merges cleanly only by luck, and on a phone there is no git and
// no upstream remote at all.
//
// Two bugs in the previous implementation are fixed here:
//  1. The local commit was read with `git rev-parse HEAD`. In the Android build
//     there is no .git directory, so this always returned ''. The comparison
//     then treated "unknown" as "different" and reported an update forever.
//  2. The check read the upstream repository while the update action ran
//     `git pull origin main` on the user's own fork — two different remotes, so
//     pressing update could never fetch what the check had found.

import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

export const UPSTREAM_REPO = 'oldiron-666/Dflow';
export const UPSTREAM_BRANCH = 'main';
const HEADERS = { Accept: 'application/vnd.github+json', 'User-Agent': 'DFlow-Version-Checker/1.0' };

/**
 * Reads the commit this build was made from.
 *
 * order matters: the build stamp is authoritative in a packaged app (there is no
 * repository there), and git is authoritative when running from a checkout.
 */
export function localBuildInfo(appDir, dataDir) {
  const candidates = [
    path.join(appDir, 'BUILD_INFO.json'),
    path.join(dataDir, 'BUILD_INFO.json'),
  ];
  for (const file of candidates) {
    try {
      const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (parsed && typeof parsed === 'object') {
        return {
          commit: String(parsed.commit || ''),
          version: String(parsed.version || ''),
          builtAt: String(parsed.builtAt || ''),
          source: 'build-stamp',
        };
      }
    } catch {
      // Not present or unreadable: fall through to git.
    }
  }

  try {
    const commit = execFileSync('git', ['rev-parse', 'HEAD'], {
      cwd: appDir, encoding: 'utf8', windowsHide: true, timeout: 5000,
    }).trim();
    return { commit, version: '', builtAt: '', source: commit ? 'git' : 'unknown' };
  } catch {
    return { commit: '', version: '', builtAt: '', source: 'unknown' };
  }
}

/** Reads the newest commit on upstream's branch. */
export async function fetchUpstreamHead(timeoutMs = 8000) {
  const response = await fetch(
    `https://api.github.com/repos/${UPSTREAM_REPO}/commits/${UPSTREAM_BRANCH}`,
    { headers: HEADERS, signal: AbortSignal.timeout(timeoutMs) },
  );
  if (!response.ok) throw new Error(`GitHub HTTP ${response.status}`);
  const commit = await response.json();
  return {
    commit: String(commit.sha || ''),
    message: String(commit.commit?.message || '').split('\n')[0],
    date: String(commit.commit?.committer?.date || ''),
  };
}

/**
 * Compares a local build against upstream.
 *
 * The important part is the third state. Saying "up to date" when the local
 * commit is unknown would hide a real update, and saying "update available" would
 * cry wolf on every launch — which is what the old code did. So an unknown local
 * revision is reported as exactly that.
 */
export function compareBuilds(local, upstream) {
  if (!upstream?.commit) return { state: 'unknown', changed: false };
  if (!local?.commit) return { state: 'local-unknown', changed: false };
  const sameRevision = local.commit === upstream.commit;
  return {
    state: sameRevision ? 'current' : 'behind',
    changed: !sameRevision,
  };
}

/**
 * Builds the follow-up instructions shown to the user.
 *
 * Deliberately not executed by the server. The fork contains an Android project
 * and server changes upstream does not have, so a merge can conflict and needs a
 * human at a keyboard with the repository and a way to rebuild.
 */
export function upgradeGuidance(local, upstream) {
  const steps = [
    '以下命令在电脑上的仓库目录执行，不要在手机上执行。',
    '',
    '# 1. 添加上游（只需一次）',
    `git remote add upstream https://github.com/${UPSTREAM_REPO}.git`,
    '',
    '# 2. 取回上游代码（只下载，不改你的分支）',
    'git fetch upstream',
    '',
    '# 3. 看看上游到底改了什么',
    `git log --oneline HEAD..upstream/${UPSTREAM_BRANCH}`,
    `git diff --stat HEAD..upstream/${UPSTREAM_BRANCH}`,
    '',
    '# 4. 先开一个分支试合并，出问题随时丢弃',
    `git switch -c merge-upstream-$(Get-Date -Format yyyyMMdd)`,
    `git merge upstream/${UPSTREAM_BRANCH}`,
  ];

  const risks = [
    'server.js 是最容易冲突的文件：上游和本项目都在改它。',
    '本次合并后 Android 工程不受影响，但服务端源码变了就必须重新构建 APK，',
    '因为 APK 里的服务端代码是打包时固化进去的，不会自己更新。',
  ];

  if (local?.source === 'build-stamp') {
    risks.push('当前运行的 APK 不带 git 信息，只能按构建标记判断版本。');
  }

  return {
    upstreamRepo: UPSTREAM_REPO,
    upstreamBranch: UPSTREAM_BRANCH,
    localCommit: local?.commit || '',
    localSource: local?.source || 'unknown',
    upstreamCommit: upstream?.commit || '',
    upstreamMessage: upstream?.message || '',
    upstreamDate: upstream?.date || '',
    steps,
    risks,
    note: '本项目包含上游没有的 Android 工程与内置 AI 反推，合并冲突需人工处理。'
      + '服务端代码改动后必须重新构建并安装 APK。',
  };
}
