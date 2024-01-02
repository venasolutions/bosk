package io.vena.bosk.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.Serializers;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.vena.bosk.Bosk;
import io.vena.bosk.Catalog;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.ListValue;
import io.vena.bosk.Listing;
import io.vena.bosk.ListingEntry;
import io.vena.bosk.MapValue;
import io.vena.bosk.Path;
import io.vena.bosk.Phantom;
import io.vena.bosk.Reference;
import io.vena.bosk.ReflectiveEntity;
import io.vena.bosk.SerializationPlugin;
import io.vena.bosk.SideTable;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.annotations.DerivedRecord;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.exceptions.TunneledCheckedException;
import io.vena.bosk.exceptions.UnexpectedPathException;
import io.vena.bosk.jackson.JacksonCompiler.CompiledSerDes;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import lombok.Value;

import static com.fasterxml.jackson.core.JsonToken.END_ARRAY;
import static com.fasterxml.jackson.core.JsonToken.END_OBJECT;
import static com.fasterxml.jackson.core.JsonToken.START_ARRAY;
import static com.fasterxml.jackson.core.JsonToken.START_OBJECT;
import static io.vena.bosk.ListingEntry.LISTING_ENTRY;
import static io.vena.bosk.ReferenceUtils.rawClass;
import static io.vena.bosk.ReferenceUtils.theOnlyConstructorFor;
import static java.util.Objects.requireNonNull;

/**
 * Provides JSON serialization/deserialization using Jackson.
 * @see SerializationPlugin
 */
public final class JacksonPlugin extends SerializationPlugin {
	private final JacksonCompiler compiler = new JacksonCompiler(this);

	public BoskJacksonModule moduleFor(Bosk<?> bosk) {
		return new BoskJacksonModule() {
			@Override
			public void setupModule(SetupContext context) {
				context.addSerializers(new BoskSerializers(bosk));
				context.addDeserializers(new BoskDeserializers(bosk));
			}
		};
	}

	private final class BoskSerializers extends Serializers.Base {
		private final Bosk<?> bosk;

		public BoskSerializers(Bosk<?> bosk) {
			this.bosk = bosk;
		}

		@Override
		@SuppressWarnings({ "unchecked", "rawtypes" })
		public JsonSerializer<?> findSerializer(SerializationConfig config, JavaType type, BeanDescription beanDesc) {
			Class theClass = type.getRawClass();
			if (theClass.isAnnotationPresent(DerivedRecord.class)) {
				return derivedRecordSerializer(config, type, beanDesc);
			} else if (Catalog.class.isAssignableFrom(theClass)) {
				return catalogSerializer(config, beanDesc);
			} else if (Listing.class.isAssignableFrom(theClass)) {
				return listingSerializer(config, beanDesc);
			} else if (Reference.class.isAssignableFrom(theClass)) {
				return referenceSerializer(config, beanDesc);
			} else if (Identifier.class.isAssignableFrom(theClass)) {
				return identifierSerializer(config, beanDesc);
			} else if (ListingEntry.class.isAssignableFrom(theClass)) {
				return listingEntrySerializer(config, beanDesc);
			} else if (SideTable.class.isAssignableFrom(theClass)) {
				return sideTableSerializer(config, beanDesc);
			} else if (StateTreeNode.class.isAssignableFrom(theClass)) {
				return stateTreeNodeSerializer(config, type, beanDesc);
			} else if (Optional.class.isAssignableFrom(theClass)) {
				// Optional.empty() can't be serialized on its own because the field name itself must also be omitted
				throw new IllegalArgumentException("Cannot serialize an Optional on its own; only as a field of another object");
			} else if (Phantom.class.isAssignableFrom(theClass)) {
				throw new IllegalArgumentException("Cannot serialize a Phantom on its own; only as a field of another object");
			} else if (MapValue.class.isAssignableFrom(theClass)) {
				return mapValueSerializer(config, beanDesc);
			} else {
				return null;
			}
		}

		private JsonSerializer<Object> derivedRecordSerializer(SerializationConfig config, JavaType type, BeanDescription beanDesc) {
			return derivedRecordSerDes(type, beanDesc, bosk).serializer(config);
		}

		private JsonSerializer<Catalog<Entity>> catalogSerializer(SerializationConfig config, BeanDescription beanDesc) {
			return new JsonSerializer<Catalog<Entity>>() {
				@Override
				public void serialize(Catalog<Entity> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					writeMapEntries(gen, value.asMap().entrySet(), serializers);
				}
			};
		}

		private JsonSerializer<Listing<Entity>> listingSerializer(SerializationConfig config, BeanDescription beanDesc) {
			return new JsonSerializer<Listing<Entity>>() {
				@Override
				public void serialize(Listing<Entity> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeStartObject();

					gen.writeFieldName("ids");
					serializers
						.findContentValueSerializer(ID_LIST_TYPE, null)
						.serialize(new ArrayList<>(value.ids()), gen, serializers);

					gen.writeFieldName("domain");
					serializers
						.findContentValueSerializer(Reference.class, null)
						.serialize(value.domain(), gen, serializers);

					gen.writeEndObject();
				}
			};
		}

		private JsonSerializer<Reference<?>> referenceSerializer(SerializationConfig config, BeanDescription beanDesc) {
			return new JsonSerializer<Reference<?>>() {
				@Override
				public void serialize(Reference<?> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeString(value.path().urlEncoded());
				}
			};
		}

		private JsonSerializer<Identifier> identifierSerializer(SerializationConfig config, BeanDescription beanDesc) {
			return new JsonSerializer<Identifier>() {
				@Override
				public void serialize(Identifier value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeString(value.toString());
				}
			};
		}

		private JsonSerializer<ListingEntry> listingEntrySerializer(SerializationConfig config, BeanDescription beanDesc) {
			// We serialize ListingEntry as a boolean `true` with the following rationale:
			// - The only "unit type" in JSON is null
			// - `null` is not suitable because many systems treat that as being equivalent to an absent field
			// - Of the other types, boolean seems the most likely to be efficiently processed in every system
			// - `false` gives the wrong impression
			// Hence, by a process of elimination, `true` it is

			return new JsonSerializer<ListingEntry>() {
				@Override
				public void serialize(ListingEntry value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeBoolean(true);
				}
			};
		}

		private JsonSerializer<SideTable<Entity, Object>> sideTableSerializer(SerializationConfig config, BeanDescription beanDesc) {
			return new JsonSerializer<SideTable<Entity, Object>>() {
				@Override
				public void serialize(SideTable<Entity, Object> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeStartObject();

					gen.writeFieldName("valuesById");
					writeMapEntries(gen, value.idEntrySet(), serializers);

					gen.writeFieldName("domain");
					serializers
						.findContentValueSerializer(Reference.class, null)
						.serialize(value.domain(), gen, serializers);

					gen.writeEndObject();
				}
			};
		}

		private JsonSerializer<StateTreeNode> stateTreeNodeSerializer(SerializationConfig config, JavaType type, BeanDescription beanDesc) {
			StateTreeNodeFieldModerator moderator = new StateTreeNodeFieldModerator(type);
			return compiler.<StateTreeNode>compiled(type, bosk, moderator).serializer(config);
		}

		private JsonSerializer<MapValue<Object>> mapValueSerializer(SerializationConfig config, BeanDescription beanDesc) {
			return new JsonSerializer<MapValue<Object>>() {
				@Override
				public void serialize(MapValue<Object> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
					gen.writeStartObject();
					for (Entry<String, Object> element : value.entrySet()) {
						gen.writeFieldName(requireNonNull(element.getKey()));
						Object val = requireNonNull(element.getValue());
						JsonSerializer<Object> valueSerializer = serializers.findValueSerializer(val.getClass());
						valueSerializer.serialize(val, gen, serializers);
					}
					gen.writeEndObject();
				}
			};
		}

		// Thanks but no thanks, Jackson. We don't need your help.

		@Override
		public JsonSerializer<?> findCollectionSerializer(SerializationConfig config, CollectionType type, BeanDescription beanDesc, TypeSerializer elementTypeSerializer, JsonSerializer<Object> elementValueSerializer) {
			return findSerializer(config, type, beanDesc);
		}

		@Override
		public JsonSerializer<?> findMapSerializer(SerializationConfig config, MapType type, BeanDescription beanDesc, JsonSerializer<Object> keySerializer, TypeSerializer elementTypeSerializer, JsonSerializer<Object> elementValueSerializer) {
			return findSerializer(config, type, beanDesc);
		}
	}

	private final class BoskDeserializers extends Deserializers.Base {
		private final Bosk<?> bosk;

		public BoskDeserializers(Bosk<?> bosk) {
			this.bosk = bosk;
		}

		@Override
		@SuppressWarnings({ "unchecked", "rawtypes" })
		public JsonDeserializer<?> findBeanDeserializer(JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
			Class theClass = type.getRawClass();
			if (theClass.isAnnotationPresent(DerivedRecord.class)) {
				return derivedRecordDeserializer(type, config, beanDesc);
			} else if (Catalog.class.isAssignableFrom(theClass)) {
				return catalogDeserializer(type, config, beanDesc);
			} else if (Listing.class.isAssignableFrom(theClass)) {
				return listingDeserializer(type, config, beanDesc);
			} else if (Reference.class.isAssignableFrom(theClass)) {
				return referenceDeserializer(type, config, beanDesc);
			} else if (Identifier.class.isAssignableFrom(theClass)) {
				return identifierDeserializer(type, config, beanDesc);
			} else if (ListingEntry.class.isAssignableFrom(theClass)) {
				return listingEntryDeserializer(type, config, beanDesc);
			} else if (SideTable.class.isAssignableFrom(theClass)) {
				return sideTableDeserializer(type, config, beanDesc);
			} else if (StateTreeNode.class.isAssignableFrom(theClass)) {
				return stateTreeNodeDeserializer(type, config, beanDesc);
			} else if (Optional.class.isAssignableFrom(theClass)) {
				// Optional.empty() can't be serialized on its own because the field name itself must also be omitted
				throw new IllegalArgumentException("Cannot serialize an Optional on its own; only as a field of another object");
			} else if (Phantom.class.isAssignableFrom(theClass)) {
				throw new IllegalArgumentException("Cannot serialize a Phantom on its own; only as a field of another object");
			} else if (ListValue.class.isAssignableFrom(theClass)) {
				return listValueDeserializer(type, config, beanDesc);
			} else if (MapValue.class.isAssignableFrom(theClass)) {
				return mapValueDeserializer(type, config, beanDesc);
			} else {
				return null;
			}
		}

		private JsonDeserializer<Object> derivedRecordDeserializer(JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
			return derivedRecordSerDes(type, beanDesc, bosk).deserializer(config);
		}

		private JsonDeserializer<Catalog<Entity>> catalogDeserializer(JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
			JavaType entryType = catalogEntryType(type);

			return new BoskDeserializer<Catalog<Entity>>() {
				@Override
				@SuppressWarnings({"rawtypes", "unchecked"})
				public Catalog<Entity> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					JsonDeserializer valueDeserializer = ctxt.findContextualValueDeserializer(entryType, null);
					LinkedHashMap<Identifier, Entity> entries = readMapEntries(p, valueDeserializer, ctxt);
					return Catalog.of(entries.values());
				}
			};
		}

		private JsonDeserializer<Listing<Entity>> listingDeserializer(JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
			return new BoskDeserializer<Listing<Entity>>() {
				@Override
				@SuppressWarnings("unchecked")
				public Listing<Entity> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					Reference<Catalog<Entity>> domain = null;
					List<Identifier> ids = null;

					expect(START_OBJECT, p);
					while (p.nextToken() != END_OBJECT) {
						p.nextValue();
						switch (p.currentName()) {
							case "ids":
								if (ids != null) {
									throw new JsonParseException(p, "'ids' field appears twice");
								}
								ids = (List<Identifier>) ctxt
									.findContextualValueDeserializer(ID_LIST_TYPE, null)
									.deserialize(p, ctxt);
								break;
							case "domain":
								if (domain != null) {
									throw new JsonParseException(p, "'domain' field appears twice");
								}
								domain = (Reference<Catalog<Entity>>) ctxt
									.findContextualValueDeserializer(CATALOG_REF_TYPE, null)
									.deserialize(p, ctxt);
								break;
							default:
								throw new JsonParseException(p, "Unrecognized field in Listing: " + p.currentName());
						}
					}

					if (domain == null) {
						throw new JsonParseException(p, "Missing 'domain' field");
					} else if (ids == null) {
						throw new JsonParseException(p, "Missing 'ids' field");
					} else {
						return Listing.of(domain, ids);
					}
				}
			};
		}

		private JsonDeserializer<Reference<?>> referenceDeserializer(JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
			return new BoskDeserializer<Reference<?>>() {
				@Override
				public Reference<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					try {
						return bosk.rootReference().then(Object.class, Path.parse(p.getText()));
					} catch (InvalidTypeException e) {
						throw new UnexpectedPathException(e);
					}
				}
			};
		}

		private JsonDeserializer<Identifier> identifierDeserializer(JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
			return new BoskDeserializer<Identifier>() {
				@Override
				public Identifier deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					return Identifier.from(p.getText());
				}
			};
		}

		private JsonDeserializer<ListingEntry> listingEntryDeserializer(JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
			return new BoskDeserializer<ListingEntry>() {
				@Override
				public ListingEntry deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					if (p.getBooleanValue()) {
						return LISTING_ENTRY;
					} else {
						throw new JsonParseException(p, "Unexpected Listing entry value: " + p.getBooleanValue());
					}
				}
			};
		}

		private JsonDeserializer<SideTable<Entity, Object>> sideTableDeserializer(JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
			JavaType valueType = sideTableValueType(type);
			return new BoskDeserializer<SideTable<Entity, Object>>() {
				@Override
				@SuppressWarnings("unchecked")
				public SideTable<Entity, Object> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					Reference<Catalog<Entity>> domain = null;
					LinkedHashMap<Identifier, Object> valuesById = null;

					JsonDeserializer<Object> valueDeserializer = ctxt.findContextualValueDeserializer(valueType, null);

					expect(START_OBJECT, p);
					while (p.nextToken() != END_OBJECT) {
						p.nextValue();
						switch (p.currentName()) {
							case "valuesById":
								if (valuesById == null) {
									valuesById = readMapEntries(p, valueDeserializer, ctxt);
								} else {
									throw new JsonParseException(p, "'valuesById' field appears twice");
								}
								break;
							case "domain":
								if (domain == null) {
									domain = (Reference<Catalog<Entity>>) ctxt
										.findContextualValueDeserializer(CATALOG_REF_TYPE, null)
										.deserialize(p, ctxt);
								} else {
									throw new JsonParseException(p, "'domain' field appears twice");
								}
								break;
							default:
								throw new JsonParseException(p, "Unrecognized field in SideTable: " + p.currentName());
						}
					}
					expect(END_OBJECT, p);

					if (domain == null) {
						throw new JsonParseException(p, "Missing 'domain' field");
					} else if (valuesById == null) {
						throw new JsonParseException(p, "Missing 'valuesById' field");
					} else {
						return SideTable.fromOrderedMap(domain, valuesById);
					}
				}
			};
		}

		private JsonDeserializer<StateTreeNode> stateTreeNodeDeserializer(JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
			StateTreeNodeFieldModerator moderator = new StateTreeNodeFieldModerator(type);
			return compiler.<StateTreeNode>compiled(type, bosk, moderator).deserializer(config);
		}

		private JsonDeserializer<ListValue<Object>> listValueDeserializer(JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
			Constructor<?> ctor = theOnlyConstructorFor(type.getRawClass());
			JavaType arrayType = listValueEquivalentArrayType(type);
			return new BoskDeserializer<ListValue<Object>>() {
				@Override
				@SuppressWarnings({"unchecked"})
				public ListValue<Object> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					Object elementArray = ctxt
						.findContextualValueDeserializer(arrayType, null)
						.deserialize(p, ctxt);
					try {
						return (ListValue<Object>) ctor.newInstance(elementArray);
					} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
						throw new IOException("Failed to instantiate " + type.getRawClass().getSimpleName() + ": " + e.getMessage(), e);
					}
				}
			};
		}

		private JsonDeserializer<MapValue<Object>> mapValueDeserializer(JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
			JavaType valueType = mapValueValueType(type);
			return new BoskDeserializer<MapValue<Object>>() {
				@Override
				public MapValue<Object> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
					LinkedHashMap<String, Object> result1 = new LinkedHashMap<>();
					expect(START_OBJECT, p);
					while (p.nextToken() != END_OBJECT) {
						p.nextValue();
						String key = p.currentName();
						Object value = ctxt.findContextualValueDeserializer(valueType, null)
							.deserialize(p, ctxt);
						Object old = result1.put(key, value);
						if (old != null) {
							throw new JsonParseException(p, "MapValue key appears twice: \"" + key + "\"");
						}
					}
					expect(END_OBJECT, p);
					return MapValue.fromOrderedMap(result1);
				}
			};
		}

		// Thanks but no thanks, Jackson. We don't need your help.

		@Override
		public JsonDeserializer<?> findCollectionDeserializer(CollectionType type, DeserializationConfig config, BeanDescription beanDesc, TypeDeserializer elementTypeDeserializer, JsonDeserializer<?> elementDeserializer) {
			return findBeanDeserializer(type, config, beanDesc);
		}

		@Override
		public JsonDeserializer<?> findMapDeserializer(MapType type, DeserializationConfig config, BeanDescription beanDesc, KeyDeserializer keyDeserializer, TypeDeserializer elementTypeDeserializer, JsonDeserializer<?> elementDeserializer) {
			return findBeanDeserializer(type, config, beanDesc);
		}
	}

	/**
	 * Common properties all our deserializers have.
	 */
	private abstract static class BoskDeserializer<T> extends JsonDeserializer<T> {
		@Override public boolean isCachable() { return true; }
	}

	private <V> void writeMapEntries(JsonGenerator gen, Set<Entry<Identifier,V>> entries, SerializerProvider serializers) throws IOException {
		gen.writeStartArray();
		for (Entry<Identifier, V> entry: entries) {
			gen.writeStartObject();
			gen.writeFieldName(entry.getKey().toString());
			JsonSerializer<Object> valueSerializer = serializers.findContentValueSerializer(entry.getValue().getClass(), null);
			valueSerializer.serialize(entry.getValue(), gen, serializers);
			gen.writeEndObject();
		}
		gen.writeEndArray();
	}

	/**
	 * Leaves the parser sitting on the END_ARRAY token. You could call nextToken() to continue with parsing.
	 */
	private <V> LinkedHashMap<Identifier, V> readMapEntries(JsonParser p, JsonDeserializer<V> valueDeserializer, DeserializationContext ctxt) throws IOException {
		LinkedHashMap<Identifier, V> result = new LinkedHashMap<>();
		expect(START_ARRAY, p);
		while (p.nextToken() != END_ARRAY) {
			expect(START_OBJECT, p);
			p.nextValue();
			String fieldName = p.currentName();
			Identifier entryID = Identifier.from(fieldName);
			V value;
			try (@SuppressWarnings("unused") DeserializationScope scope = entryDeserializationScope(entryID)) {
				value = valueDeserializer.deserialize(p, ctxt);
			}
			p.nextToken();
			expect(END_OBJECT, p);

			V oldValue = result.put(entryID, value);
			if (oldValue != null) {
				throw new JsonParseException(p, "Duplicate sideTable entry '" + fieldName + "'");
			}
		}
		return result;
	}

	private static final JavaType ID_LIST_TYPE = TypeFactory.defaultInstance().constructType(new TypeReference<
		List<Identifier>>() {});

	private static final JavaType CATALOG_REF_TYPE = TypeFactory.defaultInstance().constructType(new TypeReference<
		Reference<Catalog<?>>>() {});

	private <T> CompiledSerDes<T> derivedRecordSerDes(JavaType objType, BeanDescription beanDesc, Bosk<?> bosk) {
		// Check for special cases
		Class<?> objClass = objType.getRawClass();
		if (ListValue.class.isAssignableFrom(objClass)) { // TODO: MapValue?
			Class<?> entryClass = javaParameterType(objType, ListValue.class, 0).getRawClass();
			if (ReflectiveEntity.class.isAssignableFrom(entryClass)) {
				@SuppressWarnings("unchecked")
				CompiledSerDes<T> result = derivedRecordListValueOfReflectiveEntitySerDes(objType, objClass, entryClass);
				return result;
			} else if (Entity.class.isAssignableFrom(entryClass)) {
				throw new IllegalArgumentException("Can't hold non-reflective Entity type in @" + DerivedRecord.class.getSimpleName() + " " + objType);
			}
		}

		// Default DerivedRecord handling
		DerivedRecordFieldModerator moderator = new DerivedRecordFieldModerator(objType);
		return compiler.compiled(objType, bosk, moderator);
	}


	@SuppressWarnings({ "rawtypes", "unchecked" })
	private <E extends ReflectiveEntity<E>, L extends ListValue<E>> CompiledSerDes derivedRecordListValueOfReflectiveEntitySerDes(JavaType objType, Class objClass, Class entryClass) {
		Constructor<L> constructor = (Constructor<L>) theOnlyConstructorFor(objClass);
		Class<?>[] parameters = constructor.getParameterTypes();
		if (parameters.length == 1 && parameters[0].getComponentType().equals(entryClass)) {
			JavaType referenceType = TypeFactory.defaultInstance().constructParametricType(Reference.class, entryClass);
			return new CompiledSerDes<L>() {
				@Override
				public JsonSerializer<L> serializer(SerializationConfig config) {
					return new JsonSerializer<L>() {
						@Override
						public void serialize(L value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
							JsonSerializer<Object> refSerializer = serializers
								.findValueSerializer(referenceType);
							gen.writeStartArray();
							try {
								value.forEach(entry -> {
									try {
										refSerializer.serialize(entry.reference(), gen, serializers);
									} catch (IOException e) {
										throw new TunneledCheckedException(e);
									}
								});
							} catch (TunneledCheckedException e) {
								throw e.getCause(IOException.class);
							}
							gen.writeEndArray();
						}
					};
				}

				@Override
				public JsonDeserializer<L> deserializer(DeserializationConfig config) {
					return new BoskDeserializer<L>() {
						@Override
						public L deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
							JsonDeserializer<Reference<E>> refDeserializer = (JsonDeserializer<Reference<E>>)(JsonDeserializer) ctxt
								.findContextualValueDeserializer(referenceType, null);

							List<E> entries = new ArrayList<>();
							expect(START_ARRAY, p);
							while (p.nextToken() != END_ARRAY) {
								entries.add(refDeserializer.deserialize(p, ctxt).value());
							}
							expect(END_ARRAY, p);

							E[] array = (E[])Array.newInstance(entryClass, entries.size());
							try {
								return constructor.newInstance(new Object[] { entries.toArray(array) } );
							} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
								throw new IOException("Error creating " + objClass.getSimpleName() + ": " + e.getMessage(), e);
							}
						}
					};
				}
			};
		} else {
			throw new IllegalArgumentException("Cannot serialize " + ListValue.class.getSimpleName() + " subtype " + objType
					+ ": constructor must have a single array parameter of type " + entryClass.getSimpleName() + "[]");
		}
	}

	/**
	 * Allows custom logic for the serialization and deserialization of an
	 * object's fields (actually its constructor parameters).
	 *
	 * @author Patrick Doyle
	 */
	public interface FieldModerator {
		JavaType typeOf(JavaType parameterType);
		Object valueFor(JavaType parameterType, Object deserializedValue);
	}

	/**
	 * The "normal" {@link FieldModerator} that doesn't add any extra logic.
	 *
	 * @author Patrick Doyle
	 */
	private record StateTreeNodeFieldModerator(Type nodeType) implements FieldModerator {
		@Override
		public JavaType typeOf(JavaType parameterType) {
			return parameterType;
		}

		@Override
		public Object valueFor(JavaType parameterType, Object deserializedValue) {
			return deserializedValue;
		}

	}

	/**
	 * Performs additional serialization logic for {@link DerivedRecord}
	 * objects. Specifically {@link ReflectiveEntity} fields, serializes them as
	 * though they were {@link Reference}s; otherwise, serializes normally.
	 *
	 * @author Patrick Doyle
	 */
	private record DerivedRecordFieldModerator(Type nodeType) implements FieldModerator {
		@Override
		public JavaType typeOf(JavaType parameterType) {
			if (reflectiveEntity(parameterType)) {
				// These are serialized as References
				return TypeFactory.defaultInstance()
					.constructParametricType(Reference.class, parameterType);
			} else {
				return parameterType;
			}
		}

		@Override
		public Object valueFor(JavaType parameterType, Object deserializedValue) {
			if (reflectiveEntity(parameterType)) {
				// The deserialized value is a Reference; what we want is Reference.value()
				return ((Reference<?>) deserializedValue).value();
			} else {
				return deserializedValue;
			}
		}

		private boolean reflectiveEntity(JavaType parameterType) {
			Class<?> parameterClass = parameterType.getRawClass();
			if (ReflectiveEntity.class.isAssignableFrom(parameterClass)) {
				return true;
			} else if (Entity.class.isAssignableFrom(parameterClass)) {
				throw new IllegalArgumentException(DerivedRecord.class.getSimpleName() + " " + rawClass(nodeType).getSimpleName() + " cannot contain " + Entity.class.getSimpleName() + " that is not a " + ReflectiveEntity.class.getSimpleName() + ": " + parameterType);
			} else if (Catalog.class.isAssignableFrom(parameterClass)) {
				throw new IllegalArgumentException(DerivedRecord.class.getSimpleName() + " " + rawClass(nodeType).getSimpleName() + " cannot contain Catalog (try Listing)");
			} else {
				return false;
			}
		}

	}

	//
	// Helpers
	//

	/**
	 * Returns the fields present in the JSON, with value objects deserialized
	 * using type information from <code>parametersByName</code>.
	 */
	public Map<String, Object> gatherParameterValuesByName(JavaType nodeJavaType, Map<String, Parameter> parametersByName, FieldModerator moderator, JsonParser p, DeserializationContext ctxt) throws IOException {
		Class<?> nodeClass = nodeJavaType.getRawClass();
		Map<String, Object> parameterValuesByName = new HashMap<>();
		expect(START_OBJECT, p);
		while (p.nextToken() != END_OBJECT) {
			p.nextValue();
			String name = p.currentName();
			Parameter parameter = parametersByName.get(name);
			if (parameter == null) {
				throw new JsonParseException(p, "No such parameter in constructor for " + nodeClass.getSimpleName() + ": " + name);
			} else {
				JavaType parameterType = TypeFactory.defaultInstance().resolveMemberType(parameter.getParameterizedType(), nodeJavaType.getBindings());
				Object deserializedValue;
				try (@SuppressWarnings("unused") DeserializationScope scope = nodeFieldDeserializationScope(nodeClass, name)) {
					deserializedValue = readField(name, p, ctxt, parameterType, moderator);
				}
				Object value = moderator.valueFor(parameterType, deserializedValue);
				Object prev = parameterValuesByName.put(name, value);
				if (prev != null) {
					throw new JsonParseException(p, "Parameter appeared twice: " + name);
				}
			}
		}
		return parameterValuesByName;
	}

	private Object readField(String name, JsonParser p, DeserializationContext ctxt, JavaType parameterType, FieldModerator moderator) throws IOException {
		// TODO: Combine with similar method in BsonPlugin
		JavaType effectiveType = moderator.typeOf(parameterType);
		Class<?> effectiveClass = effectiveType.getRawClass();
		if (Optional.class.isAssignableFrom(effectiveClass)) {
			// Optional field is present in JSON; wrap deserialized value in Optional.of
			JavaType contentsType = javaParameterType(effectiveType, Optional.class, 0);
			Object deserializedValue = readField(name, p, ctxt, contentsType, moderator);
			return Optional.of(deserializedValue);
		} else if (Phantom.class.isAssignableFrom(effectiveClass)) {
			throw new JsonParseException(p, "Unexpected phantom field \"" + name + "\"");
		} else {
			JsonDeserializer<Object> parameterDeserializer = ctxt.findContextualValueDeserializer(effectiveType, null);
			return parameterDeserializer.deserialize(p, ctxt);
		}
	}

	private static JavaType catalogEntryType(JavaType catalogType) {
		return javaParameterType(catalogType, Catalog.class, 0);
	}

	private static JavaType sideTableValueType(JavaType sideTableType) {
		return javaParameterType(sideTableType, SideTable.class, 1);
	}

	private static JavaType listValueEquivalentArrayType(JavaType listValueType) {
		return TypeFactory.defaultInstance().constructArrayType(javaParameterType(listValueType, ListValue.class, 0));
	}

	private static JavaType mapValueValueType(JavaType mapValueType) {
		return javaParameterType(mapValueType, MapValue.class, 0);
	}

	public static JavaType javaParameterType(JavaType parameterizedType, Class<?> expectedClass, int index) {
		try {
			return parameterizedType.findTypeParameters(expectedClass)[index];
		} catch (IndexOutOfBoundsException e) {
			throw new IllegalStateException("Error computing javaParameterType(" + parameterizedType + ", " + expectedClass + ", " + index + ")");
		}
	}

	public static void expect(JsonToken expected, JsonParser p) throws IOException {
		if (p.currentToken() != expected) {
			throw new JsonParseException(p, "Expected " + expected);
		}
	}

}
