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
import java.util.Optional;
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


        ConcurrentHashMap<Atom, Boolean> knownTrue = new ConcurrentHashMap<>();
        ConcurrentHashMap<Atom, Boolean> knownFalse = new ConcurrentHashMap<>();


        Map<Predicate, List<Rule>> rulesByPredicate = buildRulesIndex(program);

        ExecutorService executor = Executors.newFixedThreadPool(numWorkerThreads);
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

            if (Thread.interrupted()) {
                throw new InterruptedException();
            }

            // Jeśli już znamy wynik globalnie – używamy go.
            Boolean cachedTrue = knownTrue.get(statement);
            if (cachedTrue != null && cachedTrue) {
                return Collections.emptySet();
            }
            Boolean cachedFalse = knownFalse.get(statement);
            if (cachedFalse != null && !cachedFalse) {
                // Globalnie niewyprowadzalne.
                return Collections.singleton(statement);
            }

            // Wykrywanie pętli rekurencyjnej – lokalnie dla zapytania.
            if (inProgress.contains(statement)) {
                // Nie udało się znaleźć dowodu w tej gałęzi.
                Set<Atom> res = new HashSet<>();
                res.add(statement);
                return res;
            }

            inProgress.add(statement);
            try {
                Predicate predicate = statement.predicate();

                // Obsługa wyroczni.
                if (oracle.isCalculatable(predicate)) {
                    boolean value = oracle.calculate(statement);
                    if (value) {
                        knownTrue.putIfAbsent(statement, Boolean.TRUE);
                        return Collections.emptySet();
                    } else {
                        // Lokalnie: kandydat na niewyprowadzalne.
                        Set<Atom> res = new HashSet<>();
                        res.add(statement);
                        return res;
                    }
                }

                // Reguły z odpowiednim predykatem w głowie.
                List<Rule> rules = rulesByPredicate.get(predicate);
                if (rules != null) {
                    for (Rule rule : rules) {

                        // Zbieramy zmienne występujące w regule (głowa + ciało).
                        List<Variable> variablesInRule = collectVariables(rule);

                        if (variablesInRule.isEmpty()) {
                            // Prosta reguła bez zmiennych – sprawdzamy tylko dopasowanie głowy.
                            if (rule.head().equals(statement)) {
                                // Sprawdź ciało.
                                Set<Atom> bodyNonDerivable =
                                        deriveBody(rule.body(), inProgress);
                                if (bodyNonDerivable.isEmpty()) {
                                    knownTrue.putIfAbsent(statement, Boolean.TRUE);
                                    return Collections.emptySet();
                                }
                            }
                        } else {
                            // Generujemy wszystkie przypisania zmiennych do stałych.
                            FunctionGenerator funcGen =
                                    new FunctionGenerator(variablesInRule, program.constants());

                            for (Object assignmentObj : funcGen) {
                                @SuppressWarnings("unchecked")
                                Map<Variable, Constant> assignment =
                                        (Map<Variable, Constant>) assignmentObj;

                                Atom instantiatedHead = applyAssignment(rule.head(), assignment);
                                if (!instantiatedHead.equals(statement)) {
                                    continue;
                                }

                                // Ciało z podstawionymi zmiennymi.
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

                // Nie znaleźliśmy żadnego wyprowadzenia dla statement.
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

        /**
         * Zbiera wszystkie zmienne występujące w regule (głowie i ciele).
         */
        private List<Variable> collectVariables(Rule rule) {
            Set<Variable> vars = new HashSet<>();
            vars.addAll(Datalog.getVariables(Collections.singletonList(rule.head())));
            for (Atom bodyAtom : rule.body()) {
                vars.addAll(Datalog.getVariables(Collections.singletonList(bodyAtom)));
            }
            return new ArrayList<>(vars);
        }

        /**
         * Zastosowanie przypisania zmiennych do elementów atomu.
         */
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
