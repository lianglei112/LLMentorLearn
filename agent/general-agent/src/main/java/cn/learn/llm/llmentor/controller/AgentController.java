package cn.learn.llm.llmentor.controller;

import cn.learn.llm.llmentor.agent.SimpleRecActAgent;
import cn.learn.llm.llmentor.tools.SearchService;
import cn.learn.llm.llmentor.tools.WeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * @author lianglei
 * @version 1.0
 * @date 2026/5/7 20:59
 */
@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentController {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ChatMemory chatMemory;

    @GetMapping("/react")
    public Flux<String> reactAgentCall(@RequestParam("question") String question) {
        ToolCallback[] toolCallbacks = ToolCallbacks.from(new WeatherService(), new SearchService());

        SimpleRecActAgent actAgent = SimpleRecActAgent.builder()
                .name("SimpleRecActAgent")
                .chatModel(chatModel)
                .chatMemory(chatMemory)
                .tools(toolCallbacks)
                .maxRounds(10)
                .systemPrompt("You are a helpful assistant.")
                .build();

        return (Flux<String>) actAgent.steam(question)
                .doOnNext(System.out::println)
                .doOnComplete(() -> System.out.println("\n\n=== 流式输出全部完成 ==="));
    }

}
