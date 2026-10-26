package edu.t07.coursemate.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders the small Markdown subset DeepSeek commonly returns. The chat stores the original text. */
final class ChatMarkdown {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(\\S.*)$");
    private static final Pattern BULLET = Pattern.compile("^[-*]\\s+(\\S.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\d+\\.\\s+(\\S.*)$");
    private static final Pattern FENCE_LANGUAGE = Pattern.compile("[A-Za-z0-9_+#.-]{0,32}");

    private ChatMarkdown() {}

    static String toHtml(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder html = new StringBuilder();
        // Match line fences, including longer fences used to quote literal lecture excerpts.
        Matcher opening = Pattern.compile("(?m)^(`{3,})[ \\t]*([A-Za-z0-9_+#.-]{0,32})[ \\t]*\\n").matcher(normalized);
        int position = 0;
        while (opening.find(position)) {
            html.append(prose(normalized.substring(position, opening.start())));
            Matcher closing = Pattern.compile("(?m)^`{" + opening.group(1).length() + ",}[ \\t]*(?:\\n|$)")
                    .matcher(normalized);
            int end = closing.find(opening.end()) ? closing.start() : normalized.length();
            html.append(codeBlock(opening.group(2) + "\n" + normalized.substring(opening.end(), end)));
            position = end == normalized.length() ? end : closing.end();
        }
        html.append(prose(normalized.substring(position)));
        return html.toString();
    }

    private static String codeBlock(String fenced) {
        String code = fenced;
        int newline = code.indexOf('\n');
        if (newline >= 0 && FENCE_LANGUAGE.matcher(code.substring(0, newline).strip()).matches()) {
            code = code.substring(newline + 1);
        }
        if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
        return "<pre>" + escape(code) + "</pre>";
    }

    private static String prose(String text) {
        StringBuilder html = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        String listTag = null;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            Matcher heading = HEADING.matcher(trimmed);
            Matcher bullet = BULLET.matcher(trimmed);
            Matcher numbered = NUMBERED.matcher(trimmed);
            if (trimmed.isEmpty()) {
                listTag = closeList(html, listTag);
                flushParagraph(html, paragraph);
            } else if (heading.matches()) {
                listTag = closeList(html, listTag);
                flushParagraph(html, paragraph);
                int level = heading.group(1).length();
                html.append("<h").append(level).append('>')
                        .append(inline(heading.group(2)))
                        .append("</h").append(level).append('>');
            } else if (bullet.matches() || numbered.matches()) {
                flushParagraph(html, paragraph);
                String nextTag = bullet.matches() ? "ul" : "ol";
                if (listTag != null && !listTag.equals(nextTag)) listTag = closeList(html, listTag);
                if (listTag == null) {
                    html.append('<').append(nextTag).append('>');
                    listTag = nextTag;
                }
                String item = bullet.matches() ? bullet.group(1) : numbered.group(1);
                html.append("<li>").append(inline(item)).append("</li>");
            } else {
                listTag = closeList(html, listTag);
                if (!paragraph.isEmpty()) paragraph.append("<br>");
                paragraph.append(inline(trimmed));
            }
        }
        closeList(html, listTag);
        flushParagraph(html, paragraph);
        return html.toString();
    }

    private static String inline(String escapedSource) {
        String text = escape(escapedSource);
        text = text.replaceAll("`([^`]+)`", "<code>$1</code>");
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        return text;
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.isEmpty()) return;
        html.append("<p>").append(paragraph).append("</p>");
        paragraph.setLength(0);
    }

    private static String closeList(StringBuilder html, String listTag) {
        if (listTag != null) html.append("</").append(listTag).append('>');
        return null;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
