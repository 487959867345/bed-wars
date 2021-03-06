package xyz.nucleoid.bedwars.game.active;

import xyz.nucleoid.bedwars.game.BwMap;
import xyz.nucleoid.bedwars.game.active.upgrade.PlayerUpgrades;
import xyz.nucleoid.bedwars.game.active.upgrade.UpgradeType;
import xyz.nucleoid.plasmid.game.player.GameTeam;
import xyz.nucleoid.plasmid.util.PlayerRef;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import javax.annotation.Nullable;

public final class BwParticipant {
    private final ServerWorld world;
    public final PlayerRef playerRef;
    public final GameTeam team;

    public final PlayerUpgrades upgrades;

    AttackRecord lastAttack;

    BwMap.TeamSpawn respawningAt;
    long respawnTime = -1;
    boolean eliminated;

    BwParticipant(BwActive game, ServerPlayerEntity player, GameTeam team) {
        this.world = player.getServerWorld();
        this.playerRef = PlayerRef.of(player);
        this.team = team;

        this.upgrades = new PlayerUpgrades(game, this);

        this.upgrades.addAt(UpgradeType.ARMOR, 0);
        this.upgrades.addAt(UpgradeType.SWORD, 0);
        this.upgrades.add(UpgradeType.PICKAXE);
        this.upgrades.add(UpgradeType.AXE);
        this.upgrades.add(UpgradeType.SHEARS);
    }

    public void startRespawning(BwMap.TeamSpawn spawn) {
        this.respawnTime = this.world.getTime() + BwActive.RESPAWN_TICKS;
        this.respawningAt = spawn;
    }

    public void stopRespawning() {
        this.respawningAt = null;
        this.respawnTime = -1;
    }

    public boolean isRespawning() {
        return this.respawningAt != null;
    }

    @Nullable
    public ServerPlayerEntity player() {
        return this.playerRef.getEntity(this.world);
    }

    public boolean isAlive() {
        return !this.eliminated && this.isOnline();
    }

    public boolean isOnline() {
        return this.playerRef.isOnline(this.world);
    }
}
