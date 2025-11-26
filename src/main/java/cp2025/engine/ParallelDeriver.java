package cp2025.engine;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import cp2025.engine.Datalog;
import cp2025.engine.Datalog.Atom;
import cp2025.engine.Datalog.Constant;
import cp2025.engine.Datalog.Element;
import cp2025.engine.Datalog.Predicate;
import cp2025.engine.Datalog.Program;
import cp2025.engine.Datalog.Rule;
import cp2025.engine.Datalog.Variable;

public class ParallelDeriver implements AbstractDeriver {

    private final int numWorkerThreads;

    public ParallelDeriver(int numWorkerThreads) {
        if (numWorkerThreads < 1) {
            throw new IllegalArgumentException("numWorkerThreads must be >= 1");
        }
        this.numWorkerThreads = numWorkerThreads;
    }

    @Override
    public Map<Atom, Boolean> derive(Program program, AbstractOracle oracle)
            throws InterruptedException {

        List<Atom> queries = program.queries();
        Map<Atom, Boolean> results = new LinkedHashMap<>();
        if (queries.isEmpty()) {
            return results;
        }

        // Wspólny cache: stwierdzenia już rozstrzygnięte (true/false).
        ConcurrentMap<Atom, Boolean> knownStatements = new ConcurrentHashMap<>();

        // Wspólny indeks reguł po predykacie w głowie.
        Map<Predicate, List<Rule>> rulesByPredicate = buildRulesIndex(program);

        // Wspólny rejestr „in progress” dla wszystkich wątków.
        ConcurrentMap<Atom, Set<InProgressContext>> inProgressRegistry =
                new ConcurrentHashMap<>();

        // Flaga globalnego przerwania (przerwanie derive()).
        AtomicBoolean globalCancel = new AtomicBoolean(false);

        int poolSize = Math.min(numWorkerThreads, queries.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        try {
            List<Future<Map.Entry<Atom, Boolean>>> futures = new ArrayList<>();

            for (Atom query : queries) {
                Callable<Map.Entry<Atom, Boolean>> task = () -> {
                    ParallelDeriverState state = new ParallelDeriverState(
                            program,
                            oracle,
                            rulesByPredicate,
                            knownStatements,
                            inProgressRegistry,
                            globalCancel
                    );
                    DerivationResult result = state.deriveStatement(query);
                    return new AbstractMap.SimpleEntry<>(query, result.derivable());
                };
                futures.add(executor.submit(task));
            }

            for (Future<Map.Entry<Atom, Boolean>> f : futures) {
                try {
                    Map.Entry<Atom, Boolean> entry = f.get();
                    results.put(entry.getKey(), entry.getValue());
                } catch (InterruptedException e) {
                    // Przerwanie wątku wywołującego derive() – globalne zakończenie.
                    globalCancel.set(true);
                    executor.shutdownNow();
                    throw e;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof InterruptedException ie) {
                        // Przerwanie z wątku roboczego traktujemy jako globalne.
                        globalCancel.set(true);
                        executor.shutdownNow();
                        throw ie;
                    }
                    throw new RuntimeException("Error in worker thread", cause);
                }
            }

            return results;
        } finally {
            // Upewniamy się, że wątki pomocnicze kończą pracę.
            executor.shutdownNow();
        }
    }

    private Map<Predicate, List<Rule>> buildRulesIndex(Program program) {
        Map<Predicate, List<Rule>> index = new HashMap<>();
        for (Rule rule : program.rules()) {
            Predicate headPred = rule.head().predicate();
            index.computeIfAbsent(headPred, k -> new ArrayList<>()).add(rule);
        }
        return Collections.unmodifiableMap(index);
    }

    /**
     * Wynik próby wyprowadzenia stwierdzenia: czy wyprowadzalne oraz
     * zbiór „failedStatements” w sensie opisu SimpleDeriver.
     */
    private record DerivationResult(boolean derivable, Set<Atom> failedStatements) {
    }

    /**
     * Kontekst jednego wątku aktualnie wyprowadzającego dane stwierdzenie.
     * Umożliwia lokalne anulowanie (bez kończenia całego derive()).
     */
    private static final class InProgressContext {
        final Atom statement;
        final Thread ownerThread;
        volatile boolean cancelled;

        InProgressContext(Atom statement) {
            this.statement = statement;
            this.ownerThread = Thread.currentThread();
            this.cancelled = false;
        }
    }

    /**
     * Stan lokalny wątku przetwarzającego jedno zapytanie,
     * z dostępem do współdzielonych struktur.
     */
    private static final class ParallelDeriverState {

        private final Program program;
        private final AbstractOracle oracle;
        private final Map<Predicate, List<Rule>> rulesByPredicate;
        private final ConcurrentMap<Atom, Boolean> knownStatements;
        private final ConcurrentMap<Atom, Set<InProgressContext>> inProgressRegistry;
        private final AtomicBoolean globalCancel;

        // Lokalne inProgress, jak w SimpleDeriver.
        private final Set<Atom> inProgressStatements = new HashSet<>();

        ParallelDeriverState(
                Program program,
                AbstractOracle oracle,
                Map<Predicate, List<Rule>> rulesByPredicate,
                ConcurrentMap<Atom, Boolean> knownStatements,
                ConcurrentMap<Atom, Set<InProgressContext>> inProgressRegistry,
                AtomicBoolean globalCancel) {
            this.program = program;
            this.oracle = oracle;
            this.rulesByPredicate = rulesByPredicate;
            this.knownStatements = knownStatements;
            this.inProgressRegistry = inProgressRegistry;
            this.globalCancel = globalCancel;
        }

        DerivationResult deriveStatement(Atom goal) throws InterruptedException {
            // Globalne przerwanie.
            checkGlobalInterrupt();

            // Cache: już wiemy.
            Boolean known = knownStatements.get(goal);
            if (known != null) {
                if (known) {
                    return new DerivationResult(true, Set.of());
                } else {
                    return new DerivationResult(false, Set.of(goal));
                }
            }

            // Wykrywanie cykli.
            if (inProgressStatements.contains(goal)) {
                return new DerivationResult(false, Set.of(goal));
            }

            // Wyrocznia?
            if (oracle.isCalculatable(goal.predicate())) {
                return deriveWithOracle(goal);
            }

            InProgressContext ctx = new InProgressContext(goal);
            registerInProgress(ctx);
            inProgressStatements.add(goal);

            try {
                DerivationResult result = deriveNewStatement(goal, ctx);

                if (result.derivable()) {
                    markTrueAndNotify(goal, ctx);
                } else if (inProgressStatements.isEmpty()) {
                    // Jak w SimpleDeriver: na szczycie rekurencji
                    // rozstrzygamy wszystkie failedStatements jako false.
                    for (Atom s : result.failedStatements()) {
                        Boolean prev = knownStatements.putIfAbsent(s, Boolean.FALSE);
                        if (prev == null) {
                            notifyOtherInProgress(s, null);
                        }
                    }
                }

                return result;
            } finally {
                inProgressStatements.remove(goal);
                unregisterInProgress(ctx);
            }
        }

        private DerivationResult deriveWithOracle(Atom goal) throws InterruptedException {
            try {
                checkGlobalInterrupt();
                boolean value = oracle.calculate(goal);
                knownStatements.put(goal, value);
                if (value) {
                    notifyOtherInProgress(goal, null);
                    return new DerivationResult(true, Set.of());
                } else {
                    return new DerivationResult(false, Set.of(goal));
                }
            } catch (InterruptedException e) {
                // Rozróżnienie: globalne czy lokalne?
                if (globalCancel.get()) {
                    // Globalne – przerywamy całe derive().
                    throw e;
                }
                // Lokalne przerwanie (np. inny wątek rozstrzygnął to stwierdzenie).
                Thread.interrupted(); // wyczyść flagę.
                Boolean known = knownStatements.get(goal);
                if (known != null) {
                    if (known) {
                        return new DerivationResult(true, Set.of());
                    } else {
                        return new DerivationResult(false, Set.of(goal));
                    }
                }
                // Konserwatywnie traktujemy jako „nie do wyprowadzenia bez inProgress”.
                return new DerivationResult(false, Set.of(goal));
            }
        }

        private DerivationResult deriveNewStatement(Atom goal, InProgressContext ctx)
                throws InterruptedException {

            List<Rule> rules = rulesByPredicate.get(goal.predicate());
            if (rules == null || rules.isEmpty()) {
                return new DerivationResult(false, Set.of(goal));
            }

            Set<Atom> failedStatements = new HashSet<>();

            for (Rule rule : rules) {
                // Lokalne anulowanie tego stwierdzenia.
                DerivationResult cancelled = checkLocalCancellation(goal, ctx);
                if (cancelled != null) {
                    return cancelled;
                }

                checkGlobalInterrupt();

                // Unifikacja głowy z celem (jak w SimpleDeriver).
                java.util.Optional<List<Atom>> partiallyAssignedBodyOpt =
                        Unifier.unify(rule, goal);
                if (partiallyAssignedBodyOpt.isEmpty()) {
                    continue;
                }

                List<Atom> partiallyAssignedBody = partiallyAssignedBodyOpt.get();
                List<Variable> variables = Datalog.getVariables(partiallyAssignedBody);

                if (variables.isEmpty()) {
                    DerivationResult bodyRes = deriveBody(partiallyAssignedBody, ctx);
                    if (bodyRes.derivable()) {
                        return new DerivationResult(true, Set.of());
                    }
                    failedStatements.addAll(bodyRes.failedStatements());
                    continue;
                }

                // Wszystkie podstawienia zmiennych w ciele.
                FunctionGenerator<Variable, Constant> iterator =
                        new FunctionGenerator<>(variables, program.constants());

                for (Map<Variable, Constant> assignment : iterator) {
                    DerivationResult cancelled2 = checkLocalCancellation(goal, ctx);
                    if (cancelled2 != null) {
                        return cancelled2;
                    }

                    checkGlobalInterrupt();

                    List<Atom> assignedBody =
                            Unifier.applyAssignment(partiallyAssignedBody, assignment);

                    DerivationResult bodyRes = deriveBody(assignedBody, ctx);
                    if (bodyRes.derivable()) {
                        return new DerivationResult(true, Set.of());
                    }
                    failedStatements.addAll(bodyRes.failedStatements());
                }
            }

            failedStatements.add(goal);
            return new DerivationResult(false, failedStatements);
        }

        private DerivationResult deriveBody(List<Atom> body, InProgressContext parentCtx)
                throws InterruptedException {

            Set<Atom> failedStatements = new HashSet<>();

            for (Atom statement : body) {
                // Jeśli nadrzędne stwierdzenie zostało lokalnie anulowane,
                // przerywamy jak na wyższym poziomie.
                DerivationResult cancelled = checkLocalCancellation(parentCtx.statement, parentCtx);
                if (cancelled != null) {
                    return cancelled;
                }

                checkGlobalInterrupt();

                DerivationResult result = deriveStatement(statement);
                if (!result.derivable()) {
                    failedStatements.addAll(result.failedStatements());
                    return new DerivationResult(false, failedStatements);
                }
            }

            return new DerivationResult(true, Set.of());
        }

        private void registerInProgress(InProgressContext ctx) {
            inProgressRegistry.compute(ctx.statement, (atom, set) -> {
                if (set == null) {
                    set = ConcurrentHashMap.newKeySet();
                }
                set.add(ctx);
                return set;
            });
        }

        private void unregisterInProgress(InProgressContext ctx) {
            inProgressRegistry.computeIfPresent(ctx.statement, (atom, set) -> {
                set.remove(ctx);
                return set.isEmpty() ? null : set;
            });
        }

        private void markTrueAndNotify(Atom statement, InProgressContext currentCtx) {
            Boolean prev = knownStatements.putIfAbsent(statement, Boolean.TRUE);
            if (prev == null) {
                notifyOtherInProgress(statement, currentCtx);
            }
        }

        /**
         * Ustawia flagę anulowania i wywołuje Thread.interrupt()
         * na wszystkich innych wątkach wyprowadzających to samo stwierdzenie.
         * Wątki odróżniają to od globalnego przerwania dzięki globalCancel.
         */
        private void notifyOtherInProgress(Atom statement, InProgressContext currentCtx) {
            Set<InProgressContext> contexts = inProgressRegistry.get(statement);
            if (contexts == null) {
                return;
            }
            for (InProgressContext ctx : contexts) {
                if (ctx != currentCtx) {
                    ctx.cancelled = true;
                    // Przerywamy potencjalne oracle.calculate().
                    ctx.ownerThread.interrupt();
                }
            }
        }

        /**
         * Sprawdza, czy to stwierdzenie zostało lokalnie anulowane przez inny wątek
         * i w razie czego korzysta z globalnego cache jakby wynik był wyprowadzony lokalnie.
         */
        private DerivationResult checkLocalCancellation(Atom statement, InProgressContext ctx) {
            if (!ctx.cancelled) {
                return null;
            }
            Boolean known = knownStatements.get(statement);
            if (known != null) {
                if (known) {
                    return new DerivationResult(true, Set.of());
                } else {
                    return new DerivationResult(false, Set.of(statement));
                }
            }
            // Konserwatywnie: niewyprowadzalne bez użycia inProgress.
            return new DerivationResult(false, Set.of(statement));
        }

        /**
         * Sprawdza globalne przerwanie (z zewnątrz derive()).
         * Jeśli globalCancel ustawione lub bieżący wątek ma przerwanie
         * i globalCancel już ustawione, rzuca InterruptedException.
         */
        private void checkGlobalInterrupt() throws InterruptedException {
            if (globalCancel.get()) {
                throw new InterruptedException("Derivation cancelled");
            }
            if (Thread.interrupted()) {
                // Jeśli ktoś nas przerwał, ale globalCancel jeszcze nie jest ustawione,
                // traktujemy to jako potencjalne lokalne przerwanie; globalne
                // rozpoznajemy dopiero gdy globalCancel zostanie ustawione.
                if (globalCancel.get()) {
                    throw new InterruptedException("Derivation cancelled");
                }
            }
        }
    }
}
