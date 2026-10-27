package edu.t07.coursemate.rag;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Small in-memory inverted index with BM25 ranking. No embedding API or external service. */
public final class KeywordIndex {
    // Keep the three highest-ranked distinct pages; matches the fixed Hit@3 context budget.
    private static final int MAX_PAGES = 3;
    private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9_]+|[\\p{IsHan}]{2}");
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "this", "that", "what", "when",
            "where", "which", "why", "how", "does", "are", "can", "you", "your", "from", "into", "about",
            "explain", "please", "java", "course", "programming", "code", "example", "examples", "using",
            "use", "is", "in", "of", "to", "an", "as", "on", "or", "it", "be", "by", "at", "do", "me",
            "simple", "terms", "simply", "briefly", "describe", "give", "tell", "show", "help", "understand",
            "detail", "details", "according", "lecture", "lectures", "based",
            "used", "here",
            "improve", "improves", "improving", "eliminate", "eliminates", "reduce", "reduces",
            "should", "instead", "than", "between", "difference", "different", "versus", "vs",
            "interact", "not", "my", "its", "their", "them", "these", "those", "a", "i",
            "count", "frequency", "words", "word", "text", "each", "elements", "element",
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
    private static final Map<String, String> CHINESE = Map.ofEntries(
            Map.entry("类型安全", "type safety"), Map.entry("强制类型转换", "casting"),
            Map.entry("参数化类型", "parameterized types"), Map.entry("类型参数", "type parameters"),
            Map.entry("遍历", "iteration"), Map.entry("迭代", "iteration"), Map.entry("删除", "remove"),
            Map.entry("键值", "map"), Map.entry("字典", "map"), Map.entry("词频", "map merge"),
            Map.entry("断言", "assertions"), Map.entry("受检", "checked"), Map.entry("运行时", "runtime"));
    private static final List<Set<String>> SYNONYMS = List.of(
            Set.of("generic", "generics"), Set.of("exception", "exceptions", "ioexception"),
            Set.of("map", "hashmap", "linkedhashmap", "treemap", "dictionary"),
            Set.of("list", "arraylist", "linkedlist"), Set.of("set", "hashset", "treeset", "linkedhashset"),
            Set.of("iteration", "iterator", "iterating", "iterate", "traverse", "traverses", "entryset", "remove"),
            Set.of("type", "types"), Set.of("casting", "cast", "casts"),
            Set.of("parameter", "parameters", "argument", "arguments"),
            Set.of("assertions", "assertion", "assertequals", "assertthrows", "asserttrue"));
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

    public List<CourseChunk> search(String question) { return retrieve(question).evidence(); }

    public RetrievalResult retrieve(String question) {
        QueryPlan plan = QueryPlan.understand(question);
        Set<String> query = new HashSet<>(tokens(normalizeQuery(question)));
        query.addAll(plan.terms());
        Map<Integer, Double> scores = recall(plan.topics().isEmpty() ? query : plan.terms());
        if (!plan.topics().isEmpty()) recall(query).forEach((id, score) -> scores.merge(id, score * 0.15, Double::sum));
        Set<String> facetTerms = new LinkedHashSet<>();
        plan.intents().values().forEach(facetTerms::addAll);
        plan.attributes().values().forEach(facetTerms::addAll);
        Map<Integer, Double> facetScores = recall(facetTerms);
        // With no known subject, generic facets cannot alone establish lexical relevance.
        Set<String> subjectQuery = new HashSet<>(query);
        subjectQuery.removeAll(facetTerms);
        List<RetrievalResult.Hit> ranked = new ArrayList<>();
        for (var entry : scores.entrySet()) {
            CourseChunk c = chunks.get(entry.getKey());
            if (isNonTeachingPage(c)) continue;
            double coverage = topicCoverage(entry.getKey(), plan);
            double score = entry.getValue();
            // Rerank recalled subject matches with bilingual intent/attribute BM25 evidence.
            // Facet-only hits cannot add candidates or relax evidence admission.
            if (coverage > 0) score += 0.5 * facetScores.getOrDefault(entry.getKey(), 0.0);
            // Java tasks prefer Java teaching pages over C++ revision, even when generic vocabulary matches.
            String lower = c.text().toLowerCase(Locale.ROOT);
            if (!question.toLowerCase(Locale.ROOT).contains("c++") && lower.contains("c++")) score *= 0.12;
            Set<String> heading = Set.copyOf(tokens(c.title().isBlank() ? c.text().lines().findFirst().orElse("") : c.title()));
            long headingHits = plan.terms().stream().filter(heading::contains).count();
            score *= 1 + Math.min(3, headingHits) * 0.35;
            score *= plan.topics().isEmpty() ? 1 : (0.3 + coverage);
            if (coverage > 0) score *= 1 + 2 * conceptCoverage(entry.getKey(), plan.intents());
            ranked.add(new RetrievalResult.Hit(c, entry.getValue(), score, coverage));
        }
        ranked.sort(Comparator.comparingDouble(RetrievalResult.Hit::score).reversed().thenComparing(h -> h.chunk().chunkId()));
        List<RetrievalResult.Hit> candidates = List.copyOf(ranked.subList(0, Math.min(20, ranked.size())));
        List<CourseChunk> evidence = new ArrayList<>();
        int characters = 0;
        Set<String> acceptedTopics = new HashSet<>();
        for (var hit : ranked) {
            if (plan.outsideDomain() && !plan.partial()) continue;
            if (!question.toLowerCase(Locale.ROOT).contains("c++") && hit.chunk().text().toLowerCase(Locale.ROOT).contains("c++")) continue;
            boolean sufficient = !plan.topics().isEmpty() ? hit.coverage() > 0
                    : lexicalCoverage(hit.chunk(), query) >= 0.5 && lexicalCoverage(hit.chunk(), subjectQuery) >= 0.5;
            if (!sufficient || hit.bm25() < 0.10) continue;
            // Keep complementary chunks, including different ranges of the same PDF page.
            boolean duplicate = evidence.stream().anyMatch(c -> c.chunkId().equals(hit.chunk().chunkId())
                    || c.fileName().equals(hit.chunk().fileName()) && c.page() == hit.chunk().page()
                    && (c.text().contains(hit.chunk().text()) || hit.chunk().text().contains(c.text())));
            if (duplicate) continue;
            long pageCount = evidence.stream().map(c -> c.fileName() + ":" + c.page()).distinct().count();
            boolean samePage = evidence.stream().anyMatch(c -> c.fileName().equals(hit.chunk().fileName()) && c.page() == hit.chunk().page());
            if ((!samePage && pageCount >= 3) || evidence.size() >= 4 || characters + hit.chunk().text().length() > 5000) continue;
            evidence.add(hit.chunk()); characters += hit.chunk().text().length();
            for (var topic : plan.topics().entrySet()) if (topic.getValue().stream().anyMatch(t -> postings.getOrDefault(t, Map.of()).containsKey(chunks.indexOf(hit.chunk())))) acceptedTopics.add(topic.getKey());
        }
        // Join only verified overlapping ranges; never synthesize omitted text between separate excerpts.
        for (int i = 0; i < evidence.size(); i++) {
            for (int j = i + 1; j < evidence.size();) {
                CourseChunk merged = CourseChunk.merge(evidence.get(i), evidence.get(j));
                if (merged != null) { evidence.set(i, merged); evidence.remove(j); } else j++;
            }
        }
        List<String> missing = plan.topics().keySet().stream().filter(t -> !acceptedTopics.contains(t)).toList();
        RetrievalResult.Status status = candidates.isEmpty() ? RetrievalResult.Status.NO_HITS
                : evidence.isEmpty() ? RetrievalResult.Status.INSUFFICIENT
                : plan.partial() || !missing.isEmpty() ? RetrievalResult.Status.PARTIAL : RetrievalResult.Status.READY;
        return new RetrievalResult(status, question, candidates, evidence, missing, "");
    }

    private Map<Integer, Double> recall(Set<String> query) {
        Map<Integer, Double> scores = new HashMap<>();
        for (String term : query) {
            Map<Integer, Integer> matches = postings.getOrDefault(term, Map.of());
            double idf = Math.log(1 + (chunks.size() - matches.size() + 0.5) / (matches.size() + 0.5));
            matches.forEach((id, tf) -> scores.merge(id, idf * tf * 2.2 /
                    (tf + 1.2 * (0.25 + 0.75 * lengths.get(id) / averageLength)), Double::sum));
        }
        return scores;
    }
    private double topicCoverage(int id, QueryPlan plan) {
        return conceptCoverage(id, plan.topics());
    }
    private double conceptCoverage(int id, Map<String, Set<String>> concepts) {
        if (concepts.isEmpty()) return 0;
        long count = concepts.values().stream().filter(terms -> terms.stream()
                .anyMatch(term -> postings.getOrDefault(term, Map.of()).containsKey(id))).count();
        return (double) count / concepts.size();
    }
    private static double lexicalCoverage(CourseChunk c, Set<String> query) {
        if (query.isEmpty()) return 0;
        Set<String> words = Set.copyOf(tokens(c.text()));
        return (double) query.stream().filter(words::contains).count() / query.size();
    }

    static boolean hasCourseConcept(String question) {
        String normalized = normalizeQuery(question);
        Set<String> words = Set.copyOf(tokens(normalized));
        return QueryPlan.hasCourseTopic(question) || SYNONYMS.stream().anyMatch(group -> group.stream().anyMatch(words::contains))
                || words.stream().anyMatch(Set.of("inheritance", "encapsulation", "polymorphism", "junit", "rag", "overriding", "overloading")::contains);
    }

    private static String normalizeQuery(String question) {
        String normalized = Normalizer.normalize(question, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        // Replace meaningful phrases first; unknown topic words remain and still count against relevance.
        normalized = normalized.replaceAll("\\b[a-z]\\w*\\s*<[^<>\\r\\n]{1,100}>", "generics")
                .replaceAll("\\bkey[ -]value\\b", "map")
                .replaceAll("\\b(?:count(?:ing)? (?:the )?(?:frequency of )?words|word (?:frequency|count))\\b", "map merge");
        List<Map.Entry<String, String>> translations = new ArrayList<>(CHINESE.entrySet());
        translations.addAll(ALIASES.entrySet());
        translations.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        for (var entry : translations) normalized = normalized.replace(entry.getKey(), " " + entry.getValue() + " ");
        return normalized.replaceAll("为什么能够|为什么|能够|提高|减少|详细|说明|根据|课件|资料|请|解释|分析|一下|这段|此段|选中|当前|代码|怎么|如何|什么|中的|的|并|和|与|及|元素|作用", " ");
    }

    private static List<Set<String>> queryConcepts(String question) {
        question = normalizeQuery(question);
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
        for (var group : SYNONYMS) {
            if (group.stream().anyMatch(originalTerms::contains)) {
                concepts.add(group);
                aliasTerms.addAll(group);
            }
        }
        for (String term : originalTerms) {
            if (!aliasTerms.contains(term)) {
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
