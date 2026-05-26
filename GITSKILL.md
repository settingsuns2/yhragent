# Git 上传 GitHub 操作指南

## 项目地址

- **GitHub 仓库**：https://github.com/settingsuns2/yhragent
- **远程名称**：origin
- **主分支**：main

## 首次推送新仓库

```bash
git remote set-url origin https://github.com/settingsuns2/yhragent.git
git push -u origin main
```

## 必须忽略的文件

以下文件包含 API Key、数据库密码等敏感信息，**绝对不能提交到 Git**：

### 配置文件（含密钥）

| 文件 | 说明 |
| --- | --- |
| `bootstrap/src/main/resources/application.yaml` | 主配置，包含百炼/DeepSeek/SiliconFlow API Key、数据库密码 |
| `mcp-server/src/main/resources/application.yml` | MCP 服务配置，包含本地服务地址 |

> 替代方案：项目中提供了 `.example` 模板文件，克隆后复制为真实配置再填入自己的 Key：
> ```bash
> cp bootstrap/src/main/resources/application.yaml.example bootstrap/src/main/resources/application.yaml
> cp mcp-server/src/main/resources/application.yml.example mcp-server/src/main/resources/application.yml
> ```

### 工作区与临时文件

| 路径 | 说明 |
| --- | --- |
| `mcp-server/workspace/` | MCP 文件管理工作区数据 |
| `mcpworkspace/` | MCP 运行时工作区 |
| `workspace/` | Agent workspace 记忆文件 |
| `build-output.txt` | 构建输出日志 |
| `build-output2.txt` | 构建输出日志 |
| `req.json` | 临时请求 JSON |
| `benchmark-output.txt` | 基准测试输出 |
| `.claude/` | Claude 本地配置 |

### IDE 与构建产物

```
.idea/
*.iml
target/
node_modules/
dist/
.vite/
.env.local
```

## .gitignore 完整配置

确保 `.gitignore` 中包含以下内容：

```gitignore
target/
temp
.mvn/wrapper/maven-wrapper.jar
!**/src/main/**/target/
!**/src/test/**/target/

### IDE ###
.idea
*.iws
*.iml
*.ipr
.vscode/

### Frontend ###
node_modules/
dist/
.vite/
.env.local

### Sensitive config files with API keys ###
bootstrap/src/main/resources/application.yaml
mcp-server/src/main/resources/application.yml

### Workspace & temp data ###
mcp-server/workspace/
mcpworkspace/
workspace/
build-output.txt
build-output2.txt
req.json
benchmark-output.txt

### Claude ###
.claude/
```

## 日常提交流程

```bash
git add -A
git status          # 检查是否有敏感文件被误添加
git commit -m "feat: 描述信息"
git push origin main
```

## 从 Git 中移除已跟踪的敏感文件

如果敏感文件已经被提交过，需要从 Git 跟踪中移除（文件本身不会删除）：

```bash
git rm --cached bootstrap/src/main/resources/application.yaml
git rm --cached mcp-server/src/main/resources/application.yml
git commit -m "security: remove sensitive config from tracking"
git push origin main
```

## 注意事项

- **API Key 轮换**：如果 API Key 曾经被提交到 Git 历史，即使后续移除，仍可通过 `git log` 找到。应立即到对应平台重新生成 Key
- **提交前检查**：每次 `git add -A` 后，务必 `git status` 确认没有误添加敏感文件
- **模板文件**：`application.yaml.example` 和 `application.yml.example` 是安全的模板文件，可以提交
