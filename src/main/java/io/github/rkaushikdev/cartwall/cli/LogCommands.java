package io.github.rkaushikdev.cartwall.cli;

import io.github.rkaushikdev.cartwall.BroadcastWeek;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.CutLibrary;
import io.github.rkaushikdev.cartwall.logs.Approval;
import io.github.rkaushikdev.cartwall.logs.DayLog;
import io.github.rkaushikdev.cartwall.logs.LogStatus;
import io.github.rkaushikdev.cartwall.logs.Problem;
import io.github.rkaushikdev.cartwall.persistence.LogRepository;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.playout.AsRun;
import io.github.rkaushikdev.cartwall.playout.AsRunFile;
import io.github.rkaushikdev.cartwall.playout.LogExport;
import io.github.rkaushikdev.cartwall.reconcile.Matcher;
import io.github.rkaushikdev.cartwall.reconcile.Posting;
import io.github.rkaushikdev.cartwall.scheduling.OrderResult;
import io.github.rkaushikdev.cartwall.services.Houses;
import io.github.rkaushikdev.cartwall.services.LogBuild;
import io.github.rkaushikdev.cartwall.services.LogBuild.BuildReport;
import io.github.rkaushikdev.cartwall.services.LogBuild.Skipped;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * The log commands, which are the ones cron actually runs.
 *
 * <p>Build, export and post run unattended every night and their contract with cron is the exit
 * code. Check, approve and release are for the traffic director at a terminal when the service is
 * not answering.
 *
 * <p>The build takes a day rather than a week, because nobody in the building thinks in broadcast
 * weeks except this program, and asking cron to work out which Monday a week starts on is asking
 * for the one week a year when it gets it wrong.
 */
final class LogCommands {

    private LogCommands() {}

    /** The grid the station's log is laid out on, whatever station the spot was bought on. */
    private static ClockGrid grid(UnitOfWork uow, String station) {
        return Houses.gridFor(uow, station, Optional.empty());
    }

    private static DayLog log(UnitOfWork uow, ClockGrid grid, LocalDate day) {
        return DayLog.build(grid, new LogRepository(uow.entityManager()).board(grid, day));
    }

    /** Rebuild the broadcast week a day falls in, across the cluster. */
    @Command(
            name = "build",
            mixinStandardHelpOptions = true,
            description = "Rebuild the broadcast week that DAY falls in, across the cluster.")
    static final class Build implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(paramLabel = "DAY", converter = Days.class)
        private LocalDate day;

        @Override
        public Integer call() {
            BuildReport report;
            try (UnitOfWork uow = main.work()) {
                report = LogBuild.buildWeek(uow, Houses.houseFrom(uow, Optional.empty()), BroadcastWeek.of(day));
                uow.commit();
            }
            PrintWriter out = main.terminal().out();
            out.println(report.week());
            out.println("  placed   " + report.placed() + " of " + report.wanted());
            out.println("  written  " + report.written().size() + " logs");
            for (Skipped skipped : report.skipped()) {
                out.println("  left     " + skipped.call() + " " + skipped.day() + " (" + skipped.state() + ")");
            }
            for (OrderResult shortfall : report.shortfalls()) {
                out.println("  short    " + shortfall.order().number() + " " + shortfall.placed() + " of "
                        + shortfall.wanted());
            }
            return Main.OK;
        }
    }

    /** Write a station's log in the automation's format. */
    @Command(
            name = "build-export",
            mixinStandardHelpOptions = true,
            description = "Write STATION's log for DAY in the automation's format.")
    static final class BuildExport implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "STATION")
        private String station;

        @Parameters(index = "1", paramLabel = "DAY", converter = Days.class)
        private LocalDate day;

        @Option(names = "--into", description = "Directory to write into.")
        private Path into;

        @Override
        public Integer call() {
            ClockGrid grid;
            DayLog log;
            try (UnitOfWork uow = main.work()) {
                grid = grid(uow, station);
                log = log(uow, grid, day);
            }
            String text = LogExport.render(log);
            PrintWriter out = main.terminal().out();
            if (into == null) {
                out.print(text);
                return Main.OK;
            }
            Path path = into.resolve(LogExport.filename(grid.station(), day));
            try {
                Files.writeString(path, text, StandardCharsets.UTF_8);
            } catch (IOException unwritable) {
                throw new UncheckedIOException(unwritable);
            }
            out.println(text.lines().count() + " lines written to " + path);
            return Main.OK;
        }
    }

    /** Read a station's as-run for a day out of a file and post it. */
    @Command(
            name = "post-asrun",
            mixinStandardHelpOptions = true,
            description = "Read STATION's as-run for DAY out of FILE and post it.")
    static final class PostAsRun implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Spec
        private CommandSpec spec;

        @Parameters(index = "0", paramLabel = "STATION")
        private String station;

        @Parameters(index = "1", paramLabel = "DAY", converter = Days.class)
        private LocalDate day;

        @Parameters(index = "2", paramLabel = "FILE")
        private Path file;

        @Option(names = "--force", description = "Post against a day that has not aired.")
        private boolean force;

        @Override
        public Integer call() {
            if (!Files.exists(file)) {
                throw new ParameterException(
                        spec.commandLine(), "Invalid value for FILE: '" + file + "' does not exist.");
            }
            List<AsRun> rows;
            try {
                rows = AsRunFile.parse(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
            Posting report;
            try (UnitOfWork uow = main.work()) {
                ClockGrid grid = grid(uow, station);
                report = Posting.post(uow.entityManager(), Matcher.reconcile(log(uow, grid, day), rows), force);
                uow.commit();
            }
            PrintWriter out = main.terminal().out();
            out.println(report.station() + " " + report.day());
            out.println("  aired    " + report.aired());
            out.println("  missed   " + report.missed());
            out.println("  moved    " + report.moved());
            out.println("  unlogged " + report.unmatched());
            return Main.OK;
        }
    }

    /** Say whether a log can be signed, and what is wrong with it. */
    @Command(
            name = "check",
            mixinStandardHelpOptions = true,
            description = "Say whether STATION's log for DAY can be signed, and what is wrong.")
    static final class Check implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "STATION")
        private String station;

        @Parameters(index = "1", paramLabel = "DAY", converter = Days.class)
        private LocalDate day;

        @Override
        public Integer call() {
            ClockGrid grid;
            DayLog log;
            CutLibrary library;
            try (UnitOfWork uow = main.work()) {
                grid = grid(uow, station);
                log = log(uow, grid, day);
                library = uow.accounts().library();
            }
            PrintWriter out = main.terminal().out();
            List<Problem> found = Approval.problems(log, library);
            found.forEach(problem -> out.println("problem  " + problem));
            Approval.warnings(log, library).forEach(note -> out.println("warning  " + note));
            if (!found.isEmpty()) {
                main.terminal().err().println(grid.station() + " " + day + " is not ready");
                return Main.REFUSED;
            }
            out.println(grid.station() + " " + day + " is ready to sign");
            return Main.OK;
        }
    }

    /** Sign a station's log for a day. */
    @Command(name = "approve", mixinStandardHelpOptions = true, description = "Sign STATION's log for DAY.")
    static final class Approve implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "STATION")
        private String station;

        @Parameters(index = "1", paramLabel = "DAY", converter = Days.class)
        private LocalDate day;

        @Option(names = "--who", required = true, description = "Who is signing it.")
        private String who;

        @Override
        public Integer call() {
            LogStatus status;
            try (UnitOfWork uow = main.work()) {
                ClockGrid grid = grid(uow, station);
                List<Problem> found =
                        Approval.problems(log(uow, grid, day), uow.accounts().library());
                if (!found.isEmpty()) {
                    main.terminal().err().println(grid.station() + " " + day + " has " + found.size() + " problem(s)");
                    return Main.REFUSED;
                }
                status = new LogRepository(uow.entityManager()).approve(grid.station(), day, who, LocalDateTime.now());
                uow.commit();
            }
            main.terminal()
                    .out()
                    .println(status.station() + " " + status.day() + " approved by " + status.approvedBy());
            return Main.OK;
        }
    }

    /** Send a station's log for a day to the automation. */
    @Command(
            name = "release",
            mixinStandardHelpOptions = true,
            description = "Send STATION's log for DAY to the automation.")
    static final class Release implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "STATION")
        private String station;

        @Parameters(index = "1", paramLabel = "DAY", converter = Days.class)
        private LocalDate day;

        @Override
        public Integer call() {
            LogStatus status;
            try (UnitOfWork uow = main.work()) {
                ClockGrid grid = grid(uow, station);
                status = new LogRepository(uow.entityManager()).release(grid.station(), day, LocalDateTime.now());
                uow.commit();
            }
            main.terminal()
                    .out()
                    .println(status.station() + " " + status.day() + " released at revision " + status.revision());
            return Main.OK;
        }
    }
}
