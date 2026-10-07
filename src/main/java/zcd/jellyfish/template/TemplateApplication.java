package zcd.jellyfish.template;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例应用的入口。
 * <p>
 * <b>这里没有任何一句「接上 Jellyfish」的代码</b>：starter 通过 {@code META-INF/spring.factories} 注册
 * 自动配置，只要它在这条 classpath 上、且 {@code jellyfish.enabled} 不为 {@code false}，内核就会被装配，
 * 并在容器启动时自动拉起（{@code jellyfish.lifecycle.bootstrap-on-start}）。
 * <p>
 * 想关掉整个内核：{@code jellyfish.enabled=false}。那时连对象图都不会被构造——「不启用」是零成本，
 * 不是「装好了但不用」。
 *
 * @author zcd
 */
@SpringBootApplication
public class TemplateApplication {

    /**
     * 启动应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(TemplateApplication.class, args);
    }
}
