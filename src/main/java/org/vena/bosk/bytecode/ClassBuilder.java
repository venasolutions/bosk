package org.vena.bosk.bytecode;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import static java.lang.reflect.Modifier.isStatic;
import static java.security.AccessController.doPrivileged;
import static java.util.stream.Collectors.joining;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.Type.getMethodDescriptor;
import static org.vena.bosk.ReferenceUtils.setAccessible;

/**
 * Wrapper around {@link ClassWriter} to simplify things for our purposes.
 * Users of this should not need to import anything from org.objectweb.asm.
 *
 * @param <T> The interface class that resulting class will implement.
 */
public final class ClassBuilder<T> implements Opcodes {
	private final Class<? extends T> supertype;
	private final String superClassName;
	private final String slashyName;
	private final String dottyName;
	private final StackTraceElement sourceFileOrigin; // Where this ClassBuilder was instantiated
	private ClassWriter classWriter = null;
	private MethodBuilder currentMethod = null;

	private final List<CurriedField> curriedFields = new ArrayList<>();

	/**
	 * @param className The name of the generated class
	 * @param supertype A superclass or interface for the generated class to inherit
	 * @param sourceFileOrigin Indicates the package in which the generated class should reside, and
	 *                         the source file to which all debug line number information should refer.
	 */
	public ClassBuilder(String className, Class<? extends T> supertype, StackTraceElement sourceFileOrigin) {
		this.supertype = supertype;
		if (supertype.isInterface()) {
			superClassName = Type.getInternalName(Object.class);
		} else {
			superClassName = Type.getInternalName(supertype);
		}
		String sourceDottyName = sourceFileOrigin.getClassName();
		this.dottyName = sourceDottyName.substring(0, sourceDottyName.lastIndexOf('.')) + ".GENERATED_" + className;
		this.slashyName = dottyName.replace('.', '/');
		this.sourceFileOrigin = sourceFileOrigin;
	}

	public void beginClass() {
		String[] interfaces;
		if (supertype.isInterface()) {
			interfaces = new String[]{Type.getInternalName(supertype)};
		} else {
			interfaces = new String[0];
		}
		this.classWriter = new ClassWriter(COMPUTE_FRAMES);
		classWriter.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, slashyName, null, superClassName, interfaces);
		classWriter.visitSource(sourceFileOrigin.getFileName(), null);
	}

	private void generateConstructor(StackTraceElement sourceFileOrigin) {
		String ctorParameterDescriptor = curriedFields.stream()
			.map(CurriedField::typeDescriptor)
			.collect(joining());
		MethodVisitor ctor = classWriter.visitMethod(ACC_PUBLIC, "<init>", "(" + ctorParameterDescriptor + ")V", null, null);
		ctor.visitCode();
		Label label = new Label();
		ctor.visitLabel(label);
		ctor.visitLineNumber(sourceFileOrigin.getLineNumber(), label);
		ctor.visitVarInsn(ALOAD, 0);
		ctor.visitMethodInsn(INVOKESPECIAL, superClassName, "<init>", "()V", false);
		for (CurriedField field: curriedFields) {
			ctor.visitVarInsn(ALOAD, 0);
			ctor.visitVarInsn(ALOAD, field.slot());
			ctor.visitFieldInsn(PUTFIELD, slashyName, field.name(), field.typeDescriptor());
		}
		ctor.visitInsn(RETURN);
		ctor.visitMaxs(0, 0); // Computed automatically
		ctor.visitEnd();
	}

	public static StackTraceElement here() {
		return new Exception().getStackTrace()[1];
	}

	public void beginMethod(Method method) {
		if (currentMethod == null) {
			currentMethod = new MethodBuilder(method, getMethodDescriptor(method), classWriter);
		} else {
			throw new IllegalStateException("Method already in progress: " + currentMethod.method);
		}
	}

	public void finishMethod() {
		currentMethod.buildMethod();
		currentMethod = null;
	}

	/**
	 * Emit a CHECKCAST: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.checkcast
	 */
	public void castTo(Class<?> expectedType) {
		emitLineNumberInfo();
		methodVisitor().visitTypeInsn(CHECKCAST, Type.getInternalName(expectedType));
	}

	public LocalVariable parameter(int index) {
		if (0 <= index && index < currentMethod.numParameters) {
			return new LocalVariable(index);
		} else {
			throw new IllegalStateException("No parameter #" + index);
		}
	}

	/**
	 * Emit ALOAD: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.aload
	 */
	public void pushLocal(LocalVariable var) {
		beginPush();
		methodVisitor().visitVarInsn(ALOAD, var.slot());
	}

	/**
	 * Emit ASTORE: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.astore
	 */
	public LocalVariable popToLocal() {
		LocalVariable result = currentMethod.newLocal();
		methodVisitor().visitVarInsn(ASTORE, result.slot());
		endPop(1);
		return result;
	}

	public CurriedField curry(Object object) {
		for (CurriedField candidate: curriedFields) {
			if (candidate.value() == object) {
				return candidate;
			}
		}

		int ctorParameterSlot = 1 + curriedFields.size();
		CurriedField result = new CurriedField(
			ctorParameterSlot,
			"CURRIED" + ctorParameterSlot + "_" + object.getClass().getSimpleName(),
			Type.getDescriptor(object.getClass()),
			object);
		curriedFields.add(result);

		classWriter.visitField(
			ACC_PRIVATE | ACC_FINAL,
			result.name(),
			result.typeDescriptor(),
			null, null
		).visitEnd();

		return result;
	}

	/**
	 * Emit GETFIELD
	 */
	public void pushField(CurriedField field) {
		beginPush();
		methodVisitor().visitVarInsn(ALOAD, 0);
		methodVisitor().visitFieldInsn(GETFIELD, slashyName, field.name(), field.typeDescriptor());
	}

	/**
	 * Emit LDC: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.ldc
	 */
	public void pushInt(int value) {
		beginPush();
		methodVisitor().visitLdcInsn(value);
	}

	/**
	 * Emit LDC: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.ldc
	 */
	public void pushString(String value) {
		beginPush();
		methodVisitor().visitLdcInsn(value);
	}

	/**
	 * Emit DUP: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.dup
	 */
	public void dup() {
		beginPush();
		methodVisitor().visitInsn(DUP);
	}

	/**
	 * Emit SWAP: https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.swap
	 */
	public void swap() {
		methodVisitor().visitInsn(SWAP);
	}

	/**
	 * Pop the top item off the operand stack
	 */
	public void pop() {
		methodVisitor().visitInsn(POP);
		endPop(1);
	}

	/**
	 * Emit the appropriate INVOKE instruction for the given Method.
	 */
	public void invoke(Method method) {
		setAccessible(method); // Hmm, we seem to get IllegalAccessError even after doing this
		emitLineNumberInfo();
		Class<?> type = method.getDeclaringClass();
		String typeName = Type.getInternalName(type);
		String methodName = method.getName();
		String signature = getMethodDescriptor(method);
		int resultSlots = (method.getReturnType() == void.class) ? 0 : 1; // TODO: Won't work for long or double
		if (isStatic(method.getModifiers())) {
			methodVisitor().visitMethodInsn(INVOKESTATIC, typeName, methodName, signature, false);
			endPop(method.getParameterCount() - resultSlots);
		} else if (type.isInterface()) {
			methodVisitor().visitMethodInsn(INVOKEINTERFACE, typeName, methodName, signature, true);
			endPop(1 + method.getParameterCount() - resultSlots);
		} else {
			methodVisitor().visitMethodInsn(INVOKEVIRTUAL, typeName, methodName, signature, false);
			endPop(1 + method.getParameterCount() - resultSlots);
		}
	}

	/**
	 * Emit INVOKESPECIAL for the given Constructor.
	 */
	public void invoke(Constructor<?> ctor) {
		setAccessible(ctor); // Hmm, we seem to get IllegalAccessError even after doing this
		emitLineNumberInfo();
		String typeName = Type.getInternalName(ctor.getDeclaringClass());
		Type[] parameterTypes = Stream.of(ctor.getParameterTypes()).map(Type::getType).toArray(Type[]::new);
		String signature = getMethodDescriptor(Type.getType(void.class), parameterTypes);
		methodVisitor().visitMethodInsn(INVOKESPECIAL, typeName, "<init>", signature, false);
		endPop(ctor.getParameterCount());
	}

	/**
	 * Emit NEW for the given class.
	 */
	public void instantiate(Class<?> type) {
		methodVisitor().visitTypeInsn(NEW, Type.getInternalName(type));
	}

	private void branchAround(Runnable action, int opcode, int poppedSlots) {
		Label label = new Label();
		methodVisitor().visitJumpInsn(opcode, label);
		endPop(poppedSlots);
		action.run();
		methodVisitor().visitLabel(label);
	}

	public void ifFalse(Runnable action) { branchAround(action, IFNE, 1); }
	public void ifTrue(Runnable action) { branchAround(action, IFEQ, 1); }

	/**
	 * Finish building the class, load it with its own ClassLoader, and instantiate it.
	 * @return A new instance of the class.
	 */
	public T buildInstance() {
		generateConstructor(sourceFileOrigin);
		classWriter.visitEnd();

		CustomClassLoader customClassLoader = doPrivileged((PrivilegedAction<CustomClassLoader>) CustomClassLoader::new);
		Class<?> instanceClass = customClassLoader.loadThemBytes(dottyName, classWriter.toByteArray());
		Constructor<?> ctor = instanceClass.getConstructors()[0];
		Object[] args = curriedFields.stream().map(CurriedField::value).toArray();
		try {
			return supertype.cast(ctor.newInstance(args));
		} catch (InstantiationException | IllegalAccessException | VerifyError | InvocationTargetException e) {
			throw new AssertionError("Should be able to instantiate the generated class", e);
		}
	}

	private void beginPush() {
		emitLineNumberInfo();
		currentMethod.pushSlots(1);
	}

	private void endPop(int count) {
		currentMethod.popSlots(count);
	}

	private void emitLineNumberInfo() {
		StackTraceElement bestFrame = sourceFileOrigin;

		// Try to find a more specific line number. Due to the limits of
		// Java's source line number info, it needs to be in the same file
		// as sourceFileOrigin; try to pick the deepest frame in that file.
		//
		String sourceFileName = sourceFileOrigin.getFileName();
		for (StackTraceElement frame: new Exception().getStackTrace()) {
			if (Objects.equals(sourceFileName, frame.getFileName())) {
				bestFrame = frame;
				break;
			}
		}

		Label label = new Label();
		methodVisitor().visitLabel(label);
		methodVisitor().visitLineNumber(bestFrame.getLineNumber(), label);
	}

	private MethodVisitor methodVisitor() {
		try {
			return currentMethod.methodVisitor;
		} catch (NullPointerException e) {
			throw new IllegalStateException("No method in progress");
		}
	}

	private final class CustomClassLoader extends ClassLoader {
		protected CustomClassLoader() {
			// Delegate to targetInterface.getClassLoader() to make sure our class
			// implements the right interface class.
			super(supertype.getClassLoader());
		}

		public Class<?> loadThemBytes(String dottyName, byte[] b) {
			return defineClass(dottyName, b, 0, b.length);
		}
	}
}
