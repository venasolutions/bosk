package io.vena.bosk.drivers;

import io.vena.bosk.BoskDiagnosticContext;
import io.vena.bosk.BoskDiagnosticContext.DiagnosticScope;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;

import static lombok.AccessLevel.PRIVATE;

/**
 * Automatically sets a {@link DiagnosticScope} around each driver operation based on a user-supplied function.
 * Allows diagnostic context to be supplied automatically to every operation.
 */
@RequiredArgsConstructor(access = PRIVATE)
public class DiagnosticScopeDriver<R extends StateTreeNode> implements BoskDriver<R> {
	final BoskDriver<R> downstream;
	final BoskDiagnosticContext diagnosticContext;
	final Function<BoskDiagnosticContext, DiagnosticScope> scopeSupplier;

	public static <RR extends StateTreeNode> DriverFactory<RR> factory(Function<BoskDiagnosticContext, DiagnosticScope> scopeSupplier) {
		return (b,d) -> new DiagnosticScopeDriver<>(d, b.diagnosticContext(), scopeSupplier);
	}

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		try (var __ = scopeSupplier.apply(diagnosticContext)) {
			return downstream.initialRoot(rootType);
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		try (var __ = scopeSupplier.apply(diagnosticContext)) {
			downstream.submitReplacement(target, newValue);
		}
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		try (var __ = scopeSupplier.apply(diagnosticContext)) {
			downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
		}
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		try (var __ = scopeSupplier.apply(diagnosticContext)) {
			downstream.submitInitialization(target, newValue);
		}
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		try (var __ = scopeSupplier.apply(diagnosticContext)) {
			downstream.submitDeletion(target);
		}
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		try (var __ = scopeSupplier.apply(diagnosticContext)) {
			downstream.submitConditionalDeletion(target, precondition, requiredValue);
		}
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		try (var __ = scopeSupplier.apply(diagnosticContext)) {
			downstream.flush();
		}
	}
}
