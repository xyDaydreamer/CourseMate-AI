# CourseMate AI — Internal Development Handover

[中文交接](HANDOVER_CN.md) · [User guide](README.md)

**Internal team working document, not the final course report or presentation.**  
Team T07 / Group A · inspected version **0.4.0** · inspection date **2026-10-25**

## 1. State and evidence

| Category | What can currently be stated |
| --- | --- |
| Implemented in source | Tool-window chat, DeepSeek requests, PDF RAG, verified source metadata, general-knowledge fallback, and explicitly attached Java selections. These cover the planned F0 MVP feature areas. |
| Automated verification | Existing records: **87 passed / 0 failed / 0 errors / 0 skipped**. Includes five IntelliJ light-fixture cases and simulated HTTP tests. |
| Build | Existing `coursemate-ai-0.4.0.zip` is present. The documentation-time offline Gradle check returned BUILD SUCCESSFUL, with tests and packaging UP-TO-DATE. |
| User-reported manual observations | Earlier 0.3.x startup/live answers worked; inheritance retrieved OOP page 19, generics retrieved relevant lecture pages, and the physics question received a reasonable general answer. Earlier false candidates and confusing wording were also reported. |
| Not signed off | A repeatable 0.4.0 live-API/desktop acceptance record, first-open window activation, multi-project focus, cancellation, attachment cleanup/retry, and model-answer quality. No live API call or desktop UI automation was performed during this documentation work. |
| Repository provenance | No `.git` directory/verified remote was available. There is no verified GitHub URL, commit identifier, or history audit to cite. |

“Implemented” is not equivalent to “manually accepted.” Keep user reports and automated evidence separate. This task changed only the four Markdown documents, not features, dependency declarations, tests, PDFs, or personal configuration.

### Test record identity

Read `build/test-results/test/TEST-*.xml` and `build/reports/tests/test/index.html`. The recorded suite starts span **2026-10-25T12:53:39.793Z–12:53:41.106Z** (**20:53:39.793–20:53:41.106 UTC+8**). XML files were written around **20:53:46 UTC+8**; the ZIP timestamp is **2026-10-25T12:53:46.7105629Z**.

Documentation inspection ran:

~~~powershell
.\gradlew.bat test buildPlugin --offline --console=plain --no-daemon
~~~

Gradle reported `test UP-TO-DATE` and `buildPlugin UP-TO-DATE`. This confirms its input/output tracking still accepts the existing result for this checkout; it is **not a new run of all test bodies**, a live API test, a compatibility sweep, or a coverage/performance measurement. A sandbox-local daemon connection restriction occurred first; the same check succeeded outside that restriction without a source change.

The README explains portable Windows/JDK/IDE-path setup. Retain the XML, console result and artifact identity when the team records a release; do not invent a commit ID for this non-Git folder.

## 2. Stack and configuration

- Java toolchain/release **25**; target IDEA **2026.2.3 / 262.10968.63**. Declared range `262.10968.63–262.*` is not evidence that every matching release was exercised.
- Gradle Wrapper **9.1.0**, IntelliJ Platform Gradle Plugin **2.19.0**.
- Gson **2.13.2**, PDFBox **3.0.8**. No vector store, paid embeddings, or source-project parser.
- Tests: JUnit BOM **5.13.4**, Jupiter plus Vintage, JUnit **4.13.2**, matching Platform test framework. Test-only dependencies are not runtime plugin libraries.
- Plugin ID `edu.t07.coursemate.ai`; sole declared platform dependency `com.intellij.modules.platform`.
- Tool window `CourseMate AI`; Action ID `edu.t07.coursemate.AskSelectedCode` in `EditorPopupMenu`.
- Endpoint `https://api.deepseek.com/chat/completions`; configured model `deepseek-flash`. Non-streaming, thinking disabled, output limit 2,048 tokens, 15-second connection / 120-second request timeout, redirects disabled, no automatic retry.
- Only `DEEPSEEK_API_KEY` is used for production credentials. No `.env` reader or password-store/settings UI exists. The model name is a source constant, not an end-user setting or an account-availability check.

## 3. Architecture and actual files

All main Java paths below are relative to `src/main/java/edu/t07/coursemate/`.

| File | Responsibility |
| --- | --- |
| `actions/AskCourseMateAction.java` | Stateless `DumbAwareAction` with BGT updates. Checks selection again on invocation, activates the correct project window, and prepares a draft; it never invokes AI itself. |
| `actions/EditorCodeSelection.java` | Reads `CommonDataKeys` for a live project/editor and `.java` virtual file. Returns only nonblank `SelectionModel.getSelectedText()`; it does not read the full document or perform Java semantic validation. |
| `ui/CourseMateToolWindowFactory.java` | Creates one panel in the project's tool window and attaches its disposer to the content. |
| `ui/CourseMateChatPanel.java` | Owns chat, question input, code preview, busy/answering state and SwingWorker lifecycle. Loads the KB in the background, submits confirmed drafts, retains failed drafts, and clears attachments on success/cancel/disposal. |
| `ai/DeepSeekClient.java` | Reads the environment Key, constructs UTF-8 HTTP JSON, calls an injectable transport, parses `choices[0].message.content` and maps errors to safe text. No raw response/error-body logging is added by this client. |
| `ai/DeepSeekException.java` | Checked exception for locally authored safe user messages; lower-level secrets/error bodies are not chained into it. |
| `rag/RagAnswerService.java` | Refreshes metadata, retrieves using the question, optionally packages selected code, invokes the same generator, validates grounded JSON/source IDs and renders course/general states. API failures propagate as errors. |
| `rag/CourseChunk.java` | Immutable `fileName` / one-based `page` / nonblank `text` record with basic validation. |
| `rag/PdfCourseReader.java` | Read-only PDFBox extraction using one PDF page at a time. Normalizes whitespace, chunks within a page to 1,000 Java string characters with approximately 150-character overlap, preserves metadata, and rejects textless documents. |
| `rag/KeywordIndex.java` | In-memory inverted postings, BM25 ranking, stop words, Chinese aliases, RAG acronym grouping, weighted topic coverage and non-teaching-page filters. Returns up to four distinct pages. |
| `rag/CourseKnowledgeBase.java` | Lecture whitelist, course-path resolution, per-file fingerprints, persistent chunk cache, incremental refresh and forced rebuild. Builds postings from cached chunks on startup; does not persist a separate postings database. |

| Other file | Responsibility |
| --- | --- |
| `src/main/resources/META-INF/plugin.xml` | Plugin identity, platform dependency, tool-window factory, editor Action class/text/group. |
| `build.gradle.kts` | Version, SDK/local-path resolution, production/test dependencies, compiler, test setup, runIde course path, compatibility bounds and Wrapper task. |
| `settings.gradle.kts` | Project name `coursemate-ai`. |
| `gradle.properties` | Gradle memory/UTF-8 settings, disabled parallel build, and source-download setting; no Key. |
| `gradle/wrapper/gradle-wrapper.properties`, `gradle-wrapper.jar`, `gradlew`, `gradlew.bat` | Pinned Wrapper, HTTPS distribution and SHA-256 verification; cross-platform launchers. |
| `local.properties` / `local.properties.example` | Ignored machine-local IDEA path / shareable template with a generic placeholder. Every developer must copy and adapt the template locally; only `local.properties` is ignored. |
| `.gitignore` | Excludes credentials/environment files, the private local IDEA path, build/Gradle/IDE caches, `.coursemate`, the full root `course_files/` directory, and three specifically named root assignment/exam PDFs. It explicitly re-allows a reviewed `.env.example` and avoids blanket PDF/JAR/ZIP rules. |
| `test.java` | Existing root-level scratch file, outside Gradle's `src/main/java` and `src/test/java` source sets; not a plugin build input or part of the 87-case suite. Leave it untouched unless separately authorized. |
| `README.md` / `README_CN.md` | Matched English/Chinese user guides. |
| `HANDOVER.md` / `HANDOVER_CN.md` | Matched internal handover and acceptance documents, not submission artifacts. |

### Test files

Paths are under `src/test/java/edu/t07/coursemate/`; counts are parameterized **cases**, not method counts.

| File | Cases | Scope |
| --- | ---: | --- |
| `actions/AskCourseMateActionTest.java` | 5 | Actual platform Action loading plus descriptor checks, precise real-editor selection, disabled invalid contexts, and no document mutation on invalid invocation. Does not automate opening the popup/focusing the window. |
| `ai/DeepSeekClientTest.java` | 43 | Request URL/headers/JSON, code and course-context serialization, response parsing, error mapping, cancellation and credential suppression using fake transport/Keys. |
| `rag/CourseKnowledgeBaseTest.java` | 4 | Cache reuse, restart, changes/removal/forced rebuild, exclusion rules, corrupt/textless documents and cache/directory recovery. |
| `rag/KeywordIndexTest.java` | 7 | Course concepts, noise filters, Chinese mapping, unrelated-topic coverage and acronym normalization. |
| `rag/PdfCourseReaderTest.java` | 4 | Physical page metadata, chunk size/overlap, blank pages and invalid PDFs; fixtures only. |
| `rag/RagAnswerServiceTest.java` | 14 | Course/general status, real-metadata rendering, invented-source rejection, low candidates excluded before generation, code routing, and API failure propagation/recovery. |
| `rag/RealCourseRetrievalTest.java` | 10 | Read-only current-corpus checks, known concepts and unrelated English queries, metadata provenance, and PDF hash invariance. Asserts 9 documents/199 pages and at least 199 chunks; the observed count is 200. |
| `rag/TestPdfs.java` | Helper | Generates synthetic PDF fixtures only in JUnit temporary directories, not `course_files`. |

The test task removes `DEEPSEEK_API_KEY` and restricts loaded plugins to CourseMate and required modules. This does not alter normal IDEA/runIde plugin activation.

## 4. Actual data flows

### 4.1 Ordinary question

1. Panel ignores blank input or busy/disposed state. Send and Ctrl+Enter share the same path.
2. It snapshots the question, enters busy state and runs `answers.answer(question, codeForRequest)` in a background SwingWorker; ordinary chat has an empty code string.
3. `refresh(false)` checks lecture filenames/size/mtime and refreshes changed files; an unchanged PDF is not parsed again.
4. `search(question)` ranks chunks. BM25 must be ≥ **0.25**, weighted topic coverage ≥ **0.5**; presentation words are excluded and unknown query topics remain in the coverage denominator. Alias alternatives count as one concept.
5. Recognized covers/schedules/reference-only pages are skipped. At most four distinct-page chunks survive; there is no requirement to fill four slots.
6. DeepSeek receives one system message and one user message. With sources, the user content is JSON with `question` and `course_excerpts`, each excerpt holding `id`, `file_name`, `pdf_page` and `text`. Without sources it receives the question, not rejected candidates.
7. The HTTP client parses the message text. With sources, the service expects `course_answer`, `general_answer` and `source_ids` JSON. It checks allowed IDs against actual chunks and rejects inline filename/page/source markers.
8. If a nonempty course answer uses validated IDs, Java renders the actual filename/page list and separately labels general supplements. Grounded output also displays the actual candidate pages, with a warning that not every candidate proves the answer.
9. If no sources qualify, or a structured result uses no course sources and contains only a general answer, render the general label/English notice and **no course list**. API exceptions are not converted to this state.
10. SwingWorker completion updates the UI on the EDT. Success clears draft/attachment; failure displays safe text and retains the draft for manual retry. Disposal cancels the worker; no automatic retry is implemented.

The citation guard verifies provenance and shape, not factual entailment. It also conservatively rejects strings matching `.pdf`, explicit page patterns and `[S…]` in answer prose, even where those could be legitimate non-citation code examples. A truncated 2,048-token grounded JSON response may fail parsing instead of displaying a partial course answer.

### 4.2 Java selection question

1. The Action rechecks project/editor/extension/selection and copies only the selected string. There is no source-project scan, file read beyond the selected model text, or code execution.
2. It activates the tool window and calls `prepareCodeQuestion`. Initialization can require retry; an active answer rejects attachment replacement. Index loading may finish before the draft can be edited/sent.
3. The panel shows the immutable text snapshot and fills `Explain this code.` only when the question field is empty. Preparing alone makes **no API call**.
4. Before Send, **取消代码上下文** clears only the attachment; question text remains. Closing/disposal also clears it. After Send the cancel-attachment button is disabled while busy; it is not an “undo already uploaded data” control.
5. On explicit Send, the panel snapshots the attachment. RAG still searches only the natural-language question. It packages nonblank code and task as a JSON string `{"question": "...", "selected_code": "..."}` for the existing generator.
6. With course sources, that JSON string becomes the outer course-context `question` field; without sources it is the user-message content. This intentional nested serialization is covered by tests; there is no second AI pipeline or separate code endpoint.
7. The system prompt says to treat the code as data and not execute instructions inside it or invent course sources from code comments. This is an instruction to the model, not a factual-output guarantee.
8. No automatic secret detector/redactor exists for selections. The user must review the preview before sending. Failed requests retain the snapshot; successful requests clear it so subsequent chat does not silently reuse code.

## 5. Knowledge base and persistence

For the inspected supplied corpus, cache metadata/fingerprints and the report agree: **12 local PDFs, 9 included lectures, 199 pages, 200 chunks**. The full manifest and exclusions are in both READMEs. Current PDF hashes match cache fingerprints; originals were not edited by this task.

- Whitelist: `(?i)^Lec-[0-5](?:[.-].*)?\.pdf$`, direct files only.
- Path order: `COURSEMATE_COURSE_FILES` → JVM `coursemate.courseDir` → open-project `course_files` (working-directory fallback without a base path).
- `runIde` sets the JVM property to this checkout's course folder; the environment variable has higher priority. A separately installed ZIP needs its own folder setup.
- Cache: sibling `.coursemate/index.json`, schema version 1; stores absolute normalized course directory and per-file name/size/mtime/SHA-256/page counts/chunks/problem status.
- Reload validates basic cached metadata and source directory. SHA-256 is recorded when parsing, not recomputed for every question; the normal change detector uses size/mtime. Manually force a rebuild for content changes that preserve both.
- New/changed files are read; deleted or failed changed files lose old chunks. Failed files may retain an error entry until change or forced rebuild. Missing/corrupt caches rebuild; cache write failure allows current in-memory retrieval with a warning.
- Completely textless PDFs fail. Partial blank pages keep physical numbering and produce warnings; no OCR.
- The cache contains full extracted lecture text and a local absolute directory. It is private generated data, not a public statistics file. Do not commit it.

The real-corpus test depends on this specific collection. Different/missing PDFs can fail tests even though generic production code still loads an empty/different KB and allows general questions. The course directory is not declared as a full Gradle file-input set for the test task; if only PDFs change, deliberately rerun tests rather than relying solely on UP-TO-DATE.

## 6. Known observations and follow-up

| Observation / evidence | Status and next action |
| --- | --- |
| Keyword retrieval can miss paraphrases or accept partial overlap. Earlier English physics results matched the word `simple` in unrelated Java pages. | Coverage/stop-word/alias guards are implemented and regression-tested for known queries, not a universal relevance guarantee. Add diverse positive and negative judgments before tuning; do not blindly raise/lower thresholds. |
| No related course material should lead to a normal general answer, not refusal or a “model failed” status. | Current service/prompt has this behavior and simulated regression coverage. Live runs still require acceptance; API failures must stay errors. |
| A general answer reportedly contained “Dog 自动ViewController继承了 Animal 中可被继承的成员”. | **User-reported anomaly; exact version, sanitized request, response and reproduction record were not supplied. Cause is unconfirmed.** Do not attribute it to a particular model, prompt or rendering line without evidence. Reproduce with synthetic code and compare request intent, model content and UI display without exposing Keys/private material. |
| Welcome text says “问题和最多 4 个相关课件片段将交给 DeepSeek”. | Confirmed string in `CourseMateChatPanel`. It may imply every question sends snippets; actual logic conditionally sends 0–4. Clarify wording in a separately approved UI change; no string was changed here. |
| Citations are structurally checked, but general prose may be inaccurate or odd. | No semantic/factual answer-quality validator exists. Review live answers and the cited pages separately; collect a redacted minimal reproduction rather than assuming a cause. |
| Context-menu and tool-window focus, cancellation, live failures and retries | Platform models and transport are tested, but desktop interaction has no recorded 0.4.0 sign-off. Record manual cases per project/window, including the first opening. |
| No public-sharing permission or repository origin was available. | Treat PDFs/derived text as restricted until authorized. Create/confirm the team repository before replacing URL placeholders; do not infer publication rights from local availability. |
| User intent recognition and concept disambiguation | When asked, “What is polymorphism, overriding, and another concept that is easy to confuse with it?”, the model explained Hiding but did not prioritize Overloading, which was likely the concept the user intended. Hiding is a related and technically relevant concept, so this should not be treated as an entirely incorrect answer. The issue is that the model did not adequately resolve an ambiguous request about commonly confused concepts. Without expanding the MVP scope, a future prompt or response-strategy improvement could present the most likely alternatives and briefly compare Overloading, Overriding, and Hiding, using the retrieved course material where available. Add a regression test for similar ambiguous questions and separately inspect user intent, retrieved excerpts, model output, and final UI rendering. The root cause has not been established, and no fix has been validated.

For manual records capture date/time/timezone, version, IDE build/JDK, task, a synthetic/minimized selection, source IDs/pages, expected/actual UI state, and pass/fail. Never capture Authorization headers, a real Key, a full private project, or unapproved lecture extracts in logs/screenshots. Live model calls may cost money; they are separate from the automated suite.

## 7. Next-stage priorities

### MVP acceptance and delivery — still required

1. Run a clean-machine build/startup with authorized lectures and each member's own Key.
2. Sign off ordinary course Q&A, general fallback, precise code attachment/cancel, live API errors/retry, and verified references using the checklist below.
3. Investigate reproducible misleading output or state errors that block demonstration; keep changes narrow and add regression evidence.
4. Prepare the final **English report**, slide deck/export and English demonstration/Q&A; these internal documents do not replace submission materials.

### Optional work after MVP sign-off — not implemented

- UI styling and interaction refinement, including clearer conditional evidence/status wording.
- More rigorous relevance evaluation and multilingual query handling.
- Hybrid/semantic vector retrieval, with explicit privacy/cost decisions.
- ZIP-project/source-file parsing and indexing.
- True multi-turn conversation history and streaming output; visible window history alone is not multi-turn context.
- Better model-output quality/consistency checks.
- Richer distinction between evidence-backed statements and general knowledge.

Do not describe these as shipped. Automatic source modification/diff approval and Group B/C extensions remain outside this Group A MVP scope.

## 8. Collaboration, GitHub and privacy

There is no working Git repository here, so no remote state, commit history, branch protection or already-published secret/PDF status could be checked. This task did not initialize Git, commit, or push.

When an approved repository exists:

- Each member uses their own environment Key; never commit or exchange a real Key.
- The current ignore rules cover `.env`, `.env.*`, `local.properties`, build outputs, Gradle caches, `.intellijPlatform`, `.coursemate`, `.idea`, `*.iml`, the entire root `course_files/` directory, and three specifically named root assignment/exam PDFs. A reviewed, credential-free `.env.example` remains eligible for version control, although the plugin does not load `.env` files.
- Ignore rules are not a substitute for reviewing the staged file list. Adding a rule does **not** automatically stop tracking a file that was already added or committed; check tracked/ignored files explicitly before the first push or after any ignore-policy change.
- PDF sharing requires permission. Derived text caches are also course material; a private repository is not automatically permission to redistribute, even though the current raw-course and cache paths are ignored.
- The real `local.properties` remains machine-specific and ignored. The shared example and both README guides use a generic path placeholder; every developer must create and adapt their own local file or pass an explicit `-PideaLocalPath`.
- A limited scan of 27 source/config/document text files found no long `sk-`-style literal credential. This is not a complete secret/history audit or proof that no Key ever existed elsewhere; environment values were not printed.
- Use one feature branch per change and a reviewed Pull Request. Once in a real repository, typical local checks are `git status` and `git diff`; do not overwrite the main branch with copied folders.
- Merge `CourseMateChatPanel.java`, `plugin.xml` and `RagAnswerService.java` carefully: confirm selected-code lifecycle, Action IDs, generator arguments and source-state logic remain consistent. `DeepSeekClient.java` payload/prompt changes require corresponding serialization/error tests.
- Review source changes, test reports and an actual UI run before merging. Never log whole selections or credentials to debug a merge.

## 9. Final acceptance checklist

Checked items reflect automated/build evidence only. Unchecked items require a named reviewer and a dated manual/submission record.

- [x] 0.4.0 builds; existing tests/artifact are accepted as UP-TO-DATE for current code.
- [x] 87 automated cases pass in the recorded run; XML and artifact paths are documented.
- [x] Action registration/selection model, code-to-request serialization, no-match states and simulated API errors have automated checks.
- [ ] Launch 0.4.0 in a real IDEA on a teammate/clean machine.
- [ ] Ordinary Java course question receives a live answer with real, content-supporting PDF citations.
- [ ] An unrelated English and Chinese question receives a successful general answer without course citations.
- [ ] Java selection → right-click → correct project tool window → editable task works, including first open.
- [ ] Live answer is based on exactly the selected code; unselected text is absent.
- [ ] Cancelling the attachment before Send makes no request; a later ordinary request contains no cancelled code.
- [ ] Successful attachment cleanup, failure retention/manual retry and duplicate-send protection work in the desktop.
- [ ] Missing Key, authentication, balance and network/service errors are clear; no error is disguised as “no match.”
- [ ] Staged/shared files and screenshots have no real Key, personal local settings, unauthorized PDFs or extracted caches.
- [ ] READMEs and commands work on a second machine with its own paths, Key and permitted lecture copies.
- [ ] English report, presentation/slides (and required export), English demo and Q&A are ready.
- [ ] Confirm course submission requirements, team-member details, final package contents and deadline before submission.

**Manual acceptance record template**

| Date/time + timezone | Version / IDE / JDK | Case / synthetic input | Expected | Actual | Pass/fail | Reviewer |
| --- | --- | --- | --- | --- | --- | --- |
| Not yet recorded | 0.4.0 / fill in | Fill in without secrets | Fill in | Fill in | Pending | Fill in |