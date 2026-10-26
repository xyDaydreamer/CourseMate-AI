package edu.t07.coursemate.chat;

import com.google.gson.*;
import edu.t07.coursemate.ReplyLanguage;
import edu.t07.coursemate.ai.*;
import java.util.*;
import java.util.regex.Pattern;

/** On-demand translation of existing prose; source snapshots and code are never regenerated. */
public final class AnswerTranslation {
    @FunctionalInterface public interface Translator { ModelResponse translate(String json) throws DeepSeekException; }
    private static final Pattern PROTECTED = Pattern.compile("(?s)```.*?(?:```|$)|`[^`\\r\\n]*`|[\\p{L}\\p{N}_./\\\\:-]+\\.(?:pdf|java)|\\bS\\d+\\b|\\b\\d+\\b");
    private final Translator translator;
    public AnswerTranslation(Translator translator) { this.translator = translator; }
    public List<String> translate(AnswerResult answer, ReplyLanguage target) throws DeepSeekException {
        List<String> original = new ArrayList<>(answer.claims().stream().map(AnswerResult.Claim::text).toList());
        original.add(answer.generalAnswer());
        JsonObject request = new JsonObject(); request.addProperty("target_language", target.code());
        JsonArray segments = new JsonArray(); List<Map<String,String>> protectedValues = new ArrayList<>();
        for (String segment : original) {
            Map<String,String> tokens = new LinkedHashMap<>(); StringBuilder masked = new StringBuilder();
            var matcher = PROTECTED.matcher(segment);
            while (matcher.find()) {
                String token = "CMKEEP" + UUID.randomUUID().toString().replace("-", "") + "END";
                tokens.put(token, matcher.group()); matcher.appendReplacement(masked, token);
            }
            matcher.appendTail(masked); segments.add(masked.toString()); protectedValues.add(tokens);
        }
        request.add("segments", segments);
        ModelResponse response = translator.translate(request.toString());
        if (response.truncated()) throw new DeepSeekException(DeepSeekException.Kind.FORMAT, "Translation was incomplete; the original answer was preserved.", 0, null);
        try {
            JsonObject root = JsonParser.parseString(response.content()).getAsJsonObject();
            if (!root.keySet().equals(Set.of("segments"))) throw new IllegalArgumentException();
            JsonArray translated = root.getAsJsonArray("segments");
            if (translated.size() != original.size()) throw new IllegalArgumentException();
            List<String> result = new ArrayList<>();
            for (int i=0; i<translated.size(); i++) {
                JsonElement element = translated.get(i);
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
                String value = element.getAsString();
                if (!original.get(i).isBlank() && value.isBlank()) throw new IllegalArgumentException();
                for (var token : protectedValues.get(i).entrySet()) {
                    if (value.indexOf(token.getKey()) < 0 || value.indexOf(token.getKey()) != value.lastIndexOf(token.getKey())) throw new IllegalArgumentException();
                    value = value.replace(token.getKey(), token.getValue());
                }
                if (value.contains("CMKEEP")) throw new IllegalArgumentException();
                result.add(value);
            }
            return List.copyOf(result);
        } catch (RuntimeException e) {
            throw new DeepSeekException(DeepSeekException.Kind.FORMAT, "Translation format or protected code changed; the original answer was preserved.", 0, e);
        }
    }
}
