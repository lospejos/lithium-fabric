package me.jellysquid.mods.lithium.common.entity;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.jellysquid.mods.lithium.common.entity.nearby.EntityWithNearbyListener;
import me.jellysquid.mods.lithium.common.entity.nearby.NearbyEntityListener;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;

import java.util.ArrayList;
import java.util.List;

public class EntityTrackerEngine {
    private final Long2ObjectOpenHashMap<TrackedEntityList> sections = new Long2ObjectOpenHashMap<>();

    public void onEntityAdded(int x, int y, int z, LivingEntity entity) {
        if (this.addEntity(x, y, z, entity)) {
            if (entity instanceof EntityWithNearbyListener) {
                this.addListener(x, y, z, ((EntityWithNearbyListener) entity).getListener());
            }
        }
    }

    public void onEntityRemoved(int x, int y, int z, LivingEntity entity) {
        if (this.removeEntity(x, y, z, entity)) {
            if (entity instanceof EntityWithNearbyListener) {
                this.removeListener(x, y, z, ((EntityWithNearbyListener) entity).getListener());
            }
        }
    }

    public void onEntityMoved(int aX, int aY, int aZ, int bX, int bY, int bZ, LivingEntity entity) {
        if (this.removeEntity(aX, aY, aZ, entity) && this.addEntity(bX, bY, bZ, entity)) {
            if (entity instanceof EntityWithNearbyListener) {
                this.moveListener(aX, aY, aZ, bX, bY, bZ, ((EntityWithNearbyListener) entity).getListener());
            }
        }
    }

    private boolean addEntity(int x, int y, int z, LivingEntity entity) {
        return this.getOrCreateList(x, y, z).addTrackedEntity(entity);
    }

    private boolean removeEntity(int x, int y, int z, LivingEntity entity) {
        TrackedEntityList list = this.getList(x, y, z);

        if (list == null) {
            return false;
        }

        return list.removeTrackedEntity(entity);
    }

    private void addListener(int x, int y, int z, NearbyEntityListener listener) {
        int r = listener.getChunkRange();

        if (r == 0) {
            return;
        }

        for (int x2 = x - r; x2 <= x + r; x2++) {
            for (int y2 = y - r; y2 <= y + r; y2++) {
                for (int z2 = z - r; z2 <= z + r; z2++) {
                    TrackedEntityList list = this.getOrCreateList(x2, y2, z2);
                    list.addListener(listener);
                }
            }
        }
    }

    private void removeListener(int x, int y, int z, NearbyEntityListener listener) {
        int r = listener.getChunkRange();

        if (r == 0) {
            return;
        }

        for (int x2 = x - r; x2 <= x + r; x2++) {
            for (int y2 = y - r; y2 <= y + r; y2++) {
                for (int z2 = z - r; z2 <= z + r; z2++) {
                    TrackedEntityList list = this.getList(x2, y2, z2);

                    if (list == null) {
                        throw new IllegalStateException("There is no list of listeners despite being within the range of another listener");
                    }

                    list.removeListener(listener);
                }
            }
        }
    }

    // Faster implementation which avoids removing from/adding to every list twice on an entity move event
    private void moveListener(int aX, int aY, int aZ, int bX, int bY, int bZ, NearbyEntityListener listener) {
        int radius = listener.getChunkRange();

        if (radius == 0) {
            return;
        }

        BlockBox before = new BlockBox(aX - radius, aY - radius, aZ - radius, aX + radius, aY + radius, aZ + radius);
        BlockBox after = new BlockBox(aX - radius, aY - radius, aZ - radius, bX + radius, bY + radius, bZ + radius);

        this.moveListener(before, after, listener);
    }

    private void moveListener(BlockBox before, BlockBox after, NearbyEntityListener listener) {
        BlockBox merged = new BlockBox(before);
        merged.encompass(after);

        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int x = merged.minX; x <= merged.maxX; x++) {
            for (int y = merged.minY; y <= merged.maxY; y++) {
                for (int z = merged.minZ; z <= merged.maxZ; z++) {
                    pos.set(x, y, z);

                    boolean leaving = before.contains(pos);
                    boolean entering = after.contains(pos);

                    // Nothing to change
                    if (leaving == entering) {
                        continue;
                    }

                    if (leaving) {
                        // The listener has left the chunk
                        TrackedEntityList list = this.getList(x, y, z);

                        if (list == null) {
                            throw new IllegalStateException("Expected there to be a listener list while moving entity but there was none");
                        }

                        list.removeListener(listener);
                    } else {
                        // The listener has entered the chunk
                        TrackedEntityList list = this.getOrCreateList(x, y, z);
                        list.addListener(listener);
                    }
                }
            }
        }
    }

    private TrackedEntityList getOrCreateList(int x, int y, int z) {
        return this.sections.computeIfAbsent(encode(x, y, z), TrackedEntityList::new);
    }

    private TrackedEntityList getList(int x, int y, int z) {
        return this.sections.get(encode(x, y, z));
    }

    private static long encode(int x, int y, int z) {
        return ChunkSectionPos.asLong(x, y, z);
    }

    private class TrackedEntityList {
        private final List<LivingEntity> entities = new ArrayList<>();
        private final List<NearbyEntityListener> listeners = new ArrayList<>();

        private final long key;

        private boolean removed = false;

        private TrackedEntityList(long key) {
            this.key = key;
        }

        public boolean isEmpty() {
            return this.entities.isEmpty() && this.listeners.isEmpty();
        }

        public boolean isRemoved() {
            return this.removed;
        }

        public void markRemoved() {
            this.removed = true;
        }

        public void addListener(NearbyEntityListener listener) {
            for (LivingEntity entity : this.entities) {
                listener.onEntityEnteredRange(entity);
            }

            this.listeners.add(listener);
        }

        public void removeListener(NearbyEntityListener listener) {
            if (this.listeners.remove(listener)) {
                for (LivingEntity entity : this.entities) {
                    listener.onEntityLeftRange(entity);
                }

                this.checkEmpty();
            }
        }

        public boolean addTrackedEntity(LivingEntity entity) {
            for (NearbyEntityListener listener : this.listeners) {
                listener.onEntityEnteredRange(entity);
            }

            return this.entities.add(entity);
        }

        public boolean removeTrackedEntity(LivingEntity entity) {
            boolean ret = this.entities.remove(entity);

            if (ret) {
                for (NearbyEntityListener listener : this.listeners) {
                    listener.onEntityLeftRange(entity);
                }

                this.checkEmpty();
            }

            return ret;
        }

        private void checkEmpty() {
            if (!this.isRemoved() && this.isEmpty()) {
                EntityTrackerEngine.this.sections.remove(this.key);

                this.markRemoved();
            }
        }
    }
}
