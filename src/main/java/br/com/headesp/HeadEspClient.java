package br.com.headesp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix4f;

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
    private static final List<Long> SCAN_CHUNKS = new ArrayList<>();
    private static final Set<BlockPos> SCAN_RESULTS = new HashSet<>();
    private static World lastWorld;
    private static boolean enabled = true;
    private static int scanCursor;
    private static int scanCenterChunkX = Integer.MIN_VALUE;
    private static int scanCenterChunkZ = Integer.MIN_VALUE;
    private static BlockPos scanCenter = BlockPos.ORIGIN;
    private static KeyBinding toggleKey;
    private static KeyBinding resetKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.headesp.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.category.headesp.controls"));
        resetKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.headesp.reset", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.category.headesp.controls"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world != lastWorld) {
                lastWorld = client.world;
                IGNORED.clear();
                VISIBLE.clear();
                SCAN_CHUNKS.clear();
                SCAN_RESULTS.clear();
                scanCursor = 0;
                scanCenterChunkX = Integer.MIN_VALUE;
                scanCenterChunkZ = Integer.MIN_VALUE;
            }
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                notifyPlayer(client, "Head ESP: " + (enabled ? "§aATIVADO" : "§cDESATIVADO"));
            }
            while (resetKey.wasPressed()) {
                IGNORED.clear();
                notifyPlayer(client, "§aCabeças ignoradas restauradas!");
            }
            if (client.world == null || client.player == null || !enabled) {
                VISIBLE.clear();
                return;
            }
            scanStep(client);
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient && isPlayerHead(world, hit.getBlockPos())) {
                markIgnored(world, hit.getBlockPos());
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient && isPlayerHead(world, pos)) {
                markIgnored(world, pos);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        WorldRenderEvents.LAST.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!enabled || client.world == null || VISIBLE.isEmpty()) return;

            MatrixStack matrices = new MatrixStack();
            var cameraPos = context.camera().getPos();
            matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            Matrix4f matrix = matrices.peek().getPositionMatrix();

            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.lineWidth(2.0F);
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);

            BufferBuilder buffer = Tessellator.getInstance().begin(
                VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
            for (BlockPos pos : VISIBLE) {
                if (IGNORED.contains(pos)) continue;
                drawBox(buffer, matrix, new Box(
                    pos.getX() + 0.20, pos.getY() + 0.20, pos.getZ() + 0.20,
                    pos.getX() + 0.80, pos.getY() + 0.80, pos.getZ() + 0.80));
            }
            BufferRenderer.drawWithGlobalProgram(buffer.end());
            RenderSystem.lineWidth(1.0F);
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
        });
    }

    private static void drawBox(BufferBuilder b, Matrix4f m, Box box) {
        double x1=box.minX, y1=box.minY, z1=box.minZ, x2=box.maxX, y2=box.maxY, z2=box.maxZ;
        line(b,m,x1,y1,z1,x2,y1,z1); line(b,m,x2,y1,z1,x2,y1,z2);
        line(b,m,x2,y1,z2,x1,y1,z2); line(b,m,x1,y1,z2,x1,y1,z1);
        line(b,m,x1,y2,z1,x2,y2,z1); line(b,m,x2,y2,z1,x2,y2,z2);
        line(b,m,x2,y2,z2,x1,y2,z2); line(b,m,x1,y2,z2,x1,y2,z1);
        line(b,m,x1,y1,z1,x1,y2,z1); line(b,m,x2,y1,z1,x2,y2,z1);
        line(b,m,x2,y1,z2,x2,y2,z2); line(b,m,x1,y1,z2,x1,y2,z2);
    }

    private static void line(BufferBuilder b, Matrix4f m, double x1,double y1,double z1,double x2,double y2,double z2) {
        b.vertex(m,(float)x1,(float)y1,(float)z1).color(255,12,12,255);
        b.vertex(m,(float)x2,(float)y2,(float)z2).color(255,12,12,255);
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
        SCAN_RESULTS.remove(saved);
    }

    private static void rebuildScan(BlockPos center) {
        SCAN_CHUNKS.clear();
        SCAN_RESULTS.clear();
        scanCursor = 0;
        scanCenter = center.toImmutable();
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        scanCenterChunkX = cx;
        scanCenterChunkZ = cz;
        int chunkRadius = (RADIUS + 15) >> 4;
        for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++) {
            for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) {
                SCAN_CHUNKS.add((((long)x) << 32) | (z & 0xffffffffL));
            }
        }
    }

    private static void scanStep(MinecraftClient client) {
        var world = client.world;
        if (world == null || client.player == null) return;
        BlockPos playerPos = client.player.getBlockPos();
        int cx = playerPos.getX() >> 4, cz = playerPos.getZ() >> 4;
        if (cx != scanCenterChunkX || cz != scanCenterChunkZ || SCAN_CHUNKS.isEmpty()) rebuildScan(playerPos);

        int processed = 0;
        while (scanCursor < SCAN_CHUNKS.size() && processed < CHUNKS_PER_TICK) {
            long packed = SCAN_CHUNKS.get(scanCursor++);
            int chunkX = (int)(packed >> 32), chunkZ = (int)packed;
            WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
            processed++;
            if (chunk == null) continue;
            for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                long dx=(long)pos.getX()-scanCenter.getX();
                long dy=(long)pos.getY()-scanCenter.getY();
                long dz=(long)pos.getZ()-scanCenter.getZ();
                if (dx*dx+dy*dy+dz*dz > RADIUS_SQUARED || IGNORED.contains(pos)) continue;
                if (isPlayerHead(world,pos)) SCAN_RESULTS.add(pos.toImmutable());
            }
        }
        if (scanCursor >= SCAN_CHUNKS.size()) {
            VISIBLE.clear();
            VISIBLE.addAll(SCAN_RESULTS);
            SCAN_RESULTS.clear();
            scanCursor = 0;
        }
    }
}
