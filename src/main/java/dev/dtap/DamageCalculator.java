package dev.dtap;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

public class DamageCalculator {
    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    // null = automatisch vom Server uebernehmen, sonst manuell erzwungen
    public static Difficulty override = null;

    public static Difficulty current(World w) {
        return override != null ? override : w.getDifficulty();
    }

    public static float crystalDamage(LivingEntity e, Vec3d pos, World w) {
        double dist = Math.sqrt(e.squaredDistanceTo(pos)) / 12.0;
        if (dist > 1.0) return 0;
        double impact = (1.0 - dist) * exposure(pos, e, w);
        float d = (float) Math.floor((impact * impact + impact) / 2.0 * 7.0 * 12.0 + 1.0);
        switch (current(w)) {
            case PEACEFUL -> d = 0;
            case EASY -> d = Math.min(d / 2f + 1f, d);
            case HARD -> d = d * 3f / 2f;
            default -> { }
        }
        float armor = e.getArmor();
        float tough = (float) e.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        float eff = MathHelper.clamp(armor - d / (2f + tough / 4f), armor / 5f, 20f);
        d *= 1f - eff / 25f;
        StatusEffectInstance res = e.getStatusEffect(StatusEffects.RESISTANCE);
        if (res != null) d *= Math.max(0f, 1f - 0.2f * (res.getAmplifier() + 1));
        int prot = Math.min(20, protectionPoints(e, w));
        d *= 1f - prot / 25f;
        return Math.max(d, 0f);
    }

    private static int protectionPoints(LivingEntity e, World w) {
        int sum = 0;
        for (EquipmentSlot s : ARMOR) {
            ItemStack st = e.getEquippedStack(s);
            if (st.isEmpty()) continue;
            sum += level(w, st, Enchantments.PROTECTION) + 2 * level(w, st, Enchantments.BLAST_PROTECTION);
        }
        return sum;
    }

    private static int level(World w, ItemStack st, RegistryKey<Enchantment> key) {
        var reg = w.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        return EnchantmentHelper.getLevel(reg.getOrThrow(key), st);
    }

    // Vanilla-Exposure
    private static double exposure(Vec3d src, Entity t, World w) {
        Box b = t.getBoundingBox();
        double dx = 1.0 / ((b.maxX - b.minX) * 2.0 + 1.0);
        double dy = 1.0 / ((b.maxY - b.minY) * 2.0 + 1.0);
        double dz = 1.0 / ((b.maxZ - b.minZ) * 2.0 + 1.0);
        double ox = (1.0 - Math.floor(1.0 / dx) * dx) / 2.0;
        double oz = (1.0 - Math.floor(1.0 / dz) * dz) / 2.0;
        if (dx < 0 || dy < 0 || dz < 0) return 0;
        int hit = 0, total = 0;
        for (double x = 0; x <= 1.0; x += dx)
            for (double y = 0; y <= 1.0; y += dy)
                for (double z = 0; z <= 1.0; z += dz) {
                    Vec3d p = new Vec3d(
                        MathHelper.lerp(x, b.minX, b.maxX) + ox,
                        MathHelper.lerp(y, b.minY, b.maxY),
                        MathHelper.lerp(z, b.minZ, b.maxZ) + oz);
                    if (w.raycast(new RaycastContext(p, src, RaycastContext.ShapeType.COLLIDER,
                            RaycastContext.FluidHandling.NONE, t)).getType() == HitResult.Type.MISS) hit++;
                    total++;
                }
        return total == 0 ? 0 : (double) hit / total;
    }
}
