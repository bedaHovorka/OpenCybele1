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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;

/**
 * very simple ADT Multimap prototype
 *
 * @param <K> key
 * @param <V> value
 */
public class TreeMultiMap<K,V> implements Serializable {
    private static final long serialVersionUID = 1L;
    private final SortedMap<K,Set<V>> map;
    
    /**
     * Counstructs empty multimap
     */
    public TreeMultiMap() {
	this(new TreeMap<K,Set<V>>());
    }
    
    private TreeMultiMap(SortedMap<K,Set<V>> map) {
	this.map = map;
    }
    
    /**
     * put element to multimap
     * @param key
     * @param value
     */
    public void put(K key, V value) {
	Set<V> valueSet = map.get(key);
	if (valueSet == null) {
	    valueSet = new LinkedHashSet<V>();
	    map.put(key, valueSet);
	}
	valueSet.add(value);
    }
    
    /**
     * get elements from multimap
     * @param key
     * @return set of elements
     */
    public Set<V> get(K key) {
    	//	EXTENSION jak to ma byt spravne...
    	return Collections.unmodifiableSet(map.get(key));
    }
    
    @Override
    public String toString() {
	return map.toString();
    }

    /**
     * Values in map - reading access to multimap
     * @return values
     */
    public Collection<V> values() {
    	//	EXTENSION jak to ma byt spravne...
    	Collection<V> coll = new ArrayList<V>();
    	for (Set<V> set : map.values()) {
    		coll.addAll(set);
    	}
		return coll;
    }
    
    /**
     * 
     * @param key
     * @return submultimap with keys greater than key
     */
    public TreeMultiMap<K,V> tailSubMultiMap(K key) {
//	EXTENSION jak to ma byt spravne...
    	return new TreeMultiMap<K, V>(map.tailMap(key));
    }
    
    /**
     * 
     * @param fromKey
     * @param toKey
     * @return  submultimap with keys between fromKey and toKey
     */
    public TreeMultiMap<K,V> subMultiMap(K fromKey, K toKey) {
//	EXTENSION jak to ma byt spravne...
    	return new TreeMultiMap<K, V>(map.subMap(fromKey, toKey));
    }
    
    /**
     * @return the last (highest) key currently in this multimap
     */
    public K lastKey() {
	return map.lastKey();
    }
    
    /**
     * Remove value
     * @param removingValue
     */
    public void removeValue(V removingValue) {
	final Iterator<Entry<K, Set<V>>> iterator = map.entrySet().iterator();
	for (;iterator.hasNext();) {
	    final Entry<K, Set<V>> next = iterator.next();
	    next.getValue().remove(removingValue);
	    if (next.getValue().size() == 0) {
		iterator.remove();
	    }
	}
    }
}
