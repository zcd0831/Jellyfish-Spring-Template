package zcd.jellyfish.template;

import zcd.jellyfish.core.conversation.Submission;
import zcd.jellyfish.spring.boot.user.JellyfishUserFacade;
import zcd.jellyfish.template.api.ChatReply;
import zcd.jellyfish.template.api.ChatRequest;
import zcd.jellyfish.template.api.SessionCreated;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 三个示例接口：建会话、同步提问、流式提问。
 * <p>
 * <b>全部走 {@link JellyfishUserFacade}，没有一处直接碰内核 Bean</b>。这不是风格问题：内核没有「用户」
 * 这一维（会话无归属字段、命令域能操作任意会话、审批与提问通道不校验调用方说的会话是不是自己的），
 * 那些护栏全都在门面里。绕过门面 = 绕过全部护栏。
 * <p>
 * <b>接口里没有身份参数</b>：每个方法都从 {@link CurrentUser} 取「这次请求是谁」——把它做成请求参数
 * 或请求头就等于<b>把归属校验交给调用方</b>（换一个自报的标识就能读别人的会话）。示例实现读
 * {@code X-User-Id} 头，替换它只需要声明一个自己的 {@link CurrentUser} Bean。
 * <p>
 * <b>建会话也不收 agentId</b>：用哪个 agent 是<b>授权决策</b>（谁能用权限更宽的那个），不是请求参数——
 * 让客户端自选等于用请求里的一句话绕开本用户原有的权限收窄。示例只用内核的默认 agent；
 * 真实业务里按用户/租户挑，再传 {@code JellyfishUserFacade.createSession(userId, agentId, ...)}。
 * <p>
 * <b>同步接口也不是「阻塞一个容器线程等到底」</b>：它返回 {@link DeferredResult}，由回合的终态事件
 * 直接填结果，因此<b>没有任何线程为这次请求阻塞</b>（早先的写法是 {@code await(5 分钟)}，两百个这样的
 * 请求就能把 Tomcat 的线程池排满）。接口形状没变——仍是「等回合结束，一次性返回完整 JSON」。
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

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);

    /** 同步接口最多等多久。 */
    private static final long SYNC_TURN_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5);

    /** 门面：业务侧与内核之间的唯一入口。 */
    private final JellyfishUserFacade jellyfish;

    /** 身份来源：这次请求是谁。 */
    private final CurrentUser currentUser;

    /**
     * 构造控制器。
     *
     * @param jellyfish   用户门面，由 starter 装配，不可为 {@code null}
     * @param currentUser 身份来源，由本示例装配（可被接入方替换），不可为 {@code null}
     */
    public ChatController(JellyfishUserFacade jellyfish, CurrentUser currentUser) {
        this.jellyfish = jellyfish;
        this.currentUser = currentUser;
    }

    /**
     * 建一个会话。
     * <p>
     * 会话建好后就绑在 {@link CurrentUser} 给出的这个用户上，之后每次访问都会先校验归属。
     *
     * @return 新会话标识
     */
    @PostMapping
    public SessionCreated create() {
        String userId = currentUser.userId();
        SessionCreated created = new SessionCreated();
        created.setSessionId(jellyfish.createSession(userId));
        return created;
    }

    /**
     * 提问并等回合结束，一次性返回完整回答。
     * <p>
     * <b>它是「异步等待」，不是「阻塞」</b>：返回 {@link DeferredResult} 之后容器线程立刻释放，
     * 结果由回合的终态事件填上。客户端看到的行为与阻塞写法完全一样（一个请求、一个 JSON 响应），
     * 区别只在服务端的线程占用：阻塞写法下，一个在途请求占住一个容器线程最长 5 分钟。
     * <p>
     * <b>HTTP 200 不等于「答出来了」</b>：回合本身的终态在响应体的 {@code kind} 里，只有
     * {@code COMPLETED} 是正常答完；{@code ERROR}（例如模型侧报错）与 {@code BLOCKED}（插件拦下）
     * 时 {@code reply} 可能为空，看 {@code reason}。之所以不用 HTTP 状态码表达它：
     * 「请求成功、回合失败」与「请求根本没受理」是两件事，混在一起会让客户端分不清该重试还是该改参数。
     * <p>
     * <b>插件接管了输入时也是 200</b>：那种情况下压根没有回合，响应体里 {@code kind=HANDLED_INPUT}、
     * {@code notice} 是接管者的说明。这与内核外壳的做法一致（它同样回一个正常的 JSON 体）。
     * <p>
     * <b>客户端断开这条路只是「尽力」</b>：容器在<b>没有任何写出</b>的长安静期里通常不会上报断开
     * （本仓库实测过，见 README 的 SSE 一节），因此这里注册的完成 / 出错回调只在容器真的发现时才生效；
     * 想尽早释放回合请用流式接口——那边有心跳，断开是确定能发现的。
     *
     * @param sessionId 会话标识
     * @param request   请求体，{@code message} 为本次提问
     * @return 延迟结果：回合收敛后的完整答复，或错误
     */
    @PostMapping("/{sessionId}/chat")
    public DeferredResult<ChatReply> chat(@PathVariable String sessionId, @RequestBody ChatRequest request) {
        String userId = currentUser.userId();
        String message = requireMessage(request);
        DeferredResult<ChatReply> result = new DeferredResult<ChatReply>(SYNC_TURN_TIMEOUT_MILLIS);
        // 「已经交付过」的闸门：超时、断开、回合终态三条路都可能走到交付，而交付只能发生一次
        AtomicBoolean settled = new AtomicBoolean();
        TurnCollector collector = new TurnCollector(collected -> settle(result, settled, sessionId, collected));
        // 顺序是刻意的：先把收尾路径挂上，再提交回合。反过来的话，回合可能在挂载之前就结束，
        // 那时超时回调挂在一个已经完成的结果上——Spring 不报错，但也没人负责取消那个回合
        result.onTimeout(() -> onTimeout(result, settled, collector, userId, sessionId));
        result.onError(error -> cancelIfNobodyWaits(settled, userId, sessionId, error));
        result.onCompletion(() -> cancelIfNobodyWaits(settled, userId, sessionId, null));

        Submission submission = jellyfish.chat(userId, sessionId, message, collector);
        if (submission.getKind() == Submission.Kind.HANDLED_INPUT) {
            // 插件把输入接过去了：没有回合，也就不会有终态事件，因此在这里当场交付
            settled.set(true);
            result.setResult(handled(sessionId, submission));
            return result;
        }
        requireStartedTurn(submission);
        return result;
    }

    /**
     * 提问并以 SSE 推回过程与结果。
     * <p>
     * 每帧的 {@code data} 是一段 JSON（字段见 {@code SseFrame}），收到终态帧即流结束。
     * 前端用 {@code EventSource} 就能读，不需要额外的协议。
     *
     * @param sessionId 会话标识
     * @param message   本次提问
     * @return SSE 响应
     */
    @GetMapping(value = "/{sessionId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String sessionId, @RequestParam String message) {
        String userId = currentUser.userId();
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
            requireStreamableSubmission(jellyfish.chat(userId, sessionId, message, stream));
        } catch (RuntimeException e) {
            // emitter 还没交回给 Spring 就出了错：叫停写线程，让异常按普通 JSON 响应返回。
            // 不叫停的话，那条线程会一直等着一个不会到来的终态
            stream.abort();
            throw e;
        }
        return emitter;
    }

    /**
     * 交付回合结果。
     * <p>
     * <b>先抢闸门再交付</b>：抢不到说明超时或断开那条路已经交付过了（或者正在交付），此时什么也不做。
     *
     * @param result    延迟结果
     * @param settled   交付闸门
     * @param sessionId 会话标识
     * @param collected 回合收集器
     */
    private static void settle(DeferredResult<ChatReply> result, AtomicBoolean settled, String sessionId,
                               TurnCollector collected) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        ChatReply reply = new ChatReply();
        reply.setSessionId(sessionId);
        reply.setTurnId(collected.turnId());
        reply.setReply(collected.answer());
        reply.setNotice(collected.notice());
        reply.setKind(collected.kind());
        reply.setReason(collected.reason());
        reply.setRounds(collected.rounds());
        result.setResult(reply);
    }

    /**
     * 等超时了：先看是不是刚好收敛（那是白等，不该取消），确实还在跑才取消并回 504。
     * <p>
     * <b>为什么必须先复查</b>：超时判定与回合收敛是两个线程上的两件事，它们之间的间隔可能只有几毫秒。
     * 不复查的话，一个已经把活干完的回合会被在这里取消掉，客户端还收到 504——活干了、token 花了、
     * 结果没人要。<b>复查用的是收集器里的终态</b>（它在回调之前就已经写好了），因此「刚收敛」这一段
     * 是能被认出来的；剩下的窗口是「查完到 cancel 之间」那一瞬，那一段无法消除，
     * 但代价已经小得多（{@code cancel} 对一个已收敛的回合是无害的）。
     *
     * @param result    延迟结果
     * @param settled   交付闸门
     * @param collected 回合收集器
     * @param userId    请求者标识
     * @param sessionId 会话标识
     */
    void onTimeout(DeferredResult<ChatReply> result, AtomicBoolean settled, TurnCollector collected,
                   String userId, String sessionId) {
        if (collected.kind() != null) {
            // 刚好在这几毫秒里收敛了：把已经干完的活交付出去（这不叫超时）
            settle(result, settled, sessionId, collected);
            return;
        }
        if (jellyfish.isTurnRunning(userId, sessionId)) {
            jellyfish.cancel(userId, sessionId);
        }
        if (settled.compareAndSet(false, true)) {
            result.setErrorResult(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "回合在 " + TimeUnit.MILLISECONDS.toMinutes(SYNC_TURN_TIMEOUT_MILLIS)
                            + " 分钟内没有结束，已取消该回合；请改用流式接口"));
        }
    }

    /**
     * 客户端已经不在了（容器发现断开 / 出错）时放行内核，别让模型继续为一个没人等的答复烧 token。
     * <p>
     * <b>已经交付过就不动</b>：正常收尾之后容器也会回调 {@code onCompletion}，那是「请求完成了」，
     * 不是「客户端跑了」——认错了会把一个刚答完的回合取消掉。
     *
     * @param settled   交付闸门
     * @param userId    请求者标识
     * @param sessionId 会话标识
     * @param error     容器给出的错误，可为 {@code null}（完成回调那条路）
     */
    void cancelIfNobodyWaits(AtomicBoolean settled, String userId, String sessionId, Throwable error) {
        if (settled.get()) {
            return;
        }
        LOG.debug("客户端已不再等待，取消回合: sessionId={}", sessionId, error);
        jellyfish.cancel(userId, sessionId);
    }

    /**
     * 构造「输入被插件接管」的答复。
     *
     * @param sessionId  会话标识
     * @param submission 提交结果（{@code getTurnId()} 为 {@code null}）
     * @return 答复，{@code kind} 为 {@code HANDLED_INPUT}
     */
    private static ChatReply handled(String sessionId, Submission submission) {
        ChatReply reply = new ChatReply();
        reply.setSessionId(sessionId);
        reply.setKind(submission.getKind().name());
        reply.setNotice(submission.getNotice());
        return reply;
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
     * 确认回合真的起来了（同步接口）。
     * <p>
     * <b>按落点分别回应，不一律回 409</b>：{@code REJECTED(BLANK_INPUT)} 是客户端写错了请求（400）、
     * {@code REJECTED(NO_SESSION)} 是会话不存在（404，与「不是你的会话」同一个答复），
     * 而 {@code EXECUTED_COMMAND} / {@code STARTED_DIRECTIVE} 到不了这里——门面用的是
     * {@code serverChat()} 策略（不执行命令、不解析输入指令）。把它们压成同一个 409 会让调用方
     * 分不清「该改参数」还是「该先建会话」。
     *
     * @param submission 提交结果，不可为 {@code null}
     * @throws ResponseStatusException 没有起回合时抛出
     */
    static void requireStartedTurn(Submission submission) {
        if (submission.getKind() == Submission.Kind.STARTED_TURN) {
            return;
        }
        throw new ResponseStatusException(statusOf(submission), describe(submission));
    }

    /**
     * 确认这个提交能产出流（流式接口）。
     * <p>
     * 与 {@link #requireStartedTurn(Submission)} 分开，因为流式接口<b>表达不了</b>「插件接管了输入」：
     * 它的协议就是「一直推到终态帧」，而没有回合就没有终态帧，客户端会一直等。因此这里把那一种
     * 明确地拒掉，并在消息里指向同步接口——而不是让请求挂在那儿。
     *
     * @param submission 提交结果，不可为 {@code null}
     * @throws ResponseStatusException 没有起回合时抛出
     */
    static void requireStreamableSubmission(Submission submission) {
        if (submission.getKind() == Submission.Kind.STARTED_TURN) {
            return;
        }
        if (submission.getKind() == Submission.Kind.HANDLED_INPUT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "本次输入被插件接管，没有起回合（流式接口无法表达这个结果，请改用同步接口）");
        }
        throw new ResponseStatusException(statusOf(submission), describe(submission));
    }

    /**
     * 把「没起回合」的落点翻成 HTTP 状态码。
     * <p>
     * {@code NO_SESSION} 用 404 是刻意的：与「不是你的会话」同一个状态码，客户端无法靠状态码
     * 判断某个 id 到底存不存在。
     *
     * @param submission 提交结果，不可为 {@code null}
     * @return 状态码
     */
    private static HttpStatus statusOf(Submission submission) {
        if (submission.getKind() == Submission.Kind.REJECTED
                && submission.getRejectReason() == Submission.RejectReason.NO_SESSION) {
            return HttpStatus.NOT_FOUND;
        }
        if (submission.getKind() == Submission.Kind.REJECTED) {
            // 走到这里只可能是 BLANK_INPUT（上面的 NO_SESSION 已分流）
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.CONFLICT;
    }

    /**
     * 拼一句「为什么没有起回合」。
     *
     * @param submission 提交结果，不可为 {@code null}
     * @return 说明
     */
    private static String describe(Submission submission) {
        return "没有起回合：" + submission.getKind()
                + (submission.getRejectReason() == null ? "" : "（" + submission.getRejectReason() + "）");
    }
}
