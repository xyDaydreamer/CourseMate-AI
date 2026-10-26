# CourseMate AI 0.5.2

IntelliJ IDEA 内的 Java 课程助教。使用本地课程 PDF 检索和 DeepSeek；保留选区预览、课程来源白名单和离线测试。

版本快照分支：**hzy_0.5.2**，日期 2026-10-27。最终本地验证：**137 项基础测试、10 项真实 PDF 集成测试通过，打包成功**；原有 28 案例回归通过，冻结题集 Hit@3 为 13/14，B12 仍待改善。真实模型正确性和结论 / 引用一致性仍待人工验收，本次验证没有付费调用。详见 [验证汇总](evaluation/verification-0.5.2.json)。

从 main（`7696251`）到当前版本的累计修改、验证证据、已知限制和提交范围，见 [README_CHANGES.md](README_CHANGES.md)。评测方法与归档结果见 [evaluation/README.md](evaluation/README.md)。

## 0.5.2 追问与交互修复

- 修复“修改后应该是什么样”“那这里的泛型怎么改”和英文修正类追问。承接判断同时考虑当前主题和明确选区中的概念；原始选区、近期历史和当前追问均进入实际请求，模型建议不会自动替换选区。
- 追问检索同时考虑稳定主题和当前问题；新知识点不会因只检索原始主题而直接被丢弃。明确新话题不沿用旧代码；勾选沿用但当前为新问题时，输入区明确提示。
- 蓝色卡片操作项加入悬停、按下、选中、禁用与键盘焦点反馈，保持主题配色；交互状态不改变布局高度。
- 解释不完整选区时强调可见范围及条件判断，避免把选区边界或原始泛型类型本身当作整个项目的编译错误。
- 任务和会话关系分开记录；换题 / 新选区不再把旧主题历史带回后续追问。明确纠正会更新解析后的问题并排除旧回答；历史仍有界，不增加摘要调用或长期记忆。

## 0.5.1 实测反馈修复

该阶段先 fetch 并核对远端 main：仍为 7696251，没有新提交；当时的本地成果完整保留，现纳入 hzy_0.5.2 版本快照。

- 长回答按实际宽度计算高度，修复窄窗口/大字体下按钮覆盖正文；减少重复课程标签，辅助提示与证据编号采用较小的弱化样式。
- 删除“显示本语言回答”按钮；顶部语言切换会直接显示已有缓存译文，缺少译文时仍由用户按需翻译，不自动调用 API。
- “会话记录”下拉框保留当前插件窗口内最近 10 个会话，可切换回看并继续；每会话最多 30 张卡片。关闭项目/插件面板销毁后不保存，旧版已被清除的记录无法恢复。
- 补充“再举一个例子，并说明实际调用哪个方法”这类复合追问，以及明确的解析主题/近期历史提示。
- 兼容精确 JSON 代码围栏及可空的非必需响应元数据；格式错误分阶段提示，保持来源白名单、不自动重试。此修复不等同于已经验证用户之前那一次真实响应的原始报文。

## 0.5.0 基础更新

- 在检索前形成任务与会话主题；连续追问保持解析后的主题，每轮重新检索。
- IDEA Java PSI 只解析已选文本，不能从未提供的方法名猜测方法实现；不完整片段使用受限词法降级。
- BM25 保留，加入课程概念查询、标题/主题重排、Java/C++ 区分、独立证据准入和最多 3 页/4 块/5000 字符的证据预算。
- 模型正文、结束原因、usage 分开保存。截断、格式错误、非法引用、网络失败和取消分别处理，无自动重试。
- 缓存 v3：稳定块 ID、文件 SHA-256、物理页码、规范化页内偏移、标题、块类型和邻接 ID。重叠原文只在内容一致时合并。
- Swing 每轮独立卡片，底部固定课程来源区域；展开选区/原文、逐条复制、按需翻译并缓存。
- 新对话、停止请求、迟到响应隔离；沿用历史代码时在发送前明确预览，可移除，新选区优先。
- 界面语言立即切换；问题/选区/课程原文保持原文，历史回答只在用户点击翻译时调用 API。

尚未采用 embedding、外部数据库、Web UI、自动事实验证、长期记忆或历史自动翻译。来源 ID 合法不代表结论已被事实验证。

## 环境和构建

JDK 25、IDEA 2026.2.3、项目 Gradle Wrapper。支持的 IDE build 为 262.10968.63 至 262.*。Java 插件是 IDEA 自带依赖，无新增第三方库。

```powershell
$env:JAVA_HOME = '自己的 JDK 25 路径'
$env:COURSEMATE_COURSE_FILES = '自己的 course_files 完整路径'
.\gradlew.bat test --console=plain --no-daemon
.\gradlew.bat integrationTest --console=plain --no-daemon
.\gradlew.bat buildPlugin --console=plain --no-daemon
```

`test` 不需要教师 PDF 或真实 Key。`integrationTest` 单独执行原有真实课件的 10 个案例，要求指定版本的 9 份讲义；测试进程均移除真实 Key。需要时使用 `-PideaLocalPath='自己的 IDEA 根目录'`。完整测试和构建不做模型调用。

普通构建安装包为 `build/distributions/coursemate-ai-0.5.2.zip`，测试 HTML 位于构建目录的 `reports/tests/test` 与 `reports/tests/integrationTest`。0.5.2 本机验证使用隔离目录 `build/feedback-052`；该目录和安装包均为本地产物，不随源码提交。验证汇总见 [evaluation/verification-0.5.2.json](evaluation/verification-0.5.2.json)。

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

`evaluation/questions.json` 的原有 28 案例保留。`holdout-20261027.json` 首次评估后暴露问题，后续已作为开发回归，不能再称独立盲测。`holdout-final-20261027.json` 是最终冻结的 24 案例，不按其结果调参；也不等同于独立人员标注的外部盲测。

离线 fake 只验证检索、格式和状态，不证明真实回答正确。真实 API 人工验收使用 `evaluation/run-live-acceptance.ps1`，本机隐藏输入 Key，最多 6 次调用、失败不自动重试；输出真实回答供逐条人工核对。缺少 Key 时保持 NOT_RUN，不能拿 fake 代替。
