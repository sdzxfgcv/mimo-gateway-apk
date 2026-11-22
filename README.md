# MiMo Gateway — Eta 专属分支

> 本分支（`eta`）专为 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) 定制：**纯对话、支持看思考、不提供工具调用**。

把小米 MiMo 网页端（`aistudio.xiaomimimo.com`）变成 OpenAI 兼容 API 的极简安卓网关，原生 Kotlin 编写、零第三方依赖、APK 约 60 KB。

## 为什么是纯对话

Eta 的 Agent Runtime 会要求模型返回"正文或工具调用"。MiMo 免费网页通道的工具调用极不稳定（官方实现也要 5 重格式兜底），因此本分支**从网关层彻底屏蔽工具能力**：

- 忽略客户端传来的 `tools` / `tool_choice`，不注入、不解析、不回传 `tool_calls`
- 每次响应都保证 `choices[0].message.content` 非空，避免 Eta 报"模型未返回正文或工具调用"
- 思考内容通过 `reasoning_content` 字段下发——Eta 的 `OpenAiChatCompletionsProvider` 正是读这个字段渲染思考块

## 在 Eta 中配置

1. 安装 APK → 点「App 内登录获取凭证」→ 在内置浏览器里登录小米账号、发一条消息 → 自动捕获凭证。
2. 点「启动服务」，记下局域网地址（如 `http://192.168.1.5:8080`）。
3. Eta 中添加 OpenAI 兼容模型：
   - Base URL：`http://手机IP:8080/v1`
   - API Key：网关里设置的值（没设可随便填，如 `sk-mimo`）
   - 模型名：`mimo-v2.6-flash`（推荐）或 `mimo-v2.6-pro`
   - 推理强度：设为「中等 / 高」以显示思考；不设也默认开启

## 功能端点

- `POST /v1/chat/completions`（流式 + 非流式，纯文本对话 + 思考）
- `GET /v1/models`、`GET /health`

## 编译

GitHub Actions 会在推送到 `eta` 分支时自动编译；也可在 Actions 页面手动触发 `Build APK (Eta)`，Artifacts 下载。

本地：`gradle assembleRelease`（Gradle 8.7 + JDK 17）。

## 注意

- 依赖 MiMo 网页端免费额度与登录 Cookie，Cookie 过期需重新在 App 内登录。
- 本分支不支持工具调用；Eta 中需要执行工具的功能不会生效，仅作对话/问答/看思考用途。
- 仅供个人合法使用，请遵守小米相关服务条款。
