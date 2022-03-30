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
import org.vena.bosk.Phantom;
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

	/**
	 * A stack of types for which we are in the midst of compiling a {@link TypeAdapter}.
	 *
	 * <p>
	 * Compiling for a particular node type recursively triggers compilations for the
	 * node's fields. This stack tracks those compilations to avoid infinite recursion
	 * for recursive datatypes.
	 */
	private final ThreadLocal<Deque<Type>> compilationsInProgress = ThreadLocal.withInitial(ArrayDeque::new);

	/**
	 * The main entry point to the compiler.
	 *
	 * @return a newly compiled {@link TypeAdapter} for values of the given <code>nodeType</code>.
	 */
	public <T> TypeAdapter<T> compiled(TypeToken<T> nodeTypeToken, Bosk<?> bosk, Gson gson, FieldModerator moderator) {
		try {
			// Record that we're compiling this one to avoid infinite recursion
			compilationsInProgress.get().addLast(nodeTypeToken.getType());

			// Grab some required info about the node class
			@SuppressWarnings("unchecked")
			Class<T> nodeClass = (Class<T>) nodeTypeToken.getRawType();
			Constructor<?> constructor = theOnlyConstructorFor(nodeClass);
			List<Parameter> parameters = asList(constructor.getParameters());

			// Generate the Codec class and instantiate it
			ClassBuilder<Codec> cb = new ClassBuilder<>("GSON_CODEC_" + nodeClass.getSimpleName(), CodecRuntime.class, nodeClass.getClassLoader(), here());
			cb.beginClass();

			generate_writeFields(nodeClass, gson, parameters, cb);
			generate_instantiateFrom(constructor, parameters, cb);

			Codec codec = cb.buildInstance();

			// Return a CodecWrapper for the codec
			LinkedHashMap<String, Parameter> parametersByName = new LinkedHashMap<>();
			parameters.forEach(p -> parametersByName.put(p.getName(), p));
			return new CodecWrapper<>(codec, gson, bosk, nodeClass, parametersByName, moderator);
		} finally {
			Type removed = compilationsInProgress.get().removeLast();
			assert removed.equals(nodeTypeToken.getType());
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
		 * A faster version of {@link Constructor#newInstance} without the overhead
		 * of checking for errors that we know can't happen.
		 */
		Object instantiateFrom(List<Object> parameterValues) throws IOException;
	}

	/**
	 * Generates the body of the {@link Codec#writeFields} method.
	 */
	private void generate_writeFields(Class<?> nodeClass, Gson gson, List<Parameter> parameters, ClassBuilder<Codec> cb) {
		cb.beginMethod(CODEC_WRITE_FIELDS);
		try {
			// Incoming arguments
			final LocalVariable node = cb.parameter(1);
			final LocalVariable jsonWriter = cb.parameter(2);

			for (Parameter parameter : parameters) {
				if (isImplicitParameter(nodeClass, parameter)) {
					continue;
				}
				if (Phantom.class.isAssignableFrom(parameter.getType())) {
					continue;
				}

				String name = parameter.getName();

				// Build a FieldWritePlan
				// Maintenance note: resist the urge to put case-specific intelligence into
				// building the plan. The plan should be straightforward and "obviously
				// correct". The execution of the plan should contain the sophistication.
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
				if (Optional.class.isAssignableFrom(parameter.getType())) {
					plan = new OptionalFieldWritePlan(plan);
				}

				LOGGER.debug("FieldWritePlan for {}.{}: {}", nodeClass.getSimpleName(), name, plan);

				// Put the field value on the operand stack
				cb.pushLocal(node);
				cb.castTo(nodeClass);
				cb.invoke(getterMethod(nodeClass, name));

				// Execute the plan
				plan.generateFieldWrite(name, cb, gson, jsonWriter, parameterType);
			}
			// TODO: Support void methods
			cb.pushLocal(node);
		} catch (InvalidTypeException e) {
			throw new NotYetImplementedException(e);
		}
		cb.finishMethod();
	}

	/**
	 * Generates the body of the {@link Codec#instantiateFrom} method.
	 */
	private void generate_instantiateFrom(Constructor<?> constructor, List<Parameter> parameters, ClassBuilder<Codec> cb) {
		cb.beginMethod(CODEC_INSTANTIATE_FROM);

		// Save incoming operand to local variable
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

	/**
	 * This is the building block of compiler's "intermediate form" describing
	 * how to write a single field to Gson.
	 *
	 * <p>
	 * There is a wide variety of ways that fields might need to be written,
	 * and of combinations of "modifiers" (like {@link OptionalFieldWritePlan}).
	 * It is hard to reason about these in a way that ensures we get all the combinations right.
	 * Expressing modifiers in the form of "planning" objects helps us to tame
	 * the multitude of combinations of cases. For example, how should we deal
	 * with a {@link DerivedRecord} that has an {@link Optional} field that
	 * is a recursive type? We build the appropriate stack of {@link FieldWritePlan}
	 * objects, each of whose {@link #generateFieldWrite} method handles one specific
	 * concern; and the composition of these objects will generate the appropriate code.
	 */
	private interface FieldWritePlan {
		/**
		 * Emit code that writes the given field's name and value to a {@link JsonWriter}.
		 * The value is required to be on the operand stack at the start of the generated sequence.
		 *
		 * <p>
		 * Some implementations will be stackable modifiers that perhaps emit some code,
		 * then delegate to some downstream <code>generateFieldWrite</code> method, possibly
		 * with modified parameters.
		 */
		void generateFieldWrite(String name, ClassBuilder<Codec> cb, Gson gson, LocalVariable jsonWriter, Type type);
	}

	/**
	 * The basic, un-optimized, canonical way to write a field.
	 */
	@Value
	private static class OrdinaryFieldWritePlan implements FieldWritePlan {
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void generateFieldWrite(String name, ClassBuilder<Codec> cb, Gson gson, LocalVariable jsonWriter, Type type) {
			cb.pushString(name);
			cb.pushObject(TypeToken.get(type));
			cb.pushObject(gson);
			cb.pushLocal(jsonWriter);
			cb.invoke(DYNAMIC_WRITE_FIELD);
		}
	}

	/**
	 * An optimized way to write a field that looks up the {@link TypeAdapter} at
	 * compile time to avoid the runtime overhead.
	 *
	 * <p>
	 * This the common case, and is used more often than {@link OrdinaryFieldWritePlan}
	 * because this optimization is always possible except in recursive data structures,
	 * where we can't look up the field's type adapter because we're still in the midst
	 * of compiling that very adapter itself.
	 */
	@Value
	private static class StaticallyBoundFieldWritePlan implements FieldWritePlan {
		/**
		 * {@inheritDoc}
		 */
		@Override
		public void generateFieldWrite(String name, ClassBuilder<Codec> cb, Gson gson, LocalVariable jsonWriter, Type type) {
			// Find or create the TypeAdapter for the given type.
			// If the TypeAdapter doesn't already exist, we will attempt to compile one,
			// so this is where our compiler's recursion happens.
			TypeAdapter<?> typeAdapter = gson.getAdapter(TypeToken.get(type));

			// Save incoming operand to local variable
			LocalVariable fieldValue = cb.popToLocal();

			// Write the field name
			cb.pushLocal(jsonWriter);
			cb.pushString(name);
			cb.invoke(JSON_WRITER_NAME);
			cb.pop();

			// Write the field value using the statically bound TypeAdapter
			cb.pushObject(typeAdapter);
			cb.pushLocal(jsonWriter);
			cb.pushLocal(fieldValue);
			cb.invoke(TYPE_ADAPTER_WRITE);
		}
	}

	/**
	 * A stackable wrapper that writes an <code>{@link Optional}&lt;T&gt;</code> given
	 * a {@link FieldWritePlan} for <code>T</code>.
	 */
	@Value
	private static class OptionalFieldWritePlan implements FieldWritePlan {
		/**
		 * Handles the value inside the {@link Optional}.
		 */
		FieldWritePlan valueWriter;

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void generateFieldWrite(String name, ClassBuilder<Codec> cb, Gson gson, LocalVariable jsonWriter, Type type) {
			cb.castTo(Optional.class);
			LocalVariable optional = cb.popToLocal();
			cb.pushLocal(optional);
			cb.invoke(OPTIONAL_IS_PRESENT);
			cb.ifTrue(()-> {
				// Unwrap
				cb.pushLocal(optional);
				cb.invoke(OPTIONAL_GET);

				// Write the value
				valueWriter.generateFieldWrite(name, cb, gson, jsonWriter,
					parameterType(type, Optional.class, 0));
			});
		}
	}

	/**
	 * A stackable wrapper to implement {@link DerivedRecord} semantics: writes {@link Entity}
	 * fields as {@link Reference References}, and all other fields are written using the
	 * supplied {@link #nonEntityWriter}.
	 */
	@Value
	private static class ReferencingFieldWritePlan implements FieldWritePlan {
		FieldWritePlan nonEntityWriter;
		String nodeClassName; // Just for error messages

		/**
		 * {@inheritDoc}
		 */
		@Override
		public void generateFieldWrite(String name, ClassBuilder<Codec> cb, Gson gson, LocalVariable jsonWriter, Type type) {
			Class<?> parameterClass = rawClass(type);
			boolean isEntity = Entity.class.isAssignableFrom(parameterClass);
			if (isEntity) {
				if (ReflectiveEntity.class.isAssignableFrom(parameterClass)) {
					cb.castTo(ReflectiveEntity.class);
					cb.invoke(REFLECTIVE_ENTITY_REFERENCE);
					// Recurse to write the Reference
					generateFieldWrite(name, cb, gson, jsonWriter, parameterizedType(Reference.class, type));
				} else {
					throw new IllegalArgumentException(String.format("%s %s cannot contain Entity that is not a ReflectiveEntity: \"%s\"", DerivedRecord.class.getSimpleName(), nodeClassName, name));
				}
			} else if (Catalog.class.isAssignableFrom(parameterClass)) {
				throw new IllegalArgumentException(String.format("%s %s cannot contain Catalog \"%s\" (try Listing?)", DerivedRecord.class.getSimpleName(), nodeClassName, name));
			} else {
				nonEntityWriter.generateFieldWrite(name, cb, gson, jsonWriter, type);
			}
		}
	}

	/**
	 * Implements the Gson {@link TypeAdapter} interface using a {@link Codec} object.
	 * Putting boilerplate code in this wrapper is much easier than generating it
	 * in the compiler, and allows us to keep the {@link Codec} interface focused
	 * on just the highly-customized code that we do want to generate.
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
			// Performance-critical. Pre-compute as much as possible outside this method.
			out.beginObject();
			codec.writeFields(value, out);
			out.endObject();
		}

		@Override
		public T read(JsonReader in) throws IOException {
			// Performance-critical. Pre-compute as much as possible outside this method.
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

	private static final Method CODEC_WRITE_FIELDS, CODEC_INSTANTIATE_FROM;
	private static final Method DYNAMIC_WRITE_FIELD;
	private static final Method LIST_GET;
	private static final Method OPTIONAL_IS_PRESENT, OPTIONAL_GET;
	private static final Method REFLECTIVE_ENTITY_REFERENCE;
	private static final Method JSON_WRITER_NAME, TYPE_ADAPTER_WRITE;

	static {
		try {
			CODEC_WRITE_FIELDS = Codec.class.getDeclaredMethod("writeFields", Object.class, JsonWriter.class);
			CODEC_INSTANTIATE_FROM = Codec.class.getDeclaredMethod("instantiateFrom", List.class);
			DYNAMIC_WRITE_FIELD = CodecRuntime.class.getDeclaredMethod("dynamicWriteField", Object.class, String.class, TypeToken.class, Gson.class, JsonWriter.class);
			LIST_GET = List.class.getDeclaredMethod("get", int.class);
			OPTIONAL_IS_PRESENT = Optional.class.getDeclaredMethod("isPresent");
			OPTIONAL_GET = Optional.class.getDeclaredMethod("get");
			REFLECTIVE_ENTITY_REFERENCE = ReflectiveEntity.class.getDeclaredMethod("reference");
			JSON_WRITER_NAME = JsonWriter.class.getDeclaredMethod("name", String.class);
			TYPE_ADAPTER_WRITE = TypeAdapter.class.getDeclaredMethod("write", JsonWriter.class, Object.class);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(GsonAdapterCompiler.class);

}
