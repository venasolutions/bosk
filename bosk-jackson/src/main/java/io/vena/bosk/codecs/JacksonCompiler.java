package io.vena.bosk.codecs;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.vena.bosk.Bosk;
import io.vena.bosk.Catalog;
import io.vena.bosk.Entity;
import io.vena.bosk.JacksonPlugin;
import io.vena.bosk.JacksonPlugin.FieldModerator;
import io.vena.bosk.JacksonPlugin.SerDes;
import io.vena.bosk.Phantom;
import io.vena.bosk.Reference;
import io.vena.bosk.ReflectiveEntity;
import io.vena.bosk.annotations.DerivedRecord;
import io.vena.bosk.bytecode.ClassBuilder;
import io.vena.bosk.bytecode.LocalVariable;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.exceptions.NotYetImplementedException;
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

import static io.vena.bosk.JacksonPlugin.javaParameterType;
import static io.vena.bosk.ReferenceUtils.getterMethod;
import static io.vena.bosk.ReferenceUtils.theOnlyConstructorFor;
import static io.vena.bosk.SerializationPlugin.isImplicitParameter;
import static io.vena.bosk.bytecode.ClassBuilder.here;
import static java.util.Arrays.asList;

@RequiredArgsConstructor
public final class JacksonCompiler {
	private final JacksonPlugin jacksonPlugin;

	/**
	 * A stack of types for which we are in the midst of compiling a {@link SerDes}.
	 *
	 * <p>
	 * Compiling for a particular node type recursively triggers compilations for the
	 * node's fields. This stack tracks those compilations to avoid infinite recursion
	 * for recursive datatypes.
	 */
	private final ThreadLocal<Deque<JavaType>> compilationsInProgress = ThreadLocal.withInitial(ArrayDeque::new);

	/**
	 * The main entry point to the compiler.
	 *
	 * @return a newly compiled {@link SerDes} for values of the given <code>nodeType</code>.
	 */
	public <T> SerDes<T> compiled(JavaType nodeType, Bosk<?> bosk, FieldModerator moderator) {
		try {
			// Record that we're compiling this one to avoid infinite recursion
			compilationsInProgress.get().addLast(nodeType);

			// Grab some required info about the node class
			@SuppressWarnings("unchecked")
			Class<T> nodeClass = (Class<T>) nodeType.getRawClass();
			Constructor<?> constructor = theOnlyConstructorFor(nodeClass);
			List<Parameter> parameters = asList(constructor.getParameters());

			// Generate the Codec class and instantiate it
			ClassBuilder<Codec> cb = new ClassBuilder<>("BOSK_JACKSON_" + nodeClass.getSimpleName(), JacksonCodecRuntime.class, nodeClass.getClassLoader(), here());
			cb.beginClass();

			generate_writeFields(nodeClass, parameters, cb);
			generate_instantiateFrom(constructor, parameters, cb);

			Codec codec = cb.buildInstance();

			// Return a CodecWrapper for the codec
			LinkedHashMap<String, Parameter> parametersByName = new LinkedHashMap<>();
			parameters.forEach(p -> parametersByName.put(p.getName(), p));
			return new CodecWrapper<>(codec, bosk, nodeClass, parametersByName, moderator);
		} finally {
			Type removed = compilationsInProgress.get().removeLast();
			assert removed.equals(nodeType);
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
		Object writeFields(Object node, JsonGenerator jsonGenerator, SerializerProvider serializers) throws IOException;

		/**
		 * A faster version of {@link Constructor#newInstance} without the overhead
		 * of checking for errors that we know can't happen.
		 */
		Object instantiateFrom(List<Object> parameterValues) throws IOException;
	}

	/**
	 * Generates the body of the {@link Codec#writeFields} method.
	 */
	private void generate_writeFields(Class<?> nodeClass, List<Parameter> parameters, ClassBuilder<Codec> cb) {
		cb.beginMethod(CODEC_WRITE_FIELDS);
		// Incoming arguments
		final LocalVariable node = cb.parameter(1);
		final LocalVariable jsonGenerator = cb.parameter(2);
		final LocalVariable serializers = cb.parameter(3);

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
			JavaType parameterType = TypeFactory.defaultInstance().constructType(parameter.getParameterizedType());
			// TODO: Is the static optimization possible??
//			if (compilationsInProgress.get().contains(parameterType)) {
//				// Avoid infinite recursion - look up this field's adapter dynamically
//				plan = new OrdinaryFieldWritePlan();
//			} else {
//				plan = new StaticallyBoundFieldWritePlan();
//			}
			plan = new OrdinaryFieldWritePlan();
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
			try {
				cb.invoke(getterMethod(nodeClass, name));
			} catch (InvalidTypeException e) {
				throw new AssertionError("Should be impossible for a type that has already been validated", e);
			}

			// Execute the plan
			SerializerProvider serializerProvider = null; // static optimization not yet implemented
			plan.generateFieldWrite(name, cb, jsonGenerator, serializers, serializerProvider, parameterType);
		}
		// TODO: Support void methods
		cb.pushLocal(node);
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
	 * how to write a single field to Jackson.
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
		 * Emit code that writes the given field's name and value to a {@link JsonGenerator}.
		 * The value is required to be on the operand stack at the start of the generated sequence.
		 *
		 * <p>
		 * Some implementations will be stackable modifiers that perhaps emit some code,
		 * then delegate to some downstream <code>generateFieldWrite</code> method, possibly
		 * with modified parameters.
		 */
		void generateFieldWrite(
			String name,
			ClassBuilder<Codec> cb,
			LocalVariable jsonGenerator,
			LocalVariable serializers,
			SerializerProvider serializerProvider,
			JavaType type);
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
		public void generateFieldWrite(String name, ClassBuilder<Codec> cb, LocalVariable jsonGenerator, LocalVariable serializers, SerializerProvider serializerProvider, JavaType type) {
			cb.pushString(name);
			cb.pushObject(type);
			cb.pushLocal(jsonGenerator);
			cb.pushLocal(serializers);
			cb.invoke(DYNAMIC_WRITE_FIELD);
		}
	}

	/**
	 * An optimized way to write a field that looks up the {@link JsonSerializer} at
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
		public void generateFieldWrite(String name, ClassBuilder<Codec> cb, LocalVariable jsonGenerator, LocalVariable serializers, SerializerProvider serializerProvider, JavaType type) {
			// Find or create the TypeAdapter for the given type.
			// If the TypeAdapter doesn't already exist, we will attempt to compile one,
			// so this is where our compiler's recursion happens.

			JsonSerializer<Object> serializer;
			try {
				serializer = serializerProvider.findValueSerializer(type);
			} catch (JsonMappingException e) {
				throw new NotYetImplementedException(e);
			}

			// Save incoming operand to local variable
			LocalVariable fieldValue = cb.popToLocal();

			// Write the field name
			cb.pushLocal(jsonGenerator);
			cb.pushString(name);
			cb.invoke(JSON_GENERATOR_WRITE_FIELD_NAME);
			cb.pop();

			// Write the field value using the statically bound serializer
			cb.pushObject(serializer);
			cb.pushLocal(fieldValue);
			cb.pushLocal(jsonGenerator);
			cb.pushLocal(serializers);
			cb.invoke(JSON_SERIALIZER_SERIALIZE);
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
		public void generateFieldWrite(String name, ClassBuilder<Codec> cb, LocalVariable jsonGenerator, LocalVariable serializers, SerializerProvider serializerProvider, JavaType type) {
			cb.castTo(Optional.class);
			LocalVariable optional = cb.popToLocal();
			cb.pushLocal(optional);
			cb.invoke(OPTIONAL_IS_PRESENT);
			cb.ifTrue(()-> {
				// Unwrap
				cb.pushLocal(optional);
				cb.invoke(OPTIONAL_GET);

				// Write the value
				valueWriter.generateFieldWrite(name, cb, jsonGenerator, serializers, serializerProvider,
					javaParameterType(type, Optional.class, 0));
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
		public void generateFieldWrite(String name, ClassBuilder<Codec> cb, LocalVariable jsonGenerator, LocalVariable serializers, SerializerProvider serializerProvider, JavaType type) {
			Class<?> parameterClass = type.getRawClass();
			boolean isEntity = Entity.class.isAssignableFrom(parameterClass);
			if (isEntity) {
				if (ReflectiveEntity.class.isAssignableFrom(parameterClass)) {
					cb.castTo(ReflectiveEntity.class);
					cb.invoke(REFLECTIVE_ENTITY_REFERENCE);
					// Recurse to write the Reference
					JavaType referenceType = TypeFactory.defaultInstance().constructParametricType(Reference.class, type);
					generateFieldWrite(name, cb, jsonGenerator, serializers, serializerProvider, referenceType);
				} else {
					throw new IllegalArgumentException(String.format("%s %s cannot contain Entity that is not a ReflectiveEntity: \"%s\"", DerivedRecord.class.getSimpleName(), nodeClassName, name));
				}
			} else if (Catalog.class.isAssignableFrom(parameterClass)) {
				throw new IllegalArgumentException(String.format("%s %s cannot contain Catalog \"%s\" (try Listing?)", DerivedRecord.class.getSimpleName(), nodeClassName, name));
			} else {
				nonEntityWriter.generateFieldWrite(name, cb, jsonGenerator, serializers, serializerProvider, type);
			}
		}
	}

	/**
	 * Implements the {@link SerDes} interface using a {@link Codec} object.
	 * Putting boilerplate code in this wrapper is much easier than generating it
	 * in the compiler, and allows us to keep the {@link Codec} interface focused
	 * on just the highly-customized code that we do want to generate.
	 */
	@Value
	@EqualsAndHashCode(callSuper = false)
	private class CodecWrapper<T> implements SerDes<T> {
		Codec codec;
		Bosk<?> bosk;
		Class<T> nodeClass;
		LinkedHashMap<String, Parameter> parametersByName;
		FieldModerator moderator;

		@Override
		public JsonSerializer<T> serializer(SerializationConfig config) {
			return new JsonSerializer<T>() {
				@Override
				public void serialize(T value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeStartObject();
					codec.writeFields(value, gen, serializers);
					gen.writeEndObject();
				}
			};
		}

		@Override
		public JsonDeserializer<T> deserializer(DeserializationConfig config) {
			return new JsonDeserializer<T>() {
				@Override
				public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					// Performance-critical. Pre-compute as much as possible outside this method.
					// Note: the reading side can't be as efficient as the writing side
					// because we need to tolerate the fields arriving in arbitrary order.
					Map<String, Object> valueMap = jacksonPlugin.gatherParameterValuesByName(nodeClass, parametersByName, moderator, p, ctxt);

					List<Object> parameterValues = jacksonPlugin.parameterValueList(nodeClass, valueMap, parametersByName, bosk);

					@SuppressWarnings("unchecked")
					T result = (T)codec.instantiateFrom(parameterValues);
					return result;
				}
			};
		}
	}

	private static final Method CODEC_WRITE_FIELDS, CODEC_INSTANTIATE_FROM;
	private static final Method DYNAMIC_WRITE_FIELD;
	private static final Method LIST_GET;
	private static final Method OPTIONAL_IS_PRESENT, OPTIONAL_GET;
	private static final Method REFLECTIVE_ENTITY_REFERENCE;
	private static final Method JSON_GENERATOR_WRITE_FIELD_NAME, JSON_SERIALIZER_SERIALIZE;

	static {
		try {
			CODEC_WRITE_FIELDS = Codec.class.getDeclaredMethod("writeFields", Object.class, JsonGenerator.class, SerializerProvider.class);
			CODEC_INSTANTIATE_FROM = Codec.class.getDeclaredMethod("instantiateFrom", List.class);
			DYNAMIC_WRITE_FIELD = JacksonCodecRuntime.class.getDeclaredMethod("dynamicWriteField", Object.class, String.class, JavaType.class, JsonGenerator.class, SerializerProvider.class);
			LIST_GET = List.class.getDeclaredMethod("get", int.class);
			OPTIONAL_IS_PRESENT = Optional.class.getDeclaredMethod("isPresent");
			OPTIONAL_GET = Optional.class.getDeclaredMethod("get");
			REFLECTIVE_ENTITY_REFERENCE = ReflectiveEntity.class.getDeclaredMethod("reference");
			JSON_GENERATOR_WRITE_FIELD_NAME = JsonGenerator.class.getDeclaredMethod("writeFieldName", String.class);
			JSON_SERIALIZER_SERIALIZE = JsonSerializer.class.getDeclaredMethod("serialize", Object.class, JsonGenerator.class, SerializerProvider.class);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(JacksonCompiler.class);

}
