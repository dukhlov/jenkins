/*
 * The MIT License
 *
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.util;

import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.converters.collections.MapConverter;
import com.thoughtworks.xstream.converters.collections.TreeMapConverter;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.mapper.Mapper;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * {@link Map} that has copy-on-write semantics.
 *
 * <p>
 * This class is suitable where highly concurrent access is needed, yet
 * the write operation is relatively uncommon.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class CopyOnWriteMap<K, V> implements Map<K, V> {
    protected volatile Map<K, V> snapshot;

    protected CopyOnWriteMap(Map<K, V> core) {
        update(core);
    }

    public Map<K, V> getSnapshot() {
        return snapshot;
    }

    protected void update(Map<K, V> m) {
        snapshot = Collections.unmodifiableMap(m);
    }

    /**
     * Atomically replaces the entire map by the copy of the specified map.
     */
    public void replaceBy(Map<? extends K, ? extends V> data) {
        Map<K, V> d = copy();
        d.clear();
        d.putAll(data);
        update(d);
    }

    @Override
    public int size() {
        return snapshot.size();
    }

    @Override
    public boolean isEmpty() {
        return snapshot.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return snapshot.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return snapshot.containsValue(value);
    }

    @Override
    public V get(Object key) {
        return snapshot.get(key);
    }

    @Override
    public synchronized V put(K key, V value) {
        Map<K, V> m = copy();
        V r = m.put(key, value);
        update(m);

        return r;
    }

    @Override
    public synchronized V remove(Object key) {
        Map<K, V> m = copy();
        V r = m.remove(key);
        update(m);

        return r;
    }

    @Override
    public synchronized void putAll(Map<? extends K, ? extends V> t) {
        Map<K, V> m = copy();
        m.putAll(t);
        update(m);
    }

    protected abstract Map<K, V> copy();

    @Override
    public synchronized void clear() {
        update(Collections.emptyNavigableMap());
    }

    /**
     * This method will return a read-only {@link Set}.
     */
    @Override
    public Set<K> keySet() {
        return snapshot.keySet();
    }

    /**
     * This method will return a read-only {@link Collection}.
     */
    @Override
    public Collection<V> values() {
        return snapshot.values();
    }

    /**
     * This method will return a read-only {@link Set}.
     */
    @Override
    public Set<Entry<K, V>> entrySet() {
        return snapshot.entrySet();
    }

    @Override public int hashCode() {
        return snapshot.hashCode();
    }

    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    @Override public boolean equals(Object obj) {
        return snapshot.equals(obj);
    }

    @Override public String toString() {
        return snapshot.toString();
    }

    /**
     * {@link CopyOnWriteMap} backed by {@link HashMap}.
     */
    public static final class Hash<K, V> extends CopyOnWriteMap<K, V> {
        public Hash(Map<K, V> core) {
            super(new LinkedHashMap<>(core));
        }

        public Hash() {
            super(Collections.emptyMap());
        }

        @Override
        protected Map<K, V> copy() {
            return new LinkedHashMap<>(getSnapshot());
        }

        public static class ConverterImpl extends MapConverter {
            public ConverterImpl(Mapper mapper) {
                super(mapper);
            }

            @Override
            public boolean canConvert(Class type) {
                return type == Hash.class;
            }

            @Override
            public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
                return new Hash<>((Map<?, ?>) super.unmarshal(reader, context));
            }

            @Override
            public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
                super.marshal(((Hash<?, ?>) source).getSnapshot(), writer, context);
            }
        }
    }

    /**
     * {@link CopyOnWriteMap} backed by {@link TreeMap}.
     */
    public static final class Tree<K, V> extends CopyOnWriteMap<K, V> implements NavigableMap<K, V> {
        private final Comparator<? super K> comparator;

        public Tree(Map<K, V> core, Comparator<? super K> comparator) {
            this(comparator);
            putAll(core);
        }

        public Tree(NavigableMap<K, V> m) {
            this(m.comparator());
            putAll(m);
        }

        public Tree(Comparator<? super K> comparator) {
            super(Collections.emptyNavigableMap());
            this.comparator = comparator;
        }

        public Tree() {
            this((Comparator<? super K>) null);
        }

        @Override
        protected void update(Map<K, V> m) {
           snapshot = Collections.unmodifiableNavigableMap((NavigableMap<K, ? extends V>) m);
        }

        @Override
        protected TreeMap<K, V> copy() {
            TreeMap<K, V> m = new TreeMap<>(comparator);
            m.putAll(getSnapshot());
            return m;
        }

        @Override
        public NavigableMap<K, V> getSnapshot() {
            return (NavigableMap<K, V>) super.getSnapshot();
        }

        @Override
        public Entry<K, V> lowerEntry(K key) {
            return getSnapshot().lowerEntry(key);
        }

        @Override
        public K lowerKey(K key) {
            return getSnapshot().lowerKey(key);
        }

        @Override
        public Entry<K, V> floorEntry(K key) {
            return getSnapshot().floorEntry(key);
        }

        @Override
        public K floorKey(K key) {
            return getSnapshot().floorKey(key);
        }

        @Override
        public Entry<K, V> ceilingEntry(K key) {
            return getSnapshot().ceilingEntry(key);
        }

        @Override
        public K ceilingKey(K key) {
            return getSnapshot().ceilingKey(key);
        }

        @Override
        public Entry<K, V> higherEntry(K key) {
            return getSnapshot().higherEntry(key);
        }

        @Override
        public K higherKey(K key) {
            return getSnapshot().higherKey(key);
        }

        @Override
        public Entry<K, V> firstEntry() {
            return getSnapshot().firstEntry();
        }

        @Override
        public Entry<K, V> lastEntry() {
            return getSnapshot().lastEntry();
        }

        @Override
        public Entry<K, V> pollFirstEntry() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entry<K, V> pollLastEntry() {
            throw new UnsupportedOperationException();
        }

        @Override
        public NavigableMap<K, V> descendingMap() {
            return getSnapshot().descendingMap();
        }

        @Override
        public NavigableSet<K> navigableKeySet() {
            return getSnapshot().navigableKeySet();
        }

        @Override
        public NavigableSet<K> descendingKeySet() {
            return getSnapshot().descendingKeySet();
        }

        @Override
        public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
            return getSnapshot().subMap(fromKey, fromInclusive, toKey, toInclusive);
        }

        @Override
        public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
            return getSnapshot().headMap(toKey, inclusive);
        }

        @Override
        public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
            return getSnapshot().tailMap(fromKey, inclusive);
        }

        @Override
        public Comparator<? super K> comparator() {
            return getSnapshot().comparator();
        }

        @Override
        public SortedMap<K, V> subMap(K fromKey, K toKey) {
            return getSnapshot().subMap(fromKey, toKey);
        }

        @Override
        public SortedMap<K, V> headMap(K toKey) {
            return getSnapshot().headMap(toKey);
        }

        @Override
        public SortedMap<K, V> tailMap(K fromKey) {
            return getSnapshot().tailMap(fromKey);
        }

        @Override
        public K firstKey() {
            return getSnapshot().firstKey();
        }

        @Override
        public K lastKey() {
            return getSnapshot().lastKey();
        }

        public static class ConverterImpl extends TreeMapConverter {
            public ConverterImpl(Mapper mapper) {
                super(mapper);
            }

            @Override
            public boolean canConvert(Class type) {
                return type == Tree.class;
            }

            @Override
            public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
                return new Tree<>((TreeMap<?, ?>) super.unmarshal(reader, context));
            }

            @Override
            public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
                super.marshal(((Tree<?, ?>) source).getSnapshot(), writer, context);
            }
        }
    }
}
