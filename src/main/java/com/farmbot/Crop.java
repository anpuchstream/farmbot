package com.farmbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Plantações, algas e gramas que o bot pode farmar. */
public enum Crop {
    // --- plantações clássicas ---
    WHEAT("Trigo", Items.WHEAT_SEEDS, Blocks.WHEAT),
    CARROT("Cenoura", Items.CARROT, Blocks.CARROTS),
    POTATO("Batata", Items.POTATO, Blocks.POTATOES),
    BEETROOT("Beterraba", Items.BEETROOT_SEEDS, Blocks.BEETROOTS),
    NETHER_WART("Verruga do Nether", Items.NETHER_WART, Blocks.NETHER_WART),
    MELON("Melancia", null, Blocks.MELON),
    PUMPKIN("Abóbora", null, Blocks.PUMPKIN),
    SUGAR_CANE("Cana-de-açúcar", null, Blocks.SUGAR_CANE),
    // --- outras plantas ---
    SWEET_BERRY("Frutinhas doces", null, Blocks.SWEET_BERRY_BUSH),
    COCOA("Cacau", null, Blocks.COCOA),
    BAMBOO("Bambu", null, Blocks.BAMBOO),
    CACTUS("Cacto", null, Blocks.CACTUS),
    // --- algas ---
    KELP("Alga (kelp)", null, Blocks.KELP, Blocks.KELP_PLANT),
    SEAGRASS("Grama marinha", null, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS),
    // --- gramas ---
    GRASS("Grama", null, Blocks.SHORT_GRASS),
    TALL_GRASS("Grama alta", null, Blocks.TALL_GRASS),
    FERN("Samambaia", null, Blocks.FERN),
    LARGE_FERN("Samambaia grande", null, Blocks.LARGE_FERN);

    public final String label;
    /** Item usado para replantar (null = não replanta). */
    public final Item seed;
    private final Block[] blocks;

    Crop(String label, Item seed, Block... blocks) {
        this.label = label;
        this.seed = seed;
        this.blocks = blocks;
    }

    /** Frutinhas doces são colhidas com botão direito (a moita continua viva). */
    public boolean usesRightClick() {
        return this == SWEET_BERRY;
    }

    /** Gramas e grama marinha só dropam com tesoura. */
    public boolean usesShears() {
        return this == GRASS || this == TALL_GRASS || this == FERN || this == LARGE_FERN || this == SEAGRASS;
    }

    public boolean matches(BlockState state) {
        for (Block b : blocks) if (state.isOf(b)) return true;
        return false;
    }

    /** O bloco é dessa planta e está pronto para colher? */
    public boolean isReady(World world, BlockPos pos, BlockState state) {
        if (!matches(state)) return false;

        BlockState below = world.getBlockState(pos.down());
        if (this == SUGAR_CANE) return below.isOf(Blocks.SUGAR_CANE);   // deixa a base crescer
        if (this == CACTUS) return below.isOf(Blocks.CACTUS);
        if (this == BAMBOO) return below.isOf(Blocks.BAMBOO);
        if (this == KELP) return below.isOf(Blocks.KELP) || below.isOf(Blocks.KELP_PLANT);
        if (this == NETHER_WART) return state.get(NetherWartBlock.AGE) >= 3;
        if (this == SWEET_BERRY) return state.get(SweetBerryBushBlock.AGE) >= 2;
        if (this == COCOA) return state.get(CocoaBlock.AGE) >= 2;
        if (state.getBlock() instanceof CropBlock crop) return crop.isMature(state);
        return true; // melancia, abóbora, gramas, grama marinha
    }

    public static Crop fromName(String name) {
        try { return valueOf(name); } catch (Exception e) { return null; }
    }
}
