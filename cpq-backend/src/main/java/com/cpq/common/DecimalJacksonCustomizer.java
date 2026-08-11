package com.cpq.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.math.BigDecimal;

/** Global lossless decimal configuration for REST payloads and persisted JSON snapshots. */
@Singleton
public class DecimalJacksonCustomizer implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper mapper) {
        configure(mapper);
    }

    public static ObjectMapper newMapper() {
        return configure(new ObjectMapper());
    }

    public static ObjectMapper configure(ObjectMapper mapper) {
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);
        mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

        SimpleModule decimalStrings = new SimpleModule("cpq-decimal-strings");
        decimalStrings.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(PrecisionPolicy.toPlainDecimalString(value));
            }
        });
        mapper.registerModule(decimalStrings);
        return mapper;
    }
}
