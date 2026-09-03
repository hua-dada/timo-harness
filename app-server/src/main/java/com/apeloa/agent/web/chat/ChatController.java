package com.apeloa.agent.web.chat;

import com.apeloa.agent.chat.AgentSession;
import com.apeloa.agent.chat.AgentSessionManager;
import com.apeloa.agent.chat.ChatDelta;
import com.apeloa.agent.chat.SessionEventBuffer.Sequenced;
import com.apeloa.agent.chat.SessionSubscription;
import com.apeloa.agent.chat.SessionSummary;
import com.apeloa.agent.web.auth.CurrentUserProvider;
import com.apeloa.agent.web.auth.UnauthenticatedException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * 聊天 REST + SSE 路由（M1-6）。源项目走 WS 双向，这里拆成「上行 POST + 下行 SSE」：
 * 建会话 / 列会话 / SSE 下行 / 发消息 / 中止。
 *
 * <p><b>SSE 契约</b>（前端 lib/sse.ts 按此接）：
 * <ul>
 *   <li>每次连接先发 session_info + thinking_level，<b>不带 id</b>——SSE 规范下无 id 字段的帧
 *       不推进客户端 Last-Event-ID，握手帧因此不会污染续传游标。</li>
 *   <li>messages_loaded 只在全量重建时发（无 Last-Event-Id 或已超出缓冲窗）：它在源 reducer 里
 *       会<b>整体替换</b> messages 并把 streaming 置 false，窗内续传发它会抹掉刚补发的增量。</li>
 *   <li>其后每条事件带 id=seq（会话内单调），断线重连由浏览器自动带回 Last-Event-ID，
 *       服务端从 seq+1 补发。</li>
 *   <li>空闲时每 15 秒发一条注释帧保活（穿代理、探测断开）。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：每连接一根虚拟线程做唯一写者，从 {@link SessionSubscription} 队列取事件后
 * 写帧——补发在 HTTP 线程、实时事件在 Reactor 线程，若都直接写 emitter 会乱序。队列积压
 * （{@link SessionSubscription#overflowed()}）即结束本连接，让客户端带 Last-Event-Id 重连补发。
 */
@RestController
@RequestMapping("/api/sessions")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 0 = 不设 async 超时：长连接靠心跳保活，断开由客户端重连补发。 */
    private static final long NO_TIMEOUT = 0L;

    /** 心跳间隔，同时也是写线程发现连接被作废（驱逐/关闭）的最长延迟。 */
    private static final long HEARTBEAT_MILLIS = 15_000;

    /** 显式带 charset：MockMvc 与代理才不会按 ISO-8859-1 解码中文事件体。 */
    private static final MediaType EVENT_STREAM =
            new MediaType("text", "event-stream", StandardCharsets.UTF_8);

    private final AgentSessionManager sessions;
    private final CurrentUserProvider currentUser;

    /** 每 SSE 连接一根虚拟线程（ADR#4 已全局开虚拟线程，这里独立持有便于随 bean 收尾）。 */
    private final ExecutorService pumps = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(AgentSessionManager sessions, CurrentUserProvider currentUser) {
        this.sessions = sessions;
        this.currentUser = currentUser;
    }

    /** 建会话请求体（name 可省，默认「新会话」）。 */
    public record CreateSessionRequest(String name) {
    }

    /** 会话概要。 */
    public record SessionResponse(String sessionId, String name, String state, long createdAt) {
        static SessionResponse of(SessionSummary summary) {
            return new SessionResponse(
                    summary.sessionId(), summary.name(), summary.state(), summary.createdAt());
        }
    }

    public record SessionListResponse(List<SessionResponse> sessions) {
    }

    /**
     * 上行 prompt。images 与 streamingBehavior 为兼容源前端载荷保留：M1-6 忽略（多模态与流式
     * 档位无对应 AgentScope 能力，M2 再议），不报错以免前端改字段。
     */
    public record MessageRequest(String message, List<String> images, String streamingBehavior) {
    }

    public record OkResponse(boolean ok) {
    }

    @PostMapping
    public SessionResponse create(@RequestBody(required = false) CreateSessionRequest body) {
        String name = body == null ? null : body.name();
        AgentSession session = sessions.create(requireUserId(), name);
        return new SessionResponse(
                session.sessionId(),
                session.name(),
                session.state().name().toLowerCase(Locale.ROOT),
                session.createdAt());
    }

    @GetMapping
    public SessionListResponse list() {
        return new SessionListResponse(
                sessions.list(requireUserId()).stream().map(SessionResponse::of).toList());
    }

    @PostMapping("/{sessionId}/messages")
    public OkResponse send(@PathVariable String sessionId, @RequestBody MessageRequest body) {
        AgentSession session = sessions.require(requireUserId(), sessionId);
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new BadChatRequestException("message 不能为空");
        }
        if (body.images() != null && !body.images().isEmpty()) {
            log.debug("会话 {} 忽略 {} 张图片附件（M1-6 未接多模态）", sessionId, body.images().size());
        }
        session.send(body.message());
        return new OkResponse(true);
    }

    /** 中止：在跑就打断，等确认就按全部拒绝续跑（否则挂起态会卡住下一条 prompt）。 */
    @PostMapping("/{sessionId}/abort")
    public OkResponse abort(@PathVariable String sessionId) {
        sessions.require(requireUserId(), sessionId).abort();
        return new OkResponse(true);
    }

    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(
            @PathVariable String sessionId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        AgentSession session = sessions.require(requireUserId(), sessionId);
        SessionSubscription subscription = session.subscribe(parseLastEventId(lastEventId));
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        // 客户端断开 / 超时 / 出错都只置标志，由写线程下次 poll 超时后自行收尾。
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(error -> subscription.close());
        try {
            pumps.execute(() -> pump(emitter, session, subscription));
        } catch (RuntimeException e) {
            subscription.close();
            throw e;
        }
        return ResponseEntity.ok()
                .contentType(EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                // 关掉 nginx 缓冲，否则事件会被攒着不下发。
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    /** 唯一写者：握手 → 补发/实时事件 → 心跳，直到连接被作废。 */
    private void pump(SseEmitter emitter, AgentSession session, SessionSubscription subscription) {
        try (subscription) {
            send(emitter, null, session.sessionInfo());
            send(emitter, null, session.thinkingLevel());
            if (subscription.fullRebuild()) {
                // M1-11 起为 DB 真实历史（session_entries 投影），随后只补 seq > 落盘点的缓冲尾巴。
                send(emitter, null, ChatDelta.MessagesLoaded.of(subscription.rebuildHistory()));
            }
            while (!subscription.closed()) {
                Sequenced event = subscription.poll(HEARTBEAT_MILLIS);
                if (event == null) {
                    emitter.send(SseEmitter.event().comment("hb"));
                } else {
                    send(emitter, event.seq(), event.delta());
                }
            }
            if (subscription.overflowed()) {
                log.info("SSE 连接积压作废，等重连补发：session={}", session.sessionId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException e) {
            // 客户端断开（写失败）或响应已由容器收尾：正常路径，缓冲里的事件等重连补发。
            log.debug("SSE 连接结束：session={} 原因={}", session.sessionId(), e.toString());
        } catch (RuntimeException e) {
            log.warn("SSE 下发失败：session={}", session.sessionId(), e);
        } finally {
            complete(emitter);
        }
    }

    /** seq 为 null 即握手帧（不带 id）。id/event 必须先于 data 写入：帧内字段按调用顺序落。 */
    private void send(SseEmitter emitter, Long seq, ChatDelta delta) throws IOException {
        SseEventBuilder frame = SseEmitter.event();
        if (seq != null) {
            frame.id(Long.toString(seq));
        }
        frame.name(delta.type()).data(delta, MediaType.APPLICATION_JSON);
        emitter.send(frame);
    }

    private static void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("SSE 收尾忽略：{}", e.toString());
        }
    }

    /** 非法 Last-Event-Id 按缺失处理（走全量重建），不因客户端脏数据 400。 */
    private static Long parseLastEventId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            log.debug("忽略非法 Last-Event-Id：{}", raw);
            return null;
        }
    }

    private String requireUserId() {
        String userId = currentUser.currentUserId();
        if (userId == null) {
            throw new UnauthenticatedException();
        }
        return userId;
    }

    @PreDestroy
    void shutdown() {
        pumps.shutdownNow();
    }
}
