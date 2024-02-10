package io.vena.bosk.drivers.mongo;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.UpdateDescription;
import io.vena.bosk.Bosk;
import io.vena.bosk.Listing;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import io.vena.bosk.SerializationPlugin;
import io.vena.bosk.SideTable;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import lombok.NonNull;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonReader;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.codecs.BsonValueCodec;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodecProvider;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.ValueCodecProvider;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.io.BasicOutputBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.vena.bosk.ReferenceUtils.rawClass;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

/**
 * Facilities to translate between in-DB and in-memory representations.
 *
 * @author pdoyle
 */
final class Formatter {
	private final CodecRegistry simpleCodecs;
	private final Function<Type, Codec<?>> preferredBoskCodecs;
	private final Function<Reference<?>, SerializationPlugin.DeserializationScope> deserializationScopeFunction;

	/**
	 * If the diagnostic attributes are identical from one update to the next,
	 * MongoDB won't send them. This field retains the last value so we can
	 * always set the correct context for each downstream operation.
	 */
	private volatile MapValue<String> lastEventDiagnosticAttributes = MapValue.empty();

	Formatter(Bosk<?> bosk, BsonPlugin bsonPlugin) {
		this.simpleCodecs = CodecRegistries.fromProviders(
			bsonPlugin.codecProviderFor(bosk),
			new ValueCodecProvider(),
			new DocumentCodecProvider());
		this.preferredBoskCodecs = type -> bsonPlugin.getCodec(type, rawClass(type), simpleCodecs, bosk);
		this.deserializationScopeFunction = bsonPlugin::newDeserializationScope;
	}

	/**
	 * Revision number zero represents a nonexistent version number,
	 * consistent with the behaviour of the MongoDB <code>$inc</code> operator,
	 * which treats a nonexistent field as a zero.
	 * For us, this occurs in two situations:
	 *
	 * <ol><li>
	 *     Initialization: There is no state information yet because the document doesn't exist
	 * </li><li>
	 *     Legacy: The database collection pre-dates revision numbers and doesn't have one
	 * </li></ol>
	 *
	 * The revision field in the database is never less than this value: it is initialized to
	 * {@link #REVISION_ONE} and incremented thereafter. Therefore, waiting for a revision
	 * greater than or equal to this is equivalent to waiting for any update at all.
	 */
	static final BsonInt64 REVISION_ZERO = new BsonInt64(0);

	/**
	 * The revision number used when the bosk document is first created.
	 */
	static final BsonInt64 REVISION_ONE = new BsonInt64(1);

	/**
	 * The fields of the main MongoDB document.  Case-sensitive.
	 *
	 * <p>
	 * No field name should be a prefix of any other.
	 */
	enum DocumentFields {
		path,
		state,
		revision,
		diagnostics,
	}

	private final BsonInt32 SUPPORTED_MANIFEST_VERSION = new BsonInt32(1);

	//
	// Helpers to translate Bosk <-> MongoDB
	//

	Codec<?> codecFor(Type type) {
		// BsonPlugin gives better codecs than CodecRegistry, because BsonPlugin is aware of generics,
		// so we always try that first. The CodecSupplier protocol uses "null" to indicate that another
		// CodecSupplier should be used, so we follow that protocol and fall back on the CodecRegistry.
		// TODO: Should this logic be in BsonPlugin? It has nothing to do with MongoDriver really.
		Codec<?> result = preferredBoskCodecs.apply(type);
		if (result == null) {
			return simpleCodecs.get(rawClass(type));
		} else {
			return result;
		}
	}

	@SuppressWarnings("unchecked")
	<T> T document2object(BsonDocument doc, Reference<T> target) {
		Type type = target.targetType();
		Class<T> objectClass = (Class<T>) rawClass(type);
		Codec<T> objectCodec = (Codec<T>) codecFor(type);
		try (@SuppressWarnings("unused") BsonPlugin.DeserializationScope scope = deserializationScopeFunction.apply(target)) {
			return objectClass.cast(objectCodec.decode(doc.asBsonReader(), DecoderContext.builder().build()));
		}
	}

	void validateManifest(BsonDocument manifest) throws UnrecognizedFormatException {
		try {
			Set<String> keys = new HashSet<>(manifest.keySet());
			List<String> supportedFormats = asList("sequoia", "pando");
			String detectedFormat = null;
			for (String format: supportedFormats) {
				if (keys.remove(format)) {
					if (detectedFormat == null) {
						detectedFormat = format;
					} else {
						throw new UnrecognizedFormatException("Found two supported formats: " + detectedFormat + " and " + format);
					}
				}
			}
			if (detectedFormat == null) {
				throw new UnrecognizedFormatException("Found none of the supported formats: " + supportedFormats);
			}
			HashSet<String> requiredKeys = new HashSet<>(singletonList("version"));
			if (!keys.equals(requiredKeys)) {
				keys.removeAll(requiredKeys);
				if (keys.isEmpty()) {
					requiredKeys.removeAll(manifest.keySet());
					throw new UnrecognizedFormatException("Missing keys in manifest: " + requiredKeys);
				} else {
					throw new UnrecognizedFormatException("Unrecognized keys in manifest: " + keys);
				}
			}
			if (!SUPPORTED_MANIFEST_VERSION.equals(manifest.getInt32("version"))) {
				throw new UnrecognizedFormatException("Manifest version " + manifest.getInt32("version") + " not suppoted");
			}
		} catch (ClassCastException e) {
			throw new UnrecognizedFormatException("Manifest field has unexpected type", e);
		}
	}

	Manifest decodeManifest(BsonDocument manifestDoc) throws UnrecognizedFormatException {
		BsonDocument manifest = manifestDoc.clone();
		manifest.remove("_id");
		validateManifest(manifest);
		return (Manifest) codecFor(Manifest.class)
			.decode(
				new BsonDocumentReader(manifest),
				DecoderContext.builder().build());
	}

	BsonDocument encodeDiagnostics(MapValue<String> attributes) {
		BsonDocument result = new BsonDocument();
		attributes.forEach((name, value) -> result.put(name, new BsonString(value)));
		return new BsonDocument("attributes", result);
	}

	MapValue<String> decodeDiagnosticAttributes(BsonDocument diagnostics) {
		MapValue<String> result = MapValue.empty();
		for (Map.Entry<String, BsonValue> foo: diagnostics.getDocument("attributes").entrySet()) {
			String name = foo.getKey();
			String value = foo.getValue().asString().getValue();
			result = result.with(name, value);
		}
		return result;
	}

	/**
	 * @see #bsonValue2object(BsonValue, Reference)
	 */
	@SuppressWarnings("unchecked")
	<T> BsonValue object2bsonValue(T object, Type type) {
		rawClass(type).cast(object);
		Codec<T> objectCodec = (Codec<T>) codecFor(type);
		BsonDocument document = new BsonDocument();
		try (BsonDocumentWriter writer = new BsonDocumentWriter(document)) {
			// To support arbitrary values, not just whole documents, we put the result INSIDE a document.
			writer.writeStartDocument();
			writer.writeName("value");
			objectCodec.encode(writer, object, EncoderContext.builder().build());
			writer.writeEndDocument();
		}
		return document.get("value");
	}

	/**
	 * @see #object2bsonValue(Object, Type)
	 */
	@SuppressWarnings("unchecked")
	<T> T bsonValue2object(BsonValue bson, Reference<T> target) {
		Codec<T> objectCodec = (Codec<T>) codecFor(target.targetType());
		BsonDocument document = new BsonDocument();
		document.append("value", bson);
		try (
			@SuppressWarnings("unused") BsonPlugin.DeserializationScope scope = deserializationScopeFunction.apply(target);
			BsonReader reader = document.asBsonReader()
		) {
			reader.readStartDocument();
			reader.readName("value");
			return objectCodec.decode(reader, DecoderContext.builder().build());
		}
	}

	@SuppressWarnings("unchecked")
	long bsonValueBinarySize(BsonValue bson) {
		Codec<BsonValue> codec = new BsonValueCodec();
		try (
			BasicOutputBuffer buffer = new BasicOutputBuffer();
			BsonBinaryWriter w = new BsonBinaryWriter(buffer)
		) {
			codec.encode(w, bson, EncoderContext.builder().build());
			return buffer.getPosition();
		}
	}

	BsonInt64 getRevisionFromFullDocument(BsonDocument fullDocument) {
		if (fullDocument == null) {
			return null;
		}
		return fullDocument.getInt64(DocumentFields.revision.name(), null);
	}

	@NonNull MapValue<String> eventDiagnosticAttributesFromFullDocument(BsonDocument fullDocument) {
		return getOrSetEventDiagnosticAttributes(getDiagnosticAttributesFromFullDocument(fullDocument));
	}

	MapValue<String> getDiagnosticAttributesFromFullDocument(BsonDocument fullDocument) {
		BsonDocument diagnostics = getDiagnosticAttributesIfAny(fullDocument);
		return diagnostics == null ? null : decodeDiagnosticAttributes(diagnostics);
	}

	BsonInt64 getRevisionFromUpdateEvent(ChangeStreamDocument<BsonDocument> event) {
		BsonDocument updatedFields = getUpdatedFieldsIfAny(event);
		if (updatedFields == null) {
			return null;
		}
		return updatedFields.getInt64(DocumentFields.revision.name(), null);
	}

	@NonNull MapValue<String> eventDiagnosticAttributesFromUpdate(ChangeStreamDocument<BsonDocument> event) {
		return getOrSetEventDiagnosticAttributes(getDiagnosticAttributesFromUpdateEvent(event));
	}

	MapValue<String> getDiagnosticAttributesFromUpdateEvent(ChangeStreamDocument<BsonDocument> event) {
		BsonDocument updatedFields = getUpdatedFieldsIfAny(event);
		BsonDocument diagnostics = getDiagnosticAttributesIfAny(updatedFields);
		return diagnostics == null ? null : decodeDiagnosticAttributes(diagnostics);
	}

	private static BsonDocument getUpdatedFieldsIfAny(ChangeStreamDocument<BsonDocument> event) {
		if (event == null) {
			return null;
		}
		UpdateDescription updateDescription = event.getUpdateDescription();
		if (updateDescription == null) {
			return null;
		}
		return updateDescription.getUpdatedFields();
	}

	static BsonDocument getDiagnosticAttributesIfAny(BsonDocument fullDocument) {
		if (fullDocument == null) {
			return null;
		}
		BsonDocument diagnostics = fullDocument.getDocument(DocumentFields.diagnostics.name(), null);
		if (diagnostics == null) {
			return null;
		}
		return diagnostics;
	}

	@NonNull private MapValue<String> getOrSetEventDiagnosticAttributes(MapValue<String> fromEvent) {
		if (fromEvent == null) {
			LOGGER.debug("No diagnostic attributes in event; assuming they are unchanged");
			return lastEventDiagnosticAttributes;
		} else {
			lastEventDiagnosticAttributes = fromEvent;
			return fromEvent;
		}
	}

	/**
	 * @return MongoDB field name corresponding to the given Reference
	 * @see #referenceTo(String, Reference)
	 */
	static <T> String dottedFieldNameOf(Reference<T> ref, Reference<?> startingRef) {
		return dottedFieldNameOf(ref, ref.path().length(), startingRef);
	}

	/**
	 * @param refLength behave as though <code>ref</code> were truncated to this length, without actually having to do it
	 * @return MongoDB field name corresponding to the given Reference
	 * @see #referenceTo(String, Reference)
	 */
	static <T> String dottedFieldNameOf(Reference<T> ref, int refLength, Reference<?> startingRef) {
		ArrayList<String> segments = dottedFieldNameSegments(ref, refLength, startingRef);
		return String.join(".", segments.toArray(new String[0]));
	}

	static <T> ArrayList<String> dottedFieldNameSegments(Reference<T> ref, int refLength, Reference<?> startingRef) {
		assert startingRef.path().matchesPrefixOf(ref.path()): "'" + ref + "' must be under '" + startingRef + "'";
		ArrayList<String> segments = new ArrayList<>();
		segments.add(DocumentFields.state.name());
		buildDottedFieldNameOf(ref, startingRef.path().length(), refLength, segments);
		return segments;
	}

	/**
	 * @param elementRefLength behave as though <code>elementRef</code> were truncated to this length, without actually having to do it
	 * @return MongoDB field name corresponding to the container (Catalog or SideTable) that contains the given element
	 * @see #referenceTo(String, Reference)
	 */
	static <T> List<String> containerSegments(Reference<T> elementRef, int elementRefLength, Reference<?> startingRef) {
		List<String> elementSegments = dottedFieldNameSegments(elementRef, elementRefLength, startingRef);
		return elementSegments.subList(0, elementSegments.size()-1); // Trim off the element itself
	}

	/**
	 * @param refLength behave as though <code>ref</code> were truncated to this length without actually having to do it
	 */
	private static <T> void buildDottedFieldNameOf(Reference<T> ref, int startLength, int refLength, ArrayList<String> segments) {
		if (ref.path().length() > startLength) {
			Reference<?> enclosingReference = enclosingReference(ref);
			buildDottedFieldNameOf(enclosingReference, startLength, refLength, segments);
			if (ref.path().length() <= refLength) {
				if (Listing.class.isAssignableFrom(enclosingReference.targetClass())) {
					segments.add("ids");
				} else if (SideTable.class.isAssignableFrom(enclosingReference.targetClass())) {
					segments.add("valuesById");
				}
				segments.add(dottedFieldNameSegment(ref.path().lastSegment()));
			}
		}
	}

	static String dottedFieldNameSegment(String segment) {
		return ENCODER.apply(segment);
	}

	static String undottedFieldNameSegment(String dottedSegment) {
		return DECODER.apply(dottedSegment);
	}

	/**
	 * @return Reference corresponding to the given field name
	 * @see #dottedFieldNameOf
	 */
	@SuppressWarnings("unchecked")
	static <T> Reference<T> referenceTo(String dottedName, Reference<?> startingReference) throws InvalidTypeException {
		Reference<?> ref = startingReference;
		Iterator<String> iter = Arrays.asList(dottedName.split(Pattern.quote("."))).iterator();
		skipField(ref, iter, DocumentFields.state.name()); // The entire Bosk state is in this field
		while (iter.hasNext()) {
			if (Listing.class.isAssignableFrom(ref.targetClass())) {
				skipField(ref, iter, "ids");
			} else if (SideTable.class.isAssignableFrom(ref.targetClass())) {
				skipField(ref, iter, "valuesById");
			}
			if (iter.hasNext()) {
				String segment = undottedFieldNameSegment(iter.next());
				ref = ref.then(Object.class, segment);
			}
		}
		return (Reference<T>) ref;
	}

	private static void skipField(Reference<?> ref, Iterator<String> iter, String expectedName) {
		String actualName;
		try {
			actualName = iter.next();
		} catch (NoSuchElementException e) {
			throw new IllegalStateException("Expected '" + expectedName + "' for " + ref.targetClass().getSimpleName() + "; encountered end of dotted field name");
		}
		if (!expectedName.equals(actualName)) {
			throw new IllegalStateException("Expected '" + expectedName + "' for " + ref.targetClass().getSimpleName() + "; was: " + actualName);
		}
	}

	/**
	 * If the reference is not a root reference, it always has an enclosing reference
	 * conforming to Object, so this can't throw. Eat the InvalidTypeException.
	 */
	static <T> Reference<?> enclosingReference(Reference<T> ref) {
		assert !ref.path().isEmpty();
		try {
			return ref.enclosingReference(Object.class);
		} catch (InvalidTypeException e) {
			throw new AssertionError(format("Reference must have an enclosing Object: '%s'", ref), e);
		}
	}

	private static final UnaryOperator<String> DECODER;
	private static final UnaryOperator<String> ENCODER;

	static {
		DECODER = s->{
			try {
				return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
			} catch (UnsupportedEncodingException e) {
				throw new AssertionError(e);
			}
		};

		ENCODER = s->{
			// Selective percent-encoding of characters MongoDB doesn't like.
			// Standard percent-encoding doesn't handle the period character, which
			// we want, so if we're already diverging from the standard, we might
			// as well do something that suits our needs.
			// Good to stay compatible with standard percent-DEcoding, though.
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < s.length(); ) {
				int cp = s.codePointAt(i);
				switch (cp) {
					case '%': // For percent-encoding
					case '+': case ' ': // These two are affected by URLDecoder
					case '$': // MongoDB treats these specially
					case '.': // MongoDB separator for dotted field names
					case 0:   // Can MongoDB handle nulls? Probably. Do we want to find out? Not really.
					case '|': // (These are reserved for internal use)
					case '!':
					case '~':
					case '[':
					case ']':
						appendPercentEncoded(sb, cp);
						break;
					default:
						sb.appendCodePoint(cp);
						break;
				}
				i += Character.charCount(cp);
			}
			return sb.toString();
		};
	}

	private static void appendPercentEncoded(StringBuilder sb, int cp) {
		assert 0 <= cp && cp <= 255;
		sb
			.append('%')
			.append(hexCharForDigit(cp / 16))
			.append(hexCharForDigit(cp % 16));
	}

	/**
	 * An uppercase version of {@link Character#forDigit} with a radix of 16.
	 */
	private static char hexCharForDigit(int value) {
		if (value < 10) {
			return (char)('0' + value);
		} else {
			return (char)('A' + value - 10);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(Formatter.class);
}
