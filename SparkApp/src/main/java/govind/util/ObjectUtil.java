package govind.util;

import exception.ESIlegalArgumentException;

public abstract class ObjectUtil {
	public static Class<?> loadClass(String clzName, ClassLoader classLoader) {
		try {
			return Class.forName(clzName, true, classLoader);
		} catch (ClassNotFoundException e) {
			throw new ESIlegalArgumentException(String.format("%s Not Found Exception!", clzName));
		}
	}
}
