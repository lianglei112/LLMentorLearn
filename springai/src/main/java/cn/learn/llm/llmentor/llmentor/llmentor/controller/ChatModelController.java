package cn.learn.llm.llmentor.llmentor.llmentor.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;


/**
 * @author lianglei
 * @version 1.0
 * @date 2026/4/10 18:07
 */
@RestController
@RequestMapping("/model")
public class ChatModelController {

    @Autowired
    private DashScopeChatModel dashScopeChatModel;

    @RequestMapping("/call/string")
    public String callString(String message) {
        return dashScopeChatModel.call(message);
    }

    @RequestMapping("/call/messages")
    public String callMessages(String message) {
        SystemMessage systemMessage = new SystemMessage("你是一个翻译工具，请把用户的消息翻译成英文");
        Message userMsg = new UserMessage(message);
        return dashScopeChatModel.call(systemMessage, userMsg);
    }

    @RequestMapping("/call/prompt")
    public String callPrompt(String message) {
        SystemMessage systemMessage = new SystemMessage("请如实回答我的问题");
        Message userMsg = new UserMessage(message);

        ChatOptions chatOptions = ChatOptions.builder().model("deepseek-v3").build();
        Prompt prompt = new Prompt.Builder().messages(systemMessage, userMsg).chatOptions(chatOptions)
                .build();
        return dashScopeChatModel.call(prompt).getResult().getOutput().getText();
    }

    /**
     * 流式输出
     *
     * @param message
     * @param response
     * @return
     */
    @RequestMapping("/stream/string")
    public Flux<String> callStreamString(String message, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return dashScopeChatModel.stream(message);

    }
}
