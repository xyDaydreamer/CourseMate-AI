# CourseMate AI

[English](README.md) · [内部开发交接](HANDOVER_CN.md)

版本 **0.4.0** · CST3492 Java Enterprise Application Development · **T07** 小组 · **Group A**

## 1. 项目简介

CourseMate AI 是 IntelliJ IDEA 插件，通过老师提供的 PDF 讲义和 DeepSeek AI 帮助学生理解 Java。它结合本地检索增强生成（RAG）、来源文件名和 PDF 页码，以及用户明确选中的 Java 代码提问。

这是学生项目的 MVP。下列功能已在代码中实现，但自动化检查不能代替实际 IDEA 操作和真实模型回答的验收。本次文档更新没有修改应用代码或依赖。

## 2. 已实现功能与边界

| 功能 | 当前行为 |
| --- | --- |
| IDE 聊天 | 右侧 **CourseMate AI** 工具窗口、多行输入、Send、Ctrl+Enter、只读聊天显示；忽略空白问题。 |
| DeepSeek | HTTPS POST 到 `https://api.deepseek.com/chat/completions`，配置模型 `deepseek-flash`，JSON、`stream=false`、关闭思考模式、`max_tokens=2048`。这些是当前配置，不保证模型今后始终可用。 |
| PDF 知识库 | 从本地目录读取白名单讲义，按 PDF 页提取文字、分块，建立本地可搜索索引；不调用付费 embedding 服务。 |
| 有限上下文 | 最多选取不同 PDF 页的 4 个文本块，每块最多 1,000 个 Java 字符串字符；也可能少于 4 个或没有。 |
| 课程引用 | Java 按真实检索片段校验模型选择的来源 ID，再显示原始文件名和从 1 开始的 PDF 页码。 |
| 通用知识 | 调用前排除相关性不足的候选。无课程依据时允许回答通用问题，包括非 Java 问题，不生成课程引用。 |
| 代码提问 | 只有活动项目、编辑器和扩展名为 `.java` 的文件中存在非空白选区时，右键 **Ask CourseMate AI** 才启用。 |
| 代码附件 | 只读代码预览、可编辑问题、显式发送及 **取消代码上下文**；仅打开窗口不会发送代码。 |
| 请求生命周期 | 使用后台 `SwingWorker`、等待提示、防重复发送；失败后保留输入和附件，供手动重试，成功后清除。 |
| 错误处理 | 中文提示覆盖 Key 缺失/格式错误、超时、连接失败、HTTP 401/402/429 等 API 错误，以及回答格式或引用错误；不会自动重试 API。 |

聊天记录只保留在当前窗口中，**不会**随请求发送历史轮次，也不会持久化为聊天历史。插件不执行或修改代码，不读取 ZIP 项目，不索引源码仓库，不提供流式输出，也不做 OCR。

## 3. 系统要求

| 组件 | 已确认配置 |
| --- | --- |
| 目标 IDE | IntelliJ IDEA **2026.2.3**，SDK build **262.10968.63** |
| 声明的兼容范围 | `since-build=262.10968.63`、`until-build=262.*`；范围内其他版本仍需实际验证 |
| Java | 工具链和编译目标均为 **JDK 25**；Java 17 不足以构建 |
| 构建 | Gradle Wrapper **9.1.0**、IntelliJ Platform Gradle Plugin **2.19.0** |
| 业务库 | Gson **2.13.2**、Apache PDFBox **3.0.8** |
| 测试 | JUnit BOM **5.13.4** 下的 Jupiter/Vintage、JUnit **4.13.2**、对应 SDK 的 IntelliJ Platform 测试框架 |
| 其他 | 克隆时需要 Git；首次下载依赖及真实 DeepSeek 调用需要网络；真实问答需要个人 API Key |

下列命令面向 Windows PowerShell；其他操作系统未在此验证。使用仓库内 Wrapper，不需要额外安装 Gradle 或 Maven。

## 4. Windows 配置、测试、构建与启动

### 4.1 获取项目

当前工作目录**没有 Git 仓库元数据，也没有可核实的远程地址**。请向小组索取真实 GitHub 地址，替换占位内容后执行：

~~~powershell
$repositoryUrl = 'https://github.com/REPLACE_OWNER/REPLACE_REPOSITORY.git'
git clone $repositoryUrl CourseMate-AI
Set-Location -LiteralPath '.\CourseMate-AI'
~~~

如果已有项目，跳过克隆，直接在含有 `gradlew.bat` 和 `build.gradle.kts` 的项目根目录打开 PowerShell。不要克隆覆盖现有工作目录。

### 4.2 选择自己的 IDEA 和 Java 安装路径

把示例路径替换为**你自己**安装的 IntelliJ IDEA 2026.2.3 根目录：

~~~powershell
$ideaHome = 'C:\Path\To\IntelliJ IDEA 2026.2.3'
$env:JAVA_HOME = Join-Path $ideaHome 'jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME = Join-Path (Get-Location).Path '.gradle-user-home'
java -version
javac -version
~~~

两个版本都应为 25。如果本机 IDEA 运行时没有 `javac`，将 `JAVA_HOME` 改为另行安装的 **JDK 25**。`$ideaHome` 仍指向 IDEA 根目录，不能指向 `bin` 或 `jbr`。

#### 配置本地 IntelliJ IDEA 路径

每位开发者都应在自己的电脑上将共享示例复制为个人配置。已有 `local.properties` 时，应先检查内容，不要直接覆盖：

~~~powershell
if (-not (Test-Path -LiteralPath '.\local.properties')) {
    Copy-Item -LiteralPath '.\local.properties.example' -Destination '.\local.properties'
}
notepad .\local.properties
~~~

在复制得到的文件中填写该电脑真实的 IDEA 安装根目录，并使用正斜杠。该目录应包含 `product-info.json`，不能指向它的 `bin` 或 `jbr` 子目录：

~~~properties
ideaLocalPath=C:/Path/To/IntelliJ IDEA 2026.2.3
~~~

`local.properties` 已被 `.gitignore` 忽略，不得提交到 Git。上面的路径有意使用通用占位符，不能把某位组员的安装路径当作全组默认路径。

Gradle 首先检查 `ideaLocalPath` Gradle 属性，例如 `-PideaLocalPath="$ideaHome"`；没有该属性时，本项目才从根目录 `local.properties` 读取 `ideaLocalPath`。非空路径会让 IntelliJ Platform Gradle Plugin 使用该位置已安装的 IDEA；两处都没有提供路径时，Gradle 会从已配置的软件仓库解析 IntelliJ IDEA 2026.2.3。后面的命令仍显式传入 `-PideaLocalPath`，因此该命令行值优先于 `local.properties`。

如果既没有显式 Gradle 属性，也没有非空的本地路径属性，构建会下载 IDEA 2026.2.3，体积可能较大；仍需使用 Java 25。

### 4.3 准备课程 PDF

从获准渠道单独获取讲义，直接放入 `course_files`，详见第 6 节。插件 ZIP 不包含讲义。完整测试中有真实课件集成测试，需要表中指定的讲义集合。

### 4.4 运行测试和构建

这一步不需要 API Key：

~~~powershell
.\gradlew.bat test "-PideaLocalPath=$ideaHome" --console=plain --no-daemon
.\gradlew.bat buildPlugin "-PideaLocalPath=$ideaHome" --console=plain --no-daemon
~~~

已验证的合并命令形式为：

~~~powershell
.\gradlew.bat test buildPlugin "-PideaLocalPath=$ideaHome" --console=plain --no-daemon
~~~

依赖缓存齐全后可以加入 `--offline`；离线模式不能下载缺失依赖。输出位置：

- 安装包：`build/distributions/coursemate-ai-0.4.0.zip`
- HTML 测试报告：`build/reports/tests/test/index.html`
- XML 测试记录：`build/test-results/test/TEST-*.xml`
- 讲义元数据报告：`build/reports/rag-validation.txt`

### 4.5 安全配置自己的 API Key

生产客户端读取 `System.getenv("DEEPSEEK_API_KEY")`。**项目没有 .env 加载器**，也没有插件内 Key 设置界面；新建 `.env` 并不能完成配置。

每位组员应独立申请和配置自己的 Key。不得把真实 Key 写入 Java、Gradle、README、`.env.example`、Git 提交、截图或聊天；不要发送给其他组员，也不要上传到 GitHub。

先**单独执行**这一行：

~~~powershell
$secret = Read-Host 'Enter your DeepSeek API Key (hidden input)' -AsSecureString
~~~

仅在 PowerShell 的隐藏输入提示处粘贴 Key 并回车，然后执行：

~~~powershell
$env:DEEPSEEK_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
Remove-Variable secret
if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    'Key is missing'
} else {
    'Key is configured; its value is not displayed'
}
~~~

这只设置当前终端及随后启动的子进程环境，不把 Key 写入项目，也不修改系统级环境变量。运行时环境中仍然需要明文字符串；隐藏输入不等于加密运行时存储。不要打印该变量，也不要导出整个进程环境。

### 4.6 启动沙盒

先关闭旧沙盒，再在**同一个** PowerShell 窗口执行：

~~~powershell
.\gradlew.bat runIde "-PideaLocalPath=$ideaHome" --console=plain --no-daemon
~~~

在新 IDEA 中打开或创建项目，然后选择 **View → Tool Windows → CourseMate AI**。只有欢迎页面时没有项目工具窗口。等待本地知识库摘要，输入问题，点击 Send 或 Ctrl+Enter。

保持终端开启；关闭沙盒后 `runIde` 才结束。已存在的 IDEA 进程不会自动继承新设置的环境变量。关闭沙盒后，可以清除当前终端中的 Key：

~~~powershell
Remove-Item Env:\DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
~~~

关闭该终端也会丢弃其环境。更换 Key 后需要重新启动沙盒。

### 4.7 打开源码或安装 ZIP

开发时在 IDEA 打开项目根目录，按 Gradle 导入，将 **Project SDK** 和 **Gradle JVM** 都设为 JDK 25，并使用项目 Wrapper。从旧 IDEA 的 Gradle 面板启动任务，可能拿不到新终端中的 Key；PowerShell 启动更容易复现。

安装到日常使用的兼容 IDEA：**Settings → Plugins → 齿轮 → Install Plugin from Disk**，选择 0.4.0 ZIP，按提示重启。该 IDEA 进程也需要 Key 环境和正确课件路径。只修改源码不会更新已经安装的 ZIP。

## 5. 选中 Java 代码提问

1. 打开 `.java` 文件，只选中你确实希望发送的代码。
2. 右键 **Ask CourseMate AI**，现有工具窗口会显示只读预览。
3. 输入框为空时填入 `Explain this code.`；已有问题保留。可以改为：
   - `Find potential bugs.`
   - `Explain how inheritance is used here.`
   - `Suggest improvements without modifying the code.`
4. 核对快照，点击 Send/Ctrl+Enter。选区是在 Action 执行时捕获的；之后修改编辑器内容，不会悄悄替换预览。
5. 发送前要取消附件，点击 **取消代码上下文**。不会发送请求，问题文字仍可编辑。
6. 成功后问题和附件清除；失败后保留供显式重试。正在回答时附加新代码会被拒绝，并提示等待。

只附加选中的文字，不自动读取未选中代码和其他文件。代码被视为分析数据，不执行其中的指令。RAG 检索依据是**用户的问题**，不是选区里的变量名或注释；希望结合课件时，请在问题中写明 `inheritance`、`generics` 等课程概念。

预览在成功、取消或面板销毁时清除。聊天显示只注明附件字符数，不自动复制整段选区；回答本身仍可能引用代码，因为这属于解释内容。

## 6. 课程资料、路径与来源

已检查的本地资料共 **12 份 PDF**，其中 **9 份讲义 / 199 个 PDF 页面 / 200 个文本块**纳入索引。2026-10-25 已核对元数据报告，以及与当前原始 PDF 相符的缓存指纹；这些不是其他组员任意资料集的固定计数。

| 纳入的讲义 | 页数 | 文本块 |
| --- | ---: | ---: |
| `Lec-0-Introduction.pdf` | 4 | 5 |
| `Lec-1-Java-Fundamentals.pdf` | 34 | 34 |
| `Lec-2-OOP-with-Java.pdf` | 32 | 32 |
| `Lec-3.1-Exceptions.pdf` | 11 | 11 |
| `Lec-3.2-Generics-and-Collections.pdf` | 17 | 17 |
| `Lec-3.3-ModernSyntax-Annotations-and-Reflection.pdf` | 16 | 16 |
| `Lec-3.4-JUnit.pdf` | 7 | 7 |
| `Lec-4-AI-Assisted-Java-Development.pdf` | 34 | 34 |
| `Lec-5-Building-AI-Powered-Java-Applications.pdf` | 44 | 44 |

排除 `AY202627S1-CST3492-Assignment-A.pdf`、`AY202627S1-CST3492-Assignment-A-Schedule.pdf`、`AY202627S1-CST3492-Programming-Exam.pdf`。根目录的作业 PDF 不参与检索。

白名单为 `(?i)^Lec-[0-5](?:[.-].*)?\.pdf$`。只扫描指定目录的直接文件，不扫描子目录。其他讲义需要明确修改白名单，不是任意文件名都会入库。

课件路径优先级：

1. 环境变量 `COURSEMATE_COURSE_FILES`。
2. JVM 属性 `coursemate.courseDir`；`runIde` 会将它设为当前检出目录中的 `course_files`。
3. 当前打开项目的 `course_files`；没有项目基础路径时回退到工作目录相对路径。

独立安装插件时，可在启动 IDEA **之前**配置路径，替换占位内容：

~~~powershell
$env:COURSEMATE_COURSE_FILES = 'C:\Path\To\CourseMate-AI\course_files'
~~~

实际路径会显示在欢迎文字中。缓存位于课件目录同级的 `.coursemate/index.json`，包含提取后的正文和元数据，而非只有统计数字，不能公开发布。程序通过文件名、大小和修改时间避免重复解析；新建/修改的文件重新解析，已删除文件移出索引，**重建课件索引**按钮可强制更新。如果内容改变但大小、时间均完全相同，请手动重建。损坏、受限或无文字 PDF 会警告并排除，部分空白页跳过；不进行 OCR。

检索采用 BM25（最低 **0.25**）、主题加权覆盖率（最低 **50%**）、中文术语映射和 RAG 缩写分组，并过滤可识别的封面、课程日程和纯参考文献页。它仍是启发式关键词检索，改写问题可能导致漏检或低相关匹配。

有依据的回答显示课程正文及 Java 生成的引用；页码指从 1 开始的实际 PDF 页序。通用补充单独标记。没有足够依据时不发送课程片段，界面显示：

> Response is based on general knowledge; no relevant course material was found.  
> [通用知识补充；未找到相关课程资料]

成功的通用回答不显示课程引用或未使用候选页。API 失败仍是错误，而不是“无匹配”。文件名和来源 ID 校验**不能**证明模型的每句话都有事实依据。

### 课件集冒烟检查

对于当前讲义集，现有检索回归的预期是：

- `Java inheritance`：OOP 讲义第 19 页排在首位，其他相关页可能只支持某些具体比较。
- `Java generics`：相关候选来自 `Lec-3.2-Generics-and-Collections.pdf` 第 3、4、8 页。
- `RAG retrieval augmented generation`：命中 Lec-5 讲义。
- `Explain quantum entanglement and photon polarization measurements in simple terms.`：没有 Java 讲义依据；真实回答若成功，应显示通用知识标记，不显示课程引用。

这些是回归/人工检查预期，不是新观察到的真实模型回答。应打开实际引用的 PDF 页核对内容，并通过重建按钮检查索引，无需修改原始资料。

资料缺失时请自行取得原件。当前 `.gitignore` 已忽略根目录下整个 `course_files/`，以及根目录中三个被明确点名的作业/考试 PDF。**这只是 Git 安全规则；如果要主动将课程 PDF 放入任何仓库，或将其片段发送给外部模型，仍须先确认老师允许相应分享和使用。** 私有仓库本身不代表获得了再分发许可；对于已经被 Git 跟踪的文件，新增忽略规则也不会自动将其移出版本控制。

## 7. 测试、构建记录及验证边界

现有 XML 记录共 **87 项测试：87 通过、0 失败、0 错误、0 跳过**。各套件开始时间为 **2026-10-25 12:53:39.793–12:53:41.106 UTC**，即 **UTC+8 的 20:53:39.793–20:53:41.106**；报告约在 **UTC+8 的 20:53:46** 写入。

本次文档检查日 **2026-10-25** 执行下列现有命令，得到 **BUILD SUCCESSFUL**，且 `test`、`buildPlugin` 均为 **UP-TO-DATE**，表明 Gradle 认为原记录和安装包仍适用于当前输入。**这不是本次重新执行了全部 87 项测试**：

~~~powershell
.\gradlew.bat test buildPlugin --offline --console=plain --no-daemon
~~~

| 测试套件 | 用例数 |
| --- | ---: |
| `AskCourseMateActionTest` | 5 |
| `DeepSeekClientTest` | 43 |
| `CourseKnowledgeBaseTest` | 4 |
| `KeywordIndexTest` | 7 |
| `PdfCourseReaderTest` | 4 |
| `RagAnswerServiceTest` | 14 |
| `RealCourseRetrievalTest` | 10 |

覆盖真实 IntelliJ 编辑器/Action 模型、模拟 HTTP 请求与错误、PDF 解析和分块元数据、缓存和重建、真实讲义检索、无匹配状态及代码上下文传递。测试环境会移除 `DEEPSEEK_API_KEY` 并使用假凭据，**不会调用真实或付费 DeepSeek**。

团队先前报告过 0.3.x 的真实插件启动、继承/泛型引用和合理的通用物理解释，同时发现了误匹配和文案问题。这些是用户报告的运行观察，不是新一次 0.4.0 端到端验收。真实 API 回答、菜单展开、窗口聚焦、取消和失败重试仍需分别记录人工结果，详见 [HANDOVER_CN.md](HANDOVER_CN.md)。

## 8. 故障排查

| 现象 | 检查方法 |
| --- | --- |
| Java/版本报错 | 确认 `java -version`、`javac -version` 均为 25；还要设置 Gradle JVM，不只是 Project SDK。 |
| 本地 IDE 路径失效 | 路径应指向包含 `product-info.json` 的 IDEA 根目录；显式 `-PideaLocalPath` 可覆盖个人设置。 |
| Key 未读取 | 在启动终端中配置，再从同一终端重启沙盒；不会读取 `.env`。 |
| HTTP 401 | 核实自己的 DeepSeek 开放平台 Key，重新配置并重启，不分享或打印 Key。 |
| HTTP 402 | 检查 API 账户余额；能使用网页聊天不代表 API 余额充足。 |
| HTTP 429 / 5xx | 等待后手动重试，插件不进行后台自动重试。 |
| 超时/连接失败 | 连接最长 15 秒、请求最长 120 秒。检查网络、DNS、代理、防火墙和服务状态；IDEA 代理不一定适用于 Java HttpClient。不要关闭 TLS 验证。 |
| HTTP 400/422/404 | 缩短问题，按服务官方文档核对配置的模型和接口。 |
| PDF 失败/无文字 | 检查权限、白名单、损坏/加密情况和文字可提取性；使用获准的可复制文字 PDF，扫描件需另行 OCR，然后重建。 |
| 知识库为空 | 看工具窗口实际路径并检查第 6 节；仍可进行带警告的通用问答。 |
| 没有相关资料 | 可尝试明确的课程术语。无匹配是正常的通用知识状态，不是 API 错误。 |
| 工具窗口不出现 | 在新启动或已更新的 IDEA 中打开项目，再使用 View → Tool Windows。 |
| 右键菜单不出现/不可用 | 确认已加载 0.4.0、扩展名为 `.java`、当前选区非空白；初始化完成后重试。 |
| 回答/来源格式不合法 | 保护逻辑拒绝了错误 JSON 或未经验证的引用，可缩小任务重试。该规则较保守，即使部分内容有用，也可能拒绝模型正文。 |
| Gradle 无法连接 daemon | 检查本机进程通信限制或安全软件，区别于 DeepSeek 认证问题。 |
| 首次依赖下载失败 | 检查仓库网络访问；离线模式需要 SDK 和测试依赖已经缓存。 |
| 新克隆目录完整测试失败 | `RealCourseRetrievalTest` 需要指定的 9 份讲义，并断言已知资料集特征，不是与资料集无关的通用冒烟测试。 |

## 9. 项目维护与后续工作

当前 `.gitignore` 已忽略 `.env` 和 `.env.*`，同时明确允许经过检查且不含凭据的 `.env.example`；插件本身不会读取 `.env` 文件。它还忽略个人 `local.properties`、`.gradle/`、`.gradle-user-home/`、`build/`、`.intellijPlatform/`、`.coursemate/`、`.idea/`、`*.iml`、旧式 IDEA 个人文件、根目录下整个 `course_files/`，以及根目录中三个被明确点名的作业/考试 PDF。规则有意不使用笼统的 `*.pdf`、`*.jar` 或 `*.zip`，以便保留必须提交的 Gradle Wrapper 文件和经批准的项目产物。共享前应检查所有暂存文件，确认其中没有凭据、个人路径、未经许可的课程资料、生成缓存或个人 IDE 状态。新增忽略规则**不会**自动取消对已经加入或提交文件的跟踪。

UI 优化、语义/混合检索、ZIP/源码索引、多轮对话、流式输出和更强的回答质量检查均为可选后续工作，不是已经交付的功能。内部风险、架构、协作和最终验收清单见 [HANDOVER_CN.md](HANDOVER_CN.md)；两份交接文档都不能替代课程最终英文报告和展示材料。