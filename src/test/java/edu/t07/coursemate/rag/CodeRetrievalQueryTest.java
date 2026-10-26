package edu.t07.coursemate.rag;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodeRetrievalQueryTest {
    @Test void extractsOnlyKnownLanguageAndApiFeatures() {
        String result = CodeRetrievalQuery.build("Explain this code.",
                "Map<String, Integer> arbitraryName = new HashMap<>(); arbitraryName.merge(\"java\", 1, Integer::sum);");
        assertTrue(result.contains("map merge generics"));
        assertFalse(result.contains("arbitraryName"));
        assertTrue(CodeRetrievalQuery.build("解释这段代码。", "try { f(); } catch (IOException e) {} finally { g(); }").contains("exception catch finally"));
        assertTrue(CodeRetrievalQuery.build("Explain this code.", "List<String> items = new ArrayList<>();").contains("list generics"));
    }

    @Test void ignoresCommentsEscapedStringsCharactersAndTextBlocks() {
        String code = "// Map generics\n/* HashMap */ class Example { String x = \"a\\\"Map\"; char c = '\\''; String y = \"\"\"\nList<String>\n\"\"\"; }";
        assertEquals("Explain this code.", CodeRetrievalQuery.build("Explain this code.", code));
    }

    @Test void preservesOriginalTaskAndDoesNotEnrichUnrelatedTasks() {
        String question = "Explain photon polarization measurements.";
        assertEquals(question, CodeRetrievalQuery.build(question, "Map<String, Integer> values = new HashMap<>();"));
        assertEquals("Explain quantum using this code.", CodeRetrievalQuery.build("Explain quantum using this code.", "Map<String, Integer> values = new HashMap<>();"));
        assertTrue(CodeRetrievalQuery.build("Find potential bugs.", "Map<String, Integer> values = new HashMap<>();").contains("map generics"));
        assertTrue(CodeRetrievalQuery.build("Why does HashMap return null?", "Map<String, Integer> values = new HashMap<>();").startsWith("Why does HashMap return null? "));
    }
}
