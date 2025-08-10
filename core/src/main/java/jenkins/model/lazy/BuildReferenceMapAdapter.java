package jenkins.model.lazy;

import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.util.AdaptedIterator;
import hudson.util.Iterators;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * Take {@code SortedMap<Integer,BuildReference<R>>} and make it look like {@code SortedMap<Integer,R>}.
 *
 * When {@link BuildReference} lost the build object,
 * we'll use {@link AbstractLazyLoadRunMap#resolveBuildRef(BuildReference)} to obtain one.
 *
 * @author Kohsuke Kawaguchi
 */
class BuildReferenceMapAdapter<R> extends AbstractMap<Integer, R> implements SortedMap<Integer, R> {
    private final Function<BuildReference<R>, R> buildRefResolver;
    private final Function<R, Integer> buildNumberProvider;
    private final Function<R, Boolean> buildRemover;

    private final SortedMap<Integer, BuildReference<R>> core;

    private transient volatile Set<Integer> keySet;
    private transient volatile Collection<R> values;
    private transient volatile Set<Map.Entry<Integer, R>> entrySet;

    BuildReferenceMapAdapter(SortedMap<Integer, BuildReference<R>> core,
                             Function<BuildReference<R>, R> buildRefResolver, Function<R, Integer> buildNumberProvider,
                             Function<R, Boolean> buildRemover) {
        this.buildRefResolver = buildRefResolver;
        this.buildNumberProvider = buildNumberProvider;
        this.buildRemover = buildRemover;
        this.core = core;
    }

    BuildReferenceMapAdapter(SortedMap<Integer, BuildReference<R>> core,
                Function<BuildReference<R>, R> buildRefResolver, Function<R, Integer> buildNumberProvider) {
            this(core, buildRefResolver, buildNumberProvider, null);
        }

    private R unwrap(@Nullable BuildReference<R> ref) {
        return buildRefResolver.apply(ref);
    }

    @Override
    public Comparator<? super Integer> comparator() {
        return core.comparator();
    }

    @Override
    public SortedMap<Integer, R> subMap(Integer fromKey, Integer toKey) {
        return new BuildReferenceMapAdapter<>(core.subMap(fromKey, toKey), buildRefResolver, buildNumberProvider);
    }

    @Override
    public SortedMap<Integer, R> headMap(Integer toKey) {
        return new BuildReferenceMapAdapter<>(core.headMap(toKey), buildRefResolver, buildNumberProvider);
    }

    @Override
    public SortedMap<Integer, R> tailMap(Integer fromKey) {
        return new BuildReferenceMapAdapter<>(core.tailMap(fromKey), buildRefResolver, buildNumberProvider);
    }

    @Override
    public Integer firstKey() {
        return core.firstKey();
    }

    @Override
    public Integer lastKey() {
        return core.lastKey();
    }

    @Override
    public Set<Integer> keySet() {
        Set<Integer> ks = keySet;
        if (ks == null) {
            ks = new KeySetAdapter();
        }
        return keySet = ks;
    }

    @Override
    public Collection<R> values() {
        Collection<R> vals = values;
        if (vals == null) {
            vals = new ValuesAdapter();
        }
        return values = vals;
    }

    @Override
    public Set<Entry<Integer, R>> entrySet() {
        Set<Map.Entry<Integer, R>> es = entrySet;
        if (es == null) {
            es = new EntrySetAdapter();
        }
        return entrySet = es;
    }

    @Override
    public int size() {
        return core.size();
    }

    @Override
    public boolean isEmpty() {
        return entrySet().isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        BuildReference<R> ref = core.get(key);

        if (ref == null) {
            return false;
        }
        // if found, resolve it and check that value is equal
        if (!ref.isSet()) {
            buildRefResolver.apply(ref);
        }
        return !ref.isUnloadable();
    }

    @Override
    public boolean containsValue(Object value) {
        R val;
        // try to find buildRef for this build
        try {
            //noinspection unchecked
            val = (R) value;
        } catch (ClassCastException e) {
            return false;
        }
        int buildNum = buildNumberProvider.apply(val);
        return Objects.equals(get(buildNum), val);
    }

    @Override
    public R get(Object key) {
        return unwrap(core.get(key));
    }

    @Override
    public R remove(Object key) {
        if (buildRemover == null) {
            throw new UnsupportedOperationException();
        }
        R val = get(key);
        if (val == null) {
            return null;
        }
        return buildRemover.apply(val) ? val : null;
    }

    private class KeySetAdapter extends AbstractSet<Integer> {
        @Override
        public int size() {
            return BuildReferenceMapAdapter.this.size();
        }

        @Override
        public boolean isEmpty() {
            return BuildReferenceMapAdapter.this.isEmpty();
        }

        @Override
        public boolean contains(Object k) {
            return BuildReferenceMapAdapter.this.containsKey(k);
        }

        @Override
        public Iterator<Integer> iterator() {
            return new AdaptedIterator<>(BuildReferenceMapAdapter.this.entrySet().iterator()) {
                @Override
                protected Integer adapt(Entry<Integer, R> e) {
                    return e.getKey();
                }
            };
        }

        @Override
        public Spliterator<Integer> spliterator() {
            return new Spliterators.AbstractIntSpliterator(
                    Long.MAX_VALUE,
                    Spliterator.DISTINCT | Spliterator.ORDERED | Spliterator.SORTED) {
                private final Iterator<Integer> it = KeySetAdapter.this.iterator();

                @Override
                public boolean tryAdvance(IntConsumer action) {
                    if (action == null) {
                        throw new NullPointerException();
                    }
                    if (it.hasNext()) {
                        action.accept(it.next());
                        return true;
                    }
                    return false;
                }

                @Override
                public Comparator<? super Integer> getComparator() {
                    return BuildReferenceMapAdapter.this.comparator();
                }
            };
        }
    }

    private class ValuesAdapter extends AbstractCollection<R> {
        @Override
        public int size() {
            return BuildReferenceMapAdapter.this.size();
        }

        @Override
        public boolean isEmpty() {
            return BuildReferenceMapAdapter.this.isEmpty();
        }

        @Override
        public boolean contains(Object v) {
            return BuildReferenceMapAdapter.this.containsValue(v);
        }

        @Override
        public Iterator<R> iterator() {
            return new AdaptedIterator<>(BuildReferenceMapAdapter.this.entrySet().iterator()) {
                @Override
                protected R adapt(Entry<Integer, R> e) {
                    return e.getValue();
                }
            };
        }

        @Override
        public Spliterator<R> spliterator() {
            return Spliterators.spliteratorUnknownSize(iterator(), Spliterator.DISTINCT | Spliterator.ORDERED);
        }
    }

    private class EntrySetAdapter extends AbstractSet<Entry<Integer, R>> {
        @Override
        public int size() {
            return BuildReferenceMapAdapter.this.core.size();
        }

        @Override
        public boolean isEmpty() {
            return this.stream().findFirst().isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            if (o instanceof Map.Entry<?, ?> e) {
                Object k = e.getKey();
                if (k instanceof Integer) {
                    return Objects.equals(BuildReferenceMapAdapter.this.get(k), e.getValue());
                }
            }
            return false;
        }

        @Override
        public Iterator<Entry<Integer, R>> iterator() {
            return new Iterator<>() {
                private Entry<Integer, R> current;
                private final Iterator<Entry<Integer, R>> it = Iterators.removeNull(
                        new AdaptedIterator<>(BuildReferenceMapAdapter.this.core.entrySet().iterator()) {
                            @Override
                            protected Entry<Integer, R> adapt(Entry<Integer, BuildReference<R>> e) {
                                R v = unwrap(e.getValue());
                                if (v == null)
                                    return null;
                                return new AbstractMap.SimpleEntry<>(e.getKey(), v);
                            }
                        });

                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public Entry<Integer, R> next() {
                    return current = it.next();
                }

                @Override
                public void remove() {
                    if (buildRemover == null) {
                        throw new UnsupportedOperationException();
                    }

                    if (current == null) {
                        throw new IllegalStateException();
                    }
                    BuildReferenceMapAdapter.this.buildRemover.apply(current.getValue());
                }
            };
        }

        @Override
        public Spliterator<Map.Entry<Integer, R>> spliterator() {
            return Spliterators.spliteratorUnknownSize(iterator(), Spliterator.DISTINCT | Spliterator.ORDERED);
        }
    }
}
