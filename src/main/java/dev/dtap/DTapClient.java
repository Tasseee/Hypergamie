package dev.dtap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Predicate;

public class DTapClient implements ClientModInitializer {
    static final float SAFETY = 1.15f;

    enum S { IDLE, HIT, OBSI, CRYSTAL, BREAK, WAIT_POP }
    record Plan(BlockPos base, BlockPos support, Direction face, float dmg) { }

    static S state = S.IDLE;
    static KeyBinding toggleKey, diffKey;
    static PlayerEntity target;
    static Plan plan;
    static boolean second, popped, greeted;
    static int timer, total, retries;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.dtap.toggle", GLFW.GLFW_KEY_H, KeyBinding.Category.MISC));
        diffKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.dtap.difficulty", GLFW.GLFW_KEY_J, KeyBinding.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(DTapClient::tick);

        // Explosions-Check: immer aktiv
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient() || !(entity instanceof EndCrystalEntity)) return ActionResult.PASS;
            float dmg = DamageCalculator.crystalDamage(player,
                new Vec3d(entity.getX(), entity.getY(), entity.getZ()), world);
            float hp = player.getHealth() + player.getAbsorptionAmount();
            if (dmg * SAFETY >= hp && !hasTotem(player)) {
                MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                    Text.literal("Crystal blockiert! Schaden ca. " + Math.round(dmg) + " / Leben " + Math.round(hp)
                        + " (" + DamageCalculator.current(world).name() + ")"), false);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }

    // vom Mixin: Totem-Pop (Status 35)
    public static void onPop(Entity e) {
        if (state != S.IDLE && e == target) popped = true;
    }

    static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            greeted = false;
            stop();
            return;
        }
        if (!greeted) { greeted = true; msg(mc, "DTap-Mod geladen (Taste H)"); }

        // J: Schwierigkeit umschalten AUTO -> EASY -> NORMAL -> HARD -> AUTO
        while (diffKey.wasPressed()) {
            Difficulty cur = DamageCalculator.override;
            DamageCalculator.override = cur == null ? Difficulty.EASY
                : cur == Difficulty.EASY ? Difficulty.NORMAL
                : cur == Difficulty.NORMAL ? Difficulty.HARD : null;
            msg(mc, "Schwierigkeit: " + (DamageCalculator.override == null ? "AUTO" : DamageCalculator.override.name())
                + " (Server meldet: " + mc.world.getDifficulty().name() + ")");
        }

        while (toggleKey.wasPressed()) {
            if (state != S.IDLE) { msg(mc, "DTap: abgebrochen"); stop(); }
            else start(mc);
        }
        if (state == S.IDLE) return;

        total++;
        timer++;
        if (target == null || total > 200) { abort(mc, "Zeitlimit"); return; }

        switch (state) {
            case HIT -> {
                if (mc.player.getAttackCooldownProgress(0.5f) < 0.9f && timer < 15) return;
                if (mc.player.distanceTo(target) <= 3.3f) {
                    int w = findSlot(mc, st -> st.isIn(ItemTags.SWORDS) || st.isIn(ItemTags.AXES));
                    if (w >= 0) select(mc, w);
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                state = S.OBSI;
                timer = 0;
            }
            case OBSI -> {
                plan = bestPlan(mc, true);
                if (plan == null) { abort(mc, "kein sicherer Platz gefunden"); return; }
                if (mc.world.getBlockState(plan.base()).isAir()) {
                    int o = findSlot(mc, st -> st.isOf(Items.OBSIDIAN));
                    if (o < 0) { abort(mc, "kein Obsidian in der Hotbar"); return; }
                    select(mc, o);
                    Direction f = plan.face();
                    Vec3d hit = Vec3d.ofCenter(plan.support()).add(f.getOffsetX() * 0.5, f.getOffsetY() * 0.5, f.getOffsetZ() * 0.5);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                        new BlockHitResult(hit, f, plan.support(), false));
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                state = S.CRYSTAL;
                timer = 0;
            }
            case CRYSTAL -> {
                var b = mc.world.getBlockState(plan.base());
                if (!(b.isOf(Blocks.OBSIDIAN) || b.isOf(Blocks.BEDROCK))) {
                    if (timer < 6) return;
                    abort(mc, "Obsidian wurde nicht platziert");
                    return;
                }
                int c = findSlot(mc, st -> st.isOf(Items.END_CRYSTAL));
                if (c < 0) { abort(mc, "kein End Crystal in der Hotbar"); return; }
                select(mc, c);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(plan.base()).add(0, 0.5, 0), Direction.UP, plan.base(), false));
                mc.player.swingHand(Hand.MAIN_HAND);
                state = S.BREAK;
                timer = 0;
            }
            case BREAK -> {
                Box area = new Box(plan.base().up()).expand(0.5);
                List<EndCrystalEntity> list = mc.world.getEntitiesByClass(EndCrystalEntity.class, area, e -> true);
                if (!list.isEmpty()) {
                    popped = false;
                    mc.interactionManager.attackEntity(mc.player, list.get(0));
                    mc.player.swingHand(Hand.MAIN_HAND);
                    if (second) { finish(mc, "DTap fertig"); }
                    else { state = S.WAIT_POP; timer = 0; }
                } else if (timer > 20) {
                    abort(mc, "Crystal nicht gefunden");
                }
            }
            case WAIT_POP -> {
                if (!target.isAlive() || target.getHealth() <= 0) { finish(mc, "Gegner besiegt"); return; }
                if (popped) {
                    plan = bestPlan(mc, false);
                    if (plan == null) { abort(mc, "kein sicherer Platz fuer Crystal 2"); return; }
                    second = true;
                    state = S.CRYSTAL;
                    timer = 0;
                } else if (timer > 12) {
                    if (++retries > 2) { abort(mc, "kein Totem-Pop erkannt"); return; }
                    plan = bestPlan(mc, false);
                    if (plan == null) { abort(mc, "kein sicherer Platz"); return; }
                    state = S.CRYSTAL;
                    timer = 0;
                }
            }
            default -> { }
        }
    }

    static void start(MinecraftClient mc) {
        PlayerEntity best = null;
        double bd = 36;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator() || !p.isAlive()) continue;
            double d = mc.player.squaredDistanceTo(p);
            if (d < bd) { bd = d; best = p; }
        }
        if (best == null) { msg(mc, "DTap: kein Gegner in 6 Bloecken"); return; }
        target = best;
        state = S.HIT;
        timer = 0; total = 0; retries = 0;
        second = false; popped = false; plan = null;
        msg(mc, "DTap: laeuft");
    }

    // beste Position fuer Obsidian/Bedrock + Crystal
    static Plan bestPlan(MinecraftClient mc, boolean allowPlace) {
        World w = mc.world;
        BlockPos t = target.getBlockPos();
        Vec3d eye = mc.player.getEyePos();
        Plan best = null;
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPos b = t.add(dx, dy, dz);
                    // Crystal muss in Schlagreichweite liegen
                    if (eye.squaredDistanceTo(b.getX() + 0.5, b.getY() + 1.5, b.getZ() + 0.5) > 12) continue;
                    var st = w.getBlockState(b);
                    boolean solid = st.isOf(Blocks.OBSIDIAN) || st.isOf(Blocks.BEDROCK);
                    BlockPos support = null;
                    Direction face = null;
                    if (!solid) {
                        if (!allowPlace || !st.isAir()) continue;
                        for (Direction d : Direction.values()) {
                            if (d == Direction.UP) continue;
                            BlockPos n = b.offset(d);
                            if (!w.getBlockState(n).isAir() && w.getFluidState(n).isEmpty()) {
                                support = n.toImmutable();
                                face = d.getOpposite();
                                break;
                            }
                        }
                        if (support == null) continue;
                        if (!w.getOtherEntities(null, new Box(b), e -> !e.isSpectator()).isEmpty()) continue;
                    }
                    if (!w.isAir(b.up()) || !w.isAir(b.up(2))) continue;
                    // Vanilla prueft beim Crystal-Platzieren eine 1x2x1-Box ueber dem Block
                    if (!w.getOtherEntities(null, new Box(b.getX(), b.getY() + 1, b.getZ(), b.getX() + 1, b.getY() + 3, b.getZ() + 1), e -> !e.isSpectator()).isEmpty()) continue;

                    Vec3d pos = new Vec3d(b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5);
                    float dmg = DamageCalculator.crystalDamage(target, pos, w);
                    if (dmg <= 0 || (best != null && dmg <= best.dmg())) continue;
                    float self = DamageCalculator.crystalDamage(mc.player, pos, w);
                    if (selfDies(mc.player, self)) continue;
                    best = new Plan(b.toImmutable(), support, face, dmg);
                }
        return best;
    }

    static int findSlot(MinecraftClient mc, Predicate<ItemStack> f) {
        for (int i = 0; i < 9; i++)
            if (f.test(mc.player.getInventory().getStack(i))) return i;
        return -1;
    }

    static void select(MinecraftClient mc, int slot) {
        mc.player.getInventory().setSelectedSlot(slot);
    }

    static boolean hasTotem(PlayerEntity p) {
        return p.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) || p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    static boolean selfDies(PlayerEntity me, float dmg) {
        return dmg * SAFETY >= me.getHealth() + me.getAbsorptionAmount() && !hasTotem(me);
    }

    static void msg(MinecraftClient mc, String s) {
        if (mc.inGameHud != null) mc.inGameHud.setOverlayMessage(Text.literal(s), false);
    }

    static void abort(MinecraftClient mc, String why) { msg(mc, "DTap abgebrochen: " + why); stop(); }
    static void finish(MinecraftClient mc, String s) { msg(mc, "DTap: " + s); stop(); }
    static void stop() { state = S.IDLE; target = null; plan = null; second = false; popped = false; }
}
