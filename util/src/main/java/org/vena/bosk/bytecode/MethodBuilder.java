package org.vena.bosk.bytecode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class MethodBuilder {
	final Method method;
	final MethodVisitor methodVisitor;
	final int numParameters;
	private int stackDepth = 0;
	private int numLocals;

	MethodBuilder(Method method, String signature, ClassVisitor classVisitor) {
		this.method = method;
		this.numLocals = this.numParameters = method.getParameterCount() + ((Modifier.isStatic(method.getModifiers())) ? 0 : 1);
		this.methodVisitor = classVisitor.visitMethod(Opcodes.ACC_PUBLIC, method.getName(), Type.getMethodDescriptor(method), signature, null);
	}

	void buildMethod() {
		if (stackDepth != 1) {
			throw new IllegalStateException("Expected one item on operand stack; found " + stackDepth);
		}
		methodVisitor.visitInsn(Opcodes.ARETURN);
		methodVisitor.visitMaxs(0, 0); // Computed automatically
		methodVisitor.visitEnd();
	}

	void pushSlots(int numSlots) {
		int newStackDepth = Math.addExact(this.stackDepth, numSlots);
		if (newStackDepth < 0) {
			throw new IllegalStateException("Too many items popped off stack");
		}
		this.stackDepth = newStackDepth;
	}

	void popSlots(int numSlots) {
		pushSlots(-numSlots);
	}

	LocalVariable newLocal() {
		return new LocalVariable(++numLocals);
	}

}
