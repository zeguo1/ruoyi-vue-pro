package cn.iocoder.yudao.framework.common.util.json.databind;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Epoch milliseconds and explicit date strings; never coerce malformed input to epoch zero. */
public class TimestampLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> implements ContextualDeserializer {
    public static final TimestampLocalDateTimeDeserializer INSTANCE = new TimestampLocalDateTimeDeserializer();
    private final String pattern;
    public TimestampLocalDateTimeDeserializer() { this(""); }
    private TimestampLocalDateTimeDeserializer(String pattern) { this.pattern = pattern; }

    @Override public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
        JsonFormat format = property == null ? null : property.getAnnotation(JsonFormat.class);
        return format == null || format.pattern().isBlank() ? INSTANCE : new TimestampLocalDateTimeDeserializer(format.pattern());
    }

    @Override public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        try {
            if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return epoch(parser.getLongValue());
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                String text = parser.getText().trim();
                if (text.matches("-?\\d+")) return epoch(Long.parseLong(text));
                if (!pattern.isBlank()) {
                    var formatter = new java.time.format.DateTimeFormatterBuilder().appendPattern(pattern)
                            .parseDefaulting(java.time.temporal.ChronoField.ERA, 1).toFormatter()
                            .withResolverStyle(java.time.format.ResolverStyle.STRICT);
                    var parsed = formatter.parseBest(text, LocalDateTime::from, LocalDate::from);
                    return parsed instanceof LocalDateTime dateTime ? dateTime : ((LocalDate) parsed).atStartOfDay();
                }
                try { return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); }
                catch (java.time.format.DateTimeParseException ignored) {
                    return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                }
            }
        } catch (RuntimeException exception) {
            // Fall through to a Jackson validation error, retaining its complete field path.
        }
        return (LocalDateTime) context.handleWeirdStringValue(LocalDateTime.class, parser.getValueAsString(),
                "需要整数毫秒时间戳或合法日期时间字符串%s", pattern.isBlank() ? "（ISO 8601）" : "（" + pattern + "）");
    }
    private static LocalDateTime epoch(long value) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneId.systemDefault());
    }
}
