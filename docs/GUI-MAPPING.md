# GUI → Android mapping

Source of truth for UX: `lucky-agent/UI/GUI`.

| GUI | Android |
|-----|---------|
| `App.tsx` shell / sidebar nav | `LuckyAgentAppRoot`。手机抽屉和宽屏侧栏都分「当前会话」和「这台电脑」；服务器配置从基础设置进入 |
| Chat view + composer + bubbles | `ChatScreen` |
| Sessions list / search | `SessionDrawerContent` |
| WebSocket `/api/v1/ws` | `LuckyAgentWsClient` |
| `fetchRuntime('/v1/sessions')` | `LuckyAgentApi.listSessions` |
| Settings (server addr, models…) | `SettingsScreen` overview by default; hosts/tokens/session open via **Open connection details** |
| Memory graph | `MemoryScreen`，导航和页标题为「记忆」 |
| Skills / Gateways / Trajectory | 同名 Screen，JSON 只读骨架 |
| clover CSS tokens | `ui/theme/Color.kt` + `res/values/colors.xml` |

Non-goals for v0:

- Embed Go runtime on device
- Public gRPC without TLS/auth
- Shipping provider API keys on phone

## WS event map (runtime → Android)

| Server (`message.go`) | Android handler |
|-----------------------|-----------------|
| stream_chunk | assistant bubble append (also accepts assistant_delta) |
| stream_end | finalize assistant (full_response) |
| tool_call | tool card (name + args/params/display) |
| tool_result | merge into same step card |
| status / reasoning | activity line |
| error | error bubble |
| cancel status | stop streaming |
