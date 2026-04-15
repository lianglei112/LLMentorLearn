package cn.learn.llm.llmentor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @author lianglei
 * @version 1.0
 * @date 2026/4/15 19:54
 */
@SpringBootApplication
@EnableScheduling
public class McpClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpClientApplication.class, args);
    }
}
