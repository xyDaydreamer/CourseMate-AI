import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Select countWords and use the editor's Ask CourseMate AI action. */
public class CourseMateDemo {
    public static Map<String, Integer> countWords(String text) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        Map<String, Integer> counts = new HashMap
        return Map.copyOf(counts);
    }

    public static void main(String[] args) {
        System.out.println(countWords("Java maps Java collections"));
    }
}
