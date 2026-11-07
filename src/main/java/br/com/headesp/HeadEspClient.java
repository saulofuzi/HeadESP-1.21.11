package br.com.headesp;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.AABB;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class HeadEspClient implements ClientModInitializer {
    private static final int RADIUS = 128;
    private static final int CHUNKS_PER_TICK = 16;
    private static final long RADIUS_SQUARED = (long) RADIUS * RADIUS;
    private static final Set<BlockPos> VISIBLE = new HashSet<>();
    private static final Set<BlockPos> IGNORED = new HashSet<>();
    private static Level lastLevel;
    private static boolean enabled = true;
    private static final List<Long> SCAN_CHUNKS = new ArrayList<>();
    private static final Set<BlockPos> SCAN_RESULTS = new HashSet<>();
    private static int scanCursor = 0;
    private static int scanCenterChunkX = Integer.MIN_VALUE;
    private static int scanCenterChunkZ = Integer.MIN_VALUE;
    private static BlockPos scanCenter = BlockPos.ZERO;
    private static KeyMapping toggleKey;
    private static KeyMapping resetKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("headesp", "controls"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.headesp.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_H, category));
        resetKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.headesp.reset", InputConstants.Type.KEYSYM, InputConstants.KEY_J, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != lastLevel) {
                lastLevel = client.level;
                IGNORED.clear();
                VISIBLE.clear();
                SCAN_CHUNKS.clear();
                SCAN_RESULTS.clear();
                scanCursor = 0;
                scanCenterChunkX = Integer.MIN_VALUE;
                scanCenterChunkZ = Integer.MIN_VALUE;
            }
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                notifyPlayer(client, "Head ESP: " + (enabled ? "§aATIVADO" : "§cDESATIVADO"));
            }
            while (resetKey.consumeClick()) {
                IGNORED.clear();
                notifyPlayer(client, "§aCabeças ignoradas restauradas!");
            }
            if (client.level == null || client.player == null || !enabled) {
                VISIBLE.clear();
                return;
            }
            scanStep(client);
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide()) markIgnored(world, hit.getBlockPos());
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide()) markIgnored(world, pos);
            return InteractionResult.PASS;
        });

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            if (!enabled || mc.level == null || VISIBLE.isEmpty()) return;
            try (var ignored = mc.levelRenderer.collectPerFrameRenderThreadGizmos()) {
                for (BlockPos pos : VISIBLE) {
                    if (IGNORED.contains(pos)) continue;
                    AABB box = new AABB(
                        pos.getX() + 0.245, pos.getY() + 0.245, pos.getZ() + 0.245,
                        pos.getX() + 0.755, pos.getY() + 0.755, pos.getZ() + 0.755
                    );
                    Gizmos.cuboid(box, GizmoStyle.stroke(0xFFFF0D0D, 2.0f)).setAlwaysOnTop();
                }
            }
        });
    }

    private static void notifyPlayer(Minecraft client, String message) {
        if (client.player != null) client.player.displayClientMessage(Component.literal(message), true);
    }

    private static boolean isPlayerHead(Level level, BlockPos pos) {
        var block = level.getBlockState(pos).getBlock();
        return block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD;
    }

    private static void markIgnored(Level level, BlockPos pos) {
        if (!isPlayerHead(level, pos)) return;
        BlockPos saved = pos.immutable();
        IGNORED.add(saved);
        VISIBLE.remove(saved);
    }

    private static void rebuildScan(BlockPos center) {
        SCAN_CHUNKS.clear();
        SCAN_RESULTS.clear();
        scanCursor = 0;
        scanCenter = center.immutable();

        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        scanCenterChunkX = centerChunkX;
        scanCenterChunkZ = centerChunkZ;

        int chunkRadius = (RADIUS + 15) >> 4;
        for (int x = centerChunkX - chunkRadius; x <= centerChunkX + chunkRadius; x++) {
            for (int z = centerChunkZ - chunkRadius; z <= centerChunkZ + chunkRadius; z++) {
                SCAN_CHUNKS.add((((long) x) << 32) | (z & 0xffffffffL));
            }
        }
    }

    private static void scanStep(Minecraft client) {
        Level level = client.level;
        if (level == null || client.player == null) return;

        BlockPos playerPos = client.player.blockPosition();
        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        if (playerChunkX != scanCenterChunkX || playerChunkZ != scanCenterChunkZ || SCAN_CHUNKS.isEmpty()) {
            rebuildScan(playerPos);
        }

        int processed = 0;
        while (scanCursor < SCAN_CHUNKS.size() && processed < CHUNKS_PER_TICK) {
            long packed = SCAN_CHUNKS.get(scanCursor++);
            int chunkX = (int) (packed >> 32);
            int chunkZ = (int) packed;

            ChunkAccess chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            processed++;
            if (chunk == null) continue;

            for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                long dx = (long) pos.getX() - scanCenter.getX();
                long dy = (long) pos.getY() - scanCenter.getY();
                long dz = (long) pos.getZ() - scanCenter.getZ();

                if (dx * dx + dy * dy + dz * dz > RADIUS_SQUARED) continue;
                if (IGNORED.contains(pos)) continue;

                var block = level.getBlockState(pos).getBlock();
                if (block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD) {
                    SCAN_RESULTS.add(pos.immutable());
                }
            }
        }

        // Publica o resultado somente quando a varredura inteira termina,
        // evitando picos de processamento em um único tick.
        if (scanCursor >= SCAN_CHUNKS.size()) {
            VISIBLE.clear();
            VISIBLE.addAll(SCAN_RESULTS);
            SCAN_RESULTS.clear();
            scanCursor = 0;
        }
    }
}
