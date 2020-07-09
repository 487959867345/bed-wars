package net.gegy1000.bedwars.game;

import net.gegy1000.bedwars.util.BlockBounds;
import net.minecraft.nbt.CompoundTag;

public final class GameRegion {
    private final String marker;
    private final BlockBounds bounds;

    public GameRegion(String marker, BlockBounds bounds) {
        this.marker = marker;
        this.bounds = bounds;
    }

    public String getMarker() {
        return this.marker;
    }

    public BlockBounds getBounds() {
        return this.bounds;
    }

    public CompoundTag serialize(CompoundTag tag) {
        tag.putString("marker", this.marker);
        this.bounds.serialize(tag);
        return tag;
    }

    public static GameRegion deserialize(CompoundTag tag) {
        String marker = tag.getString("marker");
        return new GameRegion(marker, BlockBounds.deserialize(tag));
    }
}
