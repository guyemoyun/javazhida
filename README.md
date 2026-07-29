# 智答多模型问答

智答是一个本地网页问答应用，可以通过服务端代理调用兼容 OpenAI 协议的模型 API。API 密钥只保存在服务端，不会发送到浏览器。

## 配置模型服务商

启动应用前，至少在终端中配置一个 API 密钥：

```powershell
$env:OPENAI_API_KEY = "你的密钥"
# or
# 或者
$env:DEEPSEEK_API_KEY = "你的密钥"
# or
# 或者
$env:QWEN_API_KEY = "你的密钥"
```

可选环境变量包括 `OPENAI_MODEL`、`DEEPSEEK_MODEL`、`QWEN_MODEL`，以及各服务商的 `*_BASE_URL`。还可以在 `application.properties` 中通过服务商名称、基础地址、API 密钥和模型名称添加任意兼容 OpenAI 协议的服务。

## 启动项目

```powershell
$env:JAVA_HOME = "D:\jdk"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd spring-boot:run
```

浏览器打开 http://localhost:8080，在页面中选择服务商和模型，然后输入问题。

## 运行测试

```powershell
.\mvnw.cmd test
```

更详细的配置、测试、打包和故障排查说明，请阅读 [PROJECT_GUIDE.md](PROJECT_GUIDE.md)。
