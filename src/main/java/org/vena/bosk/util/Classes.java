package org.vena.bosk.util;

import org.vena.bosk.Catalog;
import org.vena.bosk.CatalogReference;
import org.vena.bosk.Entity;
import org.vena.bosk.EnumerableByIdentifier;
import org.vena.bosk.ListValue;
import org.vena.bosk.Listing;
import org.vena.bosk.ListingReference;
import org.vena.bosk.MapValue;
import org.vena.bosk.Mapping;
import org.vena.bosk.MappingReference;
import org.vena.bosk.Reference;

/**
 * An imperfect, non-idiomatic way to describe complex parameterized types.
 *
 * <p>
 * To represent the type <code>Mapping&lt;X, Listing&lt;Y>></code>, for example,
 * write <code>mapping(X.class, listing(Y.class))</code>.
 */
public abstract class Classes {
	@SuppressWarnings({"unchecked","rawtypes","unused"})
	public static <E extends Entity> Class<Catalog<E>> catalog(Class<E> entryClass) {
		return (Class)Catalog.class;
	}

	@SuppressWarnings({"unchecked","rawtypes","unused"})
	public static <E extends Entity> Class<Listing<E>> listing(Class<E> entryClass) {
		return (Class)Listing.class;
	}

	@SuppressWarnings({"unchecked","rawtypes","unused"})
	public static <K extends Entity,V> Class<Mapping<K,V>> mapping(Class<K> keyClass, Class<V> valueClass) {
		return (Class)Mapping.class;
	}

	@SuppressWarnings({"unchecked","rawtypes","unused"})
	public static <E extends Entity> Class<EnumerableByIdentifier<E>> enumerableByIdentifier(Class<E> entryClass) {
		return (Class) EnumerableByIdentifier.class;
	}

	@SuppressWarnings({"unchecked","rawtypes","unused"})
	public static <T> Class<Reference<T>> reference(Class<T> targetClass) {
		return (Class)Reference.class;
	}

	@SuppressWarnings({"unchecked","rawtypes","unused"})
	public static <E extends Entity> Class<CatalogReference<E>> catalogReference(Class<E> targetClass) {
		return (Class)CatalogReference.class;
	}

	@SuppressWarnings({"unchecked","rawtypes","unused"})
	public static <E extends Entity> Class<ListingReference<E>> listingReference(Class<E> targetClass) {
		return (Class)ListingReference.class;
	}

	@SuppressWarnings({"unchecked","rawtypes","unused"})
	public static <K extends Entity,V> Class<MappingReference<K,V>> mappingReference(Class<K> keyClass, Class<V> valueClass) {
		return (Class)MappingReference.class;
	}

	@SuppressWarnings({"unchecked","rawtypes","unused"})
	public static <E> Class<ListValue<E>> listValue(Class<E> entryClass) {
		return (Class)ListValue.class;
	}

	@SuppressWarnings({"unchecked","rawtypes","unused"})
	public static <V> Class<MapValue<V>> mapValue(Class<V> valueClass) {
		return (Class)MapValue.class;
	}

}
