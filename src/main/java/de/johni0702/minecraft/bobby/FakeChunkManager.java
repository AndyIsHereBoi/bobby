package de.johni0702.minecraft.bobby;

import de.johni0702.minecraft.bobby.ext.ChunkLightProviderExt;
import de.johni0702.minecraft.bobby.ext.ClientChunkManagerExt;
import de.johni0702.minecraft.bobby.ext.ClientPlayNetworkHandlerExt;
import de.johni0702.minecraft.bobby.mixin.BiomeAccessAccessor;
import de.johni0702.minecraft.bobby.util.FileSystemUtils;
import io.netty.util.concurrent.DefaultThreadFactory;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.level.storage.LevelStorage;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

public class FakeChunkManager {
    private static final String FALLBACK_LEVEL_NAME = "bobby-fallback";
    private static final MinecraftClient client = MinecraftClient.getInstance();

    private final ClientWorld world;
    private final ClientChunkManager clientChunkManager;
    private final ClientChunkManagerExt clientChunkManagerExt;
    private final Worlds worlds;
    private final FakeChunkStorage storage;
    // 1.18.2: FakeChunkStorage lookups are synchronous (they only became async in MC 1.19), so the entries in here
    // return already-completed futures, which keeps upstream's shape and algorithm identical.
    private final List<Function<ChunkPos, CompletableFuture<Optional<NbtCompound>>>> storages = new ArrayList<>();
    private int ticksSinceLastSave;

    private final Long2ObjectMap<WorldChunk> fakeChunks = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());
    private final VisibleChunksTracker chunkTracker = new VisibleChunksTracker();
    private final Long2LongMap toBeUnloaded = new Long2LongOpenHashMap();
    // Contains chunks in order to be unloaded. We keep the chunk and time so we can cross-reference it with
    // [toBeUnloaded] to see if the entry has since been removed / the time reset. This way we do not need
    // to remove entries from the middle of the queue.
    private final Deque<Pair<Long, Long>> unloadQueue = new ArrayDeque<>();

    // There unfortunately is only a synchronous api for loading chunks (even though that one just waits on a
    // CompletableFuture, annoying but oh well), so we call that blocking api from a separate thread pool.
    // The size of the pool must be sufficiently large such that there is always at least one query operation
    // running, as otherwise the storage io worker will start writing chunks which slows everything down to a crawl.
    private static final ExecutorService loadExecutor = Executors.newFixedThreadPool(8, new DefaultThreadFactory("bobby-loading", true));
    private final Long2ObjectMap<LoadingJob> loadingJobs = new Long2ObjectLinkedOpenHashMap<>();

    // Executor for serialization and saving. Single-threaded so we do not have to worry about races between multiple saves for the same chunk.
    private static final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(new DefaultThreadFactory("bobby-saving", true));

    private final Long2ObjectMap<FingerprintJob> fingerprintJobs = new Long2ObjectLinkedOpenHashMap<>();

    public FakeChunkManager(ClientWorld world, ClientChunkManager clientChunkManager) {
        this.world = world;
        this.clientChunkManager = clientChunkManager;
        this.clientChunkManagerExt = (ClientChunkManagerExt) clientChunkManager;

        String serverName = getCurrentWorldOrServerName();
        if (serverName.isEmpty()) {
            serverName = "<empty>";
        }
        BobbyConfig config = Bobby.getInstance().getConfig();

        String serverName = getCurrentWorldOrServerName(((ClientWorldAccessor) world).getNetworkHandler());
        long seedHash = ((BiomeAccessAccessor) world.getBiomeAccess()).getSeed();
        RegistryKey<World> worldKey = world.getRegistryKey();
        Identifier worldId = worldKey.getValue();
        Path storagePath = client.runDirectory
                .toPath()
                .resolve(".bobby");
        if (oldFolderExists(storagePath, serverName)) {
            storagePath = storagePath.resolve(serverName);
        } else {
            storagePath = FileSystemUtils.resolveSafeDirectoryName(storagePath, serverName);
        }
        storagePath = storagePath
                .resolve(seedHash + "")
                .resolve(worldId.getNamespace())
                .resolve(worldId.getPath());

        if (config.isDynamicMultiWorld()) {
            worlds = Worlds.getFor(storagePath);
            storages.add(worlds::loadTag);

            storage = null;
        } else {
            FakeChunkStorage primaryStorage = FakeChunkStorage.getFor(storagePath, true);
            storage = primaryStorage;
            storages.add(pos -> loadTag(primaryStorage, pos));

            worlds = null;
        }

        LevelStorage levelStorage = client.getLevelStorage();
        if (levelStorage.levelExists(FALLBACK_LEVEL_NAME)) {
            try (LevelStorage.Session session = levelStorage.createSession(FALLBACK_LEVEL_NAME)) {
                Path worldDirectory = session.getWorldDirectory(worldKey);
                Path regionDirectory = worldDirectory.resolve("region");
                FakeChunkStorage fallbackStorage = FakeChunkStorage.getFor(regionDirectory, false);
                storages.add(pos -> loadTag(fallbackStorage, pos));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static boolean oldFolderExists(Path bobbyFolder, String name) {
        try {
            return Files.exists(bobbyFolder.resolve(name));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    public WorldChunk getChunk(int x, int z) {
        return fakeChunks.get(ChunkPos.toLong(x, z));
    }

    public FakeChunkStorage getStorage() {
        return storage;
    }

    public Worlds getWorlds() {
        return worlds;
    }

    public void update(boolean blocking, BooleanSupplier shouldKeepTicking) {
        update(blocking, shouldKeepTicking, client.options.viewDistance);
    }

    private void update(boolean blocking, BooleanSupplier shouldKeepTicking, int newViewDistance) {
        // Once a minute, force chunks to disk
        if (++ticksSinceLastSave > 20 * 60) {
            // completeAll is blocking, so we run it on the io pool
            Util.getIoWorkerExecutor().execute(worlds != null ? worlds::saveAll : storage::completeAll);

            ticksSinceLastSave = 0;
        }

        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }

        BobbyConfig config = Bobby.getInstance().getConfig();
        long time = Util.getMeasuringTimeMs();

        List<LoadingJob> newJobs = new ArrayList<>();
        ChunkPos playerChunkPos = player.getChunkPos();
        int newCenterX =  playerChunkPos.x;
        int newCenterZ = playerChunkPos.z;
        chunkTracker.update(newCenterX, newCenterZ, newViewDistance, chunkPos -> {
            // Chunk is now outside view distance, can be unloaded / cancelled
            cancelLoad(chunkPos);
            toBeUnloaded.put(chunkPos, time);
            unloadQueue.add(Pair.of(chunkPos, time));
        }, chunkPos -> {
            // Chunk is now inside view distance, load it
            int x = ChunkPos.getPackedX(chunkPos);
            int z = ChunkPos.getPackedZ(chunkPos);

            // We want this chunk, so don't unload it if it's still here
            toBeUnloaded.remove(chunkPos);
            // Not removing it from [unloadQueue], we check [toBeUnloaded] when we poll it.

            // If there already is a chunk loaded, there's nothing to do
            if (clientChunkManager.getChunk(x, z, ChunkStatus.FULL, false) != null) {
                return;
            }

            // All good, load it
            int distanceX = Math.abs(x - newCenterX);
            int distanceZ = Math.abs(z - newCenterZ);
            int distanceSquared = distanceX * distanceX + distanceZ * distanceZ;
            newJobs.add(new LoadingJob(x, z, distanceSquared));
        });

        if (!newJobs.isEmpty()) {
            newJobs.sort(LoadingJob.BY_DISTANCE);
            newJobs.forEach(job -> {
                loadingJobs.put(ChunkPos.toLong(job.x, job.z), job);
                loadExecutor.execute(job);
            });
        }

        // Anything remaining in the set is no longer needed and can now be unloaded
        long unloadTime = time - config.getUnloadDelaySecs() * 1000L;
        int countSinceLastThrottleCheck = 0;
        while (true) {
            Pair<Long, Long> next = unloadQueue.pollFirst();
            if (next == null) {
                break;
            }
            long chunkPos = next.getLeft();
            long queuedTime = next.getRight();

            if (queuedTime > unloadTime) {
                // Unload is still being delayed, put the entry back into the queue
                // and be done for this update.
                unloadQueue.addFirst(next);
                break;
            }

            long actualQueuedTime = toBeUnloaded.remove(chunkPos);
            if (actualQueuedTime != queuedTime) {
                // The chunk has either been un-queued or re-queued.
                if (actualQueuedTime != 0) {
                    // If it was re-queued, put it back in the map.
                    toBeUnloaded.put(chunkPos, actualQueuedTime);
                }
                // Either way, skip it for now and go to the next entry.
                continue;
            }

            // This chunk is due for unloading
            unload(ChunkPos.getPackedX(chunkPos), ChunkPos.getPackedZ(chunkPos), false);

            if (countSinceLastThrottleCheck++ > 10) {
                countSinceLastThrottleCheck = 0;
                if (!shouldKeepTicking.getAsBoolean()) {
                    break;
                }
            }
        }

        ObjectIterator<FingerprintJob> fingerprintJobsIter = this.fingerprintJobs.values().iterator();
        jobs: while (fingerprintJobsIter.hasNext()) {
            FingerprintJob fingerprintJob = fingerprintJobsIter.next();

            while (fingerprintJob.result == 0) {
                // Still loading, should we wait for it?
                if (blocking) {
                    try {
                        // This code path is not the default one, it doesn't need super high performance, and having the
                        // workers notify the main thread just for it is probably not worth it.
                        //noinspection BusyWait
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    continue jobs;
                }
            }

            // Done loading
            fingerprintJobsIter.remove();

            assert worlds != null; // fingerprint jobs should only be queued when multi-world support is active
            worlds.observeChunk(world, fingerprintJob.chunk.getPos(), fingerprintJob.result);

            if (countSinceLastThrottleCheck++ > 10) {
                countSinceLastThrottleCheck = 0;
                if (!shouldKeepTicking.getAsBoolean()) {
                    break;
                }
            }
        }

        ObjectIterator<LoadingJob> loadingJobsIter = this.loadingJobs.values().iterator();
        jobs: while (loadingJobsIter.hasNext()) {
            LoadingJob loadingJob = loadingJobsIter.next();

            //noinspection OptionalAssignedToNull
            while (loadingJob.result == null) {
                // Still loading, should we wait for it?
                if (blocking) {
                    try {
                        // This code path is not the default one, it doesn't need super high performance, and having the
                        // workers notify the main thread just for it is probably not worth it.
                        //noinspection BusyWait
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    continue jobs;
                }
            }

            // Done loading
            loadingJobsIter.remove();

            client.getProfiler().push("loadFakeChunk");
            loadingJob.complete();
            client.getProfiler().pop();

            if (!shouldKeepTicking.getAsBoolean()) {
                break;
            }
        }

        if (worlds != null) {
            boolean didMerge = worlds.update();
            if (didMerge) {
                loadMissingChunksFromCache();
            }
        }
    }

    public void loadMissingChunksFromCache() {
        // We do this by temporarily reducing the client view distance to 0. That will unload all chunks and then try
        // to re-load them (by canceling the unload when they were already loaded, or from the cache when they are
        // missing).
        update(false, () -> false, 0);
        update(false, () -> false);
    }

    public boolean shouldBeLoaded(int x, int z) {
        return chunkTracker.isInViewDistance(x, z);
    }

    // 1.18.2: FakeChunkStorage lookups are synchronous, so wrap them in an already-completed future.
    private static CompletableFuture<Optional<NbtCompound>> loadTag(FakeChunkStorage storage, ChunkPos pos) {
        try {
            return CompletableFuture.completedFuture(Optional.ofNullable(storage.loadTag(pos)));
        } catch (IOException e) {
            e.printStackTrace();
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private @Nullable NbtCompound loadTag(int x, int z) {
        ChunkPos chunkPos = new ChunkPos(x, z);
        for (Function<ChunkPos, CompletableFuture<Optional<NbtCompound>>> storage : storages) {
            NbtCompound tag = storage.apply(chunkPos).join().orElse(null);
            if (tag != null) {
                return tag;
            }
        }
        return null;
    }

    public void load(int x, int z, WorldChunk chunk) {
        fakeChunks.put(ChunkPos.toLong(x, z), chunk);

        world.resetChunkColor(new ChunkPos(x, z));

        for (int i = world.getBottomSectionCoord(); i < world.getTopSectionCoord(); i++) {
            world.scheduleBlockRenders(x, i, z);
        }

        clientChunkManagerExt.bobby_onFakeChunkAdded(x, z);
    }

    public boolean unload(int x, int z, boolean willBeReplaced) {
        long chunkPos = ChunkPos.toLong(x, z);
        cancelLoad(chunkPos);
        WorldChunk chunk = fakeChunks.remove(chunkPos);
        if (chunk != null) {
            chunk.clear();

            LightingProvider lightingProvider = clientChunkManager.getLightingProvider();
            ChunkLightProviderExt blockLightProvider = ChunkLightProviderExt.get(lightingProvider.get(LightType.BLOCK));
            ChunkLightProviderExt skyLightProvider = ChunkLightProviderExt.get(lightingProvider.get(LightType.SKY));
            // See the comment above the bobby_queueUnloadFakeLightDataTask implementation
            Runnable unloadLightData = () -> {
                for (int i = 0; i < chunk.getSectionArray().length; i++) {
                    int y = world.sectionIndexToCoord(i);
                    if (blockLightProvider != null) {
                        blockLightProvider.bobby_removeSectionData(ChunkSectionPos.asLong(x, y, z));
                    }
                    if (skyLightProvider != null) {
                        skyLightProvider.bobby_removeSectionData(ChunkSectionPos.asLong(x, y, z));
                    }
                }
            };
            // 1.18.2: the network handler is reachable straight from the client (upstream reads it off the world)
            ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
            if (willBeReplaced && networkHandler != null) {
                ClientPlayNetworkHandlerExt.get(networkHandler).bobby_queueUnloadFakeLightDataTask(() -> {
                    if (fakeChunks.containsKey(chunkPos)) {
                        // Real chunk has been unloaded in the meantime and is now a fake chunk again, that fake
                        // chunk will have loaded its own fake light, so we shouldn't unload it.
                        return;
                    }
                    unloadLightData.run();
                });
            } else {
                unloadLightData.run();
            }

            clientChunkManagerExt.bobby_onFakeChunkRemoved(x, z);

            return true;
        }
        return false;
    }

    private void cancelLoad(long chunkPos) {
        LoadingJob loadingJob = loadingJobs.remove(chunkPos);
        if (loadingJob != null) {
            loadingJob.cancelled = true;
        }
    }

    public Supplier<WorldChunk> save(WorldChunk chunk) {
        Pair<WorldChunk, Supplier<WorldChunk>> copy = ChunkSerializer.shallowCopy(chunk);
        fingerprint(copy.getLeft());
        LightingProvider lightingProvider = chunk.getWorld().getLightingProvider();
        FakeChunkStorage storage = worlds != null ? worlds.getCurrentStorage() : this.storage;
        saveExecutor.execute(() -> {
            NbtCompound nbt = ChunkSerializer.serialize(copy.getLeft(), lightingProvider);
            nbt.putLong("age", System.currentTimeMillis()); // fallback in case meta gets corrupted
            storage.save(chunk.getPos(), nbt);
        });
        return copy.getRight();
    }

    public void fingerprint(WorldChunk chunk) {
        if (worlds == null) {
            return;
        }

        long chunkCoord = chunk.getPos().toLong();

        FingerprintJob job = fingerprintJobs.get(chunkCoord);
        if (job != null) {
            job.cancelled = true;
        }

        job = new FingerprintJob(chunk);
        fingerprintJobs.put(chunkCoord, job);
        Util.getMainWorkerExecutor().execute(job);
    }

    // 1.18.2: the world's network handler is not exposed, so this takes no argument and derives the name from the
    // client instead (upstream reads it off the world via ClientWorldAccessor).
    private static String getCurrentWorldOrServerName() {
        IntegratedServer integratedServer = client.getServer();
        if (integratedServer != null) {
            return integratedServer.getSaveProperties().getLevelName();
        }

        // Needs to be before the ServerInfo because that one will contain a random IP
        if (client.isConnectedToRealms()) {
            return "realms";
        }

        ServerInfo serverInfo = client.getCurrentServerEntry();
        if (serverInfo != null) {
            return serverInfo.address.replace(':', '_');
        }

        return "unknown";
    }

    public String getDebugString() {
        return "F: " + fakeChunks.size() + " L: " + loadingJobs.size() + " U: " + toBeUnloaded.size() + " C: " + fingerprintJobs.size();
    }

    public Collection<WorldChunk> getFakeChunks() {
        return fakeChunks.values();
    }

    private class LoadingJob implements Runnable {
        private final int x;
        private final int z;
        private final int distanceSquared;
        private volatile boolean cancelled;
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType") // null while loading, empty() if no chunk was found
        private volatile Optional<Supplier<WorldChunk>> result;

        public LoadingJob(int x, int z, int distanceSquared) {
            this.x = x;
            this.z = z;
            this.distanceSquared = distanceSquared;
        }

        @Override
        public void run() {
            if (cancelled) {
                return;
            }
            result = Optional.ofNullable(loadTag(x, z))
                    .map(it -> ChunkSerializer.deserialize(new ChunkPos(x, z), it, world).getRight());
        }

        public void complete() {
            result.ifPresent(it -> load(x, z, it.get()));
        }

        public static final Comparator<LoadingJob> BY_DISTANCE = Comparator.comparing(it -> it.distanceSquared);
    }

    private static class FingerprintJob implements Runnable {
        private final WorldChunk chunk;
        private volatile boolean cancelled;
        private volatile long result;

        private FingerprintJob(WorldChunk chunk) {
            this.chunk = chunk;
        }

        @Override
        public void run() {
            if (cancelled) {
                return;
            }
            result = ChunkSerializer.fingerprint(chunk);
        }
    }
}
