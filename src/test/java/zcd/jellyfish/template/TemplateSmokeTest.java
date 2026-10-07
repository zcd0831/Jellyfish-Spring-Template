package zcd.jellyfish.template;

import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.spring.boot.user.JellyfishUserFacade;
import zcd.jellyfish.template.api.SessionCreated;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 启动验证：真 Web 容器 + 真内核 + 真 HTTP，一次跑完「起得来、装配对、路由在、护栏生效」。
 * <p>
 * <b>它刻意不调模型</b>：那要花钱、要联网、还不稳定，属于「有 key 的时候自己手动跑一次」的事
 * （见 README 的「跑一遍」）。这里只验与模型无关、但一改就静默坏掉的那些性质：
 * <ul>
 *     <li>内核真的被拉起来了（否则所有接口都会在第一次请求时才暴露问题）；</li>
 *     <li>classpath 上那三份示例配置真的被读进来了（路径写错时接口照样响应，只是模型永远是空的）；</li>
 *     <li>三个路由都注册在 Web 层；</li>
 *     <li>门面护栏生效：别人的会话一律 404。</li>
 * </ul>
 *
 * @author zcd
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("示例应用启动验证")
class TemplateSmokeTest {

    /**
     * 本次测试专用的插件扫描目录。
     * <p>
     * 指到一个空临时目录，而不是 starter 缺省的 {@code ~/.jellyfish/plugins/}：单测不该去看开发机上
     * 真实存在的那些插件。本示例的启用名单是空的，所以这里放什么都不会被加载。
     */
    private static final Path PLUGIN_ROOT = createPluginRoot();

    @DynamicPropertySource
    static void jellyfishProperties(DynamicPropertyRegistry registry) {
        registry.add("jellyfish.plugins.roots", () -> PLUGIN_ROOT.toString());
    }

    /** 真 HTTP 客户端（随机端口）。 */
    @Autowired
    private TestRestTemplate rest;

    /** 用户门面，用来直接问内核状态。 */
    @Autowired
    private JellyfishUserFacade jellyfish;

    /** 模型索引，用来验证配置文件真的被读进来了。 */
    @Autowired
    private ModelManager models;

    /** 路由表，用来验证三个路由都在。 */
    @Autowired
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("内核起得来：示例配置被读进来，建会话可用")
    void context_should_bootstrapKernelAndCreateSession_whenApplicationStarts() {
        // 装配：classpath:jellyfish/models.json 里声明的模型必须真的在索引里
        assertNotNull(models.findProvider("deepseek"), "示例 models.json 的 provider 应当被读进来");
        assertNotNull(models.findModel("deepseek", "deepseek-chat"), "示例 models.json 的模型应当被读进来");
        assertNotNull(models.resolveDefault(), "默认模型应当解析得出来");

        // 接口：建会话
        ResponseEntity<SessionCreated> created =
                rest.postForEntity("/api/sessions?userId=smoke", null, SessionCreated.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().getSessionId()).isNotBlank();

        // 门面：这个会话确实归 smoke，且此刻没有在途回合
        String sessionId = created.getBody().getSessionId();
        assertThat(jellyfish.isTurnRunning("smoke", sessionId)).isFalse();
        assertThat(jellyfish.listSessions("smoke")).extracting("sessionId").contains(sessionId);
    }

    @Test
    @DisplayName("门面护栏生效：访问别人的会话一律 404，与「会话不存在」同一个答复")
    void chat_should_answerNotFound_whenSessionBelongsToAnotherUser() {
        // Given：alice 的会话
        String sessionId = rest.postForEntity("/api/sessions?userId=alice", null, SessionCreated.class)
                .getBody().getSessionId();

        // When：bob 拿这个 id 提问
        ResponseEntity<String> borrowed = rest.postForEntity("/api/sessions/" + sessionId + "/chat?userId=bob",
                new HttpEntity<Map<String, String>>(Collections.singletonMap("message", "你好")), String.class);

        // Then：404，且与「这个 id 根本不存在」答复相同——不给调用方枚举会话 id 的机会
        ResponseEntity<String> unknown = rest.postForEntity("/api/sessions/no-such-session/chat?userId=bob",
                new HttpEntity<Map<String, String>>(Collections.singletonMap("message", "你好")), String.class);
        assertThat(borrowed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(borrowed.getBody()).isEqualTo(unknown.getBody());
    }

    @Test
    @DisplayName("参数校验：message 为空当场 400（也就是说 chat 路由确实被解析到了）")
    void chat_should_rejectBlankMessage_whenMessageMissing() {
        String sessionId = rest.postForEntity("/api/sessions?userId=smoke", null, SessionCreated.class)
                .getBody().getSessionId();

        ResponseEntity<String> blank = rest.postForEntity("/api/sessions/" + sessionId + "/chat?userId=smoke",
                new HttpEntity<Map<String, String>>(Collections.singletonMap("message", "   ")), String.class);

        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("三个示例路由都已注册，且指向本示例的控制器")
    void routes_should_beRegistered_whenApplicationStarts() {
        // Given：路由表
        Map<RequestMappingInfo, HandlerMethod> handlers = mappings.getHandlerMethods();
        assertThat(handlers).isNotEmpty();

        // Then：三个路由都在，且 chat 那条的处理器就是本示例的控制器
        // （换个同名的别的东西顶上来也算错，那正是「接口还在，行为不是你的了」这种故障）
        Set<String> patterns = new HashSet<String>();
        HandlerMethod chatHandler = null;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlers.entrySet()) {
            Set<String> current = patternsOf(entry.getKey());
            patterns.addAll(current);
            if (current.contains("/api/sessions/{sessionId}/chat")) {
                chatHandler = entry.getValue();
            }
        }
        assertThat(patterns).contains("/api/sessions", "/api/sessions/{sessionId}/chat",
                "/api/sessions/{sessionId}/chat/stream");
        assertNotNull(chatHandler, "chat 路由应当有处理器方法");
        assertThat(chatHandler.getBeanType()).isEqualTo(ChatController.class);
    }

    /**
     * 取一条路由映射上的路径模式。
     * <p>
     * <b>两个条件都要看</b>：Spring 5.3 起路径有两种表示（{@code PathPattern} 与老的 Ant 风格字符串），
     * Boot 2.6+ 缺省走前者，此时 {@code getPatternsCondition()} 返回 {@code null}——
     * 只读它就会得到「一条路由都没有」的假象。
     *
     * @param info 路由映射，不可为 {@code null}
     * @return 路径模式集合，保证非 {@code null}
     */
    private static Set<String> patternsOf(RequestMappingInfo info) {
        Set<String> patterns = new HashSet<String>();
        if (info.getPatternsCondition() != null) {
            patterns.addAll(info.getPatternsCondition().getPatterns());
        }
        if (info.getPathPatternsCondition() != null) {
            for (PathPattern pattern : info.getPathPatternsCondition().getPatterns()) {
                patterns.add(pattern.getPatternString());
            }
        }
        return patterns;
    }

    /**
     * 建一个临时插件目录。
     *
     * @return 临时目录路径，保证存在
     */
    private static Path createPluginRoot() {
        try {
            return Files.createTempDirectory("jellyfish-template-plugins");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建临时插件目录", e);
        }
    }
}
