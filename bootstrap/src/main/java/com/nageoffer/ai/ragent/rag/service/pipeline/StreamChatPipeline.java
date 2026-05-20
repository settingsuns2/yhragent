/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.DefaultIntentClassifier;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.mcp.LLMMCPParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.FREE_CHAT_PROMPT_PATH;

/**
 * 流式对话流水线
 * <p>
 * 承载从 RAGChatServiceImpl 提取的业务编排逻辑：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 系统响应 / 检索 -> Prompt 组装 -> 流式输出
 * <p>
 * 流水线模式：通过私有方法 + boolean 返回值（handleXxx 返回 true 表示已处理并短路）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamChatPipeline {

    private final ConversationMemoryService memoryService;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final StreamTaskManager taskManager;
    private final DefaultIntentClassifier intentClassifier;
    private final MCPToolRegistry mcpToolRegistry;
    private final LLMMCPParameterExtractor mcpParameterExtractor;
    private final Gson gson = new Gson();

    /**
     * 执行流式对话管道
     * <p>
     * 路由策略：
     * 1. 先通过 Domain 根节点正则预路由判断用户问题是否可能属于知识库
     * 2. 正则命中 → 走意图树（知识库 RAG 流程）
     * 3. 正则未命中 → 走自由 Chat（工具调用流程）
     */
    public void execute(StreamChatContext ctx) {
        loadMemory(ctx);

        String matchedDomain = matchDomainByRegex(ctx.getQuestion());
        ctx.setMatchedDomainId(matchedDomain);

        if (matchedDomain != null) {
            IntentNode domainNode = findDomainById(matchedDomain);
            if (domainNode != null && domainNode.getKind() == IntentKind.MCP) {
                log.info("正则预路由命中 MCP Domain: {}, 走自由 Chat(ReAct) 流程", matchedDomain);
                executeFreeChatFlow(ctx);
            } else {
                log.info("正则预路由命中 KB Domain: {}, 走知识库流程", matchedDomain);
                executeKBFlow(ctx);
            }
        } else {
            log.info("正则预路由未命中任何 Domain, 走自由 Chat 流程");
            executeFreeChatFlow(ctx);
        }
    }

    // ==================== 正则预路由 ====================

    /**
     * 对用户问题做 Domain 级正则匹配
     * 遍历所有 Domain 根节点的 domainRegex 字段，返回第一个匹配的 Domain ID
     *
     * @param question 用户原始问题
     * @return 匹配到的 Domain 节点 ID，未匹配返回 null
     */
    private String matchDomainByRegex(String question) {
        if (StrUtil.isBlank(question)) {
            return null;
        }
        String lowerQuestion = question.toLowerCase();
        List<IntentNode> roots = intentClassifier.loadRoots();
        if (CollUtil.isEmpty(roots)) {
            log.warn("正则预路由: 意图树为空（缓存和数据库均无数据），无法进行正则匹配");
            return null;
        }
        log.info("正则预路由: 意图树根节点数={}, 问题={}", roots.size(), question);
        for (IntentNode root : roots) {
            String regex = StrUtil.isNotBlank(root.getDomainRegex())
                    ? root.getDomainRegex()
                    : root.getName();
            log.info("正则预路由: 检查 Domain [{}], domainRegex={}", root.getId(), regex);
            if (StrUtil.isNotBlank(regex)) {
                try {
                    Pattern pattern = Pattern.compile(regex.toLowerCase(), Pattern.CASE_INSENSITIVE);
                    if (pattern.matcher(lowerQuestion).find()) {
                        return root.getId();
                    }
                } catch (Exception e) {
                    log.warn("Domain [{}] 正则表达式编译失败: regex={}, error={}",
                            root.getId(), regex, e.getMessage());
                }
            }
        }
        return null;
    }

    // ==================== 知识库流程 ====================

    private void executeKBFlow(StreamChatContext ctx) {
        IntentNode matchedDomain = findDomainById(ctx.getMatchedDomainId());

        if (matchedDomain != null && !matchedDomain.hasChildren()) {
            log.info("Domain [{}] 无子节点，直接用 Domain 自身配置检索", ctx.getMatchedDomainId());
            RetrievalContext retrievalCtx = retrieveByDomainNode(ctx, matchedDomain);
            if (retrievalCtx.isEmpty()) {
                log.info("Domain [{}] 直接检索结果为空，降级到自由 Chat", ctx.getMatchedDomainId());
                ctx.setFallbackReason("KB_MISS");
                executeFreeChatFlow(ctx);
                return;
            }
            streamRagResponse(ctx, retrievalCtx);
            return;
        }

        rewriteQuery(ctx);
        resolveIntents(ctx);

        if (handleGuidance(ctx)) {
            return;
        }
        if (handleSystemOnly(ctx)) {
            return;
        }

        RetrievalContext retrievalCtx = retrieve(ctx);

        if (retrievalCtx.isEmpty()) {
            log.info("Domain [{}] 命中但检索结果为空，降级到自由 Chat", ctx.getMatchedDomainId());
            ctx.setFallbackReason("KB_MISS");
            executeFreeChatFlow(ctx);
            return;
        }

        streamRagResponse(ctx, retrievalCtx);
    }

    private IntentNode findDomainById(String domainId) {
        if (StrUtil.isBlank(domainId)) {
            return null;
        }
        List<IntentNode> roots = intentClassifier.loadRoots();
        if (CollUtil.isEmpty(roots)) {
            return null;
        }
        return roots.stream()
                .filter(r -> domainId.equals(r.getId()))
                .findFirst()
                .orElse(null);
    }

    private RetrievalContext retrieveByDomainNode(StreamChatContext ctx, IntentNode domainNode) {
        List<SubQuestionIntent> wrappedIntents = List.of(
                new SubQuestionIntent(ctx.getQuestion(), List.of(
                        new NodeScore(domainNode, 1.0)
                ))
        );
        ctx.setSubIntents(wrappedIntents);
        return retrievalEngine.retrieve(wrappedIntents, DEFAULT_TOP_K);
    }

    // ==================== 自由 Chat 流程 ====================

    private void executeFreeChatFlow(StreamChatContext ctx) {
        List<MCPTool> availableTools = mcpToolRegistry.listAllTools();

        if (CollUtil.isEmpty(availableTools)) {
            executeSimpleChat(ctx, null);
            return;
        }

        String[] selection = selectToolViaLLM(ctx, availableTools);
        String toolId = selection[0];
        String intentSummary = selection[1];

        if (StrUtil.isBlank(toolId) || "NONE".equalsIgnoreCase(toolId.trim())) {
            executeSimpleChat(ctx, null);
            return;
        }

        MCPTool selectedTool = availableTools.stream()
                .filter(t -> t.getToolId().equals(toolId.trim()))
                .findFirst()
                .orElse(null);

        if (selectedTool == null) {
            log.warn("LLM 选择的工具不存在: {}, 可用工具: {}", toolId,
                    availableTools.stream().map(MCPTool::getToolId).toList());
            executeSimpleChat(ctx, null);
            return;
        }

        log.info("LLM 选中工具: {}, 意图: {}", selectedTool.getToolId(), intentSummary);
        executeWithTool(ctx, selectedTool, intentSummary);
    }

    private String[] selectToolViaLLM(StreamChatContext ctx, List<MCPTool> tools) {
        StringBuilder toolList = new StringBuilder();
        for (MCPTool tool : tools) {
            toolList.append("- ").append(tool.getToolId()).append(": ");
            toolList.append(StrUtil.isNotBlank(tool.getDescription()) ? tool.getDescription() : "无描述");
            toolList.append("\n");
        }

        String systemPrompt = "你是一个工具路由助手。根据用户的问题，判断是否需要使用以下工具之一。\n" +
                "请用以下 JSON 格式回复（不要有任何其他文字）：\n" +
                "{\"tool\": \"工具ID\", \"intent\": \"用一句话描述用户想通过这个工具完成什么\"}\n" +
                "如果不需要任何工具，回复：{\"tool\": \"NONE\", \"intent\": \"\"}\n\n" +
                "可用工具列表：\n" + toolList;

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(ctx.getHistory())) {
            int historySize = ctx.getHistory().size();
            int start = Math.max(0, historySize - 4);
            messages.addAll(ctx.getHistory().subList(start, historySize));
        }
        messages.add(ChatMessage.user(ctx.getQuestion()));

        try {
            ChatRequest req = ChatRequest.builder()
                    .messages(messages)
                    .temperature(0.0D)
                    .topP(0.1D)
                    .thinking(false)
                    .build();
            String result = llmService.chat(req);
            String cleaned = result == null ? "" : result.trim();
            log.info("工具选择 LLM 响应: {}", cleaned);

            String jsonStr = cleaned;
            if (cleaned.startsWith("```")) {
                jsonStr = cleaned.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }

            JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
            String toolId = obj.has("tool") ? obj.get("tool").getAsString() : "NONE";
            String intent = obj.has("intent") ? obj.get("intent").getAsString() : "";
            return new String[]{toolId, intent};
        } catch (Exception e) {
            log.error("工具选择 LLM 调用失败", e);
            return new String[]{"NONE", ""};
        }
    }

    private static final int REACT_MAX_ROUNDS = 5;

    private void executeWithTool(StreamChatContext ctx, MCPTool tool, String intentSummary) {
        List<ReActStep> steps = new ArrayList<>();
        String[] currentToolIdHolder = {tool.getToolId()};
        List<MCPTool> availableTools = mcpToolRegistry.listAllTools();

        for (int round = 0; round < REACT_MAX_ROUNDS; round++) {
            String currentToolId = currentToolIdHolder[0];
            log.info("ReAct Round {}: toolId={}", round + 1, currentToolId);

            MCPTool currentTool = availableTools.stream()
                    .filter(t -> t.getToolId().equals(currentToolId))
                    .findFirst()
                    .orElse(null);

            if (currentTool == null) {
                steps.add(ReActStep.failure(currentToolId, "选择", "工具不存在: " + currentToolId));
                break;
            }

            String reactDecision = callReActLLM(ctx, currentTool, intentSummary, steps);
            log.info("ReAct Round {} LLM 决策: {}", round + 1, reactDecision);

            if ("FINISH".equalsIgnoreCase(reactDecision.trim())) {
                log.info("ReAct: LLM 判断任务完成，退出循环");
                break;
            }

            if (reactDecision.startsWith("SWITCH:")) {
                String newToolId = reactDecision.substring("SWITCH:".length()).trim();
                log.info("ReAct: 切换工具 {} -> {}", currentToolId, newToolId);
                currentToolIdHolder[0] = newToolId;
                continue;
            }

            Map<String, Object> params = parseReActParams(reactDecision);
            if (params.isEmpty()) {
                log.warn("ReAct: 无法解析参数，退出循环, raw={}", reactDecision);
                steps.add(ReActStep.failure(currentToolId, "参数解析", "无法解析: " + reactDecision));
                break;
            }

            String action = String.valueOf(params.getOrDefault("action", ""));
            ctx.getCallback().onContent("[正在执行 " + currentToolId + "(" + action + ")...]\n");

            MCPResponse mcpResponse = callToolSafely(currentToolId, params);
            if (mcpResponse != null && mcpResponse.isSuccess()) {
                String result = mcpResponse.getTextResult();
                steps.add(ReActStep.success(currentToolId, action, result));
                log.info("ReAct Round {} 工具调用成功, action={}, resultLength={}",
                        round + 1, action, result != null ? result.length() : 0);
            } else {
                String errorMsg = mcpResponse != null ? mcpResponse.getErrorMessage() : "调用失败";
                steps.add(ReActStep.failure(currentToolId, action, errorMsg));
                log.warn("ReAct Round {} 工具调用失败, action={}, error={}", round + 1, action, errorMsg);
            }
        }

        streamFinalReActResponse(ctx, steps, intentSummary);
    }

    private String callReActLLM(StreamChatContext ctx, MCPTool tool, String intentSummary, List<ReActStep> steps) {
        StringBuilder toolSchema = new StringBuilder();
        toolSchema.append("工具ID: ").append(tool.getToolId()).append("\n");
        toolSchema.append("功能: ").append(tool.getDescription()).append("\n");
        toolSchema.append("参数:\n");
        if (tool.getParameters() != null) {
            for (Map.Entry<String, MCPTool.ParameterDef> entry : tool.getParameters().entrySet()) {
                MCPTool.ParameterDef def = entry.getValue();
                toolSchema.append("  - ").append(entry.getKey())
                        .append(" (").append(def.getType())
                        .append(def.isRequired() ? ", 必填" : ", 可选")
                        .append("): ").append(def.getDescription());
                if (CollUtil.isNotEmpty(def.getEnumValues())) {
                    toolSchema.append(" [可选值: ").append(String.join("|", def.getEnumValues())).append("]");
                }
                toolSchema.append("\n");
            }
        }

        StringBuilder availableToolIds = new StringBuilder();
        List<MCPTool> allTools = mcpToolRegistry.listAllTools();
        for (MCPTool t : allTools) {
            availableToolIds.append("- ").append(t.getToolId()).append(": ").append(t.getDescription()).append("\n");
        }

        StringBuilder historySb = new StringBuilder();
        if (!steps.isEmpty()) {
            historySb.append("\n\n## 已执行的工具调用\n");
            for (int i = 0; i < steps.size(); i++) {
                historySb.append("第").append(i + 1).append("次: ").append(steps.get(i).toDescription()).append("\n\n");
            }
        }

        String systemPrompt = "你是一个工具调用决策器。你需要根据用户问题和已执行的工具结果，决定下一步操作。\n\n" +
                "## 当前工具定义\n" + toolSchema + "\n" +
                "## 所有可用工具\n" + availableToolIds + "\n" +
                "## 决策规则\n" +
                "1. 如果已有足够的工具结果来回答用户问题，回复: FINISH\n" +
                "2. 如果需要继续调用当前工具（比如先读后写），回复工具参数 JSON，例如: {\"action\":\"write\",\"path\":\"test.txt\",\"content\":\"hello\"}\n" +
                "3. 如果需要切换到其他工具，回复: SWITCH:工具ID\n\n" +
                "## 重要\n" +
                "- 对于 write/append 操作的 content 参数，你应该根据工具结果和用户意图生成具体内容，不要留空\n" +
                "- 如果之前的工具调用已读取了文件内容，你应该基于读取的内容生成修改后的完整内容\n" +
                "- 只输出 JSON 或 FINISH 或 SWITCH:xxx，不要有任何其他文字\n";

        if (StrUtil.isNotBlank(intentSummary)) {
            systemPrompt += "\n## 用户意图总结\n" + intentSummary + "\n";
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(ctx.getHistory())) {
            int historySize = ctx.getHistory().size();
            int start = Math.max(0, historySize - 4);
            messages.addAll(ctx.getHistory().subList(start, historySize));
        }

        String userMsg = "用户问题: " + ctx.getQuestion() + historySb;
        messages.add(ChatMessage.user(userMsg));

        try {
            ChatRequest req = ChatRequest.builder()
                    .messages(messages)
                    .temperature(0.1D)
                    .topP(0.3D)
                    .thinking(false)
                    .build();
            String result = llmService.chat(req);
            return result != null ? result.trim() : "FINISH";
        } catch (Exception e) {
            log.error("ReAct 决策 LLM 调用失败", e);
            return "FINISH";
        }
    }

    private Map<String, Object> parseReActParams(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Collections.emptyMap();
        }
        try {
            String jsonStr = raw;
            if (raw.startsWith("```")) {
                jsonStr = raw.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }
            JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
            Map<String, Object> result = new java.util.HashMap<>();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                JsonElement val = entry.getValue();
                if (val.isJsonPrimitive()) {
                    result.put(entry.getKey(), val.getAsString());
                } else if (val.isJsonNull()) {
                    // skip
                } else {
                    result.put(entry.getKey(), gson.fromJson(val, Object.class));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("ReAct 参数解析失败: {}", raw, e);
            return Collections.emptyMap();
        }
    }

    private void streamFinalReActResponse(StreamChatContext ctx, List<ReActStep> steps, String intentSummary) {
        String systemPrompt = buildFreeChatPrompt(ctx);

        StringBuilder toolResults = new StringBuilder();
        toolResults.append("# 工具调用执行记录\n\n");
        if (StrUtil.isNotBlank(intentSummary)) {
            toolResults.append("用户意图: ").append(intentSummary).append("\n\n");
        }
        for (int i = 0; i < steps.size(); i++) {
            toolResults.append("## 第").append(i + 1).append("次工具调用\n");
            toolResults.append(steps.get(i).toDescription()).append("\n\n");
        }
        toolResults.append("请基于以上工具执行结果，完整回答用户的问题。");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(ctx.getHistory())) {
            messages.addAll(ctx.getHistory());
        }
        messages.add(ChatMessage.user(ctx.getQuestion()));
        messages.add(ChatMessage.assistant("[系统已通过工具完成操作，以下是执行详情]"));
        messages.add(ChatMessage.system(toolResults.toString()));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .topP(0.9D)
                .thinking(ctx.isDeepThinking())
                .build();

        StreamCancellationHandle handle = llmService.streamChat(req, ctx.getCallback());
        taskManager.bindHandle(ctx.getTaskId(), handle);
    }

    private MCPResponse callToolSafely(String toolId, Map<String, Object> params) {
        try {
            MCPToolExecutor executor = mcpToolRegistry.getExecutor(toolId).orElse(null);
            if (executor == null) {
                return MCPResponse.error(toolId, "TOOL_NOT_FOUND", "工具不存在: " + toolId);
            }
            MCPRequest request = MCPRequest.builder()
                    .toolId(toolId)
                    .userQuestion("")
                    .parameters(params != null ? params : Collections.emptyMap())
                    .build();
            return executor.execute(request);
        } catch (Exception e) {
            log.error("MCP 工具调用异常, toolId: {}", toolId, e);
            return MCPResponse.error(toolId, "EXECUTION_ERROR", "工具调用异常: " + e.getMessage());
        }
    }

    private void executeSimpleChat(StreamChatContext ctx, String extraSystemPrompt) {
        String systemPrompt = buildFreeChatPrompt(ctx);
        if (StrUtil.isNotBlank(extraSystemPrompt)) {
            systemPrompt = systemPrompt + "\n\n" + extraSystemPrompt;
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(ctx.getHistory())) {
            messages.addAll(ctx.getHistory());
        }
        messages.add(ChatMessage.user(ctx.getQuestion()));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .topP(0.9D)
                .thinking(ctx.isDeepThinking())
                .build();

        StreamCancellationHandle handle = llmService.streamChat(req, ctx.getCallback());
        taskManager.bindHandle(ctx.getTaskId(), handle);
    }

    /**
     * 构建自由 Chat 的系统提示词
     * 根据降级原因动态拼接前缀提示，并追加 workspace 个性化配置
     */
    private String buildFreeChatPrompt(StreamChatContext ctx) {
        String basePrompt = promptTemplateLoader.load(FREE_CHAT_PROMPT_PATH);

        if ("KB_MISS".equals(ctx.getFallbackReason())) {
            String prefix = "【重要】知识库中暂未收录与用户问题相关的信息。" +
                    "请在回答开头明确说明这一点，然后基于你的通用知识和工具能力尽量帮助用户。\n\n";
            basePrompt = prefix + basePrompt;
        }
        return promptBuilder.buildFreeChatSystemPrompt(basePrompt);
    }

    // ==================== 流水线阶段 ====================

    private void loadMemory(StreamChatContext ctx) {
        List<ChatMessage> history = memoryService.loadAndAppend(
                ctx.getConversationId(),
                ctx.getUserId(),
                ChatMessage.user(ctx.getQuestion())
        );
        ctx.setHistory(history);
    }

    private void rewriteQuery(StreamChatContext ctx) {
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(ctx.getQuestion(), ctx.getHistory());
        ctx.setRewriteResult(rewriteResult);
    }

    private void resolveIntents(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = intentResolver.resolve(ctx.getRewriteResult());
        ctx.setSubIntents(subIntents);
    }

    private boolean handleGuidance(StreamChatContext ctx) {
        GuidanceDecision decision = guidanceService.detectAmbiguity(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getSubIntents()
        );
        if (!decision.isPrompt()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent(decision.getPrompt());
        callback.onComplete();
        return true;
    }

    private boolean handleSystemOnly(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = ctx.getSubIntents();
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        if (!allSystemOnly) {
            return false;
        }
        String customPrompt = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .map(ns -> ns.getNode().getPromptTemplate())
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
        StreamCancellationHandle handle = streamSystemResponse(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getHistory(),
                customPrompt,
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle);
        return true;
    }

    private RetrievalContext retrieve(StreamChatContext ctx) {
        return retrievalEngine.retrieve(ctx.getSubIntents(), DEFAULT_TOP_K);
    }

    private void streamRagResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(ctx.getSubIntents());

        StreamCancellationHandle handle = streamLLMResponse(
                ctx.getRewriteResult(),
                ctx.getQuestion(),
                retrievalCtx,
                mergedGroup,
                ctx.getHistory(),
                ctx.isDeepThinking(),
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle);
    }

    // ==================== LLM 响应 ====================

    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        String basePrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);
        String systemPrompt = promptBuilder.buildFreeChatSystemPrompt(basePrompt);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, String originalQuestion,
                                                       RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        String question = rewriteResult != null
                ? rewriteResult.rewrittenQuestion()
                : originalQuestion;
        PromptContext promptContext = PromptContext.builder()
                .question(question)
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                question,
                rewriteResult != null ? rewriteResult.subQuestions() : List.of()
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }
}
