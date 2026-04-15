package cn.learn.llm.llmentor;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * @author lianglei
 * @version 1.0
 * @date 2026/4/14 20:40
 */
@SpringBootApplication
public class McpServerSseApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerSseApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider weatherTools(WeatherService weatherService) {
        // 自动扫描 WeatherService 中带有 @Tool 注解的方法
        return MethodToolCallbackProvider.builder().toolObjects(weatherService).build();
    }

}
