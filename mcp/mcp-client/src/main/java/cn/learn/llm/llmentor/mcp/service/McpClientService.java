package cn.learn.llm.llmentor.mcp.service;

import com.alibaba.fastjson2.JSON;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author lianglei
 * @version 1.0
 * @date 2026/4/15 20:13
 */
@Slf4j
@Service
public class McpClientService {

    @Autowired
    private List<McpSyncClient> mcpSyncClients;

    @Autowired
    private OpenAiChatModel chatModel;

    private ChatClient chatClient;

    @Autowired
    private SyncMcpToolCallbackProvider toolCallbackProvider;


    /**
     * 智能体调用
     */
    public String chat(String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
    }

    /**
     * 直接调用mcp server
     */
    public McpSchema.CallToolResult callTool(String type) {
        String toolName = "getWeather";
        Map param = new HashMap();
        param.put("city", "北京");

        for (McpSyncClient client : mcpSyncClients) {
            McpSchema.Implementation clientInfo = client.getClientInfo();
            McpSchema.Implementation serverInfo = client.getServerInfo();
            log.info("clientInfo: {}", JSON.toJSONString(clientInfo));
            log.info("serverInfo: {}", JSON.toJSONString(serverInfo));
            try {
                if (clientInfo.title().contains(type)) {
                    log.info("开始调用mcp服务");
                    McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder().name(toolName).arguments(param).build();
                    McpSchema.CallToolResult result = client.callTool(request);
                    log.info("callTool result: {}", result);
                    return result;
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            log.info("====================================================");
        }
        return null;
    }

    @PostConstruct
    public void init() {
        ToolCallback[] toolCallbacks = toolCallbackProvider.getToolCallbacks();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(toolCallbacks)
                .build();
    }
}
