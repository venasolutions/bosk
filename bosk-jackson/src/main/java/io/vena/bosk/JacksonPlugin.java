package io.vena.bosk;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.Serializers;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.vena.bosk.annotations.DerivedRecord;
import io.vena.bosk.codecs.JacksonCompiler;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.exceptions.TunneledCheckedException;
import io.vena.bosk.exceptions.UnexpectedPathException;
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

public final class JacksonPlugin extends SerializationPlugin {
	private final JacksonCompiler compiler = new JacksonCompiler(this);

	public Module moduleFor(Bosk<?> bosk) {
		return new Module() {
			@Override
			public String getModuleName() {
				return JacksonPlugin.class.getSimpleName();
			}

			@Override
			public Version version() {
				return Version.unknownVersion();
			}

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
				return derivedRecordSerDes(type, beanDesc, bosk).serializer(config);
			} else if (Catalog.class.isAssignableFrom(theClass)) {
				return catalogSerDes(type, beanDesc, bosk).serializer(config);
			} else if (Listing.class.isAssignableFrom(theClass)) {
				return listingSerDes(type, beanDesc, bosk).serializer(config);
			} else if (Reference.class.isAssignableFrom(theClass)) {
				return referenceSerDes(type, beanDesc, bosk).serializer(config);
			} else if (Identifier.class.isAssignableFrom(theClass)) {
				return identifierSerDes(type, beanDesc, bosk).serializer(config);
			} else if (ListingEntry.class.isAssignableFrom(theClass)) {
				return listingEntrySerDes(type, beanDesc, bosk).serializer(config);
			} else if (SideTable.class.isAssignableFrom(theClass)) {
				return sideTableSerDes(type, beanDesc, bosk).serializer(config);
			} else if (StateTreeNode.class.isAssignableFrom(theClass)) {
				return stateTreeNodeSerDes(type, beanDesc, bosk).serializer(config);
			} else if (Optional.class.isAssignableFrom(theClass)) {
				// Optional.empty() can't be serialized on its own because the field name itself must also be omitted
				throw new IllegalArgumentException("Cannot serialize an Optional on its own; only as a field of another object");
			} else if (Phantom.class.isAssignableFrom(theClass)) {
				throw new IllegalArgumentException("Cannot serialize a Phantom on its own; only as a field of another object");
			} else if (ListValue.class.isAssignableFrom(theClass)) {
				return listValueSerDes(type, beanDesc, bosk).serializer(config);
			} else if (MapValue.class.isAssignableFrom(theClass)) {
				return mapValueSerDes(type, beanDesc, bosk).serializer(config);
			} else {
				return null;
			}
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
				return derivedRecordSerDes(type, beanDesc, bosk).deserializer(config);
			} else if (Catalog.class.isAssignableFrom(theClass)) {
				return catalogSerDes(type, beanDesc, bosk).deserializer(config);
			} else if (Listing.class.isAssignableFrom(theClass)) {
				return listingSerDes(type, beanDesc, bosk).deserializer(config);
			} else if (Reference.class.isAssignableFrom(theClass)) {
				return referenceSerDes(type, beanDesc, bosk).deserializer(config);
			} else if (Identifier.class.isAssignableFrom(theClass)) {
				return identifierSerDes(type, beanDesc, bosk).deserializer(config);
			} else if (ListingEntry.class.isAssignableFrom(theClass)) {
				return listingEntrySerDes(type, beanDesc, bosk).deserializer(config);
			} else if (SideTable.class.isAssignableFrom(theClass)) {
				return sideTableSerDes(type, beanDesc, bosk).deserializer(config);
			} else if (StateTreeNode.class.isAssignableFrom(theClass)) {
				return stateTreeNodeSerDes(type, beanDesc, bosk).deserializer(config);
			} else if (Optional.class.isAssignableFrom(theClass)) {
				// Optional.empty() can't be serialized on its own because the field name itself must also be omitted
				throw new IllegalArgumentException("Cannot serialize an Optional on its own; only as a field of another object");
			} else if (Phantom.class.isAssignableFrom(theClass)) {
				throw new IllegalArgumentException("Cannot serialize a Phantom on its own; only as a field of another object");
			} else if (ListValue.class.isAssignableFrom(theClass)) {
				return listValueSerDes(type, beanDesc, bosk).deserializer(config);
			} else if (MapValue.class.isAssignableFrom(theClass)) {
				return mapValueSerDes(type, beanDesc, bosk).deserializer(config);
			} else {
				return null;
			}
		}

		// Thanks but no thanks, Jackson. We don't need your help.

		@Override
		public JsonDeserializer<?> findCollectionDeserializer(CollectionType type, DeserializationConfig config, BeanDescription beanDesc, TypeDeserializer elementTypeDeserializer, JsonDeserializer<?> elementDeserializer) throws JsonMappingException {
			return findBeanDeserializer(type, config, beanDesc);
		}

		@Override
		public JsonDeserializer<?> findMapDeserializer(MapType type, DeserializationConfig config, BeanDescription beanDesc, KeyDeserializer keyDeserializer, TypeDeserializer elementTypeDeserializer, JsonDeserializer<?> elementDeserializer) throws JsonMappingException {
			return findBeanDeserializer(type, config, beanDesc);
		}
	}

	public interface SerDes<T> {
		JsonSerializer<T> serializer(SerializationConfig config);
		JsonDeserializer<T> deserializer(DeserializationConfig config);
	}

	private <V> SerDes<ListValue<V>> listValueSerDes(JavaType type, BeanDescription beanDesc, Bosk<?> bosk) {
		Constructor<?> ctor = theOnlyConstructorFor(type.getRawClass());
		JavaType arrayType = listValueEquivalentArrayType(type);
		JavaType listType = listValueEquivalentListType(type);
		return new SerDes<ListValue<V>>() {
			@Override
			public JsonSerializer<ListValue<V>> serializer(SerializationConfig serializationConfig) {
				return new JsonSerializer<ListValue<V>>() {
					@Override
					public void serialize(ListValue<V> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
						// Note that a ListValue<String> can actually contain an Object[],
						// which Jackson won't serialize as a String[], so we can't use arrayType.
						serializers.findValueSerializer(listType, null)
							.serialize(value, gen, serializers);
					}
				};
			}

			@Override
			public JsonDeserializer<ListValue<V>> deserializer(DeserializationConfig deserializationConfig) {
				return new JsonDeserializer<ListValue<V>>() {
					@Override
					@SuppressWarnings({"unchecked"})
					public ListValue<V> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
						Object elementArray = ctxt
							.findContextualValueDeserializer(arrayType, null)
							.deserialize(p, ctxt);
						try {
							return (ListValue<V>) ctor.newInstance(elementArray);
						} catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
							throw new IOException("Failed to instantiate " + type.getRawClass().getSimpleName() + ": " + e.getMessage(), e);
						}
					}
				};
			}
		};
	}

	private <V> SerDes<MapValue<V>> mapValueSerDes(JavaType type, BeanDescription beanDesc, Bosk<?> bosk) {
		JavaType valueType = mapValueValueType(type);
		return new SerDes<MapValue<V>>() {
			@Override
			public JsonSerializer<MapValue<V>> serializer(SerializationConfig serializationConfig) {
				return new JsonSerializer<MapValue<V>>() {
					@Override
					public void serialize(MapValue<V> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
						JsonSerializer<Object> valueSerializer = serializers.findValueSerializer(valueType);
						gen.writeStartObject();
						for (Entry<String, V> element : value.entrySet()) {
							gen.writeFieldName(requireNonNull(element.getKey()));
							valueSerializer.serialize(requireNonNull(element.getValue()), gen, serializers);
						}
						gen.writeEndObject();
					}
				};
			}

			@Override
			public JsonDeserializer<MapValue<V>> deserializer(DeserializationConfig deserializationConfig) {
				return new JsonDeserializer<MapValue<V>>() {
					@Override
					public MapValue<V> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
						LinkedHashMap<String, V> result = new LinkedHashMap<>();
						expect(START_OBJECT, p);
						while (p.nextToken() != END_OBJECT) {
							p.nextValue();
							String key = p.currentName();
							@SuppressWarnings("unchecked")
							V value = (V) ctxt.findContextualValueDeserializer(valueType, null)
								.deserialize(p, ctxt);
							V old = result.put(key, value);
							if (old != null) {
								throw new JsonParseException(p, "MapValue key appears twice: \"" + key + "\"");
							}
						}
						expect(END_OBJECT, p);
						return MapValue.fromOrderedMap(result);
					}
				};
			}
		};
	}

	private SerDes<Reference<?>> referenceSerDes(JavaType type, BeanDescription beanDesc, Bosk<?> bosk) {
		return new SerDes<Reference<?>>() {
			@Override
			public JsonSerializer<Reference<?>> serializer(SerializationConfig config) {
				return new JsonSerializer<Reference<?>>() {
					@Override
					public void serialize(Reference<?> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
						gen.writeString(value.path().urlEncoded());
					}
				};
			}

			@Override
			public JsonDeserializer<Reference<?>> deserializer(DeserializationConfig config) {
				return new JsonDeserializer<Reference<?>>() {
					@Override
					public Reference<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
						try {
							return bosk.reference(Object.class, Path.parse(p.getText()));
						} catch (InvalidTypeException e) {
							throw new UnexpectedPathException(e);
						}
					}
				};
			}
		};
	}

	private <E extends Entity> SerDes<Listing<E>> listingSerDes(JavaType type, BeanDescription beanDesc, Bosk<?> bosk) {
		return new SerDes<Listing<E>>() {
			@Override
			public JsonSerializer<Listing<E>> serializer(SerializationConfig config) {
				return new JsonSerializer<Listing<E>>() {
					@Override
					public void serialize(Listing<E> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
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

			@Override
			public JsonDeserializer<Listing<E>> deserializer(DeserializationConfig config) {
				return new JsonDeserializer<Listing<E>>() {
					@Override
					@SuppressWarnings("unchecked")
					public Listing<E> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
						Reference<Catalog<E>> domain = null;
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
									domain = (Reference<Catalog<E>>) ctxt
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
		};
	}

	private <K extends Entity, V> SerDes<SideTable<K,V>> sideTableSerDes(JavaType type, BeanDescription beanDesc, Bosk<?> bosk) {
		JavaType valueType = sideTableValueType(type);
		return new SerDes<SideTable<K,V>>() {
			@Override
			public JsonSerializer<SideTable<K, V>> serializer(SerializationConfig config) {
				return new JsonSerializer<SideTable<K, V>>() {
					@Override
					public void serialize(SideTable<K, V> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
						gen.writeStartObject();

						gen.writeFieldName("valuesById");
						@SuppressWarnings("unchecked")
						JsonSerializer<V> contentValueSerializer = (JsonSerializer<V>) serializers.findContentValueSerializer(valueType, null);
						writeMapEntries(gen, value.idEntrySet(), contentValueSerializer, serializers);

						gen.writeFieldName("domain");
						serializers
							.findContentValueSerializer(Reference.class, null)
							.serialize(value.domain(), gen, serializers);

						gen.writeEndObject();
					}
				};
			}

			@Override
			@SuppressWarnings("unchecked")
			public JsonDeserializer<SideTable<K, V>> deserializer(DeserializationConfig config) {
				return new JsonDeserializer<SideTable<K, V>>() {
					@Override
					public SideTable<K, V> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
						Reference<Catalog<K>> domain = null;
						LinkedHashMap<Identifier, V> valuesById = null;

						JsonDeserializer<V> valueDeserializer = (JsonDeserializer<V>) ctxt.findContextualValueDeserializer(valueType, null);

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
										domain = (Reference<Catalog<K>>) ctxt
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

		};
	}

	private <V> void writeMapEntries(JsonGenerator gen, Set<Entry<Identifier,V>> entries, JsonSerializer<V> valueSerializer, SerializerProvider serializers) throws IOException {
		gen.writeStartArray();
		for (Entry<Identifier, V> entry: entries) {
			gen.writeStartObject();
			gen.writeFieldName(entry.getKey().toString());
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
			V value;
			try (@SuppressWarnings("unused") DeserializationScope scope = innerDeserializationScope(fieldName)) {
				value = valueDeserializer.deserialize(p, ctxt);
			}
			p.nextToken();
			expect(END_OBJECT, p);

			V oldValue = result.put(Identifier.from(fieldName), value);
			if (oldValue != null) {
				throw new JsonParseException(p, "Duplicate sideTable entry '" + fieldName + "'");
			}
		}
		return result;
	}

	private <E extends Entity> SerDes<Catalog<E>> catalogSerDes(JavaType type, BeanDescription beanDesc, Bosk<?> bosk) {
		JavaType entryType = catalogEntryType(type);

		return new SerDes<Catalog<E>>() {
			@Override
			public JsonSerializer<Catalog<E>> serializer(SerializationConfig config) {
				return new JsonSerializer<Catalog<E>>() {
					@Override
					@SuppressWarnings({"rawtypes", "unchecked"})
					public void serialize(Catalog<E> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
						JsonSerializer valueSerializer = serializers.findContentValueSerializer(entryType, null);
						writeMapEntries(gen, value.asMap().entrySet(), valueSerializer, serializers);
					}
				};
			}

			@Override
			public JsonDeserializer<Catalog<E>> deserializer(DeserializationConfig config) {
				return new JsonDeserializer<Catalog<E>>() {
					@Override
					@SuppressWarnings({"rawtypes", "unchecked"})
					public Catalog<E> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
						JsonDeserializer valueDeserializer = ctxt.findContextualValueDeserializer(entryType, null);
						LinkedHashMap<Identifier, E> entries = readMapEntries(p, valueDeserializer, ctxt);
						return Catalog.of(entries.values());
					}
				};
			}
		};
	}

	private static final JavaType ID_LIST_TYPE = TypeFactory.defaultInstance().constructType(new TypeReference<
		List<Identifier>>() {});

	private static final JavaType CATALOG_REF_TYPE = TypeFactory.defaultInstance().constructType(new TypeReference<
		Reference<Catalog<?>>>() {});

	private SerDes<Identifier> identifierSerDes(JavaType type, BeanDescription beanDesc, Bosk<?> bosk) {
		return new SerDes<Identifier>() {
			@Override
			public JsonSerializer<Identifier> serializer(SerializationConfig config) {
				return new JsonSerializer<Identifier>() {
					@Override
					public void serialize(Identifier value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
						gen.writeString(value.toString());
					}
				};
			}

			@Override
			public JsonDeserializer<Identifier> deserializer(DeserializationConfig config) {
				return new JsonDeserializer<Identifier>() {
					@Override
					public Identifier deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
						return Identifier.from(p.getText());
					}
				};
			}
		};
	}

	private SerDes<ListingEntry> listingEntrySerDes(JavaType type, BeanDescription beanDesc, Bosk<?> bosk) {
		// We serialize ListingEntry as a boolean `true` with the following rationale:
		// - The only "unit type" in JSON is null
		// - `null` is not suitable because many systems treat that as being equivalent to an absent field
		// - Of the other types, boolean seems the most likely to be efficiently processed in every system
		// - `false` gives the wrong impression
		// Hence, by a process of elimination, `true` it is

		return new SerDes<ListingEntry>() {
			@Override
			public JsonSerializer<ListingEntry> serializer(SerializationConfig config) {
				return new JsonSerializer<ListingEntry>() {
					@Override
					public void serialize(ListingEntry value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
						gen.writeBoolean(true);
					}
				};
			}

			@Override
			public JsonDeserializer<ListingEntry> deserializer(DeserializationConfig config) {
				return new JsonDeserializer<ListingEntry>() {
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
		};
	}

	private <N extends StateTreeNode> SerDes<N> stateTreeNodeSerDes(JavaType type, BeanDescription beanDesc, Bosk<?> bosk) {
		StateTreeNodeFieldModerator moderator = new StateTreeNodeFieldModerator(type);
		return compiler.compiled(type, bosk, moderator);
	}

	private <T> SerDes<T> derivedRecordSerDes(JavaType objType, BeanDescription beanDesc, Bosk<?> bosk) {
		// Check for special cases
		Class<?> objClass = objType.getRawClass();
		if (ListValue.class.isAssignableFrom(objClass)) { // TODO: MapValue?
			Class<?> entryClass = javaParameterType(objType, ListValue.class, 0).getRawClass();
			if (ReflectiveEntity.class.isAssignableFrom(entryClass)) {
				@SuppressWarnings("unchecked")
				SerDes<T> result = derivedRecordListValueOfReflectiveEntitySerDes(objType, objClass, entryClass);
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
	private <E extends ReflectiveEntity<E>, L extends ListValue<E>> SerDes derivedRecordListValueOfReflectiveEntitySerDes(JavaType objType, Class objClass, Class entryClass) {
		Constructor<L> constructor = (Constructor<L>) theOnlyConstructorFor(objClass);
		Class<?>[] parameters = constructor.getParameterTypes();
		if (parameters.length == 1 && parameters[0].getComponentType().equals(entryClass)) {
			JavaType referenceType = TypeFactory.defaultInstance().constructParametricType(Reference.class, entryClass);
			return new SerDes<L>() {
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
					return new JsonDeserializer<L>() {
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
	@Value
	private static class StateTreeNodeFieldModerator implements FieldModerator {
		Type nodeType;

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
	@Value
	private static class DerivedRecordFieldModerator implements FieldModerator {
		Type nodeType;

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
				return ((Reference<?>)deserializedValue).value();
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
	public Map<String, Object> gatherParameterValuesByName(Class<?> nodeClass, Map<String, Parameter> parametersByName, FieldModerator moderator, JsonParser p, DeserializationContext ctxt) throws IOException {
		Map<String, Object> parameterValuesByName = new HashMap<>();
		expect(START_OBJECT, p);
		while (p.nextToken() != END_OBJECT) {
			p.nextValue();
			String name = p.currentName();
			Parameter parameter = parametersByName.get(name);
			if (parameter == null) {
				throw new JsonParseException(p, "No such parameter in constructor for " + nodeClass.getSimpleName() + ": " + name);
			} else {
				JavaType parameterType = TypeFactory.defaultInstance().constructType(parameter.getParameterizedType());
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

	private static JavaType listValueEquivalentListType(JavaType listValueType) {
		return TypeFactory.defaultInstance().constructCollectionType(List.class, javaParameterType(listValueType, ListValue.class, 0));
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
