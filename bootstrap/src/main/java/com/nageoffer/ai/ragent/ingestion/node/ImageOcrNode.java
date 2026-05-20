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

package com.nageoffer.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.ingestion.domain.context.ImageResource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.ImageOcrSettings;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageOcrNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final LLMService llmService;

    @Override
    public String getNodeType() {
        return IngestionNodeType.IMAGE_OCR.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<ImageResource> images = context.getImageResources();
        if (images == null || images.isEmpty()) {
            return NodeResult.ok("无嵌入图片需要处理");
        }

        ImageOcrSettings settings = parseSettings(config.getSettings());
        if (!settings.isEnabled()) {
            return NodeResult.ok("图片OCR节点已禁用，跳过 " + images.size() + " 张图片");
        }

        Semaphore semaphore = new Semaphore(settings.getConcurrency());
        ExecutorService executor = Executors.newFixedThreadPool(settings.getConcurrency());

        try {
            List<CompletableFuture<ImageOcrResult>> futures = new ArrayList<>();
            for (ImageResource image : images) {
                CompletableFuture<ImageOcrResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        semaphore.acquire();
                        return recognizeImage(image, settings);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new ImageOcrResult(image.getImageId(), image.getPlaceholder(), null, e);
                    } finally {
                        semaphore.release();
                    }
                }, executor);
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            Map<String, String> ocrResults = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toMap(
                            ImageOcrResult::placeholder,
                            r -> r.text() != null ? r.text() : r.placeholder(),
                            (a, b) -> a
                    ));

            String text = StringUtils.hasText(context.getEnhancedText()) ? context.getEnhancedText() : context.getRawText();
            if (StringUtils.hasText(text)) {
                for (Map.Entry<String, String> entry : ocrResults.entrySet()) {
                    text = text.replace(entry.getKey(), entry.getValue());
                }
                context.setRawText(text);
                if (StringUtils.hasText(context.getEnhancedText())) {
                    context.setEnhancedText(text);
                }
            }

            long successCount = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(r -> r.text() != null)
                    .count();
            long failCount = images.size() - successCount;

            if (settings.isFailOnError() && failCount > 0) {
                String errorMsg = "图片识别失败 " + failCount + "/" + images.size() + " 张，中断分块";
                log.error(errorMsg);
                return NodeResult.fail(new RuntimeException(errorMsg));
            }

            String message = "图片识别完成：成功 " + successCount + " 张";
            if (failCount > 0) {
                message += "，失败 " + failCount + " 张（已保留占位符）";
            }
            return NodeResult.ok(message);
        } finally {
            executor.shutdownNow();
        }
    }

    private ImageOcrResult recognizeImage(ImageResource image, ImageOcrSettings settings) {
        try {
            String base64 = Base64.getEncoder().encodeToString(image.getImageData());

            String systemPrompt = StringUtils.hasText(settings.getSystemPrompt())
                    ? settings.getSystemPrompt()
                    : "请识别并提取图片中的所有文字和内容。";
            String userPrompt = StringUtils.hasText(settings.getUserPromptTemplate())
                    ? settings.getUserPromptTemplate()
                    : "请识别这张图片中的内容。";

            ChatMessage userMessage = ChatMessage.userWithImage(userPrompt, base64, image.getMimeType());

            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(systemPrompt),
                            userMessage
                    ))
                    .temperature(0.1)
                    .build();

            String response = llmService.chat(request, settings.getModelId());
            String cleaned = response != null ? response.trim() : null;

            if (StringUtils.hasText(cleaned)) {
                log.info("图片 {} 识别成功，文本长度: {}", image.getImageId(), cleaned.length());
            } else {
                log.warn("图片 {} 识别结果为空", image.getImageId());
            }

            return new ImageOcrResult(image.getImageId(), image.getPlaceholder(), cleaned, null);
        } catch (Exception e) {
            log.error("图片 {} 识别失败: {}", image.getImageId(), e.getMessage());
            return new ImageOcrResult(image.getImageId(), image.getPlaceholder(), null, e);
        }
    }

    private ImageOcrSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return ImageOcrSettings.builder().build();
        }
        return objectMapper.convertValue(node, ImageOcrSettings.class);
    }

    private record ImageOcrResult(String imageId, String placeholder, String text, Throwable error) {
    }
}
