package com.apeloa.agent.web.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 测试用最小 SSE 客户端：真 HTTP 读原始事件流，逐帧断言 id / event / data——这样
 * Last-Event-ID 重连、帧字段顺序、注释心跳都按浏览器 EventSource 的实际口径验证，
 * 而不是只验证控制器返回值。
 */
final class SseTestClient implements AutoCloseable {

    /** 流结束哨兵：让等帧的用例快速失败而不是干等超时。 */
    private static final String END = "__sse_stream_end__";

    private final HttpClient client =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final InputStream body;
    private final HttpResponse<InputStream> response;

    /** 一个 SSE 帧（握手帧无 id，故 id 可为 null）。 */
    record Frame(String id, String event, String data) {
    }

    SseTestClient(String url, String userId, String lastEventId)
            throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .header("Accept", "text/event-stream")
                        .header("X-User-Id", userId);
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        // 服务端首帧（session_info）落地即 flush，响应头很快到达，不会在这里干等。
        response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        body = response.body();
        Thread reader = new Thread(this::readLoop, "sse-test-reader");
        reader.setDaemon(true);
        reader.start();
    }

    HttpResponse<InputStream> response() {
        return response;
    }

    /** 等下一个事件帧，跳过注释心跳帧。 */
    Frame nextFrame(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String id = null;
        String event = null;
        StringBuilder data = new StringBuilder();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("等不到下一个 SSE 帧（超时）");
            }
            String line = lines.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null) {
                throw new AssertionError("等不到下一个 SSE 帧（超时）");
            }
            if (END.equals(line)) {
                throw new AssertionError("SSE 流已结束，收不到期望的帧");
            }
            if (line.isEmpty()) {
                if (id != null || event != null || !data.isEmpty()) {
                    return new Frame(id, event, data.toString());
                }
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("id:")) {
                id = line.substring(3);
            } else if (line.startsWith("event:")) {
                event = line.substring(6);
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5));
            }
        }
    }

    private void readLoop() {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            // 客户端主动关流或服务端收尾：正常路径。
        } finally {
            lines.add(END);
        }
    }

    /** 模拟浏览器断线：直接掐掉连接，服务端下次写才会察觉。 */
    @Override
    public void close() {
        try {
            body.close();
        } catch (IOException e) {
            // 忽略：已断开
        }
        client.close();
    }
}
