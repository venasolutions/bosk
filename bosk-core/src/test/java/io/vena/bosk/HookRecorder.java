package io.vena.bosk;

import java.util.ArrayList;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(fluent = true)
class HookRecorder {
	@Getter
	private final List<Event> events = new ArrayList<>();

	/**
	 * Call if you want to record just the events that happen after a certain point.
	 */
	void restart() {
		events.clear();
	}

	@RequiredArgsConstructor
	@EqualsAndHashCode
	static class Event {
		final String hookName;
		final Event.Kind kind;
		final Reference<?> scope;
		final Object value;

		enum Kind {CHANGED}

		@Override
		public String toString() {
			// Start with a newline to make diffs easier to read if there are test failures
			return "\n" + kind + "(" + hookName + ":" + scope + (EVENT_STRINGS_INCLUDE_VALUE ? "=" + value : "") + ")";
		}
	}

	/**
	 * Usually the value is just too much info, but sometimes it can help diagnose errors.
	 */
	private static final boolean EVENT_STRINGS_INCLUDE_VALUE = true;

	public <T> BoskHook<T> hookNamed(String name) {
		return hookNamed(name, x -> {
		});
	}

	public <T> BoskHook<T> hookNamed(String name, BoskHook<T> body) {
		return reference -> {
			events.add(new Event(name, Event.Kind.CHANGED, reference, reference.valueIfExists()));
			body.onChanged(reference);
		};
	}
}
