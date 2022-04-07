package org.vena.bosk.drivers.mongo;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.bson.BsonDocument;
import org.bson.BsonDocumentWriter;
import org.bson.BsonReader;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodecProvider;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.ValueCodecProvider;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.vena.bosk.Bosk;
import org.vena.bosk.BsonPlugin;
import org.vena.bosk.Listing;
import org.vena.bosk.Reference;
import org.vena.bosk.SerializationPlugin;
import org.vena.bosk.SideTable;
import org.vena.bosk.exceptions.InvalidTypeException;

import static java.lang.String.format;
import static org.vena.bosk.ReferenceUtils.rawClass;

/**
 * Facilities to translate between in-DB and in-memory representations.
 *
 * @author pdoyle
 */
final class Formatter {
	private final CodecRegistry simpleCodecs;
	private final Function<Type, Codec<?>> preferredBoskCodecs;
	private final Function<Reference<?>, SerializationPlugin.DeserializationScope> deserializationScopeFunction;

	Formatter(Bosk<?> bosk, BsonPlugin bsonPlugin) {
		this.simpleCodecs = CodecRegistries.fromProviders(bsonPlugin.codecProviderFor(bosk), new ValueCodecProvider(), new DocumentCodecProvider());
		this.preferredBoskCodecs = type -> bsonPlugin.getCodec(type, rawClass(type), simpleCodecs, bosk);
		this.deserializationScopeFunction = bsonPlugin::newDeserializationScope;
	}

	/**
	 * The fields of the main MongoDB document.  Case-sensitive.
	 *
	 * No field name should be a prefix of any other.
	 */
	enum DocumentFields {
		state,
		echo,
	}

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
	<T> T document2object(Document document, Reference<T> target) {
		BsonDocument doc = document.toBsonDocument(BsonDocument.class, simpleCodecs);
		Type type = target.targetType();
		Class<T> objectClass = (Class<T>) rawClass(type);
		Codec<T> objectCodec = (Codec<T>) codecFor(type);
		try (@SuppressWarnings("unused") BsonPlugin.DeserializationScope scope = deserializationScopeFunction.apply(target)) {
			return objectClass.cast(objectCodec.decode(doc.asBsonReader(), DecoderContext.builder().build()));
		}
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

	/**
	 * @return MongoDB field name corresponding to the given Reference
	 * @see #referenceTo(String, Reference)
	 */
	static <T> String dottedFieldNameOf(Reference<T> ref, Reference<?> startingRef) {
		assert startingRef.path().isPrefixOf(ref.path()): "'" + ref + "' must be under '" + startingRef + "'";
		ArrayList<String> segments = new ArrayList<>();
		segments.add(DocumentFields.state.name());
		buildDottedFieldNameOf(ref, startingRef.path().length(), segments);
		return String.join(".", segments.toArray(new String[0]));
	}

	private static <T> void buildDottedFieldNameOf(Reference<T> ref, int startingRefLength, ArrayList<String> segments) {
		if (ref.path().length() > startingRefLength) {
			Reference<?> enclosingReference = enclosingReference(ref);
			buildDottedFieldNameOf(enclosingReference, startingRefLength, segments);
			if (Listing.class.isAssignableFrom(enclosingReference.targetClass())) {
				segments.add("ids");
			} else if (SideTable.class.isAssignableFrom(enclosingReference.targetClass())) {
				segments.add("valuesById");
			}
			segments.add(ref.path().lastSegment());
		}
	}

	/**
	 * @return Reference corresponding to the given field name
	 * @see #dottedFieldNameOf(Reference, Reference)
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
				String segment = iter.next();
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

}
