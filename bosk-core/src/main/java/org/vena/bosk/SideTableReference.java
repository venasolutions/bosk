package org.vena.bosk;

import org.vena.bosk.exceptions.InvalidTypeException;

import static java.util.Arrays.asList;

/**
 * A convenience interface equivalent to <code>Reference&lt;SideTable&lt;K,V>></code>
 * but avoids throwing {@link InvalidTypeException} from some methods that are known
 * to be type-safe, like {@link #then(Identifier) then}.
 */
public interface SideTableReference<K extends Entity, V> extends Reference<SideTable<K,V>> {
	/**
	 * @return {@link Reference} to the value of the entry whose key has the given <code>id</code>.
	 */
	Reference<V> then(Identifier id);

	/**
	 * @return {@link Reference} to the value of the entry with the given <code>key</code>.
	 */
	Reference<V> then(K key);

	@Override
	SideTableReference<K, V> boundBy(BindingEnvironment bindings);

	@Override default SideTableReference<K,V> boundBy(Path definitePath) { return this.boundBy(parametersFrom(definitePath)); }
	@Override default SideTableReference<K,V> boundTo(Identifier... ids) { return this.boundBy(path().parametersFrom(asList(ids))); }

	Class<K> keyClass();
	Class<V> valueClass();
}
