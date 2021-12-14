package org.vena.bosk;

import java.util.List;

public interface EnumerableByIdentifier<T> extends AddressableByIdentifier<T> {
	List<Identifier> ids();
}
