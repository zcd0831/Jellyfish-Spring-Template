# AGENTS.md

本文件用于指导 AI 编码代理在**本仓库**（`jellyfish-spring-template`）中工作。修改代码前请先阅读。

> **本文件是常驻上下文，只放「每次动手前都该知道的规则」**：项目描述、模块边界、关键不变量、编码 / 测试 / Git 约定。
> 面向使用者的说明在 `README.md`；内核与 starter 自己的规则在各自仓库的 `AGENTS.md` 里，本文只写本示例特有的部分。

## 项目描述

`jellyfish-spring-boot-starter` 的**示例应用**：Spring Boot 2.7 / Java 8，三个接口演示「建会话 / 同步提问 /
流式提问」，外加三份配置骨架。它跑在 starter 之上，本身不实现任何内核能力。

三条立场，先记住再动手：

1. **示例即文档**：这里每一处写法都是「推荐的写法」，会被照着抄进业务代码。因此宁可多两行注释讲清
   「为什么不能那样写」，也不要为了短而省掉关键细节（例如 listener 为什么只入队）。
2. **只走 `JellyfishUserFacade`**：不注入内核 Bean、不调 `switchTo` / `createDefault`。
   内核没有「用户」这一维，护栏全在门面里，绕过门面 = 绕过全部护栏。
3. **示例代码同样要守 starter 的纪律**：listener 不阻塞、序列化走 `ObjectMapperWrapper`、
   配置只在 yml 里给路径。示例里「凑合的写法」会被当成正确示范。

运行环境 **JDK 1.8**（**不要使用 Java 9+ 的 API 或语法**），Spring Boot 2.7。

## 常用命令

```bash
# 0. 前置：内核与 starter 都得先在本地仓库里（本仓库是独立 reactor，依赖不来自源码树）
(cd ../Jellyfish && mvn -q install -DskipTests)
(cd ../Jellyfish-Spring-Boot-Starter && mvn -q install -DskipTests)

mvn -q compile
mvn -q test                                  # 全量单测（含启动验证）
mvn -q -Dtest=TemplateSmokeTest test         # 单类单测
mvn -q package                               # 打出可执行 jar
mvn spring-boot:run                          # 真的起一个（监听 8080）
```

**第 0 步不能省**：忘了它的表现是「starter 明明改了，这边编译却看不到」，或更糟——用到的是本地仓库里的旧快照。

## 结构

```
src/main/java/zcd/jellyfish/template/
├── TemplateApplication.java        # 启动类：没有一句「接 Jellyfish」的代码，靠 starter 的自动配置
├── ChatController.java             # ★ 三个接口（建会话 / 同步提问 / SSE 流式提问），无身份参数
├── CurrentUser.java                # ★ 「这次请求是谁」的唯一出处（生产替换这个 Bean）
├── CurrentUserConfiguration.java   # 示例实现 @ConditionalOnMissingBean：接入方声明自己的就顶掉它
├── HeaderCurrentUser.java          # 示例实现：读请求头 X-User-Id，缺省 demo（不是认证）
├── TurnCollector.java              # 回合事件 → 完整回答（同步接口用）
├── SseTurnStream.java              # ★ 回合事件 → SSE 帧（本仓库唯一有并发技巧的地方）
├── ApiExceptionHandler.java        # 异常 → HTTP 状态码
└── api/                            # ChatRequest / ChatReply / SessionCreated / SseFrame / ApiError

src/main/resources/
├── application.yml                 # 只给路径与开关；global-path 置空，插件名单为空
└── jellyfish/{models,agents,jellyfish}.json + brief.md   # 仍是内核格式的内容（提示词是 {agentId}.md）

src/test/java/zcd/jellyfish/template/TemplateSmokeTest.java   # 启动验证（真容器 + 真内核 + 真 HTTP）
src/test/java/zcd/jellyfish/template/SseTurnStreamTest.java    # 流写与断开发现（mock 掉 emitter 的单元测试）
src/test/java/zcd/jellyfish/template/SseDisconnectTest.java    # 安静期断开的端到端验证（真容器 + 裸 socket）
```

## 关键不变量

- **`userId` 只来自登录态，永不接受客户端自称**。示例里它来自 `CurrentUser` Bean
  （`HeaderCurrentUser` 读 `X-User-Id` 头，同样是自报），**接口里没有任何身份参数**；
  README 必须继续写明「接进真实业务要换掉这一个 Bean」。**别为了省事把 userId 加回接口参数**——
  那等于把归属校验交给调用方。
- **「不存在」与「不是你的」必须回同一个答复**（404，同为 `SessionAccessDeniedException`）。
  区分开来等于给了调用方一个枚举别人会话 id 的工具。
- **listener 里不做 I/O**：它在内核线程（`react` / `llm-stream` / 工具输出泵）上被调用，会并发。
  SSE 必须「listener 只入队 + 单独一条写线程」。
- **SSE 队列有界、终态帧不走队列**：无界队列会被慢客户端吃光内存；终态帧一旦被丢掉，
  客户端就永远等不到结尾、连接永不 complete。
- **客户端断开 / 同步等待超时要放行内核**：要取消那个回合，否则模型继续烧 token 而没有去处。
- **断开不能只靠「下一次写失败」发现**：模型在长调用或等审批时一帧都不产生，那次写可能几分钟都不来。
  因此除了注册 `onCompletion` / `onError` / `onTimeout`，还要有周期性心跳帧（写才会定期发生）。
  **实测过：安静期里挂断，容器不会主动通知**（`SseDisconnectTest` 把心跳调成 1 小时就会红）。
- **正常收尾与「客户端没了」必须分得开**：`emitter.complete()` 会同步触发完成回调，
  认错了会把一个刚答完的回合取消掉。因此先置 `finished` 再完成，且「放行」只允许发生一次。
- **HTTP 200 ≠ 回合成功**：终态在响应体 / 终态帧的 `kind` 里（`COMPLETED` / `CANCELLED` / `ERROR` /
  `BLOCKED`）；`notice` 是内核的补充说明，不是正文，不要拼进 `reply`。
- **配置分工不变**：yml 只回答「文件在哪」（四份配置文件路径 + 插件扫描目录），内容仍是内核格式的 JSON。
  **不去把 JSON 翻译成 yml**。
- **示例的三段 `global-path` 保持空串**：不然示例会去读开发机上的 `~/.jellyfish/`，
  「示例用的是哪个模型」随机器而变。
- **`models.json` 的密钥用 `${VAR:-默认值}` 而不是裸 `${VAR}`**：裸占位符在变量缺失时会让整份配置加载
  失败、应用起不来；示例要的是「没 key 也能起来」。
- **`plugins.enabled: []` 是「一个都不启用」**，与「不声明」（不额外限定）不是一回事。
- **`TemplateSmokeTest` 不联网、不调模型、不碰 `~/.jellyfish`**：插件扫描目录指向临时目录；
  它的价值在于「起得来 / 装配对 / 路由在 / 护栏生效」，调模型是使用者自己的事（README 有说明）。
- **路由表断言要同时看 `getPatternsCondition()` 与 `getPathPatternsCondition()`**：
  Boot 2.6+ 缺省用 `PathPatternParser`，只读前者会得到「一条路由都没有」的假象。

## 编码规范

- 缩进 4 空格，K&R 风格大括号，文件末尾保留换行。
- 依赖注入一律用构造器注入，不用字段注入。
- 注释用中文，说明「为什么」而非复述代码。
- 类、接口、私有方法、成员变量都要有文档注释；类注释加 `@author zcd`；方法注释用 `@param` 写清每个参数、
  用 `@return` 写清返回值。
- 序列化统一走 `ObjectMapperWrapper`，不要直接 `new ObjectMapper()`。
- 请求 / 响应模型是纯数据类；工具方法 / 常量类用 `final` + 私有构造器。
- 所有代码均需满足 sonar 规范要求。

## 单元测试规范

- JUnit5 + AssertJ，命名 `{被测试方法}_should_{预期结果}_when_{条件}`，结构 Given/When/Then。
- **不联网、不调模型、不访问 `~/.jellyfish`**（插件目录指到 JUnit 临时目录）。
- 启动验证用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`：真 Web 容器、真内核、
  真 HTTP，只打不花钱的路径（建会话、参数校验、越权、路由注册）。
- **涉及容器行为的地方就用真容器 + 裸 socket**（见 `SseDisconnectTest`）：mock 掉容器等于把被测的性质抽走。
  需要用短心跳时走 `SseTurnStream` 的包私有构造器，别去改产品里的常量。
- 用例要能重复跑、互相独立、不依赖执行顺序（同一个上下文里跑，注意别让共享的内核状态互相干扰）。

## Git 约定

- 只 `git add` 本会话实际修改的文件，禁止 `git add -A` / `git add .`。
- 提交前先 `git status` 确认暂存范围。
- 提交信息格式：`{feat,fix,docs,refactor,chore}[(scope)]: 描述`，描述简洁说明改动。
- 不要提交密钥、真实 apiKey 或本地配置文件（`application.yml` 里只允许出现环境变量占位符）。
- 提交代码前必须先得到允许。

## 语言

- 使用中文沟通

## 行为准则

- 开发时必须严格按与用户确认的方案执行，如果开发过程中发现方案有问题，先征求用户意见，禁止私自变更方案。
- **改了使用者可见的行为就同步改文档**：新增 / 修改接口、状态码、响应字段、配置项时，`README.md`
  的对应位置要一起改。
- 注释 / 文档记录的是最终状态，不要记录决策过程。
