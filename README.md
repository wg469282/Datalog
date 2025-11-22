# Datalog Parallel Deriver

This repository contains a Java implementation of a query evaluation engine for a simplified Datalog language, including both single-threaded and parallel derivation backends.  
This project was completed as part of the concurrent programing course at the [University of Warsaw](https://www.mimuw.edu.pl/en/).
## Features

- Single-threaded `SimpleDeriver` that evaluates Datalog queries using depth-first, top-down derivation. 
- Multi-threaded `ParallelDeriver` that evaluates multiple queries in parallel using a fixed-size worker pool.  
- Shared cache of derivation results reused across queries to avoid redundant work. 
- Pluggable oracle (`AbstractOracle`) to model externally calculatable predicates.  
- Text-based parser for a small Datalog dialect (constants, rules, queries) with simple validation utilities.   

## Datalog Overview

The engine evaluates Datalog programs consisting of constants, predicates, rules, and ground queries.  
Rules define how new facts can be derived from existing ones, and queries ask whether specific ground atoms are derivable under the given rules and optional oracle.   
The semantics follow the standard notion of derivability via finite derivation trees, with support for recursion and cycles in the rule set.   

## Project Structure

- `src/main/java/cp2025/datalog/` – generated parser and related infrastructure for the Datalog input language.  
- `src/main/java/cp2025/engine/Datalog.java` – core immutable data structures for elements, predicates, atoms, rules, and programs. 
- `src/main/java/cp2025/engine/Parser.java` – convenience wrapper around the generated parser that returns validated `Program` instances. 
- `src/main/java/cp2025/engine/AbstractDeriver.java` – interface for derivation engines. 
- `src/main/java/cp2025/engine/SimpleDeriver.java` – baseline single-threaded implementation. 
- `src/main/java/cp2025/engine/ParallelDeriver.java` – parallel implementation using a shared cache and a thread pool.  
- `src/main/java/cp2025/engine/AbstractOracle.java` and `NullOracle.java` – oracle interface and a trivial implementation with no calculatable predicates. 
- `src/main/java/cp2025/engine/Main.java` – example command-line entry point. 
- `src/test/java/cp2025/` – basic tests for parsing and derivation.  
- `examples/` – sample Datalog programs and expected outputs. 

## ParallelDeriver Design

`ParallelDeriver` implements the same logical semantics as `SimpleDeriver` but distributes queries across a fixed-size pool of worker threads.
Each worker evaluates one query at a time while sharing a concurrent cache of known derivable and non-derivable statements to reduce duplicated work. 
Derivation for a single query remains top-down and depth-first, with recursion tracked via a per-query `inProgress` set to detect cycles. 

## Requirements

- Java 17 or later. 
- Maven 3.x for building and running tests.

## Building and Running

To build the project and run all tests: 

```
mvn clean test
```


 

## Using the Engine Programmatically

Typical usage consists of parsing a Datalog program, instantiating an oracle, choosing a derivation engine, and invoking `derive`. 

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

Both derivation engines return a map from query atoms to booleans indicating whether each query is derivable under the given rules and oracle. 

## Examples

Sample Datalog programs demonstrating recursion, cycles, reachability, and oracle usage are available in the `examples/` directory. 
These examples can be used to understand the input language and to compare the behavior of `SimpleDeriver` and `ParallelDeriver` on the same programs. 



