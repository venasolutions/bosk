package io.vena.bosk.annotations;

import io.vena.bosk.SerializationPlugin;
import io.vena.bosk.SerializationPlugin.DeserializationScope;
import io.vena.bosk.StateTreeNode;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * On a field of a {@link StateTreeNode}, indicates that implicit references
 * enclosed by that field should be constructed using the supplied path string as a prefix.
 *
 * <p>
 * For example:
 *
 * <pre>
 *     public class MyDTO implements StateTreeNode {
 *        &#64DeserializationPath("a/b/c")
 *        MyObject field;
 *     }
 *
 *     public class MyObject extends ReflectiveEntity&lt;MyObject> {
 *         Reference&lt;MyObject> self;
 *         Optional&lt;MyObject> nested;
 *     }
 * </pre>
 *
 * If we deserialize an instance <code>x</code> of <code>MyDTO</code>, then
 * the reference <code>x.field.self</code> will have a path of <code>"a/b/c"</code>,
 * and <code>x.field.nested.get().self</code> will have a path of <code>"a/b/c/nested"</code>.
 *
 * <p>
 * If the path contains parameters, their values will be taken from the
 * {@link DeserializationScope#bindingEnvironment binding environment} on the
 * {@link DeserializationScope deserialization scope}, which can be set using
 * {@link SerializationPlugin#overlayScope}.
 */
@Retention(RUNTIME)
@Target({ FIELD, PARAMETER })
public @interface DeserializationPath {
	String value();
}
