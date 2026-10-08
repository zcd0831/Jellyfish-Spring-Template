package zcd.jellyfish.template;

import zcd.jellyfish.core.conversation.ShellTurnEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TurnCollector} 的单元测试：聚合语义与「终态回调恰好一次」。
 * <p>
 * 这个类是同步接口那条路的全部逻辑所在（流式那条是 {@code SseTurnStream}），而它此前一条用例都没有——
 * 内部几个决定都不显然：正文取增量拼接而不是终态那一段、回合标识从事件里取而不是从提交结果里取、
 * 回调在锁外且只发一次。每一条都各有一个用例。
 *
 * @author zcd
 */
@DisplayName("TurnCollector 回合聚合")
class TurnCollectorTest {

    @Test
    @DisplayName("正文取增量拼接，而不是终态事件里那一段")
    void answer_should_joinDeltas_whenTextEventsArrive() {
        // Given：一轮的终态正文与逐段增量（多轮推理时两者不等，终态只带最后一轮）
        TurnCollector collector = new TurnCollector(collected -> { });

        // When
        collector.onTurnEvent(ShellTurnEvent.text("s1", "t1", "第一段"));
        collector.onTurnEvent(ShellTurnEvent.text("s1", "t1", "第二段"));
        collector.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "只有最后一轮", 1, false, null));

        // Then
        assertEquals("第一段第二段", collector.answer());
        assertEquals(1, collector.rounds());
    }

    @Test
    @DisplayName("一条增量都没有时回落到终态正文（模型一次性给出整段的情形）")
    void answer_should_fallBackToTerminalText_whenNoDeltaArrived() {
        // Given
        TurnCollector collector = new TurnCollector(collected -> { });

        // When
        collector.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "整段正文", 1, false, null));

        // Then
        assertEquals("整段正文", collector.answer());
    }

    @Test
    @DisplayName("终态四种落点各自的字段都被收下来")
    void onTurnEvent_should_recordFields_whenTerminal() {
        // Given / When：正常收敛
        TurnCollector completed = new TurnCollector(collected -> { });
        completed.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "正文", 3, true, "被输出上限截断"));

        // Then
        assertEquals("COMPLETED", completed.kind());
        assertEquals("被输出上限截断", completed.notice());
        assertEquals(3, completed.rounds());
        assertNull(completed.reason(), "正常收敛没有原因");

        // Given / When：插件拦下
        TurnCollector blocked = new TurnCollector(collected -> { });
        blocked.onTurnEvent(ShellTurnEvent.blocked("s1", "t1", "权限不足"));

        // Then
        assertEquals("BLOCKED", blocked.kind());
        assertEquals("权限不足", blocked.reason());

        // Given / When：异常收尾
        TurnCollector error = new TurnCollector(collected -> { });
        error.onTurnEvent(ShellTurnEvent.error("s1", "t1", new IllegalStateException("模型侧报错")));

        // Then
        assertEquals("ERROR", error.kind());
        assertEquals("模型侧报错", error.reason(), "异常只留消息文本，不带异常对象");

        // Given / When：被取消
        TurnCollector cancelled = new TurnCollector(collected -> { });
        cancelled.onTurnEvent(ShellTurnEvent.cancelled("s1", "t1"));

        // Then
        assertEquals("CANCELLED", cancelled.kind());
    }

    @Test
    @DisplayName("终态回调恰好一次，且收到的是收集器自己")
    void onTurnEvent_should_notifyTerminalOnce() {
        // Given
        AtomicInteger notified = new AtomicInteger();
        List<TurnCollector> seen = new ArrayList<TurnCollector>();
        TurnCollector collector = new TurnCollector(collected -> {
            notified.incrementAndGet();
            seen.add(collected);
        });

        // When：终态之后又推来一帧（真实内核不会这么干，但收集器不该依赖那一条）
        collector.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "正文", 1, false, null));
        collector.onTurnEvent(ShellTurnEvent.text("s1", "t1", "终态之后的增量"));

        // Then：交付动作只发生一次，否则容器会收到两次 setResult
        assertEquals(1, notified.get());
        assertSame(collector, seen.get(0));
    }

    @Test
    @DisplayName("非终态事件不触发回调（还没到交付的时候）")
    void onTurnEvent_should_notNotify_whenNotTerminal() {
        // Given
        AtomicInteger notified = new AtomicInteger();
        TurnCollector collector = new TurnCollector(collected -> notified.incrementAndGet());

        // When
        collector.onTurnEvent(ShellTurnEvent.started("s1", "t1"));
        collector.onTurnEvent(ShellTurnEvent.text("s1", "t1", "增量"));

        // Then
        assertEquals(0, notified.get());
        assertNull(collector.kind());
    }

    @Test
    @DisplayName("回调自己抛异常时不影响回合（那已经是最后一步，抛出去会让调用方永远等不到结果）")
    void notifyTerminal_should_swallowFailure_whenCallbackThrows() {
        // Given：一个交付时就出错的回调（例如响应已经被容器收走了）
        TurnCollector collector = new TurnCollector(collected -> {
            throw new IllegalStateException("setResult 失败");
        });

        // When / Then：不抛出去，且状态照常留在收集器里供事后排查
        assertDoesNotThrow(() -> collector.onTurnEvent(
                ShellTurnEvent.completed("s1", "t1", "正文", 1, false, null)));
        assertEquals("COMPLETED", collector.kind());
    }

    @Test
    @DisplayName("回合标识从事件里取：终态可能早于调用方拿到提交结果")
    void turnId_should_comeFromEvents_whenSubmissionIsNotAvailableYet() {
        // Given
        TurnCollector collector = new TurnCollector(collected -> { });
        assertNull(collector.turnId(), "还没收到事件时没有回合标识");

        // When
        collector.onTurnEvent(ShellTurnEvent.started("s1", "turn-42"));

        // Then
        assertEquals("turn-42", collector.turnId());
    }

    @Test
    @DisplayName("多线程并发推事件时一条增量都不丢（内核会从多条线程回调）")
    void answer_should_keepEveryDelta_whenEventsArriveConcurrently() throws InterruptedException {
        // Given：8 条线程各推 200 条增量
        TurnCollector collector = new TurnCollector(collected -> { });
        int threads = 8;
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        // When
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        collector.onTurnEvent(ShellTurnEvent.text("s1", "t1", "x"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发推事件应当在时限内结束");

        // Then：锁外的那段不能吃掉任何一条
        assertEquals(threads * perThread, collector.answer().length());
    }
}
