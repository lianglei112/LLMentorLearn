package cn.learn.llm.llmentor.entity;

import lombok.Data;

/**
 * @author lianglei
 * @version 1.0
 * @date 2026/4/15 19:40
 */
@Data
public class WeatherResponse {

    private String city;
    private String date;

    private String i;

    private String s;

    private String description;
    private double temperature;

    public WeatherResponse(String city, String date,String i,String s, String description, double temperature) {
        this.city = city;
        this.date = date;
        this.i = i;
        this.s = s;
        this.description = description;
        this.temperature = temperature;
    }
}
