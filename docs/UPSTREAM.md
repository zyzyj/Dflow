# 上游更新与本地分叉

本仓库是 [`oldiron-666/Dflow`](https://github.com/oldiron-666/Dflow) 的 fork，并在此之上做了大量不进入上游的改动：

- `android/` 整个 Android 工程（PRoot 运行时、Compose 界面、CI 打包）
- `ai-reverse.js` —— 内置视觉模型反推（不依赖外部 Agent）
- `mcp-http.js` / `mcp-tools.js` —— MCP over HTTP 与对外访问控制
- `app-log.js` / `version-check.js` —— 日志落盘与构建版本识别
- `server.js` 中的远程访问保护、代理改造、AI 接口

因此**上游更新不是一条自动通路**：合并需要人判断，而且服务端代码一变就必须重新构建 APK，
因为 APK 里的服务端源码是打包时固化进去的。

## 怎么知道上游有没有更新

App 内：设置页会显示当前构建的版本与提交；`/api/version` 返回 `state` 字段：

| state | 含义 |
|---|---|
| `current` | 与上游同一提交 |
| `behind` | 上游有更新的提交 |
| `local-unknown` | 当前构建不含版本信息，无法自动比对 |
| `unknown` | 读不到上游 |

`local-unknown` 表示这个 APK 没有 `BUILD_INFO.json`（由 CI 在打包时写入）。
老版本 APK 会是这个状态 —— 它**不会**误报"有新版本"，这正是修复过的行为。

用命令行检查：

```sh
curl -s http://127.0.0.1:4173/api/version | node -e "process.stdin.once('data',d=>console.log(JSON.parse(d)))"
```

获取完整跟进步骤：

```sh
curl -s http://127.0.0.1:4173/api/version/upgrade-guide
```

## 怎么安全合并上游

在**电脑上**的仓库目录执行，不要在手机上（手机里没有 `.git`，也没有 git 命令）。

```sh
# 1. 添加上游（只需一次）
git remote add upstream https://github.com/oldiron-666/Dflow.git

# 2. 只下载，不动你的分支
git fetch upstream

# 3. 看上游改了什么
git log --oneline HEAD..upstream/main
git diff --stat HEAD..upstream/main

# 4. 开分支试合并 —— 出问题直接丢弃，不影响 main
git switch -c merge-upstream-20260101
git merge upstream/main
```

冲突高发文件，按优先级排：

1. **`server.js`** —— 上游和本项目都在改，冲突几乎必然。本项目在这里加了远程访问中间件、
   日志接口、版本检查改造、AI 反推接口。
2. **`presets.js`** —— 预设名称与迁移逻辑。
3. **`mcp-server.js`** —— 已被改为调用 `mcp-tools.js`；上游若仍内联工具定义，合并时
   应保留本项目的结构（否则 stdio 与 HTTP 两套工具会不一致）。

合并后必须做的检查：

```sh
for f in server.js app-log.js version-check.js mcp-tools.js mcp-http.js ai-reverse.js mcp-server.js; do
  node --check "$f"
done
sh android/tools/ci-server-tests.sh
```

`ci-server-tests.sh` 会跑四套端到端测试（反推链路、内置 AI 反推、批量删除、MCP over HTTP），
全部离线可跑。

## 合并后必须重新构建

APK 内含服务端源码（`app/app/`），所以服务端一改动就要重出包：

```sh
git push origin main          # 推上去触发 CI
gh workflow run build-apk.yml # 或手动 dispatch
```

CI 会重新打包并运行「打包树完整性校验」，检查新模块有没有漏进白名单 ——
这个校验是因为 `ai-reverse.js` 曾被漏掉、导致 APK 里 AI 反推一调用就报模块找不到才加的。

## 反过来的情况：只想拿上游的某个修复

如果只是想要上游的某个小修复，而不是整批合并，用 cherry-pick 更安全：

```sh
git fetch upstream
git log --oneline HEAD..upstream/main     # 找到目标提交
git switch -c pick-fix
git cherry-pick <sha>
```

冲突范围会比整批合并小得多，也更容易验证。
