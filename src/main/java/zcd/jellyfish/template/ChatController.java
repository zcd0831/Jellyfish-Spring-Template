package zcd.jellyfish.template;

import zcd.jellyfish.core.conversation.Submission;
import zcd.jellyfish.spring.boot.user.JellyfishUserFacade;
import zcd.jellyfish.template.api.ChatReply;
import zcd.jellyfish.template.api.ChatRequest;
import zcd.jellyfish.template.api.SessionCreated;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.TimeUnit;

/**
 * 三个示例接口：建会话、同步提问、流式提问。
 * <p>
 * <b>全部走 {@link JellyfishUserFacade}，没有一处直接碰内核 Bean</b>。这不是风格问题：内核没有「用户」
 * 这一维（会话无归属字段、命令域能操作任意会话、审批与提问通道不校验调用方说的会话是不是自己的），
 * 那些护栏全都在门面里。绕过门面 = 绕过全部护栏。
 * <p>
 * <b>每个方法都以 {@code userId} 开头</b>（这里是它的 HTTP 形态）：示例没有登录体系，缺省成同一个
 * {@code demo} 用户；接进真实业务时，换成从会话 / 令牌里取当前用户，并且<b>永远不接受客户端自己声称的
 * userId</b>——那等于把归属校验交给调用方。
 * <p>
 * 接口只有三个是刻意的：starter 不提供任何 HTTP 接口，这里演示的是「怎么把一个内核回合开在 HTTP 上」，
 * 而不是一个成品聊天服务。会话列表、历史快照、审批、提问这些入口在
 * {@code JellyfishUserFacade} 上都有对应方法，照这几个接口的样子加即可。
 *
 * @author zcd
 */
@RestController
@RequestMapping("/api/sessions")
public class ChatController {

    /** 缺省用户标识，见类注释。 */
    private static final String DEFAULT_USER = "demo";

    /** 同步接口最多等多久。 */
    private static final long SYNC_TURN_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5);

    /** 门面：业务侧与内核之间的唯一入口。 */
    private final JellyfishUserFacade jellyfish;

    /**
     * 构造控制器。
     *
     * @param jellyfish 用户门面，由 starter 装配，不可为 {@code null}
     */
    public ChatController(JellyfishUserFacade jellyfish) {
        this.jellyfish = jellyfish;
    }

    /**
     * 建一个会话。
     * <p>
     * 会话建好后就绑在这个 {@code userId} 上，之后每次访问都会先校验归属。
     * <p>
     * <b>示例里 {@code userId} 与 {@code agentId} 都不做授权</b>：前者是客户端自报的（真实业务必须
     * 从登录态取，见类注释），后者也只是原样交给内核——门面只校验「这个 agent 存在」，不问「这个用户
     * 能不能用它」。接进真实业务时，后者同样要自己把关：用一个权限更宽的 agent 建会话，
     * 等于绕过本用户原有的权限收窄。
     *
     * @param userId  归属者标识，缺省 {@code demo}（示例简化，真实业务从登录态取）
     * @param agentId 用哪个 agent，缺省 {@code null} 表示跟随内核的默认 agent；示例不校验用户能否用它
     * @return 新会话标识
     */
    @PostMapping
    public SessionCreated create(@RequestParam(defaultValue = DEFAULT_USER) String userId,
                                 @RequestParam(required = false) String agentId) {
        SessionCreated created = new SessionCreated();
        created.setSessionId(jellyfish.createSession(userId, agentId, null, null));
        return created;
    }

    /**
     * 提问并等回合结束，一次性返回完整回答。
     * <p>
     * <b>它是阻塞的</b>：一个回合可能跑几十秒甚至更久（纯对话很快，装上工具就不一定了）。
     * 拿不准的时候用流式那个接口——它能让你在模型开口的第一秒就把字显示出来。
     * <p>
     * <b>HTTP 200 不等于「答出来了」</b>：回合本身的终态在响应体的 {@code kind} 里，只有
     * {@code COMPLETED} 是正常答完；{@code ERROR}（例如模型侧报错）与 {@code BLOCKED}（插件拦下）
     * 时 {@code reply} 可能为空，看 {@code reason}。之所以不用 HTTP 状态码表达它：
     * 「请求成功、回合失败」与「请求根本没受理」是两件事，混在一起会让客户端分不清该重试还是该改参数。
     *
     * @param userId    请求者标识，缺省 {@code demo}
     * @param sessionId 会话标识
     * @param request   请求体，{@code message} 为本次提问
     * @return 回合结果
     */
    @PostMapping("/{sessionId}/chat")
    public ChatReply chat(@RequestParam(defaultValue = DEFAULT_USER) String userId,
                          @PathVariable String sessionId,
                          @RequestBody ChatRequest request) {
        String message = requireMessage(request);
        TurnCollector collector = new TurnCollector();
        Submission submission = jellyfish.chat(userId, sessionId, message, collector);
        requireStartedTurn(submission);
        if (!collector.awaitCompletion(SYNC_TURN_TIMEOUT_MILLIS)) {
            // 等不到不代表回合停了：它还在跑、还占着这个用户的一个并发额度，而请求方已经走了。
            // 因此顺手取消——否则模型会继续为一个没人等的答复烧 token
            jellyfish.cancel(userId, sessionId);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "回合在 " + TimeUnit.MILLISECONDS.toMinutes(SYNC_TURN_TIMEOUT_MILLIS)
                            + " 分钟内没有结束，已取消该回合；请改用流式接口");
        }
        ChatReply reply = new ChatReply();
        reply.setSessionId(sessionId);
        reply.setTurnId(submission.getTurnId());
        reply.setReply(collector.answer());
        reply.setNotice(collector.notice());
        reply.setKind(collector.kind());
        reply.setReason(collector.reason());
        reply.setRounds(collector.rounds());
        return reply;
    }

    /**
     * 提问并以 SSE 推回过程与结果。
     * <p>
     * 每帧的 {@code data} 是一段 JSON（字段见 {@code SseFrame}），收到终态帧即流结束。
     * 前端用 {@code EventSource} 就能读，不需要额外的协议。
     *
     * @param userId    请求者标识，缺省 {@code demo}
     * @param sessionId 会话标识
     * @param message   本次提问
     * @return SSE 响应
     */
    @GetMapping(value = "/{sessionId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(defaultValue = DEFAULT_USER) String userId,
                             @PathVariable String sessionId,
                             @RequestParam String message) {
        if (message.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        // 0L = 不超时。一个回合可能跑几分钟，而 SSE 的默认超时到点就会把连接收掉——那样用户看到的是
        // 「答到一半没了」。什么时候收尾由写线程决定（收到终态帧之后）
        SseEmitter emitter = new SseEmitter(0L);
        SseTurnStream stream = new SseTurnStream(sessionId, emitter,
                () -> jellyfish.cancel(userId, sessionId));
        stream.start();
        try {
            // 顺序不能反：回合一启动就会推事件，晚订阅会丢掉开头那一段（STARTED 之后紧跟着就是 TEXT）
            requireStartedTurn(jellyfish.chat(userId, sessionId, message, stream));
        } catch (RuntimeException e) {
            // emitter 还没交回给 Spring 就出了错：叫停写线程，让异常按普通 JSON 响应返回。
            // 不叫停的话，那条线程会一直等着一个不会到来的终态
            stream.abort();
            throw e;
        }
        return emitter;
    }

    /**
     * 校验并取出提问内容。
     *
     * @param request 请求体，可为 {@code null}
     * @return 去掉首尾空白后的提问内容，保证非空白
     * @throws ResponseStatusException 内容为空时抛出
     */
    private String requireMessage(ChatRequest request) {
        String message = request == null || request.getMessage() == null ? "" : request.getMessage().trim();
        if (message.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return message;
    }

    /**
     * 确认回合真的起来了。
     * <p>
     * {@code chat} 的返回里带着落点：可能是「回合已启动」，也可能是别的（会话在并发下被关掉等）。
     * 后者不会有任何终态事件，因此必须在这里当场拒绝——否则本次请求会一直等到超时。
     *
     * @param submission 提交结果，不可为 {@code null}
     * @throws ResponseStatusException 没有起回合时抛出
     */
    private void requireStartedTurn(Submission submission) {
        if (submission.getKind() == Submission.Kind.STARTED_TURN) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "没有起回合：" + submission.getKind()
                        + (submission.getRejectReason() == null ? "" : "（" + submission.getRejectReason() + "）"));
    }
}
