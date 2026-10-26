package edu.t07.coursemate.chat;

import com.google.gson.*;
import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.ai.*;
import edu.t07.coursemate.rag.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnswerTranslationTest {
    private AnswerResult answer() {
        return new AnswerResult(UUID.randomUUID(),List.of(new AnswerResult.Claim("Use `Map<String,Integer>` in notes.pdf page 5.\n```java\nx++;\n```",List.of("S1"))),"Extra text.",List.of(),new RetrievalResult(RetrievalResult.Status.READY,"q",List.of(),List.of(),List.of(),"v"),AnswerResult.Completion.COMPLETE,ModelResponse.complete(""),List.of());
    }
    @Test void translatesExistingProseWhileProtectingCodeFilesNumbersAndSources() throws Exception {
        var translator=new AnswerTranslation(json->{var request=JsonParser.parseString(json).getAsJsonObject();var result=new JsonObject();result.add("segments",request.get("segments"));return ModelResponse.complete(result.toString());});
        var answer=answer();var translated=translator.translate(answer,ReplyLanguage.ZH);
        assertEquals(answer.claims().getFirst().text(),translated.getFirst());assertEquals("Extra text.",translated.getLast());
    }
    @Test void refusesDroppedOrModifiedProtectedCodeWithoutReplacingOriginal() {
        var translator=new AnswerTranslation(json->ModelResponse.complete("{\"segments\":[\"changed code\",\"extra\"]}"));
        assertThrows(DeepSeekException.class,()->translator.translate(answer(),ReplyLanguage.EN));assertTrue(answer().claims().getFirst().text().contains("x++;"));
    }
}
