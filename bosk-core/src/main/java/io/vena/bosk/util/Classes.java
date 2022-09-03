package io.vena.bosk.util;

import io.vena.bosk.Catalog;
import io.vena.bosk.CatalogReference;
import io.vena.bosk.Entity;
import io.vena.bosk.EnumerableByIdentifier;
import io.vena.bosk.ListValue;
import io.vena.bosk.Listing;
import io.vena.bosk.ListingReference;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import io.vena.bosk.SideTable;
import io.vena.bosk.SideTableReference;

/**
 * An imperfect, non-idiomatic way to describe complex parameterized types.
 *
 * <p>
 * To represent the type <code>SideTable&lt;X, Listing&lt;Y>></code>, for example,
 * write <code>sideTable(X.class, listing(Y.class))</code>.
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
	public static <K extends Entity,V> Class<SideTable<K,V>> sideTable(Class<K> keyClass, Class<V> valueClass) {
		return (Class) SideTable.class;
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
	public static <K extends Entity,V> Class<SideTableReference<K,V>> sideTableReference(Class<K> keyClass, Class<V> valueClass) {
		return (Class) SideTableReference.class;
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
