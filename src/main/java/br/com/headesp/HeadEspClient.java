package br.com.headesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

public final class HeadEspClient implements ClientModInitializer {
    private static final int RADIUS = 32;
    private static final int MAX_VERTICAL = 20;
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
            if (++ticks % 12 == 0) scan(client);
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
            PoseStack poses = context.matrices();
            if (poses == null || context.consumers() == null) return;
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            var lines = context.consumers().getBuffer(RenderType.lines());
            poses.pushPose();
            poses.translate(-cam.x, -cam.y, -cam.z);
            for (BlockPos pos : VISIBLE) {
                if (IGNORED.contains(pos)) continue;
                LevelRenderer.renderLineBox(poses, lines,
                    pos.getX() + 0.245, pos.getY() + 0.245, pos.getZ() + 0.245,
                    pos.getX() + 0.755, pos.getY() + 0.755, pos.getZ() + 0.755,
                    1.0f, 0.05f, 0.05f, 1.0f);
            }
            poses.popPose();
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
        Set<BlockPos> found = new HashSet<>();
        int bottom = Math.max(level.getMinY(), center.getY() - MAX_VERTICAL);
        int top = Math.min(level.getMaxY() - 1, center.getY() + MAX_VERTICAL);
        for (int x = center.getX() - RADIUS; x <= center.getX() + RADIUS; x++) {
            for (int z = center.getZ() - RADIUS; z <= center.getZ() + RADIUS; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                for (int y = bottom; y <= top; y++) {
                    p.set(x, y, z);
                    if (isPlayerHead(level, p) && !IGNORED.contains(p)) found.add(p.immutable());
                }
            }
        }
        VISIBLE.clear();
        VISIBLE.addAll(found);
    }
}
