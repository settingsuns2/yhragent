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

import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolExecutor;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class RemoteMCPToolExecutor implements MCPToolExecutor {

    private final MCPToolDefinition toolDefinition;
    private final MCPRemoteClient remoteClient;
    private final String remoteToolName;

    public RemoteMCPToolExecutor(
            MCPToolDefinition toolDefinition,
            MCPRemoteClient remoteClient,
            String remoteToolName
    ) {
        this.toolDefinition = toolDefinition;
        this.remoteClient = remoteClient;
        this.remoteToolName = remoteToolName;
    }

    @Override
    public MCPToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> arguments = new HashMap<>();
            if (request.getParameters() != null) {
                arguments.putAll(request.getParameters());
            }

            String result = remoteClient.callTool(remoteToolName, arguments);
            long cost = System.currentTimeMillis() - start;

            return MCPToolResponse.builder()
                    .success(true)
                    .toolId(getToolId())
                    .textResult(result)
                    .costMs(cost)
                    .build();
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("远程 MCP 工具执行失败: {} -> {}", getToolId(), remoteToolName, e);
            return MCPToolResponse.builder()
                    .success(false)
                    .toolId(getToolId())
                    .errorMessage("远程工具调用异常: " + e.getMessage())
                    .errorCode("REMOTE_CALL_ERROR")
                    .costMs(cost)
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    public static RemoteMCPToolExecutor fromRemoteSchema(
            Map<String, Object> toolSchema,
            MCPRemoteClient remoteClient,
            String serviceId
    ) {
        String name = String.valueOf(toolSchema.get("name"));
        String description = toolSchema.get("description") != null
                ? toolSchema.get("description").toString()
                : "";

        Map<String, MCPToolDefinition.ParameterDef> parameters = new HashMap<>();
        List<String> requiredParams = List.of();

        if (toolSchema.get("inputSchema") instanceof Map<?, ?> inputSchema) {
            if (inputSchema.get("properties") instanceof Map<?, ?> properties) {
                for (Map.Entry<?, ?> entry : properties.entrySet()) {
                    String paramName = String.valueOf(entry.getKey());
                    if (entry.getValue() instanceof Map<?, ?> propDef) {
                        MCPToolDefinition.ParameterDef paramDef = MCPToolDefinition.ParameterDef.builder()
                                .type(propDef.get("type") != null ? propDef.get("type").toString() : "string")
                                .description(propDef.get("description") != null ? propDef.get("description").toString() : "")
                                .build();
                        parameters.put(paramName, paramDef);
                    }
                }
            }
            if (inputSchema.get("required") instanceof List<?> required) {
                requiredParams = required.stream().map(Object::toString).toList();
            }
        }

        for (String reqParam : requiredParams) {
            MCPToolDefinition.ParameterDef paramDef = parameters.get(reqParam);
            if (paramDef != null) {
                parameters.put(reqParam, MCPToolDefinition.ParameterDef.builder()
                        .type(paramDef.getType())
                        .description(paramDef.getDescription())
                        .required(true)
                        .enumValues(paramDef.getEnumValues())
                        .defaultValue(paramDef.getDefaultValue())
                        .build());
            }
        }

        String localToolId = serviceId + "__" + name;

        MCPToolDefinition toolDef = MCPToolDefinition.builder()
                .toolId(localToolId)
                .description("[远程:" + serviceId + "] " + description)
                .parameters(parameters)
                .requireUserId(false)
                .build();

        return new RemoteMCPToolExecutor(toolDef, remoteClient, name);
    }
}
