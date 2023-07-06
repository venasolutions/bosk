package io.vena.hello;

import java.util.List;

public record GreetingDTO(
	List<String> greetings
) { }
