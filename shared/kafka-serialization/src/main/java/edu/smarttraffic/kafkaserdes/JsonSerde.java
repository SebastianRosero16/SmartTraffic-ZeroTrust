package edu.smarttraffic.kafkaserdes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.Objects;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

public final class JsonSerde<T> implements Serde<T> {
    private final Serializer<T> serializer;
    private final Deserializer<T> deserializer;

    public JsonSerde(Class<T> type) {
        Objects.requireNonNull(type, "Target type is required");
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        serializer = (topic, value) -> serialize(mapper, value, topic);
        deserializer = (topic, data) -> deserialize(mapper, type, data, topic);
    }

    @Override
    public Serializer<T> serializer() {
        return serializer;
    }

    @Override
    public Deserializer<T> deserializer() {
        return deserializer;
    }

    private static <T> byte[] serialize(ObjectMapper mapper, T value, String topic) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new SerializationException("Could not serialize a Kafka record for topic " + topic, exception);
        }
    }

    private static <T> T deserialize(ObjectMapper mapper, Class<T> type, byte[] data, String topic) {
        if (data == null) {
            return null;
        }
        try {
            return mapper.readValue(data, type);
        } catch (IOException exception) {
            throw new SerializationException("Could not deserialize a Kafka record from topic " + topic, exception);
        }
    }
}
