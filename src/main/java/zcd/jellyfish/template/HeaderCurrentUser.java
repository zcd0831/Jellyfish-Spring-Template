package zcd.jellyfish.template;

import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 示例用的 {@link CurrentUser}：读请求头 {@code X-User-Id}，没有就落到 {@code demo}。
 * <p>
 * <b>它同样是「客户端自报」，不是身份认证</b>——只是把这件事<b>收敛到一处</b>并写在明面上：
 * 任何人都能改这个头，因此换一个头就能读到别人的会话。本示例没有登录体系，这只是让示例能同时
 * 演示「alice 的会话 bob 拿不到」这一幕。接进真实业务时把它换掉：
 *
 * <pre>
 * &#64;Bean
 * CurrentUser currentUser() {
 *     return () -&gt; SecurityContextHolder.getContext().getAuthentication().getName();
 * }
 * </pre>
 * <p>
 * 本类的 Bean 声明处带了 {@code @ConditionalOnMissingBean}，因此接入方自己声明一个就会顶掉它。
 * <p>
 * <b>读不到请求上下文时返回 {@code demo}</b>：这是给「没有 HTTP 请求的那些调用」准备的（例如你自己写的
 * 定时任务、健康检查探针）。那种场景下要么换成真正的身份来源，要么明确知道自己就是示例用户。
 *
 * @author zcd
 */
public final class HeaderCurrentUser implements CurrentUser {

    /** 承载用户标识的请求头名。 */
    public static final String USER_HEADER = "X-User-Id";

    /** 没有任何身份线索时用的标识：示例的单用户缺省。 */
    public static final String DEFAULT_USER = "demo";

    @Override
    public String userId() {
        String declared = currentHeader();
        return StringUtils.hasText(declared) ? declared : DEFAULT_USER;
    }

    /**
     * 取当前 HTTP 请求上那个身份头。
     *
     * @return 头值；不在 HTTP 请求线程上、或没带这个头时返回 {@code null}
     */
    private static String currentHeader() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes)) {
            // 非 HTTP 请求线程（定时任务、启动期调用）：没有「这次请求是谁」这回事
            return null;
        }
        return ((ServletRequestAttributes) attributes).getRequest().getHeader(USER_HEADER);
    }
}
