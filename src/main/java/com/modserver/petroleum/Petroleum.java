package com.modserver.petroleum;

import com.modserver.ModServer;
import com.modserver.NationService;
import com.modserver.ServerLanguage;
import com.modserver.config.ModServerConfig;
import com.modserver.economy.NationEconomy;
import com.modserver.nation.Nation;
import com.modserver.network.GovernmentView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;

public final class Petroleum {
    private static final int BUCKET = 1000;
    public static boolean enabled() {
        return ModList.get().isLoaded("createdieselgenerators") && ModList.get().isLoaded("create");
    }

    public enum Grade {
        CRUDE("crude_oil", ModServerConfig.CRUDE_PRICE), DIESEL("diesel", ModServerConfig.DIESEL_PRICE),
        GASOLINE("gasoline", ModServerConfig.GASOLINE_PRICE);
        public final String id;
        private final ModConfigSpec.IntValue price;
        Grade(String id, ModConfigSpec.IntValue price) { this.id = id; this.price = price; }
        public long price() { return price.get() * NationEconomy.CENTS; }
        static Grade of(FluidStack stack) {
            if (stack.isEmpty()) return null;
            var id = BuiltInRegistries.FLUID.getKey(stack.getFluid());
            if (!id.getNamespace().equals("createdieselgenerators")) return null;
            for (Grade grade : values()) if (grade.id.equals(id.getPath())) return grade;
            return null;
        }
    }

    /** One snapshot of the physical terminal under the crosshair. No world or tank is cached between requests. */
    public record Terminal(String status, int reading, int remaining, BlockPos base, Grade grade,
                           int millibuckets, IFluidHandler handler) {
        public GovernmentView.Oil view(GovernmentView.Reserves reserves) {
            return new GovernmentView.Oil(status, reading, remaining, grade == null ? "" : grade.id, millibuckets,
                    List.of(Grade.CRUDE.price(), Grade.DIESEL.price(), Grade.GASOLINE.price()), reserves);
        }
        public String problem(int buckets) {
            if (!status.equals("ready")) return status;
            if (buckets <= 0 || buckets > millibuckets / BUCKET) return "stock";
            return buckets > remaining ? "quota" : "";
        }
    }

    public record Quote(String dimension, BlockPos base, Grade grade, int buckets, long unitPrice) {
        public long value() { return buckets * unitPrice; }
    }

    private static String landStatus(ServerPlayer p, Nation nation) {
        if (p.isSpectator() || nation == null || !nation.owner().equals(p.getUUID())) return "land";
        var service = ModServer.nations();
        var chunk = p.chunkPosition();
        String dim = NationService.dimensionId(p);
        var claim = service.claims().claimAt(dim, chunk.x, chunk.z);
        if (claim == null || !claim.nationId().equals(nation.id())) return "land";
        return service.disputes().openAt(dim, chunk.x, chunk.z) == null ? "" : "disputed";
    }

    public static String survey(ServerPlayer p, Nation nation) {
        var lang = ServerLanguage.of(p);
        if (!enabled()) return lang.text("gov.oil.off");
        String problem = landStatus(p, nation);
        if (!problem.isEmpty()) return lang.text("gov.oil." + problem);
        var scanner = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createdieselgenerators", "oil_scanner"));
        if (!p.isHolding(scanner)) return lang.text("gov.oil.scanner");
        int ceiling = DieselCompat.scannerCeiling();
        if (p.getY() >= ceiling) return lang.text("gov.oil.depth", ceiling);
        if (p.getCooldowns().isOnCooldown(scanner)) return lang.text("gov.oil.wait");
        p.getCooldowns().addCooldown(scanner, 20);
        int amount = Math.max(0, DieselCompat.survey(p.serverLevel(), p.chunkPosition()));
        recordReading(p, amount);
        return surveyResult(lang, amount);
    }

    /** Called once when the addon's own scanner finishes, including scans from the off hand. */
    public static void onScannerReading(ServerPlayer player, int amount) {
        var nation = ModServer.nations().nationOf(player.getUUID()).orElse(null);
        String problem = landStatus(player, nation);
        if (!problem.isEmpty()) {
            if (amount > 0 && !player.isSpectator())
                player.sendSystemMessage(Component.literal(ServerLanguage.of(player).text("gov.oil.scan." + problem)));
            return;
        }
        if (recordReading(player, Math.max(0, amount)))
            player.sendSystemMessage(Component.literal(surveyResult(ServerLanguage.of(player), amount)));
    }

    private static boolean recordReading(ServerPlayer player, int amount) {
        var fields = OilFields.of(player.serverLevel());
        long chunk = player.chunkPosition().toLong();
        boolean discovery = amount > 0 && !fields.discovered(chunk);
        fields.survey(chunk, amount);
        if (discovery) ModServer.nations().markMapDirty();
        return discovery;
    }

    private static String surveyResult(ServerLanguage lang, int amount) {
        return lang.text(amount == 0 ? "gov.oil.dry" : amount == Integer.MAX_VALUE ? "gov.oil.bottomless" : "gov.oil.found",
                amount / BUCKET);
    }

    public static Terminal inspect(ServerPlayer p, Nation nation) {
        var level = p.serverLevel();
        var chunk = p.chunkPosition();
        var fields = OilFields.of(level);
        int reading = fields.reading(chunk.toLong());
        int remaining = fields.remaining(chunk.toLong(), day(p));
        String status = landStatus(p, nation);
        if (status.isEmpty() && reading < 0) status = "unregistered";
        if (!status.isEmpty()) return new Terminal(status, reading, remaining, null, null, 0, null);
        if (!(p.pick(p.blockInteractionRange(), 1, false) instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !new ChunkPos(hit.getBlockPos()).equals(chunk))
            return new Terminal("aim", reading, remaining, null, null, 0, null);
        BlockPos base = DieselCompat.tankBase(level, hit.getBlockPos());
        if (base == null) return new Terminal("aim", reading, remaining, null, null, 0, null);
        var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, base, null);
        if (handler == null || handler.getTanks() != 1) return new Terminal("aim", reading, remaining, null, null, 0, null);
        var stack = handler.getFluidInTank(0);
        var grade = Grade.of(stack);
        return new Terminal(stack.isEmpty() ? "empty" : grade == null ? "unsupported" : "ready",
                reading, remaining, base, grade, stack.getAmount(), handler);
    }

    public static Quote quote(ServerPlayer p, Terminal terminal, int buckets) {
        return new Quote(NationService.dimensionId(p), terminal.base(), terminal.grade(), buckets, terminal.grade().price());
    }

    public static boolean confirm(ServerPlayer p, Nation nation, Quote quote) {
        if (!enabled() || !NationService.dimensionId(p).equals(quote.dimension())) return false;
        Terminal current = inspect(p, nation);
        if (!current.problem(quote.buckets()).isEmpty() || !quote.base().equals(current.base())
                || quote.grade() != current.grade() || quote.unitPrice() != current.grade().price()) return false;
        var economy = nation.economy();
        if (economy.balance() > Long.MAX_VALUE - quote.value()) return false;
        var fluid = current.handler().getFluidInTank(0).copyWithAmount(quote.buckets() * BUCKET);
        if (!FluidStack.matches(current.handler().drain(fluid, IFluidHandler.FluidAction.SIMULATE), fluid)) return false;
        // Both supported multiblocks expose a single FluidTank. Validate and drain on the same server thread.
        var drained = current.handler().drain(fluid, IFluidHandler.FluidAction.EXECUTE);
        if (!FluidStack.matches(drained, fluid)) {
            if (!drained.isEmpty()) current.handler().fill(drained, IFluidHandler.FluidAction.EXECUTE);
            return false;
        }
        OilFields.of(p.serverLevel()).recordExport(p.chunkPosition().toLong(), day(p), quote.buckets());
        economy.setBalance(economy.balance() + quote.value());
        ModServer.nations().markDirty();
        return true;
    }

    private static long day(ServerPlayer p) { return p.server.overworld().getGameTime() / 24000; }
    private Petroleum() {}
}
