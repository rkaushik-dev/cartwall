package io.github.rkaushikdev.cartwall.domain;

import io.github.rkaushikdev.cartwall.Details;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every advertiser and agency on file, and who pays for whom.
 *
 * <p>The advertiser is whose commercial it is. The agency placed the order and keeps the
 * commission. The payer is whoever the invoice goes to: the agency when there is one, the
 * advertiser when there is not, except that some agencies buy on a station's paper and the
 * advertiser is still liable. That last case is what {@link #liableParty} is for.
 */
public final class AccountBook {

    private final Map<String, Advertiser> advertisers = new LinkedHashMap<>();
    private final Map<String, Agency> agencies = new LinkedHashMap<>();

    /** Every advertiser, by code, in the order opened. */
    public Map<String, Advertiser> advertisers() {
        return Collections.unmodifiableMap(advertisers);
    }

    /** Every agency, by code, in the order opened. */
    public Map<String, Agency> agencies() {
        return Collections.unmodifiableMap(agencies);
    }

    public void addAgency(Agency agency) {
        if (agencies.containsKey(agency.code())) {
            throw new AccountError("agency " + agency.code() + " is already on file");
        }
        agencies.put(agency.code(), agency);
    }

    public void addAdvertiser(Advertiser advertiser) {
        if (advertisers.containsKey(advertiser.code())) {
            throw new AccountError("advertiser " + advertiser.code() + " is already on file");
        }
        String agency = advertiser.agency().orElse("");
        if (!agency.isEmpty() && !agencies.containsKey(agency)) {
            throw new AccountError(
                    "advertiser " + advertiser.code() + " names agency " + agency + ", which is not on file");
        }
        String parent = advertiser.parent().orElse("");
        if (!parent.isEmpty() && !advertisers.containsKey(parent)) {
            throw new AccountError(
                    "advertiser " + advertiser.code() + " names parent " + parent + ", which is not on file");
        }
        advertisers.put(advertiser.code(), advertiser);
    }

    /** Put a changed account in place of the one on file under the same code. */
    public void replaceAdvertiser(Advertiser changed) {
        advertiser(changed.code());
        advertisers.put(changed.code(), changed);
    }

    public Advertiser advertiser(String code) {
        Advertiser advertiser = advertisers.get(code);
        if (advertiser == null) {
            throw new NotOnFile("advertiser " + code + " is not on file", Details.of("code", code));
        }
        return advertiser;
    }

    public Agency agency(String code) {
        Agency agency = agencies.get(code);
        if (agency == null) {
            throw new NotOnFile("agency " + code + " is not on file", Details.of("code", code));
        }
        return agency;
    }

    /** What comes off this advertiser's gross. Direct business pays none. */
    public BigDecimal commissionRate(String code) {
        Advertiser advertiser = advertiser(code);
        return advertiser.agency().map(agency -> agency(agency).rate()).orElse(BigDecimal.ZERO);
    }

    /** Whose name the invoice carries. */
    public String liableParty(String code) {
        Advertiser advertiser = advertiser(code);
        if (advertiser.isDirect()) {
            return advertiser.code();
        }
        Agency agency = agency(advertiser.agency().orElseThrow());
        return agency.advertiserLiable() ? advertiser.code() : agency.code();
    }

    /** The account the money rolls up to, walking any chain of parents. */
    public String billingGroup(String code) {
        List<Advertiser> chain = chain(code);
        return chain.get(chain.size() - 1).code();
    }

    /**
     * The standing that governs an account: its own, or the strictest of the chain it buys under.
     *
     * <p>A store's credit is the chain's credit. When the head office is held, every store is
     * held with it, whatever the store's own line in the book says, because it is the head office
     * that pays.
     */
    public Credit standing(String code) {
        return chain(code).stream().map(Advertiser::credit).reduce(Credit.OPEN, Credit::atLeast);
    }

    public boolean maySchedule(String code) {
        return standing(code).maySchedule();
    }

    /** Why the account may not be scheduled, naming the account that holds it, or nothing when it may. */
    public Optional<String> whyNotScheduled(String code) {
        return chain(code).stream()
                .filter(account -> !account.credit().maySchedule())
                .findFirst()
                .map(held -> held.code().equals(code)
                        ? code + " is on credit hold"
                        : code + " is on credit hold through " + held.code());
    }

    /** The account and every parent above it, the account first. */
    private List<Advertiser> chain(String code) {
        Advertiser advertiser = advertiser(code);
        List<Advertiser> chain = new ArrayList<>(List.of(advertiser));
        while (advertiser.parent().isPresent()) {
            String parent = advertiser.parent().get();
            if (chain.stream().anyMatch(seen -> seen.code().equals(parent))) {
                List<String> codes = chain.stream().map(Advertiser::code).toList();
                throw new AccountError(String.join(" under ", codes) + " loops back on itself");
            }
            advertiser = advertiser(parent);
            chain.add(advertiser);
        }
        return List.copyOf(chain);
    }

    /** Everybody on file this advertiser may not sit beside, in code order. */
    public List<Advertiser> conflicting(String code) {
        Advertiser subject = advertiser(code);
        return advertisers.values().stream()
                .filter(other -> !other.code().equals(code) && subject.conflictsWith(other))
                .sorted(Comparator.comparing(Advertiser::code))
                .toList();
    }
}
