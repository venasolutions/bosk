package io.vena.bosk;

import io.vena.bosk.exceptions.InvalidTypeException;

import static io.vena.bosk.ReferenceUtils.parameterType;
import static io.vena.bosk.ReferenceUtils.rawClass;
import static java.util.Arrays.asList;

/**
 * A convenience interface equivalent to <code>Reference&lt;Catalog&lt;E>></code>
 * but avoids throwing {@link InvalidTypeException} from some methods that are known
 * to be type-safe, like {@link #then(Identifier) then}.
 */
public sealed interface CatalogReference<E extends Entity>
	extends Reference<Catalog<E>>
	permits ReferenceUtils.CatalogRef {
	/**
	 * @return {@link Reference} to the catalog entry with the given <code>id</code>.
	 */
	Reference<E> then(Identifier id);
	Class<E> entryClass();

	@Override CatalogReference<E> boundBy(BindingEnvironment bindings);
	@Override default CatalogReference<E> boundBy(Path definitePath) { return this.boundBy(parametersFrom(definitePath)); }
	@Override default CatalogReference<E> boundTo(Identifier... ids) { return this.boundBy(path().parametersFrom(asList(ids))); }

	/**
	 * <code>CatalogReference&lt;TT></code> has extra special superpowers that
	 * <code>Reference&lt;Catalog&lt;TT>></code> doesn't possess.
	 */
	static <TT extends Entity> CatalogReference<TT> from(Reference<Catalog<TT>> plainReference) {
		@SuppressWarnings("unchecked")
		Class<TT> ttClass = (Class<TT>) rawClass(parameterType(plainReference.targetType(), Catalog.class, 0));
		try {
			return plainReference.thenCatalog(ttClass);
		} catch (InvalidTypeException e) {
			throw new AssertionError("Any Reference to a Catalog can become a CatalogReference", e);
		}
	}

}
