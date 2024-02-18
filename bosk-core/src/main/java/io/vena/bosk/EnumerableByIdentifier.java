package io.vena.bosk;

import java.util.List;

sealed public interface EnumerableByIdentifier<T> extends AddressableByIdentifier<T> permits Catalog, SideTable {
	List<Identifier> ids();
}
