package org.vena.bosk;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import lombok.Value;
import org.vena.bosk.annotations.DerivedRecord;
import org.vena.bosk.codecs.GsonAdapterCompiler;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.exceptions.TunneledCheckedException;
import org.vena.bosk.exceptions.UnexpectedPathException;

import static java.util.Objects.requireNonNull;
import static org.vena.bosk.ListingEntry.LISTING_ENTRY;
import static org.vena.bosk.ReferenceUtils.parameterType;
import static org.vena.bosk.ReferenceUtils.rawClass;
import static org.vena.bosk.ReferenceUtils.theOnlyConstructorFor;

public final class GsonPlugin extends SerializationPlugin {
	private final GsonAdapterCompiler compiler = new GsonAdapterCompiler(this);
	private final boolean separateOrderList;

	public GsonPlugin() {
		this(false);
	}

	public GsonPlugin(boolean separateOrderList) {
		this.separateOrderList = separateOrderList;
	}

	// Java's generics are just not capable of the following shenanigans.
	// This method leaps on the generics grenade so most of this class can
	// benefit from solid type checking.
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public <R extends Entity> TypeAdapterFactory adaptersFor(Bosk<R> bosk) {
		return new TypeAdapterFactory() {
			@Override
			public TypeAdapter create(Gson gson, TypeToken typeToken) {
				TypeAdapter result = getTypeAdapter(gson, typeToken);
				// While we don't have any particular desire to tolerate nulls here,
				// the Gson docs require it.
				// See: https://github.com/google/gson/blob/ceae88bd6667f4263bbe02e6b3710b8a683906a2/gson/src/main/java/com/google/gson/TypeAdapter.java#L146
				return (result == null) ? null : result.nullSafe();
			}

			private TypeAdapter getTypeAdapter(Gson gson, TypeToken typeToken) {
				Class theClass = typeToken.getRawType();
				if (theClass.isAnnotationPresent(DerivedRecord.class)) {
					return derivedRecordAdapter(gson, typeToken, bosk);
				} else if (Catalog.class.isAssignableFrom(theClass)) {
					return catalogAdapter(gson, typeToken);
				} else if (Listing.class.isAssignableFrom(theClass)) {
					return listingAdapter(gson);
				} else if (Reference.class.isAssignableFrom(theClass)) {
					return referenceAdapter(bosk);
				} else if (Identifier.class.isAssignableFrom(theClass)) {
					return identifierAdapter();
				} else if (ListingEntry.class.isAssignableFrom(theClass)) {
					return listingEntryAdapter();
				} else if (Mapping.class.isAssignableFrom(theClass)) {
					return mappingAdapter(gson, typeToken);
				} else if (ConfigurationNode.class.isAssignableFrom(theClass)) {
					return configurationNodeAdapter(gson, typeToken, bosk);
				} else if (Optional.class.isAssignableFrom(theClass)) {
					// Optional.empty() can't be serialized on its own because the field name itself must also be omitted
					throw new IllegalArgumentException("Cannot serialize an Optional on its own; only as a field of another object");
				} else if (Phantom.class.isAssignableFrom(theClass)) {
					throw new IllegalArgumentException("Cannot serialize a Phantom on its own; only as a field of another object");
				} else if (ListValue.class.isAssignableFrom(theClass)) {
					return listValueAdapter(gson, typeToken);
				} else if (MapValue.class.isAssignableFrom(theClass)) {
					return mapValueAdapter(gson, typeToken);
				} else {
					return null;
				}
			}

		};
	}

	private <V> TypeAdapter<ListValue<V>> listValueAdapter(Gson gson, TypeToken<ListValue<V>> typeToken) {
		Constructor<?> ctor = theOnlyConstructorFor(typeToken.getRawType());
		TypeToken<V[]> arrayType = listValueEquivalentArrayTypeToken(typeToken);
		TypeAdapter<V[]> arrayTypeAdapter = gson.getAdapter(arrayType);
		return new TypeAdapter<ListValue<V>>() {
			@Override
			public void write(JsonWriter out, ListValue<V> value) throws IOException {
				arrayTypeAdapter.write(out, value.toArray());
			}

			@SuppressWarnings("unchecked")
			@Override
			public ListValue<V> read(JsonReader in) throws IOException {
				V[] elementArray = arrayTypeAdapter.read(in);
				try {
					return (ListValue<V>) ctor.newInstance((Object) elementArray);
				} catch (InvocationTargetException | InstantiationException | IllegalAccessException e) {
					throw new IOException("Failed to instantiate " + typeToken.getRawType().getSimpleName() + ": " + e.getMessage(), e);
				}
			}
		};
	}

	private <V> TypeAdapter<MapValue<V>> mapValueAdapter(Gson gson, TypeToken<MapValue<V>> typeToken) {
		@SuppressWarnings("unchecked")
		TypeToken<V> valueTypeToken = (TypeToken<V>) TypeToken.get(parameterType(typeToken.getType(), MapValue.class, 0));
		TypeAdapter<V> valueAdapter = gson.getAdapter(valueTypeToken);
		return new TypeAdapter<MapValue<V>>() {
			@Override
			public void write(JsonWriter out, MapValue<V> value) throws IOException {
				out.beginObject();
				for (Entry<String, V> element: value.entrySet()) {
					out.name(requireNonNull(element.getKey()));
					valueAdapter.write(out, requireNonNull(element.getValue()));
				}
				out.endObject();
			}

			@Override
			public MapValue<V> read(JsonReader in) throws IOException {
				LinkedHashMap<String, V> result = new LinkedHashMap<>();
				in.beginObject();
				while (in.hasNext()) {
					String key = in.nextName();
					try (@SuppressWarnings("unused") DeserializationScope scope = innerDeserializationScope(key)) {
						V value = valueAdapter.read(in);
						V old = result.put(requireNonNull(key), requireNonNull(value));
						if (old != null) {
							throw new JsonParseException("MapValue key appears twice: \"" + key + "\"");
						}
					}
				}
				in.endObject();
				return MapValue.fromOrderedMap(result);
			}
		};
	}

	private TypeAdapter<Reference<?>> referenceAdapter(Bosk<?> bosk) {
		return new TypeAdapter<Reference<?>>() {
			@Override
			public void write(JsonWriter out, Reference<?> ref) throws IOException {
				out.value(ref.path().urlEncoded());
			}

			@Override
			public Reference<?> read(JsonReader in) throws IOException {
				try {
					return bosk.reference(Object.class, Path.parse(in.nextString()));
				} catch (InvalidTypeException e) {
					throw new UnexpectedPathException(e);
				}
			}
		};
	}

	private <E extends Entity> TypeAdapter<Listing<E>> listingAdapter(Gson gson) {
		TypeAdapter<Reference<Catalog<E>>> referenceAdapter = gson.getAdapter(new TypeToken<Reference<Catalog<E>>>() {});
		TypeAdapter<List<Identifier>> idListAdapter = gson.getAdapter(ID_LIST_TOKEN);
		return new TypeAdapter<Listing<E>>() {
			@Override
			public void write(JsonWriter out, Listing<E> listing) throws IOException {
				out.beginObject();

				out.name("domain");
				referenceAdapter.write(out, listing.domain());

				out.name("ids");
				idListAdapter.write(out, listing.ids());

				out.endObject();
			}

			@Override
			public Listing<E> read(JsonReader in) throws IOException {
				Reference<Catalog<E>> domain = null;
				List<Identifier> ids = null;

				in.beginObject();

				while (in.hasNext()) {
					String fieldName = in.nextName();
					switch (fieldName) {
					case "domain":
						if (domain == null) {
							domain = referenceAdapter.read(in);
						} else {
							throw new JsonParseException("'domain' field appears twice");
						}
						break;
					case "ids":
						if (ids == null) {
							ids = idListAdapter.read(in);
						} else {
							throw new JsonParseException("'ids' field appears twice");
						}
						break;
					default:
						throw new JsonParseException("Unrecognized field in Listing: " + fieldName);
					}
				}

				in.endObject();

				if (domain == null) {
					throw new JsonParseException("Missing 'domain' field");
				} else if (ids == null) {
					throw new JsonParseException("Missing 'ids' field");
				} else {
					return Listing.of(domain, ids);
				}
			}
		};
	}

	private <K extends Entity, V> TypeAdapter<Mapping<K,V>> mappingAdapter(Gson gson, TypeToken<Mapping<K,V>> typeToken) {
		TypeToken<V> valueToken = mappingValueTypeToken(typeToken);
		TypeAdapter<Reference<Catalog<K>>> referenceAdapter = gson.getAdapter(new TypeToken<Reference<Catalog<K>>>() {});
		TypeAdapter<List<Identifier>> idListAdapter = gson.getAdapter(ID_LIST_TOKEN);
		TypeAdapter<Map<Identifier, V>> mapAdapter = identifierMapAdapter(gson, valueToken);
		TypeAdapter<V> valueAdapter = gson.getAdapter(valueToken);
		return new TypeAdapter<Mapping<K,V>>() {
			@Override
			public void write(JsonWriter out, Mapping<K,V> mapping) throws IOException {
				out.beginObject();

				out.name("domain");
				referenceAdapter.write(out, mapping.domain());

				if (separateOrderList) {
					out.name("order");
					idListAdapter.write(out, mapping.ids());
					out.name("valuesById");
					mapAdapter.write(out, mapping.asMap());
				} else {
					out.name("valuesById");
					writeMapEntries(out, mapping.idEntrySet(), valueAdapter);
				}

				out.endObject();
			}

			@Override
			public Mapping<K,V> read(JsonReader in) throws IOException {
				Reference<Catalog<K>> domain = null;
				LinkedHashMap<Identifier, V> valuesById = null;
				List<Identifier> order = null;

				in.beginObject();

				while (in.hasNext()) {
					String fieldName = in.nextName();
					switch (fieldName) {
					case "domain":
						if (domain == null) {
							domain = referenceAdapter.read(in);
						} else {
							throw new JsonParseException("'domain' field appears twice");
						}
						break;
					case "order":
						if (order == null) {
							if (separateOrderList) {
								order = idListAdapter.read(in);
							} else {
								throw new JsonParseException("Unexpected 'oder' field");
							}
						} else {
							throw new JsonParseException("'order' field appears twice");
						}
						break;
					case "valuesById":
						if (valuesById == null) {
							if (separateOrderList) {
								valuesById = (LinkedHashMap<Identifier, V>) mapAdapter.read(in); // We know what type this really returns
							} else {
								valuesById = readMapEntries(in, valueAdapter);
							}
						} else {
							throw new JsonParseException("'valuesById' field appears twice");
						}
						break;
					default:
						throw new JsonParseException("Unrecognized field in Mapping: " + fieldName);
					}
				}

				in.endObject();

				if (domain == null) {
					throw new JsonParseException("Missing 'domain' field");
				} else if (valuesById == null) {
					throw new JsonParseException("Missing 'valuesById' field");
				} else if (order == null) {
					return Mapping.fromOrderedMap(domain, valuesById);
				} else if (valuesById.size() != order.size() && !valuesById.keySet().equals(new HashSet<>(order))) {
					throw new JsonParseException("'valuesById' and 'order' don't match");
				} else {
					return Mapping.fromFunction(domain, order.stream(), valuesById::get);
				}
			}

		};
	}

	private <V> void writeMapEntries(JsonWriter out, Iterable<Entry<Identifier, V>> entries, TypeAdapter<V> valueAdapter) throws IOException {
		out.beginArray();
		for (Entry<Identifier, V> entry: entries) {
			out.beginObject();
			out.name(entry.getKey().toString());
			valueAdapter.write(out, entry.getValue());
			out.endObject();
		}
		out.endArray();
	}

	private <V> LinkedHashMap<Identifier, V> readMapEntries(JsonReader in, TypeAdapter<V> valueAdapter) throws IOException {
		LinkedHashMap<Identifier, V> result = new LinkedHashMap<>();
		in.beginArray();
		while (in.hasNext()) {
			in.beginObject();
			String fieldName = in.nextName();
			V value;
			try (@SuppressWarnings("unused") DeserializationScope scope = innerDeserializationScope(fieldName)) {
				value = valueAdapter.read(in);
			}
			in.endObject();

			V oldValue = result.put(Identifier.from(fieldName), value);
			if (oldValue != null) {
				throw new JsonParseException("Duplicate mapping entry '" + fieldName + "'");
			}
		}
		in.endArray();
		return result;
	}

	private <E extends Entity> TypeAdapter<Catalog<E>> catalogAdapter(Gson gson, TypeToken<Catalog<E>> typeToken) {
		TypeToken<E> typeParameterToken = catalogEntryTypeToken(typeToken);
		TypeAdapter<E> elementAdapter = gson.getAdapter(typeParameterToken);
		TypeAdapter<Map<Identifier, E>> mapAdapter = identifierMapAdapter(gson, typeParameterToken);
		TypeAdapter<List<Identifier>> idListAdapter = gson.getAdapter(ID_LIST_TOKEN);

		return new TypeAdapter<Catalog<E>>() {
			@Override
			public void write(JsonWriter out, Catalog<E> catalog) throws IOException {
				if (separateOrderList) {
					out.beginObject();

					out.name("order");
					idListAdapter.write(out, catalog.ids());

					out.name("contents");
					out.beginObject();
					for (E element: catalog) {
						out.name(element.id().toString());
						elementAdapter.write(out, element);
					}
					out.endObject();

					out.endObject();
				} else {
					writeMapEntries(out, catalog.asMap().entrySet(), elementAdapter);
				}
			}

			@Override
			public Catalog<E> read(JsonReader in) throws IOException {
				Map<Identifier, E> contents = null;
				List<Identifier> order = null;

				if (separateOrderList) {
					in.beginObject();

					while (in.hasNext()) {
						String fieldName = in.nextName();
						switch (fieldName) {
							case "order":
								if (order == null) {
									order = idListAdapter.read(in);
								} else {
									throw new JsonParseException("'order' field appears twice");
								}
								break;
							case "contents":
								if (contents == null) {
									contents = mapAdapter.read(in);
								} else {
									throw new JsonParseException("'contents' field appears twice");
								}
								break;
							default:
								throw new JsonParseException("Unrecognized field in Catalog: " + fieldName);
						}
					}

					in.endObject();
				} else {
					contents = readMapEntries(in, elementAdapter);
				}

				if (contents == null) {
					throw new JsonParseException("Missing 'contents' field");
				} else if (order == null) {
					return Catalog.of(contents.values());
				} else if (contents.entrySet().size() != order.size() && !contents.keySet().equals(new HashSet<>(order))) {
					throw new JsonParseException("'contents' and 'order' don't match");
				} else {
					return Catalog.of(order.stream().map(contents::get));
				}
			}
		};
	}

	private static final TypeToken<List<Identifier>> ID_LIST_TOKEN = new TypeToken<List<Identifier>>() {};

	/**
	 * @return {@link TypeAdapter} for {@link Map} that preserves the order of
	 * fields, and also maintains the deserialization path.
	 *
	 * <p>
	 * This could be more efficient. We usually end up building a LinkedHashMap object
	 * that we then discard. But man, having this available makes the rest of the logic simpler.
	 */
	private <V> TypeAdapter<Map<Identifier, V>> identifierMapAdapter(Gson gson, TypeToken<V> valueTypeToken) {
		TypeAdapter<V> valueAdapter = gson.getAdapter(valueTypeToken);
		return new TypeAdapter<Map<Identifier,V>>() {
			@Override
			public void write(JsonWriter out, Map<Identifier, V> value) throws IOException {
				out.beginObject();
				for (Entry<Identifier, V> element: value.entrySet()) {
					out.name(element.getKey().toString());
					valueAdapter.write(out, element.getValue());
				}
				out.endObject();
			}

			@Override
			public LinkedHashMap<Identifier, V> read(JsonReader in) throws IOException {
				LinkedHashMap<Identifier, V> result = new LinkedHashMap<>();
				in.beginObject();
				while (in.hasNext()) {
					String fieldName = in.nextName();
					try (@SuppressWarnings("unused") DeserializationScope scope = innerDeserializationScope(fieldName)) {
						V value = valueAdapter.read(in);
						result.put(Identifier.from(fieldName), value);
					}
				}
				in.endObject();
				return result;
			}
		};
	}

	private TypeAdapter<Identifier> identifierAdapter() {
		return new TypeAdapter<Identifier>() {
			@Override
			public void write(JsonWriter out, Identifier id) throws IOException {
				out.value(id.toString());
			}

			@Override
			public Identifier read(JsonReader in) throws IOException {
				return Identifier.from(in.nextString());
			}
		};
	}

	private TypeAdapter<ListingEntry> listingEntryAdapter() {
		// We serialize ListingEntry as a boolean `true` with the following rationale:
		// - The only "unit type" in JSON is null
		// - `null` is not suitable because many systems treat that as being equivalent to an absent field
		// - Of the other types, boolean seems the most likely to be efficiently processed in every system
		// - `false` gives the wrong impression
		// Hence, by a process of elimination, `true` it is

		return new TypeAdapter<ListingEntry>() {
			@Override
			public void write(JsonWriter out, ListingEntry entry) throws IOException {
				out.value(true);
			}

			@Override
			public ListingEntry read(JsonReader in) throws IOException {
				boolean result = in.nextBoolean();
				if (result) {
					return LISTING_ENTRY;
				} else {
					throw new JsonParseException("Unexpected Listing entry value: " + result);
				}
			}
		};
	}

	<N extends ConfigurationNode> TypeAdapter<N> configurationNodeAdapter(Gson gson, TypeToken<N> typeToken, Bosk<?> bosk) {
		ConfigurationNodeFieldModerator moderator = new ConfigurationNodeFieldModerator(typeToken.getType());
		return compiler.compiled(typeToken, bosk, gson, moderator);
	}

	private <T> TypeAdapter<T> derivedRecordAdapter(Gson gson, TypeToken<T> typeToken, Bosk<?> bosk) {
		Type objType = typeToken.getType();

		// Check for special cases
		Class<?> objClass = rawClass(objType);
		if (ListValue.class.isAssignableFrom(objClass)) { // TODO: MapValue?
			Class<?> entryClass = rawClass(parameterType(objType, ListValue.class, 0));
			if (ReflectiveEntity.class.isAssignableFrom(entryClass)) {
				@SuppressWarnings("unchecked")
				TypeAdapter<T> result = derivedRecordListValueOfReflectiveEntityAdapter(gson, objType, objClass, entryClass);
				return result;
			} else if (Entity.class.isAssignableFrom(entryClass)) {
				throw new IllegalArgumentException("Can't hold non-reflective Entity type in @" + DerivedRecord.class.getSimpleName() + " " + objType);
			}
		}

		// Default DerivedRecord handling
		DerivedRecordFieldModerator moderator = new DerivedRecordFieldModerator(objType);
		return compiler.compiled(typeToken, bosk, gson, moderator);
	}


	@SuppressWarnings({ "rawtypes", "unchecked" })
	private <E extends ReflectiveEntity<E>, L extends ListValue<E>> TypeAdapter derivedRecordListValueOfReflectiveEntityAdapter(Gson gson, Type objType, Class objClass, Class entryClass) {
		Constructor<L> constructor = (Constructor<L>) theOnlyConstructorFor(objClass);
		Class<?>[] parameters = constructor.getParameterTypes();
		if (parameters.length == 1 && parameters[0].getComponentType().equals(entryClass)) {
			TypeToken<Reference<E>> elementType = (TypeToken)TypeToken.getParameterized(Reference.class, entryClass);
			TypeAdapter<Reference<E>> elementAdapter = gson.getAdapter(elementType);
			return new TypeAdapter<L>() {
				@Override
				public void write(JsonWriter out, L value) throws IOException {
					out.beginArray();
					try {
						value.forEach(entry -> {
							try {
								elementAdapter.write(out, entry.reference());
							} catch (IOException e) {
								throw new TunneledCheckedException(e);
							}
						});
					} catch (TunneledCheckedException e) {
						throw e.getCause(IOException.class);
					}
					out.endArray();
				}

				@Override
				public L read(JsonReader in) throws IOException {
					in.beginArray();
					List<E> entries = new ArrayList<>();
					while (in.hasNext()) {
						entries.add(elementAdapter.read(in).value());
					}
					in.endArray();

					E[] array = (E[])Array.newInstance(entryClass, entries.size());
					try {
						return constructor.newInstance(new Object[] { entries.toArray(array) } );
					} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
						throw new IOException("Error creating " + objClass.getSimpleName() + ": " + e.getMessage(), e);
					}
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
		Type typeOf(Type parameterType);
		Object valueFor(Type parameterType, Object deserializedValue);
	}

	/**
	 * The "normal" {@link FieldModerator} that doesn't add any extra logic.
	 *
	 * @author Patrick Doyle
	 */
	@Value
	private static class ConfigurationNodeFieldModerator implements FieldModerator {
		Type nodeType;

		@Override
		public Type typeOf(Type parameterType) {
			return parameterType;
		}

		@Override
		public Object valueFor(Type parameterType, Object deserializedValue) {
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
		public Type typeOf(Type parameterType) {
			if (reflectiveEntity(parameterType)) {
				// These are serialized as References
				return ReferenceUtils.referenceTypeFor(parameterType);
			} else {
				return parameterType;
			}
		}

		@Override
		public Object valueFor(Type parameterType, Object deserializedValue) {
			if (reflectiveEntity(parameterType)) {
				// The deserialized value is a Reference; what we want is Reference.value()
				return ((Reference<?>)deserializedValue).value();
			} else {
				return deserializedValue;
			}
		}

		private boolean reflectiveEntity(Type parameterType) {
			Class<?> parameterClass = rawClass(parameterType);
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
	public Map<String, Object> gatherParameterValuesByName(Class<?> nodeClass, Map<String, Parameter> parametersByName, FieldModerator moderator, JsonReader in, Gson gson) throws IOException {
		Map<String, Object> parameterValuesByName = new HashMap<>();
		while (in.hasNext()) {
			String name = in.nextName();
			Parameter parameter = parametersByName.get(name);
			if (parameter == null) {
				throw new JsonParseException("No such parameter in constructor for " + nodeClass.getSimpleName() + ": " + name);
			} else {
				Type parameterType = parameter.getParameterizedType();
				Object deserializedValue;
				try (@SuppressWarnings("unused") DeserializationScope scope = nodeFieldDeserializationScope(nodeClass, name)) {
					deserializedValue = readField(name, in, gson, parameterType, moderator);
				}
				Object value = moderator.valueFor(parameterType, deserializedValue);
				Object prev = parameterValuesByName.put(name, value);
				if (prev != null) {
					throw new JsonParseException("Parameter appeared twice: " + name);
				}
			}
		}
		return parameterValuesByName;
	}

	private Object readField(String name, JsonReader in, Gson gson, Type parameterType, FieldModerator moderator) throws IOException {
		// TODO: Combine with similar method in BsonPlugin
		Type effectiveType = moderator.typeOf(parameterType);
		Class<?> effectiveClass = rawClass(effectiveType);
		if (Optional.class.isAssignableFrom(effectiveClass)) {
			// Optional field is present in JSON; wrap deserialized value in Optional.of
			Type contentsType = parameterType(effectiveType, Optional.class, 0);
			Object deserializedValue = readField(name, in, gson, contentsType, moderator);
			return Optional.of(deserializedValue);
		} else if (Phantom.class.isAssignableFrom(effectiveClass)) {
			throw new JsonParseException("Unexpected phantom field \"" + name + "\"");
		} else {
			TypeAdapter<?> parameterAdapter = gson.getAdapter(TypeToken.get(effectiveType));
			return parameterAdapter.read(in);
		}
	}

	@SuppressWarnings("unchecked")
	private static <EE extends Entity> TypeToken<EE> catalogEntryTypeToken(TypeToken<Catalog<EE>> typeToken) {
		return (TypeToken<EE>) TypeToken.get(tokenParameterType(typeToken, Catalog.class, 0));
	}

	@SuppressWarnings("unchecked")
	private static <K extends Entity, V> TypeToken<V> mappingValueTypeToken(TypeToken<Mapping<K,V>> typeToken) {
		return (TypeToken<V>) TypeToken.get(tokenParameterType(typeToken, Mapping.class, 1));
	}

	@SuppressWarnings("unchecked")
	private static <V> TypeToken<V[]> listValueEquivalentArrayTypeToken(TypeToken<ListValue<V>> typeToken) {
		return (TypeToken<V[]>) TypeToken.getArray(parameterType(typeToken.getType(), ListValue.class, 0));
	}

	private static Type tokenParameterType(TypeToken<?> typeToken, Class<?> expectedClass, int index) {
		return parameterType(typeToken.getType(), expectedClass, index);
	}

}
