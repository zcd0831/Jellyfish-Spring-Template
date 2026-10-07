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
 * <b>三条不肯让步的细节</b>：
 * <ol>
 *     <li><b>队列有界</b>（{@value #QUEUE_CAPACITY} 帧）：客户端读到一半不读了，无界队列会把内存吃光。
 *     满了就丢当前帧并计数，计数随终态帧一起送达（见 {@link SseFrame#terminal}）——宁可让前端知道
 *     「这段流不完整」，也不要静默地把自身撑爆。</li>
 *     <li><b>终态帧不走队列</b>：它一旦被丢掉，客户端就永远等不到结尾、SSE 连接永不 complete，
 *     而这种「卡住」在线上会被误诊成模型没返回。因此单独用一个引用，永远收得到。</li>
 *     <li><b>客户端断开要放行内核</b>：写失败说明没人在看了，此时调 {@code onClientGone}
 *     （本示例里是取消这个回合），否则模型继续推理、继续烧 token，而这些结果没有去处。</li>
 * </ol>
 * 线程安全：{@link #onTurnEvent} 会被多线程并发调用，因此只经由线程安全的数据结构通信。
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

    /** 是否已被宿主叫停。 */
    private volatile boolean aborted;

    /** 写线程句柄。 */
    private volatile Thread writer;

    /**
     * 构造流。
     *
     * @param sessionId    会话标识，不可为空白
     * @param emitter      SSE 响应对象，不可为 {@code null}
     * @param onClientGone 客户端断开时的放行动作，不可为 {@code null}
     */
    public SseTurnStream(String sessionId, SseEmitter emitter, Runnable onClientGone) {
        this.sessionId = sessionId;
        this.emitter = emitter;
        this.onClientGone = onClientGone;
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
     * 写线程主循环：有帧就写，没帧就等，等到「队列空 + 终态已到」就收尾。
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
                    emitter.complete();
                    return;
                }
            }
        } catch (InterruptedException e) {
            // 只可能是宿主调了 abort()：恢复中断标记后安静退出
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 客户端断开 / 连接已被容器关闭。放行内核里的回合，别让它继续为一个没人看的答复烧 token
            LOG.debug("SSE 写出失败，按客户端断开处理: sessionId={}", sessionId, e);
            releaseTurnQuietly();
            completeQuietly(e);
        }
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
        if (aborted) {
            return;
        }
        emitter.send(SseEmitter.event().data(ObjectMapperWrapper.writeValueAsString(frame)));
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
