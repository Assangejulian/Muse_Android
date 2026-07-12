# Android Agent 项目需求报告（修订版）

## 1. 项目定位

Android Agent 是运行在一台私人专用 Android 13 平板上的自动化 Agent。

项目采用私人侧载方式安装，不以发布到 Google Play 为目标。用户在首次安装时完成无障碍、通知、精确闹钟和厂商后台运行设置。完成初始化后，Agent 可在指定时间窗口内唤醒无安全锁屏的设备，对白名单应用中的预定义低风险任务进行无人值守执行，不需要逐步人工审批。

目标设备当前已关闭 PIN、密码和图案锁屏，可以直接进入系统。项目不实现安全锁屏绕过，也不保存任何锁屏凭据。

## 2. 第一目标场景

每天在用户配置的时间窗口内随机选择一次执行时间：

1. 唤醒平板并启动指定短视频应用。
2. 根据无障碍节点、页面文字、当前包名和历史动作识别页面。
3. 寻找金币、福利、任务、签到或领取入口。
4. 处理普通弹窗、更新提示、加载失败和允许关闭的广告。
5. 识别“领取成功”“今日已领取”“明日再来”等完成状态。
6. 保存结构化日志、结果和必要的失败截图。
7. 退出目标应用并释放运行资源。

单次任务默认最多 25 步、最长 5 分钟。连续失败 3 次后停止当天任务。

## 3. 可实现边界

### 3.1 目标能力

- 在 Android 13 平板本机独立运行，不依赖电脑或树莓派长期在线。
- 用户首次授权后，不要求逐动作审批。
- 读取普通应用暴露的无障碍节点树和页面文字。
- 执行节点点击、坐标点击、长按、滑动、文本输入、返回、主页和应用启动。
- 在 API 30 及以上通过 `AccessibilityService.takeScreenshot` 截图。
- 使用本地状态机完成确定性流程。
- 在本地规则无法识别页面时，可选用文本模型或视觉模型规划单步动作。
- 使用本地安全检查器批准或拒绝每个动作。
- 在熄屏且无安全锁屏时，按计划唤醒设备并尝试执行任务。
- 设备重启后恢复调度；重启后检查无障碍服务和必要授权。
- 用户可以随时通过通知或应用界面停止任务。

### 3.2 不保证的能力

- 不保证绕过 PIN、密码、图案、验证码、滑块或人脸验证。
- 不保证读取所有 Canvas、游戏引擎、DRM 或 `FLAG_SECURE` 页面。
- 不保证目标应用永远暴露稳定的无障碍节点。
- 不保证目标应用不会检测或限制自动化行为。
- 不保证应用被强行停止、权限被撤销或厂商后台策略拦截后仍能自动恢复。
- 不保证后台启动目标 Activity 在所有系统状态和所有厂商 ROM 上都成功。
- 不执行支付、充值、购买、转账、实名、账号安全、权限授予或系统设置修改。

## 4. 首次初始化

用户只在初始化或权限失效时介入：

1. 安装 APK。
2. 开启 Android Agent 无障碍服务。
3. 授予通知权限。
4. 授予精确闹钟权限。
5. 将 Android Agent 加入电池优化例外和厂商自启动白名单。
6. 配置目标应用白名单和任务时间窗口。
7. 如启用云端模型，由用户在本机配置 API Key，并确认允许发送脱敏页面信息。

应用不得尝试静默授予自身权限。应用应提供初始化检查页，逐项显示当前状态和跳转到对应系统设置的入口。

## 5. 设备访问状态

```kotlin
enum class DeviceAccessState {
    SCREEN_OFF,
    NON_SECURE_KEYGUARD,
    UNLOCKED,
    SECURE_KEYGUARD,
    USER_UNLOCK_REQUIRED,
    READY
}
```

执行规则：

- `SCREEN_OFF`：触发唤醒流程，并等待系统稳定。
- `NON_SECURE_KEYGUARD`：允许尝试解除普通滑动锁屏。
- `UNLOCKED` 或 `READY`：允许进入 Agent Loop。
- `SECURE_KEYGUARD`：立即停止自动点击，不请求或输入凭据。
- 重启后的首次解锁如果受到系统保护，应转为 `USER_UNLOCK_REQUIRED`。

本项目当前目标平板无安全锁屏，因此主要验收 `SCREEN_OFF → READY → RUN_TASK` 路径。

## 6. 系统架构

```text
Scheduler
  -> Run Coordinator
  -> Device State Gate
  -> Observer
  -> Local Rule Planner
  -> Optional Model Planner
  -> Action Parser
  -> Safety Guard
  -> Action Executor
  -> Result Verifier
  -> Run Repository
```

核心约束：

- `AccessibilityService` 只负责观察系统界面和执行动作。
- 模型请求、任务状态机和持久化逻辑不得写入 `AccessibilityService`。
- 每次模型规划最多输出一个动作。
- 每次动作后必须重新观察并验证，不允许一次执行模型返回的动作序列。
- 本地安全策略始终高于 Prompt 和模型输出。

## 7. 技术栈

| 类别 | 方案 |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose |
| 构建 | Gradle Kotlin DSL |
| 主要设备 | Android 13 / API 33 |
| 建议最低版本 | API 30 |
| 异步 | Kotlin Coroutines |
| 网络 | OkHttp |
| JSON | Kotlinx Serialization |
| 数据库 | Room |
| 设置 | DataStore |
| 系统交互 | AccessibilityService |
| 短时运行 | ForegroundService |
| 调度 | AlarmManager |
| 重启恢复 | BroadcastReceiver |
| 密钥 | Android Keystore |
| 测试 | JUnit、AndroidX Test、Espresso |

首版建议将 `minSdk` 设为 30，因为截图能力从 API 30 开始。如果仍需支持 API 26～29，截图模块必须按系统版本降级，不得调用不可用 API。

## 8. 观察模型

每一步动作前生成一次不可变 `Observation`：

```json
{
  "timestamp": 0,
  "currentPackage": "",
  "currentActivity": null,
  "windows": [],
  "screenWidth": 0,
  "screenHeight": 0,
  "screenText": [],
  "uiNodes": [],
  "screenshotAvailable": false,
  "screenshotPath": null,
  "previousActions": [],
  "taskGoal": "",
  "stepIndex": 0
}
```

`currentActivity` 必须允许为空，不能作为必需观察信息。节点需要携带本次观察生成的临时 ID，该 ID 只在当前观察周期内有效。

观察器需要：

- 限制最大节点数、最大深度和单节点文本长度。
- 删除无意义容器并合并重复文本。
- 标记密码节点，不读取或记录密码内容。
- 检测当前包是否跳出白名单。
- 检测页面是否稳定、空白或长时间无变化。
- 截图失败时返回明确原因，不阻塞节点树流程。
- 对受保护页面禁止截图上传，并允许纯节点模式降级。

## 9. 动作模型

首版动作集合：

```text
launch_app
click_text
click_node
click_coordinate
long_click
swipe
scroll_forward
scroll_backward
input_text
clear_text
back
home
wait
finish
fail
```

动作必须使用严格 JSON Schema 校验：

- 拒绝未知字段和未知动作。
- 拒绝缺少必填字段。
- 限制字符串、正则和等待时间长度。
- 校验坐标、手势距离和持续时间。
- `launch_app` 只能使用任务白名单包名。
- 禁止任意 Intent、Shell 命令、URL 和文件路径。

每个动作结果至少包含：

```text
runId
taskId
stepIndex
actionId
startedAt
finishedAt
success
errorCode
beforeObservationHash
afterObservationHash
```

## 10. Agent Loop

```text
Prepare
  -> Check Device
  -> Observe
  -> Match Local Rules
  -> Optional Model Plan
  -> Parse
  -> Validate
  -> Act
  -> Observe Again
  -> Verify
  -> Repeat or Finish
```

必须具备：

- 单步超时和任务总超时。
- 最大步数。
- 同一动作重复检测。
- 页面无变化检测。
- 包名跳转检测。
- 用户接管检测和紧急停止。
- 网络或模型失败时的有限重试。
- 任务取消后同步取消协程和网络请求。
- 成功结果必须由新观察验证，不能只相信点击回调或模型判断。

## 11. 本地优先规划

目标任务高度确定，默认规划顺序为：

1. 本地状态机和人工定义规则。
2. 节点匹配和页面特征评分。
3. 文本模型处理未知页面。
4. 视觉模型作为最后回退。

已知页面和高频入口不得每一步调用模型。这样可降低延迟、费用和随机性。

## 12. 模型安全

页面文字、节点内容和截图均是不可信输入。页面中出现的指令不得改变任务目标或安全规则。

模型不得：

- 修改应用白名单或黑名单。
- 修改安全关键词和风险等级。
- 请求更多系统权限。
- 选择任意包名、Intent、URL 或 Shell 命令。
- 输出或读取 API Key、密码和验证码。
- 直接决定支付、购买、充值或账号安全动作。

模型输出只能作为动作建议，必须经过本地 `SafetyGuard`。

## 13. 安全策略

安全检查按以下顺序执行：

```text
Package allowlist
  -> Package transition guard
  -> Sensitive page detection
  -> Action policy
  -> Coordinate policy
  -> Frequency and limit policy
```

必须实现：

- 只允许目标包和明确列出的系统弹窗包。
- 跳转到未知包后立即停止，不在未知包执行返回以外的动作。
- 禁止支付、充值、购买、转账、银行卡、实名、账号安全和验证码页面。
- 禁止操作权限授权、安装、卸载、设备管理和系统设置页面。
- 禁止在未知页面输入文本或执行坐标点击。
- 禁止点击包含价格、货币符号、付款方式或确认购买语义的区域。
- 限制点击频率、总步数、总时长和视觉调用次数。
- 安全判断不确定时默认拒绝。

## 14. 调度与后台运行

- 使用 `AlarmManager` 计算每日时间窗口内的随机执行时间。
- Android 13 新安装后必须检查 `canScheduleExactAlarms()`。
- 精确闹钟不可用时明确提示，不伪装为已成功调度。
- 闹钟触发后只启动一次短时任务。
- 前台服务必须显示持续通知并提供停止按钮。
- WakeLock 仅用于短时保持 CPU 运行，必须设置超时并在所有退出路径释放。
- 屏幕唤醒、前台服务启动和目标 Activity 启动分别记录结果。
- 设备时间、时区变化后重新计算计划。
- 重启后只恢复调度，不立即执行目标任务。
- 用户强行停止应用后，不承诺自动恢复。

## 15. 任务幂等性

任务运行状态：

```kotlin
enum class RunStatus {
    NOT_STARTED,
    RUNNING,
    SUCCEEDED,
    ALREADY_DONE,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    INTERRUPTED,
    CANCELLED
}
```

要求：

- 每次运行生成唯一 `runId`。
- 每日任务使用稳定的日期业务键，防止重复执行。
- API 超时或进程异常后，重新观察目标页面再决定是否重试。
- 页面显示“今日已领取”时记录为 `ALREADY_DONE`，视为当天完成。
- 跨午夜后不得把前一天的失败次数计入新一天。
- 数据库写入失败时停止后续自动操作，避免状态无法审计。

## 16. 日志与隐私

- Room 保存任务、运行和动作的结构化记录。
- Logcat 只用于开发调试，统一 TAG 为 `AndroidAgent`。
- 文件日志按 `runId` 导出并脱敏。
- 默认保存失败截图，不长期保存所有成功截图。
- 日志和截图同时设置保留天数与总容量上限。
- API Key 使用 Android Keystore 保护，不写入源码、日志或导出文件。
- 密码节点、验证码、账号标识和可能的个人信息默认脱敏。
- 云端模型默认只接收压缩后的必要信息。
- 用户可以一键清除日志、截图和模型配置。

## 17. 用户接管

- 前台通知提供“立即停止”。
- 应用主界面提供紧急停止。
- 观察到非预期页面变化时暂停并重新评估。
- 自动检测用户触摸只能作为辅助信号，不能作为唯一接管机制。
- 停止后取消模型请求、协程、手势队列并释放 WakeLock。

## 18. 厂商兼容与真机测试

真机报告必须记录：

```text
manufacturer
model
Android version
security patch
battery optimization state
auto-start state
accessibility state after reboot
exact alarm state
```

必须测试：

- 亮屏执行。
- 熄屏 10 分钟、1 小时和 8 小时后执行。
- 充电与非充电状态。
- Wi-Fi 断开、恢复和高延迟。
- 设备重启后的调度恢复。
- 无障碍服务关闭后的失败报告。
- 精确闹钟权限撤销后的失败报告。
- 前台服务被终止后的资源清理。
- 目标应用节点为空、Canvas 页面和受保护截图。
- 用户在任务执行中接管。
- 目标应用升级后的回归测试。

## 19. 开发阶段

### 阶段 0：真机能力探针

创建最小可安装 APK，在目标平板验证：

- 无障碍服务启用和状态读取。
- 目标应用包名和节点树读取。
- 节点点击、坐标点击、滑动和全局动作。
- 截图能力。
- 熄屏唤醒。
- 后台触发和目标应用启动。
- 重启后状态。

阶段验收结果决定后续设计，不提前实现完整模型和任务系统。

### 阶段 1：确定性单任务 MVP

- 单一目标包。
- 手动启动和停止任务。
- 节点树调试页。
- 基础动作执行器。
- 本地状态机。
- 严格白名单和基础安全策略。
- 结构化运行日志。

### 阶段 2：可靠性与安全

- 动作后验证。
- 死循环检测。
- 幂等性和中断恢复。
- 用户接管。
- 包跳转防护。
- 失败截图和日志导出。

### 阶段 3：定时无人值守

- 精确闹钟。
- 前台服务。
- WakeLock。
- 熄屏唤醒。
- 开机恢复调度。
- 厂商后台设置检查。

### 阶段 4：文本模型辅助

- 可替换 `ModelClient`。
- 严格单动作 JSON Schema。
- Prompt 注入防护。
- 超时、限流和费用记录。
- 本地规则无法识别时才调用。

### 阶段 5：视觉回退

- 截图压缩和脱敏。
- 视觉模型接口。
- 坐标映射和安全区域校验。
- 受保护页面降级。
- 调用次数和费用限制。

### 阶段 6：目标应用适配

- 收集真实页面状态。
- 建立页面特征和确定性流程。
- 覆盖弹窗、加载失败、入口变化和完成状态。
- 对目标应用每个新版本进行回归测试。

## 20. 首个可用版本验收标准

首个真机可用版本应满足：

1. APK 可安装到目标 Android 13 平板。
2. 用户开启无障碍后可读取目标应用包名和节点树。
3. 可执行节点点击、坐标点击、滑动、返回、主页和应用启动。
4. 可在支持的普通页面截图，截图失败不会崩溃。
5. 可手动运行一个仅限白名单应用的确定性任务。
6. 每一步均有结构化日志和动作后验证。
7. 包名跳出白名单或出现敏感页面时立即停止。
8. 达到最大步数、超时或死循环条件时自动停止。
9. 用户可以通过通知立即停止。
10. `assembleDebug`、单元测试和 Lint 通过。

无人值守版本额外满足：

1. 已授权精确闹钟时可在时间窗口内触发任务。
2. 当前无安全锁屏的平板在熄屏后可以被唤醒并尝试执行。
3. 当天成功或已领取后不再重复执行。
4. 重启后恢复下一次计划。
5. 权限失效、强行停止和厂商后台拦截均有明确诊断结果，不虚报成功。

## 21. 暂不实现

- Root、Shizuku、系统签名和定制 ROM。
- PIN、密码或图案自动解锁。
- 验证码、滑块和人脸验证自动处理。
- 支付、充值、购买、转账和账号安全操作。
- 自动安装、卸载、授权和系统设置修改。
- 多设备集群、云端控制台和远程桌面。
- 本地大模型推理。
- Google Play 发布适配。

## 22. 最终交付物

```text
完整 Android Studio 项目
README.md
docs/architecture.md
docs/security.md
docs/testing.md
docs/device-compatibility.md
示例任务配置
单元测试和真机测试记录
Debug APK
已知问题列表
后续开发计划
```

## 23. 开发质量要求

- 代码、注释和技术标识使用英文。
- 不提交密钥、设备凭据和 `local.properties`。
- 不使用伪代码代替已声明完成的核心实现。
- 每个阶段完成后执行编译、单元测试和 Lint。
- 真机连接时安装 APK、运行目标流程并分析 Logcat。
- 每个阶段更新 README、已完成项、未完成项和已知问题。
- 遇到系统安全边界时明确报告，不使用脆弱技巧伪装为正式能力。

