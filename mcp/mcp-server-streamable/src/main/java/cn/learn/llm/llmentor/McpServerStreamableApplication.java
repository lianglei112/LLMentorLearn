package cn.learn.llm.llmentor;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * @author lianglei
 * @version 1.0
 * @date 2026/4/15 17:20
 */
@SpringBootApplication
public class McpServerStreamableApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerStreamableApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider weatherTools(WeatherService weatherService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(weatherService)
                .build();
    }

}
