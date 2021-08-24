package org.vena.bosk;

import static java.util.Arrays.asList;

public interface ListingReference<E extends Entity> extends Reference<Listing<E>> {
	Reference<ListingEntry> then(Identifier id);
	default Class<ListingEntry> entryClass() { return ListingEntry.class; }

	@Override ListingReference<E> boundBy(BindingEnvironment bindings);
	@Override default ListingReference<E> boundBy(Path definitePath) { return this.boundBy(parametersFrom(definitePath)); }
	@Override default ListingReference<E> boundTo(Identifier... ids) { return this.boundBy(path().parametersFrom(asList(ids))); }

}
