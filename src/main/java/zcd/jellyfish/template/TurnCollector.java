package zcd.jellyfish.template;

import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.core.conversation.ShellTurnListener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 把一次回合的事件聚合成「完整回答」的监听器，供同步接口使用。
 * <p>
 * <b>正文为什么取增量拼接，而不是终态事件里那一段</b>：终态 {@code COMPLETED} 携带的是
 * <b>最后一轮</b>的模型输出，而 {@code TEXT} 增量是整个回合逐轮累积的——对同一次请求来说两者在
 * 「一轮就答完」时相等，多轮推理时不等。因此以增量拼接为准，只有当一条增量都没收到时才回落到终态正文。
 * <p>
 * <b>线程安全</b>：内核会在 {@code react} / {@code llm-stream} / 工具输出泵等多条线程上并发调用
 * {@link #onTurnEvent}，因此所有可变状态都在同一把锁下改写。
 * <p>
 * 本类不做 I/O、不阻塞，符合「监听器必须快」的纪律；等待回合结束是 {@link #awaitCompletion(long)} 的事，
 * 由业务线程承担。
 *
 * @author zcd
 */
public final class TurnCollector implements ShellTurnListener {

    /** 保护下面所有可变状态的锁。 */
    private final Object lock = new Object();

    /** 回合结束时打开的闩：业务线程等它。 */
    private final CountDownLatch done = new CountDownLatch(1);

    /** 正文增量累积。 */
    private final StringBuilder answer = new StringBuilder();

    /** 终态正文（仅当一条增量都没收到时作为回落值）。 */
    private String terminalText;

    /** 终态附加提示（正文被输出上限截断、模型一个字都没回等）。 */
    private String notice;

    /** 终态的落点名称。 */
    private String kind;

    /** 被拦下或失败的原因。 */
    private String reason;

    /** 收敛时的轮数。 */
    private int rounds;

    @Override
    public void onTurnEvent(ShellTurnEvent event) {
        synchronized (lock) {
            switch (event.getKind()) {
                case TEXT:
                    append(event.getText());
                    break;
                case COMPLETED:
                    terminalText = event.getText();
                    notice = event.getNotice();
                    rounds = event.getRounds();
                    kind = event.getKind().name();
                    break;
                case BLOCKED:
                    reason = event.getReason();
                    kind = event.getKind().name();
                    break;
                case ERROR:
                    Throwable error = event.getError();
                    reason = error == null ? "回合以异常结束" : String.valueOf(error.getMessage());
                    kind = event.getKind().name();
                    break;
                case CANCELLED:
                    kind = event.getKind().name();
                    break;
                default:
                    // STARTED / THINKING / TOOL_*：同步答复里用不到（示例没有工具，思考过程也不回灌）
                    break;
            }
            if (event.isTerminal()) {
                done.countDown();
            }
        }
    }

    /**
     * 等回合进入终态。
     * <p>
     * <b>等不到不代表回合停了</b>：它只是「本次请求放弃了等待」，回合仍在跑、仍在占该用户的并发额度，
     * 因此超时路径上应当顺手取消（见 {@code ChatController}）。
     *
     * @param timeoutMillis 最多等多少毫秒
     * @return 等到了终态返回 {@code true}；超时或被中断返回 {@code false}
     */
    public boolean awaitCompletion(long timeoutMillis) {
        try {
            return done.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 恢复中断标记后按「没等到」处理：本方法只回答「回合收敛了吗」，
            // 中断语义交给调用方判断（本示例里等价于超时）
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 取完整正文。
     *
     * @return 正文，保证非 {@code null}；模型没说话时为空串
     */
    public String answer() {
        synchronized (lock) {
            if (answer.length() > 0) {
                return answer.toString();
            }
            return terminalText == null ? "" : terminalText;
        }
    }

    /**
     * 取终态附加提示。
     *
     * @return 提示；没有时返回 {@code null}
     */
    public String notice() {
        synchronized (lock) {
            return notice;
        }
    }

    /**
     * 取终态落点名称。
     *
     * @return {@code COMPLETED} / {@code CANCELLED} / {@code ERROR} / {@code BLOCKED} 之一；
     *         还没到终态时为 {@code null}
     */
    public String kind() {
        synchronized (lock) {
            return kind;
        }
    }

    /**
     * 取被拦下或失败的原因。
     *
     * @return 原因；正常收敛时为 {@code null}
     */
    public String reason() {
        synchronized (lock) {
            return reason;
        }
    }

    /**
     * 取收敛时的轮数。
     *
     * @return 轮数；还没到终态时为 {@code 0}
     */
    public int rounds() {
        synchronized (lock) {
            return rounds;
        }
    }

    /**
     * 追加一段正文增量。
     *
     * @param delta 增量，可为 {@code null}（忽略）
     */
    private void append(String delta) {
        if (delta != null) {
            answer.append(delta);
        }
    }
}
