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

import java.io.Serializable;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Pair of nodes for implementation ADT Graph
 * Represents combination of two elements
 * @param <T> type of elements
 */
public class Doubleton<T> extends AbstractSet<T> implements Serializable {
    private static final long serialVersionUID = 1L;

	private enum IteratorState {
		/** **/
		INIT, 
		/**	**/
		FIRST,
		/**	**/
		SECOND
	}
	
	final class DoubletonIterator implements Iterator<T> {
		IteratorState state = IteratorState.INIT;
		
		public boolean hasNext() {
			assert state != null;
			return state != IteratorState.SECOND;
		}

		public T next() {
			assert state != null;
			if (state == IteratorState.INIT) {
				state = IteratorState.FIRST;
				return first;
			} else if (state == IteratorState.FIRST) {
				state = IteratorState.SECOND;
				return second;
			} else throw new NoSuchElementException();
		}

		public void remove() {
			throw new UnsupportedOperationException();
		}
	}

	private final T first;
	private final T second;
	
	/**
	 * 
	 * @param first
	 * @param second
	 */
	public Doubleton(final T first, final T second) {
		this.first = first;
		this.second = second;
	}

	@Override
	public int hashCode() {
		int result = (first == null) ? 0 : first.hashCode();
		result += (second == null) ? 0 : second.hashCode();//kongruentni komutativita?
		return result;
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final Doubleton<?> other = (Doubleton) obj;
		
		//nezalezi na poradi - rozcvicka na null pointery
		if (first == null){
		    if (second == null) return other.first == null && other.second == null;
		    
		    //second musi rovnat tomu nenulovymu
		    if (other.first == null) return second.equals(other.second);
		    return other.second == null && second.equals(other.first);
		}
		
		if (first.equals(other.first)) {
			if (second == null) return other.second == null;
			return second.equals(other.second);
		} else if (first.equals(other.second)) {
		    if (second == null) return other.first == null;
			return second.equals(other.first);
		} else return false;
	}

	/**
	 * {@link AbstractSet#equals(Object)}
	 * @param o 
	 * @return For tests
	 */
	public boolean superEquals(Object o) {
		return super.equals(o);
	}
	
	@Override
	public Iterator<T> iterator() {
		return new DoubletonIterator();
	}

	@Override
	public int size() {
		return 2;
	}
	
	@Override
	public boolean isEmpty() {
		return false;
	}
}
