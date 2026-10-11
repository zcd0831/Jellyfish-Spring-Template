# 变更日志

本文件记录 Jellyfish Spring Boot Starter 示例项目所有值得使用者知道的变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [0.1.2] - 2026-10-11

### Changed

- 版本号对齐到 `0.1.2`：本示例依赖的内核与 starter 同步到 `0.1.2`。本版示例没有行为变更。

## [0.1.1] - 2026-10-11

### Changed

- **接口不再收身份参数**（破坏性）：`POST /api/sessions`、`POST /api/sessions/{id}/chat`、
  `GET /api/sessions/{id}/chat/stream` 去掉了 `userId`（以及建会话的 `agentId`）。身份改由
  `CurrentUser` Bean 给出（示例实现 `HeaderCurrentUser` 读请求头 `X-User-Id`，缺省 `demo`），
  替换它只需声明一个自己的 `CurrentUser`——示例那份带 `@ConditionalOnMissingBean`，会自动让位。
  理由：身份做成请求参数等于把归属校验交给调用方；`agentId` 自选则等于用请求里的一句话绕开
  该用户原有的权限收窄。
- Maven 坐标由 `zcd` 改为 `io.github.zcd0831`，版本号 0.1.1：本示例依赖的内核与 starter 随之改为
  `jellyfish.version` 0.1.1。两者上传到 Maven Central 之后可直接解析；在此之前（以及本地改了内核时）
  先按 `AGENTS.md` 里那两条 `mvn install` 把它们装到本地仓库。
- **HTTP 状态码各归各位**：`CommandNotAllowedException` 此前没有映射、落兜底 `500`，现在回 `403`
  （与内核的 `COMMAND_NOT_ALLOWED` 对齐）；同步接口的收尾状态细分（`NO_SESSION` → `404`、
  `BLANK_INPUT` → `400`、`HANDLED_INPUT` → `200`），流式接口对 `HANDLED_INPUT` 回 `409` 并指向
  同步接口；同步接口超过 5 分钟仍未收敛回 `504`（超时回调先复查一次终态，已收敛的照常交付）。
  依赖旧状态码的调用方要复核。
- **同步接口不再占着容器线程等 5 分钟**：`POST /api/sessions/{id}/chat` 改为 `DeferredResult`，
  容器线程提交回合后立即释放，结果由终态事件填入（接口形状不变）；超时前先查一次终态再决定是否取消。
- **SSE 流每 15 秒发一个 `:keep-alive` 注释帧，安静期里客户端断开也能被发现**：此前只靠「下一次写」
  发现断连，而实测容器在安静期不会通知回调。注释帧被 `EventSource` 忽略，前端无需改动。

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

[Unreleased]: https://github.com/zcd0831/Jellyfish-Spring-Template/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/zcd0831/Jellyfish-Spring-Template/releases/tag/v0.1.2
[0.1.1]: https://github.com/zcd0831/Jellyfish-Spring-Template/releases/tag/v0.1.1
[0.1.0]: https://github.com/zcd0831/Jellyfish-Spring-Template/releases/tag/v0.1.0
