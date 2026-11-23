package io.github.rkaushikdev.cartwall.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Everything production has dubbed, live and dead, by cart number. */
public final class CutLibrary {

    private final Map<String, Cut> cuts = new LinkedHashMap<>();

    /** File a new cut. A cart number is filed once. */
    public void file(Cut cut) {
        if (cuts.containsKey(cut.cart())) {
            throw new SpotError("cart " + cut.cart() + " is already filed");
        }
        cuts.put(cut.cart(), cut);
    }

    /** Replace a cut in place. Production redubs against the same cart. */
    public void refile(Cut cut) {
        if (!cuts.containsKey(cut.cart())) {
            throw new SpotError("cart " + cut.cart() + " has never been filed");
        }
        cuts.put(cut.cart(), cut);
    }

    public Cut cut(String cart) {
        Cut cut = cuts.get(cart);
        if (cut == null) {
            throw new SpotError("cart " + cart + " is not in the library");
        }
        return cut;
    }

    /** Stop a cut after {@code on} without losing what it was or what it ran. */
    public Cut kill(String cart, LocalDate on) {
        Cut killed = cut(cart).killedAfter(on);
        cuts.put(cart, killed);
        return killed;
    }

    /** Cuts eligible to air on {@code day}, in cart order. */
    public List<Cut> running(LocalDate day) {
        return matching(cut -> cut.runsOn(day));
    }

    /** One advertiser's cuts eligible to air on {@code day}, in cart order. */
    public List<Cut> running(LocalDate day, String advertiser) {
        return matching(cut -> cut.runsOn(day) && cut.advertiser().equals(advertiser));
    }

    /** Every cut, live or dead, one advertiser has had dubbed, in cart order. */
    public List<Cut> forAdvertiser(String advertiser) {
        return matching(cut -> cut.advertiser().equals(advertiser));
    }

    /**
     * Every cut whose code carries {@code prefix}, in cart order.
     *
     * <p>An agency dubbing for several clients registers one prefix, so this is how production
     * finds everything that came in on one agency's dub list, whoever the advertiser is.
     */
    public List<Cut> withPrefix(String prefix) {
        return matching(cut -> cut.code().prefix().equals(prefix));
    }

    /** Which sold lengths this advertiser can actually be scheduled in on {@code day}. */
    public Set<SpotLength> lengthsOnHand(String advertiser, LocalDate day) {
        return running(day, advertiser).stream().map(Cut::length).collect(Collectors.toUnmodifiableSet());
    }

    private List<Cut> matching(Predicate<Cut> test) {
        return cuts.values().stream()
                .filter(test)
                .sorted(Comparator.comparing(Cut::cart))
                .toList();
    }

    /** Every cut, in the order filed. */
    public List<Cut> cuts() {
        return List.copyOf(cuts.values());
    }

    public int size() {
        return cuts.size();
    }

    public boolean contains(String cart) {
        return cuts.containsKey(cart);
    }
}
