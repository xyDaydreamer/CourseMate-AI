# CourseMate AI 0.5.3

IntelliJ IDEA 内的 Java 课程助教，使用本地讲义 PDF、BM25 检索和官方 DeepSeek API。支持解释明确选中的代码、上下文追问和课程来源原文展示。

**当前状态：构建、自动测试和检索回归通过；真实回答质量尚未全部验收，并发与异常解释仍可能出现事实错误。**

## 0.5.3 更新

- 集成组员 `lxh_0.5.3` 的 `d9bebb4`：连续 Markdown 展示、回答格式兼容，以及未引用课程资料时的明确提示。
- 保留 BM25，区分核心主题、具体意图与属性词并用于重排。B12 原因是排序不合理，第 15 页从候选第 8 升至证据第 1；没有添加 B12、Reflection 或特定文件名规则。
- 修复来源编号校验：拒绝 `1.9`、整数溢出和畸形编号，保留合法整数 / 字符串兼容；服务端标记 `length` 的回答始终保留截断状态。
- 代码提示分别核对内部调用、分号和外围大括号，忽略字符串 / 注释中的定界符。最终安装包上的原题与新回调题 2/2 复测正确。
- 格式或引用校验失败时最多自动再请求一次；网络、HTTP 和截断回答不自动重试。

## 验证结果（2026-10-27）

| 项目 | 实际结果 |
| --- | --- |
| 基础测试 | 163/163 通过 |
| 真实 PDF 集成测试 | 14/14 通过，使用 9 份讲义 |
| 打包 | 成功：`build/distributions/coursemate-ai-0.5.3.zip` |
| 用户指定六项检索回归 | 6/6 组通过，共 12 条案例；中英文 B12 第 15 页、概念第 9 页、调用第 14 页均排第 1 |
| 原 28 题 / 原冻结 24 题 | 无退化；有来源题 Hit@3 分别为 18/18、14/14 |
| 第二轮预先固定的检索新题 | 有来源 6/6、无来源 4/4；开发者编写，不是独立人员盲测 |
| 真实 API | 累计 31 次 HTTP 请求，含 1 次格式失败后成功重试；无 HTTP / 网络失败 |

**31 次是实际请求次数，不代表 31 道题全部答对。** 并发 / 异常事实提示没有证明稳定收益，已撤回；普通 HashMap 的原子性、null 行为和具体并发实现细节仍可能被模型解释错误。来源 ID 合法也不代表结论正确。

可复核证据：[测试及安装包指纹](evaluation/verification-0.5.3.json)、[检索审查](evaluation/review-0.5.3.md)、[真实 API 逐项记录](evaluation/live-review-0.5.3.md)、[最终复测截图](evaluation/evidence-053/live-content-final.jpg)。B12 原始失败记录保持原样。含课程原文的真实回答和诊断结果仅保存在被忽略的 `.coursemate/acceptance/`，不随源码发布。

重跑方法和历史成绩见 [evaluation/README.md](evaluation/README.md)；0.5.2 及之前的累计修改记录保留在 [README_CHANGES.md](README_CHANGES.md)。

## 环境和构建

JDK 25、IDEA 2026.2.3、项目 Gradle Wrapper。支持的 IDE build 为 262.10968.63 至 262.*。Java 插件是 IDEA 自带依赖，无新增第三方库。

```powershell
$env:JAVA_HOME = '自己的 JDK 25 路径'
$env:COURSEMATE_COURSE_FILES = '自己的 course_files 完整路径'
.\gradlew.bat test --console=plain --no-daemon
.\gradlew.bat integrationTest --console=plain --no-daemon
.\gradlew.bat buildPlugin --console=plain --no-daemon
```

`test` 不需要教师 PDF 或真实 Key。`integrationTest` 执行原有 10 个真实课件案例及新增 4 个中英文意图回归，要求指定版本的 9 份讲义；测试进程均移除真实 Key。需要时使用 `-PideaLocalPath='自己的 IDEA 根目录'`。完整测试和构建不做模型调用。

构建安装包为 `build/distributions/coursemate-ai-0.5.3.zip`，测试 HTML 位于 `build/reports/tests/test` 与 `build/reports/tests/integrationTest`。安装包和本机运行环境不随源码提交；当前验证汇总见 [evaluation/verification-0.5.3.json](evaluation/verification-0.5.3.json)。

## 配置与启动

### Windows 双击快捷启动（推荐）

1. 安装 IntelliJ IDEA **2026.2.3**，准备完整的 **JDK 25**。启动器会优先使用 `JAVA_HOME`，也可使用所选 IDEA 自带、含 `javac.exe` 的 JBR 25。
2. 在资源管理器打开本仓库，双击根目录的 **[start-coursemate.cmd](start-coursemate.cmd)**。不要双击构建产物 ZIP 来启动。
3. 未配置 IDEA 路径时，按终端提示输入 IDEA 安装根目录（包含 `product-info.json` 的目录，不是 `bin`）。也可以提前将 `local.properties.example` 复制为 `local.properties`，填写自己的 `ideaLocalPath`；该本地文件不会提交。
4. 没有进程 API Key 时，启动器会显示隐藏输入提示。输入自己的 DeepSeek Key；直接回车也能打开界面，但不能请求真实模型。Key 不写入源码、快捷方式或配置文件，不会因本次输入而永久保存。
5. 启动器自动构建并打开安装了当前插件的 IDEA 测试实例，默认进入仓库的 **`demo/` 演示 Java 项目**，并展开 CourseMate AI 工具窗口。可以直接提问，或选中代码后右键 **Ask CourseMate AI**，确认预览再发送。

首次启动可能需要联网下载 Gradle / 依赖。课程问答还需要把讲义放入仓库 `course_files/`，或通过 `COURSEMATE_COURSE_FILES` / `-CourseDir` 指定目录；教师 PDF 不随仓库提供。使用新版前先关闭旧的 CourseMate 测试实例，已运行的实例不会自动加载新代码或新环境。

**桌面快捷方式：**右键 `start-coursemate.cmd` → 发送到 → 桌面快捷方式。之后双击桌面图标即可，无须每次手动打开 PowerShell 输入 Gradle 命令。

**打开自己的 Java 项目：**修改该快捷方式的“目标”，在启动器路径后追加 `-ProjectDir`。下面路径只是示例，请替换为自己的实际路径，含空格的路径保留双引号：

```text
"C:\Projects\CourseMate-AI\start-coursemate.cmd" -ProjectDir "C:\Projects\MyJavaApp"
```

也可追加 `-CourseDir "C:\CourseFiles"` 指定讲义目录。启动器会打开已有项目，不会替普通项目改写 SDK；默认演示项目才有专用的 SDK 自动配置。这里启动的是带插件的 IDEA 实例；需要开发插件源码时，在自己的 IDEA 中打开仓库根目录并导入 Gradle 项目。

### 手动启动与安装 ZIP

启动器调用 `scripts/start-coursemate.ps1`；Key 从 `DEEPSEEK_API_KEY` 进程环境读取，不加载 `.env`，也不写入 Git。

手动配置时单独运行以下命令，在隐藏提示处输入自己的 Key，不能将 Key 粘贴到聊天或源码：

```powershell
$secret = Read-Host 'DeepSeek API Key (hidden)' -AsSecureString
$env:DEEPSEEK_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
Remove-Variable secret
.\gradlew.bat runIde --no-daemon
```

关闭旧沙盒，从这个终端启动新的 IDEA 后才会继承环境。安装 ZIP 则使用 Settings → Plugins → Install Plugin from Disk 并重启兼容 IDEA。窗口顶部显示 Key 是否存在，不代表已通过服务认证。模型仍为 `deepseek-flash`，接口仍为官方 DeepSeek endpoint。

## 资料与来源

仅索引指定目录直接文件中符合 `(?i)^Lec-[0-5](?:[.-].*)?\.pdf$` 的讲义，排除作业/考试材料，不进行 OCR。目录优先级为环境变量 `COURSEMATE_COURSE_FILES`、JVM 属性 `coursemate.courseDir`、项目 `course_files`。缓存位于相邻 `.coursemate/index-<路径标识>.json`，旧 v2 会重建。缓存包含课程正文，不应提交。

窗口顶部可查看目录名称（悬浮查看完整路径）和索引状态，点击重建更新资料。来源的块范围属于保留换行后的规范化页文本；页码是从 1 开始的物理 PDF 页码。每轮使用来源保存当时的元数据和原文快照，重建不会改写旧卡片。打开来源前核对 PDF 指纹；文件已变化时提示重新提问。PDF 的 `#page=` 跳转是否被采用取决于本机默认浏览器/阅读器。

检索状态与回答依据分开：未命中、候选不足、部分覆盖、知识库不可用；以及课程回答、混合回答、通用知识或候选未采用。只有模型实际选择且通过本轮白名单检查的来源会作为引用展示，候选不冒充引用。

## 上下文与限制

每个项目面板可切换最近 10 个独立会话，每个会话提供最近 3 个完整问答轮次给模型；问题上限 6000、代码上限 16000、总上下文上限 28000 字符。超限输入明确拒绝，不静默截断；较早历史或完整证据因预算省略时显示提示。最多显示 30 张卡片，移除更早卡片时提示。上下文保存在当前面板内，不持久化。

追问可沿用上一轮明确选中的代码快照。取消勾选或点击移除代码可停止沿用；新选区优先。插件不会自动读取其他未选中源码。当前本地词法与概念检索仍可能漏检/误排，复杂混合领域问题和未收录中文概念需要人工核对，不宣称语义理解完整。

原始异常/受限服务端响应仅留在进程内诊断字段，界面使用本地化安全提示，不将原始诊断写入磁盘日志。

## 评测

`evaluation/questions.json` 的原有 28 案例保留。`holdout-20261027.json` 首次评估后暴露问题，后续已作为开发回归，不能再称独立盲测。`holdout-final-20261027.json` 原为冻结 24 案例，本轮已用于 B12 分析，现明确作为开发回归。后续预先固定的新题也由开发者编写，不是独立人员盲测。

离线 fake 只验证检索、格式和状态，不证明真实回答正确。真实 API 人工验收使用 `evaluation/run-live-acceptance.ps1`，本机隐藏输入 Key，每次运行最多 6 次实际 HTTP 调用，格式 / 引用校验的一次重试也计入预算；网络、HTTP 和截断不自动重试。结果只在本地保存供逐条核对。累计 31 次来自多次有界运行，不是一次脚本发出 31 次调用。
