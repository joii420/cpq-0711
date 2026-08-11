package com.cpq.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** REST precision field deserializer: canonical plain decimal JSON strings only. */
public final class DecimalStringDeserializer extends JsonDeserializer<BigDecimal> {

    private static final Pattern PLAIN_DECIMAL =
            Pattern.compile("[+-]?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");

    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            throw InvalidFormatException.from(parser,
                    "precision-sensitive decimal must be a JSON string", parser.getText(), BigDecimal.class);
        }
        String text = parser.getText();
        if (text == null || !PLAIN_DECIMAL.matcher(text).matches()) {
            throw InvalidFormatException.from(parser,
                    "decimal must use plain notation", text, BigDecimal.class);
        }
        BigDecimal value = new BigDecimal(text);
        if (Math.max(value.scale(), 0) > PrecisionPolicy.CALCULATION_SCALE) {
            throw InvalidFormatException.from(parser,
                    "decimal supports at most 12 fractional digits", text, BigDecimal.class);
        }
        return value;
    }
}
