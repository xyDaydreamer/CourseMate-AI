package edu.t07.coursemate.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.t07.coursemate.rag.CourseChunk;
import edu.t07.coursemate.rag.CourseKnowledgeBase;
import edu.t07.coursemate.rag.PdfCourseReader;
import edu.t07.coursemate.rag.RagAnswerService;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** All credentials are synthetic, all transports are in memory. No real API calls. */
class DeepSeekClientTest {
    @TempDir Path temporary;
    private static final String FAKE_KEY = "unit-test-credential-not-real";
    private static final String SUCCESS = "{\"choices\":[{\"message\":{\"content\":\"你好，Java！\\n第二行\"},"
            + "\"finish_reason\":\"stop\"}]}";

    @Test void repairFollowUpReachesHttpWithTheOriginalCodeAndActualConversation() throws Exception {
        var session=new edu.t07.coursemate.chat.ChatSession();
        String code="Map<String, Integer> counts = new HashMap";
        var initial=session.prepare("解释这段代码。",edu.t07.coursemate.chat.ChatSession.Code.of(code),true,edu.t07.coursemate.ReplyLanguage.ZH);
        session.commit(initial,"选区可能只是一段声明；如果这就是完整语句，应补全构造调用。");
        var follow=session.prepare("修改后应该是什么样？",null,true,edu.t07.coursemate.ReplyLanguage.ZH);
        var client=new DeepSeekClient(()->FAKE_KEY,request->{
            try{
                var payload=JsonParser.parseString(readBody(request)).getAsJsonObject();
                var context=JsonParser.parseString(payload.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
                assertEquals(code,context.get("selected_code").getAsString());assertTrue(context.get("historical_code").getAsBoolean());
                assertEquals("修改后应该是什么样？",context.get("question").getAsString());
                assertEquals("解释这段代码。",context.get("resolved_question").getAsString());
                assertEquals("解释这段代码。",context.get("topic").getAsString());
                assertEquals("CODE_REVISION",context.get("task").getAsString());assertEquals("CONTINUATION",context.get("context_relation").getAsString());
                assertTrue(context.getAsJsonArray("history_for_reference_only").get(0).getAsJsonObject().get("answer").getAsString().contains("如果这就是完整语句"));
            }catch(Exception e){throw new AssertionError(e);}
            return new DeepSeekClient.Response(200,SUCCESS);
        });
        var kb=new CourseKnowledgeBase(temporary,temporary.resolve("index.json"),new PdfCourseReader());
        RagAnswerService.structured(kb,client::askResponse).ask(follow);
    }

    @Test
    void selectedCodeTravelsThroughRagIntoActualHttpRequestBodyWithoutOutsideText() throws Exception {
        String selected = "int count = 1;\ncount++; // 中文";
        var client = new DeepSeekClient(() -> FAKE_KEY, request -> {
            try {
                var body = JsonParser.parseString(readBody(request)).getAsJsonObject();
                var messages = body.getAsJsonArray("messages");
                String system = messages.get(0).getAsJsonObject().get("content").getAsString();
                assertTrue(system.contains("selected_code 是用户明确选中的源代码数据"));
                var context = JsonParser.parseString(messages.get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
                assertEquals("Find potential bugs.", context.get("question").getAsString());
                assertEquals(selected, context.get("selected_code").getAsString());
                assertEquals(2, context.size());
                assertFalse(readBody(request).contains(FAKE_KEY));
            } catch (Exception exception) {
                throw new AssertionError("Cannot inspect synthetic request", exception);
            }
            return new DeepSeekClient.Response(200, SUCCESS);
        });
        var kb = new CourseKnowledgeBase(temporary, temporary.resolve("index.json"), new PdfCourseReader());
        String answer = new RagAnswerService(kb, client::ask).answer("Find potential bugs.", selected);
        assertTrue(answer.contains("你好，Java！"));
    }

    @Test
    void ragHttpBodyPreservesSelectedCodeAlongsideOnlyProvidedCourseExcerpts() throws Exception {
        String code = "class Child extends Parent {}";
        JsonObject task = new JsonObject();
        task.addProperty("question", "Explain inheritance here.");
        task.addProperty("selected_code", code);
        var source = new CourseChunk("Lec-2-Fixture.pdf", 19, "Inheritance derives subclasses.");
        var request = JsonParser.parseString(readBody(DeepSeekClient.buildRequest(FAKE_KEY, task.toString(), List.of(source)))).getAsJsonObject();
        var user = JsonParser.parseString(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
        var question = JsonParser.parseString(user.get("question").getAsString()).getAsJsonObject();
        assertEquals(code, question.get("selected_code").getAsString());
        assertEquals("Explain inheritance here.", question.get("question").getAsString());
        assertEquals("Lec-2-Fixture.pdf", user.getAsJsonArray("course_excerpts").get(0).getAsJsonObject().get("file_name").getAsString());
    }

    @Test
    void testProcessDoesNotInheritDeveloperCredential() {
        // Boolean assertion deliberately avoids printing a credential on failure.
        assertTrue(System.getenv("DEEPSEEK_API_KEY") == null,
                "The test process must not inherit DEEPSEEK_API_KEY");
    }

    @Test
    void noCourseContextExplicitlyAllowsNonJavaGeneralKnowledgeWithoutInventingSources() throws Exception {
        String question = "请解释量子纠缠实验中的光子偏振测量。";
        var request = JsonParser.parseString(readBody(DeepSeekClient.buildRequest(FAKE_KEY, question))).getAsJsonObject();
        var messages = request.getAsJsonArray("messages");
        String system = messages.get(0).getAsJsonObject().get("content").getAsString();
        assertTrue(system.contains("其他领域的通用知识问题"));
        assertTrue(system.contains("不要仅因为问题不属于 Java 就拒绝回答"));
        assertTrue(system.contains("不表示问题无法回答"));
        assertTrue(system.contains("不得编造课件名称、页码或来源引用"));
        assertFalse(system.contains("当前没有接入任何教师课件或课程知识库"));
        assertEquals(question, messages.get(1).getAsJsonObject().get("content").getAsString());
        assertFalse(request.has("response_format"));
    }

    @Test
    void ragRequestCarriesOnlyRetrievedExcerptsAndExactMetadata() throws Exception {
        var source = new CourseChunk("Lec-2-Fixture.pdf", 19, "Inheritance extends superclass.");
        String body = readBody(DeepSeekClient.buildRequest(FAKE_KEY, "解释继承", List.of(source)));
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        String system = request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        assertTrue(system.contains("这属于正常回答，不要描述为回答失败"));
        assertEquals("json_object", request.getAsJsonObject("response_format").get("type").getAsString());
        String user = request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        var context = JsonParser.parseString(user).getAsJsonObject();
        assertEquals("解释继承", context.get("question").getAsString());
        var excerpt = context.getAsJsonArray("course_excerpts").get(0).getAsJsonObject();
        assertEquals("S1", excerpt.get("id").getAsString());
        assertEquals(source.fileName(), excerpt.get("file_name").getAsString());
        assertEquals(19, excerpt.get("pdf_page").getAsInt());
        assertEquals(source.text(), excerpt.get("text").getAsString());
        assertFalse(body.contains(FAKE_KEY));
    }

    @Test
    void buildsOfficialPostWithUtf8JsonAndCredentialOnlyInHeader() throws Exception {
        String question = "解释 \"Servlet\"\n路径 C:\\demo\\中文.java";
        HttpRequest request = DeepSeekClient.buildRequest(FAKE_KEY, question);
        assertEquals("https://api.deepseek.com/chat/completions", request.uri().toString());
        assertEquals("POST", request.method());
        assertEquals("Bearer " + FAKE_KEY, request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json; charset=UTF-8",
                request.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(Duration.ofSeconds(120), request.timeout().orElseThrow());

        String body = readBody(request);
        assertFalse(body.contains(FAKE_KEY));
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("deepseek-flash", json.get("model").getAsString());
        assertFalse(json.get("stream").getAsBoolean());
        assertEquals("disabled", json.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals(4096, json.get("max_tokens").getAsInt());
        var messages = json.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertTrue(messages.get(0).getAsJsonObject().get("content").getAsString().contains("不得编造"));
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals(question, messages.get(1).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void parsesChineseAnswerAndIgnoresReasoningAndExtraMetadata() throws Exception {
        assertEquals("你好，Java！\n第二行", DeepSeekClient.parseAnswer(SUCCESS));
        assertEquals("final answer", DeepSeekClient.parseAnswer("{\"choices\":[{\"message\":{"
                + "\"reasoning_content\":\"not displayed\",\"content\":\"final answer\"}}],\"usage\":{}}"));
    }

    @Test void nullableOptionalMetadataDoesNotDiscardAValidAnswer() throws Exception {
        ModelResponse response=DeepSeekClient.parseResponse(SUCCESS.substring(0,SUCCESS.length()-1)+",\"id\":null,\"usage\":null}");
        assertEquals("你好，Java！\n第二行",response.content());assertEquals("",response.requestId());
        assertTrue(DeepSeekClient.parseResponse("{\"choices\":[{\"message\":{\"content\":null},\"finish_reason\":\"length\"}]}").truncated());
    }
    @Test
    void retainsTruncationMetadataWithoutPollutingContent() throws Exception {
        String answer = DeepSeekClient.parseAnswer(SUCCESS.replace("stop", "length"));
        assertTrue(answer.startsWith("你好，Java！"));
        assertEquals("你好，Java！\n第二行", answer);
        assertTrue(DeepSeekClient.parseResponse(SUCCESS.replace("stop", "length")).truncated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{}", "[]", "null", "{\"choices\":[]}",
            "{\"choices\":[{}]}", "{\"choices\":[{\"message\":null}]}",
            "{\"choices\":[{\"message\":{\"content\":null}}]}",
            "{\"choices\":[{\"message\":{\"content\":123}}]}",
            "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}"})
    void rejectsMalformedOrEmptyAnswers(String response) {
        DeepSeekException error = assertThrows(DeepSeekException.class,
                () -> DeepSeekClient.parseAnswer(response));
        assertNull(error.getCause());
        assertTrue(error.getMessage().contains("DeepSeek"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void missingKeyShowsSetupInstructionsAndNeverSends(String key) {
        AtomicBoolean called = new AtomicBoolean();
        DeepSeekClient client = new DeepSeekClient(() -> key, request -> {
            called.set(true);
            return new DeepSeekClient.Response(200, SUCCESS);
        });
        DeepSeekException error = assertThrows(DeepSeekException.class, () -> client.ask("问题"));
        assertFalse(called.get());
        assertTrue(error.getMessage().contains("DEEPSEEK_API_KEY"));
        assertTrue(error.getMessage().contains("Read-Host"));
        assertTrue(error.getMessage().contains("同一个 PowerShell"));
    }

    @Test
    void invalidCredentialIsRejectedBeforeTransportAndNotEchoed() {
        String invalid = FAKE_KEY + "\r\ninjected";
        DeepSeekClient client = new DeepSeekClient(() -> invalid, request -> {
            fail("Invalid credential must not reach HTTP transport");
            return null;
        });
        DeepSeekException error = assertThrows(DeepSeekException.class, () -> client.ask("问题"));
        assertTrue(error.getMessage().contains("格式"));
        assertFalse(error.getMessage().contains(FAKE_KEY));
    }

    @Test
    void blankQuestionIsNotSent() {
        DeepSeekClient client = new DeepSeekClient(() -> FAKE_KEY, request -> {
            fail("Blank question must not reach HTTP transport");
            return null;
        });
        assertThrows(DeepSeekException.class, () -> client.ask("\n  "));
    }

    @Test
    void usesInjectedTransportAndReturnsActualResponseText() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        DeepSeekClient client = new DeepSeekClient(() -> FAKE_KEY, request -> {
            called.set(true);
            assertEquals(DeepSeekClient.ENDPOINT, request.uri());
            return new DeepSeekClient.Response(200, SUCCESS);
        });
        assertEquals("你好，Java！\n第二行", client.ask("解释 Servlet"));
        assertTrue(called.get());
    }

    @ParameterizedTest
    @CsvSource({"400,参数", "401,认证失败", "402,余额不足", "403,访问被拒绝",
            "404,模型不可用", "408,超时", "422,参数", "429,频繁", "500,服务暂时异常",
            "502,服务暂时异常", "503,服务暂时异常", "504,超时", "418,请求失败", "302,请求失败"})
    void mapsHttpErrorsWithoutExposingServerBodyOrCredential(int status, String expected) {
        DeepSeekClient client = new DeepSeekClient(() -> FAKE_KEY, request ->
                new DeepSeekClient.Response(status, "Authorization: Bearer " + FAKE_KEY));
        DeepSeekException error = assertThrows(DeepSeekException.class, () -> client.ask("问题"));
        assertTrue(error.getMessage().contains(expected));
        assertTrue(error.getMessage().contains(Integer.toString(status)));
        assertFalse(error.getMessage().contains(FAKE_KEY));
        assertFalse(error.getMessage().contains("Authorization"));
        assertNull(error.getCause());
    }

    @Test
    void timeoutHasHelpfulSafeMessage() {
        DeepSeekClient client = new DeepSeekClient(() -> FAKE_KEY, request -> {
            throw new HttpTimeoutException("sensitive " + FAKE_KEY);
        });
        DeepSeekException error = assertThrows(DeepSeekException.class, () -> client.ask("问题"));
        assertTrue(error.getMessage().contains("超时"));
        assertFalse(error.getMessage().contains(FAKE_KEY));
        assertNull(error.getCause());
    }

    @Test
    void connectionFailureDoesNotLeakExceptionDetails() {
        DeepSeekClient client = new DeepSeekClient(() -> FAKE_KEY, request -> {
            throw new IOException("sensitive " + FAKE_KEY);
        });
        DeepSeekException error = assertThrows(DeepSeekException.class, () -> client.ask("问题"));
        assertTrue(error.getMessage().contains("无法连接"));
        assertFalse(error.getMessage().contains(FAKE_KEY));
        assertNull(error.getCause());
    }

    @Test
    void unexpectedTransportFailureDoesNotLeakExceptionDetails() {
        DeepSeekClient client = new DeepSeekClient(() -> FAKE_KEY, request -> {
            throw new IllegalStateException(FAKE_KEY);
        });
        DeepSeekException error = assertThrows(DeepSeekException.class, () -> client.ask("问题"));
        assertFalse(error.getMessage().contains(FAKE_KEY));
        assertNull(error.getCause());
    }

    @Test
    void interruptionPreservesCancellationSignal() {
        DeepSeekClient client = new DeepSeekClient(() -> FAKE_KEY, request -> {
            throw new InterruptedException(FAKE_KEY);
        });
        try {
            DeepSeekException error = assertThrows(DeepSeekException.class, () -> client.ask("问题"));
            assertEquals("请求已取消。", error.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static String readBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<String> result = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }

            @Override
            public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable error) { result.completeExceptionally(error); }

            @Override
            public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.get(2, TimeUnit.SECONDS);
    }
}
