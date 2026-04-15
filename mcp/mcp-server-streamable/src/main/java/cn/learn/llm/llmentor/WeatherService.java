package cn.learn.llm.llmentor;

import cn.learn.llm.llmentor.entity.WeatherRequest;
import cn.learn.llm.llmentor.entity.WeatherResponse;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * @author lianglei
 * @version 1.0
 * @date 2026/4/15 17:41
 */
@Slf4j
@Service
public class WeatherService {

    @Tool(name = "getWeather", description = "根据城市名称查询天气信息")
    public String getWeather(String city) {
        if (city == null) {
            return "请提供城市名称";
        }
        return switch (city) {
            case "北京" -> "北京: 晴, 25°C";
            case "上海" -> "上海: 多云, 22°C";
            case "深圳" -> "深圳: 小雨, 28°C";
            default -> city + ": 下雪, -20°C";
        };
    }

    @Tool(name = "query_weather_by_city&date",
            description = "根据城市和日期获取天气信息")
    public WeatherResponse queryWeather(WeatherRequest request) {
        log.info("query weather for city:{}", JSON.toJSONString(request));
        try {
            // 模拟调用api
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        double temp = Math.random() * 15 + 10;

        return new WeatherResponse(
                request.getCity(),
                request.getDate(),
                request.getI(),
                request.getS(),
                "晴朗，有微风",
                temp
        );
    }
}
