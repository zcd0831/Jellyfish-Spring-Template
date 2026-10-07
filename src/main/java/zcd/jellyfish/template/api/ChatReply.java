package zcd.jellyfish.template.api;

/**
 * 同步提问接口的响应体：一次回合收敛后的结果。
 * <p>
 * <b>{@code notice} 不是正文</b>：它是内核给这次收敛补的一句话（例如「正文被输出上限截断」、
 * 「模型没有返回任何内容」）。它与「模型说了什么」是两件事，因此不拼进 {@code reply}。
 *
 * @author zcd
 */
public class ChatReply {

    /** 会话标识。 */
    private String sessionId;

    /** 回合标识。 */
    private String turnId;

    /** 模型这一回合说的话。 */
    private String reply;

    /** 内核补的提示。 */
    private String notice;

    /** 终态落点：{@code COMPLETED} / {@code CANCELLED} / {@code ERROR} / {@code BLOCKED}。 */
    private String kind;

    /** 终态原因：被拦下或失败时的说明，正常收敛时为 {@code null}。 */
    private String reason;

    /** 收敛时的轮数。 */
    private int rounds;

    /**
     * 取会话标识。
     *
     * @return 会话标识
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 设置会话标识。
     *
     * @param sessionId 会话标识
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 取回合标识。
     *
     * @return 回合标识
     */
    public String getTurnId() {
        return turnId;
    }

    /**
     * 设置回合标识。
     *
     * @param turnId 回合标识
     */
    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    /**
     * 取回答正文。
     *
     * @return 回答正文
     */
    public String getReply() {
        return reply;
    }

    /**
     * 设置回答正文。
     *
     * @param reply 回答正文
     */
    public void setReply(String reply) {
        this.reply = reply;
    }

    /**
     * 取内核补的提示。
     *
     * @return 提示；没有时为 {@code null}
     */
    public String getNotice() {
        return notice;
    }

    /**
     * 设置内核补的提示。
     *
     * @param notice 提示
     */
    public void setNotice(String notice) {
        this.notice = notice;
    }

    /**
     * 取终态落点。
     *
     * @return 终态落点
     */
    public String getKind() {
        return kind;
    }

    /**
     * 设置终态落点。
     *
     * @param kind 终态落点
     */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /**
     * 取终态原因。
     *
     * @return 原因；正常收敛时为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    /**
     * 设置终态原因。
     *
     * @param reason 原因
     */
    public void setReason(String reason) {
        this.reason = reason;
    }

    /**
     * 取收敛轮数。
     *
     * @return 轮数
     */
    public int getRounds() {
        return rounds;
    }

    /**
     * 设置收敛轮数。
     *
     * @param rounds 轮数
     */
    public void setRounds(int rounds) {
        this.rounds = rounds;
    }
}
