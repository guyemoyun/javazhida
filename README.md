# 智答多模型问答

智答是一个本地运行的 Spring Boot 网页问答项目。前端页面、后端 API 和模型请求代理位于同一个应用中，启动一个 Java 进程即可使用。

项目默认选择 **DeepSeek / `deepseek-chat`**，同时支持 GLM、OpenAI 和通义千问 Qwen。这些服务商都通过 OpenAI 兼容的非流式 `/chat/completions` 协议调用。

## 环境要求

- Windows PowerShell 5.1 或 PowerShell 7
- JDK 17
- 能访问相应模型服务的网络
- 至少一个有效的模型 API Key

启动脚本会优先使用 `JAVA_HOME`；未配置时会尝试 `D:\jdk`，最后尝试系统 `Path` 中的 `java.exe`。

## 1. 配置模型

直接编辑项目文件：

```text
config/model-config.properties
```

该文件已在当前项目中创建，也已被 `.gitignore` 忽略。只需填写要使用的服务商：

```properties
# 默认服务商
DEEPSEEK_API_KEY=你的_DeepSeek_API_Key
DEEPSEEK_MODEL=deepseek-chat

# 智谱 GLM
GLM_API_KEY=你的_GLM_API_Key
GLM_MODEL=glm-4-flash

# 可选
OPENAI_API_KEY=
OPENAI_MODEL=gpt-4o-mini
QWEN_API_KEY=
QWEN_MODEL=qwen-plus
```

如果本地配置文件不存在，`start.ps1` 会从 [model-config.properties.example](config/model-config.properties.example) 自动创建它，提示填写密钥后退出。

如需使用代理、私有部署或兼容网关，可在同一文件中取消注释并修改 `*_BASE_URL`。基础地址不要包含 `/chat/completions`，后端会自动追加该路径。

> API Key 只由后端读取，不会返回到浏览器。不要删除 `.gitignore` 中对 `config/model-config.properties` 的忽略规则。

## 2. 启动整个项目

在项目根目录执行：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\start.ps1
```

脚本会依次执行：

1. 读取 `.run/zhida.pid`，确认记录的进程确实属于当前项目。
2. 终止上一次由该脚本启动的 Zhida Java 进程。
3. 使用 Maven Wrapper 打包项目。
4. 后台启动可执行 JAR，并记录新 PID。
5. 轮询 `/api/providers` 做启动健康检查。
6. 启动成功后打开 `http://127.0.0.1:8080`。

因此，修改配置或代码后只需再次执行 `.\start.ps1`，无需手动查找和结束旧进程。脚本不会结束 PID 已被其他程序复用的无关进程。

常用参数：

```powershell
# 改用 8081 端口
.\start.ps1 -Port 8081

# 已经打包过时跳过构建
.\start.ps1 -NoBuild

# 不自动打开浏览器
.\start.ps1 -NoBrowser
```

兼容入口 `.\start-deepseek.ps1` 仍可使用，它会转调统一启动脚本并使用 `8081` 端口。

运行日志位于：

```text
logs/zhida.log
```

## 3. 使用网页

1. 打开 `http://127.0.0.1:8080`。
2. 新对话默认选择 DeepSeek，也可在右上角切换 GLM、OpenAI 或 Qwen。
3. 确认模型名称，输入问题后按 Enter 发送，Shift+Enter 换行。
4. 消息和对话记录保存在当前浏览器的 `localStorage` 中。

每次提问会携带最近 `100` 条历史消息，再加上当前问题发送给模型。超过该数量的更早消息仍保留在浏览器对话记录中，但不再作为当次模型上下文。

服务商显示“未配置”时，检查 `config/model-config.properties` 中对应的 `API_KEY`，然后重新执行 `.\start.ps1`。

## 项目请求流程

```text
浏览器页面
  -> POST /api/chat
  -> ChatController 校验请求
  -> ChatService 根据 provider 读取服务端配置
  -> 请求 {base-url}/chat/completions
  -> 统一 Result 结构返回前端
```

- `GET /api/providers`：返回可用服务商、默认模型和是否已配置密钥。DeepSeek 始终排在第一位。
- `POST /api/chat`：接收服务商、模型、当前问题和最近的对话历史。
- 密钥不在 `/api/providers` 或聊天响应中暴露。

## 测试与手动启动

运行自动化测试：

```powershell
.\mvnw.cmd test
```

不使用启动脚本时，也可以前台运行：

```powershell
.\mvnw.cmd spring-boot:run
```

注意：这种方式不写入 `.run/zhida.pid`，下次执行 `start.ps1` 时不会主动终止它。

`api-test.http` 包含查询服务商和调用默认 DeepSeek 的请求示例。

## 添加其他兼容模型

在 `src/main/resources/application.properties` 中增加服务商：

```properties
chat.providers.custom.name=Custom AI
chat.providers.custom.base-url=${CUSTOM_BASE_URL:https://example.com/v1}
chat.providers.custom.api-key=${CUSTOM_API_KEY:}
chat.providers.custom.model=${CUSTOM_MODEL:custom-model}
```

然后在 `config/model-config.properties` 中填写：

```properties
CUSTOM_API_KEY=你的_API_Key
CUSTOM_MODEL=custom-model
```

新服务必须兼容 OpenAI 的非流式 `/chat/completions` 请求和响应结构，否则需要在后端增加单独的协议适配器。

## 常见问题

- **页面提示未配置**：密钥为空或配置修改后未重启。
- **401 / 403**：API Key 无效、过期、无权限或账户不可用。
- **502**：上游模型请求失败，检查 Base URL、模型名称、网络和服务商错误信息。
- **端口被占用**：执行 `.\start.ps1 -Port 8081`。脚本只会结束 PID 文件记录的实例，或命令行明确指向当前项目的旧 JAR / Maven 实例，不会强制结束无关程序。
- **页面未更新**：再次启动并在浏览器按 `Ctrl+F5`。
