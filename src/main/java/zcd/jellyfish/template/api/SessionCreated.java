package zcd.jellyfish.template.api;

/**
 * 建会话接口的响应体。
 *
 * @author zcd
 */
public class SessionCreated {

    /** 新会话标识。 */
    private String sessionId;

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
}
