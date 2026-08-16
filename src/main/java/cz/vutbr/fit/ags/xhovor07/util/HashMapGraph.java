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

import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Map.Entry;

/**
 * The ADT Graph prototype
 *
 * @param <N> nodes
 * @param <E> edges
 */
public class HashMapGraph<N, E> implements UnorientedGraph<N, E>, Serializable {
    private static final long serialVersionUID = 1L;

	final class NodeCollection extends AbstractCollection<N> {
		private final class NodeCollectionIterator implements Iterator<N> {
			private final Iterator<Doubleton<N>> keySetIterator = keySet.iterator();
			private Iterator<N> currentPair;
			private N current;

			/**
			 * Construct iterator for iterating over nodes
			 */
			public NodeCollectionIterator() {
				super();
				if (keySetIterator.hasNext()) {
					currentPair = keySetIterator.next().iterator();
				}
			}

			public void remove() {
				if (current == null) throw new IllegalStateException();
				HashMapGraph.this.removeAll(current);
				current = null;
			}

			public N next() {
				if (!currentPair.hasNext()) {
					currentPair = keySetIterator.next().iterator();
				}
				current = currentPair.next();
				return current;
			}

			public boolean hasNext() {
				return currentPair.hasNext() || keySetIterator.hasNext();
			}
		}

		private final Set<Doubleton<N>> keySet = map.keySet();

		@Override
		public Iterator<N> iterator() {
			return new NodeCollectionIterator();
		}

		@Override
		public int size() {
			return keySet.size() << 1;
		}
	}
	
	private transient NodeCollection nodeCollection;
	
	private HashMap<Doubleton<N>, E> map = new HashMap<Doubleton<N>, E>();
	
	
	public void put(N first, N second, E value) {
	    map.put(new Doubleton<N>(first, second), value);
	}
	
	
	public E get(N first, N second) {
	    return map.get(new Doubleton<N>(first, second));
	}
	
	
	public E remove(N first, N second) {
	    return map.remove(new Doubleton<N>(first, second));
	}
	
	
	public Collection<E> removeAll(N node) {
		return allIndicesJoinsWith(node, true);
	}
	
	private Collection<E> allIndicesJoinsWith(N node, boolean remove) {
		assert node != null;
		final Collection<E> collection = new ArrayList<E>();
		
		final Iterator<Entry<Doubleton<N>, E>> iterator = map.entrySet().iterator();
		while (iterator.hasNext()) {
			final Entry<Doubleton<N>, E> next = iterator.next();
			final Doubleton<N> key = next.getKey();
			if (key.contains(node)) {
				collection.add(next.getValue());
				if (remove) iterator.remove();
		    }
		}
		return collection;
	}
	
	
	public Collection<N> remove(E h) {
	    assert h != null;
	    final Collection<N> collection = new HashSet<N>();
	    final Iterator<Entry<Doubleton<N>, E>> iterator = map.entrySet().iterator();
	    while (iterator.hasNext()) {
	    	final Entry<Doubleton<N>, E> next = iterator.next();
	    	if (h.equals(next.getValue())) {
	    		collection.addAll(next.getKey());
	    		iterator.remove();
	    	}
	    }
	    return collection;
	}
	
	public Collection<N> allNodesWithEdge(E h) {
	    assert h != null;
	    final Collection<N> collection = new ArrayList<N>();
	    final Iterator<Entry<Doubleton<N>, E>> iterator = map.entrySet().iterator();
	    while (iterator.hasNext()) {
	    	final Entry<Doubleton<N>, E> next = iterator.next();
	    	if (h.equals(next.getValue())) {
	    		collection.addAll(next.getKey());
	    	}
	    }
	    return collection;
	}
	
	
	public Set<N> nodeSet() {
	    if (nodeCollection == null) nodeCollection = new NodeCollection();
		Set<N> set = new HashSet<N>(nodeCollection);
		//for (Doubleton<N> c: map.keySet()) {
		//	set.addAll(c);
		//}
		return set;
	}

	
	public boolean isEmpty() {
	    return map.isEmpty();
	}

	/**
	 * @return entry set
	 */
	public Set<Entry<Doubleton<N>, E>> entrySet() {
		return map.entrySet();
	}

	
	/**
	 * @param first
	 * @param second
	 * @param i
	 */
	public void putIfNotExists(N first, N second, E i) {
	    final Doubleton<N> pair = new Doubleton<N>(first, second);  
	    if (!map.containsKey(pair)) {
		map.put(pair, i);
	    }
	}

	
	/**
	 *
	 */
	public Collection<E> values() {
		return map.values();
	}

	public Collection<E> get(N node) {
		return allIndicesJoinsWith(node, false);
	}

	public int size() {
		return map.size();
	}

	@Override
	public void clear() {
	    map.clear();
	}

	@Override
	public boolean contains(N node1, N node2) {
	    return map.containsKey(new Doubleton<N>(node1, node2));
	}

	@Override
	public boolean containsEdge(E edge) {
	    return map.containsValue(edge);
	}
}
