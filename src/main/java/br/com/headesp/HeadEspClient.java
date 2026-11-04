package br.com.headesp;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class HeadEspClient implements ClientModInitializer {
    // O raio não faz o mod procurar chunks que o servidor não enviou.
    private static final int RADIUS_CHUNKS = 8;
    private static final int MAX_VERTICAL = 32;
    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final Set<BlockPos> VISIBLE = new HashSet<>();
    private static final Set<BlockPos> IGNORED = new HashSet<>();

    private static World lastWorld;
    private static boolean enabled = true;
    private static int tickCounter;
    private static KeyBinding toggleKey;
    private static KeyBinding resetKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.headesp.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.headesp"));
        resetKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.headesp.reset", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "category.headesp"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world != lastWorld) {
                lastWorld = client.world;
                VISIBLE.clear();
                IGNORED.clear();
                tickCounter = 0;
            }

            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                if (!enabled) VISIBLE.clear();
                notifyPlayer(client, "Head ESP: " + (enabled ? "§aATIVADO" : "§cDESATIVADO"));
            }
            while (resetKey.wasPressed()) {
                IGNORED.clear();
                notifyPlayer(client, "§aCabeças ignoradas restauradas!");
                tickCounter = SCAN_INTERVAL_TICKS;
            }

            if (client.world == null || client.player == null || !enabled) return;
            if (++tickCounter >= SCAN_INTERVAL_TICKS) {
                tickCounter = 0;
                scanLoadedChunks(client);
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient) markIgnored(world, hit.getBlockPos());
            return ActionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient) markIgnored(world, pos);
            return ActionResult.PASS;
        });

        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!enabled || client.world == null || VISIBLE.isEmpty()) return;

            MatrixStack matrices = context.matrixStack();
            if (matrices == null) return;
            var camera = context.camera().getPos();
            VertexConsumerProvider.Immediate consumers =
                client.getBufferBuilders().getEntityVertexConsumers();
            VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());

            matrices.push();
            matrices.translate(-camera.x, -camera.y, -camera.z);
            // Renderização sem teste de profundidade: os contornos aparecem através de paredes.
            RenderSystem.disableDepthTest();
            try {
                for (BlockPos pos : VISIBLE) {
                    Box box = new Box(
                        pos.getX() + 0.245, pos.getY() + 0.245, pos.getZ() + 0.245,
                        pos.getX() + 0.755, pos.getY() + 0.755, pos.getZ() + 0.755
                    );
                    WorldRenderer.drawBox(matrices, lines, box, 1.0f, 0.05f, 0.05f, 1.0f);
                }
            } finally {
                consumers.draw(RenderLayer.getLines());
                RenderSystem.enableDepthTest();
                matrices.pop();
            }
        });
    }

    private static void notifyPlayer(MinecraftClient client, String message) {
        if (client.player != null) client.player.sendMessage(Text.literal(message), true);
    }

    private static boolean isPlayerHead(World world, BlockPos pos) {
        var block = world.getBlockState(pos).getBlock();
        return block == Blocks.PLAYER_HEAD || block == Blocks.PLAYER_WALL_HEAD;
    }

    private static void markIgnored(World world, BlockPos pos) {
        if (!isPlayerHead(world, pos)) return;
        BlockPos saved = pos.toImmutable();
        IGNORED.add(saved);
        VISIBLE.remove(saved);
    }

    /**
     * Procura apenas Block Entities dos chunks carregados.
     * Cabeças de jogador são Skull Block Entities, então não precisamos
     * percorrer milhões de coordenadas bloco a bloco.
     */
    private static void scanLoadedChunks(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        var world = client.world;
        BlockPos center = client.player.getBlockPos();
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int bottom = Math.max(world.getBottomY(), center.getY() - MAX_VERTICAL);
        int top = Math.min(world.getTopY() - 1, center.getY() + MAX_VERTICAL);
        Set<BlockPos> found = new HashSet<>();

        for (int cx = centerChunkX - RADIUS_CHUNKS; cx <= centerChunkX + RADIUS_CHUNKS; cx++) {
            for (int cz = centerChunkZ - RADIUS_CHUNKS; cz <= centerChunkZ + RADIUS_CHUNKS; cz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;

                for (Map.Entry<BlockPos, ?> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (pos.getY() < bottom || pos.getY() > top) continue;
                    if (IGNORED.contains(pos)) continue;
                    if (isPlayerHead(world, pos)) found.add(pos.toImmutable());
                }
            }
        }

        VISIBLE.clear();
        VISIBLE.addAll(found);
    }
}
