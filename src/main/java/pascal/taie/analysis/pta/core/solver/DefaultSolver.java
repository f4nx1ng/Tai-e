/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2020-- Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020-- Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * Tai-e is only for educational and academic purposes,
 * and any form of commercial use is disallowed.
 * Distribution of Tai-e is disallowed without the approval.
 */

package pascal.taie.analysis.pta.core.solver;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.ArrayIndex;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.element.InstanceField;
import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.cs.element.StaticField;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.ir.exp.CastExp;
import pascal.taie.ir.exp.Exp;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.InvokeStatic;
import pascal.taie.ir.exp.Literal;
import pascal.taie.ir.exp.NewExp;
import pascal.taie.ir.exp.NewMultiArray;
import pascal.taie.ir.exp.ReferenceLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.AssignLiteral;
import pascal.taie.ir.stmt.Cast;
import pascal.taie.ir.stmt.Copy;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.StmtVisitor;
import pascal.taie.ir.stmt.StoreArray;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.natives.NativeModel;
import pascal.taie.language.type.ArrayType;
import pascal.taie.language.type.NullType;
import pascal.taie.language.type.ReferenceType;
import pascal.taie.language.type.Type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static pascal.taie.language.classes.StringReps.FINALIZE;
import static pascal.taie.language.classes.StringReps.FINALIZER_REGISTER;
import static pascal.taie.util.collection.Maps.newMap;
import static pascal.taie.util.collection.Sets.newSet;

public class DefaultSolver extends Solver {

    private static final Logger logger = LogManager.getLogger(DefaultSolver.class);

    /**
     * Description for array objects created implicitly by multiarray instruction.
     */
    private static final String MULTI_ARRAY_DESC = "MultiArrayObj";

    private WorkList workList;

    private Set<JMethod> reachableMethods;

    /**
     * Set of classes that have been initialized.
     */
    private Set<JClass> initializedClasses;

    private StmtProcessor stmtProcessor;

    @Override
    public CallGraph<CSCallSite, CSMethod> getCallGraph() {
        return callGraph;
    }

    /**
     * Runs pointer analysis algorithm.
     */
    @Override
    public void solve() {
        initialize();
        doSolve();
    }

     /**
     * Initializes pointer analysis.
     */
    private void initialize() {
        onlyApp = options.getBoolean("only-app");
        callGraph = new OnFlyCallGraph(csManager);
        pointerFlowGraph = new PointerFlowGraph();
        workList = new WorkList();
        reachableMethods = newSet();
        initializedClasses = newSet();
        stmtProcessor = new StmtProcessor();
        plugin.onStart();

        // process program entries (including implicit entries)
        Context defContext = contextSelector.getDefaultContext();
        for (JMethod entry : computeEntries()) {
            // initialize class type of entry methods
            CSMethod csMethod = csManager.getCSMethod(defContext, entry);
            callGraph.addEntryMethod(csMethod);
            processNewCSMethod(csMethod);
        }
        // setup main arguments
        NativeModel nativeModel = World.getNativeModel();
        Obj args = nativeModel.getMainArgs();
        Obj argsElem = nativeModel.getMainArgsElem();
        addArrayPointsTo(defContext, args, defContext, argsElem);
        JMethod main = World.getMainMethod();
        addVarPointsTo(defContext, main.getIR().getParam(0), defContext, args);
    }

    private Collection<JMethod> computeEntries() {
        List<JMethod> entries = new ArrayList<>();
        entries.add(World.getMainMethod());
        if (options.getBoolean("implicit-entries")) {
            entries.addAll(World.getImplicitEntries());
        }
        return entries;
    }

    /**
     * Processes worklist entries until the worklist is empty.
     */
    private void doSolve() {
        while (!workList.isEmpty()) {
            while (workList.hasPointerEntries()) {
                WorkList.Entry entry = workList.pollPointerEntry();
                Pointer p = entry.pointer;
                PointsToSet pts = entry.pointsToSet;
                PointsToSet diff = propagate(p, pts);
                if (p instanceof CSVar) {
                    CSVar v = (CSVar) p;
                    if (onlyApp && !v.getVar().getMethod()
                            .getDeclaringClass().isApplication()) {
                        continue;
                    }
                    processInstanceStore(v, diff);
                    processInstanceLoad(v, diff);
                    processArrayStore(v, diff);
                    processArrayLoad(v, diff);
                    processCall(v, diff);
                    plugin.onNewPointsToSet(v, diff);
                }
            }
            while (workList.hasCallEdges()) {
                processCallEdge(workList.pollCallEdge());
            }
        }
        plugin.onFinish();
    }

    /**
     * Propagates pointsToSet to pt(pointer) and its PFG successors,
     * returns the difference set of pointsToSet and pt(pointer).
     */
    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        logger.trace("Propagate {} to {}", pointsToSet, pointer);
        final PointsToSet diff = PointsToSetFactory.make();
        pointsToSet.forEach(obj -> {
            if (pointer.getPointsToSet().addObject(obj)) {
                diff.addObject(obj);
            }
        });
        if (!diff.isEmpty()) {
            pointerFlowGraph.outEdgesOf(pointer).forEach(edge -> {
                Pointer to = edge.getTo();
                edge.getType().ifPresentOrElse(
                        type -> workList.addPointerEntry(to,
                                getAssignablePointsToSet(diff, type)),
                        () -> workList.addPointerEntry(to, diff));
            });
        }
        return diff;
    }

    /**
     * Given a points-to set pts and a type t, returns the objects of pts
     * which can be assigned to t.
     */
    private PointsToSet getAssignablePointsToSet(PointsToSet pts, Type type) {
        PointsToSet result = PointsToSetFactory.make();
        pts.objects()
                .filter(o -> typeManager.isSubtype(type, o.getObject().getType()))
                .forEach(result::addObject);
        return result;
    }

    @Override
    public void addCallEdge(Edge<CSCallSite, CSMethod> edge) {
        workList.addCallEdge(edge);
    }

    @Override
    public void addCSMethod(CSMethod csMethod) {
        processNewCSMethod(csMethod);
    }

    @Override
    public void initializeClass(JClass cls) {
        if (cls == null || initializedClasses.contains(cls)) {
            return;
        }
        // initialize super class
        JClass superclass = cls.getSuperClass();
        if (superclass != null) {
            initializeClass(superclass);
        }
        // TODO: initialize the superinterfaces which
        //  declare default methods
        JMethod clinit = cls.getClinit();
        if (clinit != null) {
            // processNewCSMethod() may trigger initialization of more
            // classes. So cls must be added before processNewCSMethod(),
            // otherwise, infinite recursion may occur.
            initializedClasses.add(cls);
            CSMethod csMethod = csManager.getCSMethod(
                    contextSelector.getDefaultContext(), clinit);
            addCSMethod(csMethod);
        }
    }

    @Override
    public void addPointsTo(Pointer pointer, PointsToSet pts) {
        workList.addPointerEntry(pointer, pts);
    }

    @Override
    public void addPFGEdge(Pointer from, Pointer to, Type type, PointerFlowEdge.Kind kind) {
        if (pointerFlowGraph.addEdge(from, to, type, kind)) {
            PointsToSet fromSet = type == null ?
                    from.getPointsToSet() :
                    getAssignablePointsToSet(from.getPointsToSet(), type);
            if (!fromSet.isEmpty()) {
                workList.addPointerEntry(to, fromSet);
            }
        }
    }

    /**
     * Processes instance stores when points-to set of the base variable changes.
     *
     * @param baseVar the base variable
     * @param pts     set of new discovered objects pointed by the variable.
     */
    private void processInstanceStore(CSVar baseVar, PointsToSet pts) {
        Context context = baseVar.getContext();
        Var var = baseVar.getVar();
        for (StoreField store : var.getStoreFields()) {
            Var fromVar = store.getRValue();
            if (isConcerned(fromVar)) {
                CSVar from = csManager.getCSVar(context, fromVar);
                pts.forEach(baseObj -> {
                    InstanceField instField = csManager.getInstanceField(
                            baseObj, store.getFieldRef().resolve());
                    addPFGEdge(from, instField, PointerFlowEdge.Kind.INSTANCE_STORE);
                });
            }
        }
    }

    /**
     * Processes instance loads when points-to set of the base variable changes.
     *
     * @param baseVar the base variable
     * @param pts     set of new discovered objects pointed by the variable.
     */
    private void processInstanceLoad(CSVar baseVar, PointsToSet pts) {
        Context context = baseVar.getContext();
        Var var = baseVar.getVar();
        for (LoadField load : var.getLoadFields()) {
            Var toVar = load.getLValue();
            JField field = load.getFieldRef().resolveNullable();
            if (isConcerned(toVar) && field != null) {
                CSVar to = csManager.getCSVar(context, toVar);
                pts.forEach(baseObj -> {
                    InstanceField instField = csManager.getInstanceField(
                            baseObj, field);
                    addPFGEdge(instField, to, PointerFlowEdge.Kind.INSTANCE_LOAD);
                });
            }
        }
    }
    
    /**
     * Processes array stores when points-to set of the array variable changes.
     *
     * @param arrayVar the array variable
     * @param pts      set of new discovered arrays pointed by the variable.
     */
    private void processArrayStore(CSVar arrayVar, PointsToSet pts) {
        Context context = arrayVar.getContext();
        Var var = arrayVar.getVar();
        for (StoreArray store : var.getStoreArrays()) {
            Var rvalue = store.getRValue();
            if (isConcerned(rvalue)) {
                CSVar from = csManager.getCSVar(context, rvalue);
                pts.forEach(array -> {
                    ArrayIndex arrayIndex = csManager.getArrayIndex(array);
                    // we need type guard for array stores as Java arrays
                    // are covariant
                    addPFGEdge(from, arrayIndex, arrayIndex.getType(),
                            PointerFlowEdge.Kind.ARRAY_STORE);
                });
            }
        }
    }

    /**
     * Processes array loads when points-to set of the array variable changes.
     *
     * @param arrayVar the array variable
     * @param pts      set of new discovered arrays pointed by the variable.
     */
    private void processArrayLoad(CSVar arrayVar, PointsToSet pts) {
        Context context = arrayVar.getContext();
        Var var = arrayVar.getVar();
        for (LoadArray load : var.getLoadArrays()) {
            Var lvalue = load.getLValue();
            if (isConcerned(lvalue)) {
                CSVar to = csManager.getCSVar(context, lvalue);
                pts.forEach(array -> {
                    ArrayIndex arrayIndex = csManager.getArrayIndex(array);
                    addPFGEdge(arrayIndex, to, PointerFlowEdge.Kind.ARRAY_LOAD);
                });
            }
        }
    }
    
    /**
     * Processes instance calls when points-to set of the receiver variable changes.
     *
     * @param recv the receiver variable
     * @param pts  set of new discovered objects pointed by the variable.
     */
    private void processCall(CSVar recv, PointsToSet pts) {
        Context context = recv.getContext();
        Var var = recv.getVar();
        for (Invoke callSite : var.getInvokes()) {
            pts.forEach(recvObj -> {
                // resolve callee
                JMethod callee = CallGraphs.resolveCallee(
                        recvObj.getObject().getType(), callSite);
                if (callee != null) {
                    // select context
                    CSCallSite csCallSite = csManager.getCSCallSite(context, callSite);
                    Context calleeContext = contextSelector.selectContext(
                            csCallSite, recvObj, callee);
                    // build call edge
                    CSMethod csCallee = csManager.getCSMethod(calleeContext, callee);
                    workList.addCallEdge(new Edge<>(CallGraphs.getCallKind(callSite),
                            csCallSite, csCallee));
                    // pass receiver object to *this* variable
                    addVarPointsTo(calleeContext, callee.getIR().getThis(),
                            recvObj);
                } else {
                    plugin.onUnresolvedCall(recvObj, context, callSite);
                }
            });
        }
    }
    
    /**
     * Processes the call edges in work list.
     */
    private void processCallEdge(Edge<CSCallSite, CSMethod> edge) {
        if (!callGraph.containsEdge(edge)) {
            callGraph.addEdge(edge);
            CSMethod csCallee = edge.getCallee();
            processNewCSMethod(csCallee);
            if (edge.getKind() != CallKind.OTHER) {
                Context callerCtx = edge.getCallSite().getContext();
                Invoke callSite = edge.getCallSite().getCallSite();
                Context calleeCtx = csCallee.getContext();
                JMethod callee = csCallee.getMethod();
                InvokeExp invokeExp = callSite.getInvokeExp();
                // pass arguments to parameters
                for (int i = 0; i < invokeExp.getArgCount(); ++i) {
                    Var arg = invokeExp.getArg(i);
                    if (isConcerned(arg)) {
                        Var param = callee.getIR().getParam(i);
                        CSVar argVar = csManager.getCSVar(callerCtx, arg);
                        CSVar paramVar = csManager.getCSVar(calleeCtx, param);
                        addPFGEdge(argVar, paramVar, PointerFlowEdge.Kind.PARAMETER_PASSING);
                    }
                }
                // pass results to LHS variable
                Var lhs = callSite.getResult();
                if (lhs != null && isConcerned(lhs)) {
                    CSVar csLHS = csManager.getCSVar(callerCtx, lhs);
                    for (Var ret : callee.getIR().getReturnVars()) {
                        if (isConcerned(ret)) {
                            CSVar csRet = csManager.getCSVar(calleeCtx, ret);
                            addPFGEdge(csRet, csLHS, PointerFlowEdge.Kind.RETURN);
                        }
                    }
                }
            }
            plugin.onNewCallEdge(edge);
        }
    }

    /**
     * Processes new reachable context-sensitive method.
     */
    private void processNewCSMethod(CSMethod csMethod) {
        if (callGraph.addReachableMethod(csMethod)) {
            JMethod method = csMethod.getMethod();
            if (onlyApp && !method.getDeclaringClass().isApplication()) {
                return;
            }
            processNewMethod(method);
            stmtProcessor.setCSMethod(csMethod);
            method.getIR()
                    .forEach(s -> s.accept(stmtProcessor));
            plugin.onNewCSMethod(csMethod);
        }
    }

    /**
     * Processes new reachable methods.
     */
    private void processNewMethod(JMethod method) {
        if (reachableMethods.add(method)) {
            plugin.onNewMethod(method);
        }
    }

    /**
     * @return if the type of given expression is concerned in pointer analysis.
     */
    private static boolean isConcerned(Exp exp) {
        Type type = exp.getType();
        return type instanceof ReferenceType && !(type instanceof NullType);
    }

    /**
     * Processes the statements in context-sensitive new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor {

        private CSMethod csMethod;

        private Context context;

        private final Map<NewMultiArray, MockObj[]> newArrays = newMap();

        private final Map<New, Invoke> registerInvokes = newMap();

        private final JMethod finalize = Objects.requireNonNull(
                hierarchy.getJREMethod(FINALIZE));

        private final MethodRef finalizeRef = finalize.getRef();

        private final MethodRef registerRef = Objects.requireNonNull(
                hierarchy.getJREMethod(FINALIZER_REGISTER))
                .getRef();

        private void setCSMethod(CSMethod csMethod) {
            this.csMethod = csMethod;
            this.context = csMethod.getContext();
        }

        @Override
        public void visit(New stmt) {
            // obtain context-sensitive heap object
            NewExp rvalue = stmt.getRValue();
            Obj obj = heapModel.getObj(stmt);
            Context heapContext = contextSelector.selectHeapContext(csMethod, obj);
            addVarPointsTo(context, stmt.getLValue(), heapContext, obj);
            if (rvalue instanceof NewMultiArray) {
                processNewMultiArray(stmt, heapContext, obj);
            }
            if (hasOverriddenFinalize(rvalue)) {
                processFinalizer(stmt);
            }
        }

        private void processNewMultiArray(
                New allocSite, Context arrayContext, Obj array) {
            NewMultiArray newMultiArray = (NewMultiArray) allocSite.getRValue();
            MockObj[] arrays = newArrays.computeIfAbsent(newMultiArray, nma -> {
                ArrayType type = nma.getType();
                MockObj[] newArrays = new MockObj[nma.getLengthCount() - 1];
                for (int i = 1; i < nma.getLengthCount(); ++i) {
                    type = (ArrayType) type.getElementType();
                    newArrays[i - 1] = new MockObj(MULTI_ARRAY_DESC,
                            allocSite, type, allocSite.getContainer());
                }
                return newArrays;
            });
            for (MockObj newArray : arrays) {
                // TODO: process the newArray by heapModel?
                Context elemContext = contextSelector
                        .selectHeapContext(csMethod, newArray);
                addArrayPointsTo(arrayContext, array, elemContext, newArray);
                array = newArray;
                arrayContext = elemContext;
            }
        }

        private boolean hasOverriddenFinalize(NewExp newExp) {
            return !finalize.equals(
                    hierarchy.dispatch(newExp.getType(), finalizeRef));
        }

        /**
         * Call Finalizer.register() at allocation sites of objects which override
         * Object.finalize() method.
         * NOTE: finalize() has been deprecated starting with Java 9, and will
         * eventually be removed.
         */
        private void processFinalizer(New stmt) {
            Invoke registerInvoke = registerInvokes.computeIfAbsent(stmt, s -> {
                InvokeStatic callSite = new InvokeStatic(registerRef,
                        Collections.singletonList(s.getLValue()));
                Invoke invoke = new Invoke(csMethod.getMethod(), callSite);
                invoke.setLineNumber(stmt.getLineNumber());
                return invoke;
            });
            processInvokeStatic(registerInvoke);
        }

        private void processInvokeStatic(Invoke callSite) {
            JMethod callee = CallGraphs.resolveCallee(null, callSite);
            if (callee != null) {
                CSCallSite csCallSite = csManager.getCSCallSite(context, callSite);
                Context calleeCtx = contextSelector.selectContext(csCallSite, callee);
                CSMethod csCallee = csManager.getCSMethod(calleeCtx, callee);
                Edge<CSCallSite, CSMethod> edge =
                        new Edge<>(CallKind.STATIC, csCallSite, csCallee);
                workList.addCallEdge(edge);
            }
        }

        @Override
        public void visit(AssignLiteral stmt) {
            Literal literal = stmt.getRValue();
            if (isConcerned(literal)) {
                Obj obj = heapModel.getConstantObj((ReferenceLiteral) literal);
                Context heapContext = contextSelector
                        .selectHeapContext(csMethod, obj);
                addVarPointsTo(context, stmt.getLValue(), heapContext, obj);
            }
        }

        @Override
        public void visit(Copy stmt) {
            Var rvalue = stmt.getRValue();
            if (isConcerned(rvalue)) {
                CSVar from = csManager.getCSVar(context, rvalue);
                CSVar to = csManager.getCSVar(context, stmt.getLValue());
                addPFGEdge(from, to, PointerFlowEdge.Kind.LOCAL_ASSIGN);
            }
        }

        @Override
        public void visit(Cast stmt) {
            CastExp cast = stmt.getRValue();
            if (isConcerned(cast.getValue())) {
                CSVar from = csManager.getCSVar(context, cast.getValue());
                CSVar to = csManager.getCSVar(context, stmt.getLValue());
                addPFGEdge(from, to, cast.getType(), PointerFlowEdge.Kind.CAST);
            }
        }

        /**
         * Processes static load.
         */
        @Override
        public void visit(LoadField stmt) {
            if (stmt.isStatic() && isConcerned(stmt.getRValue())) {
                JField field = stmt.getFieldRef().resolve();
                StaticField sfield = csManager.getStaticField(field);
                CSVar to = csManager.getCSVar(context, stmt.getLValue());
                addPFGEdge(sfield, to, PointerFlowEdge.Kind.STATIC_LOAD);
            }
        }

        /**
         * Processes static store.
         */
        @Override
        public void visit(StoreField stmt) {
            if (stmt.isStatic() && isConcerned(stmt.getRValue())) {
                JField field = stmt.getFieldRef().resolve();
                StaticField sfield = csManager.getStaticField(field);
                CSVar from = csManager.getCSVar(context, stmt.getRValue());
                addPFGEdge(from, sfield, PointerFlowEdge.Kind.STATIC_STORE);
            }
        }

        /**
         * Processes static invocation.
         */
        @Override
        public void visit(Invoke stmt) {
            if (stmt.isStatic()) {
                processInvokeStatic(stmt);
            }
        }
    }
}