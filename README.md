# MiMo Gateway · Eta 工具版（App 通道）

把小米 MiMo 的 **App / CLI 通道**包装成本地 OpenAI 兼容 API，让 [Eta](https://github.com/Mangi-11/Eta) 等 Android AI Agent 能原生调用工具。

## 与网页通道的区别

之前用网页端 `aistudio.xiaomimimo.com/open-apis/react/chat`（私有 SSE 协议），模型认为"只能用内置 search/scrape"，工具调用需要靠 MiMoML 提示词转换 + 人设引导，成功率很低。

本版改用 MiMo App 的 OpenAI 兼容接口：

```
https://mimo-server-<region>.xiaomimimo.com/api/route/chat/completions
```

上游原生支持 `tools` / `tool_calls` / `reasoning_content` / `usage`。网关只做**原样透传**：Eta 发什么就转发什么，上游回什么就回传什么，不做任何提示词注入或协议翻译。

参考实现：`magpie-community/plugins` 的 `packages/mimo`（其模型声明中明确标注 `tool_call: true`）。

## 使用

1. 安装 APK，打开后点 **「App 内登录获取 passToken」**，用小米账号登录一次。
   - App 通道需要 `passToken`（小米账号长期凭证），不是网页端的 `xiaomichatbot_serviceToken`。
   - 登录成功后会自动抓取 `passToken` / `userId` / `cUserId`。
2. 点 **「启动服务」**。
3. Eta 里配置：
   - Base URL：`http://手机IP:8080/v1`
   - API Key：与 APK 里填的一致（没填就随便填）
   - 模型：`mimo-flash` 或 `mimo-pro`（也兼容 `mimo-v2.6-flash` / `mimo-v2.6-pro`）

## 接口

- `GET /health`
- `GET /v1/models`
- `POST /v1/chat/completions`（stream / non-stream 均原样透传）

## 会话换取

`passToken` → `GET /api/user/xiaomi/me`（手动跟随重定向，携带小米账号 Cookie）→ `/sts` 设置 mimo-server 会话 Cookie → 用 `X-Mimo-Source: mimocode-cli-free` + `X-Client-Version: 26.929.292248` 调 `/api/route/chat/completions`。会话缓存 23 小时，401/403 时自动重换一次。

## 分支

- `eta`：本版，App 通道，工具调用
- `main`：网页通道，纯对话

## 故障排查

点启动服务后如果失败，日志写在 App 私有目录，错误会显示在首页「状态」下方。常见原因：

- 没抓到 `passToken`：重新登录一次，确保是"用小米账号登录"而不是仅浏览页面
- HTTP 403 `membership_required`：账号没有 App 会员，且没有可用 Token Plan
- 上游返回格式异常：检查日志中 `APPCH` 标签的记录
