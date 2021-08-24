package org.vena.bosk.codecs;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vena.bosk.Bosk;
import org.vena.bosk.Catalog;
import org.vena.bosk.Entity;
import org.vena.bosk.GsonPlugin;
import org.vena.bosk.GsonPlugin.FieldModerator;
import org.vena.bosk.Reference;
import org.vena.bosk.ReflectiveEntity;
import org.vena.bosk.annotations.DerivedRecord;
import org.vena.bosk.bytecode.ClassBuilder;
import org.vena.bosk.bytecode.LocalVariable;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.exceptions.NotYetImplementedException;

import static java.util.Arrays.asList;
import static org.vena.bosk.ReferenceUtils.getterMethod;
import static org.vena.bosk.ReferenceUtils.parameterType;
import static org.vena.bosk.ReferenceUtils.rawClass;
import static org.vena.bosk.ReferenceUtils.theOnlyConstructorFor;
import static org.vena.bosk.SerializationPlugin.isImplicitParameter;
import static org.vena.bosk.bytecode.ClassBuilder.here;
import static org.vena.bosk.util.Types.parameterizedType;

@RequiredArgsConstructor
public final class GsonAdapterCompiler {
	private final GsonPlugin gsonPlugin;
	private final ThreadLocal<Deque<Type>> compilationsInProgress = ThreadLocal.withInitial(ArrayDeque::new);

	public <T> TypeAdapter<T> compiled(TypeToken<T> typeToken, Bosk<?> bosk, Gson gson, FieldModerator moderator) {
		try {
			compilationsInProgress.get().addLast(typeToken.getType());
			@SuppressWarnings("unchecked")
			Class<T> nodeClass = (Class<T>) typeToken.getRawType();
			Constructor<?> constructor = theOnlyConstructorFor(nodeClass);
			List<Parameter> parameters = asList(constructor.getParameters());

			ClassBuilder<Codec> cb = new ClassBuilder<>("GSON_CODEC_" + nodeClass.getSimpleName(), CodecRuntime.class, here());
			cb.beginClass();

			generate_writeFields(nodeClass, gson, parameters, cb);
			generate_instantiateFrom(constructor, parameters, cb);

			Codec codec = cb.buildInstance();

			LinkedHashMap<String, Parameter> parametersByName = new LinkedHashMap<>();
			parameters.forEach(p -> parametersByName.put(p.getName(), p));
			return new CodecWrapper<>(codec, gson, bosk, nodeClass, parametersByName, moderator);
		} finally {
			Type removed = compilationsInProgress.get().removeLast();
			assert removed.equals(typeToken.getType());
		}
	}

	/**
	 * The interface to the compiled code for a given type.
	 */
	interface Codec {
		/**
		 * Send all fields of <code>node</code> to the given <code>jsonWriter</code>
		 * as name+value pairs.
		 *
		 * @return Nothing. {@link ClassBuilder} does not yet support void methods.
		 */
		Object writeFields(Object node, JsonWriter jsonWriter) throws IOException;

		/**
		 * A more efficient version of Constructor.newInstance
		 */
		Object instantiateFrom(List<Object> parameterValues) throws IOException;
	}

	/**
	 * Implements the {@link TypeAdapter} interface using a {@link Codec} object.
	 * This allows us to design the Codec interface for ease of compilation
	 * without having to generate <code>TypeAdapter</code> boilerplate code.
	 */
	@Value
	@EqualsAndHashCode(callSuper = false)
	private class CodecWrapper<T> extends TypeAdapter<T> {
		Codec codec;
		Gson gson;
		Bosk<?> bosk;
		Class<T> nodeClass;
		LinkedHashMap<String, Parameter> parametersByName;
		FieldModerator moderator;

		@Override
		public void write(JsonWriter out, T value) throws IOException {
			out.beginObject();
			codec.writeFields(value, out);
			out.endObject();
		}

		@Override
		public T read(JsonReader in) throws IOException {
			// Performance-critical. Pre-compute as much as possible in the constructor
			// Note: the reading side can't be as efficient as the writing side
			// because we need to tolerate the fields arriving in arbitrary order.
			in.beginObject();
			Map<String, Object> valueMap = gsonPlugin.gatherParameterValuesByName(nodeClass, parametersByName, moderator, in, gson);
			in.endObject();

			List<Object> parameterValues = gsonPlugin.parameterValueList(nodeClass, valueMap, parametersByName, bosk);

			@SuppressWarnings("unchecked")
			T result = (T)codec.instantiateFrom(parameterValues);
			return result;
		}
	}

	private void generate_writeFields(Class<?> nodeClass, Gson gson, List<Parameter> parameters, ClassBuilder<Codec> cb) {
		cb.beginMethod(CODEC_WRITE_FIELDS);
		try {
			// Incoming arguments
			final LocalVariable node = cb.parameter(1);
			final LocalVariable jsonWriter = cb.parameter(2);

			for (Parameter parameter : parameters) {
				boolean isImplicit = isImplicitParameter(nodeClass, parameter);
				if (!isImplicit) {
					String name = parameter.getName();

					// Build a FieldWritePlan
					FieldWritePlan plan;
					Type parameterType = parameter.getParameterizedType();
					if (compilationsInProgress.get().contains(parameterType)) {
						// Avoid infinite recursion - look up this field's adapter dynamically
						plan = new OrdinaryFieldWritePlan();
					} else {
						plan = new StaticallyBoundFieldWritePlan();
					}
					if (nodeClass.isAnnotationPresent(DerivedRecord.class)) {
						plan = new ReferencingFieldWritePlan(plan, nodeClass.getSimpleName());
					}
					boolean isOptional = Optional.class.isAssignableFrom(parameter.getType());
					if (isOptional) {
						plan = new OptionalFieldWritePlan(plan);
					}

					LOGGER.debug("FieldWritePlan for {}.{}: {}", nodeClass.getSimpleName(), name, plan);

					// Get the field value
					cb.pushLocal(node);
					cb.castTo(nodeClass);
					cb.invoke(getterMethod(nodeClass, name));

					plan.call_writeField(name, cb, gson, jsonWriter, parameterType);
				}
			}
			// TODO: Support void methods
			cb.pushLocal(node);
		} catch (InvalidTypeException e) {
			throw new NotYetImplementedException(e);
		}
		cb.finishMethod();
	}

	/**
	 * Permits us to build a "stack" of independent objects that cooperate to
	 * generate the code to write a single field's value. This helps us tame
	 * the multitude of combinations of cases.
	 */
	private interface FieldWritePlan {
		void call_writeField(String name, ClassBuilder<Codec> cb, Gson gson, LocalVariable jsonWriter, Type type);
	}

	@Value
	private static class OrdinaryFieldWritePlan implements FieldWritePlan {
		@Override
		public void call_writeField(String name, ClassBuilder<Codec> cb, Gson gson, LocalVariable jsonWriter, Type type) {
			cb.pushField(cb.curry(name));
			cb.pushField(cb.curry(TypeToken.get(type)));
			cb.pushField(cb.curry(gson));
			cb.pushLocal(jsonWriter);
			cb.invoke(DYNAMIC_WRITE_FIELD);
		}
	}

	@Value
	private static class StaticallyBoundFieldWritePlan implements FieldWritePlan {
		@Override
		public void call_writeField(String name, ClassBuilder<Codec> cb, Gson gson, LocalVariable jsonWriter, Type type) {
			LocalVariable fieldValue = cb.popToLocal();

			cb.pushLocal(jsonWriter);
			cb.pushField(cb.curry(name));
			cb.invoke(JSONWRITER_NAME);
			cb.pop();

			cb.pushField(cb.curry(gson.getAdapter(TypeToken.get(type))));
			cb.pushLocal(jsonWriter);
			cb.pushLocal(fieldValue);
			cb.invoke(TYPEADAPTER_WRITE);
		}
	}

	@Value
	private static class OptionalFieldWritePlan implements FieldWritePlan {
		/**
		 * Handles the value inside the {@link Optional}.
		 */
		FieldWritePlan valueWriter;

		@Override
		public void call_writeField(String name, ClassBuilder<Codec> cb, Gson gson, LocalVariable jsonWriter, Type type) {
			cb.castTo(Optional.class);
			LocalVariable optional = cb.popToLocal();
			cb.pushLocal(optional);
			cb.invoke(OPTIONAL_IS_PRESENT);
			cb.ifTrue(()-> {
				// Unwrap
				cb.pushLocal(optional);
				cb.invoke(OPTIONAL_GET);

				// Write the value
				valueWriter.call_writeField(name, cb, gson, jsonWriter,
					parameterType(type, Optional.class, 0));
			});
		}
	}

	@Value
	private static class ReferencingFieldWritePlan implements FieldWritePlan {
		FieldWritePlan nonEntityWriter;
		String nodeClassName;

		@Override
		public void call_writeField(String name, ClassBuilder<Codec> cb, Gson gson, LocalVariable jsonWriter, Type type) {
			Class<?> parameterClass = rawClass(type);
			boolean isEntity = Entity.class.isAssignableFrom(parameterClass);
			if (isEntity) {
				if (ReflectiveEntity.class.isAssignableFrom(parameterClass)) {
					cb.invoke(REFLECTIVE_REFERENCE);
					// Recurse to write the Reference
					call_writeField(name, cb, gson, jsonWriter, parameterizedType(Reference.class, type));
				} else {
					throw new IllegalArgumentException(String.format("%s %s cannot contain Entity that is not a ReflectiveEntity: \"%s\"", DerivedRecord.class.getSimpleName(), nodeClassName, name));
				}
			} else if (Catalog.class.isAssignableFrom(parameterClass)) {
				throw new IllegalArgumentException(String.format("%s %s cannot contain Catalog \"%s\" (try Listing?)", DerivedRecord.class.getSimpleName(), nodeClassName, name));
			} else {
				nonEntityWriter.call_writeField(name, cb, gson, jsonWriter, type);
			}
		}
	}

	private void generate_instantiateFrom(Constructor<?> constructor, List<Parameter> parameters, ClassBuilder<Codec> cb) {
		cb.beginMethod(CODEC_INSTANTIATE_FROM);

		// Incoming arguments
		final LocalVariable parameterValues = cb.parameter(1);

		// New object
		cb.instantiate(constructor.getDeclaringClass());

		// Push parameters and invoke constructor
		cb.dup();
		for (int i = 0; i < parameters.size(); i++) {
			cb.pushLocal(parameterValues);
			cb.pushInt(i);
			cb.invoke(LIST_GET);
			cb.castTo(parameters.get(i).getType());
		}
		cb.invoke(constructor);

		cb.finishMethod();
	}

	private static final Method CODEC_WRITE_FIELDS, CODEC_INSTANTIATE_FROM;
	private static final Method DYNAMIC_WRITE_FIELD;
	private static final Method LIST_GET;
	private static final Method OPTIONAL_IS_PRESENT, OPTIONAL_GET;
	private static final Method REFLECTIVE_REFERENCE;
	private static final Method JSONWRITER_NAME, TYPEADAPTER_WRITE;

	static {
		try {
			CODEC_WRITE_FIELDS = Codec.class.getDeclaredMethod("writeFields", Object.class, JsonWriter.class);
			CODEC_INSTANTIATE_FROM = Codec.class.getDeclaredMethod("instantiateFrom", List.class);
			DYNAMIC_WRITE_FIELD = CodecRuntime.class.getDeclaredMethod("dynamicWriteField", Object.class, String.class, TypeToken.class, Gson.class, JsonWriter.class);
			LIST_GET = List.class.getDeclaredMethod("get", int.class);
			OPTIONAL_IS_PRESENT = Optional.class.getDeclaredMethod("isPresent");
			OPTIONAL_GET = Optional.class.getDeclaredMethod("get");
			REFLECTIVE_REFERENCE = ReflectiveEntity.class.getDeclaredMethod("reference");
			JSONWRITER_NAME = JsonWriter.class.getDeclaredMethod("name", String.class);
			TYPEADAPTER_WRITE = TypeAdapter.class.getDeclaredMethod("write", JsonWriter.class, Object.class);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(GsonAdapterCompiler.class);

}
