package edu.t07.coursemate.rag;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Small, bounded lexical feature extractor; never indexes comments, literals or arbitrary identifiers. */
public final class CodeRetrievalQuery {
    private CodeRetrievalQuery() { }

    public static String build(String question, String selectedCode) {
        if (selectedCode == null || selectedCode.isBlank()) return question;
        boolean codeTask = isGenericCodeTask(question);
        if (QueryPlan.understand(question).outsideDomain() || (!codeTask && !KeywordIndex.hasCourseConcept(question))) return question;
        String code = stripCommentsAndLiterals(selectedCode.substring(0, Math.min(selectedCode.length(), 100_000)));
        Set<String> features = new LinkedHashSet<>();
        if (has(code, "\\b(Map|HashMap|LinkedHashMap|TreeMap)\\b")) {
            features.add("map");
            if (has(code, "\\.\\s*(merge|getOrDefault)\\s*\\(")) features.add("merge");
            if (has(code, "\\.\\s*entrySet\\s*\\(")) features.add("iteration");
        }
        if (has(code, "\\b(List|ArrayList|LinkedList)\\b")) features.add("list");
        if (has(code, "\\b(Set|HashSet|TreeSet|LinkedHashSet)\\b")) features.add("set");
        if (has(code, "\\bfor\\s*\\([^)]*:") && has(code, "\\.\\s*remove\\s*\\(")) features.add("iteration");
        if (has(code, "\\bIterator\\b|\\.\\s*iterator\\s*\\(")) features.add("iteration");
        if (has(code, "\\b[A-Z][\\w.]*\\s*<[^;{}=]*>")) features.add("generics");
        if (has(code, "\\b(class|interface)\\s+\\w+(?:\\s*<[^>]*>)?\\s+(extends|implements)\\b")) features.add("inheritance");
        if (has(code, "\\b(try|catch|throw|throws|finally)\\b|\\b\\w*Exception\\b")) {
            features.add("exception");
            if (has(code, "\\bcatch\\b")) features.add("catch");
            if (has(code, "\\bfinally\\b")) features.add("finally");
        }
        if (has(code, "@(Test|BeforeEach|AfterEach)\\b|\\b(assertEquals|assertThrows|assertTrue)\\s*\\(")) features.add("junit assertions");
        return features.isEmpty() ? question : question + " " + String.join(" ", features);
    }

    private static boolean has(String code, String regex) { return Pattern.compile(regex).matcher(code).find(); }

    public static boolean isGenericCodeTask(String question) {
        if (QueryPlan.understand(question).outsideDomain()) return false;
        if (question.matches("(?is).*(?:explain|review|analy[sz]e|debug|walk.*through|describe|check|find|spot|解释|检查|分析|调试).*(?:code|snippet|fragment|method|selection|代码|选区|程序).*")) return true;
        String remainder = question.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\b(explain|describe|analyze|analyse|review|this|the|selected|following|attached|java|code|snippet|method|function|please|how|does|work|works|what|do|it|potential|bugs|find|fix|suggest|improvements|without|modifying|behavior|performance|complexity|why|error|here|is|a|an|in|of|and|which|that|can|you|help|understand|step|by|line|lines)\\b", " ")
                .replaceAll("请|帮我|解释|说明|分析|检查|这段|此段|选中|以下|当前|代码|这个方法|有什么问题|如何改进|一下|逐行", " ")
                .replaceAll("[\\p{Punct}\\p{P}\\s\\d]", "");
        return remainder.isEmpty();
    }

    // Preserve newlines so a // comment cannot consume the following declaration. Handles escaped quotes/text blocks.
    public static String stripCommentsAndLiterals(String source) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < source.length();) {
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i + 2); i = end < 0 ? source.length() : end;
                result.append(' ');
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2); i = end < 0 ? source.length() : end + 2;
                result.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3); i = end < 0 ? source.length() : end + 3;
                result.append(' ');
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                char quote = source.charAt(i++);
                while (i < source.length()) {
                    char next = source.charAt(i++);
                    if (next == '\\' && i < source.length()) i++;
                    else if (next == quote) break;
                }
                result.append(' ');
            } else result.append(source.charAt(i++));
        }
        return result.toString();
    }
}
