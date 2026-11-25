package dev.belis.trader.desk.proposal;

import dev.belis.trader.desk.config.DeskClock;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Every minute on weekdays around the US session: the break-even rule and the end-of-day cancel, on the order
 * executor's worker. The exact session (half days, holidays) comes from IBKR inside each job; this only avoids
 * asking IBKR at night.
 */
@ApplicationScoped
public class OrderJobs {

    private static final LocalTime FROM = LocalTime.of(9, 25);
    private static final LocalTime TO = LocalTime.of(16, 5);

    private final OrderExecutor executor;
    private final DeskClock clock;

    public OrderJobs(OrderExecutor executor, DeskClock clock) {
        this.executor = executor;
        this.clock = clock;
    }

    @Scheduled(every = "60s", delayed = "45s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void everyMinute() {
        ZonedDateTime now = clock.marketNow();
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY
                || now.toLocalTime().isBefore(FROM) || now.toLocalTime().isAfter(TO)) {
            return;
        }
        executor.submit("Break-even check", executor::breakEven).join();
        executor.submit("End-of-day cancel", executor::endOfDay).join();
    }
}
