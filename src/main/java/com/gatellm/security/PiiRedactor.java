package com.gatellm.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts PII (emails, phone numbers) from text before sending to LLM providers.
 * Replaces emails with [EMAIL] and phone numbers with [PHONE].
 */
public class PiiRedactor {

    private static final Logger log = LoggerFactory.getLogger(PiiRedactor.class);

    // Email regex: matches standard emails, handles subdomains, common TLDs
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b"
    );

    // Phone regex: handles Indian (+91, 0 prefix), international formats
    // Matches: +91 98765 43210, +91-98765-43210, 9876543210, 09876543210
    //         +1 (555) 123-4567, +1-555-123-4567, 555-123-4567
    //         With spaces, dashes, dots, parentheses
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+\\d{1,3}[\\s.-]?)?" +                 // Optional country code
            "(?:\\(?\\d{2,4}\\)?[\\s.-]?)?" +            // Optional area code
            "\\d{3,4}[\\s.-]?\\d{3,4}" +                 // Main number parts
            "(?:\\s*(?:ext|x|extension)[\\s.:]?\\d{1,5})?" // Optional extension
    );

    // More specific Indian mobile pattern (10 digits, optional +91/0)
    private static final Pattern INDIAN_MOBILE_PATTERN = Pattern.compile(
            "(?:\\+91[\\s.-]?|0)?[6-9]\\d{9}\\b"
    );

    // International format with country code
    private static final Pattern INTERNATIONAL_PHONE_PATTERN = Pattern.compile(
            "\\+\\d{1,3}[\\s.-]?\\(?\\d{1,4}\\)?[\\s.-]?\\d{3,4}[\\s.-]?\\d{3,4}"
    );

    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String original = text;
        String redacted = text;

        // Redact emails
        redacted = redactEmails(redacted);

        // Redact phones (Indian first, then international, then generic)
        redacted = redactPhones(redacted);

        if (!original.equals(redacted)) {
            log.debug("PII redacted: {} chars replaced", original.length() - redacted.length());
        }

        return redacted;
    }

    private String redactEmails(String text) {
        Matcher matcher = EMAIL_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "[EMAIL]");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String redactPhones(String text) {
        // Order matters: most specific first
        String result = text;

        // Indian mobiles
        result = replacePattern(result, INDIAN_MOBILE_PATTERN);

        // International format with country code
        result = replacePattern(result, INTERNATIONAL_PHONE_PATTERN);

        // Generic phone pattern (catches remaining formats)
        result = replacePattern(result, PHONE_PATTERN);

        return result;
    }

    private String replacePattern(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            // Avoid double-redaction: skip if already contains [PHONE] or [EMAIL]
            String match = matcher.group();
            if (!match.contains("[PHONE]") && !match.contains("[EMAIL]")) {
                matcher.appendReplacement(sb, "[PHONE]");
                found = true;
            } else {
                matcher.appendReplacement(sb, match);
            }
        }
        matcher.appendTail(sb);
        return found ? sb.toString() : text;
    }
}