# Jellyfish-Spring-Template

[Jellyfish Spring Boot Starter](https://github.com/zcd0831/Jellyfish-Spring-Boot-Starter) 的示例项目：
一个 **Spring Boot 2.7 / Java 8** 应用，用**三个接口**演示怎么把 Jellyfish 内核接进自己的服务——
建会话、同步提问、流式提问。

它同时是一份**可照抄的起点**：配置怎么放、listener 为什么不能直接写 SSE、失败怎么翻成 HTTP 状态码，
都在代码注释里写清楚了「为什么这么写」。

| 仓库 | 是什么 |
| --- | --- |
| [Jellyfish](https://github.com/zcd0831/Jellyfish) | 内核：会话 / 回合 / 模型 / 权限 / 工具，能力全部由插件提供 |
| [Jellyfish-Plugins](https://github.com/zcd0831/Jellyfish-Plugins) | 官方插件（文件工具 / shell / 持久化 / 压缩 / skills / …） |
| [Jellyfish-Spring-Boot-Starter](https://github.com/zcd0831/Jellyfish-Spring-Boot-Starter) | 把内核接进已有的 Spring Boot 应用（一个自动配置 + 一组门面 Bean） |
| **Jellyfish-Spring-Template**（本仓库） | 用 starter 写的一个最小应用：三个接口 + 配置骨架 |

## 它不是什么

- **不是成品聊天服务**。三个接口演示的是「怎么把一个内核回合开在 HTTP 上」，没有登录、没有限流、
  没有历史消息接口、没有前端页面。要做成产品，缺的东西在 starter 与现实世界里都能找到。
- **`userId` 是示例的简化**。接口里带 `userId` 参数是为了让你一眼看到「内核没有用户这一维、
  护栏都在门面里」这件事。接进真实业务时，它必须换成从登录态里取出来的当前用户——
  **永远不要接受客户端自己声称的 userId**，那等于把归属校验交给调用方。
- **一个插件都没启用**。纯对话，模型没有任何工具。装插件见下文「加能力」。

## 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | 8 |
| Spring Boot | 2.7（本仓库用 2.7.18） |
| 内核与 starter | `0.1.1`（坐标 `io.github.zcd0831`，从中央仓库解析） |

**内核与 starter 已发布到中央仓库，这一步只在「本地改了内核」时才需要**——那时若不覆盖，表现是
「明明改了内核，这边编译却看不到」，或更糟——用到的是本地仓库里的旧快照：

```bash
(cd ../Jellyfish && mvn -q install -DskipTests)
(cd ../Jellyfish-Spring-Boot-Starter && mvn -q install -DskipTests)
```

## 跑一遍

```bash
# 1. 给一个 key（配置里读的是环境变量，不写进仓库）
export DEEPSEEK_API_KEY=sk-xxxxxx

# 2. 起服务
mvn spring-boot:run          # 监听 8080，内核随容器一起启动
```

另开一个终端，依次跑这三条：

```bash
# ① 建会话：返回的 sessionId 就是后面要用的那个
curl -s -X POST 'http://localhost:8080/api/sessions?userId=demo'
# {"sessionId":"5f0d…"}

# ② 同步提问：等回合结束，一次性拿到完整回答
SID=上面那个 sessionId
curl -s -X POST "http://localhost:8080/api/sessions/$SID/chat?userId=demo" \
     -H 'Content-Type: application/json' \
     -d '{"message":"用一句话介绍你自己"}'
# {"sessionId":"…","turnId":"…","reply":"…","notice":null,"kind":"COMPLETED","reason":null,"rounds":1}

# ③ 流式提问：逐帧推过程与结果，收到终态帧即流结束
curl -N "http://localhost:8080/api/sessions/$SID/chat/stream?userId=demo&message=写一句诗"
# data:{"kind":"STARTED"}
# data:{"kind":"TEXT","text":"床"}
# data:{"kind":"TEXT","text":"前"}
# …
# data:{"kind":"COMPLETED","text":"…","rounds":1,"dropped":0}
```

**没配 key 会怎样**：服务照常起来（内核「配置缺失只告警」的既有纪律），但提问时模型请求会失败，
同步接口返回 `kind=ERROR` + `reason`，流式接口在 `STARTED` 之后直接来一条
`{"kind":"ERROR","reason":"…"}`。这是设计如此，不是 bug——**看 `kind` 才知道回合到底答完了没有**。

## 三个接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/sessions?userId=&agentId=` | 建会话，返回 `{"sessionId":…}`；`agentId` 可选，不传就用内核默认 agent |
| POST | `/api/sessions/{sid}/chat?userId=` | 同步提问，body `{"message":"…"}`，等回合结束返回 `ChatReply` |
| GET | `/api/sessions/{sid}/chat/stream?userId=&message=` | SSE 流式提问，每帧 `data` 是一段 JSON（字段见 `SseFrame`） |

两处返回值需要留意的语义：

- **`kind` 才是回合的结论**：`COMPLETED`（正常答完）/ `CANCELLED`（被取消）/ `ERROR`（异常终止）/
  `BLOCKED`（插件拦下）。HTTP 200 只表示请求被受理了——「请求成功、回合失败」与「请求根本没受理」
  是两件事，混在一个状态码里会让客户端分不清该重试还是该改参数。
- **`notice` 不是正文**：它是内核对这次收敛的补充说明（「正文被输出上限截断」「模型没返回任何内容」），
  前端要另起一行展示，不要拼进 `reply`。

失败时的状态码（`ApiExceptionHandler`）：

| 情形 | 状态码 | 说明 |
| --- | --- | --- |
| 会话不存在，**或不属于该用户** | 404 | 两种情况答复完全相同：区分开来等于给了调用方枚举别人会话 id 的工具 |
| 该用户并发对话已达上限 | 429 | 门面**立即拒绝**，不排队（排队会把容器线程一起堵住） |
| 同一会话已有在途回合 | 409 | 先 `cancel` 再提问 |
| `message` 为空 | 400 | |
| 内核异常 | 500 | 正文只有一句安全说明，原文进服务端日志 |

## 代码导览

```
src/main/java/zcd/jellyfish/template/
├── TemplateApplication.java     # 启动类：一句「接 Jellyfish」的代码都没有，全靠 starter 的自动配置
├── ChatController.java          # ★ 三个接口。业务侧的写法示范
├── TurnCollector.java           # 把回合事件聚合成完整回答（同步接口用）
├── SseTurnStream.java           # ★ 把回合事件推成 SSE 流（唯一有并发技巧的地方）
├── ApiExceptionHandler.java     # 异常 → HTTP 状态码
└── api/                         # 请求 / 响应 DTO（ChatRequest、ChatReply、SessionCreated、SseFrame、ApiError）
```

三个值得先看懂的点：

**1. 只注入 `JellyfishUserFacade`，不碰内核 Bean。**
内核不认识「用户」：会话没有归属字段、`SessionManager.all()` 返回全进程会话、命令域能操作任意会话、
审批与提问通道也不校验调用方说的会话是不是自己的。这些护栏全在门面里，**绕过门面就是绕过全部护栏**。

**2. listener 里不能写 I/O。**
`chat(...)` 的回调是在内核线程上被调用的（`react` / `llm-stream` / 工具输出泵，后两者会并发），
客户端一慢，写操作就把内核线程按在那里。因此 `SseTurnStream` 只做一件事：把帧塞进队列，
由它自己那条线程去写。队列是**有界**的（满了丢帧并计数，计数随终态帧送达），
而**终态帧不走队列**——它一旦被丢掉，客户端就永远等不到结尾。

**3. 客户端断开要放行内核。**
写失败说明没人在看了，此时调 `onClientGone`（本示例里是取消这个回合），
否则模型继续推理、继续烧 token，而这些结果没有去处。同理，同步接口等超时之后也顺手取消了回合。

## 配置：yml 只给路径，内容仍是内核格式的 JSON

| 配置 | 在哪 | 为什么 |
| --- | --- | --- |
| 四份配置文件**在哪**、插件扫描目录 | `application.yml` | 「文件在哪」是部署事实，随环境而变 |
| providers / models / agent 定义 | `classpath:jellyfish/models.json`、`agents.json` | 内核已有格式，`classpath:` 前缀可用 |
| 插件启用名单、逐插件配置段、`react` 段等 | `classpath:jellyfish/jellyfish.json` | 同上 |

**本示例把三段的 `global-path` 都置成了空串**，只读 classpath 上自带的那份骨架：

```yaml
jellyfish:
  models:
    global-path: ''                              # 空串 = 这一级不配置
    project-path: 'classpath:jellyfish/models.json'
```

于是它不会去读你 `~/.jellyfish/` 下的真实配置——否则「示例用的是哪个模型、哪个 key」会随机器而变。

**想改用本机那份配置**（比如你已经在 `~/.jellyfish/` 里配好了别的厂商）：把那一级的 `global-path` 写回来，
把 `project-path` 改成空串（空串 = 这一级未配置，不是配错）即可。两个层级不是「缺什么补什么」，
而是**同名 `provider` / `agent` / 插件配置段整对象以项目级覆盖全局级**，启用 / 禁用名单也是整体替换——
本示例正是不想让骨架被本机配置顶掉，才把 `global-path` 留空的。

**想换厂商**：改 `models.json` 里的 provider（`type` 决定用哪家协议），密钥一律走 `${ENV_VAR}`
占位符。注意 `${VAR}` 缺失会让**整份配置加载失败、应用起不来**；想容错就写 `${VAR:-默认值}`——
本示例对 `DEEPSEEK_API_KEY` 用的就是后者，所以不设 key 也能启动。

**agent 的提示词不在 `agents.json` 里**，而是同目录的 `{agentId}.md`
（本示例是 `classpath:jellyfish/brief.md`）。这就是 `agentId=brief` 与不传的默认 agent 的差别。

## 加能力：装插件

内核的一切能力都在插件里（读文件、跑命令、持久化会话、长对话自动压缩……），本示例一个都没装。
装法三步：

1. 把插件 jar（或「一插件一子目录」的目录）放进 `jellyfish.plugins.roots` 指向的位置（缺省
   `~/.jellyfish/plugins/`）；
2. 在 `src/main/resources/jellyfish/jellyfish.json` 的 `enabled` 里写上插件 id（现在是空名单 `[]`，
   表示一个都不启用；**不声明与声明为空是两回事**）；
3. 重启进程（新增 / 删除 jar 只在启动期生效）。

插件从哪来、每个插件有哪些配置项，见 [Jellyfish-Plugins](https://github.com/zcd0831/Jellyfish-Plugins)。

两条提醒：**`shell` / `mcp` / 脚本桥接类插件不做用户隔离**，装进多租户服务等于把所有用户放在同一个
权限面里；**插件是唯一的扩展方式**，不要在 Spring 容器里替换内核组件（那些 Bean 刻意不做
`@ConditionalOnMissingBean`，因为它们是同一张对象图上的视图，不是可以换掉的实现）。

## 验证

```bash
mvn package        # 编译 + 跑 TemplateSmokeTest + 打出可执行 jar
mvn test           # 只跑测试
java -jar target/jellyfish-spring-template-0.1.1.jar   # 跑打好的包
```

`TemplateSmokeTest` 是**启动验证**：真 Web 容器 + 真内核 + 真 HTTP，验的是与模型无关、但一改就静默
坏掉的东西——内核起得来没、classpath 上的示例配置真的被读进来没、三个路由都注册没、越权访问是否一律
404。它**刻意不调模型**（那要花钱、要联网、还不稳定），因此「真调模型」这一步得你自己带 key 跑一次。

## License

[MIT](LICENSE)
