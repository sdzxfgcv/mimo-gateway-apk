# MiMo Gateway — Eta 专属分支（工具版）

> 本分支（`eta`）专为 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) 定制：**支持工具调用 + 看思考**。

把小米 MiMo 网页端（`aistudio.xiaomimimo.com`）变成 OpenAI 兼容 API 的极简安卓网关，原生 Kotlin、零第三方依赖、约 60 KB。

## 工具调用方案（人设注入）

MiMo 免费网页端模型默认认为"我只能用内置 search/scrape"，用命令式格式说明书会被判定为提示注入而拒答。本版改用**人设注入**：

- 在 system 里建立人设——"你是 Eta 的内置大脑，这些工具是你的原生能力"；
- 精简工具清单（只列名称+一句话描述，不塞 60 个工具的完整 JSON，避免模型复述/拒答）；
- 用一个真实示例展示工具调用方式；
- 模型输出的 `<|MiMoML|tool_calls>` 由网关解析成标准 OpenAI `tool_calls` 回传给 Eta；
- 工具结果用 `<function_results>` 方言回传，会话续接只发工具结果（防循环）。

支持 Eta 的全部工具：终端（terminal）、文件读写（read_file/write_file/…）、设备控制（tap/swipe/…）、网络（web_search/fetch_url）、memory、skills 等约 60 个。

> ⚠️ 成功率说明：MiMo 网页端对自定义工具调用的支持本身不稳定（连官方实现也只有约 5/5 遵守率，且随账号/模型状态波动）。本版是尽力适配，不保证每次成功。若工具调用仍失败，可切到 `main` 分支的纯对话版。

## 在 Eta 中配置

1. 安装 APK → 「App 内登录获取凭证」→ 内置浏览器登录小米账号并发一条消息 → 自动捕获凭证。
2. 点「启动服务」，记下局域网地址。
3. Eta 添加 OpenAI 兼容模型：
   - Base URL：`http://手机IP:8080/v1`
   - API Key：网关设置的值（没设可填 `sk-mimo`）
   - 模型：`mimo-v2.6-flash`（推荐）或 `mimo-v2.6-pro`
   - 推理强度：中等/高（显示思考）

## 端点

- `POST /v1/chat/completions`（流式 + 非流式，工具调用 + 思考）
- `GET /v1/models`、`GET /health`

## 编译

推送到 `eta` 分支自动编译，或 Actions 手动触发；Artifacts 下载 `mimo-gateway-eta`。

## 注意

- 依赖 MiMo 网页端免费额度与登录 Cookie，过期需重新登录。
- 仅供个人合法使用，遵守小米服务条款。
