package zcd.jellyfish.template;

import zcd.jellyfish.core.conversation.ShellTurnEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * {@link SseTurnStream} 的单元测试：断开发现与正常收尾的区分。
 * <p>
 * 这里用 mock 的 {@link SseEmitter}，因为要验的是「容器通知时我们做了什么」——那是本类的逻辑，
 * 而不是容器的行为。容器在真实网络下会不会通知、什么时候通知，由 {@link SseDisconnectTest}
 * 用真容器 + 裸 socket 验（那里的结论是：安静期里不会主动通知，因此还需要心跳）。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SseTurnStream 流写与断开发现")
class SseTurnStreamTest {

    /** 心跳间隔：压到 100 毫秒，用例不必等分钟级。 */
    private static final long HEARTBEAT_MILLIS = 100L;

    /** SSE 响应对象（mock）。 */
    @Mock
    private SseEmitter emitter;

    /** 放行动作的调用次数：它必须最多发生一次。 */
    private final AtomicInteger released = new AtomicInteger();

    /** 被测流；用例起过才需要收工。 */
    private SseTurnStream stream;

    /**
     * 收工：让写线程退出去，免得它在 mock 上继续空转（测试进程里会有别的用例在跑）。
     */
    @AfterEach
    void tearDown() {
        if (stream != null) {
            stream.abort();
        }
    }

    @Test
    @DisplayName("容器侧三个回调都注册了，任一触发就放行回合，且只放行一次")
    void callbacks_should_releaseTurnOnce_whenContainerReportsTheStreamIsOver() {
        // Given
        stream = new SseTurnStream("s1", emitter, released::incrementAndGet);

        // When / Then：完成、超时、出错三条路都得接上——缺一条就少一条「安静期里发现断开」的路
        ArgumentCaptor<Runnable> done = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(done.capture());
        verify(emitter).onTimeout(done.capture());
        ArgumentCaptor<Consumer<Throwable>> failed = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onError(failed.capture());

        // 三条通知都来一遍（真实场景里它们会同时到）
        for (Runnable callback : done.getAllValues()) {
            callback.run();
        }
        failed.getValue().accept(new IOException("对端挂了"));

        // 放行是「取消回合」这种有副作用的事，重复做等于把别的东西也算进这笔账
        assertEquals(1, released.get(), "多条通知只能放行一次");
    }

    @Test
    @DisplayName("正常收尾之后，容器的完成回调不会把回合当成「客户端没了」")
    void completionCallback_should_notRelease_whenTurnFinishedNormally() throws Exception {
        // Given：一条已经收到终态帧的流
        stream = new SseTurnStream("s1", emitter, released::incrementAndGet, HEARTBEAT_MILLIS);
        ArgumentCaptor<Runnable> done = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(done.capture());
        stream.start();
        stream.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "答完了", 1, false, null));

        // When：写线程写完终态帧并完成响应——这一步会（同步地）触发上面那个回调
        verify(emitter, timeout(3000)).complete();
        done.getValue().run();

        // Then：回合是它自己答完的，不该再被取消一次
        assertEquals(0, released.get(), "正常收尾不是「客户端没了」");
    }

    @Test
    @DisplayName("安静期里心跳会定期写：断开因此有确定的发现路径")
    void heartbeat_should_write_whenNoFrameForAWhile() throws Exception {
        // Given：一条不推任何帧的流（模型正在长调用 / 等审批）
        stream = new SseTurnStream("s1", emitter, released::incrementAndGet, HEARTBEAT_MILLIS);
        stream.start();

        // When / Then：什么都没推，但写必须发生——注释帧就是那条「下一次写」
        verify(emitter, timeout(3000)).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(0, released.get(), "心跳本身不代表断开");
    }

    @Test
    @DisplayName("心跳写失败时放行回合（客户端已经走了），写线程随之退出")
    void heartbeat_should_releaseTurn_whenWriteFails() throws Exception {
        // Given：一条写就报错的流，即客户端已经走了
        doThrow(new IOException("Broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        stream = new SseTurnStream("s1", emitter, released::incrementAndGet, HEARTBEAT_MILLIS);

        // When
        stream.start();

        // Then：回合必须被放行——否则它继续烧 token，而结果没有去处
        assertTrue(awaitRelease(), "写失败之后必须放行回合");
    }

    @Test
    @DisplayName("写数据帧失败同样放行回合（客户端读到一半走了）")
    void send_should_releaseTurn_whenDataWriteFails() throws Exception {
        // Given
        doThrow(new IOException("Connection reset")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        stream = new SseTurnStream("s1", emitter, released::incrementAndGet, HEARTBEAT_MILLIS);
        stream.start();

        // When：内核推来一帧
        stream.onTurnEvent(ShellTurnEvent.text("s1", "t1", "第一段"));

        // Then
        assertTrue(awaitRelease(), "写数据帧失败之后必须放行回合");
    }

    /**
     * 等放行动作发生。
     *
     * @return 三秒内放行过返回 {@code true}
     */
    private boolean awaitRelease() {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (released.get() > 0) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
