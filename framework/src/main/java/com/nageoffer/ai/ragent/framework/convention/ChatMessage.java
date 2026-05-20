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

package com.nageoffer.ai.ragent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话消息实体
 *
 * <p>
 * 用于统一抽象「大模型对话」中的一条消息，包含角色和消息内容：
 * <ul>
 *   <li>{@link Role#SYSTEM}：系统提示词，用于为大模型设定行为、规则</li>
 *   <li>{@link Role#USER}：用户输入消息</li>
 *   <li>{@link Role#ASSISTANT}：大模型（助手）回复内容</li>
 * </ul>
 * 该结构适合在不同模型/厂商之间做一层通用抽象
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL;

        public static Role fromString(String value) {
            for (Role role : Role.values()) {
                if (role.name().equalsIgnoreCase(value)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("无效的角色类型: " + value);
        }
    }

    private Role role;

    private String content;

    private String thinkingContent;

    private Integer thinkingDuration;

    private List<ContentPart> multiModalContent;

    private List<ToolCall> toolCalls;

    private String toolCallId;

    public ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content;
    }

    public boolean isMultiModal() {
        return multiModalContent != null && !multiModalContent.isEmpty();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentPart {

        private String type;

        private String text;

        private ImageUrl imageUrl;

        public static ContentPart ofText(String text) {
            return new ContentPart("text", text, null);
        }

        public static ContentPart ofImageUrl(String url) {
            return new ContentPart("image_url", null, new ImageUrl(url));
        }

        public static ContentPart ofImageBase64(String base64, String mimeType) {
            String dataUrl = "data:" + mimeType + ";base64," + base64;
            return new ContentPart("image_url", null, new ImageUrl(dataUrl));
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageUrl {

        private String url;
    }

    /**
     * 创建一条系统消息
     *
     * @param content 系统提示词内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#SYSTEM}
     */
    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    /**
     * 创建一条用户消息
     *
     * @param content 用户输入内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#USER}
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    /**
     * 创建一条助手消息
     *
     * @param content 助手回复内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    /**
     * 创建一条带思考内容的助手消息
     *
     * @param content         助手回复内容
     * @param thinkingContent 深度思考内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content, String thinkingContent) {
        return assistant(content, thinkingContent, null);
    }

    /**
     * 创建一条带思考内容和思考耗时的助手消息
     *
     * @param content          助手回复内容
     * @param thinkingContent  深度思考内容
     * @param thinkingDuration 深度思考耗时（秒）
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content, String thinkingContent, Integer thinkingDuration) {
        ChatMessage message = new ChatMessage(Role.ASSISTANT, content);
        message.setThinkingContent(thinkingContent);
        message.setThinkingDuration(thinkingDuration);
        return message;
    }

    public static ChatMessage userWithImage(String textPrompt, String base64Image, String imageMimeType) {
        ChatMessage msg = new ChatMessage(Role.USER, null);
        msg.setMultiModalContent(List.of(
                ContentPart.ofText(textPrompt),
                ContentPart.ofImageBase64(base64Image, imageMimeType)
        ));
        return msg;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCall {

        private String id;

        private String type;

        private FunctionCall function;

        public ToolCall(String id, String functionName, String arguments) {
            this.id = id;
            this.type = "function";
            this.function = new FunctionCall(functionName, arguments);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FunctionCall {

        private String name;

        private String arguments;
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        ChatMessage msg = new ChatMessage(Role.TOOL, content);
        msg.setToolCallId(toolCallId);
        return msg;
    }
}
