/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 * 
 * Open Cybele 1
 * 
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.xhovor07.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

/**
 * Base for Graphs
 * @param <N> node
 * @param <E> edge
 *
 */
public abstract class AbstractUnorientedGraph<N,E> implements UnorientedGraph<N,E> {
	/**
	 * @return delegat obj
	 */
    	protected abstract Object implementationContainer();
	
	public boolean containsEdge(E edge) {
		return values().contains(edge);
	}
	
	@Override
	public String toString() {
		final Object o = implementationContainer();
		return (o == null) ? super.toString() : o.toString();
	}
	
	public int size() {
		final Object o = invokeForImplementationContainer();
		assert o instanceof Integer;
		return ((Integer) o).intValue();
	}

	public void clear() {
		invokeForImplementationContainer();
	}
	
	private Object invokeForImplementationContainer(Object... args) {
		final Object o = implementationContainer();
		if (o == null || (!(o instanceof Map) && !(o instanceof Collection))) throw new UnsupportedOperationException();
		
		final StackTraceElement e = new Throwable().getStackTrace()[1];
		final String methodName = e.getMethodName();
		try {
			final Method method = o.getClass().getMethod(methodName, Util.toClass(args));
			return method.invoke(o, args);
		}  catch (InvocationTargetException ee) {
			final Throwable cause = ee.getCause();
			assert cause instanceof RuntimeException;
			throw (RuntimeException) cause;
		}  catch (Exception ee) {
			throw new UnsupportedOperationException(ee);
		}
	}

	public boolean isEmpty() {
		return size() == 0;
	}
}
