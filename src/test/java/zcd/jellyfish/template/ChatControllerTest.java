package zcd.jellyfish.template;

import zcd.jellyfish.core.conversation.Submission;
import zcd.jellyfish.core.conversation.ShellTurnEvent;
import zcd.jellyfish.spring.boot.user.JellyfishUserFacade;
import zcd.jellyfish.template.api.ChatReply;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatController} 的单元测试：同步接口的三条收尾路径与「没起回合」的状态码。
 * <p>
 * 这些决定都在控制器里，且大多是「两种情形看起来一样、处理却必须不同」的地方：超时那一刻刚好收敛的回合
 * 不该被取消、正常交付之后容器的完成回调不该被当成「客户端跑了」、不同的「没起回合」落点不该回同一个码。
 * 用 mock 的门面可以把它们逐个摆出来；真容器那一条路由 {@code TemplateSmokeTest} 覆盖。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatController 同步接口的收尾")
class ChatControllerTest {

    @Mock
    private JellyfishUserFacade jellyfish;

    /** 被测控制器：身份来源用固定值（本用例不验身份）。 */
    private ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController(jellyfish, () -> "alice");
    }

    @Test
    @DisplayName("超时那一刻刚好收敛：交付已经干完的活，既不取消也不回 504")
    void onTimeout_should_deliverResult_whenTurnJustConverged() {
        // Given：回合已经推出终态（收集器里有了终态），但交付还没来得及发生
        DeferredResult<ChatReply> result = new DeferredResult<ChatReply>();
        AtomicBoolean settled = new AtomicBoolean();
        TurnCollector collector = new TurnCollector(collected -> { });
        collector.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "答完了", 1, false, null));

        // When
        controller.onTimeout(result, settled, collector, "alice", "s1");

        // Then：白等一场，但活是干完的——客户端该收到结果，而不是 504
        ChatReply reply = (ChatReply) result.getResult();
        assertNotNull(reply, "应当交付正常结果");
        assertEquals("答完了", reply.getReply());
        assertEquals("COMPLETED", reply.getKind());
        verify(jellyfish, never()).cancel("alice", "s1");
        verify(jellyfish, never()).isTurnRunning("alice", "s1");
    }

    @Test
    @DisplayName("超时且回合确实还在跑：取消它并回 504（别让模型为一个没人等的答复继续烧 token）")
    void onTimeout_should_cancelAndFail_whenTurnStillRunning() {
        // Given：收集器里没有任何终态，且门面说这个回合还在跑
        DeferredResult<ChatReply> result = new DeferredResult<ChatReply>();
        AtomicBoolean settled = new AtomicBoolean();
        TurnCollector collector = new TurnCollector(collected -> { });
        when(jellyfish.isTurnRunning("alice", "s1")).thenReturn(true);

        // When
        controller.onTimeout(result, settled, collector, "alice", "s1");

        // Then
        verify(jellyfish).cancel("alice", "s1");
        Object error = result.getResult();
        assertTrue(error instanceof ResponseStatusException, "应当是一个状态异常：" + error);
        ResponseStatusException timeout = (ResponseStatusException) error;
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, timeout.getStatus());
        assertTrue(timeout.getReason().contains("流式"), "原因文本要指向流式接口：" + timeout.getReason());
    }

    @Test
    @DisplayName("已经交付过之后超时回调不再动结果（交付只能发生一次）")
    void onTimeout_should_doNothing_whenAlreadySettled() {
        // Given：终态先到、正常交付
        DeferredResult<ChatReply> result = new DeferredResult<ChatReply>();
        AtomicBoolean settled = new AtomicBoolean();
        TurnCollector collector = new TurnCollector(collected -> {
            ChatReply reply = new ChatReply();
            reply.setKind(collected.kind());
            result.setResult(reply);
        });
        collector.onTurnEvent(ShellTurnEvent.completed("s1", "t1", "正文", 1, false, null));
        settled.set(true);

        // When
        controller.onTimeout(result, settled, collector, "alice", "s1");

        // Then：结果还是那个正常结果，且没人去取消回合
        assertEquals("COMPLETED", ((ChatReply) result.getResult()).getKind());
        verify(jellyfish, never()).cancel("alice", "s1");
    }

    @Test
    @DisplayName("客户端不再等待（容器发现断开）时放行回合")
    void cancelIfNobodyWaits_should_cancel_whenNobodyWaits() {
        // Given：还没交付
        AtomicBoolean settled = new AtomicBoolean();

        // When
        controller.cancelIfNobodyWaits(settled, "alice", "s1", new IllegalStateException("连接被重置"));

        // Then
        verify(jellyfish).cancel("alice", "s1");
    }

    @Test
    @DisplayName("正常交付之后的完成回调不算「客户端跑了」——否则会把刚答完的回合取消掉")
    void cancelIfNobodyWaits_should_notCancel_whenAlreadySettled() {
        // Given：已经交付
        AtomicBoolean settled = new AtomicBoolean(true);

        // When
        controller.cancelIfNobodyWaits(settled, "alice", "s1", null);

        // Then
        verify(jellyfish, never()).cancel("alice", "s1");
    }

    @Test
    @DisplayName("没起回合时按落点分别回应：空输入 400、会话不存在 404（与「不是你的会话」同一个答复）")
    void requireStartedTurn_should_mapRejectReason_toDistinctStatus() {
        // When / Then：空输入是客户端写错了请求
        ResponseStatusException blank = assertThrows(ResponseStatusException.class,
                () -> ChatController.requireStartedTurn(
                        Submission.rejected("s1", Submission.RejectReason.BLANK_INPUT)));
        assertEquals(HttpStatus.BAD_REQUEST, blank.getStatus());

        // When / Then：会话不存在用 404——与越权那条路径同一个码，客户端没法靠状态码枚举会话 id
        ResponseStatusException noSession = assertThrows(ResponseStatusException.class,
                () -> ChatController.requireStartedTurn(
                        Submission.rejected(null, Submission.RejectReason.NO_SESSION)));
        assertEquals(HttpStatus.NOT_FOUND, noSession.getStatus());

        // When / Then：起了回合就放行
        ChatController.requireStartedTurn(Submission.turn("s1", "t1"));
    }

    @Test
    @DisplayName("流式接口拒掉「插件接管了输入」：那个结果它表达不了，指示改用同步接口")
    void requireStreamableSubmission_should_rejectHandledInput_withPointerToSyncEndpoint() {
        // When
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> ChatController.requireStreamableSubmission(Submission.handled("s1", "被某个插件接管")));

        // Then：不能让它挂在那儿（没有回合就没有终态帧，客户端会一直等）
        assertEquals(HttpStatus.CONFLICT, thrown.getStatus());
        assertTrue(thrown.getReason().contains("同步接口"));
    }

    @Test
    @DisplayName("同步接口把「插件接管了输入」当正常结果交付（200，kind=HANDLED_INPUT）")
    void chat_should_deliverHandledInput_whenPluginTookOver() {
        // Given：门面说输入被插件接过去了（没有回合）
        when(jellyfish.chat(org.mockito.ArgumentMatchers.eq("alice"),
                org.mockito.ArgumentMatchers.eq("s1"), org.mockito.ArgumentMatchers.eq("你好"),
                org.mockito.ArgumentMatchers.any(TurnCollector.class)))
                .thenReturn(Submission.handled("s1", "被某个插件接管"));

        // When
        DeferredResult<ChatReply> result = controller.chat("s1", request("你好"));

        // Then：当场交付，且不占着等待（不会等到 5 分钟超时）
        ChatReply reply = (ChatReply) result.getResult();
        assertNotNull(reply);
        assertEquals("HANDLED_INPUT", reply.getKind());
        assertEquals("被某个插件接管", reply.getNotice());
        assertNull(reply.getReply(), "没有回合就没有正文");
    }

    @Test
    @DisplayName("提问内容为空当场 400（不提交、不占额度）")
    void chat_should_rejectBlankMessage_whenMessageBlank() {
        // When / Then
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.chat("s1", request("   ")));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatus());
        verify(jellyfish, never()).chat(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(TurnCollector.class));
    }

    /**
     * 造一个请求体。
     *
     * @param message 提问内容
     * @return 请求体，保证非 {@code null}
     */
    private static zcd.jellyfish.template.api.ChatRequest request(String message) {
        zcd.jellyfish.template.api.ChatRequest request = new zcd.jellyfish.template.api.ChatRequest();
        request.setMessage(message);
        return request;
    }
}
