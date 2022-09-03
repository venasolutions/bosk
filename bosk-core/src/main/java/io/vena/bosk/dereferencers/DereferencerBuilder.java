package io.vena.bosk.dereferencers;

import io.vena.bosk.Path;
import java.lang.reflect.Type;

interface DereferencerBuilder {
	Type targetType();
	Path fullyParameterizedPath();
	Dereferencer buildInstance();
}
