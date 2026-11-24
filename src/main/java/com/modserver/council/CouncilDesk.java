package com.modserver.council;

import com.modserver.NationService;
import com.modserver.council.CouncilPost.Kind;
import com.modserver.government.CapitalChest;
import com.modserver.government.Commodity;
import com.modserver.nation.Nation;
import com.modserver.nation.NationRelation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** All settlement runs on the server thread. Offers promise terms, not reserved funds or goods. */
public final class CouncilDesk {
    public static String propose(NationService service, Nation sender, Nation recipient, CouncilPost offer) {
        if (sender == recipient || !offer.from().equals(sender.id()) || !offer.to().equals(recipient.id()) || !offer.kind().offer()) return "invalid";
        Kind kind = offer.kind();
        if (kind.treaty() && (sender.relationWith(recipient.id()) == kind.relation()
                || kind == Kind.PEACE && sender.relationWith(recipient.id()) != NationRelation.WAR)) return "current";
        if (kind == Kind.TRADE && sender.relationWith(recipient.id()) == NationRelation.WAR) return "atWar";
        if (recipient.register().stream().anyMatch(p -> p.from().equals(sender.id()) && p.kind() == kind
                && (kind != Kind.TRADE || (p.item() == offer.item() && p.quantity() == offer.quantity() && p.coins() == offer.coins())))) return "duplicate";
        if (recipient.register().stream().filter(p -> p.kind().offer()).count() >= 64
                || recipient.register().stream().filter(p -> p.kind().offer() && p.from().equals(sender.id())).count() >= 6) return "full";
        recipient.file(offer);
        service.markDirty();
        if (recipient.isEnvoy() && kind.treaty()) {
            decide(service, recipient, sender, offer.id(), "accept", null, null);
            return "ratified";
        }
        return "proposed";
    }

    public static String decide(NationService service, Nation actor, Nation other, UUID id, String action,
                                CapitalChest.Access sellerChest, CapitalChest.Access buyerChest) {
        boolean withdraw = action.equals("withdraw");
        Nation seller = withdraw ? actor : other;
        Nation recipient = withdraw ? other : actor;
        var offer = recipient.register().stream().filter(p -> p.id().equals(id) && p.kind().offer()
                && p.from().equals(seller.id()) && p.to().equals(recipient.id())).findFirst().orElse(null);
        if (offer == null) return "gone";
        Kind outcome;
        switch (action) {
            case "accept" -> {
                if (offer.kind() == Kind.TRADE) {
                    if (seller.relationWith(recipient.id()) == NationRelation.WAR) return "atWar";
                    if (sellerChest == null || !offer.seal().equals(sellerChest.seal()) || buyerChest == null
                            || buyerChest.inventory() == null) return "chests";
                    String error = exchange(seller, recipient, sellerChest.inventory(), buyerChest.inventory(), offer);
                    if (!error.isEmpty()) return error;
                } else {
                    service.applyRelation(recipient, seller, offer.kind().relation());
                }
                outcome = Kind.ACCEPTED;
            }
            case "decline" -> outcome = Kind.DECLINED;
            case "withdraw" -> outcome = Kind.WITHDRAWN;
            default -> { return "invalid"; }
        }
        recipient.removePost(id);
        var receipt = new CouncilPost(UUID.randomUUID(), actor.id(), other.id(), outcome, offer.item(),
                offer.quantity(), offer.coins(), null, offer.kind().name());
        actor.file(receipt);
        other.file(receipt);
        service.markDirty();
        return outcome == Kind.ACCEPTED ? (offer.kind() == Kind.TRADE ? "settled" : "ratified")
                : withdraw ? "withdrawn" : "declined";
    }

    private static String exchange(Nation seller, Nation buyer, Container source, Container target, CouncilPost offer) {
        if (source == null || target == null || source == target) return "chests";
        Commodity item = offer.item();
        int amount = offer.quantity();
        long payment = offer.coins() * 100L;
        if (buyer.economy().balance() < payment) return "funds";
        if (seller.economy().balance() > Long.MAX_VALUE - payment) return "overflow";
        if (CapitalChest.count(source, item) < amount) return "stock";
        int capacity = 0;
        int stackSize = Math.min(target.getMaxStackSize(), item.item.getDefaultInstance().getMaxStackSize());
        for (int i = 0; i < target.getContainerSize(); i++) {
            var stack = target.getItem(i);
            if (stack.isEmpty()) capacity += stackSize;
            else if (stack.is(item.item) && stack.isComponentsPatchEmpty()) capacity += Math.max(0, stackSize - stack.getCount());
        }
        if (capacity < amount) return "space";
        int left = amount;
        for (int i = 0; i < source.getContainerSize() && left > 0; i++) {
            var stack = source.getItem(i);
            if (stack.is(item.item) && stack.isComponentsPatchEmpty()) left -= source.removeItem(i, Math.min(stack.getCount(), left)).getCount();
        }
        left = amount;
        for (int i = 0; i < target.getContainerSize() && left > 0; i++) {
            var stack = target.getItem(i);
            if (!stack.isEmpty() && (!stack.is(item.item) || !stack.isComponentsPatchEmpty())) continue;
            int count = Math.min(left, stackSize - stack.getCount());
            if (count <= 0) continue;
            target.setItem(i, new ItemStack(item.item, stack.getCount() + count));
            left -= count;
        }
        source.setChanged();
        target.setChanged();
        seller.economy().setBalance(seller.economy().balance() + payment);
        buyer.economy().setBalance(buyer.economy().balance() - payment);
        return "";
    }

    public static void correspond(Nation sender, Nation recipient, Kind kind, String text) {
        var mail = CouncilPost.note(sender.id(), recipient.id(), kind, text);
        sender.file(mail);
        recipient.file(mail);
    }
    private CouncilDesk() {}
}
