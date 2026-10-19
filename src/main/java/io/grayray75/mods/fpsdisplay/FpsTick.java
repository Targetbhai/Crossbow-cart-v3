package io.grayray75.mods.fpsdisplay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.UseAction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Auto crossbow cart (client side only).
 *
 * Right-click a block with a sword in the main hand -> place rail, TNT minecart, fire,
 * then shoot the loaded crossbow through the fire into the cart.
 * Runs as a step machine on END_CLIENT_TICK, one action per tick.
 */
public class FpsTick implements ClientModInitializer {

    private enum Step {
        IDLE, SWAP_IN, PLACE_RAIL, WAIT_RAIL, PLACE_CART, WAIT_CART,
        PLACE_FIRE, WAIT_FIRE, SNEAK, SHOOT, RESTORE_SLOT, SWAP_OUT
    }

    private record Plan(BlockPos ground, BlockPos fireSupport) {}

    // Arrow physics (per tick: pos += v; v *= drag; v.y -= gravity)
    private static final double ARROW_SPEED = 3.15;
    private static final double ARROW_DRAG = 0.99;
    private static final double ARROW_GRAVITY = 0.05;
    private static final double SPAWN_DROP = 0.1F; // arrow spawns at eyeY - 0.1F

    private static final int COOLDOWN_TICKS = 20;      // 1 second
    private static final int WAIT_TIMEOUT_TICKS = 40;  // placement verification timeout
    private static final double REACH = 4.5;

    private Step step = Step.IDLE;
    private int cooldown = 0;
    private int waitTicks = 0;

    private BlockPos groundPos;      // block the rail sits on
    private BlockPos railPos;        // rail cell
    private BlockPos fireSupportPos; // block the fire sits on (next to the rail's support)
    private BlockPos firePos;        // fire cell

    private int origSlot;
    private int railSlot, cartSlot, flintSlot, crossbowSlot;

    private final ArrayDeque<int[]> pendingSwaps = new ArrayDeque<>(); // {inventoryIndex, hotbarSlot}
    private final ArrayDeque<int[]> doneSwaps = new ArrayDeque<>();

    private boolean sneakEngaged = false;
    private boolean sneakWasPressed = false;

    @Override
    public void onInitializeClient() {
        UseBlockCallback.EVENT.register(this::onUseBlock);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    // ------------------------------------------------------------------ trigger

    private ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        if (!world.isClient || hand != Hand.MAIN_HAND) return ActionResult.PASS;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != player || client.world == null || client.currentScreen != null) return ActionResult.PASS;
        if (step != Step.IDLE || cooldown > 0) return ActionResult.PASS;
        if (!player.getMainHandStack().isIn(ItemTags.SWORDS)) return ActionResult.PASS;
        if (isUsable(player.getOffHandStack())) return ActionResult.PASS;

        // FAIL cancels the click without sending anything to the server.
        return tryStart(client, client.player, client.world, hit.getBlockPos())
                ? ActionResult.FAIL
                : ActionResult.PASS;
    }

    /** Food, potions, shields, bows, crossbows... anything with a use animation. */
    private static boolean isUsable(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getUseAction() != UseAction.NONE || stack.contains(DataComponentTypes.FOOD));
    }

    private static boolean matches(ItemStack stack, Item item) {
        return stack.isOf(item) && (item != Items.CROSSBOW || CrossbowItem.isCharged(stack));
    }

    /** Checks everything up front. Returns false (and changes nothing) if the run cannot start. */
    private boolean tryStart(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, BlockPos clicked) {
        if (client.interactionManager == null) return false;

        PlayerInventory inv = player.getInventory();
        int sword = inv.selectedSlot;

        // order: rail, tnt minecart, flint and steel, loaded crossbow
        Item[] need = {Items.RAIL, Items.TNT_MINECART, Items.FLINT_AND_STEEL, Items.CROSSBOW};
        int[] slot = {-1, -1, -1, -1};   // hotbar slot to use
        int[] source = {-1, -1, -1, -1}; // inventory index to pull from, -1 if already in hotbar
        boolean[] reserved = new boolean[9];
        reserved[sword] = true;

        for (int i = 0; i < 4; i++) {
            int found = -1;
            for (int s = 0; s < 9 && found < 0; s++) {
                if (s != sword && matches(inv.getStack(s), need[i])) found = s;
            }
            if (found >= 0) {
                slot[i] = found;
                reserved[found] = true;
                continue;
            }
            for (int s = 9; s < 36 && found < 0; s++) {
                if (matches(inv.getStack(s), need[i])) found = s;
            }
            if (found < 0) return false; // missing item -> do nothing
            source[i] = found;
        }

        Plan plan = findSpot(player, world, clicked);
        if (plan == null) return false;

        ArrayDeque<int[]> swaps = new ArrayDeque<>();
        for (int i = 0; i < 4; i++) {
            if (source[i] < 0) continue;
            int dest = -1;
            for (int s = 0; s < 9 && dest < 0; s++) {
                if (!reserved[s] && inv.getStack(s).isEmpty()) dest = s; // prefer an empty slot
            }
            for (int s = 0; s < 9 && dest < 0; s++) {
                if (!reserved[s]) dest = s;
            }
            if (dest < 0) return false;
            reserved[dest] = true;
            slot[i] = dest;
            swaps.add(new int[]{source[i], dest});
        }

        // ---- commit
        origSlot = sword;
        railSlot = slot[0];
        cartSlot = slot[1];
        flintSlot = slot[2];
        crossbowSlot = slot[3];
        groundPos = plan.ground();
        railPos = groundPos.up();
        fireSupportPos = plan.fireSupport();
        firePos = fireSupportPos.up();

        pendingSwaps.clear();
        pendingSwaps.addAll(swaps);
        doneSwaps.clear();
        sneakEngaged = false;
        waitTicks = 0;
        step = pendingSwaps.isEmpty() ? Step.PLACE_RAIL : Step.SWAP_IN;

        // A held right mouse button would otherwise keep using whatever slot we select.
        client.options.useKey.setPressed(false);
        return true;
    }

    // ------------------------------------------------------------------ spot search

    /**
     * Uses the clicked block itself as the rail's support. Does nothing unless it is
     * 3.65 - 4.65 blocks (horizontal) from the player, has a solid top and a free block above,
     * and a fire spot between player and rail exists that the arrow's first tick lands in.
     */
    private Plan findSpot(ClientPlayerEntity player, ClientWorld world, BlockPos clicked) {
        Vec3d feet = player.getPos();
        double dist = Math.hypot(clicked.getX() + 0.5 - feet.x, clicked.getZ() + 0.5 - feet.z);
        if (dist < 3.65 || dist > 4.65) return null; // too close or too far

        if (!solidTop(world, clicked) || !replaceable(world, clicked.up())) return null;

        double sneakEyeY = feet.y + player.getEyeHeight(EntityPose.CROUCHING);
        return tryGround(player, world, feet, clicked, sneakEyeY);
    }

    private Plan tryGround(ClientPlayerEntity player, ClientWorld world, Vec3d feet, BlockPos ground, double sneakEyeY) {
        if (!reachable(player, ground)) return null;

        BlockPos rail = ground.up();
        // estimated cart centre (minecart box is 0.98 x 0.7, sits 0.0625 above the rail cell floor)
        Vec3d center = new Vec3d(rail.getX() + 0.5, rail.getY() + 0.0625 + 0.35, rail.getZ() + 0.5);
        Vec3d spawn = new Vec3d(feet.x, sneakEyeY - SPAWN_DROP, feet.z);

        double pitch = solvePitch(world, player, spawn, center);
        if (Double.isNaN(pitch)) return null;
        double yaw = yawTo(spawn, center);

        // The arrow moves ~3 blocks per tick and fire only ignites it where it ends a tick,
        // so the fire cell must contain the arrow's first end-of-tick position.
        Vec3d first = spawn.add(direction(yaw, pitch).multiply(ARROW_SPEED));
        Box arrowBox = Box.of(first, 0.5, 0.5, 0.5);

        double ex = feet.x - center.x;
        double ez = feet.z - center.z;
        int sx = (int) Math.signum(ex);
        int sz = (int) Math.signum(ez);
        int[][] dirs = Math.abs(ex) >= Math.abs(ez)
                ? new int[][]{{sx, 0}, {0, sz}}
                : new int[][]{{0, sz}, {sx, 0}};

        for (int[] dir : dirs) {
            if (dir[0] == 0 && dir[1] == 0) continue;
            BlockPos support = ground.add(dir[0], 0, dir[1]);
            BlockPos fire = support.up();
            if (!solidTop(world, support) || !world.getBlockState(fire).isAir()) continue;
            if (!reachable(player, support)) continue;
            if (!new Box(fire).intersects(arrowBox)) continue;
            return new Plan(ground, support);
        }
        return null;
    }

    private static boolean solidTop(ClientWorld world, BlockPos pos) {
        return world.getBlockState(pos).isSideSolidFullSquare(world, pos, Direction.UP);
    }

    private static boolean replaceable(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || (state.isReplaceable() && state.getFluidState().isEmpty());
    }

    private static boolean reachable(ClientPlayerEntity player, BlockPos pos) {
        Vec3d e = player.getEyePos();
        double dx = Math.max(Math.max(pos.getX() - e.x, 0), e.x - (pos.getX() + 1));
        double dy = Math.max(Math.max(pos.getY() - e.y, 0), e.y - (pos.getY() + 1));
        double dz = Math.max(Math.max(pos.getZ() - e.z, 0), e.z - (pos.getZ() + 1));
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    // ------------------------------------------------------------------ step machine

    private void onTick(MinecraftClient client) {
        if (cooldown > 0) cooldown--;
        if (step == Step.IDLE) return;

        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || client.interactionManager == null) {
            reset(client);
            return;
        }

        // Never act while a screen is open. Cleanup waits until it closes.
        if (client.currentScreen != null && step != Step.RESTORE_SLOT) {
            if (step != Step.SWAP_OUT) abort(client);
            return;
        }

        switch (step) {
            case SWAP_IN -> swapIn(client, player);
            case PLACE_RAIL -> placeRail(client, player);
            case WAIT_RAIL -> waitFor(client, world.getBlockState(railPos).isIn(BlockTags.RAILS), Step.PLACE_CART);
            case PLACE_CART -> placeCart(client, player);
            case WAIT_CART -> waitFor(client, findCart(world) != null, Step.PLACE_FIRE);
            case PLACE_FIRE -> placeFire(client, player);
            case WAIT_FIRE -> waitFor(client, world.getBlockState(firePos).isIn(BlockTags.FIRE), Step.SNEAK);
            case SNEAK -> sneak(client, player);
            case SHOOT -> shoot(client, player, world);
            case RESTORE_SLOT -> restoreSlot(player);
            case SWAP_OUT -> swapOut(client, player);
            default -> { }
        }
    }

    private void swapIn(MinecraftClient client, ClientPlayerEntity player) {
        int[] swap = pendingSwaps.poll();
        if (swap == null) {
            step = Step.PLACE_RAIL;
            return;
        }
        click(client, player, swap[0], swap[1]);
        doneSwaps.push(swap);
        if (pendingSwaps.isEmpty()) step = Step.PLACE_RAIL;
    }

    private void placeRail(MinecraftClient client, ClientPlayerEntity player) {
        if (!holds(player, railSlot, Items.RAIL)) { abort(client); return; }
        useOnBlock(client, player, railSlot, groundPos);
        waitTicks = 0;
        step = Step.WAIT_RAIL;
    }

    private void placeCart(MinecraftClient client, ClientPlayerEntity player) {
        if (!holds(player, cartSlot, Items.TNT_MINECART)) { abort(client); return; }
        useOnBlock(client, player, cartSlot, railPos);
        waitTicks = 0;
        step = Step.WAIT_CART;
    }

    private void placeFire(MinecraftClient client, ClientPlayerEntity player) {
        if (!holds(player, flintSlot, Items.FLINT_AND_STEEL)) { abort(client); return; }
        useOnBlock(client, player, flintSlot, fireSupportPos);
        waitTicks = 0;
        step = Step.WAIT_FIRE;
    }

    /** Selects the slot and right-clicks the top face of the given block. */
    private void useOnBlock(MinecraftClient client, ClientPlayerEntity player, int hotbarSlot, BlockPos target) {
        player.getInventory().selectedSlot = hotbarSlot;
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(target).add(0, 0.5, 0), Direction.UP, target, false);
        ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        if (result.shouldSwingHand()) player.swingHand(Hand.MAIN_HAND);
    }

    private void waitFor(MinecraftClient client, boolean ok, Step next) {
        if (ok) {
            step = next;
            waitTicks = 0;
        } else if (++waitTicks > WAIT_TIMEOUT_TICKS) {
            abort(client);
        }
    }

    /** Tick A: select crossbow and press sneak. Tick A+1 (shoot) is the only tick the player sneaks. */
    private void sneak(MinecraftClient client, ClientPlayerEntity player) {
        ItemStack bow = player.getInventory().getStack(crossbowSlot);
        if (!bow.isOf(Items.CROSSBOW) || !CrossbowItem.isCharged(bow)) { abort(client); return; }
        player.getInventory().selectedSlot = crossbowSlot;
        pressSneak(client);
        step = Step.SHOOT;
    }

    private void shoot(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
        ItemStack bow = player.getInventory().getStack(crossbowSlot);
        TntMinecartEntity cart = findCart(world);
        if (!bow.isOf(Items.CROSSBOW) || !CrossbowItem.isCharged(bow) || cart == null) {
            abort(client);
            return;
        }
        player.getInventory().selectedSlot = crossbowSlot;

        // Eye height is read live: the player is sneaking right now.
        Vec3d spawn = new Vec3d(player.getX(), player.getEyeY() - SPAWN_DROP, player.getZ());
        Vec3d target = cart.getBoundingBox().getCenter();
        double pitch = solvePitch(world, player, spawn, target);
        if (Double.isNaN(pitch)) { abort(client); return; }

        float yaw = (float) yawTo(spawn, target);
        float pitchF = (float) pitch;

        player.setYaw(yaw);
        player.setPitch(pitchF);
        player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitchF, player.isOnGround()));
        ActionResult result = client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        if (result.shouldSwingHand()) player.swingHand(Hand.MAIN_HAND);

        releaseSneak(client);
        step = Step.RESTORE_SLOT;
    }

    private void restoreSlot(ClientPlayerEntity player) {
        player.getInventory().selectedSlot = origSlot;
        if (doneSwaps.isEmpty()) finish();
        else step = Step.SWAP_OUT;
    }

    private void swapOut(MinecraftClient client, ClientPlayerEntity player) {
        int[] swap = doneSwaps.pop(); // last swap first
        click(client, player, swap[0], swap[1]);
        if (doneSwaps.isEmpty()) finish();
    }

    private void finish() {
        step = Step.IDLE;
        cooldown = COOLDOWN_TICKS;
    }

    /** Stops the run: release sneak, then put the slot and swaps back. */
    private void abort(MinecraftClient client) {
        releaseSneak(client);
        pendingSwaps.clear();
        step = Step.RESTORE_SLOT;
    }

    private void reset(MinecraftClient client) {
        releaseSneak(client);
        pendingSwaps.clear();
        doneSwaps.clear();
        step = Step.IDLE;
        cooldown = COOLDOWN_TICKS;
    }

    // ------------------------------------------------------------------ inventory / input helpers

    private static boolean holds(ClientPlayerEntity player, int hotbarSlot, Item item) {
        return player.getInventory().getStack(hotbarSlot).isOf(item);
    }

    /** SWAP click between an inventory slot and a hotbar slot (same click undoes itself). */
    private void click(MinecraftClient client, ClientPlayerEntity player, int inventoryIndex, int hotbarSlot) {
        int slotId = inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
        client.interactionManager.clickSlot(player.playerScreenHandler.syncId, slotId, hotbarSlot, SlotActionType.SWAP, player);
    }

    private void pressSneak(MinecraftClient client) {
        KeyBinding key = client.options.sneakKey;
        sneakEngaged = true;
        sneakWasPressed = key.isPressed();
        if (!key.isPressed()) key.setPressed(true); // in toggle mode this toggles sneak on
    }

    /** Puts the sneak key back to what the physical key is doing. */
    private void releaseSneak(MinecraftClient client) {
        if (!sneakEngaged) return;
        sneakEngaged = false;
        KeyBinding key = client.options.sneakKey;
        if (client.options.getSneakToggled().getValue()) {
            if (key.isPressed() && !sneakWasPressed) key.setPressed(true); // toggles back off
        } else {
            key.setPressed(physicallyDown(client, key));
        }
    }

    private static boolean physicallyDown(MinecraftClient client, KeyBinding key) {
        InputUtil.Key bound = InputUtil.fromTranslationKey(key.getBoundKeyTranslationKey());
        long window = client.getWindow().getHandle();
        if (bound.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, bound.getCode()) == GLFW.GLFW_PRESS;
        }
        if (bound.getCategory() == InputUtil.Type.KEYSYM) {
            return InputUtil.isKeyPressed(window, bound.getCode());
        }
        return false;
    }

    private TntMinecartEntity findCart(ClientWorld world) {
        List<TntMinecartEntity> carts = world.getEntitiesByClass(
                TntMinecartEntity.class, new Box(railPos).expand(1.0), Entity::isAlive);
        TntMinecartEntity best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3d rail = Vec3d.ofCenter(railPos);
        for (TntMinecartEntity cart : carts) {
            double d = cart.getPos().squaredDistanceTo(rail);
            if (d < bestDist) {
                bestDist = d;
                best = cart;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ aiming

    private static double yawTo(Vec3d from, Vec3d to) {
        return Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90.0;
    }

    private static Vec3d direction(double yawDeg, double pitchDeg) {
        double y = Math.toRadians(yawDeg);
        double p = Math.toRadians(pitchDeg);
        return new Vec3d(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /** Arrow height at the target's horizontal distance minus target height; NaN if it never gets there. */
    private static double heightError(Vec3d spawn, double yaw, double pitch, double horiz, double targetY) {
        Vec3d v = direction(yaw, pitch).multiply(ARROW_SPEED);
        double x = spawn.x, y = spawn.y, z = spawn.z;
        double vx = v.x, vy = v.y, vz = v.z;
        double prevH = 0;
        for (int i = 0; i < 200; i++) {
            double nx = x + vx, ny = y + vy, nz = z + vz;
            double h = Math.hypot(nx - spawn.x, nz - spawn.z);
            if (h >= horiz) {
                double f = h > prevH ? (horiz - prevH) / (h - prevH) : 0;
                return (y + (ny - y) * f) - targetY;
            }
            x = nx; y = ny; z = nz;
            prevH = h;
            vx *= ARROW_DRAG;
            vy = vy * ARROW_DRAG - ARROW_GRAVITY;
            vz *= ARROW_DRAG;
        }
        return Double.NaN;
    }

    /**
     * Pitch (degrees, positive = down) so the simulated arrow reaches the target. NaN if there is
     * no solution or terrain blocks the flight path. Yaw is toward the target.
     */
    private static double solvePitch(ClientWorld world, Entity shooter, Vec3d spawn, Vec3d target) {
        double yaw = yawTo(spawn, target);
        double horiz = Math.hypot(target.x - spawn.x, target.z - spawn.z);
        if (horiz < 0.5) return Double.NaN;

        double lo = -60, hi = 60; // arrival height falls as pitch rises
        double fLo = heightError(spawn, yaw, lo, horiz, target.y);
        double fHi = heightError(spawn, yaw, hi, horiz, target.y);
        if (Double.isNaN(fLo) || Double.isNaN(fHi) || fLo <= 0 || fHi >= 0) return Double.NaN;

        for (int i = 0; i < 50; i++) {
            double mid = (lo + hi) / 2;
            double f = heightError(spawn, yaw, mid, horiz, target.y);
            if (Double.isNaN(f)) return Double.NaN;
            if (f > 0) lo = mid;
            else hi = mid;
        }
        double pitch = (lo + hi) / 2;
        return pathClear(world, shooter, spawn, yaw, pitch, horiz) ? pitch : Double.NaN;
    }

    /** Raycasts every tick-segment of the flight for solid blocks. */
    private static boolean pathClear(ClientWorld world, Entity shooter, Vec3d spawn, double yaw, double pitch, double horiz) {
        Vec3d v = direction(yaw, pitch).multiply(ARROW_SPEED);
        Vec3d pos = spawn;
        for (int i = 0; i < 100; i++) {
            Vec3d next = pos.add(v);
            double h = Math.hypot(next.x - spawn.x, next.z - spawn.z);
            boolean last = h >= horiz;
            if (last) {
                double hp = Math.hypot(pos.x - spawn.x, pos.z - spawn.z);
                if (h > hp) next = pos.add(v.multiply((horiz - hp) / (h - hp)));
            }
            BlockHitResult hit = world.raycast(new RaycastContext(
                    pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, shooter));
            if (hit.getType() != HitResult.Type.MISS) return false;
            if (last) return true;
            pos = next;
            v = new Vec3d(v.x * ARROW_DRAG, v.y * ARROW_DRAG - ARROW_GRAVITY, v.z * ARROW_DRAG);
        }
        return false;
    }
}
