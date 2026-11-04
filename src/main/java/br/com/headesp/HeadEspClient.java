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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;

import java.util.HashSet;
import java.util.Set;

public final class HeadEspClient implements ClientModInitializer {
    private static final int RADIUS = 128;
    private static final int MAX_VERTICAL = 32;
    private static final Set<BlockPos> VISIBLE = new HashSet<>();
    private static final Set<BlockPos> IGNORED = new HashSet<>();
    private static Level lastLevel;
    private static boolean enabled = true;
    private static int ticks = 0;
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
            if (++ticks % 20 == 0) scan(client);
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
            try (var ignored = mc.levelRenderer.collectPerFrameGizmos()) {
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

    private static void scan(Minecraft client) {
        Level level = client.level;
        if (level == null || client.player == null) return;

        BlockPos center = client.player.blockPosition();
        int minX = center.getX() - RADIUS;
        int maxX = center.getX() + RADIUS;
        int minZ = center.getZ() - RADIUS;
        int maxZ = center.getZ() + RADIUS;
        int minY = Math.max(level.getMinY(), center.getY() - MAX_VERTICAL);
        int maxY = Math.min(level.getMaxY() - 1, center.getY() + MAX_VERTICAL);

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        Set<BlockPos> found = new HashSet<>();

        // Percorre somente block entities dos chunks já carregados.
        // Isso evita consultar milhões de blocos individualmente.
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkAccess chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;

                for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                    int x = pos.getX();
                    int y = pos.getY();
                    int z = pos.getZ();

                    if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
                    if (y < minY || y > maxY) continue;
                    if (IGNORED.contains(pos)) continue;

                    var block = level.getBlockState(pos).getBlock();
                    if (block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD) {
                        found.add(pos.immutable());
                    }
                }
            }
        }

        VISIBLE.clear();
        VISIBLE.addAll(found);
    }
}
