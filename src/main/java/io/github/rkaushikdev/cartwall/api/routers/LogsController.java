package io.github.rkaushikdev.cartwall.api.routers;

import static io.github.rkaushikdev.cartwall.HouseLog.Field.field;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.ConflictError;
import io.github.rkaushikdev.cartwall.Details;
import io.github.rkaushikdev.cartwall.LookupFailure;
import io.github.rkaushikdev.cartwall.api.Caller;
import io.github.rkaushikdev.cartwall.api.Requires;
import io.github.rkaushikdev.cartwall.api.Role;
import io.github.rkaushikdev.cartwall.api.Work;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.CutLibrary;
import io.github.rkaushikdev.cartwall.logs.Approval;
import io.github.rkaushikdev.cartwall.logs.DayLog;
import io.github.rkaushikdev.cartwall.logs.LogLine;
import io.github.rkaushikdev.cartwall.logs.LogState;
import io.github.rkaushikdev.cartwall.logs.LogStatus;
import io.github.rkaushikdev.cartwall.logs.Problem;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.services.Audit;
import io.github.rkaushikdev.cartwall.services.Houses;
import io.github.rkaushikdev.cartwall.services.LogBuild;
import io.github.rkaushikdev.cartwall.services.LogBuild.BuildReport;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading a log, signing it and sending it out.
 *
 * <p>The log is one document two audiences want in two shapes. The operator and the automation
 * want every element in air order, network breaks and legal IDs included; sales wants the spots
 * and nothing else. Both come off the same route, and the {@code spots} query decides which.
 *
 * <p>Building is a POST rather than a side effect of a GET: it writes, it can take a second on a
 * heavy week, and a GET that quietly rebuilds a log is a GET somebody will call from a dashboard on
 * a timer.
 */
@RestController
@RequestMapping("/logs")
@Tag(name = "logs")
public class LogsController {

    private final Work work;

    public LogsController(Work work) {
        this.work = work;
    }

    /** One element of a log as the operator reads it. */
    public record LogLineOut(
            String at,
            int hour,
            int breakNumber,
            String element,
            String length,
            String order,
            int line,
            String advertiser,
            String cart) {}

    /** A log and where it stands. */
    public record LogOut(
            String station,
            LocalDate day,
            LogState state,
            int revision,
            String approvedBy,
            int soldUnits,
            int openUnits,
            List<LogLineOut> lines) {}

    /** One thing wrong with a log, or worth knowing about it. */
    public record ProblemOut(String code, String where, String detail) {}

    /** Whether a log may be signed, and what stands in the way. */
    public record ReadyOut(
            String station, LocalDate day, boolean ready, List<ProblemOut> problems, List<ProblemOut> warnings) {}

    /** What one build of the week did. */
    public record BuildOut(String week, int placed, int wanted, boolean complete, int written, List<String> skipped) {}

    /** Who signs, and when; the moment defaults to now. */
    public record SignIn(@NotNull(message = "Field required") String who, LocalDateTime at) {}

    @GetMapping("/{station}/{day}")
    @Requires(Role.READER)
    public LogOut readLog(
            @PathVariable String station,
            @PathVariable LocalDate day,
            @Parameter(description = "Spots only, without the fixed elements") @RequestParam(defaultValue = "false")
                    boolean spots) {
        return work.apply(uow -> logOut(uow, station, day, spots));
    }

    /** What is wrong with this log, and what is merely worth knowing. */
    @GetMapping("/{station}/{day}/ready")
    @Requires(Role.READER)
    public ReadyOut checkLog(@PathVariable String station, @PathVariable LocalDate day) {
        return work.apply(uow -> {
            ClockGrid grid = Houses.gridFor(uow, station, Optional.of(day));
            DayLog built = DayLog.build(grid, logs(uow).board(grid, day));
            CutLibrary library = uow.accounts().library();
            List<Problem> problems = Approval.problems(built, library);
            return new ReadyOut(
                    grid.station(),
                    day,
                    problems.isEmpty(),
                    problems.stream().map(LogsController::problemOut).toList(),
                    Approval.warnings(built, library).stream()
                            .map(LogsController::problemOut)
                            .toList());
        });
    }

    /** Rebuild the broadcast week containing {@code weekOf} across the cluster. */
    @PostMapping("/build/{weekOf}")
    @Requires(Role.TRAFFIC)
    public BuildOut build(@PathVariable LocalDate weekOf, Caller caller) {
        return work.apply(uow -> {
            BroadcastWeek week = BroadcastWeek.of(weekOf);
            BuildReport report = LogBuild.buildWeek(uow, Houses.houseFrom(uow, Optional.empty()), week);
            Audit.tryRecord(
                    uow.entityManager(),
                    "log.built",
                    caller.name(),
                    "week",
                    week.toString(),
                    field("placed", report.placed()),
                    field("wanted", report.wanted()),
                    field("written", report.written().size()));
            return new BuildOut(
                    week.toString(),
                    report.placed(),
                    report.wanted(),
                    report.isComplete(),
                    report.written().size(),
                    report.skipped().stream()
                            .map(skip -> skip.call() + " " + skip.day() + " " + skip.state())
                            .toList());
        });
    }

    @PostMapping("/{station}/{day}/approve")
    @Requires(Role.TRAFFIC)
    public LogOut approve(
            @PathVariable String station, @PathVariable LocalDate day, @Valid @RequestBody SignIn body, Caller caller) {
        return work.apply(uow -> {
            LogRepository logs = logs(uow);
            ClockGrid grid = Houses.gridFor(uow, station, Optional.of(day));
            if (logs.status(grid.station(), day).state() != LogState.DRAFT) {
                throw new ConflictError(
                        grid.station() + " " + day + " is not a draft",
                        Details.of("station", grid.station(), "day", day.toString()));
            }
            List<Problem> found = Approval.problems(
                    DayLog.build(grid, logs.board(grid, day)), uow.accounts().library());
            if (!found.isEmpty()) {
                throw new ConflictError(
                        grid.station() + " " + day + " has " + found.size() + " problem(s) to fix first",
                        Details.of(
                                "station",
                                grid.station(),
                                "problems",
                                found.stream().map(Problem::toString).toList()));
            }
            if (body.who().isEmpty()) {
                throw new ConflictError("a log has to be approved by somebody");
            }
            logs.approve(grid.station(), day, body.who(), signedAt(body));
            Audit.tryRecord(
                    uow.entityManager(),
                    "log.approved",
                    caller.name(),
                    "log",
                    grid.station() + " " + day,
                    field("signed_by", body.who()));
            return logOut(uow, grid.station(), day, false);
        });
    }

    @PostMapping("/{station}/{day}/release")
    @Requires(Role.TRAFFIC)
    public LogOut release(
            @PathVariable String station, @PathVariable LocalDate day, @Valid @RequestBody SignIn body, Caller caller) {
        return work.apply(uow -> {
            LogRepository logs = logs(uow);
            ClockGrid grid = Houses.gridFor(uow, station, Optional.of(day));
            if (logs.status(grid.station(), day).state() != LogState.APPROVED) {
                throw new ConflictError(
                        grid.station() + " " + day + " has not been approved",
                        Details.of("station", grid.station(), "day", day.toString()));
            }
            LogStatus status = logs.release(grid.station(), day, signedAt(body));
            Audit.tryRecord(
                    uow.entityManager(),
                    "log.released",
                    caller.name(),
                    "log",
                    grid.station() + " " + day,
                    field("revision", status.revision()));
            return logOut(uow, grid.station(), day, false);
        });
    }

    @PostMapping("/{station}/{day}/revise")
    @Requires(Role.MANAGER)
    public LogOut revise(@PathVariable String station, @PathVariable LocalDate day, Caller caller) {
        return work.apply(uow -> {
            LogRepository logs = logs(uow);
            ClockGrid grid = Houses.gridFor(uow, station, Optional.of(day));
            LogStatus status = logs.status(grid.station(), day);
            if (!status.state().isOut()) {
                throw new ConflictError(
                        grid.station() + " " + day + " is " + status.state().value() + " and does not need a revision",
                        Details.of("station", grid.station()));
            }
            LogStatus revised = logs.revise(grid.station(), day);
            Audit.tryRecord(
                    uow.entityManager(),
                    "log.revised",
                    caller.name(),
                    "log",
                    grid.station() + " " + day,
                    field("revision", revised.revision()));
            return logOut(uow, grid.station(), day, false);
        });
    }

    @GetMapping("/{station}")
    @Requires(Role.READER)
    public List<Map<String, Object>> listLogs(
            @PathVariable String station,
            @Parameter(description = "First day of the span") @RequestParam LocalDate first,
            @Parameter(description = "Last day of the span") @RequestParam LocalDate last) {
        return work.apply(uow -> {
            if (last.isBefore(first)) {
                throw new LookupFailure(
                        "the span runs backwards", Details.of("first", first.toString(), "last", last.toString()));
            }
            return logs(uow).between(station, first, last).stream()
                    .map(LogsController::listed)
                    .toList();
        });
    }

    private static Map<String, Object> listed(LogStatus status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("day", status.day().toString());
        row.put("state", status.state().value());
        row.put("revision", status.revision());
        row.put("approved_by", status.approvedBy());
        return row;
    }

    private static LogOut logOut(UnitOfWork uow, String station, LocalDate day, boolean spots) {
        LogRepository logs = logs(uow);
        ClockGrid grid = Houses.gridFor(uow, station, Optional.of(day));
        LogStatus status = logs.status(grid.station(), day);
        DayLog built = DayLog.build(grid, logs.board(grid, day));
        List<LogLine> wanted = spots ? built.spots() : built.lines();
        return new LogOut(
                grid.station(),
                day,
                status.state(),
                status.revision(),
                status.approvedBy(),
                built.soldUnits(),
                built.openUnits(),
                wanted.stream().map(LogsController::lineOut).toList());
    }

    private static LogLineOut lineOut(LogLine entry) {
        return new LogLineOut(
                entry.at().twelveHour(),
                entry.hour(),
                entry.breakNumber(),
                entry.element().value(),
                entry.length().toString(),
                entry.order(),
                entry.line(),
                entry.advertiser(),
                entry.cart());
    }

    private static ProblemOut problemOut(Problem problem) {
        return new ProblemOut(problem.code(), problem.where(), problem.detail());
    }

    private static LocalDateTime signedAt(SignIn body) {
        return body.at() != null ? body.at() : LocalDateTime.now();
    }

    private static LogRepository logs(UnitOfWork uow) {
        return new LogRepository(uow.entityManager());
    }
}
