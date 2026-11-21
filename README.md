# MiMo Gateway (APK)

把小米 MiMo 网页端（`aistudio.xiaomimimo.com`）变成 OpenAI 兼容 API 的**极简安卓网关**。

原生 Kotlin 编写，零第三方依赖，APK 约 3–5 MB。核心逻辑参考 [mimo2api_mimoapi](https://github.com/GoblinHonest/mimo2api_mimoapi)，做了精简：去掉 Web 管理面板、图表、多账号轮询、日志统计，只保留真实需要的能力。

## 功能
- OpenAI `POST /v1/chat/completions`（流式 + 非流式）
- `GET /v1/models`、`GET /health`
- Tool Calling（`<tool_call>` XML ↔ OpenAI `tool_calls`）
- thinking 内容处理（separate / strip）
- 本地前台服务常驻，手机开机或切后台都能用
- 凭据保存在本机，不上传

## 使用方法
1. 安装 APK 后打开。
2. 浏览器登录 `aistudio.xiaomimimo.com`，F12 → Network，发一条消息，找到 `chat` 请求，右键 **Copy as cURL**，整段粘贴到 App 第一个输入框，点「解析并导入凭据」。
3. （可选）设置客户端调用密钥 apiKey、端口。点「启动服务」。
4. 在 AI 客户端里把 base_url 设为 `http://手机IP:8080/v1`，apiKey 填你设置的值（或留空），模型选一个 `mimo-v2-*`。

## 编译
GitHub Actions 自动编译。推送到 main 或在 Actions 页面手动触发 `Build APK`，完成后在 Artifacts 下载 `mimo-gateway-apk`。

也可本地用 Android Studio / Gradle 8.7 + JDK 17 编译：
```
gradle assembleRelease
```

## 注意
- 依赖 MiMo 网页端免费额度与你的登录 Cookie，Cookie 过期需重新导入。
- 仅供个人合法使用，请遵守小米相关服务条款。
