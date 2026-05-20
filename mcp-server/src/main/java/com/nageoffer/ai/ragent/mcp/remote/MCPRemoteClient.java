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

package com.nageoffer.ai.ragent.mcp.remote;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MCPRemoteClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private volatile boolean initialized;
    private volatile List<Map<String, Object>> cachedTools;

    public MCPRemoteClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("protocolVersion", "2026-02-28");

        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        params.put("capabilities", capabilities);

        Map<String, Object> clientInfo = new HashMap<>();
        clientInfo.put("name", "ragent-mcp-client");
        clientInfo.put("version", "0.0.1");
        params.put("clientInfo", clientInfo);

        Map<String, Object> request = buildRequest(1, "initialize", params);
        Map<String, Object> response = sendRequest(request);

        if (response != null && !response.containsKey("error")) {
            initialized = true;
            log.info("MCP 远程客户端初始化成功: {}", baseUrl);
            sendNotification("notifications/initialized", null);
        } else {
            log.error("MCP 远程客户端初始化失败: {}", baseUrl);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTools() {
        if (cachedTools != null) {
            return cachedTools;
        }
        ensureInitialized();
        Map<String, Object> request = buildRequest(2, "tools/list", null);
        Map<String, Object> response = sendRequest(request);
        if (response != null && response.get("result") instanceof Map<?, ?> result) {
            Object toolsObj = result.get("tools");
            if (toolsObj instanceof List<?> toolsList) {
                cachedTools = new ArrayList<>();
                for (Object item : toolsList) {
                    if (item instanceof Map<?, ?> toolMap) {
                        Map<String, Object> tool = new HashMap<>((Map<String, Object>) item);
                        cachedTools.add(tool);
                    }
                }
                return cachedTools;
            }
        }
        return List.of();
    }

    public String callTool(String toolName, Map<String, Object> arguments) {
        ensureInitialized();
        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Map.of());

        Map<String, Object> request = buildRequest(System.nanoTime(), "tools/call", params);
        Map<String, Object> response = sendRequest(request);

        if (response == null) {
            return "远程 MCP 服务调用失败: 无响应";
        }

        if (response.get("error") instanceof Map<?, ?> error) {
            String message = error.get("message") != null ? error.get("message").toString() : "未知错误";
            return "远程 MCP 服务调用失败: " + message;
        }

        if (response.get("result") instanceof Map<?, ?> result) {
            Object contentObj = result.get("content");
            if (contentObj instanceof List<?> contentList) {
                StringBuilder sb = new StringBuilder();
                for (Object item : contentList) {
                    if (item instanceof Map<?, ?> contentItem) {
                        if ("text".equals(contentItem.get("type")) && contentItem.get("text") != null) {
                            if (!sb.isEmpty()) {
                                sb.append("\n");
                            }
                            sb.append(contentItem.get("text").toString());
                        }
                    }
                }
                return sb.toString();
            }
        }
        return GSON.toJson(response.get("result"));
    }

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    private void sendNotification(String method, Map<String, Object> params) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.put("params", params);
        }

        String json = GSON.toJson(notification);
        RequestBody body = RequestBody.create(json, JSON_TYPE);
        Request httpRequest = new Request.Builder()
                .url(baseUrl)
                .post(body)
                .build();

        try (Response httpResponse = httpClient.newCall(httpRequest).execute()) {
            log.debug("MCP notification sent: {}", method);
        } catch (IOException e) {
            log.warn("MCP notification 发送失败: {}", method, e);
        }
    }

    private Map<String, Object> buildRequest(Object id, String method, Map<String, Object> params) {
        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }
        return request;
    }

    private Map<String, Object> sendRequest(Map<String, Object> rpcRequest) {
        String json = GSON.toJson(rpcRequest);
        RequestBody body = RequestBody.create(json, JSON_TYPE);
        Request httpRequest = new Request.Builder()
                .url(baseUrl)
                .post(body)
                .build();

        try (Response httpResponse = httpClient.newCall(httpRequest).execute()) {
            if (!httpResponse.isSuccessful()) {
                log.error("MCP 远程调用 HTTP 错误: {} - status={}", baseUrl, httpResponse.code());
                return null;
            }
            String responseBody = httpResponse.body() != null ? httpResponse.body().string() : "";
            if (responseBody.isEmpty()) {
                return null;
            }
            return GSON.fromJson(responseBody, MAP_TYPE);
        } catch (IOException e) {
            log.error("MCP 远程调用 IO 异常: {}", baseUrl, e);
            return null;
        }
    }
}
