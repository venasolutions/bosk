package org.vena.bosk;

import static java.util.Arrays.asList;

public interface MappingReference<K extends Entity, V> extends Reference<Mapping<K,V>> {
	Reference<V> then(Identifier id);
	Reference<V> then(K key);

	@Override MappingReference<K, V> boundBy(BindingEnvironment bindings);

	@Override default MappingReference<K,V> boundBy(Path definitePath) { return this.boundBy(parametersFrom(definitePath)); }
	@Override default MappingReference<K,V> boundTo(Identifier... ids) { return this.boundBy(path().parametersFrom(asList(ids))); }

	Class<K> keyClass();
	Class<V> valueClass();
}
