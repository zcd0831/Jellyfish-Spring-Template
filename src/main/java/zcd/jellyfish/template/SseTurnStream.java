package zcd.jellyfish.template;

import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.core.conversation.ShellTurnListener;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;
import zcd.jellyfish.template.api.SseFrame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把一次回合的事件推成 SSE 流。
 * <p>
 * <b>为什么要多一条线程</b>：listener 是在内核线程（{@code react} / {@code llm-stream} / 工具输出泵）
 * 上被调用的，而 {@code emitter.send(...)} 是<b>会阻塞的 I/O</b>——客户端一慢，写操作就把内核线程按在那里，
 * 整个会话（甚至同一个 {@code react} 线程上的别的会话）都跟着卡住。因此 listener 只做一件事：把帧塞进队列，
 * 由本类自己那条线程去写。
 * <p>
 * <b>四条不肯让步的细节</b>：
 * <ol>
 *     <li><b>队列有界</b>（{@value #QUEUE_CAPACITY} 帧）：客户端读到一半不读了，无界队列会把内存吃光。
 *     满了就丢当前帧并计数，计数随终态帧一起送达（见 {@link SseFrame#terminal}）——宁可让前端知道
 *     「这段流不完整」，也不要静默地把自身撑爆。</li>
 *     <li><b>终态帧不走队列</b>：它一旦被丢掉，客户端就永远等不到结尾、SSE 连接永不 complete，
 *     而这种「卡住」在线上会被误诊成模型没返回。因此单独用一个引用，永远收得到。</li>
 *     <li><b>客户端断开要放行内核</b>：写失败说明没人在看了，此时调 {@code onClientGone}
 *     （本示例里是取消这个回合），否则模型继续推理、继续烧 token，而这些结果没有去处。</li>
 *     <li><b>断开不能只靠「下一次写失败」发现</b>：模型可能在长调用或等着审批，那期间<b>一帧都没有</b>，
 *     于是「下一次写」可能几分钟都不来——而这几分钟里回合照样占着 react 线程、并发额度与 token。
 *     因此这里注册容器侧的完成 / 出错回调（对端挂断、连接出错时容器会通知），
 *     并按 {@value #HEARTBEAT_MILLIS} 毫秒发一个注释帧当心跳，让「写」这件事在安静期里也定期发生。</li>
 * </ol>
 * 线程安全：{@link #onTurnEvent} 会被多线程并发调用，因此只经由线程安全的数据结构通信；
 * 容器侧的回调与写线程之间用 {@link #released} 保证「放行内核」只发生一次。
 *
 * @author zcd
 */
public final class SseTurnStream implements ShellTurnListener {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SseTurnStream.class);

    /** 待写帧队列的容量。 */
    private static final int QUEUE_CAPACITY = 1024;

    /** 写线程单次轮询的等待时长（毫秒）：只在「队列空 + 终态还没来」时用到。 */
    private static final long POLL_MILLIS = 100L;

    /**
     * 心跳间隔（毫秒）：安静这么久就发一个注释帧。
     * <p>
     * <b>为什么必须有心跳</b>：容器通知「对端没了」并不总是及时——很多实现要等到下一次<b>写</b>
     * 才发现连接已经断了。而一个回合里最长的安静期恰恰是最要紧的那段：长 LLM 调用、等人工审批、
     * 长命令执行，这些时候一帧都不产生，写也就永远不发生，断开因此可以几分钟不被察觉。
     * 心跳把「写」变成定期发生的事，断开于是有了确定的发现路径；它同时挡住中间代理把空闲连接掐掉
     * （那些代理按「多久没数据」判空闲，而 SSE 的安静期是正常现象）。
     * <p>
     * <b>为什么是注释帧</b>：{@code :keep-alive} 是 SSE 协议里的注释，EventSource 直接忽略，
     * 前端一行都不用改；用真数据帧当心跳会污染前端的消息序列。
     */
    private static final long HEARTBEAT_MILLIS = 15000L;

    /** SSE 响应对象。 */
    private final SseEmitter emitter;

    /** 会话标识，仅用于线程命名。 */
    private final String sessionId;

    /** 客户端断开时的放行动作。 */
    private final Runnable onClientGone;

    /** 待写帧队列。 */
    private final BlockingQueue<SseFrame> queue = new ArrayBlockingQueue<SseFrame>(QUEUE_CAPACITY);

    /** 终态帧（不走队列，见类注释）。 */
    private final AtomicReference<SseFrame> terminal = new AtomicReference<SseFrame>();

    /** 已丢弃的帧数。 */
    private final AtomicLong dropped = new AtomicLong();

    /** 心跳间隔（毫秒）：允许测试缩短，见 {@value #HEARTBEAT_MILLIS} 的理由。 */
    private final long heartbeatMillis;

    /** 「放行内核」这道闸门：容器回调与写线程都可能触发，只允许过一次。 */
    private final AtomicBoolean released = new AtomicBoolean();

    /** 是否已被宿主叫停。 */
    private volatile boolean aborted;

    /** 是否已正常收尾（终态帧写完、响应已 complete）。 */
    private volatile boolean finished;

    /** 写线程句柄。 */
    private volatile Thread writer;

    /** 上一次成功写出任何东西的时刻（毫秒），心跳据此计时。 */
    private volatile long lastWriteAt;

    /**
     * 构造流。
     *
     * @param sessionId    会话标识，不可为空白
     * @param emitter      SSE 响应对象，不可为 {@code null}
     * @param onClientGone 客户端断开时的放行动作，不可为 {@code null}
     */
    public SseTurnStream(String sessionId, SseEmitter emitter, Runnable onClientGone) {
        this(sessionId, emitter, onClientGone, HEARTBEAT_MILLIS);
    }

    /**
     * 构造流，并指定心跳间隔（测试用：心跳是分钟级的，用例等不起）。
     *
     * @param sessionId       会话标识，不可为空白
     * @param emitter         SSE 响应对象，不可为 {@code null}
     * @param onClientGone    客户端断开时的放行动作，不可为 {@code null}
     * @param heartbeatMillis 心跳间隔（毫秒），必须为正
     */
    SseTurnStream(String sessionId, SseEmitter emitter, Runnable onClientGone, long heartbeatMillis) {
        this.sessionId = sessionId;
        this.emitter = emitter;
        this.onClientGone = onClientGone;
        this.heartbeatMillis = heartbeatMillis;
        // 容器侧的三条通知：对端挂断、连接出错、或超时（本示例用 0L 不超时，这里是为完整性）。
        // 注册它们是为了「安静期里也能发现断开」——只看写失败的话，模型在长调用或等审批时
        // 一帧都没有，也就永远等不到那次失败
        emitter.onCompletion(this::onEmitterClosed);
        emitter.onError(this::onEmitterFailed);
        emitter.onTimeout(this::onEmitterClosed);
    }

    /**
     * 起写线程。
     * <p>
     * <b>每次请求一条线程、且是守护线程</b>：写线程要活到整个回合结束（可能几分钟），
     * 放进共享线程池会让若干个「客户端不读」的流把池子占满；而守护线程不会在进程退出时拖住关停
     * （在途回合由内核关停流程收敛）。
     */
    public void start() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                pump();
            }
        }, "sse-turn-" + sessionId);
        thread.setDaemon(true);
        writer = thread;
        lastWriteAt = System.currentTimeMillis();
        thread.start();
    }

    /**
     * 叫停写线程：本次请求已改走错误响应，不再往 {@code emitter} 里写任何东西。
     * <p>
     * 用中断而不是 {@code emitter.complete()}：emitter 此刻还没交回给 Spring，
     * 对它做完成动作没有意义（也没人读），这里只需要让那条线程退出去。
     */
    public void abort() {
        aborted = true;
        Thread thread = writer;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void onTurnEvent(ShellTurnEvent event) {
        if (event.isTerminal()) {
            terminal.set(SseFrame.terminal(event, dropped.get()));
            return;
        }
        if (!queue.offer(SseFrame.of(event))) {
            dropped.incrementAndGet();
        }
    }

    /**
     * 写线程主循环：有帧就写，没帧就等，等久了发一个心跳，等到「队列空 + 终态已到」就收尾。
     */
    private void pump() {
        try {
            while (true) {
                SseFrame frame = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    send(frame);
                    continue;
                }
                SseFrame last = terminal.get();
                if (last != null) {
                    // 终态之后内核不再推事件，因此这里排空队列不会有新的竞争；不排空会丢掉最后几个增量
                    drain();
                    send(last);
                    completeNormally();
                    return;
                }
                heartbeatIfDue();
            }
        } catch (InterruptedException e) {
            // 宿主调了 abort()，或容器回调已判定客户端没了：恢复中断标记后安静退出
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 客户端断开 / 连接已被容器关闭。放行内核里的回合，别让它继续为一个没人看的答复烧 token
            LOG.debug("SSE 写出失败，按客户端断开处理: sessionId={}", sessionId, e);
            releaseTurn();
            completeQuietly(e);
        }
    }

    /**
     * 到点就发一个注释帧当心跳，让「写」在安静期里定期发生——客户端断开正是靠写失败发现的。
     *
     * @throws IOException 客户端已断开时抛出（这正是心跳要发现的那件事）
     */
    private void heartbeatIfDue() throws IOException {
        if (System.currentTimeMillis() - lastWriteAt < heartbeatMillis) {
            return;
        }
        // 注释帧：EventSource 直接忽略，前端一行都不用改
        sendRaw(SseEmitter.event().comment("keep-alive"));
    }

    /**
     * 把队列里剩下的帧写完。
     *
     * @throws IOException 客户端已断开时抛出
     */
    private void drain() throws IOException {
        SseFrame frame;
        while ((frame = queue.poll()) != null) {
            send(frame);
        }
    }

    /**
     * 写一帧。
     * <p>
     * 序列化统一走内核的 {@link ObjectMapperWrapper}，不 {@code new ObjectMapper()}：
     * 「全进程只有一处 Jackson 配置」这条纪律与本仓库是不是 starter 无关。
     *
     * @param frame 待写的帧
     * @throws IOException 客户端已断开时抛出
     */
    private void send(SseFrame frame) throws IOException {
        sendRaw(SseEmitter.event().data(ObjectMapperWrapper.writeValueAsString(frame)));
    }

    /**
     * 写出一条 SSE 事件，并记下「刚刚写过东西」。
     * <p>
     * 心跳与数据帧都走这里：{@link #lastWriteAt} 是心跳的计时依据，两处各记一次迟早会漏一处。
     *
     * @param builder 待写事件
     * @throws IOException 客户端已断开时抛出
     */
    private void sendRaw(SseEmitter.SseEventBuilder builder) throws IOException {
        if (aborted) {
            return;
        }
        emitter.send(builder);
        lastWriteAt = System.currentTimeMillis();
    }

    /**
     * 正常收尾：置上 {@link #finished} 之后完成响应。
     * <p>
     * <b>顺序不能反</b>：{@code emitter.complete()} 会（同步地）触发容器侧的回调，
     * 而那个回调要能认出「这是正常收尾、不是客户端没了」——认错了会把一个刚答完的回合取消掉。
     */
    private void completeNormally() {
        finished = true;
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            LOG.debug("收敛 SSE 响应失败（容器可能已经提前关掉了它）: sessionId={}", sessionId, e);
        }
    }

    /**
     * 容器通知「这次 SSE 响应结束了」（正常完成或超时）。
     * <p>
     * 这就是「安静期里也能发现断开」的那条路：模型在长调用或等审批时一帧都不产生，
     * 只看写失败的话要等到下一次写——可能是几分钟以后。
     */
    private void onEmitterClosed() {
        if (finished || aborted) {
            return;
        }
        LOG.debug("SSE 响应已被容器收敛，按客户端断开处理: sessionId={}", sessionId);
        releaseTurn();
    }

    /**
     * 容器通知「这次 SSE 响应出错了」：对端挂断最常见，也可能是容器自己出了问题。
     * <p>
     * 两种情况要做的是同一件事——放行内核里的回合，因为它产出的东西已经没有去处了。
     *
     * @param error 容器给出的错误，可为 {@code null}
     */
    private void onEmitterFailed(Throwable error) {
        if (finished || aborted) {
            return;
        }
        LOG.debug("SSE 响应出错，按客户端断开处理: sessionId={}", sessionId, error);
        releaseTurn();
    }

    /**
     * 放行内核里的回合，并把写线程叫停。
     * <p>
     * <b>只放行一次</b>（{@link #released}）：容器回调与写线程的失败路径会同时走到这里，
     * 而 {@code onClientGone} 是「取消回合」这种有副作用的事，重复做等于把别的东西也算进这笔账。
     */
    private void releaseTurn() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        releaseTurnQuietly();
        Thread thread = writer;
        if (thread != null) {
            // 写线程可能正阻塞在轮询上，而它等的那些帧再没有谁需要了
            thread.interrupt();
        }
    }

    /**
     * 放行内核里的回合，失败只记日志（此时已经在处理「客户端没了」这条路径上，不能再抛出去）。
     */
    private void releaseTurnQuietly() {
        try {
            onClientGone.run();
        } catch (RuntimeException e) {
            LOG.debug("放行回合失败（回合可能已经自己收敛了）: sessionId={}", sessionId, e);
        }
    }

    /**
     * 收敛 SSE 响应，失败只记日志。
     *
     * @param cause 失败原因
     */
    private void completeQuietly(Exception cause) {
        if (aborted) {
            return;
        }
        try {
            emitter.completeWithError(cause);
        } catch (RuntimeException e) {
            LOG.debug("收敛 SSE 响应失败（容器可能已经提前关掉了它）: sessionId={}", sessionId, e);
        }
    }
}
