package zcd.jellyfish.template;

/**
 * 「这次请求是谁」的唯一出处。
 * <p>
 * <b>为什么要有这个接口</b>：内核没有「用户」这一维——{@code JellyfishUserFacade} 的每一条路径都以
 * {@code userId} 开头，而归属校验的正确性完全取决于「这个 userId 是怎么来的」。它若是从请求参数
 * 或请求头里取的，那就等于<b>把归属校验交给了调用方</b>：换一个自报的标识就能读到别人的会话。
 * 因此本示例把「身份从哪来」收成一个 Bean，控制器里没有任何身份参数——接进真实业务时，
 * <b>只需要换掉这一个 Bean</b>（从登录态、令牌或网关注入的身份里取）。
 * <p>
 * <b>实现必须是请求作用域的</b>：同一个进程要同时服务多个用户，身份不能存在字段里跨请求复用。
 *
 * @author zcd
 */
public interface CurrentUser {

    /**
     * 取当前请求的用户标识。
     *
     * @return 用户标识，保证非空白
     */
    String userId();
}
