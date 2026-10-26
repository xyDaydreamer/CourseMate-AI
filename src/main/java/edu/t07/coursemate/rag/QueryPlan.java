package edu.t07.coursemate.rag;

import java.util.*;
import java.util.regex.Pattern;

/** Local course concept linking, separate from lexical recall and evidence admission. No model call. */
public record QueryPlan(String original, Map<String, Set<String>> topics, boolean outsideDomain, boolean partial) {
    private record Topic(String name, Pattern mentions, Set<String> terms) { }
    private static Topic topic(String name, String aliases, String terms) {
        return new Topic(name, Pattern.compile(aliases, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), Set.of(terms.split(" ")));
    }
    // A small bilingual course vocabulary, not complete-question substitutions. Ranking still uses corpus statistics.
    private static final List<Topic> CATALOG = List.of(
        topic("inheritance", "\\b(inheritance|inherited|inherits?|subclass|superclass|parent class)\\b|继承|父类|子类", "inheritance subclass superclass"),
        topic("overriding", "\\b(overrid\\w*|customi[sz]\\w*)\\b|重写|覆盖方法", "overriding override"),
        topic("polymorphism", "\\b(polymorphism|dynamic dispatch|virtual method)\\b|多态|父类引用|动态绑定", "polymorphism overriding invocation"),
        topic("encapsulation", "\\b(encapsulation|hide internal|hiding data)\\b|封装|隐藏.*(?:数据|内部)|私有.*公开", "encapsulation private public"),
        topic("generics", "\\b(generic\\w*|parameterized|type safety|type arguments?|diamond)\\b|泛型|类型安全|类型参数|参数化类型|菱形", "generic generics parameterized"),
        topic("casting", "\\b(cast|casts|casting)\\b|强制类型转换", "casting cast"),
        topic("map", "\\b(map|hashmap|treemap|linkedhashmap|dictionary|key.value|word count|word frequency|frequency of words)\\b|键值|字典|词频", "map hashmap merge"),
        topic("merge", "\\bmerge\\b", "merge"),
        topic("list", "\\b(list|arraylist|linkedlist)\\b", "list arraylist"),
        topic("set", "\\b(set|hashset|treeset|linkedhashset)\\b", "set hashset"),
        topic("iteration", "\\b(iterat\\w*|travers\\w*|entryset)\\b|遍历|迭代", "iteration iterator entryset remove"),
        topic("exception", "\\b(exception\\w*|ioexception|catch|caught|throws?|finally)\\b|异常|捕获|声明抛出", "exception exceptions throw catch"),
        topic("checked", "\\b(checked|unchecked|caught or declared|catch or specify)\\b|受检|检查型|必须.*(?:捕获|声明)", "checked unchecked requirement"),
        topic("finally", "\\bfinally\\b|最终清理", "finally"),
        topic("interface", "\\binterfaces?\\b|接口", "interface interfaces"),
        topic("overloading", "\\boverload\\w*\\b|重载", "overloading overload"),
        topic("junit", "\\b(junit|unit tests?|assert\\w*|beforeeach|aftereach)\\b|单元测试|断言", "junit testing test assertions"),
        topic("lifecycle", "\\b(beforeeach|aftereach|lifecycle)\\b|每次测试前|测试.*清理", "beforeeach aftereach lifecycle"),
        topic("rag", "\\b(rag|retrieval.augmented|knowledge base)\\b|检索增强|知识库|检索.*(?:课程|资料|模型)", "rag retrieval augmented generation"),
        topic("embedding", "\\b(embedding\\w*|vector|cosine)\\b|向量|嵌入", "embedding embeddings vector cosine"),
        topic("reflection", "\\breflection\\b|反射", "reflection"),
        topic("annotation", "\\bannotations?\\b|注解", "annotation annotations"),
        topic("lambda", "\\b(lambda|stream api)\\b|匿名函数|流操作", "lambda stream"),
        topic("constructor", "\\bconstructors?\\b|构造", "constructor constructors"),
        topic("abstraction", "\\babstract\\w*\\b|抽象", "abstract abstraction"),
        topic("array", "\\barrays?\\b|数组", "array arrays"),
        topic("string", "\\bstrings?\\b|字符串", "string strings")
    );
    private static final Pattern OUTSIDE = Pattern.compile("quantum|photon|orbital|geology|geological|lunar|volcanic|astronom|quasar|ocean|dinner|recipe|spring|transactional|量子|光子|偏振|月球|地质|晚餐|吃什么|海洋", Pattern.CASE_INSENSITIVE);

    public QueryPlan { topics = Collections.unmodifiableMap(new LinkedHashMap<>(topics)); }
    public static QueryPlan understand(String question) {
        String q = question == null ? "" : question;
        Map<String, Set<String>> found = new LinkedHashMap<>();
        for (Topic t : CATALOG) if (t.mentions.matcher(q).find()) found.put(t.name, t.terms);
        // Exact named concepts remain precise; broader relation terms are for paraphrases, not query drift.
        if (q.matches("(?is).*\\binheritance\\b.*") || q.contains("继承")) found.put("inheritance", Set.of("inheritance"));
        if (q.matches("(?is).*\\bgenerics?\\b.*") || q.contains("泛型")) found.put("generics", Set.of("generic", "generics"));
        // A parent-reference dispatch question targets polymorphism; do not let its broad inheritance prerequisite dominate.
        if (found.containsKey("polymorphism") && !q.matches("(?is).*\\binheritance\\b.*") && !q.contains("继承")) found.remove("inheritance");
        if (found.containsKey("generics") && found.containsKey("string") && !q.matches("(?is).*(?:equals|string operations|字符串处理|字符串拼接).*")) found.remove("string");
        boolean outside = OUTSIDE.matcher(q).find();
        String[] clauses = q.split("(?i)and also|and separately|also discuss|另外|同时介绍|并讨论|并解释");
        boolean hasCourseClause = false, hasUnknownClause = false;
        if (clauses.length > 1) {
            for (String clause : clauses) {
                boolean known = CATALOG.stream().anyMatch(t -> t.mentions.matcher(clause).find());
                if (known) hasCourseClause = true;
                else if (clause.strip().length() > 3) hasUnknownClause = true;
            }
        }
        boolean split = hasCourseClause && hasUnknownClause;
        return new QueryPlan(q, found, outside, split);
    }
    public Set<String> terms() {
        Set<String> terms = new LinkedHashSet<>(); topics.values().forEach(terms::addAll); return Set.copyOf(terms);
    }
    public static boolean hasCourseTopic(String question) { return !understand(question).topics.isEmpty(); }
}
