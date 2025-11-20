package cp2025.engine;

import java.util.*;
import java.util.concurrent.*;
import cp2025.engine.Datalog.*;

public class ParallelDeriver implements AbstractDeriver {
    private final int numWorkerThreads;

    public ParallelDeriver(int numWorkerThreads) {
        this.numWorkerThreads = numWorkerThreads;
    }

    @Override
    public Map<Atom, Boolean> derive(Program program, AbstractOracle oracle)
            throws InterruptedException {

        SharedStatementCache sharedCache = new SharedStatementCache();
        ExecutorService executor = Executors.newFixedThreadPool(numWorkerThreads);
        List<Future<Map.Entry<Atom, Boolean>>> futures = new ArrayList<>();

        try {
            for (Atom query : program.queries()) {
                Future<Map.Entry<Atom, Boolean>> future = executor.submit(() -> {
                    try {
                        ParallelDeriverState state = new ParallelDeriverState(
                                program, oracle, sharedCache
                        );
                        boolean result = state.deriveStatement(query).isEmpty();
                        return new AbstractMap.SimpleEntry<>(query, result);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                });
                futures.add(future);
            }

            Map<Atom, Boolean> results = new LinkedHashMap<>();
            for (Future<Map.Entry<Atom, Boolean>> future : futures) {
                try {
                    Map.Entry<Atom, Boolean> entry = future.get();
                    results.put(entry.getKey(), entry.getValue());
                } catch (ExecutionException e) {
                    throw new RuntimeException("Error in worker thread: " + e.getCause(), e);
                }
            }

            return results;

        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    private static class SharedStatementCache {
        private final ConcurrentHashMap<Atom, Boolean> cache = new ConcurrentHashMap<>();

        Optional<Boolean> getCached(Atom atom) {
            Boolean result = cache.get(atom);
            return result == null ? Optional.empty() : Optional.of(result);
        }

        void cache(Atom atom, boolean result) {
            cache.put(atom, result);
        }
    }

    private static class ParallelDeriverState {
        private final Program program;
        private final AbstractOracle oracle;
        private final SharedStatementCache sharedCache;
        private final Set<Atom> inProgressStatements = new HashSet<>();
        private final Map<Predicate, List<Rule>> rulesByPredicate;

        ParallelDeriverState(Program program, AbstractOracle oracle,
                             SharedStatementCache sharedCache) {
            this.program = program;
            this.oracle = oracle;
            this.sharedCache = sharedCache;

            this.rulesByPredicate = new HashMap<>();
            for (Rule rule : program.rules()) {
                Predicate pred = rule.head().predicate();
                rulesByPredicate.computeIfAbsent(pred, k -> new ArrayList<>())
                        .add(rule);
            }
        }

        Set<Atom> deriveStatement(Atom statement) throws InterruptedException {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            Optional<Boolean> cached = sharedCache.getCached(statement);
            if (cached.isPresent()) {
                if (cached.get()) {
                    return Collections.emptySet();
                } else {
                    return Collections.singleton(statement);
                }
            }

            if (inProgressStatements.contains(statement)) {
                return Collections.singleton(statement);
            }

            inProgressStatements.add(statement);

            try {
                Predicate predicate = statement.predicate();
                if (oracle.isCalculatable(predicate)) {
                    boolean result = oracle.calculate(statement);
                    sharedCache.cache(statement, result);
                    if (result) {
                        return Collections.emptySet();
                    } else {
                        return Collections.singleton(statement);
                    }
                }

                List<Rule> rules = rulesByPredicate.get(predicate);
                if (rules != null) {
                    for (Rule rule : rules) {
                        // FIX: Use Set to avoid immutable list issue
                        Set<Variable> varsSet = new HashSet<>();
                        varsSet.addAll(Datalog.getVariables(Arrays.asList(rule.head())));
                        for (Atom bodyAtom : rule.body()) {
                            varsSet.addAll(Datalog.getVariables(Arrays.asList(bodyAtom)));
                        }
                        List<Variable> varsInRule = new ArrayList<>(varsSet);

                        FunctionGenerator funcGen = new FunctionGenerator(varsInRule, program.constants());

                        for (Object assignmentObj : funcGen) {
                            @SuppressWarnings("unchecked")
                            Map<Variable, Constant> assignment = (Map<Variable, Constant>) assignmentObj;

                            Atom ruleHead = applyAssignment(rule.head(), assignment);

                            if (!ruleHead.equals(statement)) {
                                continue;
                            }

                            List<Atom> unifiedBody = new ArrayList<>();
                            for (Atom bodyAtom : rule.body()) {
                                unifiedBody.add(applyAssignment(bodyAtom, assignment));
                            }

                            Set<Atom> bodyNonDerived = deriveBody(unifiedBody);
                            if (bodyNonDerived.isEmpty()) {
                                sharedCache.cache(statement, true);
                                return Collections.emptySet();
                            }
                        }
                    }
                }

                sharedCache.cache(statement, false);
                return Collections.singleton(statement);

            } finally {
                inProgressStatements.remove(statement);
            }
        }

        Set<Atom> deriveBody(List<Atom> body) throws InterruptedException {
            Set<Atom> allNonDerived = new HashSet<>();

            for (Atom atom : body) {
                Set<Atom> atomNonDerived = deriveStatement(atom);
                if (!atomNonDerived.isEmpty()) {
                    allNonDerived.addAll(atomNonDerived);
                    return allNonDerived;
                }
            }

            return Collections.emptySet();
        }

        private Atom applyAssignment(Atom atom, Map<Variable, Constant> assignment) {
            List<Element> newElements = new ArrayList<>();
            for (Element elem : atom.elements()) {
                if (elem instanceof Variable) {
                    Variable var = (Variable) elem;
                    Constant const_ = assignment.get(var);
                    if (const_ != null) {
                        newElements.add(const_);
                    } else {
                        newElements.add(elem);
                    }
                } else {
                    newElements.add(elem);
                }
            }
            return new Atom(atom.predicate(), newElements);
        }
    }
}