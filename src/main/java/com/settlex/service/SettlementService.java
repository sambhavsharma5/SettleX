package com.settlex.service;

import com.settlex.config.SettleXProperties;
import com.settlex.dto.BenchmarkResult;
import com.settlex.dto.EngineBenchmark;
import com.settlex.dto.OptimizationStrategy;
import com.settlex.dto.Settlement;
import com.settlex.dto.SettlementPlan;
import com.settlex.dto.SettlementRequest;
import com.settlex.dto.Transaction;
import com.settlex.engine.GreedyPriorityQueueEngine;
import com.settlex.engine.OptimalSubsetPartitionEngine;
import com.settlex.engine.SettlementEngine;
import com.settlex.engine.support.BitmaskPartitionSolver;
import com.settlex.engine.support.LedgerNormalizer;
import com.settlex.engine.support.SettlementProblem;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Application service: routes a request to the requested engine and orchestrates the
 * head-to-head benchmark.
 *
 * <p>Holds no algorithmic state of its own — the work belongs to the engines. Its
 * responsibilities are strategy dispatch, validation of semantic (non-bean-validation)
 * preconditions, and the concurrency choreography behind {@code /benchmark}.</p>
 */
@Service
public class SettlementService {

    private final GreedyPriorityQueueEngine greedyEngine;
    private final OptimalSubsetPartitionEngine optimalEngine;
    private final SettleXProperties properties;
    private final Executor benchmarkExecutor;

    public SettlementService(GreedyPriorityQueueEngine greedyEngine,
                             OptimalSubsetPartitionEngine optimalEngine,
                             SettleXProperties properties,
                             @Qualifier("benchmarkExecutor") Executor benchmarkExecutor) {
        this.greedyEngine = greedyEngine;
        this.optimalEngine = optimalEngine;
        this.properties = properties;
        this.benchmarkExecutor = benchmarkExecutor;
    }

    /**
     * Runs the requested algorithm over the supplied ledger.
     *
     * <p>Dispatch is an exhaustive {@code switch} over the strategy rather than a map lookup,
     * so adding a strategy becomes a compile-time obligation instead of a runtime surprise.
     * The non-exhaustive {@code default} branch exists only to keep the compiler honest and
     * throws rather than silently degrading.</p>
     */
    public SettlementPlan optimize(SettlementRequest request) {
        List<Transaction> transactions = requireUsableTransactions(request.transactions());
        OptimizationStrategy strategy = request.strategy() == null
                ? OptimizationStrategy.GREEDY
                : request.strategy();

        SettlementEngine engine = switch (strategy) {
            case GREEDY -> greedyEngine;
            case OPTIMAL_SUBSET -> optimalEngine;
        };

        return engine.minimize(transactions, strategy);
    }

    /**
     * Runs both engines <em>concurrently</em> over the same ledger and compares them.
     *
     * <p>Concurrency is real, not merely interleaved: the two {@code CompletableFuture}s are
     * dispatched to a dedicated pool so the engines are genuinely in flight at the same time.
     * Per-engine {@code executionTimeNs} is measured inside each engine and excludes
     * scheduling jitter, which is the only number worth comparing.</p>
     *
     * <p>Both engines are deterministic and side-effect free, so running them in parallel
     * cannot change either result.</p>
     */
    public BenchmarkResult benchmark(SettlementRequest request) {
        List<Transaction> transactions = requireUsableTransactions(request.transactions());

        // Validate once up front so a bad ledger fails fast and identically for both engines.
        SettlementProblem problem = LedgerNormalizer.normalize(transactions);

        CompletableFuture<SettlementPlan> greedyFuture =
                CompletableFuture.supplyAsync(
                        () -> greedyEngine.minimize(transactions, OptimizationStrategy.GREEDY),
                        benchmarkExecutor);
        CompletableFuture<SettlementPlan> optimalFuture =
                CompletableFuture.supplyAsync(
                        () -> optimalEngine.minimize(transactions, OptimizationStrategy.OPTIMAL_SUBSET),
                        benchmarkExecutor);

        SettlementPlan greedyPlan;
        SettlementPlan optimalPlan;
        try {
            long timeoutMs = properties.benchmark().timeoutMs();
            greedyPlan = greedyFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            optimalPlan = optimalFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            greedyFuture.cancel(true);
            optimalFuture.cancel(true);
            throw new IllegalStateException(
                    "Benchmark exceeded the configured timeout of "
                            + properties.benchmark().timeoutMs() + "ms", timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark was interrupted", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Benchmark engine failed", cause);
        }

        EngineBenchmark greedy = toBenchmark(greedyPlan);
        EngineBenchmark optimal = toBenchmark(optimalPlan);

        long greedyNanos = greedy.executionTimeNs();
        long optimalNanos = optimal.executionTimeNs();
        boolean greedyFaster = greedyNanos <= optimalNanos;
        long slower = Math.max(greedyNanos, optimalNanos);
        long faster = Math.max(1L, Math.min(greedyNanos, optimalNanos));
        double speedup = (double) slower / (double) faster;

        int transactionDelta = greedy.optimizedTransactionCount()
                - optimal.optimizedTransactionCount();

        return new BenchmarkResult(
                problem.netBalances().size(),
                problem.initialTransactionCount(),
                problem.creditors().size(),
                problem.debtors().size(),
                problem.settledParticipantCount(),
                greedy,
                optimal,
                transactionDelta,
                com.settlex.engine.support.MoneyMath.fromMinorUnits(
                        greedy.totalCashFlowAfter().movePointRight(2).longValueExact()
                                - optimal.totalCashFlowAfter().movePointRight(2).longValueExact()),
                greedyFaster ? greedy.algorithmUsed() : optimal.algorithmUsed(),
                round2(speedup),
                transactionDelta > 0,
                buildNotes(transactionDelta, optimalPlan, problem.activeParticipantCount(),
                        properties.engine().exactSolverMaxParticipants()));
    }

    private static String buildNotes(int transactionDelta, SettlementPlan optimalPlan,
                                     int activeCount, int exactLimit) {
        String limitNote;
        if (optimalPlan.isOptimalFallback() || activeCount > exactLimit) {
            limitNote = "Exact solver skipped: " + activeCount + " active participants exceeds the "
                    + "bitmask limit, so both engines ran the same heuristic and results are identical.";
        } else if (optimalPlan.isMathematicallyOptimal()) {
            limitNote = "Exact solver proved the minimum is " + optimalPlan.optimizedTransactionCount()
                    + " transfer(s) for " + activeCount + " active participants across "
                    + optimalPlan.independentSubsetCount() + " independent zero-sum group(s).";
        } else {
            limitNote = "Exact solver result was not provably minimal; treat the transfer count as a bound.";
        }

        if (transactionDelta > 0) {
            return "Bitmask DP eliminated " + transactionDelta
                    + " transfer(s) that greedy could not see. " + limitNote;
        }
        if (transactionDelta == 0) {
            return "Both engines produced an identical transfer count on this ledger. " + limitNote;
        }
        return "Greedy happened to beat the exact solver's output, which should be impossible; "
                + "please report this input. " + limitNote;
    }

    private static EngineBenchmark toBenchmark(SettlementPlan plan) {
        return new EngineBenchmark(
                plan.algorithmUsed(),
                plan.optimizedTransactionCount(),
                plan.executionTimeNs(),
                plan.totalCashFlowAfter(),
                plan.isMathematicallyOptimal(),
                plan.isOptimalFallback(),
                plan.independentSubsetCount(),
                List.copyOf(plan.settlements()));
    }

    private static List<Transaction> requireUsableTransactions(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            throw new IllegalArgumentException("At least one transaction is required");
        }
        return List.copyOf(transactions);
    }

    private static double round2(double value) {
        return java.math.BigDecimal.valueOf(value)
                .setScale(2, java.math.RoundingMode.HALF_EVEN)
                .doubleValue();
    }

    /** Exposed so the dashboard and tests can report the configured exact-solver ceiling. */
    public int exactSolverMaxParticipants() {
        return properties.engine().exactSolverMaxParticipants();
    }

    /** Exposed for diagnostics: whether the exact solver would attempt this participant count. */
    public boolean isExactSolverInRange(int participantCount) {
        return BitmaskPartitionSolver.isWithinExactLimit(participantCount,
                properties.engine().exactSolverMaxParticipants());
    }
}