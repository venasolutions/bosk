package org.vena.bosk;

import org.vena.bosk.exceptions.InvalidTypeException;

import static java.util.Arrays.asList;
import static org.vena.bosk.ReferenceUtils.parameterType;
import static org.vena.bosk.ReferenceUtils.rawClass;

public interface CatalogReference<E extends Entity> extends Reference<Catalog<E>> {
	Reference<E> then(Identifier id);
	Class<E> entryClass();

	@Override CatalogReference<E> boundBy(BindingEnvironment bindings);
	@Override default CatalogReference<E> boundBy(Path definitePath) { return this.boundBy(parametersFrom(definitePath)); }
	@Override default CatalogReference<E> boundTo(Identifier... ids) { return this.boundBy(path().parametersFrom(asList(ids))); }

	/**
	 * <code>CatalogReference<TT></code> has extra special superpowers that
	 * <code>Reference<Catalog<TT>></code> doesn't possess.
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
