# CourseMate AI 0.5.3

An IntelliJ IDEA Java course assistant using local lecture PDFs, BM25 retrieval and the official DeepSeek API. It explains explicitly selected code, supports contextual follow-ups and displays the course excerpts actually cited.

**Current status:** build, automated tests and retrieval regressions pass. Live-answer quality is only partially accepted; concurrency and exception explanations can still contain factual errors.

## Changes in 0.5.3

- Integrates the teammate's `lxh_0.5.3` commit `d9bebb4`: continuous Markdown answers, tolerant response parsing and an explicit notice when no course material is cited.
- Keeps BM25 and separates core subjects, question intent and attributes for ranking. B12 was a ranking failure: its labelled page moved from candidate rank 8 to evidence rank 1. No B12-, Reflection- or filename-specific rule was added.
- Rejects fractional, overflowing and malformed source IDs while preserving valid integer/string compatibility. A `length` finish reason remains truncated even when its JSON parses.
- Distinguishes complete inner statements from omitted enclosing braces in code-explanation prompts. On the final artifact, the original question and a new callback example both passed targeted live checks.
- Format/citation failures may trigger **one automatic retry**. Network, HTTP and truncated responses are not automatically retried.

## Verification — 2026-10-27

| Check | Result |
| --- | --- |
| Unit tests | 163/163 passed |
| Real-PDF integration tests | 14/14 passed using 9 lecture PDFs |
| Plugin packaging | Successful; `build/distributions/coursemate-ai-0.5.3.zip` |
| Six requested retrieval groups | All passed, covering 12 cases; Chinese/English B12 page 15, concept page 9 and invocation page 14 all rank first |
| Existing 28 / former frozen 24 cases | No regression; positive Hit@3 18/18 and 14/14 respectively |
| Second pre-fixed retrieval set | Positive Hit@3 6/6; no-source 4/4; developer-authored, not an independent blind test |
| Live API checks | 31 HTTP requests including one recovered format retry; no HTTP/network failures |

The 31 calls are a request count, not 31 correct answers. The additional concurrency/exception prompt did not show stable benefit and was reverted. Wrong atomicity, null-behavior or implementation-specific assertions remain a known limitation. A valid citation ID does not establish factual correctness.

Evidence: [verification and artifact fingerprint](evaluation/verification-0.5.3.json), [retrieval review](evaluation/review-0.5.3.md), [live-answer review](evaluation/live-review-0.5.3.md), and [final live-check screenshot](evaluation/evidence-053/live-content-final.jpg). Original B12 failure records are preserved. Raw live responses containing course text stay local under the ignored `.coursemate/acceptance/` directory.

Usage in Chinese: [README_CN.md](README_CN.md). Reproduction and historical results: [evaluation/README.md](evaluation/README.md). The cumulative 0.5.2 history is archived in [README_CHANGES.md](README_CHANGES.md).

## Build and run

Requires JDK 25 and IntelliJ IDEA 2026.2.3 (build range 262.10968.63–262.*). Use the Gradle wrapper. `local.properties` or `-PideaLocalPath=...` can select an installed IDE. The bundled Java plugin is an explicit dependency; no new external parser or embedding service is used.

```powershell
.\gradlew.bat test --console=plain --no-daemon
.\gradlew.bat integrationTest --console=plain --no-daemon
.\gradlew.bat buildPlugin --console=plain --no-daemon
```

Unit tests require no teacher PDFs or API key. The separate `integrationTest` task preserves the ten real-course cases and adds four bilingual intent regressions; it requires the nine matching lecture PDFs. Both tasks remove real keys from their test environments. The plugin archive is `build/distributions/coursemate-ai-0.5.3.zip`.

Use the existing `start-coursemate.cmd` launcher, or set `DEEPSEEK_API_KEY` securely in a terminal and launch `runIde` from that terminal. The plugin does not load `.env`. Never commit or paste credentials into chat. Installing a new ZIP requires restarting the compatible IDE; an already-running IDE does not automatically reload changed source or environment variables.

### Windows double-click launch

1. Install **IDEA 2026.2.3** and a full **JDK 25**. The launcher uses `JAVA_HOME`, or the selected IDE's bundled JBR 25 if it includes `javac.exe`.
2. Double-click **[start-coursemate.cmd](start-coursemate.cmd)** in the repository root. If asked, enter the IDE installation directory containing `product-info.json`. Alternatively, copy `local.properties.example` to `local.properties` and set your local IDE path.
3. If no process API key is available, enter it at the hidden terminal prompt. Empty input opens the interface without model access. The launcher does not permanently save this key.
4. The launcher builds the plugin and opens an IDEA test instance with the bundled **`demo/` Java project** and CourseMate AI tool window. Ask a question, or select code and use **Ask CourseMate AI**, then review the snapshot and send.

The first run may download Gradle/dependencies. Add your lecture PDFs to `course_files/`, or set `COURSEMATE_COURSE_FILES` / pass `-CourseDir`. Close an older CourseMate test instance before starting a new version.

For a desktop icon, right-click the CMD file and choose **Send to → Desktop (create shortcut)**. To open an existing Java project, append `-ProjectDir` to that shortcut's Target, replacing these example paths:

```text
"C:\Projects\CourseMate-AI\start-coursemate.cmd" -ProjectDir "C:\Projects\MyJavaApp"
```

Optionally append `-CourseDir "C:\CourseFiles"`. Automatic SDK setup applies only to the bundled demo; existing user projects keep their SDK settings. To develop the plugin itself, open the repository root as a Gradle project in your own IDE.

## Course files

Set `COURSEMATE_COURSE_FILES`, the `coursemate.courseDir` JVM property, or the project's `course_files` folder. Only direct lecture PDFs matching `(?i)^Lec-[0-5](?:[.-].*)?\.pdf$` are indexed. Assignment/exam files are excluded; OCR is not performed.

Cache v3 retains newlines and stores stable chunk IDs, physical PDF pages, normalized page-text offsets, headings, block kinds, neighbor IDs and document fingerprints. Old caches rebuild automatically. The cache contains course text and must not be published. Each course path gets a separate cache file under the adjacent `.coursemate` folder.

## Interaction

Ask in the CourseMate AI tool window, or select Java code and use **Ask CourseMate AI**. Review the snapshot before sending. PSI analysis parses only selected text; incomplete fragments use a restricted lexical fallback. Unseen method implementations are not inferred from their names.

Each turn has an independent Swing card with question, selected-code disclosure, answer, copy/translation controls and a fixed course-source region. Retrieval candidates and actually used citations are distinct. Citations show original excerpts, stable chunk IDs and PDF pages; clicking attempts to open the exact page using the default reader/browser. The PDF fingerprint is checked first. Page-fragment support depends on that reader/browser.

**New conversation** starts a separate context; previous chats remain accessible through **Chat history** in this panel. They are not persisted after the panel is disposed. **Stop** cancels the operation, and generation fences prevent late responses from entering a new conversation. Explicit follow-ups retain the resolved topic and may reuse the last selected code snapshot, visibly previewed before sending. Users can disable/remove historical code; new selections take priority. No unselected project source is added.

The session stores task and conversational relation separately. Successful new-topic/new-selection turns replace the active history boundary, so later follow-ups cannot restore an earlier subject. Explicit corrections update the resolved focus and omit superseded answers; user statements are still not course evidence. Whole historical turns may be omitted for budget reasons, with a notice; no summary-model calls or long-term memory are used.

Changing language updates interface text immediately and controls subsequent answers. User questions, selected code and original excerpts stay unchanged. Translating an existing answer is explicit and cached; it translates existing prose without regenerating an answer, while protecting fenced/inline code, filenames, numbers and source IDs.

## Reliability boundaries

BM25 remains the baseline, with concept-oriented requests, topic/title reranking, Java/C++ relevance handling and independent evidence admission. No embedding, vector database, external UI framework, automatic fact-checking model, long-term memory or automatic history translation is included.

Model content, finish reason and usage are separate. Truncated structured answers never enter the JSON/citation validator. Network, HTTP, format, citation and cancellation errors are distinct. Format/citation failures allow one automatic retry; network, HTTP and truncated responses do not. Raw bounded diagnostics remain process-local and are not logged or shown in the interface.

A valid source ID does not prove a claim is correct. Manually compare claims with their excerpts. Retrieval can still fail on unfamiliar vocabulary, multilingual paraphrases or mixed-domain requests.

The panel retains at most three complete historical turns and displays at most 30 cards. Limits are 6,000 question characters, 16,000 selected-code characters and 28,000 total context characters; excessive input is rejected explicitly. Whole historical turns/excerpts omitted for budget reasons are reported.

## Evaluation

The original 28-case regression set is preserved. Tests and scripted answers assess retrieval/contracts, not real answer correctness. The first new 24-case set became a development regression after its initial run; it is not an independent holdout any longer. The former final 24-case set was subsequently used for B12 development and is now a regression set. The newer pre-fixed sets are developer-authored; none is an independently human-authored blind study.

Use `evaluation/run-live-acceptance.ps1` for an explicitly bounded live check: hidden local key input, at most six HTTP calls per run, including any format/citation retry. It records real answers and excerpts locally for review. The recorded 31-call campaign spans multiple bounded runs; its remaining quality issues are listed above.

Detailed Chinese usage and limitations: [README_CN.md](README_CN.md).
