package com.modserver.client.map;

import com.modserver.military.TroopBranch;
import com.modserver.military.TroopCounts;
import com.modserver.military.ArmyCombat;
import com.modserver.military.TroopTransfer;
import net.minecraft.Util;
import com.modserver.client.CampaignStyle;
import com.modserver.network.TacticalBoard;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.math.BigDecimal;
import java.time.Duration;
import java.text.NumberFormat;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.Consumer;

/** A client-side order desk. Selecting a chunk never submits an order. */
public final class TacticalScreen extends Screen {
    private static final int HEADER = 24, FOOTER = 34, MAP_LEGEND = 16;
    private static final int GOLD = 0xFFF4CB75, MINT = 0xFF98E4CE, MUTED = 0xFFC0D1CE, WHITE = 0xFFF6F7EF;
    private static final ChunkPos UNUSED = new ChunkPos(0, 0);

    private enum Field {
        DEPLOYMENT("deployment"), MOVE_FROM("moveFrom"), MOVE_TO("moveTo"),
        ATTACK_FROM("attackFrom"), ATTACK_TO("attackTo"), SEND_FROM("sendFrom"), SEND_TO("sendTo"),
        WORKSHOP("workshop"), LAUNCH_FROM("launchFrom"), LAUNCH_TO("launchTo"), SILO("silo"), EPICENTER("epicenter");

        final String key;
        Field(String key) { this.key = key; }
        boolean rival() { return this == ATTACK_TO || this == LAUNCH_TO || this == EPICENTER; }
        int color() {
            if (this == SEND_TO) return 0xFFC9B5FF;
            if (this == MOVE_TO) return 0xFF88C9FF;
            return rival() ? 0xFFFFBC59 : 0xFF76EBCD;
        }
    }

    private enum Action {
        BUY("buy", Items.GOLD_INGOT, null, null),
        DEPLOY("deploy", Items.WHITE_BANNER, Field.DEPLOYMENT, null),
        MOVE("move", Items.LEATHER_BOOTS, Field.MOVE_FROM, Field.MOVE_TO),
        ATTACK("attack", Items.IRON_SWORD, Field.ATTACK_FROM, Field.ATTACK_TO),
        SEND("send", Items.CHEST_MINECART, Field.SEND_FROM, Field.SEND_TO),
        BUILD("build", Items.CRAFTING_TABLE, Field.WORKSHOP, null),
        LAUNCH("launch", Items.FIREWORK_ROCKET, Field.LAUNCH_FROM, Field.LAUNCH_TO),
        NUCLEAR("nuclear", Items.NETHER_STAR, Field.SILO, Field.EPICENTER);

        final String key;
        final Item icon;
        final Field from, to;
        Action(String key, Item icon, Field from, Field to) {
            this.key = key;
            this.icon = icon;
            this.from = from;
            this.to = to;
        }
    }

    private record PendingOrder(String command, ChunkPos from, ChunkPos to, int quantity, TroopBranch branch, TacticalBoard.ViewContext view) {
        void send(TacticalBoard board) {
            ChunkPos origin = from == null ? UNUSED : from;
            ChunkPos target = to == null ? UNUSED : to;
            PacketDistributor.sendToServer(new TacticalBoard.Request(board.token(), command,
                    origin.x, origin.z, target.x, target.z, quantity, branch, view));
        }

        boolean matchesPreview(TacticalBoard before, TacticalBoard after) {
            if (!before.dimension().equals(after.dimension()) || !before.mine().equals(after.mine())) return false;
            if (command.equals("BUY_NUCLEAR") && before.nuclearPrice() != after.nuclearPrice()) return false;
            if (command.equals("NUKE") && to != null) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    int x = to.x + dx, z = to.z + dz;
                    if (!Objects.equals(before.at(x, z), after.at(x, z))) return false;
                }
            }
            if (from == null) return before.price() == after.price() && before.reserveForces().equals(after.reserveForces());
            if (command.equals("DEPLOY") && !before.reserveForces().equals(after.reserveForces())) return false;
            return Objects.equals(before.at(from.x, from.z), after.at(from.x, from.z))
                    && (to == null || Objects.equals(before.at(to.x, to.z), after.at(to.x, to.z)));
        }
    }

    private final CampaignMapArt artwork = new CampaignMapArt();
    private final CampaignTerrain terrain = new CampaignTerrain();
    private TacticalBoard board;
    private Action action = Action.BUY;
    private final EnumMap<Field, ChunkPos> selections = new EnumMap<>(Field.class);
    private final EnumMap<Field, Button> pickers = new EnumMap<>(Field.class);
    private final EnumMap<Action, TroopBranch> branches = new EnumMap<>(Action.class);
    private Button branchButton;
    private Button commitAll, transitToggle, previousTrip, nextTrip;
    private boolean tracking;
    private int transitIndex, snapshotTick;
    private String sendAmount = "1";
    private String attackAmount = "1";
    private Field picking;
    private String buyAmount = "1", deployAmount = "1", moveAmount = "1";
    private int cx, cz, cellSize = 32, ticks;
    private boolean refreshing, dragging, moved;
    private PendingOrder pendingOrder;
    private double dragX, dragY;
    private int dragCenterX, dragCenterZ;
    private String report;
    private Component pickError;
    private Button currentTab, submit, buyNuclear;
    private int gridWidth, left, innerWidth, headingY, fieldY, rowGap, previewY, submitY;
    private boolean compact, overFormationKey;
    private NumberFormat numbers;
    private long hoverChunk = Long.MIN_VALUE, hoverSince;

    private TacticalScreen(TacticalBoard board) {
        super(tr("campaign"));
        branches.put(Action.BUY, TroopBranch.GROUND);
        this.board = board;
        artwork.setBoard(board);
        cx = board.x();
        cz = board.z();
        report = board.notice();
        ChunkPos center = new ChunkPos(cx, cz);
        var claim = board.at(cx, cz);
        if (claim != null && !board.mine().equals(TacticalBoard.OBSERVER)) {
            if (claim.owner().equals(board.mine())) {
                action = Action.DEPLOY;
                selections.put(Field.DEPLOYMENT, center);
            } else if (board.countries().get(claim.owner()).allied()) {
                action = Action.SEND;
                selections.put(Field.SEND_TO, center);
            } else {
                action = Action.ATTACK;
                selections.put(Field.ATTACK_TO, center);
            }
        }
        picking = nextField();
    }

    public static void accept(TacticalBoard next) {
        var mc = Minecraft.getInstance();
        if (next.open()) {
            mc.setScreen(new TacticalScreen(next));
        } else if (mc.screen instanceof TacticalScreen screen) {
            if (next.closeToken() != null) {
                if (screen.board.token().equals(next.closeToken()) && screen.pendingOrder != null) {
                    mc.setScreen(null);
                    if (mc.player != null) mc.player.displayClientMessage(Component.literal(next.notice()), true);
                }
            } else screen.refresh(next);
        }
    }

    private void refresh(TacticalBoard next) {
        TacticalBoard previous = board;
        if (!next.dimension().equals(board.dimension()) || !next.mine().equals(board.mine())) {
            selections.clear();
            picking = null;
            cx = next.x();
            cz = next.z();
            report = "";
            pickError = null;
        }
        board = next;
        snapshotTick = ticks;
        transitIndex = next.transit().index();
        if (previous.x() != next.x() || previous.z() != next.z() || !previous.dimension().equals(next.dimension())
                || !previous.cells().equals(next.cells())) artwork.setBoard(next);
        // The snapshot covers only the current map window. An off-screen selection
        // is retained, but cannot authorize an order until visible to the server.
        boolean changed = selections.entrySet().removeIf(entry -> {
            ChunkPos pos = entry.getValue();
            if (next.at(pos.x, pos.z) == null && (Math.abs(pos.x - next.x()) > TacticalBoard.RADIUS || Math.abs(pos.z - next.z()) > TacticalBoard.RADIUS))
                return false;
            return !canPick(entry.getKey(), next.at(pos.x, pos.z));
        });
        if (changed || picking == null) picking = nextField();
        if (!next.notice().isEmpty()) {
            report = next.notice();
            pickError = null;
        }
        // Each reply rotates the token. A click received during a view request
        // must use the new token and still match what the player confirmed.
        if (refreshing && pendingOrder != null) {
            if (pendingOrder.matchesPreview(previous, next)) {
                pendingOrder.send(next);
            } else {
                pendingOrder = null;
                report = tr("changed").getString();
            }
        } else {
            pendingOrder = null;
        }
        refreshing = false;
        updateControls();
    }

    private static Component tr(String key, Object... args) { return Component.translatable("tac." + key, args); }

    private Field nextField() {
        if (action.from != null && !selections.containsKey(action.from)) return action.from;
        if (action.to != null && !selections.containsKey(action.to)) return action.to;
        return null;
    }

    private TacticalBoard.Cell claim(Field field) {
        ChunkPos pos = selections.get(field);
        return pos == null ? null : board.at(pos.x, pos.z);
    }

    private void open(Action next) {
        action = next;
        tracking = false;
        if (next == Action.LAUNCH && !hasMissile(claim(Field.LAUNCH_FROM))) {
            ChunkPos base = selections.get(Field.WORKSHOP);
            if (!hasMissile(claim(Field.WORKSHOP))) {
                base = null;
                for (var entry : board.cells().entrySet()) {
                    if (!hasMissile(entry.getValue())) continue;
                    if (base != null) { base = null; break; }
                    base = new ChunkPos(entry.getKey());
                }
            }
            if (base != null) {
                selections.put(Field.LAUNCH_FROM, base);
                cx = base.x;
                cz = base.z;
            }
            else selections.remove(Field.LAUNCH_FROM);
            selections.remove(Field.LAUNCH_TO);
        }
        picking = nextField();
        pickError = null;
        rebuildWidgets();
    }

    private boolean canPick(Field field, TacticalBoard.Cell cell) {
        if (field == Field.SEND_TO) return cell != null && board.countries().get(cell.owner()).allied();
        return cell != null && cell.owner().equals(board.mine()) != field.rival();
    }

    private boolean hasMissile(TacticalBoard.Cell cell) {
        return cell != null && cell.owner().equals(board.mine()) && cell.missiles() > 0;
    }

    private int distanceFromBase(int x, int z) {
        ChunkPos base = selections.get(Field.LAUNCH_FROM);
        return Math.abs(x - base.x) + Math.abs(z - base.z);
    }

    private String pickProblem(Field field, int x, int z, TacticalBoard.Cell cell) {
        if (!canPick(field, cell)) {
            if (field == Field.SEND_TO) return "allyTarget";
            if (field == Field.MOVE_TO) return "moveDestination";
            return field.rival() ? "target" : "origin";
        }
        if (field == Field.LAUNCH_FROM && !hasMissile(cell)) return "noMissile";
        if (field == Field.LAUNCH_TO) {
            if (!hasMissile(claim(Field.LAUNCH_FROM))) return "pick.launchFrom";
            if (board.countries().get(cell.owner()).protectedByTreaty()) return "pact";
            return ArmyCombat.missileTargetProblem(distanceFromBase(x, z), cell.armies());
        }
        if (field == Field.SEND_FROM && cell.armies() == 0) return "branchLow";
        if (field == Field.MOVE_FROM && cell.armies() < 2) return "moveLeave";
        ChunkPos from = selections.get(Field.MOVE_FROM);
        if (field == Field.MOVE_TO && from != null && Math.abs(from.x - x) + Math.abs(from.z - z) != 1)
            return "adjacent";
        return null;
    }

    private void selectOnMap(int x, int z) {
        if (picking == null || tracking) return;
        String problem = pickProblem(picking, x, z, board.at(x, z));
        if (problem != null) {
            pickError = picking == Field.LAUNCH_TO ? launchProblem(problem, x, z) : tr(problem);
        } else {
            if (picking == Field.LAUNCH_FROM && !new ChunkPos(x, z).equals(selections.get(Field.LAUNCH_FROM)))
                selections.remove(Field.LAUNCH_TO);
            selections.put(picking, new ChunkPos(x, z));
            if (picking == Field.MOVE_FROM && selections.containsKey(Field.MOVE_TO) && distance() != 1)
                selections.remove(Field.MOVE_TO);
            picking = nextField();
            pickError = null;
        }
        updateControls();
    }

    private Component launchProblem(String problem, int x, int z) {
        if (problem.equals("occupation"))
            return tr(problem, numbers.format(board.at(x, z).armies()), ArmyCombat.missileDamage(distanceFromBase(x, z)) + 1);
        return tr(problem);
    }

    private void send(String order, ChunkPos from, ChunkPos to, int quantity) {
        if (pendingOrder != null) return;
        pendingOrder = new PendingOrder(order, from, to, quantity, branches.get(action), viewContext());
        if (!refreshing) pendingOrder.send(board);
        updateControls();
    }

    private TacticalBoard.ViewContext viewContext() {
        var from = selections.get(action.from);
        var to = selections.get(action.to);
        return new TacticalBoard.ViewContext(from == null ? null : from.toLong(), to == null ? null : to.toLong(), transitIndex);
    }

    private void requestView() {
        if (refreshing || pendingOrder != null) return;
        refreshing = true;
        PacketDistributor.sendToServer(new TacticalBoard.Request(board.token(), "view", cx, cz, 0, 0, 1, null, viewContext()));
    }

    private static int quantity(String amount) { return amount.isEmpty() ? 0 : Integer.parseInt(amount); }

    private String coins(long cents) {
        return String.format(minecraft.getLanguageManager().getJavaLocale(), "%.2f", BigDecimal.valueOf(cents, 2));
    }

    private Button button(int x, int y, int w, int h, Component label, Runnable click) {
        return addRenderableWidget(CampaignStyle.button(x, y, w, h, label, b -> click.run()));
    }

    private void tabRow(int y, Action... tabs) {
        int[] widths = new int[tabs.length];
        int spare = innerWidth - 3 * (tabs.length - 1);
        boolean useBold = innerWidth >= 190;
        for (int i = 0; i < tabs.length; i++) {
            Component label = tr("tab." + tabs[i].key);
            widths[i] = font.width(useBold ? label.copy().withStyle(ChatFormatting.BOLD) : label);
            spare -= widths[i];
        }
        int x = left;
        for (int i = 0; i < tabs.length; i++) {
            Action tab = tabs[i];
            int w = widths[i] + (i + 1) * spare / tabs.length - i * spare / tabs.length;
            Component label = tr("tab." + tab.key);
            if (tab == action) label = label.copy().withStyle(style -> style.withColor(MINT & 0xFFFFFF).withBold(useBold));
            Button nav = button(x, y, w, 18, label, () -> open(tab));
            if (tab == action) currentTab = nav;
            x += w + 3;
        }
    }

    private boolean hasBranches() {
        return action != Action.BUILD && action != Action.LAUNCH && action != Action.NUCLEAR;
    }

    private static String armId(TroopBranch arm) { return arm == null ? "mixed" : arm.id; }

    private void branchRow() {
        int count = action == Action.BUY ? 3 : 4;
        for (int i = 0; i < count; i++) {
            var arm = i < 3 ? TroopBranch.ALL.get(i) : null;
            int x = left + i * (innerWidth + 3) / count;
            int w = left + (i + 1) * (innerWidth + 3) / count - x - 3;
            var label = tr("branch." + armId(arm));
            boolean selected = branches.get(action) == arm;
            if (selected) label = label.copy().withStyle(ChatFormatting.BOLD).withColor(MINT & 0xFFFFFF);
            var button = button(x, headingY + (compact ? -1 : 13), w, 16, label, () -> {
                branches.put(action, arm);
                pickError = null;
                rebuildWidgets();
            });
            button.setTooltip(Tooltip.create(tr("branch.tip." + armId(arm))));
            if (selected) branchButton = button;
        }
    }

    private boolean defenseAvailable() {
        return minecraft.getConnection() != null && minecraft.getConnection().hasChannel(com.modserver.defense.DefenseDesk.Request.TYPE.id());
    }

    @Override protected void init() {
        numbers = NumberFormat.getIntegerInstance(minecraft.getLanguageManager().getJavaLocale());
        hoverChunk = Long.MIN_VALUE;
        gridWidth = width - Math.clamp(width * 43 / 100, 184, 246);
        left = gridWidth + 8;
        innerWidth = width - left - 8;
        compact = height < 284;
        headingY = compact ? 71 : 84;
        fieldY = headingY + (compact ? 20 : hasBranches() ? 37 : 28);
        rowGap = compact ? 30 : 42;
        pickers.clear();
        submit = null;

        if (defenseAvailable()) button(width - 74, 3, 69, 17, Component.literal("Defense"), com.modserver.client.DefenseScreen::open);
        tabRow(HEADER + 4, Action.BUY, Action.DEPLOY, Action.MOVE, Action.ATTACK);
        tabRow(HEADER + 24, Action.BUILD, Action.LAUNCH, Action.SEND, Action.NUCLEAR);
        if (tracking) {
            int y = Math.min(height - FOOTER - 21, headingY + 114);
            previousTrip = button(left, y, 23, 18, Component.literal("<"), () -> flipTrip(-1));
            nextTrip = button(left+innerWidth-23, y, 23, 18, Component.literal(">"), () -> flipTrip(1));
            button(left+26, y, innerWidth-52, 18, tr("convoy.new"), () -> open(Action.SEND));
            updateControls();
            return;
        }
        if (hasBranches()) branchRow();
        int controls = fieldY + 11;
        int half = (innerWidth - 4) / 2;
        boolean twoRows = action == Action.SEND || action == Action.ATTACK || action == Action.DEPLOY || action == Action.MOVE;
        int lastControl = controls + (twoRows ? rowGap : 0) + 18;
        previewY = lastControl + (twoRows ? 0 : font.lineHeight + 3) + (compact ? 4 : 8);
        if (action == Action.BUY || action == Action.NUCLEAR) previewY += font.lineHeight + 1;
        submitY = previewY + 3 * font.lineHeight + 3;

        switch (action) {
            case BUY -> {
                amountInput(left, controls, "buyAmount", buyAmount, value -> buyAmount = value);
                submit = button(left, submitY, innerWidth, 20, tr("order.buy"),
                        () -> send("BUY", null, null, quantity(buyAmount)));
            }
            case DEPLOY -> {
                picker(Field.DEPLOYMENT, left, controls, innerWidth);
                amountInput(left, controls + rowGap, "deployAmount", deployAmount, value -> deployAmount = value);
                submit = button(left, submitY, innerWidth, 20, tr("order.deploy"),
                        () -> send("DEPLOY", selections.get(Field.DEPLOYMENT), null, quantity(deployAmount)));
            }
            case SEND -> {
                picker(Field.SEND_FROM, left, controls, half);
                picker(Field.SEND_TO, left+half+4, controls, innerWidth-half-4);
                amountInput(left, controls+rowGap, innerWidth, "sendAmount", sendAmount, value -> sendAmount = value, true);
                submit = button(left, submitY, half, 20, tr("order.send"), () ->
                        send("SEND", selections.get(Field.SEND_FROM), selections.get(Field.SEND_TO), quantity(sendAmount)));
                transitToggle = button(left+half+4, submitY, innerWidth-half-4, 20, tr("convoy.tracking", board.transit().total()), () -> {
                    tracking = true;
                    rebuildWidgets();
                    requestView();
                });
            }
            case MOVE -> {
                picker(Field.MOVE_FROM, left, controls, half);
                picker(Field.MOVE_TO, left + half + 4, controls, innerWidth - half - 4);
                amountInput(left, controls + rowGap, "moveAmount", moveAmount, value -> moveAmount = value);
                submit = button(left, submitY, innerWidth, 20, tr("order.move"),
                        () -> send("MOVE", selections.get(Field.MOVE_FROM), selections.get(Field.MOVE_TO), quantity(moveAmount)));
            }
            case ATTACK -> {
                picker(Field.ATTACK_FROM, left, controls, half);
                picker(Field.ATTACK_TO, left + half + 4, controls, innerWidth - half - 4);
                var amount = amountInput(left, controls + rowGap, half, "diceLabel", attackAmount, value -> attackAmount = value, true);
                commitAll = button(left + half + 4, controls + rowGap, innerWidth - half - 4, 18, tr("allTroops"), () -> {
                    var origin = claim(Field.ATTACK_FROM);
                    if (origin != null) amount.setValue(Integer.toString(origin.forces().count(branches.get(action))));
                });
                submit = button(left, submitY, innerWidth, 20, tr("roll"),
                        () -> send("ROLL", selections.get(Field.ATTACK_FROM), selections.get(Field.ATTACK_TO), quantity(attackAmount)));
            }
            case BUILD -> {
                picker(Field.WORKSHOP, left, controls, innerWidth);
                submit = button(left, submitY, innerWidth, 20, tr("order.build"),
                        () -> send("EXCHANGE", selections.get(Field.WORKSHOP), null, 1));
            }
            case NUCLEAR -> {
                picker(Field.SILO, left, controls, half);
                picker(Field.EPICENTER, left + half + 4, controls, innerWidth - half - 4);
                buyNuclear = button(left, submitY, half, 20, tr("order.nuclearBuy"),
                        () -> send("BUY_NUCLEAR", selections.get(Field.SILO), null, 1));
                submit = button(left + half + 4, submitY, innerWidth - half - 4, 20, tr("order.nuclearFire"),
                        () -> send("NUKE", selections.get(Field.SILO), selections.get(Field.EPICENTER), 1));
            }
            case LAUNCH -> {
                picker(Field.LAUNCH_FROM, left, controls, half);
                picker(Field.LAUNCH_TO, left + half + 4, controls, innerWidth - half - 4);
                submit = button(left, submitY, innerWidth, 20, tr("order.launch"),
                        () -> send("FIRE", selections.get(Field.LAUNCH_FROM), selections.get(Field.LAUNCH_TO), 1));
            }
        }
        updateControls();
    }

    private void amountInput(int x, int y, String label, String value, Consumer<String> save) {
        amountInput(x, y, innerWidth, label, value, save, false);
    }

    private EditBox amountInput(int x, int y, int w, String label, String value, Consumer<String> save, boolean armyCount) {
        var input = addRenderableWidget(CampaignStyle.entry(font, x + 1, y + 1, w - 2, 16, tr(label)));
        input.setMaxLength(armyCount ? 10 : 4);
        input.setFilter(s -> s.length() <= (armyCount ? 10 : 4) && s.matches("[0-9]*") && (s.isEmpty() || Long.parseLong(s) <= Integer.MAX_VALUE));
        input.setValue(value);
        input.setResponder(s -> {
            save.accept(s);
            updateControls();
        });
        return input;
    }

    private void picker(Field field, int x, int y, int w) {
        Button button = button(x, y, w, 18, Component.empty(), () -> {
            picking = picking == field && selections.containsKey(field) ? nextField() : field;
            pickError = null;
            updateControls();
        });
        button.setTooltip(Tooltip.create(tr("pick." + field.key)));
        button.setTooltipDelay(Duration.ofMillis(650));
        pickers.put(field, button);
    }

    private int distance() {
        ChunkPos from = selections.get(action.from), to = selections.get(action.to);
        return Math.abs(from.x - to.x) + Math.abs(from.z - to.z);
    }

    /** Server revalidates at execution; these messages explain disabled orders in the form. */
    private Component blocker() {
        if (board.mine().equals(TacticalBoard.OBSERVER)) return tr("owner");
        if (picking != null && selections.containsKey(picking)) return tr("pick." + picking.key);
        if (action == Action.BUY) {
            int n = quantity(buyAmount);
            if (n == 0) return tr("quantity");
            if (n * board.price() > board.balance()) return tr("funds");
            if ((long) board.reserve() + n > Integer.MAX_VALUE) return tr("capacity");
            return null;
        }

        var from = claim(action.from);
        if (from == null) return missing(action.from);
        switch (action) {
            case SEND -> {
                var to = claim(Field.SEND_TO);
                if (to == null) return missing(Field.SEND_TO);
                if (!canPick(Field.SEND_TO, to)) return tr("allyTarget");
                int n = quantity(sendAmount);
                if (n == 0) return tr("convoy.quantity");
                if (n > from.forces().count(branches.get(action))) return tr("branchLow");
                if ((long)to.armies() + n > Integer.MAX_VALUE) return tr("capacity");
            }
            case MOVE -> {
                var to = claim(Field.MOVE_TO);
                if (to == null) return missing(Field.MOVE_TO);
                String problem = ArmyCombat.moveProblem(distance(), from.armies(), to.armies(), quantity(moveAmount));
                if (problem != null) return tr(problem);
                if (quantity(moveAmount) > from.forces().count(branches.get(action))) return tr("branchLow");
            }
            case DEPLOY -> {
                int n = quantity(deployAmount);
                if (n == 0) return tr("quantity");
                if (n > board.reserveForces().count(branches.get(action))) return tr("reserveLow");
                if ((long) from.armies() + n > Integer.MAX_VALUE) return tr("capacity");
            }
            case BUILD -> {
                if (from.armies() < 7 || from.missiles() == Integer.MAX_VALUE) return tr("exchangeLow");
            }
            case ATTACK, LAUNCH, NUCLEAR -> {
                if (action == Action.NUCLEAR && from.nuclearMissiles() == 0) return tr("noNuclear");
                if (action == Action.LAUNCH && from.missiles() == 0) return tr("noMissile");
                var to = claim(action.to);
                if (to == null) return missing(action.to);
                if (action == Action.LAUNCH) {
                    var target = selections.get(Field.LAUNCH_TO);
                    String problem = pickProblem(Field.LAUNCH_TO, target.x, target.z, to);
                    return problem == null ? null : launchProblem(problem, target.x, target.z);
                }
                if (board.countries().get(to.owner()).protectedByTreaty()) return tr("pact");
                int d = distance();
                if (action == Action.ATTACK) {
                    if (d != 1) return tr("adjacent");
                    if (to.attackers() > 0) return tr("battleCounts", numbers.format(to.attackers()), numbers.format(to.armies()));
                    if (quantity(attackAmount) < 1 || quantity(attackAmount) > from.armies()) return tr("commit");
                    if (quantity(attackAmount) > from.forces().count(branches.get(action))) return tr("branchLow");
                } else {
                    if (d < 1 || d > 3) return tr("missileDistance");
                }
            }
        }
        return null;
    }

    private Component missing(Field field) {
        if (field == Field.LAUNCH_TO && !selections.containsKey(field)) {
            var base = selections.get(Field.LAUNCH_FROM);
            if (base != null && Math.abs(base.x - board.x()) <= TacticalBoard.RADIUS - 3
                    && Math.abs(base.z - board.z()) <= TacticalBoard.RADIUS - 3) {
                for (int dz = -3; dz <= 3; dz++) for (int dx = -3 + Math.abs(dz); dx <= 3 - Math.abs(dz); dx++) {
                    int x = base.x + dx, z = base.z + dz;
                    if (pickProblem(field, x, z, board.at(x, z)) == null) return tr("pick.launchTo");
                }
                return tr("noMissileTargets");
            }
        }
        return selections.containsKey(field) ? tr("outside") : tr("pick." + field.key);
    }

    private void flipTrip(int delta) {
        if (refreshing || pendingOrder != null) return;
        transitIndex = Math.clamp(board.transit().index() + delta, 0, Math.max(0,board.transit().total()-1));
        requestView();
    }

    private void updateControls() {
        if (tracking) {
            previousTrip.active = board.transit().index() > 0;
            nextTrip.active = board.transit().index()+1 < board.transit().total();
            return;
        }
        if (submit == null) return;
        if (action == Action.SEND) transitToggle.setMessage(tr("convoy.tracking", board.transit().total()));
        for (var entry : pickers.entrySet()) {
            Field field = entry.getKey();
            Component label = tr((selections.containsKey(field) ? "change." : "choose.") + field.key);
            if (field == picking) {
                if (selections.containsKey(field)) label = Component.translatable("gui.cancel");
                label = label.copy().withColor(field.color() & 0xFFFFFF);
            }
            entry.getValue().setMessage(label);
            if (field == Field.LAUNCH_TO) entry.getValue().active = hasMissile(claim(Field.LAUNCH_FROM));
        }
        if (action == Action.ATTACK) {
            var from = claim(Field.ATTACK_FROM);
            commitAll.active = from != null && from.forces().count(branches.get(action)) > 0;
        }
        if (action == Action.NUCLEAR) {
            var base = claim(Field.SILO);
            buyNuclear.active = pendingOrder == null && base != null && base.owner().equals(board.mine())
                    && base.nuclearMissiles() < Integer.MAX_VALUE && board.balance() >= board.nuclearPrice();
            buyNuclear.setTooltip(Tooltip.create(board.balance() < board.nuclearPrice()
                    ? tr("funds") : tr("nuclearPrice", numbers.format(board.nuclearPrice() / 100))));
        }
        submit.active = pendingOrder == null && blocker() == null;
    }

    @Override public void tick() {
        terrain.tick(minecraft.level, board);
        if (++ticks % 40 == 0 && !dragging) requestView();
    }

    private int midX() { return gridWidth / 2; }
    private int mapBottom() { return height - FOOTER - MAP_LEGEND; }
    private int midY() { return (HEADER + mapBottom()) / 2; }
    private int mapX(double x) { return cx + Math.floorDiv((int) Math.floor(x) - midX() + cellSize / 2, cellSize); }
    private int mapZ(double y) { return cz + Math.floorDiv((int) Math.floor(y) - midY() + cellSize / 2, cellSize); }
    private boolean inMap(double x, double y) { return x >= 0 && x < gridWidth && y >= HEADER && y < mapBottom(); }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && inMap(x, y)) {
            dragging = true;
            moved = false;
            dragX = x;
            dragY = y;
            dragCenterX = cx;
            dragCenterZ = cz;
            setFocused(null);
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (!dragging) return super.mouseDragged(x, y, button, dx, dy);
        if (Math.hypot(x - dragX, y - dragY) > 4) moved = true;
        cx = Math.clamp(dragCenterX - Math.round((x - dragX) / cellSize), -1875000, 1875000);
        cz = Math.clamp(dragCenterZ - Math.round((y - dragY) / cellSize), -1875000, 1875000);
        return true;
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button != 0 || !dragging) return super.mouseReleased(x, y, button);
        dragging = false;
        if (moved) requestView();
        else if (inMap(x, y)) selectOnMap(mapX(x), mapZ(y));
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (!inMap(x, y)) return super.mouseScrolled(x, y, dx, dy);
        cellSize = Math.clamp(cellSize + (int) Math.signum(dy) * 3, 17, 50);
        return true;
    }

    private void label(GuiGraphics g, Component text, int x, int y, int max, int color) {
        g.drawString(font, font.substrByWidth(text, max).getString(), x, y, color, false);
    }

    private void drawSelection(GuiGraphics g, int px, int py, Field field, boolean confirmed) {
        int left = px + 2, top = py + 2, size = cellSize - 4;
        int right = left + size, bottom = top + size;
        int color = field.color();
        int tint = (confirmed ? 0x52000000 : 0x31000000) | (color & 0xFFFFFF);
        g.fill(left, top, right, bottom, tint);
        int stroke = confirmed ? Math.clamp(cellSize / 10, 2, 3) : 1;
        int outline = confirmed ? 0xFF3B372D : 0xA53B372D;
        if (field != action.to) {
            g.renderOutline(left - 1, top - 1, size + 2, size + 2, outline);
            g.renderOutline(left + stroke, top + stroke, size - 2 * stroke, size - 2 * stroke, outline);
            for (int i = 0; i < stroke; i++)
                g.renderOutline(left + i, top + i, size - 2 * i, size - 2 * i, color);
            return;
        }
        // Four open corners keep the target distinct from the solid origin at every zoom.
        int arm = size / 3;
        drawCorners(g, left - 1, top - 1, size + 2, stroke + 2, arm + 2, outline);
        drawCorners(g, left, top, size, stroke, arm, color);
    }

    private static void drawCorners(GuiGraphics g, int left, int top, int size, int stroke, int arm, int color) {
        int right = left + size, bottom = top + size;
        for (int side = 0; side < 2; side++) {
            int endX = side == 0 ? left : right - arm;
            int edgeX = side == 0 ? left : right - stroke;
            g.fill(endX, top, endX + arm, top + stroke, color);
            g.fill(endX, bottom - stroke, endX + arm, bottom, color);
            g.fill(edgeX, top + stroke, edgeX + stroke, top + arm, color);
            g.fill(edgeX, bottom - arm, edgeX + stroke, bottom - stroke, color);
        }
    }

    private void drawMap(GuiGraphics g, int mx, int my) {
        int bottom = mapBottom();
        g.enableScissor(0, HEADER, gridWidth, bottom);
        CampaignMapArt.paper(g, HEADER, bottom, gridWidth);
        int firstX = mapX(0), lastX = mapX(gridWidth - 1);
        int firstZ = mapZ(HEADER), lastZ = mapZ(bottom - 1);
        terrain.draw(g, board, cx, cz, midX(), midY(), cellSize, firstX, lastX, firstZ, lastZ);
        int startX = midX() + (firstX - cx) * cellSize - cellSize / 2;
        int startY = midY() + (firstZ - cz) * cellSize - cellSize / 2;

        g.drawManaged(() -> {
            for (int x = firstX, px = startX; x <= lastX; x++, px += cellSize) {
                if ((x & 3) == 0) g.fill(px, HEADER, px + 1, bottom, 0x164D4635);
            }
            for (int z = firstZ, py = startY; z <= lastZ; z++, py += cellSize) {
                if ((z & 3) == 0) g.fill(0, py, gridWidth, py + 1, 0x164D4635);
                for (int x = firstX, px = startX; x <= lastX; x++, px += cellSize) {
                    if ((x & 1) == 0 && (z & 1) == 0) {
                        g.fill(px - 2, py, px + 3, py + 1, 0x474D4635);
                        g.fill(px, py - 2, px + 1, py + 3, 0x474D4635);
                    }
                    var c = board.at(x, z);
                    if (c != null) CampaignMapArt.zone(g, board, c, x, z, px, py, cellSize);
                }
            }
        });

        ChunkPos origin = selections.get(action.from), target = selections.get(action.to);
        boolean showHover = !tracking && picking != null && !dragging && inMap(mx, my);
        boolean markTargets = picking == Field.LAUNCH_TO && hasMissile(claim(Field.LAUNCH_FROM));
        int hoverX = mapX(mx), hoverZ = mapZ(my);
        ChunkPos blast = action == Action.NUCLEAR ? target : null;
        if (action == Action.NUCLEAR && showHover && picking == Field.EPICENTER && canPick(picking, board.at(hoverX, hoverZ)))
            blast = new ChunkPos(hoverX, hoverZ);
        for (int z = firstZ, py = startY; z <= lastZ; z++, py += cellSize) {
            for (int x = firstX, px = startX; x <= lastX; x++, px += cellSize) {
                var c = board.at(x, z);
                if (blast != null && Math.abs(x - blast.x) <= 1 && Math.abs(z - blast.z) <= 1) {
                    g.fill(px, py, px + cellSize, py + cellSize, 0x77D14B25);
                    g.renderOutline(px, py, cellSize, cellSize, 0xFFF9AE50);
                }
                if (c == null) continue;
                if (origin != null && origin.x == x && origin.z == z)
                    drawSelection(g, px, py, action.from, true);
                else if (target != null && target.x == x && target.z == z)
                    drawSelection(g, px, py, action.to, true);
                else if (markTargets && pickProblem(Field.LAUNCH_TO, x, z, c) == null)
                    drawSelection(g, px, py, Field.LAUNCH_TO, false);
                else if (showHover && x == hoverX && z == hoverZ && pickProblem(picking, x, z, c) == null)
                    drawSelection(g, px, py, picking, false);
                CampaignMapArt.garrison(g, font, c, px, py, cellSize, board.countries().get(c.owner()).color());
            }
        }
        artwork.names(g, font, board, cx, cz, midX(), midY(), cellSize, gridWidth, HEADER, bottom);
        if (tracking) drawConvoyMarker(g);
        com.modserver.client.MissileRenderer.drawOnMap(g, cx, cz, midX(), midY(), cellSize);
        g.disableScissor();
        overFormationKey = CampaignMapArt.legend(g, font, gridWidth, bottom, cellSize, mx, my, artwork.hasOil());
    }

    private Component preview() {
        var from = claim(action.from);
        return switch (action) {
            case BUY -> tr("reserveAfter", numbers.format(board.reserve()), numbers.format((long)board.reserve() + quantity(buyAmount)));
            case DEPLOY -> tr("deployTotal", numbers.format(from.armies()), numbers.format((long)from.armies() + quantity(deployAmount)));
            case MOVE -> {
                int n = quantity(moveAmount);
                int to = claim(Field.MOVE_TO).armies();
                yield tr("moveTotal", numbers.format(from.armies()), numbers.format(from.armies() - n), numbers.format(to), numbers.format(to + n));
            }
            case SEND -> tr("convoy.preview", board.countries().get(claim(Field.SEND_TO).owner()).name(),
                    numbers.format(distance()), TroopTransfer.formatDuration(TroopTransfer.travelSeconds(distance())));
            case ATTACK -> tr("attackTotal", numbers.format(quantity(attackAmount)), numbers.format(claim(action.to).armies()));
            case BUILD -> tr("buildTotal", numbers.format(from.armies() - 6), numbers.format(from.missiles() + 1));
            case NUCLEAR -> {
                var center = selections.get(Field.EPICENTER);
                long casualties = 0;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    var cell = board.at(center.x + dx, center.z + dz);
                    if (cell != null) casualties += (long)cell.armies() + cell.attackers();
                }
                yield tr("nuclearPreview", numbers.format(casualties));
            }
            case LAUNCH -> {
                int damage = ArmyCombat.missileDamage(distance());
                yield tr("launchTotal", damage, numbers.format(claim(action.to).armies() - damage));
            }
        };
    }

    private long remainingTicks(TacticalBoard.Transit trip) {
        return Math.max(0, trip.remainingTicks() - (ticks - snapshotTick));
    }

    private void drawConvoyMarker(GuiGraphics g) {
        var t = board.transit().trip();
        if (t == null || !board.dimension().toString().equals(t.dimension())) return;
        var from = new ChunkPos(t.from());
        var to = new ChunkPos(t.to());
        double progress = Math.clamp(1 - remainingTicks(t) / (double)t.legTicks(), 0, 1);
        if (t.leg() == TroopTransfer.Leg.RETURNING) progress = 1 - progress;
        if (t.leg() == TroopTransfer.Leg.WAITING) progress = 0;
        int x = midX() + (int)Math.round((from.x-cx+(to.x-from.x)*progress)*cellSize);
        int y = midY() + (int)Math.round((from.z-cz+(to.z-from.z)*progress)*cellSize);
        if (x < -10 || x > gridWidth+10 || y < HEADER-10 || y > mapBottom()+10) return;
        g.fill(x-10,y-10,x+10,y+10,0xFF554565);
        g.renderOutline(x-10,y-10,20,20,0xFFE8CEE9);
        g.renderItem(Items.CHEST_MINECART.getDefaultInstance(),x-8,y-8);
    }

    private void drawTransit(GuiGraphics g) {
        var page = board.transit();
        int y = headingY;
        label(g, tr("convoy.heading"), left, y, innerWidth, GOLD);
        y += 15;
        var t = page.trip();
        if (t == null) {
            g.drawWordWrap(font, tr("convoy.empty"), left, y, innerWidth, MUTED);
            return;
        }
        label(g, tr("convoy.page", page.index()+1, page.total()),left,y,innerWidth,MUTED);
        y += 13;
        var route = Component.literal(t.originName() + " → " + t.destinationName());
        g.drawWordWrap(font, route, left, y, innerWidth, WHITE);
        y += font.wordWrapHeight(route,innerWidth)+4;
        label(g,tr("convoy." + t.leg().name()),left,y,innerWidth,MINT);
        y += 12;
        if (t.leg() != TroopTransfer.Leg.WAITING) {
            label(g,tr("convoy.eta", TroopTransfer.formatDuration((remainingTicks(t)+19)/20), numbers.format(t.distance())),left,y,innerWidth,WHITE);
            y += 12;
        }
        var troops = t.troops();
        g.drawWordWrap(font,tr("composition",numbers.format(troops.ground()),numbers.format(troops.air()),numbers.format(troops.naval())),left,y,innerWidth,MUTED);
    }

    private void underline(GuiGraphics g, Button button, int color) {
        int y = button.getY() + button.getHeight();
        g.fill(button.getX() + 2, y, button.getX() + button.getWidth() - 2, y + 1, color);
    }

    private void drawForm(GuiGraphics g) {
        g.fill(gridWidth, HEADER, width, height - FOOTER, 0xFF343B31);
        g.fill(gridWidth, HEADER, gridWidth + 1, height - FOOTER, 0xFF8B7957);
        if (tracking) {
            underline(g, currentTab, MINT);
            drawTransit(g);
            return;
        }
        if (!compact || !hasBranches()) {
            g.renderItem(action.icon.getDefaultInstance(), left, headingY - 4);
            label(g, tr("desk." + action.key), left + 21, headingY - 1, innerWidth - 21, WHITE);
        }
        if (hasBranches()) underline(g, branchButton, MINT);
        if (action.from != null) {
            int w = action.to == null ? innerWidth : (innerWidth - 4) / 2;
            label(g, tr("field." + action.from.key), left, fieldY, w, action.from.color());
            if (action.to != null) label(g, tr("field." + action.to.key), left + w + 4, fieldY, innerWidth - w - 4, action.to.color());
        }
        if (hasBranches()) {
            boolean reserve = action == Action.BUY || action == Action.DEPLOY;
            var source = reserve ? board.reserveForces() : claim(action.from) == null ? TroopCounts.EMPTY : claim(action.from).forces();
            int y = action == Action.BUY ? fieldY : fieldY + rowGap;
            var stock = tr(reserve ? "branch.reserveStock" : "branch.originStock", numbers.format(source.count(branches.get(action))));
            label(g, stock, left, y, innerWidth, MUTED);
        }

        underline(g, currentTab, MINT);
        if (picking != null) underline(g, pickers.get(picking), picking.color());
        int summaryY = fieldY + 32;
        if (action == Action.BUY) {
            label(g, tr("unitPrice", coins(board.price())), left, summaryY, innerWidth, MUTED);
            label(g, tr("buyTotal", coins(quantity(buyAmount) * board.price())), left, summaryY + font.lineHeight + 1, innerWidth, WHITE);
        } else if (!compact || !hasBranches()) {
            for (var entry : pickers.entrySet()) {
                var c = claim(entry.getKey());
                if (c == null) continue;
                var button = entry.getValue();
                Component forces = entry.getKey() == Field.SILO ? tr("nuclearStock", numbers.format(c.nuclearMissiles()))
                        : entry.getKey() == Field.LAUNCH_FROM ? tr("missileStock", numbers.format(c.missiles())) : tr("armyStock", numbers.format(c.armies()));
                label(g, forces, button.getX(), summaryY, button.getWidth(), MUTED);
            }
        }
        if (action == Action.NUCLEAR)
            label(g, tr("nuclearPrice", numbers.format(board.nuclearPrice() / 100)), left, summaryY + font.lineHeight + 1, innerWidth, GOLD);
        Component problem = pickError == null ? blocker() : pickError;
        g.drawWordWrap(font, problem == null ? preview() : problem, left, previewY, innerWidth, problem == null ? MINT : GOLD);
        if (submitY + 40 < height - FOOTER) {
            int noteY = submitY + 30;
            if (hasBranches()) {
                var available = board.reserveForces();
                var stock = tr("composition", numbers.format(available.ground()), numbers.format(available.air()), numbers.format(available.naval()));
                var lines = new java.util.ArrayList<Component>();
                if (branches.get(action) == null && blocker() == null) {
                    var source = action == Action.DEPLOY ? available : claim(action.from).forces();
                    int n = quantity(switch (action) {
                        case DEPLOY -> deployAmount;
                        case MOVE -> moveAmount;
                        case SEND -> sendAmount;
                        case ATTACK -> attackAmount;
                        default -> throw new IllegalStateException("No mixed force for " + action);
                    });
                    var units = source.take(n, null);
                    lines.add(tr("branch.orderMix", tr("composition", numbers.format(units.ground()),
                            numbers.format(units.air()), numbers.format(units.naval()))));
                }
                if (action != Action.SEND) lines.add(tr("reserveMix", stock));
                if (action != Action.SEND) lines.add(tr("branch.tip." + armId(branches.get(action))));
                for (Component line : lines) {
                    int h = font.wordWrapHeight(line, innerWidth);
                    if (noteY + h > height - FOOTER - 3) return;
                    g.drawWordWrap(font, line, left, noteY, innerWidth, GOLD);
                    noteY += h + 6;
                }
            }
            Component note = action == Action.SEND ? tr("note.send", TroopTransfer.MINUTES_PER_CHUNK) : tr("note." + action.key);
            if (noteY + font.wordWrapHeight(note, innerWidth) <= height - FOOTER - 3)
                g.drawWordWrap(font, note, left, noteY, innerWidth, MUTED);
        }
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float dt) {
        g.fill(0, 0, width, height, 0xFF272B25);
        drawMap(g, mx, my);
        drawForm(g);
        g.fill(0, HEADER - 2, width, HEADER - 1, 0xFFA58C59);
        g.renderItem(Items.FILLED_MAP.getDefaultInstance(), 6, 3);
        g.drawString(font, getTitle(), 26, 8, GOLD, false);
        Component wallet = tr("wallet", coins(board.balance()), numbers.format(board.reserve()));
        int walletX = Math.max(font.width(getTitle()) + 32, width - (defenseAvailable() ? 82 : 8) - font.width(wallet));
        label(g, wallet, walletX, 8, width - walletX - (defenseAvailable() ? 79 : 6), WHITE);

        int bottom = height - FOOTER;
        g.fill(0, bottom, width, bottom + 1, 0xFF78694D);
        Component hint = tr("navigate");
        var hover = inMap(mx, my) ? board.at(mapX(mx), mapZ(my)) : null;
        if (hover != null) hint = tr("hoverForces",
                board.countries().get(hover.owner()).name(), numbers.format(hover.armies()), numbers.format(hover.missiles()));
        if (hover != null && hover.oilField()) hint = hint.copy().append("  ·  ").append(Component.translatable("gov.oil.field"));
        if (hover != null && hover.nuclearMissiles() > 0) hint = hint.copy().append("  ·  ").append(tr("nuclearStock", numbers.format(hover.nuclearMissiles())));
        if (hover != null && board.countries().get(hover.owner()).allied()) hint = hint.copy().append("  ·  ").append(tr("convoy.ally"));
        if (hover != null && hover.attackers() > 0) hint = hint.copy().append("  ·  ").append(tr("battleCounts", numbers.format(hover.attackers()), numbers.format(hover.armies())));
        else if (hover != null && hover.disputed()) hint = hint.copy().append("  ·  ").append(tr("legend.disputes"));
        label(g, hint, 7, bottom + 4, width - 14, MUTED);
        if (!report.isEmpty()) g.drawWordWrap(font, Component.literal(report), 7, bottom + 15, width - 14, GOLD);
        else if (!tracking && action.from != null && picking == null) g.drawWordWrap(font, tr("reselect"), 7, bottom + 15, width - 14, MUTED);
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);
        var cell = inMap(mx, my) && !dragging ? board.at(mapX(mx), mapZ(my)) : null;
        boolean grouped = cell != null && (cell.armies() > 0 || cell.attackers() > 0);
        long key = grouped ? ChunkPos.asLong(mapX(mx), mapZ(my)) : Long.MIN_VALUE;
        long now = Util.getMillis();
        if (key != hoverChunk) {
            hoverChunk = key;
            hoverSince = now;
        }
        if (overFormationKey || (grouped && now - hoverSince >= 400))
            CampaignMapArt.formationCard(g, font, overFormationKey ? null : cell, numbers, mx, my, width, height);
    }

    @Override public void removed() {
        terrain.close();
        super.removed();
    }

    @Override public boolean isPauseScreen() { return false; }
}
