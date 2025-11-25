package cp2025.engine;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
        if (numWorkerThreads <= 0) {
            throw new IllegalArgumentException("numWorkerThreads must be >= 1");
        }
        this.numWorkerThreads = numWorkerThreads;
    }

    @Override
    public Map<Atom, Boolean> derive(Program program, AbstractOracle oracle)
            throws InterruptedException {

        // Wspólne cache dla wszystkich zapytań i wątków.
        ConcurrentHashMap<Atom, Boolean> knownTrue = new ConcurrentHashMap<>();
        ConcurrentHashMap<Atom, Boolean> knownFalse = new ConcurrentHashMap<>();

        // Wspólny rejestr "in progress" dla wszystkich wątków.
        ConcurrentHashMap<Atom, Set<InProgressContext>> inProgressRegistry =
                new ConcurrentHashMap<>();

        // Niezmienny indeks reguł po predykacie w głowie.
        Map<Predicate, List<Rule>> rulesByPredicate = buildRulesIndex(program);

        int queryCount = program.queries().size();
        int poolSize = Math.min(numWorkerThreads, Math.max(1, queryCount));

        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        List<Future<Map.Entry<Atom, Boolean>>> futures = new ArrayList<>();

        try {
            for (Atom query : program.queries()) {
                Callable<Map.Entry<Atom, Boolean>> task = () -> {
                    try {
                        ParallelDeriverState state = new ParallelDeriverState(
                                program,
                                oracle,
                                rulesByPredicate,
                                knownTrue,
                                knownFalse,
                                inProgressRegistry
                        );

                        Set<Atom> nonDerivable = state.deriveStatement(query, new HashSet<>());
                        boolean result = nonDerivable.isEmpty();

                        if (result) {
                            // Zapytanie jest wyprowadzalne.
                            Boolean prev = knownTrue.putIfAbsent(query, Boolean.TRUE);
                            if (prev == null) {
                                // Pierwsze ustalenie tego stwierdzenia – powiadom inne wątki.
                                state.notifyOtherInProgress(query, null);
                            }
                        } else {
                            // Zapytanie niewyprowadzalne; oznacz wszystkie zbiorem z korzenia.
                            for (Atom a : nonDerivable) {
                                Boolean prev = knownFalse.putIfAbsent(a, Boolean.FALSE);
                                if (prev == null) {
                                    state.notifyOtherInProgress(a, null);
                                }
                            }
                        }

                        return new AbstractMap.SimpleEntry<>(query, result);
                    } catch (InterruptedException e) {
                        // Zachowaj status przerwania i przekaż dalej.
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                };

                futures.add(executor.submit(task));
            }

            Map<Atom, Boolean> results = new LinkedHashMap<>();
            for (Future<Map.Entry<Atom, Boolean>> f : futures) {
                try {
                    Map.Entry<Atom, Boolean> entry = f.get();
                    results.put(entry.getKey(), entry.getValue());
                } catch (ExecutionException e) {
                    throw new RuntimeException("Error in worker thread", e.getCause());
                }
            }

            return results;

        } finally {
            // Upewnij się, że wątki pomocnicze zakończą pracę; brak wycieków.
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
     * Kontekst pojedynczego wyprowadzania konkretnego stwierdzenia.
     * Może zostać oznaczony jako anulowany przez inny wątek.
     */
    private static final class InProgressContext {
        final Atom statement;
        volatile boolean cancelled;

        InProgressContext(Atom statement) {
            this.statement = statement;
            this.cancelled = false;
        }
    }

    private static class ParallelDeriverState {

        private final Program program;
        private final AbstractOracle oracle;
        private final Map<Predicate, List<Rule>> rulesByPredicate;

        private final ConcurrentHashMap<Atom, Boolean> knownTrue;
        private final ConcurrentHashMap<Atom, Boolean> knownFalse;

        // Wspólny rejestr dla wszystkich wątków wywołujących derive().
        private final ConcurrentHashMap<Atom, Set<InProgressContext>> inProgressRegistry;

        ParallelDeriverState(Program program,
                             AbstractOracle oracle,
                             Map<Predicate, List<Rule>> rulesByPredicate,
                             ConcurrentHashMap<Atom, Boolean> knownTrue,
                             ConcurrentHashMap<Atom, Boolean> knownFalse,
                             ConcurrentHashMap<Atom, Set<InProgressContext>> inProgressRegistry) {
            this.program = program;
            this.oracle = oracle;
            this.rulesByPredicate = rulesByPredicate;
            this.knownTrue = knownTrue;
            this.knownFalse = knownFalse;
            this.inProgressRegistry = inProgressRegistry;
        }

        Set<Atom> deriveStatement(Atom statement, Set<Atom> inProgress)
                throws InterruptedException {

            // Szybkie zakończenie przy globalnym przerwaniu.
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            // Globalny cache: stwierdzenie już wyprowadzalne.
            Boolean cachedTrue = knownTrue.get(statement);
            if (cachedTrue != null && cachedTrue) {
                return Collections.emptySet();
            }
            // Globalny cache: stwierdzenie już rozpoznane jako niewyprowadzalne.
            Boolean cachedFalse = knownFalse.get(statement);
            if (cachedFalse != null && !cachedFalse) {
                Set<Atom> res = new HashSet<>();
                res.add(statement);
                return res;
            }

            // Lokalne wykrywanie cykli.
            if (inProgress.contains(statement)) {
                Set<Atom> res = new HashSet<>();
                res.add(statement);
                return res;
            }

            // Rejestrujemy ten atom jako "in progress" w tym wątku.
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
         * Właściwa logika wyprowadzania jednego stwierdzenia, z obsługą lokalnego anulowania.
         */
        private Set<Atom> deriveStatementInternal(Atom statement,
                                                  Set<Atom> inProgress,
                                                  InProgressContext ctx)
                throws InterruptedException {

            // Czy inne wątki anulowały wyprowadzanie tego stwierdzenia?
            Set<Atom> cancelledResult = checkLocalCancellation(statement, ctx);
            if (cancelledResult != null) {
                return cancelledResult;
            }

            Predicate predicate = statement.predicate();

            // Obsługa wyroczni – potencjalnie kosztowna.
            if (oracle.isCalculatable(predicate)) {
                // Ponowna kontrola lokalnego anulowania.
                cancelledResult = checkLocalCancellation(statement, ctx);
                if (cancelledResult != null) {
                    return cancelledResult;
                }
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }

                boolean value = oracle.calculate(statement);
                if (value) {
                    markTrueAndNotify(statement, ctx);
                    return Collections.emptySet();
                } else {
                    Set<Atom> res = new HashSet<>();
                    res.add(statement);
                    return res;
                }
            }

            List<Rule> rules = rulesByPredicate.get(predicate);
            if (rules != null) {
                for (Rule rule : rules) {
                    cancelledResult = checkLocalCancellation(statement, ctx);
                    if (cancelledResult != null) {
                        return cancelledResult;
                    }
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }

                    // Ponowna kontrola cache – inny wątek mógł to już rozwiązać.
                    Boolean cachedTrue = knownTrue.get(statement);
                    if (cachedTrue != null && cachedTrue) {
                        return Collections.emptySet();
                    }
                    Boolean cachedFalse = knownFalse.get(statement);
                    if (cachedFalse != null && !cachedFalse) {
                        Set<Atom> res = new HashSet<>();
                        res.add(statement);
                        return res;
                    }

                    List<Variable> variablesInRule = collectVariables(rule);

                    if (variablesInRule.isEmpty()) {
                        // Reguła bez zmiennych – sprawdzamy prostą równość głowy.
                        if (rule.head().equals(statement)) {
                            Set<Atom> bodyNonDerivable =
                                    deriveBody(rule.body(), inProgress);
                            if (bodyNonDerivable.isEmpty()) {
                                markTrueAndNotify(statement, ctx);
                                return Collections.emptySet();
                            }
                        }
                    } else {
                        // Wszystkie przypisania zmiennych w regule.
                        FunctionGenerator funcGen =
                                new FunctionGenerator(variablesInRule, program.constants());

                        for (Object assignmentObj : funcGen) {
                            cancelledResult = checkLocalCancellation(statement, ctx);
                            if (cancelledResult != null) {
                                return cancelledResult;
                            }
                            if (Thread.interrupted()) {
                                throw new InterruptedException();
                            }

                            // Ponowna kontrola cache wewnątrz potencjalnie dużej pętli.
                            cachedTrue = knownTrue.get(statement);
                            if (cachedTrue != null && cachedTrue) {
                                return Collections.emptySet();
                            }
                            cachedFalse = knownFalse.get(statement);
                            if (cachedFalse != null && !cachedFalse) {
                                Set<Atom> res = new HashSet<>();
                                res.add(statement);
                                return res;
                            }

                            @SuppressWarnings("unchecked")
                            Map<Variable, Constant> assignment =
                                    (Map<Variable, Constant>) assignmentObj;

                            Atom instantiatedHead = applyAssignment(rule.head(), assignment);
                            if (!instantiatedHead.equals(statement)) {
                                continue;
                            }

                            List<Atom> instantiatedBody = new ArrayList<>();
                            for (Atom bodyAtom : rule.body()) {
                                instantiatedBody.add(applyAssignment(bodyAtom, assignment));
                            }

                            Set<Atom> bodyNonDerivable =
                                    deriveBody(instantiatedBody, inProgress);
                            if (bodyNonDerivable.isEmpty()) {
                                markTrueAndNotify(statement, ctx);
                                return Collections.emptySet();
                            }
                        }
                    }
                }
            }

            // Nie udało się wyprowadzić tego stwierdzenia.
            Set<Atom> res = new HashSet<>();
            res.add(statement);
            return res;
        }

        Set<Atom> deriveBody(List<Atom> body, Set<Atom> inProgress)
                throws InterruptedException {

            Set<Atom> allNonDerivable = new HashSet<>();

            for (Atom atom : body) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }

                Set<Atom> atomNonDerivable = deriveStatement(atom, inProgress);
                if (!atomNonDerivable.isEmpty()) {
                    allNonDerivable.addAll(atomNonDerivable);
                    return allNonDerivable;
                }
            }

            return Collections.emptySet();
        }

        private List<Variable> collectVariables(Rule rule) {
            Set<Variable> vars = new HashSet<>();
            vars.addAll(Datalog.getVariables(Collections.singletonList(rule.head())));
            for (Atom bodyAtom : rule.body()) {
                vars.addAll(Datalog.getVariables(Collections.singletonList(bodyAtom)));
            }
            return new ArrayList<>(vars);
        }

        private Atom applyAssignment(Atom atom, Map<Variable, Constant> assignment) {
            List<Element> newElements = new ArrayList<>(atom.elements().size());
            for (Element elem : atom.elements()) {
                if (elem instanceof Variable var) {
                    Constant constVal = assignment.get(var);
                    if (constVal != null) {
                        newElements.add(constVal);
                    } else {
                        newElements.add(elem);
                    }
                } else {
                    newElements.add(elem);
                }
            }
            return new Atom(atom.predicate(), newElements);
        }

        /**
         * Rejestruje rozpoczęcie wyprowadzania danego stwierdzenia w tym wątku.
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
         * Jeśli inne wątki anulowały wyprowadzanie tego stwierdzenia, użyj
         * globalnego cache, aby natychmiast zwrócić wynik tak,
         * jakby został wyprowadzony lokalnie.
         */
        private Set<Atom> checkLocalCancellation(Atom statement, InProgressContext ctx) {
            if (!ctx.cancelled) {
                return null;
            }

            Boolean cachedTrue = knownTrue.get(statement);
            if (cachedTrue != null && cachedTrue) {
                return Collections.emptySet();
            }
            Boolean cachedFalse = knownFalse.get(statement);
            if (cachedFalse != null && !cachedFalse) {
                Set<Atom> res = new HashSet<>();
                res.add(statement);
                return res;
            }

            // Konserwatywnie traktujemy to jak niewyprowadzalne,
            // jeśli z jakiegoś powodu cache nie został jeszcze uzupełniony.
            Set<Atom> res = new HashSet<>();
            res.add(statement);
            return res;
        }

        /**
         * Zaznacza stwierdzenie jako wyprowadzalne i powiadamia inne wątki,
         * które mogą je aktualnie wyprowadzać.
         */
        private void markTrueAndNotify(Atom statement, InProgressContext currentCtx) {
            Boolean prev = knownTrue.putIfAbsent(statement, Boolean.TRUE);
            if (prev == null) {
                notifyOtherInProgress(statement, currentCtx);
            }
        }

        /**
         * Ustawia flagę anulowania dla wszystkich innych aktywnych kontekstów
         * wyprowadzających dane stwierdzenie.
         */
        void notifyOtherInProgress(Atom statement, InProgressContext currentCtx) {
            Set<InProgressContext> contexts = inProgressRegistry.get(statement);
            if (contexts == null) {
                return;
            }
            for (InProgressContext ctx : contexts) {
                if (ctx != currentCtx) {
                    ctx.cancelled = true;
                }
            }
        }
    }
}
