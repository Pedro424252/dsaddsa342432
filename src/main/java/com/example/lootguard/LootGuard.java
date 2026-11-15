package com.example.lootguard;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aesir Loot Guard - Gaks_v310
 *
 * SADECE SENIN oldurdugun oyuncu icin, kilit suresi boyunca:
 *  - ekranda "X OLDU" yazar ve eline kilic alir
 *  - kristal / glowstone / anchor eline gelirse surekli guvenli slota gecer
 *  - kristal / glowstone / anchor kullanimini ve kristale vurmayi engeller
 *
 * HIZ: tum zamanlama mikrosaniye (System.nanoTime) uzerinden. Olum paketi (status 3) netty thread'inde,
 *  ana thread'e siraya girmeden ONCE yakalanir ve kilit hemen kurulur (ClientConnectionMixin).
 *  Ayrica TAHMIN modu: sunucuya gonderilmis ama cevabi gelmemis kristal vurulari us damgasiyla sayilir;
 *  hedef bunlarla olmus olabilirse yeni kristal vurusu/yerlestirmesi ANINDA durdurulur.
 *
 * "Senin oldurdugun" su sekilde anlasilir:
 *  1) olum mesajinda senin adin gecerse (X was slain by SEN, blown up by SEN ...)
 *  2) mesaj yoksa: son 8 sn icinde ona vurduysan
 *  3) mesaj yoksa: son 4 sn icinde yakinindaki bir kristale vurduysan
 *  4) anchor: "[Intentional Game Design]" mesaji + son 4 sn icinde anchor'a tikladiysan
 */
public class LootGuard implements ClientModInitializer {
    public static volatile boolean enabled = true;
    public static volatile int lockSeconds = 5;   // kilit suresi (saniye), GUI'den ayarlanir
    public static volatile boolean predict = true;     // tahmin modu (olumden ONCE durdur)
    public static volatile int estDamage = 10;         // bir kristalin tahmini hasari (HP)
    public static volatile int estAnchorDamage = 20;   // bir anchor patlamasinin tahmini hasari (HP)
    public static volatile boolean ignoreFake = true;  // sunucunun anti-aimbot sahte oyuncularini yok say

    private static final double RANGE = 30.0;           // olum algilama mesafesi (blok)
    private static final double NEAR = 20.0;            // "kayboldu" algilamasi icin mesafe
    private static final double CRYSTAL_RADIUS = 12.0;  // kristal patlamasinin etki mesafesi
    private static final double ANCHOR_RADIUS = 12.0;   // anchor patlamasinin etki mesafesi

    // --- TUM ZAMANLAR MIKROSANIYE (us). Saat: System.nanoTime() / 1000 ---
    private static final long US = 1_000_000L;                    // 1 saniye = 1_000_000 us
    private static final long DEDUPE_US = 4 * US;                 // ayni oyuncu icin tekrar algilama araligi
    private static final long HIT_WINDOW_US = 8 * US;             // vurustan sonra "benim" sayilma suresi
    private static final long CRYSTAL_WINDOW_US = 4 * US;         // kristale vurdugundan sonra sure
    private static final long ANCHOR_WINDOW_US = 4 * US;          // anchor'a tikladiktan sonra sure
    private static final long MIN_WINDOW_US = 150_000L;           // tahmin penceresi alt sinir (150 ms)
    private static final long MAX_WINDOW_US = 600_000L;           // tahmin penceresi ust sinir (600 ms)
    private static final long WINDOW_MARGIN_US = 100_000L;        // ping uzerine eklenen pay (100 ms)

    // --- SAHTE OYUNCU (anti-aimbot botu) tespiti ---
    private static final int IMMUNE_HITS = 5;                     // bu kadar vurusa hic hasar yemezse sahte say
    private static final long IMMUNE_MIN_US = 1_500_000L;         // ilk vurustan beri en az 1.5 sn gecmis olmali
    private static final long TRACK_RESET_US = 10 * US;           // vurus serisi bu kadar sure bos kalirsa sifirlanir
    private static final long FAKE_TTL_US = 30 * US;              // "hasar yemiyor" isaretinin gecerlilik suresi

    private static long nowUs() {
        return System.nanoTime() / 1000L;
    }

    private record Seen(String name, double x, double y, double z) {}
    /** Zaman damgali konum kaydi; tek nesne oldugu icin thread'ler arasi tutarli okunur. */
    private record Mark(long tUs, double x, double y, double z) {}
    /** Sunucuya gonderilmis, cevabi henuz gelmemis kristal vurusu. */
    private record Hit(long id, long tUs, double x, double y, double z, float dmg, double radius) {}
    /** Bir oyuncuya attigimiz vuruslarin kaydi (hasar yiyor mu?). */
    private record Track(long firstUs, long lastUs, int hits, float hp) {}

    private static KeyBinding openKey;
    private static KeyBinding toggleKey;
    private static volatile long lockUntilUs = nowUs() - 1;
    private static volatile String lastName = "";
    private static volatile boolean wantSword = false;   // netty thread'inden ana thread'e "kilica gec" istegi

    // netty thread'i (paket gelisi) ile ana thread ortak kullandigi icin thread-safe
    private static final Map<String, Long> lastTrigger = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastHit = new ConcurrentHashMap<>();
    private static volatile Mark crystalMark = null;
    private static volatile Mark anchorMark = null;

    // sadece ana thread
    private static Map<UUID, Seen> prevNear = new HashMap<>();
    private static Object lastWorld = null;
    private static final ArrayDeque<Hit> inflight = new ArrayDeque<>();
    private static final Map<UUID, Track> tracks = new HashMap<>();

    // netty thread'i de okudugu icin thread-safe
    private static final Map<UUID, Long> fakeUntil = new ConcurrentHashMap<>();
    private static volatile boolean tabTrusted = false;   // sunucu tab listesini gercekten gonderiyor mu?

    /**
     * Bu oyuncu sunucunun anti-aimbot eklentisinin spawnladigi SAHTE oyuncu mu?
     *  1) Tab listesinde kaydi yok: modern surumde oyuncu entity'si ancak tab kaydiyla spawn olabilir;
     *     sahte botlar kaydi hemen sildigi icin listede bulunmazlar. Gercek oyuncular listede kalir.
     *     (Sunucu tab listesini hic gondermiyorsa bu kontrol otomatik devre disi kalir: tabTrusted.)
     *  2) Ona defalarca vurduk ama hic hasar yemedi (hurtTime/can degismedi) -> hasar almayan bot.
     */
    private static boolean isFake(MinecraftClient client, PlayerEntity p) {
        if (!ignoreFake) return false;
        UUID id = p.getUuid();
        Long until = fakeUntil.get(id);
        if (until != null) {
            if (nowUs() < until) return true;
            fakeUntil.remove(id);
        }
        if (tabTrusted) {
            ClientPlayNetworkHandler nh = client.getNetworkHandler();
            if (nh != null && nh.getPlayerListEntry(id) == null) return true;
        }
        return false;
    }

    /** Ana thread: tab listesine guvenilir mi? (sunucu bizden baska kimseyi listelemiyorsa guvenme) */
    private static void updateTabTrust(MinecraftClient client) {
        ClientPlayNetworkHandler nh = client.getNetworkHandler();
        tabTrusted = nh != null && nh.getPlayerList().size() > 1;
    }

    /** Ana thread: oyuncuya vurdugumuzu kaydet. */
    private static void noteHit(PlayerEntity p) {
        long now = nowUs();
        UUID id = p.getUuid();
        Track t = tracks.get(id);
        float hp = p.getHealth() + p.getAbsorptionAmount();
        if (t == null || now - t.lastUs() > TRACK_RESET_US) {
            if (tracks.size() > 64) tracks.clear();
            tracks.put(id, new Track(now, now, 1, hp));
        } else {
            tracks.put(id, new Track(t.firstUs(), now, t.hits() + 1, t.hp()));
        }
    }

    /** Ana thread: vurus kaydina gore "hasar yemiyor" isaretini guncelle. */
    private static void updateImmunity(PlayerEntity p, long now) {
        UUID id = p.getUuid();
        Track t = tracks.get(id);
        if (t == null) return;
        float hp = p.getHealth() + p.getAbsorptionAmount();
        if (p.hurtTime > 0 || hp < t.hp() - 0.01f) {   // hasar yedi -> gercek oyuncu
            tracks.remove(id);
            fakeUntil.remove(id);
        } else if (t.hits() >= IMMUNE_HITS && now - t.firstUs() >= IMMUNE_MIN_US) {
            fakeUntil.put(id, now + FAKE_TTL_US);
        }
    }

    /** Mesajdaki kurban dunyada gorunuyorsa ve sahteyse true. */
    private static boolean victimIsFake(MinecraftClient client, String name) {
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p.getName().getString().equals(name)) return isFake(client, p);
        }
        return false;
    }

    /** Tahmin penceresi (us): ping + pay, [150ms, 600ms] araliginda. */
    private static long predictWindowUs(MinecraftClient client) {
        long pingMs = 0;
        if (client.getNetworkHandler() != null && client.player != null) {
            var e = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            if (e != null) pingMs = e.getLatency();
        }
        return Math.max(MIN_WINDOW_US, Math.min(MAX_WINDOW_US, pingMs * 1000L + WINDOW_MARGIN_US));
    }

    /**
     * (x,y,z) civarindaki oyunculardan biri, sunucuya zaten gonderdigimiz ama cevabi henuz gelmemis
     * kristallerle olmus olabilir mi? Olabilirse true -> bu kristal islemi ENGELLENIR.
     *
     * Eski surum: hp degisince sayaci SIFIRLIYORDU. Spam'de ilk cevap gelince hala yolda olan
     * kristaller unutuluyor ve loot patliyordu. Yeni surum: her vurus kendi mikrosaniye damgasiyla
     * tutulur, SADECE pencere (ping+pay) dolunca duser. Ayni kristale tekrar vurmak tekrar sayilmaz.
     * Totemi olan hedef: her totem bir olumcul vurusu daha tolere eder.
     */
    private static boolean predictCheck(MinecraftClient client, double x, double y, double z,
                                        long id, float dmg, double radius, boolean count) {
        if (!enabled || !predict || client.player == null || client.world == null) return false;
        long now = nowUs();
        long win = predictWindowUs(client);
        while (!inflight.isEmpty() && now - inflight.peekFirst().tUs() > win) inflight.pollFirst();

        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p == client.player || p.isDead() || isFake(client, p)) continue;
            if (!within(p.getX(), p.getY(), p.getZ(), x, y, z, radius)) continue;
            float flying = 0f;   // yoldaki kristal + anchor hasarlarinin toplami
            for (Hit h : inflight) {
                if (within(p.getX(), p.getY(), p.getZ(), h.x(), h.y(), h.z(), h.radius())) flying += h.dmg();
            }
            float hp = p.getHealth() + p.getAbsorptionAmount();
            int totems = (p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) ? 1 : 0)
                    + (p.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) ? 1 : 0);
            if (hp + totems * dmg - flying <= 0f) return true;
        }
        if (count) {
            for (Hit h : inflight) if (h.id() == id) return false;   // ayni kristal/anchor, tekrar sayma
            inflight.addLast(new Hit(id, now, x, y, z, dmg, radius));
        }
        return false;
    }

    public static boolean locked() {
        return enabled && nowUs() - lockUntilUs < 0;
    }

    private static boolean bad(ItemStack s) {
        return s.isOf(Items.END_CRYSTAL) || s.isOf(Items.GLOWSTONE) || s.isOf(Items.RESPAWN_ANCHOR);
    }

    /** Bu oyuncuyu ben mi oldurdum? (mesaj yoksa vurus/kristal kaydina bakar) */
    private static boolean mine(String name, double x, double y, double z) {
        long now = nowUs();
        Long h = lastHit.get(name);
        if (h != null && now - h < HIT_WINDOW_US) return true;
        Mark c = crystalMark;
        if (c != null && now - c.tUs() < CRYSTAL_WINDOW_US && within(x, y, z, c.x(), c.y(), c.z(), CRYSTAL_RADIUS)) return true;
        Mark a = anchorMark;
        if (a != null && now - a.tUs() < ANCHOR_WINDOW_US && within(x, y, z, a.x(), a.y(), a.z(), ANCHOR_RADIUS)) return true;
        return false;
    }

    private static boolean within(double x, double y, double z, double ax, double ay, double az, double r) {
        double dx = x - ax, dy = y - ay, dz = z - az;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    /** Thread-safe: kilidi hemen kurar (netty thread'inden de cagrilabilir). Envantere DOKUNMAZ. */
    private static void lockNow(String name) {
        long now = nowUs();
        Long last = lastTrigger.get(name);
        if (last != null && now - last < DEDUPE_US) return;
        lastTrigger.put(name, now);
        lastName = name;
        wantSword = true;
        lockUntilUs = now + lockSeconds * US;
    }

    /** Ana thread: kilit + kilica gec. */
    private static void trigger(MinecraftClient client, String name) {
        lockNow(name);
        applySword(client);
    }

    private static void applySword(MinecraftClient client) {
        if (!wantSword || client.player == null) return;
        wantSword = false;
        switchToSword(client);
    }

    /**
     * NETTY THREAD: sunucudan gelen paket ana thread'e siraya girmeden ONCE burada gorulur.
     * Olum durumu (status 3) ve olen oyuncu benimse kilit mikrosaniyeler icinde kurulur;
     * ana thread'in tick/frame'i beklenmez.
     */
    public static void onPacketNetty(Packet<?> packet) {
        if (!enabled) return;
        if (!(packet instanceof EntityStatusS2CPacket st) || st.getStatus() != 3) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            ClientWorld w = mc.world;
            if (w == null || mc.player == null) return;
            Entity e = st.getEntity(w);
            if (!(e instanceof AbstractClientPlayerEntity p) || p == mc.player) return;
            if (isFake(mc, p)) return;   // anti-aimbot botu, olum sayma
            if (mine(p.getName().getString(), p.getX(), p.getY(), p.getZ())) lockNow(p.getName().getString());
        } catch (Throwable ignored) {
            // netty thread'inde dunya okumasi nadiren yaris yaratabilir; ana thread taramasi yedek
        }
    }

    private static void switchToSword(MinecraftClient client) {
        var inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) {
                inv.setSelectedSlot(i);
                return;
            }
        }
    }

    /** Oncelik: kilic -> bos slot -> pearl -> totem. Bulamazsa bir sey yapmaz. */
    private static void switchToSafe(MinecraftClient client) {
        var inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) { inv.setSelectedSlot(i); return; }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) { inv.setSelectedSlot(i); return; }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.ENDER_PEARL)) { inv.setSelectedSlot(i); return; }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) { inv.setSelectedSlot(i); return; }
        }
    }

    /** Olum taramasi. Tick basinda, tick sonunda VE her tikta (kristal/anchor) cagrilir - gecikme minimum. */
    private static void scan(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            prevNear.clear();
            inflight.clear();
            tracks.clear();
            fakeUntil.clear();
            tabTrusted = false;
            lastWorld = null;
            return;
        }
        applySword(client);   // netty thread'inin istedigi kilic gecisi
        if (!enabled) {
            prevNear.clear();
            return;
        }
        if (client.world != lastWorld) {
            lastWorld = client.world;
            prevNear.clear();
            tracks.clear();
            fakeUntil.clear();
        }
        updateTabTrust(client);
        long nowScan = nowUs();

        Map<UUID, Seen> curNear = new HashMap<>();
        Set<UUID> present = new HashSet<>();
        for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
            if (p == client.player) continue;
            present.add(p.getUuid());
            updateImmunity(p, nowScan);
            if (isFake(client, p)) continue;        // sunucunun sahte oyuncusu: hicbir sekilde algilama
            String name = p.getName().getString();
            double d2 = client.player.squaredDistanceTo(p);
            if (p.isDead()) {                       // 1) olu olarak goruldu
                if (d2 <= RANGE * RANGE && mine(name, p.getX(), p.getY(), p.getZ())) {
                    trigger(client, name);
                }
                continue;
            }
            if (d2 <= NEAR * NEAR) curNear.put(p.getUuid(), new Seen(name, p.getX(), p.getY(), p.getZ()));
        }
        // 2) yakindayken bir anda kayboldu (olu hali hic gorulmediyse)
        for (Map.Entry<UUID, Seen> e : prevNear.entrySet()) {
            if (present.contains(e.getKey())) continue;
            Seen s = e.getValue();
            if (mine(s.name(), s.x(), s.y(), s.z())) trigger(client, s.name());
        }
        prevNear = curNear;
    }

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("lootguard", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lootguard.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));
        // Ac/kapat tusu: varsayilan atanmamis, Ayarlar > Kontroller > Aesir Loot Guard'dan istedigin tusu ver
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lootguard.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

        // Sag ust: koruma aciksa "Loot Koruma Acik" yazar, kapaliysa hic gorunmez
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                Identifier.of("lootguard", "status"), (context, tickCounter) -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (!enabled || mc.options.hudHidden || mc.player == null) return;
                    Text t = Text.literal("Loot Koruma A\u00E7\u0131k");
                    int x = context.getScaledWindowWidth() - mc.textRenderer.getWidth(t) - 4;
                    context.drawTextWithShadow(mc.textRenderer, t, x, 4, 0xFF55FF55);
                });

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Aesir Loot Guard"),
                        b -> MinecraftClient.getInstance().setScreen(new LootGuardScreen()))
                        .dimensions(5, 5, 110, 20).build());
            }
        });

        ClientTickEvents.START_CLIENT_TICK.register(client -> scan(client));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                client.setScreen(new LootGuardScreen());
            }
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                if (client.player != null) {
                    client.inGameHud.setOverlayMessage(Text.literal("Loot Koruma: " + (enabled ? "A\u00E7\u0131k" : "Kapal\u0131")), false);
                }
            }
            scan(client);
            if (client.player == null || !enabled) return;

            if (locked() && bad(client.player.getMainHandStack())) {
                switchToSafe(client);   // kilit boyunca surekli kontrol, her tick
            }
            if (locked() && client.player.age % 5 == 0) {
                long leftUs = Math.max(0, lockUntilUs - nowUs());
                client.inGameHud.setOverlayMessage(
                        Text.literal("Aesir Loot Guard: " + lastName + " OLDU - loot korunuyor ("
                                + (leftUs / US + 1) + " sn)"), false);
            }
        });

        // 3) sohbetteki olum mesaji: sadece mesajda SENIN adin geciyorsa
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!enabled || overlay) return;
            if (!(message.getContent() instanceof TranslatableTextContent t)) return;
            String key = t.getKey();
            if (!key.startsWith("death.")) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) return;
            String myName = client.player.getName().getString();

            // Anchor: "X was killed by [Intentional Game Design]" - mesajda katil adi yok
            if (key.startsWith("death.attack.badRespawnPoint")) {
                Object[] a = t.getArgs();
                if (a.length == 0) return;
                String v = (a[0] instanceof Text tx0) ? tx0.getString() : String.valueOf(a[0]);
                if (v.equals(myName)) return;
                if (victimIsFake(client, v)) return;
                Mark am = anchorMark;
                if (am == null || nowUs() - am.tUs() >= ANCHOR_WINDOW_US) return;
                boolean ok = true;   // oyuncu goruluyorsa anchor'a yakin olmali
                for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
                    if (p.getName().getString().equals(v)) {
                        ok = within(p.getX(), p.getY(), p.getZ(), am.x(), am.y(), am.z(), ANCHOR_RADIUS);
                        break;
                    }
                }
                if (ok) trigger(client, v);
                return;
            }
            String victim = null;
            boolean me = false;
            Object[] args = t.getArgs();
            for (int i = 0; i < args.length; i++) {
                String s = (args[i] instanceof Text tx) ? tx.getString() : String.valueOf(args[i]);
                if (i == 0) victim = s;
                else if (s.equals(myName)) me = true;
            }
            if (me && victim != null && !victim.equals(myName) && !victimIsFake(client, victim)) trigger(client, victim);
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient()) return ActionResult.PASS;
            if (enabled) scan(MinecraftClient.getInstance());   // tiktan hemen once olum kontrolu
            BlockState bs = world.getBlockState(hit.getBlockPos());
            boolean anchor = bs.isOf(Blocks.RESPAWN_ANCHOR);
            if (locked()) {
                if (bad(player.getStackInHand(hand)) || anchor) return ActionResult.FAIL;
                return ActionResult.PASS;
            }
            if (player.getStackInHand(hand).isOf(Items.END_CRYSTAL)
                    && predictCheck(MinecraftClient.getInstance(), hit.getBlockPos().getX() + 0.5,
                            hit.getBlockPos().getY() + 1, hit.getBlockPos().getZ() + 0.5,
                            -1L, (float) estDamage, CRYSTAL_RADIUS, false)) {
                return ActionResult.FAIL;   // olum tahmini: yeni kristal koyma
            }
            if (anchor && enabled) {
                // Patlayacak tik mi? (sarjli + elde glowstone sarj etmiyor). Patlayacaksa olum tahmini:
                // yoldaki kristal/anchor hasari hedefi oldurduyse yeni anchor patlatma ENGELLENIR.
                int charges = bs.get(RespawnAnchorBlock.CHARGES);
                boolean glow = player.getStackInHand(hand).isOf(Items.GLOWSTONE);
                boolean explodes = charges > 0 && !(glow && charges < 4);
                if (explodes) {
                    long aid = hit.getBlockPos().asLong() ^ (1L << 62);   // kristal entity id'leriyle cakismasin
                    if (predictCheck(MinecraftClient.getInstance(), hit.getBlockPos().getX() + 0.5,
                            hit.getBlockPos().getY() + 0.5, hit.getBlockPos().getZ() + 0.5,
                            aid, (float) estAnchorDamage, ANCHOR_RADIUS, true)) {
                        return ActionResult.FAIL;
                    }
                }
                // anchor'a tikladim: patlarsa benim sayilir
                anchorMark = new Mark(nowUs(), hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ());
            }
            return ActionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient()) return ActionResult.PASS;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (enabled) scan(mc);                               // tiktan hemen once olum kontrolu
            if (entity instanceof EndCrystalEntity) {
                if (locked()) return ActionResult.FAIL;
                if (predictCheck(mc, entity.getX(), entity.getY(), entity.getZ(), entity.getId(), (float) estDamage, CRYSTAL_RADIUS, true)) return ActionResult.FAIL;   // yoldaki kristaller oldurmus olabilir
                crystalMark = new Mark(nowUs(), entity.getX(), entity.getY(), entity.getZ());
            } else if (entity instanceof PlayerEntity pe) {
                if (isFake(mc, pe)) return ActionResult.PASS;   // sahte bota vurmak "benim" sayilmaz
                noteHit(pe);
                lastHit.put(entity.getName().getString(), nowUs());
            }
            return ActionResult.PASS;
        });
    }
}
