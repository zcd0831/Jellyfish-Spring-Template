package zcd.jellyfish.template;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.core.conversation.TurnInProgressException;
import zcd.jellyfish.spring.boot.user.CommandNotAllowedException;
import zcd.jellyfish.spring.boot.user.SessionAccessDeniedException;
import zcd.jellyfish.spring.boot.user.UserQuotaExceededException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiExceptionHandler} 的映射测试——<b>走真容器</b>。
 * <p>
 * 为什么不能只调 handler 方法：那样只证明「这个方法返回 403」，不证明 Spring 会把那个异常路由到它。
 * 而这次要拦的正是「漏了一条映射」——漏掉的表现是异常落进兜底的 500，调用方以为服务端坏了、
 * 去重试，而正确的动作是换一条请求。因此这里让探针端点真的抛出来，看客户端拿到什么。
 * <p>
 * 探针端点只在本用例的上下文里存在（{@link ProbeConfig}），不进产品代码。
 *
 * @author zcd
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("异常到状态码的映射")
class ApiExceptionMappingTest {

    /** 临时插件目录，与其它用例同一个理由：不碰使用者的真实目录。 */
    private static final Path PLUGIN_ROOT = createTempDir();

    /** 哪些响应体不该出现的内容（路径、类名、异常消息这类服务端细节）。 */
    private static final Pattern LEAKY = Pattern.compile("/Users/|[A-Za-z]+\\.java|Exception|at zcd\\.");

    /** 真 HTTP 客户端（随机端口）。 */
    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("越权与会话不存在回同一个 404（不让调用方靠状态码枚举会话 id）")
    void accessDenied_should_answerNotFound() {
        // When
        ResponseEntity<String> response = rest.exchange("/probe/throw/access-denied", org.springframework.http.HttpMethod.PUT,
                null, String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("命令被拒回 403 而不是 500（这是客户端错误，重试没用）")
    void commandNotAllowed_should_answerForbidden_notServerError() {
        // When
        ResponseEntity<String> response = rest.exchange("/probe/throw/command-not-allowed", org.springframework.http.HttpMethod.PUT,
                null, String.class);

        // Then：403 与内核外壳一致（ServerCommandGate 同样回 403 COMMAND_NOT_ALLOWED）
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("不该由终端用户执行");
    }

    @Test
    @DisplayName("每用户额度用尽回 429（而进程级容量用满是内核外壳的 503，两者不合并）")
    void quotaExceeded_should_answerTooManyRequests() {
        // When
        ResponseEntity<String> response = rest.exchange("/probe/throw/quota", org.springframework.http.HttpMethod.PUT,
                null, String.class);

        // Then：429 是「你自己跑太多、退避重试」，503 才是「服务端没容量」
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("同会话已有在途回合回 409")
    void turnInProgress_should_answerConflict() {
        // When
        ResponseEntity<String> response = rest.exchange("/probe/throw/turn-in-progress", org.springframework.http.HttpMethod.PUT,
                null, String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("没映射过的内核异常回 500，但正文里没有异常原文（路径、类名、消息都不透出）")
    void unmappedJellyfishException_should_answerGenericServerError() {
        // When
        ResponseEntity<String> response = rest.exchange("/probe/throw/other", org.springframework.http.HttpMethod.PUT,
                null, String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("服务端处理失败");
        assertThat(response.getBody()).doesNotContain("/Users/");
        assertThat(LEAKY.matcher(response.getBody()).find())
                .as("响应体不该带出服务端细节：%s", response.getBody())
                .isFalse();
    }

    /**
     * 建一个临时插件目录。
     *
     * @return 临时目录，保证存在
     */
    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("jellyfish-mapping");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建临时插件目录", e);
        }
    }

    /**
     * 把插件扫描目录指到临时目录。
     *
     * @param registry 属性注册表
     */
    @DynamicPropertySource
    static void jellyfishProperties(DynamicPropertyRegistry registry) {
        registry.add("jellyfish.plugins.roots", () -> PLUGIN_ROOT.toString());
    }

    /**
     * 本用例的装配：只多一个探针端点。
     *
     * @author zcd
     */
    @TestConfiguration
    static class ProbeConfig {

        /**
         * 装那个探针端点。
         *
         * @return 探针端点，保证非 {@code null}
         */
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    /**
     * 探针端点：按路径抛出任一种被映射的异常，用来验证「异常真的被路由到了对应的处理办法」。
     * <p>
     * 它刻意用 {@code PUT}：本示例的三个真实接口用的是 {@code POST} / {@code GET}，
     * 换一个动词就不会有「探针挡住了真实路由」这种可能。
     *
     * @author zcd
     */
    @RestController
    static class ProbeController {

        /**
         * 抛出指定的异常。
         *
         * @param kind 异常种类
         */
        @PutMapping("/probe/throw/{kind}")
        void throwIt(@PathVariable String kind) {
            switch (kind) {
                case "access-denied":
                    throw new SessionAccessDeniedException("alice", "s1");
                case "command-not-allowed":
                    throw new CommandNotAllowedException("new", "这个命令不该由终端用户执行");
                case "quota":
                    throw new UserQuotaExceededException("alice", "并发对话已达上限");
                case "turn-in-progress":
                    throw new TurnInProgressException("s1");
                default:
                    // 没映射过的内核异常：验证兜底只给一句安全说明，不带服务端细节
                    throw new JellyfishException("内部细节：/Users/someone/secret.json 打不开");
            }
        }
    }
}
