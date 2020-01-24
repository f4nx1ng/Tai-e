package sa.icfg;

import sa.callgraph.CallGraph;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.Stmt;
import soot.toolkits.graph.BriefUnitGraph;
import soot.toolkits.graph.DirectedGraph;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static sa.util.CollectionUtils.addToMapSet;

public class JimpleICFG extends AbstractICFG<SootMethod, Unit> {

    private Map<Unit, Set<Edge<Unit>>> inEdges;
    private Map<Unit, Set<Edge<Unit>>> outEdges;
    private Map<Unit, SootMethod> unitToMethod;
    private Map<SootMethod, DirectedGraph<Unit>> methodToCFG;

    @Override
    public Collection<Edge<Unit>> getInEdgesOf(Unit unit) {
        return inEdges.getOrDefault(unit, Collections.emptySet());
    }

    @Override
    public Collection<Edge<Unit>> getOutEdgesOf(Unit unit) {
        return outEdges.getOrDefault(unit, Collections.emptySet());
    }

    @Override
    public Collection<Unit> getEntriesOf(SootMethod method) {
        // TODO - consider multi-head due to unreachable code?
        return methodToCFG.get(method).getHeads();
    }

    @Override
    public Collection<Unit> getExitsOf(SootMethod method) {
        return methodToCFG.get(method).getTails();
    }

    @Override
    public Collection<Unit> getReturnSitesOf(Unit callSite) {
        return methodToCFG.get(unitToMethod.get(callSite))
                .getSuccsOf(callSite);
    }

    @Override
    public SootMethod getContainingMethodOf(Unit unit) {
        return unitToMethod.get(unit);
    }

    @Override
    public boolean isCallSite(Unit unit) {
        return ((Stmt) unit).containsInvokeExpr();
    }

    private void build(CallGraph<Unit, SootMethod> callGraph) {
        for (SootMethod method : callGraph.getReachableMethods()) {
            DirectedGraph<Unit> cfg = getCFG(method);
            // TODO - handle special cases
            methodToCFG.put(method, cfg);
            for (Unit unit : cfg) {
                unitToMethod.put(unit, method);
                // Add local edges
                for (Unit succ : cfg.getSuccsOf(unit)) {
                    Edge<Unit> local = new LocalEdge<>(unit, succ);
                    addToMapSet(outEdges, unit, local);
                    addToMapSet(inEdges, succ, local);
                }
                if (isCallSite(unit)) {
                    for (SootMethod callee : getCalleesOf(unit)) {
                        // Add call edges
                        getEntriesOf(callee).forEach(entry -> {
                            Edge<Unit> call = new CallEdge<>(unit, entry);
                            addToMapSet(outEdges, unit, call);
                            addToMapSet(inEdges, entry, call);
                        });
                        // Add return edges
                        for (Unit exit : getExitsOf(callee)) {
                            for (Unit returnSite : getReturnSitesOf(unit)) {
                                Edge<Unit> ret = new ReturnEdge<>(exit, returnSite, unit);
                                addToMapSet(outEdges, exit, ret);
                                addToMapSet(inEdges, returnSite, ret);
                            }
                        }
                    }
                }
            }
        }
    }

    private DirectedGraph<Unit> getCFG(SootMethod method) {
        DirectedGraph<Unit> cfg = methodToCFG.get(method);
        if (cfg == null) {
            // TODO - handle special cases such as native methods
            cfg = new BriefUnitGraph(method.retrieveActiveBody());
            methodToCFG.put(method, cfg);
        }
        return cfg;
    }
}