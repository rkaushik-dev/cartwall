package io.github.rkaushikdev.cartwall.api.routers;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;

import io.github.rkaushikdev.cartwall.ConflictError;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.api.Caller;
import io.github.rkaushikdev.cartwall.api.Requires;
import io.github.rkaushikdev.cartwall.api.Role;
import io.github.rkaushikdev.cartwall.api.Schemas.OrderIn;
import io.github.rkaushikdev.cartwall.api.Schemas.OrderOut;
import io.github.rkaushikdev.cartwall.api.Work;
import io.github.rkaushikdev.cartwall.domain.Order;
import io.github.rkaushikdev.cartwall.domain.OrderError;
import io.github.rkaushikdev.cartwall.domain.Revision;
import io.github.rkaushikdev.cartwall.services.Audit;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order entry over HTTP.
 *
 * <p>The routes are thin: turn a body into a domain object, hand it to a repository, turn the
 * answer back into a body. Confirming and cancelling are their own routes rather than a PATCH of a
 * status field, because they are events, not edits: confirming makes an order schedulable and
 * cancelling stops the log builder touching it, and the audit trail wants each as a distinct act.
 */
@RestController
@RequestMapping("/orders")
@Tag(name = "orders")
public class OrdersController {

    private final Work work;

    public OrdersController(Work work) {
        this.work = work;
    }

    /** Write a new order. The number comes from the caller and must be free. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Role.SELLER)
    public OrderOut writeOrder(@Valid @RequestBody OrderIn body, Caller caller) {
        return work.apply(uow -> {
            if (uow.orders().exists(body.number())) {
                throw new ConflictError(
                        "order " + body.number() + " is already on file", Details.of("order", body.number()));
            }
            Order order = body.toDomain();
            uow.orders().save(order);
            Audit.tryRecord(
                    uow.entityManager(),
                    "order.written",
                    caller.name(),
                    "order",
                    order.number(),
                    field("advertiser", order.advertiser()),
                    field("lines", order.size()),
                    field("value", order.value().toString()));
            return OrderOut.of(order);
        });
    }

    @GetMapping("/{number}")
    @Requires(Role.READER)
    public OrderOut readOrder(@PathVariable String number) {
        return work.apply(uow -> OrderOut.of(uow.orders().get(number)));
    }

    /** Replace an order that has not been confirmed yet. */
    @PutMapping("/{number}")
    @Requires(Role.SELLER)
    public OrderOut rewriteOrder(@PathVariable String number, @Valid @RequestBody OrderIn body, Caller caller) {
        return work.apply(uow -> {
            if (!number.equals(body.number())) {
                throw new OrderError(
                        "the body says " + body.number() + " and the path says " + number,
                        Details.of("path", number, "body", body.number()));
            }
            Order existing = uow.orders().get(number);
            if (existing.status().maySchedule()) {
                throw new ConflictError(
                        "order " + number + " is confirmed, cancel it rather than rewriting it",
                        Details.of("order", number));
            }
            Order order = body.toDomain();
            Revision revision = Revision.between(existing, order);
            uow.orders().save(order);
            Audit.tryRecord(
                    uow.entityManager(),
                    "order.rewritten",
                    caller.name(),
                    "order",
                    order.number(),
                    field("changes", revision.summary()),
                    field("value", revision.valueChange().toString()));
            return OrderOut.of(order);
        });
    }

    /** Make an order schedulable. Refused if the advertiser is on hold. */
    @PostMapping("/{number}/confirm")
    @Requires(Role.SELLER)
    public OrderOut confirmOrder(@PathVariable String number, Caller caller) {
        return work.apply(uow -> {
            Order order = uow.orders().get(number);
            if (!uow.accounts().book().maySchedule(order.advertiser())) {
                throw new ConflictError(
                        order.advertiser() + " is on credit hold",
                        Details.of("order", number, "advertiser", order.advertiser()));
            }
            Order confirmed = order.confirmed();
            uow.orders().save(confirmed);
            Audit.tryRecord(
                    uow.entityManager(),
                    "order.confirmed",
                    caller.name(),
                    "order",
                    number,
                    field("advertiser", order.advertiser()),
                    field("value", order.value().toString()));
            return OrderOut.of(confirmed);
        });
    }

    @PostMapping("/{number}/cancel")
    @Requires(Role.SELLER)
    public OrderOut cancelOrder(@PathVariable String number, Caller caller) {
        return work.apply(uow -> {
            Order cancelled = uow.orders().get(number).cancelled();
            uow.orders().save(cancelled);
            Audit.tryRecord(uow.entityManager(), "order.cancelled", caller.name(), "order", number);
            return OrderOut.of(cancelled);
        });
    }

    @GetMapping
    @Requires(Role.READER)
    public List<OrderOut> listOrders(
            @Parameter(description = "Account code the orders belong to") @RequestParam String advertiser) {
        return work.apply(uow -> uow.orders().forAdvertiser(advertiser).stream()
                .map(OrderOut::of)
                .toList());
    }

    /** Take an order off the file. Confirmed orders are cancelled, not deleted. */
    @DeleteMapping("/{number}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Requires(Role.SELLER)
    public void deleteOrder(@PathVariable String number, Caller caller) {
        work.run(uow -> {
            Order order = uow.orders().get(number);
            if (order.status().maySchedule()) {
                throw new ConflictError(
                        "order " + number + " is confirmed, cancel it rather than deleting it",
                        Details.of("order", number));
            }
            uow.orders().delete(number);
            Audit.tryRecord(uow.entityManager(), "order.deleted", caller.name(), "order", number);
        });
    }
}
