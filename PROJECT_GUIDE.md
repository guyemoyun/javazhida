# Zhida 项目使用与测试指南

本文档介绍如何在 Windows PowerShell 中配置、启动和测试 Zhida 多模型问答项目。

## 1. 项目功能

Zhida 是一个 Spring Boot 网页问答应用：

- 浏览器访问聊天页面。
- 支持 OpenAI、DeepSeek、通义千问 Qwen。
- 支持切换服务商和模型名称。
- 浏览器本地保存对话记录。
- 支持编辑、删除和搜索当前对话中的单条消息。
- 显示当前对话的消息数、UTF-8 内容字节数和估算 token 数。
- 支持一次清空当前对话中的全部消息。
- API 密钥只保存在服务端环境变量中，不会发送给浏览器。
- 可以继续添加其他兼容 OpenAI `/chat/completions` 协议的服务。

## 2. 环境要求

当前项目需要：

- Windows PowerShell。
- JDK 17，当前安装目录为 `D:\jdk`。
- 网络能够访问所选模型服务商。
- 至少一个有效的模型 API 密钥。

打开 PowerShell，进入项目目录：

```powershell
cd D:\zhida
```

为当前终端指定 JDK 17：

```powershell
$env:JAVA_HOME = 'D:\jdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

验证 Java：

```powershell
java -version
javac -version
```

预期看到 Java `17.0.12` 或其他 Java 17 版本。

## 3. 配置模型 API 密钥

只需要配置你实际使用的服务商。不要把真实密钥写入代码、`application.properties` 或提交到版本库。

**关键规则：** API 密钥必须在启动 Spring Boot **之前**设置，并且设置密钥与启动服务必须使用同一个 PowerShell 窗口。已经运行的服务不会读取后来在另一个窗口设置的环境变量，必须停止并重新启动。

### 推荐方式：隐藏输入 API 密钥

下面的命令不会在终端中回显密钥，也不会把密钥写入项目文件。按实际需要执行对应服务商的区块：

```powershell
$secureKey = Read-Host 'OpenAI API key' -AsSecureString
$env:OPENAI_API_KEY = [Net.NetworkCredential]::new('', $secureKey).Password
Remove-Variable secureKey
$env:OPENAI_MODEL = 'gpt-4o-mini'
```

```powershell
$secureKey = Read-Host 'DeepSeek API key' -AsSecureString
$env:DEEPSEEK_API_KEY = [Net.NetworkCredential]::new('', $secureKey).Password
Remove-Variable secureKey
$env:DEEPSEEK_MODEL = 'deepseek-chat'
```

```powershell
$secureKey = Read-Host 'Qwen DashScope API key' -AsSecureString
$env:QWEN_API_KEY = [Net.NetworkCredential]::new('', $secureKey).Password
Remove-Variable secureKey
$env:QWEN_MODEL = 'qwen-plus'
```

环境变量仅在当前 PowerShell 窗口有效。关闭窗口、按 `Ctrl+C` 停止服务或重新打开终端后，需要再次输入密钥。

### OpenAI

```powershell
$env:OPENAI_API_KEY = '你的 OpenAI API 密钥'
$env:OPENAI_MODEL = 'gpt-4o-mini'
```

### DeepSeek

```powershell
$env:DEEPSEEK_API_KEY = '你的 DeepSeek API 密钥'
$env:DEEPSEEK_MODEL = 'deepseek-chat'
```

### 通义千问 Qwen

```powershell
$env:QWEN_API_KEY = '你的 DashScope API 密钥'
$env:QWEN_MODEL = 'qwen-plus'
```

这些 `$env:` 设置只对当前 PowerShell 窗口有效。关闭窗口后需要重新设置。

如需使用代理或其他 OpenAI 兼容地址，可以修改当前终端的基础地址：

```powershell
$env:OPENAI_BASE_URL = 'https://你的兼容服务地址/v1'
```

服务端会自动在基础地址后添加 `/chat/completions`，因此基础地址不要以该路径结尾。

## 4. 运行自动化测试

在项目根目录执行：

```powershell
.\mvnw.cmd clean test
```

该命令会：

1. 删除旧的 `target` 构建产物。
2. 重新编译全部 Java 源码。
3. 编译测试代码。
4. 运行 Spring 容器和接口测试。

成功时末尾会显示：

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

测试报告位于：

```text
target\surefire-reports
```

其中：

- `Failures` 表示断言与预期不一致。
- `Errors` 表示测试执行期间发生异常。
- `Skipped` 表示未执行的测试。
- 只有看到 `BUILD SUCCESS` 才表示本次构建通过。

## 5. 启动网页应用

确保在同一个 PowerShell 窗口中设置好了 `JAVA_HOME` 和模型 API 密钥，然后运行：

```powershell
.\mvnw.cmd spring-boot:run
```

如果使用 DeepSeek，也可以运行项目根目录的安全启动脚本：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\start-deepseek.ps1
```

脚本会提示 `DeepSeek API key:`。粘贴 Key 后按 Enter，输入不会回显；脚本会直接在 `8081` 端口启动应用。密钥只存在于该 PowerShell 进程内存中，不会保存到项目文件。

启动成功后会看到类似信息：

```text
Tomcat started on port 8080
Started ZhidaApplication
```

浏览器打开：

```text
http://localhost:8080
```

如果 8080 端口已被占用，改用 8081：

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
```

然后打开：

```text
http://localhost:8081
```

使用 DeepSeek 安全启动脚本时，地址固定为：

```text
http://127.0.0.1:8081/
```

项目默认未配置 HTTPS。地址必须以 `http://` 开头；使用 `https://localhost:8081` 会连接失败。

需要停止服务时，在运行服务的 PowerShell 窗口按 `Ctrl+C`。

## 6. 使用网页问答

1. 打开网页。
2. 在右上角选择服务商。
3. 确认模型名称。
4. 在底部输入问题。
5. 按 Enter 或点击“发送”。
6. 使用 Shift+Enter 在问题中换行。

如果服务商显示“未配置”，说明启动服务的 PowerShell 中没有对应的 API 密钥。设置密钥后必须停止并重新启动 Spring Boot。

对话记录保存在浏览器 `localStorage` 中：

- 不会写入服务器数据库。
- 更换浏览器或清除浏览器站点数据后记录会消失。
- 左侧“新建对话”可以创建独立会话。

消息工具栏只作用于当前对话：

- 搜索会按关键词筛选消息并高亮匹配文本，不会修改原始消息。
- 编辑后的内容会持久化，并作为后续提问的历史上下文发送给模型。
- 删除第一条用户消息后，对话标题会根据剩余的第一条用户消息自动更新。
- “清空消息”会在确认后删除当前对话内的全部消息，不会删除其他对话。
- 字节数按消息正文的 UTF-8 编码统计；token 数为近似估算，不代表服务商账单中的精确用量。

## 7. 手动测试后端接口

启动应用后，再打开一个 PowerShell 窗口执行以下测试。先为实际端口赋值；默认启动为 `8080`，DeepSeek 安全启动脚本为 `8081`。

```powershell
$port = 8081
```

### 查看服务商配置状态

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/providers"
```

成功响应的 `code` 应为 `200`。每个服务商都有 `configured` 字段：

- `True`：服务端已读取到 API 密钥。
- `False`：尚未配置 API 密钥。

只检查服务商和配置状态：

```powershell
(Invoke-RestMethod "http://127.0.0.1:$port/api/providers").data |
    Select-Object id, configured, model
```

### 发送 OpenAI 问题

```powershell
$body = @{
    provider = 'openai'
    model = 'gpt-4o-mini'
    message = '请用三句话介绍 Spring Boot'
    history = @()
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
    -Method Post `
    -Uri "http://127.0.0.1:$port/api/chat" `
    -ContentType 'application/json' `
    -Body $body
```

使用 DeepSeek 时，将请求中的字段替换为：

```text
provider = deepseek
model = deepseek-chat
```

使用 Qwen 时替换为：

```text
provider = qwen
model = qwen-plus
```

成功响应结构：

```json
{
  "code": 200,
  "msg": "成功",
  "data": {
    "provider": "openai",
    "model": "gpt-4o-mini",
    "message": "模型返回的回答"
  }
}
```

项目根目录的 `api-test.http` 也包含服务商列表和聊天请求，可以在支持 HTTP Client 的 IDE 中直接运行。

## 8. 打包运行

生成可执行 JAR：

```powershell
.\mvnw.cmd clean package
```

成功后运行：

```powershell
& "$env:JAVA_HOME\bin\java.exe" -jar .\target\zhida-0.0.1-SNAPSHOT.jar
```

使用其他端口：

```powershell
& "$env:JAVA_HOME\bin\java.exe" -jar .\target\zhida-0.0.1-SNAPSHOT.jar --server.port=8081
```

API 密钥仍然必须在运行 JAR 的同一个终端中提前设置。

## 9. 添加其他兼容模型服务

在 `src/main/resources/application.properties` 中添加一个服务商，例如：

```properties
chat.providers.custom.name=Custom AI
chat.providers.custom.base-url=${CUSTOM_BASE_URL:https://example.com/v1}
chat.providers.custom.api-key=${CUSTOM_API_KEY:}
chat.providers.custom.model=${CUSTOM_MODEL:custom-model}
```

启动前设置：

```powershell
$env:CUSTOM_API_KEY = '你的密钥'
$env:CUSTOM_MODEL = '实际模型名称'
```

该服务必须兼容 OpenAI 的非流式 `/chat/completions` 请求和响应结构，否则需要单独编写适配代码。

## 10. 常见问题

### 执行 `mvnw.cmd` 后没有任何输出

当前终端可能使用了失效的系统 Java。重新执行：

```powershell
$env:JAVA_HOME = 'D:\jdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
.\mvnw.cmd test
```

### 页面提示未配置 API 密钥

确认密钥是在启动 Spring Boot 的同一个 PowerShell 窗口中设置的。设置或修改密钥后必须重启应用。网页本身不能保存或配置 API 密钥。

先执行下列命令确认服务端状态：

```powershell
(Invoke-RestMethod 'http://127.0.0.1:8081/api/providers').data |
    Select-Object id, configured, model
```

若 DeepSeek 仍为 `False`，按 `Ctrl+C` 停止运行中的服务，再重新执行 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` 与 `.\start-deepseek.ps1`；不要继续访问旧的、未携带 Key 的服务进程。

### 浏览器无法打开页面

确认终端已出现 `Started ZhidaApplication`，并使用与实际启动端口一致的 HTTP 地址，例如：

```text
http://127.0.0.1:8081/
```

不要使用 `https://`。可用 PowerShell 验证本机服务：

```powershell
Invoke-WebRequest 'http://127.0.0.1:8081/' | Select-Object StatusCode
```

预期输出 `200`。若命令成功但浏览器仍无法打开，检查浏览器代理或扩展是否将地址升级为 HTTPS。

### 返回 401 或 403

通常表示 API 密钥无效、过期、没有权限，或服务商账户不可用。检查对应服务商控制台。

### 返回 502

表示 Zhida 已收到请求，但上游模型服务调用失败。检查基础地址、模型名称、网络连接和服务商返回的错误消息。

### 端口被占用

使用其他端口启动：

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
```

### 修改代码后页面没有更新

停止应用后重新运行 `spring-boot:run`，并在浏览器中执行强制刷新 `Ctrl+F5`。

## 11. 推荐测试顺序

每次修改代码后按以下顺序检查：

1. 执行 `.\mvnw.cmd clean test`。
2. 确认出现 `BUILD SUCCESS`。
3. 启动 `spring-boot:run`。
4. 请求 `/api/providers`，确认密钥配置状态。
5. 在网页发送一个简单问题。
6. 再测试一个包含历史上下文的连续问题。
7. 修改模型名称，确认错误能被网页正确显示。

这套顺序可以同时覆盖编译、Spring 配置、接口校验、静态网页和真实模型网络调用。
