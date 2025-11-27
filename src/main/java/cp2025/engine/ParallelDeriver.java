package cp2025.engine;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import cp2025.engine.Datalog.Atom;
import cp2025.engine.Datalog.Constant;
import cp2025.engine.Datalog.Predicate;
import cp2025.engine.Datalog.Program;
import cp2025.engine.Datalog.Rule;
import cp2025.engine.Datalog.Variable;

/**
 * Implementacja wielowątkowego silnika wyprowadzania dla języka Datalog.
 * Równoległość na poziomie zapytań (nie w obrębie pojedynczego zapytania).
 */
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
        if (queries.isEmpty()) {
            return new LinkedHashMap<>();
        }

        // POPRAWKA #1: JEDEN cache zamiast dwóch (knownTrue i knownFalse)
        ConcurrentHashMap<Atom, Boolean> knownStatements = new ConcurrentHashMap<>();

        // Wspólny rejestr kontekstów "in progress" dla wszystkich wątków.
        ConcurrentHashMap<Atom, Set<InProgressContext>> inProgressRegistry =
                new ConcurrentHashMap<>();

        // Flaga globalnego przerwania całego derive().
        AtomicBoolean globalCancelled = new AtomicBoolean(false);

        // Indeks reguł po predykacie w głowie (niezmienny).
        Map<Predicate, List<Rule>> rulesByPredicate = buildRulesIndex(program);

        // Tworzymy pulę wątków (nie więcej niż liczba zapytań).
        int poolSize = Math.min(numWorkerThreads, queries.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        try {
            List<Future<Map.Entry<Atom, Boolean>>> futures = new ArrayList<>();

            // Każde zapytanie przetwarzamy w osobnym zadaniu.
            for (Atom query : queries) {
                Callable<Map.Entry<Atom, Boolean>> task = () -> {
                    ParallelDeriverState state = new ParallelDeriverState(
                            program,
                            oracle,
                            rulesByPredicate,
                            knownStatements,
                            inProgressRegistry,
                            globalCancelled
                    );

                    // Wyprowadzamy zapytanie; zwracamy zbiór niewyprowadzalnych.
                    Set<Atom> nonDerivable = state.deriveStatement(query, new HashSet<>());
                    boolean result = nonDerivable.isEmpty();

                    // Zapisujemy wynik w cache.
                    if (result) {
                        Boolean prev = knownStatements.putIfAbsent(query, Boolean.TRUE);
                        if (prev == null) {
                            state.notifyOtherInProgress(query, null);
                        }
                    } else {
                        // Na szczycie rekurencji – rozstrzygamy wszystkie failedStatements.
                        for (Atom a : nonDerivable) {
                            Boolean prev = knownStatements.putIfAbsent(a, Boolean.FALSE);
                            if (prev == null) {
                                state.notifyOtherInProgress(a, null);
                            }
                        }
                    }

                    return new AbstractMap.SimpleEntry<>(query, result);
                };

                futures.add(executor.submit(task));
            }

            // Zbieramy wyniki od wszystkich wątków.
            Map<Atom, Boolean> results = new LinkedHashMap<>();
            for (Future<Map.Entry<Atom, Boolean>> future : futures) {
                try {
                    Map.Entry<Atom, Boolean> entry = future.get();
                    results.put(entry.getKey(), entry.getValue());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof InterruptedException) {
                        throw (InterruptedException) cause;
                    }
                    throw new RuntimeException("Error in worker thread", cause);
                }
            }

            return results;

        } catch (InterruptedException e) {
            // Globalne przerwanie – sygnalizujemy wszystkim wątkom.
            globalCancelled.set(true);
            throw e;
        } finally {
            // Zawsze zamykamy executor – bez wycieków zasobów.
            executor.shutdownNow();
        }
    }

    /**
     * Buduje indeks reguł po predykacie w głowie dla szybkiego dostępu.
     */
    private Map<Predicate, List<Rule>> buildRulesIndex(Program program) {
        Map<Predicate, List<Rule>> index = new HashMap<>();
        for (Rule rule : program.rules()) {
            Predicate headPred = rule.head().predicate();
            index.computeIfAbsent(headPred, k -> new ArrayList<>()).add(rule);
        }
        return Collections.unmodifiableMap(index);
    }

    /**
     * POPRAWKA #2: Thread w InProgressContext
     * Kontekst wyprowadzania jednego stwierdzenia w konkretnym wątku.
     * Umożliwia lokalne anulowanie przez inne wątki.
     */
    private static final class InProgressContext {
        final Atom statement;
        final Thread ownerThread;  // ← Dla Thread.interrupt()
        volatile boolean cancelled;

        InProgressContext(Atom statement) {
            this.statement = statement;
            this.ownerThread = Thread.currentThread();
            this.cancelled = false;
        }
    }

    /**
     * Stan lokalny jednego wątku z dostępem do wspólnych struktur.
     */
    private static class ParallelDeriverState {

        private final Program program;
        private final AbstractOracle oracle;
        private final Map<Predicate, List<Rule>> rulesByPredicate;

        // POPRAWKA #1: Wspólny cache wyników (JEDEN zamiast dwóch)
        private final ConcurrentHashMap<Atom, Boolean> knownStatements;

        // Wspólny rejestr kontekstów "in progress".
        private final ConcurrentHashMap<Atom, Set<InProgressContext>> inProgressRegistry;

        // Flaga globalnego przerwania.
        private final AtomicBoolean globalCancelled;

        ParallelDeriverState(
                Program program,
                AbstractOracle oracle,
                Map<Predicate, List<Rule>> rulesByPredicate,
                ConcurrentHashMap<Atom, Boolean> knownStatements,
                ConcurrentHashMap<Atom, Set<InProgressContext>> inProgressRegistry,
                AtomicBoolean globalCancelled) {
            this.program = program;
            this.oracle = oracle;
            this.rulesByPredicate = rulesByPredicate;
            this.knownStatements = knownStatements;
            this.inProgressRegistry = inProgressRegistry;
            this.globalCancelled = globalCancelled;
        }

        /**
         * Wyprowadza stwierdzenie, zwracając zbiór niewyprowadzalnych atomów
         * (jak w SimpleDeriver).
         *
         * @param statement stwierdzenie do wyprowadzenia
         * @param inProgress lokalne stwierdzenia aktualnie wyprowadzane (wykrywanie cykli)
         * @return pusty zbiór jeśli wyprowadzalne, w p.p. zbiór failedStatements
         */
        Set<Atom> deriveStatement(Atom statement, Set<Atom> inProgress)
                throws InterruptedException {

            // Sprawdź globalne przerwanie.
            checkGlobalInterrupt();

            // POPRAWKA #1: Sprawdź JEDEN cache atomowo
            Boolean known = knownStatements.get(statement);
            if (known != null) {
                if (known) {
                    return Collections.emptySet();
                } else {
                    Set<Atom> result = new HashSet<>();
                    result.add(statement);
                    return result;
                }
            }

            // Wykrywanie cykli lokalnych (w tym wątku).
            if (inProgress.contains(statement)) {
                Set<Atom> result = new HashSet<>();
                result.add(statement);
                return result;
            }

            // Wyrocznia – obsługa predykatów kalkulowalnych.
            if (oracle.isCalculatable(statement.predicate())) {
                return deriveWithOracle(statement);
            }

            // Rejestrujemy rozpoczęcie wyprowadzania.
            InProgressContext ctx = new InProgressContext(statement);
            registerInProgress(ctx);

            inProgress.add(statement);
            try {
                return deriveStatementInternal(statement, inProgress, ctx);
            } finally {
                inProgress.remove(statement);
                unregisterInProgress(ctx);
            }
        }

        /**
         * POPRAWKA #4: Prosta implementacja deriveWithOracle bez race conditions
         */
        private Set<Atom> deriveWithOracle(Atom statement)
                throws InterruptedException {

            // Sprawdź ponownie cache (inny wątek mógł już wyliczyć).
            Boolean cached = knownStatements.get(statement);
            if (cached != null) {
                if (cached) {
                    return Collections.emptySet();
                } else {
                    Set<Atom> result = new HashSet<>();
                    result.add(statement);
                    return result;
                }
            }

            // Wywołaj wyrocznię.
            boolean value;
            try {
                checkGlobalInterrupt();
                value = oracle.calculate(statement);
            } catch (InterruptedException e) {
                // Rozróżnienie: globalne czy lokalne przerwanie?
                if (globalCancelled.get()) {
                    // Globalne – przerywamy całe derive().
                    throw e;
                }

                // Lokalne przerwanie – inny wątek ustalił wynik.
                // Sprawdź cache i użyj tego wyniku.
                Boolean known = knownStatements.get(statement);
                if (known != null) {
                    if (known) {
                        return Collections.emptySet();
                    } else {
                        Set<Atom> result = new HashSet<>();
                        result.add(statement);
                        return result;
                    }
                }

                // Konserwatywnie: niewyprowadzalne bez użycia inProgress.
                Set<Atom> result = new HashSet<>();
                result.add(statement);
                return result;
            }

            // Zapisz wynik atomowo.
            Boolean prev = knownStatements.putIfAbsent(statement, value);
            if (prev == null) {
                // To my pierwszy raz zapisujemy – powiadom inne wątki.
                notifyOtherInProgress(statement, null);
            }

            if (value) {
                return Collections.emptySet();
            } else {
                Set<Atom> result = new HashSet<>();
                result.add(statement);
                return result;
            }
        }

        /**
         * POPRAWKA #3: Używa Unifier.unify() przed generowaniem podstawień
         */
        private Set<Atom> deriveStatementInternal(
                Atom statement,
                Set<Atom> inProgress,
                InProgressContext ctx)
                throws InterruptedException {

            // Sprawdź czy inny wątek anulował wyprowadzanie tego stwierdzenia.
            Set<Atom> cancelledResult = checkLocalCancellation(statement, ctx);
            if (cancelledResult != null) {
                return cancelledResult;
            }

            Predicate predicate = statement.predicate();
            List<Rule> rules = rulesByPredicate.get(predicate);
            if (rules == null || rules.isEmpty()) {
                // Brak reguł – niewyprowadzalne.
                Set<Atom> result = new HashSet<>();
                result.add(statement);
                return result;
            }

            Set<Atom> allFailedStatements = new HashSet<>();

            for (Rule rule : rules) {
                // Sprawdź lokalne anulowanie przed każdą regułą.
                cancelledResult = checkLocalCancellation(statement, ctx);
                if (cancelledResult != null) {
                    return cancelledResult;
                }
                checkGlobalInterrupt();

                // Sprawdź cache ponownie (inny wątek mógł rozstrzygnąć).
                Boolean known = knownStatements.get(statement);
                if (known != null) {
                    if (known) {
                        return Collections.emptySet();
                    } else {
                        Set<Atom> result = new HashSet<>();
                        result.add(statement);
                        return result;
                    }
                }

                // POPRAWKA #3: Unifikacja NAJPIERW – dopasuj głowę reguły do celu
                Optional<List<Atom>> partiallyAssignedBodyOpt =
                        Unifier.unify(rule, statement);

                if (partiallyAssignedBodyOpt.isEmpty()) {
                    // Głowa nie pasuje do celu – próbuj następną regułę.
                    continue;
                }

                List<Atom> partiallyAssignedBody = partiallyAssignedBodyOpt.get();

                // Zbierz zmienne, które pozostały nieprzypisane po unifikacji.
                List<Variable> remainingVariables =
                        Datalog.getVariables(partiallyAssignedBody);

                if (remainingVariables.isEmpty()) {
                    // Brak zmiennych – ciało już w pełni podstawione.
                    Set<Atom> bodyNonDerivable =
                            deriveBody(partiallyAssignedBody, inProgress, ctx);
                    if (bodyNonDerivable.isEmpty()) {
                        // Wyprowadziliśmy ciało – statement jest wyprowadzalne.
                        markTrueAndNotify(statement, ctx);
                        return Collections.emptySet();
                    }
                    allFailedStatements.addAll(bodyNonDerivable);
                    continue;
                }

                // Iteruj po wszystkich podstawieniach dla POZOSTAŁYCH zmiennych.
                FunctionGenerator funcGen =
                        new FunctionGenerator(remainingVariables, program.constants());

                for (Object assignmentObj : funcGen) {
                    // Sprawdź anulowanie w każdej iteracji.
                    cancelledResult = checkLocalCancellation(statement, ctx);
                    if (cancelledResult != null) {
                        return cancelledResult;
                    }
                    checkGlobalInterrupt();

                    // Sprawdź cache ponownie.
                    known = knownStatements.get(statement);
                    if (known != null) {
                        if (known) {
                            return Collections.emptySet();
                        } else {
                            Set<Atom> result = new HashSet<>();
                            result.add(statement);
                            return result;
                        }
                    }

                    @SuppressWarnings("unchecked")
                    Map<Variable, Constant> assignment =
                            (Map<Variable, Constant>) assignmentObj;

                    // Podstaw pozostałe zmienne w ciele.
                    List<Atom> fullyAssignedBody =
                            Unifier.applyAssignment(partiallyAssignedBody, assignment);

                    Set<Atom> bodyNonDerivable =
                            deriveBody(fullyAssignedBody, inProgress, ctx);
                    if (bodyNonDerivable.isEmpty()) {
                        // Wyprowadziliśmy ciało – statement jest wyprowadzalne.
                        markTrueAndNotify(statement, ctx);
                        return Collections.emptySet();
                    }

                    allFailedStatements.addAll(bodyNonDerivable);
                }
            }

            // Żadna reguła nie pozwoliła wyprowadzić – zwracamy failed.
            allFailedStatements.add(statement);
            return allFailedStatements;
        }

        /**
         * Wyprowadza ciało reguły (listę atomów).
         */
        private Set<Atom> deriveBody(
                List<Atom> body,
                Set<Atom> inProgress,
                InProgressContext parentCtx)
                throws InterruptedException {

            Set<Atom> allNonDerivable = new HashSet<>();

            for (Atom atom : body) {
                // Sprawdź czy nadrzędne wyprowadzanie zostało anulowane.
                Set<Atom> cancelled = checkLocalCancellation(parentCtx.statement, parentCtx);
                if (cancelled != null) {
                    return cancelled;
                }
                checkGlobalInterrupt();

                Set<Atom> atomNonDerivable = deriveStatement(atom, inProgress);
                if (!atomNonDerivable.isEmpty()) {
                    // Ta przesłanka jest niewyprowadzalna – ciało niewyprowadzalne.
                    allNonDerivable.addAll(atomNonDerivable);
                    return allNonDerivable;
                }
            }

            // Wszystkie przesłanki wyprowadzalne – ciało wyprowadzalne.
            return Collections.emptySet();
        }

        /**
         * Rejestruje rozpoczęcie wyprowadzania danego stwierdzenia.
         */
        private void registerInProgress(InProgressContext ctx) {
            inProgressRegistry.compute(ctx.statement, (atom, set) -> {
                if (set == null) {
                    set = ConcurrentHashMap.newKeySet();
                }
                set.add(ctx);
                return set;
            });
        }

        /**
         * Usuwa kontekst z rejestru po zakończeniu wyprowadzania.
         */
        private void unregisterInProgress(InProgressContext ctx) {
            inProgressRegistry.computeIfPresent(ctx.statement, (atom, set) -> {
                set.remove(ctx);
                return set.isEmpty() ? null : set;
            });
        }

        /**
         * Sprawdza globalne przerwanie całego derive().
         */
        private void checkGlobalInterrupt() throws InterruptedException {
            if (globalCancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Derivation interrupted");
            }
        }

        /**
         * Sprawdza czy lokalne wyprowadzanie zostało anulowane przez inny wątek.
         * Jeśli tak, używa wyniku z globalnego cache.
         */
        private Set<Atom> checkLocalCancellation(Atom statement, InProgressContext ctx) {
            if (!ctx.cancelled) {
                return null;
            }

            // POPRAWKA #1: Anulowane – sprawdź JEDEN cache
            Boolean known = knownStatements.get(statement);
            if (known != null) {
                if (known) {
                    return Collections.emptySet();
                } else {
                    Set<Atom> result = new HashSet<>();
                    result.add(statement);
                    return result;
                }
            }

            // Konserwatywnie: niewyprowadzalne bez użycia inProgress.
            Set<Atom> result = new HashSet<>();
            result.add(statement);
            return result;
        }

        /**
         * Zaznacza stwierdzenie jako wyprowadzalne i powiadamia inne wątki.
         */
        private void markTrueAndNotify(Atom statement, InProgressContext currentCtx) {
            Boolean prev = knownStatements.putIfAbsent(statement, Boolean.TRUE);
            if (prev == null) {
                // To my pierwszy raz zapisujemy – powiadom inne wątki.
                notifyOtherInProgress(statement, currentCtx);
            }
        }

        /**
         * POPRAWKA #2: Powiadamia wszystkie inne wątki z interrupt()
         * Ustawia flagę anulowania i przerywa wątek (dla oracle.calculate()).
         */
        void notifyOtherInProgress(Atom statement, InProgressContext currentCtx) {
            Set<InProgressContext> contexts = inProgressRegistry.get(statement);
            if (contexts == null) {
                return;
            }

            for (InProgressContext ctx : contexts) {
                if (ctx != currentCtx) {
                    ctx.cancelled = true;
                    // KRYTYCZNE: przerwij wątek, żeby zakończyć oracle.calculate().
                    ctx.ownerThread.interrupt();
                }
            }
        }
    }
}
