package com.example.backend.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps stored reply bodies small.
 *
 * Before: the FULL text of every reply was stored, including the whole quoted history
 * ("On Mon ... wrote: > > > ..."), and the raw HTML source for HTML-only emails. Every new
 * reply repeated everything that came before it, so rfi_email_messages.body_text grew fast.
 *
 * Now:
 *  - text/plain is preferred; HTML-only mail is converted to plain text
 *  - the quoted history is cut off (the original thread is still in Gmail)
 *  - the result is capped (default 20,000 characters)
 *
 * Safety rule: if cutting would leave NOTHING (a mail that is only a quote), the text is kept.
 */
public final class EmailBodyCleaner {

    public static final int DEFAULT_MAX_CHARS = 20_000;
    private static final String TRUNCATED_NOTE = "\n\n[... message truncated ...]";

    // "On Mon, 3 Jan 2026 at 10:00, John <j@x.com> wrote:"  (Gmail may wrap it onto a 2nd line)
    // plus a few common translations.
    private static final Pattern ON_WROTE = Pattern.compile(
            "(?m)^[ \\t]*(?:On|El|Le|Am)[ \\t][^\\n]{0,200}(?:\\n[^\\n]{0,200})?"
                    + "(?:wrote:|escribi\u00f3:|a \u00e9crit[ \u00a0]?:|schrieb[^\\n]{0,80}:)[ \\t]*$");

    private static final Pattern ORIGINAL_MESSAGE = Pattern.compile(
            "(?mi)^[ \\t]*-{2,}[ \\t]*(?:Original Message|Forwarded message)[ \\t]*-{2,}[ \\t]*$");

    // Outlook: "From: x\nSent: y\nTo: z"
    private static final Pattern OUTLOOK_HEADER = Pattern.compile(
            "(?mi)^[ \\t]*From:[ \\t][^\\n]+\\n[ \\t]*Sent:[ \\t]");

    // Start of the quoted part inside an HTML mail.
    private static final Pattern HTML_QUOTE_START = Pattern.compile(
            "(?i)<div[^>]+class=\"[^\"]*gmail_(?:quote|attr)[^\"]*\"|<blockquote|<div[^>]+id=\"(?:divRplyFwdMsg|appendonsend)\"");

    private static final Pattern BLOCK_TAGS = Pattern.compile("(?i)<br\\s*/?>|</(?:p|div|tr|li|h[1-6])>");
    private static final Pattern STYLE_SCRIPT_HEAD = Pattern.compile("(?is)<(style|script|head)[^>]*>.*?</\\1>");
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d{1,6});");

    private EmailBodyCleaner() {
    }

    /** Plain-text mail: cut the quoted history, then cap. */
    public static String cleanPlain(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return cap(stripQuotedReply(text), maxChars);
    }

    /** HTML-only mail: cut the quoted block, convert to text, then cap. */
    public static String cleanHtml(String html, int maxChars) {
        if (html == null) {
            return "";
        }
        String cut = html;
        Matcher m = HTML_QUOTE_START.matcher(html);
        if (m.find() && m.start() > 0) {
            String before = htmlToText(html.substring(0, m.start()));
            if (!before.isBlank()) {
                cut = html.substring(0, m.start());
            }
        }
        return cap(stripQuotedReply(htmlToText(cut)), maxChars);
    }

    /** Very small HTML -> text converter (no extra library needed). */
    public static String htmlToText(String html) {
        if (html == null) {
            return "";
        }
        String s = STYLE_SCRIPT_HEAD.matcher(html).replaceAll("");
        s = BLOCK_TAGS.matcher(s).replaceAll("\n");
        s = ANY_TAG.matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        Matcher n = NUMERIC_ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (n.find()) {
            int cp = Integer.parseInt(n.group(1));
            String rep = Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : "";
            n.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        n.appendTail(sb);
        s = sb.toString().replace("&amp;", "&");
        s = s.replace("\r\n", "\n").replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    /** Removes the quoted history. Never returns an empty string for a non-empty input. */
    public static String stripQuotedReply(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replace("\r\n", "\n");

        // 1) first "On ... wrote:" / "-----Original Message-----" / Outlook header
        int cut = -1;
        for (Pattern p : new Pattern[]{ON_WROTE, ORIGINAL_MESSAGE, OUTLOOK_HEADER}) {
            Matcher m = p.matcher(t);
            if (m.find() && (cut == -1 || m.start() < cut)) {
                cut = m.start();
            }
        }
        if (cut > 0 && !t.substring(0, cut).isBlank()) {
            t = t.substring(0, cut);
        }

        // 2) a trailing block of ">" quoted lines (inline replies in the middle are kept)
        String[] lines = t.split("\n", -1);
        int end = lines.length;
        while (end > 0) {
            String l = lines[end - 1].trim();
            if (l.isEmpty() || l.startsWith(">")) {
                end--;
            } else {
                break;
            }
        }
        if (end > 0 && end < lines.length) {
            t = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, end));
        }

        String result = t.trim();
        return result.isEmpty() ? text.trim() : result;
    }

    /** Cuts to at most maxChars (never in the middle of an emoji) and says so. */
    public static String cap(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int end = maxChars;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + TRUNCATED_NOTE;
    }
}