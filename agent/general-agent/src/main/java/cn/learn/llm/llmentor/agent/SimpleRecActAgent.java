package cn.learn.llm.llmentor.agent;

import cn.learn.llm.llmentor.config.ChatModelConfig;
import cn.learn.llm.llmentor.tools.SearchService;
import cn.learn.llm.llmentor.tools.WeatherService;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;


import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * @author lianglei
 * @version 1.0
 * @date 2026/5/5 20:05
 */
@Slf4j
public class SimpleRecActAgent {

    private static final String REACT_SYSTEM_PROMPT = """
            # 角色
            你是一个严格的基于React架构（Reasoning-Act-Observation）的智能助手，会通过Reasoning -> Act(ToolCall) -> Observation
            的反复循环来逐步解决任务  ，你擅长使用工具帮我解决问题。
            
            你的工作流程是（极其重要）：
            思考：基于当前获得的信息进行推理和反思，明确下一步行动的目标。
            行动：用于表示需要调用的工具，每一步行动必须是以下两种之一：
              1、工具调用 [Function Calling]：根据任务需要，确定调用工具。
              2、Finish[答案]：得出明确答案后使用此操作，返回答案并终止任务。
            观察：记录前一步行动的结果。
            
            你可以进行多轮推理和检索，但必须严格按照上述格式进行操作，尤其是每一步“行动”只能使用上述两种类型之一。
            """;

    private final String name;

    private final ChatModel chatModel;

    private final String systemPrompt;

    private final List<ToolCallback> tools;

    private ChatClient chatClient;

    private int maxRounds;

    private ChatMemory chatMemory;

    public SimpleRecActAgent(String name, ChatMemory chatMemory, ChatModel chatModel, String systemPrompt, List<ToolCallback> tools, int maxRounds) {
        this.name = name;
        this.chatMemory = chatMemory;
        this.chatModel = chatModel;
        this.systemPrompt = systemPrompt;
        this.tools = tools;
        this.maxRounds = maxRounds;
        // 初始化ChatClient
        initChatClient();

        // 检查ChatClient是否初始化成功
        if (this.chatClient == null) {
            throw new IllegalArgumentException("ChatClient 初始化失败！");
        }
    }

    public String call(String question) {
        return callInternal(null, question);
    }

    public String call(String conversationId, String question) {
        return callInternal(conversationId, question);
    }

    private String callInternal(String conversationId, String question) {
        //1、创建对话上下文
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        boolean useMemory = StringUtils.isNotEmpty(conversationId) && chatMemory != null;

        //2、添加系统提示词
        messages.add(new SystemMessage(REACT_SYSTEM_PROMPT));
        messages.add(new SystemMessage(systemPrompt));

        //3、判断是否存在记忆
        if (useMemory) {
            List<Message> historyMemory = chatMemory.get(conversationId);
            if (!CollectionUtils.isEmpty(historyMemory)) {
                messages.addAll(historyMemory);
            }
        }

        messages.add(new UserMessage("<question>" + question + "</question>"));

        if (useMemory) {
            chatMemory.add(conversationId, new UserMessage(question));
        }

        //4、开始对话循环
        int round = 0;
        while (true) {
            round++;
            if (maxRounds > 0 && round >= maxRounds) {
                log.warn("达到最大轮数，终止对话");
                messages.add(new UserMessage("""
                        你已达到最大推理轮次限制。
                        请基于当前已有的上下文信息，
                        直接给出最终答案。
                        禁止再调用任何工具。
                        如果信息不完整，请合理总结和说明。
                        """));
                String finalText = chatClient.prompt().messages(messages).call().content();
                if (useMemory) {
                    chatMemory.add(conversationId, new AssistantMessage(finalText));
                }
                return finalText;
            }

            ChatClientResponse chatResponse = chatClient.prompt()
                    .messages(messages)
                    .call()
                    .chatClientResponse();

            String aiText = chatResponse.chatResponse().getResult().getOutput().getText();

            AssistantMessage.Builder builder = AssistantMessage.builder().content(aiText);

            //没有工具可以调用，直接返回答案
            if (!chatResponse.chatResponse().hasToolCalls()) {
                if (useMemory) {
                    chatMemory.add(conversationId, new AssistantMessage(aiText));
                }
                return aiText;
            }

            AssistantMessage assistantMessage = builder.toolCalls(
                    chatResponse.chatResponse()
                            .getResult()
                            .getOutput()
                            .getToolCalls()
            ).build();

            //5、有工具调用，执行工具调用
            messages.add(assistantMessage);

            chatResponse.chatResponse()
                    .getResult()
                    .getOutput()
                    .getToolCalls()
                    .forEach(toolCall -> {
                        String toolName = toolCall.name();
                        String argsJson = toolCall.arguments();

                        ToolCallback callback = findTool(toolName);
                        if (callback == null) {
                            addErrorToolResponse(messages, toolCall, "工具未找到：" + toolName);
                            return;
                        }


                        Object result;
                        try {
                            result = callback.call(argsJson);

                            ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                                    toolCall.id(),
                                    toolName,
                                    result.toString()
                            );

                            messages.add(ToolResponseMessage.builder()
                                    .responses(List.of(tr))
                                    .build());
                        } catch (Exception e) {
                            log.error("工具调用失败，工具名：" + toolName + "，错误信息：" + e.getMessage(), e);
                            addErrorToolResponse(messages, toolCall, "工具执行失败：" + e.getMessage());
                        }
                    });
        }
    }

    /**
     * 流式输出
     *
     * @param question
     * @return
     */
    public Flux<String> steam(String question) {
        return streamInternal(null, question);
    }

    public Flux<String> steam(String conversationId, String question) {
        return streamInternal(conversationId, question);
    }

    private Flux<String> streamInternal(String conversationId, String question) {
        //1、创建对话上下文
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());

        boolean useMemory = StringUtils.isNotEmpty(conversationId) && chatMemory != null;

        //2、加载历史记忆
        if (useMemory) {
            List<Message> history = chatMemory.get(conversationId);
            if (!CollectionUtils.isEmpty(history)) {
                messages.addAll(history);
            }
        }

        //3、添加系统提示词（仅新会话，防止重复）
        if (messages.isEmpty()) {
            messages.add(new SystemMessage(REACT_SYSTEM_PROMPT));
            messages.add(new SystemMessage(systemPrompt));
        }

        // 这里的标签是为了让大模型识别出这个是用户新提出的问题，和之前的对话区分开来，方便模型进行针对性的推理和工具调用
        messages.add(new UserMessage("<question>" + question + "</question>"));

        //4、添加当前问题到记忆中
        if (useMemory) {
            messages.add(new UserMessage(question));
        }

        //创建单播 sink，
        // many表示多元素发射器（一次性可以发送n个消息）、
        // unicast单播模式，只能绑定一个订阅者
        // onBackpressureBuffer表示当订阅者处理不过来时，会进行缓存，防止数据丢失
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        // 迭代轮次
        AtomicLong roundCounter = new AtomicLong(0);
        // 是否发送最终结果标记位
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);

        hasSentFinalResult.set(false);
        roundCounter.set(0);

        // 收集最终答案，存储memory，以及其他需要在流式场景下使用的状态信息
        StringBuffer finalAnswerBuffer = new StringBuffer();

        //启动一轮 ReAct 循环
        scheduleRound(messages, sink, roundCounter, hasSentFinalResult,
                finalAnswerBuffer, useMemory, conversationId);

        return sink.asFlux()
                .doOnNext(finalAnswerBuffer::append)     // 每发来一条消息，就存到缓冲区里（后端自己用）
                .doOnCancel(() -> hasSentFinalResult.set(true)); // 如果前端断开连接，标记结束
//                .doFinally(signalType -> {
//                    log.info("最终答案: {}", finalAnswerBuffer);
//                });
    }

    private void scheduleRound(List<Message> messages, Sinks.Many<String> sink,
                               AtomicLong roundCounter, AtomicBoolean hasSentFinalResult,
                               StringBuffer finalAnswerBuffer, boolean useMemory, String conversationId) {
        //轮次加1
        roundCounter.incrementAndGet();

        //创建本轮的状态容器
        RoundState state = new RoundState();

        chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())  //切换到后台线程池执行后续逻辑
                .doOnNext(chunk -> processChunk(chunk, sink, state)) //每收到一段 AI 返回的 chunk 片段就执行处理
                .doOnComplete(() -> finishRound(messages, sink, state, roundCounter,
                        hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId))  //AI 流式输出完全结束后执行收尾逻辑
                .doOnError(err -> {
                    if (!hasSentFinalResult.get()) {
                        hasSentFinalResult.set(true);
                        sink.tryEmitError(err);
                    }
                })
                .subscribe();
    }

    private void finishRound(List<Message> messages, Sinks.Many<String> sink,
                             RoundState state, AtomicLong roundCounter, AtomicBoolean hasSentFinalResult,
                             StringBuffer finalAnswerBuffer, boolean useMemory, String conversationId) {
        // 如果整轮都没有 tool_call，才是最终答案
        if (state.mode != RoundMode.TOOL_CALL) {
            String finalText = state.textBuffer.toString();
            sink.tryEmitComplete();
            hasSentFinalResult.set(true);

            if (useMemory) {
                chatMemory.add(conversationId, new AssistantMessage(finalText));
            }
            return;
        }

        AssistantMessage assistantMessage = AssistantMessage.builder()
                .toolCalls(state.toolCalls)
                .build();

        messages.add(assistantMessage);

        // 如果达到最大轮次限制，强制生成最终答案，结束流式输出
        if (maxRounds > 0 && roundCounter.get() >= maxRounds) {
            forceFinalStream(messages, sink, hasSentFinalResult);
            return;
        }

        // 执行工具并迭代进入下一轮
        executeToolCalls(state.toolCalls, messages, hasSentFinalResult, () -> {
            if (!hasSentFinalResult.get()) {
                scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId);
            }
        });
    }


    private void executeToolCalls(List<AssistantMessage.ToolCall> toolCalls, List<Message> messages,
                                  AtomicBoolean hasSentFinalResult, Runnable onComplete) {
        // 已完成的工具调用计数器
        AtomicInteger completedCount = new AtomicInteger(0);
        int totalToolCalls = toolCalls.size();

        for (AssistantMessage.ToolCall tc : toolCalls) {
            Schedulers.boundedElastic().schedule(() -> {
                if (hasSentFinalResult.get()) {
                    completeToolCall(completedCount, totalToolCalls, onComplete);
                    return;
                }

                String toolName = tc.name();
                String argsJson = tc.arguments();

                ToolCallback callback = findTool(toolName);
                if (callback == null) {
                    addErrorToolResponse(messages, tc, "工具未找到：" + toolName);
                    completeToolCall(completedCount, totalToolCalls, onComplete);
                    return;
                }


                Object result;
                try {
                    result = callback.call(argsJson);
                    String resultStr = Objects.toString(result, "");
                    //构建工具调用消息返回结果
                    ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                            tc.id(),
                            toolName,
                            resultStr
                    );
                    //工具调用返回结果添加到上下文
                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(tr))
                            .build());

                } catch (Exception e) {
                    addErrorToolResponse(messages, tc, "工具执行失败：" + e.getMessage());
                } finally {
                    completeToolCall(completedCount, totalToolCalls, onComplete);
                }

            });
        }


    }

    private void completeToolCall(AtomicInteger completedCount, int totalToolCalls, Runnable onComplete) {
        int current = completedCount.incrementAndGet();
        if (current >= totalToolCalls) {
            onComplete.run();
        }
    }

    /**
     * 强制生成最终答案，结束流式输出
     *
     * @param messages
     * @param sink
     * @param hasSentFinalResult
     */
    private void forceFinalStream(List<Message> messages, Sinks.Many<String> sink,
                                  AtomicBoolean hasSentFinalResult) {

        messages.add(new UserMessage("""
                你已达到最大推理轮次限制。
                请基于当前已有的上下文信息，
                直接给出最终答案。
                禁止再调用任何工具。
                如果信息不完整，请合理总结和说明。
                """));

        chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                        return;
                    }

                    String text = chunk.getResult()
                            .getOutput()
                            .getText();

                    if (text != null && !hasSentFinalResult.get()) {
                        sink.tryEmitNext(text);
                    }
                })
                .doOnComplete(() -> {
                    hasSentFinalResult.set(true);
                    sink.tryEmitComplete();
                })
                .doOnError(err -> {
                    hasSentFinalResult.set(true);
                    sink.tryEmitError(err);
                })
                .subscribe();
    }

    /**
     * ReAct 智能体流式响应处理器：实现 Reasoning-Act-Observation 循环的核心流控逻辑
     * <p>
     * 【ReAct 架构语义】
     * 该方法是 ReAct 架构在流式场景下的关键实现，严格遵循：
     * - Reasoning 阶段：模型生成的文本内容（思考过程）
     * - Act 阶段：模型生成的 tool_calls（行动指令）
     * - Observation 阶段：工具执行后的返回结果（后续处理）
     * <p>
     * 【状态机行为】
     * 采用简单的状态转换机制：
     * - 初始状态：UNKNOWN
     * - 检测到 tool_calls → 转换为 TOOL_CALL 模式（停止文本输出，收集工具调用）
     * - 无 tool_calls → 保持 FINAL_ANSWER 模式（持续文本流式输出）
     * <p>
     * 【关键特性】
     * - 即时性：首次检测到 tool_call 立即切换模式，避免延迟
     * - 容错性：严格的 null 检查（chunk/result/output）
     * - 内存安全：使用 StringBuffer 进行线程安全的文本累积
     * - 低开销：无复杂状态管理，适合高频流式调用
     * <p>
     * 【与 processChunkNoThink 的区别】
     * 本方法采用 "即时决策" 策略，不等待首块数据确认模式；
     * 而 processChunkNoThink 采用 "首块确认" 策略，更适合需要精确模式识别的场景。
     *
     * @param chunk 流式响应片段，包含模型生成的文本和/或工具调用
     * @param sink  Reactor Sinks.Many 通道，用于异步推送文本到客户端
     * @param state 当前 ReAct 轮次的状态容器，维护文本缓冲区和工具调用列表
     */
    private void processChunk(ChatResponse chunk, Sinks.Many<String> sink, RoundState state) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }

        Generation gen = chunk.getResult();
        String text = gen.getOutput().getText();
        List<AssistantMessage.ToolCall> toolCalls = gen.getOutput().getToolCalls();


        // 一旦发现 tool_call，立即进入 TOOL_CALL 模式
        if (toolCalls != null && !toolCalls.isEmpty()) {
            state.mode = RoundMode.TOOL_CALL;
            state.toolCalls.addAll(toolCalls);
            return;
        }

        // 还没出现 tool_call，发送并缓存文本
        if (text != null) {
            sink.tryEmitNext(text);
            state.textBuffer.append(text);
        }


    }

    /**
     * 处理流式响应中的单个 chunk
     * <p>
     * 【原理】流式响应中，模型输出被拆分为多个 chunk 逐步到达。
     * 每个 chunk 可能包含：
     * - 文本内容（getText()）：模型正在生成的文字
     * - 工具调用（getToolCalls()）：模型决定调用的工具（参数可能不完整）
     * - 两者都有（罕见，但需要处理）
     * <p>
     * 关键逻辑：
     * 1. 如果检测到 ToolCall，立即切换到 TOOL_CALL 模式，停止向用户推送文本
     * 因为工具调用过程中的文本通常是推理过程，不应展示给用户
     * 2. 如果没有 ToolCall，将文本实时推送给用户（流式输出的核心）
     *
     * @param chunk 流式响应的一个片段
     * @param sink  数据推送通道
     * @param state 本轮的状态容器
     */
    private void processChunkNoThink(ChatResponse chunk, Sinks.Many<String> sink, RoundState state) {
        //检验操作
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }

        Generation gen = chunk.getResult();
        String text = gen.getOutput().getText();
        List<AssistantMessage.ToolCall> toolCalls = gen.getOutput().getToolCalls();

        //第一块 chunk ： 决定模式
        if (!state.firstChunkHandled) {
            // 标记处理第一块数据流现在是已经处理过了
            state.firstChunkHandled = true;

            // 如果有工具调用，则标记为工具调用模式
            //TODO 但是有一些大模型会存在 think 过程的，这样判断会存在不准确
            if (toolCalls != null && !toolCalls.isEmpty()) {
                state.mode = RoundMode.TOOL_CALL;
                state.toolCalls.addAll(toolCalls);
                return;
            }
            //没走以上逻辑则标记为最终答案模式
            state.mode = RoundMode.FINAL_ANSWER;
            // 如果第一块数据流就有文本内容，直接推送给前端，实现流式输出
            if (text != null) {
                sink.tryEmitNext(text);
            }
            return;
        }

        //后续块：根据模式处理
        switch (state.mode) {
            case FINAL_ANSWER -> {
                //后续chunk如果是最终答案，手动推送前端，实现流式输出
                if (text != null) {
                    sink.tryEmitNext(text);
                }
            }
            case TOOL_CALL -> {
                //后续chunk如果是工具调用，累积到缓冲区，等待最终结果
                if (text != null) {
                    state.textBuffer.append(text);
                }
                // 收集工具调用信息
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    state.toolCalls.addAll(toolCalls);
                }
            }
        }
    }


    private void finishRoundNoThink(List<Message> messages, Sinks.Many<String> sink,
                                    RoundState state, AtomicLong roundCounter, AtomicBoolean hasSentFinalResult,
                                    StringBuffer finalAnswerBuffer, boolean useMemory, String conversationId) {
        //最终答案模式（processChunk里边已经完成了这一轮的流式输出，tryEmitComplete这里只需要告诉前端完成了）
        if (state.mode == RoundMode.FINAL_ANSWER) {
            String aiText = state.textBuffer.toString();
            sink.tryEmitComplete();
            hasSentFinalResult.set(true);

            if (useMemory) {
                chatMemory.add(conversationId, new AssistantMessage(aiText));
            }
            return;
        }

        //工具调用模式，将工具调用消息加入上下文，准备下一轮输入
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(state.textBuffer.toString())
                .toolCalls(state.toolCalls)
                .build();
        messages.add(assistantMessage);

        //判断是否到达最大轮次限制
        if (maxRounds > 0 && roundCounter.get() >= maxRounds) {
            log.info("达到最大轮次限制，结束对话");
            if (!hasSentFinalResult.get()) {
                // 强制生成最终答案，结束流式输出
                forceFinalStream(messages, sink, hasSentFinalResult);
            }
            return;
        }

        // 执行工具并迭代进入下一轮
        executeToolCalls(state.toolCalls, messages, hasSentFinalResult, () -> {
            if (!hasSentFinalResult.get()) {
                scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId);
            }
        });
    }


    private void addErrorToolResponse(List<Message> messages, AssistantMessage.ToolCall toolCall, String errMsg) {
        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                toolCall.id(),
                toolCall.name(),
                "{ \"error\": \"" + errMsg + "\" }"
        );
        messages.add(ToolResponseMessage.builder()
                .responses(List.of(tr))
                .build());
    }

    private ToolCallback findTool(String toolName) {
        return this.tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(toolName))
                .findFirst()
                .orElse(null);
    }


    public void initChatClient() {
        try {
            log.info("初始化 ChatClient...");

            ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(tools)
                    .internalToolExecutionEnabled(false)
                    .build();

            this.chatClient = ChatClient.builder(chatModel)
                    .defaultOptions(chatOptions)
                    .defaultToolCallbacks(tools)
                    .build();
        } catch (Exception e) {
            log.error("ChatClient 初始化失败：" + e.getMessage(), e);
            throw new RuntimeException("ChatClient 初始化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 运行模式：未知、最终答案、工具调用
     */
    public enum RoundMode {
        UNKNOWN,  // 未知模式，还未接收到足够信息判断
        FINAL_ANSWER,   // 最终答案模式，已接收到足够信息判断
        TOOL_CALL // 工具调用模式，已接收到工具调用请求
    }

    private static class RoundState {
        // 当前轮次状态
        RoundMode mode = RoundMode.UNKNOWN;
        // 第一块数据流是否已处理
        boolean firstChunkHandled = false;
        // 累积收集的数据
        StringBuffer textBuffer = new StringBuffer();
        // 累积收集的工具调用（tool_call信息）
        List<AssistantMessage.ToolCall> toolCalls = Collections.synchronizedList(new ArrayList<>());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private String name;
        private ChatModel chatModel;
        private List<ToolCallback> tools;
        private String systemPrompt = "";
        private int maxRounds;

        private ChatMemory chatMemory;

        /**
         * 设置对话记忆
         */
        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;  // 返回 this，支持链式调用
        }

        /**
         * 设置 Agent 名称
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置大模型实例
         */
        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        /**
         * 设置工具列表（可变参数版本）
         */
        public Builder tools(ToolCallback... tools) {
            this.tools = Arrays.asList(tools);  // 将可变参数转为 List
            return this;
        }

        /**
         * 设置工具列表（List 版本）
         */
        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools;
            return this;
        }

        /**
         * 设置系统提示词
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * 设置最大推理轮次
         */
        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public SimpleRecActAgent build() {
            if (chatModel == null) {
                throw new IllegalArgumentException("chatModel 不能为空！");
            }
            return new SimpleRecActAgent(name, chatMemory, chatModel, systemPrompt, tools, maxRounds);
        }
    }

    public static void main(String[] args) {

        //ReAct Agent 非流式输出
//        ChatModel chatModel = ChatModelConfig.getChatModel();
//
//        ToolCallback[] toolCallbacks = ToolCallbacks.from(new WeatherService(), new SearchService());
//
//        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();
//        SimpleRecActAgent actAgent = SimpleRecActAgent.builder()
//                .name("SimpleRecActAgent")
//                .chatModel(chatModel)
//                .chatMemory(chatMemory)
//                .tools(toolCallbacks)
//                .maxRounds(10)
//                .systemPrompt("You are a helpful assistant.")
//                .build();
//
//        String question = """
//                请你根据北京今天的天气、未来七天的天气趋势、以及上海今天的天气，并搜索北京天气的预警情况，生成一份不少于 600 字的综合分析报告。
//                """;
//
//        System.out.println(actAgent.call(question));

        ChatModel chatModel = ChatModelConfig.getChatModel();

        ToolCallback[] toolCallbacks = ToolCallbacks.from(new WeatherService(), new SearchService());

        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();

        SimpleRecActAgent actAgent = SimpleRecActAgent.builder()
                .name("simple-agent")
                .chatModel(chatModel)
                .chatMemory(chatMemory)
                .tools(toolCallbacks)
                .maxRounds(-1)
                .systemPrompt("You are a helpful assistant.")
                .build();

        String question = """
                请你根据北京今天的天气、未来七天的天气趋势、以及上海今天的天气，并搜索北京天气的预警情况，生成一份不少于 600 字的综合分析报告。
                """;

        actAgent.steam(question)
                .doOnNext(System.out::print)
                .doOnError(error -> System.err.println("\n出错: " + error.getMessage()))
                .doOnComplete(() -> System.out.println("\n\n=== 流式输出全部完成 ==="))
                .blockLast();

    }

}
