package org.vena.bosk.dereferencers;

import java.lang.reflect.Type;

interface DereferencerBuilder {
	Type targetType();
	Dereferencer buildInstance();
}
