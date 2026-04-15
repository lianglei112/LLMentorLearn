package cn.learn.llm.llmentor.llmentor.llmentor.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * @author lianglei
 * @version 1.0
 * @date 2026/4/10 10:23
 */
@RestController
@RequestMapping("/stream")
public class StreamController {

    private static final String API_KEY = "sk-db77de3431244a77b75940239d143f3f";
    private static final String API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    @RequestMapping("/fakeStream")
    public String fakeStream() {

        String requestBody = """
                {
                    "model": "qwen-plus",
                    "messages": [
                        {
                            "role": "system",
                            "content": "You are a helpful assistant."
                        },
                        {
                            "role": "user",
                            "content": "你好，介绍下当前AI大模型开发的行业趋势，以及现在是否值得入坑？"
                        }
                    ],
                    "stream": false
                }
                """;

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .header("X-DashScope-SSE", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
        HttpResponse<String> response = null;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        return response.body();
    }

    /**
     * 通过SseEmitter实现流式输出
     *
     * @return
     */
    @GetMapping("/sse")
    public SseEmitter sse() {
        // 创建 SseEmitter，超时时间：30分钟
        SseEmitter emitter = new SseEmitter(60_000L);
        //创建线程池，异步进行发送数据
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try {
                for (int i = 0; i < 1000; i++) {
                    emitter.send("Message " + i);
                    Thread.sleep(1000); // 模拟每秒发送一条消息
                }
            } catch (IOException | InterruptedException e) {
                emitter.completeWithError(e); // 发生错误时完成并返回错误
            } finally {
                emitter.complete(); // 完成发送
            }
        });
        return emitter;
    }

    /**
     * 通过ResponseEntity + StreamingResponseBody 实现流式输出
     *
     * @return
     */
    @GetMapping("/entity")
    public ResponseEntity<StreamingResponseBody> chat() {

        StreamingResponseBody body = outputStream -> {
            for (int i = 0; i < 1000; i++) {
                String message = "Message " + i + "\n";
                outputStream.write(message.getBytes(StandardCharsets.UTF_8));
                outputStream.flush(); // 刷新输出流，确保数据立即发送
                try {
                    Thread.sleep(500); // 模拟每0.5秒发送一条消息
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .body(body);
    }

    /**
     * 通过Spring WebFlux的Flux实现流式输出  后期项目当中采用的比较多  额外增加换行（实现流式输出） ServerSentEvent
     *
     * @return
     */
    @GetMapping(value = "/flux")
    public Flux<ServerSentEvent<String>> fluxStream() {
        return Flux.interval(Duration.ofSeconds(1))
                .map(seq -> ServerSentEvent.builder("Message " + seq).build());
    }

}
