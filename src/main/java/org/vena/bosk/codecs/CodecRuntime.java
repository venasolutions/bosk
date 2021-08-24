package org.vena.bosk.codecs;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import org.vena.bosk.codecs.GsonAdapterCompiler.Codec;

public abstract class CodecRuntime implements Codec {
	@SuppressWarnings({"rawtypes","unchecked"})
	protected static void dynamicWriteField(Object fieldValue, String fieldName, TypeToken<?> typeToken, Gson gson, JsonWriter out) throws IOException {
		TypeAdapter adapter = gson.getAdapter(typeToken);
		out.name(fieldName);
		adapter.write(out, fieldValue);
	}

}
