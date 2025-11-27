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
        if (queries.isEmpty()) {
            return new LinkedHashMap<>();
        }

        ConcurrentHashMap<Atom, Boolean> knownStatements = new ConcurrentHashMap<>();
        ConcurrentHashMap<Atom, Set<InProgressContext>> inProgressRegistry =
                new ConcurrentHashMap<>();
        AtomicBoolean globalCancelled = new AtomicBoolean(false);

        Map<Predicate, List<Rule>> rulesByPredicate = buildRulesIndex(program);

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
                            globalCancelled
                    );

                    Set<Atom> nonDerivable = state.deriveStatement(query, new HashSet<>());
                    boolean result = nonDerivable.isEmpty();

                    if (result) {
                        Boolean prev = knownStatements.putIfAbsent(query, Boolean.TRUE);
                        if (prev == null) {
                            state.notifyOtherInProgress(query, null);
                        }
                    } else {
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
            globalCancelled.set(true);
            throw e;
        } finally {
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

    private static class ParallelDeriverState {

        private final Program program;
        private final AbstractOracle oracle;
        private final Map<Predicate, List<Rule>> rulesByPredicate;
        private final ConcurrentHashMap<Atom, Boolean> knownStatements;
        private final ConcurrentHashMap<Atom, Set<InProgressContext>> inProgressRegistry;
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

        Set<Atom> deriveStatement(Atom statement, Set<Atom> inProgress)
                throws InterruptedException {

            checkGlobalInterrupt();

            Boolean known = knownStatements.get(statement);
            if (known != null) {
                return known ? Collections.emptySet() : Set.of(statement);
            }

            if (inProgress.contains(statement)) {
                return Set.of(statement);
            }

            if (oracle.isCalculatable(statement.predicate())) {
                return deriveWithOracle(statement);
            }

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

        private Set<Atom> deriveWithOracle(Atom statement)
                throws InterruptedException {

            Boolean cached = knownStatements.get(statement);
            if (cached != null) {
                return cached ? Collections.emptySet() : Set.of(statement);
            }

            final boolean[] calculatedValue = new boolean[1];
            final boolean[] wasCalculated = new boolean[1];
            final boolean[] wasInterrupted = new boolean[1];

            knownStatements.computeIfAbsent(statement, s -> {
                try {
                    calculatedValue[0] = oracle.calculate(s);
                    wasCalculated[0] = true;
                    return calculatedValue[0];
                } catch (InterruptedException e) {
                    wasInterrupted[0] = true;
                    return null;
                }
            });

            if (wasInterrupted[0]) {
                // Wyczyść flagę przerwania (może być lokalne)
                boolean wasReallyInterrupted = Thread.interrupted();

                // Sprawdź czy to globalne przerwanie
                if (globalCancelled.get()) {
                    if (wasReallyInterrupted) {
                        Thread.currentThread().interrupt();
                    }
                    throw new InterruptedException();
                }

                // Lokalne przerwanie – flaga już wyczyszczona, użyj cache
                Boolean known = knownStatements.get(statement);
                if (known != null) {
                    return known ? Collections.emptySet() : Set.of(statement);
                }

                return Set.of(statement);
            }

            if (wasCalculated[0]) {
                notifyOtherInProgress(statement, null);
            }

            Boolean finalResult = knownStatements.get(statement);
            return (finalResult != null && finalResult)
                    ? Collections.emptySet()
                    : Set.of(statement);
        }

        private Set<Atom> deriveStatementInternal(
                Atom statement,
                Set<Atom> inProgress,
                InProgressContext ctx)
                throws InterruptedException {

            Set<Atom> cancelledResult = checkLocalCancellation(statement, ctx);
            if (cancelledResult != null) {
                return cancelledResult;
            }

            Predicate predicate = statement.predicate();
            List<Rule> rules = rulesByPredicate.get(predicate);
            if (rules == null || rules.isEmpty()) {
                return Set.of(statement);
            }

            Set<Atom> allFailedStatements = new HashSet<>();

            for (Rule rule : rules) {
                cancelledResult = checkLocalCancellation(statement, ctx);
                if (cancelledResult != null) {
                    return cancelledResult;
                }
                checkGlobalInterrupt();

                Boolean known = knownStatements.get(statement);
                if (known != null) {
                    return known ? Collections.emptySet() : Set.of(statement);
                }

                Optional<List<Atom>> partiallyAssignedBodyOpt =
                        Unifier.unify(rule, statement);

                if (partiallyAssignedBodyOpt.isEmpty()) {
                    continue;
                }

                List<Atom> partiallyAssignedBody = partiallyAssignedBodyOpt.get();
                List<Variable> remainingVariables =
                        Datalog.getVariables(partiallyAssignedBody);

                if (remainingVariables.isEmpty()) {
                    Set<Atom> bodyNonDerivable =
                            deriveBody(partiallyAssignedBody, inProgress, ctx);
                    if (bodyNonDerivable.isEmpty()) {
                        markTrueAndNotify(statement, ctx);
                        return Collections.emptySet();
                    }
                    allFailedStatements.addAll(bodyNonDerivable);
                    continue;
                }

                FunctionGenerator funcGen =
                        new FunctionGenerator(remainingVariables, program.constants());

                for (Object assignmentObj : funcGen) {
                    cancelledResult = checkLocalCancellation(statement, ctx);
                    if (cancelledResult != null) {
                        return cancelledResult;
                    }
                    checkGlobalInterrupt();

                    known = knownStatements.get(statement);
                    if (known != null) {
                        return known ? Collections.emptySet() : Set.of(statement);
                    }

                    @SuppressWarnings("unchecked")
                    Map<Variable, Constant> assignment =
                            (Map<Variable, Constant>) assignmentObj;

                    List<Atom> fullyAssignedBody =
                            applyAssignment(partiallyAssignedBody, assignment);

                    Set<Atom> bodyNonDerivable =
                            deriveBody(fullyAssignedBody, inProgress, ctx);
                    if (bodyNonDerivable.isEmpty()) {
                        markTrueAndNotify(statement, ctx);
                        return Collections.emptySet();
                    }

                    allFailedStatements.addAll(bodyNonDerivable);
                }
            }

            allFailedStatements.add(statement);
            return allFailedStatements;
        }

        private Set<Atom> deriveBody(
                List<Atom> body,
                Set<Atom> inProgress,
                InProgressContext parentCtx)
                throws InterruptedException {

            Set<Atom> allNonDerivable = new HashSet<>();

            for (Atom atom : body) {
                Set<Atom> cancelled = checkLocalCancellation(parentCtx.statement, parentCtx);
                if (cancelled != null) {
                    return cancelled;
                }
                checkGlobalInterrupt();

                Set<Atom> atomNonDerivable = deriveStatement(atom, inProgress);
                if (!atomNonDerivable.isEmpty()) {
                    allNonDerivable.addAll(atomNonDerivable);
                    return allNonDerivable;
                }
            }

            return Collections.emptySet();
        }

        private List<Atom> applyAssignment(List<Atom> atoms, Map<Variable, Constant> assignment) {
            List<Atom> result = new ArrayList<>();
            for (Atom atom : atoms) {
                result.add(applyAssignmentToAtom(atom, assignment));
            }
            return result;
        }

        private Atom applyAssignmentToAtom(Atom atom, Map<Variable, Constant> assignment) {
            List<Element> newElements = new ArrayList<>();
            for (Element elem : atom.elements()) {
                if (elem instanceof Variable var) {
                    Constant constVal = assignment.get(var);
                    newElements.add(constVal != null ? constVal : elem);
                } else {
                    newElements.add(elem);
                }
            }
            return new Atom(atom.predicate(), newElements);
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


        private void checkGlobalInterrupt() throws InterruptedException {
            // Najpierw sprawdź globalCancelled
            if (globalCancelled.get()) {
                throw new InterruptedException();
            }

            // Jeśli wątek jest przerwany, ale globalCancelled=false,
            // to było lokalne przerwanie – wyczyść flagę i kontynuuj
            if (Thread.currentThread().isInterrupted()) {
                // Sprawdź ponownie globalCancelled (może się zmienić)
                if (globalCancelled.get()) {
                    throw new InterruptedException();
                }
                // Lokalne przerwanie – wyczyść i kontynuuj
                Thread.interrupted();
            }
        }

        private Set<Atom> checkLocalCancellation(Atom statement, InProgressContext ctx) {
            if (!ctx.cancelled) {
                return null;
            }

            Boolean known = knownStatements.get(statement);
            if (known != null) {
                return known ? Collections.emptySet() : Set.of(statement);
            }

            return Set.of(statement);
        }

        private void markTrueAndNotify(Atom statement, InProgressContext currentCtx) {
            Boolean prev = knownStatements.putIfAbsent(statement, Boolean.TRUE);
            if (prev == null) {
                notifyOtherInProgress(statement, currentCtx);
            }
        }

        void notifyOtherInProgress(Atom statement, InProgressContext currentCtx) {
            Set<InProgressContext> contexts = inProgressRegistry.get(statement);
            if (contexts == null) {
                return;
            }

            for (InProgressContext ctx : contexts) {
                if (ctx != currentCtx) {
                    ctx.cancelled = true;
                    ctx.ownerThread.interrupt();
                }
            }
        }
    }
}
