package zcd.jellyfish.template.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import zcd.jellyfish.core.conversation.ShellTurnEvent;

/**
 * 一帧 SSE 载荷：把内核的 {@link ShellTurnEvent} 投影成前端看得懂的小 JSON。
 * <p>
 * <b>为什么不把内核的事件对象直接推给前端</b>：它是内核的实现类型，字段随内核演化（内部字段改了、
 * 前端解析就断了），而且 {@code ERROR} 帧里带着原始异常对象——那不该出现在线上响应里。
 * 投影一层，多花几行，换来一个自己说了算的接口契约。
 * <p>
 * 字段按 {@code kind} 取值，用不到的一律为 {@code null} 且<b>不进 JSON</b>：
 * <ul>
 *     <li>{@code STARTED}：只有 {@code kind}；</li>
 *     <li>{@code TEXT} / {@code THINKING}：{@code text} 是增量（追加，不是整体替换）；</li>
 *     <li>{@code TOOL_STARTED} / {@code TOOL_OUTPUT} / {@code TOOL_COMPLETED}：{@code toolName}，
 *     调用中含 {@code output} 与 {@code success}（本示例没装插件、没有工具，这几帧只是为将来留好位置）；</li>
 *     <li>{@code COMPLETED}：{@code rounds} 与 {@code notice}（{@code notice} 是内核的补充说明，
 *     例如「正文被输出上限截断」，它不是正文，前端应另起一行展示）；</li>
 *     <li>{@code BLOCKED}：{@code reason}；</li>
 *     <li>{@code ERROR}：{@code reason}（只有消息，没有异常对象）。</li>
 * </ul>
 * 终态帧额外带 {@code dropped}：<b>大于 0 表示本次流里丢了这么多帧</b>（客户端读得比模型写得慢），
 * 前端据此提示「本次输出可能不完整」。终态四帧（{@code COMPLETED} / {@code CANCELLED} /
 * {@code ERROR} / {@code BLOCKED}）互斥且只会来一条，收到即可收尾。
 * <p>
 * 不可变。
 *
 * @author zcd
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SseFrame {

    /** 帧种类，与 {@link ShellTurnEvent.Kind} 同名；任何一帧都不为 {@code null}。 */
    private final String kind;

    /** 正文 / 思考增量。 */
    private final String text;

    /** 工具名。 */
    private final String toolName;

    /** 工具输出片段或结果。 */
    private final String output;

    /** 工具是否成功。 */
    private final Boolean success;

    /** 收敛时的轮数。 */
    private final Integer rounds;

    /** 收敛时内核附带的提示。 */
    private final String notice;

    /** 被拦下或失败的原因。 */
    private final String reason;

    /** 本次流中被丢弃的帧数，仅终态帧非 {@code null}。 */
    private final Long dropped;

    /**
     * 构造帧。
     *
     * @param kind     帧种类
     * @param text     文本
     * @param toolName 工具名
     * @param output   工具输出
     * @param success  工具是否成功
     * @param rounds   轮数
     * @param notice   提示
     * @param reason   原因
     * @param dropped  丢弃帧数
     */
    private SseFrame(String kind, String text, String toolName, String output, Boolean success, Integer rounds,
                     String notice, String reason, Long dropped) {
        this.kind = kind;
        this.text = text;
        this.toolName = toolName;
        this.output = output;
        this.success = success;
        this.rounds = rounds;
        this.notice = notice;
        this.reason = reason;
        this.dropped = dropped;
    }

    /**
     * 把一条非终态事件投影成帧。
     *
     * @param event 内核事件，不可为 {@code null}
     * @return 帧，保证非 {@code null}
     */
    public static SseFrame of(ShellTurnEvent event) {
        ShellTurnEvent.Kind kind = event.getKind();
        // 只填这一种 kind 真的带得动的字段：内核的事件是「一个类 + 判别式」，取值方法在别的 kind 上
        // 返回的是缺省值（例如 isSuccess() 对 TEXT 帧恒为 false），照单全收就会推出一堆误导性的字段
        Boolean success = kind == ShellTurnEvent.Kind.TOOL_COMPLETED ? Boolean.valueOf(event.isSuccess()) : null;
        Integer rounds = kind == ShellTurnEvent.Kind.COMPLETED ? Integer.valueOf(event.getRounds()) : null;
        String notice = kind == ShellTurnEvent.Kind.COMPLETED ? event.getNotice() : null;
        String reason = kind == ShellTurnEvent.Kind.BLOCKED ? event.getReason() : errorTextOf(event);
        return new SseFrame(kind.name(), event.getText(), event.getToolName(), event.getOutput(), success,
                rounds, notice, reason, null);
    }

    /**
     * 取异常帧的原因文本（只要能读的消息，不带异常对象）。
     *
     * @param event 内核事件
     * @return 原因文本；不是异常帧、或异常没有消息时为 {@code null}
     */
    private static String errorTextOf(ShellTurnEvent event) {
        if (event.getKind() != ShellTurnEvent.Kind.ERROR || event.getError() == null) {
            return null;
        }
        Throwable error = event.getError();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /**
     * 把一条终态事件投影成帧，并带上本次流丢弃的帧数。
     *
     * @param event   内核事件，不可为 {@code null}
     * @param dropped 本次流中丢弃的帧数
     * @return 帧，保证非 {@code null}
     */
    public static SseFrame terminal(ShellTurnEvent event, long dropped) {
        SseFrame frame = of(event);
        return new SseFrame(frame.kind, frame.text, frame.toolName, frame.output, frame.success, frame.rounds,
                frame.notice, frame.reason, Long.valueOf(dropped));
    }

    /**
     * 取帧种类。
     *
     * @return 帧种类
     */
    public String getKind() {
        return kind;
    }

    /**
     * 取文本。
     *
     * @return 文本；本帧没有时为 {@code null}
     */
    public String getText() {
        return text;
    }

    /**
     * 取工具名。
     *
     * @return 工具名；本帧没有时为 {@code null}
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 取工具输出。
     *
     * @return 工具输出；本帧没有时为 {@code null}
     */
    public String getOutput() {
        return output;
    }

    /**
     * 取工具是否成功。
     *
     * @return 是否成功；本帧没有这个字段时为 {@code null}
     */
    public Boolean getSuccess() {
        return success;
    }

    /**
     * 取收敛轮数。
     *
     * @return 轮数；本帧没有这个字段时为 {@code null}
     */
    public Integer getRounds() {
        return rounds;
    }

    /**
     * 取收敛提示。
     *
     * @return 提示；没有时为 {@code null}
     */
    public String getNotice() {
        return notice;
    }

    /**
     * 取被拦下或失败的原因。
     *
     * @return 原因；没有时为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    /**
     * 取本次流中丢弃的帧数。
     *
     * @return 丢弃帧数；非终态帧为 {@code null}
     */
    public Long getDropped() {
        return dropped;
    }
}
