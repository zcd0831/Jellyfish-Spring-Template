package zcd.jellyfish.template;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.core.conversation.TurnInProgressException;
import zcd.jellyfish.spring.boot.user.CommandNotAllowedException;
import zcd.jellyfish.spring.boot.user.SessionAccessDeniedException;
import zcd.jellyfish.spring.boot.user.UserQuotaExceededException;
import zcd.jellyfish.template.api.ApiError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 把内核与门面的异常翻成 HTTP 状态码。
 * <p>
 * <b>为什么单独一层，而不是在每个接口里 try/catch</b>：这几种失败的语义是跨接口一致的，写在一处才不会
 * 「这个接口回 404、那个接口回 500」。尤其第一条：
 * <p>
 * <b>「会话不存在」与「不是你的会话」必须回同一个答复</b>。区分开来（404 / 403）等于给了调用方一个
 * 枚举别人会话 id 的工具——他能靠状态码的差异问出「这个 id 真的存在」。门面内部已经把这两种情况合并成
 * 一个异常（{@code SessionAccessDeniedException}），这里只负责别再把它拆开。
 * <p>
 * <b>异常消息不原样透出</b>：越权异常的消息里带着 {@code userId} 与 {@code sessionId}，那是服务端信息；
 * 兜底的 500 也一样——内核异常的原文可能含文件路径、配置片段。原文进日志，响应只给一句安全的说明。
 * <p>
 * <b>每一条都必须显式映射</b>：{@code CommandNotAllowedException} 早先漏了一条，于是「这个命令不该由
 * 终端用户执行」这种<b>客户端错误</b>落进了兜底的 500——调用方看到 500 会以为服务端坏了、去重试，
 * 而正确的动作是换一条请求。状态码在这里不只是分类，它决定了客户端接下来做什么。
 * <p>
 * <b>「这个用户跑太多」与「服务端没容量了」是两个码，不合并</b>：前者是 429（按用户计，客户端退避重试
 * 即可），后者是内核外壳的 503 {@code TOO_MANY_STREAMS}（进程级 {@code maxStreams} 用满，
 * 该做的是扩容或降并发）。把它们统一成一个码，会把「你等等」和「服务端撑不住」混成同一件事。
 *
 * @author zcd
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 会话不存在或不属于该用户。
     *
     * @return 404，且**两种情况答复完全相同**
     */
    @ExceptionHandler(SessionAccessDeniedException.class)
    public ResponseEntity<ApiError> onAccessDenied() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("会话不存在，或不属于该用户"));
    }

    /**
     * 该用户的并发对话已到上限。
     * <p>
     * 429 而不是 403：这是「现在不行、等会儿行」，客户端按 429 重试是对的。
     * 门面是**立即拒绝**而不是排队，因此不会出现「请求卡住直到额度腾出来」的假死现象。
     * <p>
     * 与内核外壳「进程级流许可用满」的 503 是两回事，见类注释。
     *
     * @return 429
     */
    @ExceptionHandler(UserQuotaExceededException.class)
    public ResponseEntity<ApiError> onQuotaExceeded() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiError("该用户的并发对话已达上限，请等上一轮结束后重试"));
    }

    /**
     * 命令被本层拒绝。
     * <p>
     * 403 与内核外壳一致（{@code ServerCommandGate} 同样回 403 {@code COMMAND_NOT_ALLOWED}）：
     * 命令本身是合法的，是<b>这一层</b>不允许终端用户执行它（它会改进程级状态、或按参数操作别人的会话，
     * 那些理由写在 {@link CommandNotAllowedException} 的 javadoc 里）。
     *
     * @return 403
     */
    @ExceptionHandler(CommandNotAllowedException.class)
    public ResponseEntity<ApiError> onCommandNotAllowed(CommandNotAllowedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(e.getMessage()));
    }

    /**
     * 同一会话已有在途回合。
     *
     * @return 409
     */
    @ExceptionHandler(TurnInProgressException.class)
    public ResponseEntity<ApiError> onTurnInProgress() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("这个会话已经有一个回合在跑；可先取消再提问"));
    }

    /**
     * 控制器自己抛的用法错误（参数为空、没起回合、同步等待超时）。
     *
     * @param e 异常，携带状态与前因
     * @return 对应状态
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> onResponseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getReason()));
    }

    /**
     * 内核异常兜底。
     *
     * @param e 异常
     * @return 500，正文只有一句安全说明
     */
    @ExceptionHandler(JellyfishException.class)
    public ResponseEntity<ApiError> onJellyfishException(JellyfishException e) {
        LOG.error("内核调用失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("服务端处理失败，请查看服务端日志"));
    }
}
