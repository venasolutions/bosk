package io.vena.bosk.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.vena.bosk.jackson.JacksonCompiler.Codec;
import java.io.IOException;

/**
 * <strong>This is not part of the public API.</strong>
 * This class must be public so it can be the superclass of our dynamically
 * generated classes.
 */
public abstract class JacksonCodecRuntime implements Codec {
	/**
	 * Looks up a {@link JsonSerializer} at serialization time, and uses it to {@link JsonSerializer#serialize} serialize} the given field.
	 *
	 * <p>
	 * This is the basic, canonical way to write fields, but usually we can optimize
	 * this by looking up the {@link JsonSerializer} ahead of time, while compiling the
	 * codec, so we can save the overhead of the lookup operation during serialization.
	 */
	protected static void dynamicWriteField(
		Object fieldValue,
		String fieldName,
		JavaType type,
		JsonGenerator gen,
		SerializerProvider serializers
	) throws IOException {
		gen.writeFieldName(fieldName);
		serializers
			.findValueSerializer(type)
			.serialize(fieldValue, gen, serializers);
	}

}
