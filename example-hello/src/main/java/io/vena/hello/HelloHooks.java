package io.vena.hello;

import io.vena.bosk.Reference;
import io.vena.bosk.annotations.Hook;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.hello.state.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HelloHooks {
	public HelloHooks(HelloBosk bosk) throws InvalidTypeException {
		bosk.registerHooks(this);
	}

	@Hook("/targets/-target-")
	void targetChanged(Reference<Target> ref) {
		if (ref.exists()) {
			LOGGER.info("Target: {}", ref.value());
		} else {
			LOGGER.info("Target removed: {}", ref);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(HelloHooks.class);
}
