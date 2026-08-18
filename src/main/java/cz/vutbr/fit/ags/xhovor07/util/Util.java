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
import java.util.Comparator;
import java.util.LinkedHashMap;
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
	 * Deterministicke poradi iterace nad kolekci.
	 *
	 * <p>Puvodni implementace {@link HashMapGraph} vystavovala poradi iterace
	 * {@link java.util.HashMap}, ktere neni specifikovano a muze se zmenit s verzi JDK.
	 * Golden-master zaznamy (viz {@code docs/Phase1.md}) na nem ale zavisi, takze
	 * poradi musi byt (a) stabilni a (b) totozne s tim, ktere baseline vykazuje dnes.</p>
	 *
	 * <p>Pravidlo: stabilni razeni podle {@link #orderRank(Object)} vzestupne;
	 * prvky se shodnym rankem si drzi poradi vlozeni. Pro <b>vychozi</b> topologii
	 * (klice stA..stH / tr1..tr7) to reprodukuje presne dnesni poradi - overuje
	 * {@code docs/probes/OrderLock.java}. Pro jinou topologii zadne "dnesni poradi"
	 * neexistuje a pozadavek je jen determinismus, ktery pravidlo splnuje vzdy.
	 * Na rozdil od HashMapu je cele v tomto kodu, takze se s JDK nemeni.</p>
	 *
	 * @param <T> typ prvku
	 * @param collection vstupni kolekce (poradi jejiho iteratoru je tie-breaker)
	 * @return novy seznam v deterministickem poradi
	 */
	public static <T> List<T> stableOrder(Collection<T> collection) {
		final List<T> list = new ArrayList<T>(collection);
		Collections.sort(list, ORDER_RANK_COMPARATOR);
		return list;
	}

	/**
	 * Radici klic pouzity v {@link #stableOrder(Collection)}.
	 *
	 * <p>Je to tzv. hash spread ({@code h ^ (h >>> 16)}), tedy tataz funkce, kterou
	 * {@code java.util.HashMap} pouziva pri vypoctu indexu prihradky.</p>
	 *
	 * <p><b>Pozor - neni to duvod, proc poradi vychazi stejne.</b> Razeni podle spreadu
	 * NENI obecne totozne s poradim iterace HashMapu: to je {@code spread & (n-1)} plus
	 * poradi v retezci plus historie zvetsovani tabulky, nikoli uplne usporadani podle
	 * spreadu. Pro vychozi topologii se shoduje jen proto, ze hashe klicu stA..stH
	 * a tr1..tr7 tvori <i>souvisly usek</i>, ktery se mapuje na po sobe jdouci prihradky
	 * bez preteceni - to je vlastnost te rodiny jmen, ne te funkce. Merenim na nahodnych
	 * sadach klicu se shoda trefi zhruba v 9 % pripadu; rozpadne se napr. pro
	 * {@code s1..s8} nebo pro stA..stH plus jedno jmeno navic.</p>
	 *
	 * <p>Pro nas to staci: zachovat dnesni poradi je treba jen u vychozi topologie
	 * (na ni jsou nahrany goldeny), u kazde jine se pozaduje pouze determinismus.
	 * Nedovozujte z toho, ze pravidlo je ekvivalentni HashMapu - neni.</p>
	 *
	 * @param o prvek (smi byt null)
	 * @return radici klic
	 */
	public static int orderRank(Object o) {
		final int h = (o == null) ? 0 : o.hashCode();
		return h ^ (h >>> 16);
	}

	private static final Comparator<Object> ORDER_RANK_COMPARATOR = new Comparator<Object>() {
		public int compare(Object first, Object second) {
			final int rankFirst = orderRank(first);
			final int rankSecond = orderRank(second);
			if (rankFirst < rankSecond) return -1;
			if (rankFirst > rankSecond) return 1;
			return 0;
		}
	};

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
	    // DETERMINISMUS (#19, NDT-04): LinkedHashMap, nikoli HashMap - poradi rekurzivniho
	    // sestupu pak odpovida poradi vlozeni, tedy poradi hran z graph.get(start),
	    // ktere je samo deterministicky serazeno (viz HashMapGraph.orderedKeys()).
	    final Map<E, Collection<N>> nodesToEdges = new LinkedHashMap<E, Collection<N>>(); 
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
