package org.vena.bosk.util;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;

public abstract class Types {
	public static ParameterizedType parameterizedType(Class<?> rawClass, Type... parameters) {
		return new ParameterizedType() {
			@Override public Type[] getActualTypeArguments() { return parameters; }
			@Override public Type getRawType() { return rawClass; }
			@Override public Type getOwnerType() { return rawClass.getEnclosingClass(); }

			@Override
			public boolean equals(Object obj) {
				if ((obj instanceof ParameterizedType)) {
					ParameterizedType other = (ParameterizedType) obj;
					return
							Objects.equals(this.getRawType(), other.getRawType())
							&& Objects.equals(this.getOwnerType(), other.getOwnerType())
							&& Arrays.equals(this.getActualTypeArguments(), other.getActualTypeArguments());
				} else {
					return false;
				}
			}

			@Override
			public int hashCode() {
				// Compatible with the one the reflection API uses for ParameterizedType
				return
						Objects.hashCode(this.getRawType())
								^ Objects.hashCode(this.getOwnerType())
								^ Arrays.hashCode(this.getActualTypeArguments());
			}

			@Override
			public String toString() {
				return "parameterizedType(" + rawClass.getSimpleName() + Arrays.toString(parameters) + ")";
			}
		};
	}
}
