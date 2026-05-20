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

import com.nageoffer.ai.ragent.mcp.core.MCPToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mcp.remote.enabled", havingValue = "true")
@EnableScheduling
public class MCPRemoteRegistrar {

    private static final long RETRY_INTERVAL_MS = 30_000;

    private final MCPToolRegistry toolRegistry;
    private final MCPRemoteProperties properties;
    private final Map<String, MCPRemoteClient> clients = new ConcurrentHashMap<>();
    private final Set<String> registeredServices = new HashSet<>();
    private final Set<String> pendingServices = new HashSet<>();

    @PostConstruct
    public void init() {
        log.info("开始异步注册远程 MCP 服务工具...");
        for (Map.Entry<String, MCPRemoteProperties.ServiceConfig> entry : properties.getServices().entrySet()) {
            String serviceId = entry.getKey();
            MCPRemoteProperties.ServiceConfig config = entry.getValue();
            if (!config.isEnabled() || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
                log.info("跳过未启用或未配置的远程 MCP 服务: {}", serviceId);
                continue;
            }
            pendingServices.add(serviceId);
        }

        Set<String> toRegister = new HashSet<>(pendingServices);
        for (String serviceId : toRegister) {
            tryRegisterService(serviceId);
        }

        log.info("远程 MCP 服务初始注册完毕, 已连接: {}, 待重试: {}",
                registeredServices.size(), pendingServices.size());
    }

    @Scheduled(fixedDelay = RETRY_INTERVAL_MS, initialDelay = RETRY_INTERVAL_MS)
    public void retryPending() {
        if (pendingServices.isEmpty()) {
            return;
        }
        log.info("重试连接待注册的远程 MCP 服务: {}", pendingServices);
        Set<String> toRetry = new HashSet<>(pendingServices);
        for (String serviceId : toRetry) {
            tryRegisterService(serviceId);
        }
    }

    private void tryRegisterService(String serviceId) {
        MCPRemoteProperties.ServiceConfig config = properties.getServices().get(serviceId);
        if (config == null) {
            pendingServices.remove(serviceId);
            return;
        }

        try {
            MCPRemoteClient client = new MCPRemoteClient(config.getBaseUrl());
            client.initialize();
            clients.put(serviceId, client);

            List<Map<String, Object>> tools = client.listTools();
            for (Map<String, Object> toolSchema : tools) {
                RemoteMCPToolExecutor executor = RemoteMCPToolExecutor.fromRemoteSchema(
                        toolSchema, client, serviceId
                );
                toolRegistry.register(executor);
                log.info("注册远程工具: {} -> {}", executor.getToolId(), config.getBaseUrl());
            }

            registeredServices.add(serviceId);
            pendingServices.remove(serviceId);
            log.info("远程 MCP 服务 [{}] 注册成功, 共 {} 个工具 (剩余待连接: {})",
                    serviceId, tools.size(), pendingServices.size());
        } catch (Exception e) {
            log.warn("远程 MCP 服务 [{}] 暂不可用: {}, 将在 {}s 后重试",
                    serviceId, e.getMessage(), RETRY_INTERVAL_MS / 1000);
        }
    }
}
