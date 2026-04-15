package cn.learn.llm.llmentor.entity;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * @author lianglei
 * @version 1.0
 * @date 2026/4/15 19:40
 */
@Data
public class WeatherRequest {
    @ToolParam(description = "城市")
    private String city;

    @ToolParam(description = "日期")
    private String date;

    @ToolParam(description = "区县")
    private String i;

    @ToolParam(description = "街道")
    private String s;
}