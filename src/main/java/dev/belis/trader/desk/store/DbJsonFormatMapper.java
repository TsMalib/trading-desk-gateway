package dev.belis.trader.desk.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.hibernate.orm.JsonFormat;
import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;

import java.lang.reflect.Type;

/**
 * JSON mapper used only for jsonb columns. Kept separate from the REST/MCP ObjectMapper
 * so customizations there can never change what is written to the audit trail.
 */
@JsonFormat
@PersistenceUnitExtension
public class DbJsonFormatMapper implements FormatMapper {

    private final FormatMapper delegate = new JacksonJsonFormatMapper(new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    @Override
    public <T> T fromString(CharSequence charSequence, org.hibernate.type.descriptor.java.JavaType<T> javaType,
                            org.hibernate.type.descriptor.WrapperOptions wrapperOptions) {
        return delegate.fromString(charSequence, javaType, wrapperOptions);
    }

    @Override
    public <T> String toString(T value, org.hibernate.type.descriptor.java.JavaType<T> javaType,
                               org.hibernate.type.descriptor.WrapperOptions wrapperOptions) {
        return delegate.toString(value, javaType, wrapperOptions);
    }
}
