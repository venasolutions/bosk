package org.vena.bosk;

import org.vena.bosk.exceptions.InvalidTypeException;

import static java.util.Arrays.asList;

/**
 * A convenience interface equivalent to <code>Reference&lt;Mapping&lt;K,V>></code>
 * but avoids throwing {@link InvalidTypeException} from some methods that are known
 * to be type-safe, like {@link #then(Identifier) then}.
 */
public interface MappingReference<K extends Entity, V> extends Reference<Mapping<K,V>> {
	Reference<V> then(Identifier id);
	Reference<V> then(K key);

	@Override MappingReference<K, V> boundBy(BindingEnvironment bindings);

	@Override default MappingReference<K,V> boundBy(Path definitePath) { return this.boundBy(parametersFrom(definitePath)); }
	@Override default MappingReference<K,V> boundTo(Identifier... ids) { return this.boundBy(path().parametersFrom(asList(ids))); }

	Class<K> keyClass();
	Class<V> valueClass();
}
