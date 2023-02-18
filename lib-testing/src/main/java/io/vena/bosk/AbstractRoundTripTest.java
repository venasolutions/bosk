package io.vena.bosk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.vena.bosk.SerializationPlugin.DeserializationScope;
import io.vena.bosk.drivers.mongo.BsonPlugin;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.gson.GsonPlugin;
import io.vena.bosk.jackson.JacksonPlugin;
import java.io.IOException;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonValue;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;
import static io.vena.bosk.ReferenceUtils.parameterType;
import static io.vena.bosk.ReferenceUtils.rawClass;
import static io.vena.bosk.ReferenceUtils.theOnlyConstructorFor;
import static java.lang.System.identityHashCode;
import static java.util.Collections.newSetFromMap;

public abstract class AbstractRoundTripTest extends AbstractBoskTest {

	static <R extends Entity> Stream<DriverFactory<R>> driverFactories() {
		return Stream.of(
				directFactory(),
				factoryThatMakesAReference(),

				jacksonRoundTripFactory(),

				// Variety of Gson configurations
				gsonRoundTripFactory(b->b),
				gsonRoundTripFactory(GsonBuilder::setPrettyPrinting),
				gsonRoundTripFactory(GsonBuilder::excludeFieldsWithoutExposeAnnotation),

				bsonRoundTripFactory()
		);
	}

	public static <R extends Entity> DriverFactory<R> directFactory() {
		return Bosk::simpleDriver;
	}

	public static <R extends Entity> DriverFactory<R> factoryThatMakesAReference() {
		return (bosk, downstream) -> {
			bosk.rootReference();
			return Bosk.simpleDriver(bosk, downstream);
		};
	}

	public static <R extends Entity> DriverFactory<R> jacksonRoundTripFactory() {
		return new JacksonRoundTripDriverFactory<>();
	}

	@RequiredArgsConstructor
	private static class JacksonRoundTripDriverFactory<R extends Entity> implements DriverFactory<R> {
		private final JacksonPlugin jp = new JacksonPlugin();

		@Override
		public BoskDriver<R> build(Bosk<R> bosk, BoskDriver<R> driver) {
			return new PreprocessingDriver<R>(driver) {
				final Module module = jp.moduleFor(bosk);
				final ObjectMapper objectMapper = new ObjectMapper()
					.registerModule(module)
					.enable(INDENT_OUTPUT);

				@Override
				<T> T preprocess(Reference<T> reference, T newValue) {
					try {
						JavaType targetType = javaType(reference.targetType());
						String json = objectMapper.writerFor(targetType).writeValueAsString(newValue);
						try (DeserializationScope scope = jp.newDeserializationScope(reference)) {
							return objectMapper.readerFor(targetType).readValue(json);
						}
					} catch (JsonProcessingException e) {
						throw new AssertionError(e);
					}
				}

				private JavaType javaType(Type type) {
					return TypeFactory.defaultInstance().constructType(type);
				}
			};
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + identityHashCode(this);
		}
	}

	public static <R extends Entity> DriverFactory<R> gsonRoundTripFactory(UnaryOperator<GsonBuilder> customizer) {
		return new GsonRoundTripDriverFactory<>(customizer);
	}

	@RequiredArgsConstructor
	private static class GsonRoundTripDriverFactory<R extends Entity> implements DriverFactory<R> {
		private final GsonPlugin gp = new GsonPlugin();
		private final UnaryOperator<GsonBuilder> customizer;

		@Override
		public BoskDriver<R> build(Bosk<R> bosk, BoskDriver<R> driver) {
			return new PreprocessingDriver<R>(driver) {
				final GsonBuilder builder = new GsonBuilder().registerTypeAdapterFactory(gp.adaptersFor(bosk));
				final Gson gson = customizer.apply(builder).create();

				@Override
				<T> T preprocess(Reference<T> reference, T newValue) {
					Type targetType = reference.targetType();
					String json = gson.toJson(newValue, targetType);
					try (DeserializationScope scope = gp.newDeserializationScope(reference)) {
						return gson.fromJson(json, targetType);
					}
				}

			};
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + identityHashCode(this);
		}
	}

	public static <R extends Entity> DriverFactory<R> bsonRoundTripFactory() {
		return new BsonRoundTripDriverFactory<>();
	}

	@RequiredArgsConstructor
	private static class BsonRoundTripDriverFactory<R extends Entity> implements DriverFactory<R> {
		@Override
		public BoskDriver<R> build(Bosk<R> bosk, BoskDriver<R> driver) {
			final BsonPlugin bp = new BsonPlugin();
			return new PreprocessingDriver<R>(driver) {
				final CodecRegistry codecRegistry = CodecRegistries.fromProviders(bp.codecProviderFor(bosk));

				/**
				 * The shortcomings of the Bson library's type system make this
				 * quite awkward. It's strongly oriented toward writing whole
				 * documents, for some reason, but Bosk supports updates of
				 * individual fields. Therefore, we must wrap the values in
				 * documents containing a single field called "value" of the
				 * type we actually want to process.
				 *
				 * @author pdoyle
				 */
				@Override
				<T> T preprocess(Reference<T> reference, T newValue) {
					Codec<T> codec = bp.getCodec(reference.targetType(), reference.targetClass(), codecRegistry, bosk);
					BsonDocument document = new BsonDocument();
					try (BsonDocumentWriter writer = new BsonDocumentWriter(document)) {
						writer.writeStartDocument();
						writer.writeName("value");
						codec.encode(writer, newValue, EncoderContext.builder().build());
						writer.writeEndDocument();
					}
					pruneDocument(document.get("value"), reference.targetType(), newSetFromMap(new IdentityHashMap<>()));
					try (BsonDocumentReader reader = new BsonDocumentReader(document)) {
						reader.readStartDocument();
						reader.readName("value");
						T result;
						try (DeserializationScope scope = bp.newDeserializationScope(reference)) {
							result = codec.decode(reader, DecoderContext.builder().build());
						}
						reader.readEndDocument();
						return result;
					}
				}

				private void pruneDocument(BsonValue value, Type nodeType, Set<BsonDocument> alreadyPruned) {
					BsonDocument document;
					if (value instanceof BsonDocument) {
						document = (BsonDocument)value;
					} else {
						return;
					}
					if (alreadyPruned.add(document)) {
						Class<?> nodeClass = rawClass(nodeType);
						if (!StateTreeNode.class.isAssignableFrom(nodeClass)) {
							return;
						}
						Map<String, Parameter> parametersByName = new LinkedHashMap<>();
						for (Parameter p: theOnlyConstructorFor(nodeClass).getParameters()) {
							parametersByName.put(p.getName(), p);
						}
						Iterator<Entry<String, BsonValue>> fieldIter = document.entrySet().iterator();
						while (fieldIter.hasNext()) {
							Entry<String, BsonValue> field = fieldIter.next();
							String fieldName = field.getKey();
							String qualifiedName = nodeClass.getSimpleName() + "." + fieldName;
							Parameter p = parametersByName.get(fieldName);
							if (p == null) {
								LOGGER.warn("No parameter corresponding to field " + qualifiedName);
								continue;
							}
							Type pType = p.getParameterizedType();
							if (Optional.class.isAssignableFrom(p.getType())) {
								if (field.getValue() == null) {
									LOGGER.warn("Pruning Optional.empty() field " + qualifiedName + " included in BSON");
									fieldIter.remove();
								} else {
									pType = parameterType(pType, Optional.class, 0);
									pruneDocument(field.getValue(), pType, alreadyPruned);
								}
							} else if (SerializationPlugin.isEnclosingReference(nodeClass, p)) {
								LOGGER.warn("Pruning enclosing reference " + qualifiedName + " included in BSON");
								fieldIter.remove();
							} else {
								pruneDocument(field.getValue(), pType, alreadyPruned);
							}
						}
					} else {
						LOGGER.error("BsonDocument object appears twice in the same document");
					}
				}
			};
		}

		@Override
		public String toString() {
			return getClass().getSimpleName() + identityHashCode(this);
		}
	}

	private static abstract class PreprocessingDriver<R extends Entity> implements BoskDriver<R> {
		private final BoskDriver<R> downstream;

		private PreprocessingDriver(BoskDriver<R> downstream) {
			this.downstream = downstream;
		}

		@Override
		public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
			downstream.submitConditionalReplacement(target, preprocess(target, newValue), precondition, requiredValue);
		}

		@Override
		public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
			downstream.submitConditionalDeletion(target, precondition, requiredValue);
		}

		@Override
		public <T> void submitReplacement(Reference<T> target, T newValue) {
			downstream.submitReplacement(target, preprocess(target, newValue));
		}

		@Override
		public <T> void submitInitialization(Reference<T> target, T newValue) {
			downstream.submitInitialization(target, preprocess(target, newValue));
		}

		abstract <T> T preprocess(Reference<T> reference, T newValue);

		@Override
		public R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
			return downstream.initialRoot(rootType);
		}

		@Override
		public <T> void submitDeletion(Reference<T> target) {
			downstream.submitDeletion(target);
		}

		@Override
		public void flush() throws InterruptedException, IOException {
			downstream.flush();
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRoundTripTest.class);

}
