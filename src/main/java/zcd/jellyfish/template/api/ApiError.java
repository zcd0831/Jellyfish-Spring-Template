package zcd.jellyfish.template.api;

/**
 * 错误响应体：一个字段，够用就好。
 * <p>
 * <b>刻意不把异常消息原样透出</b>：越权类异常的消息里带着 {@code userId} 与 {@code sessionId}，
 * 而「这个 id 存在但不是你的」正是我们不想告诉调用方的信息（见
 * {@code SessionAccessDeniedException}）。因此各接口只回一句固定的、对外安全的话。
 *
 * @author zcd
 */
public class ApiError {

    /** 给人看的说明。 */
    private String error;

    /**
     * 构造错误体。
     *
     * @param error 说明
     */
    public ApiError(String error) {
        this.error = error;
    }

    /**
     * 取说明。
     *
     * @return 说明
     */
    public String getError() {
        return error;
    }

    /**
     * 设置说明。
     *
     * @param error 说明
     */
    public void setError(String error) {
        this.error = error;
    }
}
