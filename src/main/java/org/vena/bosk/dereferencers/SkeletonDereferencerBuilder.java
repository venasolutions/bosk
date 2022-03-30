package org.vena.bosk.dereferencers;

import java.lang.reflect.Method;
import org.vena.bosk.Reference;
import org.vena.bosk.bytecode.ClassBuilder;

/**
 * The skeleton of a builder for {@link Dereferencer} objects. Creates a new class,
 * declares its methods, and calls a sequence of abstract methods to generate the
 * method bodies. Provides protected utility methods that can be called to generate
 * the desired bytecodes for the method bodies.
 *
 * <p>
 * By "skeleton" here we really mean the GoF "Template Method" pattern inside
 * {@link #buildInstance()}}.
 */
abstract class SkeletonDereferencerBuilder implements DereferencerBuilder {
	protected final ClassBuilder<Dereferencer> cb;

	public SkeletonDereferencerBuilder(String className, ClassLoader parentClassLoader, StackTraceElement sourceFileOrigin) {
		this.cb = new ClassBuilder<>(className, DereferencerRuntime.class, parentClassLoader, sourceFileOrigin);
	}

	protected abstract void generate_get();
	protected abstract void generate_with();
	protected abstract void generate_without();

	@Override
	public Dereferencer buildInstance() {
		cb.beginClass();

		cb.beginMethod(DEREFERENCER_GET);
		generate_get();
		cb.finishMethod();

		cb.beginMethod(DEREFERENCER_WITH);
		generate_with();
		cb.finishMethod();

		cb.beginMethod(DEREFERENCER_WITHOUT);
		generate_without();
		cb.finishMethod();

		return cb.buildInstance();
	}

	/**
	 * Pushes the bosk root object onto the operand stack, and typecasts it to the given class.
	 */
	protected final void pushSourceObject(Class<?> expectedType) {
		cb.pushLocal(cb.parameter(1));
		cb.castTo(expectedType);
	}

	/**
	 * Pushes the {@link Reference} object onto the operand stack.
	 */
	protected final void pushReference() {
		cb.pushLocal(cb.parameter(2));
	}

	/**
	 * For {@link Dereferencer#with}, pushes the <code>newValue</code> object onto the operand stack,
	 * and typecasts it to the given class.
	 */
	protected final void pushNewValueObject(Class<?> expectedType) {
		cb.pushLocal(cb.parameter(3));
		cb.castTo(expectedType);
	}

	/**
	 * Pushes a copy of the top operand stack value.
	 */
	protected final void dup() { cb.dup(); }

	/**
	 * Reverses the order of the top two operands on the stack.
	 */
	protected final void swap() { cb.swap(); }

	/**
	 * Discards the top operand on the stack.
	 */
	protected final void pop() { cb.pop(); }

	/**
	 * Treats the top value on the stack as the given type.
	 */
	protected final void castTo(Class<?> expectedType) {
		cb.castTo(expectedType);
	}

	/**
	 * Pushes the given value onto the operand stack.
	 */
	protected final void pushInt(int value) {
		cb.pushInt(value);
	}

	/**
	 * Invokes the given method.
	 */
	protected final void invoke(Method method) {
		cb.invoke(method);
	}

	/**
	 * Pushes the result of calling <code>reference.{@link Reference#idAt idAt}(segmentNum)</code>.
	 *
	 * <p>
	 * Equivalent to:
	 *
	 * <pre>
	 * pushReference();
	 * pushInt(segmentNum);
	 * invoke(REFERENCE_ID_AT);
	 * </pre>
	 */
	protected final void pushIdAt(int segmentNum) {
		pushReference();
		pushInt(segmentNum);
		invoke(REFERENCE_ID_AT);
	}

	static {
		try {
			REFERENCE_ID_AT = Reference.class.getDeclaredMethod("idAt", int.class);

			DEREFERENCER_GET = Dereferencer.class.getDeclaredMethod("get", Object.class, Reference.class);
			DEREFERENCER_WITH = Dereferencer.class.getDeclaredMethod("with", Object.class, Reference.class, Object.class);
			DEREFERENCER_WITHOUT = Dereferencer.class.getDeclaredMethod("without", Object.class, Reference.class);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private final static Method REFERENCE_ID_AT, DEREFERENCER_GET, DEREFERENCER_WITH, DEREFERENCER_WITHOUT;
}
