package zcd.jellyfish.template.api;

/**
 * 同步提问接口的请求体。
 *
 * @author zcd
 */
public class ChatRequest {

    /** 用户输入。 */
    private String message;

    /**
     * 取用户输入。
     *
     * @return 用户输入
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置用户输入。
     *
     * @param message 用户输入
     */
    public void setMessage(String message) {
        this.message = message;
    }
}
