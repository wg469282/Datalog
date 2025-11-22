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

/**
 * ParallelDeriver – parallel AbstractDeriver implementation.
 *
 * Parallelism is per-query: each query is evaluated in its own task,
 * and all tasks share global caches of derivable and non-derivable
 * statements.
 */
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

        // Shared caches for all queries and worker threads.
        ConcurrentHashMap<Atom, Boolean> knownTrue = new ConcurrentHashMap<>();
        ConcurrentHashMap<Atom, Boolean> knownFalse = new ConcurrentHashMap<>();

        // Immutable index of rules by head predicate – built once.
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
                                knownFalse
                        );

                        Set<Atom> nonDerivable = state.deriveStatement(query, new HashSet<>());
                        boolean result = nonDerivable.isEmpty();

                        if (result) {
                            knownTrue.putIfAbsent(query, Boolean.TRUE);
                        } else {
                            for (Atom a : nonDerivable) {
                                knownFalse.putIfAbsent(a, Boolean.FALSE);
                            }
                        }

                        return new AbstractMap.SimpleEntry<>(query, result);
                    } catch (InterruptedException e) {
                        // Preserve interrupt status and propagate.
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
            // Ensure worker threads are terminated; no leaks.
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

    private static class ParallelDeriverState {

        private final Program program;
        private final AbstractOracle oracle;
        private final Map<Predicate, List<Rule>> rulesByPredicate;

        private final ConcurrentHashMap<Atom, Boolean> knownTrue;
        private final ConcurrentHashMap<Atom, Boolean> knownFalse;

        ParallelDeriverState(Program program,
                             AbstractOracle oracle,
                             Map<Predicate, List<Rule>> rulesByPredicate,
                             ConcurrentHashMap<Atom, Boolean> knownTrue,
                             ConcurrentHashMap<Atom, Boolean> knownFalse) {
            this.program = program;
            this.oracle = oracle;
            this.rulesByPredicate = rulesByPredicate;
            this.knownTrue = knownTrue;
            this.knownFalse = knownFalse;
        }

        Set<Atom> deriveStatement(Atom statement, Set<Atom> inProgress)
                throws InterruptedException {

            // Fast abort on thread interruption.
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            // Re-use global result if already known.
            Boolean cachedTrue = knownTrue.get(statement);
            if (cachedTrue != null && cachedTrue) {
                return Collections.emptySet();
            }
            Boolean cachedFalse = knownFalse.get(statement);
            if (cachedFalse != null && !cachedFalse) {
                return Collections.singleton(statement);
            }

            // Local cycle detection for this query.
            if (inProgress.contains(statement)) {
                Set<Atom> res = new HashSet<>();
                res.add(statement);
                return res;
            }

            inProgress.add(statement);
            try {
                Predicate predicate = statement.predicate();

                // Oracle predicates: potentially expensive, so keep interruptible.
                if (oracle.isCalculatable(predicate)) {
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                    boolean value = oracle.calculate(statement);
                    if (value) {
                        knownTrue.putIfAbsent(statement, Boolean.TRUE);
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
                        if (Thread.interrupted()) {
                            throw new InterruptedException();
                        }

                        // Re-check cache in case another thread just solved it.
                        cachedTrue = knownTrue.get(statement);
                        if (cachedTrue != null && cachedTrue) {
                            return Collections.emptySet();
                        }
                        cachedFalse = knownFalse.get(statement);
                        if (cachedFalse != null && !cachedFalse) {
                            return Collections.singleton(statement);
                        }

                        List<Variable> variablesInRule = collectVariables(rule);

                        if (variablesInRule.isEmpty()) {
                            if (rule.head().equals(statement)) {
                                Set<Atom> bodyNonDerivable =
                                        deriveBody(rule.body(), inProgress);
                                if (bodyNonDerivable.isEmpty()) {
                                    knownTrue.putIfAbsent(statement, Boolean.TRUE);
                                    return Collections.emptySet();
                                }
                            }
                        } else {
                            FunctionGenerator funcGen =
                                    new FunctionGenerator(variablesInRule, program.constants());

                            for (Object assignmentObj : funcGen) {
                                if (Thread.interrupted()) {
                                    throw new InterruptedException();
                                }

                                // Again, re-check cache inside long-running loops.
                                cachedTrue = knownTrue.get(statement);
                                if (cachedTrue != null && cachedTrue) {
                                    return Collections.emptySet();
                                }
                                cachedFalse = knownFalse.get(statement);
                                if (cachedFalse != null && !cachedFalse) {
                                    return Collections.singleton(statement);
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
                                    knownTrue.putIfAbsent(statement, Boolean.TRUE);
                                    return Collections.emptySet();
                                }
                            }
                        }
                    }
                }

                Set<Atom> res = new HashSet<>();
                res.add(statement);
                return res;

            } finally {
                inProgress.remove(statement);
            }
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
    }
}
