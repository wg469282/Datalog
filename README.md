# Datalog Parallel Deriver

This repository contains a Java implementation of a query evaluation engine for a simplified Datalog language, including both single-threaded and parallel derivation backends. [web:28]  
The project is designed as a teaching and experimentation platform for recursive query evaluation, fixed-point semantics, and basic concurrent programming patterns in Java. [web:28]  

## Features

- Single-threaded `SimpleDeriver` that evaluates Datalog queries using depth-first, top-down derivation. [web:28]  
- Multi-threaded `ParallelDeriver` that evaluates multiple queries in parallel using a fixed-size worker pool. [web:28]  
- Shared cache of derivation results reused across queries to avoid redundant work. [web:28]  
- Pluggable oracle (`AbstractOracle`) to model externally calculatable predicates. [web:28]  
- Text-based parser for a small Datalog dialect (constants, rules, queries) with simple validation utilities. [web:28]  

## Datalog Overview

The engine evaluates Datalog programs consisting of constants, predicates, rules, and ground queries. [web:28]  
Rules define how new facts can be derived from existing ones, and queries ask whether specific ground atoms are derivable under the given rules and optional oracle. [web:28]  
The semantics follow the standard notion of derivability via finite derivation trees, with support for recursion and cycles in the rule set. [web:28]  

## Project Structure

- `src/main/java/cp2025/datalog/` – generated parser and related infrastructure for the Datalog input language. [web:28]  
- `src/main/java/cp2025/engine/Datalog.java` – core immutable data structures for elements, predicates, atoms, rules, and programs. [web:28]  
- `src/main/java/cp2025/engine/Parser.java` – convenience wrapper around the generated parser that returns validated `Program` instances. [web:28]  
- `src/main/java/cp2025/engine/AbstractDeriver.java` – interface for derivation engines. [web:28]  
- `src/main/java/cp2025/engine/SimpleDeriver.java` – baseline single-threaded implementation. [web:28]  
- `src/main/java/cp2025/engine/ParallelDeriver.java` – parallel implementation using a shared cache and a thread pool. [web:28]  
- `src/main/java/cp2025/engine/AbstractOracle.java` and `NullOracle.java` – oracle interface and a trivial implementation with no calculatable predicates. [web:28]  
- `src/main/java/cp2025/engine/Main.java` – example command-line entry point. [web:28]  
- `src/test/java/cp2025/` – basic tests for parsing and derivation. [web:28]  
- `examples/` – sample Datalog programs and expected outputs. [web:28]  

## ParallelDeriver Design

`ParallelDeriver` implements the same logical semantics as `SimpleDeriver` but distributes queries across a fixed-size pool of worker threads. [web:28]  
Each worker evaluates one query at a time while sharing a concurrent cache of known derivable and non-derivable statements to reduce duplicated work. [web:28]  
Derivation for a single query remains top-down and depth-first, with recursion tracked via a per-query `inProgress` set to detect cycles. [web:28]  

## Requirements

- Java 17 or later. [web:28]  
- Maven 3.x for building and running tests. [web:28]  

## Building and Running

To build the project and run all tests: [web:28]  

```
mvn clean test
```

To build and run the example `Main` class from the command line: [web:28]  

```
mvn clean package
mvn exec:java -Dexec.mainClass="cp2025.engine.Main"
```

You can also open the project in an IDE with Maven support (for example IntelliJ IDEA or VS Code with Java extensions) and run tests or the `Main` class directly. [web:28]  

## Using the Engine Programmatically

Typical usage consists of parsing a Datalog program, instantiating an oracle, choosing a derivation engine, and invoking `derive`. [web:28]  

```
Program program = Parser.parseFromFile("path/to/program.dl");
AbstractOracle oracle = new NullOracle();

// Single-threaded
AbstractDeriver simple = new SimpleDeriver();
Map<Atom, Boolean> simpleResults = simple.derive(program, oracle);

// Parallel (e.g. 4 worker threads)
AbstractDeriver parallel = new ParallelDeriver(4);
Map<Atom, Boolean> parallelResults = parallel.derive(program, oracle);
```

Both derivation engines return a map from query atoms to booleans indicating whether each query is derivable under the given rules and oracle. [web:28]  

## Examples

Sample Datalog programs demonstrating recursion, cycles, reachability, and oracle usage are available in the `examples/` directory. [web:28]  
These examples can be used to understand the input language and to compare the behavior of `SimpleDeriver` and `ParallelDeriver` on the same programs. [web:28]  

## License

Add the appropriate license information for your project here, for example an MIT License or a university or course-specific license. [web:28]  
