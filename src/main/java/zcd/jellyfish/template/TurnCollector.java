package zcd.jellyfish.template;

import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.core.conversation.ShellTurnListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 把一次回合的事件聚合成「完整回答」的监听器，供同步接口使用。
 * <p>
 * <b>它不阻塞，也不被谁等待</b>：收到终态时<b>主动回调</b> {@code onTerminal}，由调用方在那里交付结果
 * （本示例里是把 {@code DeferredResult} 填上）。早先的做法是「控制器线程 {@code await(...)} 到回合结束」，
 * 那会让一个在途请求占住一个容器线程最长 5 分钟——Tomcat 缺省 200 个线程，两百个这样的请求就把
 * {@code /health} 之类短请求排到队尾了。改成回调之后，等待这件事被完全去掉：
 * <b>没有任何线程为这次请求阻塞</b>。
 * <p>
 * <b>正文为什么取增量拼接，而不是终态事件里那一段</b>：终态 {@code COMPLETED} 携带的是
 * <b>最后一轮</b>的模型输出，而 {@code TEXT} 增量是整个回合逐轮累积的——对同一次请求来说两者在
 * 「一轮就答完」时相等，多轮推理时不等。因此以增量拼接为准，只有当一条增量都没收到时才回落到终态正文。
 * <p>
 * <b>回合标识从事件里取，不从提交结果里取</b>：回合一启动就产出事件，因此终态可能在本类所属的调用
 * 还没拿到 {@code Submission} 之前就到了——那时若还去读 {@code submission.getTurnId()}，读到的会是
 * {@code null}。
 * <p>
 * <b>线程安全</b>：内核会在 {@code react} / {@code llm-stream} / 工具输出泵等多条线程上并发调用
 * {@link #onTurnEvent}，因此所有可变状态都在同一把锁下改写；回调在锁外执行（它会走 Spring 的
 * {@code setResult}，不该持着锁）。
 * <p>
 * 本类不做 I/O、不阻塞，符合「监听器必须快」的纪律。
 *
 * @author zcd
 */
public final class TurnCollector implements ShellTurnListener {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(TurnCollector.class);

    /** 终态回调：恰好调用一次，参数是它自己（调用方据此取值）。 */
    private final Consumer<TurnCollector> onTerminal;

    /** 保护下面所有可变状态的锁。 */
    private final Object lock = new Object();

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

    /** 回合标识（取自事件）。 */
    private String turnId;

    /** 收敛时的轮数。 */
    private int rounds;

    /** 是否已经回调过：内核只会推一条终态事件，但**本类不依赖那一条**。 */
    private boolean terminalSeen;

    /**
     * 构造收集器。
     *
     * @param onTerminal 终态回调，不可为 {@code null}
     */
    public TurnCollector(Consumer<TurnCollector> onTerminal) {
        this.onTerminal = onTerminal;
    }

    @Override
    public void onTurnEvent(ShellTurnEvent event) {
        boolean firstTerminal = false;
        synchronized (lock) {
            if (event.getTurnId() != null) {
                turnId = event.getTurnId();
            }
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
            if (event.isTerminal() && !terminalSeen) {
                terminalSeen = true;
                firstTerminal = true;
            }
        }
        if (firstTerminal) {
            notifyTerminal();
        }
    }

    /**
     * 在锁外回调，并保证「回调失败不影响回合本身」。
     * <p>
     * 这里已经是回合的最后一步，回调是交付动作（{@code DeferredResult.setResult} → 容器接着去写响应），
     * 它抛出来只会变成一条内核侧的监听器异常日志，却会让调用方永远等不到结果。因此这里兜住并自己记日志。
     */
    private void notifyTerminal() {
        try {
            onTerminal.accept(this);
        } catch (RuntimeException e) {
            LOG.error("回合已完成，但交付结果时失败（调用方可能等不到这次响应）: turnId={}", turnId, e);
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
     * 取回合标识。
     *
     * @return 回合标识；一条事件都没收到时为 {@code null}
     */
    public String turnId() {
        synchronized (lock) {
            return turnId;
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
