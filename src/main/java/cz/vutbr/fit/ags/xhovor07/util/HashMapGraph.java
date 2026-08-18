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

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
	
	/**
	 * Ulozene hrany. Instance je vzdy {@link LinkedHashMap} - viz {@link #readObject}.
	 *
	 * <p>DETERMINISMUS (#19): poradi vlozeni je diky tomu definovane a slouzi jako
	 * tie-breaker pro {@link #orderedKeys()}. Zadny verejny pristupovy bod
	 * nevystavuje poradi prihradek HashMapu.</p>
	 *
	 * <p>Deklarovany typ zustava {@link HashMap} kvuli <b>ctení starych streamu</b>:
	 * {@code serialVersionUID} je pevne 1L, takze o SUID nejde - jde o to, ze stream
	 * zapsany pred #19 nese v tomto poli obycejnou {@link HashMap}, kterou by pole
	 * deklarovane jako {@link LinkedHashMap} odmitlo.</p>
	 */
	private HashMap<Doubleton<N>, E> map = new LinkedHashMap<Doubleton<N>, E>();
	
	/**
	 * Po deserializaci znormalizuje {@link #map} zpet na {@link LinkedHashMap}, aby
	 * tie-break v {@link #orderedKeys()} nikdy nezavisel na poradi prihradek zive
	 * {@link HashMap} (stream zapsany pred #19 nese prave takovou).
	 *
	 * <p><b>Zbytkove omezeni:</b> {@link HashMap} poradi vlozeni neserializuje, takze
	 * u streamu zapsaneho pred #19 uz je deklaracni poradi neobnovitelne - prebira se
	 * poradi, ve kterem stream entry nese. Od te chvile je ale zmrazene a nezavisle na
	 * verzi JDK. Zadna varianta opravy (vcetne explicitniho citace vlozeni) to nedokaze
	 * lepe, protoze ta informace ve starem streamu proste neni. Viz #27/#33.</p>
	 *
	 * @param in vstupni stream
	 * @throws IOException z {@link ObjectInputStream#defaultReadObject()}
	 * @throws ClassNotFoundException z {@link ObjectInputStream#defaultReadObject()}
	 */
	private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		if (!(map instanceof LinkedHashMap)) {
			map = new LinkedHashMap<Doubleton<N>, E>(map);
		}
	}
	
	/**
	 * Klice v deterministickem poradi - jedine poradi, ve kterem se nad
	 * {@link #map} iteruje na vsech mistech, jejichz vysledek je pozorovatelny
	 * (poradi vzniku agentu, poradi kandidatnich hran pri hledani cesty).
	 *
	 * <p>Reprodukuje presne poradi, ktere baseline vykazovala s {@link HashMap} -
	 * viz {@link Util#stableOrder(java.util.Collection)} a {@code docs/iteration-order.md}.</p>
	 *
	 * @return klice serazene podle {@link Util#orderRank(Object)}
	 */
	private List<Doubleton<N>> orderedKeys() {
		return Util.stableOrder(map.keySet());
	}
	
	
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
		
		// DETERMINISMUS (#19, NDT-04): poradi vracenych hran urcuje, kterou vetev
		// zkusi Util.privatePath jako prvni. Iterujeme proto pres orderedKeys().
		for (Doubleton<N> key : orderedKeys()) {
			if (key.contains(node)) {
				collection.add(map.get(key));
				if (remove) map.remove(key);
		    }
		}
		return collection;
	}
	
	
	public Collection<N> remove(E h) {
	    assert h != null;
	    final Collection<N> collection = new LinkedHashSet<N>();
	    for (Doubleton<N> key : orderedKeys()) {
	    	if (h.equals(map.get(key))) {
	    		collection.addAll(key);
	    		map.remove(key);
	    	}
	    }
	    return collection;
	}
	
	public Collection<N> allNodesWithEdge(E h) {
	    assert h != null;
	    final Collection<N> collection = new ArrayList<N>();
	    // Poradi UVNITR dvojice je dano konstrukci Doubleton(first, second), tedy
	    // poradim argumentu put(first, second, value) - nikoli hashovanim; presne
	    // toto rozhoduje o leftStation/rightStation v RoadAgent. Viz #19 (claim 3)
	    // a docs/iteration-order.md; zamykaji to testy v docs/probes/OrderLock.java.
	    // Hrana s danym id je v grafu prave jedna, takze poradi iterace pres klice
	    // ovlivnuje jen to, KDY se shoda najde, nikdy KTERA to je.
	    for (Doubleton<N> key : orderedKeys()) {
	    	if (h.equals(map.get(key))) {
	    		collection.addAll(key);
	    	}
	    }
	    return collection;
	}
	
	
	public Set<N> nodeSet() {
	    if (nodeCollection == null) nodeCollection = new NodeCollection();
		// DETERMINISMUS (#19, NDT-01): puvodne new HashSet<N>(nodeCollection),
		// tedy poradi prihradek HashSetu. Nyni LinkedHashSet v poradi danem
		// Util.stableOrder - stejne poradi, ale definovane timto kodem.
		final Set<N> distinct = new LinkedHashSet<N>(nodeCollection);
		return new LinkedHashSet<N>(Util.stableOrder(distinct));
	}

	
	public boolean isEmpty() {
	    return map.isEmpty();
	}

	/**
	 * Hrany ve stejnem deterministickem poradi jako {@link #values()} - viz
	 * {@link #orderedKeys()}. Neni to zivy pohled: {@code remove} pres tento set
	 * mapu nemeni (jednotlive {@link Entry} zapisuji hodnotu dal). Produkcni kod
	 * tuto metodu nepouziva; serazena je proto, aby zadny pristupovy bod tridy
	 * nevracel jine poradi nez to, ve kterem vznikaji agenti (#19).
	 * @return entry set
	 */
	public Set<Entry<Doubleton<N>, E>> entrySet() {
		// HashMap zde slouzi vyhradne k vyhledani, nikdy se pres ni neiteruje,
		// takze jeji poradi prihradek nema kudy uniknout ven.
		final Map<Doubleton<N>, Entry<Doubleton<N>, E>> byKey =
			new HashMap<Doubleton<N>, Entry<Doubleton<N>, E>>();
		for (Entry<Doubleton<N>, E> entry : map.entrySet()) {
			byKey.put(entry.getKey(), entry);
		}
		final Set<Entry<Doubleton<N>, E>> ordered = new LinkedHashSet<Entry<Doubleton<N>, E>>();
		for (Doubleton<N> key : orderedKeys()) {
			ordered.add(byKey.get(key));
		}
		return ordered;
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
	 * DETERMINISMUS (#19, NDT-02): puvodne {@code map.values()}, tedy poradi
	 * prihradek HashMapu, ktere urcuje poradi vzniku agentu RoadAgent.
	 * Nyni kopie v poradi {@link #orderedKeys()}.
	 */
	public Collection<E> values() {
		final List<E> values = new ArrayList<E>(map.size());
		for (Doubleton<N> key : orderedKeys()) {
			values.add(map.get(key));
		}
		return values;
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
