package com.modserver.data;

import com.modserver.ModServer;
import com.modserver.item.ModServerItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.concurrent.CompletableFuture;

/**
 * Data generation (recipes, advancements, etc.).
 *
 * <p>It runs with {@code ./gradlew runData} -> {@code src/generated/resources}.
 * The listener is registered from the main class of the mod (there is no
 * {@code EventBusSubscriber} annotation in this version of NeoForge).</p>
 */
public final class ModServerDataGen {

    private ModServerDataGen() {
    }

    public static void gatherData(GatherDataEvent event) {
        if (event.includeServer()) {
            event.addProvider(new ModServerRecipeProvider(
                    event.getGenerator().getPackOutput(),
                    event.getLookupProvider()));
        }
    }

    /** Mod recipes. */
    static final class ModServerRecipeProvider extends RecipeProvider {

        ModServerRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookups) {
            super(output, lookups);
        }

        @Override
        protected void buildRecipes(RecipeOutput output) {
            ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModServerItems.COG.get())
                    .define('I', Ingredient.of(Items.IRON_INGOT))
                    .define('C', Ingredient.of(Items.COPPER_INGOT))
                    .pattern("ICI")
                    .pattern("CIC")
                    .pattern("ICI")
                    .unlockedBy("has_copper", has(Items.COPPER_INGOT))
                    .save(output, ModServer.id("cog"));
        }
    }
}
