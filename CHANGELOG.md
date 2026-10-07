# 变更日志

本文件记录 Jellyfish Spring Boot Starter 示例项目所有值得使用者知道的变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Changed

- Maven 坐标由 `zcd` 改为 `io.github.zcd0831`，版本号 0.1.1；内核与 starter 由本地仓库依赖改为从
  Maven Central 解析（`jellyfish.version` 0.1.1），只在本地改了内核时才需要先 `install`。

## [0.1.0] - 2026-10-07

### Added

- 示例应用：`TemplateApplication` 启动类，自身没有一句「接 Jellyfish」的代码，靠 starter 的自动配置。
- `ChatController` 三个接口：建会话、同步提问、SSE 流式提问。
- `TurnCollector`：把回合事件汇聚成完整回答（同步接口用）。
- `SseTurnStream`：把回合事件转成 SSE 帧，队列有界、终态帧不走队列。
- `ApiExceptionHandler`：异常映射到 HTTP 状态码；`api/` 下是请求与响应模型。
- 配置骨架：`application.yml` 只给文件路径与开关（三段 `global-path` 为空串、启用插件名单为空），
  `jellyfish/{models,agents,jellyfish}.json` 与 `brief.md` 仍是内核格式的内容。
- `TemplateSmokeTest`：真 Web 容器 + 真内核 + 真 HTTP 的启动验证（不联网、不调模型、不碰 `~/.jellyfish`）。
- 越权护栏：`userId` 只来自登录态（示例里是查询参数，接进真实业务要换成从登录态取）；
  「不存在」与「不是你的」回同一个 404，不给调用方枚举别人会话 id 的途径。

[Unreleased]: https://github.com/zcd0831/Jellyfish-Spring-Template/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/zcd0831/Jellyfish-Spring-Template/releases/tag/v0.1.0
