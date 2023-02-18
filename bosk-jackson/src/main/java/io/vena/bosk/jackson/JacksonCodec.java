package io.vena.bosk.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;

interface JacksonCodec<T> {
	T read(JsonParser parser);
	void write(T object, JsonGenerator generator);
}
