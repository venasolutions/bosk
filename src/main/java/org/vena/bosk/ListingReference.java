package org.vena.bosk;

import org.vena.bosk.exceptions.InvalidTypeException;

import static java.util.Arrays.asList;

/**
 * A convenience interface equivalent to <code>Reference&lt;Listing&lt;E>></code>
 * but avoids throwing {@link InvalidTypeException} from some methods that are known
 * to be type-safe, like {@link #then(Identifier) then}.
 */
public interface ListingReference<E extends Entity> extends Reference<Listing<E>> {
	/**
	 * @return {@link Reference} to the {@link ListingEntry} representing the entry with the given id.
	 */
	Reference<ListingEntry> then(Identifier id);
	default Class<ListingEntry> entryClass() { return ListingEntry.class; }

	@Override ListingReference<E> boundBy(BindingEnvironment bindings);
	@Override default ListingReference<E> boundBy(Path definitePath) { return this.boundBy(parametersFrom(definitePath)); }
	@Override default ListingReference<E> boundTo(Identifier... ids) { return this.boundBy(path().parametersFrom(asList(ids))); }

}
