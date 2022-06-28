package org.vena.bosk.dereferencers;

import java.lang.reflect.Type;
import org.vena.bosk.Path;

interface DereferencerBuilder {
	Type targetType();
	Path fullyParameterizedPath();
	Dereferencer buildInstance();
}
