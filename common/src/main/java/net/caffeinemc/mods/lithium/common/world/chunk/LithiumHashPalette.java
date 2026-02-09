package net.caffeinemc.mods.lithium.common.world.chunk;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.world.level.chunk.MissingPaletteEntryException;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;
import org.jetbrains.annotations.NotNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static it.unimi.dsi.fastutil.Hash.FAST_LOAD_FACTOR;

/**
 * Generally provides better performance over the vanilla {@link net.minecraft.world.level.chunk.HashMapPalette} when calling
 * {@link LithiumHashPalette#idFor(Object)} through using a faster backing map and reducing pointer chasing.
 */
public class LithiumHashPalette<T> implements Palette<T> {
    private static final int ABSENT_VALUE = -1;
    private static final Logger LOGGER = LogManager.getLogger(LithiumHashPalette.class);

    private final IdMap<T> idList;
    private final PaletteResize<T> resizeHandler;
    private final int indexBits;

    private final Reference2IntOpenHashMap<T> table;
    private T[] entries;
    private int size = 0;

    private LithiumHashPalette(IdMap<T> idList, PaletteResize<T> resizeHandler, int indexBits, T[] entries, Reference2IntOpenHashMap<T> table, int size) {
        this.idList = idList;
        this.resizeHandler = resizeHandler;
        this.indexBits = indexBits;
        this.entries = entries;
        this.table = table;
        this.size = size;
    }

    public LithiumHashPalette(IdMap<T> idList, int bits, PaletteResize<T> resizeHandler, List<T> list) {
        this(idList, bits, resizeHandler);

        for (T t : list) {
            this.addEntry(t);
        }
    }

    @SuppressWarnings("unchecked")
    public LithiumHashPalette(IdMap<T> idList, int bits, PaletteResize<T> resizeHandler) {
        this.idList = idList;
        this.indexBits = bits;
        this.resizeHandler = resizeHandler;

        int capacity = 1 << bits;

        this.entries = (T[]) new Object[capacity];
        this.table = new Reference2IntOpenHashMap<>(capacity, FAST_LOAD_FACTOR);
        this.table.defaultReturnValue(ABSENT_VALUE);
    }

    @Override
    public int idFor(@NotNull T obj) {
        int id = this.table.getInt(obj);

        if (id == ABSENT_VALUE) {
            id = this.computeEntry(obj);
        }

        return id;
    }

    @Override
    public boolean maybeHas(@NotNull Predicate<T> predicate) {
        for (int i = 0; i < this.size; ++i) {
            if (predicate.test(this.entries[i])) {
                return true;
            }
        }

        return false;
    }

    private int computeEntry(T obj) {
        int id = this.addEntry(obj);

        if (id >= 1 << this.indexBits) {
            if (this.resizeHandler == null) {
                throw new IllegalStateException("Cannot grow");
            } else {
                id = this.resizeHandler.onResize(this.indexBits + 1, obj);
            }
        }

        return id;
    }

    private int addEntry(T obj) {
        int nextId = this.size;

        if (nextId >= this.entries.length) {
            this.resize(this.size);
        }

        this.table.put(obj, nextId);
        this.entries[nextId] = obj;

        this.size++;

        return nextId;
    }

    private void resize(int neededCapacity) {
        this.entries = Arrays.copyOf(this.entries, HashCommon.nextPowerOfTwo(neededCapacity + 1));
    }

    @Override
    public @NotNull T valueFor(int id) {
        T[] entries = this.entries;

        // 修复：添加边界检查，处理无效索引
        if (id < 0 || id >= entries.length) {
            return handleInvalidIndex(id);
        }
        
        T entry = entries[id];
        if (entry != null) {
            return entry;
        } else {
            return handleInvalidIndex(id);
        }
    }
    
    private T handleInvalidIndex(int invalidId) {
        // 尝试返回一个安全的默认值而不是崩溃
        
        // 1. 首先尝试返回空气方块（通常ID为0）
        T air = this.idList.byId(0);
        if (air != null) {
            LOGGER.warn("[Lithium] Invalid palette index {} requested. Palette size: {}, capacity: {}. Falling back to air.",
                    invalidId, this.size, 1 << this.indexBits);
            return air;
        }
        
        // 2. 如果没有空气方块，尝试返回第一个非空条目
        for (int i = 0; i < this.size; i++) {
            T entry = this.entries[i];
            if (entry != null) {
                LOGGER.warn("[Lithium] Invalid palette index {} requested. Falling back to entry at index {}: {}",
                        invalidId, i, entry);
                return entry;
            }
        }
        
        // 3. 如果所有都失败，抛出详细的异常
        throw this.missingPaletteEntryCrash(invalidId);
    }

    private ReportedException missingPaletteEntryCrash(int id) {
        try {
            throw new MissingPaletteEntryException(id);
        } catch (MissingPaletteEntryException e) {
            CrashReport crashReport = CrashReport.forThrowable(e, "[Lithium] Getting Palette Entry");
            CrashReportCategory crashReportCategory = crashReport.addCategory("Chunk section");
            crashReportCategory.setDetail("IndexBits", this.indexBits);
            crashReportCategory.setDetail("Size", this.size);
            crashReportCategory.setDetail("Capacity", 1 << this.indexBits);
            crashReportCategory.setDetail("RequestedIndex", id);
            
            // 构建更详细的条目信息
            StringBuilder entriesStr = new StringBuilder();
            entriesStr.append(this.entries.length).append(" Elements: [");
            for (int i = 0; i < Math.min(this.entries.length, 200); i++) { // 限制长度
                if (i > 0) entriesStr.append(", ");
                if (this.entries[i] == null) {
                    entriesStr.append("null");
                } else {
                    entriesStr.append(this.entries[i].toString());
                }
            }
            if (this.entries.length > 200) {
                entriesStr.append(", ... (").append(this.entries.length - 200).append(" more)");
            }
            entriesStr.append("]");
            crashReportCategory.setDetail("Entries", entriesStr.toString());
            
            crashReportCategory.setDetail("Table", this.table.size() + " Elements: " + this.table);
            
            // 添加诊断信息
            crashReportCategory.setDetail("Diagnosis", 
                "Index " + id + " is out of bounds (valid range: 0-" + (this.size - 1) + "). " +
                "This indicates corrupted chunk data or mod incompatibility.");
            
            return new ReportedException(crashReport);
        }
    }

    @Override
    public void read(FriendlyByteBuf buf) {
        this.clear();

        int entryCount = buf.readVarInt();
        
        // 修复：确保读取的条目数量不超过容量
        int maxCapacity = 1 << this.indexBits;
        if (entryCount > maxCapacity) {
            LOGGER.error("[Lithium] Palette entry count {} exceeds capacity {}, truncating",
                    entryCount, maxCapacity);
            entryCount = maxCapacity;
        }

        for (int i = 0; i < entryCount; ++i) {
            int globalId = buf.readVarInt();
            T obj = this.idList.byId(globalId);
            if (obj != null) {
                this.addEntry(obj);
            } else {
                // 记录并跳过无效的全局ID
                LOGGER.warn("[Lithium] Skipping invalid global ID {} at position {} in palette",
                        globalId, i);
                // 添加一个占位符条目，保持索引一致
                this.addEntry(null);
            }
        }
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        int size = this.size;
        buf.writeVarInt(size);

        for (int i = 0; i < size; ++i) {
            T value = this.valueFor(i);
            if (value != null) {
                buf.writeVarInt(this.idList.getId(value));
            } else {
                // 写入空气方块的ID作为占位符
                buf.writeVarInt(0);
            }
        }
    }

    @Override
    public int getSerializedSize() {
        int size = VarInt.getByteSize(this.size);

        for (int i = 0; i < this.size; ++i) {
            T value = this.valueFor(i);
            if (value != null) {
                size += VarInt.getByteSize(this.idList.getId(value));
            } else {
                size += VarInt.getByteSize(0); // 空气方块ID
            }
        }

        return size;
    }

    @Override
    public int getSize() {
        return this.size;
    }

    @Override
    public @NotNull Palette<T> copy() {
        return new LithiumHashPalette<>(this.idList, this.resizeHandler, this.indexBits, this.entries.clone(), this.table.clone(), this.size);
    }

    private void clear() {
        Arrays.fill(this.entries, null);
        this.table.clear();
        this.size = 0;
    }

    public List<T> getElements() {
        T[] copy = Arrays.copyOf(this.entries, this.size);
        return Arrays.asList(copy);
    }

    public static <A> Palette<A> create(int bits, IdMap<A> idList, PaletteResize<A> listener, List<A> list) {
        return new LithiumHashPalette<>(idList, bits, listener, list);
    }
}
