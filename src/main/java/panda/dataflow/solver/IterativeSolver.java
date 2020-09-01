/*
 * Bamboo - A Program Analysis Framework for Java
 *
 * Copyright (C) 2020 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020 Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * This software is designed for the "Static Program Analysis" course at
 * Nanjing University, and it supports a subset of Java features.
 * Bamboo is only for educational and academic purposes, and any form of
 * commercial use is disallowed.
 */

package panda.dataflow.solver;

import panda.dataflow.analysis.DataFlowAnalysis;
import soot.toolkits.graph.DirectedGraph;

import java.util.LinkedHashMap;

class IterativeSolver<Domain, Node> extends Solver<Domain, Node> {

    IterativeSolver(DataFlowAnalysis<Domain, Node> analysis,
                    DirectedGraph<Node> cfg) {
        super(analysis, cfg);
        inFlow = new LinkedHashMap<>();
        outFlow = new LinkedHashMap<>();
    }

    @Override
    protected void solveFixedPoint(DirectedGraph<Node> cfg) {
        boolean changed;
        do {
            changed = false;
            for (Node node : cfg) {
                Domain in;
                if (cfg.getHeads().contains(node)) {
                    // In-flow values of head nodes are pre-computed
                    in = inFlow.get(node);
                } else {
                    // Compute and store in-flow values for non-head nodes
                    in = cfg.getPredsOf(node)
                            .stream()
                            .map(outFlow::get)
                            .reduce(analysis.newInitialFlow(), analysis::meet);
                    inFlow.put(node, in);
                }
                Domain out = outFlow.get(node);
                changed |= analysis.transfer(node, in, out);
            }
        } while (changed);
    }
}