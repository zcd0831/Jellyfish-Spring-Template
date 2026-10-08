package zcd.jellyfish.template;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 身份来源的装配：本示例给一个能跑的替身，接入方换掉它就完事。
 * <p>
 * <b>为什么是 {@code @ConditionalOnMissingBean}</b>：这是本示例里唯一一个「接入方应当替换」的 Bean。
 * 其它 Bean（门面、控制器）替换掉不会更安全，只会换出一套自己的行为；而身份来源天然属于业务——
 * 身份从登录态、令牌还是网关注入的头里取，只有接入方知道。声明成「没有才给」之后，接入方
 * <b>只需声明一个自己的 {@link CurrentUser}</b>，示例的那份自动让位，不用改控制器。
 *
 * @author zcd
 */
@Configuration(proxyBeanMethods = false)
public class CurrentUserConfiguration {

    /**
     * 提供示例用的身份来源。
     *
     * @return 读请求头 {@code X-User-Id} 的身份来源，保证非 {@code null}
     */
    @Bean
    @ConditionalOnMissingBean(CurrentUser.class)
    public CurrentUser currentUser() {
        return new HeaderCurrentUser();
    }
}
