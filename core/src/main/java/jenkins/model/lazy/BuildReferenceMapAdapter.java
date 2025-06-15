package jenkins.model.lazy;

import hudson.util.AdaptedIterator;
import hudson.util.Iterators;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.Spliterator;
import java.util.Spliterators;

/**
 * Take {@code SortedMap<Integer,BuildReference<R>>} and make it look like {@code SortedMap<Integer,R>}.
 *
 * When {@link BuildReference} lost the build object, we'll use {@link AbstractLazyLoadRunMap#getById(String)}
 * to obtain one.
 *
 * @author Kohsuke Kawaguchi
 */
class BuildReferenceMapAdapter<R> extends AbstractMap<Integer, R> implements SortedMap<Integer, R> {
    private final Loader<R> loader;

    private final NavigableMap<Integer, BuildReference<R>> core;

    BuildReferenceMapAdapter(Loader<R> loader, NavigableMap<Integer, BuildReference<R>> core) {
        this.loader = loader;
        this.core = Collections.unmodifiableNavigableMap(core);
    }

    @Override
    public Comparator<? super Integer> comparator() {
        return core.comparator();
    }

    @Override
    public BuildReferenceMapAdapter<R> subMap(Integer fromKey, Integer toKey) {
        return new BuildReferenceMapAdapter<>(loader, core.subMap(fromKey, true, toKey, false));
    }

    @Override
    public BuildReferenceMapAdapter<R> headMap(Integer toKey) {
        return new BuildReferenceMapAdapter<>(loader, core.headMap(toKey, false));
    }

    @Override
    public BuildReferenceMapAdapter<R> tailMap(Integer fromKey) {
        return new BuildReferenceMapAdapter<>(loader, core.tailMap(fromKey, true));
    }

    @Override
    public Integer firstKey() {
        return core.firstKey();
    }

    @Override
    public Integer lastKey() {
        return core.lastKey();
    }

    public BuildReferenceMapAdapter<R> descendingMap() {
        return new BuildReferenceMapAdapter<>(loader, core.descendingMap());
    }

    public Map.Entry<Integer, R> firstEntry() {
        return entrySet().stream().findFirst().orElse(null);
    }

    public Map.Entry<Integer, R> lastEntry() {
        return descendingMap().firstEntry();
    }

    @Override
    public Set<Integer> keySet() {
        return core.keySet();
    }

    @Override
    public Set<Map.Entry<Integer, R>> entrySet() {
       return new EntrySetAdapter<>(loader, core.entrySet());
    }

    @Override
    public int size() {
        return core.size();
    }

    @Override
    public boolean isEmpty() {
        return core.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return core.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        R val;
        int buildNum;
        // try to find buildRef for this build
        try {
            //noinspection unchecked
            val = (R) value;
            buildNum = loader.resolveBuildNumber(val);
        } catch (ClassCastException e) {
            return false;
        }

        BuildReference<R> ref = core.get(buildNum);

        if (ref == null) {
            return false;
        }
        // if found, resolve it and check that value is equal
        R currentValue = loader.resolveBuildRef(ref);
        return Objects.equals(currentValue, val);
    }

    @Override
    public R get(Object key) {
        return loader.resolveBuildRef(core.get(key));
    }

    private static class  EntrySetAdapter<R> extends AbstractSet<Map.Entry<Integer, R>> {
        private final Set<Map.Entry<Integer, BuildReference<R>>> core;
        private final Loader<R> loader;

        protected EntrySetAdapter(Loader<R> loader, Set<Map.Entry<Integer, BuildReference<R>>> core) {
            this.core = core;
            this.loader = loader;
        }

        @Override
        public int size() {
            return core.size();
        }

        @Override
        public boolean isEmpty() {
            return core.isEmpty();
        }

        @Override
        public Iterator<Map.Entry<Integer, R>> iterator() {
            // silently drop unloadable builds, as if we didn't have them in this collection in the first place
            // this shouldn't be indistinguishable from concurrent modifications to the collection
            return Iterators.removeNull(new AdaptedIterator<>(core.iterator()) {
                @Override
                protected Map.Entry<Integer, R> adapt(Map.Entry<Integer, BuildReference<R>> coreEntry) {
                    BuildReference<R> ref = coreEntry.getValue();

                    Map.Entry<Integer, R> resolvedEntry = null;
                    if (!ref.isSet()) {
                        R r = loader.resolveBuildRef(ref);
                        // load not loaded or unloadable build
                        if (r == null) {
                            return null;
                        }

                        resolvedEntry = new AbstractMap.SimpleEntry<>(coreEntry.getKey(), r);
                    }

                    if (ref.isUnloadable()) {
                        return null;
                    }

                    return new Entry<>(loader, coreEntry, resolvedEntry);
                }
            });
        }

        @Override
        public Spliterator<Map.Entry<Integer, R>> spliterator() {
            return Spliterators.spliteratorUnknownSize(
                    iterator(), Spliterator.DISTINCT | Spliterator.ORDERED | Spliterator.IMMUTABLE);
        }
    }

    private static class Entry<Integer, R> implements Map.Entry<Integer, R> {
        private final Map.Entry<Integer, BuildReference<R>> coreEntry;
        private final Loader<R> loader;

        private Map.Entry<Integer, R> resolvedEntry;

        Entry(Loader<R> loader,  Map.Entry<Integer, BuildReference<R>> coreEntry, Map.Entry<Integer, R> resolvedEntry) {
            this.loader = loader;
            this.coreEntry = coreEntry;
            this.resolvedEntry = resolvedEntry;
        }

        private Map.Entry<Integer, R> getResolvedEntry() {
            Map.Entry<Integer, R> entry = resolvedEntry;

            if (entry == null) {
                entry = new AbstractMap.SimpleEntry<>(getKey(), loader.resolveBuildRef(coreEntry.getValue()));
                resolvedEntry = entry;
            }
            return entry;
        }

        @Override
        public Integer getKey() {
            return coreEntry.getKey();
        }

        @Override
        public R getValue() {
            return getResolvedEntry().getValue();
        }

        @Override
        public R setValue(R value) {
            // Readonly adapter
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return getResolvedEntry().toString();
        }

        @Override
        public boolean equals(Object o) {
            return (o instanceof Map.Entry<?, ?>) && getResolvedEntry().equals(o);
        }

        @Override
        public int hashCode() {
            return getResolvedEntry().hashCode();
        }
    }

    interface Loader<R> {
        /**
         * Resolve BuildReference and return referent build
         *
         * @param ref BuildReference reference to build
         * @return resolved build, null if ref is null, or ref can't be resolved
         */
        R resolveBuildRef(BuildReference<R> ref);

        /**
         * Resolve build number for build object.
         * This method exists just because current implementation delegates resolving build number
         * to {@link AbstractLazyLoadRunMap}
         *
         * @return build number
         */
        int resolveBuildNumber(R r);
    }
}
