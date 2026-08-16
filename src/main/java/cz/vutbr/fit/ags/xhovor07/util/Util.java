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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collection of shared static methods
 *
 */
public final class Util {
	private Util() {
		//EMPTY
	}

	private static Class<?> toClass(Object o) {
		final Class<?> class1 = o.getClass();
		return class1;
	}

	/**
	 * !!! THIS METHOD is designed only for interlocksim
	 * @param objects
	 * @return array of classes which reprezents types in objects
	 */
	public static Class<?>[] toClass(Object[] objects) {
		if (objects == null) return null;
		final Class<?>[] classes = new Class[objects.length];
		for (int i = 0; i < objects.length; i++) {
			assert !objects[i].getClass().isArray();
			classes[i] = objects[i] == null ? null : toClass(objects[i]);
		}
		return classes;
	}
	
	/**
	 * assert and cast routine
	 * @param <T>
	 * @param clazz
	 * @param obj
	 * @return casted instance
	 */
	public static <T> T assertInstanceOf(Class<T> clazz, Object obj) {
		assert clazz != null;
		assert clazz.isInstance(obj) : clazz + " " + obj;
		return clazz.cast(obj);
	}

	/**
	 * Covers InterruptedException
	 * @param i
	 */
	public static void sleep(int i) {
	    try {
		Thread.sleep(i);
	    } catch (InterruptedException e) {
		e.printStackTrace();
	    }
	}
	
	/**
	 * First edge on path to target
	 * (with recursive DFS for small simple graphs)
	 * @param <N>
	 * @param <E>
	 * @param graph
	 * @param start
	 * @param target
	 * @return edge
	 */
	@SuppressWarnings("unchecked")
	public static <N,E> E pathDirection(UnorientedGraph<N, E> graph, N start, N target) {
	    assert graph.nodeSet().contains(start) && graph.nodeSet().contains(target) && !start.equals(target);
	    final List<Object> privateDirection = privatePath(graph, start, target, null, Collections.<Object>singletonList(start));
	    if (privateDirection != null) return (E) privateDirection.get(1);
	    return null;
	}
	
	/**
	 * Find path from start to target in graph 
	 * (with recursive DFS for small simple graphs)
	 * @param <N>
	 * @param <E>
	 * @param graph
	 * @param start
	 * @param target
	 * @return path
	 */
	public static <N,E> List<Object> path(UnorientedGraph<N, E> graph, N start, N target) {
	    return privatePath(graph, start, target, null, Collections.<Object>singletonList(start));
	}
	
	private static <N,E> List<Object> privatePath(UnorientedGraph<N, E> graph, N start, N target, E zakazana, List<Object> path) {
	    final Map<E, Collection<N>> nodesToEdges = new HashMap<E, Collection<N>>(); 
	    final Collection<E> collection = new ArrayList<E>(graph.get(start));
	    if (zakazana != null) collection.remove(zakazana);
	    for (E e : collection) {
		final Collection<N> allNodesWithEdge = new ArrayList<N>(graph.allNodesWithEdge(e));
		allNodesWithEdge.remove(start);
		if (allNodesWithEdge.size() > 0) {
		    if (allNodesWithEdge.contains(target)) {
			final ArrayList<Object> arrayList = new ArrayList<Object>(path);
			arrayList.add(e);
			arrayList.add(target);
			return arrayList;
		    } 
		    nodesToEdges.put(e, allNodesWithEdge);
		}
	    }
	    
	    for (Map.Entry<E, Collection<N>> entry : nodesToEdges.entrySet()) {
		for (N node : entry.getValue()) {
		    final ArrayList<Object> arrayList = new ArrayList<Object>(path);
		    arrayList.add(entry.getKey());
		    arrayList.add(node);
		    final List<Object> privateDirection = privatePath(graph, node, target, entry.getKey(), arrayList);
		    if (privateDirection != null) return privateDirection;
		}
	    }
	    
	    return null;
	}
}
