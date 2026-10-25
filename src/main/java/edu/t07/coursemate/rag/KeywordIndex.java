package edu.t07.coursemate.rag;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Small in-memory inverted index with BM25 ranking. No embedding API or external service. */
public final class KeywordIndex {
    private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9_]+|[\\p{IsHan}]{2}");
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "this", "that", "what", "when",
            "where", "which", "why", "how", "does", "are", "can", "you", "your", "from", "into", "about",
            "explain", "please", "java", "course", "programming", "code", "example", "examples", "using",
            "use", "is", "in", "of", "to", "an", "as", "on", "or", "it", "be", "by", "at", "do", "me",
            "simple", "terms", "simply", "briefly", "describe", "give", "tell", "show", "help", "understand",
            "detail", "details", "according", "lecture", "lectures", "based",
            "used", "here",
            "什么", "如何", "解释", "请问", "一下", "课程", "怎么");
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("继承", "inheritance"), Map.entry("多态", "polymorphism overriding"),
            Map.entry("封装", "encapsulation"), Map.entry("抽象", "abstract abstraction"),
            Map.entry("接口", "interface"), Map.entry("构造", "constructor"),
            Map.entry("异常", "exception exceptions throw catch"), Map.entry("泛型", "generic generics"),
            Map.entry("集合", "collections collection list set map"), Map.entry("单元测试", "junit testing"),
            Map.entry("注解", "annotation annotations"), Map.entry("反射", "reflection"),
            Map.entry("检索增强", "rag retrieval augmented generation"), Map.entry("知识库", "rag retrieval"),
            Map.entry("向量", "vector embedding embeddings"), Map.entry("提示词", "prompt engineering"),
            Map.entry("大模型", "llm"), Map.entry("字符串", "string"), Map.entry("数组", "array arrays"),
            Map.entry("循环", "loop loops"), Map.entry("变量", "variable variables"),
            Map.entry("流式", "stream streaming"), Map.entry("重载", "overloading"),
            Map.entry("重写", "overriding"), Map.entry("对象", "object objects"));
    private static final Map<String, Set<String>> ACRONYMS = Map.of(
            "rag", Set.of("rag", "retrieval", "augmented", "generation"));

    private final List<CourseChunk> chunks;
    private final List<Integer> lengths = new ArrayList<>();
    private final Map<String, Map<Integer, Integer>> postings = new HashMap<>();
    private final double averageLength;

    public KeywordIndex(List<CourseChunk> chunks) {
        this.chunks = List.copyOf(chunks);
        for (int id = 0; id < chunks.size(); id++) {
            List<String> words = tokens(chunks.get(id).text());
            lengths.add(words.size());
            for (String term : words) postings.computeIfAbsent(term, ignored -> new HashMap<>()).merge(id, 1, Integer::sum);
        }
        averageLength = Math.max(1, lengths.stream().mapToInt(Integer::intValue).average().orElse(1));
    }

    public List<CourseChunk> search(String question) {
        List<Set<String>> concepts = queryConcepts(question);
        Set<String> query = new HashSet<>();
        concepts.forEach(query::addAll);
        // Keep unknown topic words in the denominator: ignoring them makes incidental known words look relevant.
        Map<Set<String>, Double> weights = new LinkedHashMap<>();
        for (Set<String> concept : concepts) {
            Set<Integer> matchingDocuments = new HashSet<>();
            for (String term : concept) matchingDocuments.addAll(postings.getOrDefault(term, Map.of()).keySet());
            weights.put(concept, Math.max(1, Math.log(1 + (chunks.size() + 0.5) / (matchingDocuments.size() + 0.5))));
        }
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<Integer, Double> scores = new HashMap<>();
        for (String term : query) {
            Map<Integer, Integer> matches = postings.getOrDefault(term, Map.of());
            double idf = Math.log(1 + (chunks.size() - matches.size() + 0.5) / (matches.size() + 0.5));
            matches.forEach((id, tf) -> {
                double score = idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * lengths.get(id) / averageLength));
                scores.merge(id, score, Double::sum);
            });
        }
        Set<String> pages = new HashSet<>();
        List<CourseChunk> result = new ArrayList<>();
        scores.entrySet().stream().filter(entry -> entry.getValue() >= 0.25)
                .filter(entry -> hasTopicCoverage(entry.getKey(), weights, totalWeight))
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> {
                    CourseChunk chunk = chunks.get(entry.getKey());
                    if (!isNonTeachingPage(chunk) && result.size() < 4
                            && pages.add(chunk.fileName() + "\u0000" + chunk.page())) result.add(chunk);
                });
        return List.copyOf(result);
    }

    private boolean hasTopicCoverage(int document, Map<Set<String>, Double> weights, double totalWeight) {
        if (totalWeight == 0) return false;
        double covered = 0;
        for (var concept : weights.entrySet()) {
            if (concept.getKey().stream().anyMatch(term -> postings.getOrDefault(term, Map.of()).containsKey(document))) {
                covered += concept.getValue();
            }
        }
        return covered / totalWeight >= 0.5;
    }

    private static List<Set<String>> queryConcepts(String question) {
        List<Set<String>> concepts = new ArrayList<>();
        Set<String> aliasTerms = new HashSet<>();
        Set<String> originalTerms = new LinkedHashSet<>(tokens(question));
        for (var acronym : ACRONYMS.entrySet()) {
            if (originalTerms.contains(acronym.getKey()) || originalTerms.containsAll(acronym.getValue().stream()
                    .filter(term -> !term.equals(acronym.getKey())).toList())) {
                concepts.add(acronym.getValue());
                aliasTerms.addAll(acronym.getValue());
            }
        }
        boolean translated = false;
        for (var alias : ALIASES.entrySet()) {
            if (question.contains(alias.getKey())) {
                translated = true;
                Set<String> alternatives = Set.copyOf(tokens(alias.getValue()));
                concepts.add(alternatives);
                aliasTerms.addAll(alternatives);
            }
        }
        for (String term : originalTerms) {
            // A recognized Chinese concept is one group of English alternatives, not multiple required words.
            // Its surrounding Chinese question grammar must not dilute that concept's coverage.
            if (!aliasTerms.contains(term) && (!translated || term.matches("[a-z][a-z0-9_]+"))) {
                concepts.add(Set.of(term));
            }
        }
        return concepts.stream().distinct().toList();
    }

    private static boolean isNonTeachingPage(CourseChunk chunk) {
        String normalized = chunk.text().strip().toLowerCase(Locale.ROOT);
        return normalized.matches("(?s)^(further\\s+reading|references|bibliography)\\b.*")
                || (normalized.startsWith("programs") && normalized.contains("week") && normalized.contains("date"))
                || (chunk.page() == 1 && normalized.contains("lecture")
                        && normalized.contains("java enterprise application development"));
    }

    private static List<String> tokens(String text) {
        var matcher = WORD.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        List<String> words = new ArrayList<>();
        while (matcher.find()) if (!STOP.contains(matcher.group())) words.add(matcher.group());
        return words;
    }
}
