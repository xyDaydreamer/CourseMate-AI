# CourseMate AI

[中文说明](README_CN.md) · [Internal handover](HANDOVER.md)

Version **0.4.0** · CST3492 Java Enterprise Application Development · Team **T07**, **Group A**

## 1. Overview

CourseMate AI is an IntelliJ IDEA plugin that helps students understand Java through teacher-provided PDF lectures and DeepSeek AI. It combines local retrieval-augmented generation (RAG), source filenames and PDF page numbers, and questions about explicitly selected Java code.

This is a student MVP. The features below exist in the code, but automated checks are not a substitute for testing the actual IDE interactions and live model answers. No application code or dependencies were changed for this documentation update.

## 2. Implemented features and boundaries

| Feature | Current behavior |
| --- | --- |
| IDE chat | Right-side **CourseMate AI** tool window, multiline input, Send, Ctrl+Enter, and read-only conversation display. Blank questions are ignored. |
| DeepSeek | HTTPS POST to `https://api.deepseek.com/chat/completions` using configured model `deepseek-flash`, JSON, `stream=false`, thinking disabled, and `max_tokens=2048`. This describes the configuration, not a guarantee of future model availability. |
| PDF knowledge base | Reads approved lecture filenames from a local folder, extracts text per PDF page, and creates chunks and a local searchable index. No paid embedding service is used. |
| Bounded context | At most four chunks from different PDF pages are selected; each is at most 1,000 Java string characters. It can send fewer or none. |
| Course references | Java validates model-selected source IDs against retrieved chunks and renders their original filenames and one-based PDF page numbers. |
| General knowledge | Insufficiently related candidates are excluded before the API call. With no evidence, the model may answer general questions, including non-Java questions, without course citations. |
| Code questions | **Ask CourseMate AI** in the editor context menu is enabled only for a nonblank selection in a file with a `.java` extension and a live project/editor. |
| Code attachment | Read-only selection preview, editable question, explicit send, and **取消代码上下文** (“Cancel code context”). No code is sent just by opening the window. |
| Request lifecycle | Background `SwingWorker` requests, waiting status, disabled duplicate sending, and manual retry with input/attachment retained after failure. Success clears them. |
| Errors | Chinese messages cover missing/invalid Key, timeout, connectivity, HTTP 401/402/429 and other API errors, malformed answers, and invalid citations. No automatic API retry. |

Visible conversation history stays in the current window; previous turns are **not** included in requests or saved as chat history. The plugin does not execute or edit code, read ZIP projects, index source repositories, provide streaming output, or perform OCR.

## 3. Requirements

| Component | Confirmed configuration |
| --- | --- |
| Target IDE | IntelliJ IDEA **2026.2.3**, SDK build **262.10968.63** |
| Declared plugin compatibility | `since-build=262.10968.63`, `until-build=262.*`; other builds in that range still need practical validation |
| Java | **JDK 25**, both the toolchain and compiler release; Java 17 is insufficient |
| Build | Gradle Wrapper **9.1.0**, IntelliJ Platform Gradle Plugin **2.19.0** |
| Libraries | Gson **2.13.2**, Apache PDFBox **3.0.8** |
| Tests | JUnit Jupiter/Vintage via BOM **5.13.4**, JUnit **4.13.2**, and the matching IntelliJ Platform test framework |
| Other | Git for cloning, network access for first-time dependencies and live DeepSeek calls, and an individually configured API Key for live use |

The supplied commands target Windows PowerShell. Other operating systems have not been validated here. Use the included Wrapper; a separate Gradle or Maven installation is unnecessary.

## 4. Windows setup, tests, build, and launch

### 4.1 Obtain the project

This working folder has **no Git repository metadata or verified remote URL**. Ask the team for the actual GitHub URL. Replace the placeholder before running:

~~~powershell
$repositoryUrl = 'https://github.com/REPLACE_OWNER/REPLACE_REPOSITORY.git'
git clone $repositoryUrl CourseMate-AI
Set-Location -LiteralPath '.\CourseMate-AI'
~~~

If you already have the project, skip cloning. Open PowerShell in its root, where `gradlew.bat` and `build.gradle.kts` are located. Do not clone over an existing working folder.

### 4.2 Select your own IDE and Java installation

Replace the example path with **your** installed IntelliJ IDEA 2026.2.3 folder:

~~~powershell
$ideaHome = 'C:\Path\To\IntelliJ IDEA 2026.2.3'
$env:JAVA_HOME = Join-Path $ideaHome 'jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME = Join-Path (Get-Location).Path '.gradle-user-home'
java -version
javac -version
~~~

Both commands must report version 25. If your IDE runtime lacks `javac`, set `JAVA_HOME` to a separately installed **JDK 25**. Keep `$ideaHome` pointing to the IDE root, not `bin` or `jbr`.

#### Local IntelliJ IDEA path configuration

On each developer's computer, copy the shared example to the private local file. Do not overwrite an existing `local.properties` without reviewing it first:

~~~powershell
if (-not (Test-Path -LiteralPath '.\local.properties')) {
    Copy-Item -LiteralPath '.\local.properties.example' -Destination '.\local.properties'
}
notepad .\local.properties
~~~

Set the copied file to the real IDEA installation root on that computer, using forward slashes. The directory must be the IDEA folder that contains `product-info.json`, not its `bin` or `jbr` subfolder:

~~~properties
ideaLocalPath=C:/Path/To/IntelliJ IDEA 2026.2.3
~~~

`local.properties` is ignored by `.gitignore` and must not be committed. The placeholder above is intentionally generic; never treat another member's installation path as the team default.

Gradle first checks the `ideaLocalPath` Gradle property, such as `-PideaLocalPath="$ideaHome"`. If it is absent, this build reads `ideaLocalPath` from the root `local.properties`. A nonblank value selects that installed IDEA through the IntelliJ Platform Gradle Plugin; if neither setting supplies a path, Gradle resolves IntelliJ IDEA 2026.2.3 from the configured repositories. The commands below keep passing `-PideaLocalPath` explicitly, so that value takes precedence over `local.properties`.

Without an explicit Gradle property or a nonblank local property, the build downloads IDEA 2026.2.3. This can be large; Java 25 is still required.

### 4.3 Supply the lecture PDFs

Obtain authorized copies separately and place the expected lectures directly in `course_files`. See Section 6. PDFs are not embedded in the plugin ZIP. The full test suite includes real-corpus tests requiring the listed lecture set.

### 4.4 Run tests and build

No API Key is needed:

~~~powershell
.\gradlew.bat test "-PideaLocalPath=$ideaHome" --console=plain --no-daemon
.\gradlew.bat buildPlugin "-PideaLocalPath=$ideaHome" --console=plain --no-daemon
~~~

The verified combined form is:

~~~powershell
.\gradlew.bat test buildPlugin "-PideaLocalPath=$ideaHome" --console=plain --no-daemon
~~~

After dependencies are cached, `--offline` may be added. It cannot fetch missing dependencies. Outputs:

- Installer: `build/distributions/coursemate-ai-0.4.0.zip`
- HTML test report: `build/reports/tests/test/index.html`
- XML test records: `build/test-results/test/TEST-*.xml`
- Lecture metadata report: `build/reports/rag-validation.txt`

### 4.5 Configure your own API Key safely

The production client reads `System.getenv("DEEPSEEK_API_KEY")`. **There is no .env loader** and no plugin Key settings screen. Creating `.env` does not configure the client.

Each member should obtain and configure their own Key. Never write a real Key into Java, Gradle, README, `.env.example`, Git commits, screenshots, or chat; do not send it to other team members or upload it to GitHub.

Run this first line **on its own**:

~~~powershell
$secret = Read-Host 'Enter your DeepSeek API Key (hidden input)' -AsSecureString
~~~

Paste the Key only at the hidden PowerShell prompt, then press Enter. Next run:

~~~powershell
$env:DEEPSEEK_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
Remove-Variable secret
if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
    'Key is missing'
} else {
    'Key is configured; its value is not displayed'
}
~~~

This sets the current terminal's environment and that of subsequently launched child processes. It does not write the Key to the project or change system-wide environment settings. The environment necessarily contains a plaintext string in process memory; hidden input is not encrypted runtime storage. Do not print the variable or dump the process environment.

### 4.6 Start the sandbox

Close the old sandbox first. In the **same** PowerShell window:

~~~powershell
.\gradlew.bat runIde "-PideaLocalPath=$ideaHome" --console=plain --no-daemon
~~~

Open or create a project in the new IDE, then choose **View → Tool Windows → CourseMate AI**. The welcome screen alone has no project tool window. Wait for the local index summary, enter a question, and click Send or Ctrl+Enter.

Keep the terminal open; `runIde` ends when the sandbox closes. Existing IDE processes do not automatically inherit newly set environment variables. After closing the sandbox, clear the terminal's Key:

~~~powershell
Remove-Item Env:\DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
~~~

Closing that terminal also discards its environment. Key changes require restarting the sandbox.

### 4.7 Open source or install the ZIP

For development, open the project root in IDEA, import Gradle, select JDK 25 for both **Project SDK** and **Gradle JVM**, and use the project's Wrapper. A Gradle task started from an old IDEA process may not inherit a Key set in a newer terminal; the PowerShell launch is the simplest reproducible path.

To install into your daily compatible IDEA: **Settings → Plugins → gear → Install Plugin from Disk**, select the 0.4.0 ZIP, and restart if requested. That IDE process also needs the Key environment and a correct course folder. Updating source alone does not update an already installed ZIP.

## 5. Asking about selected Java code

1. Open a `.java` file and select only the code you intend to send.
2. Right-click **Ask CourseMate AI**. A read-only preview appears in the existing tool window.
3. An empty question field receives `Explain this code.`; existing question text is retained. Edit it, for example:
   - `Find potential bugs.`
   - `Explain how inheritance is used here.`
   - `Suggest improvements without modifying the code.`
4. Review the snapshot and click Send/Ctrl+Enter. Selection text is captured when the Action runs; later editor edits do not silently change the preview.
5. To cancel the attachment before sending, click **取消代码上下文**. No request is sent; question text remains editable.
6. After success, the attachment and question are cleared. After failure, they remain for an explicit retry. Attaching new code during an active answer is rejected with a wait message.

Only the selected text is attached; unselected code and other project files are not automatically read. Code is analysis data, not executable instructions. RAG searches **the user's question**, not variable names/comments in the selection. Include a course concept such as `inheritance` or `generics` in the task when course references are wanted.

The preview is cleared on success, cancellation, or panel disposal. The conversation display records attachment character count rather than automatically copying the whole selection. Answers can still quote code because that is part of explaining it.

## 6. Course files, paths, and references

The inspected local corpus contains **12 PDFs**, of which **9 lectures / 199 PDF pages / 200 chunks** are indexed. Counts were checked against the metadata report and cached document fingerprints matching the current original PDFs on **2026-10-25**; these are not guaranteed counts for another member's files.

| Included lecture | Pages | Chunks |
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

Excluded: `AY202627S1-CST3492-Assignment-A.pdf`, `AY202627S1-CST3492-Assignment-A-Schedule.pdf`, and `AY202627S1-CST3492-Programming-Exam.pdf`. Root-level assignment PDFs are not searched.

The whitelist is `(?i)^Lec-[0-5](?:[.-].*)?\.pdf$`. Only direct files in the configured folder are scanned, not subfolders. Other lectures require a deliberate whitelist change; arbitrary filenames are not automatically indexed.

Course-folder precedence:

1. `COURSEMATE_COURSE_FILES` environment variable.
2. JVM property `coursemate.courseDir`; `runIde` sets this to this checkout's `course_files`.
3. The open project's `course_files`; with no project base path, a working-directory-relative fallback.

For a separately installed plugin, optionally set this **before starting IDEA**, replacing the placeholder:

~~~powershell
$env:COURSEMATE_COURSE_FILES = 'C:\Path\To\CourseMate-AI\course_files'
~~~

The selected path appears in the welcome text. The cache is `.coursemate/index.json` beside the course folder. It contains extracted text and metadata, not just statistics; do not publish it. Filename/size/modification-time checks avoid re-parsing unchanged PDFs. Changed/new files are parsed, removed files are dropped, and **重建课件索引** forces a rebuild. If content changes while size and timestamp stay identical, use that button. Broken, restricted, or textless PDFs are excluded with a warning; partial blank pages are skipped. No OCR is performed.

Retrieval uses BM25 (minimum **0.25**) plus weighted topic coverage (minimum **50%**), Chinese terminology mapping and RAG acronym grouping. Recognized cover, schedule, and reference-only pages are filtered. This is heuristic keyword retrieval: wording changes can cause misses or weak matches.

A supported answer shows course text and Java-rendered references; page numbers mean physical PDF pages starting at 1. General supplements are labeled separately. If no sufficient evidence is found, no course snippets are sent and the UI displays:

> Response is based on general knowledge; no relevant course material was found.  
> [通用知识补充；未找到相关课程资料]

A successful general answer has no course citations or unused-candidate list. API failures remain errors, not “no match.” Filenames/page IDs are validated, but this does **not** prove every model statement is factually supported.

### Corpus smoke checks

For the supplied lecture set, the existing retrieval regressions expect:

- `Java inheritance`: OOP lecture page 19 ranks first; other related pages may support narrower comparisons.
- `Java generics`: relevant candidates in `Lec-3.2-Generics-and-Collections.pdf` pages 3, 4 and 8.
- `RAG retrieval augmented generation`: a result from the Lec-5 lecture.
- `Explain quantum entanglement and photon polarization measurements in simple terms.`: no Java lecture evidence; a successful live reply should use the general-knowledge label and no course citations.

These are regression/manual-check expectations, not newly observed live model answers. Open the actually cited PDF pages to judge support, and use the rebuild button to check indexing without modifying originals.

Obtain the originals yourself if they are absent. The current `.gitignore` excludes the entire root `course_files/` directory and the three specifically named assignment/exam PDFs at the repository root. **This is only a Git safety rule: confirm the teacher's sharing permission before deliberately adding course PDFs to any repository or sending their extracts to an external model.** A private repository does not itself grant redistribution permission, and an ignore rule does not remove a file that Git was already tracking.

## 7. Verified test/build record and limits

The current XML records contain **87 tests: 87 passed, 0 failures, 0 errors, 0 skipped**. Suite start timestamps are **2026-10-25 12:53:39.793–12:53:41.106 UTC**, or **20:53:39.793–20:53:41.106 UTC+8**; reports were written around **20:53:46 UTC+8**.

During this documentation inspection on **2026-10-25**, the following existing command returned **BUILD SUCCESSFUL** with `test` and `buildPlugin` marked **UP-TO-DATE**, confirming Gradle considers the records/artifact applicable to the current inputs. It was **not a fresh execution of all 87 tests**:

~~~powershell
.\gradlew.bat test buildPlugin --offline --console=plain --no-daemon
~~~

| Suite | Cases |
| --- | ---: |
| `AskCourseMateActionTest` | 5 |
| `DeepSeekClientTest` | 43 |
| `CourseKnowledgeBaseTest` | 4 |
| `KeywordIndexTest` | 7 |
| `PdfCourseReaderTest` | 4 |
| `RagAnswerServiceTest` | 14 |
| `RealCourseRetrievalTest` | 10 |

Coverage includes real IntelliJ editor/Action model tests, simulated HTTP requests and errors, PDF parsing/chunk metadata, caches and rebuilding, actual-corpus retrieval, no-match handling, and code-context request propagation. Tests remove `DEEPSEEK_API_KEY` from the test environment and use synthetic credentials. They do **not** call paid/live DeepSeek.

The team previously reported real plugin startup, inheritance/generics references, and a reasonable general physics answer in 0.3.x, alongside false candidates and wording issues. These are user-reported observations, not fresh 0.4.0 end-to-end sign-off. Real API answers, menu expansion, window focus, cancellation, and failure/retry interactions still need separately recorded manual acceptance. See [HANDOVER.md](HANDOVER.md).

## 8. Troubleshooting

| Symptom | What to check |
| --- | --- |
| Java/version error | Confirm `java -version` and `javac -version` are 25; also set Gradle JVM, not only Project SDK. |
| Wrong local IDE path | Use the IDE root containing `product-info.json`. The explicit `-PideaLocalPath` overrides private local settings. |
| Key missing | Configure it in the launch terminal and restart the sandbox from that same terminal. A `.env` file is not read. |
| HTTP 401 | Check your own DeepSeek platform Key; reconfigure and restart. Do not share or print it. |
| HTTP 402 | Check API-account balance; access to a web chat does not establish API balance. |
| HTTP 429 / 5xx | Wait and retry manually. No background retry is performed. |
| Timeout/connectivity | Connection timeout is 15 seconds; request timeout is 120 seconds. Check network, DNS, proxy, firewall, and service status. IDEA proxy settings may not apply to Java HttpClient. Do not disable TLS verification. |
| HTTP 400/422/404 | Shorten the question; check configured endpoint/model against official service documentation. |
| PDF fails / no text | Check permissions, the whitelist, corruption/encryption, and text extractability. Use authorized text PDFs; scanning needs separate OCR. Then rebuild. |
| Knowledge base empty | Read the actual path in the tool window and check Section 6. General answers are still possible, with a warning. |
| No related material | Try an explicit course term. No match is a normal general-knowledge state, not an API failure. |
| Tool window absent | Open a project in the newly launched/updated IDE, then use View → Tool Windows. |
| Context menu absent/disabled | Verify 0.4.0 is loaded, the file extension is `.java`, and the active selection is nonblank. Reopen the window or retry after initialization. |
| Invalid answer/source format | The guard rejected malformed JSON or unverified citations. Retry with a smaller task. The guard is conservative; model prose can fail even when some content is useful. |
| Gradle cannot connect to daemon | Check restrictions on local process communication/security software. This is separate from DeepSeek authentication. |
| First dependency download fails | Check repository access/network. Offline mode works only after the required SDK and test dependencies are cached. |
| Full tests fail on a new checkout | `RealCourseRetrievalTest` requires the expected nine lectures and asserts their known corpus characteristics; it is not a corpus-independent smoke test. |

## 9. Project hygiene and further work

The current `.gitignore` excludes `.env` and `.env.*` while explicitly allowing a reviewed, credential-free `.env.example`; the plugin itself does not load `.env` files. It also excludes the private `local.properties`, `.gradle/`, `.gradle-user-home/`, `build/`, `.intellijPlatform/`, `.coursemate/`, `.idea/`, `*.iml`, legacy per-user IDEA files, the complete root `course_files/` directory, and the three specifically named assignment/exam PDFs at the repository root. It intentionally does not apply blanket `*.pdf`, `*.jar`, or `*.zip` rules, so required Wrapper files and approved project artifacts can remain shareable. Before sharing, inspect the staged files and verify that no tracked file contains credentials, private paths, unauthorized course material, generated caches, or personal IDE state. Adding an ignore rule does **not** automatically stop tracking a file that was already added or committed.

UI refinement, semantic/hybrid retrieval, ZIP/source indexing, multi-turn history, streaming, and stronger answer-quality checks are optional future work, not delivered capabilities. Internal risks, architecture, team workflow, and the final acceptance checklist are in [HANDOVER.md](HANDOVER.md). Neither handover file is the final English course report or presentation.