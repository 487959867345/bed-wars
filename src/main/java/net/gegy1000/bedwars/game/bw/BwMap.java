package net.gegy1000.bedwars.game.bw;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import net.gegy1000.bedwars.BedWarsMod;
import net.gegy1000.bedwars.util.BlockBounds;
import net.gegy1000.bedwars.custom.CustomizableEntity;
import net.gegy1000.bedwars.custom.CustomEntities;
import net.gegy1000.bedwars.custom.CustomEntity;
import net.gegy1000.bedwars.game.GameTeam;
import net.gegy1000.bedwars.map.GameMap;
import net.gegy1000.bedwars.map.provider.MapProvider;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LocalDifficulty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

// TODO: Map should not hold state! For generators it should just store the location
public final class BwMap {
//    private static final MapProvider MAP_PROVIDER = new PathMapProvider(new Identifier(BedWarsMod.ID, "bed_wars_1"));
    private static final MapProvider MAP_PROVIDER = new TestProceduralMapProvider();

    private final GameMap map;
    private final Multimap<String, BlockBounds> regions = HashMultimap.create();

    private final Map<GameTeam, TeamSpawn> teamSpawns = new HashMap<>();
    private final Map<GameTeam, TeamRegions> teamRegions = new HashMap<>();

    private final Collection<Generator> generators = new ArrayList<>();

    private final Collection<Entity> shopKeepers = new ArrayList<>();

    private BwMap(GameMap map) {
        this.map = map;
    }

    public static CompletableFuture<BwMap> create(ServerWorld world, BlockPos origin) {
        return MAP_PROVIDER.createAt(world, origin)
                .thenApplyAsync(map -> {
                    BwMap bwMap = new BwMap(map);
                    bwMap.initializeMap(map);
                    return bwMap;
                }, world.getServer());
    }

    private void initializeMap(GameMap map) {
        map.getRegions().forEach(region -> {
            String marker = region.getMarker();
            this.regions.put(marker, region.getBounds());
        });

        this.getRegions("diamond_spawn").forEach(bounds -> {
            this.generators.add(new Generator(bounds).setPool(GeneratorPool.DIAMOND).maxItems(6));
        });

        this.getRegions("emerald_spawn").forEach(bounds -> {
            this.generators.add(new Generator(bounds).setPool(GeneratorPool.EMERALD).maxItems(3));
        });

        for (GameTeam team : BedWars.TEAMS) {
            TeamRegions regions = TeamRegions.read(team, this);

            if (regions.spawn != null) {
                TeamSpawn teamSpawn = new TeamSpawn(regions.spawn);
                this.teamSpawns.put(team, teamSpawn);
                this.generators.add(teamSpawn.generator);
            } else {
                BedWarsMod.LOGGER.warn("Missing spawn for {}", team.getKey());
            }

            this.teamRegions.put(team, regions);
        }
    }

    public void spawnShopkeepers(ServerWorld world) {
        for (GameTeam team : BedWars.TEAMS) {
            TeamRegions regions = this.getTeamRegions(team);

            if (regions.teamShop != null) {
                Entity entity = this.spawn(world, EntityType.VILLAGER, CustomEntities.TEAM_SHOP, regions.teamShop);
                this.shopKeepers.add(entity);
            }

            if (regions.itemShop != null) {
                Entity entity = this.spawn(world, EntityType.VILLAGER, CustomEntities.ITEM_SHOP, regions.itemShop);
                this.shopKeepers.add(entity);
            }
        }
    }

    private Entity spawn(ServerWorld world, EntityType<?> type, CustomEntity custom, BlockBounds bounds) {
        Vec3d center = bounds.getCenter();

        Entity entity = type.create(world);
        if (entity == null) {
            return null;
        }

        if (entity instanceof CustomizableEntity) {
            ((CustomizableEntity) entity).setCustomEntity(custom);
        }

        entity.refreshPositionAndAngles(center.x, bounds.getMin().getY(), center.z, 0.0F, 0.0F);

        if (entity instanceof MobEntity) {
            MobEntity mob = (MobEntity) entity;
            mob.setAiDisabled(true);
            mob.setInvulnerable(true);
            mob.setCustomNameVisible(true);

            LocalDifficulty difficulty = world.getLocalDifficulty(mob.getBlockPos());
            mob.initialize(world, difficulty, SpawnReason.COMMAND, null, null);
        }

        world.spawnEntity(entity);
        return entity;
    }

    @Nullable
    public TeamSpawn getTeamSpawn(GameTeam team) {
        return this.teamSpawns.get(team);
    }

    @Nonnull
    public TeamRegions getTeamRegions(GameTeam team) {
        return this.teamRegions.getOrDefault(team, TeamRegions.EMPTY);
    }

    public Stream<TeamSpawn> teamSpawns() {
        return this.teamSpawns.values().stream();
    }

    public Stream<Generator> generators() {
        return this.generators.stream();
    }

    public Collection<BlockBounds> getRegions(String key) {
        return this.regions.get(key);
    }

    @Nullable
    public BlockBounds getFirstRegion(String key) {
        Iterator<BlockBounds> iterator = this.regions.get(key).iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    public boolean contains(BlockPos pos) {
        return this.map.getBounds().contains(pos);
    }

    public CompletableFuture<Void> delete() {
        this.shopKeepers.forEach(Entity::remove);
        return this.map.delete();
    }

    public boolean isStandardBlock(BlockPos pos) {
        return this.map.isStandardBlock(pos);
    }

    public Vec3d getCenter() {
        return this.map.getBounds().getCenter();
    }

    public static class TeamSpawn {
        public static final int MAX_LEVEL = 3;

        private final BlockBounds region;
        private final Generator generator;

        private int level = 1;

        TeamSpawn(BlockBounds region) {
            this.region = region;
            this.generator = new Generator(region)
                    .setPool(poolForLevel(this.level))
                    .maxItems(64)
                    .allowDuplication();
        }

        public void placePlayer(ServerPlayerEntity player) {
            player.fallDistance = 0.0F;

            Vec3d center = this.region.getCenter();
            player.teleport(center.x, center.y + 0.5, center.z);
        }

        public void setLevel(int level) {
            this.level = Math.max(level, this.level);
            this.generator.setPool(poolForLevel(this.level));
        }

        public int getLevel() {
            return this.level;
        }

        private static GeneratorPool poolForLevel(int level) {
            if (level == 1) {
                return GeneratorPool.TEAM_LVL_1;
            } else if (level == 2) {
                return GeneratorPool.TEAM_LVL_2;
            } else if (level == 3) {
                return GeneratorPool.TEAM_LVL_3;
            }
            return GeneratorPool.TEAM_LVL_1;
        }
    }

    public static class Generator {
        private final BlockBounds bounds;
        private GeneratorPool pool;

        private long lastItemSpawn;

        private int maxItems = 4;
        private boolean allowDuplication;

        public Generator(BlockBounds bounds) {
            this.bounds = bounds;
        }

        public Generator setPool(GeneratorPool pool) {
            this.pool = pool;
            return this;
        }

        public Generator allowDuplication() {
            this.allowDuplication = true;
            return this;
        }

        public Generator maxItems(int maxItems) {
            this.maxItems = maxItems;
            return this;
        }

        public void tick(ServerWorld world, BwState state) {
            if (this.pool == null) return;

            long time = world.getTime();

            if (time - this.lastItemSpawn > this.pool.getSpawnInterval()) {
                this.spawnItems(world, state);
                this.lastItemSpawn = time;
            }
        }

        private void spawnItems(ServerWorld world, BwState state) {
            Random random = world.random;
            ItemStack stack = this.pool.sample(random).copy();

            Box box = this.bounds.toBox();

            int itemCount = 0;
            for (ItemEntity entity : world.getEntities(EntityType.ITEM, box.expand(1.0), entity -> true)) {
                itemCount += entity.getStack().getCount();
            }

            if (itemCount >= this.maxItems) {
                return;
            }

            Box spawnBox = box.expand(-0.5, 0.0, -0.5);
            double x = spawnBox.minX + (spawnBox.maxX - spawnBox.minX) * random.nextDouble();
            double y = spawnBox.minY + 0.5;
            double z = spawnBox.minZ + (spawnBox.maxZ - spawnBox.minZ) * random.nextDouble();

            ItemEntity itemEntity = new ItemEntity(world, x, y, z, stack);
            itemEntity.setVelocity(Vec3d.ZERO);

            if (this.allowDuplication) {
                if (this.giveItems(world, state, itemEntity)) {
                    return;
                }
            }

            world.spawnEntity(itemEntity);
        }

        private boolean giveItems(ServerWorld world, BwState state, ItemEntity entity) {
            List<ServerPlayerEntity> players = world.getEntities(ServerPlayerEntity.class, this.bounds.toBox(), state::isParticipant);
            for (ServerPlayerEntity player : players) {
                ItemStack stack = entity.getStack();

                player.giveItemStack(stack.copy());
                player.networkHandler.sendPacket(entity.createSpawnPacket());
                player.networkHandler.sendPacket(new ItemPickupAnimationS2CPacket(entity.getEntityId(), player.getEntityId(), stack.getCount()));

                player.inventory.markDirty();
            }

            return !players.isEmpty();
        }
    }

    public static class TeamRegions {
        static final TeamRegions EMPTY = new TeamRegions(null, null, null, null, null, null);

        public final BlockBounds base;
        public final BlockBounds spawn;
        public final BlockBounds bed;
        public final BlockBounds itemShop;
        public final BlockBounds teamShop;
        public final BlockBounds teamChest;

        TeamRegions(BlockBounds spawn, BlockBounds bed, BlockBounds base, BlockBounds itemShop, BlockBounds teamShop, BlockBounds teamChest) {
            this.spawn = spawn;
            this.bed = bed;
            this.base = base;
            this.itemShop = itemShop;
            this.teamShop = teamShop;
            this.teamChest = teamChest;
        }

        static TeamRegions read(GameTeam team, BwMap map) {
            String teamKey = team.getKey();

            // TODO: consolidate the team tags and check for ones contained within the team_base
            BlockBounds base = map.getFirstRegion(teamKey + "_base");
            BlockBounds spawn = map.getFirstRegion(teamKey + "_spawn");
            BlockBounds bed = map.getFirstRegion(teamKey + "_bed");
            BlockBounds itemShop = map.getFirstRegion(teamKey + "_item_shop");
            BlockBounds teamShop = map.getFirstRegion(teamKey + "_team_shop");
            BlockBounds teamChest = map.getFirstRegion(teamKey + "_chest");

            return new TeamRegions(spawn, bed, base, itemShop, teamShop, teamChest);
        }
    }
}
