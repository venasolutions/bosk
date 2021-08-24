package org.vena.bosk;

import java.util.List;

public interface AddressableByIdentifier<T> {
	List<Identifier> ids();
	T get(Identifier id);
}
