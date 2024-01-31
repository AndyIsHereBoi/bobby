package de.johni0702.minecraft.bobby;

import com.mojang.serialization.Codec;
import de.johni0702.minecraft.bobby.util.RegionPos;
import io.netty.util.concurrent.DefaultThreadFactory;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.MessageType;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryEntry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.storage.StorageIoWorker;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FakeChunkStorage extends VersionedChunkStorage {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Map<Path, FakeChunkStorage> active = new HashMap<>();

    public static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");

    public static FakeChunkStorage getFor(Path directory, boolean writeable) {
        synchronized (active) {
            return active.computeIfAbsent(directory, f -> new FakeChunkStorage(directory, writeable));
        }
    }

    public static void closeAll() {
        synchronized (active) {
            for (FakeChunkStorage storage : active.values()) {
                try {
                    storage.close();
                } catch (IOException e) {
                    LOGGER.error("Failed to close storage", e);
                }
            }
            active.clear();
        }
    }

    private final Path directory;
    private final boolean writeable;
    private final AtomicBoolean sentUpgradeNotification = new AtomicBoolean();
    @Nullable
    private final LastAccessFile lastAccess;

    private FakeChunkStorage(Path directory, boolean writeable) {
        super(directory, MinecraftClient.getInstance().getDataFixer(), false);

        this.directory = directory;
        this.writeable = writeable;

        LastAccessFile lastAccess = null;
        if (writeable) {
            try {
                Files.createDirectories(directory);

                lastAccess = new LastAccessFile(directory);
            } catch (IOException e) {
                LOGGER.error("Failed to read last_access file:", e);
            }
        }
        this.lastAccess = lastAccess;
    }

    @Override
    public void close() throws IOException {
        super.close();

        if (lastAccess != null) {
            int deleteUnusedRegionsAfterDays = Bobby.getInstance().getConfig().getDeleteUnusedRegionsAfterDays();
            if (deleteUnusedRegionsAfterDays >= 0) {
                for (long entry : lastAccess.pollRegionsOlderThan(deleteUnusedRegionsAfterDays)) {
                    int x = ChunkPos.getPackedX(entry);
                    int z = ChunkPos.getPackedZ(entry);
                    Files.deleteIfExists(directory.resolve("r." + x + "." + z + ".mca"));
                }
            }

            lastAccess.close();
        }
    }

    public void save(ChunkPos pos, NbtCompound chunk) {
        if (lastAccess != null) {
            lastAccess.touchRegion(pos.getRegionX(), pos.getRegionZ());
        }
        setNbt(pos, chunk);
    }

    public @Nullable NbtCompound loadTag(ChunkPos pos) throws IOException {
        NbtCompound nbt = getNbt(pos);
        if (nbt != null && lastAccess != null) {
            lastAccess.touchRegion(pos.getRegionX(), pos.getRegionZ());
        }
        if (nbt != null && nbt.getInt("DataVersion") != SharedConstants.getGameVersion().getSaveVersion().getId()) {
            if (sentUpgradeNotification.compareAndSet(false, true)) {
                MinecraftClient client = MinecraftClient.getInstance();
                client.submit(() -> {
                    TranslatableText text = new TranslatableText(writeable ? "bobby.upgrade.required" : "bobby.upgrade.fallback_world");
                    client.submit(() -> client.inGameHud.addChatMessage(MessageType.SYSTEM, text, Util.NIL_UUID));
                });
            }
            return null;
        }
        return nbt;
    }

    public static List<RegionPos> getRegions(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .map(REGION_FILE_PATTERN::matcher)
                    .filter(Matcher::matches)
                    .map(it -> new RegionPos(Integer.parseInt(it.group(1)), Integer.parseInt(it.group(2))))
                    .collect(Collectors.toList());
        }
    }

    public void upgrade(RegistryKey<World> worldKey, BiConsumer<Integer, Integer> progress) throws IOException {
        Optional<RegistryKey<Codec<? extends ChunkGenerator>>> generatorKey =
                Optional.of(Registry.CHUNK_GENERATOR.getKey(FlatChunkGenerator.CODEC).orElseThrow());

        List<ChunkPos> chunks = getRegions(directory).stream().flatMap(RegionPos::getContainedChunks).toList();

        AtomicInteger done = new AtomicInteger();
        AtomicInteger total = new AtomicInteger(chunks.size());
        progress.accept(done.get(), total.get());

        StorageIoWorker io = (StorageIoWorker) getWorker();

        // We ideally split the actual work of upgrading the chunk NBT across multiple threads, leaving a few for MC
        int workThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        ExecutorService workExecutor = Executors.newFixedThreadPool(workThreads, new DefaultThreadFactory("bobby-upgrade-worker", true));

        try {
            for (ChunkPos chunkPos : chunks) {
                workExecutor.submit(() -> {
                    NbtCompound nbt;
                    try {
                        nbt = io.getNbt(chunkPos);
                    } catch (IOException e) {
                        LOGGER.warn("Error reading chunk " + chunkPos.x + "/" + chunkPos.z + ":", e);
                        nbt = null;
                    }

                    if (nbt == null) {
                        progress.accept(done.get(), total.decrementAndGet());
                        return;
                    }

                    nbt = updateChunkNbt(worldKey, null, nbt, generatorKey);

                    io.setResult(chunkPos, nbt).join();

                    progress.accept(done.incrementAndGet(), total.get());
                });
            }
        } finally {
            workExecutor.shutdown();
        }

        try {
            //noinspection ResultOfMethodCallIgnored
            workExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        progress.accept(done.get(), total.get());
    }
}
