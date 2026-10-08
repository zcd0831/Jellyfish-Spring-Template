package zcd.jellyfish.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 流的断开发现测试——<b>必须用真容器</b>。
 * <p>
 * 要验的性质只有一条：<b>安静期里客户端走了，回合能被放行</b>。这是最容易漏的一种断开：
 * 模型在长调用或等着人工审批时一帧都不产生，于是「下一次写失败」可能几分钟都不来，
 * 而这几分钟里回合照样占着 react 线程、并发额度，并继续烧 token——产出的东西却没有去处。
 * <p>
 * 这条性质由容器行为（对端挂断时会不会通知、什么时候通知）与心跳共同决定，因此 mock 掉容器
 * 就等于把被测的性质抽走了。这里用一个裸 socket 连上去、读到响应头、然后直接关掉，
 * 复现的就是浏览器关标签页那一下。
 * <p>
 * 端点刻意<b>不碰模型</b>（那要花钱、要联网、还不稳定），也不推任何帧：它模拟的正是
 * 「安静期」，也就是写失败发现不了的那一段。
 *
 * @author zcd
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("SSE 客户端断开")
class SseDisconnectTest {

    /** 本用例用的心跳间隔：心跳是分钟级的，用例等不起，因此压到 500 毫秒。 */
    /**
     * 本用例用的心跳间隔：心跳是分钟级的，用例等不起，因此压到 500 毫秒。
     * <p>
     * <b>它同时是「心跳必需」这条结论的证据</b>：把这里改成 1 小时（等价于关掉心跳）再跑，
     * 同一条用例会红——断开后 10 秒内没有任何放行发生，也就是说 Tomcat 在「安静期里对端挂断」
     * 这件事上<b>不会</b>主动通知 Spring 的回调，只有下一次写才会发现。因此光注册回调不够。
     */
    private static final long HEARTBEAT_MILLIS = 500L;

    /** 等「放行」的上限：正常应当在一秒内，给足余量是为了不在慢机器上变成随机失败。 */
    private static final long RELEASE_TIMEOUT_SECONDS = 10L;

    /** 临时插件目录，与 {@link TemplateSmokeTest} 同一个理由：不碰使用者的真实目录。 */
    private static final Path PLUGIN_ROOT = createTempDir();

    /** 随机端口。 */
    @LocalServerPort
    private int port;

    /** 探测端点（本用例自己装的，不进产品代码）。 */
    @Autowired
    private QuietStreamController probe;

    @Test
    @DisplayName("安静期里客户端断开：回合在秒级内被放行，不用等到下一次写失败")
    void stream_should_release_turn_when_client_disconnects_during_quiet_period()
            throws IOException, InterruptedException {
        // Given：连上那个「一帧都不回」的流，读到响应头（证明请求真的进了这个端点）
        Socket socket = new Socket("localhost", port);
        try {
            socket.getOutputStream().write(("GET " + QuietStreamController.PATH + " HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            readResponseHead(socket);

            // When：客户端走了——浏览器关标签页、网络断了，都是这一下
            socket.close();
            long disconnectedAt = System.currentTimeMillis();

            // Then：回合必须被放行，且不能等到下一次写（这个流本来就不写数据帧）
            boolean released = probe.awaitRelease(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(released, "断开之后 " + RELEASE_TIMEOUT_SECONDS + " 秒内必须放行回合；"
                    + "否则模型会继续为一个没人看的答复烧 token（已等 "
                    + (System.currentTimeMillis() - disconnectedAt) + " 毫秒）");
        } finally {
            socket.close();
        }
    }

    /**
     * 读掉响应头。
     * <p>
     * <b>读不到也算过</b>：本用例要验的不是「响应头有没有下发」，而是这个连接真的接上了那个端点。
     * 有些容器要等到第一次写才刷响应头，那时这里会读到超时——那不是本用例要拦的问题，
     * 直接往下走（断开那一步才是被测动作）。
     *
     * @param socket 已连上的 socket
     * @throws IOException 连接层面出错时抛出
     */
    private static void readResponseHead(Socket socket) throws IOException {
        socket.setSoTimeout(3000);
        InputStream in = socket.getInputStream();
        int matched = 0;
        try {
            while (matched < 4) {
                int read = in.read();
                if (read < 0) {
                    return;
                }
                matched = read == "\r\n\r\n".charAt(matched) ? matched + 1 : 0;
            }
        } catch (SocketTimeoutException e) {
            // 头还没刷出来：不影响本用例，继续（见方法注释）
        }
    }

    /**
     * 建一个临时插件目录。
     *
     * @return 临时目录，保证存在
     */
    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("jellyfish-sse-disconnect");
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
     * 本用例的装配：只多一个探测端点，别的照旧（真内核、真 Web 容器）。
     *
     * @author zcd
     */
    @TestConfiguration
    static class ProbeConfig {

        /**
         * 装那个探测端点。
         *
         * @return 探测端点，保证非 {@code null}
         */
        @Bean
        QuietStreamController quietStreamController() {
            return new QuietStreamController();
        }
    }

    /**
     * 探测端点：开一条<b>不推任何帧</b>的 SSE 流，并记下「放行」有没有发生。
     * <p>
     * 它就是「模型正在长调用 / 等审批」那一段的替身：连接是活的、心跳在走，但一个数据帧都没有。
     *
     * @author zcd
     */
    @RestController
    static class QuietStreamController {

        /** 探测端点路径。 */
        static final String PATH = "/probe/quiet-stream";

        /** 放行计数：客户端断开时倒数到 0。 */
        private final CountDownLatch released = new CountDownLatch(1);

        /**
         * 开一条安静期的 SSE 流。
         *
         * @return SSE 响应
         */
        @GetMapping(value = PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter quietStream() {
            SseEmitter emitter = new SseEmitter(0L);
            SseTurnStream stream = new SseTurnStream("probe", emitter, released::countDown, HEARTBEAT_MILLIS);
            stream.start();
            return emitter;
        }

        /**
         * 等「放行」发生。
         *
         * @param timeout 等多久
         * @param unit    时间单位
         * @return 放行过返回 {@code true}
         * @throws InterruptedException 等待被中断时抛出
         */
        boolean awaitRelease(long timeout, TimeUnit unit) throws InterruptedException {
            return released.await(timeout, unit);
        }
    }
}
