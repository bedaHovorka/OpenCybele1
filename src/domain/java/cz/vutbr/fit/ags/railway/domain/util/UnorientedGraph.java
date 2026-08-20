/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 * 
 * Open Cybele 1
 * 
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.railway.domain.util;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * ADT Graph interface
 *
 * @param <N> nodes
 * @param <E> edges
 */
public interface UnorientedGraph<N,E>{
	
	/**
	 * Put element to ExtendedUnorientedGraph
	 * @param first
	 * @param second
	 * @param value
	 */
	public void put(N first, N second, E value);
	
	/**
	 * get edge from graph
	 * @param first
	 * @param second
	 * @return edge
	 */
	public E get(N first, N second);
	
	/**
	 * @param node
	 * @return all edges, which joins with node
	 */
	public Collection<E> get(N node);

	/**
	 * Remove edge from graph
	 * @param first
	 * @param second
	 * @return edge
	 */
	public E remove(N first, N second);

	/**
	 * Remove node and all edges, which join node with others
	 * @param node
	 * @return all edges which were removed
	 */
	public Collection<E> removeAll(N node);

	/**
	 * Remove edge from graph
	 * @param edge
	 * @return nodes which were joined with this edge  
	 */
	public Collection<N> remove(E edge);
	
	/**
	 * 
	 * @param edge
	 * @return nodes which were joined with this edge  
	 */
	public Collection<N> allNodesWithEdge(E edge);
	
	/**
	 * @return all nodes, which is in graph
	 */
	public Set<N> nodeSet();

	/**
	 * @return {@link Map#isEmpty()}
	 */
	public boolean isEmpty();
	
	/**
	 * @see Map#values()
	 * @return all edges in graph
	 */
	public Collection<E> values();

	/**
	 * @return number of edges
	 */
	public int size();

	/**
	 * Remove all edges from graph
	 */
	public void clear();
	
	/**
	 * @param node1 
	 * @param node2 
	 * @return true If graph contains edge between nodes
	 */
	public boolean contains(N node1, N node2);
	
	/**
	 * @param edge 
	 * @return true If graph contains edge
	 */
	public boolean containsEdge(E edge);
}
