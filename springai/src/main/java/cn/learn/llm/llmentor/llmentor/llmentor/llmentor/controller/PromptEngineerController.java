package cn.learn.llm.llmentor.llmentor.llmentor.llmentor.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * @author lianglei
 * @version 1.0
 * @date 2026/4/10 22:18
 */
@RestController
@RequestMapping("/prompt/engineer")
public class PromptEngineerController implements InitializingBean {

    @Autowired
    private ChatModel chatModel;

    private ChatClient chatClient;

    @GetMapping("/role")
    public String role(String message) {
        return chatClient.prompt(message).call().content();
    }

    @GetMapping("/shot")
    public String chat2(String message) {
        return chatClient.prompt().system("""
                请你根据用户输入的问题做改写，主要有以下改写策略：
                1、改写其中的错别字。
                2、做内容精简，帮用户的一堆废话精简成简单的一句话
                可以参考以下实例：
                
                Input：ni好
                Output ：{"错别字改写":"你好","内容精简":""}
                
                Input：我今天心情不错，我想知道今天是什么天气才让我心情这么好的？
                Output ：{"错别字改写":"","内容精简":"今天是什么天气？"}
                
                """).user(message).call().content();

    }

    @GetMapping("/structureOutput")
    public String structureOutput(String message) {
        return chatClient.prompt("请你以json格式输出内容").system("你是一个有用的助手").user(message).call().content();
    }

    @GetMapping("/step")
    public Flux<String> step(@RequestParam(value = "message") String message, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");

        return chatClient.prompt("""
                    请输出json格式的故事概要和人名数量。请按照以下思考方式逐步进行，最终只输出json即可：
                        step 1-用一句话概括下面文本。
                        step 2-将摘要翻译成英语。
                        step 3-在英语摘要中列出每个人名。
                        step 4-输出一个 JSON 对象，其中包含以下键：english_summary，num_names。
                        最终输出：{"english_summary": "故事概要.", "num_names": 4}
                """).system("你是个ai").user(message).stream().content();
    }


    @Override
    public void afterPropertiesSet() throws Exception {
        chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是一个毒舌博主，说话很噎人，请根据用户问题，怼他")
                .build();
    }
}
