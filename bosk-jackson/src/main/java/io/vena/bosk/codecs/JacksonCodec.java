package io.vena.bosk.codecs;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;

public interface JacksonCodec<T> {
	T read(JsonParser parser);
	void write(T object, JsonGenerator generator);
}
