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

/** Plantações, flores, gramas, algas e outras plantas que o bot pode farmar. */
public enum Crop {
    // ===================== PLANTAÇÕES =====================
    WHEAT(Category.CROPS, "Trigo", Items.WHEAT_SEEDS, Blocks.WHEAT),
    CARROT(Category.CROPS, "Cenoura", Items.CARROT, Blocks.CARROTS),
    POTATO(Category.CROPS, "Batata", Items.POTATO, Blocks.POTATOES),
    BEETROOT(Category.CROPS, "Beterraba", Items.BEETROOT_SEEDS, Blocks.BEETROOTS),
    NETHER_WART(Category.CROPS, "Verruga do Nether", Items.NETHER_WART, Blocks.NETHER_WART),
    MELON(Category.CROPS, "Melancia", Blocks.MELON),
    PUMPKIN(Category.CROPS, "Abóbora", Blocks.PUMPKIN),
    SUGAR_CANE(Category.CROPS, "Cana-de-açúcar", Blocks.SUGAR_CANE),
    SWEET_BERRY(Category.CROPS, "Frutinhas doces", Blocks.SWEET_BERRY_BUSH),
    COCOA(Category.CROPS, "Cacau", Blocks.COCOA),
    BAMBOO(Category.CROPS, "Bambu", Blocks.BAMBOO),
    CACTUS(Category.CROPS, "Cacto", Blocks.CACTUS),

    // ===================== FLORES =====================
    DANDELION(Category.FLOWERS, "Dente-de-leão", Blocks.DANDELION),
    POPPY(Category.FLOWERS, "Papoula", Blocks.POPPY),
    BLUE_ORCHID(Category.FLOWERS, "Orquídea azul", Blocks.BLUE_ORCHID),
    ALLIUM(Category.FLOWERS, "Alium", Blocks.ALLIUM),
    AZURE_BLUET(Category.FLOWERS, "Azulzinha", Blocks.AZURE_BLUET),
    RED_TULIP(Category.FLOWERS, "Tulipa vermelha", Blocks.RED_TULIP),
    ORANGE_TULIP(Category.FLOWERS, "Tulipa laranja", Blocks.ORANGE_TULIP),
    WHITE_TULIP(Category.FLOWERS, "Tulipa branca", Blocks.WHITE_TULIP),
    PINK_TULIP(Category.FLOWERS, "Tulipa rosa", Blocks.PINK_TULIP),
    OXEYE_DAISY(Category.FLOWERS, "Margarida", Blocks.OXEYE_DAISY),
    CORNFLOWER(Category.FLOWERS, "Centáurea", Blocks.CORNFLOWER),
    LILY_OF_THE_VALLEY(Category.FLOWERS, "Lírio-do-vale", Blocks.LILY_OF_THE_VALLEY),
    WITHER_ROSE(Category.FLOWERS, "Rosa do Wither", Blocks.WITHER_ROSE),
    TORCHFLOWER(Category.FLOWERS, "Flor-tocha", Blocks.TORCHFLOWER),
    PINK_PETALS(Category.FLOWERS, "Pétalas rosas", Blocks.PINK_PETALS),
    SUNFLOWER(Category.FLOWERS, "Girassol", Blocks.SUNFLOWER),
    LILAC(Category.FLOWERS, "Lilás", Blocks.LILAC),
    ROSE_BUSH(Category.FLOWERS, "Roseira", Blocks.ROSE_BUSH),
    PEONY(Category.FLOWERS, "Peônia", Blocks.PEONY),
    PITCHER_PLANT(Category.FLOWERS, "Planta-jarro", Blocks.PITCHER_PLANT),

    // ===================== GRAMAS =====================
    GRASS(Category.GRASSES, "Grama", Blocks.SHORT_GRASS),
    TALL_GRASS(Category.GRASSES, "Grama alta", Blocks.TALL_GRASS),
    FERN(Category.GRASSES, "Samambaia", Blocks.FERN),
    LARGE_FERN(Category.GRASSES, "Samambaia grande", Blocks.LARGE_FERN),
    DEAD_BUSH(Category.GRASSES, "Arbusto seco", Blocks.DEAD_BUSH),
    NETHER_SPROUTS(Category.GRASSES, "Brotos do Nether", Blocks.NETHER_SPROUTS),
    CRIMSON_ROOTS(Category.GRASSES, "Raízes carmesim", Blocks.CRIMSON_ROOTS),
    WARPED_ROOTS(Category.GRASSES, "Raízes distorcidas", Blocks.WARPED_ROOTS),
    HANGING_ROOTS(Category.GRASSES, "Raízes pendentes", Blocks.HANGING_ROOTS),

    // ===================== ALGAS / AQUÁTICAS =====================
    KELP(Category.AQUATIC, "Alga (kelp)", Blocks.KELP, Blocks.KELP_PLANT),
    SEAGRASS(Category.AQUATIC, "Grama marinha", Blocks.SEAGRASS, Blocks.TALL_SEAGRASS),
    SEA_PICKLE(Category.AQUATIC, "Pepino-do-mar", Blocks.SEA_PICKLE),
    LILY_PAD(Category.AQUATIC, "Vitória-régia", Blocks.LILY_PAD),

    // ===================== OUTRAS =====================
    VINE(Category.OTHER, "Trepadeira", Blocks.VINE),
    GLOW_LICHEN(Category.OTHER, "Líquen luminoso", Blocks.GLOW_LICHEN),
    RED_MUSHROOM(Category.OTHER, "Cogumelo vermelho", Blocks.RED_MUSHROOM),
    BROWN_MUSHROOM(Category.OTHER, "Cogumelo marrom", Blocks.BROWN_MUSHROOM),
    SPORE_BLOSSOM(Category.OTHER, "Flor de esporos", Blocks.SPORE_BLOSSOM),

    // ===================== MUDAS DE ÁRVORES =====================
    OAK_SAPLING(Category.SAPLINGS, "Muda de carvalho", Blocks.OAK_SAPLING),
    SPRUCE_SAPLING(Category.SAPLINGS, "Muda de pinheiro", Blocks.SPRUCE_SAPLING),
    BIRCH_SAPLING(Category.SAPLINGS, "Muda de bétula", Blocks.BIRCH_SAPLING),
    JUNGLE_SAPLING(Category.SAPLINGS, "Muda da selva", Blocks.JUNGLE_SAPLING),
    ACACIA_SAPLING(Category.SAPLINGS, "Muda de acácia", Blocks.ACACIA_SAPLING),
    DARK_OAK_SAPLING(Category.SAPLINGS, "Muda carvalho escuro", Blocks.DARK_OAK_SAPLING),
    CHERRY_SAPLING(Category.SAPLINGS, "Muda de cerejeira", Blocks.CHERRY_SAPLING),
    MANGROVE_PROPAGULE(Category.SAPLINGS, "Propágulo de mangue", Blocks.MANGROVE_PROPAGULE),
    AZALEA(Category.SAPLINGS, "Azaleia", Blocks.AZALEA),
    FLOWERING_AZALEA(Category.SAPLINGS, "Azaleia florida", Blocks.FLOWERING_AZALEA);

    /** Categorias exibidas na interface (abas). */
    public enum Category {
        CROPS("Plantações", "Plantas"),
        FLOWERS("Flores", "Flores"),
        GRASSES("Gramas", "Gramas"),
        AQUATIC("Algas/Água", "Algas"),
        OTHER("Outras", "Outras"),
        SAPLINGS("Mudas de árvores", "Mudas");

        public final String label;
        public final String shortLabel;

        Category(String label, String shortLabel) {
            this.label = label;
            this.shortLabel = shortLabel;
        }
    }

    public final Category category;
    public final String label;
    /** Item usado para replantar (null = não replanta). */
    public final Item seed;
    private final Block[] blocks;

    Crop(Category category, String label, Item seed, Block... blocks) {
        this.category = category;
        this.label = label;
        this.seed = seed;
        this.blocks = blocks;
    }

    Crop(Category category, String label, Block... blocks) {
        this(category, label, (Item) null, blocks);
    }

    /** Frutinhas doces são colhidas com botão direito (a moita continua viva). */
    public boolean usesRightClick() {
        return this == SWEET_BERRY;
    }

    /** Plantas que só dropam de verdade com tesoura. */
    public boolean usesShears() {
        return this == GRASS || this == TALL_GRASS || this == FERN || this == LARGE_FERN
                || this == DEAD_BUSH || this == SEAGRASS || this == NETHER_SPROUTS
                || this == VINE || this == GLOW_LICHEN;
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
        return true; // melancia, abóbora, flores, gramas, aquáticas...
    }

    public static Crop fromName(String name) {
        try { return valueOf(name); } catch (Exception e) { return null; }
    }
}
